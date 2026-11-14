// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

/**
 * The external policy service gave no usable answer (owner decision 166: fails closed for commands that require it).
 * The message never carries a credential, a request body or a response body.
 */
public final class ExternalPolicyUnavailable extends Exception {

    public enum Reason {
        /** No answer within the configured connect or request timeout. */
        TIMEOUT,
        /** Connection or I/O failure, or the check was interrupted. */
        TRANSPORT,
        /** An HTTP status other than 200. */
        BAD_STATUS,
        /** A 200 answer that is not a decision (not JSON, too large, no decision member, unknown decision value). */
        BAD_RESPONSE,
        /** The check cannot be made as configured (for example an empty credential file). */
        NOT_CONFIGURED,
        /** The check implementation failed unexpectedly. */
        CHECK_FAILED
    }

    private final Reason reason;

    public ExternalPolicyUnavailable(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public ExternalPolicyUnavailable(Reason reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
