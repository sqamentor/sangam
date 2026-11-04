// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.rest;

import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.ResourcesContext;
import org.sangam.resources.support.VisibleQuery;

import java.sql.Connection;
import org.sangam.resources.booking.AllotmentService;
import org.sangam.resources.booking.BookingModel;
import org.sangam.resources.booking.BookingService;
import org.sangam.resources.booking.WaitlistService;
import org.sangam.resources.calendar.AvailabilityCalendar;
import org.sangam.resources.calendar.CalendarService;
import org.sangam.resources.calendar.Interval;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceCatalogue;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.support.ResourcesException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@link ResourcesRestPort} over the services; one transaction per call; page sizes capped at 1,000 (R28). */
public final class ResourcesRestAdapter implements ResourcesRestPort {

    private final Transactions tx;
    private final ResourcesContext ctx;
    private final ResourceCatalogue catalogue;
    private final CalendarService calendars;
    private final BookingService bookings;
    private final WaitlistService waitlist;
    private final AllotmentService allotments;

    public ResourcesRestAdapter(Transactions tx, ResourcesContext ctx, ResourceCatalogue catalogue, CalendarService calendars,
                                BookingService bookings, WaitlistService waitlist, AllotmentService allotments) {
        this.tx = tx;
        this.ctx = ctx;
        this.catalogue = catalogue;
        this.calendars = calendars;
        this.bookings = bookings;
        this.waitlist = waitlist;
        this.allotments = allotments;
    }

    private static int size(int size) {
        return size <= 0 ? 50 : Math.min(size, 1000);
    }

    private static int offset(int page, int size) {
        return Math.max(0, page) * size(size);
    }

    // ------------------------------------------------------------------ catalogue

    @Override
    public List<ResourceType> types(SecurityIdentity actor) {
        return tx.inTransaction(c -> catalogue.types(c, actor.tenantId()));
    }

    @Override
    public ResourceType defineType(SecurityIdentity actor, Map<String, Object> definition) {
        return tx.inTransaction(c -> catalogue.defineType(c, actor, ResourceType.fromMap(definition)));
    }

    @Override
    public void retireType(SecurityIdentity actor, String typeId) {
        tx.inTransaction(c -> {
            catalogue.retireType(c, actor, typeId);
            return null;
        });
    }

    @Override
    public Resource createResource(SecurityIdentity actor, Resource.Draft draft) {
        return tx.inTransaction(c -> catalogue.create(c, actor, draft));
    }

    @Override
    public List<Resource> search(SecurityIdentity actor, Resource.Query query) {
        return tx.inTransaction(c -> catalogue.search(c, actor, query));
    }

    @Override
    public Resource resource(SecurityIdentity actor, String resourceId) {
        return tx.inTransaction(c -> catalogue.visible(c, actor, resourceId));
    }

    @Override
    public Resource updateResource(SecurityIdentity actor, String resourceId, long version, Resource.Patch patch) {
        return tx.inTransaction(c -> catalogue.update(c, actor, resourceId, version, patch));
    }

    @Override
    public Resource setStatus(SecurityIdentity actor, String resourceId, Resource.Status status, String reason) {
        return tx.inTransaction(c -> catalogue.setStatus(c, actor, resourceId, status, reason));
    }

    @Override
    public Resource move(SecurityIdentity actor, String resourceId, String newParentId) {
        return tx.inTransaction(c -> catalogue.move(c, actor, resourceId, newParentId));
    }

    @Override
    public List<Resource> children(SecurityIdentity actor, String resourceId) {
        return tx.inTransaction(c -> {
            catalogue.visible(c, actor, resourceId);
            return catalogue.children(c, actor, resourceId);
        });
    }

    @Override
    public AvailabilityCalendar saveCalendar(SecurityIdentity actor, String calendarId, Map<String, Object> definition) {
        return tx.inTransaction(c -> calendars.saveCalendar(c, actor, AvailabilityCalendar.fromMap(calendarId, definition)));
    }

    @Override
    public AvailabilityCalendar calendar(SecurityIdentity actor, String calendarId) {
        return tx.inTransaction(c -> {
            ctx.guard().require(c, actor, ResourcesContext.RT_RESOURCE, ResourcesContext.P_READ, "calendar:" + calendarId);
            return calendars.findCalendar(c, actor.tenantId(), calendarId)
                .orElseThrow(() -> ResourcesException.notFound("calendar", calendarId));
        });
    }

