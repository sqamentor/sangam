// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keeps the CLI's 256-bit data key in the operating system's own secret store, through the tool the platform already
 * ships, with no native library: macOS {@code security} (login keychain), Linux {@code secret-tool} (Secret Service,
 * GNOME Keyring or KWallet) and Windows {@code powershell.exe} with the Data Protection API (current user). The tokens
 * themselves are encrypted with that key in the credentials file ({@link TokenStore}), so they never sit in a file in
 * clear text and their size does not matter to the keychain.
 *
 * <p>Every store is verified by reading the key back; a store that cannot be verified counts as unavailable, and the
 * caller falls back to the owner-only file.
 */
interface KeyProtector {

    String SERVICE = "org.sangam.cli";

    /** Account names are hex digests, so they are safe in every tool's argument syntax. */
    Pattern ACCOUNT = Pattern.compile("[0-9a-f]{16,64}");

    /** Shown in the credentials file and on stderr, never secret. */
    String name();

    /**
     * Stores the key for the account and returns a handle to keep in the credentials file (not secret: a marker for a
     * keychain entry, or the DPAPI-protected blob).
     *
     * @throws IOException when the platform store is missing, locked or refuses the key
     */
    String protect(String account, byte[] key) throws IOException, InterruptedException;

    /** The key, or {@code null} when the platform store no longer has it. */
    byte[] recover(String account, String handle) throws IOException, InterruptedException;

    /** Removes the key; a missing entry is not an error. */
    void forget(String account) throws IOException, InterruptedException;

    /** The protector of this operating system, or {@code null} when there is none. */
    static KeyProtector forOs(String osName, ProcessRunner runner) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return new MacKeychain(runner);
        }
        if (os.startsWith("windows")) {
            return new WindowsDpapi(runner);
        }
        if (os.contains("linux") || os.contains("bsd")) {
            return new SecretService(runner);
        }
        return null;
    }

    static void checkAccount(String account) {
        if (account == null || !ACCOUNT.matcher(account).matches()) {
            throw new IllegalArgumentException("account must be a hex digest");
        }
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    static byte[] unb64(String s) throws IOException {
        try {
            return Base64.getDecoder().decode(s.strip());
        } catch (IllegalArgumentException e) {
            throw new IOException("the platform store returned a malformed key");
        }
    }

    /** Stores, then reads back and compares, so a silent failure of the tool cannot lose the key. */
    static String verified(KeyProtector p, String account, byte[] key, String handle) throws IOException, InterruptedException {
        byte[] back = p.recover(account, handle);
        if (back == null || !java.security.MessageDigest.isEqual(back, key)) {
            throw new IOException(p.name() + " did not keep the key");
        }
        return handle;
    }

    /** macOS login keychain through {@code /usr/bin/security}. */
    final class MacKeychain implements KeyProtector {
        private static final String HANDLE = "keychain";
        private final ProcessRunner run;

        MacKeychain(ProcessRunner run) {
            this.run = run;
        }

        @Override
        public String name() {
            return "macos-keychain";
        }

        @Override
        public String protect(String account, byte[] key) throws IOException, InterruptedException {
            checkAccount(account);
            // Interactive mode reads the command from stdin, so the key is never an argument of a visible process.
            // Base64, the service and a hex account contain no white space or quotes, so no quoting is needed.
            run.run(List.of("/usr/bin/security", "-i"),
                    "add-generic-password -U -s " + SERVICE + " -a " + account + " -w " + b64(key) + "\n");
            return verified(this, account, key, HANDLE);
        }

        @Override
        public byte[] recover(String account, String handle) throws IOException, InterruptedException {
            checkAccount(account);
            ProcessRunner.Result r = run.run(List.of("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", account, "-w"), null);
            return r.ok() ? unb64(r.stdout()) : null;
        }

        @Override
        public void forget(String account) throws IOException, InterruptedException {
            checkAccount(account);
            run.run(List.of("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", account), null);
        }
    }

    /** Freedesktop Secret Service through {@code secret-tool} (libsecret); it reads the secret from stdin. */
    final class SecretService implements KeyProtector {
        private static final String HANDLE = "secret-service";
        private final ProcessRunner run;

        SecretService(ProcessRunner run) {
            this.run = run;
        }

        @Override
        public String name() {
            return "secret-service";
        }

        @Override
        public String protect(String account, byte[] key) throws IOException, InterruptedException {
            checkAccount(account);
            ProcessRunner.Result r = run.run(List.of("secret-tool", "store", "--label=Sangam CLI", "service", SERVICE, "account", account), b64(key));
            if (!r.ok()) {
                throw new IOException("secret-tool refused the key (exit " + r.exit() + ")");
            }
            return verified(this, account, key, HANDLE);
        }

        @Override
        public byte[] recover(String account, String handle) throws IOException, InterruptedException {
            checkAccount(account);
            ProcessRunner.Result r = run.run(List.of("secret-tool", "lookup", "service", SERVICE, "account", account), null);
            return r.ok() && !r.stdout().isBlank() ? unb64(r.stdout()) : null;
        }

        @Override
        public void forget(String account) throws IOException, InterruptedException {
            checkAccount(account);
            run.run(List.of("secret-tool", "clear", "service", SERVICE, "account", account), null);
        }
    }

    /**
     * Windows Data Protection API (current user, the store under Credential Manager) through the built-in Windows
     * PowerShell 5.1 and .NET Framework {@code ProtectedData}. The protected blob is the handle; only the same Windows
     * user on the same machine can unprotect it. The account is the DPAPI entropy, so a blob cannot move between
     * accounts.
     */
    final class WindowsDpapi implements KeyProtector {
        private final ProcessRunner run;

        WindowsDpapi(ProcessRunner run) {
            this.run = run;
        }

        @Override
        public String name() {
            return "windows-dpapi";
        }

        static String script(String operation, String account) {
            return "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; "
                    + "$d=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim()); "
                    + "$e=[Text.Encoding]::UTF8.GetBytes('" + account + "'); "
                    + "$r=[Security.Cryptography.ProtectedData]::" + operation + "($d,$e,[Security.Cryptography.DataProtectionScope]::CurrentUser); "
                    + "[Console]::Out.Write([Convert]::ToBase64String($r))";
        }

        private byte[] call(String operation, String account, String input) throws IOException, InterruptedException {
            checkAccount(account);
            ProcessRunner.Result r = run.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script(operation, account)), input);
            if (!r.ok() || r.stdout().isBlank()) {
                throw new IOException("the Data Protection API refused (exit " + r.exit() + ")");
            }
            return unb64(r.stdout());
        }

        @Override
        public String protect(String account, byte[] key) throws IOException, InterruptedException {
            return verified(this, account, key, b64(call("Protect", account, b64(key))));
        }

        @Override
        public byte[] recover(String account, String handle) throws IOException, InterruptedException {
            if (handle == null || handle.isBlank()) {
                return null;
            }
            try {
                return call("Unprotect", account, handle);
            } catch (IOException e) {
                return null; // another user, another machine, or a reset profile: the key is gone
            }
        }

        @Override
        public void forget(String account) {
            checkAccount(account);
            // Nothing outside the credentials file: deleting the blob forgets the key.
        }
    }
}
