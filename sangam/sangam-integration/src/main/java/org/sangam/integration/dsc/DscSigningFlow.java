// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.sangam.integration.signature.DscSignatureVerifier;
import org.sangam.integration.signature.DscSigning;
import org.sangam.integration.signature.SignatureVerification;

/**
 * Owner decisions 165 and 140: the server side of DSC signing, framework-independent. The REST layer checks the task
 * and the document; this class owns the prepared state and the order of the checks.
 *
 * <ol>
 *   <li>{@link #prepare}: the offered chain's first certificate is the signer; its key must suit an accepted algorithm
 *       and it must be the caller's own enrolled certificate that an admin approved (owner decision 227; a name or
 *       common name is never trusted). The PDF is prepared, the signed attributes are built, and everything is stored
 *       under a new random {@code prepareId} that expires after the configured time. Only the attributes, the digest,
 *       the algorithm and the expiry go back.</li>
 *   <li>{@link #complete}: the entry is consumed first (atomic, once only, same tenant, task and principal, not
 *       expired), so a failed attempt can never be repeated with the same id. The signer certificate and the algorithm
 *       must be the prepared ones and the enrolment must still be APPROVED (a revocation in between counts); the
 *       token's signature is checked and embedded; then the signed PDF is verified under decision 140 against the
 *       server's own record (prepared digest, enrolled signer certificate). The outcome is recorded; only
 *       VERIFIED_VALID returns the signed PDF.</li>
 * </ol>
 */
public final class DscSigningFlow {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    static final int MAX_CHAIN = 10;
    static final int MAX_CERTIFICATE_BYTES = 16 * 1024;
    static final int MAX_SIGNATURE_BYTES = 2048;

    /** The prepare is not usable: unknown, expired, already used, or bound to another task or principal (409). */
    public static final class NotUsable extends RuntimeException {
        public NotUsable(String message) {
            super(message);
        }
    }

    /** The request cannot be prepared or completed as sent (422), with a stable code. */
    public static final class Rejected extends RuntimeException {
        private final String code;

        public Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** What the server binds a prepare to, all from its own records (never from the request body). */
    public record Binding(String tenantId, String taskId, String documentId, int documentVersion, String documentSha256,
                          String principalId) {
        public Binding {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(documentSha256, "documentSha256");
            Objects.requireNonNull(principalId, "principalId");
        }
    }

    /** The prepare answer. */
    public record PrepareResult(String prepareId, String signedAttributes, String preparedDigestSha256, String algorithm,
                                Instant expiresAt) {
    }

    /** The complete answer; {@code signedPdf} only for VERIFIED_VALID. */
    public record CompleteResult(SignatureVerification verification, DscPreparedStore.Entry entry, byte[] signedPdf) {
        public CompleteResult {
            signedPdf = signedPdf == null ? null : signedPdf.clone();
        }

        @Override
        public byte[] signedPdf() {
            return signedPdf == null ? null : signedPdf.clone();
        }
    }

    private final DscSigning signing;
    private final DscSignatureVerifier verifier;
    private final DscPreparedStore store;
    private final DscEnrolment enrolments;
    private final Clock clock;
    private final Duration ttl;
    private final String policyVersion;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param enrolments    owner decision 227: the only source of the signer binding
     * @param policyVersion the signature policy version recorded with outcomes decided before verification
     */
    public DscSigningFlow(DscSigning signing, DscSignatureVerifier verifier, DscPreparedStore store,
                          DscEnrolment enrolments, Clock clock, Duration ttl, String policyVersion) {
        this.signing = Objects.requireNonNull(signing, "signing");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.store = Objects.requireNonNull(store, "store");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
        this.policyVersion = policyVersion;
    }

    /** Step 1. {@code certificateChain}: Base64 DER, signer first. */
    public PrepareResult prepare(Binding binding, byte[] pdf, List<String> certificateChain) {
        Objects.requireNonNull(binding, "binding");
        if (pdf == null || pdf.length == 0) {
            throw new Rejected("DOCUMENT_EMPTY", "the document has no content");
        }
        List<X509Certificate> chain = chain(certificateChain);
        X509Certificate signer = chain.get(0);
        String algorithm = DscSigning.algorithmFor(signer);
        if (algorithm == null) {
            throw new Rejected("DSC_ALGORITHM", "the signer key is neither RSA nor EC");
        }
        // owner decision 227: only the caller's own approved enrolment binds; decision 241: that exact approval
        // (enrolment id and approval sequence) is stored and checked again at complete and by the DSC gate
        DscCertificateEnrolments.Enrolment approval = enrolments.approvedFor(binding.tenantId(), binding.principalId(),
                sha256Hex(encoded(signer))).orElseThrow(() -> new Rejected("CERTIFICATE_NOT_ENROLLED",
                "the certificate is not your enrolled certificate approved by an admin"));
        Instant now = clock.instant();
        DscSigning.Prepared p;
        try {
            p = signing.prepare(pdf, signer, now);
        } catch (DscSigning.Refused r) {
            throw new Rejected(r.code(), "the document cannot be prepared for signing");
        }
        String prepareId = newId();
        Instant expires = now.plus(ttl);
        store.save(new DscPreparedStore.Entry(binding.tenantId(), prepareId, binding.taskId(), binding.documentId(),
                binding.documentVersion(), binding.documentSha256(), binding.principalId(), p.signerCertificateSha256(),
                approval.enrolmentId(), approval.approvalSeq(), shownName(signer), algorithm, p.preparedDigestSha256(), p.signedAttributes(),
                p.signingTime(), p.preparedPdf(), now, expires));
        return new PrepareResult(prepareId, Base64.getEncoder().encodeToString(p.signedAttributes()),
                p.preparedDigestSha256(), algorithm, expires);
    }

