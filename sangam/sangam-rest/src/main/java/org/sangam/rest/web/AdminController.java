// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.time.LocalDate;
import java.util.List;

import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administration: org units, seats, assignments, delegations (IX.5), policies (VI.4), tenants (EC-17). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminPort admin;
    private final PermissionPort permissions;

    public AdminController(AdminPort admin, PermissionPort permissions) {
        this.admin = admin;
        this.permissions = permissions;
    }

    public record AdminQuery(String q, String parentId, String orgUnitId, String seatId, String personId, String kind,
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate activeOn,
                             Boolean includeInactive, Integer page, Integer size) {
        AdminPort.AdminFilter filter() {
            return new AdminPort.AdminFilter(q, parentId, orgUnitId, seatId, personId, kind, activeOn, includeInactive);
        }
    }

    public record EndAssignmentRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validTo, String reason) {
    }

    public record ReasonRequest(String reason) {
    }

    // ---------------------------------------------------------------- org units

    @GetMapping("/org-units")
    public ResponseEntity<List<AdminPort.OrgUnit>> orgUnits(RequestContext ctx, @ModelAttribute AdminQuery q) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(admin.orgUnits(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @PostMapping("/org-units")
    public ResponseEntity<AdminPort.OrgUnit> createOrgUnit(RequestContext ctx, @RequestBody AdminPort.OrgUnit body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        AdminPort.OrgUnit o = admin.createOrgUnit(ctx, body);
        return Lists.created(byId("/api/v1/admin/org-units", o.id()), o.id(), o);
    }

    @GetMapping("/org-units/by-id")
    public AdminPort.OrgUnit orgUnit(RequestContext ctx, @RequestParam("id") String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return admin.orgUnit(ctx, id);
    }

    @PutMapping("/org-units/by-id")
    public ResponseEntity<AdminPort.OrgUnit> updateOrgUnit(RequestContext ctx, @RequestParam("id") String id,
                                                           @RequestBody AdminPort.OrgUnit body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return Lists.okRef(id, admin.updateOrgUnit(ctx, id, body));
    }

    @DeleteMapping("/org-units/by-id")
    public ResponseEntity<Void> deleteOrgUnit(RequestContext ctx, @RequestParam("id") String id,
                                              @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        ProcessController.requireReason(reason);
        admin.deleteOrgUnit(ctx, id, reason);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- org levels (EV-M-0923)

    @GetMapping("/org-levels")
    public AdminPort.OrgLevels orgLevels(RequestContext ctx) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return admin.orgLevels(ctx);
    }

    /** The caller's own tenant's level types, top first; idempotent (the same list again changes nothing). */
    @PutMapping("/org-levels")
    public AdminPort.OrgLevels defineOrgLevels(RequestContext ctx, @RequestBody AdminPort.OrgLevels body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return admin.defineOrgLevels(ctx, body);
    }

    // ---------------------------------------------------------------- seats

    @GetMapping("/seats")
    public ResponseEntity<List<AdminPort.Seat>> seats(RequestContext ctx, @ModelAttribute AdminQuery q) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(admin.seats(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @PostMapping("/seats")
    public ResponseEntity<AdminPort.Seat> createSeat(RequestContext ctx, @RequestBody AdminPort.Seat body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        AdminPort.Seat s = admin.createSeat(ctx, body);
        return Lists.created(byId("/api/v1/admin/seats", s.id()), s.id(), s);
    }

    @GetMapping("/seats/by-id")
    public AdminPort.Seat seat(RequestContext ctx, @RequestParam("id") String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return admin.seat(ctx, id);
    }

    @PutMapping("/seats/by-id")
    public ResponseEntity<AdminPort.Seat> updateSeat(RequestContext ctx, @RequestParam("id") String id,
                                                     @RequestBody AdminPort.Seat body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return Lists.okRef(id, admin.updateSeat(ctx, id, body));
    }

    @DeleteMapping("/seats/by-id")
    public ResponseEntity<Void> deleteSeat(RequestContext ctx, @RequestParam("id") String id,
                                           @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        ProcessController.requireReason(reason);
        admin.deleteSeat(ctx, id, reason);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- assignments

    @GetMapping("/assignments")
    public ResponseEntity<List<AdminPort.Assignment>> assignments(RequestContext ctx, @ModelAttribute AdminQuery q) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(admin.assignments(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @PostMapping("/assignments")
    public ResponseEntity<AdminPort.Assignment> createAssignment(RequestContext ctx,
                                                                 @RequestBody AdminPort.Assignment body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        AdminPort.Assignment a = admin.createAssignment(ctx, body);
        return Lists.created("/api/v1/admin/assignments/" + a.id(), a.id(), a);
    }

    @GetMapping("/assignments/{id}")
    public AdminPort.Assignment assignment(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return admin.assignment(ctx, id);
    }

    @PostMapping("/assignments/{id}/end")
    public ResponseEntity<AdminPort.Assignment> endAssignment(RequestContext ctx, @PathVariable String id,
                                                              @RequestBody EndAssignmentRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, admin.endAssignment(ctx, id, body.validTo(), body.reason()));
    }

    // ---------------------------------------------------------------- delegations

    @GetMapping("/delegations")
    public ResponseEntity<List<AdminPort.Delegation>> delegations(RequestContext ctx, @ModelAttribute AdminQuery q) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(admin.delegations(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @PostMapping("/delegations")
    public ResponseEntity<AdminPort.Delegation> createDelegation(RequestContext ctx,
                                                                 @RequestBody AdminPort.Delegation body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        AdminPort.Delegation d = admin.createDelegation(ctx, body);
        return Lists.created("/api/v1/admin/delegations/" + d.id(), d.id(), d);
    }

    @GetMapping("/delegations/{id}")
    public AdminPort.Delegation delegation(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return admin.delegation(ctx, id);
    }

    @PostMapping("/delegations/{id}/revoke")
    public ResponseEntity<AdminPort.Delegation> revokeDelegation(RequestContext ctx, @PathVariable String id,
                                                                 @RequestBody ReasonRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(id, admin.revokeDelegation(ctx, id, body.reason()));
    }

    // ---------------------------------------------------------------- policies

    @GetMapping("/policies")
    public ResponseEntity<List<AdminPort.Policy>> policies(RequestContext ctx, @ModelAttribute AdminQuery q) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        return Lists.ok(admin.policies(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @PostMapping("/policies")
    public ResponseEntity<AdminPort.Policy> createPolicy(RequestContext ctx, @RequestBody AdminPort.Policy body) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        AdminPort.Policy p = admin.createPolicy(ctx, body);
        return Lists.created(byId("/api/v1/admin/policies", p.id()), p.id(), p);
    }

    @DeleteMapping("/policies/by-id")
    public ResponseEntity<Void> deletePolicy(RequestContext ctx, @RequestParam("id") String id,
                                             @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        ProcessController.requireReason(reason);
        admin.deletePolicy(ctx, id, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/policies/version")
    public AdminPort.PolicyVersion policyVersion(RequestContext ctx) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return admin.policyVersion(ctx);
    }

    // ---------------------------------------------------------------- four-eyes policy changes (E-33, EV-M-0968)

    /** The caller's tenant's policy changes, newest request first; {@code status} PENDING (the default) is the queue. */
    @GetMapping("/policies/changes")
    public ResponseEntity<List<AdminPort.PolicyChange>> policyChanges(RequestContext ctx,
                                                                      @RequestParam(required = false) String status,
                                                                      @RequestParam(required = false) Integer page,
                                                                      @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        return Lists.ok(admin.policyChanges(ctx, status, Lists.page(page, size)));
    }

    @GetMapping("/policies/changes/{id}")
    public AdminPort.PolicyChange policyChange(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        return admin.policyChange(ctx, id);
    }

    /** Four-eyes approve: applied in the same transaction; the requester and a system identity never decide. */
    @PostMapping("/policies/changes/{id}/approve")
    public ResponseEntity<AdminPort.PolicyChange> approvePolicyChange(RequestContext ctx, @PathVariable String id,
                                                                      @RequestBody ReasonRequest body) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(id, admin.decidePolicyChange(ctx, id, true, body.reason()));
    }

    /** Four-eyes reject: nothing applied; the requester cannot reject (no self-reject, no withdraw). */
    @PostMapping("/policies/changes/{id}/reject")
    public ResponseEntity<AdminPort.PolicyChange> rejectPolicyChange(RequestContext ctx, @PathVariable String id,
                                                                     @RequestBody ReasonRequest body) {
        permissions.require(ctx, Permissions.POLICY_ADMIN);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(id, admin.decidePolicyChange(ctx, id, false, body.reason()));
    }

    // ---------------------------------------------------------------- tenants

    @GetMapping("/tenants")
    public ResponseEntity<List<AdminPort.Tenant>> tenants(RequestContext ctx, @ModelAttribute AdminQuery q) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return Lists.ok(admin.tenants(ctx, q.filter(), Lists.page(q.page(), q.size())));
    }

    @PostMapping("/tenants")
    public ResponseEntity<AdminPort.Tenant> createTenant(RequestContext ctx, @RequestBody AdminPort.Tenant body) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        AdminPort.Tenant t = admin.createTenant(ctx, body);
        return Lists.created("/api/v1/admin/tenants/" + t.id(), t.id(), t);
    }

    @GetMapping("/tenants/{id}")
    public AdminPort.Tenant tenant(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return admin.tenant(ctx, id);
    }

    @PutMapping("/tenants/{id}")
    public ResponseEntity<AdminPort.Tenant> updateTenant(RequestContext ctx, @PathVariable String id,
                                                         @RequestBody AdminPort.Tenant body) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        return Lists.okRef(id, admin.updateTenant(ctx, id, body));
    }

    /** A dry run of the activation check (AJ-01 step 10): required go-live items and those without evidence. */
    @PostMapping("/tenants/{id}/validate")
    public AdminPort.TenantReadiness validateTenant(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        return admin.validateTenant(ctx, id);
    }

    @PostMapping("/tenants/{id}/activate")
    public ResponseEntity<AdminPort.Tenant> activateTenant(RequestContext ctx, @PathVariable String id,
                                                           @RequestBody(required = false) ReasonRequest body) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        return Lists.okRef(id, admin.activateTenant(ctx, id, body == null ? null : body.reason()));
    }

    @PostMapping("/tenants/{id}/suspend")
    public ResponseEntity<AdminPort.Tenant> suspendTenant(RequestContext ctx, @PathVariable String id,
                                                          @RequestBody ReasonRequest body) {
        permissions.require(ctx, Permissions.TENANT_ADMIN);
        ProcessController.requireReason(body == null ? null : body.reason());
        return Lists.okRef(id, admin.suspendTenant(ctx, id, body.reason()));
    }

    /** Decision 393: the Location of a created item whose client-chosen id may hold '/' or ':'. */
    static String byId(String base, String id) {
        return base + "/by-id?id=" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8);
    }
}
