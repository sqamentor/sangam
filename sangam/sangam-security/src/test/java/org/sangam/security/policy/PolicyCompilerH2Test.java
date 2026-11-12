// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuthorizationPolicy;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.dialect.SecurityDialect;

/**
 * The VI.4 predicate of a non-system identity on H2, the development database, run as the server runs it
 * ({@code MODE=PostgreSQL}, dialect detected as {@link SecurityDialect#H2}): the list form ({@link PolicyCompiler#visible})
 * and the object check ({@link PolicyCompiler#allow}) of {@code HISTORIC_PROCESS_INSTANCE}, the type behind the
 * READ_HISTORY lists of the history read model. Before the {@link SecurityDialect#bitAnd} fix neither could be prepared on
 * H2 ("Unknown data type: ?n"), so those lists answered 500 for every signed-in user.
 */
class PolicyCompilerH2Test {

    static final String HPI = ResourceRegistry.HISTORIC_PROCESS_INSTANCE;

    Transactions tx;
    final PolicyCompiler compiler = new PolicyCompiler(SecurityDialect.H2, new ResourceRegistry(), Duration.ZERO);

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);       // AUTH_EFFECTIVE, AUTH_POLICY_VERSION
        }
        tx = Transactions.of(ds);
        tx.inTransaction(c -> {
            Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64),"
                    + " resource_type VARCHAR(255), expression TEXT)");
            Jdbc.update(c, "CREATE TABLE ACT_HI_PROCINST (ID_ VARCHAR(64), TENANT_ID_ VARCHAR(64), PROC_DEF_KEY_ VARCHAR(255),"
                    + " BUSINESS_KEY_ VARCHAR(255), PROC_INST_ID_ VARCHAR(64), START_USER_ID_ VARCHAR(255), STATE_ VARCHAR(255))");
            Jdbc.update(c, "INSERT INTO ACT_HI_PROCINST VALUES ('p1','t','claim','BK-1','p1',NULL,'ACTIVE'),"
                    + " ('p2','t','claim','BK-2','p2',NULL,'ACTIVE'), ('p3','t','leave','BK-3','p3',NULL,'COMPLETED'),"
                    + " ('q1','u','claim','BK-1','q1',NULL,'ACTIVE')");
            long history = Permission.READ_HISTORY.bit();
            AuthorizationPolicy.grant(c, Dialect.POSTGRESQL, "t", "group:clerks", HPI, "def:claim", history);
            AuthorizationPolicy.grant(c, Dialect.POSTGRESQL, "t", "user:al", HPI, "p3", history);
            AuthorizationPolicy.deny(c, Dialect.POSTGRESQL, "t", "user:al", HPI, "p2", history);
            AuthorizationPolicy.grant(c, Dialect.POSTGRESQL, "u", "group:clerks", HPI, "*", history);
            return null;
        });
    }

    static SecurityIdentity who(String tenant, String person, String... groups) {
        return new SecurityIdentity(tenant, person, person, Set.of(groups), Set.of(), Set.of(), Set.of(), "OIDC", null,
                null, false, Map.of());
    }

    List<String> visible(SecurityIdentity w, Permission p) {
        return tx.inTransaction(c -> {
            SqlPredicate v = compiler.visible(c, w, HPI, p, "r");
            return Jdbc.query(c, "SELECT r.ID_ FROM ACT_HI_PROCINST r WHERE " + v.sql() + " ORDER BY r.ID_",
                    rs -> rs.getString(1), v.params().toArray());
        });
    }

    boolean allow(SecurityIdentity w, Permission p, String id) {
        return tx.inTransaction(c -> compiler.allow(c, w, HPI, p, id));
    }

    @Test
    void listPredicateOfANonSystemIdentityRunsOnH2() {
        SecurityIdentity al = who("t", "al", "clerks");
        assertEquals(List.of("p1", "p3"), visible(al, Permission.READ_HISTORY),
                "definition scope (p1, p2) plus the exact id p3; the deny on p2 wins");
        assertEquals(List.of("p1", "p2"), visible(who("t", "bo", "clerks"), Permission.READ_HISTORY));
        assertEquals(List.of(), visible(al, Permission.READ), "another permission bit grants nothing");
        assertEquals(List.of(), visible(who("t", "cy", "gardeners"), Permission.READ_HISTORY), "no grant, no rows");
        assertEquals(List.of("q1"), visible(who("u", "al", "clerks"), Permission.READ_HISTORY), "tenant u sees only its row");
    }

    @Test
    void objectCheckOfANonSystemIdentityRunsOnH2() {
        SecurityIdentity al = who("t", "al", "clerks");
        assertTrue(allow(al, Permission.READ_HISTORY, "p1"));
        assertTrue(allow(al, Permission.READ_HISTORY, "p3"));
        assertFalse(allow(al, Permission.READ_HISTORY, "p2"), "deny wins");
        assertFalse(allow(al, Permission.READ, "p1"), "another permission bit");
        assertFalse(allow(al, Permission.READ_HISTORY, "q1"), "a row of tenant u");
    }
}
