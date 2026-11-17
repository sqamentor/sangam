// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Round 9 CSRF triage report: writes every side effect a GET handler can reach (precise lambda model, main code only)
 * to {@code target/get-handler-side-effects.txt} for per-site review in SECURITY-TRIAGE-JAVA.md.
 */
class GetHandlerSideEffectsReportTest {

    @Test
    void writesTheSideEffectReport() {
        GetHandlerSideEffects g = GetHandlerSideEffects.load();
        assertThat(g.sources).as("GET handlers found on the classpath").hasSizeGreaterThan(100);
        Map<GetHandlerSideEffects.M, List<GetHandlerSideEffects.Hit>> result = g.analyse();
        List<String> lines = GetHandlerSideEffects.bySite(result);
        lines.add(0, "GET handlers: " + g.sources.size() + ", with a reachable side effect: "
                + result.values().stream().filter(h -> !h.isEmpty()).count());
        GetHandlerSideEffects.writeReport(Path.of("target/get-handler-side-effects.txt"), lines);
    }
}
