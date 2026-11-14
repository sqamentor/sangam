// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * CMMN 1.1 cases (ARCHITECTURE §4 {@code POST /case-instances}; FLOWABLE-INTO-OPERATON FC-55..FLOWABLE-INTO-OPERATON FC-59 lifecycle extensions and reactivation
 * are provided by sangam-flowable-ports behind this port).
 */
public interface CasePort {

    record StartCaseRequest(String key, String businessKey, Map<String, Object> variables) {
    }

    /** state: AVAILABLE | ENABLED | DISABLED | ACTIVE | SUSPENDED | TERMINATED | COMPLETED | FAILED ... */
    record CaseExecution(String id, String activityId, String activityName, String activityType, String state,
                         boolean required, boolean repeatable, String parentId) {
    }

    record CaseInstance(String id, String caseDefinitionId, String caseDefinitionKey, String businessKey,
                        String tenantId, String state, Instant created, List<CaseExecution> executions,
                        String reactivatedFrom) {
    }

    record CaseDefinition(String id, String key, String name, int version, String deploymentId, String tenantId) {
    }

    /** action: start | complete | disable | reenable | terminate | manualStart (discretionary items). */
    record ExecutionCommand(String action, Map<String, Object> variables, String reason) {
    }

    /** action: complete | close | terminate | reactivate (FLOWABLE-INTO-OPERATON FC-59: a linked new case). */
    record InstanceCommand(String action, Map<String, Object> variables, String reason) {
    }

    CaseInstance start(RequestContext ctx, StartCaseRequest request);

    Page<CaseInstance> list(RequestContext ctx, String key, String businessKey, Boolean active, Page.PageRequest page);

    CaseInstance get(RequestContext ctx, String caseInstanceId);

    CaseInstance executionCommand(RequestContext ctx, String caseInstanceId, String executionId, ExecutionCommand command);

    CaseInstance instanceCommand(RequestContext ctx, String caseInstanceId, InstanceCommand command);

    Map<String, Object> variables(RequestContext ctx, String caseInstanceId);

    Page<CaseDefinition> definitions(RequestContext ctx, String key, Boolean latest, Page.PageRequest page);
}
