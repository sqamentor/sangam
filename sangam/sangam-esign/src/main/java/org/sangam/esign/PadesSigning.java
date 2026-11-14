// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.Objects;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;

/**
 * M-080 (R57) PDF signing with an externally produced CMS signature (an ESP's PKCS#7 from the eSign flow, a DSC
 * token's signature, an HSM), in two stateless steps so the PDF never has to stay open while the signer works:
 *
 * <ol>
 *   <li>{@link #prepare}: adds a signature field and dictionary in an incremental update with a reserved, zero-filled
 *       {@code /Contents}, and returns the prepared PDF plus the SHA-256 of its byte ranges. That digest is what the
 *       signer signs (an eSign document hash) and what the caller records next to the approval payload hash (VI.4 rule
 *       5: the signature is bound to what was approved).</li>
 *   <li>{@link #embed}: checks that the prepared PDF still hashes to the recorded digest, that the CMS's signed
 *       {@code messageDigest} attribute is that digest (the signer signed this document, not another) and, for the
 *       PAdES baseline profile ({@code ETSI.CAdES.detached}), that the CMS carries the ESS
 *       {@code signing-certificate-v2} attribute; then writes the DER of the CMS into the reserved space. Nothing else in
 *       the file changes, so the byte ranges stay valid.</li>
 * </ol>
 * A signature timestamp (RFC 3161, {@link TimestampAuthorityClient}) is added to the CMS between the two steps;
 * {@link PdfSignatureValidator} checks the result (AU-01). Uses Apache PDFBox and Bouncy Castle; both are optional
 * dependencies of this module, so a deployment without them has no PDF signing.
 */
public final class PadesSigning {

    /** The signature dictionary's sub-filter. */
    public enum Profile {
        /** {@code adbe.pkcs7.detached}: any detached CMS (the common ESP output). */
        PKCS7_DETACHED(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED),
        /** {@code ETSI.CAdES.detached}: PAdES baseline; the CMS must carry ESS signing-certificate-v2. */
        CADES_DETACHED(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);

        final COSName subFilter;

        Profile(COSName subFilter) {
            this.subFilter = subFilter;
        }
    }

    /** Signer details written into the signature dictionary (all optional). */
    public record Details(String name, String reason, String location, String contactInfo) {
    }

    /**
     * A prepared PDF: the bytes with the reserved {@code /Contents}, the SHA-256 of its byte ranges (hex, lower case) and
     * the byte range itself ({@code [0, a, b, c]}).
     */
    public record Prepared(byte[] pdf, String digestSha256, int[] byteRange, Profile profile) {
        public Prepared {
            pdf = pdf.clone();
            byteRange = byteRange.clone();
        }

        @Override
        public byte[] pdf() {
            return pdf.clone();
        }

        @Override
        public int[] byteRange() {
            return byteRange.clone();
        }
    }

    /** The PDF or the signature cannot be used; nothing was produced. */
    public static final class SigningException extends RuntimeException {
        private final String code;

