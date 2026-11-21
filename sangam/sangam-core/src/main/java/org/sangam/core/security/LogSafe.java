// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

/**
 * The single boundary for caller-supplied text that goes into a log line (CWE-117, CodeQL java/log-injection). A
 * value a client, a broker message or a remote system chose (tenant id from a path, request URI, message id, channel
 * key, an exception message) is passed through {@link #text(Object)} before it reaches a logger, so it cannot start
 * a new, forged log line or carry terminal control sequences.
 *
 * <p>Line breaks ({@code CR}, {@code LF}) become {@code _}; every other ISO control character, and the Unicode line
 * and paragraph separators, become {@code ?}. Nothing else changes, so ordinary ids stay greppable. {@code null}
 * stays the text {@code "null"}, as SLF4J would print it.
 */
public final class LogSafe {

    private LogSafe() {
    }

    /** {@code value} as one log-safe line. */
    public static String text(Object value) {
        String s = String.valueOf(value);
        // The line-break replacement is the one CodeQL's log-injection model recognises as a sanitizer.
        String oneLine = s.replaceAll("[\r\n]", "_");
        StringBuilder out = null;
        for (int i = 0; i < oneLine.length(); i++) {
            char ch = oneLine.charAt(i);
            if (Character.isISOControl(ch) || ch == ' ' || ch == ' ') {
                if (out == null) {
                    out = new StringBuilder(oneLine.length()).append(oneLine, 0, i);
                }
                out.append('?');
            } else if (out != null) {
                out.append(ch);
            }
        }
        return out == null ? oneLine : out.toString();
    }
}
