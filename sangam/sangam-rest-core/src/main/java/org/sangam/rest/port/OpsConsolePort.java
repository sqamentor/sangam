// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;

/**
 * Operations console extras (O01–O09, R63): dashboard, job summary and stack traces, inbox (R38), workers and topics
 * (R26, VI.3), incident and reconciliation work handling (claim, note, lookup, evidence: OJ-01/OJ-02), history TTL
 * (R29) and notification failures (R60).
 */
public interface OpsConsolePort {

    /** status: UP | DEGRADED | DOWN. */
    record NodeStatus(String id, String status, boolean jobExecutorActive, Instant lastHeartbeat) {
    }

    record TopicBacklog(String topic, long count, long oldestAgeSec) {
    }

    record OutboxUsage(long used, long budget, Long drainTimeSec) {
    }

    record AgeBucket(String bucket, long count) {
    }

    record ClockItem(String id, String title, Instant deadline) {
    }

    record Metric(String name, double value, String unit) {
    }

    record Dashboard(List<NodeStatus> nodes, List<TopicBacklog> backlogByTopic, OutboxUsage outbox, long openIncidents,
                     List<AgeBucket> openReconciliationByAge, long slaBreaches, Double notificationFailureRate,
                     List<ClockItem> securityIncidentClocks, List<Metric> metrics) {
    }

    record PartitionCount(int partition, String owner, long count) {
    }

    record JobSummary(long executable, long failed, long timersDue, long locked, List<PartitionCount> byPartition) {
    }

    /** status: RECEIVED | BUFFERED | CORRELATED | EXPIRED | DUPLICATE. */
    record InboxEntry(String id, String source, String messageId, String status, Instant receivedAt,
                      String correlationKey) {
    }

    record WorkerInfo(String workerId, String sdk, String sdkVersion, String language, List<String> topics,
                      long activeLeases, long heartbeatAgeSec, Double errorRate, Long staleLeaseCount) {
    }

    /** capabilityClass: a | b | c (R36). */
    record TopicInfo(String name, String capabilityClass, boolean paused, long backlog, Long lockDurationMs) {
    }

    record ReasonRequest(String reason) {
    }

    record NoteRequest(String note, String reason) {
    }

    record LookupResult(String result, Instant at) {
    }

    record Evidence(String id, String kind, String name, String addedBy, Instant addedAt, String sha256) {
    }

    record TtlRequest(int ttlDays, String reason) {
    }

    record NotificationFailure(String id, Instant at, String channel, String reason, String templateId,
                               String businessKey, String recipientMasked) {
    }

    Dashboard dashboard(RequestContext ctx);

    JobSummary jobSummary(RequestContext ctx);

    String jobStacktrace(RequestContext ctx, String jobId);

    Page<InboxEntry> inbox(RequestContext ctx, String status, Page.PageRequest page);

    List<WorkerInfo> workers(RequestContext ctx);

    List<TopicInfo> topics(RequestContext ctx);

    TopicInfo setTopicPaused(RequestContext ctx, String topic, boolean paused, String reason);

    /** action: retry | skip | cancel. */
    InstanceControlPort.ConsequencePreview incidentPreview(RequestContext ctx, String incidentId, String action);

    OpsPort.Incident claimIncident(RequestContext ctx, String incidentId);

    OpsPort.Incident noteIncident(RequestContext ctx, String incidentId, NoteRequest request);

    OpsPort.ReconciliationItem claimReconciliation(RequestContext ctx, String itemId);

    /** Capability class (b): queries the downstream system by operationId (A-2). */
    LookupResult lookupReconciliation(RequestContext ctx, String itemId);

    Evidence addReconciliationEvidence(RequestContext ctx, String itemId, String kind, String fileName,
                                       String contentType, byte[] content);

    OpsPort.CleanupStatus setHistoryTtl(RequestContext ctx, String definitionKey, TtlRequest request);

    Page<NotificationFailure> notificationFailures(RequestContext ctx, Page.PageRequest page);

    void resendNotification(RequestContext ctx, String failureId, String reason);
}
