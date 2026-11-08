// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.rest;

import org.sangam.casefile.meeting.ActionItemService;
import org.sangam.casefile.meeting.CommitteeService;
import org.sangam.casefile.meeting.MeetingService;
import org.sangam.casefile.rest.CaseApi.ApiContext;

import java.time.Instant;
import java.util.List;

/** REST port of committees, meetings and action items (N16). Routes are relative to {@code /api/v1}. */
public interface MeetingRestPort {

    record CommitteeView(CommitteeService.Committee committee, List<CommitteeService.Member> members, int quorumRequired) {
    }

    record MeetingView(MeetingService.Meeting meeting, List<MeetingService.AgendaItem> agenda, List<MeetingService.Attendance> attendance,
                       MeetingService.QuorumStatus quorum, List<MeetingService.Decision> decisions, MeetingService.Minutes minutes,
                       List<ActionItemService.ActionItem> actions) {
    }

    record ReconstituteRequest(String constitutionRef, List<CommitteeService.MemberInput> members) {
    }

    record RescheduleRequest(Instant scheduledAt, Instant endsAt, String venue, String reason) {
    }

    record AgendaRequest(String title, String description, String linkedType, String linkedRef, String presenter) {
    }

    record AttendanceRequest(String memberId, String attendeeRef, String attendance, String proxyFor) {
    }

    record AdjournRequest(String reason, Instant newAt) {
    }

    record ApproveMinutesRequest(int version, String contentHash, String esignRef) {
    }

    record VerifyRequest(boolean accept, String reason) {
    }

    // committees         POST /committees, GET /committees?kind, GET /committees/{id}, POST /committees/{id}/members,
    //                    DELETE /committees/{id}/members/{memberId}, POST /committees/{id}/reconstitute|dissolve
    CommitteeService.Committee constitute(ApiContext ctx, CommitteeService.ConstituteRequest request);

    List<CommitteeService.Committee> committees(ApiContext ctx, String kind, boolean activeOnly);

    CommitteeView committee(ApiContext ctx, String committeeId);

    CommitteeService.Member addMember(ApiContext ctx, String committeeId, CommitteeService.MemberInput member);

    void removeMember(ApiContext ctx, String committeeId, String memberId, String reason);

    CommitteeService.Committee reconstitute(ApiContext ctx, String committeeId, ReconstituteRequest request);

    CommitteeService.Committee dissolve(ApiContext ctx, String committeeId, String reason);

    // meetings           POST /meetings, GET /meetings?committeeId&from&to, GET /meetings/{id},
    //                    POST /meetings/{id}/reschedule|cancel|start|adjourn|end
    MeetingService.Meeting schedule(ApiContext ctx, MeetingService.ScheduleRequest request);

    List<MeetingService.Meeting> meetings(ApiContext ctx, String committeeId, Instant from, Instant to);

    MeetingView meeting(ApiContext ctx, String meetingId);

    MeetingService.Meeting reschedule(ApiContext ctx, String meetingId, RescheduleRequest request);

    MeetingService.Meeting cancel(ApiContext ctx, String meetingId, String reason);

    MeetingService.Meeting start(ApiContext ctx, String meetingId);

    MeetingService.Meeting adjourn(ApiContext ctx, String meetingId, AdjournRequest request);

    MeetingService.Meeting end(ApiContext ctx, String meetingId);

    // agenda             POST /meetings/{id}/agenda, DELETE /meetings/{id}/agenda/{itemId}, PUT /meetings/{id}/agenda/order,
    //                    POST /meetings/{id}/carry-forward?to
    MeetingService.AgendaItem addAgendaItem(ApiContext ctx, String meetingId, AgendaRequest request);

    void withdrawAgendaItem(ApiContext ctx, String meetingId, String itemId, String reason);

    void reorderAgenda(ApiContext ctx, String meetingId, List<String> itemIds);

    int carryForward(ApiContext ctx, String fromMeetingId, String toMeetingId);

    // notices, attendance POST /meetings/{id}/notices?kind, POST /notices/{id}/ack, PUT /meetings/{id}/attendance,
    //                    GET /meetings/{id}/quorum
    List<MeetingService.Notice> sendNotices(ApiContext ctx, String meetingId, String kind);

    void acknowledgeNotice(ApiContext ctx, String noticeId);

    MeetingService.Attendance markAttendance(ApiContext ctx, String meetingId, AttendanceRequest request);

    MeetingService.QuorumStatus quorum(ApiContext ctx, String meetingId);

    // decisions, minutes POST /meetings/{id}/decisions, POST /meetings/{id}/minutes (draft), POST /meetings/{id}/minutes/circulate,
    //                    POST /meetings/{id}/minutes/comments, POST /meetings/{id}/minutes/approve, POST /meetings/{id}/minutes/issue
    MeetingService.Decision recordDecision(ApiContext ctx, String meetingId, MeetingService.DecisionInput input);

    MeetingService.Minutes draftMinutes(ApiContext ctx, String meetingId, String body);

    MeetingService.Minutes circulateMinutes(ApiContext ctx, String meetingId);

    MeetingService.Minutes commentMinutes(ApiContext ctx, String meetingId, String text);

    MeetingService.Minutes approveMinutes(ApiContext ctx, String meetingId, ApproveMinutesRequest request);

    MeetingService.Minutes issueMinutes(ApiContext ctx, String meetingId);

    // action items       POST /action-items, GET /action-items?meetingId&committeeId&ownerSeat&open,
    //                    POST /action-items/{id}/start|atr|verify|drop|reassign, GET /committees/{id}/pendency,
    //                    GET /meetings/{id}/atr, POST /meetings/{id}/atr/place?next
    ActionItemService.ActionItem createAction(ApiContext ctx, ActionItemService.CreateRequest request);

    List<ActionItemService.ActionItem> actions(ApiContext ctx, String meetingId, String committeeId, String ownerSeat, boolean openOnly);

    ActionItemService.ActionItem startAction(ApiContext ctx, String actionId);

    ActionItemService.ActionItem submitAtr(ApiContext ctx, String actionId, String atrText);

    ActionItemService.ActionItem verifyAtr(ApiContext ctx, String actionId, VerifyRequest request);

    ActionItemService.ActionItem dropAction(ApiContext ctx, String actionId, String reason);

    ActionItemService.ActionItem reassignAction(ApiContext ctx, String actionId, String ownerSeat, String ownerPerson);

    List<ActionItemService.Pendency> pendency(ApiContext ctx, String committeeId);

    List<ActionItemService.ActionItem> atrReport(ApiContext ctx, String meetingId);

    MeetingService.AgendaItem placeAtrOnAgenda(ApiContext ctx, String previousMeetingId, String nextMeetingId);
}