    /** Step 2. {@code signature}: Base64; {@code certificateChain}: Base64 DER, signer first. */
    public CompleteResult complete(String tenantId, String taskId, String principalId, String prepareId, String signature,
                                   String algorithm, List<String> certificateChain) {
        DscPreparedStore.Entry e = store.consume(tenantId, prepareId, taskId, principalId, clock.instant())
                .orElseThrow(() -> new NotUsable("the prepare is unknown, expired, already used or not yours"));
        SignatureVerification v;
        byte[] signed = null;
        try {
            List<X509Certificate> chain = chain(certificateChain);
            X509Certificate signer = chain.get(0);
            byte[] sig = decode(signature, "signature", MAX_SIGNATURE_BYTES);
            if (!e.signerCertificateSha256().equals(sha256Hex(encoded(signer)))) {
                v = invalid("DSC_SIGNER_CHANGED", "the certificate is not the one the signature was prepared for");
            } else if (!e.algorithm().equals(algorithm)) {
                v = invalid("DSC_ALGORITHM", "the algorithm is not the one the signature was prepared for");
            } else if (!enrolments.stillApproved(e.tenantId(), e.principalId(), e.signerCertificateSha256(),
                    e.enrolmentId(), e.enrolmentApprovalSeq())) {
                v = invalid("CERTIFICATE_NOT_ENROLLED", "the approval checked at prepare is no longer in force");
            } else {
                signed = signing.complete(new DscSigning.Prepared(e.preparedPdf(), e.preparedDigestSha256(),
                        e.signedAttributes(), e.signerCertificateSha256(), e.signingTime()), signer, chain, algorithm, sig);
                v = verifier.verify(new DscSignatureVerifier.Expectation(e.tenantId(), e.documentId(),
                        e.preparedDigestSha256(), e.signerCertificateSha256()), signed);
            }
        } catch (Rejected r) {
            v = invalid(r.code(), r.getMessage());
        } catch (DscSigning.Refused r) {
            v = refusal(r.code());
        } catch (RuntimeException ex) {
            v = SignatureVerification.indeterminate("DSC_COMPLETE_FAILED", "the signature could not be completed: "
                    + ex.getClass().getSimpleName(), false, policyVersion, clock.instant());
        }
        boolean valid = v.outcome() == SignatureVerification.Outcome.VERIFIED_VALID;
        if (!valid) {
            store.recordOutcome(e.tenantId(), e.prepareId(), v.outcome().name(), v.reasonCode(), null, clock.instant());
        }
        return new CompleteResult(v, e, valid ? signed : null);
    }

    /** After a VERIFIED_VALID complete: records the stored version of the signed PDF. */
    public void recordSigned(DscPreparedStore.Entry e, int signedDocumentVersion) {
        store.recordOutcome(e.tenantId(), e.prepareId(), SignatureVerification.Outcome.VERIFIED_VALID.name(), null,
                signedDocumentVersion, clock.instant());
    }

    /** After a VERIFIED_VALID complete whose signed PDF could not be stored (for example the document changed). */
    public void recordNotStored(DscPreparedStore.Entry e, String reasonCode) {
        store.recordOutcome(e.tenantId(), e.prepareId(), SignatureVerification.Outcome.INDETERMINATE.name(), reasonCode,
                null, clock.instant());
    }

    /**
     * Owner decisions 236, 237 and 241: true only when {@code principalId} signed exactly {@code documentId} in the task
     * with a VERIFIED_VALID outcome, the signed version was stored, and the signing certificate is, at the time of this
     * check, still that principal's approved enrolment in the tenant. Another officer's signature, or one on another
     * document, is false.
     *
     * <p>"Still" is decided by identity, not by time (integrator ruling): the enrolment and approval sequence stored at
     * prepare must still be that row's APPROVED state. A revoke ends it; a re-enrolment has a new enrolment id and its
     * approval a higher sequence, so a signature made before the revoke never counts again and the officer signs
     * again. The recorded evidence is not changed.
     */
    public boolean hasVerifiedSignature(String tenantId, String taskId, String documentId, String principalId) {
        return signatureStatus(tenantId, taskId, documentId, principalId) == SignatureStatus.VERIFIED;
    }

