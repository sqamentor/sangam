// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import org.sangam.core.security.SecurityPorts;
import org.sangam.packs.port.SeatDirectory;
import org.sangam.packs.port.SeatHolding;
import org.sangam.ports.support.EngineTx;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.org.OrgModel;

/**
 * Packs {@link SeatDirectory} on sangam-security's organisation model (IX.5 rules 1, 2, 4; K7): effective-dated
 * postings, additional charge and delegation from {@code PostingService}, seat designation and office from
 * {@code SeatService}, and the escalation seat. Seat ids are the candidate groups. A date is evaluated at noon in the
 * tenant's business zone ({@link org.sangam.core.time.BusinessClock}), so an order effective "from" a date counts for the whole day.
 */
public final class PacksSeatDirectory implements SeatDirectory {

    private final SangamSecurity security;
    private final TxSupport tx;
    private final org.sangam.core.time.BusinessClock businessClock;

    /** Posting validity dates are business dates in each tenant's zone ({@code businessClock}). */
    public PacksSeatDirectory(SangamSecurity security, TxSupport tx, org.sangam.core.time.BusinessClock businessClock) {
        this.security = security;
        this.tx = tx;
        this.businessClock = java.util.Objects.requireNonNull(businessClock, "businessClock");
    }

    @Override
    public Optional<SeatHolding> holding(String tenantId, String personId, String seatId, LocalDate date) {
        return holdingsOf(tenantId, personId, date).stream().filter(h -> h.seatId().equals(seatId)).findFirst();
    }

    @Override
    public List<SeatHolding> holdingsOf(String tenantId, String personId, LocalDate date) {
        Instant at = at(tenantId, date);
        return db(c -> security.postings().seatsOf(c, tenantId, personId, at)).stream().map(h -> map(h, businessClock.zone(tenantId))).toList();
    }

    @Override
    public Optional<String> holderOf(String tenantId, String seatId, LocalDate date) {
        List<SecurityPorts.SeatHolder> holders = db(c -> security.postings().holdersAt(c, tenantId, seatId, at(tenantId, date)));
        return holders.stream().filter(h -> !h.actingFor()).findFirst()
                .or(() -> holders.stream().findFirst())
                .map(SecurityPorts.SeatHolder::personId);
    }

    @Override
    public Optional<String> roleOf(String tenantId, String seatId) {
        return seat(tenantId, seatId).map(OrgModel.Seat::designation);
    }

    @Override
    public Optional<String> officeOf(String tenantId, String seatId) {
        return seat(tenantId, seatId).map(OrgModel.Seat::unitId);
    }

    @Override
    public Optional<String> seatForRole(String tenantId, String roleCode, String officeId) {
        return eligibleSeats(tenantId, roleCode, officeId, businessClock.today(tenantId)).stream().findFirst();
    }

    @Override
    public List<String> eligibleSeats(String tenantId, String roleCode, String officeId, LocalDate date) {
        if (officeId == null) {
            return List.of();
        }
        return db(c -> security.seats().seatsInUnit(c, tenantId, officeId, roleCode, at(tenantId, date)));
    }

    @Override
    public Optional<String> escalationSeatOf(String tenantId, String seatId) {
        return db(c -> security.postings().escalationSeat(c, tenantId, seatId, Instant.now()));
    }

    private Optional<OrgModel.Seat> seat(String tenantId, String seatId) {
        return db(c -> security.seats().seatAt(c, tenantId, seatId, Instant.now()));
    }

    private static SeatHolding map(SecurityPorts.SeatHolder h, ZoneId zone) {
        String kind = h.kind() == null ? "" : h.kind().toUpperCase(Locale.ROOT);
        SeatHolding.Basis basis = kind.contains("DELEGAT") ? SeatHolding.Basis.DELEGATION
                : h.actingFor() || kind.contains("ADDITIONAL") || kind.contains("LEAVE") ? SeatHolding.Basis.ADDITIONAL_CHARGE
                : SeatHolding.Basis.SUBSTANTIVE;
        LocalDate from = h.validFrom() == null ? LocalDate.MIN : LocalDate.ofInstant(h.validFrom(), zone);
        LocalDate to = h.validTo() == null ? null : LocalDate.ofInstant(h.validTo(), zone);
        return new SeatHolding(h.seatId(), h.personId(), basis, h.postingId(), from, to);
    }

    private Instant at(String tenantId, LocalDate date) {
        ZoneId zone = businessClock.zone(tenantId);
        return (date == null ? businessClock.today(tenantId) : date).atTime(12, 0).atZone(zone).toInstant();
    }

    private <T> T db(Function<Connection, T> work) {
        if (EngineTx.inCommand()) {
            return work.apply(EngineTx.connection());
        }
        return tx.withConnection(work);
    }
}
