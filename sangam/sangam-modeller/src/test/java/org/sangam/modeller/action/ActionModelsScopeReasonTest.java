// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;
import org.sangam.modeller.ModellerSchema;

/**
 * SL-167 / owner decision 62: the run's reason is recorded in ACTION_INVOKED. SL-166 f3 / decision 64: the permission is asked
 * WITH the action's scope on every listing and every invocation, a replay included, so a binding checks the subject. SL-166 f4 /
 * decision 65: a REST target is {@code connectorId:operation}, the one form the dispatcher resolves.
 */
class ActionModelsScopeReasonTest {

    static final Dialect D = Dialect.POSTGRESQL;
    Transactions tx;
    final AuditChain audit = new AuditChain(D);

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            ModellerSchema.create(c, D);
        }
        tx = Transactions.of(ds);
    }

    /** The ACTION_INVOKED payloads of tenant t, oldest first. */
    List<Map<String, Object>> invokedPayloads() {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id=e.event_id "
                + "WHERE e.tenant_id=? AND e.event_type='ACTION_INVOKED' ORDER BY e.occurred_at, e.seq", rs -> Json.readMap(rs.getString(1)), "t"));
    }

    /** Records every scoped question; the subject "hidden" is not visible to anyone. */
    static final class ScopedPermission implements ActionModels.Permission {
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public boolean allowed(String tenantId, String user, String permission, String targetRef) {
            return "alice".equals(user);
        }

        @Override
        public boolean allowed(String tenantId, String user, String permission, ActionModels.Scope scope, String targetRef) {
            asked.add(scope + ":" + targetRef);
            return allowed(tenantId, user, permission, targetRef) && !"hidden".equals(targetRef);
        }
    }

    @Test
    void theReasonIsRecordedInActionInvokedOnlyWhenGiven() {
        ActionModels a = new ActionModels(D, tx, audit, (t, u, p, r) -> true, (t, op, ac, ref, in) -> Map.of("ok", true));
        a.publish("t", "escalate", "Escalate", ActionModels.Scope.CASE, null, "case.escalate", null, ActionModels.TargetType.BPMN, "escalation", "admin");

        a.invoke("t", "alice", "escalate", "c1", Map.of(), "k1", "  customer complained twice  ");
        a.invoke("t", "alice", "escalate", "c2", Map.of(), "k2", " ");
        a.invoke("t", "alice", "escalate", "c3", Map.of(), "k3");

        List<Map<String, Object>> p = invokedPayloads();
        assertEquals(3, p.size());
        assertEquals("customer complained twice", p.get(0).get("reason"), "trimmed");
        assertEquals("c1", p.get(0).get("target"));
        assertFalse(p.get(1).containsKey("reason"), "a blank reason is not recorded");
        assertFalse(p.get(2).containsKey("reason"), "the reason-less overload records none");
        assertTrue(p.stream().allMatch(m -> m.get("operationId") != null && "alice".equals(m.get("actor"))));
    }

    @Test
    void theSubjectIsCheckedWithTheScopeOnEveryCallAndEveryListing() {
        ScopedPermission perm = new ScopedPermission();
        AtomicInteger runs = new AtomicInteger();
        ActionModels a = new ActionModels(D, tx, audit, perm, (t, op, ac, ref, in) -> {
            runs.incrementAndGet();
            return Map.of("ref", ref);
        });
        a.publish("t", "approve", "Approve", ActionModels.Scope.TASK, null, "task.approve", null, ActionModels.TargetType.BPMN, "approval", "admin");

        assertEquals(1, a.available("t", "alice", ActionModels.Scope.TASK, null, "task-7", x -> true).size());
        assertTrue(a.available("t", "alice", ActionModels.Scope.TASK, null, "hidden", x -> true).isEmpty(), "subject not visible");
        assertThrows(SecurityException.class, () -> a.invoke("t", "alice", "approve", "hidden", Map.of(), "k"));

        a.invoke("t", "alice", "approve", "task-7", Map.of(), "k");
        assertTrue(a.invoke("t", "alice", "approve", "task-7", Map.of(), "k").replayed());
        assertEquals(1, runs.get());
        assertEquals(List.of("TASK:task-7", "TASK:hidden", "TASK:hidden", "TASK:task-7", "TASK:task-7"), perm.asked,
                "the scoped form is asked every time, the replay included");
    }

    @Test
    void aFourArgumentPermissionStillWorksThroughTheDefaultScopedForm() {
        ActionModels.Permission legacy = (t, u, p, r) -> "alice".equals(u);
        assertTrue(legacy.allowed("t", "alice", "p", ActionModels.Scope.CASE, "c1"));
        assertFalse(legacy.allowed("t", "bob", "p", ActionModels.Scope.CASE, "c1"));
    }

    @Test
    void aRestTargetIsConnectorIdColonOperation() {
        ActionModels a = new ActionModels(D, tx, audit, (t, u, p, r) -> true, (t, op, ac, ref, in) -> Map.of());
        assertEquals("payments:refund", a.publish("t", "refund", "Refund", ActionModels.Scope.CASE, null, "case.refund", null,
                ActionModels.TargetType.REST, "payments:refund", "admin").target());
        for (String bad : List.of("/api/v1/cases", "payments", "http://evil", "payments:", ":refund", "a:b:c", "pay ments:refund")) {
            assertThrows(IllegalArgumentException.class, () -> a.publish("t", "x", "x", ActionModels.Scope.CASE, null, "p", null,
                    ActionModels.TargetType.REST, bad, "admin"), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> a.publish("t", "x", "x", null, null, "p", null,
                ActionModels.TargetType.BPMN, "proc", "admin"), "a scope is required");
    }
}
