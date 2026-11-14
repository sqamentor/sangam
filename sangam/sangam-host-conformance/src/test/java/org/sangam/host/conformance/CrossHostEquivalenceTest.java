// SPDX-License-Identifier: Apache-2.0
package org.sangam.host.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/** M-150 comparison mechanics and the golden file's integrity. Written for the test phase (not run). */
class CrossHostEquivalenceTest {

    @Test
    void everyKindOfDifferenceIsNamedWithBothSides() {
        BusinessResult expected = BusinessResult.ofCanonical(Map.of("fee", "1500", "ended", "false", "gone", "x"));
        BusinessResult actual = BusinessResult.ofCanonical(Map.of("fee", "1501", "ended", "false", "extra", "y"));
        assertEquals(List.of("s.extra: not expected (got y)", "s.fee: expected 1500 but got 1501",
                "s.gone: missing (expected x)"), CrossHostEquivalence.differences("s", expected, actual));
        assertEquals(List.of(), CrossHostEquivalence.differences("s", expected, expected));
    }

    @Test
    void hostsAreComparedWithTheFirstByName() {
        BusinessResult one = BusinessResult.ofCanonical(Map.of("fee", "1500"));
        BusinessResult two = BusinessResult.ofCanonical(Map.of("fee", "2500"));
        assertEquals(List.of(), CrossHostEquivalence.acrossHosts("s", Map.of("spring", one)));
        assertEquals(List.of(), CrossHostEquivalence.acrossHosts("s", Map.of("spring", one, "quarkus", one)));
        assertEquals(List.of("spring vs quarkus: s.fee: expected 2500 but got 1500"),
                CrossHostEquivalence.acrossHosts("s", Map.of("spring", one, "quarkus", two)));
    }

    @Test
    void theRenderedReportHasTheGoldenFileFormat() throws IOException {
        String text = CrossHostEquivalence.render(Map.of("b", BusinessResult.ofCanonical(Map.of("x", "1")),
                "a", BusinessResult.ofCanonical(Map.of("y", "[{feeMinor=1500}]"))));
        assertEquals("a.y=[{feeMinor=1500}]\nb.x=1\n", text);
        GoldenResults back = GoldenResults.parse(new StringReader(text));
        assertEquals(BusinessResult.ofCanonical(Map.of("y", "[{feeMinor=1500}]")), back.forScenario("a"));
    }

    @Test
    void theGoldenFileCoversExactlyTheScenarios() {
        GoldenResults golden = GoldenResults.load();
        assertEquals(new TreeSet<>(EquivalenceScenarios.all().stream().map(EquivalenceScenario::name).toList()),
                golden.scenarios());
        for (EquivalenceScenario s : EquivalenceScenarios.all()) {
            assertFalse(golden.forScenario(s.name()).values().isEmpty(), s.name());
        }
        // the rule: below 100000 gives 1500, below 1000000 gives 2500, from 1000000 on 5000 (minor units)
        BusinessResult fee = golden.forScenario("decision-fee");
        assertEquals("[{feeMinor=1500}]", fee.values().get("fee.99999"));
        assertEquals("[{feeMinor=2500}]", fee.values().get("fee.100000"));
        assertEquals("[{feeMinor=5000}]", fee.values().get("fee.2500000"));
        assertEquals("2500", golden.forScenario("process-fee").values().get("feeMinor"));
        assertEquals("1500", golden.forScenario("cross-tenant").values().get("feeMinor.unchanged"));
        assertEquals("1", golden.forScenario("idempotent-replay").values().get("executions"));
    }

    @Test
    void aScenarioWithoutGoldenResultsNeverRunsUnchecked() {
        GoldenResults golden = GoldenResults.load();
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> golden.forScenario("no-such-scenario")).getMessage().contains("no golden result"));
    }

    @Test
    void scenarioNamesAreUniqueAndWellFormed() {
        List<String> names = EquivalenceScenarios.all().stream().map(EquivalenceScenario::name).toList();
        assertEquals(names.size(), new TreeSet<>(names).size());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new EquivalenceScenario("Bad", "x", h -> null));
    }
}
