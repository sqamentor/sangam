// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.RepositoryServiceImpl;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.pvm.PvmActivity;
import org.operaton.bpm.engine.impl.pvm.ReadOnlyProcessDefinition;
import org.operaton.bpm.engine.runtime.ActivityInstance;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.ports.support.EngineTx;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runtime API of the ad-hoc sub-process (FLOWABLE-INTO-OPERATON FC-01): Flowable's {@code getEnabledActivitiesFromAdhocSubProcess},
 * {@code executeActivityInAdhocSubProcess} and {@code completeAdhocSubProcess}.
 *
 * <p>Starting an inner activity uses Operaton's public process-instance modification
 * ({@code startBeforeActivity(activityId, adHocActivityInstanceId)}), so the new work runs inside the existing ad-hoc
 * scope instance with normal history, listeners and authorization. Callers (WS-REST) apply the VI.4 check on the
 * process instance before calling.
 */
public class AdHocSubProcessService {

    /** An activity a user may start now. */
    public record EnabledActivity(String adHocActivityId, String adHocActivityInstanceId, String activityId,
                                  String name, String type) {
    }

    /** One running ad-hoc sub-process instance. */
    public record AdHocInstance(String activityId, String activityInstanceId, AdHocSubProcessActivityBehavior.Ordering ordering,
                                List<String> activeChildActivityIds) {
    }

    private static final Set<String> NOT_STARTABLE = Set.of("startEvent", "boundaryEvent", "intermediateCatchEvent");

    private final ProcessEngine engine;

    public AdHocSubProcessService(ProcessEngine engine) {
        this.engine = engine;
    }

    public List<AdHocInstance> getAdHocInstances(String processInstanceId) {
        ReadOnlyProcessDefinition def = definitionOf(processInstanceId);
        List<AdHocInstance> out = new ArrayList<>();
        ActivityInstance root = engine.getRuntimeService().getActivityInstance(processInstanceId);
        if (root != null) {
            collect(root, def, out);
        }
        return out;
    }

    public List<EnabledActivity> getEnabledActivities(String processInstanceId) {
        ReadOnlyProcessDefinition def = definitionOf(processInstanceId);
        List<EnabledActivity> out = new ArrayList<>();
        for (AdHocInstance ah : getAdHocInstances(processInstanceId)) {
            if (ah.ordering() == AdHocSubProcessActivityBehavior.Ordering.SEQUENTIAL && !ah.activeChildActivityIds().isEmpty()) {
                continue;
            }
            PvmActivity adHoc = def.findActivity(ah.activityId());
            for (PvmActivity inner : adHoc.getActivities()) {
                if (isStartable(inner)) {
                    out.add(new EnabledActivity(ah.activityId(), ah.activityInstanceId(), inner.getId(),
                            (String) inner.getProperty("name"), (String) inner.getProperty("type")));
                }
            }
        }
        return out;
    }

    /**
     * Starts an enabled inner activity. {@code adHocActivityInstanceId} may be null when the process instance has
     * exactly one running ad-hoc instance containing {@code activityId}.
     */
    public void executeActivity(String processInstanceId, String adHocActivityInstanceId, String activityId) {
        if (processInstanceId == null || activityId == null) {
            throw new IllegalArgumentException("processInstanceId and activityId are required");
        }
        // FW-35: check and start in ONE command, with a forced version bump of the process-instance execution, so two
        // concurrent starts cannot both pass the "sequential ordering is idle" check: the loser fails with an
        // optimistic-locking conflict (retryable) instead of running two activities in a sequential ad-hoc scope.
        EngineTx.execute(engine, cc -> {
            ExecutionEntity root = cc.getExecutionManager().findExecutionById(processInstanceId);
            if (root != null) {
                cc.getDbEntityManager().forceUpdate(root);
            }
            EnabledActivity target = null;
            for (EnabledActivity e : getEnabledActivities(processInstanceId)) {
                if (e.activityId().equals(activityId)
                        && (adHocActivityInstanceId == null || adHocActivityInstanceId.equals(e.adHocActivityInstanceId()))) {
                    if (target != null) {
                        throw new ProcessEngineException("activity " + activityId + " is enabled in more than one ad-hoc "
                                + "instance; pass adHocActivityInstanceId");
                    }
                    target = e;
                }
            }
            if (target == null) {
                throw new ProcessEngineException("activity " + activityId + " is not enabled in an ad-hoc sub-process of "
                        + processInstanceId + " (unknown, has an incoming flow, or sequential ordering is busy)");
            }
            // An idle ad-hoc scope (entered, nothing started yet) is a scope execution waiting on the ad-hoc activity
            // itself. Operaton's modification reuses a scope execution only when its activity is null, so on that
            // state it would split the scope into a concurrent child that takes over the ad-hoc activity instance,
            // and every later start fails ("Cannot create concurrent execution"). The first start therefore runs the
            // inner activity on the scope execution, exactly as SubProcessActivityBehavior starts its initial
            // activity; later starts (scope busy) go through the modification and become concurrent children.
            ExecutionEntity idle = idleScopeExecution(root, target);
            if (idle != null) {
                idle.executeActivity(idle.getActivity().findActivity(activityId));
            } else {
                engine.getRuntimeService().createProcessInstanceModification(processInstanceId)
                        .startBeforeActivity(activityId, target.adHocActivityInstanceId())
                        .execute();
            }
            return null;
        });
    }