    @Override
    public CalendarService.BlackoutResult addBlackout(SecurityIdentity actor, BlackoutRequest r) {
        return tx.inTransaction(c -> calendars.addBlackout(c, actor, r.calendarId(), r.resourceId(),
                r.kind() == null ? CalendarService.BlackoutKind.BLACKOUT : r.kind(), r.from(), r.to(), r.reason()));
    }

    @Override
    public void removeBlackout(SecurityIdentity actor, String blackoutId, String reason) {
        tx.inTransaction(c -> {
            calendars.removeBlackout(c, actor, blackoutId, reason);
            return null;
        });
    }

    @Override
    public CalendarService.SlotTemplate saveSlotTemplate(SecurityIdentity actor, CalendarService.SlotTemplate template) {
        return tx.inTransaction(c -> calendars.saveSlotTemplate(c, actor, template));
    }

    @Override
    public void deleteSlotTemplate(SecurityIdentity actor, String templateId) {
        tx.inTransaction(c -> {
            calendars.deleteSlotTemplate(c, actor, templateId);
            return null;
        });
    }

    @Override
    public List<CalendarService.Slot> slots(SecurityIdentity actor, String resourceId, Instant from, Instant to) {
        return tx.inTransaction(c -> {
            Resource r = catalogue.visible(c, actor, resourceId);
            return calendars.slots(c, actor.tenantId(), r, catalogue.type(c, actor.tenantId(), r.typeId()), new Interval(from, to));
        });
    }

    // ------------------------------------------------------------------ booking

    @Override
    public List<BookingModel.SlotAvailability> availability(SecurityIdentity actor, String resourceId, Instant from, Instant to) {
        return tx.inTransaction(c -> bookings.availability(c, actor, resourceId, from, to));
    }

    @Override
    public List<BookingModel.Candidate> searchAvailable(SecurityIdentity actor, Resource.Query filter, Instant from, Instant to,
                                                        int quantity) {
        return tx.inTransaction(c -> bookings.search(c, actor, filter, from, to, Math.max(1, quantity)));
    }

    @Override
    public ResourcesPorts.Quote quote(SecurityIdentity actor, BookingModel.HoldRequest request) {
        return tx.inTransaction(c -> bookings.quote(c, actor, request));
    }

    @Override
    public BookingModel.HoldResult hold(SecurityIdentity actor, BookingModel.HoldRequest request) {
        return tx.inTransaction(c -> bookings.hold(c, actor, request));
    }

    @Override
    public BookingModel.SeriesResult holdSeries(SecurityIdentity actor, BookingModel.HoldRequest first,
                                                BookingModel.Recurrence recurrence) {
        return tx.inTransaction(c -> bookings.holdSeries(c, actor, first, recurrence));
    }

    @Override
    public List<BookingModel.Booking> bookings(SecurityIdentity actor, String resourceId, String requesterId, String state,
                                               Instant from, Instant to, boolean mine, int page, int size) {
        return tx.inTransaction(c -> bookings.list(c, actor, resourceId, requesterId,
                state == null ? null : BookingModel.State.valueOf(state), from, to, mine, offset(page, size), size(size)));
    }

    @Override
    public BookingModel.Booking booking(SecurityIdentity actor, String bookingId) {
        return tx.inTransaction(c -> bookings.visible(c, actor, bookingId));
    }

    @Override
    public BookingModel.Booking bookingByRef(SecurityIdentity actor, String bookingRef) {
        return tx.inTransaction(c -> bookings.visibleByRef(c, actor, bookingRef));
    }

    @Override
    public BookingModel.Booking confirm(SecurityIdentity actor, String bookingId) {
        return tx.inTransaction(c -> bookings.confirm(c, actor, bookingId));
    }

    @Override
    public BookingModel.Booking decideApproval(SecurityIdentity actor, String bookingId, boolean approved, String reason) {
        return tx.inTransaction(c -> bookings.decideApproval(c, actor, bookingId, approved, reason));
    }

