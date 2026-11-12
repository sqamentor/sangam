// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.roster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.sangam.resources.roster.RosterModel.AssignmentKind;
import org.sangam.resources.roster.RosterModel.AssignmentState;
import org.sangam.resources.roster.RosterModel.RosterAssignment;

/** M-130: when a bulk roster row's earlier assignment still matches its command, the row is idempotent. */
class RosterBulkApplyMatchTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    private static RosterAssignment existing(AssignmentKind kind, String dutySeat) {
        return new RosterAssignment("t1", "a1", "per1", "p1", "seat1", "sh1", DAY, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600),
                kind, null, dutySeat, null, AssignmentState.DRAFT, null, null, null, 1L);
    }

    private static RosterService.AssignCommand cmd(AssignmentKind kind, String dutySeat, String shift) {
        return new RosterService.AssignCommand("per1", "p1", shift, DAY, kind, null, dutySeat, null, false, null);
    }

    @Test
    void theSameCommandMatches() {
        assertTrue(RosterBulkApply.matches(existing(AssignmentKind.DUTY, "ds1"), cmd(AssignmentKind.DUTY, "ds1", "sh1")));
    }

    @Test
    void aMissingKindMeansDuty() {
        assertTrue(RosterBulkApply.matches(existing(AssignmentKind.DUTY, null), cmd(null, null, "sh1")));
        assertFalse(RosterBulkApply.matches(existing(AssignmentKind.ON_CALL, null), cmd(null, null, "sh1")));
    }

    @Test
    void anyChangedFieldDoesNotMatch() {
        assertFalse(RosterBulkApply.matches(existing(AssignmentKind.DUTY, "ds1"), cmd(AssignmentKind.DUTY, "ds2", "sh1")));
        assertFalse(RosterBulkApply.matches(existing(AssignmentKind.DUTY, "ds1"), cmd(AssignmentKind.DUTY, "ds1", "sh2")));
    }
}
