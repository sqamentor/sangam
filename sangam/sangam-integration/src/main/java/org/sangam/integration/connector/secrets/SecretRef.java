// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A parsed secret reference {@code secret://<segment>/<segment>...} (M-075). Each segment is 1 to 128 characters of
 * {@code [A-Za-z0-9._-]} and is never {@code .} or {@code ..}; at most 16 segments. Every provider maps the segments to
 * its own name rules; a reference that does not parse is "not found" for every provider (never a path or URL
 * injection).
 */
public record SecretRef(List<String> segments) {

    public static final String PREFIX = "secret://";
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final int MAX_SEGMENTS = 16;

    public SecretRef {
        segments = List.copyOf(segments);
    }

    public static Optional<SecretRef> parse(String reference) {
        if (reference == null || !reference.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = reference.substring(PREFIX.length()).split("/", -1);
        if (parts.length == 0 || parts.length > MAX_SEGMENTS) {
            return Optional.empty();
        }
        for (String p : parts) {
            if (!SEGMENT.matcher(p).matches() || p.equals(".") || p.equals("..")) {
                return Optional.empty();
            }
        }
        return Optional.of(new SecretRef(List.of(parts)));
    }

    /** The segments joined with {@code /}. */
    public String path() {
        return String.join("/", segments);
    }

    /** The segments joined with {@code separator}, when every segment matches {@code allowed}; else empty. */
    public Optional<String> joined(String separator, Pattern allowed) {
        for (String s : segments) {
            if (!allowed.matcher(s).matches()) {
                return Optional.empty();
            }
        }
        return Optional.of(String.join(separator, segments));
    }

    public String last() {
        return segments.get(segments.size() - 1);
    }

    /** All segments but the last one, joined with {@code /}; empty for a single segment. */
    public String parent() {
        return segments.size() == 1 ? "" : String.join("/", segments.subList(0, segments.size() - 1));
    }
}
