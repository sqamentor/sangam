// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Statement text of the bridge is constant, or built only from validated identifiers (code-scanning rule
 * {@code sangam-jdbc-sql-concat}): the PostgreSQL pack-lock timeout binds its value, the search-index readers carry
 * whole constant statements, the shared-profile export has one constant select, and a tenant-move plan cannot hold a
 * name that is not one plain identifier.
 */
class BridgeSqlTextTest {

    /** What a connection was asked to prepare, and what was bound. Any other JDBC call fails the test. */
    private static final class Recorded {
        final List<String> prepared = new ArrayList<>();
        final List<Object> bound = new ArrayList<>();
        boolean executed;

        Connection connection() {
            ResultSet rs = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "next" -> true;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException("ResultSet." + m.getName());
                    });
            PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> switch (m.getName()) {
                        case "setString" -> {
                            assertEquals(1, a[0]);
                            bound.add(a[1]);
                            yield null;
                        }
                        case "executeQuery" -> {
                            executed = true;
                            yield rs;
                        }
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException("PreparedStatement." + m.getName());
                    });
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> {
                        if (m.getName().equals("prepareStatement") && a.length == 1) {
                            prepared.add((String) a[0]);
                            return ps;
                        }
                        // createStatement included: the timeout must not be written into statement text
                        throw new UnsupportedOperationException("Connection." + m.getName());
                    });
        }
    }

    @Test
    void thePostgresLockTimeoutIsBoundNotConcatenated() {
        Recorded r = new Recorded();
        PackOperationTxLock.setLocalLockTimeout(r.connection(), Duration.ofMillis(2500));
        assertEquals(List.of("SELECT set_config('lock_timeout', ?, true)"), r.prepared);
        assertEquals(List.of("2500ms"), r.bound);
        assertTrue(r.executed);

        Recorded other = new Recorded();
        PackOperationTxLock.setLocalLockTimeout(other.connection(), Duration.ofSeconds(30));
        assertEquals(r.prepared, other.prepared, "one statement text for every wait");
        assertEquals(List.of("30000ms"), other.bound);
    }

    @Test
    void aFailureToSetTheTimeoutIsReported() {
        Connection broken = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> {
                    throw new java.sql.SQLException("no");
                });
        assertThrows(IllegalStateException.class, () -> PackOperationTxLock.setLocalLockTimeout(broken, Duration.ofSeconds(1)));
    }

    /** The statements equal the text the reader assembled at run time before (table, title column, body column). */
    @Test
    void theSearchIndexReadersCarryTheSameStatementsAsConstants() {
        Map<OpsBindings.EngineSource, String[]> former = Map.of(
                OpsBindings.EngineSource.TASK, new String[]{"ACT_RU_TASK", "NAME_", "DESCRIPTION_"},
                OpsBindings.EngineSource.HISTORIC_PROCESS_INSTANCE, new String[]{"ACT_HI_PROCINST", "BUSINESS_KEY_", "PROC_DEF_KEY_"},
                OpsBindings.EngineSource.PROCESS_DEFINITION, new String[]{"ACT_RE_PROCDEF", "NAME_", "KEY_"});
        assertEquals(former.keySet(), Set.of(OpsBindings.EngineSource.values()));
        former.forEach((source, f) -> {
            String select = "SELECT ID_, " + f[1] + ", " + f[2] + " FROM " + f[0] + " WHERE TENANT_ID_=?";
            assertEquals(f[0], source.table);
            assertEquals("SELECT COUNT(*) FROM " + f[0] + " WHERE TENANT_ID_=?", source.countSql);
            assertEquals(select + " ORDER BY ID_", source.firstPageSql);
            assertEquals(select + " AND ID_>?" + " ORDER BY ID_", source.nextPageSql);
        });
    }

    /** The readers keep their document types and their order (the index stores the type). */
    @Test
    void theSearchIndexReadersKeepTheirTypes() {
        assertEquals(List.of("TASK", "HISTORIC_PROCESS_INSTANCE", "PROCESS_DEFINITION"),
                OpsBindings.engineReaders(null).stream().map(org.sangam.ops.search.SearchIndexMaintenance.Reader::type).toList());
    }

    @Test
    void theSharedProfileSelectIsTheColumnListInOneConstant() {
        assertEquals("SELECT " + String.join(", ", CellTenantPlans.SHARED_PROFILE_COLUMNS)
                + " FROM CFG_PROFILE WHERE profile_id=? AND status='ACTIVE'", CellTenantPlans.SHARED_PROFILE_SELECT);
        assertEquals(List.of("profile_id", "version", "layer_id", "status", "effective_from", "effective_to",
                "content_hash", "document_json", "updated_at"), CellTenantPlans.SHARED_PROFILE_COLUMNS);
    }

    @Test
    void aTenantMovePlanHoldsOnlyPlainIdentifiers() {
        CellTenantPlans.Plan ok = plan("Audit_Event", List.of("event_id", "TENANT_ID_", "payload"), List.of("event_id"));
        assertEquals("Audit_Event", ok.table());
        assertEquals(List.of("event_id", "TENANT_ID_", "payload"), ok.columns());
        assertEquals(List.of("event_id"), ok.pk());
        assertThrows(UnsupportedOperationException.class, () -> ok.columns().add("x; DROP TABLE y"));

        for (String bad : List.of("t; DROP TABLE x", "A B", "x; NOTIFY y", "", "t\"", "t'", "t--", "a.b", "BIN$x==$0",
                "t".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> plan(bad, List.of("id"), List.of("id")), bad);
            assertThrows(IllegalArgumentException.class, () -> plan("T", List.of("id", bad), List.of("id")), bad);
            assertThrows(IllegalArgumentException.class, () -> plan("T", List.of("id"), List.of(bad)), bad);
        }
        assertFalse(ok.columns().isEmpty());
    }

    private static CellTenantPlans.Plan plan(String table, List<String> columns, List<String> pk) {
        return new CellTenantPlans.Plan(table, table.toUpperCase(java.util.Locale.ROOT), columns, Map.of(),
                "TENANT_ID_=?", 1, pk, Set.of(), Set.of());
    }
}
