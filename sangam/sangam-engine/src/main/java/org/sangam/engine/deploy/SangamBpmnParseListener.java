// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.CallableElementActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.behavior.DmnBusinessRuleTaskActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.core.model.BaseCallableElement.CallableElementBinding;
import org.operaton.bpm.engine.impl.core.model.CallableElement;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.sangam.core.engine.TimerCatchUpPolicy;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.feel.StrictNullResultGuard;
import org.sangam.engine.timer.TimerPolicies;

/**
 * Deploy-time validation and defaults for BPMN (FLOWABLE-INTO-OPERATON FC-46, R44, R43): a deployment that breaks a Sangam rule fails with a
 * message naming the rule, instead of deploying silently and misbehaving later.
 *
 * <ul>
 *   <li><b>GP-09 / FLOWABLE-INTO-OPERATON D-23</b>: a multi-instance activity whose literal {@code loopCardinality} exceeds
 *       {@code multiInstanceMaxCardinality} is rejected; every multi-instance activity also gets a runtime guard
 *       ({@link MultiInstanceSizeGuard}) for collection-based cardinality. Modelling rule: one instance per item.</li>
 *   <li><b>FLOWABLE-INTO-OPERATON D-46 / R6 / ADR-D-8</b>: script tasks are rejected (Release 1 runs scripts in the out-of-process script
 *       worker).</li>
 *   <li><b>FLOWABLE-INTO-OPERATON D-12</b>: a call activity without {@code calledElementBinding} binds to the <i>deployment</i>, not the
 *       latest version; a business rule task without an explicit {@code decisionRefBinding} is rejected (its binding
 *       is fixed inside the behaviour at parse time; declare {@code operaton:decisionRefBinding="deployment"}).</li>
 *   <li><b>FLOWABLE-INTO-OPERATON D-13 / R29 / R61</b>: a process without {@code historyTimeToLive} gets the TTL of its retention class
 *       (extension property {@code sangam:retentionClass}); a class mapped to no TTL is never cleaned up, which is
 *       the default for government records (R61 overrides generic TTL cleanup).</li>
 *   <li><b>SL-13</b>: call activities and business rule tasks resolve only the caller's tenant or shared definitions
 *       ({@link TenantIsolationGuard}).</li>
 *   <li><b>R31</b>: a timer event's {@code sangam:catchUp} property (FIRE_ONCE | FIRE_ALL | SKIP_MISSED) is
 *       registered for the DST guard.</li>
 * </ul>
 */
public class SangamBpmnParseListener implements BpmnParseListener {

    private final SangamEngineSettings settings;
    private final TimerPolicies timerPolicies;
    private final MultiInstanceSizeGuard miGuard;

    private final org.sangam.engine.history.ModelHistoryLevels modelHistory;

    public SangamBpmnParseListener(SangamEngineSettings settings, TimerPolicies timerPolicies) {
        this(settings, timerPolicies, null);
    }

    /** @param modelHistory M-057 registry, or null when {@code historyModelOverrides} is off */
    public SangamBpmnParseListener(SangamEngineSettings settings, TimerPolicies timerPolicies,
                                   org.sangam.engine.history.ModelHistoryLevels modelHistory) {
        this.settings = settings;
        this.timerPolicies = timerPolicies;
        this.miGuard = new MultiInstanceSizeGuard(settings.getMultiInstanceMaxCardinality());
        this.modelHistory = modelHistory;
    }

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
        registerModelHistory(processElement, processDefinition);
        checkOperationRefs(processElement, processDefinition);
        attachMultiInstanceGuards(processDefinition);
        attachCompensationOrder(processDefinition);
        validateRouting(processDefinition);
        validateInstanceHandlers(processElement, processDefinition);
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

    // ------------------------------------------------------------------ M-057 model-declared history levels

    private static final org.slf4j.Logger HISTORY_LOG = org.slf4j.LoggerFactory.getLogger("org.sangam.engine.history");

