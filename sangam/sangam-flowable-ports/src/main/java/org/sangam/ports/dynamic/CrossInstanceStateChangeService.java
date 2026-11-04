// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.dynamic;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.ports.support.EngineTx;

/**
 * Change state across call-activity boundaries (FC-17; Flowable {@code ChangeActivityStateBuilder.moveActivityIdToParentActivityId
 * / moveActivityIdToSubProcessInstanceActivityId}). Composition of two Operaton modifications in <b>one</b> engine
 * transaction: cancel the activity instance in the source instance and start the target activity in the target
 * instance. R49 checks: both instances must share the same root process instance and tenant; the caller authorises the
 * move (VI.4) and records the reason (R42 audit via history).
 */
public class CrossInstanceStateChangeService {

    private final ProcessEngine engine;

    public CrossInstanceStateChangeService(ProcessEngine engine) {
        this.engine = engine;
    }

    /**
     * @param sourceActivityInstanceId activity instance to cancel in {@code sourceProcessInstanceId}
     * @param targetActivityId         activity to start in {@code targetProcessInstanceId}
     * @param targetAncestorInstanceId optional ancestor activity instance in the target (null: the process instance)
     */
    public void move(String sourceProcessInstanceId, String sourceActivityInstanceId, String targetProcessInstanceId,
                     String targetActivityId, String targetAncestorInstanceId, String reason) {
        ProcessInstance src = instance(sourceProcessInstanceId);
        ProcessInstance tgt = instance(targetProcessInstanceId);
        if (!java.util.Objects.equals(src.getTenantId(), tgt.getTenantId())) {
            throw new ProcessEngineException("cross-instance move between tenants is not allowed");
        }
        if (!java.util.Objects.equals(src.getRootProcessInstanceId(), tgt.getRootProcessInstanceId())) {
            throw new ProcessEngineException("cross-instance move needs instances of the same root process instance");
        }
        EngineTx.execute(engine, cc -> {
            var start = engine.getRuntimeService().createProcessInstanceModification(targetProcessInstanceId);
            if (targetAncestorInstanceId == null) {
                start.startBeforeActivity(targetActivityId);
            } else {
                start.startBeforeActivity(targetActivityId, targetAncestorInstanceId);
            }
            start.setAnnotation(reason).execute();
            if (instanceOrNull(sourceProcessInstanceId) != null) {
                engine.getRuntimeService().createProcessInstanceModification(sourceProcessInstanceId)
                        .cancelActivityInstance(sourceActivityInstanceId).setAnnotation(reason).execute();
            }
            return null;
        });
    }

    /** Moves a waiting activity of a called sub-process instance to an activity of its parent process instance. */
    public void moveToParent(String subProcessInstanceId, String sourceActivityInstanceId, String parentActivityId, String reason) {
        ProcessInstance sub = instance(subProcessInstanceId);
        ProcessInstance parent = engine.getRuntimeService().createProcessInstanceQuery().subProcessInstanceId(sub.getId()).singleResult();
        if (parent == null) {
            throw new ProcessEngineException("process instance " + subProcessInstanceId + " has no parent");
        }
        move(subProcessInstanceId, sourceActivityInstanceId, parent.getId(), parentActivityId, null, reason);
    }

    private ProcessInstance instance(String id) {
        ProcessInstance p = instanceOrNull(id);
        if (p == null) {
            throw new ProcessEngineException("process instance " + id + " not found");
        }
        return p;
    }

    private ProcessInstance instanceOrNull(String id) {
        return engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(id).singleResult();
    }
}
