// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.queue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Queue and token model (capability N07): service points, counters with skills, tokens, call events, statistics and the
 * display snapshot. Priority classes, skills and routing are data (DMN or service-point configuration).
 */
public final class QueueModel {

    private QueueModel() {
    }

    /** Lifecycle of a token. Terminal: SERVED, NO_SHOW, TRANSFERRED, CANCELLED (SKIPPED unless re-queued). */
    public enum TokenState {
        WAITING, CALLED, SERVING, SERVED, SKIPPED, NO_SHOW, TRANSFERRED, CANCELLED;

        public boolean active() {
            return this == WAITING || this == CALLED || this == SERVING;
        }
    }

    /** How the token was issued. */
    public enum Channel { WALK_IN, KIOSK, SMS, QR, APPOINTMENT, TRANSFER, COUNTER }

    /** Kinds of {@code RES_CALL_EVENT} rows and feed events. */
    public enum CallKind {
        ISSUE, CALL, RECALL, SKIP, SERVE, COMPLETE, TRANSFER, CANCEL, NO_SHOW, REQUEUE,
        COUNTER_OPEN, COUNTER_PAUSE, COUNTER_CLOSE
    }

    public enum CounterStatus { OPEN, PAUSED, CLOSED }

    /**
     * A service point (a hall, a desk group, a department queue).
     *
     * @param config free configuration: {@code zone}, {@code maxRecalls}, {@code numberWidth}, {@code defaultSkill},
     *               {@code priorityLevels} (class → level, lower is served first), {@code skillRouting}
     *               (routing key → skill), {@code seriesBySkill}, {@code slaMinutesByClass}, {@code appointmentLevel},
     *               {@code keepQueuedAtOnTransfer}, {@code requeueOnSkip}, {@code noShowAfterMinutes},
     *               {@code notifyOnCall}
     */
    public record ServicePoint(String tenantId, String servicePointId, String name, String resourceId, String calendarId,
                               String tokenPrefix, int slaMinutes, String priorityDecision, String routingDecision,
                               Map<String, Object> config, String status, Instant updatedAt) {
    }

    public record Counter(String tenantId, String counterId, String servicePointId, String name, CounterStatus status,
                          String operatorId, String currentTokenId, Set<String> skills, long rowVersion) {
    }

    public record Token(String tenantId, String tokenId, String servicePointId, String dayKey, int tokenNo,
                        String displayNo, Channel channel, String bookingId, String partyId, String priorityClass,
                        int priorityLevel, String skill, TokenState state, String counterId, String transferredFrom,
                        int recallCount, Instant issuedAt, Instant queuedAt, Instant calledAt, Instant serveStartedAt,
                        Instant servedAt, Instant slaDueAt, String outcome, Map<String, Object> attributes,
                        long rowVersion) {
    }

    /** Request to issue a token. Either a walk-in (skill / priority class / routing key) or a booking check-in. */
    public record IssueRequest(String servicePointId, Channel channel, String bookingId, String partyId,
                               String priorityClass, String skill, String routingKey, Map<String, Object> attributes) {
    }

    public record CallEvent(long eventId, String servicePointId, String tokenId, String counterId, CallKind kind,
                            String actorId, String detail, Instant at) {
    }

    public record CounterStats(String counterId, int served, double avgServeSeconds, double avgWaitSeconds) {
    }

    public record QueueStats(String servicePointId, Instant from, Instant to, int issued, int served, int noShow,
                             int cancelled, int waiting, double avgWaitSeconds, long maxWaitSeconds,
                             double avgServeSeconds, int slaBreaches, List<CounterStats> perCounter) {
    }

    public record CalledEntry(String counterId, String counterName, String tokenId, String displayNo, TokenState state,
                              Instant calledAt) {
    }

    public record WaitingEntry(String tokenId, String displayNo, String priorityClass, String skill, Instant queuedAt) {
    }

    public record DisplaySnapshot(String servicePointId, Instant at, List<CalledEntry> called, List<WaitingEntry> next,
                                  int waitingCount) {
    }
}
