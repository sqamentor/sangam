// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.port.SeatHoldings;
import org.sangam.rest.support.BusinessDates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The caller's {@link SeatHoldings} when the security module is off (a development start only, decision 75): then the
 * seat directory is the default admin store behind {@link AdminPort}, the one {@code GET /me} lists the caller's seats
 * from. A seat is held when an assignment of the person to it covers the tenant's business date and was not ended;
 * every kind but SUBSTANTIVE is an acting holding (additional charge, leave substitute). With the security module on,
 * {@code SecurityIdentityFilter} resolves the holdings from sangam-security's postings and this class is not used.
 * A directory that cannot be read gives no seat (fail closed).
 */
public final class AdminStoreSeatHoldings implements Function<SangamPrincipal, SeatHoldings> {

    private static final Logger LOG = LoggerFactory.getLogger(AdminStoreSeatHoldings.class);

    private final Supplier<AdminPort> admin;
    private final BusinessDates dates;

    /** @param admin looked up per call: the port is created after the security filter chain */
    public AdminStoreSeatHoldings(Supplier<AdminPort> admin, BusinessDates dates) {
        this.admin = admin;
        this.dates = dates;
    }

    @Override
    public SeatHoldings apply(SangamPrincipal p) {
        AdminPort port = admin.get();
        if (port == null || p == null || p.tenantId() == null || p.principalId() == null) {
            return SeatHoldings.NONE;
        }
        try {
            LocalDate today = dates.today(p.tenantId());
            RequestContext self = new RequestContext(p.tenantId(), p.principalId(), p.groups(), null, null, null, null,
                    null, p.authMethod());
            return of(port.assignments(self, new AdminPort.AdminFilter(null, null, null, null, p.principalId(), null,
                    today, false), new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items(), p.principalId());
        } catch (RuntimeException e) {
            LOG.warn("the seats of a caller could not be read from the admin store; no seat is accepted: {}", e.toString());
            return SeatHoldings.NONE;
        }
    }

    /** The holdings in a list of assignments in force on the date: the person's own, not ended. */
    static SeatHoldings of(List<AdminPort.Assignment> inForce, String personId) {
        Set<String> seats = new LinkedHashSet<>();
        Set<String> acting = new LinkedHashSet<>();
        for (AdminPort.Assignment a : inForce) {
            if (a.ended() || a.seatId() == null || !personId.equals(a.personId())) {
                continue;
            }
            seats.add(a.seatId());
            if (!"SUBSTANTIVE".equals(a.kind())) {
                acting.add(a.seatId());
            }
        }
        return new SeatHoldings(seats, acting);
    }
}
