// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.sangam.engine.replay.CaptureListener;

/**
 * Decision 143: one shared environment resolution ({@code org.sangam.core.security.EnvironmentPolicy}) is consumed by
 * every module. {@code ProductionMode.isProduction(boolean, Collection)} is the resolution from before the decision (a
 * missing switch is "not production" there); it "remains for callers that have not moved yet". The last such caller
 * was the replay capture check ({@link CaptureListener#requireAllowed}); this guard fails when a main source of the
 * reactor calls the old resolution again. Run from sangam-engine, so the reactor root is the parent folder.
 */
class EnvironmentResolutionGuardTest {

    static final Pattern OLD_RESOLUTION = Pattern.compile("\\bProductionMode\\s*\\.\\s*isProduction\\s*\\(");

    @Test
    void noMainSourceAsksTheResolutionFromBeforeDecision143() throws IOException {
        Path root = EnginePatchLedgerGuardTest.root();
        List<String> hits = new ArrayList<>();
        int scanned = 0;
        for (Path file : EnginePatchLedgerGuardTest.mainSources(root)) {
            String rel = EnginePatchLedgerGuardTest.rel(root, file);
            if (rel.endsWith("/ProductionMode.java")) {
                continue;
            }
            scanned++;
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String code = line.strip();
                if (!code.startsWith("*") && !code.startsWith("//") && OLD_RESOLUTION.matcher(line).find()) {
                    hits.add(rel + ":" + (i + 1) + ": " + code);
                }
            }
        }
        assertTrue(scanned > 1000, "scanned the reactor's main sources (" + scanned + ")");
        assertTrue(hits.isEmpty(), "decision 143: ask EnvironmentPolicy (resolve or productionOrFailClosed), not "
                + "ProductionMode.isProduction: " + hits);
    }

    @Test
    void theGuardSeesACallAndNotAMention() {
        assertTrue(OLD_RESOLUTION.matcher("if (ProductionMode.isProduction(production, activeProfiles)) {").find());
        assertTrue(OLD_RESOLUTION.matcher("return org.sangam.core.security.ProductionMode.isProduction (flag, p);").find());
        assertTrue(!OLD_RESOLUTION.matcher("if (ProductionMode.isProductionProfile(this.profile)) {").find());
        assertTrue(!OLD_RESOLUTION.matcher("boolean production = ProductionEnvironment.isProduction(env);").find());
    }

    /** The capture check answers as the shared resolution does, for every combination of switch and profiles. */
    @Test
    void theCaptureCheckFollowsTheSharedResolution() {
        CaptureListener l = in -> { };
        assertThrows(IllegalStateException.class, () -> requireAllowed(l, null, List.of("test")), "missing switch");
        assertThrows(IllegalStateException.class, () -> requireAllowed(l, true, List.of("test")),
                "the switch on wins over a development profile (decision 198)");
        assertThrows(IllegalStateException.class, () -> requireAllowed(l, false, List.of("h2,eu-prod")),
                "a production profile inside a comma-separated entry contradicts the switch off");
        assertThrows(IllegalStateException.class, () -> requireAllowed(l, false, List.of(" Production ")));
        assertDoesNotThrow(() -> requireAllowed(l, false, List.of()), "non-production without a profile (staging)");
        assertDoesNotThrow(() -> requireAllowed(l, false, List.of("h2", "postgres")));
        assertDoesNotThrow(() -> requireAllowed(l, false, null));
    }

    private static void requireAllowed(CaptureListener l, Boolean production, List<String> profiles) {
        CaptureListener.requireAllowed(l, production, profiles);
    }
}
