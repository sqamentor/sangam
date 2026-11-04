// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import org.operaton.bpm.engine.impl.bpmn.behavior.ExternalTaskActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;
import org.operaton.bpm.engine.impl.util.xml.Namespace;
import org.sangam.ports.history.EngineEventBus;


/**
 * Parse-time BPMN behaviour extensions (route 1, {@link BpmnParseListener}; handed to WS-PORTS by WS-ENGINE):
 * <ul>
 *   <li>FC-12 triggerable service task ({@code sangam:triggerable="true"});</li>
 *   <li>FC-13 skip expressions ({@code sangam:skipExpression});</li>
 *   <li>FC-22 terminate end event {@code sangam:terminate="all|multiInstance"}.</li>
 * </ul>
 * D-12 bindings and D-13 TTL are WS-ENGINE's ({@code org.sangam.engine.deploy.SangamBpmnParseListener}).
 * Register as a <em>post</em> parse listener after WS-ENGINE's, so its validations see Operaton's own behaviours.
 */
public class BpmnExtensionsParseListener implements BpmnParseListener {

    private static final Namespace OPERATON = new Namespace("http://operaton.org/schema/1.0/bpmn", "http://camunda.org/schema/1.0/bpmn");

    private final boolean skipAlwaysEnabled;
    private final EngineEventBus bus;

    public BpmnExtensionsParseListener(boolean skipAlwaysEnabled, EngineEventBus bus) {
        this.skipAlwaysEnabled = skipAlwaysEnabled;
        this.bus = bus;
    }

    // ------------------------------------------------------------------ FC-12 / FC-13

    @Override
    public void parseServiceTask(Element e, ScopeImpl scope, ActivityImpl a) {
        if ("true".equalsIgnoreCase(SangamBpmn.attr(e, SangamBpmn.TRIGGERABLE))) {
            String cls = e.attributeNS(OPERATON, "class");
            String expr = e.attributeNS(OPERATON, "delegateExpression");
            a.setActivityBehavior(new TriggerableServiceTaskBehavior(cls, expr == null ? null : SangamBpmn.expression(expr)));
        }
        skip(e, a);
    }

    @Override
    public void parseUserTask(Element e, ScopeImpl scope, ActivityImpl a) {
        String skip = SangamBpmn.attr(e, SangamBpmn.SKIP_EXPRESSION);
        if (skip != null && a.getActivityBehavior() instanceof UserTaskActivityBehavior u) {
            a.setActivityBehavior(new SkipExpressionSupport.SkippableUserTask(u, SangamBpmn.expression(skip), skipAlwaysEnabled, bus));
        }
    }

    @Override
    public void parseSendTask(Element e, ScopeImpl scope, ActivityImpl a) {
        skip(e, a);
    }

    @Override
    public void parseScriptTask(Element e, ScopeImpl scope, ActivityImpl a) {
        skip(e, a);
    }

    @Override
    public void parseManualTask(Element e, ScopeImpl scope, ActivityImpl a) {
        skip(e, a);
    }

    @Override
    public void parseTask(Element e, ScopeImpl scope, ActivityImpl a) {
        skip(e, a);
    }

    @Override
    public void parseReceiveTask(Element e, ScopeImpl scope, ActivityImpl a) {
        skip(e, a);
    }

    @Override
    public void parseBusinessRuleTask(Element e, ScopeImpl scope, ActivityImpl a) {
        skip(e, a);
    }

    private void skip(Element e, ActivityImpl a) {
        String skip = SangamBpmn.attr(e, SangamBpmn.SKIP_EXPRESSION);
        if (skip == null) {
            return;
        }
        if (a.getActivityBehavior() instanceof ExternalTaskActivityBehavior) {
            throw new IllegalArgumentException("activity " + a.getId() + ": sangam:skipExpression is not supported on external tasks");
        }
        a.setActivityBehavior(new SkipExpressionSupport.SkippableActivity(a.getActivityBehavior(), SangamBpmn.expression(skip),
                skipAlwaysEnabled, bus));
    }

    // ------------------------------------------------------------------ FC-22

    @Override
    public void parseEndEvent(Element e, ScopeImpl scope, ActivityImpl a) {
        String mode = SangamBpmn.attr(e, SangamBpmn.TERMINATE);
        if (mode == null) {
            return;
        }
        if (e.element("terminateEventDefinition") == null) {
            throw new IllegalArgumentException("end event " + a.getId() + ": sangam:terminate needs a terminateEventDefinition");
        }
        a.setActivityBehavior(new TerminateExtendedBehavior(switch (mode) {
            case "all" -> TerminateExtendedBehavior.Mode.ALL;
            case "multiInstance" -> TerminateExtendedBehavior.Mode.MULTI_INSTANCE;
            default -> throw new IllegalArgumentException("end event " + a.getId() + ": sangam:terminate must be all or multiInstance");
        }));
    }
}
