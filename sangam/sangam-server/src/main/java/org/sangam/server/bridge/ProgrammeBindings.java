// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.operaton.bpm.engine.DecisionService;
import org.operaton.bpm.engine.RepositoryService;
import org.sangam.programme.ports.ProgrammePorts;
import org.sangam.security.SangamSecurity;

/**
 * Port bindings of the programme module (sangam-programme README "Integration notes" §2, §4): the reporting hierarchy
 * on sangam-security's effective-dated org units, and return validation on Operaton DMN.
 */
public final class ProgrammeBindings {

    /** Upper bound on the units visited when listing the nodes of a level under a root. */
    static final int MAX_NODES = 50_000;

    private ProgrammeBindings() {
    }

    /** Children, parent, level and head seat from sangam-security {@code OrgUnitService}, evaluated now. */
    public static ProgrammePorts.HierarchyPort orgHierarchy(SangamSecurity security) {
        return new ProgrammePorts.HierarchyPort() {
            @Override
            public List<String> children(Connection c, String tenantId, String nodeId) {
                return security.orgUnits().childrenAt(c, tenantId, nodeId, Instant.now());
            }

            @Override
            public Optional<String> parent(Connection c, String tenantId, String nodeId) {
                return security.orgUnits().parentAt(c, tenantId, nodeId, Instant.now());
            }

            @Override
            public String level(Connection c, String tenantId, String nodeId) {
                return security.orgUnits().unitAt(c, tenantId, nodeId, Instant.now()).map(u -> u.levelType()).orElse("");
            }

            @Override
            public List<String> nodesAtLevel(Connection c, String tenantId, String rootId, String level) {
                Instant now = Instant.now();
                List<String> out = new ArrayList<>();
                Deque<String> todo = new ArrayDeque<>(List.of(rootId));
                Set<String> seen = new LinkedHashSet<>();
                while (!todo.isEmpty() && seen.size() < MAX_NODES) {
                    String id = todo.pop();
                    if (!seen.add(id)) {
                        continue;
                    }
                    String type = security.orgUnits().unitAt(c, tenantId, id, now).map(u -> u.levelType()).orElse("");
                    if (level != null && level.equalsIgnoreCase(type)) {
                        out.add(id);
                        continue;
                    }
                    todo.addAll(security.orgUnits().childrenAt(c, tenantId, id, now));
                }
                return out;
            }

            @Override
            public Optional<String> responsible(Connection c, String tenantId, String nodeId) {
                return security.orgUnits().unitAt(c, tenantId, nodeId, Instant.now()).map(u -> u.headSeatId());
            }
        };
    }

    /**
     * Return validation on Operaton DMN: evaluates {@code validationDmnKey} (tenant definition first, shared otherwise)
     * with the inputs and maps the output columns {@code indicator}, {@code severity}, {@code code}, {@code message} of
     * every matched rule to an {@code ExternalFinding}. The decision runs inside the caller's engine command.
     */
    public static ProgrammePorts.ReturnValidator dmnValidator(DecisionService decisions, RepositoryService repository) {
        return (c, tenantId, decisionKey, inputs) -> {
            if (decisionKey == null || decisionKey.isBlank()) {
                return List.of();
            }
            boolean tenantOwned = tenantId != null && repository.createDecisionDefinitionQuery()
                    .decisionDefinitionKey(decisionKey).tenantIdIn(tenantId).latestVersion().count() > 0;
            var builder = decisions.evaluateDecisionByKey(decisionKey).variables(inputs == null ? Map.of() : inputs);
            builder = tenantOwned ? builder.decisionDefinitionTenantId(tenantId) : builder.decisionDefinitionWithoutTenantId();
            List<ProgrammePorts.ExternalFinding> out = new ArrayList<>();
            for (Map<String, Object> row : builder.evaluate().getResultList()) {
                Object code = row.get("code");
                if (code == null) {
                    continue;
                }
                out.add(new ProgrammePorts.ExternalFinding(str(row.get("indicator")), str(row.getOrDefault("severity", "ERROR")),
                        String.valueOf(code), str(row.get("message"))));
            }
            return out;
        };
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
