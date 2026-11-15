// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.compliance.ports.CompliancePorts.PrincipalCategory;
import org.sangam.rest.port.ApiProblem;

import tools.jackson.databind.json.JsonMapper;

/**
 * Owner decision 365: {@code POST /compliance/consents} carries the declared category of the data principal,
 * {@code ADULT} or {@code CHILD}: required by the contract, an omitted or other value refused by name
 * ({@code VALIDATION_FAILED}), never defaulted (the decision-362 pattern).
 */
class GiveConsentCategoryRequestTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String BODY = """
            {"noticeKey": "service", "purposeCodes": ["SERVICE"], "affirmativeAction": true}""";

    static void refused(String declared) {
        ApiProblem p = assertThrows(ApiProblem.class, () -> ComplianceApiImpl.declaredCategory(declared), String.valueOf(declared));
        assertEquals(400, p.status());
        assertEquals("VALIDATION_FAILED", p.code());
        assertTrue(p.getMessage().contains("declaredCategory"), p.getMessage());
    }

    @Test
    void theBodyBindsWithoutTheCategoryAndKeepsItUndeclared() {
        assertNull(JSON.readValue(BODY, ComplianceRequests.GiveConsentRequest.class).declaredCategory());
        assertEquals("CHILD", JSON.readValue(BODY.replace("}", ", \"declaredCategory\": \"CHILD\"}"),
                ComplianceRequests.GiveConsentRequest.class).declaredCategory());
    }

    @Test
    void anOmittedOrOtherCategoryIsRefusedByName() {
        refused(null);
        refused("");
        refused("child");
        refused("UNKNOWN");
        refused("PERSON_WITH_DISABILITY_WITH_GUARDIAN");
    }

    @Test
    void adultAndChildAreTakenAsDeclared() {
        assertEquals(PrincipalCategory.ADULT, ComplianceApiImpl.declaredCategory("ADULT"));
        assertEquals(PrincipalCategory.CHILD, ComplianceApiImpl.declaredCategory("CHILD"));
    }
}
