// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Signature;
import java.security.cert.TrustAnchor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.signature.DscSignatureVerifier;
import org.sangam.integration.signature.DscSigning;
import org.sangam.integration.signature.SignaturePolicy;
import org.sangam.integration.signature.SignatureVerification;

/**
 * Owner decision 165: {@link DscSigningProvider} prepare, token signature (software key standing in for the token),
 * complete, then decision 140 verification of the result; and the ServiceLoader entry.
 */
class DscSigningProviderTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant SIGNED = NOW.minusSeconds(30);

    private EsignTestPki pki;
    private EsignTestPki.Party asha;
    private EsignTestPki.Party ravi;

    @BeforeEach
    void setUp() throws Exception {
        pki = new EsignTestPki(() -> NOW);
        asha = pki.user("Asha Verma");
        ravi = pki.user("Ravi Kumar");
    }

    @AfterEach
    void tearDown() {
        pki.close();
    }

    private static byte[] tokenSign(EsignTestPki.Party p, byte[] data) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(p.keys().getPrivate());
        s.update(data);
        return s.sign();
    }

    private DscSignatureVerifier verifier() {
        SignaturePolicy policy = new SignaturePolicy("policy-dsc-1", Set.of(new TrustAnchor(pki.ca().cert(), null)),
                List.of(), List.of(), null, null, 2048, null, null);
        return new DscSignatureVerification(policy, HttpTransport.forSimulator(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theServiceLoaderFindsTheProvider() {
        assertInstanceOf(DscSigningProvider.class, DscSigning.load().orElseThrow());
    }

    @Test
    void preparedSignedAndCompletedPdfVerifies() throws Exception {
        DscSigning s = new DscSigningProvider();
        DscSigning.Prepared p = s.prepare(DscSignatureVerificationTest.blankPdf(), asha.cert(), SIGNED);
        assertEquals(SIGNED, p.signingTime());
        assertEquals(DscSigning.algorithmFor(asha.cert()), "SHA256withRSA");
        byte[] signed = s.complete(p, asha.cert(), List.of(asha.cert()), "SHA256withRSA",
                tokenSign(asha, p.signedAttributes()));
        SignatureVerification v = verifier().verify(new DscSignatureVerifier.Expectation("t1", "DOC-1",
                p.preparedDigestSha256(), p.signerCertificateSha256()), signed);
        assertEquals(SignatureVerification.Outcome.VERIFIED_VALID, v.outcome(), v::toString);
    }

    @Test
    void aSignatureOverOtherBytesOrByAnotherKeyIsRefusedBeforeEmbedding() throws Exception {
        DscSigning s = new DscSigningProvider();
        DscSigning.Prepared p = s.prepare(DscSignatureVerificationTest.blankPdf(), asha.cert(), SIGNED);
        assertEquals("DSC_SIGNATURE_INVALID", assertThrows(DscSigning.Refused.class, () -> s.complete(p, asha.cert(),
                List.of(asha.cert()), "SHA256withRSA", tokenSign(asha, "other".getBytes()))).code());
        assertEquals("DSC_SIGNATURE_INVALID", assertThrows(DscSigning.Refused.class, () -> s.complete(p, asha.cert(),
                List.of(asha.cert()), "SHA256withRSA", tokenSign(ravi, p.signedAttributes()))).code());
        assertEquals("DSC_SIGNER_CHANGED", assertThrows(DscSigning.Refused.class, () -> s.complete(p, ravi.cert(),
                List.of(ravi.cert()), "SHA256withRSA", tokenSign(ravi, p.signedAttributes()))).code());
        assertEquals("DSC_ALGORITHM", assertThrows(DscSigning.Refused.class, () -> s.complete(p, asha.cert(),
                List.of(asha.cert()), "SHA1withRSA", tokenSign(asha, p.signedAttributes()))).code());
    }

    @Test
    void aBrokenPdfCannotBePrepared() {
        DscSigning.Refused r = assertThrows(DscSigning.Refused.class,
                () -> new DscSigningProvider().prepare("not a pdf".getBytes(), asha.cert(), SIGNED));
        assertEquals("PDF_INVALID", r.code());
        assertTrue(r.getMessage().startsWith("PDF_INVALID"));
    }
}
