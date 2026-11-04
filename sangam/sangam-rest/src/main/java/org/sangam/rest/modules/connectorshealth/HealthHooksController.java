// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.web.RequestContexts;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health interoperability hooks (sangam-connectors-health):
 * <ul>
 *   <li>{@code POST /api/v1/hooks/fhir/{channel}}: FHIR R5/R4B topic-based subscription notifications. Unauthenticated
 *       at the Sangam layer; the receiver checks the channel's shared secret header (constant-time) and writes each
 *       focus resource to the integration inbox (deduplicated, tenant-bound by the channel binding).</li>
 *   <li>{@code GET /cds-services} and {@code POST /cds-services/{id}}: the CDS Hooks service endpoints (HL7 CDS Hooks
 *       2.0 fixes these paths at the server root). The caller must be an authenticated client principal.</li>
 * </ul>
 */
@RestController
public class HealthHooksController {

    private final HealthHooksPort hooks;

    public HealthHooksController(HealthHooksPort hooks) {
        this.hooks = hooks;
    }

    @PostMapping(value = "/api/v1/hooks/fhir/{channel}", consumes = {"application/fhir+json", "application/json"})
    public ResponseEntity<Map<String, Object>> fhirNotification(@PathVariable String channel,
                                                                @RequestBody(required = false) byte[] body,
                                                                HttpServletRequest request) {
        if (body == null || body.length == 0) {
            throw ApiProblem.badRequest("EMPTY_NOTIFICATION", "the notification bundle is empty");
        }
        HealthHooksPort.HookResult r = hooks.fhirNotification(channel, request::getHeader, body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", r.kind());
        out.put("messages", r.messages());
        out.put("missedEvents", r.missedEvents());
        return ResponseEntity.status(HttpStatus.valueOf(r.status())).body(out);
    }

    @GetMapping(value = "/cds-services", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> cdsDiscovery(HttpServletRequest request) {
        client(request);
        HealthHooksPort.CdsReply r = hooks.cdsDiscovery();
        return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.json());
    }

    @PostMapping(value = "/cds-services/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> cdsCall(@PathVariable String id, @RequestBody byte[] body, HttpServletRequest request) {
        HealthHooksPort.CdsReply r = hooks.cdsCall(id, body, client(request));
        return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.json());
    }

    private static String client(HttpServletRequest request) {
        SangamPrincipal p = RequestContexts.principal(request);
        if (p == null) {
            throw new ApiProblem(401, "UNAUTHENTICATED", "CDS Hooks calls need an authenticated client (JWT)");
        }
        return p.principalId();
    }
}
