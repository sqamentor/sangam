// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.List;
import java.util.Map;

/**
 * Flowable-parity dynamic behaviour, implemented by sangam-flowable-ports:
 * <ul>
 *   <li>FLOWABLE-INTO-OPERATON FC-01 ad-hoc sub-process: list enabled activities, start one, complete the ad-hoc scope;</li>
 *   <li>FLOWABLE-INTO-OPERATON FC-15 dynamic injection of a user task or embedded/parallel sub-process into one instance, done as a
 *       reviewed model variant (R43) plus migration of that single instance (no silent runtime patching, FLOWABLE-INTO-OPERATON FC-16).</li>
 * </ul>
 */
public interface DynamicPort {

    record AdHocActivity(String activityId, String name, String type, boolean enabled) {
    }

    record AdHocState(String processInstanceId, String adHocExecutionId, String adHocActivityId,
                      List<AdHocActivity> enabledActivities, List<String> activeActivityIds, boolean completable) {
    }

    record StartAdHocActivityRequest(String activityId, Map<String, Object> variables, String reason) {
    }

    /** kind: USER_TASK | EMBEDDED_SUBPROCESS | PARALLEL_USER_TASK. */
    record InjectionRequest(String kind, String name, String assignee, String candidateGroup, String formKey,
                            String afterActivityId, String subProcessBpmn, Map<String, Object> variables,
                            String reason) {
    }

    record InjectionResult(String processInstanceId, String derivedDefinitionId, String packageId,
                           List<String> injectedActivityIds, String state) {
    }

    AdHocState adHocState(RequestContext ctx, String processInstanceId, String adHocActivityId);

    AdHocState startAdHocActivity(RequestContext ctx, String processInstanceId, String adHocActivityId,
                                  StartAdHocActivityRequest request);

    AdHocState completeAdHoc(RequestContext ctx, String processInstanceId, String adHocActivityId, String reason);

    InjectionResult inject(RequestContext ctx, String processInstanceId, InjectionRequest request);
}
