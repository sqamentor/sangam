// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.sim.DemoAnswersGateway;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * Decision 225 (IFC-189): a profile value is taken only from a completed {@code profile.get} answer whose
 * {@code values} map holds the path with a value of the declared type; everything else gives no value and a reason, so
 * the model refuses the case (fail closed).
 */
class ProfileValueDelegateTest {

    static final String PATH = "procurement.scientificDepartment";

    static ConnectorResult values(Object value) {
        Map<String, Object> v = new HashMap<>();
        v.put(PATH, value); // a path the profile does not hold comes back with a null value (ConfigConnector)
        return ConnectorResult.completed(null, Map.of("values", v, "binding", "IN@2026.11.0>GOI@2026.11.0#abc"));
    }

    @Test
    void aBooleanOfTheBoundProfileIsTheValue() {
        assertEquals(new ProfileValueDelegate.Answer(Boolean.TRUE, null),
                ProfileValueDelegate.answer(values(Boolean.TRUE), PATH, "BOOLEAN"));
        assertEquals(new ProfileValueDelegate.Answer(Boolean.FALSE, null),
                ProfileValueDelegate.answer(values(Boolean.FALSE), PATH, "BOOLEAN"));
    }

    @Test
    void aCallThatDidNotCompleteGivesNoValue() {
        assertEquals("NOT_COMPLETED", ProfileValueDelegate.answer(null, PATH, "BOOLEAN").problem());
        assertEquals("NOT_COMPLETED", ProfileValueDelegate.answer(ConnectorResult.failed("BINDING_UNRESOLVABLE", "x"),
                PATH, "BOOLEAN").problem());
        assertEquals("NOT_COMPLETED", ProfileValueDelegate.answer(ConnectorResult.pending(null), PATH, "BOOLEAN").problem());
        assertEquals("NOT_COMPLETED", ProfileValueDelegate.answer(ConnectorResult.unknown("timeout"), PATH, "BOOLEAN")
                .problem());
        assertNull(ProfileValueDelegate.answer(ConnectorResult.failed("CONFIG_ERROR", "x"), PATH, "BOOLEAN").value());
    }

    @Test
    void aMissingValueGivesNoValue() {
        assertEquals("MISSING", ProfileValueDelegate.answer(values(null), PATH, "BOOLEAN").problem());
        assertEquals("MISSING", ProfileValueDelegate.answer(ConnectorResult.completed(null, Map.of()), PATH, "BOOLEAN")
                .problem());
        assertEquals("MISSING", ProfileValueDelegate.answer(ConnectorResult.completed(null,
                Map.of("values", Map.of("procurement.other", true))), PATH, "BOOLEAN").problem());
        // the answer is the values MAP of the contract; a flat key or a list is not a profile answer
        assertEquals("MISSING", ProfileValueDelegate.answer(ConnectorResult.completed(null, Map.of(PATH, true)), PATH,
                "BOOLEAN").problem());
        assertEquals("MISSING", ProfileValueDelegate.answer(ConnectorResult.completed(null,
                Map.of("values", List.of(true))), PATH, "BOOLEAN").problem());
    }

    @Test
    void aValueOfAnotherTypeGivesNoValue() {
        assertEquals("WRONG_TYPE", ProfileValueDelegate.answer(values("true"), PATH, "BOOLEAN").problem());
        assertEquals("WRONG_TYPE", ProfileValueDelegate.answer(values(1), PATH, "BOOLEAN").problem());
        assertEquals("WRONG_TYPE", ProfileValueDelegate.answer(values(List.of("DAE")), PATH, "BOOLEAN").problem());
        assertEquals("WRONG_TYPE", ProfileValueDelegate.answer(values(Boolean.TRUE), PATH, "STRING").problem());
        assertEquals("WRONG_TYPE", ProfileValueDelegate.answer(values(" "), PATH, "STRING").problem());
        assertEquals(new ProfileValueDelegate.Answer("DAE", null), ProfileValueDelegate.answer(values("DAE"), PATH,
                "STRING"));
    }

    static ConnectorRequest profileRead(String operation, Map<String, Object> payload) {
        return new ConnectorRequest("t1", "config.profile", operation, null, "BK-1", "PI-1", payload, null);
    }

    @Test
    void theSimulatedProfileAnswersInTheConnectorsShape() {
        // the demo data (demo-answers.properties) is a non-scientific department
        ConnectorResult demo = DemoAnswersGateway.withShippedAnswers().query(profileRead("profile.get",
                Map.of("paths", PATH)));
        assertEquals(new ProfileValueDelegate.Answer(Boolean.FALSE, null),
                ProfileValueDelegate.answer(demo, PATH, "BOOLEAN"));
        assertEquals("SIMULATOR", demo.data().get("binding"));
        // a case directive is the simulated profile; a path it does not hold is answered with no value
        ConnectorResult sci = new SimulatorConnectorGateway().query(profileRead("profile.get",
                Map.of("paths", PATH + ",procurement.gemMandatory", SimulatorConnectorGateway.DIRECTIVE, PATH + "=true")));
        assertEquals(Boolean.TRUE, ProfileValueDelegate.answer(sci, PATH, "BOOLEAN").value());
        assertEquals("MISSING", ProfileValueDelegate.answer(sci, "procurement.gemMandatory", "BOOLEAN").problem());
        // the bare simulator holds no profile: fail closed
        assertEquals("MISSING", ProfileValueDelegate.answer(new SimulatorConnectorGateway().query(
                profileRead("profile.get", Map.of("paths", PATH))), PATH, "BOOLEAN").problem());
        assertEquals(OperationStatus.FAILED, new SimulatorConnectorGateway().query(profileRead("profile.get",
                Map.of("paths", PATH, SimulatorConnectorGateway.DIRECTIVE, "FAILED:CONFIG_ERROR"))).status());
        // profile values are not answer fields of the other operations (profile.binding of the IFMS bill)
        ConnectorResult binding = DemoAnswersGateway.withShippedAnswers().query(profileRead("profile.binding",
                Map.of("packId", "gov.ifms.bill")));
        assertTrue(binding.data().isEmpty(), "profile.binding data: " + binding.data());
    }

    @Test
    void theConnectorIsTheReadOnlyProfileOperation() {
        assertEquals("config.profile", ProfileValueDelegate.CONNECTOR);
        assertEquals("profile.get", ProfileValueDelegate.OPERATION);
        assertEquals(OperationStatus.COMPLETED, values(true).status());
    }
}
