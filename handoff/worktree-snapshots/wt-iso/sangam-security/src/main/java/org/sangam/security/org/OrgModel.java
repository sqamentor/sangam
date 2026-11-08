// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.org;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Value types of the organisation model (SANGAM.md IX.5: seat ≠ person, hierarchy, effective-dated postings). */
public final class OrgModel {

    private OrgModel() {
    }

    /** A hierarchy node: Ministry/Department → Directorate → Region/District → Office → Section (IX.5 rule 2). */
    public record OrgUnit(String tenantId, String unitId, String levelType, int levelNo, String nameEn, String nameHi,
                          String headSeatId, String parentId, String status) {
    }

    /** A post: designation + office (IX.5 rule 1). Seat ids are stable and never reused (AJ-02 step 2). */
    public record Seat(String tenantId, String seatId, String designation, int rankNo, boolean pool,
                       String escalationSeatId, String unitId, String status) {
    }

    public enum PostingKind {
        /** Substantive holder of the seat. */
        REGULAR,
        /** Additional charge of a seat (usually vacant), "acted for" in audit (IX.5 rule 4, AJ-04 step 2). */
        ADDITIONAL_CHARGE,
        /** Substitute while the regular holder is on leave (IX.5 rule 4, AJ-04 step 5). */
        LEAVE_SUBSTITUTE;

        public boolean acting() {
            return this != REGULAR;
        }
    }

    /** An effective-dated assignment of a person to a seat; {@code validTo} is exclusive, null is open-ended. */
    public record Posting(String tenantId, String postingId, String seatId, String personId, PostingKind kind,
                          Instant validFrom, Instant validTo, String orderRef, BigDecimal powerCap) {
    }

    public record Leave(String tenantId, String leaveId, String personId, Instant validFrom, Instant validTo,
                        String substitutePersonId, String approvedBy) {
    }

    public record Person(String tenantId, String personId, String displayName, String externalSubject, String email,
                         String status) {
    }

    /** AJ-04 step 1 preview of a transfer. */
    public record TransferPreview(String personId, String fromSeatId, String toSeatId, List<String> casesActedOn,
                                  List<String> openSeatTasks) {
    }
}
