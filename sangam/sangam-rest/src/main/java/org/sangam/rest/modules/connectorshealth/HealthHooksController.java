// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.port.ApiProblem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health interoperability hooks (sangam-connectors-health):
 * <ul>
 *   <li>{@code POST /api/v1/hooks/fhir/by-channel?channel=}: FHIR R5/R4B topic-based subscription notifications. Unauthenticated
 *       at the Sangam layer; the receiver checks the channel's shared secret header (constant-time) and writes each
 *       focus resource to the integration inbox (deduplicated, tenant-bound by the channel binding).</li>
 *   <li>{@code GET /cds-services} and {@code POST /cds-services/{id}}: the CDS Hooks service endpoints (HL7 CDS Hooks
 *       2.0 fixes these paths at the server root). M-006: the caller is a CDS client (the EHR) authenticated by the JWT
 *       it signs with its own key ({@code Authorization: Bearer}), verified by {@link CdsClientVerifier} against that
 *       client's registered JWKS ({@code iss} allow-list, {@code aud} = the URL of this endpoint, {@code exp}/{@code iat},
 *       {@code jti} replay). A platform login is not a CDS client. Without a registered client the routes refuse every
 *       call (401). This check applies to these two routes only; the other hooks keep their own checks.</li>
 * </ul>
 */
@RestController
public class HealthHooksController {

    static final String CDS_SERVICES = "/cds-services";
    private static final String BEARER = "Bearer ";

    private final HealthHooksPort hooks;
    private final org.sangam.rest.port.PermissionPort permissions;
    private final ObjectProvider<CdsClientVerifier> cdsClients;

    public HealthHooksController(HealthHooksPort hooks, org.sangam.rest.port.PermissionPort permissions,
                                 ObjectProvider<CdsClientVerifier> cdsClients) {
        this.hooks = hooks;
        this.permissions = permissions;
        this.cdsClients = cdsClients;
    }

    /** An authenticated hand-over of a received 835 (an SFTP collector or an operator). Always the caller's tenant. */
    @PostMapping(value = "/api/v1/connectors-health/x12/835", consumes = {"application/edi-x12", MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<HealthHooksPort.RemittanceResult> remittance835(org.sangam.rest.port.RequestContext ctx, @RequestBody String x12) {
        permissions.require(ctx, org.sangam.rest.support.Permissions.OPS_WRITE);
        HealthHooksPort.RemittanceResult r = hooks.remittance835(ctx.tenantId(), ctx.principalId(), x12);
        return ResponseEntity.status(r.poisonItemId() != null ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.ACCEPTED).body(r);
    }

    @PostMapping(value = "/api/v1/hooks/fhir/by-channel", consumes = {"application/fhir+json", "application/json"})
    public ResponseEntity<Map<String, Object>> fhirNotification(@RequestParam("channel") String channel,
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
        cdsClient(request, CDS_SERVICES);
        HealthHooksPort.CdsReply r = hooks.cdsDiscovery();
        return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.json());
    }

    @PostMapping(value = "/cds-services/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> cdsCall(@PathVariable String id, @RequestBody byte[] body, HttpServletRequest request) {
        HealthHooksPort.CdsReply r = hooks.cdsCall(id, body, cdsClient(request, CDS_SERVICES + "/" + id));
        return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.json());
    }

    /** The verified CDS client ({@code iss}) of this call, or 401. */
    private String cdsClient(HttpServletRequest request, String endpointPath) {
        CdsClientVerifier verifier = cdsClients.getIfAvailable();
        if (verifier == null) {
            throw new ApiProblem(401, "CDS_CLIENT_NOT_CONFIGURED",
                    "no CDS Hooks client is registered (sangam.connectors-health.cds-service.clients)");
        }
        String header = request.getHeader("Authorization");
        if (header == null || header.length() <= BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new ApiProblem(401, "UNAUTHENTICATED", "CDS Hooks calls need the client's signed JWT (Authorization: Bearer)");
        }
        try {
            return verifier.verify(header.substring(BEARER.length()).trim(), endpointPath);
        } catch (CdsClientVerifier.Rejected e) {
            throw new ApiProblem(401, "CDS_CLIENT_REJECTED", e.getMessage()).with("reason", e.reason());
        }
    }
}
