// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.TenantKey;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Owner decisions 387 (4) (a) and 390 (4): the first environment's one-time bootstrap is persisted as a system audit
 * event (no tenant, chain {@value KeyEnvironmentBootstrap#CHAIN}) once the database is ready, and a second bootstrap of
 * the same environment is refused, leaving nothing behind.
 */
class KeyEnvironmentBootstrapH2Test {

    TxSupport tx;

    @BeforeEach
    void database() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:keyboot" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        h2.setUser("sa");
        org.sangam.persistence.SangamSchema.apply(h2);
        tx = new SpringTxSupport(h2, new DataSourceTransactionManager(h2), Dialect.POSTGRESQL);
    }

    static KeyPurposeSeparationWiring.Checked bootstrap(String environment) {
        return new KeyPurposeSeparationWiring.Checked(3, environment, 6, 0, 0, true);
    }

    List<String> events() {
        return tx.withConnection(c -> Jdbc.query(c, "SELECT e.tenant_id || '|' || e.chain_id || '|' || e.event_type"
                + " FROM AUDIT_EVENT e WHERE e.event_type = ? ORDER BY e.seq", rs -> rs.getString(1),
                KeyEnvironmentBootstrap.EVENT));
    }

    @Test
    void theFirstBootstrapIsRecordedAsASystemAuditEvent() {
        KeyPurposeSeparationWiring.record(bootstrap("production"), tx);
        assertEquals(List.of(TenantKey.SHARED + "|" + KeyEnvironmentBootstrap.CHAIN + "|"
                + KeyEnvironmentBootstrap.EVENT), events());
        String payload = tx.withConnection(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_PAYLOAD p JOIN AUDIT_EVENT e"
                + " ON e.event_id = p.event_id WHERE e.event_type = ?", rs -> rs.getString(1),
                KeyEnvironmentBootstrap.EVENT)).get(0);
        assertTrue(payload.contains("\"environment\":\"production\"") && payload.contains("\"own_fingerprints\":6"),
                payload);
        assertTrue(tx.withConnection(c -> new AuditChain(Dialect.POSTGRESQL).verify(c, TenantKey.SHARED,
                KeyEnvironmentBootstrap.CHAIN)).ok(), "the system chain verifies");
    }

    @Test
    void aSecondBootstrapOfTheSameEnvironmentIsRefusedAndLeavesNothing() {
        KeyPurposeSeparationWiring.record(bootstrap("production"), tx);
        String m = assertThrows(IllegalStateException.class,
                () -> KeyPurposeSeparationWiring.record(bootstrap("production"), tx)).getMessage();
        assertTrue(m.startsWith(KeyEnvironmentSeparation.LIST_REQUIRED) && m.contains("already started once"), m);
        assertEquals(1, events().size(), "the refused start's event rolled back");
        // another environment of the installation bootstraps on its own
        KeyPurposeSeparationWiring.record(bootstrap("staging"), tx);
        assertEquals(2, events().size());
    }

    @Test
    void anErasedBootstrapCountsFailClosed() {
        KeyPurposeSeparationWiring.record(bootstrap("production"), tx);
        tx.withConnection(c -> Jdbc.update(c, "UPDATE AUDIT_PAYLOAD SET erased_at = CURRENT_TIMESTAMP"));
        assertThrows(IllegalStateException.class, () -> KeyPurposeSeparationWiring.record(bootstrap("production"), tx));
    }

    @Test
    void aStartThatIsNoBootstrapRecordsNothingAndABootstrapWithoutTheDatabaseIsRefused() {
        KeyPurposeSeparationWiring.record(new KeyPurposeSeparationWiring.Checked(3, "production", 6, 4, 0, false), tx);
        assertEquals(List.of(), events());
        String m = assertThrows(IllegalStateException.class,
                () -> KeyPurposeSeparationWiring.record(bootstrap("production"), null)).getMessage();
        assertTrue(m.startsWith(KeyEnvironmentSeparation.LIST_REQUIRED) && m.contains("needs the database"), m);
    }
}
