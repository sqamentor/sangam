// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * M-044: the PROCESS_TRACE side rows (DDL of the W3-F REQUEST 14:17:20Z until the changeset lands): only valid W3C
 * trace ids are stored, an instance keeps its starting trace, no row means no trace id.
 */
class ProcessTracesH2Test {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static Connection h2() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pt" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        Connection c = ds.getConnection();
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE PROCESS_TRACE (process_instance_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + " tenant_id VARCHAR(64), root_process_instance_id VARCHAR(64), trace_id CHAR(32) NOT NULL,"
                    + " created_at_ms BIGINT NOT NULL)");
        }
        return c;
    }

    @Test
    void onlyValidTraceIdsAreAcceptedAndNoneIsMadeUp() {
        assertEquals(TRACE, ProcessTraces.valid(TRACE));
        assertNull(ProcessTraces.valid(null));
        assertNull(ProcessTraces.valid("00000000000000000000000000000000"), "the W3C invalid id");
        assertNull(ProcessTraces.valid(TRACE.toUpperCase()), "lowercase only");
        assertNull(ProcessTraces.valid(TRACE.substring(1)));
        assertEquals(TRACE, ProcessTraces.traceIdOf(new EngineTracing.TraceContext("00-" + TRACE + "-00f067aa0ba902b7-01",
                null)));
        assertNull(ProcessTraces.traceIdOf(null));
    }

    @Test
    void anInstanceKeepsItsStartingTraceAndAbsentMeansEmpty() throws Exception {
        try (Connection c = h2()) {
            ProcessTraces.record(c, "pi-1", "t1", "pi-1", TRACE, 1L);
            ProcessTraces.record(c, "pi-1", "t1", "pi-1", "ffffffffffffffffffffffffffffffff", 2L);
            ProcessTraces.record(c, "pi-2", "t1", "pi-1", null, 3L);
            ProcessTraces.record(c, "pi-3", "t1", "pi-1", "not-a-trace", 4L);
            assertEquals(Optional.of(TRACE), ProcessTraces.read(c, "pi-1"), "the start is kept, never overwritten");
            assertTrue(ProcessTraces.read(c, "pi-2").isEmpty(), "no current trace: no row, no value");
            assertTrue(ProcessTraces.read(c, "pi-3").isEmpty());
            ProcessTraces.record(c, "pi-4", "t1", "pi-1", "0af7651916cd43dd8448eb211c80319c", 5L);
            assertEquals(2, ProcessTraces.deleteRoot(c, "pi-1"), "the root and its children");
            assertTrue(ProcessTraces.read(c, "pi-1").isEmpty());
        }
    }
}
