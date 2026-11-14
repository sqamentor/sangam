// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.signature.EsignResponseVerifier;
import org.sangam.integration.signature.SignatureVerification;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.esign.EsignSimulator;

/** Owner decision 140 (RM-07): the eSign connector never accepts a signature that did not verify. */
class EsignConnectorVerificationTest {

    private static final String PAYLOAD = "{\"approval\":\"A-1\",\"amount\":\"10.00\"}";
    private static final String HASH = Hashes.hex(Hashes.sha256(PAYLOAD));

    private SimulatorProfile sims;
    private EsignSimulator sim;

    @BeforeEach
    void start() {
        sims = new SimulatorProfile();
        sim = sims.esign();
    }

    @AfterEach
    void stop() {
        sims.close();
    }

    private static ConnectorRequest request(UUID op, Map<String, Object> extra) {
        Map<String, Object> in = new HashMap<>();
        in.put("documentHash", HASH);
        in.put("approvalPayload", PAYLOAD);
        in.putAll(extra);
        return new ConnectorRequest("t1", op, "a1", 1, "root-1", null, EsignConnector.OP_SIGN, in, null);
    }

    private EsignConnector connector(ConnectorProfile profile, EsignResponseVerifier verifier) {
        UnaryOperator<String> signer = profile == ConnectorProfile.LIVE ? UnaryOperator.identity() : null;
        return new EsignConnector(new EsignConnector.Config("esign-test", sim.baseUri(), SimulatorProfile.ESIGN_ASP_ID,
                null, signer, HttpTransport.forSimulator(), profile, CapabilityClass.B_LOOKUP_ONLY,
                Duration.ofSeconds(10), verifier, null));
    }

    /** A verifier that returns a fixed outcome and counts its calls. */
    private static final class Fixed implements EsignResponseVerifier {
        final SignatureVerification result;
        final AtomicInteger calls = new AtomicInteger();

        Fixed(SignatureVerification result) {
            this.result = result;
        }

        @Override
        public SignatureVerification verify(org.sangam.integration.signature.EsignExpectation expected, byte[] xml) {
            calls.incrementAndGet();
            return result;
        }
    }