    @Override
    public BookingModel.Booking checkIn(SecurityIdentity actor, String bookingId) {
        return tx.inTransaction(c -> bookings.checkIn(c, actor, bookingId));
    }

    @Override
    public BookingModel.Booking checkOut(SecurityIdentity actor, String bookingId) {
        return tx.inTransaction(c -> bookings.checkOut(c, actor, bookingId));
    }

    @Override
    public BookingModel.Booking cancel(SecurityIdentity actor, String bookingId, String reason, String cancelledBy) {
        return tx.inTransaction(c -> bookings.cancel(c, actor, bookingId, reason, cancelledBy));
    }

    @Override
    public List<BookingModel.Booking> cancelSeries(SecurityIdentity actor, String recurrenceId, String reason) {
        return tx.inTransaction(c -> bookings.cancelSeries(c, actor, recurrenceId, reason));
    }

    @Override
    public BookingModel.Booking bump(SecurityIdentity actor, String bookingId, String reason, String authorityRef) {
        return tx.inTransaction(c -> bookings.bump(c, actor, bookingId, reason, authorityRef, null));
    }

    @Override
    public List<Map<String, Object>> history(SecurityIdentity actor, String entityType, String entityId) {
        return tx.inTransaction(c -> bookings.history(c, actor, entityType, entityId, this::requireVisible));
    }

    // ------------------------------------------------------------------ waitlist and draws

    @Override
    public BookingModel.WaitlistEntry enqueue(SecurityIdentity actor, BookingModel.WaitlistRequest request) {
        return tx.inTransaction(c -> waitlist.enqueue(c, actor, request));
    }

    @Override
    public List<BookingModel.WaitlistEntry> waitlist(SecurityIdentity actor, String scopeKey, String state, int page, int size) {
        return tx.inTransaction(c -> waitlist.list(c, actor, scopeKey,
                state == null ? null : BookingModel.WaitState.valueOf(state), offset(page, size), size(size)));
    }

    @Override
    public EntryView waitlistEntry(SecurityIdentity actor, String entryId) {
        return tx.inTransaction(c -> new EntryView(waitlist.visible(c, actor, entryId),
                waitlist.position(c, actor.tenantId(), entryId)));
    }

    @Override
    public BookingModel.WaitlistEntry withdraw(SecurityIdentity actor, String entryId, String reason) {
        return tx.inTransaction(c -> waitlist.withdraw(c, actor, entryId, reason));
    }

    @Override
    public BookingModel.Booking acceptOffer(SecurityIdentity actor, String entryId) {
        return tx.inTransaction(c -> waitlist.acceptOffer(c, actor, entryId));
    }

    @Override
    public BookingModel.Draw draw(SecurityIdentity actor, String scopeKey, String seed) {
        return tx.inTransaction(c -> waitlist.draw(c, actor, scopeKey, seed));
    }

    @Override
    public BookingModel.Draw drawResult(SecurityIdentity actor, String drawId) {
        return tx.inTransaction(c -> waitlist.findDraw(c, actor, drawId)
                .orElseThrow(() -> ResourcesException.notFound("draw", drawId)));
    }

