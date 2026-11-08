// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.assignment;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.task.TaskDefinition;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.SangamSecurity;
import org.sangam.security.sod.SegregationOfDutiesService;
import org.sangam.security.tenant.TenantContext;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code BpmnParseListener} (ADR-D-16 extension point) that
 * <ul>
 *   <li>attaches {@link SeatTaskListener} as a built-in listener to every user task (create, assignment, complete,
 *       delete), configured from the task's {@code operaton:properties} ({@code sangam.*});</li>
 *   <li>adds a process start listener that records the starting person as {@code INITIATOR} of the case for
 *       maker-checker (IX.5 rule 5), when the process declares {@code sangam.recordInitiator=true} (default true).</li>
 * </ul>
 */
public final class SeatAssignmentParseListener extends AbstractBpmnParseListener {

    private final SangamSecurity security;

    public SeatAssignmentParseListener(SangamSecurity security) {
        this.security = security;
    }

    @Override
    public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {
        if (!(activity.getActivityBehavior() instanceof UserTaskActivityBehavior behavior)) {
            return;
        }
        Map<String, String> props = properties(userTaskElement);
        SeatTaskListener.Config cfg = new SeatTaskListener.Config(props.get("sangam.seat"), props.get("sangam.seatVariable"),
                props.get("sangam.level"), props.get("sangam.designation"), props.get("sangam.unitVariable"),
                props.get("sangam.duty"), props.get("sangam.caseVariable"));
        SeatTaskListener listener = new SeatTaskListener(security, cfg);
        TaskDefinition def = behavior.getTaskDefinition();
        def.addBuiltInTaskListener(TaskListener.EVENTNAME_CREATE, listener);
        def.addBuiltInTaskListener(TaskListener.EVENTNAME_ASSIGNMENT, listener);
        def.addBuiltInTaskListener(TaskListener.EVENTNAME_COMPLETE, listener);
        def.addBuiltInTaskListener(TaskListener.EVENTNAME_DELETE, listener);
    }

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
        Map<String, String> props = properties(processElement);
        if ("false".equalsIgnoreCase(props.getOrDefault("sangam.recordInitiator", "true"))) {
            return;
        }
        String caseVariable = props.get("sangam.caseVariable");
        processDefinition.addBuiltInListener(ExecutionListener.EVENTNAME_START, (ExecutionListener) execution ->
                recordInitiator(execution, caseVariable));
    }

    private void recordInitiator(DelegateExecution execution, String caseVariable) {
        if (!execution.getId().equals(execution.getProcessInstanceId())) {
            return;
        }
        SecurityIdentity who = TenantContext.current().orElse(null);
        if (who == null || who.system()) {
            return;
        }
        Object caseVar = caseVariable == null ? null : execution.getVariable(caseVariable);
        String caseId = caseVar != null ? caseVar.toString()
                : execution.getProcessBusinessKey() != null ? execution.getProcessBusinessKey() : execution.getProcessInstanceId();
        Object seat = execution.getVariable("initiatorSeatId");
        String seatId = seat == null ? null : seat.toString();
        String actedFor = seatId != null && who.actsForSeat(seatId) ? seatId : null;
        security.sod().recordActor(EngineConnections.current(), who.tenantId(), caseId, SegregationOfDutiesService.INITIATOR,
                who.personId(), seatId, actedFor, null, null);
    }

    /** Reads {@code extensionElements/operaton:properties/operaton:property} name/value pairs. */
    static Map<String, String> properties(Element element) {
        Map<String, String> out = new HashMap<>();
        Element ext = element.element("extensionElements");
        if (ext == null) {
            return out;
        }
        for (Element props : ext.elements()) {
            if (!localName(props).equals("properties")) {
                continue;
            }
            for (Element p : props.elements()) {
                if (localName(p).equals("property") && p.attribute("name") != null) {
                    out.put(p.attribute("name"), p.attribute("value"));
                }
            }
        }
        return out;
    }

    private static String localName(Element e) {
        String n = e.getTagName();
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }
}
