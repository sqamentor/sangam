// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.resources.booking.BookingModel;
import org.sangam.resources.booking.BookingService;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;

import java.time.Instant;
import java.util.Map;

/**
 * Places a hold from a process (N01).
 *
 * <p>Inputs: {@code resourceId}, {@code startsAt}, {@code endsAt} (ISO-8601) required; optional {@code quantity},
 * {@code requesterId}, {@code beneficiaryId}, {@code partyRef}, {@code purpose}, {@code entitlementClass},
 * {@code priorityLevel}, {@code bookingAttributes} (map), {@code waitlistIfFull}, {@code bump}, {@code authorityRef},
 * {@code reason}, {@code holdMinutes}. The process business key is stored on the booking.
 * Outputs: {@code bookingId}, {@code bookingRef}, {@code bookingState}, {@code holdExpiresAt}, or {@code waitlistEntryId}
 * when waitlisted. Errors become BPMN errors with the module's reason code ({@code SLOT_TAKEN}, {@code NOT_ENTITLED},
 * {@code CLOSED} ...), so the model can route them with boundary error events.
 */
public class HoldBookingDelegate implements JavaDelegate {

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution e) {
        BookingService bookings = ResourcesRuntime.service(BookingService.class);
        Object attrs = e.getVariable("bookingAttributes");
        BookingModel.HoldRequest req = new BookingModel.HoldRequest(
                ResourcesRuntime.required(e, "resourceId"),
                Instant.parse(ResourcesRuntime.required(e, "startsAt")),
                Instant.parse(ResourcesRuntime.required(e, "endsAt")),
                Sql.intOrNull(e.getVariable("quantity")),
                ResourcesRuntime.str(e, "requesterId"),
                ResourcesRuntime.str(e, "beneficiaryId"),
                ResourcesRuntime.str(e, "partyRef"),
                ResourcesRuntime.str(e, "purpose"),
                ResourcesRuntime.str(e, "entitlementClass"),
                Sql.intOrNull(e.getVariable("priorityLevel")),
                attrs instanceof Map<?, ?> m ? (Map<String, Object>) m : null,
                e.getProcessBusinessKey(),
                Sql.bool(e.getVariable("bump"), false),
                ResourcesRuntime.str(e, "authorityRef"),
                ResourcesRuntime.str(e, "reason"),
                Sql.bool(e.getVariable("waitlistIfFull"), false),
                Sql.intOrNull(e.getVariable("holdMinutes")));
        try {
            BookingModel.HoldResult r = bookings.hold(ResourcesRuntime.connection(), ResourcesRuntime.identity(e), req);
            if (r.held()) {
                e.setVariable("bookingId", r.booking().bookingId());
                e.setVariable("bookingRef", r.booking().bookingRef());
                e.setVariable("bookingState", r.booking().state().name());
                e.setVariable("holdExpiresAt", String.valueOf(r.booking().holdExpiresAt()));
                e.setVariable("bumpedBookingIds", r.bumpedBookingIds());
            } else {
                e.setVariable("bookingState", "WAITLISTED");
                e.setVariable("waitlistEntryId", r.waitlisted().entryId());
            }
        } catch (ResourcesException ex) {
            throw new BpmnError(ex.reason(), ex.getMessage());
        }
    }
}
