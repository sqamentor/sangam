// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * UI-DEMO sweep S13 (fail closed): {@code POST /admin/policies} applies every field it is given or refuses the request.
 * Before the fix an effect outside the contract's GRANT/DENY was stored as a grant, an unknown principal type as a
 * user grant, a {@code conditionId} was dropped (a conditional grant became unconditional) and an unknown action
 * escaped as an unmapped exception.
 */
class SecurityAdminBridgePolicyValidationH2Test {

    static final String T = "t-s13";

    private SecurityAdminBridge bridge;
    private final RequestContext ctx = new RequestContext(T, "s13-admin", Set.of("sangam-system"), null, null, null,
            null, null, "SYSTEM");

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:s13" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        SangamSecurity security;
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        Transactions.of(ds).inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO SEC_TENANT(tenant_id, name_en, status, config, quotas, four_eyes, created_at)"
                    + " VALUES (?,?,?,?,?,?,?)", T, "t", "ACTIVE", "{}", "{}", 0, past);
            return null;
        });
        bridge = new SecurityAdminBridge(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), null,
                new RestProperties());
    }

    static AdminPort.Policy policy(String effect, String principalType, List<String> actions, String conditionId) {
        return new AdminPort.Policy(null, T, effect, principalType, "clerks", "PROCESS_INSTANCE", "*", actions,
                conditionId, 0, null, null);
    }

    static void refused(String field, Runnable call) {
        ApiProblem e = assertThrows(ApiProblem.class, call::run);
        assertEquals(400, e.status());
        assertEquals("VALIDATION_FAILED", e.code());
        assertEquals(field, e.extensions().get("field"));
    }

    @Test
    void anEffectOutsideTheContractIsRefusedNotStoredAsAGrant() {
        for (String effect : new String[] {"ALLOW", "PERMIT", "", null}) {
            refused("effect", () -> bridge.createPolicy(ctx, policy(effect, "GROUP", List.of("READ"), null)));
        }
    }

    @Test
    void anUnknownPrincipalTypeIsRefusedNotStoredAsAUserGrant() {
        refused("principalType", () -> bridge.createPolicy(ctx, policy("GRANT", "SERVICE", List.of("READ"), null)));
    }

    @Test
    void aConditionIsRefusedNotDropped() {
        refused("conditionId", () -> bridge.createPolicy(ctx, policy("GRANT", "GROUP", List.of("READ"), "own-unit")));
    }

    @Test
    void anUnknownActionIsABadRequest() {
        refused("actions", () -> bridge.createPolicy(ctx, policy("GRANT", "GROUP", List.of("READ", "RETRY"), null)));
    }

    @Test
    void theContractEffectsAreStoredAsGiven() {
        assertEquals("GRANT", bridge.createPolicy(ctx, policy("GRANT", "GROUP", List.of("READ"), null)).effect());
        assertEquals("DENY", bridge.createPolicy(ctx, policy("deny", "GROUP", List.of("READ"), " ")).effect());
    }
}
