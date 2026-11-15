// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.Json;
import org.sangam.packs.core.NotifyDelegate;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackAuditSink;
import org.sangam.packs.port.PackRuntime;

/**
 * Owner decision 313: {@code NOTIFICATION_SENT} is written once, by the integration result path, when the connector
 * call of a queued notification completes successfully; queueing alone writes nothing, a failed or cancelled operation
 * writes nothing, and a second settle, a re-dispatch or a late reconciliation never writes a second event.
 */
class NotificationSettlementAuditH2Test {

    private static final String CASE = "CASE-313";

    private final PackH2Fixture f;
    /** Events recorded with no connection (the pack's own, from the delegate). */
    private final List<PackAuditEvent> fromPack = new CopyOnWriteArrayList<>();
    /** Events recorded on a connection, by operation id (details; the count is read from the table). */
    private final Map<String, PackAuditEvent> onConnection = new HashMap<>();
    private boolean sinkFails;

    private final PackAuditSink sink = new PackAuditSink() {
        @Override
        public void record(PackAuditEvent e) {
            fromPack.add(e);
        }

        @Override
        public void record(Connection c, PackAuditEvent e) {
            if (sinkFails) {
                throw new IllegalStateException("audit unavailable");
            }
            Jdbc.update(c, "INSERT INTO TEST_AUDIT(type, tenant_id, business_key, operation_id) VALUES (?,?,?,?)",
                    e.type(), e.tenantId(), e.businessKey(), e.operationId());
            onConnection.put(e.operationId(), e);
        }
    };

    NotificationSettlementAuditH2Test() throws SQLException {
        f = new PackH2Fixture("notifysent");
        f.tx().inTransaction(c -> Jdbc.update(c, "CREATE TABLE TEST_AUDIT (type VARCHAR(64), tenant_id VARCHAR(64),"
                + " business_key VARCHAR(255), operation_id VARCHAR(64))"));
        f.store.onConnectorSettled(NotifyDelegate.CONNECTOR_PREFIX,
                new NotificationSettlementAudit(f.store, () -> sink, PackRuntime::clock));
    }

    @AfterEach
    void close() throws SQLException {
        PackRuntime.reset();
        f.close();
    }

    /** NOTIFICATION_SENT rows committed for {@code operationId}. */
    private int sent(String operationId) {
        return f.tx().inTransaction(c -> Jdbc.query(c, "SELECT type FROM TEST_AUDIT WHERE operation_id=? AND type=?",
                rs -> rs.getString(1), operationId, NotifyDelegate.SENT)).size();
    }

    private int allRows() {
        return f.tx().inTransaction(c -> Jdbc.query(c, "SELECT type FROM TEST_AUDIT", rs -> rs.getString(1))).size();
    }

    /** A queued (PENDING, dispatched) operation on {@code connector}, as the gateway stores a submitted notification. */
    private PackOperationStore.Row queued(String connector, String operation) {
        String op = UUID.randomUUID().toString();
        UUID ledgerOp = UUID.fromString(op);
        f.tx().inTransaction(c -> {
            f.store.insert(c, new PackOperationStore.Row(op, PackH2Fixture.TENANT, connector, operation, ledgerOp,
                    PackH2Fixture.ROOT, "pi-1", CASE, "hash-1", "{\"template\":\"t1\",\"recipient\":\"citizen-1\"}",
                    PackOperationStore.Status.PENDING, null, null, null, false, 1, false));
            f.store.markDispatched(c, PackH2Fixture.TENANT, op);
            f.ledger.recordIntent(c, ledgerOp, new OperationLedger.OperationRef(PackH2Fixture.TENANT, PackH2Fixture.ROOT,
                    "pack:" + op, 0), CapabilityClass.B_LOOKUP_ONLY, null, "attempt-1", 0L);
            return null;
        });
        return f.row(op);
    }

