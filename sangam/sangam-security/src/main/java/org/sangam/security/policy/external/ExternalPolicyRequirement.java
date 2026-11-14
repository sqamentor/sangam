// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

/** How one command uses the external deny-only policy check (owner decision 166, M-083). */
public enum ExternalPolicyRequirement {
    /** Checked; a DENY refuses the command, and an unavailable service refuses it too (fail closed). */
    REQUIRED,
    /** Checked; a DENY refuses the command, but an unavailable service lets it continue to Sangam's own checks. */
    BEST_EFFORT,
    /** Not checked; no remote call is made. */
    NONE
}
