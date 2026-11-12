// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.sangam.onboarding.flow.OnboardingException;
import org.sangam.rest.port.ApiProblem;
import org.springframework.mock.env.MockEnvironment;

/** SL-42: the environment label rule, request validation and the mapping of promotion refusals (no server). */
class PromotionControllerTest {

    static PromotionController controller(MockEnvironment env) {
        return new PromotionController(null, null, null, null, env);
    }

    @Test
    void aServerWithoutAnEnvironmentLabelCannotTakePart() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> controller(new MockEnvironment()).environment());
        assertEquals(409, p.status());
        assertEquals("PROMOTION_ENVIRONMENT_NOT_SET", p.code());
        MockEnvironment blank = new MockEnvironment().withProperty(PromotionController.ENVIRONMENT_PROPERTY, " ");
        assertEquals(409, assertThrows(ApiProblem.class, () -> controller(blank).environment()).status());
        MockEnvironment uat = new MockEnvironment().withProperty(PromotionController.ENVIRONMENT_PROPERTY, " uat ");
        assertEquals("uat", controller(uat).environment());
    }

    @Test
    void requiredFieldsAreNamed() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> PromotionController.requireText(null, "planHash"));
        assertEquals(400, p.status());
        assertEquals("planHash", p.extensions().get("field"));
    }

    @Test
    void promotionRefusalsKeepTheirStatusAndCode() {
        ApiProblem changed = assertThrows(ApiProblem.class, () -> PromotionController.call(() -> {
            throw new OnboardingException(409, "PROMOTION_PLAN_CHANGED", "changed");
        }));
        assertEquals(409, changed.status());
        assertEquals("PROMOTION_PLAN_CHANGED", changed.code());
        ApiProblem bad = assertThrows(ApiProblem.class, () -> PromotionController.call(() -> {
            throw new IllegalArgumentException("bad signature");
        }));
        assertEquals("VALIDATION_FAILED", bad.code());
    }
}
