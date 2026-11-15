// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.RespID;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * M-080 PAdES long-term validation (B-LT) from the material embedded in the PDF, with nothing fetched: a test PKI (root
 * anchor, signer, time-stamp authority) built in memory, a signed and time-stamped PDF, and a DSS added in an
 * incremental update with OCSP answers and CRLs made by the test. Every failed rule has its own code.
 */
class PadesLtvTest {

    static final Instant SIGNED_AT = Instant.parse("2026-11-12T10:00:00Z");
    static final Instant TSA_TIME = Instant.parse("2026-11-12T10:00:05Z");
    static final Clock NOW = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC);
    static final Instant NOT_BEFORE = Instant.parse("2025-01-01T00:00:00Z");
    static final Instant NOT_AFTER = Instant.parse("2028-01-01T00:00:00Z");
    static final AtomicLong SERIALS = new AtomicLong(100);

    record Party(KeyPair keys, X509Certificate cert) {
    }

    static Party root;
    static Party stranger;
    static Party signer;
    static Party tsa;
    static Party responder;
    static Party responderWithoutNoCheck;
    static byte[] signedPdf;
    static byte[] unstampedPdf;

    @BeforeAll
    static void pki() throws Exception {
        root = ca("Test Root CA");
        stranger = ca("Stranger CA");
        signer = issue("Signer", root, null, false);
        tsa = issue("Test TSA", root, KeyPurposeId.id_kp_timeStamping, false);
        responder = issue("OCSP Responder", root, KeyPurposeId.id_kp_OCSPSigning, true);
        responderWithoutNoCheck = issue("OCSP Responder 2", root, KeyPurposeId.id_kp_OCSPSigning, false);
        signedPdf = sign(true);
        unstampedPdf = sign(false);
    }

    PdfSignatureValidator.Report validate(byte[] pdf, boolean requireLtv) {
        return new PdfSignatureValidator(Set.of(new TrustAnchor(root.cert(), null)), NOW, requireLtv).validate(pdf);
    }

    static PdfSignatureValidator.Ltv ltv(PdfSignatureValidator.Report r) {
        return r.signatures().get(0).ltv();
    }

    @Test
    void embeddedOcspAnswersForTheSignerAndTheTsaMakeTheSignatureLongTermValid() throws Exception {
        byte[] pdf = withDss(signedPdf, List.of(), List.of(ocsp(signer, root, root, TSA_TIME.plusSeconds(60), null),
                ocsp(tsa, root, root, TSA_TIME.plusSeconds(60), null)), List.of(), false);
        PdfSignatureValidator.Report r = validate(pdf, true);
        assertTrue(r.valid(), r.toString());
        assertEquals(PadesLtv.VALIDATION_DATA_ONLY, r.afterLastSignature(), "only the DSS was added after signing");
        assertFalse(r.signatures().get(0).coversWholeDocument(), "the byte range itself is unchanged");
        PdfSignatureValidator.Ltv ltv = ltv(r);
        assertEquals(PadesLtv.VALID, ltv.code(), ltv.toString());
        assertTrue(ltv.valid());
        assertEquals(2, ltv.evidence().size(), "one answer each for the signer and the TSA: " + ltv.evidence());
        assertNull(r.signatures().get(0).problem());
    }

    @Test
    void embeddedCrlsAndADelegatedResponderWithNoCheckAreAuthenticEvidence() throws Exception {
        byte[] pdf = withDss(signedPdf, List.of(responder.cert()),
                List.of(ocsp(signer, root, responder, TSA_TIME.plusSeconds(30), null)),
                List.of(crl(root, root, TSA_TIME.plusSeconds(120), null)), false);
        PdfSignatureValidator.Report r = validate(pdf, true);
        assertTrue(r.valid(), r.toString());
        assertEquals(PadesLtv.VALID, ltv(r).code(), ltv(r).toString());
    }

    @Test
    void withoutADssTheSignatureIsNotLongTermValidAndRequiringLtvRefusesIt() {
        PdfSignatureValidator.Report plain = validate(signedPdf, false);
        assertTrue(plain.valid(), "LTV is reported, not required, by default");
        assertNull(plain.afterLastSignature());
        assertEquals(PadesLtv.NO_DSS, ltv(plain).code());

        PdfSignatureValidator.Report required = validate(signedPdf, true);
        assertFalse(required.valid());
        assertTrue(required.signatures().get(0).problem().startsWith("LTV: " + PadesLtv.NO_DSS),
                required.signatures().get(0).problem());
    }

    @Test
    void theSignatureTimeMustBeProvenByATimeStamp() throws Exception {
        byte[] pdf = withDss(unstampedPdf, List.of(), List.of(ocsp(signer, root, root, TSA_TIME.plusSeconds(60), null)),
                List.of(), false);
        PdfSignatureValidator.Report r = validate(pdf, true);
        assertFalse(r.valid());
        assertEquals(PadesLtv.NO_TIMESTAMP, ltv(r).code());
    }

    @Test
    void noAnchorsAndAForeignAnchorAreNamed() throws Exception {
        byte[] pdf = withDss(signedPdf, List.of(), List.of(ocsp(signer, root, root, TSA_TIME.plusSeconds(60), null),
                ocsp(tsa, root, root, TSA_TIME.plusSeconds(60), null)), List.of(), false);
        assertEquals(PadesLtv.NO_TRUST_ANCHORS, ltv(new PdfSignatureValidator(Set.of(), NOW, false).validate(pdf)).code());
        PdfSignatureValidator.Report foreign = new PdfSignatureValidator(
                Set.of(new TrustAnchor(stranger.cert(), null)), NOW, true).validate(pdf);
        assertFalse(foreign.valid());
        assertEquals(PadesLtv.CHAIN_INCOMPLETE, ltv(foreign).code());
    }

    @Test
    void aRevokedSignerIsRefusedWhicheverSourceSaysSo() throws Exception {
        byte[] byCrl = withDss(signedPdf, List.of(), List.of(ocsp(tsa, root, root, TSA_TIME.plusSeconds(60), null)),
                List.of(crl(root, root, TSA_TIME.plusSeconds(60), signer.cert().getSerialNumber())), false);
        assertEquals(PadesLtv.REVOKED, ltv(validate(byCrl, true)).code());

        byte[] byOcsp = withDss(signedPdf, List.of(), List.of(ocsp(signer, root, root, TSA_TIME.plusSeconds(60),
                TSA_TIME.plusSeconds(30)), ocsp(signer, root, root, TSA_TIME.plusSeconds(90), null),
                ocsp(tsa, root, root, TSA_TIME.plusSeconds(60), null)), List.of(), false);
        PdfSignatureValidator.Report r = validate(byOcsp, true);
        assertFalse(r.valid());
        assertEquals(PadesLtv.REVOKED, ltv(r).code(), "a revoked answer wins over a good one");
    }

    @Test
    void answersThatAreNotAuthenticDoNotCount() throws Exception {
        byte[] forged = withDss(signedPdf, List.of(), List.of(ocsp(signer, root, stranger, TSA_TIME.plusSeconds(60), null),
                ocsp(tsa, root, root, TSA_TIME.plusSeconds(60), null)), List.of(crl(root, stranger,
                TSA_TIME.plusSeconds(60), null)), false);
        assertEquals(PadesLtv.REVOCATION_MISSING, ltv(validate(forged, true)).code());

        byte[] noCheckMissing = withDss(signedPdf, List.of(responderWithoutNoCheck.cert()), List.of(
                ocsp(signer, root, responderWithoutNoCheck, TSA_TIME.plusSeconds(60), null),
                ocsp(tsa, root, root, TSA_TIME.plusSeconds(60), null)), List.of(), false);
        assertEquals(PadesLtv.REVOCATION_MISSING, ltv(validate(noCheckMissing, true)).code(),
                "a delegated responder needs id-pkix-ocsp-nocheck");
    }

    @Test
    void anAnswerOlderThanTheSignatureIsStaleAndTheTsaNeedsItsOwn() throws Exception {
        byte[] stale = withDss(signedPdf, List.of(), List.of(ocsp(signer, root, root, TSA_TIME.minusSeconds(3600), null),
                ocsp(tsa, root, root, TSA_TIME.plusSeconds(60), null)), List.of(), false);
        assertEquals(PadesLtv.REVOCATION_STALE, ltv(validate(stale, true)).code());

        byte[] noTsaAnswer = withDss(signedPdf, List.of(), List.of(ocsp(signer, root, root, TSA_TIME.plusSeconds(60),
                null)), List.of(), false);
        PdfSignatureValidator.Ltv l = ltv(validate(noTsaAnswer, true));
        assertEquals(PadesLtv.REVOCATION_MISSING, l.code());
        assertTrue(l.detail().contains("Test TSA"), l.detail());
    }

    @Test
    void anUnreadableDssEntryFailsClosed() throws Exception {
        byte[] pdf = withDss(signedPdf, List.of(), List.of(new byte[] {1, 2, 3}), List.of(), false);
        assertEquals(PadesLtv.DSS_INVALID, ltv(validate(pdf, true)).code());
    }

    @Test
    void anyOtherChangeInTheUpdateThatAddsTheDssIsAModificationAfterSigning() throws Exception {
        byte[] pdf = withDss(signedPdf, List.of(), List.of(ocsp(signer, root, root, TSA_TIME.plusSeconds(60), null),
                ocsp(tsa, root, root, TSA_TIME.plusSeconds(60), null)), List.of(), true);
        PdfSignatureValidator.Report r = validate(pdf, true);
        assertFalse(r.valid(), "the page changed after signing");
        assertTrue(r.afterLastSignature().startsWith(PadesLtv.MODIFIED_AFTER_SIGNING), r.afterLastSignature());

        byte[] appended = java.util.Arrays.copyOf(signedPdf, signedPdf.length + 8);
        System.arraycopy("\n% junk\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, appended,
                signedPdf.length, 8);
        PdfSignatureValidator.Report junk = validate(appended, false);
        assertFalse(junk.valid(), "bytes that add no validation data are not accepted after the signature");
        assertTrue(junk.afterLastSignature().startsWith(PadesLtv.MODIFIED_AFTER_SIGNING), junk.afterLastSignature());
    }

    // ---- test PKI and documents ------------------------------------------------------------------

    static Party ca(String cn) throws Exception {
        KeyPair kp = SignTestKit.rsa();
        X500Name name = new X500Name("CN=" + cn + ",O=Test");
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(SERIALS.incrementAndGet()),
                Date.from(NOT_BEFORE), Date.from(NOT_AFTER), name, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        return new Party(kp, cert(b, kp));
    }

    static Party issue(String cn, Party issuer, KeyPurposeId eku, boolean noCheck) throws Exception {
        KeyPair kp = SignTestKit.rsa();
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                new JcaX509CertificateHolder(issuer.cert()).getSubject(), BigInteger.valueOf(SERIALS.incrementAndGet()),
                Date.from(NOT_BEFORE), Date.from(NOT_AFTER), new X500Name("CN=" + cn + ",O=Test"), kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        if (eku != null) {
            b.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(eku));
        }
        if (noCheck) {
            b.addExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nocheck, false, DERNull.INSTANCE);
        }
        return new Party(kp, cert(b, issuer.keys()));
    }

    static X509Certificate cert(X509v3CertificateBuilder b, KeyPair issuerKeys) throws Exception {
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(cs));
    }

    /** A signed PDF; {@code stamped} adds a signature time-stamp from the test TSA at {@link #TSA_TIME}. */
    static byte[] sign(boolean stamped) throws Exception {
        PadesSigning.Prepared p = PadesSigning.prepare(SignTestKit.onePagePdf(), PadesSigning.Profile.PKCS7_DETACHED,
                null, SIGNED_AT, 0);
        byte[] cms = SignTestKit.detachedCms(new SignTestKit.Signer(signer.keys(), signer.cert()),
                SignTestKit.signedContent(p.pdf(), p.byteRange()), false);
        if (stamped) {
            CMSSignedData sd = new CMSSignedData(cms);
            SignerInformation si = sd.getSignerInfos().getSigners().iterator().next();
            TimeStampTokenGenerator gen = new TimeStampTokenGenerator(
                    new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                            .build(new JcaContentSignerBuilder("SHA256withRSA").build(tsa.keys().getPrivate()), tsa.cert()),
                    new JcaDigestCalculatorProviderBuilder().build()
                            .get(new DefaultDigestAlgorithmIdentifierFinder().find("SHA-256")),
                    new ASN1ObjectIdentifier("1.2.3.4.1"));
            gen.addCertificates(new JcaCertStore(List.of(tsa.cert())));
            TimeStampRequestGenerator request = new TimeStampRequestGenerator();
            request.setCertReq(true);   // the token then carries the TSA certificate
            TimeStampToken token = gen.generate(request.generate(TSPAlgorithms.SHA256,
                    PadesSigning.sha256(si.getSignature())), BigInteger.ONE, Date.from(TSA_TIME));
            AttributeTable unsigned = new AttributeTable(new ASN1EncodableVector()).add(
                    PKCSObjectIdentifiers.id_aa_signatureTimeStampToken, token.toCMSSignedData().toASN1Structure());
            cms = CMSSignedData.replaceSigners(sd, new SignerInformationStore(
                    SignerInformation.replaceUnsignedAttributes(si, unsigned))).getEncoded("DER");
        }
        return PadesSigning.embed(p.pdf(), p.digestSha256(), PadesSigning.Profile.PKCS7_DETACHED, cms);
    }

    /** A DER OCSPResponse for {@code subject} issued by {@code issuer}, signed by {@code by}; revoked when given. */
    static byte[] ocsp(Party subject, Party issuer, Party by, Instant thisUpdate, Instant revokedAt) throws Exception {
        CertificateID id = new CertificateID(new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1),
                new JcaX509CertificateHolder(issuer.cert()), subject.cert().getSerialNumber());
        BasicOCSPRespBuilder b = new BasicOCSPRespBuilder(new RespID(new JcaX509CertificateHolder(by.cert()).getSubject()));
        CertificateStatus st = revokedAt == null ? CertificateStatus.GOOD
                : new RevokedStatus(Date.from(revokedAt), CRLReason.keyCompromise);
        b.addResponse(id, st, Date.from(thisUpdate), Date.from(thisUpdate.plusSeconds(86_400)), null);
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(by.keys().getPrivate());
        X509CertificateHolder[] chain = by == issuer ? null : new X509CertificateHolder[] {
            new JcaX509CertificateHolder(by.cert())};
        return new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, b.build(cs, chain, Date.from(thisUpdate)))
                .getEncoded();
    }

    /** A DER CRL of {@code issuer} signed by {@code by}, listing {@code revokedSerial} when given. */
    static byte[] crl(Party issuer, Party by, Instant thisUpdate, BigInteger revokedSerial) throws Exception {
        X509v2CRLBuilder b = new X509v2CRLBuilder(new JcaX509CertificateHolder(issuer.cert()).getSubject(),
                Date.from(thisUpdate));
        b.setNextUpdate(Date.from(thisUpdate.plusSeconds(86_400)));
        if (revokedSerial != null) {
            b.addCRLEntry(revokedSerial, Date.from(TSA_TIME.minusSeconds(30)), CRLReason.keyCompromise);
        }
        return b.build(new JcaContentSignerBuilder("SHA256withRSA").build(by.keys().getPrivate())).getEncoded();
    }

    /**
     * {@code pdf} with a DSS ({@code /Certs}, {@code /OCSPs}, {@code /CRLs}) added in an incremental update, the way a
     * PAdES B-LT producer adds it; {@code alsoChangeThePage} changes the page's MediaBox in the same update.
     */
    static byte[] withDss(byte[] pdf, List<X509Certificate> certs, List<byte[]> ocsps, List<byte[]> crls,
                          boolean alsoChangeThePage) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            COSDictionary dss = new COSDictionary();
            List<byte[]> certBytes = new ArrayList<>();
            for (X509Certificate c : certs) {
                certBytes.add(c.getEncoded());
            }
            dss.setItem(COSName.getPDFName("Certs"), streams(doc, certBytes));
            dss.setItem(COSName.getPDFName("OCSPs"), streams(doc, ocsps));
            dss.setItem(COSName.getPDFName("CRLs"), streams(doc, crls));
            COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
            catalog.setItem(COSName.getPDFName("DSS"), dss);
            catalog.setNeedToBeUpdated(true);
            if (alsoChangeThePage) {
                doc.getPage(0).setMediaBox(PDRectangle.A4);
                doc.getPage(0).getCOSObject().setNeedToBeUpdated(true);
            }
            doc.saveIncremental(out);
            return out.toByteArray();
        }
    }

    private static COSArray streams(PDDocument doc, List<byte[]> items) throws Exception {
        COSArray a = new COSArray();
        for (byte[] b : items) {
            COSStream s = doc.getDocument().createCOSStream();
            try (OutputStream o = s.createOutputStream()) {
                o.write(b);
            }
            a.add(s);
        }
        return a;
    }
}
