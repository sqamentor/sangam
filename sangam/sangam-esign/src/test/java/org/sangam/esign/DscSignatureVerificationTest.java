// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.TrustAnchor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.signature.DscSignatureVerifier;
import org.sangam.integration.signature.SignaturePolicy;
import org.sangam.integration.signature.SignatureVerification;
import org.sangam.integration.signature.SignatureVerification.Outcome;

/**
 * Owner decisions 165 and 140: the DSC server half (signed attributes, assembly after checking the token signature,
 * embedding) and the verification of the signed PDF against a local CA, OCSP responder and CRL.
 */
class DscSignatureVerificationTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant SIGNED = NOW.minusSeconds(30);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private EsignTestPki pki;
    private EsignTestPki.Party signer;
    private PadesSigning.Prepared prepared;

    @BeforeEach
    void setUp() throws Exception {
        pki = new EsignTestPki(() -> NOW);
        signer = pki.user("Asha Verma");
        prepared = PadesSigning.prepare(blankPdf(), PadesSigning.Profile.CADES_DETACHED,
                new PadesSigning.Details("Asha Verma", "Approval", null, null), SIGNED, 0);
    }

    @AfterEach
    void tearDown() {
        pki.close();
    }

    static byte[] blankPdf() throws Exception {
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    private SignaturePolicy policy(boolean anchors) {
        return new SignaturePolicy("policy-dsc-1", anchors ? Set.of(new TrustAnchor(pki.ca().cert(), null)) : Set.of(),
                List.of(), List.of(), null, null, 2048, null, null);
    }

    private DscSignatureVerification verifier() {
        return new DscSignatureVerification(policy(true), HttpTransport.forSimulator(), CLOCK);
    }

    /** Owner decision 227: the expectation names the enrolled certificate, never a name. */
    private DscSignatureVerifier.Expectation expected(EsignTestPki.Party enrolled) throws Exception {
        return new DscSignatureVerifier.Expectation("t1", "DOC-1", prepared.digestSha256(), sha(enrolled));
    }

    private static String sha(EsignTestPki.Party p) throws Exception {
        return HexFormat.of().formatHex(PadesSigning.sha256(p.cert().getEncoded()));
    }

    /** What the agent does on the token, done here with the software key. */
    private static byte[] tokenSign(EsignTestPki.Party p, byte[] data) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(p.keys().getPrivate());
        s.update(data);
        return s.sign();
    }

    private byte[] signedPdf(EsignTestPki.Party p) throws Exception {
        DscCms.ToSign t = DscCms.toSign(prepared.digestSha256(), p.cert(), SIGNED);
        byte[] cms = DscCms.assemble(t, p.cert(), List.of(p.cert()), "SHA256withRSA", tokenSign(p, t.signedAttributes()));
        return PadesSigning.embed(prepared.pdf(), prepared.digestSha256(), prepared.profile(), cms);
    }

    private static void invalid(SignatureVerification v, String code) {
        assertEquals(Outcome.VERIFIED_INVALID, v.outcome(), v::toString);
        assertEquals(code, v.reasonCode(), v::toString);
        assertFalse(v.allowsSignedTransition());
    }

    @Test
    void aGenuineDscSignatureIsVerifiedWithEvidence() throws Exception {
        SignatureVerification v = verifier().verify(expected(signer), signedPdf(signer));
        assertEquals(Outcome.VERIFIED_VALID, v.outcome(), v::toString);
        assertTrue(v.allowsSignedTransition());
        assertEquals("policy-dsc-1", v.policyVersion());
        assertEquals(SIGNED, v.signingTime());
        assertTrue(v.revocationSources().get(0).startsWith("OCSP " + pki.ocspUrl()), v.revocationSources()::toString);
        assertEquals(1, pki.ocspCalls());
    }

    @Test
    void theSignedAttributesBindTheDigestAndTheCertificate() throws Exception {
        DscCms.ToSign t = DscCms.toSign(prepared.digestSha256(), signer.cert(), SIGNED);
        Map<String, ASN1Encodable> a = DscCms.attributes(t);
        assertArrayEquals(HexFormat.of().parseHex(prepared.digestSha256()),
                ASN1OctetString.getInstance(a.get(CMSAttributes.messageDigest.getId())).getOctets());
        assertTrue(a.containsKey(PKCSObjectIdentifiers.id_aa_signingCertificateV2.getId()));
        assertTrue(a.containsKey(CMSAttributes.signingTime.getId()));
        assertEquals(PKCSObjectIdentifiers.data, a.get(CMSAttributes.contentType.getId()));
    }

    @Test
    void assemblyRefusesASignatureThatDoesNotMatch() throws Exception {
        DscCms.ToSign t = DscCms.toSign(prepared.digestSha256(), signer.cert(), SIGNED);
        byte[] other = tokenSign(signer, "other bytes".getBytes(StandardCharsets.UTF_8));
        assertEquals("DSC_SIGNATURE_INVALID", assertThrows(PadesSigning.SigningException.class,
                () -> DscCms.assemble(t, signer.cert(), List.of(), "SHA256withRSA", other)).code());
        byte[] good = tokenSign(signer, t.signedAttributes());
        assertEquals("DSC_ALGORITHM", assertThrows(PadesSigning.SigningException.class,
                () -> DscCms.assemble(t, signer.cert(), List.of(), "SHA256withECDSA", good)).code());
        assertEquals("DSC_ALGORITHM", assertThrows(PadesSigning.SigningException.class,
                () -> DscCms.assemble(t, signer.cert(), List.of(), "SHA1withRSA", good)).code());
        EsignTestPki.Party someoneElse = pki.user("Ravi Kumar");
        assertEquals("DSC_SIGNER_CHANGED", assertThrows(PadesSigning.SigningException.class,
                () -> DscCms.assemble(t, someoneElse.cert(), List.of(), "SHA256withRSA",
                        tokenSign(someoneElse, t.signedAttributes()))).code());
    }

    @Test
    void bytesAppendedAfterSigningAreRefused() throws Exception {
        byte[] pdf = signedPdf(signer);
        byte[] longer = new byte[pdf.length + 12];
        System.arraycopy(pdf, 0, longer, 0, pdf.length);
        System.arraycopy("\n%appended\n".getBytes(StandardCharsets.US_ASCII), 0, longer, pdf.length, 11);
        invalid(verifier().verify(expected(signer), longer), "MODIFIED_AFTER_SIGNING");
    }

    @Test
    void aChangedSignedByteIsRefused() throws Exception {
        byte[] pdf = signedPdf(signer);
        pdf[10] ^= 0x01;   // inside the binary comment of the header, so the file still parses
        invalid(verifier().verify(expected(signer), pdf), "SIGNATURE_INVALID");
    }

    @Test
    void anotherPreparationIsRefused() throws Exception {
        DscSignatureVerifier.Expectation other = new DscSignatureVerifier.Expectation("t1", "DOC-1",
                "00".repeat(32), sha(signer));
        invalid(verifier().verify(other, signedPdf(signer)), "DIGEST_MISMATCH");
    }

    @Test
    void theSignerMustBeTheEnrolledCertificate() throws Exception {
        invalid(verifier().verify(expected(pki.user("Ravi Kumar")), signedPdf(signer)), "SIGNER_MISMATCH");
        DscSignatureVerifier.Expectation pinned = new DscSignatureVerifier.Expectation("t1", "DOC-1",
                prepared.digestSha256(), "ab".repeat(32));
        invalid(verifier().verify(pinned, signedPdf(signer)), "SIGNER_MISMATCH");
    }

    @Test
    void aNamesakeCertificateIsNotTheEnrolledOne() throws Exception {
        // owner decision 227: same CN, same CA, another certificate: the name is shown, never trusted
        EsignTestPki.Party namesake = pki.user("Asha Verma");
        invalid(verifier().verify(expected(signer), signedPdf(namesake)), "SIGNER_MISMATCH");
        invalid(verifier().verify(expected(namesake), signedPdf(signer)), "SIGNER_MISMATCH");
        assertEquals(Outcome.VERIFIED_VALID, verifier().verify(expected(namesake), signedPdf(namesake)).outcome());
    }

    @Test
    void aCertificateFromAnotherCaIsUntrusted() throws Exception {
        EsignTestPki.Party rogue = pki.user("Asha Verma", pki.stranger(),
                KeyUsage.digitalSignature | KeyUsage.nonRepudiation, 2048, EsignTestPki.NOT_AFTER);
        invalid(verifier().verify(expected(rogue), signedPdf(rogue)), "UNTRUSTED_CHAIN");
    }

    @Test
    void revocationIsRequired() throws Exception {
        byte[] pdf = signedPdf(signer);
        pki.ocspMode(EsignTestPki.Mode.DOWN);
        pki.crlMode(EsignTestPki.Mode.DOWN);
        SignatureVerification down = verifier().verify(expected(signer), pdf);
        assertEquals(Outcome.INDETERMINATE, down.outcome(), down::toString);
        assertEquals("REVOCATION_UNAVAILABLE", down.reasonCode());
        assertTrue(down.retryable());
        pki.ocspMode(EsignTestPki.Mode.NORMAL);
        pki.crlMode(EsignTestPki.Mode.NORMAL);
        pki.revoke(signer.cert(), SIGNED.minusSeconds(3600));
        invalid(verifier().verify(expected(signer), pdf), "REVOKED");
    }

    @Test
    void refusalsBeforeAnySignatureCheck() throws Exception {
        SignatureVerification noAnchors = new DscSignatureVerification(policy(false), HttpTransport.forSimulator(), CLOCK)
                .verify(expected(signer), signedPdf(signer));
        assertEquals(Outcome.INDETERMINATE, noAnchors.outcome());
        assertEquals("POLICY_INCOMPLETE", noAnchors.reasonCode());
        assertFalse(noAnchors.retryable());
        invalid(verifier().verify(expected(signer), blankPdf()), "NO_SIGNATURE");
        invalid(verifier().verify(expected(signer), new byte[0]), "MALFORMED_DOCUMENT");
        invalid(verifier().verify(expected(signer), "not a pdf".getBytes(StandardCharsets.US_ASCII)),
                "MALFORMED_DOCUMENT");
        assertEquals(0, pki.ocspCalls(), "nothing is fetched for a refused document");
    }

    @Test
    void theVerifierIsFoundThroughTheServiceLoader() {
        assertInstanceOf(DscSignatureVerification.class, DscSignatureVerifier.load(policy(true),
                HttpTransport.forSimulator(), CLOCK).orElseThrow());
    }

    @Test
    void theExpectationIsStrict() throws Exception {
        String cert = sha(signer);
        assertThrows(IllegalArgumentException.class, () -> new DscSignatureVerifier.Expectation("t1", "DOC-1", "xyz",
                cert));
        assertThrows(IllegalArgumentException.class, () -> new DscSignatureVerifier.Expectation("t1", " ",
                prepared.digestSha256(), cert));
        assertThrows(IllegalArgumentException.class, () -> new DscSignatureVerifier.Expectation("t1", "DOC-1",
                prepared.digestSha256(), null));
        assertThrows(IllegalArgumentException.class, () -> new DscSignatureVerifier.Expectation("t1", "DOC-1",
                prepared.digestSha256(), "Asha Verma"));
    }
}
