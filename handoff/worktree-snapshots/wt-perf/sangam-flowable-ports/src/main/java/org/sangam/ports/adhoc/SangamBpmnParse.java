// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParser;
import org.operaton.bpm.engine.impl.core.variable.mapping.IoMapping;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;

/**
 * Patch P-1 (PATCHES.md): a {@link BpmnParse} subclass, installed through
 * {@code ProcessEngineConfigurationImpl.setBpmnParseFactory}, that parses {@code bpmn:adHocSubProcess}.
 *
 * <p>Operaton 2.2.0 {@code BpmnParse.parseActivity} logs "Ignoring unsupported activity type" for
 * {@code adHocSubProcess}; the element is dropped, so every sequence flow touching it fails with ENGINE-09005. A
 * {@code BpmnParseListener} cannot help because the element never becomes an activity. This class intercepts the tag
 * before the default dispatch and builds a scope activity with {@link AdHocSubProcessActivityBehavior}. Every other
 * element goes through the unmodified Operaton code. No Operaton source file is changed; the patch depends on two
 * protected methods ({@code parseActivity}, {@code checkActivityInputOutputSupported}), which are re-checked on
 * every Operaton upgrade.
 */
public class SangamBpmnParse extends BpmnParse {

    public static final String AD_HOC_TAG = "adHocSubProcess";
    public static final String PROPERTY_AD_HOC = "sangam.adHoc";

    public SangamBpmnParse(BpmnParser parser) {
        super(parser);
    }

    @Override
    protected ActivityImpl parseActivity(Element activityElement, Element parentElement, ScopeImpl scopeElement) {
        if (!AD_HOC_TAG.equals(activityElement.getTagName())) {
            return super.parseActivity(activityElement, parentElement, scopeElement);
        }
        boolean multiInstance = false;
        ScopeImpl scope = scopeElement;
        ScopeImpl miBody = parseMultiInstanceLoopCharacteristics(activityElement, scopeElement);
        if (miBody != null) {
            scope = miBody;
            multiInstance = true;
        }
        ActivityImpl activity = parseAdHocSubProcess(activityElement, scope);
        if (multiInstance) {
            activity.setProperty(PROPERTYNAME_IS_MULTI_INSTANCE, true);
        }
        activity.setName(activityElement.attribute("name"));
        parseActivityInputOutput(activityElement, activity);
        return activity;
    }

    protected ActivityImpl parseAdHocSubProcess(Element element, ScopeImpl scope) {
        ActivityImpl activity = createActivityOnScope(element, scope);
        activity.setSubProcessScope(true);
        parseAsynchronousContinuationForActivity(element, activity);
        activity.getProperties().set(BpmnProperties.TRIGGERED_BY_EVENT, false);
        activity.setProperty(PROPERTYNAME_CONSUMES_COMPENSATION, true);
        activity.setScope(true);
        activity.setProperty(PROPERTY_AD_HOC, true);

        AdHocSubProcessActivityBehavior.Ordering ordering =
                "Sequential".equalsIgnoreCase(element.attribute("ordering"))
                        ? AdHocSubProcessActivityBehavior.Ordering.SEQUENTIAL
                        : AdHocSubProcessActivityBehavior.Ordering.PARALLEL;
        String cancelAttr = element.attribute("cancelRemainingInstances");
        boolean cancelRemaining = cancelAttr == null || Boolean.parseBoolean(cancelAttr.trim());
        Expression completion = null;
        Element cc = element.element("completionCondition");
        if (cc != null && cc.getText() != null && !cc.getText().isBlank()) {
            completion = expressionManager.createExpression(cc.getText().trim());
        }
        activity.setActivityBehavior(new AdHocSubProcessActivityBehavior(ordering, completion, cancelRemaining));

        parseScope(element, activity);

        for (BpmnParseListener listener : parseListeners) {
            listener.parseSubProcess(element, scope, activity);
        }
        return activity;
    }

    @Override
    protected boolean checkActivityInputOutputSupported(Element activityElement, ActivityImpl activity, IoMapping inputOutput) {
        if (AD_HOC_TAG.equals(activityElement.getTagName())) {
            return true;
        }
        return super.checkActivityInputOutputSupported(activityElement, activity, inputOutput);
    }
}
