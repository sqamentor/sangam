// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.adapter.operaton.WorkerTopicGuard;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthEffectiveMaintainer;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * SL-158 (R26, owner decision: enforce): the server's {@code WorkerTopicGuard} on a real H2 security schema. A worker
 * that only belongs to a worker group and has no binding to the topic gets 403 {@code WORKER_TOPIC_NOT_BOUND} naming
 * the worker and the topic; a worker bound to the topic passes; an administrator whose role grants WORK on every
 * worker topic passes.
 */
class WorkerTopicGuardWiringTest {

    static final String T = "T-SL158";
    static final String TOPIC = "billing.collect";

    SangamSecurity security;
    TxSupport tx;
    WorkerTopicGuard guard;

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:sl158" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(h2);
        try (Connection c = h2.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = new SpringTxSupport(h2, new DataSourceTransactionManager(h2), TxSupport.detectDialect(h2));
        guard = SecurityWiring.workerTopicGuard(security, tx);
        tx.inTx(() -> {
            Connection c = tx.connection();
            Instant now = Instant.now();
            Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", T, "t", "ACTIVE", "{}", "{}", 0, now);
            for (String p : List.of("w-bound", "w-unbound", "admin-1")) {
                Jdbc.update(c, "INSERT INTO SEC_PERSON(tenant_id, person_id, display_name, external_subject, status, created_at)"
                        + " VALUES (?,?,?,?,?,?)", T, p, p, "sub-" + p, "ACTIVE", now);
            }
            SecurityIdentity system = SecurityIdentity.system(T, "test");
            security.workers().bind(c, system, "w-bound", TOPIC);
            // an administrator role: WORK on every worker topic, through the group
            security.maintainer().replaceSource(c, T, "test:admin-role", List.of(new AuthEffectiveMaintainer.GrantRow(
                    SecurityIdentity.GROUP + "sangam-admin", ResourceRegistry.WORKER_TOPIC, ResourceRegistry.ANY_ID, false,
                    Permission.mask(Permission.WORK), null)));
            return null;
        });
    }

    static RequestContext worker(String principal, String... groups) {
        return new RequestContext(T, principal, Set.of(groups), null, null, null, null, null, "API_TOKEN");
    }

    @Test
    void aGroupOnlyWorkerWithoutABindingIsRefusedNamingTheMissingBinding() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> guard.requireTopic(worker("w-unbound", "sangam-worker"), TOPIC));
        assertEquals(403, p.status());
        assertEquals(SecurityWiring.TOPIC_NOT_BOUND, p.code());
        assertTrue(p.getMessage().contains("w-unbound") && p.getMessage().contains(TOPIC), p.getMessage());
    }

    @Test
    void aBoundWorkerPasses() {
        assertDoesNotThrow(() -> guard.requireTopic(worker("w-bound", "sangam-worker"), TOPIC));
    }

    @Test
    void aBoundWorkerIsStillRefusedOnAnotherTopic() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> guard.requireTopic(worker("w-bound", "sangam-worker"), "other.topic"));
        assertEquals(403, p.status());
    }

    @Test
    void anAdministratorPasses() {
        assertDoesNotThrow(() -> guard.requireTopic(worker("admin-1", "sangam-admin"), TOPIC));
    }
}
