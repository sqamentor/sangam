// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.task;

import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.persistence.entity.PropertyChange;
import org.operaton.bpm.engine.impl.persistence.entity.TaskEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.task.TaskDefinition;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.sangam.ports.history.EngineEventBus;
import org.sangam.ports.support.EngineTx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task listeners on more events (task enhancements; FLOWABLE-INTO-OPERATON FC-52). Operaton fires {@code create, assignment, update,
 * complete, delete, timeout}. This built-in listener, attached to every BPMN user task by {@link #parseListener()},
 * derives the finer events Flowable and the Sangam inbox need from the entity's property changes:
 * {@code task.claim, task.unclaim, task.delegate, task.resolve, task.owner, task.due-date, task.priority,
 * task.suspend, task.activate, task.follow-up, task.name}. Each event goes to the {@link EngineEventBus} and claim
 * times go to {@link TaskLifecycleService}, all in the engine transaction.
 */
public class TaskEventDispatcher implements TaskListener {

    private static final List<String> EVENTS = List.of(EVENTNAME_CREATE, EVENTNAME_ASSIGNMENT, EVENTNAME_UPDATE,
            EVENTNAME_COMPLETE, EVENTNAME_DELETE, EVENTNAME_TIMEOUT);

    private final EngineEventBus bus;
    private final TaskLifecycleService lifecycle;

    public TaskEventDispatcher(EngineEventBus bus, TaskLifecycleService lifecycleOrNull) {
        this.bus = bus;
        this.lifecycle = lifecycleOrNull;
    }

    @Override
    public void notify(DelegateTask task) {
        String event = task.getEventName();
        emit(task, "task." + event, Map.of());
        if (!(task instanceof TaskEntity te)) {
            return;
        }
        Map<String, PropertyChange> changes = te.getPropertyChanges();
        if (changes == null || changes.isEmpty()) {
            return;
        }
        PropertyChange assignee = changes.get("assignee");
        if (assignee != null && (EVENTNAME_ASSIGNMENT.equals(event) || EVENTNAME_UPDATE.equals(event))) {
            PropertyChange delegation = changes.get("delegation");
            String newDelegation = delegation == null ? null : delegation.getNewValueString();
            if ("PENDING".equals(newDelegation)) {
                emit(task, "task.delegate", change(assignee));
            } else if ("RESOLVED".equals(newDelegation)) {
                emit(task, "task.resolve", change(assignee));
            } else if (assignee.getNewValue() == null) {
                emit(task, "task.unclaim", change(assignee));
                if (lifecycle != null) {
                    lifecycle.unclaimed(EngineTx.connection(), task.getId(), task.getTenantId());
                }
            } else {
                emit(task, "task.claim", change(assignee));
                if (lifecycle != null) {
                    lifecycle.claimed(EngineTx.connection(), task.getId(), task.getTenantId(), assignee.getNewValueString());
                }
            }
        }
        if (!EVENTNAME_UPDATE.equals(event)) {
            return;
        }
        derived(task, changes, "owner", "task.owner");
        derived(task, changes, "dueDate", "task.due-date");
        derived(task, changes, "followUpDate", "task.follow-up");
        derived(task, changes, "priority", "task.priority");
        derived(task, changes, "name", "task.name");
        PropertyChange susp = changes.get("suspensionState");
        if (susp != null) {
            boolean suspended = "2".equals(susp.getNewValueString());
            emit(task, suspended ? "task.suspend" : "task.activate", change(susp));
            if (lifecycle != null) {
                lifecycle.suspended(EngineTx.connection(), task.getId(), task.getTenantId(), suspended);
            }
        }
    }

    private void derived(DelegateTask task, Map<String, PropertyChange> changes, String property, String type) {
        PropertyChange pc = changes.get(property);
        if (pc != null) {
            emit(task, type, change(pc));
        }
    }

    private static Map<String, Object> change(PropertyChange pc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("property", pc.getPropertyName());
        m.put("old", pc.getOrgValueString());
        m.put("new", pc.getNewValueString());
        return m;
    }

    private void emit(DelegateTask task, String type, Map<String, Object> extra) {
        if (bus == null || !bus.hasListeners()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>(extra);
        data.put("taskDefinitionKey", task.getTaskDefinitionKey());
        data.put("assignee", task.getAssignee());
        data.put("owner", task.getOwner());
        bus.dispatch(new EngineEventBus.EngineEvent(type, task.getTenantId(), task.getProcessInstanceId(), task.getCaseInstanceId(),
                task.getTaskDefinitionKey(), task.getId(), data));
    }

    /** Attaches this listener to all six Operaton task events of every BPMN user task. */
    public BpmnParseListener parseListener() {
        TaskEventDispatcher self = this;
        return new BpmnParseListener() {
            @Override
            public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {
                if (activity.getActivityBehavior() instanceof UserTaskActivityBehavior b) {
                    TaskDefinition td = b.getTaskDefinition();
                    for (String ev : EVENTS) {
                        td.addBuiltInTaskListener(ev, self);
                    }
                }
            }
        };
    }
}