    /**
     * M-057 (decision 88): {@code sangam:historyLevel} on the process and its activities. Off (the default): ignored
     * with a warning, so the model still deploys. On: kept on the parsed definition and registered per definition id by
     * {@code ModelHistoryLevels.DeployRule} (a definition has no id while it is parsed); an unknown level name, or the
     * property on an element that is not an activity, refuses the deployment.
     */
    private void registerModelHistory(Element processElement, ProcessDefinitionEntity def) {
        java.util.List<String[]> declared = new java.util.ArrayList<>();
        collectHistoryLevels(processElement, true, declared);
        if (declared.isEmpty()) {
            return;
        }
        if (modelHistory == null) {
            HISTORY_LOG.warn("M-057: process '{}' declares {} but sangam.engine.history-model-overrides is off; ignored",
                    def.getKey(), org.sangam.engine.history.ModelHistoryLevels.PROPERTY);
            return;
        }
        for (String[] d : declared) {
            if (d[0] != null && def.findActivity(d[0]) == null) {
                throw new ProcessEngineException("M-057: " + org.sangam.engine.history.ModelHistoryLevels.PROPERTY
                        + " on '" + d[0] + "' in process '" + def.getKey() + "', which is not an activity");
            }
            try {
                org.sangam.engine.history.ModelHistoryLevels.declare(def, d[0], d[1]);
            } catch (IllegalArgumentException e) {
                throw new ProcessEngineException("M-057: process '" + def.getKey() + "': " + e.getMessage()
                        + " (none, activity, audit, full)", e);
            }
        }
    }

    /** {activityId (null for the process), level} for every element declaring the property, extension elements skipped. */
    private static void collectHistoryLevels(Element e, boolean isProcess, java.util.List<String[]> out) {
        String level = properties(e).get(org.sangam.engine.history.ModelHistoryLevels.PROPERTY);
        if (level != null) {
            out.add(new String[]{isProcess ? null : e.attribute("id"), level});
        }
        for (Element c : e.elements()) {
            if (!"extensionElements".equals(c.getTagName())) {
                collectHistoryLevels(c, false, out);
            }
        }
    }

    // ------------------------------------------------------------------ M-068 sangam:operationRef

    /** Activity property holding the pinned {@code contractId@version#operationId} for the connector at run time. */
    public static final String OPERATION_REF_PROPERTY = "sangamOperationRef";

    /**
     * M-068: every {@code sangam:operationRef} that runs is pinned ({@link OperationRefPinning#PINNED}; new deployments
     * are pinned by the pre-deployer before this parse) and is exposed on its activity as
     * {@link #OPERATION_REF_PROPERTY}.
     */
    private void checkOperationRefs(Element e, ProcessDefinitionEntity def) {
        String ref = e.attributeNS(SANGAM_NS, OperationRefPinning.ATTRIBUTE);
        if (ref != null) {
            String id = e.attribute("id");
            ActivityImpl a = id == null ? null : def.findActivity(id);
            if (a == null || !OperationRefPinning.ELEMENTS.contains(e.getTagName())) {
                throw new ProcessEngineException("M-068: sangam:operationRef is allowed on a service or send task only ('"
                        + id + "' in process '" + def.getKey() + "')");
            }
            if (!OperationRefPinning.PINNED.matcher(ref).matches()) {
                throw new ProcessEngineException("M-068: sangam:operationRef '" + ref + "' on '" + id + "' in process '"
                        + def.getKey() + "' is not pinned (contractId@version#operationId)");
            }
            a.setProperty(OPERATION_REF_PROPERTY, ref);
        }
        for (Element c : e.elements()) {
            if (!"extensionElements".equals(c.getTagName())) {
                checkOperationRefs(c, def);
            }
        }
    }

    // ------------------------------------------------------------------ M-059 sangam:query / sangam:update (L6)

    private final org.sangam.engine.handlers.InstanceHandlerEvaluator handlerCheck =
            new org.sangam.engine.handlers.InstanceHandlerEvaluator();

    /**
     * M-059: the named query and update handlers of the process ({@code sangam:query} / {@code sangam:update} in its
     * extension elements) are validated at deploy; a process that declares none is not touched.
     */
    private void validateInstanceHandlers(Element processElement, ProcessDefinitionEntity processDefinition) {
        Element ext = processElement.element("extensionElements");
        if (ext == null) {
            return;
        }
        try {
            org.sangam.engine.handlers.InstanceHandlers.parse(processDefinition.getKey(), node(ext),
                    id -> processDefinition.findActivity(id) != null, handlerCheck);
        } catch (org.sangam.engine.handlers.InstanceHandlerException e) {
            throw new ProcessEngineException("M-059/L6: " + e.getMessage(), e);
        }
    }

