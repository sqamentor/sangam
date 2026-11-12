// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.sangam.cell.rollup.RollupEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * One private in-memory H2 roll-up database (PostgreSQL mode, as {@code application.yaml}), created from the
 * generated H2 changelog itself ({@code db/changelog/rollup/h2/ru-0001-baseline.sql}), so these tests also prove the
 * generator's output. Test helper only.
 */
final class RollupTestDb {

    final JdbcTemplate jdbc;
    final DataSourceTransactionManager tm;

    RollupTestDb() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:ru-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        this.jdbc = new JdbcTemplate(ds);
        this.tm = new DataSourceTransactionManager(ds);
        for (String stmt : statements("db/changelog/rollup/h2/ru-0001-baseline.sql")) {
            jdbc.execute(stmt);
        }
    }

    /** The changelog's SQL statements without Liquibase comment lines ({@code --changeset}, {@code --rollback} ...). */
    static List<String> statements(String resource) {
        String text;
        try (InputStream in = RollupTestDb.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder sql = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.strip().startsWith("--")) {
                sql.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String s : sql.toString().split(";")) {
            if (!s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    static RollupProperties props(boolean preAggregate, String scope, RollupProperties.Upstream upstream) {
        return new RollupProperties(List.of(), 4, Duration.ofDays(30), true, 500, preAggregate, scope, upstream);
    }

    static RollupProperties.Upstream upstream(String instanceId, Integer depth) {
        return new RollupProperties.Upstream("http://upper.invalid/api/v1/ingest", null, depth, null, instanceId, 50,
                null, null);
    }

    static RollupEnvelope event(String id, String type, String path, String time, Map<String, Object> data) {
        return new RollupEnvelope(id, "sangam://cell/c1", type, time, "c1", null, path, "root-" + id, 1L, null, data);
    }

    static RollupEnvelope start(String id, String path) {
        return event(id, "sangam.history.process.start", path, "2026-11-12T10:00:00Z", Map.of("processDefinitionKey", "p1"));
    }

    static RollupEnvelope end(String id, String path) {
        return event(id, "sangam.history.process.end", path, "2026-11-12T11:00:00Z", Map.of("processDefinitionKey", "p1"));
    }

    static RollupEnvelope metric(String id, String path, String metric, long day, long delta) {
        return new RollupEnvelope(id, "sangam://rollup/rj", RollupEvents.METRIC_TYPE, "2026-11-12T12:00:00Z", "rj",
                null, path, null, 0L, null, Map.of("metric", metric, "day", day, "delta", delta));
    }

    /** SUM over the shards of one counter. */
    long total(String node, String metric, int day) {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(counter_value), 0) FROM RU_COUNTER WHERE node_path = ?"
                + " AND metric = ? AND day_bucket = ?", Long.class, node, metric, day);
        return v == null ? 0 : v;
    }

    /** Every (node, metric, day) total, for whole-table comparisons. */
    Map<String, Long> totals() {
        Map<String, Long> out = new java.util.TreeMap<>();
        jdbc.query("SELECT node_path, metric, day_bucket, SUM(counter_value) FROM RU_COUNTER"
                + " GROUP BY node_path, metric, day_bucket", rs -> {
                    out.put(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getInt(3), rs.getLong(4));
                });
        return out;
    }

    long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }
}
