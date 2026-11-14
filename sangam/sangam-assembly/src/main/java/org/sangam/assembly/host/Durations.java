// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Duration parsing for {@link SangamProperties#getDuration}: ISO-8601 or Spring's simple style. */
final class Durations {

    /** Spring's simple style: an optional sign, digits, an optional unit (none = milliseconds). */
    private static final Pattern SIMPLE = Pattern.compile("^([+-]?\\d+)([a-zA-Z]{0,2})$");

    private Durations() {
    }

    /** The duration {@code text} denotes, or {@code null} when it is in neither style. */
    static Duration parse(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        Matcher m = SIMPLE.matcher(t);
        if (m.matches()) {
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            return switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "ns" -> Duration.ofNanos(n);
                case "us" -> Duration.ofNanos(Math.multiplyExact(n, 1000L));
                case "", "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> null;
            };
        }
        try {
            return Duration.parse(t.toUpperCase(Locale.ROOT));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
