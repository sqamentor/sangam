// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.support.Sql;

/**
 * The caller's own seats for the workspace ({@code GET /me}, S02: "only seats valid today (K7) offered"), from
 * sangam-security's seat directory: {@code PostingService.seatsOf} at the database time for the request's resolved
 * person. It is the rule the seat context of a request is checked with ({@code SecurityIdentityFilter},
 * {@code SeatHoldings}), so every seat offered here is accepted when the UI sends it back, and no other is: the
 * regular posting of a person who is not on leave, else additional charge or leave substitution in force; never an
 * ended or a future posting.
 *
 * <p>Reading one's own seats needs no permission. The administrators' posting directory
 * ({@code SecurityDirectoryService.postings}, READ on POSTING) is not used: an officer may not read it, which is why
 * {@code /me} offered an officer no seat while it was the source.
 *
 * <p>Each holding is an {@link AdminPort.Assignment}: id = posting id, kind SUBSTANTIVE, ADDITIONAL_CHARGE or
 * LEAVE_SUBSTITUTE, {@code actedForSeatId} = the seat for an acting holding, {@code validTo} = the last day the
 * posting is in force (dates are UTC days, as {@code SecurityAdminBridge} writes them).
 */
public final class OwnSeatHoldings implements Function<RequestContext, List<AdminPort.Assignment>> {

    private final SangamSecurity security;
    private final TxSupport tx;

    public OwnSeatHoldings(SangamSecurity security, TxSupport tx) {
        this.security = security;
        this.tx = tx;
    }

    @Override
    public List<AdminPort.Assignment> apply(RequestContext ctx) {
        SecurityIdentity who = SecurityIdentities.of(ctx);
        if (who.system() || "API_KEY".equals(who.authMethod())) {
            // E-31(e): an API token holds no seat (SecurityIdentityFilter refuses a seat header from one), so it is
            // offered none; what is offered here is exactly what is accepted when sent back
            return List.of();
        }
        return tx.withConnection(c -> {
            Instant now = Sql.dbNow(c, security.dialect());
            List<AdminPort.Assignment> out = new ArrayList<>();
            for (SecurityPorts.SeatHolder h : security.postings().seatsOf(c, ctx.tenantId(), who.personId(), now)) {
                out.add(assignment(ctx.tenantId(), h));
            }
            return out;
        });
    }

    static AdminPort.Assignment assignment(String tenantId, SecurityPorts.SeatHolder h) {
        String kind = "REGULAR".equals(h.kind()) ? "SUBSTANTIVE" : h.kind();
        return new AdminPort.Assignment(h.postingId(), tenantId, h.seatId(), h.personId(), kind, day(h.validFrom()),
                h.validTo() == null ? null : day(h.validTo().minusNanos(1)), h.actingFor() ? h.seatId() : null, null, null,
                null, false);
    }

    private static LocalDate day(Instant i) {
        return i == null ? null : LocalDate.ofInstant(i, ZoneOffset.UTC);
    }
}
