// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.TrustAnchor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.signature.EsignExpectation;
import org.sangam.integration.signature.EsignResponseVerifier;
import org.sangam.integration.signature.SignaturePolicy;
import org.sangam.integration.signature.SignatureVerification;
import org.sangam.integration.signature.SignatureVerification.Outcome;

/**
 * Owner decision 140 (RM-07): the Bouncy Castle eSign response verifier against a local CA, OCSP responder and CRL.
 * Cases: success, tampering, wrong document, wrong and substituted signer, untrusted chain, revocation and an
 * unavailable revocation service, replay, malformed XML, restart, unsupported formats and weak keys.
 */
class EsignResponseVerificationTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant REQUESTED = NOW.minusSeconds(120);
    private static final Instant SIGNED = NOW.minusSeconds(60);
    private static final String TS = "2026-06-01T09:59:00Z";
    private static final ZoneId PROVIDER_ZONE = ZoneOffset.ofHoursMinutes(5, 30);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private EsignTestPki pki;
    private EsignTestPki.Party esp;
    private EsignTestPki.Party signer;
    private byte[] hash;
    private String hashHex;

    @BeforeEach
    void setUp() throws Exception {
        pki = new EsignTestPki(() -> NOW);
        esp = EsignTestPki.selfSigned("Test ESP", false);
        signer = pki.user("Asha Verma");
        hash = MessageDigest.getInstance("SHA-256").digest("the approved document".getBytes(StandardCharsets.UTF_8));
        hashHex = HexFormat.of().formatHex(hash);
    }

    @AfterEach
    void tearDown() {
        pki.close();
    }

    private SignaturePolicy policy(ZoneId zone) {
        return new SignaturePolicy("policy-7", Set.of(new TrustAnchor(pki.ca().cert(), null)), List.of(),
                List.of(esp.cert()), null, null, 2048, null, zone);
    }

    private EsignResponseVerification verifier() {
        return new EsignResponseVerification(policy(PROVIDER_ZONE), HttpTransport.forSimulator(), CLOCK);
    }

    private EsignExpectation expected(String txn, String signerName) {
        return new EsignExpectation("t1", txn, "ASP-1", hashHex, "DOC-1", signerName, null, REQUESTED);
    }

    private byte[] answer(String txn, String ts, EsignTestPki.Party user, byte[] cms) throws Exception {
        return EsignTestPki.response(esp, txn, ts, user.cert(), cms, null);
    }

    private byte[] goodAnswer(String txn) throws Exception {
        return answer(txn, TS, signer, EsignTestPki.cmsOverHash(signer, hash, SIGNED));
    }

    private static void invalid(SignatureVerification v, String code) {
        assertEquals(Outcome.VERIFIED_INVALID, v.outcome(), v::toString);
        assertEquals(code, v.reasonCode(), v::toString);
        assertFalse(v.allowsSignedTransition());
    }

    private static void indeterminate(SignatureVerification v, String code, boolean retryable) {
        assertEquals(Outcome.INDETERMINATE, v.outcome(), v::toString);
        assertEquals(code, v.reasonCode(), v::toString);
        assertEquals(retryable, v.retryable(), v::toString);
        assertFalse(v.allowsSignedTransition());
    }

    @Test
    void aGenuineAnswerIsVerifiedWithEvidence() throws Exception {
        SignatureVerification v = verifier().verify(expected("T-1", "asha  VERMA"), goodAnswer("T-1"));
        assertEquals(Outcome.VERIFIED_VALID, v.outcome(), v::toString);
        assertTrue(v.allowsSignedTransition());
        assertEquals("policy-7", v.policyVersion());
        assertEquals(1, v.chainSha256().size(), "the leaf; the anchor is configured, not part of the path");
        assertEquals(v.signerCertificateSha256(), v.chainSha256().get(0));
        assertTrue(v.revocationSources().get(0).startsWith("OCSP " + pki.ocspUrl()), v.revocationSources()::toString);
        assertEquals(SIGNED, v.signingTime());
        assertFalse(v.demo());
        assertEquals("VERIFIED_VALID", v.evidence().get("outcome"));
        assertFalse(v.evidence().containsKey("demo"));
        assertFalse(v.evidence().toString().contains("PRIVATE"), "no key material in the evidence");
    }

    @Test
    void aRestartedVerifierReachesTheSameResult() throws Exception {
        byte[] answer = goodAnswer("T-2");
        SignatureVerification first = verifier().verify(expected("T-2", "Asha Verma"), answer);
        SignatureVerification second = verifier().verify(expected("T-2", "Asha Verma"), answer);
        assertEquals(Outcome.VERIFIED_VALID, first.outcome());
        assertEquals(first.outcome(), second.outcome());
        assertEquals(first.chainSha256(), second.chainSha256());
        assertEquals(first.signerCertificateSha256(), second.signerCertificateSha256());
    }

    @Test
    void aTamperedAnswerFailsTheEspSignature() throws Exception {
        byte[] tampered = EsignTestPki.replace(goodAnswer("T-3"), "resCode=\"RC-T-3\"", "resCode=\"RC-OTHER\"");
        invalid(verifier().verify(expected("T-3", "Asha Verma"), tampered), "RESPONSE_SIGNATURE_INVALID");
    }

    @Test
    void anAnswerSignedByAnotherKeyIsRefusedEvenWithItsCertificateInKeyInfo() throws Exception {
        EsignTestPki.Party rogue = EsignTestPki.selfSigned("Rogue ESP", false);
        byte[] answer = EsignTestPki.response(rogue, "T-4", TS, signer.cert(),
                EsignTestPki.cmsOverHash(signer, hash, SIGNED), rogue.cert());
        invalid(verifier().verify(expected("T-4", "Asha Verma"), answer), "RESPONSE_SIGNATURE_INVALID");
    }

    @Test
    void aSignatureOverAnotherDocumentIsRefused() throws Exception {
        byte[] other = MessageDigest.getInstance("SHA-256").digest("another document".getBytes(StandardCharsets.UTF_8));
        byte[] answer = answer("T-5", TS, signer, EsignTestPki.cmsOverHash(signer, other, SIGNED));
        SignatureVerification v = verifier().verify(expected("T-5", "Asha Verma"), answer);
        invalid(v, "DIGEST_MISMATCH");
        assertTrue(v.signerSubject().contains("Asha Verma"), "the signer is kept for the audit");
    }

    @Test
    void aSignatureByAnotherPersonIsRefused() throws Exception {
        invalid(verifier().verify(expected("T-6", "Ravi Kumar"), goodAnswer("T-6")), "SIGNER_MISMATCH");
        EsignExpectation pinned = new EsignExpectation("t1", "T-6", "ASP-1", hashHex, "DOC-1", "Asha Verma",
                "00".repeat(32), REQUESTED);
        invalid(verifier().verify(pinned, goodAnswer("T-6")), "SIGNER_MISMATCH");
    }

    @Test
    void aCmsSignerThatIsNotTheUserCertificateIsRefused() throws Exception {
        EsignTestPki.Party other = pki.user("Asha Verma");
        byte[] answer = answer("T-7", TS, signer, EsignTestPki.cmsOverHash(other, hash, SIGNED));
        invalid(verifier().verify(expected("T-7", "Asha Verma"), answer), "SIGNER_SUBSTITUTED");
    }

    @Test
    void aChainToAnUnconfiguredRootIsRefused() throws Exception {
        EsignTestPki.Party foreign = pki.user("Asha Verma", pki.stranger(),
                KeyUsage.digitalSignature | KeyUsage.nonRepudiation, 2048, EsignTestPki.NOT_AFTER);
        byte[] cms = EsignTestPki.cmsOverHash(foreign, hash, SIGNED, List.of(foreign.cert(), pki.stranger().cert()),
                "SHA256");
        invalid(verifier().verify(expected("T-8", "Asha Verma"), answer("T-8", TS, foreign, cms)), "UNTRUSTED_CHAIN");
    }

    @Test
    void aCertificateRevokedBeforeTheCheckIsRefused() throws Exception {
        pki.revoke(signer.cert(), SIGNED.minusSeconds(3600));
        invalid(verifier().verify(expected("T-9", "Asha Verma"), goodAnswer("T-9")), "REVOKED");
    }

    @Test
    void revocationAfterAnUntimestampedSignatureIsStillRefused() throws Exception {
        pki.revoke(signer.cert(), SIGNED.plusSeconds(30));
        invalid(verifier().verify(expected("T-10", "Asha Verma"), goodAnswer("T-10")), "REVOKED");
    }

    @Test
    void theCrlIsUsedWhenTheOcspResponderIsDown() throws Exception {
        pki.ocspMode(EsignTestPki.Mode.DOWN);
        SignatureVerification v = verifier().verify(expected("T-11", "Asha Verma"), goodAnswer("T-11"));
        assertEquals(Outcome.VERIFIED_VALID, v.outcome(), v::toString);
        assertTrue(v.revocationSources().get(0).startsWith("CRL "), v.revocationSources()::toString);
        pki.revoke(signer.cert(), SIGNED.minusSeconds(10));
        invalid(verifier().verify(expected("T-11", "Asha Verma"), goodAnswer("T-11")), "REVOKED");
    }

    @Test
    void noAuthenticCurrentRevocationAnswerIsIndeterminateAndRetryable() throws Exception {
        pki.crlMode(EsignTestPki.Mode.DOWN);
        for (EsignTestPki.Mode m : List.of(EsignTestPki.Mode.DOWN, EsignTestPki.Mode.WRONG_SIGNER,
                EsignTestPki.Mode.STALE, EsignTestPki.Mode.WRONG_NONCE)) {
            pki.ocspMode(m);
            indeterminate(verifier().verify(expected("T-12", "Asha Verma"), goodAnswer("T-12")),
                    "REVOCATION_UNAVAILABLE", true);
        }
        pki.ocspMode(EsignTestPki.Mode.DOWN);
        for (EsignTestPki.Mode m : List.of(EsignTestPki.Mode.WRONG_SIGNER, EsignTestPki.Mode.STALE)) {
            pki.crlMode(m);
            indeterminate(verifier().verify(expected("T-12", "Asha Verma"), goodAnswer("T-12")),
                    "REVOCATION_UNAVAILABLE", true);
        }
    }

    @Test
    void anAnswerForAnotherTransactionOrAnOldAnswerIsRefused() throws Exception {
        invalid(verifier().verify(expected("T-13", "Asha Verma"), goodAnswer("T-OTHER")), "TXN_MISMATCH");
        byte[] old = answer("T-14", "2026-06-01T07:00:00Z", signer, EsignTestPki.cmsOverHash(signer, hash, SIGNED));
        invalid(verifier().verify(expected("T-14", "Asha Verma"), old), "RESPONSE_TIME_OUT_OF_WINDOW");
        byte[] future = answer("T-15", "2026-06-01T11:00:00Z", signer, EsignTestPki.cmsOverHash(signer, hash, SIGNED));
        invalid(verifier().verify(expected("T-15", "Asha Verma"), future), "RESPONSE_TIME_OUT_OF_WINDOW");
    }

    @Test
    void malformedOrUnsignedXmlIsRefused() throws Exception {
        EsignResponseVerification v = verifier();
        invalid(v.verify(expected("T-16", "Asha Verma"), "<EsignResp".getBytes(StandardCharsets.UTF_8)),
                "MALFORMED_RESPONSE");
        invalid(v.verify(expected("T-16", "Asha Verma"), new byte[0]), "MALFORMED_RESPONSE");
        String doctype = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/hosts\">]><EsignResp>&e;"
                + "</EsignResp>";
        invalid(v.verify(expected("T-16", "Asha Verma"), doctype.getBytes(StandardCharsets.UTF_8)), "MALFORMED_RESPONSE");
        invalid(v.verify(expected("T-16", "Asha Verma"),
                "<EsignResp status=\"1\" txn=\"T-16\"/>".getBytes(StandardCharsets.UTF_8)), "RESPONSE_SIGNATURE_MISSING");
    }

    @Test
    void aRawSignatureIsIndeterminateNotAccepted() throws Exception {
        byte[] answer = answer("T-17", TS, signer, new byte[256]);
        indeterminate(verifier().verify(expected("T-17", "Asha Verma"), answer), "UNSUPPORTED_SIGNATURE_FORMAT", false);
    }

    @Test
    void aLocalTimeIsReadInTheProviderZoneOnlyWhenConfigured() throws Exception {
        byte[] answer = answer("T-18", "2026-06-01T15:29:00", signer, EsignTestPki.cmsOverHash(signer, hash, SIGNED));
        assertEquals(Outcome.VERIFIED_VALID, verifier().verify(expected("T-18", "Asha Verma"), answer).outcome());
        EsignResponseVerification noZone = new EsignResponseVerification(policy(null), HttpTransport.forSimulator(), CLOCK);
        indeterminate(noZone.verify(expected("T-18", "Asha Verma"), answer), "RESPONSE_TIME_UNREADABLE", false);
    }

    @Test
    void weakKeysAndWrongKeyUsageAreRefused() throws Exception {
        EsignTestPki.Party small = pki.userWithWeakKeyForNegativeTest("Asha Verma", pki.ca(), KeyUsage.digitalSignature, 1024,
                EsignTestPki.NOT_AFTER);
        invalid(verifier().verify(expected("T-19", "Asha Verma"),
                answer("T-19", TS, small, EsignTestPki.cmsOverHash(small, hash, SIGNED))), "KEY_NOT_ACCEPTED");
        EsignTestPki.Party enc = pki.user("Asha Verma", pki.ca(), KeyUsage.keyEncipherment, 2048, EsignTestPki.NOT_AFTER);
        invalid(verifier().verify(expected("T-20", "Asha Verma"),
                answer("T-20", TS, enc, EsignTestPki.cmsOverHash(enc, hash, SIGNED))), "KEY_NOT_ACCEPTED");
    }

    @Test
    void aSha1DocumentSignatureIsRefused() throws Exception {
        byte[] cms = EsignTestPki.cmsOverHash(signer, hash, SIGNED, List.of(signer.cert()), "SHA1");
        invalid(verifier().verify(expected("T-21", "Asha Verma"), answer("T-21", TS, signer, cms)),
                "ALGORITHM_NOT_ACCEPTED");
    }

    @Test
    void aCertificateExpiredAtSigningIsRefused() throws Exception {
        EsignTestPki.Party expired = pki.user("Asha Verma", pki.ca(), KeyUsage.digitalSignature,
                2048, NOW.minusSeconds(86_400));
        invalid(verifier().verify(expected("T-22", "Asha Verma"),
                answer("T-22", TS, expired, EsignTestPki.cmsOverHash(expired, hash, SIGNED))),
                "CERTIFICATE_NOT_VALID_AT_SIGNING");
    }

    @Test
    void anIncompletePolicyIsIndeterminateAndNeverValid() throws Exception {
        SignaturePolicy empty = new SignaturePolicy("p0", Set.of(), List.of(), List.of(esp.cert()), null, null, 0, null,
                null);
        indeterminate(new EsignResponseVerification(empty, HttpTransport.forSimulator(), CLOCK)
                .verify(expected("T-23", "Asha Verma"), goodAnswer("T-23")), "POLICY_INCOMPLETE", false);
        assertEquals(0, pki.ocspCalls() + pki.crlCalls(), "nothing is fetched for an incomplete policy");
    }

    @Test
    void theServiceLoaderFindsTheBouncyCastleVerifierAndTheFallbackNeverVerifies() throws Exception {
        EsignResponseVerifier loaded = EsignResponseVerifier.load(policy(PROVIDER_ZONE), HttpTransport.forSimulator(),
                CLOCK).orElseThrow();
        assertInstanceOf(EsignResponseVerification.class, loaded);
        assertEquals(Outcome.VERIFIED_VALID, loaded.verify(expected("T-24", "Asha Verma"), goodAnswer("T-24")).outcome());
        indeterminate(EsignResponseVerifier.unavailable("no module", CLOCK).verify(expected("T-24", "Asha Verma"),
                goodAnswer("T-24")), "VERIFIER_UNAVAILABLE", false);
    }
}
