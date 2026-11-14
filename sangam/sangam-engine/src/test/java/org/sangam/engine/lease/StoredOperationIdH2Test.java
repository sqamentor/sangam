// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.SangamEngineConfigurer;
import org.sangam.engine.SangamEngineSettings;

/**
 * SG-15 support (W2-L): {@link SangamLeaseTable#storedOperationIdOf} reads {@code SANGAM_LEASE.operation_id} with plain
 * SQL on the caller's connection. No engine is built here at all, which proves it needs no engine command, query or
 * entity cache.
 */
class StoredOperationIdH2Test {

    private static DataSource productSchema() {
        JdbcDataSource raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:sg15" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setApplyEnvironment(false);
        return new SangamEngineConfigurer(s).prepare(raw); // product changelog: SANGAM_LEASE exists
    }

    private static void lease(Connection c, String workId, String operationId) {
        Jdbc.update(c, "INSERT INTO SANGAM_LEASE (work_id, tenant_id, topic, root_id, operation_id, lock_version, "
                + "attempt_id, attempt_no, worker_id, expires_at_ms, state, cancel_requested, capability_class, "
                + "exclusive_scope, updated_at_ms) VALUES (?,?,?,?,?,1,?,1,?,?,?,0,?,?,?)",
                workId, "t1", "topic", "root-1", operationId, "att-1", "w1", 1_000L, "LOCKED", "c", null, 1L);
    }

    @Test
    void theStoredIdIsReadWithoutAnEngine() throws Exception {
        DataSource ds = productSchema();
        UUID op = UUID.randomUUID();
        try (Connection c = ds.getConnection()) {
            lease(c, "work-1", op.toString());
            lease(c, "work-2", null);
            assertEquals(Optional.of(op), SangamLeaseTable.storedOperationIdOf(c, "work-1"));
            assertEquals(Optional.empty(), SangamLeaseTable.storedOperationIdOf(c, "work-2"), "NULL column: empty, no error");
            assertEquals(Optional.empty(), SangamLeaseTable.storedOperationIdOf(c, "missing"), "no lease row: empty");
            Jdbc.update(c, "UPDATE SANGAM_LEASE SET operation_id = ? WHERE work_id = ?", "not-a-uuid", "work-2");
            assertThrows(IllegalArgumentException.class, () -> SangamLeaseTable.storedOperationIdOf(c, "work-2"));
        }
    }
}
