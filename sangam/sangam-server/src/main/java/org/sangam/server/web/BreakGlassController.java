// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.web.Lists;
import org.sangam.security.SangamSecurity;
import org.sangam.security.breakglass.BreakGlassService;
import org.sangam.server.bridge.SecurityIdentities;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-084 break-glass grants ({@code /api/v1/admin/security/break-glass/grants}, contract
 * {@code sangam-rest/api/routes/security_break_glass.py}). Coarse permission {@code admin.read}/{@code admin.write}, then
 * the service's object check (ADMIN or READ on {@code API_TOKEN}), dual control, the tenant-config token classes and
 * the audit ({@link BreakGlassService}). Approve answers 200 with the grant while approvals are missing, and 201 with
 * the token, shown once, when this approval issues it.
 */
@RestController
@RequestMapping("/api/v1/admin/security/break-glass/grants")
public class BreakGlassController {

    public record BreakGlassRequest(String tokenClass, String holderPersonId, String reason) {
    }

    public record ReasonRequest(String reason) {
    }

    public record BreakGlassReview(String outcome, String notes) {
    }

    public record BreakGlassIssued(Map<String, Object> grant, String token, Instant expiresAt) {
    }

    private final ObjectProvider<SangamSecurity> security;
    private final ObjectProvider<DataSource> dataSource;
    private final PermissionPort permissions;

    public BreakGlassController(ObjectProvider<SangamSecurity> security, ObjectProvider<DataSource> dataSource,
                                PermissionPort permissions) {
        this.security = security;
        this.dataSource = dataSource;
        this.permissions = permissions;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> request(RequestContext ctx, @RequestBody(required = false) BreakGlassRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        if (body == null || blank(body.tokenClass()) || blank(body.holderPersonId()) || blank(body.reason())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "tokenClass, holderPersonId and reason are required");
        }
        BreakGlassService.Grant g = tx((s, c) -> s.request(c, SecurityIdentities.of(ctx), body.tokenClass(),
                body.holderPersonId(), body.reason()));
        Map<String, Object> out = view(g);
        return Lists.created("/api/v1/admin/security/break-glass/grants/" + g.grantId(), g.grantId(), out);
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(RequestContext ctx, @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String reviewStatus,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.ADMIN_READ);
        Page.PageRequest p = Lists.page(page, size);
        Page<Map<String, Object>> out = tx((s, c) -> {
            List<BreakGlassService.Grant> items = s.list(c, SecurityIdentities.of(ctx), status, reviewStatus,
                    p.firstResult(), p.size());
            long total = s.count(c, SecurityIdentities.of(ctx), status, reviewStatus);
            return Page.of(items.stream().map(BreakGlassController::view).toList(), total, false);
        });
        return Lists.ok(out);
    }

    @PostMapping("/{grantId}/approve")
    public ResponseEntity<Object> approve(RequestContext ctx, @PathVariable String grantId) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        BreakGlassService.Approval a = tx((s, c) -> s.approve(c, SecurityIdentities.of(ctx), grantId));
        if (a.token().isEmpty()) {
            return ResponseEntity.ok(view(a.grant()));
        }
        return ResponseEntity.status(201)
                .body(new BreakGlassIssued(view(a.grant()), a.token().get().plaintext(), a.token().get().expiresAt()));
    }

    @PostMapping("/{grantId}/reject")
    public Map<String, Object> reject(RequestContext ctx, @PathVariable String grantId,
                                      @RequestBody(required = false) ReasonRequest body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        if (body == null || blank(body.reason())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "reason is required");
        }
        return view(tx((s, c) -> s.reject(c, SecurityIdentities.of(ctx), grantId, body.reason())));
    }

    @PostMapping("/{grantId}/review")
    public Map<String, Object> review(RequestContext ctx, @PathVariable String grantId,
                                      @RequestBody(required = false) BreakGlassReview body) {
        permissions.require(ctx, Permissions.ADMIN_WRITE);
        if (body == null || blank(body.outcome())) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "outcome is required");
        }
        return view(tx((s, c) -> s.review(c, SecurityIdentities.of(ctx), grantId, body.outcome(), body.notes())));
    }

    /** The contract's BreakGlassGrant (no token id: the token is shown once, at issue). */
    static Map<String, Object> view(BreakGlassService.Grant g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grantId", g.grantId());
        m.put("tokenClass", g.tokenClass());
        m.put("holderPersonId", g.holderPersonId());
        m.put("requestedBy", g.requestedBy());
        m.put("reason", g.reason());
        m.put("status", g.status());
        m.put("requiredApprovals", g.requiredApprovals());
        m.put("approvals", g.approvals().stream().map(p -> Map.of("personId", p)).toList());
        m.put("validMinutes", g.validMinutes());
        m.put("issuedAt", g.issuedAt());
        m.put("expiresAt", g.expiresAt());
        m.put("firstUsedAt", g.firstUsedAt());
        m.put("lastUsedAt", g.lastUsedAt());
        m.put("useCount", g.useCount());
        m.put("reviewStatus", g.reviewStatus());
        m.put("reviewedBy", g.reviewedBy());
        m.put("reviewOutcome", g.reviewOutcome());
        m.put("reviewedAt", g.reviewedAt());
        return m;
    }

    // ---------------------------------------------------------------- transaction plumbing

    @FunctionalInterface
    private interface Work<T> {
        T run(BreakGlassService service, java.sql.Connection c);
    }

    private <T> T tx(Work<T> work) {
        SangamSecurity sec = security.getIfAvailable();
        if (sec == null) {
            throw ApiProblem.notInstalled("break-glass access", "sangam-security");
        }
        try {
            return Transactions.of(dataSource.getObject()).inTransaction(c -> work.run(sec.breakGlass(), c));
        } catch (SangamSecurityException e) {
            throw new ApiProblem(e.httpStatus(), e.code(), e.getMessage());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