    /** Why task completion may or may not rely on a DSC signature (decision 241). */
    public enum SignatureStatus {
        /** A stored VERIFIED_VALID signature whose enrolment is still approved with the same approval sequence. */
        VERIFIED,
        /**
         * The principal did sign this document at this task with a VERIFIED_VALID outcome, but none of those signatures'
         * enrolments is still approved with the approval sequence stored at prepare (revoked, or revoked and approved
         * again): the evidence stays recorded, the officer must sign again. Completion answers
         * {@value #ENROLMENT_REVOKED_CODE}.
         */
        ENROLMENT_REVOKED,
        /** No stored VERIFIED_VALID signature of this document by this principal at this task. */
        NOT_SIGNED;

        /** The problem code for {@link #ENROLMENT_REVOKED}. */
        public static final String ENROLMENT_REVOKED_CODE = "DSC_ENROLMENT_REVOKED";
    }

    /**
     * The same check as {@link #hasVerifiedSignature}, with the reason when it is false, so the completion gate can
     * answer {@code DSC_ENROLMENT_REVOKED} instead of the generic refusal when the signature exists but its enrolment no
     * longer stands.
     */
    public SignatureStatus signatureStatus(String tenantId, String taskId, String documentId, String principalId) {
        boolean signed = false;
        for (DscPreparedStore.VerifiedSignature s : store.verifiedSignatures(tenantId, taskId, documentId, principalId)) {
            signed = true;
            if (enrolments.stillApproved(tenantId, principalId, s.signerCertificateSha256(), s.enrolmentId(),
                    s.enrolmentApprovalSeq())) {
                return SignatureStatus.VERIFIED;
            }
        }
        return signed ? SignatureStatus.ENROLMENT_REVOKED : SignatureStatus.NOT_SIGNED;
    }

    private SignatureVerification refusal(String code) {
        return switch (code) {
            case "DSC_SIGNATURE_INVALID", "DSC_ALGORITHM", "DSC_SIGNER_CHANGED", "DIGEST_MISMATCH" ->
                    invalid(code, "the token's signature does not belong to the prepared document");
            default -> SignatureVerification.indeterminate(code, "the signature could not be embedded", false,
                    policyVersion, clock.instant());
        };
    }

    private SignatureVerification invalid(String code, String detail) {
        return SignatureVerification.invalid(code, detail, policyVersion, clock.instant());
    }

    /** What the signature will show as the signer: the common name, else the subject; shown, never trusted. */
    static String shownName(X509Certificate c) {
        String cn = commonName(c);
        String s = cn == null || cn.isBlank() ? c.getSubjectX500Principal().getName() : cn.strip();
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    private String newId() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Parses a chain of Base64 DER certificates, signer first; nothing in it is trusted. */
    static List<X509Certificate> chain(List<String> certificateChain) {
        if (certificateChain == null || certificateChain.isEmpty() || certificateChain.size() > MAX_CHAIN) {
            throw new Rejected("CERTIFICATE_CHAIN_INVALID", "certificateChain needs 1 to " + MAX_CHAIN
                    + " certificates, signer first");
        }
        List<X509Certificate> out = new ArrayList<>();
        try {
            CertificateFactory f = CertificateFactory.getInstance("X.509");
            for (String c : certificateChain) {
                out.add((X509Certificate) f.generateCertificate(new ByteArrayInputStream(
                        decode(c, "certificate", MAX_CERTIFICATE_BYTES))));
            }
        } catch (CertificateException | ClassCastException e) {
            throw new Rejected("CERTIFICATE_CHAIN_INVALID", "a certificate of the chain cannot be read");
        }
        return List.copyOf(out);
    }

    private static byte[] decode(String b64, String what, int max) {
        if (b64 == null || b64.isBlank() || b64.length() > (max / 3 + 1) * 4) {
            throw new Rejected("INVALID_INPUT", what + " is missing or too large");
        }
        try {
            return Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new Rejected("INVALID_INPUT", what + " is not Base64");
        }
    }

    /** The subject common name, or null. */
    static String commonName(X509Certificate c) {
        try {
            String cn = null;
            for (Rdn r : new LdapName(c.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(r.getType())) {
                    cn = String.valueOf(r.getValue());
                }
            }
            return cn;
        } catch (InvalidNameException e) {
            return null;
        }
    }

    private static byte[] encoded(X509Certificate c) {
        try {
            return c.getEncoded();
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new Rejected("CERTIFICATE_CHAIN_INVALID", "the signer certificate cannot be encoded");
        }
    }

    static String sha256Hex(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
