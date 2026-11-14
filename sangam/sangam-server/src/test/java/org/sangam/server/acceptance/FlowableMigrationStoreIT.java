// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.ops.flowable.DefinitionMapping;
import org.sangam.ops.flowable.FlowableMigrationSchema;
import org.sangam.ops.flowable.FlowableMigrationStore;
import org.sangam.ops.flowable.FlowableMigrationStore.Item;
import org.sangam.ops.flowable.FlowableMigrationStore.ItemStatus;
import org.sangam.ops.flowable.FlowableMigrationStore.Run;
import org.sangam.ops.flowable.FlowableMigrationStore.RunState;
import org.sangam.ops.flowable.InstancePlan;
import org.sangam.ops.flowable.InstancePlan.TaskState;
import org.sangam.ops.flowable.InstancePlan.VariableValue;

/**
 * M-135 migration store on the lane's real database and driver (PostgreSQL, Oracle, SQL Server, MySQL through
 * {@code -Dsangam.db}): the portable DDL, run compare-and-set, plan JSON in the {@code {text}} column (CLOB on Oracle,
 * NVARCHAR(MAX) on SQL Server), null binds in {@code COALESCE(?, target_instance_id)}, the status IN list, the
 * cross-run duplicate check (JOIN) and the counters. Plain JDBC on {@link AcceptanceDatabase}; no server is started.
 *
 * <p>Status: written, NOT_RUN on every lane (Oracle and SQL Server included). The OPS_FMIG tables are not yet in the
 * product changelog (table REQUEST to the generator owner); until they are, the test creates them and drops them again.
 */
class FlowableMigrationStoreIT {

    private static Dialect dialect() {
        return switch (AcceptanceDatabase.name()) {
            case "postgres" -> Dialect.POSTGRESQL;
            case "oracle" -> Dialect.ORACLE;
            case "sqlserver" -> Dialect.SQLSERVER;
            case "mysql" -> Dialect.MYSQL;
            default -> throw new IllegalStateException(AcceptanceDatabase.name());
        };
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        for (String name : List.of(table, table.toLowerCase(Locale.ROOT))) {
            try (ResultSet rs = c.getMetaData().getTables(null, null, name, new String[]{"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static InstancePlan plan(String sourceId) {
        return new InstancePlan(sourceId, "order:1:100", "order", 1, "acme", "order:1:target", "ORD-" + sourceId, false,
                List.of("review"), List.of(new TaskState("review", "Review order", "bob", "carol", "PENDING", 70, 1797321600000L,
                        List.of("alice"), List.of("clerks"), List.of(new VariableValue("note", "string", "ünïcödé & \"quotes\"")))),
                List.of(), List.of(), List.of(new VariableValue("amount", "long", "1250"), new VariableValue("empty", "null", null)),
                InstancePlan.Status.SUPPORTED, List.of(), "f".repeat(64));
    }

    @Test
    void storeRoundTripsOnTheLaneDatabase() throws SQLException {
        Dialect d = dialect();
        FlowableMigrationStore store = new FlowableMigrationStore(d);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String runA = "it-a-" + suffix;
        String runB = "it-b-" + suffix;
        String system = "flowable-it-" + suffix;
        try (Connection c = DriverManager.getConnection(AcceptanceDatabase.jdbcUrl(), AcceptanceDatabase.username(),
                AcceptanceDatabase.password())) {
            c.setAutoCommit(true);
            boolean created = !tableExists(c, "OPS_FMIG_RUN");
            if (created) {
                FlowableMigrationSchema.create(c, d);
            }
            try {
                List<DefinitionMapping> mappings = List.of(new DefinitionMapping("order", 1, "order", 1, Map.of("old", "new")));
                store.createRun(c, new Run(runA, "acme", system, null, "Europe/Berlin", RunState.DRY_RUN, mappings,
                        2, 0, 0, 0, 0, 0, 1), "it");
                store.createRun(c, new Run(runB, "acme", system, "t1", "Asia/Kolkata", RunState.DRY_RUN, mappings,
                        1, 0, 0, 0, 0, 0, 1), "it");
                Run a = store.run(c, runA).orElseThrow();
                assertEquals(mappings, a.mappings());
                assertNull(a.sourceTenant());
                assertEquals(1, a.version());

                assertTrue(store.transition(c, runA, RunState.DRY_RUN, 1, RunState.FROZEN));
                assertFalse(store.transition(c, runA, RunState.DRY_RUN, 1, RunState.FROZEN), "stale version");
                assertEquals(RunState.FROZEN, store.run(c, runA).orElseThrow().state());

                store.insertItems(c, runA, List.of(plan("p1"), plan("p2")), "acme");
                store.insertItems(c, runB, List.of(plan("p1")), "acme");
                List<Item> planned = store.items(c, runA, List.of(ItemStatus.PLANNED));
                assertEquals(List.of("p1", "p2"), planned.stream().map(Item::sourceInstanceId).toList());
                assertEquals(plan("p1"), planned.get(0).plan(), "plan JSON survives the {text} column exactly");

                assertTrue(store.begin(c, runA, "p1", ItemStatus.PLANNED));
                assertFalse(store.begin(c, runA, "p1", ItemStatus.PLANNED), "begin is conditional on the status");
                assertEquals("(being applied in run " + runA + ")",
                        store.appliedElsewhere(c, system, "p1", runB).orElseThrow(), "an APPLYING item without target blocks");
                store.finish(c, runA, "p1", ItemStatus.APPLIED, "target-1", null);
                store.finish(c, runA, "p1", ItemStatus.MATCHED, null, null);
                Item p1 = store.items(c, runA, List.of(ItemStatus.MATCHED)).get(0);
                assertEquals("target-1", p1.targetInstanceId(), "a null target keeps the stored one");
                assertEquals(1, p1.attempts());
                assertEquals("target-1", store.appliedElsewhere(c, system, "p1", runB).orElseThrow());
                assertTrue(store.appliedElsewhere(c, system, "p1", runA).isEmpty(), "the own run does not count");
                assertTrue(store.appliedElsewhere(c, "other-" + suffix, "p1", runB).isEmpty(), "another source system does not count");

                store.finish(c, runA, "p2", ItemStatus.FAILED, null, "x".repeat(5000));
                store.recount(c, runA);
                Run counted = store.run(c, runA).orElseThrow();
                assertEquals(2, counted.planned());
                assertEquals(1, counted.applied());
                assertEquals(1, counted.matched());
                assertEquals(1, counted.failed());
                assertEquals(5000, store.items(c, runA, List.of(ItemStatus.FAILED)).get(0).error().length());

                store.report(c, runA, "{\"runId\":\"" + runA + "\"}");
                store.finish(c, runA, "p1", ItemStatus.ROLLED_BACK, null, null);
                assertTrue(store.appliedElsewhere(c, system, "p1", runB).isEmpty(), "a rolled-back item frees the source instance");
            } finally {
                Jdbc.update(c, "DELETE FROM OPS_FMIG_ITEM WHERE run_id IN (?,?)", runA, runB);
                Jdbc.update(c, "DELETE FROM OPS_FMIG_RUN WHERE run_id IN (?,?)", runA, runB);
                if (created) {
                    Jdbc.update(c, "DROP TABLE OPS_FMIG_ITEM");
                    Jdbc.update(c, "DROP TABLE OPS_FMIG_RUN");
                }
            }
        }
    }
}
