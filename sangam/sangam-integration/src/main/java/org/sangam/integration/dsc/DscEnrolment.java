// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import org.sangam.integration.dsc.DscCertificateEnrolments.Enrolment;
import org.sangam.integration.dsc.DscCertificateEnrolments.Status;
import org.sangam.integration.signature.DscSigning;

/**
 * Owner decision 227: DSC certificate enrolment. A signer enrols the certificate of their token; the server derives the
 * SHA-256 fingerprint, issuer and serial from the DER itself (nothing the client says about it is used). An authorised
 * admin, never the signer, approves or rejects it; the signer or an admin can revoke it. Signing accepts only the
 * signer's own APPROVED certificate ({@link #approvedFor}). The display name and the certificate's common name are shown,
 * never trusted. The REST layer checks permissions and writes the audit record in the same transaction.
 */
public final class DscEnrolment {

    public static final int MAX_LIST = 200;
    static final int MAX_REASON = 512;

    /** Why a request is refused; {@link #kind} tells the REST layer which status to answer. */
    public static final class Refused extends RuntimeException {

        public enum Kind { INVALID, NOT_FOUND, FORBIDDEN, CONFLICT }

        private final Kind kind;
        private final String code;

        public Refused(Kind kind, String code, String message) {
            super(message);
            this.kind = kind;
            this.code = code;
        }

        public Kind kind() {
            return kind;
        }

        public String code() {
            return code;
        }
    }