    /** Operaton's parse element as an {@link org.sangam.engine.handlers.InstanceHandlers.Node}. */
    static org.sangam.engine.handlers.InstanceHandlers.Node node(Element e) {
        return new org.sangam.engine.handlers.InstanceHandlers.Node() {
            @Override
            public String uri() {
                return e.getUri();
            }

            @Override
            public String localName() {
                return e.getTagName();
            }

            @Override
            public String attr(String name) {
                return e.attribute(name);
            }

            @Override
            public List<org.sangam.engine.handlers.InstanceHandlers.Node> children() {
                return e.elements().stream().map(SangamBpmnParseListener::node).toList();
            }
        };
    }

    @Override
    public void parseScriptTask(Element scriptTaskElement, ScopeImpl scope, ActivityImpl activity) {
        if (settings.isRejectScriptTasks()) {
            throw new ProcessEngineException("R6/ADR-D-8: script task '" + activity.getId() + "' is not allowed in "
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
        // The runtime guard goes on the inner activity (see attachMultiInstanceGuards): this callback receives the
        // multi-instance body before its inner activity is parsed, and the body's start listeners run before
        // nrOfInstances is set, so a guard on the body never sees the cardinality.
    }

    /** Adds {@link MultiInstanceSizeGuard} to the start of every inner activity of a multi-instance body. */
    private void attachMultiInstanceGuards(ScopeImpl scope) {
        for (ActivityImpl a : scope.getActivities()) {
            if (a.getActivityBehavior() instanceof org.operaton.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior) {
                for (ActivityImpl inner : a.getActivities()) {
                    inner.addBuiltInListener(ExecutionListener.EVENTNAME_START, miGuard);
                }
            }
            attachMultiInstanceGuards(a);
        }
    }

    // ------------------------------------------------------------------ ADR-D-25 strict exclusive routing

    private static final String RAW_PRIORITY = "sangam.priorityRaw";
    /** Note: {@code Element.attribute(String, String)} is (name, default); namespaced reads need attributeNS. */
    private static final org.operaton.bpm.engine.impl.util.xml.Namespace SANGAM_NS =
            new org.operaton.bpm.engine.impl.util.xml.Namespace(org.sangam.core.engine.RoutingPolicy.NS_URI);

    @Override
    public void parseExclusiveGateway(Element element, ScopeImpl scope, ActivityImpl activity) {
        if (activity.getActivityBehavior() == null || activity.getActivityBehavior().getClass()
                != org.operaton.bpm.engine.impl.bpmn.behavior.ExclusiveGatewayActivityBehavior.class) {
            return;
        }
        String policy = element.attributeNS(SANGAM_NS,
                org.sangam.core.engine.RoutingPolicy.ATTR_POLICY);
        String version = element.attributeNS(SANGAM_NS,
                org.sangam.core.engine.RoutingPolicy.ATTR_POLICY_VERSION);
        activity.setProperty("sangam.routingPolicyRaw", policy);
        activity.setProperty("sangam.routingPolicyVersionRaw", version);
        try {
            activity.setProperty(StrictExclusiveGatewayBehavior.PROP_MODE,
                    org.sangam.core.engine.RoutingPolicy.parseMode(policy));
        } catch (IllegalArgumentException e) {
            throw new ProcessEngineException("R44/ADR-D-25: exclusive gateway '" + activity.getId() + "': "
                    + e.getMessage());
        }
        activity.setActivityBehavior(new StrictExclusiveGatewayBehavior());
    }

    @Override
    public void parseSequenceFlow(Element element, ScopeImpl scope,
                                  org.operaton.bpm.engine.impl.pvm.process.TransitionImpl transition) {
        String p = element.attributeNS(SANGAM_NS,
                org.sangam.core.engine.RoutingPolicy.ATTR_PRIORITY);
        if (p != null) {
            transition.setProperty(RAW_PRIORITY, p);
            try {
                transition.setProperty(StrictExclusiveGatewayBehavior.PROP_PRIORITY, Integer.valueOf(p.trim()));
            } catch (NumberFormatException e) {
                // reported by validateRouting with the gateway context
            }
        }
    }

    /** R44 / ADR-D-25: every strict gateway is deterministic; a priority policy is unique and complete. */
    private static void validateRouting(ScopeImpl scope) {
        for (ActivityImpl a : scope.getActivities()) {
            if (a.getActivityBehavior() instanceof StrictExclusiveGatewayBehavior) {
                List<org.sangam.core.engine.RoutingPolicy.FlowSpec> flows = new java.util.ArrayList<>();
                for (org.operaton.bpm.engine.impl.pvm.PvmTransition t : a.getOutgoingTransitions()) {
                    flows.add(new org.sangam.core.engine.RoutingPolicy.FlowSpec(t.getId(),
                            t.getProperty("condition") != null, (String) t.getProperty(RAW_PRIORITY)));
                }
                List<String> problems = org.sangam.core.engine.RoutingPolicy.validate(a.getId(),
                        (String) a.getProperty("sangam.routingPolicyRaw"),
                        (String) a.getProperty("sangam.routingPolicyVersionRaw"), flows, (String) a.getProperty("default"));
                if (!problems.isEmpty()) {
                    throw new ProcessEngineException("R44/ADR-D-25: " + String.join("; ", problems));
                }
            }
            validateRouting(a);
        }
    }

    /** SG-08 (a): completion order of compensable activities (see {@link SequentialCompensation.OrderListener}). */
    private void attachCompensationOrder(ScopeImpl scope) {
        for (ActivityImpl a : scope.getActivities()) {
            // SL-101: a multi-instance body is compensated as one step (Operaton creates its subscription when the body
            // ends, CompensationUtil.createEventScopeExecution), so it takes part in the completion order like a scope
            if (a.findCompensationHandler() != null || a.isSubProcessScope()
                    || a.getActivityBehavior() instanceof org.operaton.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior) {
                a.addBuiltInListener(ExecutionListener.EVENTNAME_END, SequentialCompensation.OrderListener.INSTANCE);
            }
            // decision 148 (FIO D-27): after the order listener, so the instance's subscription is the newest
            if (MultiInstanceCompensationContext.applies(a)) {
                a.addBuiltInListener(ExecutionListener.EVENTNAME_END, MultiInstanceCompensationContext.INSTANCE);
            }
            attachCompensationOrder(a);
        }
    }

    @Override
    public void parseIntermediateThrowEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
        sequentialCompensation(element, activity);
    }

    @Override
    public void parseEndEvent(Element element, ScopeImpl scope, ActivityImpl activity) {
        sequentialCompensation(element, activity);
    }

    @Override
    public void parseSubProcess(Element element, ScopeImpl scope, ActivityImpl activity) {
        if (activity.getActivityBehavior() != null
                && activity.getActivityBehavior().getClass() == org.operaton.bpm.engine.impl.bpmn.behavior.SubProcessActivityBehavior.class) {
            activity.setActivityBehavior(new SequentialCompensation.SubProcessBehavior());
        }
    }

    /** SG-08 (a): a compensation throw triggers one handler at a time, in reverse completion order. */
    private static void sequentialCompensation(Element element, ActivityImpl activity) {
        Element ced = element.element("compensateEventDefinition");
        if (ced == null || !(activity.getActivityBehavior()
                instanceof org.operaton.bpm.engine.impl.bpmn.behavior.CompensationEventActivityBehavior)) {
            return;
        }
        org.operaton.bpm.engine.impl.bpmn.parser.CompensateEventDefinition def =
                new org.operaton.bpm.engine.impl.bpmn.parser.CompensateEventDefinition();
        String ref = ced.attribute("activityRef");
        def.setActivityRef(ref == null || ref.isBlank() ? null : ref);
        String wait = ced.attribute("waitForCompletion");
        def.setWaitForCompletion(wait == null || Boolean.parseBoolean(wait));
        activity.setActivityBehavior(new SequentialCompensation.ThrowBehavior(def));
    }

    /** Tenant of the definition being parsed (BpmnParse sets it from the deployment before it parses the scope). */
    private static String ownerTenant(ActivityImpl activity) {
        return activity.getProcessDefinition()
                instanceof org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity d ? d.getTenantId() : null;
    }

    @Override
    public void parseCallActivity(Element callActivityElement, ScopeImpl scope, ActivityImpl activity) {
        if (!(activity.getActivityBehavior() instanceof CallableElementActivityBehavior b) || b.getCallableElement() == null) {
            return;
        }
        CallableElement ce = b.getCallableElement();
        TenantIsolationGuard.guard(ce, "callActivity " + activity.getId(), ownerTenant(activity));
        if (settings.isDefaultBindingDeployment() && !hasAttribute(callActivityElement, "calledElementBinding")) {
            ce.setBinding(CallableElementBinding.DEPLOYMENT);
        }
    }

    @Override
    public void parseBusinessRuleTask(Element businessRuleTaskElement, ScopeImpl scope, ActivityImpl activity) {
        if (activity.getActivityBehavior() instanceof DmnBusinessRuleTaskActivityBehavior dmn) {
            TenantIsolationGuard.guard(callableElementOf(dmn), "businessRuleTask " + activity.getId(),
                    ownerTenant(activity));
        }
        // R10 strict null only where declared: sangam:routing=true, sangam:requiredOutputs=a,b
        Map<String, String> props = properties(businessRuleTaskElement);
        boolean routing = "true".equalsIgnoreCase(props.get("sangam:routing"));
        List<String> required = props.get("sangam:requiredOutputs") == null ? List.of()
                : java.util.Arrays.stream(props.get("sangam:requiredOutputs").split(","))
                        .map(String::trim).filter(x -> !x.isEmpty()).toList();
        if (routing || !required.isEmpty()) {
            String resultVariable = businessRuleTaskElement.attributes().stream()
                    .filter(a -> a.equals("resultVariable") || a.endsWith(":resultVariable"))
                    .map(a -> attributeValue(businessRuleTaskElement, a)).findFirst().orElse(null);
            if (resultVariable == null || resultVariable.isBlank()) {
                throw new ProcessEngineException("R10: business rule task '" + activity.getId() + "' declares "
                        + "sangam:routing / sangam:requiredOutputs but no operaton:resultVariable");
            }
            activity.addBuiltInListener(ExecutionListener.EVENTNAME_END,
                    new StrictNullResultGuard(activity.getId(), resultVariable, routing, required));
        }
        if (settings.isDefaultBindingDeployment() && businessRuleTaskElement.attributes().stream()
                .anyMatch(a -> a.endsWith("decisionRef")) && !hasAttribute(businessRuleTaskElement, "decisionRefBinding")) {
            throw new ProcessEngineException("FLOWABLE-INTO-OPERATON D-12: business rule task '" + activity.getId() + "' has no "
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

    /**
     * The decision reference of a DMN business rule task. Operaton 2.2 keeps it in a protected field without an accessor
     * (unlike call activities and CMMN tasks), so it is read reflectively; a renamed field fails every deployment of a
     * business rule task loudly instead of silently skipping the tenant-isolation guard.
     */
    private static org.operaton.bpm.engine.impl.core.model.BaseCallableElement callableElementOf(
            DmnBusinessRuleTaskActivityBehavior b) {
        try {
            java.lang.reflect.Field f = DmnBusinessRuleTaskActivityBehavior.class.getDeclaredField("callableElement");
            f.setAccessible(true);
            return (org.operaton.bpm.engine.impl.core.model.BaseCallableElement) f.get(b);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ProcessEngineException("SL-13: cannot read the decision reference of a business rule task"
                    + " (Operaton internals changed): " + e, e);
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

    /** Value of an attribute by its map key as returned by {@code Element.attributes()} (namespace-qualified). */
    private static String attributeValue(Element e, String key) {
        int i = key.lastIndexOf(':');
        if (i > 0) {
            String v = e.attribute(key.substring(0, i), key.substring(i + 1));
            if (v != null) {
                return v;
            }
        }
        return e.attribute(key);
    }

    private static boolean hasAttribute(Element e, String localName) {
        return e.attributes().stream().anyMatch(a -> a.equals(localName) || a.endsWith(":" + localName));
    }
}
