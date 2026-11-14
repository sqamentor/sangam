// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.inbox.EventDefinitionPolicy;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * Pack operations on in-memory H2 (PostgreSQL mode) for the decision 289 and 290 tests: the core and integration
 * tables, {@code PACK_OPERATION}, a real ledger, reconciliation queue, outbox and inbox, and the gateway (no engine, no
 * connector: only its re-dispatch is used).
 */
final class PackH2Fixture implements AutoCloseable {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "T1";
    static final String ROOT = "pack-unknown-root";

    final String url;
    private final Connection keepAlive;
    final PackOperationStore store = new PackOperationStore(D);
    final ReconciliationQueue queue = new ReconciliationQueue(D, null);
    final OperationLedger ledger = new OperationLedger(D, queue);
    final IntegrationConnectorGateway gateway;
    final PackMessages messages;

    PackH2Fixture(String name) throws SQLException {
        url = "jdbc:h2:mem:" + name + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        for (String sql : PackOperationStore.DDL) {
            String s = D.expand(sql);
            Jdbc.update(keepAlive, s.startsWith("CREATE TABLE") ? s + D.createTableSuffix() : s);
        }
        Transactions tx = tx();
        gateway = new IntegrationConnectorGateway(tx, null, store, null, null, null, queue,
                new OutboxWriter(D, (t, e) -> List.of()), BackpressureGate.open(), null);
        InboxService inbox = new InboxService(D, tx, new InboxService.Settings("node-1", 10, Duration.ofMinutes(1),
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(30)), null, new EventDefinitionPolicy.Registry(),
                (c, m) -> null, BackpressureGate.open(), null);
        messages = new PackMessages(inbox);
    }

    Transactions tx() {
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

    /**
     * A dispatched operation in {@code status} with its ledger intent: no process instance when {@code processInstanceId}
     * is null (as a REST-backed data-object create).
     */
    PackOperationStore.Row operation(String processInstanceId, String businessKey, PackOperationStore.Status status) {
        String op = UUID.randomUUID().toString();
        UUID ledgerOp = UUID.fromString(op);
        tx().inTransaction(c -> {
            store.insert(c, new PackOperationStore.Row(op, TENANT, "records", "create", ledgerOp, ROOT, processInstanceId,
                    businessKey, "hash-1", "{\"record\":{\"name\":\"a\"}}", PackOperationStore.Status.PENDING, null, null, null,
                    false, 1, false));
            store.markDispatched(c, TENANT, op);
            ledger.recordIntent(c, ledgerOp, new OperationLedger.OperationRef(TENANT, ROOT, "pack:" + op, 0),
                    CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, null, "attempt-1", 0L);
            if (status != PackOperationStore.Status.PENDING) {
                store.settle(c, TENANT, op, status, status == PackOperationStore.Status.UNKNOWN ? "OUTCOME_UNKNOWN" : null,
                        null, null);
            }
            return null;
        });
        return row(op);
    }

    /** Opens the UNKNOWN_OUTCOME item of an operation, as the connector invoker does when the outcome is unknown. */
    ReconciliationQueue.Item unknownItem(PackOperationStore.Row r) {
        return tx().inTransaction(c -> {
            long id = ledger.markUnknown(c, r.ledgerOperationId(), 0L, "outcome unknown");
            return queue.get(c, id).orElseThrow();
        });
    }

    PackOperationStore.Row row(String operationId) {
        return tx().inTransaction(c -> store.find(c, TENANT, operationId)).orElseThrow();
    }

    /** Dispatch events queued in the outbox for {@code operationId}. */
    int dispatches(String operationId) {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT payload FROM OUTBOX WHERE event_type=?",
                rs -> rs.getString(1), IntegrationConnectorGateway.DISPATCH_EVENT)).stream()
                .filter(p -> p != null && p.contains(operationId)).toList().size();
    }

    int operationRows() {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT operation_id FROM PACK_OPERATION", rs -> rs.getString(1))).size();
    }

    @Override
    public void close() throws SQLException {
        keepAlive.close();
    }
}
