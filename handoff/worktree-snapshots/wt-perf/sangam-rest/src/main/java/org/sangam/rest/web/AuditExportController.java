// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.rest.port.AuditExportPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Asynchronous audit exports and evidence packs (R42, U02/U03). Every export is itself audited. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditExportController {

    private final AuditExportPort exports;
    private final PermissionPort permissions;

    public AuditExportController(AuditExportPort exports, PermissionPort permissions) {
        this.exports = exports;
        this.permissions = permissions;
    }

    @PostMapping("/exports")
    public ResponseEntity<AuditExportPort.ExportJob> create(RequestContext ctx,
                                                            @RequestBody AuditExportPort.ExportRequest body) {
        permissions.require(ctx, Permissions.AUDIT_EXPORT);
        AuditExportPort.ExportJob job = exports.create(ctx, body);
        return Lists.accepted(job.id(), job);
    }

    @GetMapping("/exports")
    public ResponseEntity<List<AuditExportPort.ExportJob>> list(RequestContext ctx) {
        permissions.require(ctx, Permissions.AUDIT_EXPORT);
        return Lists.ok(exports.list(ctx));
    }

    @GetMapping("/exports/{id}/content")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> content(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.AUDIT_EXPORT);
        return DocumentController.download(exports.content(ctx, id));
    }

    @GetMapping("/{businessKey}/evidence-pack")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> evidencePack(RequestContext ctx, @PathVariable String businessKey) {
        permissions.require(ctx, Permissions.AUDIT_EXPORT);
        Telemetry.businessKey(businessKey);
        return DocumentController.download(exports.evidencePack(ctx, businessKey));
    }
}
