// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.sangam.core.layer.Jdbc;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Row helpers shared by the asset and maintenance services (meter and plan instantiation, next-due rules). */
final class AssetRows {

    private AssetRows() {
    }

    static String insertMeter(Connection c, String tenant, String assetId, AssetClassDefinition.MeterSpec m) {
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO REG_METER(id, tenant_id, asset_id, meter_type, unit, cumulative, rollover_at, total_value,"
                        + " last_no, last_read_at, version) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, assetId, m.meterType(), m.unit(), m.cumulative() ? 1 : 0, m.rolloverAt(), BigDecimal.ZERO,
                null, null, 1L);
        return id;
    }

    static String insertPlan(Connection c, String tenant, String assetId, AssetClassDefinition.PlanSpec p,
                             Instant timeBase, BigDecimal meterBase) {
        String id = Db.newId();
        Instant nextAt = "TIME".equals(p.trigger()) ? nextTime(timeBase, p.intervalDays()) : null;
        BigDecimal nextMeter = "METER".equals(p.trigger()) ? (meterBase == null ? BigDecimal.ZERO : meterBase).add(p.meterInterval()) : null;
        Jdbc.update(c, "INSERT INTO REG_MAINT_PLAN(id, tenant_id, asset_id, plan_key, trigger_type, interval_days, meter_type,"
                        + " meter_interval, lead_days, work_type, task_json, last_done_at, last_done_meter, next_due_at,"
                        + " next_due_meter, status, version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, assetId, p.planKey(), p.trigger(), p.intervalDays(), p.meterType(), p.meterInterval(),
                p.leadDays(), p.workType(), Json.write(taskJson(p)), null, null, nextAt, nextMeter, "ACTIVE", 1L);
        return id;
    }

    static Map<String, Object> taskJson(AssetClassDefinition.PlanSpec p) {
        return Maps.of("tasks", p.tasks(), "workOrderSeries", p.workOrderSeries());
    }

    static Instant nextTime(Instant base, Integer intervalDays) {
        return base == null || intervalDays == null ? null : base.plus(intervalDays, ChronoUnit.DAYS);
    }
}
