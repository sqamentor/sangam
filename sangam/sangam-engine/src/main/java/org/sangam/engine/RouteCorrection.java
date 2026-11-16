// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.RuntimeService;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;

/**
 * <b>ADR-D-25 recovery</b> of an {@code AMBIGUOUS_ROUTE} / {@code NO_MATCHING_ROUTE} incident, split out of
 * {@link OperatonEngineAdapter#correctRoute} (X2-04 class-size ratchet; behaviour unchanged). Runs inside that
 * method's command, under L2 on the root: object check {@code PROCESS_INSTANCE / MODIFY} for {@code actor} (fail closed
 * when no authorization port is bound), the corrected variables are set, every execution parked at a strict exclusive
 * gateway of the instance is re-routed by the shared policy, and a {@code ROUTE_CORRECTED} audit event records who
 * corrected what (variable names, not values) and the flow taken. If any gateway still does not route, the whole
 * correction rolls back and {@link org.sangam.engine.deploy.StrictExclusiveGatewayBehavior.RoutingException} carries
 * the code.
 */
final class RouteCorrection {

    private RouteCorrection() {
    }

    /**
     * @param root the instance's root (already locked), or null when the instance does not run
     * @return the ids of the re-routed gateways
     */
    static List<String> apply(Connection conn, RuntimeService runtimeService, SecurityPorts.Authorization authz,
                              AuditSink audit, OperatonEngineAdapter.RootRef root, SecurityIdentity actor,
                              String processInstanceId, Map<String, Object> variables, String reason) {
        if (root == null) {
            throw new ProcessEngineException("no running process instance " + processInstanceId);
        }
        if (authz == null) {
            throw new org.sangam.core.security.SangamSecurityException.AccessDenied(
                    "ADR-D-25: no authorization port bound for route corrections", 0);
        }
        authz.require(conn, actor, "PROCESS_INSTANCE", "MODIFY", processInstanceId);
        List<String> parked = Jdbc.query(conn, "SELECT EXECUTION_ID_ FROM ACT_RU_INCIDENT WHERE PROC_INST_ID_ = ? "
                + "AND INCIDENT_TYPE_ = ?", rs -> rs.getString(1), processInstanceId,
                org.sangam.engine.deploy.StrictExclusiveGatewayBehavior.INCIDENT_TYPE);
        if (parked.isEmpty()) {
            throw new ProcessEngineException("instance " + processInstanceId + " has no routing incident");
        }
        if (variables != null && !variables.isEmpty()) {
            runtimeService.setVariables(processInstanceId, org.sangam.core.engine.DocumentSlotNames.checked(variables));
        }
        List<String> taken = new ArrayList<>();
        for (String executionId : parked) {
            String gateway = Jdbc.one(conn, "SELECT ACT_ID_ FROM ACT_RU_EXECUTION WHERE ID_ = ?",
                    rs -> rs.getString(1), executionId).orElse(null);
            runtimeService.signal(executionId); // re-runs the shared policy; RoutingException rolls everything back
            taken.add(gateway);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("processInstanceId", processInstanceId);
        event.put("actorPersonId", actor.personId());
        event.put("actorSubject", actor.subject());
        event.put("reason", reason);
        event.put("correctedVariables", variables == null ? List.of() : new ArrayList<>(new java.util.TreeSet<>(variables.keySet())));
        event.put("gateways", taken);
        audit.append(conn, root.tenant(), root.rootId(), "ROUTE_CORRECTED", event);
        return taken;
    }
}