    @Override
    public List<String> verifyDraw(List<String> entryIds, String seed) {
        List<String> sorted = new ArrayList<>(entryIds);
        java.util.Collections.sort(sorted);
        String hash = org.sangam.core.layer.Hashes.hex(org.sangam.core.layer.Hashes.sha256(
                String.join("\n", sorted).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return WaitlistService.shuffle(sorted, seed, hash);
    }

    // ------------------------------------------------------------------ allotment

    @Override
    public BookingModel.Allotment allot(SecurityIdentity actor, BookingModel.AllotRequest request) {
        return tx.inTransaction(c -> allotments.allot(c, actor, request));
    }

    @Override
    public BookingModel.Allotment allotNext(SecurityIdentity actor, String scopeKey, String resourceId, Instant from,
                                            Instant to, String orderRef) {
        return tx.inTransaction(c -> waitlist.allotNext(c, actor, scopeKey, resourceId, from, to, orderRef)
                .orElseThrow(() -> ResourcesException.unavailable("NO_ALLOTMENT", "no waiting entrant could be allotted")));
    }

    @Override
    public List<BookingModel.Allotment> allotments(SecurityIdentity actor, String resourceId, String holderId, String state,
                                                   int page, int size) {
        return tx.inTransaction(c -> allotments.list(c, actor, resourceId, holderId,
                state == null ? null : BookingModel.AllotState.valueOf(state), offset(page, size), size(size)));
    }

    @Override
    public BookingModel.Allotment allotment(SecurityIdentity actor, String allotmentId) {
        return tx.inTransaction(c -> allotments.visible(c, actor, allotmentId));
    }

    @Override
    public BookingModel.Allotment noticeToVacate(SecurityIdentity actor, String allotmentId, Instant dueAt, String reason,
                                                 String authorityRef) {
        return tx.inTransaction(c -> allotments.noticeToVacate(c, actor, allotmentId, dueAt, reason, authorityRef));
    }

    @Override
    public BookingModel.Allotment vacate(SecurityIdentity actor, String allotmentId, Instant at, String reason) {
        return tx.inTransaction(c -> allotments.vacate(c, actor, allotmentId, at, reason));
    }

    @Override
    public BookingModel.Allotment extend(SecurityIdentity actor, String allotmentId, Instant newValidTo, long version,
                                         String reason) {
        return tx.inTransaction(c -> allotments.extend(c, actor, allotmentId, newValidTo, version, reason));
    }

    @Override
    public BookingModel.Allotment cancelAllotment(SecurityIdentity actor, String allotmentId, String reason, String authorityRef) {
        return tx.inTransaction(c -> allotments.cancel(c, actor, allotmentId, reason, authorityRef));
    }

    @Override
    public List<BookingModel.Allotment> overdue(SecurityIdentity actor, int limit) {
        return tx.inTransaction(c -> allotments.overdue(c, actor, limit));
    }

    // ------------------------------------------------------------------ operations

    @Override
    public int expireHolds(SecurityIdentity system, int limit) {
        return tx.inTransaction(c -> bookings.expireHolds(c, system, limit));
    }

    @Override
    public int markNoShows(SecurityIdentity system, int limit) {
        return tx.inTransaction(c -> bookings.markNoShows(c, system, limit));
    }

    // ------------------------------------------------------------------ capped counts (A-16)

    @Override
    public VisibleQuery.CappedCount countResources(SecurityIdentity actor, Resource.Query query, int cap) {
        return tx.inTransaction(c -> catalogue.count(c, actor, query, cap));
    }

    @Override
    public VisibleQuery.CappedCount countBookings(SecurityIdentity actor, String resourceId, String requesterId, String state,
                                                  Instant from, Instant to, boolean mine, int cap) {
        return tx.inTransaction(c -> bookings.count(c, actor, resourceId, requesterId,
                state == null ? null : BookingModel.State.valueOf(state), from, to, mine, cap));
    }

    @Override
    public VisibleQuery.CappedCount countWaitlist(SecurityIdentity actor, String scopeKey, String state, int cap) {
        return tx.inTransaction(c -> waitlist.count(c, actor, scopeKey,
                state == null ? null : BookingModel.WaitState.valueOf(state), cap));
    }

    @Override
    public VisibleQuery.CappedCount countAllotments(SecurityIdentity actor, String resourceId, String holderId, String state,
                                                    int cap) {
        return tx.inTransaction(c -> allotments.count(c, actor, resourceId, holderId,
                state == null ? null : BookingModel.AllotState.valueOf(state), cap));
    }

    /** History is readable only for an entity the caller can see through its own list predicate. */
    private void requireVisible(Connection c, SecurityIdentity actor, String entityType, String entityId) {
        switch (entityType) {
            case "BOOKING" -> bookings.visible(c, actor, entityId);
            case "ALLOTMENT" -> allotments.visible(c, actor, entityId);
            case "RESOURCE" -> catalogue.visible(c, actor, entityId);
            case "WAITLIST" -> waitlist.visible(c, actor, entityId);
            case "DRAW" -> waitlist.findDraw(c, actor, entityId).orElseThrow(() -> ResourcesException.notFound("draw", entityId));
            default -> ctx.guard().require(c, actor, ResourcesContext.RT_EVENT, ResourcesContext.P_READ, entityType + ":" + entityId);
        }
    }
}
