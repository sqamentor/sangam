// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.rest.port.ApiProblem;

import tools.jackson.databind.json.JsonMapper;

/**
 * Integrator finding on CI ui e2e run 43717963671 (main 209d2765): {@code PUT /compliance/purposes/by-code?code=} bound its body
 * to {@code PurposeDefinition}, whose {@code standardsAttested}, {@code version}, {@code active} and
 * {@code DataItem.sensitive} are primitives, so the server's JSON binding (Jackson 3 refuses a missing primitive)
 * answered 400 to every body without them, while the contract required only {@code basis}: the e2e seed and the
 * officer form both failed. The body is now {@link ComplianceRequests.DefinePurposeRequest}: the two legal attestations
 * and {@code active} are explicit (an omitted one is refused by name, never defaulted), and {@code version} is not an
 * input. The contact body's residency attestation is boxed too: omitted means not attested.
 */
class CompliancePurposeRequestTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** The e2e seed body (sangam-ui/e2e/support/compliance.ts) as it was: no standardsAttested, no version. */
    static final String SEED = """
            {"names": {"en": "E2E service delivery"}, "descriptions": {"en": "x"},
             "dataItems": [{"code": "name", "names": {"en": "Full name"}, "sensitive": false}],
             "basis": "CONSENT", "retention": "P400D", "active": true}""";

    static ComplianceRequests.DefinePurposeRequest read(String json) {
        return JSON.readValue(json, ComplianceRequests.DefinePurposeRequest.class);
    }

    static ApiProblem refused(String json) {
        ApiProblem p = assertThrows(ApiProblem.class, () -> ComplianceController.toPurpose("E2E_SERVICE", read(json)));
        assertEquals(400, p.status());
        assertEquals("VALIDATION_FAILED", p.code());
        return p;
    }

    @Test
    void theOldBodyTypeCouldNotBindABodyWithoutThePrimitives() {
        // the root cause, kept as evidence: the record with primitive components refuses the seed body outright
        assertThrows(tools.jackson.databind.DatabindException.class, () -> JSON.readValue(SEED, PurposeDefinition.class));
    }

    @Test
    void anOmittedAttestationIsRefusedByNameNeverDefaulted() {
        ApiProblem p = refused(SEED);
        assertTrue(p.getMessage().contains("standardsAttested"), p.getMessage());
        ApiProblem active = refused(SEED.replace("\"active\": true", "\"standardsAttested\": false"));
        assertTrue(active.getMessage().contains("active"), active.getMessage());
        ApiProblem sensitive = refused(SEED.replace(", \"sensitive\": false", "").replace("\"active\": true",
                "\"active\": true, \"standardsAttested\": false"));
        assertTrue(sensitive.getMessage().contains("dataItems[0].sensitive"), sensitive.getMessage());
    }

    @Test
    void explicitChoicesBindAndTheVersionIsTheServers() {
        PurposeDefinition d = ComplianceController.toPurpose("E2E_SERVICE",
                read(SEED.replace("\"active\": true", "\"active\": true, \"standardsAttested\": false")));
        assertEquals("E2E_SERVICE", d.code());
        assertFalse(d.standardsAttested());
        assertTrue(d.active());
        assertFalse(d.dataItems().get(0).sensitive());
        assertEquals(Duration.ofDays(400), d.retention());
        assertEquals(0, d.version(), "not an input: LegitimateUseRegistry.define assigns previous + 1");
        ApiProblem mismatch = assertThrows(ApiProblem.class, () -> ComplianceController.toPurpose("OTHER",
                read(SEED.replace("{\"names\"", "{\"code\": \"E2E_SERVICE\", \"names\"").replace("\"active\": true",
                        "\"active\": true, \"standardsAttested\": true"))));
        assertEquals("VALIDATION_FAILED", mismatch.code());
    }

    @Test
    void aVersionInTheBodyIsNotAnInput() {
        // unknown properties: the request shape has no version component
        assertTrue(java.util.Arrays.stream(ComplianceRequests.DefinePurposeRequest.class.getRecordComponents())
                .noneMatch(c -> c.getName().equals("version")));
    }

    @Test
    void anOmittedResidencyIsNotAttested() {
        ComplianceRequests.ContactRequest c = JSON.readValue("""
                {"name": "PoC", "email": "poc@example.gov.in", "designation": "Deputy CISO", "mobile": "9000000000",
                 "address": "New Delhi"}""", ComplianceRequests.ContactRequest.class);
        assertNull(c.resident(), "binds without the attestation (the server refuses it only for a resident-only role)");
    }
}
