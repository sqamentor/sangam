// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.booking;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.booking.BookingModel.Booking;
import org.sangam.resources.booking.BookingModel.State;
import org.sangam.resources.calendar.CalendarService;
import org.sangam.resources.calendar.Interval;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceCatalogue;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sequences;
import org.sangam.resources.support.Sql;
import org.sangam.resources.support.VisibleQuery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Time-slot booking (N01): availability search, hold with expiry, confirm (with optional approval), check-in/out,
 * cancellation with refund policy, priority bump with notice, recurring series, hold-expiry and no-show sweepers.
 * Charges are handed to the billing port; entitlement, priority, tariff and refund come from DMN or type data.
 *
 * <p>Double booking is impossible by construction: every active booking owns claim rows in {@code RES_CLAIM} under a
 * primary key ({@link ClaimLedger}). For resources of a {@code BOTH} type the resource row is also locked so slot
 * bookings and long allotments see each other.
 */
public final class BookingService {

    /** Called whenever capacity is released (cancel, bump, expire, reject, no-show, early check-out). */
    @FunctionalInterface
    public interface ReleaseListener {
        void released(Connection c, SecurityIdentity actor, String resourceId, String typeId, Instant from, Instant to);
    }

    static final String COLS = "tenant_id, booking_id, booking_ref, resource_id, type_id, state, requester_id, beneficiary_id,"
            + " party_ref, purpose, entitlement_class, priority_level, quantity, starts_at, ends_at, hold_expires_at,"
            + " tariff_code, charge_amount, refund_amount, currency, charge_ref, business_key, process_ref, recurrence_id,"
            + " waitlist_id, bumped_by, state_reason, attributes_json, row_version, created_at, updated_at";

    private static final String APPROVAL_FLAG = "_requiresApproval";
    private static final String APPROVED_FLAG = "_approved";

    private final ResourcesContext ctx;
    private final ResourceCatalogue catalogue;
    private final CalendarService calendars;
    private final ClaimLedger claims;
    private final BookingRules rules;
    private volatile ReleaseListener releaseListener = (c, a, r, t, f, to) -> { };

    public BookingService(ResourcesContext ctx, ResourceCatalogue catalogue, CalendarService calendars, ClaimLedger claims,
                          BookingRules rules) {
        this.ctx = ctx;
        this.catalogue = catalogue;
        this.calendars = calendars;
        this.claims = claims;
        this.rules = rules;
    }

    public void onRelease(ReleaseListener listener) {
        this.releaseListener = listener;
    }

    public BookingRules rules() {
        return rules;
    }

    // ------------------------------------------------------------------ availability

