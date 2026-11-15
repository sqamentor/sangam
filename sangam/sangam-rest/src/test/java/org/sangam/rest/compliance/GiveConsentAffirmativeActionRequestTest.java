// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;

import tools.jackson.databind.json.JsonMapper;

/**
 * Owner decision 369 (h): {@code GiveConsentRequest.affirmativeAction} is boxed and required (the decision-362
 * pattern). The body binds without it (Jackson 3 would otherwise refuse a missing primitive with a generic message),
 * and the omission is then refused by name ({@code VALIDATION_FAILED} naming {@code affirmativeAction}); it is never
 * read as false or true.
 */
class GiveConsentAffirmativeActionRequestTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String BODY = """
            {"noticeKey": "service", "purposeCodes": ["SERVICE"], "declaredCategory": "ADULT"}""";

    @Test
    void theBodyBindsWithoutTheAnswerAndKeepsItUnanswered() {
        assertNull(JSON.readValue(BODY, ComplianceRequests.GiveConsentRequest.class).affirmativeAction());
        assertEquals(Boolean.FALSE, JSON.readValue(BODY.replace("}", ", \"affirmativeAction\": false}"),
                ComplianceRequests.GiveConsentRequest.class).affirmativeAction());
    }

    @Test
    void anOmittedAnswerIsRefusedByName() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> ComplianceApiImpl.affirmativeAction(null));
        assertEquals(400, p.status());
        assertEquals("VALIDATION_FAILED", p.code());
        assertTrue(p.getMessage().contains("affirmativeAction"), p.getMessage());
    }

    @Test
    void trueAndFalseAreTakenAsAnswered() {
        assertTrue(ComplianceApiImpl.affirmativeAction(Boolean.TRUE));
        assertFalse(ComplianceApiImpl.affirmativeAction(Boolean.FALSE));
    }
}
