// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cmmn.entity.runtime.CaseExecutionEntity;
import org.operaton.bpm.engine.runtime.CaseExecution;
import org.sangam.ports.support.EngineTx;

import java.util.List;

/**
 * "Occur" API for CMMN user/generic event listeners (FLOWABLE-INTO-OPERATON FC-55; Flowable {@code CmmnRuntimeService.triggerPlanItemInstance}
 * / {@code completeUserEventListenerInstance}). WS-REST exposes it; authorization is the caller's (VI.4).
 */
public class CmmnEventListenerService {

    private final ProcessEngine engine;

    public CmmnEventListenerService(ProcessEngine engine) {
        this.engine = engine;
    }

    /** Available event listeners of a case instance. */
    public List<CaseExecution> availableEventListeners(String caseInstanceId) {
        return engine.getCaseService().createCaseExecutionQuery().caseInstanceId(caseInstanceId).available().list().stream()
                .filter(e -> e.getActivityType() != null && e.getActivityType().toLowerCase(java.util.Locale.ROOT).contains("eventlistener"))
                .toList();
    }

    public void occur(String caseExecutionId) {
        EngineTx.execute(engine, cc -> {
            CaseExecutionEntity ce = cc.getCaseExecutionManager().findCaseExecutionById(caseExecutionId);
            if (ce == null) {
                throw new ProcessEngineException("case execution " + caseExecutionId + " not found");
            }
            Object kind = ce.getActivity() == null ? null : ce.getActivity().getProperty(SangamEventListenerActivityBehavior.PROPERTY_LISTENER_KIND);
            if (kind == null) {
                throw new ProcessEngineException("case execution " + caseExecutionId + " is not an event listener");
            }
            if (!ce.isAvailable()) {
                throw new ProcessEngineException("event listener " + caseExecutionId + " is not available");
            }
            ce.occur();
            return null;
        });
    }
}
