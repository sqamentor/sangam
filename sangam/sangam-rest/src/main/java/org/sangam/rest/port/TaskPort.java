// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Human tasks (BPMN user tasks and CMMN human tasks). Completion is bound to the payload hash the caller saw
 * (VI.4 rule 5, K4); return/resubmit keeps version history (R43, IX.3); segregation of duties by person and seat (K5).
 */
public interface TaskPort {

    record TaskFilter(String assignee, Boolean mine, Boolean unassigned, String candidateGroup, String seatId,
                      String processInstanceId, String caseInstanceId, String businessKey, String processDefinitionKey,
                      String taskDefinitionKey, String state, String sortBy, String sortOrder) {
    }

    /** FC-52 lifecycle fields included: state, claimTime/claimedBy, lastUpdated. */
    record Task(String id, String name, String description, String taskDefinitionKey, String processInstanceId,
                String processDefinitionKey, String caseInstanceId, String businessKey, String assignee, String owner,
                String seatId, String tenantId, Instant created, Instant due, Instant followUp, Instant lastUpdated,
                Instant claimTime, String state, int priority, String formKey, String delegationState) {
    }

    /** Where a return can go (by hierarchy level and seat). {@code activityId} feeds ReturnTaskRequest.targetActivityId. */
    record ReturnTarget(int level, String activityId, String seatId, String seatName, String holderName) {
    }

    /** SoD pre-check for the caller (K5). reasons: SAME_PERSON_AS_INITIATOR, SAME_SEAT_AS_CHECKER, ... */
    record SodCheck(boolean allowed, List<String> reasons, String initiatorName, String initiatorSeatName,
                    String exceptionId) {
    }

    record CodedReason(String code, String label, String labelHi) {
    }

    /** DMN rules that routed or authorised this step (X.2.3.2 decision_ref). */
    record RuleEvidence(String rule, String version, String result) {
    }

    record TaskDetail(Task task, Map<String, Object> variables, String payloadHash, long caseVersion,
                      String formVersion, List<String> materialFields, List<String> allowedActions,
                      List<String> candidateUsers, List<String> candidateGroups, List<ReturnTarget> returnTargets,
                      SodCheck sod, List<WorkspacePort.CandidateSeat> candidates, List<CodedReason> rejectReasons,
                      List<CodedReason> returnReasons, List<WorkspacePort.FieldChange> changedSinceLastSeen,
                      List<RuleEvidence> ruleEvidence, String delegatedFrom) {
    }

    /**
     * @param payloadHash hex SHA-256 of the material payload shown to the caller; must equal the server value
     *                    ({@code 409 APPROVAL_INVALIDATED} otherwise)
     * @param caseVersion the case version shown ({@code 409 FORM_VERSION_STALE} if it moved)
     * @param decision    APPROVED | REJECTED | FORWARDED | ... (set as variable {@code sangamDecision})
     */
    record CompleteTaskRequest(String payloadHash, Long caseVersion, String decision, String remarks,
                               String formVersion, Map<String, Object> variables, String reasonCode,
                               String nextSeatId, String nextSeatReason) {
    }

    record CompleteTaskResult(String taskId, String processInstanceId, String decision, String payloadHash,
                              boolean instanceEnded, List<String> nextTaskIds) {
    }

    /** Return to an earlier level/activity (CASE_RETURNED); {@code targetActivityId} null lets the model route it. */
    record ReturnTaskRequest(String reason, String reasonCode, String targetActivityId, Map<String, Object> variables,
                             String targetSeatId, Integer targetLevel, String payloadHash, Long caseVersion) {
    }

    record DelegateTaskRequest(String toUserId, String toSeatId, String reason) {
    }

    record Comment(String id, String taskId, String processInstanceId, String userId, Instant time, String message) {
    }

    record AddCommentRequest(String message) {
    }

    Page<Task> list(RequestContext ctx, TaskFilter filter, Page.PageRequest page);

    TaskDetail get(RequestContext ctx, String taskId);

    Map<String, Object> variables(RequestContext ctx, String taskId);

    void claim(RequestContext ctx, String taskId);

    void unclaim(RequestContext ctx, String taskId);

    CompleteTaskResult complete(RequestContext ctx, String taskId, CompleteTaskRequest request);

    CompleteTaskResult returnTask(RequestContext ctx, String taskId, ReturnTaskRequest request);

    void delegate(RequestContext ctx, String taskId, DelegateTaskRequest request);

    List<Comment> comments(RequestContext ctx, String taskId);

    Comment addComment(RequestContext ctx, String taskId, AddCommentRequest request);
}
