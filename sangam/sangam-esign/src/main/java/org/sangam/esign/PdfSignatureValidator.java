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
 *       judged ({@code chainTrusted} null). Revocation (OCSP, CRL) and the long-term validation material of PAdES LTV
 *       are <b>not</b> checked here: the report says so in {@code limits}.</li>
 * </ul>
 * The report states what was checked; it is not a statement of legal validity.
 */
public final class PdfSignatureValidator {

    /** What this validator does not check (reported with every result). */
    public static final String LIMITS = "revocation (OCSP/CRL) and PAdES LTV material are not checked";

    /** One signature's findings; {@code problem} names the first failed check, null when none failed. */
    public record SignatureReport(String subFilter, boolean coversWholeDocument, boolean integrityValid,
                                  String byteRangeDigestSha256, String signerSubject, String signerCertificateSha256,
                                  Instant signingTime, boolean timestamped, Boolean timestampValid, Instant timestampTime,
                                  Boolean chainTrusted, String problem) {
    }

    /** {@code valid}: at least one signature, all intact, the last covers the whole file, time-stamps and chains OK. */
    public record Report(boolean valid, List<SignatureReport> signatures, String limits) {
        public Report {
            signatures = List.copyOf(signatures);
        }
    }

    private final Set<TrustAnchor> anchors;
    private final Clock clock;

    public PdfSignatureValidator(Set<TrustAnchor> anchors, Clock clock) {
        this.anchors = anchors == null ? Set.of() : Set.copyOf(anchors);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Report validate(byte[] pdf) {
        Objects.requireNonNull(pdf, "pdf");
        List<SignatureReport> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDSignature sig : doc.getSignatureDictionaries()) {
                out.add(check(sig, pdf));
            }
        } catch (IOException e) {
            return new Report(false, List.of(new SignatureReport(null, false, false, null, null, null, null, false, null,
                    null, null, "PDF_INVALID: " + e.getMessage())), LIMITS);
        }
        boolean valid = !out.isEmpty() && out.get(out.size() - 1).coversWholeDocument()
                && out.stream().allMatch(s -> s.integrityValid() && s.problem() == null);
        return new Report(valid, out, LIMITS);
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
    private SignatureReport check(PDSignature sig, byte[] pdf) {
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
        if (tsAttr != null) {
            try {
                TimeStampToken token = new TimeStampToken(new CMSSignedData(
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
        return new SignatureReport(sig.getSubFilter(), whole, intact, digest,
                cert.getSubjectX500Principal().getName(), certSha, signingTime, tsAttr != null, tsValid, tsTime, trusted,
                problem);
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
                problem);
    }
}
