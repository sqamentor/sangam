// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.io.IOException;
import java.util.List;

import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.IdentityAdminPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Identity and authority administration (A01–A11, R50, R67, R26, IX.5 rules 4–5, VI.4). The literal paths
 * {@code /admin/assignments/impact} and {@code /admin/org-units/import} take precedence over the {@code {id}} routes.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class IdentityAdminController {

    private final IdentityAdminPort identity;
    private final PermissionPort permissions;

    public IdentityAdminController(IdentityAdminPort identity, PermissionPort permissions) {
        this.identity = identity;
        this.permissions = permissions;
    }

    // ---------------------------------------------------------------- assignments

    @PostMapping("/assignments/impact")
    public IdentityAdminPort.AssignmentImpact impact(RequestContext ctx, @RequestBody AdminPort.Assignment body) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return identity.assignmentImpact(ctx, body);
    }

    @PostMapping("/assignments/{id}/approve")
    public ResponseEntity<AdminPort.Assignment> approveAssignment(RequestContext ctx, @PathVariable String id,
                                                                  @RequestBody IdentityAdminPort.DecisionRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, identity.decideAssignment(ctx, id, true, body.reason()));
    }

    @PostMapping("/assignments/{id}/reject")
    public ResponseEntity<AdminPort.Assignment> rejectAssignment(RequestContext ctx, @PathVariable String id,
                                                                 @RequestBody IdentityAdminPort.DecisionRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, identity.decideAssignment(ctx, id, false, body.reason()));
    }

    // ---------------------------------------------------------------- policies and SoD

    @PostMapping("/policies/explain")
    public IdentityAdminPort.ExplainResult explain(RequestContext ctx, @RequestBody IdentityAdminPort.ExplainRequest body) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        return identity.explain(ctx, body);
    }

    @GetMapping("/sod-exceptions")
    public ResponseEntity<List<IdentityAdminPort.SodException>> sodExceptions(RequestContext ctx,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(identity.sodExceptions(ctx, status, Lists.page(page, size)));
    }

    @PostMapping("/sod-exceptions")
    public ResponseEntity<IdentityAdminPort.SodException> createSodException(RequestContext ctx,
            @RequestBody IdentityAdminPort.SodException body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        IdentityAdminPort.SodException e = identity.createSodException(ctx, body);
        return Lists.created("/api/v1/admin/sod-exceptions/" + e.id(), e.id(), e);
    }

    @PostMapping("/sod-exceptions/{id}/approve")
    public ResponseEntity<IdentityAdminPort.SodException> approveSod(RequestContext ctx, @PathVariable String id,
                                                                     @RequestBody IdentityAdminPort.DecisionRequest body) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, identity.decideSodException(ctx, id, true, body.reason()));
    }

    @PostMapping("/sod-exceptions/{id}/reject")
    public ResponseEntity<IdentityAdminPort.SodException> rejectSod(RequestContext ctx, @PathVariable String id,
                                                                    @RequestBody IdentityAdminPort.DecisionRequest body) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, identity.decideSodException(ctx, id, false, body.reason()));
    }

    // ---------------------------------------------------------------- users and groups

    @GetMapping("/users")
    public ResponseEntity<List<IdentityAdminPort.User>> users(RequestContext ctx, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(identity.users(ctx, q, Lists.page(page, size)));
    }

    @GetMapping("/groups")
    public ResponseEntity<List<IdentityAdminPort.Group>> groups(RequestContext ctx, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(identity.groups(ctx, q, Lists.page(page, size)));
    }

    @GetMapping("/groups/{id}/members")
    public ResponseEntity<List<IdentityAdminPort.User>> members(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(identity.groupMembers(ctx, id));
    }

    @PostMapping("/groups/{id}/members/{userId}")
    public ResponseEntity<Void> addMember(RequestContext ctx, @PathVariable String id, @PathVariable String userId) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        identity.setMembership(ctx, id, userId, true);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id + "/" + userId).build();
    }

    @DeleteMapping("/groups/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(RequestContext ctx, @PathVariable String id, @PathVariable String userId) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        identity.setMembership(ctx, id, userId, false);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id + "/" + userId).build();
    }

    // ---------------------------------------------------------------- identity providers

    @GetMapping("/identity-providers")
    public ResponseEntity<List<IdentityAdminPort.IdentityProvider>> identityProviders(RequestContext ctx) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        return Lists.ok(identity.identityProviders(ctx));
    }

    @PostMapping("/identity-providers")
    public ResponseEntity<IdentityAdminPort.IdentityProvider> createIdp(RequestContext ctx,
            @RequestBody IdentityAdminPort.IdentityProvider body) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        IdentityAdminPort.IdentityProvider p = identity.createIdentityProvider(ctx, body);
        return Lists.created("/api/v1/admin/identity-providers/" + p.id(), p.id(), p);
    }

    @PutMapping("/identity-providers/{id}")
    public ResponseEntity<IdentityAdminPort.IdentityProvider> updateIdp(RequestContext ctx, @PathVariable String id,
            @RequestBody IdentityAdminPort.IdentityProvider body) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        return Lists.okRef(id, identity.updateIdentityProvider(ctx, id, body));
    }

    @PostMapping("/identity-providers/{id}/test")
    public IdentityAdminPort.TestResult testIdp(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        return identity.testIdentityProvider(ctx, id);
    }

    @PostMapping("/identity-providers/{id}/sync")
    public ResponseEntity<IdentityAdminPort.SyncResult> syncIdp(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        return Lists.okRef(id, identity.syncIdentityProvider(ctx, id));
    }

    // ---------------------------------------------------------------- service principals (R26)

    @GetMapping("/service-principals")
    public ResponseEntity<List<IdentityAdminPort.ServicePrincipal>> servicePrincipals(RequestContext ctx) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(identity.servicePrincipals(ctx));
    }

    @PostMapping("/service-principals")
    public ResponseEntity<IdentityAdminPort.ServicePrincipalSecret> createServicePrincipal(RequestContext ctx,
            @RequestBody IdentityAdminPort.ServicePrincipal body) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        IdentityAdminPort.ServicePrincipalSecret s = identity.createServicePrincipal(ctx, body);
        return Lists.created("/api/v1/admin/service-principals/" + s.principal().id(), s.principal().id(), s);
    }

    @PostMapping("/service-principals/{id}/rotate")
    public ResponseEntity<IdentityAdminPort.ServicePrincipalSecret> rotate(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        return Lists.okRef(id, identity.rotateServicePrincipal(ctx, id));
    }

    @PostMapping("/service-principals/{id}/revoke")
    public ResponseEntity<IdentityAdminPort.ServicePrincipal> revoke(RequestContext ctx, @PathVariable String id,
                                                                     @RequestBody IdentityAdminPort.DecisionRequest body) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, identity.revokeServicePrincipal(ctx, id, body.reason()));
    }

    // ---------------------------------------------------------------- org-unit import

    @PostMapping(value = "/org-units/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IdentityAdminPort.ImportResult importOrgUnits(RequestContext ctx, @RequestPart("file") MultipartFile file)
            throws IOException {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return identity.importOrgUnits(ctx, file.getOriginalFilename(), file.getBytes());
    }
}
