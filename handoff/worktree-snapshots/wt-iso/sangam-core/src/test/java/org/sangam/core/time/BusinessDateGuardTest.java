// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.time;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build-time guard: no production source may take a calendar date / wall-clock time from the JVM zone or pick a
 * zone ad hoc. Business dates come from {@link BusinessClock} (the tenant's profile zone). Every remaining use of a
 * clock-reading date/time factory or of the JVM default zone must be on {@link #ALLOWED} with its reason; a new one
 * fails this test, and so does an allow-list entry that no longer matches (so the list cannot go stale).
 */
class BusinessDateGuardTest {

    /** {@code LocalDate.now(...)}, {@code LocalDateTime.now(...)}, {@code ZonedDateTime/OffsetDateTime/YearMonth/Year/LocalTime.now(...)}, JVM default zone. */
    static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(?:java\\.time\\.)?(?:LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth|Year|MonthDay)\\s*\\.\\s*now\\s*\\("
                    + "|\\bZoneId\\s*\\.\\s*systemDefault\\s*\\(|\\bTimeZone\\s*\\.\\s*getDefault\\s*\\(|\\bClock\\s*\\.\\s*systemDefaultZone\\s*\\(");

    /** path (relative to the repository root, '/' separators) -> exact number of hits, with the justification. */
    static final Map<String, Integer> ALLOWED = new TreeMap<>();

    static {
        // Engine timers: Operaton computes cron/cycle due dates in the JVM zone; the DST guard must use the same wall clock.
        allow("sangam-engine/src/main/java/org/sangam/engine/SangamEngineSettings.java", 1);
        allow("sangam-engine/src/main/java/org/sangam/engine/SangamEngineComponents.java", 1);
        // Normalises a legacy JVM zone id at start-up (reads, never uses, the default zone).
        allow("sangam-core/src/main/java/org/sangam/core/layer/LegacyTimeZones.java", 1);
        // Wire protocols that mandate a fixed zone for their own timestamps (not tenant business dates).
        allow("sangam-connectors-health/src/main/java/org/sangam/connectors/health/x12/X12Builders.java", 1);   // X12 envelope date, UTC
        allow("sangam-connectors-health/src/main/java/org/sangam/connectors/health/x12/X12.java", 1);           // X12 interchange time, UTC
        allow("sangam-connectors-health/src/main/java/org/sangam/connectors/health/pas/PasConnector.java", 1);  // FHIR Claim.created, UTC
        allow("sangam-integration/src/main/java/org/sangam/integration/connector/gov/EsignConnector.java", 1);        // provider spec: ts in its own zone
        allow("sangam-integration/src/main/java/org/sangam/integration/connector/gov/DigiLockerPushConnector.java", 1); // provider spec: ts in its own zone
        allow("sangam-flowable-ports/src/main/java/org/sangam/ports/content/S3DocumentStore.java", 1);           // SigV4 signing date, UTC
        // Technical UTC day buckets (platform metrics / analytics rollups, not tenant business dates).
        allow("sangam-server/src/main/java/org/sangam/server/config/OpsWiring.java", 1);
        allow("sangam-rollup/src/main/java/org/sangam/rollup/RollupProjector.java", 2);
        // Explicit tenant zone (RegistryContext.zone, defaulting to the tenant business zone).
        allow("sangam-registry/src/main/java/org/sangam/registry/asset/AssetService.java", 2);
        allow("sangam-registry/src/main/java/org/sangam/registry/numbering/NumberingService.java", 1);
        allow("sangam-registry/src/main/java/org/sangam/registry/register/RegisterBookService.java", 1);
        allow("sangam-registry/src/main/java/org/sangam/registry/rest/AssetController.java", 1);
        // Onboarding plans a tenant that has no profile yet: the documented fallback (UTC on the injected clock).
        allow("sangam-onboarding/src/main/java/org/sangam/onboarding/flow/OnboardingService.java", 2);
        // Simulator stub data (fake hearing date), never a real business record.
        allow("sangam-packs/src/main/java/org/sangam/packs/sim/SimulatorConnectorGateway.java", 1);
    }

    static void allow(String path, int count) {
        ALLOWED.put(path, count);
    }

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("sangam-core"))) {
            p = p.getParent();
        }
        assertTrue(p != null, "repository root (with sangam-core) not found");
        return p;
    }

    @Test
    void noJvmZoneBusinessDatesInProductionCode() throws IOException {
        Path root = root();
        Map<String, Integer> found = new TreeMap<>();
        Map<String, List<String>> lines = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java"))
                    .filter(x -> x.toString().replace('\\', '/').contains("/src/main/"))
                    .filter(x -> !x.toString().replace('\\', '/').contains("/target")).toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                List<String> src = Files.readAllLines(f);
                for (int i = 0; i < src.size(); i++) {
                    String line = src.get(i);
                    String code = line.strip();
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                        continue;   // comments / javadoc
                    }
                    Matcher m = FORBIDDEN.matcher(line);
                    while (m.find()) {
                        found.merge(rel, 1, Integer::sum);
                        lines.computeIfAbsent(rel, k -> new ArrayList<>()).add((i + 1) + ": " + code);
                    }
                }
            }
        }
        List<String> violations = new ArrayList<>();
        found.forEach((path, n) -> {
            Integer ok = ALLOWED.get(path);
            if (ok == null || n > ok) {
                violations.add(path + " (" + n + " hit(s), allowed " + (ok == null ? 0 : ok) + "): " + lines.get(path));
            }
        });
        assertEquals(List.of(), violations, "Business dates must come from org.sangam.core.time.BusinessClock "
                + "(tenant zone), never the JVM zone. Use businessClock.today(tenant); a justified technical use goes on "
                + "BusinessDateGuardTest.ALLOWED.");
        List<String> stale = new ArrayList<>();
        ALLOWED.forEach((path, n) -> {
            if (!n.equals(found.getOrDefault(path, 0))) {
                stale.add(path + " allowed " + n + ", found " + found.getOrDefault(path, 0));
            }
        });
        assertEquals(List.of(), stale, "stale BusinessDateGuardTest.ALLOWED entries: tighten them");
    }

    @Test
    void patternCatchesTheForbiddenForms() {
        for (String s : List.of("LocalDate.now()", "java.time.LocalDate.now()", "LocalDateTime.now(clock)", "YearMonth.now()",
                "Year.now(ZoneOffset.UTC)", "ZonedDateTime.now()", "ZoneId.systemDefault()", "TimeZone.getDefault()")) {
            assertTrue(FORBIDDEN.matcher(s).find(), s);
        }
        for (String s : List.of("Instant.now()", "businessClock.today(t)", "LocalDate.parse(x)", "LocalDate.ofInstant(i, z)")) {
            assertTrue(!FORBIDDEN.matcher(s).find(), s);
        }
    }
}
