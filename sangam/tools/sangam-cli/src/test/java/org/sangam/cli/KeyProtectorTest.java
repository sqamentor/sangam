// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** M-093: the platform keychain front ends never pass the key on a command line and verify every store. */
class KeyProtectorTest {

    static final String ACCOUNT = "0123456789abcdef0123456789abcdef";
    static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < KEY.length; i++) {
            KEY[i] = (byte) (i * 7 + 3);
        }
    }

    static final String B64 = Base64.getEncoder().encodeToString(KEY);

    /** Records every call and plays a tiny keychain. */
    static final class FakeRunner implements ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<String> inputs = new ArrayList<>();
        final Map<String, String> kept = new HashMap<>();
        boolean missing;
        boolean dropWrites;

        @Override
        public Result run(List<String> command, String stdin) throws IOException {
            if (missing) {
                throw new IOException("not installed");
            }
            commands.add(command);
            inputs.add(stdin);
            String all = String.join(" ", command);
            if (command.get(0).endsWith("security")) {
                if (command.contains("-i")) {
                    if (!dropWrites) {
                        kept.put("mac", stdin.split(" -w ")[1].strip());
                    }
                    return new Result(0, "");
                }
                if (all.contains("find-generic-password")) {
                    return kept.containsKey("mac") ? new Result(0, kept.get("mac") + "\n") : new Result(44, "");
                }
                kept.remove("mac");
                return new Result(0, "");
            }
            if (command.get(0).equals("secret-tool")) {
                switch (command.get(1)) {
                    case "store" -> {
                        if (!dropWrites) {
                            kept.put("ss", stdin);
                        }
                        return new Result(0, "");
                    }
                    case "lookup" -> {
                        return kept.containsKey("ss") ? new Result(0, kept.get("ss")) : new Result(1, "");
                    }
                    default -> {
                        kept.remove("ss");
                        return new Result(0, "");
                    }
                }
            }
            // powershell.exe: a reversible stand-in for DPAPI
            String s = command.get(4);
            if (s.contains("::Protect(")) {
                return new Result(0, Base64.getEncoder().encodeToString(("P:" + stdin).getBytes()));
            }
            String blob = new String(Base64.getDecoder().decode(stdin.strip()));
            return blob.startsWith("P:") ? new Result(0, blob.substring(2)) : new Result(1, "");
        }

        void assertKeyNeverOnACommandLine() {
            for (List<String> c : commands) {
                assertFalse(String.join(" ", c).contains(B64), "key on a command line: " + c);
            }
        }
    }

    @Test
    void osSelection() {
        FakeRunner r = new FakeRunner();
        assertInstanceOf(KeyProtector.MacKeychain.class, KeyProtector.forOs("Mac OS X", r));
        assertInstanceOf(KeyProtector.WindowsDpapi.class, KeyProtector.forOs("Windows 11", r));
        assertInstanceOf(KeyProtector.SecretService.class, KeyProtector.forOs("Linux", r));
        assertNull(KeyProtector.forOs("SunOS", r));
        assertNull(KeyProtector.forOs(null, r));
    }

    @Test
    void macKeychainUsesStdinAndVerifies() throws Exception {
        FakeRunner r = new FakeRunner();
        KeyProtector p = new KeyProtector.MacKeychain(r);
        String handle = p.protect(ACCOUNT, KEY);
        assertArrayEquals(KEY, p.recover(ACCOUNT, handle));
        assertEquals(List.of("/usr/bin/security", "-i"), r.commands.get(0));
        assertTrue(r.inputs.get(0).startsWith("add-generic-password -U -s org.sangam.cli -a " + ACCOUNT + " -w "));
        r.assertKeyNeverOnACommandLine();
        p.forget(ACCOUNT);
        assertNull(p.recover(ACCOUNT, handle));
    }

    @Test
    void secretServiceUsesStdinAndVerifies() throws Exception {
        FakeRunner r = new FakeRunner();
        KeyProtector p = new KeyProtector.SecretService(r);
        assertArrayEquals(KEY, p.recover(ACCOUNT, p.protect(ACCOUNT, KEY)));
        assertEquals(B64, r.inputs.get(0));
        r.assertKeyNeverOnACommandLine();
    }

    @Test
    void windowsDpapiKeepsOnlyTheProtectedBlobAndBindsTheAccount() throws Exception {
        FakeRunner r = new FakeRunner();
        KeyProtector p = new KeyProtector.WindowsDpapi(r);
        String handle = p.protect(ACCOUNT, KEY);
        assertNotEquals(B64, handle);
        assertArrayEquals(KEY, p.recover(ACCOUNT, handle));
        assertTrue(r.commands.get(0).get(4).contains("UTF8.GetBytes('" + ACCOUNT + "')"), "account as DPAPI entropy");
        assertTrue(r.commands.get(0).get(4).contains("CurrentUser"));
        r.assertKeyNeverOnACommandLine();
        assertNull(p.recover(ACCOUNT, Base64.getEncoder().encodeToString("junk".getBytes())), "a foreign blob is a lost key");
    }

    @Test
    void aStoreThatDoesNotKeepTheKeyIsRefused() {
        FakeRunner r = new FakeRunner();
        r.dropWrites = true;
        assertThrows(IOException.class, () -> new KeyProtector.MacKeychain(r).protect(ACCOUNT, KEY));
        assertThrows(IOException.class, () -> new KeyProtector.SecretService(r).protect(ACCOUNT, KEY));
    }

    @Test
    void aMissingToolIsAnIoFailure() {
        FakeRunner r = new FakeRunner();
        r.missing = true;
        assertThrows(IOException.class, () -> new KeyProtector.SecretService(r).protect(ACCOUNT, KEY));
    }

    @Test
    void accountsMustBeHex() {
        KeyProtector p = new KeyProtector.MacKeychain(new FakeRunner());
        assertThrows(IllegalArgumentException.class, () -> p.protect("a b; rm", KEY));
        assertThrows(IllegalArgumentException.class, () -> p.recover("x'y", "k"));
    }
}
