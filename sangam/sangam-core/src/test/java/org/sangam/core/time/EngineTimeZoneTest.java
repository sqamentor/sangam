// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** SL-53: every engine JVM runs in UTC; anything else, an unknown or unreadable zone included, is refused. */
class EngineTimeZoneTest {

    @Test
    void theBuildRunsItsTestJvmsInUtc() {
        // root pom: surefire and failsafe pass -Duser.timezone=UTC to every test JVM
        assertTrue(EngineTimeZone.isUtc(TimeZone.getDefault().toZoneId()), "test JVM zone: " + TimeZone.getDefault().getID());
        assertEquals("UTC", EngineTimeZone.requestedJvmZone(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        assertEquals(ZoneOffset.UTC, EngineTimeZone.ZONE);
    }

    @Test
    void acceptsExactlyTheZonesWithUtcRules() {
        for (String id : List.of("UTC", "GMT", "Etc/UTC", "Etc/GMT", "Etc/Universal", "Zulu", "Z", "+00:00")) {
            assertTrue(EngineTimeZone.isUtc(ZoneId.of(id)), id);
        }
        // DST, a non-zero offset, offset 0 today with an older transition (LMT), and Asia/Kolkata: IST has no DST,
        // but the rule is strict UTC (owner 2026-11-10); an IST host runs its JVM in UTC and sets the timer zone
        for (String id : List.of("Europe/Berlin", "Asia/Kolkata", "Asia/Calcutta", "Europe/London", "Africa/Abidjan",
                "America/New_York", "+05:30")) {
            assertFalse(EngineTimeZone.isUtc(ZoneId.of(id)), id);
        }
        assertFalse(EngineTimeZone.isUtc(null));
    }

    @Test
    void anUnknownOrUnreadableZoneIsRefusedNeverTakenAsUtc() {
        // a typo in -Duser.timezone: the JDK silently falls back to a GMT-offset zone (plain "GMT" on a UTC host)
        assertTrue(EngineTimeZone.unknownOrUnreadable("GMT", "UTX").contains("'UTX' is unknown"));
        assertTrue(EngineTimeZone.unknownOrUnreadable("GMT+05:30", "Asia/Kolkatta").contains("'Asia/Kolkatta' is unknown"));
        assertTrue(EngineTimeZone.unknownOrUnreadable("GMT", "utc").contains("is unknown"), "zone ids are case-sensitive");
        // no zone requested and the platform zone could not be read: the JDK's own fallback ids
        for (String fallback : List.of("GMT", "GMT+05:30", "GMT-08:00")) {
            assertTrue(EngineTimeZone.unknownOrUnreadable(fallback, null).contains("could not read the platform time zone"),
                    fallback);
        }
        // known zones, explicitly requested (GMT included) or mapped from the platform: the UTC rule decides
        assertNull(EngineTimeZone.unknownOrUnreadable("UTC", "UTC"));
        assertNull(EngineTimeZone.unknownOrUnreadable("GMT", "GMT"));
        assertNull(EngineTimeZone.unknownOrUnreadable("Asia/Kolkata", "IST"));
        assertNull(EngineTimeZone.unknownOrUnreadable("Etc/UTC", null));
        assertNull(EngineTimeZone.unknownOrUnreadable("Asia/Calcutta", null));
    }

    @Test
    void theRequestedZoneIsTheLastUserTimezoneOption() {
        assertEquals("UTC", EngineTimeZone.requestedJvmZone(List.of("-Xmx1g", "-Duser.timezone=Asia/Kolkata",
                "-Dfile.encoding=UTF-8", "-Duser.timezone=UTC")));
        assertNull(EngineTimeZone.requestedJvmZone(List.of("-Xmx1g")));
        assertNull(EngineTimeZone.requestedJvmZone(List.of("-Duser.timezone=")));
    }

    /** End to end in fresh JVMs: the zone the JVM really starts with, and what the guard does with it. */
    @Test
    void freshJvmsOutsideUtcOrWithAnUnknownZoneAreRefusedAndAUtcJvmIsAccepted() throws Exception {
        String unknown = probe("Asia/Kolkatta");
        assertTrue(unknown.startsWith("REFUSED SL-53: the requested JVM time zone 'Asia/Kolkatta' is unknown"), unknown);
        for (String zone : List.of("Europe/Berlin", "Asia/Kolkata")) {
            String refused = probe(zone);
            assertTrue(refused.startsWith("REFUSED SL-53: the JVM default time zone is '" + zone + "', not UTC"), refused);
            assertTrue(refused.contains("-Duser.timezone=UTC") && refused.contains("sangam.engine.timer-zone"), refused);
        }
        assertEquals("ACCEPTED UTC", probe("UTC"));
    }

    private static String probe(String zone) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.timezone=" + zone, "-cp", System.getProperty("java.class.path"), Probe.class.getName());
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("JDK_JAVA_OPTIONS");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "probe JVM ended");
        return out;
    }

    /** Main class of the probe JVM. */
    static final class Probe {
        public static void main(String[] args) {
            try {
                System.out.println("ACCEPTED " + EngineTimeZone.requireUtcJvm().getId());
            } catch (IllegalStateException e) {
                System.out.println("REFUSED " + e.getMessage());
            }
        }
    }

    @Test
    void refusesANonUtcJvmZoneNamingTheOptionAndNeverChangesTheZone() {
        TimeZone saved = TimeZone.getDefault();
        try {
            for (String id : List.of("Europe/Berlin", "Asia/Kolkata", "Asia/Calcutta", "Europe/London")) {
                TimeZone.setDefault(TimeZone.getTimeZone(id));
                IllegalStateException e = assertThrows(IllegalStateException.class, EngineTimeZone::requireUtcJvm, id);
                assertTrue(e.getMessage().contains("'" + id + "', not UTC"), e.getMessage());
                assertTrue(e.getMessage().contains("-Duser.timezone=UTC"), e.getMessage());
                assertTrue(e.getMessage().contains("sangam.engine.timer-zone"), e.getMessage());
                assertEquals(id, TimeZone.getDefault().getID(), "the guard refuses; it never overrides the zone");
            }
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals(ZoneId.of("UTC"), EngineTimeZone.requireUtcJvm());
        } finally {
            TimeZone.setDefault(saved);
        }
    }
}
