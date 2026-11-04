// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.time.LocalDate;

/**
 * How a person holds a seat on a date (IX.5 rules 1 and 4).
 *
 * @param seatId          the seat acted as
 * @param personId        the person acting
 * @param basis           substantive posting, additional charge, or delegation
 * @param authorityRef    posting / additional-charge order / delegation id (audit {@code authority_basis})
 * @param validFrom       first day (inclusive)
 * @param validTo         last day (inclusive), null when open-ended
 */
public record SeatHolding(String seatId, String personId, Basis basis, String authorityRef, LocalDate validFrom,
                          LocalDate validTo) {

    public enum Basis { SUBSTANTIVE, ADDITIONAL_CHARGE, DELEGATION }

    /** True when the action must be shown as "acted for" (IX.5 rule 4). */
    public boolean actedFor() {
        return basis != Basis.SUBSTANTIVE;
    }

    public boolean validOn(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }
}
