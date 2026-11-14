// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.AuditedConnector;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * Root cause of the missing DOCUMENT_PUSHED: an operation confirmed by reconciliation ({@code CONFIRM_SUCCESS}, by the
 * engine's lookup or by an officer) writes its connector's catalogued events through the same path as a confirmation
 * by the invoker, in the resolving transaction, exactly once; a failure or a second resolution writes none.
 */
class ReconciliationConfirmEventsH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";
    private static final String ROOT = "case-1";

    private final String url = "jdbc:h2:mem:confirmevents" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final Connection keepAlive;
    private final AuditChain chain = new AuditChain(D);
    private final ReconciliationQueue queue = new ReconciliationQueue(D, chain);
    private final OperationLedger ledger = new OperationLedger(D, queue);
    private final ConnectorCallJournal journal = new ConnectorCallJournal(D);
    private final Push push = new Push();
    private final ConnectorRegistry registry = new ConnectorRegistry().register(push);
    private final ConnectorInvoker invoker;

    ReconciliationConfirmEventsH2Test() throws SQLException {
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        invoker = new ConnectorInvoker(tx(), ledger, journal, registry, chain);
    }

    @AfterEach
    void close() throws SQLException {
        keepAlive.close();
    }

    private Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /** A push whose answer is lost and whose lookup is unavailable until told otherwise (class b). */
    private static final class Push implements SangamConnector, AuditedConnector {
        volatile LookupResult lookup = new LookupResult.Unavailable("status service down");

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("push", "push", CapabilityClass.B_LOOKUP_ONLY, List.of("push"), ConnectorProfile.SIMULATOR);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            return ConnectorResult.OutcomeUnknown.of("answer lost");
        }

        @Override
        public LookupResult lookup(ConnectorRequest request) {
            return lookup;
        }

        @Override
        public List<AuditedConnector.Event> auditEvents(String operation, Map<String, Object> input, Map<String, Object> output) {
            return output.get("uri") == null ? List.of()
                    : List.of(new AuditedConnector.Event("DOCUMENT_PUSHED", Map.of("uri", output.get("uri"), "docType",
                    String.valueOf(input.get("docType")))));
        }
    }

    private ReconciliationEngine engine(boolean wired) {
        ReconciliationEngine e = new ReconciliationEngine(D, tx(), queue, ledger, journal, registry, (item, connectorId, reason) -> null,
                null, ReconciliationEngine.Settings.defaults("node-1"));
        return wired ? e.confirmations(invoker) : e;
    }

    /** A push whose outcome is unknown, with its open reconciliation item. */
    private ReconciliationQueue.Item unknownPush(UUID op) {
        ConnectorInvoker.Outcome o = invoker.invoke(new ConnectorInvoker.Invocation(TENANT, ROOT, "activity-" + op, 0, null,
                "case:1", "push", "push", Map.of("docType", "CERTI"), "attempt-1", 0L, Instant.now().plusSeconds(30), op));
        ConnectorInvoker.Unknown u = assertInstanceOf(ConnectorInvoker.Unknown.class, o);
        return tx().inTransaction(c -> queue.get(c, u.reconciliationItemId())).orElseThrow();
    }

    private List<String> events() {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? ORDER BY seq",
                rs -> rs.getString(1), TENANT));
    }

    private long pushed() {
        return events().stream().filter("DOCUMENT_PUSHED"::equals).count();
    }

    private List<String> pushedPayloads() {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                + " WHERE e.event_type='DOCUMENT_PUSHED'", rs -> rs.getString(1)));
    }

    @Test
    void anAutomaticConfirmationWritesThePushOnceOnTheRootChain() {
        UUID op = UUID.randomUUID();
        ReconciliationQueue.Item item = unknownPush(op);
        assertEquals(0, pushed(), "an unknown outcome is not a push");
        push.lookup = LookupResult.Found.success(Map.of("uri", "in.gov.dept-CERTI-1"), "txn-1");

        ReconciliationEngine engine = engine(true);
        assertEquals(1, engine.process(List.of(item)).resolved());
        assertEquals(1, pushed(), "the confirmation writes the connector's event");
        assertTrue(pushedPayloads().get(0).contains(op.toString()), "with the operationId, as the invoker writes it");
        assertTrue(pushedPayloads().get(0).contains("in.gov.dept-CERTI-1"));
        assertEquals(OperationLedger.State.SUCCEEDED, tx().inTransaction(c -> ledger.find(c, op)).orElseThrow().state());
        List<String> types = events();
        assertTrue(types.indexOf("RECONCILIATION_ACTION") < types.indexOf("DOCUMENT_PUSHED"), "in the resolving transaction");

        engine.process(List.of(item));
        assertEquals(1, pushed(), "a resolved item confirms nothing again");
    }

    @Test
    void anOfficersConfirmationWritesThePushOnce() {
        UUID op = UUID.randomUUID();
        ReconciliationQueue.Item item = unknownPush(op);
        ReconciliationEngine engine = engine(true);
        engine.resolveManually(item.id(), ReconciliationQueue.Action.CONFIRM_SUCCESS, "user:officer",
                Map.of("uri", "in.gov.dept-CERTI-2", "providerRef", "txn-2"), "seen in the issuer portal");
        assertEquals(1, pushed());
        assertThrows(RuntimeException.class, () -> engine.resolveManually(item.id(), ReconciliationQueue.Action.CONFIRM_SUCCESS,
                "user:officer", Map.of("uri", "in.gov.dept-CERTI-2"), "again"));
        assertEquals(1, pushed(), "a second resolution is refused and writes nothing");
    }

    @Test
    void aFailureConfirmsNoPush() {
        ReconciliationQueue.Item item = unknownPush(UUID.randomUUID());
        push.lookup = new LookupResult.Found(false, Map.of("uri", "in.gov.dept-CERTI-3"), null, "REJECTED");
        engine(true).process(List.of(item));
        assertEquals(0, pushed());
        assertTrue(events().contains("RECONCILIATION_ACTION"));
    }

    @Test
    void theInvokersOwnConfirmationIsNotRepeated() {
        UUID op = UUID.randomUUID();
        ReconciliationQueue.Item item = unknownPush(op);
        push.lookup = LookupResult.Found.success(Map.of("uri", "in.gov.dept-CERTI-4"), "txn-4");
        // a late outcome settled the ledger (with its event) on another path: the engine then finds the ledger SUCCEEDED
        // and only closes the item
        tx().inTransaction(c -> {
            ConnectorCallJournal.Call call = journal.find(c, op).orElseThrow();
            invoker.writeConfirmedEvents(c, call, Map.of("uri", "in.gov.dept-CERTI-4"));
            Jdbc.update(c, "UPDATE OPERATION_LEDGER SET state='SUCCEEDED', outcome=? WHERE operation_id=?",
                    ConnectorInvoker.successJson(Map.of("uri", "in.gov.dept-CERTI-4"), "txn-4", false), op);
            return null;
        });
        assertEquals(1, pushed());
        engine(true).process(List.of(item));
        assertEquals(1, pushed(), "the ledger was already SUCCEEDED: no second event");
    }

    @Test
    void unwiredConfirmationsWriteNothingButStillResolve() {
        ReconciliationQueue.Item item = unknownPush(UUID.randomUUID());
        push.lookup = LookupResult.Found.success(Map.of("uri", "in.gov.dept-CERTI-5"), "txn-5");
        assertEquals(1, engine(false).process(List.of(item)).resolved());
        assertFalse(events().contains("DOCUMENT_PUSHED"));
    }
}
