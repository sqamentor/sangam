// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.Map;

/**
 * Class-A operational history queries (R63 history views, PF-01; Flowable history-query parity). Not evidence
 * (rule D-0): evidence is the audit chain ({@link AuditPort}). Read permission {@code READ_HISTORY}.
 */
public interface HistoryPort {

    /**
     * @param activityInstanceId only rows of this activity instance (activity instances, tasks, variables, decisions,
     *                           external-task log); {@code 400 FILTER_UNSUPPORTED} on the other lists
     * @param state              activity-instance lists only: {@link #RUNNING}, {@link #COMPLETED} or
     *                           {@link #CANCELED} (case-insensitive); {@code 400} on the other lists or for another value
     */
    record HistoryFilter(String processDefinitionKey, String processInstanceId, String businessKey,
                         String caseInstanceId, String activityId, String taskDefinitionKey, String assignee,
                         Boolean finished, Instant from, Instant to, String activityInstanceId, String state) {

        public HistoryFilter(String processDefinitionKey, String processInstanceId, String businessKey,
                             String caseInstanceId, String activityId, String taskDefinitionKey, String assignee,
                             Boolean finished, Instant from, Instant to) {
            this(processDefinitionKey, processInstanceId, businessKey, caseInstanceId, activityId, taskDefinitionKey,
                    assignee, finished, from, to, null, null);
        }

        /** The activity-instance state filter, normalised; {@code 400 VALIDATION_FAILED} for an unknown value. */
        public String activityState() {
            if (state == null || state.isBlank()) {
                return null;
            }
            String s = state.trim().toLowerCase(java.util.Locale.ROOT);
            if (!STATES.contains(s)) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", "state must be one of " + STATES);
            }
            return s;
        }

        /**
         * Refuses the filters a list cannot apply ({@code 400 FILTER_UNSUPPORTED}) instead of silently ignoring them,
         * so a caller never reads an unfiltered list as a filtered one.
         */
        public HistoryFilter requireOnly(String list, boolean activityInstanceIdSupported, boolean stateSupported) {
            if (!activityInstanceIdSupported && activityInstanceId != null && !activityInstanceId.isBlank()) {
                throw ApiProblem.badRequest("FILTER_UNSUPPORTED", "activityInstanceId is not a filter of " + list);
            }
            if (!stateSupported && state != null && !state.isBlank()) {
                throw ApiProblem.badRequest("FILTER_UNSUPPORTED", "state is not a filter of " + list);
            }
            return this;
        }
    }

    /** Activity-instance states of the {@code state} filter (P14 tree quick filter). */
    String RUNNING = "running";
    String COMPLETED = "completed";
    String CANCELED = "canceled";
    java.util.List<String> STATES = java.util.List.of(RUNNING, COMPLETED, CANCELED);

    record HistoricProcessInstance(String id, String processDefinitionId, String processDefinitionKey,
                                   String businessKey, String state, Instant startTime, Instant endTime,
                                   Long durationMs, String startUserId, String deleteReason, String tenantId,
                                   String superProcessInstanceId) {
    }

    record HistoricActivityInstance(String id, String activityId, String activityName, String activityType,
                                    String processInstanceId, String executionId, String taskId, String assignee,
                                    Instant startTime, Instant endTime, Long durationMs, boolean canceled) {
    }

    record HistoricTask(String id, String name, String taskDefinitionKey, String processInstanceId,
                        String caseInstanceId, String assignee, String owner, Instant startTime, Instant endTime,
                        Long durationMs, String deleteReason, String tenantId) {
    }

    record HistoricVariable(String id, String name, String type, Object value, String processInstanceId,
                            String activityInstanceId, Instant createTime, String state) {
    }

    record HistoricDecision(String id, String decisionDefinitionKey, String decisionDefinitionId,
                            String processInstanceId, String activityId, Instant evaluationTime,
                            Map<String, Object> inputs, Map<String, Object> outputs) {
    }

    record HistoricIncident(String id, String type, String message, String processInstanceId, String activityId,
                            Instant createTime, Instant endTime, String state) {
    }

    record HistoricJobLog(String id, String jobId, String processInstanceId, String activityId, Instant timestamp,
                          String state, String exceptionMessage, int retries) {
    }

    record UserOperation(String id, String operationType, String entityType, String property, String orgValue,
                         String newValue, String userId, Instant timestamp, String processInstanceId,
                         String annotation) {
    }

    Page<HistoricProcessInstance> processInstances(RequestContext ctx, HistoryFilter filter, Page.PageRequest page);

    Page<HistoricActivityInstance> activityInstances(RequestContext ctx, HistoryFilter filter, Page.PageRequest page);

    Page<HistoricTask> tasks(RequestContext ctx, HistoryFilter filter, Page.PageRequest page);

    Page<HistoricVariable> variables(RequestContext ctx, HistoryFilter filter, Page.PageRequest page);

    Page<HistoricDecision> decisions(RequestContext ctx, HistoryFilter filter, Page.PageRequest page);

    Page<HistoricIncident> incidents(RequestContext ctx, HistoryFilter filter, Page.PageRequest page);

    Page<HistoricJobLog> jobLog(RequestContext ctx, HistoryFilter filter, Page.PageRequest page);

    Page<UserOperation> operationLog(RequestContext ctx, HistoryFilter filter, Page.PageRequest page);
}
