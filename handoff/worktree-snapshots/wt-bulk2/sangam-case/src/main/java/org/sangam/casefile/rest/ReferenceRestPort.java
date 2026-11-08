// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.rest;

import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.deadline.DeadlineService;
import org.sangam.casefile.reference.ReferenceService;
import org.sangam.casefile.rest.CaseApi.ApiContext;
import org.sangam.ports.support.Paging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** REST port of references with a deadline (N16). Routes are relative to {@code /api/v1}. */
public interface ReferenceRestPort {

    record ReferenceView(ReferenceService.Reference reference, List<ReferenceService.Move> moves,
                         List<ReferenceService.Reference> followUps, List<DeadlineService.Deadline> deadlines,
                         List<CaseEvents.Event> history) {
    }

    record AssignRequest(String seat, String person, String remarks) {
    }

    record TransferRequest(String toOrgUnit, String toSeat, String remarks) {
    }

    record ReplyRequest(String text, String replyRef) {
    }

    record ExtendRequest(Instant newDueAt, String reason) {
    }

    record FollowUpRequest(String subject, Instant fixedDueAt, Map<String, Object> attributes) {
    }

    // POST /references, GET /references?kind&status&seat&orgUnit&sourceCategory&overdue&q, GET /references/{id}
    ReferenceService.Reference register(ApiContext ctx, ReferenceService.RegisterRequest request);

    Paging.PageResult<ReferenceService.Reference> references(ApiContext ctx, ReferenceService.Filter filter, Paging.PageRequest page);

    ReferenceView reference(ApiContext ctx, String referenceId);

    // POST /references/{id}/assign|transfer|start|interim-reply|draft-reply|approve-reply|reply|extend|follow-ups|close
    ReferenceService.Reference assign(ApiContext ctx, String referenceId, AssignRequest request);

    ReferenceService.Reference transfer(ApiContext ctx, String referenceId, TransferRequest request);

    ReferenceService.Reference startWork(ApiContext ctx, String referenceId);

    ReferenceService.Reference interimReply(ApiContext ctx, String referenceId, ReplyRequest request);

    ReferenceService.Reference draftReply(ApiContext ctx, String referenceId, String text);

    ReferenceService.Reference approveReply(ApiContext ctx, String referenceId, String draftHash);

    ReferenceService.Reference reply(ApiContext ctx, String referenceId, ReplyRequest request);

    ReferenceService.Reference extend(ApiContext ctx, String referenceId, ExtendRequest request);

    ReferenceService.Reference followUp(ApiContext ctx, String referenceId, FollowUpRequest request);

    ReferenceService.Reference close(ApiContext ctx, String referenceId, String reason);

    // GET /references/pendency?kind&dueSoon=P7D
    List<ReferenceService.Pendency> pendency(ApiContext ctx, String kind, Duration dueSoonWindow);
}
