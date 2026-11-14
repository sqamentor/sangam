// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.sangam.integration.signature.EsignExpectation;
import org.sangam.integration.signature.EsignResponseVerifier;
import org.sangam.integration.signature.SignatureVerification;
import org.w3c.dom.Element;

/**
 * Owner decision 140, simulator profile only: checks an {@code EsignSimulator} answer with the simulator's own key
 * (the fake "signature" is an HMAC over the document hash) and the same txn binding as the real verifier. A pass is
 * VERIFIED_VALID with {@code demo=true} and the label {@value SignatureVerification#DEMO_LABEL}: demo evidence, never
 * a real signature. {@link EsignConnector.Config} refuses this verifier on a LIVE connector.
 */
public final class SimulatedEsignVerifier implements EsignResponseVerifier {

    /** The simulator's check: true when {@code signatureB64} is its signature over {@code hashHex}. */
    public interface SimulatorKey {
        boolean verify(String hashHex, String signatureB64);
    }

    static final String POLICY = "simulator-demo";

    private final SimulatorKey key;
    private final Clock clock;

    public SimulatedEsignVerifier(SimulatorKey key, Clock clock) {
        this.key = Objects.requireNonNull(key, "key");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public SignatureVerification verify(EsignExpectation expected, byte[] responseXml) {
        Instant now = clock.instant();
        Element root;
        try {
            root = GovXml.parse(responseXml).getDocumentElement();
        } catch (Exception e) {
            return SignatureVerification.invalid("MALFORMED_RESPONSE", "not well-formed XML", POLICY, now);
        }
        if (!"EsignResp".equals(root.getTagName()) || !"1".equals(root.getAttribute("status"))) {
            return SignatureVerification.invalid("NOT_A_SIGNED_RESPONSE", "not an EsignResp with status 1", POLICY, now);
        }
        if (!expected.txn().equals(root.getAttribute("txn"))) {
            return SignatureVerification.invalid("TXN_MISMATCH", "the response is for another transaction", POLICY, now);
        }
        List<Element> sigs = GovXml.elements(root, "DocSignature");
        if (sigs.size() != 1) {
            return SignatureVerification.invalid("MALFORMED_RESPONSE", "expected exactly one DocSignature", POLICY, now);
        }
        if (!key.verify(expected.documentHashHex(), sigs.get(0).getTextContent().trim())) {
            return SignatureVerification.invalid("DIGEST_MISMATCH", "the simulated signature is over another document",
                    POLICY, now);
        }
        return new SignatureVerification(SignatureVerification.Outcome.VERIFIED_VALID, null, null, false, POLICY,
                "CN=" + expected.intendedSignerName() + " (simulated)", null, null, null, null, null, now, true);
    }
}