        public SigningException(String code, String message, Throwable cause) {
            super(code + ": " + message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Default reserved size of {@code /Contents} in bytes (room for a chain, a timestamp and revocation data). */
    public static final int DEFAULT_RESERVED_BYTES = 32 * 1024;

    private PadesSigning() {
    }

    /**
     * Step 1: the prepared PDF and its byte-range digest.
     *
     * @param signingTime the signature dictionary's {@code /M} (from the caller's clock)
     * @param reserved    bytes reserved for the CMS ({@link #DEFAULT_RESERVED_BYTES} when not positive)
     */
    public static Prepared prepare(byte[] pdf, Profile profile, Details details, Instant signingTime, int reserved) {
        Objects.requireNonNull(pdf, "pdf");
        Profile p = profile == null ? Profile.PKCS7_DETACHED : profile;
        int size = reserved <= 0 ? DEFAULT_RESERVED_BYTES : reserved;
        ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 2 * size + 4096);
        String digest;
        try (PDDocument doc = Loader.loadPDF(pdf); SignatureOptions options = new SignatureOptions()) {
            PDSignature sig = new PDSignature();
            sig.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            sig.setSubFilter(p.subFilter);
            sig.setSignDate(GregorianCalendar.from(Objects.requireNonNull(signingTime, "signingTime").atZone(ZoneOffset.UTC)));
            if (details != null) {
                if (details.name() != null) {
                    sig.setName(details.name());
                }
                if (details.reason() != null) {
                    sig.setReason(details.reason());
                }
                if (details.location() != null) {
                    sig.setLocation(details.location());
                }
                if (details.contactInfo() != null) {
                    sig.setContactInfo(details.contactInfo());
                }
            }
            options.setPreferredSignatureSize(size);
            doc.addSignature(sig, options);
            ExternalSigningSupport ext = doc.saveIncrementalForExternalSigning(out);
            try (InputStream content = ext.getContent()) {
                digest = HexFormat.of().formatHex(sha256(content.readAllBytes()));
            }
            // no signature yet: the reserved /Contents stays zero-filled until embed()
            ext.setSignature(new byte[0]);
        } catch (IOException e) {
            throw new SigningException("PDF_INVALID", "the PDF cannot be prepared for signing: " + e.getMessage(), e);
        }
        byte[] prepared = out.toByteArray();
        int[] br = placeholder(prepared);
        if (!digest.equals(HexFormat.of().formatHex(rangeDigest(prepared, br)))) {
            throw new SigningException("PDF_INVALID", "the prepared byte ranges do not match the signed content", null);
        }
        return new Prepared(prepared, digest, br, p);
    }

    /**
     * Step 2: the signed PDF. {@code expectedDigest} is the digest recorded at {@link #prepare} (bound to the approval);
     * {@code cms} is the DER of the detached CMS SignedData from the signer (with a timestamp already added if wanted).
     */
    public static byte[] embed(byte[] preparedPdf, String expectedDigest, Profile profile, byte[] cms) {
        Objects.requireNonNull(preparedPdf, "preparedPdf");
        Objects.requireNonNull(cms, "cms");
        int[] br = placeholder(preparedPdf);
        String actual = HexFormat.of().formatHex(rangeDigest(preparedPdf, br));
        if (expectedDigest == null || !actual.equalsIgnoreCase(expectedDigest)) {
            throw new SigningException("DIGEST_MISMATCH", "the prepared PDF does not hash to the recorded digest", null);
        }
        checkCms(cms, actual, profile == null ? Profile.PKCS7_DETACHED : profile);
        String hex = HexFormat.of().withUpperCase().formatHex(cms);
        int room = br[2] - br[1] - 2;
        if (hex.length() > room) {
            throw new SigningException("SIGNATURE_TOO_LARGE", "the CMS needs " + hex.length() / 2 + " bytes, "
                    + room / 2 + " were reserved", null);
        }
        byte[] out = preparedPdf.clone();
        byte[] h = hex.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(h, 0, out, br[1] + 1, h.length);
        return out;
    }

    /** The CMS must sign {@code digestHex} (its messageDigest attribute) and fit the profile. */
    static void checkCms(byte[] cms, String digestHex, Profile profile) {
        SignerInformation signer;
        try {
            CMSSignedData sd = new CMSSignedData(cms);
            if (sd.getSignerInfos().size() != 1) {
                throw new SigningException("CMS_INVALID", "the CMS must have exactly one signer, it has "
                        + sd.getSignerInfos().size(), null);
            }
            signer = sd.getSignerInfos().getSigners().iterator().next();
        } catch (CMSException | RuntimeException e) {
            if (e instanceof SigningException s) {
                throw s;
            }
            throw new SigningException("CMS_INVALID", "not a CMS SignedData: " + e.getMessage(), e);
        }
        AttributeTable signed = signer.getSignedAttributes();
        Attribute md = signed == null ? null : signed.get(CMSAttributes.messageDigest);
        if (md == null) {
            throw new SigningException("CMS_INVALID", "the CMS has no signed messageDigest attribute", null);
        }
        byte[] signedDigest = ASN1OctetString.getInstance(md.getAttrValues().getObjectAt(0)).getOctets();
        if (!Arrays.equals(signedDigest, HexFormat.of().parseHex(digestHex))) {
            throw new SigningException("DIGEST_MISMATCH", "the CMS signs another document (messageDigest differs)", null);
        }
        if (profile == Profile.CADES_DETACHED && signed.get(PKCSObjectIdentifiers.id_aa_signingCertificateV2) == null) {
            throw new SigningException("CMS_INVALID", "PAdES baseline (ETSI.CAdES.detached) needs the ESS "
                    + "signing-certificate-v2 signed attribute", null);
        }
    }

    /**
     * The byte range of the last signature, checked to cover the whole file around one zero-filled hex placeholder
     * ({@code <00...00>}).
     */
    static int[] placeholder(byte[] pdf) {
        int[] br;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDSignature sig = doc.getLastSignatureDictionary();
            br = sig == null ? null : sig.getByteRange();
        } catch (IOException e) {
            throw new SigningException("PDF_INVALID", "the prepared PDF cannot be read: " + e.getMessage(), e);
        }
        if (br == null || br.length != 4 || br[0] != 0 || br[1] <= 0 || br[2] <= br[1] + 1
                || (long) br[2] + br[3] != pdf.length) {
            throw new SigningException("PDF_INVALID", "the last signature's byte range does not cover the file around its"
                    + " /Contents", null);
        }
        if (pdf[br[1]] != '<' || pdf[br[2] - 1] != '>') {
            throw new SigningException("PDF_INVALID", "the byte-range gap is not a hex string", null);
        }
        for (int i = br[1] + 1; i < br[2] - 1; i++) {
            if (pdf[i] != '0') {
                throw new SigningException("ALREADY_SIGNED", "the reserved /Contents is not empty", null);
            }
        }
        return br;
    }

    /** SHA-256 over the two byte ranges. */
    static byte[] rangeDigest(byte[] pdf, int[] br) {
        MessageDigest md = sha256();
        md.update(pdf, br[0], br[1]);
        md.update(pdf, br[2], br[3]);
        return md.digest();
    }

    static byte[] sha256(byte[] b) {
        return sha256().digest(b);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
