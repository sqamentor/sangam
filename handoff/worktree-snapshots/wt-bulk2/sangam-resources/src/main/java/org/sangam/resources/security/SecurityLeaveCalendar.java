// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.security;

import org.sangam.core.layer.Jdbc;
import org.sangam.resources.port.ResourcesPorts;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;

/**
 * {@link ResourcesPorts.LeaveCalendar} over the leave records of sangam-security ({@code SEC_LEAVE}, written by
 * {@code PostingService.recordLeave}). This is a read-only adapter over that module's table in the same database; it
 * reads only approved leave and never writes. sangam-security exposes no leave query port yet, so the integrator may
 * replace this adapter once one exists.
 */
public final class SecurityLeaveCalendar implements ResourcesPorts.LeaveCalendar {

    @Override
    public List<ResourcesPorts.LeaveInterval> leaveOf(Connection c, String tenantId, String personId, Instant from,
                                                      Instant to) {
        return Jdbc.query(c, "SELECT leave_id, person_id, valid_from, valid_to, substitute_person_id FROM SEC_LEAVE"
                        + " WHERE tenant_id=? AND person_id=? AND status='APPROVED' AND valid_from < ? AND valid_to > ?"
                        + " ORDER BY valid_from",
                rs -> new ResourcesPorts.LeaveInterval(rs.getString("person_id"), Jdbc.instant(rs, "valid_from"),
                        Jdbc.instant(rs, "valid_to"), rs.getString("substitute_person_id"), "leave:" + rs.getString("leave_id")),
                tenantId, personId, to, from);
    }
}
