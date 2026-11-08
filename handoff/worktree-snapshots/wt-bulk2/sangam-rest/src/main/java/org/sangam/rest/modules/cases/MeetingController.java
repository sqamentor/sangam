// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.cases;

import java.time.Instant;
import java.util.List;

import org.sangam.casefile.meeting.ActionItemService;
import org.sangam.casefile.meeting.CommitteeService;
import org.sangam.casefile.meeting.MeetingService;
import org.sangam.casefile.rest.MeetingRestPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Committees, meetings, agenda, notices, attendance, decisions, minutes and action items (N16). Routes follow the
 * sangam-case README, except {@code /notices/{id}/ack} → {@code /meeting-notices/{id}/ack}.
 */
@RestController
@RequestMapping("/api/v1")
public class MeetingController {

    private final MeetingRestPort port;
    private final PermissionPort permissions;

    public MeetingController(MeetingRestPort port, PermissionPort permissions) {
        this.port = port;
        this.permissions = permissions;
    }

    public record ReasonBody(String reason) {
    }

    public record AgendaOrder(List<String> itemIds) {
    }

    public record TextBody(String text) {
    }

    public record CarryResult(int carried) {
    }

    public record ReassignBody(String ownerSeat, String ownerPerson) {
    }

    public record AtrBody(String atrText) {
    }

