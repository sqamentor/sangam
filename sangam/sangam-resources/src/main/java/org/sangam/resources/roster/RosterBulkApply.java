// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.roster;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.roster.RosterModel.AssignmentKind;
import org.sangam.resources.roster.RosterModel.RosterAssignment;
import org.sangam.resources.support.ResourcesException;

import java.sql.Connection;
import java.util.Objects;

/**
 * The bulk entry point for roster duties (M-130), over the public {@link RosterService} API only, so every rule,
 * guard, claim and event of a manual assignment applies unchanged. All work is on the caller's connection.
 *
 * <ul>
 *   <li>No earlier assignment for the row: {@link RosterService#assign}. A person already rostered then is refused
 *       ({@code DOUBLE_ROSTERED}) and nothing is adopted silently.</li>
 *   <li>The row's earlier assignment still matches the command: returned unchanged (idempotent, no event).</li>
 *   <li>It differs: the earlier assignment is removed with {@link RosterService#unassign} and the new one assigned, in
 *       the same transaction. If the earlier assignment is gone (removed by hand), the new one is assigned.</li>
 * </ul>
 */
public final class RosterBulkApply {

    static final String REPLACED = "replaced by a bulk roster row";

    private final RosterService roster;

    public RosterBulkApply(RosterService roster) {
        this.roster = Objects.requireNonNull(roster);
    }

    /**
     * @param previousAssignmentId the assignment this row created before, or {@code null}
     */
    public RosterAssignment apply(Connection c, SecurityIdentity actor, RosterService.AssignCommand cmd, String previousAssignmentId) {
        if (previousAssignmentId != null) {
            RosterAssignment old = previous(c, actor, previousAssignmentId);
            if (old != null) {
                if (matches(old, cmd)) {
                    return old;
                }
                roster.unassign(c, actor, old.assignmentId(), old.version(), REPLACED);
            }
        }
        return roster.assign(c, actor, cmd);
    }

    private RosterAssignment previous(Connection c, SecurityIdentity actor, String assignmentId) {
        try {
            return roster.assignment(c, actor, assignmentId);
        } catch (ResourcesException e) {
            if (e.code() == ResourcesException.Code.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    static boolean matches(RosterAssignment a, RosterService.AssignCommand cmd) {
        AssignmentKind kind = cmd.kind() == null ? AssignmentKind.DUTY : cmd.kind();
        return a.periodId().equals(cmd.periodId()) && a.personId().equals(cmd.personId()) && a.shiftId().equals(cmd.shiftId())
                && a.dutyDay().equals(cmd.day()) && a.kind() == kind && Objects.equals(a.demandId(), cmd.demandId())
                && Objects.equals(a.dutySeatId(), cmd.dutySeatId()) && Objects.equals(a.locationId(), cmd.locationId());
    }
}