    /** The scope execution of {@code target}'s ad-hoc instance when it is idle (no inner work), else null. */
    private static ExecutionEntity idleScopeExecution(ExecutionEntity execution, EnabledActivity target) {
        if (execution == null) {
            return null;
        }
        if (execution.isScope() && execution.getActivity() != null
                && target.adHocActivityId().equals(execution.getActivity().getId())
                && target.adHocActivityInstanceId().equals(execution.getActivityInstanceId())
                && execution.getNonEventScopeExecutions().isEmpty()) {
            return execution;
        }
        for (ExecutionEntity child : execution.getExecutions()) {
            ExecutionEntity found = idleScopeExecution(child, target);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Completes an ad-hoc instance. {@code adHocActivityInstanceId} may be null when the process instance has exactly
     * one running ad-hoc sub-process.
     */
    public void complete(String processInstanceId, String adHocActivityInstanceId, boolean cancelRemaining) {
        String id = adHocActivityInstanceId;
        if (id == null) {
            List<AdHocInstance> running = getAdHocInstances(processInstanceId);
            if (running.size() != 1) {
                throw new ProcessEngineException("process instance " + processInstanceId + " has " + running.size()
                        + " running ad-hoc sub-processes; pass adHocActivityInstanceId");
            }
            id = running.get(0).activityInstanceId();
        }
        EngineTx.execute(engine, new CompleteAdHocSubProcessCmd(processInstanceId, id, cancelRemaining));
    }

    static boolean isStartable(PvmActivity inner) {
        Object type = inner.getProperty("type");
        if (type != null && NOT_STARTABLE.contains(type.toString())) {
            return false;
        }
        if (Boolean.TRUE.equals(inner.getProperty("triggeredByEvent"))) {
            return false;
        }
        return inner.getIncomingTransitions().isEmpty();
    }

    private void collect(ActivityInstance ai, ReadOnlyProcessDefinition def, List<AdHocInstance> out) {
        PvmActivity a = ai.getActivityId() == null ? null : def.findActivity(ai.getActivityId());
        if (a != null && AdHocSupport.isAdHoc(a)) {
            List<String> active = new ArrayList<>();
            for (ActivityInstance c : ai.getChildActivityInstances()) {
                active.add(c.getActivityId());
            }
            for (var t : ai.getChildTransitionInstances()) {
                active.add(t.getActivityId());
            }
            out.add(new AdHocInstance(ai.getActivityId(), ai.getId(),
                    ((AdHocSubProcessActivityBehavior) a.getActivityBehavior()).getOrdering(), active));
        }
        for (ActivityInstance c : ai.getChildActivityInstances()) {
            collect(c, def, out);
        }
    }

    private ReadOnlyProcessDefinition definitionOf(String processInstanceId) {
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            throw new ProcessEngineException("process instance " + processInstanceId + " not found");
        }
        return ((RepositoryServiceImpl) engine.getRepositoryService()).getDeployedProcessDefinition(pi.getProcessDefinitionId());
    }
}
