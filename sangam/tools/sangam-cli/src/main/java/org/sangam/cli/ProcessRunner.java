// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a platform tool (the OS keychain front ends) with a secret on standard input, never on the command line, so the
 * secret does not show in the process list. Standard error is discarded so that a tool's diagnostics cannot echo a
 * secret to the terminal. Replaced in tests.
 */
interface ProcessRunner {

    /** The tool's exit code and standard output. */
    record Result(int exit, String stdout) {
        boolean ok() {
            return exit == 0;
        }
    }

    /**
     * @param stdin text written to the tool's standard input, then closed; {@code null} for none
     * @throws IOException when the tool is not installed or cannot start
     */
    Result run(List<String> command, String stdin) throws IOException, InterruptedException;

    /** The real runner: {@link ProcessBuilder}, a bounded wait, the tool killed when it does not finish. */
    static ProcessRunner system() {
        return (command, stdin) -> {
            Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream in = p.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
            // Read on another thread so a tool that waits (for example on a locked keychain) cannot block past the timeout.
            CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> {
                try (InputStream o = p.getInputStream()) {
                    return o.readNBytes(MAX_OUTPUT);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("timed out: " + command.get(0));
            }
            try {
                return new Result(p.exitValue(), new String(out.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("no output from " + command.get(0));
            }
        };
    }

    /** Output bound: the tools answer with one short line. */
    int MAX_OUTPUT = 64 * 1024;

    long TIMEOUT_SECONDS = 30;
}
