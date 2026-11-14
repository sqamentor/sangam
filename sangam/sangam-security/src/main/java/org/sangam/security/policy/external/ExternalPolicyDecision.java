// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

import java.util.regex.Pattern;

/**
 * The answer of the external deny-only policy service (owner decision 166, M-083). There is no "grant": the best answer
 * is {@link Outcome#ALLOW_CONTINUE}, which only lets the command go on to Sangam's own authorisation, which still decides
 * (deny-only AND semantics, backlog M-083: the external policy can only restrict).
 *
 * @param outcome    ALLOW_CONTINUE or DENY
 * @param reasonCode for a DENY: the service's reason, reduced to a safe token ({@link #safeToken}); null otherwise
 * @param policyId   the policy (or policy version) id the service named, reduced to a safe token; may be null
 */
public record ExternalPolicyDecision(Outcome outcome, String reasonCode, String policyId) {

    public enum Outcome {
        /** The external policy does not object; Sangam's own authorisation still decides. */
        ALLOW_CONTINUE,
        /** The external policy refuses the command. */
        DENY
    }

    /** Reason code of a DENY whose service gave none (or one that is not a safe token). */
    public static final String UNSPECIFIED = "UNSPECIFIED";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public ExternalPolicyDecision {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        policyId = safeToken(policyId);
        if (outcome == Outcome.DENY) {
            String r = safeToken(reasonCode);
            reasonCode = r == null ? UNSPECIFIED : r;
        } else {
            reasonCode = null;
        }
    }

    public static ExternalPolicyDecision allowContinue(String policyId) {
        return new ExternalPolicyDecision(Outcome.ALLOW_CONTINUE, null, policyId);
    }

    public static ExternalPolicyDecision deny(String reasonCode, String policyId) {
        return new ExternalPolicyDecision(Outcome.DENY, reasonCode, policyId);
    }

    public boolean denied() {
        return outcome == Outcome.DENY;
    }

    /**
     * A value of the remote service that may reach a problem response, a log line or the audit: kept only when it is a
     * short token (1 to 64 characters) of letters, digits and {@code _ . -}; anything else (free text, markup, a value that might carry
     * personal data) becomes null.
     */
    public static String safeToken(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return SAFE.matcher(v).matches() ? v : null;
    }
}
