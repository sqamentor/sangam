// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Instant;
import java.util.function.BiFunction;

import org.sangam.rest.adapter.operaton.WorkspaceSeatHolders;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.support.Sql;

/**
 * Register E-32(c) and E-31(a): the officer who holds a seat substantively now (on leave or not), with the display
 * name of the person record, from sangam-security's postings at the database time. The workspace asks it for two
 * things only: the officer a caller acts for, for the seats of the caller's own acting holdings ({@code GET /me}
 * {@code actingForPersonName}, S02 "Acting for ..."), and the holder of the seat an officer names as the substitute
 * in a leave request (S14).
 *
 * <p>It is not the administrators' posting directory ({@code SecurityDirectoryService.postings}, READ on POSTING),
 * which an officer may not read and which stays closed: this answers one seat's current substantive holder, nothing
 * about other postings, dates or history. Null for a vacant seat.
 */
public final class SeatHolderDirectory implements BiFunction<RequestContext, String, WorkspaceSeatHolders.SeatHolderRef> {

    private final SangamSecurity security;
    private final TxSupport tx;

    public SeatHolderDirectory(SangamSecurity security, TxSupport tx) {
        this.security = security;
        this.tx = tx;
    }

    @Override
    public WorkspaceSeatHolders.SeatHolderRef apply(RequestContext ctx, String seatId) {
        if (seatId == null || ctx.tenantId() == null) {
            return null;
        }
        return tx.withConnection(c -> {
            Instant now = Sql.dbNow(c, security.dialect());
            return security.postings().regularHolderAt(c, ctx.tenantId(), seatId, now)
                    .map(person -> new WorkspaceSeatHolders.SeatHolderRef(person,
                            security.persons().find(c, ctx.tenantId(), person).map(p -> p.displayName()).orElse(null)))
                    .orElse(null);
        });
    }
}
