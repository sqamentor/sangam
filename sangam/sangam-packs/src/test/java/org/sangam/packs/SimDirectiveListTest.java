// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.sim.DemoAnswersGateway;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * Decision 223: the simulator's list-of-records answer value {@code name=[k:v,k:v][k:v,k:v]}, parsed exactly
 * (whole numbers are minor units as {@link Long}), backward compatible with the {@code key=value} pairs, and shared by
 * the demo answers ({@link DemoAnswersGateway} reuses the same parser, so the server fallback reads the same file).
 */
class SimDirectiveListTest {

    static ConnectorRequest query(String connector, String directive) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (directive != null) {
            payload.put(SimulatorConnectorGateway.DIRECTIVE, directive);
        }
        return new ConnectorRequest("t1", connector, "freeze", null, "BK-1", "PI-1", payload, null);
    }

    static Map<String, Object> rec(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i].toString(), kv[i + 1]);
        }
        return m;
    }

    @Test
    void aListOfRecordsIsParsedExactly() {
        assertEquals(List.of(rec("employeeId", "E001", "pfWages", 1250050L, "esiWages", 1250050L),
                        rec("employeeId", "E002", "pfWages", 3000000L, "member", true)),
                SimulatorConnectorGateway.records(
                        "[employeeId:E001,pfWages:1250050,esiWages:1250050][employeeId:E002,pfWages:3000000,member:true]"));
        assertEquals(List.of(), SimulatorConnectorGateway.records("[]"));
        // an identifier with a leading zero or a colon stays text, field order is kept
        assertEquals(List.of(rec("employeeId", "007", "ref", "a:b", "zero", 0L, "debit", -5L)),
                SimulatorConnectorGateway.records("[employeeId:007,ref:a:b,zero:0,debit:-5]"));
    }

    @Test
    void malformedListsAreRefusedNeverGuessed() {
        for (String bad : List.of("[employeeId:E1][]", "[employeeId:E1]x", "x[employeeId:E1]", "[employeeId]",
                "[employeeId:]", "[:E1]", "[1d:E1]", "[a:1,a:2]", "[a:1,,b:2]", "[a:12.50]", "[a:.5]",
                "[a:99999999999999999999]", "[[a:1]]", "[a:1")) {
            assertThrows(IllegalArgumentException.class, () -> SimulatorConnectorGateway.records(bad), bad);
        }
    }

    @Test
    void aQueryAnswerCarriesTheListBesideOrdinaryPairs() {
        ConnectorResult r = new SimulatorConnectorGateway().query(query("hris.inputs",
                "frozen=true;register=[employeeId:E1,pfWages:100][employeeId:E2,pfWages:200];headcount=2"));
        assertEquals(OperationStatus.COMPLETED, r.status());
        assertEquals(Boolean.TRUE, r.data().get("frozen"));
        assertEquals(new BigDecimal("2"), r.data().get("headcount"), "ordinary pairs keep their coercion");
        assertEquals(List.of(rec("employeeId", "E1", "pfWages", 100L), rec("employeeId", "E2", "pfWages", 200L)),
                r.data().get("register"));
    }

    @Test
    void ordinaryValuesAreUnchanged() {
        ConnectorResult r = new SimulatorConnectorGateway().query(query("x.check",
                "amount=310000;ok=true;name=[draft;ref=A[1]"));
        assertEquals(new BigDecimal("310000"), r.data().get("amount"));
        assertEquals(Boolean.TRUE, r.data().get("ok"));
        assertEquals("[draft", r.data().get("name"), "a value that is not bracketed both ends is text as before");
        assertEquals("A[1]", r.data().get("ref"));
    }

    @Test
    void theShippedDemoRegisterIsAListAndTheDemoGatewayReturnsIt() {
        String line = DemoAnswersGateway.shippedAnswers().get("hris.inputs");
        assertTrue(line != null && line.startsWith("register=["), "demo answer of hris.inputs: " + line);
        ConnectorResult r = DemoAnswersGateway.withShippedAnswers().query(query("hris.inputs", null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> register = (List<Map<String, Object>>) r.data().get("register");
        assertEquals(3, register.size());
        for (Map<String, Object> e : register) {
            assertTrue(e.get("employeeId") instanceof String, "employeeId: " + e);
            assertTrue(e.get("pfWages") instanceof Long && e.get("esiWages") instanceof Long, "minor units: " + e);
        }
    }
}
