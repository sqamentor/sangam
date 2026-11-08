// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.resources;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.booking.BookingModel;
import org.sangam.resources.calendar.AvailabilityCalendar;
import org.sangam.resources.calendar.CalendarService;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.rest.ResourcesRestPort;
import org.sangam.resources.support.VisibleQuery;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resource booking, allotment and scheduling (sangam-resources N01) at {@code /api/v1/resources}: catalogue and
 * types, availability calendars, blackouts and slot templates, quotes, holds and bookings (series, approval,
 * check-in/out, bump), waitlists and draws, allotments. Every call runs in one engine transaction of the module's
 * {@link ResourcesRestPort}; VI.4 visibility and object guards are applied inside the module, endpoint permissions
 * here ({@link ResourcesPermissions}).
 */
@RestController
@RequestMapping("/api/v1/resources")
public class ResourcesController {

    private static final int COUNT_CAP = 1000;

    private final ResourcesRestPort port;
    private final ResourcesIdentity identities;
    private final PermissionPort permissions;

    public ResourcesController(ResourcesRestPort port, ResourcesIdentity identities, PermissionPort permissions) {
        this.port = port;
        this.identities = identities;
        this.permissions = permissions;
    }

    // ---------------------------------------------------------------- request bodies

    public record StatusRequest(Resource.Status status, String reason) {
    }

    public record MoveRequest(String newParentId) {
    }

    public record AvailableSearchRequest(Resource.Query filter, Instant from, Instant to, Integer quantity) {
    }

    public record SeriesRequest(BookingModel.HoldRequest first, BookingModel.Recurrence recurrence) {
    }

    public record ApprovalRequest(boolean approved, String reason) {
    }

    public record CancelRequest(String reason, String cancelledBy) {
    }

    public record ReasonRequest(String reason) {
    }

    public record BumpRequest(String reason, String authorityRef) {
    }

    public record DrawRequest(String scopeKey, String seed) {
    }

    public record DrawVerifyRequest(List<String> entryIds, String seed) {
    }

    public record AllotNextRequest(String scopeKey, String resourceId, Instant from, Instant to, String orderRef) {
    }

    public record NoticeRequest(Instant dueAt, String reason, String authorityRef) {
    }

    public record VacateRequest(Instant at, String reason) {
    }

    public record ExtendRequest(Instant newValidTo, long version, String reason) {
    }

    public record CancelAllotmentRequest(String reason, String authorityRef) {
    }

    // ---------------------------------------------------------------- types and catalogue

    @GetMapping("/types")
    public ResponseEntity<List<ResourceType>> types(RequestContext ctx) {
        return Lists.ok(port.types(read(ctx)));
    }

    @PostMapping("/types")
    public ResponseEntity<ResourceType> defineType(RequestContext ctx, @RequestBody Map<String, Object> definition) {
        ResourceType t = port.defineType(admin(ctx), definition);
        return Lists.okRef(String.valueOf(definition.getOrDefault("typeId", "")), t);
    }