    /** Constitute a committee with its members. */
    @PostMapping("/committees")
    public ResponseEntity<CommitteeService.Committee> constitute(RequestContext ctx,
                                                                @RequestBody CommitteeService.ConstituteRequest body) {
        CommitteeService.Committee c = port.constitute(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), body);
        return Lists.created("/api/v1/committees/" + c.committeeId(), c.committeeId(), c);
    }

    /** Committees, optionally of one kind. */
    @GetMapping("/committees")
    public ResponseEntity<List<CommitteeService.Committee>> committees(RequestContext ctx,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false, defaultValue = "true") boolean activeOnly) {
        return Lists.ok(port.committees(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), kind, activeOnly));
    }

    /** A committee with its members and quorum. */
    @GetMapping("/committees/{id}")
    public MeetingRestPort.CommitteeView committee(RequestContext ctx, @PathVariable String id) {
        return port.committee(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id);
    }

    /** Add a member. */
    @PostMapping("/committees/{id}/members")
    public CommitteeService.Member addMember(RequestContext ctx, @PathVariable String id,
                                             @RequestBody CommitteeService.MemberInput body) {
        return port.addMember(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Remove a member with a reason. */
    @DeleteMapping("/committees/{id}/members/{memberId}")
    public ResponseEntity<Void> removeMember(RequestContext ctx, @PathVariable String id, @PathVariable String memberId,
                                             @RequestParam(required = false) String reason) {
        port.removeMember(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, memberId,
                CaseIdentity.requireReason(reason));
        return ResponseEntity.noContent().build();
    }

    /** Reconstitute a committee under a new constitution order. */
    @PostMapping("/committees/{id}/reconstitute")
    public CommitteeService.Committee reconstitute(RequestContext ctx, @PathVariable String id,
                                                   @RequestBody MeetingRestPort.ReconstituteRequest body) {
        return port.reconstitute(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Dissolve a committee. */
    @PostMapping("/committees/{id}/dissolve")
    public CommitteeService.Committee dissolve(RequestContext ctx, @PathVariable String id, @RequestBody ReasonBody body) {
        return port.dissolve(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id,
                CaseIdentity.requireReason(body.reason()));
    }

    /** Pendency of a committee's action items. */
    @GetMapping("/committees/{id}/pendency")
    public ResponseEntity<List<ActionItemService.Pendency>> pendency(RequestContext ctx, @PathVariable String id) {
        return Lists.ok(port.pendency(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id));
    }

    /** Schedule a meeting (minimum notice per committee kind; urgent meetings need a reason). */
    @PostMapping("/meetings")
    public ResponseEntity<MeetingService.Meeting> schedule(RequestContext ctx, @RequestBody MeetingService.ScheduleRequest body) {
        MeetingService.Meeting m = port.schedule(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), body);
        return Lists.created("/api/v1/meetings/" + m.meetingId(), m.meetingId(), m);
    }

    /** Meetings of a committee in a period. */
    @GetMapping("/meetings")
    public ResponseEntity<List<MeetingService.Meeting>> meetings(RequestContext ctx,
            @RequestParam(required = false) String committeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return Lists.ok(port.meetings(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), committeeId, from, to));
    }

    /** A meeting with agenda, attendance, quorum, decisions, minutes and actions. */
    @GetMapping("/meetings/{id}")
    public MeetingRestPort.MeetingView meeting(RequestContext ctx, @PathVariable String id) {
        return port.meeting(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id);
    }

    /** Reschedule a meeting. */
    @PostMapping("/meetings/{id}/reschedule")
    public MeetingService.Meeting reschedule(RequestContext ctx, @PathVariable String id,
                                             @RequestBody MeetingRestPort.RescheduleRequest body) {
        CaseIdentity.requireReason(body.reason());
        return port.reschedule(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Cancel a meeting. */
    @PostMapping("/meetings/{id}/cancel")
    public MeetingService.Meeting cancel(RequestContext ctx, @PathVariable String id, @RequestBody ReasonBody body) {
        return port.cancel(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id,
                CaseIdentity.requireReason(body.reason()));
    }

    /** Start a meeting (quorum checked). */
    @PostMapping("/meetings/{id}/start")
    public MeetingService.Meeting start(RequestContext ctx, @PathVariable String id) {
        return port.start(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id);
    }

    /** Adjourn a meeting to a new date. */
    @PostMapping("/meetings/{id}/adjourn")
    public MeetingService.Meeting adjourn(RequestContext ctx, @PathVariable String id,
                                          @RequestBody MeetingRestPort.AdjournRequest body) {
        CaseIdentity.requireReason(body.reason());
        return port.adjourn(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** End a meeting. */
    @PostMapping("/meetings/{id}/end")
    public MeetingService.Meeting end(RequestContext ctx, @PathVariable String id) {
        return port.end(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id);
    }

    /** Add an agenda item. */
    @PostMapping("/meetings/{id}/agenda")
    public MeetingService.AgendaItem addAgendaItem(RequestContext ctx, @PathVariable String id,
                                                   @RequestBody MeetingRestPort.AgendaRequest body) {
        return port.addAgendaItem(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Withdraw an agenda item with a reason. */
    @DeleteMapping("/meetings/{id}/agenda/{itemId}")
    public ResponseEntity<Void> withdrawAgendaItem(RequestContext ctx, @PathVariable String id, @PathVariable String itemId,
                                                   @RequestParam(required = false) String reason) {
        port.withdrawAgendaItem(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, itemId,
                CaseIdentity.requireReason(reason));
        return ResponseEntity.noContent().build();
    }

    /** Reorder the agenda. */
    @PutMapping("/meetings/{id}/agenda/order")
    public ResponseEntity<Void> reorderAgenda(RequestContext ctx, @PathVariable String id, @RequestBody AgendaOrder body) {
        port.reorderAgenda(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body.itemIds());
        return ResponseEntity.noContent().build();
    }

    /** Carry forward the open agenda items to another meeting. */
    @PostMapping("/meetings/{id}/carry-forward")
    public CarryResult carryForward(RequestContext ctx, @PathVariable String id, @RequestParam String to) {
        return new CarryResult(port.carryForward(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, to));
    }

    /** Send meeting notices (outbox, exactly once). */
    @PostMapping("/meetings/{id}/notices")
    public ResponseEntity<List<MeetingService.Notice>> sendNotices(RequestContext ctx, @PathVariable String id,
                                                                   @RequestParam(required = false) String kind) {
        return Lists.ok(port.sendNotices(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, kind));
    }

    /** Acknowledge a meeting notice. */
    @PostMapping("/meeting-notices/{id}/ack")
    public ResponseEntity<Void> acknowledgeNotice(RequestContext ctx, @PathVariable String id) {
        port.acknowledgeNotice(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id);
        return ResponseEntity.noContent().build();
    }

    /** Mark attendance (in person, virtual, proxy, absent). */
    @PutMapping("/meetings/{id}/attendance")
    public MeetingService.Attendance markAttendance(RequestContext ctx, @PathVariable String id,
                                                    @RequestBody MeetingRestPort.AttendanceRequest body) {
        return port.markAttendance(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Current quorum status. */
    @GetMapping("/meetings/{id}/quorum")
    public MeetingService.QuorumStatus quorum(RequestContext ctx, @PathVariable String id) {
        return port.quorum(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id);
    }

    /** Record a decision on an agenda item (votes and dissent). */
    @PostMapping("/meetings/{id}/decisions")
    public MeetingService.Decision recordDecision(RequestContext ctx, @PathVariable String id,
                                                  @RequestBody MeetingService.DecisionInput body) {
        return port.recordDecision(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Draft the minutes. */
    @PostMapping("/meetings/{id}/minutes")
    public MeetingService.Minutes draftMinutes(RequestContext ctx, @PathVariable String id, @RequestBody TextBody body) {
        return port.draftMinutes(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body.text());
    }

    /** Circulate the draft minutes to members. */
    @PostMapping("/meetings/{id}/minutes/circulate")
    public MeetingService.Minutes circulateMinutes(RequestContext ctx, @PathVariable String id) {
        return port.circulateMinutes(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id);
    }

    /** Comment on the circulated minutes. */
    @PostMapping("/meetings/{id}/minutes/comments")
    public MeetingService.Minutes commentMinutes(RequestContext ctx, @PathVariable String id, @RequestBody TextBody body) {
        return port.commentMinutes(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id, body.text());
    }

    /** Approve the minutes (bound to the content hash; optional eSign reference). */
    @PostMapping("/meetings/{id}/minutes/approve")
    public MeetingService.Minutes approveMinutes(RequestContext ctx, @PathVariable String id,
                                                 @RequestBody MeetingRestPort.ApproveMinutesRequest body) {
        return port.approveMinutes(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Issue the approved minutes. */
    @PostMapping("/meetings/{id}/minutes/issue")
    public MeetingService.Minutes issueMinutes(RequestContext ctx, @PathVariable String id) {
        return port.issueMinutes(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id);
    }

    /** Action-taken report of a meeting. */
    @GetMapping("/meetings/{id}/atr")
    public ResponseEntity<List<ActionItemService.ActionItem>> atrReport(RequestContext ctx, @PathVariable String id) {
        return Lists.ok(port.atrReport(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), id));
    }

    /** Place the action-taken report on the next meeting's agenda. */
    @PostMapping("/meetings/{id}/atr/place")
    public MeetingService.AgendaItem placeAtr(RequestContext ctx, @PathVariable String id, @RequestParam String next) {
        return port.placeAtrOnAgenda(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, next);
    }

    /** Create an action item from a decision. */
    @PostMapping("/action-items")
    public ResponseEntity<ActionItemService.ActionItem> createAction(RequestContext ctx,
                                                                     @RequestBody ActionItemService.CreateRequest body) {
        ActionItemService.ActionItem a = port.createAction(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), body);
        return Lists.created("/api/v1/action-items/" + a.actionId(), a.actionId(), a);
    }

    /** Action items by meeting, committee or owner seat. */
    @GetMapping("/action-items")
    public ResponseEntity<List<ActionItemService.ActionItem>> actions(RequestContext ctx,
            @RequestParam(required = false) String meetingId, @RequestParam(required = false) String committeeId,
            @RequestParam(required = false) String ownerSeat,
            @RequestParam(required = false, defaultValue = "false") boolean open) {
        return Lists.ok(port.actions(CaseIdentity.require(permissions, ctx, CaseIdentity.READ), meetingId, committeeId,
                ownerSeat, open));
    }

    /** Start work on an action item. */
    @PostMapping("/action-items/{id}/start")
    public ActionItemService.ActionItem startAction(RequestContext ctx, @PathVariable String id) {
        return port.startAction(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id);
    }

    /** Submit the action-taken report. */
    @PostMapping("/action-items/{id}/atr")
    public ActionItemService.ActionItem submitAtr(RequestContext ctx, @PathVariable String id, @RequestBody AtrBody body) {
        return port.submitAtr(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body.atrText());
    }

    /** Verify (accept or return) the action-taken report. */
    @PostMapping("/action-items/{id}/verify")
    public ActionItemService.ActionItem verifyAtr(RequestContext ctx, @PathVariable String id,
                                                  @RequestBody MeetingRestPort.VerifyRequest body) {
        return port.verifyAtr(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body);
    }

    /** Drop an action item with a reason. */
    @PostMapping("/action-items/{id}/drop")
    public ActionItemService.ActionItem dropAction(RequestContext ctx, @PathVariable String id, @RequestBody ReasonBody body) {
        return port.dropAction(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id,
                CaseIdentity.requireReason(body.reason()));
    }

    /** Reassign an action item. */
    @PostMapping("/action-items/{id}/reassign")
    public ActionItemService.ActionItem reassignAction(RequestContext ctx, @PathVariable String id,
                                                       @RequestBody ReassignBody body) {
        return port.reassignAction(CaseIdentity.require(permissions, ctx, CaseIdentity.WRITE), id, body.ownerSeat(),
                body.ownerPerson());
    }
}
