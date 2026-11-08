// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.sangam.core.layer.Jdbc;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import org.sangam.core.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A maintenance work order ({@code REG_WORK_ORDER}), separate from procurement work orders. Types: PREVENTIVE,
 * CORRECTIVE, BREAKDOWN, CALIBRATION, INSPECTION (or any configured plan work type). States: OPEN, ASSIGNED,
 * IN_PROGRESS, ON_HOLD, COMPLETED, CLOSED, CANCELLED.
 */
public record WorkOrder(String id, String tenant, String number, String genKey, String assetId, String planId,
                        String workType, String priority, String state, String description, String reportedBy,
                        Instant reportedAt, Instant dueAt, String assignedTo, Instant startedAt, Instant completedAt,
                        Instant closedAt, Instant downtimeStart, Instant downtimeEnd, BigDecimal meterAtDone,
                        Money cost, Map<String, Object> findings, List<Object> parts, List<Object> evidence,
                        long version, Instant updatedAt, String updatedBy) {

    static final String COLUMNS = "id, tenant_id, wo_number, gen_key, asset_id, plan_id, work_type, priority, state,"
            + " description, reported_by, reported_at, due_at, assigned_to, started_at, completed_at, closed_at,"
            + " downtime_start, downtime_end, meter_at_done, cost, currency, findings_json, parts_json, evidence_json, version,"
            + " updated_at, updated_by";

    static WorkOrder map(ResultSet rs) throws SQLException {
        return new WorkOrder(rs.getString("id"), rs.getString("tenant_id"), rs.getString("wo_number"),
                rs.getString("gen_key"), rs.getString("asset_id"), rs.getString("plan_id"), rs.getString("work_type"),
                rs.getString("priority"), rs.getString("state"), rs.getString("description"), rs.getString("reported_by"),
                Jdbc.instant(rs, "reported_at"), Jdbc.instant(rs, "due_at"), rs.getString("assigned_to"),
                Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "completed_at"), Jdbc.instant(rs, "closed_at"),
                Jdbc.instant(rs, "downtime_start"), Jdbc.instant(rs, "downtime_end"), Db.decimal(rs, "meter_at_done"),
                Asset.money(rs, "cost"), Json.map(rs.getString("findings_json")), Json.list(rs.getString("parts_json")),
                Json.list(rs.getString("evidence_json")), rs.getLong("version"), Jdbc.instant(rs, "updated_at"),
                rs.getString("updated_by"));
    }

    public Map<String, Object> toMap() {
        return Maps.of("id", id, "number", number, "assetId", assetId, "planId", planId, "workType", workType,
                "priority", priority, "state", state, "description", description, "reportedBy", reportedBy,
                "reportedAt", reportedAt, "dueAt", dueAt, "assignedTo", assignedTo, "startedAt", startedAt,
                "completedAt", completedAt, "closedAt", closedAt, "downtimeStart", downtimeStart,
                "downtimeEnd", downtimeEnd, "meterAtDone", meterAtDone, "cost", Asset.wire(cost),
                "currency", cost == null ? null : cost.currencyCode(), "findings", findings,
                "parts", parts, "evidence", evidence, "version", version, "updatedAt", updatedAt, "updatedBy", updatedBy);
    }
}