    /** Free units per bookable slot of one resource. */
    public List<BookingModel.SlotAvailability> availability(Connection c, SecurityIdentity actor, String resourceId,
                                                            Instant from, Instant to) {
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_READ, resourceId);
        Resource r = catalogue.get(c, t, resourceId);
        ResourceType type = catalogue.type(c, t, r.typeId());
        Interval range = new Interval(from, to);
        if (Duration.between(from, to).toDays() > ctx.setting("maxAvailabilityDays", 92)) {
            throw ResourcesException.invalid("RANGE_TOO_LONG", "availability range is too long");
        }
        ZoneId zone = calendars.zoneOf(c, t, r);
        int grid = ClaimLedger.gridMinutes(type, zone);
        List<CalendarService.Slot> slots = calendars.slots(c, t, r, type, range);
        if (slots.isEmpty() || !r.bookable()) {
            return List.of();
        }
        long first = ClaimLedger.cells(slots.get(0).startsAt(), slots.get(0).endsAt(), grid).get(0);
        CalendarService.Slot lastSlot = slots.get(slots.size() - 1);
        List<Long> lastCells = ClaimLedger.cells(lastSlot.startsAt(), lastSlot.endsAt(), grid);
        Map<Long, Integer> usage = claims.usage(c, t, resourceId, first, lastCells.get(lastCells.size() - 1));
        int allotted = type.bookingMode() == ResourceType.BookingMode.BOTH ? overlappingAllotments(c, t, resourceId, range) : 0;
        List<BookingModel.SlotAvailability> out = new ArrayList<>();
        for (CalendarService.Slot s : slots) {
            int used = 0;
            for (Long cell : ClaimLedger.cells(s.startsAt(), s.endsAt(), grid)) {
                used = Math.max(used, usage.getOrDefault(cell, 0));
            }
            int units = Math.max(0, Math.min(s.units(), r.capacity() - allotted));
            out.add(new BookingModel.SlotAvailability(resourceId, s.startsAt(), s.endsAt(), units, Math.max(0, units - used)));
        }
        return out;
    }

    /** Resources of a type (and catalogue filters) that are free for the whole interval with {@code quantity} units. */
    public List<BookingModel.Candidate> search(Connection c, SecurityIdentity actor, Resource.Query filter, Instant from,
                                               Instant to, int quantity) {
        String t = actor.tenantId();
        Interval iv = new Interval(from, to);
        List<BookingModel.Candidate> out = new ArrayList<>();
        for (Resource r : catalogue.search(c, actor, filter)) {
            ResourceType type = catalogue.type(c, t, r.typeId());
            if (!type.slotBookable() || r.capacity() < quantity || !calendars.isOpen(c, t, r, iv)) {
                continue;
            }
            int grid = ClaimLedger.gridMinutes(type, calendars.zoneOf(c, t, r));
            List<Long> cells = ClaimLedger.cells(from, to, grid);
            Map<Long, Integer> usage = claims.usage(c, t, r.resourceId(), cells.get(0), cells.get(cells.size() - 1));
            int used = 0;
            for (Long cell : cells) {
                used = Math.max(used, usage.getOrDefault(cell, 0));
            }
            int allotted = type.bookingMode() == ResourceType.BookingMode.BOTH ? overlappingAllotments(c, t, r.resourceId(), iv) : 0;
            int free = r.capacity() - allotted - used;
            if (free >= quantity) {
                out.add(new BookingModel.Candidate(r.resourceId(), r.code(), r.name(), r.capacity(), free));
            }
        }
        return out;
    }

    /** Price preview for a request (no state change). */
    public ResourcesPorts.Quote quote(Connection c, SecurityIdentity actor, BookingModel.HoldRequest req) {
        String t = actor.tenantId();
        Resource r = catalogue.get(c, t, req.resourceId());
        ResourceType type = catalogue.type(c, t, r.typeId());
        int qty = req.quantity() == null ? 1 : req.quantity();
        Instant now = ctx.now(c);
        String requester = req.requesterId() == null ? actor.personId() : req.requesterId();
        Map<String, Object> in = rules.inputs(actor, type, r, requester, req.entitlementClass(), req.purpose(), qty,
                req.startsAt(), req.endsAt(), now, req.attributes());
        BookingModel.Entitlement ent = rules.entitlement(t, type, in, req.priorityLevel());
        if (!ent.allowed()) {
            throw ResourcesException.notEntitled("NOT_ENTITLED", ent.reason() == null ? "not entitled" : ent.reason());
        }
        return ctx.billing().quote(c, t, ent.tariffCode(), rules.tariffInputs(t, type, in, ent.tariffCode()));
    }

    // ------------------------------------------------------------------ hold

    public BookingModel.HoldResult hold(Connection c, SecurityIdentity actor, BookingModel.HoldRequest req) {
        return hold(c, actor, req, null, null);
    }

    BookingModel.HoldResult hold(Connection c, SecurityIdentity actor, BookingModel.HoldRequest req, String recurrenceId,
                                 String waitlistId) {
        String t = actor.tenantId();
        if (req.resourceId() == null || req.startsAt() == null || req.endsAt() == null) {
            throw ResourcesException.invalid("HOLD_REQUEST", "resource, start and end are required");
        }
        ctx.guard().require(c, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_MODIFY, req.resourceId());
        Interval iv = new Interval(req.startsAt(), req.endsAt());
        Resource r = catalogue.get(c, t, req.resourceId());
        ResourceType type = catalogue.type(c, t, r.typeId());
        if (!type.slotBookable()) {
            throw ResourcesException.invalid("NOT_BOOKABLE", "resources of type " + type.typeId() + " are not booked by slot");
        }
        if (!r.bookable()) {
            throw ResourcesException.unavailable("RESOURCE_" + r.status().name(), "the resource is not bookable now");
        }
        int qty = req.quantity() == null ? 1 : req.quantity();
        Instant now = ctx.now(c);
        String requester = req.requesterId() == null ? actor.personId() : req.requesterId();
        ZoneId zone = calendars.zoneOf(c, t, r);
        validateWindow(type, iv, now, zone);

        Map<String, Object> in = rules.inputs(actor, type, r, requester, req.entitlementClass(), req.purpose(), qty,
                iv.from(), iv.to(), now, req.attributes());
        BookingModel.Entitlement ent = rules.entitlement(t, type, in, req.priorityLevel());
        if (!ent.allowed()) {
            throw ResourcesException.notEntitled("NOT_ENTITLED", ent.reason() == null ? "not entitled to book this resource" : ent.reason());
        }
        long minutes = iv.duration().toMinutes();
        if (ent.maxDurationMinutes() != null && ent.maxDurationMinutes() > 0 && minutes > ent.maxDurationMinutes()) {
            throw ResourcesException.notEntitled("DURATION_EXCEEDS_ENTITLEMENT", "the period is longer than the entitlement");
        }
        if (ent.maxAdvanceDays() != null && Duration.between(now, iv.from()).toDays() > ent.maxAdvanceDays()) {
            throw ResourcesException.notEntitled("TOO_FAR_AHEAD", "the booking window is not open yet");
        }
        enforceRequesterLimit(c, t, type, requester);
        if (!calendars.isOpen(c, t, r, iv)) {
            throw ResourcesException.unavailable("CLOSED", "the resource is closed during part of the requested period");
        }

        int capacity = r.capacity();
        if (type.bookingMode() == ResourceType.BookingMode.BOTH) {
            catalogue.lock(c, t, r.resourceId());
            capacity -= overlappingAllotments(c, t, r.resourceId(), iv);
        }
        String id = Sql.newId();
        int grid = ClaimLedger.gridMinutes(type, zone);
        List<Long> cells = ClaimLedger.cells(iv.from(), iv.to(), grid);
        List<String> bumped = new ArrayList<>();
        final int cap = capacity;
        try {
            claims.claim(c, t, r.resourceId(), id, cells, qty, Math.max(cap, 0), (cc, missing) -> releaseLapsed(cc, actor, r, missing));
        } catch (ResourcesException e) {
            if (!"SLOT_TAKEN".equals(e.reason()) && !"CAPACITY".equals(e.reason())) {
                throw e;
            }
            if (req.bump() && type.allowBump()) {
                bumped.addAll(bumpConflicts(c, actor, r, type, cells, ent.priorityLevel(), id, req.reason(), req.authorityRef()));
                claims.claim(c, t, r.resourceId(), id, cells, qty, Math.max(cap, 0), null);
            } else if (req.waitlistIfFull() && waitlistSink != null) {
                BookingModel.WaitlistEntry w = waitlistSink.enqueue(c, actor, new BookingModel.WaitlistRequest(type.typeId(),
                        r.resourceId(), requester, req.beneficiaryId(), req.entitlementClass(), ent.priorityLevel(), null,
                        iv.from(), iv.to(), qty, req.attributes()));
                return new BookingModel.HoldResult(null, w, List.of());
            } else {
                throw e;
            }
        }

        int holdMinutes = req.holdMinutes() != null && req.holdMinutes() > 0 ? req.holdMinutes() : type.holdMinutes();
        Instant expires = now.plus(Duration.ofMinutes(holdMinutes));
        Map<String, Object> attrs = new LinkedHashMap<>(req.attributes() == null ? Map.of() : req.attributes());
        attrs.put(APPROVAL_FLAG, ent.requiresApproval());
        String ref = nextRef(c, t, type, iv.from(), zone);
        Jdbc.update(c, "INSERT INTO RES_BOOKING(" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                t, id, ref, r.resourceId(), type.typeId(), State.HELD.name(), requester, req.beneficiaryId(), req.partyRef(),
                req.purpose(), ent.entitlementClass(), ent.priorityLevel(), qty, iv.from(), iv.to(), expires,
                ent.tariffCode(), null, null, type.currency(), null, req.businessKey(), null, recurrenceId, waitlistId,
                null, null, Sql.toJson(attrs), 1L, now, now);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("resourceId", r.resourceId());
        p.put("from", iv.from().toString());
        p.put("to", iv.to().toString());
        p.put("quantity", qty);
        p.put("priorityLevel", ent.priorityLevel());
        p.put("entitlementClass", ent.entitlementClass());
        p.put("entitlementDecision", ent.decisionHash());
        p.put("bumped", bumped);
        ctx.event(c, actor, "BOOKING", id, "HELD", req.reason(), req.authorityRef(), p);
        return new BookingModel.HoldResult(get(c, t, id), null, bumped);
    }

    /** Waitlist sink used by {@code waitlistIfFull}; set by the module wiring. */
    public interface WaitlistSink {
        BookingModel.WaitlistEntry enqueue(Connection c, SecurityIdentity actor, BookingModel.WaitlistRequest req);
    }

    private volatile WaitlistSink waitlistSink;

    public void waitlistSink(WaitlistSink sink) {
        this.waitlistSink = sink;
    }

    /** A recurring series (weekly hall booking, daily session). */
    public BookingModel.SeriesResult holdSeries(Connection c, SecurityIdentity actor, BookingModel.HoldRequest first,
                                                BookingModel.Recurrence rec) {
        String t = actor.tenantId();
        Resource r = catalogue.get(c, t, first.resourceId());
        ZoneId zone = calendars.zoneOf(c, t, r);
        Duration length = Duration.between(first.startsAt(), first.endsAt());
        int maxOcc = Math.min(rec.count() == null ? ctx.setting("maxSeriesOccurrences", 366) : rec.count(),
                ctx.setting("maxSeriesOccurrences", 366));
        String recurrenceId = Sql.newId();
        List<Booking> held = new ArrayList<>();
        List<Instant> missed = new ArrayList<>();
        ZonedDateTime cursor = first.startsAt().atZone(zone);
        int produced = 0;
        int guard = 0;
        int step = Math.max(1, rec.interval());
        while (produced < maxOcc && guard++ < 5000) {
            if (rec.until() != null && cursor.toLocalDate().isAfter(rec.until())) {
                break;
            }
            boolean include = rec.frequency() != BookingModel.Recurrence.Frequency.WEEKLY || rec.byDays() == null
                    || rec.byDays().isEmpty() || rec.byDays().contains(cursor.getDayOfWeek());
            if (include) {
                Instant s = cursor.toInstant();
                BookingModel.HoldRequest occ = new BookingModel.HoldRequest(first.resourceId(), s, s.plus(length),
                        first.quantity(), first.requesterId(), first.beneficiaryId(), first.partyRef(), first.purpose(),
                        first.entitlementClass(), first.priorityLevel(), first.attributes(), first.businessKey(), false,
                        null, first.reason(), false, first.holdMinutes());
                try {
                    held.add(hold(c, actor, occ, recurrenceId, null).booking());
                } catch (ResourcesException e) {
                    if (rec.allOrNothing()) {
                        for (Booking b : held) {
                            claims.release(c, t, b.bookingId());
                            Jdbc.update(c, "DELETE FROM RES_BOOKING WHERE tenant_id=? AND booking_id=?", t, b.bookingId());
                        }
                        throw ResourcesException.unavailable("SERIES_UNAVAILABLE",
                                "occurrence at " + s + " is not available: " + e.getMessage());
                    }
                    missed.add(s);
                }
                produced++;
            }
            cursor = switch (rec.frequency()) {
                case DAILY -> cursor.plusDays(step);
                case WEEKLY -> (rec.byDays() == null || rec.byDays().isEmpty()) ? cursor.plusWeeks(step)
                        : nextWeekly(cursor, step);
                case MONTHLY -> cursor.plusMonths(step);
            };
        }
        return new BookingModel.SeriesResult(recurrenceId, held, missed);
    }

    private static ZonedDateTime nextWeekly(ZonedDateTime cursor, int weeks) {
        ZonedDateTime n = cursor.plusDays(1);
        if (n.getDayOfWeek() == DayOfWeek.MONDAY && weeks > 1) {
            n = n.plusWeeks(weeks - 1L);
        }
        return n;
    }

    // ------------------------------------------------------------------ confirm and approval

    public Booking confirm(Connection c, SecurityIdentity actor, String bookingId) {
        String t = actor.tenantId();
        Booking b = lockBooking(c, t, bookingId);
        requireOwnerOrAdmin(c, actor, b);
        Instant now = ctx.now(c);
        if (b.state() == State.CONFIRMED) {
            return b;
        }
        if (b.state() != State.HELD) {
            throw ResourcesException.conflict("INVALID_STATE", "only a held booking can be confirmed (state " + b.state() + ")");
        }
        if (b.holdExpiresAt() != null && !now.isBefore(b.holdExpiresAt())) {
            expire(c, actor, b, now, true);
            throw ResourcesException.conflict("HOLD_EXPIRED", "the hold has expired");
        }
        ResourceType type = catalogue.type(c, t, b.typeId());
        boolean needsApproval = Sql.bool(b.attributes().get(APPROVAL_FLAG), type.requiresApproval())
                && !Sql.bool(b.attributes().get(APPROVED_FLAG), false);
        if (needsApproval) {
            int minutes = Sql.intOr(type.extra().get("approvalHoldMinutes"), 2880);
            String processKey = type.approvalProcessKey() != null ? type.approvalProcessKey() : "sangam-booking-approval";
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("bookingId", b.bookingId());
            vars.put("bookingRef", b.bookingRef());
            vars.put("resourceId", b.resourceId());
            vars.put("typeId", b.typeId());
            vars.put("requesterId", b.requesterId());
            vars.put("startsAt", b.startsAt().toString());
            vars.put("endsAt", b.endsAt().toString());
            vars.put("entitlementClass", b.entitlementClass());
            vars.put("tenantId", t);
            // candidate seat from configuration (default BPMN sangam-booking-approval): per resource, then per type
            Resource res = catalogue.get(c, t, b.resourceId());
            Object seat = res.attributes().getOrDefault("approverSeat", type.extra().get("approverSeat"));
            if (seat == null) {
                seat = ctx.settings().get("bookingApproverSeat");
            }
            if (seat != null) {
                vars.put("approverSeatId", seat.toString());
            }
            vars.put("approvalTimeout", "PT" + minutes + "M");
            String processRef = ctx.approvals().startApproval(c, actor, processKey, "BOOKING", b.bookingId(),
                    b.bookingRef(), vars);
            update(c, b, State.PENDING_APPROVAL, "hold_expires_at=?, process_ref=?", now.plus(Duration.ofMinutes(minutes)), processRef);
            ctx.event(c, actor, "BOOKING", b.bookingId(), "APPROVAL_REQUESTED", null, null, Map.of("processKey", processKey));
            return get(c, t, bookingId);
        }
        return doConfirm(c, actor, b, type, now);
    }

    /** Outcome of the approval process (BPMN delegate or task completion). */
    public Booking decideApproval(Connection c, SecurityIdentity actor, String bookingId, boolean approved, String reason) {
        String t = actor.tenantId();
        Booking b = lockBooking(c, t, bookingId);
        ctx.guard().require(c, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_APPROVE, bookingId);
        if (b.state() != State.PENDING_APPROVAL) {
            throw ResourcesException.conflict("INVALID_STATE", "the booking is not awaiting approval (state " + b.state() + ")");
        }
        Instant now = ctx.now(c);
        if (!approved) {
            claims.release(c, t, b.bookingId());
            update(c, b, State.REJECTED, "hold_expires_at=NULL, state_reason=?", reason);
            ctx.event(c, actor, "BOOKING", b.bookingId(), "REJECTED", reason, null, Map.of());
            ctx.notify(c, t, b.requesterId(), "booking.rejected", notice(b, reason));
            releaseListener.released(c, actor, b.resourceId(), b.typeId(), b.startsAt(), b.endsAt());
            return get(c, t, bookingId);
        }
        ctx.event(c, actor, "BOOKING", b.bookingId(), "APPROVED", reason, null, Map.of());
        return doConfirm(c, actor, b, catalogue.type(c, t, b.typeId()), now);
    }

    private Booking doConfirm(Connection c, SecurityIdentity actor, Booking b, ResourceType type, Instant now) {
        String t = actor.tenantId();
        Resource r = catalogue.get(c, t, b.resourceId());
        Map<String, Object> in = rules.inputs(actor, type, r, b.requesterId(), b.entitlementClass(), b.purpose(),
                b.quantity(), b.startsAt(), b.endsAt(), now, b.attributes());
        ResourcesPorts.Quote q = ctx.billing().quote(c, t, b.tariffCode(), rules.tariffInputs(t, type, in, b.tariffCode()));
        String chargeRef = null;
        if (q.payable()) {
            String payer = b.beneficiaryId() != null && Sql.bool(type.extra().get("beneficiaryPays"), false)
                    ? b.beneficiaryId() : b.requesterId();
            chargeRef = ctx.billing().charge(c, t, "booking:" + b.bookingId() + ":charge", payer, b.bookingRef(), q);
        }
        Map<String, Object> attrs = new LinkedHashMap<>(b.attributes());
        attrs.put(APPROVED_FLAG, true);
        update(c, b, State.CONFIRMED, "hold_expires_at=NULL, charge_amount=?, currency=?, charge_ref=?, tariff_code=?, attributes_json=?",
                q.amount() == null ? null : q.amount().toPlainString(), q.currency(), chargeRef, q.tariffCode(), Sql.toJson(attrs));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("amount", q.amount() == null ? null : q.amount().toPlainString());
        p.put("currency", q.currency());
        p.put("chargeRef", chargeRef);
        ctx.event(c, actor, "BOOKING", b.bookingId(), "CONFIRMED", null, null, p);
        ctx.notify(c, t, b.requesterId(), "booking.confirmed", notice(b, null));
        return get(c, t, b.bookingId());
    }

    // ------------------------------------------------------------------ check-in / check-out

    public Booking checkIn(Connection c, SecurityIdentity actor, String bookingId) {
        String t = actor.tenantId();
        Booking b = lockBooking(c, t, bookingId);
        ctx.guard().require(c, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_WORK, bookingId);
        if (b.state() == State.CHECKED_IN) {
            return b;
        }
        if (b.state() != State.CONFIRMED) {
            throw ResourcesException.conflict("INVALID_STATE", "only a confirmed booking can be checked in (state " + b.state() + ")");
        }
        ResourceType type = catalogue.type(c, t, b.typeId());
        Instant now = ctx.now(c);
        int early = Sql.intOr(type.extra().get("earlyCheckInMinutes"), 24 * 60);
        if (now.isBefore(b.startsAt().minus(Duration.ofMinutes(early)))) {
            throw ResourcesException.conflict("TOO_EARLY", "check-in is not open yet");
        }
        if (!now.isBefore(b.endsAt())) {
            throw ResourcesException.conflict("PERIOD_OVER", "the booked period is over");
        }
        update(c, b, State.CHECKED_IN, "attributes_json=?", Sql.toJson(with(b.attributes(), "checkedInAt", now.toString())));
        ctx.event(c, actor, "BOOKING", bookingId, "CHECKED_IN", null, null, Map.of("at", now.toString()));
        return get(c, t, bookingId);
    }

    public Booking checkOut(Connection c, SecurityIdentity actor, String bookingId) {
        String t = actor.tenantId();
        Booking b = lockBooking(c, t, bookingId);
        ctx.guard().require(c, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_WORK, bookingId);
        if (b.state() == State.CHECKED_OUT) {
            return b;
        }
        if (b.state() != State.CHECKED_IN) {
            throw ResourcesException.conflict("INVALID_STATE", "only a checked-in booking can be checked out (state " + b.state() + ")");
        }
        Instant now = ctx.now(c);
        Map<String, Object> attrs = with(b.attributes(), "checkedOutAt", now.toString());
        if (now.isAfter(b.endsAt())) {
            attrs.put("overstayMinutes", Duration.between(b.endsAt(), now).toMinutes());
        }
        if (now.isBefore(b.endsAt())) {
            ResourceType type = catalogue.type(c, t, b.typeId());
            Resource r = catalogue.get(c, t, b.resourceId());
            int grid = ClaimLedger.gridMinutes(type, calendars.zoneOf(c, t, r));
            long nextCell = (Math.floorDiv(Sql.slotKey(now), grid) + 1) * grid;
            claims.releaseFrom(c, t, b.bookingId(), nextCell);
            attrs.put("earlyCheckOut", true);
        }
        update(c, b, State.CHECKED_OUT, "attributes_json=?", Sql.toJson(attrs));
        ctx.event(c, actor, "BOOKING", bookingId, "CHECKED_OUT", null, null, Map.of("at", now.toString()));
        if (now.isBefore(b.endsAt())) {
            releaseListener.released(c, actor, b.resourceId(), b.typeId(), now, b.endsAt());
        }
        return get(c, t, bookingId);
    }

    // ------------------------------------------------------------------ cancel, bump, expire, no-show

    /**
     * Cancels a booking and refunds by policy.
     *
     * @param cancelledBy REQUESTER (default), ADMIN or SYSTEM_CLOSURE (a blackout or maintenance displaced it)
     */
    public Booking cancel(Connection c, SecurityIdentity actor, String bookingId, String reason, String cancelledBy) {
        String t = actor.tenantId();
        Booking b = lockBooking(c, t, bookingId);
        String by = cancelledBy == null ? "REQUESTER" : cancelledBy;
        if ("REQUESTER".equals(by)) {
            requireOwnerOrAdmin(c, actor, b);
        } else {
            ctx.guard().require(c, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_CANCEL, bookingId);
        }
        if (b.state() == State.CANCELLED) {
            return b;
        }
        if (!b.state().canMoveTo(State.CANCELLED)) {
            throw ResourcesException.conflict("INVALID_STATE", "the booking cannot be cancelled in state " + b.state());
        }
        Instant now = ctx.now(c);
        BigDecimal refund = refund(c, actor, b, now, by, false, reason);
        claims.release(c, t, b.bookingId());
        update(c, b, State.CANCELLED, "hold_expires_at=NULL, refund_amount=?, state_reason=?",
                refund == null ? null : refund.toPlainString(), reason);
        ctx.event(c, actor, "BOOKING", bookingId, "CANCELLED", reason, null,
                mapOf("cancelledBy", by, "refund", refund == null ? null : refund.toPlainString()));
        ctx.notify(c, t, b.requesterId(), "booking.cancelled", notice(b, reason));
        releaseListener.released(c, actor, b.resourceId(), b.typeId(), b.startsAt(), b.endsAt());
        return get(c, t, bookingId);
    }

    public List<Booking> cancelSeries(Connection c, SecurityIdentity actor, String recurrenceId, String reason) {
        String t = actor.tenantId();
        Instant now = ctx.now(c);
        List<String> ids = Jdbc.query(c, "SELECT booking_id FROM RES_BOOKING WHERE tenant_id=? AND recurrence_id=?"
                + " AND state IN ('HELD','PENDING_APPROVAL','CONFIRMED') AND starts_at > ? ORDER BY starts_at",
                rs -> rs.getString(1), t, recurrenceId, now);
        List<Booking> out = new ArrayList<>();
        for (String id : ids) {
            out.add(cancel(c, actor, id, reason, null));
        }
        return out;
    }

    /**
     * Administrative priority bump of one booking (e.g. an override by a higher authority). Always audited with the
     * reason and the authority reference; the displaced party gets a notice and a full refund.
     */
    public Booking bump(Connection c, SecurityIdentity actor, String bookingId, String reason, String authorityRef,
                        String bumpedBy) {
        if (reason == null || reason.isBlank() || authorityRef == null || authorityRef.isBlank()) {
            throw ResourcesException.invalid("BUMP_AUTHORITY", "a bump needs a reason and an authority reference");
        }
        String t = actor.tenantId();
        ctx.guard().require(c, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_APPROVE, bookingId);
        Booking b = lockBooking(c, t, bookingId);
        if (!b.state().canMoveTo(State.BUMPED)) {
            throw ResourcesException.conflict("INVALID_STATE", "the booking cannot be bumped in state " + b.state());
        }
        Instant now = ctx.now(c);
        BigDecimal refund = refund(c, actor, b, now, "BUMP", false, reason);
        claims.release(c, t, b.bookingId());
        update(c, b, State.BUMPED, "hold_expires_at=NULL, refund_amount=?, bumped_by=?, state_reason=?",
                refund == null ? null : refund.toPlainString(), bumpedBy, reason);
        ctx.event(c, actor, "BOOKING", bookingId, "BUMPED", reason, authorityRef, mapOf("bumpedBy", bumpedBy,
                "refund", refund == null ? null : refund.toPlainString()));
        Map<String, Object> n = notice(b, reason);
        n.put("authorityRef", authorityRef);
        ctx.notify(c, t, b.requesterId(), "booking.bumped", n);
        if (b.beneficiaryId() != null && !b.beneficiaryId().equals(b.requesterId())) {
            ctx.notify(c, t, b.beneficiaryId(), "booking.bumped", n);
        }
        return get(c, t, bookingId);
    }

    private List<String> bumpConflicts(Connection c, SecurityIdentity actor, Resource r, ResourceType type, List<Long> cells,
                                       int priorityLevel, String newBookingId, String reason, String authorityRef) {
        String t = actor.tenantId();
        List<Booking> victims = new ArrayList<>();
        for (String owner : claims.owners(c, t, r.resourceId(), cells)) {
            Booking v = lockBooking(c, t, owner);
            if (!v.state().active()) {
                continue;
            }
            if (v.state() == State.CHECKED_IN || v.priorityLevel() <= priorityLevel) {
                throw ResourcesException.unavailable("SLOT_TAKEN", "the slot is held by a booking that cannot be bumped");
            }
            victims.add(v);
        }
        List<String> ids = new ArrayList<>();
        for (Booking v : victims) {
            bump(c, actor, v.bookingId(), reason, authorityRef, newBookingId);
            ids.add(v.bookingId());
        }
        return ids;
    }

    /** Releases lapsed holds that stand in the way of a claim; returns true when something was released. */
    private boolean releaseLapsed(Connection c, SecurityIdentity actor, Resource r, List<Long> cells) {
        String t = actor.tenantId();
        Instant now = ctx.now(c);
        boolean any = false;
        for (String owner : claims.owners(c, t, r.resourceId(), cells)) {
            Optional<Booking> b = find(c, t, owner);
            if (b.isPresent() && (b.get().state() == State.HELD || b.get().state() == State.PENDING_APPROVAL)
                    && b.get().holdExpiresAt() != null && !now.isBefore(b.get().holdExpiresAt())) {
                Booking locked = lockBooking(c, t, owner);
                if (locked.state() == State.HELD || locked.state() == State.PENDING_APPROVAL) {
                    expire(c, SecurityIdentity.system(t, actor.personId()), locked, now, false);
                    any = true;
                }
            } else if (b.isEmpty()) {
                // orphan claims of a booking that was never written (crashed writer) are removed
                claims.release(c, t, owner);
                any = true;
            }
        }
        return any;
    }

    /** @param notifyRelease false while a claim is in progress on the same resource (no re-entrant promotion) */
    private void expire(Connection c, SecurityIdentity actor, Booking b, Instant now, boolean notifyRelease) {
        String t = actor.tenantId();
        claims.release(c, t, b.bookingId());
        update(c, b, State.EXPIRED, "hold_expires_at=NULL, state_reason=?", "hold expired");
        ctx.event(c, actor, "BOOKING", b.bookingId(), "EXPIRED", "hold expired", null, Map.of("at", now.toString()));
        if (notifyRelease) {
            releaseListener.released(c, actor, b.resourceId(), b.typeId(), b.startsAt(), b.endsAt());
        }
    }

    /** Sweeper: expires lapsed holds (skip-locked, safe to run on every node). */
    public int expireHolds(Connection c, SecurityIdentity system, int limit) {
        String t = system.tenantId();
        Instant now = ctx.now(c);
        List<String> ids = Jdbc.query(c, ctx.dialect().skipLockedSelect("b.booking_id", "RES_BOOKING", "b",
                "b.tenant_id=? AND b.state IN ('HELD','PENDING_APPROVAL') AND b.hold_expires_at <= ?", "b.hold_expires_at",
                Math.max(1, limit)), rs -> rs.getString(1), t, now);
        int n = 0;
        for (String id : ids) {
            Booking b = get(c, t, id);
            if (b.state() == State.HELD || b.state() == State.PENDING_APPROVAL) {
                expire(c, system, b, now, true);
                n++;
            }
        }
        return n;
    }

    /** Sweeper: confirmed bookings not checked in within the type's grace become NO_SHOW (refund by policy). */
    public int markNoShows(Connection c, SecurityIdentity system, int limit) {
        String t = system.tenantId();
        Instant now = ctx.now(c);
        List<String> ids = Jdbc.query(c, ctx.dialect().skipLockedSelect("b.booking_id", "RES_BOOKING", "b",
                "b.tenant_id=? AND b.state='CONFIRMED' AND b.starts_at < ?", "b.starts_at", Math.max(1, limit)),
                rs -> rs.getString(1), t, now);
        int n = 0;
        for (String id : ids) {
            Booking b = get(c, t, id);
            ResourceType type = catalogue.type(c, t, b.typeId());
            if (type.noShowGraceMinutes() <= 0 || now.isBefore(b.startsAt().plus(Duration.ofMinutes(type.noShowGraceMinutes())))) {
                continue;
            }
            BigDecimal refund = refund(c, system, b, now, "SYSTEM", true, "no-show");
            claims.release(c, t, b.bookingId());
            update(c, b, State.NO_SHOW, "refund_amount=?, state_reason=?", refund == null ? null : refund.toPlainString(), "no-show");
            ctx.event(c, system, "BOOKING", id, "NO_SHOW", null, null, Map.of());
            releaseListener.released(c, system, b.resourceId(), b.typeId(), now, b.endsAt());
            n++;
        }
        return n;
    }

    private BigDecimal refund(Connection c, SecurityIdentity actor, Booking b, Instant now, String by, boolean noShow, String reason) {
        if (b.chargeAmount() == null || b.chargeAmount().signum() <= 0 || b.chargeRef() == null) {
            return null;
        }
        String t = actor.tenantId();
        ResourceType type = catalogue.type(c, t, b.typeId());
        Resource r = catalogue.get(c, t, b.resourceId());
        Map<String, Object> in = rules.inputs(actor, type, r, b.requesterId(), b.entitlementClass(), b.purpose(),
                b.quantity(), b.startsAt(), b.endsAt(), now, b.attributes());
        long notice = Math.max(0, Duration.between(now, b.startsAt()).toHours());
        BookingModel.RefundDecision d = rules.refund(t, type, in, notice, b.state().name(), by, noShow);
        BigDecimal amount = b.chargeAmount().multiply(d.refundPercent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_EVEN);
        if (d.fee() != null) {
            amount = amount.subtract(d.fee());
        }
        if (amount.signum() <= 0) {
            ctx.event(c, actor, "BOOKING", b.bookingId(), "NO_REFUND", reason, null,
                    mapOf("rule", d.rule(), "decision", d.decisionHash()));
            return BigDecimal.ZERO;
        }
        String refundRef = ctx.billing().refund(c, t, "booking:" + b.bookingId() + ":refund", b.chargeRef(), amount,
                b.currency(), reason);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("amount", amount.toPlainString());
        p.put("percent", d.refundPercent().toPlainString());
        p.put("rule", d.rule());
        p.put("decision", d.decisionHash());
        p.put("refundRef", refundRef);
        ctx.event(c, actor, "BOOKING", b.bookingId(), "REFUNDED", reason, null, p);
        return amount;
    }

    // ------------------------------------------------------------------ reads

    public Optional<Booking> find(Connection c, String tenantId, String bookingId) {
        return Jdbc.one(c, "SELECT " + COLS + " FROM RES_BOOKING WHERE tenant_id=? AND booking_id=?", BookingService::map,
                tenantId, bookingId);
    }

    public Booking get(Connection c, String tenantId, String bookingId) {
        return find(c, tenantId, bookingId).orElseThrow(() -> ResourcesException.notFound("booking", bookingId));
    }

    public Optional<Booking> byRef(Connection c, String tenantId, String ref) {
        return Jdbc.one(c, "SELECT " + COLS + " FROM RES_BOOKING WHERE tenant_id=? AND booking_ref=?", BookingService::map,
                tenantId, ref);
    }

    /** Get-by-id through the visibility predicate (hidden = NOT_FOUND). */
    public Booking visible(Connection c, SecurityIdentity actor, String bookingId) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_READ, "RES_BOOKING", "b")
                .select(COLS).require(c, "booking_id", bookingId, BookingService::map, "booking");
    }

    public Booking visibleByRef(Connection c, SecurityIdentity actor, String ref) {
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_READ, "RES_BOOKING", "b")
                .select(COLS).require(c, "booking_ref", ref, BookingService::map, "booking");
    }

    /** Lists visible bookings (VI.4 predicate in the statement); null filters do not apply. {@code mine} = caller as requester. */
    public List<Booking> list(Connection c, SecurityIdentity actor, String resourceId, String requesterId, State state,
                              Instant from, Instant to, boolean mine, int offset, int limit) {
        return listQuery(actor, resourceId, requesterId, state, from, to, mine).orderBy("b.starts_at, b.booking_id")
                .list(c, BookingService::map, Math.max(0, offset), limit <= 0 ? 50 : Math.min(limit, 1000));
    }

    /** Capped count of the same list ("more than N", A-16). */
    public VisibleQuery.CappedCount count(Connection c, SecurityIdentity actor, String resourceId, String requesterId,
                                          State state, Instant from, Instant to, boolean mine, int cap) {
        return listQuery(actor, resourceId, requesterId, state, from, to, mine).count(c, cap);
    }

    private VisibleQuery listQuery(SecurityIdentity actor, String resourceId, String requesterId, State state, Instant from,
                                   Instant to, boolean mine) {
        VisibleQuery q = VisibleQuery.on(ctx, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_READ, "RES_BOOKING", "b")
                .select(COLS);
        if (resourceId != null) {
            q.where("b.resource_id=?", resourceId);
        }
        if (mine) {
            q.where("b.requester_id=?", actor.personId());
        } else if (requesterId != null) {
            q.where("b.requester_id=?", requesterId);
        }
        if (state != null) {
            q.where("b.state=?", state.name());
        }
        if (from != null) {
            q.where("b.ends_at > ?", from);
        }
        if (to != null) {
            q.where("b.starts_at < ?", to);
        }
        return q;
    }

    /**
     * The RES_EVENT history of an entity of this module. The entity itself must be visible to the caller (same
     * predicate as its lists), and the event rows pass the {@code RES_EVENT} predicate too.
     */
    public List<Map<String, Object>> history(Connection c, SecurityIdentity actor, String entityType, String entityId,
                                             EntityVisibility entityCheck) {
        entityCheck.requireVisible(c, actor, entityType, entityId);
        return VisibleQuery.on(ctx, actor, ResourcesContext.RT_EVENT, ResourcesContext.P_READ, "RES_EVENT", "e")
                .select("event_id, event_type, actor_id, reason, authority_ref, payload_json, at_time")
                .where("e.entity_type=? AND e.entity_id=?", entityType, entityId).orderBy("e.event_id")
                .all(c, rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("eventId", rs.getLong("event_id"));
                    m.put("eventType", rs.getString("event_type"));
                    m.put("actorId", rs.getString("actor_id"));
                    m.put("reason", rs.getString("reason"));
                    m.put("authorityRef", rs.getString("authority_ref"));
                    m.put("payload", Sql.mapJson(Sql.text(rs, "payload_json")));
                    m.put("at", String.valueOf(Jdbc.instant(rs, "at_time")));
                    return m;
                }, 1000);
    }

    /** Resolves "is this entity visible to the caller" for {@link #history}. */
    @FunctionalInterface
    public interface EntityVisibility {
        void requireVisible(Connection c, SecurityIdentity actor, String entityType, String entityId);
    }

    // ------------------------------------------------------------------ helpers

    private void validateWindow(ResourceType type, Interval iv, Instant now, ZoneId zone) {
        long minutes = iv.duration().toMinutes();
        if (iv.from().isBefore(now.plus(Duration.ofMinutes(type.minNoticeMinutes())))) {
            throw ResourcesException.invalid("NOTICE", "the booking starts too soon (minimum notice " + type.minNoticeMinutes() + " minutes)");
        }
        if (type.maxAdvanceDays() > 0 && Duration.between(now, iv.from()).toDays() > type.maxAdvanceDays()) {
            throw ResourcesException.invalid("TOO_FAR_AHEAD", "the booking window is " + type.maxAdvanceDays() + " days");
        }
        if (type.minDurationMinutes() > 0 && minutes < type.minDurationMinutes()) {
            throw ResourcesException.invalid("TOO_SHORT", "the minimum duration is " + type.minDurationMinutes() + " minutes");
        }
        if (type.maxDurationMinutes() > 0 && minutes > type.maxDurationMinutes()) {
            throw ResourcesException.invalid("TOO_LONG", "the maximum duration is " + type.maxDurationMinutes() + " minutes");
        }
        if (!Sql.bool(type.extra().get("flexibleTimes"), false)) {
            if (!CalendarService.aligned(iv.from(), zone, type.alignMinutes())) {
                throw ResourcesException.invalid("ALIGNMENT", "bookings must start on a " + type.alignMinutes() + "-minute boundary");
            }
            if (minutes % type.slotMinutes() != 0) {
                throw ResourcesException.invalid("GRANULARITY", "the duration must be a multiple of " + type.slotMinutes() + " minutes");
            }
        }
    }

    private void enforceRequesterLimit(Connection c, String t, ResourceType type, String requester) {
        Integer max = Sql.intOrNull(type.extra().get("maxActivePerRequester"));
        if (max == null || max <= 0) {
            return;
        }
        long active = Jdbc.one(c, "SELECT COUNT(*) FROM RES_BOOKING WHERE tenant_id=? AND type_id=? AND requester_id=?"
                + " AND state IN ('HELD','PENDING_APPROVAL','CONFIRMED','CHECKED_IN')", rs -> rs.getLong(1),
                t, type.typeId(), requester).orElse(0L);
        if (active >= max) {
            throw ResourcesException.notEntitled("LIMIT_REACHED", "the requester already has " + active + " active bookings");
        }
    }

    int overlappingAllotments(Connection c, String t, String resourceId, Interval iv) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM RES_ALLOTMENT WHERE tenant_id=? AND resource_id=? AND state='ACTIVE'"
                        + " AND valid_from < ? AND (valid_to IS NULL OR valid_to > ?)", rs -> rs.getInt(1),
                t, resourceId, iv.to(), iv.from()).orElse(0);
    }

    private String nextRef(Connection c, String t, ResourceType type, Instant start, ZoneId zone) {
        String prefix = Sql.str(type.extra().getOrDefault("refPrefix", type.typeId().toUpperCase()));
        String period = Integer.toString(start.atZone(zone == null ? ZoneOffset.UTC : zone).getYear());
        long n = Sequences.next(c, ctx.dialect(), t, "booking:" + type.typeId(), period);
        return Sequences.format(prefix, period, n, 6);
    }

    private Booking lockBooking(Connection c, String t, String bookingId) {
        return Jdbc.one(c, ctx.dialect().lockingSelect(COLS, "RES_BOOKING", "tenant_id=? AND booking_id=?"),
                BookingService::map, t, bookingId).orElseThrow(() -> ResourcesException.notFound("booking", bookingId));
    }

    private void requireOwnerOrAdmin(Connection c, SecurityIdentity actor, Booking b) {
        if (!actor.system() && !actor.personId().equals(b.requesterId())) {
            ctx.guard().require(c, actor, ResourcesContext.RT_BOOKING, ResourcesContext.P_MODIFY, b.bookingId());
        }
    }

    /** State transition with the optimistic version check; {@code extraSet} is a SET fragment with its own binds. */
    private void update(Connection c, Booking b, State next, String extraSet, Object... extra) {
        if (!b.state().canMoveTo(next)) {
            throw ResourcesException.conflict("INVALID_TRANSITION", b.state() + " -> " + next + " is not allowed");
        }
        List<Object> p = new ArrayList<>();
        p.add(next.name());
        p.addAll(java.util.Arrays.asList(extra));
        p.add(Sql.dbNow(c, ctx.dialect()));
        p.add(b.tenantId());
        p.add(b.bookingId());
        p.add(b.version());
        int n = Jdbc.update(c, "UPDATE RES_BOOKING SET state=?" + (extraSet == null ? "" : ", " + extraSet)
                + ", updated_at=?, row_version=row_version+1 WHERE tenant_id=? AND booking_id=? AND row_version=?", p.toArray());
        if (n == 0) {
            throw ResourcesException.stale("booking " + b.bookingId() + " was changed concurrently");
        }
    }

    private static Map<String, Object> notice(Booking b, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bookingId", b.bookingId());
        m.put("bookingRef", b.bookingRef());
        m.put("resourceId", b.resourceId());
        m.put("startsAt", b.startsAt().toString());
        m.put("endsAt", b.endsAt().toString());
        if (reason != null) {
            m.put("reason", reason);
        }
        return m;
    }

    private static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put(k, v);
        return out;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    static Booking map(ResultSet rs) throws SQLException {
        return new Booking(rs.getString("tenant_id"), rs.getString("booking_id"), rs.getString("booking_ref"),
                rs.getString("resource_id"), rs.getString("type_id"), State.valueOf(rs.getString("state")),
                rs.getString("requester_id"), rs.getString("beneficiary_id"), rs.getString("party_ref"),
                rs.getString("purpose"), rs.getString("entitlement_class"), rs.getInt("priority_level"),
                rs.getInt("quantity"), Jdbc.instant(rs, "starts_at"), Jdbc.instant(rs, "ends_at"),
                Jdbc.instant(rs, "hold_expires_at"), rs.getString("tariff_code"), Sql.decimal(rs, "charge_amount"),
                Sql.decimal(rs, "refund_amount"), rs.getString("currency"), rs.getString("charge_ref"),
                rs.getString("business_key"), rs.getString("process_ref"), rs.getString("recurrence_id"),
                rs.getString("waitlist_id"), rs.getString("bumped_by"), rs.getString("state_reason"),
                Sql.mapJson(Sql.text(rs, "attributes_json")), rs.getLong("row_version"), Jdbc.instant(rs, "created_at"),
                Jdbc.instant(rs, "updated_at"));
    }
}
