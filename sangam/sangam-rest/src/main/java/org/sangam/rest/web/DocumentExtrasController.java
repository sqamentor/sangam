// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DocumentExtrasPort;
import org.sangam.rest.port.DocumentPort;
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

/**
 * Document versions, legal hold (R61) and template generation (R74, EC-18). The literal paths
 * {@code /documents/templates} and {@code /documents/generate} win over {@code /documents/{id}}.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentExtrasController {

    private final DocumentExtrasPort extras;
    private final PermissionPort permissions;

    public DocumentExtrasController(DocumentExtrasPort extras, PermissionPort permissions) {
        this.extras = extras;
        this.permissions = permissions;
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<DocumentPort.Document>> versions(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.DOCUMENT_READ);
        return Lists.ok(extras.versions(ctx, id));
    }

    @PostMapping("/{id}/legal-hold")
    public ResponseEntity<DocumentPort.Document> legalHold(RequestContext ctx, @PathVariable String id,
                                                           @RequestBody DocumentExtrasPort.LegalHoldRequest body) {
        permissions.require(ctx, Permissions.DOCUMENT_LEGAL_HOLD);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(id, extras.setLegalHold(ctx, id, body));
    }

    @GetMapping("/templates")
    public ResponseEntity<List<DocumentExtrasPort.DocumentTemplate>> templates(RequestContext ctx) {
        permissions.require(ctx, Permissions.DOCUMENT_READ);
        return Lists.ok(extras.templates(ctx));
    }

    @PostMapping("/generate")
    public ResponseEntity<DocumentPort.Document> generate(RequestContext ctx,
                                                          @RequestBody DocumentExtrasPort.GenerateRequest body) {
        permissions.require(ctx, Permissions.DOCUMENT_WRITE);
        if (body == null || body.templateId() == null || body.businessKey() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "templateId and businessKey are required");
        }
        Telemetry.businessKey(body.businessKey());
        DocumentPort.Document d = extras.generate(ctx, body);
        return Lists.created("/api/v1/documents/" + d.id(), d.id(), d);
    }
}
