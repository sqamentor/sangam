// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.integration.signature.SignatureVerification.Outcome;

/** Owner decision 140: the three outcomes, the binding record, the policy and the absent-module behaviour. */
class SignatureVerificationTest {

    private static final Instant AT = Instant.parse("2026-06-01T10:00:00Z");
    private static final String HASH = "ab".repeat(32);

    @Test
    void onlyVerifiedValidAllowsASignedTransition() {
        SignatureVerification valid = new SignatureVerification(Outcome.VERIFIED_VALID, null, null, false, "p", "CN=A",
                "cd".repeat(32), List.of("cd".repeat(32)), List.of("OCSP x"), AT, null, AT, false);
        assertTrue(valid.allowsSignedTransition());
        assertFalse(SignatureVerification.invalid("DIGEST_MISMATCH", "d", "p", AT).allowsSignedTransition());
        assertFalse(SignatureVerification.indeterminate("REVOCATION_UNAVAILABLE", "d", true, "p", AT)
                .allowsSignedTransition());
    }

    @Test
    void theOutcomeRulesAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new SignatureVerification(Outcome.VERIFIED_VALID, "X", null,
                false, "p", null, null, null, null, null, null, AT, false));
        assertThrows(IllegalArgumentException.class, () -> SignatureVerification.invalid(" ", "d", "p", AT));
        assertThrows(IllegalArgumentException.class, () -> new SignatureVerification(Outcome.VERIFIED_INVALID, "X",
                null, true, "p", null, null, null, null, null, null, AT, false));
        assertThrows(NullPointerException.class, () -> SignatureVerification.invalid("X", "d", "p", null));
    }

    @Test
    void evidenceHasNoNullsAndLabelsDemoResults() {
        Map<String, Object> e = SignatureVerification.indeterminate("REVOCATION_UNAVAILABLE", "down", true, "p", AT)
                .withSigner("CN=A", "cd".repeat(32)).evidence();
        assertEquals("INDETERMINATE", e.get("outcome"));
        assertEquals(true, e.get("retryable"));
        assertEquals("CN=A", e.get("signerSubject"));
        assertFalse(e.containsValue(null));
        assertFalse(e.containsKey("demo"));
        SignatureVerification demo = new SignatureVerification(Outcome.VERIFIED_VALID, null, null, false, "sim", "CN=S",
                null, null, null, null, null, AT, true);
        assertEquals(true, demo.evidence().get("demo"));
        assertEquals(SignatureVerification.DEMO_LABEL, demo.evidence().get("label"));
    }

    @Test
    void theExpectationNeedsTheBindingFields() {
        EsignExpectation e = new EsignExpectation("t", "T-1", "ASP", HASH.toUpperCase(), "DOC", "Asha  Verma", null, AT);
        assertEquals(HASH, e.documentHashHex());
        assertTrue(e.namesIntendedSigner(" asha verma "));
        assertFalse(e.namesIntendedSigner("Asha Varma"));
        assertFalse(e.namesIntendedSigner(null));
        assertThrows(IllegalArgumentException.class,
                () -> new EsignExpectation("t", "T-1", "ASP", "abc", "DOC", "A", null, AT));
        assertThrows(IllegalArgumentException.class,
                () -> new EsignExpectation("t", "T-1", "ASP", HASH, " ", "A", null, AT));
        assertThrows(IllegalArgumentException.class,
                () -> new EsignExpectation("t", "T-1", "ASP", HASH, "DOC", null, null, AT));
        assertThrows(IllegalArgumentException.class,
                () -> new EsignExpectation("t", "T-1", "ASP", HASH, "DOC", "A", "zz", AT));
        assertNull(new EsignExpectation("t", "T-1", "ASP", HASH, "DOC", "A", null, null).requestedAt(),
                "a status lookup has no request time");
    }

    @Test
    void aPolicyWithoutAnchorsOrEspCertificatesIsIncompleteAndRevocationCannotBeSwitchedOff() {
        SignaturePolicy p = new SignaturePolicy("v1", null, null, null, null, null, 1024, null, null);
        assertEquals("no trust anchors are configured", p.incomplete());
        assertEquals(2048, p.minRsaBits(), "a smaller minimum is raised to 2048");
        assertTrue(p.revocationTimeout().toMillis() > 0);
        assertFalse(java.util.Arrays.stream(SignaturePolicy.class.getRecordComponents())
                .anyMatch(c -> c.getName().toLowerCase().contains("revocationenabled")
                        || c.getName().toLowerCase().contains("skiprevocation")), "no revocation off switch");
    }

    @Test
    void theUnavailableVerifierNeverVerifies() {
        SignatureVerification v = EsignResponseVerifier.unavailable("module absent",
                Clock.fixed(AT, ZoneOffset.UTC)).verify(
                new EsignExpectation("t", "T-1", "ASP", HASH, "DOC", "A", null, AT), new byte[]{1});
        assertEquals(Outcome.INDETERMINATE, v.outcome());
        assertEquals("VERIFIER_UNAVAILABLE", v.reasonCode());
        assertFalse(v.retryable());
    }
}