    private final DscCertificateEnrolments store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DscEnrolment(DscCertificateEnrolments store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The signer enrols a certificate (Base64 DER). The result is PENDING until an admin decides. */
    public Enrolment enrol(String tenantId, String principalId, String certificateBase64) {
        require(tenantId, "tenantId");
        require(principalId, "principalId");
        X509Certificate c;
        try {
            c = DscSigningFlow.chain(certificateBase64 == null ? null : List.of(certificateBase64)).get(0);
        } catch (DscSigningFlow.Rejected r) {
            throw new Refused(Refused.Kind.INVALID, "CERTIFICATE_INVALID", "the certificate cannot be read: "
                    + r.getMessage());
        }
        if (DscSigning.algorithmFor(c) == null) {
            throw new Refused(Refused.Kind.INVALID, "CERTIFICATE_KEY_UNSUPPORTED", "the certificate key is neither RSA nor EC");
        }
        Instant now = clock.instant();
        if (!c.getNotAfter().toInstant().isAfter(now)) {
            throw new Refused(Refused.Kind.INVALID, "CERTIFICATE_EXPIRED", "the certificate has expired");
        }
        byte[] der = encoded(c);
        Enrolment e = new Enrolment(tenantId, newId(), principalId, DscSigningFlow.sha256Hex(der),
                c.getIssuerX500Principal().getName(X500Principal.RFC2253), c.getSerialNumber().toString(16),
                c.getSubjectX500Principal().getName(X500Principal.RFC2253), c.getNotBefore().toInstant(),
                c.getNotAfter().toInstant(), der, Status.PENDING, now, null, null, null);
        // two simultaneous first enrolments of one certificate: the primary key lets one in, the other fails as an error
        if (!store.enrol(e)) {
            throw new Refused(Refused.Kind.CONFLICT, "CERTIFICATE_ALREADY_ENROLLED",
                    "the certificate is already enrolled or awaiting approval");
        }
        return e;
    }

    /** An admin approves a PENDING enrolment of another principal. */
    public Enrolment approve(String tenantId, String enrolmentId, String adminId, String reason) {
        Enrolment e = find(tenantId, enrolmentId);
        notSelf(e, adminId);
        if (e.status() != Status.PENDING) {
            throw new Refused(Refused.Kind.CONFLICT, "ENROLMENT_NOT_PENDING", "only a pending enrolment can be approved");
        }
        Instant now = clock.instant();
        if (e.notAfter() != null && !e.notAfter().isAfter(now)) {
            throw new Refused(Refused.Kind.INVALID, "CERTIFICATE_EXPIRED", "the certificate has expired");
        }
        return decide(e, EnumSet.of(Status.PENDING), Status.APPROVED, adminId, now, reason(reason, false),
                "ENROLMENT_NOT_PENDING");
    }

    /** An admin rejects a PENDING enrolment of another principal; a reason is required. */
    public Enrolment reject(String tenantId, String enrolmentId, String adminId, String reason) {
        Enrolment e = find(tenantId, enrolmentId);
        notSelf(e, adminId);
        return decide(e, EnumSet.of(Status.PENDING), Status.REJECTED, adminId, clock.instant(), reason(reason, true),
                "ENROLMENT_NOT_PENDING");
    }

    /**
     * The signer (their own enrolment) or an admin ({@code asAdmin}) revokes a PENDING or APPROVED enrolment; a reason
     * is required. Revocation removes trust, so the signer may do it alone.
     */
    public Enrolment revoke(String tenantId, String enrolmentId, String actorId, boolean asAdmin, String reason) {
        Enrolment e = find(tenantId, enrolmentId);
        if (!asAdmin && !e.principalId().equals(actorId)) {
            // a stranger learns nothing: the same answer as an unknown id
            throw new Refused(Refused.Kind.NOT_FOUND, "ENROLMENT_NOT_FOUND", "no such enrolment");
        }
        return decide(e, DscCertificateEnrolments.LIVE, Status.REVOKED, actorId, clock.instant(), reason(reason, true),
                "ENROLMENT_NOT_LIVE");
    }

    /**
     * The principal's APPROVED enrolment of exactly this certificate (SHA-256 of the DER), or empty. Prepare and complete
     * call this; nothing else binds a DSC signer.
     */
    public Optional<Enrolment> approvedFor(String tenantId, String principalId, String certificateSha256) {
        if (tenantId == null || principalId == null || certificateSha256 == null) {
            return Optional.empty();
        }
        return store.byCertificate(tenantId, certificateSha256)
                .filter(e -> e.status() == Status.APPROVED && e.principalId().equals(principalId));
    }

    /**
     * Integrator ruling on owner decision 241: true only while enrolment {@code enrolmentId} of the tenant is still
     * APPROVED for {@code principalId} and {@code certificateSha256} with exactly {@code approvalSeq}, the approval that
     * prepare bound. A revoke ends it; a later approval (also of the same certificate) has another enrolment id or a
     * higher {@code approvalSeq}, so it never revives an earlier binding.
     */
    public boolean stillApproved(String tenantId, String principalId, String certificateSha256, String enrolmentId,
                                 long approvalSeq) {
        if (tenantId == null || principalId == null || certificateSha256 == null || enrolmentId == null) {
            return false;
        }
        return store.byId(tenantId, enrolmentId).filter(e -> e.status() == Status.APPROVED
                && e.principalId().equals(principalId) && e.certificateSha256().equals(certificateSha256)
                && e.approvalSeq() == approvalSeq).isPresent();
    }

    public List<Enrolment> mine(String tenantId, String principalId) {
        return store.ofPrincipal(tenantId, principalId);
    }

    public List<Enrolment> withStatus(String tenantId, Status status, int limit) {
        return store.withStatus(tenantId, status, Math.max(1, Math.min(limit, MAX_LIST)));
    }

    /** One enrolment of the tenant (admin view). */
    public Enrolment get(String tenantId, String enrolmentId) {
        return find(tenantId, enrolmentId);
    }

    private Enrolment decide(Enrolment e, Set<Status> from, Status to, String by, Instant at, String reason,
                             String conflictCode) {
        if (!store.decide(e.tenantId(), e.enrolmentId(), from, to, by, at, reason)) {
            throw new Refused(Refused.Kind.CONFLICT, conflictCode, "the enrolment is " + e.status()
                    + " or was decided by someone else first");
        }
        // the stored row is the answer (it carries the approval_seq the store assigned)
        return store.byId(e.tenantId(), e.enrolmentId()).orElseThrow(() -> new IllegalStateException(
                "enrolment " + e.enrolmentId() + " vanished after its decision"));
    }

    private Enrolment find(String tenantId, String enrolmentId) {
        return store.byId(tenantId, enrolmentId).orElseThrow(
                () -> new Refused(Refused.Kind.NOT_FOUND, "ENROLMENT_NOT_FOUND", "no such enrolment"));
    }

    private static void notSelf(Enrolment e, String adminId) {
        require(adminId, "adminId");
        if (e.principalId().equals(adminId)) {
            throw new Refused(Refused.Kind.FORBIDDEN, "SELF_APPROVAL",
                    "an enrolment is decided by an admin other than the signer");
        }
    }

    private static String reason(String reason, boolean required) {
        String r = reason == null ? null : reason.strip();
        if (r == null || r.isEmpty()) {
            if (required) {
                throw new Refused(Refused.Kind.INVALID, "REASON_REQUIRED", "a reason is required");
            }
            return null;
        }
        if (r.length() > MAX_REASON) {
            throw new Refused(Refused.Kind.INVALID, "REASON_TOO_LONG", "the reason is longer than " + MAX_REASON);
        }
        return r;
    }

    private static void require(String v, String what) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
    }

    private String newId() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static byte[] encoded(X509Certificate c) {
        try {
            return c.getEncoded();
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new Refused(Refused.Kind.INVALID, "CERTIFICATE_INVALID", "the certificate cannot be encoded");
        }
    }
}
