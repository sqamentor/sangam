// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.sim.DemoAnswersGateway;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * SL-115 (integrator decision): a demo's happy path comes from simulator DATA (demo-answers.properties through
 * {@link DemoAnswersGateway}), never from a pack default. The bare simulator stays bare (the fail-closed tests rely on
 * it); the demo gateway answers the checks; a case's own directive always wins.
 */
class SimulatorDemoAnswersTest {

    static ConnectorRequest query(String connector, Map<String, Object> payload) {
        return new ConnectorRequest("t1", connector, "check", null, "BK-1", "PI-1", payload, null);
    }

    @Test
    void theShippedDemoDataAnswersEveryCheckThatLostItsPackDefault() {
        Map<String, String> answers = DemoAnswersGateway.shippedAnswers();
        for (String c : java.util.List.of("pan.verify", "sanctions.screen", "bureau.pull", "policy.coverCheck",
                "fraud.score", "switch.status", "erp.netSupplies", "eproc.bidsReceived", "billing.arrears", "x12.claim")) {
            assertTrue(answers.containsKey(c), "no demo answer for " + c);
            assertTrue(answers.get(c).contains("="), c + " answers data: " + answers.get(c));
        }
    }

    @Test
    void theDemoGatewayAnswersAndTheBareSimulatorDoesNot() {
        ConnectorResult bare = new SimulatorConnectorGateway().query(query("pan.verify", Map.of("pan", "X")));
        assertNull(bare.data().get("valid"), "the bare simulator has no PAN answer: the check fails closed");
        ConnectorResult demo = DemoAnswersGateway.withShippedAnswers().query(query("pan.verify", Map.of("pan", "X")));
        assertEquals(OperationStatus.COMPLETED, demo.status());
        assertEquals(Boolean.TRUE, demo.data().get("valid"));
    }

    @Test
    void aCaseDirectiveWinsOverTheDemoAnswer() {
        DemoAnswersGateway g = DemoAnswersGateway.withShippedAnswers();
        ConnectorResult failed = g.query(query("pan.verify", Map.of("pan", "X",
                SimulatorConnectorGateway.DIRECTIVE, "FAILED:ITD_DOWN")));
        assertEquals(OperationStatus.FAILED, failed.status());
        ConnectorResult hit = g.query(query("sanctions.screen", Map.of(SimulatorConnectorGateway.DIRECTIVE, "hit=true")));
        assertEquals(Boolean.TRUE, hit.data().get("hit"));
        assertFalse(g.simulator().journal().isEmpty(), "the wrapped simulator saw the calls");
    }
}
