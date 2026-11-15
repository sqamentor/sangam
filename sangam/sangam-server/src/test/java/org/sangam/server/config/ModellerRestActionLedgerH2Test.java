// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
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
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.schema.IntegrationSchema;
import org.sangam.modeller.ModellerSchema;
import org.sangam.modeller.action.ActionModels;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.modules.modeller.ModellerRequestScope;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * A REST modeller action ({@code connectorId:operation}, decision 65) runs through the server's connector invoker bean
 * (R36), not straight from the registry: the action's operation id is the {@code OPERATION_LEDGER} row's id, the call is
 * journalled, and the {@code OPERATION_*} audit lands on the action's own chain next to {@code ACTION_INVOKED}. Before the
 * fix {@code ModellerWiring} called {@code registry.require(..).invoke(..)} and wrote no ledger row at all.
 */
class ModellerRestActionLedgerH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final RequestContext ALICE = new RequestContext("T1", "alice", Set.of("users"), null, null, null, null, null, "token");

    private final JdbcDataSource ds = new JdbcDataSource();
    private final Connection keepAlive;
    private final Transactions tx;
    private final TxSupport support;
    private final AuditChain chain = new AuditChain(D);
    private final OperationLedger ledger = new OperationLedger(D, new ReconciliationQueue(D, chain));
    private final Crm crm = new Crm();
    private final ConnectorInvoker invoker;

    ModellerRestActionLedgerH2Test() throws Exception {
        ds.setURL("jdbc:h2:mem:modelleraction" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        keepAlive = ds.getConnection();
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        ModellerSchema.create(keepAlive, D);
        tx = Transactions.of(ds);
        support = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), D);
        invoker = new IntegrationWiring().sangamConnectorInvoker(ds, ledger, new ConnectorCallJournal(D),
                new ConnectorRegistry().register(crm), chain);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    /** Class (a) connector: records each request, answers what the test sets. */
    private static final class Crm implements SangamConnector {
        final List<ConnectorRequest> seen = new ArrayList<>();
        volatile ConnectorResult answer = ConnectorResult.Success.of(Map.of("ticket", "T-1"), "crm-ref-1");

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("crm", "crm", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, List.of("escalate"),
                    ConnectorProfile.SIMULATOR);
        }

        @Override
        public synchronized ConnectorResult invoke(ConnectorRequest request) {
            seen.add(request);
            return answer;
        }
    }

    private ActionModels actions(ConnectorInvoker withInvoker) {
        PermissionPort permissions = mock(PermissionPort.class);
        when(permissions.has(any(), anyString())).thenReturn(true);
        CasePort cases = mock(CasePort.class);
        when(cases.get(any(), anyString())).thenReturn(mock(CasePort.CaseInstance.class));
        Map<String, Object> beans = new HashMap<>(Map.of("cases", cases, "processes", mock(ProcessPort.class),
                "tasks", mock(TaskPort.class)));
        if (withInvoker != null) {
            beans.put("sangamConnectorInvoker", withInvoker);
        }
        StaticListableBeanFactory f = new StaticListableBeanFactory(beans);
        ActionModels a = new ModellerWiring().sangamActionModels(support, permissions, f.getBeanProvider(ProcessEngine.class),
                f.getBeanProvider(ConnectorInvoker.class), f.getBeanProvider(CasePort.class), f.getBeanProvider(ProcessPort.class),
                f.getBeanProvider(TaskPort.class));
        a.publish("T1", "escalate", "Escalate", ActionModels.Scope.CASE, null, "case.escalate", null, ActionModels.TargetType.REST,
                "crm:escalate", "admin");
        return a;
    }

    private static <T> T asAlice(Supplier<T> work) {
        return ModellerRequestScope.with(ALICE, work);
    }

    private Map<String, Object> ledgerRow(UUID op) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT tenant_id, root_id, activity_instance_id, state, outcome FROM OPERATION_LEDGER"
                + " WHERE operation_id=?", rs -> Map.<String, Object>of("tenant", rs.getString(1), "root", rs.getString(2),
                "activity", rs.getString(3), "state", rs.getString(4), "outcome", rs.getString(5)), op.toString())).orElse(null);
    }

    private List<String> auditOn(String root) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? ORDER BY seq",
                rs -> rs.getString(1), "T1", root));
    }

    @Test
    void aRestActionWritesItsOperationLedgerRowUnderTheActionsOperationId() {
        ActionModels a = actions(invoker);
        ActionModels.Invocation run = asAlice(() -> a.invoke("T1", "alice", "escalate", "case-1", Map.of("note", "late"), "click-1"));

        assertEquals(Map.of("ticket", "T-1"), run.result());
        assertFalse(run.replayed());
        Map<String, Object> row = ledgerRow(run.operationId());
        assertTrue(row != null, "the REST action has an OPERATION_LEDGER row keyed by its own operation id");
        assertEquals("T1", row.get("tenant"));
        assertEquals("SUCCEEDED", row.get("state"));
        assertEquals("action:" + run.operationId(), row.get("root"));
        assertEquals(1, crm.seen.size());
        ConnectorRequest sent = crm.seen.get(0);
        assertEquals(run.operationId(), sent.operationId(), "downstream idempotency key is the action's operation id");
        assertEquals("escalate", sent.operation());
        assertEquals("case-1", sent.businessKey());
        assertEquals(1L, tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM CONNECTOR_CALL WHERE operation_id=? AND connector_id=?",
                rs -> rs.getLong(1), run.operationId().toString(), "crm")).orElse(0L), "the call is journalled");
        List<String> events = auditOn("action:" + run.operationId());
        assertEquals(List.of("ACTION_INVOKED", "OPERATION_DISPATCHED", "OPERATION_OUTCOME"), events,
                "the dispatch and outcome are audited on the action's own chain");
    }

    @Test
    void aRepeatOfTheSameClickDoesNotDispatchAgain() {
        ActionModels a = actions(invoker);
        ActionModels.Invocation first = asAlice(() -> a.invoke("T1", "alice", "escalate", "case-1", Map.of(), "click-1"));
        ActionModels.Invocation again = asAlice(() -> a.invoke("T1", "alice", "escalate", "case-1", Map.of(), "click-1"));
        assertTrue(again.replayed());
        assertEquals(first.operationId(), again.operationId());
        assertEquals(1, crm.seen.size());
    }

    @Test
    void aBusinessFailureIsANamedFailureAndASettledLedgerRow() {
        crm.answer = ConnectorResult.BusinessFailure.of("CASE_CLOSED", "the case is closed");
        ActionModels a = actions(invoker);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> asAlice(() -> a.invoke("T1", "alice", "escalate", "case-1", Map.of(), "click-2")));
        assertTrue(e.getMessage().startsWith("ACTION_TARGET_FAILED: CASE_CLOSED"), e.getMessage());
        UUID op = crm.seen.get(0).operationId();
        assertEquals("FAILED", ledgerRow(op).get("state"));
    }

    @Test
    void anUnknownOutcomeStaysInReconciliation() {
        ActionModels a = actions(new IntegrationWiring().sangamConnectorInvoker(ds, ledger, new ConnectorCallJournal(D),
                new ConnectorRegistry().register(new SangamConnector() {
                    @Override
                    public ConnectorDescriptor descriptor() {
                        return new ConnectorDescriptor("crm", "crm", CapabilityClass.C_NEITHER, List.of("escalate"),
                                ConnectorProfile.SIMULATOR);
                    }

                    @Override
                    public ConnectorResult invoke(ConnectorRequest request) {
                        return ConnectorResult.OutcomeUnknown.of("answer lost");
                    }
                }), chain));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> asAlice(() -> a.invoke("T1", "alice", "escalate", "case-1", Map.of(), "click-3")));
        assertTrue(e.getMessage().startsWith("ACTION_OUTCOME_UNKNOWN"), e.getMessage());
        long unknown = tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OPERATION_LEDGER WHERE state='UNKNOWN_OUTCOME'",
                rs -> rs.getLong(1))).orElse(0L);
        assertEquals(1L, unknown);
    }

    @Test
    void withoutTheInvokerARestActionIsNotInstalledAndNothingIsCalled() {
        ActionModels a = actions(null);
        ApiProblem p = assertThrows(ApiProblem.class,
                () -> asAlice(() -> a.invoke("T1", "alice", "escalate", "case-1", Map.of(), "click-4")));
        assertEquals(501, p.status());
        assertTrue(crm.seen.isEmpty());
    }
}
