// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.rest;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.queue.QueueModel;
import org.sangam.resources.support.VisibleQuery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST port of queue/token management (N07) for the REST workstream. No Spring types: the controller maps HTTP to these
 * calls; every list, count and get-by-id reads through the VI.4 visibility predicate (a hidden row is
 * reported as NOT_FOUND). The implementation ({@link QueueRestAdapter}) runs each call in one transaction. Errors are
 * {@code org.sangam.resources.support.ResourcesException} (code → RFC 9457 status via {@code httpStatus()}).
 *
 * <h2>Intended HTTP endpoints (base {@code /api/v1/resources})</h2>
 * <pre>
 * PUT    /service-points/{id}                      defineServicePoint   (ServicePointRequest)
 * GET    /service-points?page=&amp;size=             servicePoints
 * GET    /service-points/{id}                      servicePoint
 * POST   /service-points/{id}/status               setServicePointStatus {status}
 * PUT    /service-points/{id}/counters/{counterId} defineCounter        (CounterRequest)
 * GET    /service-points/{id}/counters             counters
 * POST   /counters/{id}/open                       openCounter          {operatorId}
 * POST   /counters/{id}/pause                      pauseCounter         {reason}
 * POST   /counters/{id}/close                      closeCounter
 * POST   /service-points/{id}/tokens               issueToken           (IssueTokenRequest)   Idempotency-Key
 * GET    /service-points/{id}/tokens?state=&amp;page=&amp;size=   tokens
 * GET    /service-points/{id}/tokens/count?state=&amp;cap=  countTokens (capped)
 * GET    /tokens/{id}                              token
 * GET    /tokens/{id}/position                     position
 * POST   /tokens/{id}/transfer                     transfer             (TransferRequest)
 * POST   /tokens/{id}/cancel                       cancel               {reason}
 * POST   /counters/{id}/call-next                  callNext             (204 when nobody waits)
 * POST   /counters/{id}/call/{tokenId}             callToken            {reason}
 * POST   /counters/{id}/recall                     recall
 * POST   /counters/{id}/skip                       skip                 {reason}
 * POST   /counters/{id}/serve                      startServing
 * POST   /counters/{id}/complete                   complete             {outcome}
 * POST   /service-points/{id}/no-show-sweep        noShowSweep
 * GET    /service-points/{id}/sla-breaches         slaBreaches
 * GET    /service-points/{id}/stats?from=&amp;to=      stats
 * GET    /service-points/{id}/display?next=        displaySnapshot  (poll; push feed via DisplayFeedPort webhook/websocket)
 * GET    /service-points/{id}/events?after=&amp;limit=  callEvents       (feed replay for display boards)
 * </pre>
 */
public interface QueueRestPort {

    record ServicePointRequest(String name, String resourceId, String calendarId, String tokenPrefix, int slaMinutes,
                               String priorityDecision, String routingDecision, Map<String, Object> config) {
    }

    record CounterRequest(String name, List<String> skills) {
    }

    record IssueTokenRequest(String channel, String bookingId, String partyId, String priorityClass, String skill,
                             String routingKey, Map<String, Object> attributes) {
    }

    record TransferRequest(String targetServicePointId, String targetSkill, String targetCounterId, String reason) {
    }

    QueueModel.ServicePoint defineServicePoint(SecurityIdentity actor, String servicePointId, ServicePointRequest req);

    QueueModel.ServicePoint setServicePointStatus(SecurityIdentity actor, String servicePointId, String status);

    QueueModel.ServicePoint servicePoint(SecurityIdentity actor, String servicePointId);

    List<QueueModel.ServicePoint> servicePoints(SecurityIdentity actor, int page, int size);

    QueueModel.Counter defineCounter(SecurityIdentity actor, String servicePointId, String counterId, CounterRequest req);

    List<QueueModel.Counter> counters(SecurityIdentity actor, String servicePointId);

    QueueModel.Counter openCounter(SecurityIdentity actor, String counterId, String operatorId);

    QueueModel.Counter pauseCounter(SecurityIdentity actor, String counterId, String reason);

    QueueModel.Counter closeCounter(SecurityIdentity actor, String counterId);

    QueueModel.Token issueToken(SecurityIdentity actor, String servicePointId, IssueTokenRequest req);

    List<QueueModel.Token> tokens(SecurityIdentity actor, String servicePointId, String state, int page, int size);

    /** Capped count of visible tokens (A-16): header {@code X-Total-Count}, {@code X-Total-Count-Capped}. */
    VisibleQuery.CappedCount countTokens(SecurityIdentity actor, String servicePointId, String state, int cap);

    QueueModel.Token token(SecurityIdentity actor, String tokenId);

    Map<String, Object> position(SecurityIdentity actor, String tokenId);

    QueueModel.Token transfer(SecurityIdentity actor, String tokenId, TransferRequest req);

    QueueModel.Token cancel(SecurityIdentity actor, String tokenId, String reason);

    Optional<QueueModel.Token> callNext(SecurityIdentity actor, String counterId);

    QueueModel.Token callToken(SecurityIdentity actor, String counterId, String tokenId, String reason);

    QueueModel.Token recall(SecurityIdentity actor, String counterId);

    QueueModel.Token skip(SecurityIdentity actor, String counterId, String reason);

    QueueModel.Token startServing(SecurityIdentity actor, String counterId);

    QueueModel.Token complete(SecurityIdentity actor, String counterId, String outcome);

    int noShowSweep(SecurityIdentity actor, String servicePointId);

    List<QueueModel.Token> slaBreaches(SecurityIdentity actor, String servicePointId);

    QueueModel.QueueStats stats(SecurityIdentity actor, String servicePointId, Instant from, Instant to);

    QueueModel.DisplaySnapshot displaySnapshot(SecurityIdentity actor, String servicePointId, int next);

    List<QueueModel.CallEvent> callEvents(SecurityIdentity actor, String servicePointId, long afterEventId, int limit);
}
