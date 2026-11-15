// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.sangam.compliance.ComplianceException;
import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.core.layer.Jdbc;

/**
 * Owner decision 361: the server's {@link CompliancePorts.PersonalDataPort} over the process-instance store (the
 * engine's runtime and history) and the compliance retention register, so a data principal's erasure request reaches
 * the process roots that hold her data and the erasure port scrubs them (engine, outbox, inbox, documents through
 * {@code ErasureService}).
 *
 * <p>The roots of a principal are, within the tenant: the roots the retention register tracks for her
 * ({@code CMP_RETENTION_ITEM}, written by {@link CompliancePurposePlugin} at process start), and the roots of every
 * process instance whose variable {@code sangamApplicantId} or {@code principalRef} (the plugin's defaults) is her
 * reference. Correlation keys are those roots' business keys. The summary lists each root with its process, start
 * and end and the names (never the values) of its variables. Correction of process data is refused by name: the
 * owning process changes its own data.
 */
public final class CompliancePersonalData implements CompliancePorts.PersonalDataPort {

    /** The principal variables of {@link CompliancePurposePlugin} (default, then fallback). */
    static final List<String> PRINCIPAL_VARIABLES = List.of("sangamApplicantId", "principalRef");

    private final HistoryService history;

    public CompliancePersonalData(ProcessEngine engine) {
        this.history = engine.getHistoryService();
    }

    @Override
    public Map<String, Object> summarise(Connection c, String tenantId, String principalRef) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (HistoricProcessInstance p : instances(tenantId, rootsOf(c, tenantId, principalRef))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("process", p.getProcessDefinitionKey());
            r.put("startedAt", p.getStartTime() == null ? null : p.getStartTime().toInstant().toString());
            r.put("endedAt", p.getEndTime() == null ? null : p.getEndTime().toInstant().toString());
            Set<String> names = new TreeSet<>();
            for (HistoricVariableInstance v : history.createHistoricVariableInstanceQuery().processInstanceIdIn(p.getId())
                    .tenantIdIn(tenantId).list()) {
                names.add(v.getName());
            }
            r.put("data", List.copyOf(names));
            out.put("process " + p.getId(), r);
        }
        return out;
    }

    @Override
    public List<String> correct(Connection c, String tenantId, String principalRef, Map<String, Object> corrections,
                                String actor) {
        throw new ComplianceException(ComplianceException.Code.INVALID_STATE, "process data is corrected by the process"
                + " that holds it; this server applies no correction to process variables");
    }

    @Override
    public List<String> rootsOf(Connection c, String tenantId, String principalRef) {
        if (principalRef == null || principalRef.isBlank()) {
            return List.of();
        }
        Set<String> roots = new LinkedHashSet<>(Jdbc.query(c, "SELECT DISTINCT root_id FROM CMP_RETENTION_ITEM WHERE tenant_id=?"
                + " AND principal_ref=? AND state<>'ERASED' ORDER BY root_id", rs -> rs.getString(1), tenantId, principalRef));
        Set<String> instances = new LinkedHashSet<>();
        for (String name : PRINCIPAL_VARIABLES) {
            for (HistoricVariableInstance v : history.createHistoricVariableInstanceQuery().variableValueEquals(name, principalRef)
                    .tenantIdIn(tenantId).list()) {
                if (v.getProcessInstanceId() != null) {
                    instances.add(v.getProcessInstanceId());
                }
            }
        }
        for (HistoricProcessInstance p : instances(tenantId, instances)) {
            roots.add(p.getRootProcessInstanceId() != null ? p.getRootProcessInstanceId() : p.getId());
        }
        return List.copyOf(roots);
    }

    @Override
    public List<String> correlationKeysOf(Connection c, String tenantId, String principalRef) {
        List<String> keys = new ArrayList<>();
        for (HistoricProcessInstance p : instances(tenantId, rootsOf(c, tenantId, principalRef))) {
            if (p.getBusinessKey() != null && !p.getBusinessKey().isBlank()) {
                keys.add(p.getBusinessKey());
            }
        }
        return List.copyOf(new LinkedHashSet<>(keys));
    }

    @Override
    public boolean isProcessRoot(Connection c, String tenantId, String rootId) {
        if (rootId == null || rootId.isBlank()) {
            return false;
        }
        HistoricProcessInstance p = history.createHistoricProcessInstanceQuery().processInstanceId(rootId).tenantIdIn(tenantId)
                .singleResult();
        return p != null && (p.getRootProcessInstanceId() == null || rootId.equals(p.getRootProcessInstanceId()));
    }

    private List<HistoricProcessInstance> instances(String tenantId, java.util.Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return history.createHistoricProcessInstanceQuery().processInstanceIds(new LinkedHashSet<>(ids)).tenantIdIn(tenantId)
                .orderByProcessInstanceStartTime().asc().list();
    }
}