    private boolean settle(PackOperationStore.Row r, PackOperationStore.Status status, String reason, String ref) {
        return f.tx().inTransaction(c -> f.store.settle(c, PackH2Fixture.TENANT, r.operationId(), status, reason, ref,
                Json.write(Map.of())));
    }

    @Test
    void aQueuedNotificationThatCompletesIsSentExactlyOnceAndASecondSettleWritesNothing() {
        PackOperationStore.Row r = queued("notify.email", "send");
        assertEquals(0, sent(r.operationId()), "queueing alone writes nothing");

        assertTrue(settle(r, PackOperationStore.Status.COMPLETED, null, "smtp-77"));
        assertEquals(1, sent(r.operationId()));
        PackAuditEvent e = onConnection.get(r.operationId());
        assertEquals(NotifyDelegate.SENT, e.type());
        assertEquals(PackH2Fixture.TENANT, e.tenantId());
        assertEquals(CASE, e.businessKey(), "on the case's own timeline");
        assertEquals("pi-1", e.processInstanceId());
        assertEquals(r.operationId(), e.operationId());
        assertEquals("email", e.details().get("channel"));
        assertEquals("t1", e.details().get("template"));
        assertEquals("citizen-1", e.details().get("recipient"));
        assertEquals(r.operationId(), e.details().get("operationId"));
        assertEquals("smtp-77", e.details().get("externalRef"));
        assertEquals(PackSettlementListener.SYSTEM_ACTOR, e.details().get("settledBy"));

        assertFalse(settle(r, PackOperationStore.Status.COMPLETED, null, "smtp-78"), "a final row is never settled again");
        assertFalse(settle(r, PackOperationStore.Status.FAILED, "LATE", null));
        assertEquals(1, sent(r.operationId()), "still exactly one");
        assertEquals(1, allRows());
    }

    @Test
    void aFailedOrCancelledNotificationIsNeverSent() {
        PackOperationStore.Row failed = queued("notify.sms", "send");
        assertTrue(settle(failed, PackOperationStore.Status.FAILED, "TEMPLATE_NOT_REGISTERED", null));
        assertFalse(settle(failed, PackOperationStore.Status.COMPLETED, null, "late"), "a failure is final");
        PackOperationStore.Row cancelled = queued("notify.sms", "send");
        assertTrue(settle(cancelled, PackOperationStore.Status.CANCELLED, "CANCELLED", null));
        assertEquals(0, allRows(), "no NOTIFICATION_SENT for a failed or cancelled settlement");
    }

    @Test
    void anUnknownOutcomeWritesNothingUntilItIsConfirmedAndARedispatchIsStillOneEvent() {
        PackOperationStore.Row unknown = queued("notify.email", "send");
        assertTrue(settle(unknown, PackOperationStore.Status.UNKNOWN, "OUTCOME_UNKNOWN", null));
        assertEquals(0, allRows(), "UNKNOWN is not a settlement");
        ReconciliationQueue.Item item = f.unknownItem(f.row(unknown.operationId()));
        PackReconciliationHooks hooks = new PackReconciliationHooks(f.tx(), f.store, f.gateway, f.messages);
        hooks.resolved(item, ReconciliationQueue.Action.CONFIRM_SUCCESS, "user:officer",
                ConnectorInvoker.successJson(Map.of(), "smtp-9", true));
        assertEquals(1, sent(unknown.operationId()));
        assertEquals("user:officer", onConnection.get(unknown.operationId()).details().get("settledBy"));
        hooks.resolved(item, ReconciliationQueue.Action.CONFIRM_SUCCESS, "user:officer",
                ConnectorInvoker.successJson(Map.of(), "smtp-9", true));
        assertEquals(1, sent(unknown.operationId()), "a second resolution writes nothing");

        // a retry: RETRY_REQUESTED, dispatched again with the same operationId, then completed: one event
        PackOperationStore.Row retried = queued("notify.email", "send");
        assertTrue(settle(retried, PackOperationStore.Status.UNKNOWN, "OUTCOME_UNKNOWN", null));
        assertTrue(settle(retried, PackOperationStore.Status.RETRY_REQUESTED, "NOT_FOUND", null));
        f.tx().inTransaction(c -> {
            f.store.redispatch(c, PackH2Fixture.TENANT, retried.operationId());
            return null;
        });
        assertEquals(0, sent(retried.operationId()));
        assertTrue(settle(retried, PackOperationStore.Status.COMPLETED, null, "smtp-10"));
        assertFalse(settle(retried, PackOperationStore.Status.COMPLETED, null, "smtp-10"));
        assertEquals(1, sent(retried.operationId()));
        assertEquals(2, allRows());
    }

