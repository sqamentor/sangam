// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.signature.DscSignatureVerifier;
import org.sangam.integration.signature.SignaturePolicy;
import org.sangam.integration.signature.SignatureVerification;

/**
 * Owner decisions 165 and 140: verification of a DSC-signed PDF, in this order (the first failure decides):
 *
 * <ol>
 *   <li>trust anchors configured, else INDETERMINATE ({@code POLICY_INCOMPLETE});</li>
 *   <li>{@link PdfSignatureValidator}: at least one signature, every signature intact, the last one covering the whole
 *       file (nothing appended after signing);</li>
 *   <li>the last signature covers exactly the prepared bytes ({@code DIGEST_MISMATCH} otherwise: another document or
 *       another preparation);</li>
 *   <li>its CMS: one signer, SHA-256/384/512, signed {@code messageDigest} equal to the prepared digest, the signature,
 *       an optional signature time-stamp (the same check as eSign);</li>
 *   <li>the signer certificate is exactly the enrolled, approved one (owner decision 227: its name is shown, never
 *       trusted), then the shared decision 140 signer checks: key size and usage, validity at the signing
 *       time, a PKIX path to a configured anchor (certificates in the PDF only help build it) and revocation of every
 *       certificate on the path, OCSP then CRL, through the egress-controlled transport.</li>
 * </ol>
 * Whatever the agent claimed is irrelevant: only the signed PDF and the server's own record count.
 */
public final class DscSignatureVerification implements DscSignatureVerifier {

    /** ServiceLoader entry (META-INF/services). */
    public static final class Provider implements DscSignatureVerifier.Factory {
        @Override
        public DscSignatureVerifier create(SignaturePolicy policy, HttpTransport revocationTransport, Clock clock) {
            return new DscSignatureVerification(policy, revocationTransport, clock);
        }
    }

    private final SignaturePolicy policy;
    private final Clock clock;
    private final EsignResponseVerification signerChecks;

    public DscSignatureVerification(SignaturePolicy policy, HttpTransport revocationTransport, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.signerChecks = new EsignResponseVerification(policy, revocationTransport, this.clock);
    }

    @Override
    public SignatureVerification verify(Expectation expected, byte[] signedPdf) {
        try {
            return check(expected, signedPdf);
        } catch (RuntimeException e) {
            return SignatureVerification.indeterminate("VERIFIER_ERROR", e.getClass().getSimpleName() + ": "
                    + e.getMessage(), false, policy.version(), clock.instant());
        }
    }

    private SignatureVerification check(Expectation expected, byte[] pdf) {
        Instant now = clock.instant();
        String v = policy.version();
        if (policy.trustAnchors().isEmpty()) {
            return SignatureVerification.indeterminate("POLICY_INCOMPLETE", "no trust anchors are configured", false, v,
                    now);
        }
        if (pdf == null || pdf.length == 0) {
            return SignatureVerification.invalid("MALFORMED_DOCUMENT", "empty document", v, now);
        }
        // anchors are judged below with the policy's intermediates and revocation, not by the structural validator
        PdfSignatureValidator.Report report = new PdfSignatureValidator(Set.of(), clock).validate(pdf);
        List<PdfSignatureValidator.SignatureReport> sigs = report.signatures();
        if (sigs.isEmpty()) {
            return SignatureVerification.invalid("NO_SIGNATURE", "the document carries no signature", v, now);
        }
        PdfSignatureValidator.SignatureReport last = sigs.get(sigs.size() - 1);
        if (last.problem() != null && last.problem().startsWith("PDF_INVALID")) {
            return SignatureVerification.invalid("MALFORMED_DOCUMENT", last.problem(), v, now);
        }
        if (!last.coversWholeDocument()) {
            return SignatureVerification.invalid("MODIFIED_AFTER_SIGNING",
                    "the last signature does not cover the whole file", v, now);
        }
        for (PdfSignatureValidator.SignatureReport s : sigs) {
            if (!s.integrityValid() || s.problem() != null) {
                return SignatureVerification.invalid("SIGNATURE_INVALID", s.problem() == null
                        ? "a signature of the document does not verify" : s.problem(), v, now);
            }
        }
        if (!expected.preparedDigestSha256().equals(last.byteRangeDigestSha256())) {
            return SignatureVerification.invalid("DIGEST_MISMATCH",
                    "the signature does not cover the prepared document", v, now);
        }
        Signed signed;
        try {
            signed = lastSignature(pdf);
        } catch (Exception e) {
            return SignatureVerification.invalid("CMS_INVALID", e.getMessage(), v, now);
        }
        X509Certificate user = signed.signer();
        String userSha = HexFormat.of().formatHex(PadesSigning.sha256(encoded(user)));
        String subject = user.getSubjectX500Principal().getName();
        EsignResponseVerification.CmsCheck cms = EsignResponseVerification.cms(signed.cms(), user,
                expected.preparedDigestSha256());
        if (cms.code() != null) {
            SignatureVerification r = cms.unsupported()
                    ? SignatureVerification.indeterminate(cms.code(), cms.detail(), false, v, now)
                    : SignatureVerification.invalid(cms.code(), cms.detail(), v, now);
            return r.withSigner(subject, userSha);
        }
        // owner decision 227: only the enrolled, approved certificate binds the signer; its name is never trusted
        if (!expected.signerCertificateSha256().equals(userSha)) {
            return SignatureVerification.invalid("SIGNER_MISMATCH",
                    "the signer certificate is not the enrolled certificate", v, now).withSigner(subject, userSha);
        }
        // no signed signing-time and no time-stamp: the certificate must be valid now
        return signerChecks.signer(expected.tenantId(), user, cms, now, now);
    }

    private record Signed(CMSSignedData cms, X509Certificate signer) {
    }

    /** The CMS of the last signature dictionary, with its signed byte ranges as content, and its signer certificate. */
    private static Signed lastSignature(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<PDSignature> all = doc.getSignatureDictionaries();
            PDSignature sig = all.get(all.size() - 1);
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(sig.getSignedContent(pdf)),
                    sig.getContents(pdf));
            if (cms.getSignerInfos().size() != 1) {
                throw new IllegalStateException("expected one signer, found " + cms.getSignerInfos().size());
            }
            SignerInformation signer = cms.getSignerInfos().getSigners().iterator().next();
            @SuppressWarnings("unchecked")
            var matches = cms.getCertificates().getMatches(signer.getSID());
            if (matches.isEmpty()) {
                throw new IllegalStateException("the signer certificate is not in the CMS");
            }
            X509Certificate cert = new JcaX509CertificateConverter()
                    .getCertificate((X509CertificateHolder) matches.iterator().next());
            return new Signed(cms, cert);
        }
    }

    private static byte[] encoded(X509Certificate c) {
        try {
            return c.getEncoded();
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
