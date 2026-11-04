// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.booking.BookingModel;
import org.sangam.resources.booking.BookingService;
import org.sangam.resources.support.ResourcesException;
import org.sangam.resources.support.Sql;

import java.sql.Connection;

/**
 * One delegate for the booking life-cycle steps, selected by the field {@code action} (an Operaton field injection,
 * {@code <operaton:field name="action" stringValue="CONFIRM"/>}):
 * <ul>
 *   <li>{@code CONFIRM}: confirm the held booking {@code bookingId} (starts approval when the type requires it);</li>
 *   <li>{@code APPROVAL_DECISION}: apply {@code approved} (boolean) and {@code reason} from the approval user task;</li>
 *   <li>{@code CHECK_IN}, {@code CHECK_OUT};</li>
 *   <li>{@code CANCEL}: with {@code reason} and optional {@code cancelledBy} (REQUESTER, ADMIN, SYSTEM_CLOSURE);</li>
 *   <li>{@code BUMP}: with {@code reason}, {@code authorityRef} and optional {@code bumpedBy};</li>
 *   <li>{@code EXPIRE_HOLDS} and {@code MARK_NO_SHOWS}: sweepers for a timer-started process ({@code limit}, default 200).</li>
 * </ul>
 * Output: {@code bookingState} (or {@code sweptCount}). Module errors become BPMN errors with the reason code.
 */
public class BookingActionDelegate implements JavaDelegate {

    /** Injected field. */
    private Expression action;

    public void setAction(Expression action) {
        this.action = action;
    }

    @Override
    public void execute(DelegateExecution e) {
        BookingService bookings = ResourcesRuntime.service(BookingService.class);
        String a = action == null ? ResourcesRuntime.required(e, "bookingAction") : String.valueOf(action.getValue(e));
        Connection c = ResourcesRuntime.connection();
        SecurityIdentity actor = ResourcesRuntime.identity(e);
        try {
            switch (a) {
                case "EXPIRE_HOLDS" -> e.setVariable("sweptCount", bookings.expireHolds(c, actor, limit(e)));
                case "MARK_NO_SHOWS" -> e.setVariable("sweptCount", bookings.markNoShows(c, actor, limit(e)));
                default -> {
                    String id = ResourcesRuntime.required(e, "bookingId");
                    BookingModel.Booking b = switch (a) {
                        case "CONFIRM" -> bookings.confirm(c, actor, id);
                        case "APPROVAL_DECISION" -> bookings.decideApproval(c, actor, id,
                                Sql.bool(e.getVariable("approved"), false), ResourcesRuntime.str(e, "reason"));
                        case "CHECK_IN" -> bookings.checkIn(c, actor, id);
                        case "CHECK_OUT" -> bookings.checkOut(c, actor, id);
                        case "CANCEL" -> bookings.cancel(c, actor, id, ResourcesRuntime.str(e, "reason"),
                                ResourcesRuntime.str(e, "cancelledBy"));
                        case "BUMP" -> bookings.bump(c, actor, id, ResourcesRuntime.str(e, "reason"),
                                ResourcesRuntime.str(e, "authorityRef"), ResourcesRuntime.str(e, "bumpedBy"));
                        default -> throw new IllegalArgumentException("unknown booking action: " + a);
                    };
                    e.setVariable("bookingState", b.state().name());
                    e.setVariable("bookingRef", b.bookingRef());
                    if (b.chargeRef() != null) {
                        e.setVariable("chargeRef", b.chargeRef());
                    }
                }
            }
        } catch (ResourcesException ex) {
            throw new BpmnError(ex.reason(), ex.getMessage());
        }
    }

    private static int limit(DelegateExecution e) {
        return Sql.intOr(e.getVariable("limit"), 200);
    }
}
