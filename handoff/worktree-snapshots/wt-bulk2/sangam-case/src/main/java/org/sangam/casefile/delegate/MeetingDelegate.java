// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.delegate;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.casefile.CaseModule;
import org.sangam.casefile.meeting.ActionItemService;
import org.sangam.casefile.meeting.MeetingService;
import org.sangam.casefile.support.Db;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.sangam.casefile.delegate.DelegateSupport.*;

/**
 * BPMN service task for committee meetings (N16). Field {@code op}:
 * <ul>
 *   <li>{@code SCHEDULE}: {@code committeeId}, {@code meetingTitle}, {@code meetingAt}, optional {@code meetingEndsAt},
 *       {@code venue}, {@code meetingMode}, {@code urgentReason}, {@code caseFileId}; sets {@code meetingId},
 *       {@code meetingNumber};</li>
 *   <li>{@code AGENDA}: adds every map of {@code agendaItems} ({@code title}, {@code description}, {@code linkedType},
 *       {@code linkedRef}, {@code presenter}) to {@code meetingId};</li>
 *   <li>{@code SEND_NOTICES}: {@code noticeKind} (default NOTICE);</li>
 *   <li>{@code CHECK_QUORUM}: sets {@code quorumMet}, {@code quorumRequired}, {@code quorumPresent};</li>
 *   <li>{@code START}, {@code END}; {@code ADJOURN} ({@code adjournReason}, optional {@code adjournTo}; sets
 *       {@code adjournedMeetingId});</li>
 *   <li>{@code ISSUE_MINUTES}: issues the approved minutes;</li>
 *   <li>{@code CREATE_ACTIONS}: one action item per map of {@code actionItems} ({@code title}, {@code ownerSeat},
 *       {@code ownerPerson}, {@code dueAt}, {@code period}, {@code decisionId}); sets {@code actionIds};</li>
 *   <li>{@code CARRY_FORWARD}: {@code meetingId} → {@code nextMeetingId}; {@code PLACE_ATR}: the ATR of
 *       {@code meetingId} on the agenda of {@code nextMeetingId};</li>
 *   <li>{@code PENDING_ACTIONS}: sets {@code pendingActions} (count) for {@code committeeId}.</li>
 * </ul>
 */
public class MeetingDelegate implements JavaDelegate {

    private Expression op;

    @Override
    public void execute(DelegateExecution ex) {
        String operation = field(op, ex, "SCHEDULE");
        run(() -> perform(ex, operation));
    }

    private void perform(DelegateExecution ex, String operation) {
        CaseModule m = module();
        Connection c = connection();
        String t = tenant(ex);
        String actor = actor(ex);
        switch (operation) {
            case "SCHEDULE" -> {
                MeetingService.Meeting mt = m.meetings().schedule(c, t, new MeetingService.ScheduleRequest(req(ex, "committeeId"),
                        req(ex, "meetingTitle"), Db.require(instant(ex, "meetingAt"), "meetingAt"), instant(ex, "meetingEndsAt"),
                        str(ex, "venue"), str(ex, "meetingMode"), str(ex, "urgentReason"), str(ex, "caseFileId")), actor);
                ex.setVariable("meetingId", mt.meetingId());
                ex.setVariable("meetingNumber", mt.meetingNumber());
            }
            case "AGENDA" -> {
                String meetingId = req(ex, "meetingId");
                for (Map<String, Object> i : maps(ex, "agendaItems")) {
                    m.meetings().addAgendaItem(c, t, meetingId, Db.str(i, "title", null), Db.str(i, "description", null),
                            Db.str(i, "linkedType", null), Db.str(i, "linkedRef", null), Db.str(i, "presenter", null), actor);
                }
            }
            case "SEND_NOTICES" -> m.meetings().sendNotices(c, t, req(ex, "meetingId"), str(ex, "noticeKind") == null ? "NOTICE"
                    : str(ex, "noticeKind"), actor);
            case "CHECK_QUORUM" -> {
                MeetingService.QuorumStatus q = m.meetings().quorum(c, t, req(ex, "meetingId"));
                ex.setVariable("quorumMet", q.met());
                ex.setVariable("quorumRequired", q.required());
                ex.setVariable("quorumPresent", q.presentVoting());
            }
            case "START" -> m.meetings().start(c, t, req(ex, "meetingId"), actor);
            case "END" -> m.meetings().end(c, t, req(ex, "meetingId"), actor);
            case "ADJOURN" -> {
                MeetingService.Meeting mt = m.meetings().adjourn(c, t, req(ex, "meetingId"), req(ex, "adjournReason"),
                        instant(ex, "adjournTo"), actor);
                ex.setVariable("adjournedMeetingId", mt.adjournedTo());
            }
            case "ISSUE_MINUTES" -> m.meetings().issueMinutes(c, t, req(ex, "meetingId"), actor);
            case "CREATE_ACTIONS" -> {
                String meetingId = str(ex, "meetingId");
                List<String> ids = new ArrayList<>();
                for (Map<String, Object> a : maps(ex, "actionItems")) {
                    ActionItemService.ActionItem item = m.actions().create(c, t, new ActionItemService.CreateRequest(meetingId,
                            Db.str(a, "decisionId", null), str(ex, "committeeId"), Db.str(a, "title", null), Db.str(a, "ownerSeat", null),
                            Db.str(a, "ownerPerson", null), Db.instant(a.get("dueAt")), Db.str(a, "period", null)), actor);
                    ids.add(item.actionId());
                }
                ex.setVariable("actionIds", String.join(",", ids));
            }
            case "CARRY_FORWARD" -> m.meetings().carryForward(c, t, req(ex, "meetingId"), req(ex, "nextMeetingId"), actor);
            case "PLACE_ATR" -> m.actions().placeAtrOnAgenda(c, t, req(ex, "meetingId"), req(ex, "nextMeetingId"), actor);
            case "PENDING_ACTIONS" -> ex.setVariable("pendingActions",
                    m.actions().list(c, t, null, str(ex, "committeeId"), null, true).size());
            default -> throw new ProcessEngineException("unknown meeting operation " + operation);
        }
    }
}
