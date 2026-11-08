// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.metrics;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily roll-up per tenant from the engine's history tables: process instances, tasks, decisions and active users (distinct
 * assignees), plus storage bytes when a probe is supplied. Re-running a day replaces that day. For capacity planning only: nothing
 * in the product reads these numbers to allow or refuse anything (R70). Instances with no tenant are counted under tenant {@code -}.
 */
public final class UsageMetrics {

    public record Metric(String tenantId, String day, String metric, long value) {
    }

    /** Stored bytes per tenant, for example from the document store. May be absent. */
    @FunctionalInterface
    public interface StorageProbe {
        Map<String, Long> bytesByTenant();
    }

    private static final Map<String, String> QUERIES = Map.of(
            "process_instances", "SELECT COALESCE(TENANT_ID_,'-'), COUNT(*) FROM ACT_HI_PROCINST WHERE START_TIME_ >= ? AND START_TIME_ < ? GROUP BY COALESCE(TENANT_ID_,'-')",
            "tasks", "SELECT COALESCE(TENANT_ID_,'-'), COUNT(*) FROM ACT_HI_TASKINST WHERE START_TIME_ >= ? AND START_TIME_ < ? GROUP BY COALESCE(TENANT_ID_,'-')",
            "decisions", "SELECT COALESCE(TENANT_ID_,'-'), COUNT(*) FROM ACT_HI_DECINST WHERE EVAL_TIME_ >= ? AND EVAL_TIME_ < ? GROUP BY COALESCE(TENANT_ID_,'-')",
            "active_users", "SELECT COALESCE(TENANT_ID_,'-'), COUNT(DISTINCT ASSIGNEE_) FROM ACT_HI_TASKINST WHERE ASSIGNEE_ IS NOT NULL AND START_TIME_ >= ? AND START_TIME_ < ? GROUP BY COALESCE(TENANT_ID_,'-')");

    private final Transactions tx;
    private final StorageProbe storage;

    public UsageMetrics(Dialect dialect, Transactions tx, StorageProbe storageOrNull) {
        this.tx = tx;
        this.storage = storageOrNull;
    }

    public int rollup(LocalDate day) {
        Instant from = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        String d = day.toString();
        return tx.inTransaction(c -> {
            Jdbc.update(c, "DELETE FROM OPS_USAGE_DAILY WHERE day_utc=?", d);
            int n = 0;
            for (Map.Entry<String, String> q : QUERIES.entrySet()) {
                Map<String, Long> rows = new LinkedHashMap<>();
                Jdbc.query(c, q.getValue(), rs -> rows.put(rs.getString(1), rs.getLong(2)), from, to);
                n += insert(c, d, q.getKey(), rows);
            }
            if (storage != null) {
                n += insert(c, d, "storage_bytes", storage.bytesByTenant());
            }
            return n;
        });
    }

    private static int insert(Connection c, String day, String metric, Map<String, Long> rows) {
        rows.forEach((t, v) -> Jdbc.update(c, "INSERT INTO OPS_USAGE_DAILY(tenant_id,day_utc,metric,value_num) VALUES (?,?,?,?)", t, day, metric, v));
        return rows.size();
    }

    /** One tenant's rows; the caller's authorization on that tenant is checked by the REST layer. */
    public List<Metric> read(String tenantId, LocalDate from, LocalDate toInclusive) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT tenant_id,day_utc,metric,value_num FROM OPS_USAGE_DAILY WHERE tenant_id=? AND day_utc>=? AND day_utc<=? ORDER BY day_utc,metric",
                rs -> new Metric(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)), tenantId, from.toString(), toInclusive.toString()));
    }
}
