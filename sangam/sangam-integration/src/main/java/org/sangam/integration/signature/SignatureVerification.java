// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owner decision 140 (RM-07): the result of verifying one signature. Three outcomes only; {@link Outcome#VERIFIED_INVALID}
 * and {@link Outcome#INDETERMINATE} never allow a signed or approved transition. This is the signature verification
 * step only: the signing outcome (did the provider sign) and the business authorisation (may this actor approve) are
 * separate decisions.
 *
 * <p>The evidence is what an auditor needs to re-check the decision later, and never a secret: the policy version, the
 * SHA-256 of the signer certificate and of each certificate of the validated path (leaf first), the revocation source
 * per certificate, the signing and time-stamp times, and the time of the check.
 *
 * @param reasonCode   null when valid; otherwise a stable code (for example {@code DIGEST_MISMATCH},
 *                     {@code UNTRUSTED_CHAIN}, {@code REVOKED}, {@code REVOCATION_UNAVAILABLE})
 * @param detail       a readable explanation (no secret)
 * @param retryable    for {@code INDETERMINATE}: true when the same check may succeed later (for example a revocation
 *                     service was unreachable); false when configuration or input must change first
 * @param demo         true for simulator evidence: never a real signature, labelled so wherever it is shown
 */
public record SignatureVerification(Outcome outcome, String reasonCode, String detail, boolean retryable,
                                    String policyVersion, String signerSubject, String signerCertificateSha256,
                                    List<String> chainSha256, List<String> revocationSources, Instant signingTime,
                                    Instant timestampTime, Instant checkedAt, boolean demo) {

    /** The three outcomes of decision 140. */
    public enum Outcome {
        VERIFIED_VALID, VERIFIED_INVALID, INDETERMINATE
    }

    /** Label carried by simulator evidence. */
    public static final String DEMO_LABEL = "DEMO_EVIDENCE_SIMULATED_SIGNATURE";

    public SignatureVerification {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(checkedAt, "checkedAt");
        chainSha256 = chainSha256 == null ? List.of() : List.copyOf(chainSha256);
        revocationSources = revocationSources == null ? List.of() : List.copyOf(revocationSources);
        if (outcome == Outcome.VERIFIED_VALID && reasonCode != null) {
            throw new IllegalArgumentException("a valid verification has no reason code");
        }
        if (outcome != Outcome.VERIFIED_VALID && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("an invalid or indeterminate verification needs a reason code");
        }
        if (outcome != Outcome.INDETERMINATE && retryable) {
            throw new IllegalArgumentException("only an indeterminate verification can be retryable");
        }
    }

    /** True only for {@link Outcome#VERIFIED_VALID}: the one outcome that may allow a signed transition. */
    public boolean allowsSignedTransition() {
        return outcome == Outcome.VERIFIED_VALID;
    }

    public static SignatureVerification invalid(String code, String detail, String policyVersion, Instant at) {
        return new SignatureVerification(Outcome.VERIFIED_INVALID, code, detail, false, policyVersion, null, null, null,
                null, null, null, at, false);
    }

    public static SignatureVerification indeterminate(String code, String detail, boolean retryable, String policyVersion,
                                                      Instant at) {
        return new SignatureVerification(Outcome.INDETERMINATE, code, detail, retryable, policyVersion, null, null, null,
                null, null, null, at, false);
    }

    /** The same verification with the signer identified (kept on invalid results too, for the audit). */
    public SignatureVerification withSigner(String subject, String certificateSha256) {
        return new SignatureVerification(outcome, reasonCode, detail, retryable, policyVersion, subject, certificateSha256,
                chainSha256, revocationSources, signingTime, timestampTime, checkedAt, demo);
    }

    /** Audit evidence as a map without null values (connector outputs refuse nulls). */
    public Map<String, Object> evidence() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", outcome.name());
        put(m, "reasonCode", reasonCode);
        put(m, "detail", detail);
        if (outcome == Outcome.INDETERMINATE) {
            m.put("retryable", retryable);
        }
        put(m, "policyVersion", policyVersion);
        put(m, "signerSubject", signerSubject);
        put(m, "signerCertificateSha256", signerCertificateSha256);
        if (!chainSha256.isEmpty()) {
            m.put("chainSha256", chainSha256);
        }
        if (!revocationSources.isEmpty()) {
            m.put("revocationSources", revocationSources);
        }
        put(m, "signingTime", signingTime == null ? null : signingTime.toString());
        put(m, "timestampTime", timestampTime == null ? null : timestampTime.toString());
        m.put("checkedAt", checkedAt.toString());
        if (demo) {
            m.put("demo", true);
            m.put("label", DEMO_LABEL);
        }
        return m;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
