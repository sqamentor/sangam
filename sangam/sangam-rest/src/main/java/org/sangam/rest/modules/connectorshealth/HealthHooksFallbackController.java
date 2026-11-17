// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.modules.config.ModuleProblems;
import org.sangam.rest.port.ApiProblem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Decision 187: sangam-connectors-health is optional and off by default. When no {@link HealthHooksPort} is bound (the
 * module is absent or {@code sangam.modules.connectors-health.enabled} is false) the health surfaces say so instead of an
 * unmapped 404: {@code 501 CAPABILITY_NOT_INSTALLED} (integrator ruling, the repository convention of
 * {@link ApiProblem#notInstalled}). It needs no health class on the class path and reads no request body.
 */
@RestController
public class HealthHooksFallbackController {

    static final String CAPABILITY = "connectors-health";
    static final String MODULE = "sangam-connectors-health";

    @PostMapping("/api/v1/hooks/fhir/by-channel")
    public ResponseEntity<Map<String, Object>> fhirNotification(HttpServletRequest request) {
        return notInstalled(request);
    }

    @PostMapping("/api/v1/connectors-health/x12/835")
    public ResponseEntity<Map<String, Object>> remittance835(HttpServletRequest request) {
        return notInstalled(request);
    }

    @GetMapping("/cds-services")
    public ResponseEntity<Map<String, Object>> cdsDiscovery(HttpServletRequest request) {
        return notInstalled(request);
    }

    @PostMapping("/cds-services/{id}")
    public ResponseEntity<Map<String, Object>> cdsCall(HttpServletRequest request) {
        return notInstalled(request);
    }

    static ResponseEntity<Map<String, Object>> notInstalled(HttpServletRequest request) {
        return ModuleProblems.respond(ApiProblem.notInstalled(CAPABILITY, MODULE), request);
    }
}
