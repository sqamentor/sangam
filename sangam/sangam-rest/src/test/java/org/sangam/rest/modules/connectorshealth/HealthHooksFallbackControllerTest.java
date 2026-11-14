// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Decision 187 with the integrator ruling: the health routes answer 501 CAPABILITY_NOT_INSTALLED while the module is off. */
class HealthHooksFallbackControllerTest {

    private final HealthHooksFallbackController c = new HealthHooksFallbackController();

    @Test
    void everyHealthRouteAnswersNotInstalled() {
        List<Function<MockHttpServletRequest, ResponseEntity<Map<String, Object>>>> routes = List.of(
                c::fhirNotification, c::remittance835, c::cdsDiscovery, c::cdsCall);
        for (var route : routes) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/hooks/fhir/ch-1");
            ResponseEntity<Map<String, Object>> r = route.apply(req);
            assertEquals(501, r.getStatusCode().value());
            assertNotNull(r.getBody());
            assertEquals("CAPABILITY_NOT_INSTALLED", String.valueOf(r.getBody().get("code")), String.valueOf(r.getBody()));
            assertTrue(String.valueOf(r.getBody()).contains("sangam-connectors-health"), String.valueOf(r.getBody()));
        }
    }

    @Test
    void theFallbackIsRegisteredOnlyWithoutAHealthHooksPort() {
        ConditionalOnMissingBean missing = HealthHooksFallbackConfiguration.class.getAnnotation(ConditionalOnMissingBean.class);
        assertNotNull(missing);
        assertEquals(List.of(HealthHooksPort.class), List.of(missing.value()));
        assertEquals(List.of(HealthHooksFallbackController.class),
                List.of(HealthHooksFallbackConfiguration.class.getAnnotation(Import.class).value()));
    }
}
