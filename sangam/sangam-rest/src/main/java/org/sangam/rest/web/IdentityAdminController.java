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

    /**
     * A LOCAL user of the caller's tenant: {@code id} (else {@code username}) 1-64 characters, display name and e-mail at
     * most 255; a user is created active ({@code active=false} is refused: disable it afterwards, with a reason) and
     * LOCAL (SCIM and directory sync provision their own users).
     */
    @PostMapping("/users")
    public ResponseEntity<IdentityAdminPort.User> createUser(RequestContext ctx,
                                                             @RequestBody IdentityAdminPort.UserRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        if (body == null) {
            throw invalid("body", "a user is required");
        }
        String id = body.id() != null && !body.id().isBlank() ? body.id() : body.username();
        text("id", id, 64, true);
        text("displayName", body.displayName(), 255, false);
        text("email", body.email(), 255, false);
        if (body.source() != null && !"LOCAL".equals(body.source())) {
            throw invalid("source", "an administrator creates LOCAL users; " + body.source()
                    + " users are provisioned by their source");
        }
        if (Boolean.FALSE.equals(body.active())) {
            throw invalid("active", "a user is created active; disable it afterwards with a reason");
        }
        IdentityAdminPort.User u = identity.createUser(ctx, body);
        return Lists.created("/api/v1/admin/users/by-id?id=" + query(u.id()), u.id(), u);
    }

    /** Decision 393: a user id is an IdP principal id (may carry '/' or ':'), so it is a query parameter. */
    @PutMapping("/users/by-id")
    public ResponseEntity<IdentityAdminPort.User> updateUser(RequestContext ctx, @RequestParam("id") String id,
                                                             @RequestBody IdentityAdminPort.UserRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        if (body == null) {
            throw invalid("body", "a user is required");
        }
        text("displayName", body.displayName(), 255, false);
        text("email", body.email(), 255, false);
        return Lists.okRef(id, identity.updateUser(ctx, id, body));
    }

    @PostMapping("/users/by-id/disable")
    public ResponseEntity<IdentityAdminPort.User> disableUser(RequestContext ctx, @RequestParam("id") String id,
                                                              @RequestBody IdentityAdminPort.DecisionRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(id, identity.disableUser(ctx, id, body.reason()));
    }

    /** A LOCAL group: {@code id} and {@code name} 1-255 characters. */
    @PostMapping("/groups")
    public ResponseEntity<IdentityAdminPort.Group> createGroup(RequestContext ctx, @RequestBody IdentityAdminPort.Group body) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        if (body == null) {
            throw invalid("body", "a group is required");
        }
        text("id", body.id(), 255, true);
        text("name", body.name(), 255, true);
        if (body.source() != null && !"LOCAL".equals(body.source())) {
            throw invalid("source", "an administrator creates LOCAL groups; " + body.source()
                    + " groups are provisioned by their source");
        }
        IdentityAdminPort.Group g = identity.createGroup(ctx, body);
        return Lists.created("/api/v1/admin/groups/by-id?id=" + query(g.id()), g.id(), g);
    }

    /** Decision 393: a group id is an IdP group path (may carry '/'), so it is a query parameter. */
    @PutMapping("/groups/by-id")
    public ResponseEntity<IdentityAdminPort.Group> updateGroup(RequestContext ctx, @RequestParam("id") String id,
                                                               @RequestBody IdentityAdminPort.Group body) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        if (body == null) {
            throw invalid("body", "a group is required");
        }
        text("name", body.name(), 255, true);
        return Lists.okRef(id, identity.updateGroup(ctx, id, body));
    }

    private static void text(String field, String value, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(field, field + " is required");
            }
            return;
        }
        if (value.length() > max || !value.equals(value.strip())) {
            throw invalid(field, field + " must be at most " + max + " characters, without leading or trailing blanks");
        }
    }

    /** A value for a query parameter of a Location header (decision 393: ids may carry '/' or ':'). */
    private static String query(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static org.sangam.rest.port.ApiProblem invalid(String field, String detail) {
        return org.sangam.rest.port.ApiProblem.badRequest("VALIDATION_FAILED", detail).with("field", field);
    }

    @GetMapping("/groups/by-id/members")
    public ResponseEntity<List<IdentityAdminPort.User>> members(RequestContext ctx, @RequestParam("id") String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(identity.groupMembers(ctx, id));
    }

    @PostMapping("/groups/by-id/members/by-user-id")
    public ResponseEntity<Void> addMember(RequestContext ctx, @RequestParam("id") String id,
                                          @RequestParam("userId") String userId) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        identity.setMembership(ctx, id, userId, true);
        return ResponseEntity.noContent().header(Lists.RESOURCE_REF, id + "/" + userId).build();
    }

    @DeleteMapping("/groups/by-id/members/by-user-id")
    public ResponseEntity<Void> removeMember(RequestContext ctx, @RequestParam("id") String id,
                                             @RequestParam("userId") String userId) {
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

    // ---------------------------------------------------------------- roles (decision 368)

    /** Decision 393: ROLE_ID_PATTERN allows ':' (pack:clerk), so the role id is a query parameter. */
    @PutMapping("/roles/by-role-id")
    public ResponseEntity<IdentityAdminPort.RoleDefinition> defineRole(RequestContext ctx, @RequestParam("roleId") String roleId,
            @RequestBody IdentityAdminPort.RoleDefinition body) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        return Lists.okRef(roleId, identity.defineRole(ctx, roleId, body));
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
