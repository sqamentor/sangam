// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.Map;
import java.util.UUID;

import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.engine.OperationIdentity;
import org.sangam.core.engine.SangamEngineAdapter;

/**
 * The T35 operation id of an Operaton external task for every REST path (worker API fallback, delete preview,
 * modification preview, ops console). With the Sangam engine adapter it asks the adapter (the id stored with the
 * lease); without one it gathers the same facts and calls {@link OperationIdentity#forWork}, the single derivation.
 */
public final class OperatonOperationIds {

    private final RuntimeService runtime;
    private final HistoryService history;
    private final ExternalTaskService externalTasks;
    private volatile SangamEngineAdapter engine;

    public OperatonOperationIds(RuntimeService runtime, HistoryService history, ExternalTaskService externalTasks) {
        this.runtime = runtime;
        this.history = history;
        this.externalTasks = externalTasks;
    }

    /** The Sangam engine adapter, when one is bound: it is then the authority for operation ids. */
    public void setEngine(SangamEngineAdapter engine) {
        this.engine = engine;
    }

    /** The bound Sangam engine adapter, or null (plain Operaton binding). */
    public SangamEngineAdapter engine() {
        return engine;
    }

    /** By work id; null when the external task no longer exists. */
    public UUID of(String workId) {
        ExternalTask t = OperatonSupport.call(() ->
                externalTasks.createExternalTaskQuery().externalTaskId(workId).singleResult());
        return t == null ? null : of(t);
    }

    public UUID of(ExternalTask t) {
        SangamEngineAdapter e = engine;
        if (e != null) {
            UUID id = e.operationIdOf(t.getId());
            if (id != null) {
                return id;
            }
        }
        ProcessInstance pi = OperatonSupport.call(() ->
                runtime.createProcessInstanceQuery().processInstanceId(t.getProcessInstanceId()).singleResult());
        String rootId = pi != null && pi.getRootProcessInstanceId() != null ? pi.getRootProcessInstanceId()
                : t.getProcessInstanceId();
        long visit = OperatonSupport.call(() -> history.createHistoricActivityInstanceQuery()
                .processInstanceId(t.getProcessInstanceId()).activityId(t.getActivityId()).finished().count());
        Map<String, String> ext = t.getExtensionProperties() == null ? Map.of() : t.getExtensionProperties();
        Object loop = OperatonSupport.call(() -> runtime.getVariable(t.getExecutionId(), "loopCounter"));
        String keyVar = ext.get("sangam:retrySafeKey");
        Object key = keyVar == null ? null : OperatonSupport.call(() -> runtime.getVariable(t.getExecutionId(), keyVar));
        return OperationIdentity.forWork(t.getTenantId(), t.getBusinessKey(), rootId, t.getActivityId(), visit,
                loop instanceof Number n ? n.intValue() : null, key == null ? null : String.valueOf(key));
    }
}