    @Test
    void withoutAVerifierNothingIsSent() {
        EsignConnector c = connector(ConnectorProfile.SIMULATOR, null);
        long before = sim.requestsReceived();
        ConnectorResult r = c.invoke(request(UUID.randomUUID(), Map.of()));
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r);
        assertEquals("ESIGN_VERIFICATION_UNAVAILABLE", f.code());
        assertEquals(before, sim.requestsReceived(), "no request reaches the ESP");
        assertEquals(0, sim.ledger().count(EsignSimulator.EFFECT_SIGN));
    }

    @Test
    void theNineArgumentConfigHasNoVerifierAndRefuses() {
        EsignConnector c = new EsignConnector(new EsignConnector.Config("esign-test", sim.baseUri(),
                SimulatorProfile.ESIGN_ASP_ID, null, null, HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR,
                CapabilityClass.B_LOOKUP_ONLY, Duration.ofSeconds(10)));
        ConnectorResult r = c.invoke(request(UUID.randomUUID(), Map.of()));
        assertEquals("ESIGN_VERIFICATION_UNAVAILABLE", assertInstanceOf(ConnectorResult.BusinessFailure.class, r).code());
    }

    @Test
    void theSimulatorSignatureIsAcceptedOnlyAsLabelledDemoEvidence() {
        EsignConnector c = EsignConnector.simulator(sims);
        UUID op = UUID.randomUUID();
        ConnectorResult r = c.invoke(request(op, Map.of()));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r);
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) s.output().get("verification");
        assertEquals("VERIFIED_VALID", v.get("outcome"));
        assertEquals(true, v.get("demo"));
        assertEquals(SignatureVerification.DEMO_LABEL, v.get("label"));
        assertEquals(SimulatedEsignVerifier.POLICY, v.get("policyVersion"));
        assertTrue(sim.verify(HASH, (String) s.output().get("signature")));
    }

    @Test
    void anInvalidAnswerIsABusinessFailureWithoutTheSignature() {
        Fixed verifier = new Fixed(SignatureVerification.invalid("DIGEST_MISMATCH", "other document", "p1",
                Instant.parse("2026-01-01T00:00:00Z")));
        ConnectorResult r = connector(ConnectorProfile.SIMULATOR, verifier).invoke(request(UUID.randomUUID(), Map.of()));
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r);
        assertEquals("SIGNATURE_INVALID", f.code());
        assertFalse(f.output().containsKey("signature"), "a refused signature is never handed on");
        assertFalse(f.output().containsKey("certificate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) f.output().get("verification");
        assertEquals("VERIFIED_INVALID", v.get("outcome"));
        assertEquals("DIGEST_MISMATCH", v.get("reasonCode"));
        assertEquals(1, verifier.calls.get());
    }

    @Test
    void aRetryableIndeterminateAnswerIsTransientAndTheLookupVerifiesAgain() {
        Fixed verifier = new Fixed(SignatureVerification.indeterminate("REVOCATION_UNAVAILABLE", "OCSP down", true, "p1",
                Instant.parse("2026-01-01T00:00:00Z")));
        EsignConnector c = connector(ConnectorProfile.SIMULATOR, verifier);
        UUID op = UUID.randomUUID();
        ConnectorResult r = c.invoke(request(op, Map.of()));
        ConnectorResult.TransientFailure t = assertInstanceOf(ConnectorResult.TransientFailure.class, r);
        assertFalse(t.noEffect(), "the ESP did sign: the call had an effect");
        LookupResult l = c.lookup(request(op, Map.of()));
        assertInstanceOf(LookupResult.Unavailable.class, l);
        assertEquals(2, verifier.calls.get());
    }

    @Test
    void aFinalIndeterminateAnswerIsABusinessFailure() {
        Fixed verifier = new Fixed(SignatureVerification.indeterminate("UNSUPPORTED_SIGNATURE_FORMAT", "raw RSA", false,
                "p1", Instant.parse("2026-01-01T00:00:00Z")));
        ConnectorResult r = connector(ConnectorProfile.SIMULATOR, verifier).invoke(request(UUID.randomUUID(), Map.of()));
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r);
        assertEquals("SIGNATURE_INDETERMINATE", f.code());
        assertFalse(f.output().containsKey("signature"));
    }

    @Test
    void theLookupNeverReportsAnUnverifiedSignatureAsSigned() {
        UUID op = UUID.randomUUID();
        assertInstanceOf(ConnectorResult.Success.class, EsignConnector.simulator(sims).invoke(request(op, Map.of())));
        Fixed verifier = new Fixed(SignatureVerification.invalid("UNTRUSTED_CHAIN", "no anchor", "p1",
                Instant.parse("2026-01-01T00:00:00Z")));
        LookupResult l = connector(ConnectorProfile.SIMULATOR, verifier).lookup(request(op, Map.of()));
        LookupResult.Found found = assertInstanceOf(LookupResult.Found.class, l);
        assertFalse(found.succeeded());
        assertEquals("SIGNATURE_INVALID", found.failureCode());
        assertFalse(found.output().containsKey("signature"));
        assertInstanceOf(LookupResult.Unavailable.class, connector(ConnectorProfile.SIMULATOR, null)
                .lookup(request(op, Map.of())));
    }

    @Test
    void theLookupOfAVerifiedSignatureCarriesTheEvidence() {
        UUID op = UUID.randomUUID();
        EsignConnector c = EsignConnector.simulator(sims);
        assertInstanceOf(ConnectorResult.Success.class, c.invoke(request(op, Map.of())));
        LookupResult.Found found = assertInstanceOf(LookupResult.Found.class, c.lookup(request(op, Map.of())));
        assertTrue(found.succeeded());
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) found.output().get("verification");
        assertEquals("VERIFIED_VALID", v.get("outcome"));
        assertEquals(true, v.get("demo"));
    }

    @Test
    void theSimulatorVerifierRefusesASignatureOverAnotherDocument() {
        SimulatedEsignVerifier v = new SimulatedEsignVerifier(sim::verify, null);
        String other = Hashes.hex(Hashes.sha256("other"));
        String xml = "<EsignResp status=\"1\" txn=\"T1\"><Signatures><DocSignature id=\"1\">"
                + sim.signatureFor(other) + "</DocSignature></Signatures></EsignResp>";
        SignatureVerification r = v.verify(new org.sangam.integration.signature.EsignExpectation("t1", "T1", "asp",
                HASH, "doc-1", "Signer", null, null), xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(SignatureVerification.Outcome.VERIFIED_INVALID, r.outcome());
        assertEquals("DIGEST_MISMATCH", r.reasonCode());
        SignatureVerification wrongTxn = v.verify(new org.sangam.integration.signature.EsignExpectation("t1", "T2",
                "asp", other, "doc-1", "Signer", null, null), xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("TXN_MISMATCH", wrongTxn.reasonCode());
    }

    @Test
    void aLiveConnectorRefusesTheSimulatorVerifier() {
        assertThrows(IllegalArgumentException.class,
                () -> connector(ConnectorProfile.LIVE, new SimulatedEsignVerifier(sim::verify, null)));
    }

    @Test
    void aLiveConnectorNeedsTheIntendedSignerAndDocumentBeforeSending() {
        Fixed verifier = new Fixed(SignatureVerification.invalid("X", "x", "p1", Instant.parse("2026-01-01T00:00:00Z")));
        EsignConnector c = connector(ConnectorProfile.LIVE, verifier);
        long before = sim.requestsReceived();
        ConnectorResult r = c.invoke(request(UUID.randomUUID(), Map.of("documentId", "DOC-1")));
        assertEquals("SIGNER_NOT_BOUND", assertInstanceOf(ConnectorResult.BusinessFailure.class, r).code());
        r = c.invoke(request(UUID.randomUUID(), Map.of("signerName", "A Signer")));
        assertEquals("SIGNER_NOT_BOUND", assertInstanceOf(ConnectorResult.BusinessFailure.class, r).code());
        assertEquals(before, sim.requestsReceived(), "nothing is sent without the binding inputs");
        assertEquals(0, verifier.calls.get());
    }

    @Test
    void anUnknownResponseSignatureTypeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new EsignConnector.Config("e", sim.baseUri(), "asp", null,
                null, HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR, null, null, null, "rawrsa"));
        assertEquals("PKCS7pdf", connector(ConnectorProfile.SIMULATOR, null).config().responseSigType());
    }
}
