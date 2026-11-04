// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.rest;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.booking.BookingModel;
import org.sangam.resources.calendar.AvailabilityCalendar;
import org.sangam.resources.calendar.CalendarService;
import org.sangam.resources.catalogue.Resource;
import org.sangam.resources.catalogue.ResourceType;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.support.VisibleQuery;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Port for the REST workstream: catalogue, calendars, availability, booking, allotment and waitlist (N01). No Spring
 * here; {@code sangam-rest} maps these methods to controllers, problem+json errors
 * ({@link org.sangam.resources.support.ResourcesException#httpStatus()} and {@code reason()}) and Idempotency-Key.
 * Every method runs in one transaction ({@link ResourcesRestAdapter}). Every list, count and get-by-id applies the
 * VI.4 visibility predicate inside the SQL ({@link org.sangam.resources.support.VisibleQuery}); a hidden row is
 * reported as 404 exactly like a missing one, and counts are capped ("more than N").
 *
 * <h2>Endpoints (base {@code /api/v1/resources})</h2>
 * <pre>
 * GET    /types                                  types
 * PUT    /types/{typeId}                         defineType (body: type definition map)
 * DELETE /types/{typeId}                         retireType
 * POST   /items                                  createResource
 * GET    /items?type=&amp;parent=&amp;ancestor=&amp;unit=&amp;location=&amp;minCapacity=&amp;status=&amp;q=&amp;attr.k=v&amp;page=&amp;size=   search
 * GET    /items/count?...&amp;cap=                 countResources
 * GET    /items/{id}                             resource
 * PATCH  /items/{id}  (If-Match: version)        updateResource
 * POST   /items/{id}/status                      setStatus
 * POST   /items/{id}/move                        move
 * GET    /items/{id}/children                    children
 * GET    /items/{id}/availability?from=&amp;to=      availability
 * GET    /items/{id}/slots?from=&amp;to=             slots
 * PUT    /items/{id}/slot-templates/{tid}        saveSlotTemplate
 * DELETE /slot-templates/{tid}                   deleteSlotTemplate
 * PUT    /calendars/{id}                         saveCalendar
 * GET    /calendars/{id}                         calendar
 * POST   /blackouts                              addBlackout (returns affected booking ids)
 * DELETE /blackouts/{id}                         removeBlackout
 * GET    /search?from=&amp;to=&amp;quantity=&amp;type=...    searchAvailable
 * POST   /bookings/quote                         quote
 * POST   /bookings          (Idempotency-Key)    hold
 * POST   /bookings/series   (Idempotency-Key)    holdSeries
 * GET    /bookings?resource=&amp;requester=&amp;state=&amp;from=&amp;to=&amp;mine=&amp;page=&amp;size=   bookings
 * GET    /bookings/count?...&amp;cap=              countBookings (X-Total-Count, X-Total-Count-Capped)
 * GET    /bookings/{id}                          booking
 * GET    /bookings/by-ref/{ref}                  bookingByRef
 * POST   /bookings/{id}/confirm                  confirm
 * POST   /bookings/{id}/approval                 decideApproval
 * POST   /bookings/{id}/check-in                 checkIn
 * POST   /bookings/{id}/check-out                checkOut
 * POST   /bookings/{id}/cancel                   cancel
 * POST   /bookings/{id}/bump                     bump
 * POST   /bookings/series/{recurrenceId}/cancel  cancelSeries
 * GET    /history/{entityType}/{id}              history
 * POST   /waitlist                               enqueue
 * GET    /waitlist?scope=&amp;state=&amp;page=&amp;size=     waitlist
 * GET    /waitlist/count?scope=&amp;state=&amp;cap=     countWaitlist
 * GET    /waitlist/{id}                          waitlistEntry (+ position)
 * POST   /waitlist/{id}/withdraw                 withdraw
 * POST   /waitlist/{id}/accept                   acceptOffer
 * POST   /draws                                  draw
 * GET    /draws/{id}                             drawResult
 * POST   /draws/verify                           verifyDraw (pure recomputation)
 * POST   /allotments        (Idempotency-Key)    allot
 * POST   /allotments/from-waitlist               allotNext
 * GET    /allotments?resource=&amp;holder=&amp;state=&amp;page=&amp;size=   allotments
 * GET    /allotments/count?...&amp;cap=            countAllotments
 * GET    /allotments/{id}                        allotment
 * POST   /allotments/{id}/notice                 noticeToVacate
 * POST   /allotments/{id}/vacate                 vacate
 * POST   /allotments/{id}/extend  (If-Match)     extend
 * POST   /allotments/{id}/cancel                 cancelAllotment
 * GET    /allotments/overdue                     overdue
 * POST   /ops/expire-holds                       expireHolds (system)
 * POST   /ops/no-shows                           markNoShows (system)
 * </pre>
 */
public interface ResourcesRestPort {

    // catalogue and calendars
    List<ResourceType> types(SecurityIdentity actor);

    ResourceType defineType(SecurityIdentity actor, Map<String, Object> definition);

    void retireType(SecurityIdentity actor, String typeId);

    Resource createResource(SecurityIdentity actor, Resource.Draft draft);

    List<Resource> search(SecurityIdentity actor, Resource.Query query);

    Resource resource(SecurityIdentity actor, String resourceId);

    Resource updateResource(SecurityIdentity actor, String resourceId, long version, Resource.Patch patch);

    Resource setStatus(SecurityIdentity actor, String resourceId, Resource.Status status, String reason);

    Resource move(SecurityIdentity actor, String resourceId, String newParentId);

    List<Resource> children(SecurityIdentity actor, String resourceId);

    AvailabilityCalendar saveCalendar(SecurityIdentity actor, String calendarId, Map<String, Object> definition);

    AvailabilityCalendar calendar(SecurityIdentity actor, String calendarId);

    record BlackoutRequest(String calendarId, String resourceId, CalendarService.BlackoutKind kind, Instant from,
                           Instant to, String reason) {
    }

    CalendarService.BlackoutResult addBlackout(SecurityIdentity actor, BlackoutRequest request);

    void removeBlackout(SecurityIdentity actor, String blackoutId, String reason);

    CalendarService.SlotTemplate saveSlotTemplate(SecurityIdentity actor, CalendarService.SlotTemplate template);

    void deleteSlotTemplate(SecurityIdentity actor, String templateId);

    List<CalendarService.Slot> slots(SecurityIdentity actor, String resourceId, Instant from, Instant to);

    // availability and booking
    List<BookingModel.SlotAvailability> availability(SecurityIdentity actor, String resourceId, Instant from, Instant to);

    List<BookingModel.Candidate> searchAvailable(SecurityIdentity actor, Resource.Query filter, Instant from, Instant to,
                                                 int quantity);

    ResourcesPorts.Quote quote(SecurityIdentity actor, BookingModel.HoldRequest request);

    BookingModel.HoldResult hold(SecurityIdentity actor, BookingModel.HoldRequest request);

    BookingModel.SeriesResult holdSeries(SecurityIdentity actor, BookingModel.HoldRequest first, BookingModel.Recurrence recurrence);

    List<BookingModel.Booking> bookings(SecurityIdentity actor, String resourceId, String requesterId, String state,
                                        Instant from, Instant to, boolean mine, int page, int size);

    BookingModel.Booking booking(SecurityIdentity actor, String bookingId);

    BookingModel.Booking bookingByRef(SecurityIdentity actor, String bookingRef);

    BookingModel.Booking confirm(SecurityIdentity actor, String bookingId);

    BookingModel.Booking decideApproval(SecurityIdentity actor, String bookingId, boolean approved, String reason);

    BookingModel.Booking checkIn(SecurityIdentity actor, String bookingId);

    BookingModel.Booking checkOut(SecurityIdentity actor, String bookingId);

    BookingModel.Booking cancel(SecurityIdentity actor, String bookingId, String reason, String cancelledBy);

    List<BookingModel.Booking> cancelSeries(SecurityIdentity actor, String recurrenceId, String reason);

    BookingModel.Booking bump(SecurityIdentity actor, String bookingId, String reason, String authorityRef);

    List<Map<String, Object>> history(SecurityIdentity actor, String entityType, String entityId);

    // waitlist and draws
    BookingModel.WaitlistEntry enqueue(SecurityIdentity actor, BookingModel.WaitlistRequest request);

    List<BookingModel.WaitlistEntry> waitlist(SecurityIdentity actor, String scopeKey, String state, int page, int size);

    record EntryView(BookingModel.WaitlistEntry entry, int position) {
    }

    EntryView waitlistEntry(SecurityIdentity actor, String entryId);

    BookingModel.WaitlistEntry withdraw(SecurityIdentity actor, String entryId, String reason);

    BookingModel.Booking acceptOffer(SecurityIdentity actor, String entryId);

    BookingModel.Draw draw(SecurityIdentity actor, String scopeKey, String seed);

    BookingModel.Draw drawResult(SecurityIdentity actor, String drawId);

    List<String> verifyDraw(List<String> entryIds, String seed);

    // allotment
    BookingModel.Allotment allot(SecurityIdentity actor, BookingModel.AllotRequest request);

    BookingModel.Allotment allotNext(SecurityIdentity actor, String scopeKey, String resourceId, Instant from, Instant to,
                                     String orderRef);

    List<BookingModel.Allotment> allotments(SecurityIdentity actor, String resourceId, String holderId, String state,
                                            int page, int size);

    BookingModel.Allotment allotment(SecurityIdentity actor, String allotmentId);

    BookingModel.Allotment noticeToVacate(SecurityIdentity actor, String allotmentId, Instant dueAt, String reason,
                                          String authorityRef);

    BookingModel.Allotment vacate(SecurityIdentity actor, String allotmentId, Instant at, String reason);

    BookingModel.Allotment extend(SecurityIdentity actor, String allotmentId, Instant newValidTo, long version, String reason);

    BookingModel.Allotment cancelAllotment(SecurityIdentity actor, String allotmentId, String reason, String authorityRef);

    List<BookingModel.Allotment> overdue(SecurityIdentity actor, int limit);

    // operations
    int expireHolds(SecurityIdentity system, int limit);

    int markNoShows(SecurityIdentity system, int limit);

    // capped counts of the lists above (A-16): same predicate, at most cap + 1 rows counted
    VisibleQuery.CappedCount countResources(SecurityIdentity actor, Resource.Query query, int cap);

    VisibleQuery.CappedCount countBookings(SecurityIdentity actor, String resourceId, String requesterId, String state,
                                           Instant from, Instant to, boolean mine, int cap);

    VisibleQuery.CappedCount countWaitlist(SecurityIdentity actor, String scopeKey, String state, int cap);

    VisibleQuery.CappedCount countAllotments(SecurityIdentity actor, String resourceId, String holderId, String state, int cap);
}
