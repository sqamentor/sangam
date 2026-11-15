// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.integration.packs.PackOperationStore;
import org.sangam.integration.packs.PackOperationStore.Status;
import org.sangam.integration.packs.PackSettlementListener;

/**
 * Owner decision 289: DATA_OBJECT_RECORD_SETTLED is written exactly once per REST-backed create that reaches COMPLETED
 * or FAILED, on the operation's tenant and chain data:(key), with the payload agreed with W4-H; nothing for UNKNOWN,
 * RETRY_REQUESTED or a re-dispatch (decision 290). Runs through the real PackOperationStore. Written NOT_RUN.
 */
class DataObjectSettlementAuditH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";

    private Connection c;
    private PackOperationStore store;

    @BeforeEach
    void open() throws SQLException {
        c = DriverManager.getConnection("jdbc:h2:mem:dosettle" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        Schema.create(c, D);
        for (String sql : PackOperationStore.DDL) {
            String s = D.expand(sql);
            Jdbc.update(c, s.startsWith("CREATE TABLE") ? s + D.createTableSuffix() : s);
        }
        store = new PackOperationStore(D);
        store.onSettled(DataObjectSettlementAudit.KIND, new DataObjectSettlementAudit(new AuditChain(D)));
    }

    @AfterEach
    void close() throws SQLException {
        c.close();
    }

    private String operation(String businessKey) {
        String id = UUID.randomUUID().toString();
        assertTrue(store.insert(c, new PackOperationStore.Row(id, TENANT, "rest-records", "create", null, "root-" + id,
                null, businessKey, "h", "{}", Status.PENDING, null, null, null, false, 1, false)));
        return id;
    }

    private boolean settle(String id, Status status, String reason, String settledBy, Map<String, Object> output) {
        return store.settle(c, TENANT, id, status, reason, null, null, output, settledBy);
    }

    private List<String> settledPayloads(String chain) {
        return Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                        + " WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=? ORDER BY e.seq",
                rs -> rs.getString(1), TENANT, chain, DataObjectSettlementAudit.EVENT);
    }

    private int settledEvents() {
        return Jdbc.query(c, "SELECT event_id FROM AUDIT_EVENT WHERE event_type=?", rs -> rs.getString(1),
                DataObjectSettlementAudit.EVENT).size();
    }

    @Test
    void aCompletedCreateWritesOneEventWithItsRecord() {
        String id = operation("data-object:leave");
        assertTrue(settle(id, Status.COMPLETED, null, null, Map.of("record", Map.of("id", "rec-9"))));
        assertFalse(settle(id, Status.COMPLETED, null, null, Map.of("record", Map.of("id", "rec-9"))));
        assertFalse(settle(id, Status.FAILED, "LATE", null, Map.of()));

        List<String> events = settledPayloads("data:leave");
        assertEquals(1, events.size(), "exactly once per operation");
        String p = events.get(0);
        assertTrue(p.contains("\"dataObject\":\"leave\""), p);
        assertTrue(p.contains("\"operationId\":\"" + id + "\""), p);
        assertTrue(p.contains("\"status\":\"COMPLETED\""), p);
        assertTrue(p.contains("\"recordId\":\"rec-9\""), p);
        assertTrue(p.contains("\"failureCode\":null"), p);
        assertTrue(p.contains("\"settledBy\":\"" + PackSettlementListener.SYSTEM_ACTOR + "\""), p);
        assertTrue(p.contains("\"actor\":\"" + PackSettlementListener.SYSTEM_ACTOR + "\""), p);
    }

    @Test
    void aFailedCreateWritesOneEventWithItsCodeAndActor() {
        String id = operation("data-object:leave");
        assertTrue(settle(id, Status.FAILED, "DOWNSTREAM_REJECTED", "user:officer", Map.of("message", "no")));
        assertFalse(settle(id, Status.COMPLETED, null, null, Map.of()));

        List<String> events = settledPayloads("data:leave");
        assertEquals(1, events.size());
        String p = events.get(0);
        assertTrue(p.contains("\"status\":\"FAILED\""), p);
        assertTrue(p.contains("\"failureCode\":\"DOWNSTREAM_REJECTED\""), p);
        assertTrue(p.contains("\"recordId\":null"), p);
        assertTrue(p.contains("\"settledBy\":\"user:officer\""), p);
        assertTrue(p.contains("\"actor\":\"user:officer\""), p);
    }

    @Test
    void aFailureWithoutCodeAndACancelAreReportedAsFailed() {
        String failed = operation("data-object:leave");
        assertTrue(settle(failed, Status.FAILED, null, null, Map.of()));
        String cancelled = operation("data-object:leave");
        assertTrue(settle(cancelled, Status.CANCELLED, null, null, Map.of()));
        String cancelledWithReason = operation("data-object:leave");
        assertTrue(settle(cancelledWithReason, Status.CANCELLED, "CANCELLED_BY_OFFICER", null, Map.of()));

        List<String> events = settledPayloads("data:leave");
        assertEquals(3, events.size());
        assertTrue(events.get(0).contains("\"failureCode\":\"" + DataObjectSettlementAudit.DOWNSTREAM_FAILED + "\""),
                events.get(0));
        assertTrue(events.get(1).contains("\"status\":\"FAILED\"") && events.get(1).contains("\"failureCode\":\"CANCELLED\""),
                events.get(1));
        assertTrue(events.get(2).contains("\"failureCode\":\"CANCELLED_BY_OFFICER\""), events.get(2));
    }

    @Test
    void retriesAndRedispatchesWriteNothing() {
        String id = operation("data-object:leave");
        assertTrue(settle(id, Status.UNKNOWN, "OUTCOME_UNKNOWN", null, null));
        assertTrue(settle(id, Status.RETRY_REQUESTED, "NOT_FOUND", null, null));
        store.redispatch(c, TENANT, id);
        assertEquals(0, settledEvents(), "UNKNOWN, RETRY_REQUESTED and a re-dispatch are not settlements");

        assertTrue(settle(id, Status.COMPLETED, null, null, Map.of("record", Map.of("id", "rec-1"))));
        assertEquals(1, settledEvents(), "the re-dispatched operation settles once");
    }

    @Test
    void otherKindsAreNotThisListeners() {
        String other = operation("invoice:42");
        assertTrue(settle(other, Status.COMPLETED, null, null, Map.of()));
        assertEquals(0, settledEvents());
        assertThrows(IllegalStateException.class, () -> DataObjectSettlementAudit.dataObjectKey("invoice:42"));
        assertThrows(IllegalStateException.class, () -> DataObjectSettlementAudit.dataObjectKey("data-object:"));
    }
}
