// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.BusinessDates;

/**
 * Who holds which seat, as {@link OperatonWorkspaceAdapter} needs it (IX.5 rules 1 and 4, K7): the caller's own seats
 * and the substantive holder of one seat. Two sources:
 * <ul>
 *   <li>the host's seat directory, when bound ({@link #setOwnHoldings}, {@link #setSeatHolders}): what the person can
 *       act in now and who holds a seat substantively, without any admin permission;</li>
 *   <li>otherwise the {@link AdminPort} assignment list in force today (the default adapter; under the security
 *       module an officer may not read it).</li>
 * </ul>
 */
public final class WorkspaceSeatHolders {

    /** The officer holding a seat substantively: person id and display name (name null when the directory has none). */
    public record SeatHolderRef(String personId, String name) {
    }

    private final AdminPort admin;
    private final BusinessDates dates;
    private volatile Function<RequestContext, List<AdminPort.Assignment>> ownHoldings;
    private volatile BiFunction<RequestContext, String, SeatHolderRef> seatHolders;

    WorkspaceSeatHolders(AdminPort admin, BusinessDates dates) {
        this.admin = admin;
        this.dates = dates;
    }

    void setOwnHoldings(Function<RequestContext, List<AdminPort.Assignment>> ownHoldings) {
        this.ownHoldings = ownHoldings;
    }

    void setSeatHolders(BiFunction<RequestContext, String, SeatHolderRef> seatHolders) {
        this.seatHolders = seatHolders;
    }

    /** The caller's seats: the bound directory, else the caller's assignments in force today (none when refused). */
    List<AdminPort.Assignment> myAssignments(RequestContext ctx) {
        Function<RequestContext, List<AdminPort.Assignment>> own = ownHoldings;
        if (own != null) {
            return own.apply(ctx);
        }
        try {
            return admin.assignments(ctx, new AdminPort.AdminFilter(null, null, null, null, ctx.principalId(), null,
                    dates.today(ctx.tenantId()), false), new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items();
        } catch (ApiProblem e) {
            return List.of();
        }
    }

    /** The assignments to a seat in force today, from the admin port (none when refused). */
    List<AdminPort.Assignment> seatAssignments(RequestContext ctx, String seatId) {
        try {
            return admin.assignments(ctx, new AdminPort.AdminFilter(null, null, null, seatId, null, null,
                    dates.today(ctx.tenantId()), false), new Page.PageRequest(0, Page.PageRequest.MAX_SIZE)).items();
        } catch (ApiProblem e) {
            return List.of();
        }
    }

    /** The person holding a seat substantively today by the admin port, or null. */
    private String holderOf(RequestContext ctx, String seatId) {
        return seatAssignments(ctx, seatId).stream().filter(a -> "SUBSTANTIVE".equals(a.kind()))
                .map(AdminPort.Assignment::personId).findFirst().orElse(null);
    }

    /** The substantive holder of a seat, or null: the bound directory, else the admin port. */
    private SeatHolderRef seatHolder(RequestContext ctx, String seatId) {
        BiFunction<RequestContext, String, SeatHolderRef> bound = seatHolders;
        if (bound != null) {
            return bound.apply(ctx, seatId);
        }
        if (ownHoldings != null) {
            return null;   // the caller's seats come from the host, and it gave no holder directory: nobody is named
        }
        String person = holderOf(ctx, seatId);
        return person == null ? null : new SeatHolderRef(person, null);
    }

    /** Whom the caller acts for in a seat: the holder's display name, else the person id, else null (vacant seat). */
    String actedForName(RequestContext ctx, String seatId) {
        SeatHolderRef h = seatHolder(ctx, seatId);
        return h == null ? null : h.name() != null && !h.name().isBlank() ? h.name() : h.personId();
    }

    /** The person id of a seat's substantive holder (the substitute seat of a leave request), or null. */
    String holderId(RequestContext ctx, String seatId) {
        BiFunction<RequestContext, String, SeatHolderRef> bound = seatHolders;
        if (bound == null) {
            return holderOf(ctx, seatId);
        }
        SeatHolderRef h = bound.apply(ctx, seatId);
        return h == null ? null : h.personId();
    }
}
