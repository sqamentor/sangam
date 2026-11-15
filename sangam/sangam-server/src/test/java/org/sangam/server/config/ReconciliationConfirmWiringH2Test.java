// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
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
import org.sangam.integration.reconciliation.ReconciliationEngine;
import org.sangam.integration.reconciliation.ReconciliationTaskPort;
import org.sangam.integration.schema.IntegrationSchema;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The server's reconciliation engine bean ({@link IntegrationWiring#sangamReconciliationEngine}) is wired with the
 * direct-audit connector invoker ({@code confirmations}), so a reconciliation {@code CONFIRM_SUCCESS}, by the engine's
 * lookup or by an officer, writes the connector's catalogued events (here {@code DOCUMENT_PUSHED}) exactly once.
 * Before the wiring the bean resolved the item and wrote no such event (the unwired case of
 * sangam-integration's {@code ReconciliationConfirmEventsH2Test}).
 */
class ReconciliationConfirmWiringH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";

    private final JdbcDataSource ds = new JdbcDataSource();
    private final Connection keepAlive;
    private final Transactions tx;
    private final AuditChain chain = new AuditChain(D);
    private final ReconciliationQueue queue = new ReconciliationQueue(D, chain);
    private final OperationLedger ledger = new OperationLedger(D, queue);
    private final ConnectorCallJournal journal = new ConnectorCallJournal(D);
    private final Push push = new Push();
    private final ConnectorRegistry registry = new ConnectorRegistry().register(push);
    private final ConnectorInvoker invoker;
    private final ReconciliationEngine engine;

    ReconciliationConfirmWiringH2Test() throws Exception {
        ds.setURL("jdbc:h2:mem:confirmwiring" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        keepAlive = ds.getConnection();
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        tx = Transactions.of(ds);
        IntegrationWiring wiring = new IntegrationWiring();
        // the server's own invoker bean: direct audit
        invoker = wiring.sangamConnectorInvoker(ds, ledger, journal, registry, chain);
        TxSupport support = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), D);
        RestProperties rest = new RestProperties();
        rest.setNode("node-1");
        engine = wiring.sangamReconciliationEngine(support, ds, queue, ledger, journal, registry, null,
                new StaticListableBeanFactory().getBeanProvider(ReconciliationTaskPort.class), rest, invoker);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    /** A push whose answer is lost and whose lookup answers what the test sets (class b). */
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
                    : List.of(new AuditedConnector.Event("DOCUMENT_PUSHED", Map.of("uri", output.get("uri"))));
        }
    }

    private ReconciliationQueue.Item unknownPush(UUID op) {
        ConnectorInvoker.Outcome o = invoker.invoke(new ConnectorInvoker.Invocation(TENANT, "case-" + op, "activity-" + op, 0,
                null, "case:1", "push", "push", Map.of("docType", "CERTI"), "attempt-1", 0L, Instant.now().plusSeconds(30), op));
        ConnectorInvoker.Unknown u = assertInstanceOf(ConnectorInvoker.Unknown.class, o);
        return tx.inTransaction(c -> queue.get(c, u.reconciliationItemId())).orElseThrow();
    }

    private long pushed() {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=?",
                rs -> rs.getString(1), TENANT)).stream().filter("DOCUMENT_PUSHED"::equals).count();
    }

    @Test
    void theServersInvokerBeanKeepsDirectAudit() {
        assertFalse(invoker.defersAudit(), "the events are written in the resolving transaction, not deferred");
    }

    @Test
    void aConfirmationThroughTheServerBeanWritesTheConnectorsEventOnce() {
        UUID op = UUID.randomUUID();
        ReconciliationQueue.Item item = unknownPush(op);
        assertEquals(0, pushed(), "an unknown outcome is not a push");

        engine.resolveManually(item.id(), ReconciliationQueue.Action.CONFIRM_SUCCESS, "user:officer",
                Map.of("uri", "in.gov.dept-CERTI-2", "providerRef", "txn-2"), "seen in the issuer portal");
        assertEquals(1, pushed(), "the server's reconciliation engine writes the connector's catalogued event");
        assertEquals(OperationLedger.State.SUCCEEDED, tx.inTransaction(c -> ledger.find(c, op)).orElseThrow().state());
        assertTrue(tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=?",
                rs -> rs.getString(1), TENANT)).contains("RECONCILIATION_ACTION"));

        assertThrows(RuntimeException.class, () -> engine.resolveManually(item.id(),
                ReconciliationQueue.Action.CONFIRM_SUCCESS, "user:officer", Map.of("uri", "in.gov.dept-CERTI-2"), "again"));
        assertEquals(1, pushed(), "a second resolution is refused and writes nothing");
    }

    @Test
    void aFailureResolutionWritesNoConnectorEvent() {
        ReconciliationQueue.Item item = unknownPush(UUID.randomUUID());
        engine.resolveManually(item.id(), ReconciliationQueue.Action.MARK_FAILED, "user:officer",
                Map.of("uri", "in.gov.dept-CERTI-3"), "rejected by the issuer");
        assertEquals(0, pushed());
    }
}
