// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.booking.AllotmentService;
import org.sangam.resources.booking.BookingModel;
import org.sangam.resources.booking.WaitlistService;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Waitlist, draw and allotment steps, selected by the field {@code action}:
 * <ul>
 *   <li>{@code ENQUEUE}: {@code typeId} or {@code resourceId}, {@code requesterId}, {@code category},
 *       {@code seniorityAt}, {@code wantedFrom}, {@code wantedTo}, {@code quantity} → {@code waitlistEntryId},
 *       {@code waitlistPosition};</li>
 *   <li>{@code DRAW}: {@code scopeKey}, {@code seed} → {@code drawId}, {@code entrantsHash}, {@code drawOrder};</li>
 *   <li>{@code ALLOT_NEXT}: {@code scopeKey}, optional {@code resourceId}, {@code validFrom}, {@code validTo},
 *       {@code orderRef}, {@code count} (default 1) → {@code allotmentIds};</li>
 *   <li>{@code ALLOT}: {@code resourceId}, {@code holderId}, {@code validFrom}, optional {@code validTo},
 *       {@code unitNo}, {@code orderRef}, {@code entitlementClass} → {@code allotmentId}, {@code allotmentRef};</li>
 *   <li>{@code VACATE}: {@code allotmentId}, optional {@code at}, {@code reason};</li>
 *   <li>{@code ACCEPT_OFFER}: {@code waitlistEntryId} → {@code bookingId}, {@code bookingState}.</li>
 * </ul>
 */
public class WaitlistDelegate implements JavaDelegate {

    private Expression action;

    public void setAction(Expression action) {
        this.action = action;
    }

    @Override
    public void execute(DelegateExecution e) {
        WaitlistService waitlist = ResourcesRuntime.service(WaitlistService.class);
        AllotmentService allotments = ResourcesRuntime.service(AllotmentService.class);
        String a = action == null ? ResourcesRuntime.required(e, "waitlistAction") : String.valueOf(action.getValue(e));
        Connection c = ResourcesRuntime.connection();
        SecurityIdentity actor = ResourcesRuntime.identity(e);
        try {
            switch (a) {
                case "ENQUEUE" -> {
                    BookingModel.WaitlistEntry w = waitlist.enqueue(c, actor, new BookingModel.WaitlistRequest(
                            ResourcesRuntime.str(e, "typeId"), ResourcesRuntime.str(e, "resourceId"),
                            ResourcesRuntime.str(e, "requesterId"), ResourcesRuntime.str(e, "beneficiaryId"),
                            ResourcesRuntime.str(e, "category"), Sql.intOrNull(e.getVariable("priorityLevel")),
                            instant(e, "seniorityAt"), instant(e, "wantedFrom"), instant(e, "wantedTo"),
                            Sql.intOrNull(e.getVariable("quantity")), null));
                    e.setVariable("waitlistEntryId", w.entryId());
                    e.setVariable("waitlistPosition", waitlist.position(c, actor.tenantId(), w.entryId()));
                }
                case "DRAW" -> {
                    BookingModel.Draw d = waitlist.draw(c, actor, ResourcesRuntime.required(e, "scopeKey"),
                            ResourcesRuntime.required(e, "seed"));
                    e.setVariable("drawId", d.drawId());
                    e.setVariable("entrantsHash", d.entrantsHash());
                    e.setVariable("drawOrder", d.orderedEntryIds());
                }
                case "ALLOT_NEXT" -> {
                    int count = Sql.intOr(e.getVariable("count"), 1);
                    Instant from = Optional.ofNullable(instant(e, "validFrom"))
                            .orElseGet(() -> ResourcesRuntime.service(org.sangam.resources.ResourcesContext.class).now(c));
                    List<String> ids = new java.util.ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        Optional<BookingModel.Allotment> al = waitlist.allotNext(c, actor, ResourcesRuntime.required(e, "scopeKey"),
                                ResourcesRuntime.str(e, "resourceId"), from, instant(e, "validTo"), ResourcesRuntime.str(e, "orderRef"));
                        if (al.isEmpty()) {
                            break;
                        }
                        ids.add(al.get().allotmentId());
                    }
                    e.setVariable("allotmentIds", ids);
                }
                case "ALLOT" -> {
                    BookingModel.Allotment al = allotments.allot(c, actor, new BookingModel.AllotRequest(
                            ResourcesRuntime.required(e, "resourceId"), Sql.intOrNull(e.getVariable("unitNo")),
                            ResourcesRuntime.required(e, "holderId"), ResourcesRuntime.str(e, "beneficiaryId"),
                            Instant.parse(ResourcesRuntime.required(e, "validFrom")), instant(e, "validTo"),
                            ResourcesRuntime.str(e, "orderRef"), ResourcesRuntime.str(e, "waitlistEntryId"),
                            ResourcesRuntime.str(e, "entitlementClass"), Map.of()));
                    e.setVariable("allotmentId", al.allotmentId());
                    e.setVariable("allotmentRef", al.allotmentRef());
                }
                case "VACATE" -> {
                    BookingModel.Allotment al = allotments.vacate(c, actor, ResourcesRuntime.required(e, "allotmentId"),
                            instant(e, "at"), ResourcesRuntime.str(e, "reason"));
                    e.setVariable("allotmentState", al.state().name());
                }
                case "ACCEPT_OFFER" -> {
                    BookingModel.Booking b = waitlist.acceptOffer(c, actor, ResourcesRuntime.required(e, "waitlistEntryId"));
                    e.setVariable("bookingId", b.bookingId());
                    e.setVariable("bookingState", b.state().name());
                }
                default -> throw new IllegalArgumentException("unknown waitlist action: " + a);
            }
        } catch (ResourcesException ex) {
            throw new BpmnError(ex.reason(), ex.getMessage());
        }
    }

    private static Instant instant(DelegateExecution e, String name) {
        String s = ResourcesRuntime.str(e, name);
        return s == null || s.isBlank() ? null : Instant.parse(s);
    }
}