    @Test
    void onlyNotificationSendsAreReported() {
        assertTrue(settle(queued("records", "create"), PackOperationStore.Status.COMPLETED, null, "r-1"));
        assertTrue(settle(queued("notify.email", "status"), PackOperationStore.Status.COMPLETED, null, "r-2"));
        assertTrue(settle(queued("notifyx.email", "send"), PackOperationStore.Status.COMPLETED, null, "r-3"));
        assertEquals(0, allRows());
        assertThrows(IllegalStateException.class, () -> f.store.onConnectorSettled(NotifyDelegate.CONNECTOR_PREFIX,
                (c, s) -> { }), "one listener per connector family");
        assertThrows(IllegalArgumentException.class, () -> f.store.onConnectorSettled(" ", (c, s) -> { }));
    }

    @Test
    void theEventAndTheSettlementAreOneTransaction() {
        PackOperationStore.Row r = queued("notify.email", "send");
        sinkFails = true;
        assertThrows(IllegalStateException.class, () -> settle(r, PackOperationStore.Status.COMPLETED, null, "x"));
        assertEquals(PackOperationStore.Status.PENDING, f.row(r.operationId()).status(), "no settlement without its event");
        sinkFails = false;
        // the event is written on the settling connection: a transaction that fails after the settle leaves neither
        assertThrows(IllegalArgumentException.class, () -> f.tx().inTransaction(c -> {
            f.store.settle(c, PackH2Fixture.TENANT, r.operationId(), PackOperationStore.Status.COMPLETED, null, "x", "{}");
            throw new IllegalArgumentException("commit never happens");
        }));
        assertEquals(PackOperationStore.Status.PENDING, f.row(r.operationId()).status());
        assertEquals(0, sent(r.operationId()), "rolled back with the settlement");
        assertTrue(settle(r, PackOperationStore.Status.COMPLETED, null, "x"));
        assertEquals(1, sent(r.operationId()));
    }