    @DeleteMapping("/types/{typeId}")
    public ResponseEntity<Void> retireType(RequestContext ctx, @PathVariable String typeId) {
        port.retireType(admin(ctx), typeId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/items")
    public ResponseEntity<Resource> createResource(RequestContext ctx, @RequestBody Resource.Draft draft) {
        Resource r = port.createResource(admin(ctx), draft);
        return Lists.created("/api/v1/resources/items/" + r.resourceId(), r.resourceId(), r);
    }

    @GetMapping("/items")
    public ResponseEntity<List<Resource>> search(RequestContext ctx,
            @RequestParam(required = false) String typeId, @RequestParam(required = false) String parentId,
            @RequestParam(required = false) String ancestorId, @RequestParam(required = false) String orgUnitId,
            @RequestParam(required = false) String locationId, @RequestParam(required = false) Integer minCapacity,
            @RequestParam(required = false) Resource.Status status, @RequestParam(required = false) String text,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        SecurityIdentity id = read(ctx);
        Page.PageRequest pr = Lists.page(page, size);
        Resource.Query q = new Resource.Query(typeId, parentId, ancestorId, orgUnitId, locationId, minCapacity, status,
                Map.of(), text, pr.firstResult(), pr.size());
        return listed(port.search(id, q), port.countResources(id, q, COUNT_CAP));
    }

    @GetMapping("/items/{resourceId}")
    public Resource resource(RequestContext ctx, @PathVariable String resourceId) {
        return port.resource(read(ctx), resourceId);
    }

    @PatchMapping("/items/{resourceId}")
    public ResponseEntity<Resource> updateResource(RequestContext ctx, @PathVariable String resourceId,
            @RequestParam long version, @RequestBody Resource.Patch patch) {
        return Lists.okRef(resourceId, port.updateResource(admin(ctx), resourceId, version, patch));
    }

    @PostMapping("/items/{resourceId}/status")
    public ResponseEntity<Resource> setStatus(RequestContext ctx, @PathVariable String resourceId,
                                              @RequestBody StatusRequest body) {
        return Lists.okRef(resourceId, port.setStatus(admin(ctx), resourceId, body.status(), body.reason()));
    }

    @PostMapping("/items/{resourceId}/move")
    public ResponseEntity<Resource> move(RequestContext ctx, @PathVariable String resourceId, @RequestBody MoveRequest body) {
        return Lists.okRef(resourceId, port.move(admin(ctx), resourceId, body.newParentId()));
    }

    @GetMapping("/items/{resourceId}/children")
    public ResponseEntity<List<Resource>> children(RequestContext ctx, @PathVariable String resourceId) {
        return Lists.ok(port.children(read(ctx), resourceId));
    }

    // ---------------------------------------------------------------- calendars, blackouts, slot templates

    @PutMapping("/calendars/{calendarId}")
    public ResponseEntity<AvailabilityCalendar> saveCalendar(RequestContext ctx, @PathVariable String calendarId,
                                                             @RequestBody Map<String, Object> definition) {
        return Lists.okRef(calendarId, port.saveCalendar(admin(ctx), calendarId, definition));
    }

    @GetMapping("/calendars/{calendarId}")
    public AvailabilityCalendar calendar(RequestContext ctx, @PathVariable String calendarId) {
        return port.calendar(read(ctx), calendarId);
    }

    @PostMapping("/blackouts")
    public ResponseEntity<CalendarService.BlackoutResult> addBlackout(RequestContext ctx,
                                                                      @RequestBody ResourcesRestPort.BlackoutRequest body) {
        CalendarService.BlackoutResult r = port.addBlackout(admin(ctx), body);
        return Lists.created("/api/v1/resources/blackouts/" + r.blackout().blackoutId(), r.blackout().blackoutId(), r);
    }

    @DeleteMapping("/blackouts/{blackoutId}")
    public ResponseEntity<Void> removeBlackout(RequestContext ctx, @PathVariable String blackoutId,
                                               @RequestParam(required = false) String reason) {
        port.removeBlackout(admin(ctx), blackoutId, reason);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/slot-templates")
    public ResponseEntity<CalendarService.SlotTemplate> saveSlotTemplate(RequestContext ctx,
                                                                         @RequestBody CalendarService.SlotTemplate body) {
        CalendarService.SlotTemplate t = port.saveSlotTemplate(admin(ctx), body);
        return Lists.okRef(t.templateId(), t);
    }

    @DeleteMapping("/slot-templates/{templateId}")
    public ResponseEntity<Void> deleteSlotTemplate(RequestContext ctx, @PathVariable String templateId) {
        port.deleteSlotTemplate(admin(ctx), templateId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/items/{resourceId}/slots")
    public ResponseEntity<List<CalendarService.Slot>> slots(RequestContext ctx, @PathVariable String resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return Lists.ok(port.slots(read(ctx), resourceId, from, to));
    }

    @GetMapping("/items/{resourceId}/availability")
    public ResponseEntity<List<BookingModel.SlotAvailability>> availability(RequestContext ctx,
            @PathVariable String resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return Lists.ok(port.availability(read(ctx), resourceId, from, to));
    }

    @PostMapping("/search-available")
    public ResponseEntity<List<BookingModel.Candidate>> searchAvailable(RequestContext ctx,
                                                                        @RequestBody AvailableSearchRequest body) {
        Resource.Query f = body.filter() == null ? new Resource.Query(null, null, null, null, null, null, null,
                Map.of(), null, 0, 100) : body.filter();
        return Lists.ok(port.searchAvailable(read(ctx), f, body.from(), body.to(),
                body.quantity() == null ? 1 : body.quantity()));
    }

    // ---------------------------------------------------------------- quotes, holds and bookings

    @PostMapping("/bookings/quote")
    public ResourcesPorts.Quote quote(RequestContext ctx, @RequestBody BookingModel.HoldRequest body) {
        return port.quote(read(ctx), body);
    }

    @PostMapping("/bookings/hold")
    public ResponseEntity<BookingModel.HoldResult> hold(RequestContext ctx, @RequestBody BookingModel.HoldRequest body) {
        BookingModel.HoldResult r = port.hold(write(ctx), body);
        String ref = r.booking() != null ? r.booking().bookingId() : r.waitlisted() != null ? r.waitlisted().entryId() : null;
        return ResponseEntity.status(201).header(Lists.RESOURCE_REF, ref).body(r);
    }

    @PostMapping("/bookings/hold-series")
    public ResponseEntity<BookingModel.SeriesResult> holdSeries(RequestContext ctx, @RequestBody SeriesRequest body) {
        BookingModel.SeriesResult r = port.holdSeries(write(ctx), body.first(), body.recurrence());
        return ResponseEntity.status(201).header(Lists.RESOURCE_REF, r.recurrenceId()).body(r);
    }

    @GetMapping("/bookings")
    public ResponseEntity<List<BookingModel.Booking>> bookings(RequestContext ctx,
            @RequestParam(required = false) String resourceId, @RequestParam(required = false) String requesterId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        SecurityIdentity id = read(ctx);
        Page.PageRequest pr = Lists.page(page, size);
        return listed(port.bookings(id, resourceId, requesterId, state, from, to, mine, pr.page(), pr.size()),
                port.countBookings(id, resourceId, requesterId, state, from, to, mine, COUNT_CAP));
    }

    @GetMapping("/bookings/{bookingId}")
    public BookingModel.Booking booking(RequestContext ctx, @PathVariable String bookingId) {
        return port.booking(read(ctx), bookingId);
    }

    @GetMapping("/bookings/by-ref/{bookingRef}")
    public BookingModel.Booking bookingByRef(RequestContext ctx, @PathVariable String bookingRef) {
        return port.bookingByRef(read(ctx), bookingRef);
    }

    @PostMapping("/bookings/{bookingId}/confirm")
    public ResponseEntity<BookingModel.Booking> confirm(RequestContext ctx, @PathVariable String bookingId) {
        return Lists.okRef(bookingId, port.confirm(write(ctx), bookingId));
    }

    @PostMapping("/bookings/{bookingId}/approval")
    public ResponseEntity<BookingModel.Booking> decideApproval(RequestContext ctx, @PathVariable String bookingId,
                                                               @RequestBody ApprovalRequest body) {
        return Lists.okRef(bookingId, port.decideApproval(write(ctx), bookingId, body.approved(), body.reason()));
    }

    @PostMapping("/bookings/{bookingId}/check-in")
    public ResponseEntity<BookingModel.Booking> checkIn(RequestContext ctx, @PathVariable String bookingId) {
        return Lists.okRef(bookingId, port.checkIn(write(ctx), bookingId));
    }

    @PostMapping("/bookings/{bookingId}/check-out")
    public ResponseEntity<BookingModel.Booking> checkOut(RequestContext ctx, @PathVariable String bookingId) {
        return Lists.okRef(bookingId, port.checkOut(write(ctx), bookingId));
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    public ResponseEntity<BookingModel.Booking> cancel(RequestContext ctx, @PathVariable String bookingId,
                                                       @RequestBody CancelRequest body) {
        return Lists.okRef(bookingId, port.cancel(write(ctx), bookingId, body.reason(), body.cancelledBy()));
    }

    @PostMapping("/bookings/series/{recurrenceId}/cancel")
    public ResponseEntity<List<BookingModel.Booking>> cancelSeries(RequestContext ctx, @PathVariable String recurrenceId,
                                                                   @RequestBody ReasonRequest body) {
        return Lists.ok(port.cancelSeries(write(ctx), recurrenceId, body.reason()));
    }

    @PostMapping("/bookings/{bookingId}/bump")
    public ResponseEntity<BookingModel.Booking> bump(RequestContext ctx, @PathVariable String bookingId,
                                                     @RequestBody BumpRequest body) {
        return Lists.okRef(bookingId, port.bump(admin(ctx), bookingId, body.reason(), body.authorityRef()));
    }

    @GetMapping("/history/{entityType}/{entityId}")
    public ResponseEntity<List<Map<String, Object>>> history(RequestContext ctx, @PathVariable String entityType,
                                                             @PathVariable String entityId) {
        return Lists.ok(port.history(read(ctx), entityType, entityId));
    }

    // ---------------------------------------------------------------- waitlist and draws

    @PostMapping("/waitlist")
    public ResponseEntity<BookingModel.WaitlistEntry> enqueue(RequestContext ctx,
                                                              @RequestBody BookingModel.WaitlistRequest body) {
        BookingModel.WaitlistEntry e = port.enqueue(write(ctx), body);
        return Lists.created("/api/v1/resources/waitlist/" + e.entryId(), e.entryId(), e);
    }

    @GetMapping("/waitlist")
    public ResponseEntity<List<BookingModel.WaitlistEntry>> waitlist(RequestContext ctx,
            @RequestParam(required = false) String scopeKey, @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        SecurityIdentity id = read(ctx);
        Page.PageRequest pr = Lists.page(page, size);
        return listed(port.waitlist(id, scopeKey, state, pr.page(), pr.size()),
                port.countWaitlist(id, scopeKey, state, COUNT_CAP));
    }

    @GetMapping("/waitlist/{entryId}")
    public ResourcesRestPort.EntryView waitlistEntry(RequestContext ctx, @PathVariable String entryId) {
        return port.waitlistEntry(read(ctx), entryId);
    }

    @PostMapping("/waitlist/{entryId}/withdraw")
    public ResponseEntity<BookingModel.WaitlistEntry> withdraw(RequestContext ctx, @PathVariable String entryId,
                                                               @RequestBody ReasonRequest body) {
        return Lists.okRef(entryId, port.withdraw(write(ctx), entryId, body.reason()));
    }

    @PostMapping("/waitlist/{entryId}/accept-offer")
    public ResponseEntity<BookingModel.Booking> acceptOffer(RequestContext ctx, @PathVariable String entryId) {
        return Lists.okRef(entryId, port.acceptOffer(write(ctx), entryId));
    }

    @PostMapping("/draws")
    public ResponseEntity<BookingModel.Draw> draw(RequestContext ctx, @RequestBody DrawRequest body) {
        BookingModel.Draw d = port.draw(admin(ctx), body.scopeKey(), body.seed());
        return Lists.created("/api/v1/resources/draws/" + d.drawId(), d.drawId(), d);
    }

    @GetMapping("/draws/{drawId}")
    public BookingModel.Draw drawResult(RequestContext ctx, @PathVariable String drawId) {
        return port.drawResult(read(ctx), drawId);
    }

    /** Public re-computation of a draw from its entrants and seed (transparency). */
    @PostMapping("/draws/verify")
    public ResponseEntity<List<String>> verifyDraw(RequestContext ctx, @RequestBody DrawVerifyRequest body) {
        read(ctx);
        return Lists.ok(port.verifyDraw(body.entryIds(), body.seed()));
    }

    // ---------------------------------------------------------------- allotments

    @PostMapping("/allotments")
    public ResponseEntity<BookingModel.Allotment> allot(RequestContext ctx, @RequestBody BookingModel.AllotRequest body) {
        BookingModel.Allotment a = port.allot(admin(ctx), body);
        return Lists.created("/api/v1/resources/allotments/" + a.allotmentId(), a.allotmentId(), a);
    }

    @PostMapping("/allotments/next")
    public ResponseEntity<BookingModel.Allotment> allotNext(RequestContext ctx, @RequestBody AllotNextRequest body) {
        BookingModel.Allotment a = port.allotNext(admin(ctx), body.scopeKey(), body.resourceId(), body.from(), body.to(),
                body.orderRef());
        return Lists.created("/api/v1/resources/allotments/" + a.allotmentId(), a.allotmentId(), a);
    }

    @GetMapping("/allotments")
    public ResponseEntity<List<BookingModel.Allotment>> allotments(RequestContext ctx,
            @RequestParam(required = false) String resourceId, @RequestParam(required = false) String holderId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        SecurityIdentity id = read(ctx);
        Page.PageRequest pr = Lists.page(page, size);
        return listed(port.allotments(id, resourceId, holderId, state, pr.page(), pr.size()),
                port.countAllotments(id, resourceId, holderId, state, COUNT_CAP));
    }

    @GetMapping("/allotments/overdue")
    public ResponseEntity<List<BookingModel.Allotment>> overdue(RequestContext ctx,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        return Lists.ok(port.overdue(read(ctx), Math.min(Math.max(limit, 1), 1000)));
    }

    @GetMapping("/allotments/{allotmentId}")
    public BookingModel.Allotment allotment(RequestContext ctx, @PathVariable String allotmentId) {
        return port.allotment(read(ctx), allotmentId);
    }

    @PostMapping("/allotments/{allotmentId}/notice-to-vacate")
    public ResponseEntity<BookingModel.Allotment> noticeToVacate(RequestContext ctx, @PathVariable String allotmentId,
                                                                 @RequestBody NoticeRequest body) {
        return Lists.okRef(allotmentId, port.noticeToVacate(admin(ctx), allotmentId, body.dueAt(), body.reason(),
                body.authorityRef()));
    }

    @PostMapping("/allotments/{allotmentId}/vacate")
    public ResponseEntity<BookingModel.Allotment> vacate(RequestContext ctx, @PathVariable String allotmentId,
                                                         @RequestBody VacateRequest body) {
        return Lists.okRef(allotmentId, port.vacate(write(ctx), allotmentId, body.at(), body.reason()));
    }

    @PostMapping("/allotments/{allotmentId}/extend")
    public ResponseEntity<BookingModel.Allotment> extend(RequestContext ctx, @PathVariable String allotmentId,
                                                         @RequestBody ExtendRequest body) {
        return Lists.okRef(allotmentId, port.extend(admin(ctx), allotmentId, body.newValidTo(), body.version(),
                body.reason()));
    }

    @PostMapping("/allotments/{allotmentId}/cancel")
    public ResponseEntity<BookingModel.Allotment> cancelAllotment(RequestContext ctx, @PathVariable String allotmentId,
                                                                  @RequestBody CancelAllotmentRequest body) {
        return Lists.okRef(allotmentId, port.cancelAllotment(admin(ctx), allotmentId, body.reason(), body.authorityRef()));
    }

    // ---------------------------------------------------------------- operator jobs (the sweepers run these too)

    @PostMapping("/jobs/expire-holds")
    public Map<String, Object> expireHolds(RequestContext ctx, @RequestParam(required = false, defaultValue = "200") int limit) {
        permissions.require(ctx, ResourcesPermissions.ADMIN);
        return Map.of("expired", port.expireHolds(SecurityIdentity.system(ctx.tenantId(), ctx.principalId()),
                Math.min(Math.max(limit, 1), 1000)));
    }

    @PostMapping("/jobs/no-shows")
    public Map<String, Object> markNoShows(RequestContext ctx, @RequestParam(required = false, defaultValue = "200") int limit) {
        permissions.require(ctx, ResourcesPermissions.ADMIN);
        return Map.of("noShows", port.markNoShows(SecurityIdentity.system(ctx.tenantId(), ctx.principalId()),
                Math.min(Math.max(limit, 1), 1000)));
    }

    // ---------------------------------------------------------------- helpers

    private SecurityIdentity read(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.READ);
        return identities.of(ctx);
    }

    private SecurityIdentity write(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.WRITE);
        return identities.of(ctx);
    }

    private SecurityIdentity admin(RequestContext ctx) {
        permissions.require(ctx, ResourcesPermissions.ADMIN);
        return identities.of(ctx);
    }

    static <T> ResponseEntity<List<T>> listed(List<T> items, VisibleQuery.CappedCount count) {
        if (count == null) {
            return Lists.ok(items);
        }
        return Lists.ok(Page.of(items, count.value(), count.capped()));
    }
}
