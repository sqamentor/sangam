// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.Map;

/**
 * Class-A operational history queries (R63 history views, PF-01; Flowable history-query parity). Not evidence
 * (rule D-0): evidence is the audit chain ({@link AuditPort}). Read permission {@code READ_HISTORY}.
 */
public interface HistoryPort {

    record HistoryFilter(String processDefinitionKey, String processInstanceId, String businessKey,
                         String caseInstanceId, String activityId, String taskDefinitionKey, String assignee,
                         Boolean finished, Instant from, Instant to) {
    }

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
