// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.history.HistoricCaseInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.repository.CaseDefinition;
import org.operaton.bpm.engine.runtime.CaseInstance;
import org.sangam.ports.links.InstanceLinkService;
import org.sangam.ports.support.EngineTx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Case reactivation and case "migration" by run-off / cut-over.
 * <ul>
 *   <li><b>FLOWABLE-INTO-OPERATON FC-59 reactivation</b> (Flowable {@code CaseReactivationBuilder}, also of historic cases): Operaton
 *       cannot reopen a closed case, so a <em>linked new case</em> is started from the same (or a newer) definition
 *       with the historic variables (minus excluded names), and an {@code INSTANCE_LINK} of type
 *       {@code reactivation} connects old and new (GP-22 pattern).</li>
 *   <li><b>FLOWABLE-INTO-OPERATON FC-19 case migration</b>: native CMMN migration does not exist in Operaton (blocker, XL patch). Route 2:
 *       <em>run-off</em> (old cases finish on their definition, new cases start on the new one: nothing to do), or
 *       <em>cut-over</em> of selected cases: start a new case on the target definition with the current variables,
 *       link it ({@code cut-over}), and terminate the old case, in one transaction.</li>
 * </ul>
 */
public class CaseLifecycleService {

    private final ProcessEngine engine;
    private final InstanceLinkService links;

    public CaseLifecycleService(ProcessEngine engine, InstanceLinkService links) {
        this.engine = engine;
        this.links = links;
    }

    /** Reactivates a closed/completed/terminated case as a new linked case; returns the new case instance id. */
    public String reactivate(String historicCaseInstanceId, String targetDefinitionIdOrNull, Set<String> excludeVariables,
                             Map<String, Object> extraVariables, String user) {
        HistoricCaseInstance h = engine.getHistoryService().createHistoricCaseInstanceQuery()
                .caseInstanceId(historicCaseInstanceId).singleResult();
        if (h == null) {
            throw new ProcessEngineException("historic case " + historicCaseInstanceId + " not found");
        }
        if (h.isActive()) {
            throw new ProcessEngineException("case " + historicCaseInstanceId + " is still active");
        }
        Map<String, Object> vars = new HashMap<>();
        List<HistoricVariableInstance> hv = engine.getHistoryService().createHistoricVariableInstanceQuery()
                .caseInstanceId(historicCaseInstanceId).list();
        for (HistoricVariableInstance v : hv) {
            if (v.getCaseExecutionId() != null && !v.getCaseExecutionId().equals(historicCaseInstanceId)) {
                continue; // only case-level variables
            }
            if (excludeVariables == null || !excludeVariables.contains(v.getName())) {
                vars.put(v.getName(), v.getValue());
            }
        }
        if (extraVariables != null) {
            vars.putAll(extraVariables);
        }
        String defId = targetDefinitionIdOrNull != null ? targetDefinitionIdOrNull : h.getCaseDefinitionId();
        requireSameTenant(defId, h.getTenantId(), historicCaseInstanceId);
        return EngineTx.execute(engine, cc -> {
            CaseInstance ci = engine.getCaseService().withCaseDefinition(defId).businessKey(h.getBusinessKey()).setVariables(vars).create();
            links.link(EngineTx.connection(), h.getTenantId(), "reactivation", null, "case", historicCaseInstanceId, "case",
                    ci.getId(), "case", historicCaseInstanceId, user);
            return ci.getId();
        });
    }

    /** Cut-over of one running case to the latest (or given) definition. Returns the new case instance id. */
    public String cutOver(String caseInstanceId, String targetDefinitionIdOrNull, String user) {
        CaseInstance old = engine.getCaseService().createCaseInstanceQuery().caseInstanceId(caseInstanceId).singleResult();
        if (old == null) {
            throw new ProcessEngineException("case instance " + caseInstanceId + " not found or not active");
        }
        String target = targetDefinitionIdOrNull;
        if (target == null) {
            CaseDefinition current = engine.getRepositoryService().getCaseDefinition(old.getCaseDefinitionId());
            var q = engine.getRepositoryService().createCaseDefinitionQuery().caseDefinitionKey(current.getKey()).latestVersion();
            if (current.getTenantId() != null) {
                q.tenantIdIn(current.getTenantId());
            } else {
                q.withoutTenantId();
            }
            target = q.singleResult().getId();
        }
        if (target.equals(old.getCaseDefinitionId())) {
            throw new ProcessEngineException("case " + caseInstanceId + " already runs on " + target);
        }
        requireSameTenant(target, old.getTenantId(), caseInstanceId);
        String targetId = target;
        Map<String, Object> vars = engine.getCaseService().getVariables(caseInstanceId);
        return EngineTx.execute(engine, cc -> {
            CaseInstance ci = engine.getCaseService().withCaseDefinition(targetId).businessKey(old.getBusinessKey()).setVariables(vars).create();
            links.link(EngineTx.connection(), old.getTenantId(), "cut-over", null, "case", caseInstanceId, "case", ci.getId(),
                    "case", caseInstanceId, user);
            engine.getCaseService().withCaseExecution(caseInstanceId).terminate();
            return ci.getId();
        });
    }

    /**
     * The new case must run in the source case's tenant: a target definition of another tenant (or a shared one for a
     * tenant case) would move the case's variables across the tenant boundary.
     */
    private void requireSameTenant(String definitionId, String tenantId, String caseId) {
        CaseDefinition target = engine.getRepositoryService().createCaseDefinitionQuery().caseDefinitionId(definitionId).singleResult();
        if (target == null) {
            throw new ProcessEngineException("case definition " + definitionId + " not found");
        }
        if (!java.util.Objects.equals(target.getTenantId(), tenantId)) {
            throw new ProcessEngineException("case " + caseId + " cannot move to definition " + definitionId + " of another tenant");
        }
    }
}
