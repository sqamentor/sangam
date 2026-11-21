// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.Set;

/**
 * The seats the authenticated caller may act in at the time of this request (IX.5 rules 1 and 4, K7), as the host
 * resolved them from its seat directory: regular postings, additional charge and leave substitution that are in force
 * now. The host puts it on the servlet request as attribute {@link #ATTRIBUTE} next to the {@link SangamPrincipal}.
 * A seat never comes from the caller: the seat context the caller names ({@code X-Sangam-Seat},
 * {@code X-Sangam-Acting-For-Seat}) is accepted only when it is in here, and a request for which no host resolved any
 * holding names no seat at all ({@link #require}: 403 {@code SEAT_NOT_HELD}, fail closed).
 *
 * @param seats     every seat the caller can act in now, on any basis
 * @param actingFor the subset of {@code seats} held by additional charge or as leave substitute ("acted for")
 */
public record SeatHoldings(Set<String> seats, Set<String> actingFor) {

    public static final String ATTRIBUTE = SeatHoldings.class.getName();

    /** Problem code of a seat context the caller does not hold now. */
    public static final String NOT_HELD = "SEAT_NOT_HELD";

    /** No seat: what a caller without a posting holds. */
    public static final SeatHoldings NONE = new SeatHoldings(Set.of(), Set.of());

    public SeatHoldings {
        seats = seats == null ? Set.of() : Set.copyOf(seats);
        actingFor = actingFor == null ? Set.of() : Set.copyOf(actingFor);
    }

    /**
     * The seat context of a request: {@code seatId} must be a seat the caller holds now on any basis, and
     * {@code actingForSeatId} one held by additional charge or as leave substitute. Null names are not checked.
     *
     * @param held the holdings the host resolved for this request, or null when it resolved none
     * @throws ApiProblem 403 {@value #NOT_HELD} for a named seat that is not held
     */
    public static void require(SeatHoldings held, String seatId, String actingForSeatId) {
        if (seatId != null && (held == null || !held.seats().contains(seatId))) {
            throw ApiProblem.forbidden(NOT_HELD, "You do not hold seat '" + seatId + "' now");
        }
        if (actingForSeatId != null && (held == null || !held.actingFor().contains(actingForSeatId))) {
            throw ApiProblem.forbidden(NOT_HELD, "You do not act for seat '" + actingForSeatId + "' now");
        }
    }
}
