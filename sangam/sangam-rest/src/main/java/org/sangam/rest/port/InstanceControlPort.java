// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;

/**
 * Consequence previews and suspension for instances and definitions (O03, O08, R63: "show a consequence preview"),
 * cross-boundary moves (FLOWABLE-INTO-OPERATON FC-17) and the historic external-task log (PF-01).
 */
public interface InstanceControlPort {

    /** state: OFFERED | LOCKED | UNKNOWN. */
    record OpenTaskCount(String state, long count) {
    }

    record ConsequencePreview(List<String> summary, List<OpenTaskCount> openTasks, List<String> openOperationIds,
                              Integer tombstones, boolean blocked, String blockedReason, boolean requiresFourEyes,
                              Long affectedCount, boolean limitExceeded) {
    }

    /** FLOWABLE-INTO-OPERATON D-32: {@code redateDeadlines} shifts SLA due dates by the suspended time on resume. */
    record SuspensionRequest(boolean suspended, String reason, Boolean redateDeadlines, Boolean includeInstances) {
    }

    record ModifyPreviewRequest(List<ProcessPort.ModificationInstruction> instructions) {
    }

    record CrossBoundaryMoveRequest(String fromActivityInstanceId, String targetInstanceId, String targetActivityId,
                                    String reason) {
    }

    record ExternalTaskLogEntry(String id, String externalTaskId, String topicName, String workerId, String state,
                                String processInstanceId, String activityId, Instant timestamp, Integer retries,
                                String errorMessage) {
    }

    /** mode: BUSINESS (cancel) | TECHNICAL (delete). */
    ConsequencePreview cancelPreview(RequestContext ctx, String processInstanceId, String mode);

    ProcessPort.ProcessInstance setSuspension(RequestContext ctx, String processInstanceId, SuspensionRequest request);

    ProcessPort.ProcessDefinition setDefinitionSuspension(RequestContext ctx, String definitionId,
                                                          SuspensionRequest request);

    ConsequencePreview modifyPreview(RequestContext ctx, String processInstanceId, ModifyPreviewRequest request);

    ProcessPort.ProcessInstance crossBoundaryMove(RequestContext ctx, String processInstanceId,
                                                  CrossBoundaryMoveRequest request);

    Page<ExternalTaskLogEntry> externalTaskLog(RequestContext ctx, HistoryPort.HistoryFilter filter,
                                               Page.PageRequest page);
}
