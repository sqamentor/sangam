// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Seat (post) model as seen by pack delegates (IX.5). WS-SECURITY implements it on the effective-dated posting,
 * additional-charge and delegation tables; the default is {@link org.sangam.packs.sim.InMemorySeatDirectory}, loaded
 * from the pack seed files.
 *
 * <p>Seat ids are used directly as Operaton candidate groups, so a task assigned to a seat is visible to whoever holds
 * the seat on the day (transfer and leave need no reassignment, J-P1-E05).
 */
public interface SeatDirectory {

    /** How {@code personId} holds {@code seatId} on {@code date} (K7), if at all. */
    Optional<SeatHolding> holding(String tenantId, String personId, String seatId, LocalDate date);

    /** All seats {@code personId} holds or acts for on {@code date} (substantive, additional charge, delegation). */
    List<SeatHolding> holdingsOf(String tenantId, String personId, LocalDate date);

    /** Substantive holder of the seat on the date, or the acting holder if the seat is vacant. */
    Optional<String> holderOf(String tenantId, String seatId, LocalDate date);

    /** Role (designation) code of the seat, e.g. {@code EE}, {@code SO}, {@code PIO}; used by the power DMN tables. */
    Optional<String> roleOf(String tenantId, String seatId);

    /** Office (hierarchy node) of the seat. */
    Optional<String> officeOf(String tenantId, String seatId);

    /**
     * The seat holding {@code roleCode} that serves {@code officeId}: the nearest seat with that role at the office or
     * one of its ancestors (IX.5 rule 2: routing refers to hierarchy levels, not to people).
     */
    Optional<String> seatForRole(String tenantId, String roleCode, String officeId);

    /** Seats with {@code roleCode} under {@code officeId} whose holder is available on the date (candidate list S07). */
    List<String> eligibleSeats(String tenantId, String roleCode, String officeId, LocalDate date);

    /** Escalation / reporting seat of {@code seatId} (one level up), used by SLA escalation and power forwarding. */
    Optional<String> escalationSeatOf(String tenantId, String seatId);
}
