// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.rights;

/**
 * Neutral categories of data-subject rights. A jurisdiction pack's {@code rights} map its own rights onto these
 * (IN: s.11 → ACCESS, s.12(2) → RECTIFICATION, s.12(3) → ERASURE, s.13 → GRIEVANCE, s.14 → NOMINATION;
 * US-HIPAA: 164.524 → ACCESS, 164.526 → RECTIFICATION, 164.528 → ACCOUNTING, 164.522 → RESTRICTION;
 * EU-GDPR: Arts 15-22 → ACCESS, RECTIFICATION, ERASURE, RESTRICTION, PORTABILITY, OBJECTION, AUTOMATED_DECISION).
 */
public enum RightCategory {
    ACCESS, RECTIFICATION, ERASURE, GRIEVANCE, NOMINATION, OBJECTION, RESTRICTION, PORTABILITY,
    /** Accounting of disclosures (who received the data). */
    ACCOUNTING,
    /** Human review of a decision based solely on automated processing. */
    AUTOMATED_DECISION
}
