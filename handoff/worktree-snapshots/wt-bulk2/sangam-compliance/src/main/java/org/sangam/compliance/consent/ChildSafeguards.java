// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.consent;

/**
 * Neutral vocabulary for child safeguards. Which flags a law restricts for children, and which exemptions lift them,
 * come from the jurisdiction pack ({@code childExemptions}); e.g. the IN pack maps DPDP s.9(2)-(3) and the Fourth
 * Schedule.
 */
public final class ChildSafeguards {

    private ChildSafeguards() {
    }

    /** What a processing activity does that child-protection rules restrict. */
    public enum ProcessingFlag {
        TRACKING,
        BEHAVIOURAL_MONITORING,
        TARGETED_ADVERTISING,
        /** Processing likely to harm a child's well-being. Never exempt. */
        DETRIMENTAL_TO_WELLBEING
    }
}
