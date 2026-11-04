// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Operations (O01–O09, R63, R64): incidents, reconciliation workbench (VI.3 rule 4, VI.5), outbox repair (VI.2.3),
 * jobs, batches (R64: count preview from the same predicate, pause/resume/cancel, per-item results, one root per
 * transaction, four-eyes above a threshold), migration (R48, FC-18 1:N/N:1 mapping), restart and history cleanup.
 * Every state-changing call requires a reason and is audited (R42).
 */
public interface OpsPort {

    record Incident(String id, String type, String message, String processInstanceId, String processDefinitionId,
                    String activityId, String jobId, String externalWorkId, String causeIncidentId,
                    String rootCauseIncidentId, Instant createdAt, String annotation, String tenantId) {
    }

    record IncidentFilter(String type, String processInstanceId, String processDefinitionKey, String activityId) {
    }

    record IncidentAction(String reason, Integer retries, String annotation) {
    }

    /** kind: UNKNOWN_OUTCOME | LATE_RESULT_AFTER_DELETE. state: OPEN | RESOLVED. */
    record ReconciliationItem(String id, String kind, String operationId, String rootId, String taskId, String state,
                              Object details, String action, String resolvedBy, Object resolution, Instant createdAt,
                              Instant resolvedAt) {
    }

    /** action: CONFIRM_SUCCESS (with result) | RETRY_SAME_OPERATION | MARK_FAILED. Evidence is mandatory. */
    record ResolveRequest(String action, String evidence, Map<String, Object> result, String reason) {
    }

    record OutboxDelivery(String outboxId, String destination, String rootId, long rootSeq, String eventType,
                          String state, int attempts, Instant nextAttemptAt, String lastError, Instant createdAt) {
    }

    /** backpressure: NONE | WARN | REFUSE_NEW (VI.2.4, ≥ 80 % refuses new starts). */
    record OutboxStats(long pending, long inFlight, long failed, long dead, long budget, double usedPercent,
                       String backpressure) {
    }

    record OutboxRepairRequest(String destination, String reason) {
    }

    record Job(String id, String type, String processInstanceId, String processDefinitionKey, String activityId,
               Instant dueDate, int retries, String exceptionMessage, boolean suspended, long priority,
               String tenantId) {
    }

    record JobFilter(String processInstanceId, String processDefinitionKey, Boolean withException, Boolean timers,
                     Boolean executable) {
    }

    record JobRetriesRequest(int retries, String reason) {
    }

    record JobSuspensionRequest(boolean suspended, String reason) {
    }

    record BatchFilter(String processDefinitionKey, String processDefinitionId, List<String> processInstanceIds,
                       List<String> businessKeys, String activityId, Boolean withIncidents) {
    }

    /** type: RETRY | CANCEL | DELETE | SUSPEND | RESUME | MIGRATE | MODIFY | SET_VARIABLES | RESTART. */
    record BatchRequest(String type, BatchFilter filter, Map<String, Object> params, String reason) {
    }

    record BatchPreview(String type, long count, boolean capped, List<String> sample, boolean fourEyesRequired) {
    }

    /** state: AWAITING_APPROVAL | RUNNING | PAUSED | COMPLETED | CANCELLED | FAILED. */
    record Batch(String id, String type, String state, long total, long succeeded, long failed, String createdBy,
                 String approvedBy, String reason, Instant createdAt, Instant completedAt, BatchFilter filter,
                 Map<String, Object> params, boolean fourEyesRequired) {
    }

    /** One item, key {@code <idempotencyKey>:<itemIndex>} (X.2.2.7 rule 3). state: PENDING | SUCCEEDED | FAILED. */
    record BatchItem(int index, String rootId, String state, String error, String itemKey, Instant finishedAt) {
    }

    /** action: PAUSE | RESUME | CANCEL | APPROVE. */
    record BatchControlRequest(String action, String reason) {
    }

    record MigrationInstruction(List<String> sourceActivityIds, List<String> targetActivityIds,
                                boolean updateEventTrigger) {
    }

    record MigrationPlanRequest(String sourceDefinitionId, String targetDefinitionId, boolean mapEqualActivities,
                                List<MigrationInstruction> instructions, Map<String, Object> formMappings,
                                Map<String, Object> variables) {
    }

    record MigrationPlan(String sourceDefinitionId, String targetDefinitionId, List<MigrationInstruction> instructions,
                         boolean valid, List<String> validationErrors, long affectedInstances) {
    }

    record MigrationExecuteRequest(MigrationPlanRequest plan, List<String> processInstanceIds, BatchFilter filter,
                                   boolean skipCustomListeners, boolean skipIoMappings, String reason) {
    }

    record RestartRequest(String processDefinitionId, List<String> processInstanceIds,
                          List<String> startBeforeActivityIds, boolean initialVariables, boolean skipCustomListeners,
                          String reason) {
    }

    record CleanupStatus(String jobId, Instant dueDate, Integer retries, String exceptionMessage,
                         Map<String, Long> removable) {
    }

    Page<Incident> incidents(RequestContext ctx, IncidentFilter filter, Page.PageRequest page);

    Incident incident(RequestContext ctx, String id);

    Incident retryIncident(RequestContext ctx, String id, IncidentAction action);

    Incident resolveIncident(RequestContext ctx, String id, IncidentAction action);

    Page<ReconciliationItem> reconciliation(RequestContext ctx, String state, String kind, Page.PageRequest page);

    ReconciliationItem reconciliationItem(RequestContext ctx, String id);

    ReconciliationItem resolveReconciliation(RequestContext ctx, String id, ResolveRequest request);

    Page<OutboxDelivery> outbox(RequestContext ctx, String state, String destination, Page.PageRequest page);

    OutboxStats outboxStats(RequestContext ctx);

    OutboxDelivery requeueOutbox(RequestContext ctx, String outboxId, OutboxRepairRequest request);

    OutboxDelivery skipOutbox(RequestContext ctx, String outboxId, OutboxRepairRequest request);

    Page<Job> jobs(RequestContext ctx, JobFilter filter, Page.PageRequest page);

    Job setJobRetries(RequestContext ctx, String jobId, JobRetriesRequest request);

    Job executeJob(RequestContext ctx, String jobId, String reason);

    Job setJobSuspension(RequestContext ctx, String jobId, JobSuspensionRequest request);

    BatchPreview previewBatch(RequestContext ctx, BatchRequest request);

    Batch createBatch(RequestContext ctx, BatchRequest request);

    Page<Batch> batches(RequestContext ctx, String state, Page.PageRequest page);

    Batch batch(RequestContext ctx, String id);

    Page<BatchItem> batchItems(RequestContext ctx, String id, String state, Page.PageRequest page);

    Batch controlBatch(RequestContext ctx, String id, BatchControlRequest request);

    MigrationPlan planMigration(RequestContext ctx, MigrationPlanRequest request);

    Batch executeMigration(RequestContext ctx, MigrationExecuteRequest request);

    Batch restart(RequestContext ctx, RestartRequest request);

    CleanupStatus cleanupStatus(RequestContext ctx);

    CleanupStatus triggerCleanup(RequestContext ctx, String reason);
}
