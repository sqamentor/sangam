// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * GP-49: a simulator directive limited to some cases by business-key suffix ({@code <directive> @ <suffix>|...}).
 * A batch item is {@code <parent>:<round>:<item>}, so a suffix names one item in one round: the failure happens in
 * round 1 and the resend in round 2 succeeds.
 */
class SimDirectiveSelectorTest {

    private static final String D = "FAILED:ACCOUNT_CLOSED @ :1:ITEM-00002|:1:ITEM-00004";

    private static ConnectorResult submit(SimulatorConnectorGateway g, String bk, String op) {
        return g.submit(new ConnectorRequest(null, "pfms.dbt", "submitDbtRecord", op, bk, "pi",
                Map.of(SimulatorConnectorGateway.DIRECTIVE, D, "key", bk), "h-" + op));
    }

    @Test
    void theSelectedItemsFailInTheirRoundOnly() {
        SimulatorConnectorGateway g = new SimulatorConnectorGateway();
        ConnectorResult r = submit(g, "B/1:1:ITEM-00002", "op-1");
        assertEquals(OperationStatus.FAILED, r.status());
        assertEquals("ACCOUNT_CLOSED", r.reasonCode());
        assertEquals(OperationStatus.FAILED, submit(g, "B/1:1:ITEM-00004", "op-2").status());
        assertEquals(OperationStatus.COMPLETED, submit(g, "B/1:1:ITEM-00001", "op-3").status(), "not selected");
        assertEquals(OperationStatus.COMPLETED, submit(g, "B/1:2:ITEM-00002", "op-4").status(), "round 2 resend");
    }

    @Test
    void alternativesSelectedByAPayloadField() {
        String d = "score=80 @ scoreRound=1 || score=50 @ scoreRound=2";
        assertEquals("score=80", SimulatorConnectorGateway.selected(d, "C/app-3", Map.of("scoreRound", 1)));
        assertEquals("score=50", SimulatorConnectorGateway.selected(d, "C/app-3", Map.of("scoreRound", "2")),
                "compared as text");
        assertNull(SimulatorConnectorGateway.selected(d, "C/app-3", Map.of("scoreRound", 3)), "no alternative applies");
        assertNull(SimulatorConnectorGateway.selected(d, "C/app-3", Map.of()), "field absent");
        assertEquals("score=70", SimulatorConnectorGateway.selected("score=99 @ scoreRound=9 || score=70", "x",
                Map.of("scoreRound", 1)), "an alternative without a selector is the fallback");
        assertEquals("FAILED:X", SimulatorConnectorGateway.selected("FAILED:X @ :1:A|scoreRound=2", "P:9:B",
                Map.of("scoreRound", 2)), "business-key and field selectors mix");
    }

    @Test
    void theSimulatorAnswersTheQueryOfTheSelectedRound() {
        SimulatorConnectorGateway g = new SimulatorConnectorGateway();
        java.util.function.IntFunction<Object> score = round -> g.query(new ConnectorRequest(null, "exam.score", "score",
                null, "C/app-6", "pi", Map.of(SimulatorConnectorGateway.DIRECTIVE,
                "score=75 @ scoreRound=1 || score=95 @ scoreRound=2", "scoreRound", round), "h")).data().get("score");
        assertEquals(0, new java.math.BigDecimal("75").compareTo((java.math.BigDecimal) score.apply(1)));
        assertEquals(0, new java.math.BigDecimal("95").compareTo((java.math.BigDecimal) score.apply(2)));
    }

    @Test
    void malformedDirectivesAreRefused() {
        for (String bad : List.of("score=1 ||", "|| score=1", "score=1 || || score=2", "FAILED:X @", "FAILED:X @ a||",
                "FAILED:X @ a||b @ ", "FAILED:X @ a| |b")) {
            assertThrows(IllegalArgumentException.class, () -> SimulatorConnectorGateway.selected(bad, "a", Map.of()),
                    bad);
        }
    }

    @Test
    void selectionRules() {
        assertEquals("FAILED:X", SimulatorConnectorGateway.selected("FAILED:X", "any"), "no selector: every case");
        assertEquals("FAILED:X", SimulatorConnectorGateway.selected("FAILED:X @ :1:A", "P:1:A"));
        assertNull(SimulatorConnectorGateway.selected("FAILED:X @ :1:A", "P:1:AB"), "suffix, not substring");
        assertNull(SimulatorConnectorGateway.selected("FAILED:X @ :1:A", null));
        assertEquals("available=5", SimulatorConnectorGateway.selected("available=5 @ /x|/y", "S/y"),
                "data directives are selectable too");
        assertNull(SimulatorConnectorGateway.selected(" @ /x", "S/x"), "an empty directive is the default outcome");
    }
}
