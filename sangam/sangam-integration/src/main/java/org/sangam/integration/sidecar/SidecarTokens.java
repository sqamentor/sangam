// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Token rules shared by both sides of the sidecar authentication (owner decision 153). A token is read from a secret
 * file (a mounted secret), never from a URL, a command line or a hard-coded value, and never appears in a log, an error
 * message or a report. A token is at least {@value #MIN_LENGTH} visible ASCII characters (for example 32 random bytes in
 * base64url, which is 43 characters). Error texts here name the file and the line, never the content.
 */
public final class SidecarTokens {

    /** Shortest accepted token. */
    public static final int MIN_LENGTH = 32;
    /** Longest accepted token (an HTTP header must stay small). */
    public static final int MAX_LENGTH = 512;
    /** Largest token file read. */
    static final long MAX_FILE_BYTES = 64 * 1024;

    private SidecarTokens() {
    }

    /** {@code null} when the token is acceptable, otherwise why not (without the token). */
    public static String problem(String token) {
        if (token == null || token.isEmpty()) {
            return "empty";
        }
        if (token.length() < MIN_LENGTH) {
            return "shorter than " + MIN_LENGTH + " characters";
        }
        if (token.length() > MAX_LENGTH) {
            return "longer than " + MAX_LENGTH + " characters";
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                return "not visible ASCII";
            }
        }
        return null;
    }

    public static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** The content of a token file and the change stamp it was read at. */
    record Snapshot(FileTime modified, long size, List<String> tokens, String problem) {
    }

    /**
     * The problem text of a snapshot is logged as the sidecar's not-ready reason, so it never names the token file
     * (CodeQL java/sensitive-log): the operator knows the file from the {@code *_TOKEN_FILE} setting.
     *
     * <p>Reads the non-blank, non-comment ({@code #}) lines of {@code file}. Any invalid line makes the whole file
     * unusable (fail closed: a half-valid file is an operator error, not a smaller token set).
     */
    static Snapshot read(Path file) {
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                return new Snapshot(modified, size, List.of(), "the token file is larger than "
                        + MAX_FILE_BYTES + " bytes");
            }
            List<String> tokens = new ArrayList<>();
            int n = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                n++;
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String p = problem(t);
                if (p != null) {
                    return new Snapshot(modified, size, List.of(), "line " + n + " of the token file is not a usable token (" + p + ")");
                }
                tokens.add(t);
            }
            if (tokens.isEmpty()) {
                return new Snapshot(modified, size, List.of(), "the token file holds no token");
            }
            return new Snapshot(modified, size, List.copyOf(tokens), null);
        } catch (IOException | RuntimeException e) {
            return new Snapshot(null, -1, List.of(), "the token file cannot be read ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    /** True when the file changed since {@code s} was read (or {@code s} is null). */
    static boolean changed(Path file, Snapshot s) {
        if (s == null || s.modified() == null) {
            return true;
        }
        try {
            return !Files.getLastModifiedTime(file).equals(s.modified()) || Files.size(file) != s.size();
        } catch (IOException e) {
            return true;
        }
    }
}
