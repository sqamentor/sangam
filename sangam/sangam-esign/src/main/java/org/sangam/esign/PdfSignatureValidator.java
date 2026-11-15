// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.io.IOException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TimeStampToken;

/**
 * M-080 / AU-01 validation of the signatures in a PDF. For each signature dictionary it reports:
 *
 * <ul>
 *   <li><b>Coverage.</b> Whether the byte range starts at 0 and ends at the end of the file (a later incremental
 *       update after the last signature means the signed revision is not the whole document).</li>
 *   <li><b>Integrity.</b> The CMS's signed {@code messageDigest} equals the SHA-256 of the byte ranges and the signer's
 *       signature over the signed attributes verifies with the signer certificate carried in the CMS. The byte-range
 *       digest is reported, so the caller can compare it with the digest it bound to the approval at
 *       {@link PadesSigning#prepare}.</li>
 *   <li><b>Time-stamp.</b> When the signer carries an RFC 3161 {@code signature-time-stamp}, its imprint must be the
 *       SHA-256 of the signature value and the token's signature must verify with the TSA certificate it carries.</li>
 *   <li><b>Trust.</b> With configured trust anchors (for example a national root CA list), the signer certificate must
 *       chain to one of them at the time-stamp time (else the signing time, else now); without anchors the chain is not
 *       judged ({@code chainTrusted} null). Online revocation is not checked here (the callers that need it, such as
 *       {@link DscSignatureVerification}, check it through the egress-controlled transport).</li>
 *   <li><b>Long-term validation (M-080, PAdES B-LT).</b> {@link PadesLtv}: from the material embedded in the PDF only
 *       (the document security store, the CMS and the signature time-stamp), with nothing fetched: the signer's and the
 *       time-stamp authority's paths to a configured anchor at the time-stamp time, and an authentic, fresh revocation
 *       answer for every certificate on them. Every signature gets an {@link Ltv} with a stable code; with
 *       {@code requireLtv} a signature that is not {@code LTV_VALID} makes the report invalid.</li>
 *   <li><b>After the last signature.</b> {@code afterLastSignature} is null when the last signature covers the whole
 *       file, {@code VALIDATION_DATA_ONLY} when the only later change is the catalog's {@code /DSS} (how PAdES adds
 *       validation data), else {@code MODIFIED_AFTER_SIGNING} with what changed.</li>
 * </ul>
 * The report states what was checked; it is not a statement of legal validity.
 */
public final class PdfSignatureValidator {

    /** What this validator does not check (reported with every result). */
    public static final String LIMITS = "online revocation (OCSP/CRL fetch) is not checked; long-term validation uses"
            + " only the DSS material embedded in the PDF; document time-stamps (PAdES B-LTA) and"
            + " adbe-revocationInfoArchival are not evaluated";

    /**
     * The long-term validation of one signature ({@link PadesLtv}): {@code code} is {@code LTV_VALID} or the first
     * failed rule ({@code LTV_NO_TRUST_ANCHORS}, {@code LTV_NO_TIMESTAMP}, {@code LTV_NO_DSS}, {@code LTV_DSS_INVALID},
     * {@code LTV_CHAIN_INCOMPLETE}, {@code LTV_REVOCATION_MISSING}, {@code LTV_REVOCATION_STALE}, {@code LTV_REVOKED});
     * {@code evidence} names the embedded revocation answers that were used.
     */
    public record Ltv(String code, String detail, List<String> evidence) {
        public Ltv {
            Objects.requireNonNull(code, "code");
            evidence = List.copyOf(evidence);
        }

        public boolean valid() {
            return PadesLtv.VALID.equals(code);
        }
    }

    /** One signature's findings; {@code problem} names the first failed check, null when none failed. */
    public record SignatureReport(String subFilter, boolean coversWholeDocument, boolean integrityValid,
                                  String byteRangeDigestSha256, String signerSubject, String signerCertificateSha256,
                                  Instant signingTime, boolean timestamped, Boolean timestampValid, Instant timestampTime,
                                  Boolean chainTrusted, Ltv ltv, String problem) {
    }

    /**
     * {@code valid}: at least one signature, all intact; the last covers the whole file or only validation data was
     * added after it; time-stamps and chains OK; with {@code requireLtv} every signature {@code LTV_VALID}.
     */
    public record Report(boolean valid, List<SignatureReport> signatures, String afterLastSignature, String limits) {
        public Report {
            signatures = List.copyOf(signatures);
        }
    }

    private final Set<TrustAnchor> anchors;
    private final Clock clock;
    private final boolean requireLtv;

    /** Long-term validation is reported for every signature but not required. */
    public PdfSignatureValidator(Set<TrustAnchor> anchors, Clock clock) {
        this(anchors, clock, false);
    }

