// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.sangam.integration.dsc.DscCertificateEnrolments.Enrolment;
import org.sangam.integration.dsc.DscCertificateEnrolments.Status;
import org.sangam.integration.dsc.DscEnrolment;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.web.Lists;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner decision 227: DSC certificate enrolment. A signer enrols the certificate of their token; an authorised admin,
 * never the signer, approves or rejects it; the signer or an admin revokes it. Every change writes an audit record in
 * the same transaction (chain {@code dsc-certificate:<sha256>}). DSC prepare and complete accept only the caller's
 * APPROVED certificate. Without the opt-in wiring ({@code sangam.dsc.enabled}) every route answers 501.
 *
 * <ul>
 *   <li>{@code POST /api/v1/me/dsc/certificates} {@code {certificate}} (Base64 DER): a PENDING enrolment; the server
 *       derives fingerprint, issuer and serial from the DER.</li>
 *   <li>{@code GET /api/v1/me/dsc/certificates}; {@code POST /api/v1/me/dsc/certificates/{enrolmentId}/revoke}
 *       {@code {reason}}.</li>
 *   <li>{@code GET /api/v1/admin/dsc/certificates?status=PENDING}, {@code GET .../{enrolmentId}},
 *       {@code POST .../{enrolmentId}/approve} {@code {reason?}}, {@code .../reject} and {@code .../revoke}
 *       {@code {reason}}: permission {@value #APPROVE_PERMISSION}.</li>
 * </ul>
 */
@RestController
public class DscCertificateController {

    /**
     * The permission an admin needs to decide another user's enrolment. Requested from W4-B (board REQUEST
     * 2026-11-13T01:36:18Z); until it is registered and granted, no one holds it and approval stays closed.
     */
    public static final String APPROVE_PERMISSION = "dsc.certificate.approve";

    static final String ENROLLED = "DSC_CERTIFICATE_ENROLLED";
    static final String APPROVED = "DSC_CERTIFICATE_APPROVED";
    static final String REJECTED = "DSC_CERTIFICATE_REJECTED";
    static final String REVOKED = "DSC_CERTIFICATE_REVOKED";

    public record EnrolRequest(String certificate) {
    }

    public record DecisionRequest(String reason) {
    }

    public record CertificateView(String enrolmentId, String principalId, String certificateSha256, String issuer,
                                  String serialHex, String subject, Instant notBefore, Instant notAfter, String status,
                                  Instant enrolledAt, String decidedBy, Instant decidedAt, String decisionReason) {

        static CertificateView of(Enrolment e) {
            return new CertificateView(e.enrolmentId(), e.principalId(), e.certificateSha256(), e.issuer(),
                    e.serialHex(), e.subject(), e.notBefore(), e.notAfter(), e.status().name(), e.enrolledAt(),
                    e.decidedBy(), e.decidedAt(), e.decisionReason());
        }
    }

    private final ObjectProvider<DscEnrolment> enrolments;
    private final TxSupport tx;
    private final AuditPort audit;
    private final PermissionPort permissions;

    public DscCertificateController(ObjectProvider<DscEnrolment> enrolments, TxSupport tx, AuditPort audit,
                                    PermissionPort permissions) {
        this.enrolments = enrolments;
        this.tx = tx;
        this.audit = audit;
        this.permissions = permissions;
    }

    @PostMapping("/api/v1/me/dsc/certificates")
    public ResponseEntity<CertificateView> enrol(RequestContext ctx, @RequestBody(required = false) EnrolRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        DscEnrolment s = service();
        if (body == null || body.certificate() == null || body.certificate().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "certificate (Base64 DER) is required");
        }
        Enrolment e = audited(ctx, ENROLLED, () -> s.enrol(ctx.tenantId(), ctx.principalId(), body.certificate()));
        return Lists.created("/api/v1/me/dsc/certificates/" + e.enrolmentId(), e.enrolmentId(), CertificateView.of(e));
    }

    @GetMapping("/api/v1/me/dsc/certificates")
    public ResponseEntity<List<CertificateView>> mine(RequestContext ctx) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        return Lists.ok(service().mine(ctx.tenantId(), ctx.principalId()).stream().map(CertificateView::of).toList());
    }

    @PostMapping("/api/v1/me/dsc/certificates/{enrolmentId}/revoke")
    public ResponseEntity<CertificateView> revokeMine(RequestContext ctx, @PathVariable String enrolmentId,
                                                      @RequestBody(required = false) DecisionRequest body) {
        permissions.require(ctx, Permissions.TASK_WRITE);
        DscEnrolment s = service();
        Enrolment e = audited(ctx, REVOKED, () -> s.revoke(ctx.tenantId(), enrolmentId, ctx.principalId(), false,
                reason(body)));
        return Lists.okRef(e.enrolmentId(), CertificateView.of(e));
    }

    @GetMapping("/api/v1/admin/dsc/certificates")
    public ResponseEntity<List<CertificateView>> list(RequestContext ctx,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) Integer limit) {
        permissions.require(ctx, APPROVE_PERMISSION);
        DscEnrolment s = service();
        Status st;
        try {
            st = status == null || status.isBlank() ? Status.PENDING : Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "status is PENDING, APPROVED, REJECTED or REVOKED");
        }
        if (limit != null && (limit < 1 || limit > DscEnrolment.MAX_LIST)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "limit is 1 to " + DscEnrolment.MAX_LIST);
        }
        return Lists.ok(s.withStatus(ctx.tenantId(), st, limit == null ? DscEnrolment.MAX_LIST : limit).stream()
                .map(CertificateView::of).toList());
    }

    @GetMapping("/api/v1/admin/dsc/certificates/{enrolmentId}")
    public ResponseEntity<CertificateView> get(RequestContext ctx, @PathVariable String enrolmentId) {
        permissions.require(ctx, APPROVE_PERMISSION);
        DscEnrolment s = service();
        return Lists.okRef(enrolmentId, CertificateView.of(refusals(() -> s.get(ctx.tenantId(), enrolmentId))));
    }

    @PostMapping("/api/v1/admin/dsc/certificates/{enrolmentId}/approve")
    public ResponseEntity<CertificateView> approve(RequestContext ctx, @PathVariable String enrolmentId,
                                                   @RequestBody(required = false) DecisionRequest body) {
        permissions.require(ctx, APPROVE_PERMISSION);
        DscEnrolment s = service();
        Enrolment e = audited(ctx, APPROVED, () -> s.approve(ctx.tenantId(), enrolmentId, ctx.principalId(),
                reason(body)));
        return Lists.okRef(e.enrolmentId(), CertificateView.of(e));
    }

    @PostMapping("/api/v1/admin/dsc/certificates/{enrolmentId}/reject")
    public ResponseEntity<CertificateView> reject(RequestContext ctx, @PathVariable String enrolmentId,
                                                  @RequestBody(required = false) DecisionRequest body) {
        permissions.require(ctx, APPROVE_PERMISSION);
        DscEnrolment s = service();
        Enrolment e = audited(ctx, REJECTED, () -> s.reject(ctx.tenantId(), enrolmentId, ctx.principalId(),
                reason(body)));
        return Lists.okRef(e.enrolmentId(), CertificateView.of(e));
    }

    @PostMapping("/api/v1/admin/dsc/certificates/{enrolmentId}/revoke")
    public ResponseEntity<CertificateView> revoke(RequestContext ctx, @PathVariable String enrolmentId,
                                                  @RequestBody(required = false) DecisionRequest body) {
        permissions.require(ctx, APPROVE_PERMISSION);
        DscEnrolment s = service();
        Enrolment e = audited(ctx, REVOKED, () -> s.revoke(ctx.tenantId(), enrolmentId, ctx.principalId(), true,
                reason(body)));
        return Lists.okRef(e.enrolmentId(), CertificateView.of(e));
    }

    /** The change and its audit record in one transaction: no decision without its record, no record without it. */
    private Enrolment audited(RequestContext ctx, String event, Supplier<Enrolment> change) {
        return refusals(() -> tx.inTx(() -> {
            Enrolment e = change.get();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("enrolment_id", e.enrolmentId());
            p.put("principal_id", e.principalId());
            p.put("certificate_sha256", e.certificateSha256());
            p.put("issuer", e.issuer());
            p.put("serial", e.serialHex());
            p.put("subject_shown", e.subject());
            p.put("status", e.status().name());
            p.put("reason", e.decisionReason());
            p.put("actor", ctx.actor());
            p.values().removeIf(Objects::isNull);
            audit.record(ctx, "dsc-certificate:" + e.certificateSha256(), event, p);
            return e;
        }));
    }

    private static <T> T refusals(Supplier<T> work) {
        try {
            return work.get();
        } catch (DscEnrolment.Refused r) {
            throw switch (r.kind()) {
                case NOT_FOUND -> new ApiProblem(404, r.code(), r.getMessage());
                case FORBIDDEN -> ApiProblem.forbidden(r.code(), r.getMessage());
                case CONFLICT -> ApiProblem.conflict(r.code(), r.getMessage());
                case INVALID -> ApiProblem.unprocessable(r.code(), r.getMessage());
            };
        }
    }

    private DscEnrolment service() {
        DscEnrolment s = enrolments.getIfAvailable();
        if (s == null) {
            throw ApiProblem.notInstalled("DSC certificate enrolment", "sangam-esign");
        }
        return s;
    }

    private static String reason(DecisionRequest body) {
        return body == null ? null : body.reason();
    }
}
