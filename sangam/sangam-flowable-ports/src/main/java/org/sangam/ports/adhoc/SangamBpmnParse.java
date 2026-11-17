// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.adhoc;

import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParser;
import org.operaton.bpm.engine.impl.bpmn.parser.XMLImporter;
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
    public static final String STANDARD_LOOP_TAG = "standardLoopCharacteristics";
    public static final String PROPERTY_STANDARD_LOOP = "sangam.standardLoop";

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

    /**
     * P-7: a BPMN {@code <import>} (XML Schema, WSDL, BPMN or DMN definitions; BPMN 2.0.2 §8.3.5) is documentation for
     * Sangam: the engine evaluates JUEL/FEEL over process variables and never reads imported item or message types.
     * Operaton 2.2.0 {@code parseImports} adds a parse error for every import type without an {@code XMLImporter}
     * (only WSDL has one, and only with the CXF module, which Sangam does not ship), which refused whole deployments,
     * including descriptive ones (MIWG C.1.1, C.8.0, C.8.1, C.9.0, C.9.2). An import with a working importer is still
     * imported; any other import becomes a parse warning. The pre-deploy validator reports it as DOC-ONLY.
     */
    @Override
    protected void parseImports() {
        Element definitions = getRootElement();
        if (definitions == null) {
            return;
        }
        for (Element imp : definitions.elements("import")) {
            String type = imp.attribute("importType");
            XMLImporter importer = null;
            try {
                importer = getImporter(type, imp);
            } catch (RuntimeException e) {
                // no importer implementation on the class path (WSDL without CXF): same as no importer
            }
            if (importer == null) {
                addWarning("Import of type " + type + " is documentation only (no importer); imported definitions are "
                        + "not used at run time", imp);
            } else {
                importer.importFrom(imp, this);
            }
        }
    }

    /**
     * P-8: BPMN 2.0.2 allows any number of {@code collaboration} root elements (§8.2, Definitions.rootElements);
     * Operaton 2.2.0 {@code parseCollaboration} reads the root with {@code element("collaboration")}, which throws
     * "multiple elements with tag name 'collaboration' found" and refuses the deployment (MIWG C.4.0). Same logic as
     * Operaton (participant name becomes the process's participant), applied to every collaboration.
     */
    @Override
    public void parseCollaboration() {
        Element definitions = getRootElement();
        if (definitions == null) {
            return;
        }
        for (Element collaboration : definitions.elements("collaboration")) {
            for (Element participant : collaboration.elements("participant")) {
                String processRef = participant.attribute("processRef");
                if (processRef == null) {
                    continue;
                }
                var process = getProcessDefinition(processRef);
                if (process == null) {
                    continue;
                }
                var pp = new org.operaton.bpm.engine.impl.pvm.process.ParticipantProcess();
                pp.setId(participant.attribute("id"));
                pp.setName(participant.attribute("name"));
                process.setParticipantProcess(pp);
                participantProcesses.put(pp.getId(), processRef);   // as Operaton (javap): participant id -> process key
            }
        }
    }

    /**
     * P-9: in BPMN DI (BPMNDI.xsd) {@code BPMNEdge/@bpmnElement} is optional: an edge may be pure drawing. Operaton
     * 2.2.0 makes it a parse error ("'bpmnElement' attribute is required on BPMNEdge"), which refused MIWG C.7.0. Such an
     * edge carries no semantics, so it is skipped with a warning; an edge with the attribute is parsed as before.
     */
    @Override
    public void parseBPMNEdge(Element edge) {
        String ref = edge.attribute("bpmnElement");
        if (ref == null || ref.isEmpty()) {
            addWarning("BPMNEdge without bpmnElement is drawing only; skipped", edge);
            return;
        }
        super.parseBPMNEdge(edge);
    }

    /**
     * P-6: Operaton reads the async flags of an activity inside a multi-instance body from the activity (for the body)
     * and from {@code multiInstanceLoopCharacteristics} (for the inner activity), with a {@code requireNonNull} on the
     * latter. For a standard loop the inner activity's flags come from {@code standardLoopCharacteristics} (which has
     * none, so the inner activity is synchronous) and the activity's own {@code asyncBefore/After} go on the loop body,
     * as for a multi-instance activity.
     */
    @Override
    protected void parseAsynchronousContinuationForActivity(Element activityElement, ActivityImpl activity) {
        ActivityImpl parent = activity.getParentFlowScopeActivity();
        Element slc = activityElement.element(STANDARD_LOOP_TAG);
        if (parent != null && slc != null && Boolean.TRUE.equals(parent.getProperty(PROPERTY_STANDARD_LOOP))
                && !activity.isCompensationHandler()) {
            parseAsynchronousContinuation(activityElement, parent);
            parseAsynchronousContinuation(slc, activity);
            return;
        }
        super.parseAsynchronousContinuationForActivity(activityElement, activity);
    }

    /**
     * P-6: {@code standardLoopCharacteristics} becomes a loop body with {@link StandardLoopActivityBehavior}. Operaton
     * 2.2.0 has no parse branch for it (no such string in BpmnParse), so without this the activity ran once and the
     * loop was silently lost. Built like Operaton's multi-instance body so the rest of the engine sees a sequential
     * loop.
     */
    @Override
    public ScopeImpl parseMultiInstanceLoopCharacteristics(Element activityElement, ScopeImpl scope) {
        Element slc = activityElement.element(STANDARD_LOOP_TAG);
        if (slc == null || activityElement.element("multiInstanceLoopCharacteristics") != null) {
            return super.parseMultiInstanceLoopCharacteristics(activityElement, scope);
        }
        String id = activityElement.attribute("id");
        ActivityImpl body = scope.createActivity(getIdForMiBody(id));
        setActivityAsyncDelegates(body);
        body.setProperty(BpmnProperties.TYPE.name(), "multiInstanceBody");
        body.setProperty(PROPERTY_STANDARD_LOOP, true);
        body.setScope(true);

        Expression condition = null;
        Element lc = slc.element("loopCondition");
        if (lc != null && lc.getText() != null && !lc.getText().isBlank()) {
            condition = expressionManager.createExpression(lc.getText().trim());
        }
        boolean testBefore = parseBooleanAttribute(slc.attribute("testBefore"), false);
        Integer maximum = null;
        String max = slc.attribute("loopMaximum");
        if (max == null) {
            Element maxEl = slc.element("loopMaximum");   // BPMN 2.0 (pre-2.0.2) wrote it as an expression element
            max = maxEl == null ? null : maxEl.getText();
        }
        if (max != null && !max.isBlank()) {
            try {
                maximum = Integer.valueOf(max.trim());
                if (maximum < 0) {
                    addError("loopMaximum must not be negative", slc);
                }
            } catch (NumberFormatException e) {
                addError("loopMaximum must be an integer literal", slc);
            }
        }
        if (condition == null && maximum == null) {
            addError("standardLoopCharacteristics needs a loopCondition or a loopMaximum; otherwise the loop never ends", slc);
        }
        body.setActivityBehavior(new StandardLoopActivityBehavior(condition, testBefore, maximum));
        return body;
    }

    @Override
    protected boolean checkActivityInputOutputSupported(Element activityElement, ActivityImpl activity, IoMapping inputOutput) {
        if (AD_HOC_TAG.equals(activityElement.getTagName())) {
            return true;
        }
        return super.checkActivityInputOutputSupported(activityElement, activity, inputOutput);
    }
}
