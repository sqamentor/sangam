// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Owner decision 303: a parked ordered delivery writes INCIDENT_OPENED (incidentType DELIVERY_PARKED) exactly once, in
 * the PARKED transaction, on the delivery's tenant and root chain, with errorClass derived as decision 302 says
 * (retryable for a retryable database conflict, else unknown); a retried attempt, a fenced-out update and an unordered
 * SKIPPED delivery write none. Written NOT_RUN.
 */
class RelayParkedIncidentTest {

    private static final String TENANT = "RJ";
    private static final Relay.Config TWO_ATTEMPTS =
            new Relay.Config("n1", 100, Duration.ofSeconds(30), 2, Duration.ZERO, Duration.ZERO);

    private Transactions tx;
    private final Outbox outbox = new Outbox(Dialect.POSTGRESQL);
    private final AuditChain audit = new AuditChain(Dialect.POSTGRESQL);

    @BeforeEach
    void schema() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
    }

    private void append(String root, Outbox.Destination destination) {
        tx.inTransaction(c -> outbox.append(c, TENANT, root, "E", "{\"n\":1}", List.of(destination)));
    }

    private Relay failingRelay(Exception failure) {
        return new Relay(Dialect.POSTGRESQL, tx, (d, e) -> {
            throw failure;
        }, TWO_ATTEMPTS, audit);
    }

    private List<String> incidents(String root) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p"
                        + " ON p.event_id = e.event_id WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=? ORDER BY e.seq",
                rs -> rs.getString(1), TENANT, root, Relay.INCIDENT_OPENED));
    }

    private List<String> eventTypes(String root) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?"
                + " ORDER BY seq", rs -> rs.getString(1), TENANT, root));
    }

    @Test
    void aParkedDeliveryOpensOneIncidentAndARetryNone() {
        append("case-1", Outbox.Destination.ordered("feed"));
        Relay relay = failingRelay(new IllegalStateException("feed down"));

        relay.runOnce();
        assertTrue(incidents("case-1").isEmpty(), "the first failure is retried: no incident");

        relay.runOnce();
        List<String> opened = incidents("case-1");
        assertEquals(1, opened.size(), "exactly one incident when the delivery parks");
        assertEquals(List.of("OUTBOX_DELIVERY_PARKED", "INCIDENT_OPENED"), eventTypes("case-1"),
                "written in the PARKED transaction, right after the parked event");
        String p = opened.get(0);
        assertTrue(p.contains("\"incidentType\":\"DELIVERY_PARKED\""), p);
        assertTrue(p.contains("\"errorClass\":\"unknown\""), p);
        assertTrue(p.contains("\"message\":\"feed down\""), p);
        assertTrue(p.contains("\"destination\":\"feed\""), p);
        assertTrue(p.contains("\"configuration\":\"feed\""), p);
        assertTrue(p.contains("\"resolvedScope\":\"" + TENANT + "\""), p);
        assertTrue(p.contains("\"attempts\":2"), p);
        assertTrue(p.contains("\"processInstanceId\":null"), p);
        assertTrue(p.contains("\"incidentId\":\"delivery:"), p);

        relay.runOnce();
        relay.runOnce();
        assertEquals(1, incidents("case-1").size(), "a parked delivery is not claimed again: still one incident");
    }

    @Test
    void aRetryableDatabaseConflictIsClassifiedRetryable() {
        append("case-2", Outbox.Destination.ordered("projector"));
        Relay relay = failingRelay(new IllegalStateException("projection failed",
                new SQLException("deadlock detected", "40P01")));
        relay.runOnce();
        relay.runOnce();
        List<String> opened = incidents("case-2");
        assertEquals(1, opened.size());
        assertTrue(opened.get(0).contains("\"errorClass\":\"retryable\""), opened.get(0));
        assertEquals(Relay.ERROR_CLASS_RETRYABLE,
                Relay.errorClass(new SQLException("deadlock detected", "40P01")));
        assertEquals(Relay.ERROR_CLASS_UNKNOWN, Relay.errorClass(null), "no known cause, for example a poisoned delivery");
        assertEquals(Relay.ERROR_CLASS_UNKNOWN, Relay.errorClass(new java.io.IOException("timeout")));
    }

    @Test
    void anUnorderedSkippedDeliveryOpensNoIncident() {
        append("case-3", Outbox.Destination.unordered("webhook"));
        Relay relay = failingRelay(new IllegalStateException("gone"));
        relay.runOnce();
        relay.runOnce();
        assertTrue(incidents("case-3").isEmpty());
        assertEquals(List.of("OUTBOX_DELIVERY_SKIPPED"), eventTypes("case-3"));
    }

    @Test
    void aFencedOutUpdateOpensNoIncidentAndThePoisonPathIsUnknown() {
        append("case-4", Outbox.Destination.ordered("feed"));
        Relay relay = failingRelay(new IllegalStateException("unused"));
        Relay.Claimed cl = relay.claim().get(0);
        Relay.Claimed stale = new Relay.Claimed(cl.event(), cl.destination(), cl.epoch() - 1, 1, true);
        assertFalse(relay.markFailed(stale, "late node", new IllegalStateException("late")), "fenced out");
        assertTrue(incidents("case-4").isEmpty());

        // the poison path: presented on its last attempt, no cause object
        Relay.Claimed last = new Relay.Claimed(cl.event(), cl.destination(), cl.epoch(), 1, true);
        assertTrue(relay.markFailed(last, "POISON: rejected"));
        List<String> opened = incidents("case-4");
        assertEquals(1, opened.size());
        assertTrue(opened.get(0).contains("\"errorClass\":\"unknown\""), opened.get(0));
        assertTrue(opened.get(0).contains("\"message\":\"POISON: rejected\""), opened.get(0));
    }
}
