// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.rights;

/**
 * Neutral categories of data-subject rights. A jurisdiction pack's {@code rights} map its own rights onto these
 * (IN: s.11 → ACCESS, s.12(2) → RECTIFICATION, s.12(3) → ERASURE, s.13 → GRIEVANCE, s.14 → NOMINATION); other packs
 * can use OBJECTION, RESTRICTION or PORTABILITY.
 */
public enum RightCategory { ACCESS, RECTIFICATION, ERASURE, GRIEVANCE, NOMINATION, OBJECTION, RESTRICTION, PORTABILITY }
