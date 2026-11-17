// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.incident;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.engine.RouteDecision;
import org.sangam.engine.deploy.StrictExclusiveGatewayBehavior;

/**
 * <b>R8-pnp PNP-3 (SG-35 / ROUTING-INCIDENT v1): the host-side read of routing incidents, shipped by Sangam.</b> A
 * strict exclusive gateway that cannot route parks the instance with an incident of type {@value #TYPE} whose message
 * starts with the stable code ({@code NO_MATCHING_ROUTE}, {@code AMBIGUOUS_ROUTE}); plain Operaton throws
 * {@code ENGINE-02004} instead. A host that maps engine outcomes to its own reason codes reads them here, typed,
 * instead of writing the query, the walk over called child instances and the message parsing itself.
 *
 * <pre>
 *   for (RoutingIncidents.Found f : RoutingIncidents.collect(engine, caseInstanceId)) {
 *       String reason = switch (f.outcome()) {          // the host's own mapping, nothing else
 *           case NO_MATCHING_ROUTE -> "GATEWAY_NO_MATCH";
 *           case AMBIGUOUS_ROUTE -> "GATEWAY_OVERLAP";
 *           default -> throw new IllegalStateException();
 *       };
 *       hostAudit.appendOnce(f.dedupKey(), reason, f.message());
 *   }
 * </pre>
 *
 * Fail closed: an incident of this type whose message has no known code is never dropped or guessed; it is returned
 * with {@link Found#outcome()} {@code null} (and {@link Found#code()} the raw prefix, or null), so the host sees it.
 */
public final class RoutingIncidents {

    /** The incident type of the strict exclusive gateway. */
    public static final String TYPE = StrictExclusiveGatewayBehavior.INCIDENT_TYPE;

    private RoutingIncidents() {
    }

    /**
     * One routing incident.
     *
     * @param outcome the parsed stable code; null when the message carries no known code (never guessed)
     * @param code    the message prefix before the first ':' (null when there is none)
     */
    public record Found(String incidentId, String processInstanceId, String rootProcessInstanceId, String gatewayId,
                        RouteDecision.Outcome outcome, String code, String message) {
        /** A key that is the same on every read of the same incident and new for a re-raised one. */
        public String dedupKey() {
            return TYPE + ":" + incidentId;
        }
    }

    /** The message prefix before the first ':' (stripped), or null. */
    public static String code(String message) {
        if (message == null) {
            return null;
        }
        int i = message.indexOf(':');
        if (i <= 0) {
            return null;
        }
        String c = message.substring(0, i).strip();
        return c.isEmpty() ? null : c;
    }

    /** The stable routing outcome of an incident message, if it carries a known code. */
    public static Optional<RouteDecision.Outcome> outcome(String message) {
        String c = code(message);
        if (RouteDecision.Outcome.NO_MATCHING_ROUTE.name().equals(c)) {
            return Optional.of(RouteDecision.Outcome.NO_MATCHING_ROUTE);
        }
        if (RouteDecision.Outcome.AMBIGUOUS_ROUTE.name().equals(c)) {
            return Optional.of(RouteDecision.Outcome.AMBIGUOUS_ROUTE);
        }
        return Optional.empty();
    }

    /**
     * The open routing incidents of {@code rootProcessInstanceId} and of every instance it called, at any depth, in
     * walk order (root first). Empty for a null id.
     */
    public static List<Found> collect(ProcessEngine engine, String rootProcessInstanceId) {
        List<Found> out = new ArrayList<>();
        if (rootProcessInstanceId == null) {
            return out;
        }
        Deque<String> todo = new ArrayDeque<>(List.of(rootProcessInstanceId));
        Set<String> seen = new LinkedHashSet<>();
        while (!todo.isEmpty()) {
            String pi = todo.removeFirst();
            if (!seen.add(pi)) {
                continue;
            }
            for (Incident i : engine.getRuntimeService().createIncidentQuery().processInstanceId(pi)
                    .incidentType(TYPE).orderByIncidentTimestamp().asc().list()) {
                if (i.getRootCauseIncidentId() != null && !i.getId().equals(i.getRootCauseIncidentId())) {
                    continue; // Operaton's copy on each calling activity (no message); the child's own incident is read
                }
                out.add(new Found(i.getId(), pi, rootProcessInstanceId, i.getActivityId(),
                        outcome(i.getIncidentMessage()).orElse(null), code(i.getIncidentMessage()),
                        i.getIncidentMessage()));
            }
            for (ProcessInstance child : engine.getRuntimeService().createProcessInstanceQuery()
                    .superProcessInstanceId(pi).list()) {
                todo.addLast(child.getId());
            }
        }
        return out;
    }
}
