// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.incident;

import java.sql.Connection;
import java.time.Instant;

import org.sangam.core.engine.IncidentSink;
import org.sangam.core.engine.SangamClock;
import org.sangam.core.layer.Jdbc;

/**
 * Default {@link IncidentSink}: one row per engine incident in {@code SANGAM_ENGINE_INCIDENT} (state OPEN, then
 * RESOLVED or DELETED), written in the engine command. The reconciliation console (WS-INTEGRATION / WS-UI) lists open
 * rows; for {@code failedExternalTask} incidents {@code engine_work_id} joins to {@code SANGAM_LEASE} and
 * {@code OPERATION_LEDGER} (A-14), so the operator sees the operationId and capability class.
 */
public final class JdbcIncidentSink implements IncidentSink {

    private final SangamClock clock;

    public JdbcIncidentSink(SangamClock clock) {
        this.clock = clock;
    }

    @Override
    public void opened(Connection c, Incident i) {
        String op = "failedExternalTask".equals(i.type()) && i.engineWorkId() != null
                ? Jdbc.one(c, "SELECT operation_id FROM SANGAM_LEASE WHERE work_id = ?", rs -> rs.getString(1),
                i.engineWorkId()).orElse(null)
                : null;
        Jdbc.update(c, "INSERT INTO SANGAM_ENGINE_INCIDENT (incident_id, tenant_id, incident_type, root_id, "
                        + "process_instance_id, execution_id, activity_id, process_definition_id, configuration, "
                        + "engine_work_id, operation_id, message, state, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?)",
                i.incidentId(), i.tenantId(), i.type(), i.rootId(), i.processInstanceId(), i.executionId(),
                i.activityId(), i.processDefinitionId(), truncate(i.configuration(), 255), i.engineWorkId(), op,
                i.message(), Instant.ofEpochMilli(clock.now().toEpochMilli()));
    }

    @Override
    public void closed(Connection c, String incidentId, String reason) {
        Jdbc.update(c, "UPDATE SANGAM_ENGINE_INCIDENT SET state = ?, close_reason = ?, closed_at = ? "
                + "WHERE incident_id = ? AND state = 'OPEN'", reason, reason, clock.now(), incidentId);
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
