// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.operaton.bpm.engine.runtime.Execution;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.api.LeaseTable;
import org.sangam.integration.lifecycle.LifecyclePorts;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Operaton implementations of the VI.5 lifecycle ports.
 *
 * <ul>
 *   <li>{@link #openWork}: external tasks of the instance tree with their {@code SPIKE_LEASE} fencing row (lock version,
 *       expiry); locked = a live lease is held.</li>
 *   <li>{@link #businessCancel}: correlates message {@value #CANCEL_MESSAGE} when the model has a cancel path waiting
 *       for it (its compensation runs), otherwise deletes with listeners and I/O mappings executed
 *       ({@code skipCustomListeners=false}).</li>
 *   <li>{@link #technicalDelete}: {@code deleteProcessInstance(id, reason, skipCustomListeners, externallyTerminated,
 *       skipIoMappings)} — runtime rows, timers and async jobs go with it (internal jobs dropped quietly, VI.5 rule 2).
 *       The adapter removes the lease rows in the same transaction, so a late worker call gets {@code NO_LEASE}.</li>
 *   <li>{@link #erase}: removes runtime variables of every execution and historic variable instances of the tree.</li>
 * </ul>
 */
public final class OperatonLifecyclePorts implements LifecyclePorts.OpenWorkPort, LifecyclePorts.EngineInstancePort,
        LifecyclePorts.EngineDataEraser {

    public static final String CANCEL_MESSAGE = "sangam.cancel";

    private final ProcessEngine engine;

    public OperatonLifecyclePorts(ProcessEngine engine) {
        this.engine = engine;
    }

    @Override
    public List<LifecyclePorts.OpenWork> openWork(Connection c, String instanceId) {
        List<LifecyclePorts.OpenWork> out = new ArrayList<>();
        for (String pid : instanceTree(c, instanceId)) {
            for (ExternalTask t : engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(pid).list()) {
                LeaseTable.Row lease = LeaseTable.lock(c, t.getId());
                long version = lease == null ? 0 : lease.lockVersion();
                Instant exp = lease == null ? null : lease.expiresAt();
                boolean locked = lease != null && lease.held();
                if (locked || t.getLockExpirationTime() != null) {
                    out.add(new LifecyclePorts.OpenWork(t.getId(), t.getActivityInstanceId(), version,
                            exp != null ? exp : t.getLockExpirationTime() == null ? null : t.getLockExpirationTime().toInstant(),
                            locked));
                }
            }
        }
        return out;
    }

    @Override
    public void businessCancel(Connection c, String instanceId, String reason) {
        var rs = engine.getRuntimeService();
        boolean hasCancelPath = rs.createEventSubscriptionQuery().eventType("message").eventName(CANCEL_MESSAGE)
                .processInstanceId(instanceId).count() > 0;
        if (hasCancelPath) {
            rs.createMessageCorrelation(CANCEL_MESSAGE).processInstanceId(instanceId)
                    .setVariable("sangamCancelReason", reason).correlateAll();
        } else {
            rs.deleteProcessInstance(instanceId, reason, false, true);
        }
    }

    @Override
    public void technicalDelete(Connection c, String instanceId, String reason) {
        engine.getRuntimeService().deleteProcessInstance(instanceId, reason, true, true, true);
    }

    @Override
    public List<String> instanceTree(Connection c, String instanceId) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(instanceId);
        for (ProcessInstance pi : engine.getRuntimeService().createProcessInstanceQuery().rootProcessInstanceId(instanceId).list()) {
            ids.add(pi.getId());
        }
        for (HistoricProcessInstance h : engine.getHistoryService().createHistoricProcessInstanceQuery()
                .rootProcessInstanceId(instanceId).list()) {
            ids.add(h.getId());
        }
        return List.copyOf(ids);
    }

    @Override
    public int erase(Connection c, String tenantId, List<String> instanceIds, String reason) {
        int n = 0;
        var rs = engine.getRuntimeService();
        for (String pid : instanceIds) {
            for (Execution e : rs.createExecutionQuery().processInstanceId(pid).list()) {
                Set<String> names = rs.getVariablesLocal(e.getId()).keySet();
                if (!names.isEmpty()) {
                    rs.removeVariablesLocal(e.getId(), names);
                    n += names.size();
                }
            }
        }
        if (!instanceIds.isEmpty()) {
            List<HistoricVariableInstance> hv = engine.getHistoryService().createHistoricVariableInstanceQuery()
                    .processInstanceIdIn(instanceIds.toArray(new String[0])).list();
            for (HistoricVariableInstance v : hv) {
                engine.getHistoryService().deleteHistoricVariableInstance(v.getId());
                n++;
            }
        }
        return n;
    }
}
