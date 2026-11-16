// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import org.sangam.rest.port.IncidentActionPort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident skip and cancel (O01, R63), next to retry and resolve: the same permission ({@code ops.write}), a mandatory
 * reason and the same idempotency (an {@code Idempotency-Key} replays the first answer, command types
 * {@code ops.incident.skip} and {@code ops.incident.cancel} in the contract).
 */
@RestController
@RequestMapping("/api/v1/ops")
public class IncidentActionsController {

    private final IncidentActionPort incidents;
    private final PermissionPort permissions;

    public IncidentActionsController(IncidentActionPort incidents, PermissionPort permissions) {
        this.incidents = incidents;
        this.permissions = permissions;
    }

    @PostMapping("/incidents/{id}/skip")
    public ResponseEntity<OpsPort.Incident> skipIncident(RequestContext ctx, @PathVariable String id,
                                                         @RequestBody OpsPort.IncidentAction body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(id, incidents.skipIncident(ctx, id, body));
    }

    @PostMapping("/incidents/{id}/cancel")
    public ResponseEntity<OpsPort.Incident> cancelIncident(RequestContext ctx, @PathVariable String id,
                                                           @RequestBody OpsPort.IncidentAction body) {
        permissions.require(ctx, Permissions.OPS_WRITE);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(id, incidents.cancelIncident(ctx, id, body));
    }
}
