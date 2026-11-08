// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.util.Map;
import java.util.TimeZone;

/**
 * Replaces a JVM default time zone that is a legacy IANA alias with its canonical name, before any JDBC connection.
 *
 * <p>The PostgreSQL JDBC driver sends the JVM zone as the session {@code TimeZone}; database images built on current
 * Debian (e.g. {@code postgres:17}) ship tzdata without the legacy "backward" links, so the server rejects the
 * connection ({@code FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"}). The JDK on Windows maps
 * "India Standard Time" to {@code Asia/Calcutta}. The table is exactly the 17 ids of the JDK's Windows mapping
 * ({@code lib/tzmappings}, JDK 21.0.10) that are missing from the {@code postgres:17} image, each with its target in
 * IANA {@code backward}. The offset rules are identical, so business dates ({@code LocalDate.now()}) do not change.
 */
public final class LegacyTimeZones {

    static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("Africa/Asmera", "Africa/Asmara"),
            Map.entry("America/Buenos_Aires", "America/Argentina/Buenos_Aires"),
            Map.entry("America/Godthab", "America/Nuuk"),
            Map.entry("America/Indianapolis", "America/Indiana/Indianapolis"),
            Map.entry("Asia/Calcutta", "Asia/Kolkata"),
            Map.entry("Asia/Katmandu", "Asia/Kathmandu"),
            Map.entry("Asia/Rangoon", "Asia/Yangon"),
            Map.entry("Asia/Saigon", "Asia/Ho_Chi_Minh"),
            Map.entry("Atlantic/Faeroe", "Atlantic/Faroe"),
            Map.entry("CST6CDT", "America/Chicago"),
            Map.entry("EST5EDT", "America/New_York"),
            Map.entry("Europe/Kiev", "Europe/Kyiv"),
            Map.entry("MST7MDT", "America/Denver"),
            Map.entry("Pacific/Enderbury", "Pacific/Kanton"),
            Map.entry("Pacific/Ponape", "Pacific/Pohnpei"),
            Map.entry("Pacific/Truk", "Pacific/Chuuk"),
            Map.entry("PST8PDT", "America/Los_Angeles"));

    private LegacyTimeZones() {
    }

    /** The canonical id for {@code id}, or {@code id} itself when it is not a known legacy alias. */
    public static String canonical(String id) {
        return CANONICAL.getOrDefault(id, id);
    }

    /** Sets the JVM default zone to the canonical id when it is a legacy alias; returns the (possibly new) id. */
    public static synchronized String normalizeJvmDefault() {
        String id = TimeZone.getDefault().getID();
        String c = canonical(id);
        if (!c.equals(id)) {
            TimeZone.setDefault(TimeZone.getTimeZone(c));
        }
        return c;
    }
}
