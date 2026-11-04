// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Process instances, definitions, messages and signals (ARCHITECTURE §4). Cancel runs the model's cancel paths and
 * moves open external work to CANCEL_REQUESTED; delete additionally writes tombstones (VI.5). Resubmit raises the case
 * version and records the version history (R43, IX.3 "return and resubmit with version history").
 */
public interface ProcessPort {

    record StartProcessRequest(String key, String businessKey, Map<String, Object> variables, Integer version,
                               String packageHash, String formVersion, List<String> startBeforeActivityIds) {
    }

    record ProcessInstance(String id, String processDefinitionId, String processDefinitionKey,
                           Integer processDefinitionVersion, String businessKey, String tenantId, String state,
                           boolean ended, Instant startTime, Instant endTime, List<String> activeActivityIds,
                           long caseVersion, String startedBy, String businessStatus, String superProcessInstanceId,
                           String rootProcessInstanceId) {
    }

    record ProcessFilter(String key, String businessKey, String state, Instant startedAfter, Instant startedBefore,
                         Boolean withIncidents, String superProcessInstanceId) {
    }

    record TerminateRequest(String reason, String reasonCode) {
    }

    record TerminationResult(String processInstanceId, String state, List<String> openOperationIds,
                             int cancelRequestedWork, int tombstones) {
    }

    record ResubmitRequest(Map<String, Object> variables, String reason, Long expectedVersion) {
    }

    /** One entry of the case version history (CASE_SUBMITTED / CASE_RETURNED / CASE_RESUBMITTED). */
    record CaseVersion(long version, String kind, String payloadHash, String diffHash, List<String> changedFields,
                       String actor, String seatId, String reason, Instant at) {
    }

    /**
     * Modification instruction (O03, R49; FC-17 across call-activity boundaries when {@code targetProcessInstanceId}
     * is set). type: startBeforeActivity | startAfterActivity | startTransition | cancelActivity |
     * cancelActivityInstance | cancelTransitionInstance.
     */
    record ModificationInstruction(String type, String activityId, String activityInstanceId,
                                   String transitionInstanceId, String targetProcessInstanceId,
                                   Map<String, Object> variables) {
    }

    record ModifyRequest(List<ModificationInstruction> instructions, String reason, boolean skipCustomListeners,
                         boolean skipIoMappings) {
    }

    /** FC-12 triggerable tasks / receive tasks: signal one waiting execution. */
    record TriggerRequest(String executionId, String activityId, Map<String, Object> variables, String reason) {
    }

    record ActivityInstance(String id, String activityId, String activityName, String activityType, String parentId,
                            List<String> executionIds, List<ActivityInstance> children) {
    }

    record ProcessDefinition(String id, String key, String name, int version, String deploymentId, String tenantId,
                             String versionTag, boolean suspended, String resourceName, boolean startableInTasklist) {
    }

    record DefinitionFilter(String key, Boolean latest, String deploymentId) {
    }

    record MessageRequest(String messageName, String businessKey, String processInstanceId,
                          Map<String, Object> correlationKeys, Map<String, Object> variables, boolean all) {
    }

    record MessageResult(String messageName, List<String> processInstanceIds, List<String> executionIds) {
    }

    record SignalRequest(String name, String executionId, Map<String, Object> variables) {
    }

    ProcessInstance start(RequestContext ctx, StartProcessRequest request);

    Page<ProcessInstance> list(RequestContext ctx, ProcessFilter filter, Page.PageRequest page);

    ProcessInstance get(RequestContext ctx, String processInstanceId);

    ActivityInstance activityTree(RequestContext ctx, String processInstanceId);

    Map<String, Object> variables(RequestContext ctx, String processInstanceId);

    Map<String, Object> setVariables(RequestContext ctx, String processInstanceId, Map<String, Object> variables);

    TerminationResult cancel(RequestContext ctx, String processInstanceId, TerminateRequest request);

    TerminationResult delete(RequestContext ctx, String processInstanceId, TerminateRequest request);

    CaseVersion resubmit(RequestContext ctx, String processInstanceId, ResubmitRequest request);

    List<CaseVersion> versions(RequestContext ctx, String processInstanceId);

    ProcessInstance modify(RequestContext ctx, String processInstanceId, ModifyRequest request);

    ProcessInstance trigger(RequestContext ctx, String processInstanceId, TriggerRequest request);

    Page<ProcessDefinition> definitions(RequestContext ctx, DefinitionFilter filter, Page.PageRequest page);

    ProcessDefinition definition(RequestContext ctx, String definitionId);

    String definitionXml(RequestContext ctx, String definitionId);

    MessageResult correlate(RequestContext ctx, MessageRequest request);

    void signal(RequestContext ctx, SignalRequest request);
}
