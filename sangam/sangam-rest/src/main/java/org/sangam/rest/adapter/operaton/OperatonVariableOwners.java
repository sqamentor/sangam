// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricCaseInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.runtime.CaseInstance;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.task.Task;
import org.sangam.rest.port.VariableMaskPort;
import org.sangam.rest.support.VariableMasking;

/**
 * Decisions 87 and 98: the owner of a variable read on Operaton. A process instance (running or finished) is owned by
 * itself under its process definition key; a task by its process instance, else its CMMN case instance; a case
 * instance by its process super-instance when a process started it, else by itself under its case definition key.
 * Unknown ids and standalone tasks have no owner (no definition, so no field rule applies). An instance's definition
 * never changes, so resolved owners are cached (bounded).
 */
public final class OperatonVariableOwners implements VariableMasking.Owners {

    private static final int CACHE = 10_000;

    private final ProcessEngine engine;
    private final Map<String, VariableMasking.Owner> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, VariableMasking.Owner> e) {
                    return size() > CACHE;
                }
            });

    public OperatonVariableOwners(ProcessEngine engine) {
        this.engine = engine;
    }

    @Override
    public VariableMasking.Owner ofProcessInstance(String id) {
        if (id == null) {
            return null;
        }
        VariableMasking.Owner hit = cache.get("p:" + id);
        if (hit != null) {
            return hit;
        }
        String key = null;
        ProcessInstance pi = OperatonSupport.call(() ->
                engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(id).singleResult());
        if (pi != null) {
            key = OperatonSupport.call(() ->
                    engine.getRepositoryService().getProcessDefinition(pi.getProcessDefinitionId()).getKey());
        } else {
            HistoricProcessInstance h = OperatonSupport.call(() ->
                    engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(id).singleResult());
            if (h != null) {
                key = h.getProcessDefinitionKey();
            }
        }
        return remember("p:" + id, key == null ? null : new VariableMasking.Owner(VariableMaskPort.PROCESS_INSTANCE, id, key));
    }

    @Override
    public VariableMasking.Owner ofCaseInstance(String id) {
        if (id == null) {
            return null;
        }
        VariableMasking.Owner hit = cache.get("c:" + id);
        if (hit != null) {
            return hit;
        }
        // a case started by a process belongs to that process instance (the owning process instance, decision 98)
        ProcessInstance parent = OperatonSupport.call(() ->
                engine.getRuntimeService().createProcessInstanceQuery().subCaseInstanceId(id).singleResult());
        if (parent != null) {
            return remember("c:" + id, ofProcessInstance(parent.getId()));
        }
        String key = null;
        CaseInstance ci = OperatonSupport.call(() ->
                engine.getCaseService().createCaseInstanceQuery().caseInstanceId(id).singleResult());
        if (ci != null) {
            key = OperatonSupport.call(() ->
                    engine.getRepositoryService().getCaseDefinition(ci.getCaseDefinitionId()).getKey());
        } else {
            HistoricCaseInstance h = OperatonSupport.call(() ->
                    engine.getHistoryService().createHistoricCaseInstanceQuery().caseInstanceId(id).singleResult());
            if (h != null) {
                key = OperatonSupport.call(() ->
                        engine.getRepositoryService().getCaseDefinition(h.getCaseDefinitionId()).getKey());
            }
        }
        return remember("c:" + id, key == null ? null : new VariableMasking.Owner(VariableMaskPort.CASE_INSTANCE, id, key));
    }

    @Override
    public VariableMasking.Owner ofTask(String taskId) {
        if (taskId == null) {
            return null;
        }
        Task t = OperatonSupport.call(() -> engine.getTaskService().createTaskQuery().taskId(taskId).singleResult());
        if (t == null) {
            return null;
        }
        if (t.getProcessInstanceId() != null) {
            return ofProcessInstance(t.getProcessInstanceId());
        }
        return t.getCaseInstanceId() == null ? null : ofCaseInstance(t.getCaseInstanceId());
    }

    private VariableMasking.Owner remember(String k, VariableMasking.Owner o) {
        if (o != null) {
            cache.put(k, o);
        }
        return o;
    }
}
