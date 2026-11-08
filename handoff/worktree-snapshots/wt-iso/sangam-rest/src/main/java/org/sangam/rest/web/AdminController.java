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
        return Lists.created("/api/v1/admin/org-units/" + o.id(), o.id(), o);
    }

    @GetMapping("/org-units/{id}")
    public AdminPort.OrgUnit orgUnit(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return admin.orgUnit(ctx, id);
    }

    @PutMapping("/org-units/{id}")
    public ResponseEntity<AdminPort.OrgUnit> updateOrgUnit(RequestContext ctx, @PathVariable String id,
                                                           @RequestBody AdminPort.OrgUnit body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return Lists.okRef(id, admin.updateOrgUnit(ctx, id, body));
    }

    @DeleteMapping("/org-units/{id}")
    public ResponseEntity<Void> deleteOrgUnit(RequestContext ctx, @PathVariable String id,
                                              @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        ProcessController.requireReason(reason);
        admin.deleteOrgUnit(ctx, id, reason);
        return ResponseEntity.noContent().build();
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
        return Lists.created("/api/v1/admin/seats/" + s.id(), s.id(), s);
    }

    @GetMapping("/seats/{id}")
    public AdminPort.Seat seat(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        return admin.seat(ctx, id);
    }

    @PutMapping("/seats/{id}")
    public ResponseEntity<AdminPort.Seat> updateSeat(RequestContext ctx, @PathVariable String id,
                                                     @RequestBody AdminPort.Seat body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        return Lists.okRef(id, admin.updateSeat(ctx, id, body));
    }

    @DeleteMapping("/seats/{id}")
    public ResponseEntity<Void> deleteSeat(RequestContext ctx, @PathVariable String id,
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
        return Lists.created("/api/v1/admin/policies/" + p.id(), p.id(), p);
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> deletePolicy(RequestContext ctx, @PathVariable String id,
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
}
