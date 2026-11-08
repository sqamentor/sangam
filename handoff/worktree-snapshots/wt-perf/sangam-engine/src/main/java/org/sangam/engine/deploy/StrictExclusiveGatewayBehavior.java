// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.Condition;
import org.operaton.bpm.engine.impl.bpmn.behavior.ExclusiveGatewayActivityBehavior;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.IncidentEntity;
import org.operaton.bpm.engine.impl.pvm.PvmTransition;
import org.operaton.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.operaton.bpm.engine.runtime.Incident;
import org.sangam.core.engine.RouteDecision;
import org.sangam.core.engine.RoutingPolicy;

/**
 * <b>ADR-D-25: strict exclusive routing on Operaton.</b> Replaces {@code ExclusiveGatewayActivityBehavior} (which
 * takes the first true flow in document order) and applies the engine-neutral {@link RoutingPolicy}.
 *
 * <ul>
 *   <li>Routable: leaves through the one selected flow.</li>
 *   <li>{@code AMBIGUOUS_ROUTE} / {@code NO_MATCHING_ROUTE}: an incident of type {@link #INCIDENT_TYPE} is created
 *       at the gateway, whose message starts with the stable code, and the execution <b>stays at the gateway</b>.
 *       The command that brought it there commits, so the incident is durable, and nothing after the gateway runs
 *       (no activity, listener, outbox row or ledger posting).</li>
 *   <li>Recovery: an authorized correction (data change) followed by a signal of the waiting execution
 *       ({@code OperatonEngineAdapter.correctRoute}) re-runs the policy; if it now routes, the incident is resolved
 *       and the execution leaves through exactly one flow. If it still does not route, the signal fails with
 *       {@link RoutingException} and the correction is rolled back.</li>
 *   <li>A condition that throws (missing variable, type error) is an error: the exception propagates; it is never
 *       treated as false and the default flow is not taken.</li>
 * </ul>
 */
public class StrictExclusiveGatewayBehavior extends ExclusiveGatewayActivityBehavior {

    public static final String INCIDENT_TYPE = "sangamRouting";
    /** Activity property: the gateway's {@link RoutingPolicy.Mode}. */
    public static final String PROP_MODE = "sangam.routingMode";
    /** Transition property: the flow's {@code sangam:priority} (Integer). */
    public static final String PROP_PRIORITY = "sangam.priority";

    /** A non-routable decision raised to a caller (signal / correction); {@link #code()} is the stable code. */
    public static final class RoutingException extends ProcessEngineException {
        private final transient RouteDecision decision;

        public RoutingException(RouteDecision d) {
            super(d.message());
            this.decision = d;
        }

        public String code() {
            return decision.code();
        }

        public RouteDecision decision() {
            return decision;
        }
    }

    @Override
    public void doLeave(ActivityExecution execution) {
        RouteDecision d = decide(execution);
        if (d.routable()) {
            execution.leaveActivityViaTransition(execution.getActivity().findOutgoingTransition(d.flowId()));
            return;
        }
        if (openIncidents(execution).isEmpty()) {
            execution.createIncident(INCIDENT_TYPE, execution.getActivity().getId(), d.message());
        }
        // the execution stays at the gateway until an authorized correction signals it
    }

    @Override
    public void signal(ActivityExecution execution, String signalName, Object signalData) {
        RouteDecision d = decide(execution);
        if (!d.routable()) {
            throw new RoutingException(d);
        }
        for (Incident i : openIncidents(execution)) {
            execution.resolveIncident(i.getId());
        }
        execution.leaveActivityViaTransition(execution.getActivity().findOutgoingTransition(d.flowId()));
    }

    /** Decision for the execution's current data (shared policy; condition exceptions propagate). */
    public static RouteDecision decide(ActivityExecution execution) {
        Object m = execution.getActivity().getProperty(PROP_MODE);
        RoutingPolicy.Mode mode = m instanceof RoutingPolicy.Mode mm ? mm : RoutingPolicy.Mode.STRICT;
        String defaultFlow = (String) execution.getActivity().getProperty("default");
        List<RoutingPolicy.Candidate> candidates = new ArrayList<>();
        for (PvmTransition t : execution.getActivity().getOutgoingTransitions()) {
            Condition c = (Condition) t.getProperty("condition");
            Integer prio = (Integer) t.getProperty(PROP_PRIORITY);
            candidates.add(new RoutingPolicy.Candidate(t.getId(), prio,
                    c == null ? null : () -> c.evaluate(execution)));
        }
        return RoutingPolicy.decide(execution.getActivity().getId(), mode, candidates, defaultFlow);
    }

    static List<Incident> openIncidents(ActivityExecution execution) {
        List<Incident> out = new ArrayList<>();
        if (execution instanceof ExecutionEntity e) {
            for (IncidentEntity i : e.getIncidents()) {
                if (INCIDENT_TYPE.equals(i.getIncidentType())) {
                    out.add(i);
                }
            }
        }
        return out;
    }
}