    /**
     * A gateway that queues as {@code IntegrationConnectorGateway.submit} does for a bound connector: the first submit
     * of an operationId stores one PENDING {@code PACK_OPERATION} row (same columns) and answers PENDING; a repeat
     * answers the stored state. The production gateway's own submit is not called here: it takes the engine command's
     * connection, and outside an engine command its {@code Context.getCommandContext()} throws (Operaton 2.2,
     * ENGINE-09025), so it cannot run without an engine.
     */
    private ConnectorGateway queueing() {
        return new ConnectorGateway() {
            @Override
            public ConnectorResult submit(org.sangam.packs.port.ConnectorRequest req) {
                return f.tx().inTransaction(c -> {
                    java.util.Optional<PackOperationStore.Row> existing = f.store.lock(c, req.tenantId(), req.operationId());
                    if (existing.isPresent()) {
                        return IntegrationConnectorGateway.toResult(existing.get());
                    }
                    f.store.insert(c, new PackOperationStore.Row(req.operationId(), req.tenantId(), req.connector(),
                            req.operation(), IntegrationConnectorGateway.ledgerOperationId(req.tenantId(),
                            req.processInstanceId(), req.operationId()), req.processInstanceId(), req.processInstanceId(),
                            req.businessKey(), req.payloadHash(), Json.write(req.payload()),
                            PackOperationStore.Status.PENDING, null, null, null, false, 1, false));
                    return ConnectorResult.pending(null);
                });
            }

            @Override
            public ConnectorResult query(org.sangam.packs.port.ConnectorRequest request) {
                throw new UnsupportedOperationException("query");
            }

            @Override
            public ConnectorResult lookup(String tenantId, String connector, String operationId) {
                throw new UnsupportedOperationException("lookup");
            }

            @Override
            public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
                throw new UnsupportedOperationException("cancel");
            }

            @Override
            public String openReconciliation(org.sangam.packs.port.ReconciliationRequest request) {
                throw new UnsupportedOperationException("openReconciliation");
            }

            @Override
            public CapabilityClass capabilityOf(String connector) {
                return CapabilityClass.B_LOOKUP_ONLY;
            }
        };
    }

    /**
     * The whole path: the pack's {@code NotifyDelegate.send} to a gateway that queues the call is only queued
     * (NOTIFICATION_QUEUED, a PENDING operation row); the later successful settlement writes the one
     * NOTIFICATION_SENT; a repeat of the step is suppressed and nothing is written again.
     */
    @Test
    void aNotificationSubmittedToABoundConnectorIsQueuedThenSentOnceBySettlement() {
        PackRuntime.install(PackRuntime.defaults().withAudit(sink));
        ConnectorGateway gateway = queueing();
        Map<String, Object> vars = new HashMap<>();
        DelegateExecution ex = execution(vars);

        NotifyDelegate.send(ex, "email", "t1", "citizen-1", gateway);
        assertEquals(List.of("NOTIFICATION_QUEUED"), fromPack.stream().map(PackAuditEvent::type).toList(),
                "queueing alone writes no NOTIFICATION_SENT: " + fromPack.stream().map(PackAuditEvent::details).toList());
        assertEquals(1, f.operationRows());
        String opId = vars.keySet().stream().filter(k -> k.startsWith(NotifyDelegate.NOTIFIED_PREFIX)).findFirst()
                .orElseThrow().substring(NotifyDelegate.NOTIFIED_PREFIX.length());
        PackOperationStore.Row row = f.row(opId);
        assertEquals(PackOperationStore.Status.PENDING, row.status());
        assertEquals("notify.email", row.connector());
        assertEquals(0, sent(opId));

        assertTrue(settle(row, PackOperationStore.Status.COMPLETED, null, "smtp-1-ref"));
        assertEquals(1, sent(opId));
        PackAuditEvent e = onConnection.get(opId);
        assertEquals(CASE, e.businessKey());
        assertEquals("email", e.details().get("channel"));
        assertEquals("t1", e.details().get("template"));
        assertEquals("citizen-1", e.details().get("recipient"));
        assertEquals("smtp-1-ref", e.details().get("externalRef"));

        // the same step again at the same case version: suppressed in the case, nothing submitted, nothing sent again
        NotifyDelegate.send(ex, "email", "t1", "citizen-1", gateway);
        assertEquals(List.of("NOTIFICATION_QUEUED", "NOTIFICATION_SUPPRESSED_DUPLICATE"),
                fromPack.stream().map(PackAuditEvent::type).toList());
        assertEquals(1, f.operationRows());
        assertEquals(1, sent(opId));
        assertEquals(1, allRows());
    }

    /** A case execution over a variable map: tenant T1, business key {@value #CASE}, step n1. */
    private static DelegateExecution execution(Map<String, Object> vars) {
        return (DelegateExecution) Proxy.newProxyInstance(DelegateExecution.class.getClassLoader(),
                new Class<?>[] {DelegateExecution.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getVariable" -> vars.get((String) args[0]);
                    case "setVariable" -> {
                        vars.put((String) args[0], args[1]);
                        yield null;
                    }
                    case "hasVariable" -> vars.containsKey((String) args[0]);
                    case "getTenantId" -> PackH2Fixture.TENANT;
                    case "getProcessBusinessKey", "getBusinessKey" -> CASE;
                    case "getProcessInstanceId" -> "pi-1";
                    case "getCurrentActivityId" -> "n1";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "execution(" + CASE + ")";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
