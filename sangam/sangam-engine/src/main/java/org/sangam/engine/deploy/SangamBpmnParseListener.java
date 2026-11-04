// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.HashMap;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.CallableElementActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.core.model.BaseCallableElement.CallableElementBinding;
import org.operaton.bpm.engine.impl.core.model.CallableElement;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.sangam.core.engine.TimerCatchUpPolicy;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.timer.TimerPolicies;

/**
 * Deploy-time validation and defaults for BPMN (FC-46, R44, R43): a deployment that breaks a Sangam rule fails with a
 * message naming the rule, instead of deploying silently and misbehaving later.
 *
 * <ul>
 *   <li><b>GP-09 / D-23</b>: a multi-instance activity whose literal {@code loopCardinality} exceeds
 *       {@code multiInstanceMaxCardinality} is rejected; every multi-instance activity also gets a runtime guard
 *       ({@link MultiInstanceSizeGuard}) for collection-based cardinality. Modelling rule: one instance per item.</li>
 *   <li><b>D-46 / R6 / D-8</b>: script tasks are rejected (Release 1 runs scripts in the out-of-process script
 *       worker).</li>
 *   <li><b>D-12</b>: a call activity without {@code calledElementBinding} binds to the <i>deployment</i>, not the
 *       latest version; a business rule task without an explicit {@code decisionRefBinding} is rejected (its binding
 *       is fixed inside the behaviour at parse time; declare {@code operaton:decisionRefBinding="deployment"}).</li>
 *   <li><b>D-13 / R29 / R61</b>: a process without {@code historyTimeToLive} gets the TTL of its retention class
 *       (extension property {@code sangam:retentionClass}); a class mapped to no TTL is never cleaned up, which is
 *       the default for government records (R61 overrides generic TTL cleanup).</li>
 *   <li><b>R31</b>: a timer event's {@code sangam:catchUp} property (FIRE_ONCE | FIRE_ALL | SKIP_MISSED) is
 *       registered for the DST guard.</li>
 * </ul>
 */
public class SangamBpmnParseListener implements BpmnParseListener {

    private final SangamEngineSettings settings;
    private final TimerPolicies timerPolicies;
    private final MultiInstanceSizeGuard miGuard;

    public SangamBpmnParseListener(SangamEngineSettings settings, TimerPolicies timerPolicies) {
        this.settings = settings;
        this.timerPolicies = timerPolicies;
        this.miGuard = new MultiInstanceSizeGuard(settings.getMultiInstanceMaxCardinality());
    }

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
        if (processDefinition.getHistoryTimeToLive() == null) {
            String retentionClass = properties(processElement).getOrDefault("sangam:retentionClass", "default");
            Integer ttl = settings.getHistoryTtlDaysByRetentionClass().containsKey(retentionClass)
                    ? settings.getHistoryTtlDaysByRetentionClass().get(retentionClass)
                    : ("default".equals(retentionClass) ? settings.getDefaultHistoryTtlDays() : null);
            if (ttl != null) {
                processDefinition.setHistoryTimeToLive(ttl);
            }
        }
    }

    @Override
    public void parseScriptTask(Element scriptTaskElement, ScopeImpl scope, ActivityImpl activity) {
        if (settings.isRejectScriptTasks()) {
            throw new ProcessEngineException("R6/D-8: script task '" + activity.getId() + "' is not allowed in "
                    + "Release 1; model it as an external task served by the out-of-process script worker");
        }
    }

    @Override
    public void parseMultiInstanceLoopCharacteristics(Element activityElement, Element miElement, ActivityImpl activity) {
        int max = settings.getMultiInstanceMaxCardinality();
        Element cardinality = miElement.element("loopCardinality");
        if (cardinality != null && cardinality.getText() != null) {
            String text = cardinality.getText().trim();
            if (text.matches("\\d+") && Long.parseLong(text) > max) {
                throw new ProcessEngineException("GP-09: multi-instance '" + activity.getId() + "' has loopCardinality "
                        + text + " > " + max + "; model one process instance per item with an aggregation step "
                        + "(large multi-instance completion is n-squared on Operaton)");
            }
        }
        activity.addBuiltInListener(ExecutionListener.EVENTNAME_START, miGuard);
    }

    @Override
    public void parseCallActivity(Element callActivityElement, ScopeImpl scope, ActivityImpl activity) {
        if (!settings.isDefaultBindingDeployment() || hasAttribute(callActivityElement, "calledElementBinding")) {
            return;
        }
        if (activity.getActivityBehavior() instanceof CallableElementActivityBehavior b) {
            CallableElement ce = b.getCallableElement();
            if (ce != null) {
                ce.setBinding(CallableElementBinding.DEPLOYMENT);
            }
        }
    }

    @Override
    public void parseBusinessRuleTask(Element businessRuleTaskElement, ScopeImpl scope, ActivityImpl activity) {
        if (settings.isDefaultBindingDeployment() && businessRuleTaskElement.attributes().stream()
                .anyMatch(a -> a.endsWith("decisionRef")) && !hasAttribute(businessRuleTaskElement, "decisionRefBinding")) {
            throw new ProcessEngineException("D-12: business rule task '" + activity.getId() + "' has no "
                    + "decisionRefBinding; old instances would silently use a newer rule. Declare "
                    + "operaton:decisionRefBinding=\"deployment\" (or \"version\"/\"versionTag\")");
        }
    }

    @Override
    public void parseStartEvent(Element startEventElement, ScopeImpl scope, ActivityImpl startEventActivity) {
        declareCatchUp(startEventElement, startEventActivity);
    }

    @Override
    public void parseBoundaryEvent(Element boundaryEventElement, ScopeImpl scopeElement, ActivityImpl nestedActivity) {
        declareCatchUp(boundaryEventElement, nestedActivity);
    }

    @Override
    public void parseIntermediateCatchEvent(Element intermediateEventElement, ScopeImpl scope, ActivityImpl activity) {
        declareCatchUp(intermediateEventElement, activity);
    }

    private void declareCatchUp(Element eventElement, ActivityImpl activity) {
        if (eventElement.element("timerEventDefinition") == null) {
            return;
        }
        String declared = properties(eventElement).get("sangam:catchUp");
        if (declared != null) {
            TimerCatchUpPolicy policy;
            try {
                policy = TimerCatchUpPolicy.parse(declared, settings.getTimerCatchUpDefault());
            } catch (IllegalArgumentException e) {
                throw new ProcessEngineException("R31: timer '" + activity.getId() + "': " + e.getMessage());
            }
            // 2.2.0: ProcessDefinitionImpl has no getKey(); during deployment parsing it is a ProcessDefinitionEntity
            if (activity.getProcessDefinition() instanceof ProcessDefinitionEntity pd) {
                timerPolicies.declare(pd.getKey(), activity.getId(), policy);
            }
        }
    }

    /** {@code <extensionElements><operaton:properties><operaton:property name= value=/>}, any extension namespace. */
    static Map<String, String> properties(Element element) {
        Map<String, String> out = new HashMap<>();
        Element ext = element.element("extensionElements");
        if (ext == null) {
            return out;
        }
        for (Element props : ext.elements()) {
            if (!"properties".equals(props.getTagName())) {
                continue;
            }
            for (Element p : props.elements()) {
                if ("property".equals(p.getTagName()) && p.attribute("name") != null) {
                    out.put(p.attribute("name"), p.attribute("value"));
                }
            }
        }
        return out;
    }

    private static boolean hasAttribute(Element e, String localName) {
        return e.attributes().stream().anyMatch(a -> a.equals(localName) || a.endsWith(":" + localName));
    }
}
