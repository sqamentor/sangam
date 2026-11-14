// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

/**
 * Port to an external deny-only policy decision point (OPA, a Cedar agent or any service speaking the request shape of
 * {@link ExternalPolicyRequest#CONTRACT}), owner decision 166, M-083. It is a remote call, so it is made only at command
 * entry, before any idempotency claim, transaction or L2 lock ({@link ExternalPolicyGate}, decision 86); no in-process
 * policy library is used.
 */
@FunctionalInterface
public interface ExternalPolicyCheck {

    /**
     * Asks the policy service about one command.
     *
     * @return ALLOW_CONTINUE or DENY, never null
     * @throws ExternalPolicyUnavailable when the service gives no usable answer (timeout, transport, status, format)
     */
    ExternalPolicyDecision check(ExternalPolicyRequest request) throws ExternalPolicyUnavailable;
}
