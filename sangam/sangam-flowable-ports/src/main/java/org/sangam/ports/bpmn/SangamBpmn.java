// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.operaton.bpm.engine.impl.util.xml.Namespace;

/**
 * The Sangam BPMN extension namespace and its attributes. Models stay valid BPMN 2.0.2: extensions are attributes in
 * a foreign namespace, which Operaton's schema validation accepts and ignores.
 */
public final class SangamBpmn {

    public static final String NS_URI = "http://sangam.org/schema/1.0/bpmn";
    public static final Namespace NS = new Namespace(NS_URI);

    /** Event registry: event key sent (throw/end/send) or awaited (catch) by the element. FLOWABLE-INTO-OPERATON FC-02. */
    public static final String EVENT_TYPE = "eventType";
    /** Comma-separated outbound channel keys for a send. FLOWABLE-INTO-OPERATON FC-02. */
    public static final String CHANNELS = "channels";
    /** Triggerable service task. FLOWABLE-INTO-OPERATON FC-12. */
    public static final String TRIGGERABLE = "triggerable";
    /** Skip expression (FEEL/JUEL boolean). FLOWABLE-INTO-OPERATON FC-13. */
    public static final String SKIP_EXPRESSION = "skipExpression";
    /** Terminate end event scope: {@code all} (whole process instance) or {@code multiInstance}. FLOWABLE-INTO-OPERATON FC-22. */
    public static final String TERMINATE = "terminate";
    /** Multi-instance aggregation: target variable name. FLOWABLE-INTO-OPERATON FC-21. */
    public static final String AGGREGATE_TO = "aggregateTo";
    /** Multi-instance aggregation: comma-separated variable names collected per item. FLOWABLE-INTO-OPERATON FC-21. */
    public static final String AGGREGATE_VARIABLES = "aggregateVariables";

    /** On a conditionalEventDefinition: trigger only on a variable change, never on entry (imported Flowable variable listener, FW-12). */
    public static final String ON_VARIABLE_CHANGE = "onVariableChange";

    private SangamBpmn() {
    }

    public static String attr(Element e, String name) {
        String v = e.attributeNS(NS, name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** DOM variant (validator, importer); empty string when absent. */
    public static String attrDom(org.w3c.dom.Element e, String name) {
        return e.getAttributeNS(NS_URI, name).trim();
    }

    /** Expression for a text value at parse time (deployment runs inside an engine command). */
    public static Expression expression(String text) {
        return Context.getProcessEngineConfiguration().getExpressionManager().createExpression(text);
    }
}
