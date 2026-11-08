// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.resources.port.ResourcesPorts;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ResourcesPorts.ApprovalPort} on Operaton: starts the approval process by key, with the subject as business key
 * and variables, in the caller's tenant. Called inside an engine command (a delegate) the start joins that command's
 * transaction; called from a REST adapter it is atomic with the product rows only when the adapter runs on
 * {@code Transactions.of(engineAdapter)} (integration note).
 *
 * <p>The approval model ends with a service task using {@link BookingActionDelegate} ({@code APPROVAL_DECISION}),
 * {@link PublishRosterDelegate} or {@link RosterSwapDecisionDelegate} with the variables {@code approved} and
 * {@code reason}.
 */
public final class OperatonApprovalPort implements ResourcesPorts.ApprovalPort {

    private final RuntimeService runtime;

    public OperatonApprovalPort(RuntimeService runtime) {
        this.runtime = runtime;
    }

    @Override
    public String startApproval(Connection c, SecurityIdentity actor, String processKey, String subjectType, String subjectId,
                                String businessKey, Map<String, Object> variables) {
        Map<String, Object> vars = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        vars.put("subjectType", subjectType);
        vars.put("subjectId", subjectId);
        vars.put("tenantId", actor.tenantId());
        vars.put("initiator", actor.onBehalfOf() != null ? actor.onBehalfOf() : actor.personId());
        ProcessInstantiationBuilder b = runtime.createProcessInstanceByKey(processKey)
                .businessKey(businessKey == null ? subjectType + ":" + subjectId : businessKey)
                .setVariables(vars);
        if (actor.tenantId() != null) {
            b = b.processDefinitionTenantId(actor.tenantId());
        }
        ProcessInstance pi = b.execute();
        return pi.getId();
    }
}