    /** @param requireLtv a signature that is not {@code LTV_VALID} makes the report invalid (its problem says why) */
    public PdfSignatureValidator(Set<TrustAnchor> anchors, Clock clock, boolean requireLtv) {
        this.anchors = anchors == null ? Set.of() : Set.copyOf(anchors);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.requireLtv = requireLtv;
    }

    public Report validate(byte[] pdf) {
        Objects.requireNonNull(pdf, "pdf");
        List<SignatureReport> out = new ArrayList<>();
        int[] br = null;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PadesLtv.Material material = PadesLtv.material(doc);
            for (PDSignature sig : doc.getSignatureDictionaries()) {
                out.add(check(sig, pdf, material));
                br = sig.getByteRange();
            }
        } catch (IOException e) {
            return new Report(false, List.of(new SignatureReport(null, false, false, null, null, null, null, false, null,
                    null, null, null, "PDF_INVALID: " + e.getMessage())), null, LIMITS);
        }
        if (out.isEmpty()) {
            return new Report(false, out, null, LIMITS);
        }
        SignatureReport last = out.get(out.size() - 1);
        boolean wellFormed = br != null && br.length == 4 && br[0] == 0 && br[2] >= 0 && br[3] >= 0
                && (long) br[2] + br[3] <= pdf.length;
        String after = last.coversWholeDocument() ? null : !wellFormed ? PadesLtv.MODIFIED_AFTER_SIGNING
                + ": the last signature has no valid byte range" : PadesLtv.afterLastSignature(pdf, (long) br[2] + br[3]);
        boolean valid = (last.coversWholeDocument() || PadesLtv.VALIDATION_DATA_ONLY.equals(after))
                && out.stream().allMatch(s -> s.integrityValid() && s.problem() == null);
        return new Report(valid, out, after, LIMITS);
    }

    /**
     * The CMS of a signature's {@code /Contents}: its first ASN.1 structure, byte for byte, without the zero padding that
     * fills the rest of the space reserved for the signature. Bouncy Castle's {@code CMSSignedData(byte[])} refuses
     * bytes after the structure ({@code ASN1Primitive.fromByteArray}: "Extra data detected in stream", reported as
     * "IOException reading content."), so the structure is cut out before it is parsed.
     *
     * @throws IOException when the bytes do not start with an ASN.1 structure (for example an unfilled placeholder)
     */
    static byte[] cmsOf(byte[] contents) throws IOException {
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(contents);
        // reads exactly one structure from the stream (no read-ahead), so what is left of it is the padding
        org.bouncycastle.asn1.ASN1InputStream asn1 = new org.bouncycastle.asn1.ASN1InputStream(in, contents.length);
        if (asn1.readObject() == null) {
            throw new IOException("the signature /Contents holds no ASN.1 structure");
        }
        return java.util.Arrays.copyOf(contents, contents.length - in.available());
    }

    private SignatureReport check(PDSignature sig, byte[] pdf, PadesLtv.Material material) {
        int[] br = sig.getByteRange();
        boolean whole = br != null && br.length == 4 && br[0] == 0 && (long) br[2] + br[3] == pdf.length;
        byte[] content;
        byte[] contents;
        try {
            content = sig.getSignedContent(pdf);
            contents = sig.getContents(pdf);
        } catch (IOException | RuntimeException e) {
            return fail(sig, whole, null, "BYTE_RANGE_INVALID: " + e.getMessage());
        }
        String digest = HexFormat.of().formatHex(PadesSigning.sha256(content));
        CMSSignedData cms;
        SignerInformation signer;
        X509CertificateHolder holder;
        try {
            cms = new CMSSignedData(new CMSProcessableByteArray(content), cmsOf(contents));
            if (cms.getSignerInfos().size() != 1) {
                return fail(sig, whole, digest, "CMS_INVALID: expected one signer, found " + cms.getSignerInfos().size());
            }
            signer = cms.getSignerInfos().getSigners().iterator().next();
            @SuppressWarnings("unchecked")
            var matches = cms.getCertificates().getMatches(signer.getSID());
            if (matches.isEmpty()) {
                return fail(sig, whole, digest, "CMS_INVALID: the signer certificate is not in the CMS");
            }
            holder = (X509CertificateHolder) matches.iterator().next();
        } catch (Exception e) {
            return fail(sig, whole, digest, "CMS_INVALID: " + e.getMessage());
        }
        X509Certificate cert;
        try {
            cert = new JcaX509CertificateConverter().getCertificate(holder);
        } catch (CertificateException e) {
            return fail(sig, whole, digest, "CMS_INVALID: signer certificate: " + e.getMessage());
        }
        String certSha = HexFormat.of().formatHex(sha256Quiet(cert));
        boolean intact;
        String problem = null;
        try {
            intact = digestMatches(signer, content) && signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(cert));
            if (!intact) {
                problem = "INTEGRITY: the signature does not verify over the signed byte ranges";
            }
        } catch (Exception e) {
            intact = false;
            problem = "INTEGRITY: " + e.getMessage();
        }
        Instant signingTime = signingTime(signer);
        Attribute tsAttr = signer.getUnsignedAttributes() == null ? null
                : signer.getUnsignedAttributes().get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
        Boolean tsValid = null;
        Instant tsTime = null;
        TimeStampToken token = null;
        if (tsAttr != null) {
            try {
                token = new TimeStampToken(new CMSSignedData(
                        ContentInfo.getInstance(tsAttr.getAttrValues().getObjectAt(0))));
                tsTime = token.getTimeStampInfo().getGenTime().toInstant();
                byte[] imprint = token.getTimeStampInfo().getMessageImprintDigest();
                @SuppressWarnings("unchecked")
                var tsaCerts = token.getCertificates().getMatches(token.getSID());
                if (!Arrays.equals(imprint, PadesSigning.sha256(signer.getSignature())) || tsaCerts.isEmpty()) {
                    tsValid = false;
                } else {
                    token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(
                            (X509CertificateHolder) tsaCerts.iterator().next()));
                    tsValid = true;
                }
            } catch (Exception e) {
                tsValid = false;
            }
            if (!tsValid && problem == null) {
                problem = "TIMESTAMP: the signature time-stamp does not verify";
            }
        }
        Boolean trusted = null;
        if (!anchors.isEmpty()) {
            Instant at = tsValid != null && tsValid ? tsTime : signingTime != null ? signingTime : clock.instant();
            trusted = chains(cert, cms, at);
            if (!trusted && problem == null) {
                problem = "UNTRUSTED: the signer certificate does not chain to a configured trust anchor";
            }
        }
        Ltv ltv = PadesLtv.check(anchors, cert, certificates(cms), token, tsValid, material, clock.instant());
        if (requireLtv && !ltv.valid() && problem == null) {
            problem = "LTV: " + ltv.code() + ": " + ltv.detail();
        }
        return new SignatureReport(sig.getSubFilter(), whole, intact, digest,
                cert.getSubjectX500Principal().getName(), certSha, signingTime, tsAttr != null, tsValid, tsTime, trusted,
                ltv, problem);
    }

    private static List<X509Certificate> certificates(CMSSignedData cms) {
        List<X509Certificate> out = new ArrayList<>();
        JcaX509CertificateConverter conv = new JcaX509CertificateConverter();
        for (Object o : cms.getCertificates().getMatches(null)) {
            try {
                out.add(conv.getCertificate((X509CertificateHolder) o));
            } catch (CertificateException e) {
                // an unreadable extra certificate cannot help a path
            }
        }
        return out;
    }

    private boolean chains(X509Certificate cert, CMSSignedData cms, Instant at) {
        try {
            List<X509Certificate> pool = new ArrayList<>();
            JcaX509CertificateConverter conv = new JcaX509CertificateConverter();
            for (Object o : cms.getCertificates().getMatches(null)) {
                pool.add(conv.getCertificate((X509CertificateHolder) o));
            }
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(cert);
            PKIXBuilderParameters params = new PKIXBuilderParameters(anchors, target);
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(pool)));
            params.setRevocationEnabled(false);   // LIMITS: revocation is not checked here
            params.setDate(Date.from(at));
            CertPathBuilder.getInstance("PKIX").build(params);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean digestMatches(SignerInformation signer, byte[] content) {
        AttributeTable signed = signer.getSignedAttributes();
        Attribute md = signed == null ? null : signed.get(CMSAttributes.messageDigest);
        if (md == null) {
            return false;
        }
        byte[] expected = ASN1OctetString.getInstance(md.getAttrValues().getObjectAt(0)).getOctets();
        if (!"2.16.840.1.101.3.4.2.1".equals(signer.getDigestAlgOID())) {
            // a digest other than SHA-256: BC's verify() checks it against the content itself
            return true;
        }
        return Arrays.equals(expected, PadesSigning.sha256(content));
    }

    private static Instant signingTime(SignerInformation signer) {
        AttributeTable signed = signer.getSignedAttributes();
        Attribute t = signed == null ? null : signed.get(CMSAttributes.signingTime);
        if (t == null) {
            return null;
        }
        try {
            return Time.getInstance(t.getAttrValues().getObjectAt(0)).getDate().toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] sha256Quiet(X509Certificate cert) {
        try {
            return PadesSigning.sha256(cert.getEncoded());
        } catch (java.security.cert.CertificateEncodingException e) {
            return new byte[0];
        }
    }

    private static SignatureReport fail(PDSignature sig, boolean whole, String digest, String problem) {
        return new SignatureReport(sig.getSubFilter(), whole, false, digest, null, null, null, false, null, null, null,
                null, problem);
    }
}
