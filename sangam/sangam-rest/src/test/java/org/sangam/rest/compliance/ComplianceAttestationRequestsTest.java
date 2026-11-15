// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.compliance.consent.ConsentManagerPort;
import org.sangam.compliance.transfer.CrossBorderTransferGuard;
import org.sangam.rest.port.ApiProblem;

import tools.jackson.databind.json.JsonMapper;

/**
 * Owner decision 362 (lane D1 open point C): the remaining compliance request bodies whose attestations were
 * primitives. A primitive cannot tell "not answered" from "false" (or "version 0"): the server's JSON binding (Jackson 3)
 * refuses a missing primitive with a bare 400 that names nothing, while the contract did not require the field.
 * {@code VerifyLawfulRequest.rankAdequate}, {@code AuditEvidenceRequest.independent},
 * {@code ImpactAssessmentRequest.independent}, the transfer's {@code recipientSameProtectionAttested} and
 * {@code recipientConditionMet}, and the artefact's {@code noticeVersion} are now boxed in the REST bodies, required in
 * the contract, and an omitted one is refused by name ({@code VALIDATION_FAILED}), never defaulted: the pattern of
 * {@code ComplianceDefinePurposeRequest} ({@link CompliancePurposeRequestTest}).
 */
class ComplianceAttestationRequestsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String TRANSFER = """
            {"destinationCountry": "SG", "recipient": "Processor Pte", "purposeCode": "E2E_SERVICE",
             "dataCategories": ["contact"]}""";

    static final String ARTEFACT = """
            {"consentManagerId": "cm-1", "artefactId": "a-1", "principalRef": "citizen-1", "fiduciaryId": "f-1",
             "noticeKey": "service", "languageCode": "en", "purposeCodes": ["E2E_SERVICE"], "signature": "sig"}""";

    static <T> T read(String json, Class<T> type) {
        return JSON.readValue(json, type);
    }

    static ApiProblem refused(Runnable r, String field) {
        ApiProblem p = assertThrows(ApiProblem.class, r::run, field);
        assertEquals(400, p.status());
        assertEquals("VALIDATION_FAILED", p.code());
        assertTrue(p.getMessage().contains(field), "refused by name: " + p.getMessage());
        return p;
    }

    @Test
    void theModuleRecordsCannotBindABodyWithoutTheAttestations() {
        // the root cause, kept as evidence: with a primitive component a missing value is refused by the binding itself
        assertThrows(tools.jackson.databind.DatabindException.class,
                () -> JSON.readValue(TRANSFER, CrossBorderTransferGuard.TransferRequest.class));
        assertThrows(tools.jackson.databind.DatabindException.class,
                () -> JSON.readValue(ARTEFACT, ConsentManagerPort.ConsentArtefact.class));
    }

    @Test
    void theRestBodiesBindWithoutThemAndKeepThemUnanswered() {
        assertNull(read("{}", ComplianceRequests.VerifyLawfulRequest.class).rankAdequate());
        assertNull(read("{\"kind\": \"DATA_AUDIT\", \"auditor\": \"A\"}", ComplianceRequests.AuditEvidenceRequest.class)
                .independent());
        assertNull(read("{\"scope\": \"s\"}", ComplianceRequests.ImpactAssessmentRequest.class).independent());
        ComplianceRequests.EvaluateTransferRequest t = read(TRANSFER, ComplianceRequests.EvaluateTransferRequest.class);
        assertNull(t.recipientSameProtectionAttested());
        assertNull(t.recipientConditionMet());
        assertNull(read(ARTEFACT, ComplianceRequests.ConsentArtefactRequest.class).noticeVersion());
    }

    @Test
    void anOmittedRankAttestationIsRefusedByName() {
        refused(() -> ComplianceController.requireRank(read("{}", ComplianceRequests.VerifyLawfulRequest.class)),
                "rankAdequate");
        ComplianceController.requireRank(read("{\"rankAdequate\": false}", ComplianceRequests.VerifyLawfulRequest.class));
    }

    @Test
    void anOmittedIndependenceIsRefusedByName() {
        refused(() -> ComplianceController.requireIndependence(read("{\"kind\": \"DATA_AUDIT\"}",
                ComplianceRequests.AuditEvidenceRequest.class).independent()), "independent");
        refused(() -> ComplianceController.requireIndependence(read("{\"scope\": \"s\"}",
                ComplianceRequests.ImpactAssessmentRequest.class).independent()), "independent");
        ComplianceController.requireIndependence(read("{\"scope\": \"s\", \"independent\": false}",
                ComplianceRequests.ImpactAssessmentRequest.class).independent());
    }

    @Test
    void anOmittedTransferAttestationIsRefusedByNameAndAGivenOneIsKept() {
        refused(() -> ComplianceController.toTransfer(read(TRANSFER, ComplianceRequests.EvaluateTransferRequest.class)),
                "recipientSameProtectionAttested");
        refused(() -> ComplianceController.toTransfer(read(TRANSFER.replace("}", ", \"recipientSameProtectionAttested\": true}"),
                ComplianceRequests.EvaluateTransferRequest.class)), "recipientConditionMet");
        CrossBorderTransferGuard.TransferRequest r = ComplianceController.toTransfer(read(TRANSFER.replace("}",
                ", \"recipientSameProtectionAttested\": true, \"recipientConditionMet\": false, \"mechanism\": \"SCC\"}"),
                ComplianceRequests.EvaluateTransferRequest.class));
        assertTrue(r.recipientSameProtectionAttested());
        assertFalse(r.recipientConditionMet());
        assertEquals("SG", r.destinationCountry());
        assertEquals("SCC", r.mechanism());
        assertEquals(Set.of("contact"), r.dataCategories());
    }

    @Test
    void anOmittedNoticeVersionIsRefusedByNameNeverReadAsZero() {
        refused(() -> ComplianceController.toArtefact("cm-1", read(ARTEFACT, ComplianceRequests.ConsentArtefactRequest.class)),
                "noticeVersion");
        ConsentManagerPort.ConsentArtefact a = ComplianceController.toArtefact("cm-1", read(ARTEFACT.replace("}",
                ", \"noticeVersion\": 3}"), ComplianceRequests.ConsentArtefactRequest.class));
        assertEquals(3, a.noticeVersion());
        assertEquals("a-1", a.artefactId());
        ApiProblem other = assertThrows(ApiProblem.class, () -> ComplianceController.toArtefact("cm-2", read(ARTEFACT.replace("}",
                ", \"noticeVersion\": 3}"), ComplianceRequests.ConsentArtefactRequest.class)));
        assertEquals("VALIDATION_FAILED", other.code(), "the path names the consent manager");
    }
}
