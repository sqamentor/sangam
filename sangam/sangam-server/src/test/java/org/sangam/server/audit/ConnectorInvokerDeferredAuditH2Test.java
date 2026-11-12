// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * SL-133 (M-003), at the invoker: a caller whose own transaction C1 holds the root's {@code ROOT_SEQ} lock (the
 * JavaDelegate in a job) calls the invoker, which writes the ledger in its own transactions on other connections.
 * The direct invoker appends audit there and waits for C1's lock (the self-deadlock, here bounded by a 1 s lock
 * timeout); the deferring invoker records pending rows atomically with the ledger, returns at once, and the drain
 * appends them after C1 commits.
 */
class ConnectorInvokerDeferredAuditH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t";

    static final class Echo implements SangamConnector {
        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("echo", "test", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, List.of("send"),
                    ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            return ConnectorResult.Success.of(Map.of("ok", true), "prov-" + request.operationId());
        }
    }

    record Fixture(JdbcDataSource ds, Transactions tx, AuditChain chain, ConnectorInvoker invoker) {
    }

    static Fixture fixture(long lockTimeoutMs) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT="
                + lockTimeoutMs);
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            IntegrationSchema.create(c, D);
            PendingAudit.create(c, D);
        }
        Transactions tx = Transactions.of(ds);
        AuditChain chain = new AuditChain(D);
        ConnectorRegistry registry = new ConnectorRegistry();
        registry.register(TENANT, new Echo());
        ConnectorInvoker invoker = new ConnectorInvoker(tx, new OperationLedger(D, new ReconciliationQueue(D, chain)),
                new ConnectorCallJournal(D), registry, chain);
        return new Fixture(ds, tx, chain, invoker);
    }

    static ConnectorInvoker.Invocation call(String root) {
        return new ConnectorInvoker.Invocation(TENANT, root, "act-1", 0, null, "bk-1", "echo", "send", Map.of("x", 1),
                "attempt-1", 0L, null, UUID.nameUUIDFromBytes(root.getBytes()));
    }

    static List<String> types(Transactions tx, String root) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? ORDER BY seq",
                rs -> rs.getString(1), TENANT, root));
    }

    @Test
    void theDirectInvokerWaitsForTheCallersOwnRootLock() throws Exception {
        Fixture f = fixture(1_000);
        try (Connection c1 = f.ds().getConnection()) {
            c1.setAutoCommit(false);
            f.chain().append(c1, TENANT, "root-a", "JOB_STEP", Map.of());   // C1 holds ROOT_SEQ(t, root-a)
            ExecutionException e = assertThrows(ExecutionException.class, () -> CompletableFuture
                    .supplyAsync(() -> f.invoker().invoke(call("root-a"))).get(9, TimeUnit.SECONDS));
            assertTrue(String.valueOf(e.getCause()).toLowerCase().contains("timeout")
                    || String.valueOf(e.getCause().getCause()).toLowerCase().contains("timeout"), "lock timeout: " + e.getCause());
            c1.rollback();
        }
    }

    @Test
    void theDeferringInvokerReturnsAtOnceAndTheDrainAppendsAfterTheHolderCommits() throws Exception {
        Fixture f = fixture(10_000);
        PendingAudit pending = new PendingAudit(f.tx(), D, f.chain());
        try (PendingAuditDrainer drainer = new PendingAuditDrainer(pending,
                new PendingAuditDrainer.Settings(1, Duration.ZERO, Duration.ZERO, 10))) {
            ConnectorInvoker deferring = f.invoker().deferringAudit(drainer);
            assertTrue(deferring.defersAudit());
            assertFalse(f.invoker().defersAudit());
            ConnectorInvoker.Outcome o;
            try (Connection c1 = f.ds().getConnection()) {
                c1.setAutoCommit(false);
                f.chain().append(c1, TENANT, "root-b", "JOB_STEP", Map.of());
                o = CompletableFuture.supplyAsync(() -> deferring.invoke(call("root-b"))).get(5, TimeUnit.SECONDS);
                assertInstanceOf(ConnectorInvoker.Completed.class, o);
                assertEquals("SUCCEEDED", f.tx().inTransaction(c -> Jdbc.one(c, "SELECT state FROM OPERATION_LEDGER"
                        + " WHERE operation_id=?", rs -> rs.getString(1), o.operationId().toString()).orElseThrow()),
                        "the ledger committed while C1 still holds the root lock");
                assertEquals(2, pending.count(TENANT, "root-b"), "dispatch and outcome recorded with the ledger");
                c1.commit();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
            while (pending.count(TENANT, "root-b") > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(List.of("JOB_STEP", "OPERATION_DISPATCHED", "OPERATION_OUTCOME"), types(f.tx(), "root-b"));
            assertTrue(f.tx().inTransaction(c -> f.chain().verify(c, TENANT, "root-b")).ok());
        }
    }

    @Test
    void withoutAHeldLockTheDirectInvokerStillAppendsInItsOwnTransaction() throws Exception {
        Fixture f = fixture(10_000);
        assertInstanceOf(ConnectorInvoker.Completed.class, f.invoker().invoke(call("root-c")));
        assertEquals(List.of("OPERATION_DISPATCHED", "OPERATION_OUTCOME"), types(f.tx(), "root-c"));
    }
}
