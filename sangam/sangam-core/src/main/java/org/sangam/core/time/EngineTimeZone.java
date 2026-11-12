// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.time;

import java.lang.management.ManagementFactory;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * <b>The engine JVM runs in UTC (SL-53, owner decision 2026-11-09 "b", confirmed 2026-11-10 as strict UTC).</b>
 *
 * <p>Operaton stores {@code java.util.Date} values in zone-less {@code TIMESTAMP} columns as wall time of the JVM
 * default zone, and writes the start it inserts into a cycle ({@code R/PT1H} becomes {@code R/<local start>/PT1H}) as
 * local time of that zone. In a zone with daylight saving time the repeated hour of an autumn fold is ambiguous: a due
 * date stored as 02:00 reads back one hour early, the DST timer guard sees an interval that already fired and drops the
 * firing (E8 under Europe/Berlin: 6 fires expected, 5 got, 3 misplaced). In UTC every wall time is unambiguous.
 *
 * <p>So every JVM that runs a Sangam engine (the server, an embedded engine in a host application, a test JVM) must
 * have UTC as its default time zone. {@link #requireUtcJvm()} refuses anything else at start-up, including a zone
 * the JVM did not know or could not read; the zone is never overridden silently and there is no switch to turn the
 * check off. A business zone never comes from the JVM: timers use {@code sangam.engine.timer-zone}
 * ({@code SangamEngineSettings.timerZone}, default UTC), business dates use {@link BusinessClock}.
 */
public final class EngineTimeZone {

    /** The engine zone: Operaton's stored wall times and every date-time literal written without an offset. */
    public static final ZoneId ZONE = ZoneOffset.UTC;

    /** The JVM option that sets it. */
    public static final String JVM_OPTION = "-Duser.timezone=UTC";

    /** The JDK's own fallback zone ids: "GMT", or a custom "GMT+hh:mm" / "GMT-hh:mm" built from the current offset. */
    private static final Pattern JDK_FALLBACK_ID = Pattern.compile("GMT(?:[+-]\\d{1,2}(?::?\\d{2})?)?");

    private EngineTimeZone() {
    }

    /** True if the rules of {@code zone} are UTC: offset 0, no daylight saving time, no offset change ever. */
    public static boolean isUtc(ZoneId zone) {
        return zone != null && zone.normalized().equals(ZoneOffset.UTC);
    }

    /**
     * Refuses to go on unless the JVM default time zone is a known zone with UTC rules; returns it. Called by the
     * engine bootstrap ({@code SangamEngineConfigurer}, {@code SangamEngineComponents}) before the first database
     * connection, and by the server before any bean.
     *
     * <p>An unknown or unreadable zone is refused, never taken as UTC: for an unknown {@code -Duser.timezone} value, or
     * a platform zone it cannot read, the JDK silently falls back to a "GMT" or "GMT+hh:mm" zone built from the current
     * offset, which on a UTC host would pass the rules check ({@link #unknownOrUnreadable}).
     *
     * @throws IllegalStateException naming the zone, why it is refused, {@value #JVM_OPTION} and
     *                               {@code sangam.engine.timer-zone}
     */
    public static ZoneId requireUtcJvm() {
        TimeZone jvm = TimeZone.getDefault();
        String why = unknownOrUnreadable(jvm.getID(), requestedJvmZone(jvmArguments()));
        ZoneId zone = why == null ? jvm.toZoneId() : null;
        if (why == null && !isUtc(zone)) {
            why = "the JVM default time zone is '" + jvm.getID() + "', not UTC (offset 0, no daylight saving time)";
        }
        if (why != null) {
            throw new IllegalStateException("SL-53: " + why + ". The Sangam engine refuses to start: Operaton stores "
                    + "timestamps as JVM-local wall time, and outside UTC some wall times are ambiguous (at an autumn "
                    + "fold a timer firing is lost). Start every engine JVM, an embedding host included, with "
                    + JVM_OPTION + " (for example JAVA_TOOL_OPTIONS=\"" + JVM_OPTION + "\"). Set the business zone of "
                    + "timers with sangam.engine.timer-zone (for example Asia/Kolkata); business dates take the tenant "
                    + "zone from the tenant profile (BusinessClock).");
        }
        return zone;
    }

    /**
     * Why the JVM zone cannot be trusted at all, or null when it is a known zone.
     *
     * @param jvmZoneId the id of {@code TimeZone.getDefault()}
     * @param requested the zone the JVM was started with ({@link #requestedJvmZone}), or null when none was requested
     *                  (the JDK then maps the platform zone)
     */
    static String unknownOrUnreadable(String jvmZoneId, String requested) {
        if (requested != null) {
            try {
                ZoneId.of(requested, ZoneId.SHORT_IDS);
                return null;
            } catch (DateTimeException e) {
                return "the requested JVM time zone '" + requested + "' is unknown (the JVM fell back to '" + jvmZoneId
                        + "')";
            }
        }
        if (JDK_FALLBACK_ID.matcher(jvmZoneId).matches()) {
            return "the JVM could not read the platform time zone and fell back to '" + jvmZoneId + "'";
        }
        return null;
    }

    /**
     * The zone the JVM was started with: the value of the last {@code -Duser.timezone=} option among {@code arguments}
     * (the JVM's input arguments, {@code JAVA_TOOL_OPTIONS} included), or null when there is none or it is empty.
     */
    static String requestedJvmZone(List<String> arguments) {
        String requested = null;
        for (String a : arguments) {
            if (a.startsWith("-Duser.timezone=")) {
                String v = a.substring("-Duser.timezone=".length()).trim();
                requested = v.isEmpty() ? null : v;
            }
        }
        return requested;
    }

    private static List<String> jvmArguments() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (RuntimeException | LinkageError e) {
            return List.of(); // no management support: the platform-zone check still applies
        }
    }
}
