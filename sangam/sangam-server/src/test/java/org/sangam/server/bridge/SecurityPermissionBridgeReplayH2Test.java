// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.adapter.memory.GroupPermissionAdapter;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * RM-11, owner decision 124 (C-09): on an idempotent replay the object re-check is final, deny wins. A caller whose
 * persona group grants the command's endpoint permission ({@code sangam-system} holds {@code process.write}) gets no
 * stored response when sangam-security refuses {@code READ} on the stored process instance: an explicit deny, or no
 * grant at all. Before the fix {@code || groups.has(...)} let the persona group override the refusal.
 *
 * <p>The process-instance table is a minimal {@code ACT_RU_EXECUTION} with the columns the registry's predicate reads.
 * Written for the test phase (not run here).
 */
class SecurityPermissionBridgeReplayH2Test {

    static final String T = "t1";
    /**
     * A contract command type (replay fails closed for any other) on a process instance whose route permission,
     * {@code process.write}, the persona group holds.
     */
    static final String CMD = "process.variables.update";

    private SecurityPermissionBridge bridge;
    private final RequestContext persona = new RequestContext(T, "u1", Set.of(Permissions.GROUP_SYSTEM), null, null,
            null, null, null, "OIDC");
    /** A tenant administrator who also holds the route permission (the tenant admin persona alone does not). */
    private final RequestContext tenantAdmin = new RequestContext(T, "a1",
            Set.of(Permissions.GROUP_TENANT_ADMIN, Permissions.GROUP_USER), null, null, null, null, null, "OIDC");

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:rp" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        SangamSecurity security;
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
            Jdbc.update(c, "CREATE TABLE IF NOT EXISTS ACT_RU_EXECUTION (ID_ VARCHAR(64) PRIMARY KEY,"
                    + " PROC_INST_ID_ VARCHAR(64), TENANT_ID_ VARCHAR(64), BUSINESS_KEY_ VARCHAR(255),"
                    + " PROC_DEF_ID_ VARCHAR(64), SUSPENSION_STATE_ INTEGER)");
            for (String pi : new String[]{"pi-denied", "pi-granted", "pi-ungranted"}) {
                Jdbc.update(c, "INSERT INTO ACT_RU_EXECUTION(ID_, PROC_INST_ID_, TENANT_ID_, SUSPENSION_STATE_)"
                        + " VALUES (?,?,?,1)", pi, pi, T);
            }
            Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 1)", T);
            long read = Permission.READ.bit();
            // the persona group may read pi-denied, but the user is explicitly denied: deny wins
            effective(c, "group:" + Permissions.GROUP_SYSTEM, "pi-denied", 0, read);
            effective(c, "user:u1", "pi-denied", 1, read);
            // pi-granted: a plain grant, no deny
            effective(c, "user:u1", "pi-granted", 0, read);
            // pi-ungranted: no row at all
        }
        bridge = new SecurityPermissionBridge(security,
                new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)),
                new GroupPermissionAdapter());
    }

    private static void effective(Connection c, String principal, String resourceId, int deny, long perms) {
        Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                        + " condition_id, cond_key, policy_version) VALUES (?, ?, ?, ?, ?, ?, NULL, '-', 0)",
                T, principal, ResourceRegistry.PROCESS_INSTANCE, resourceId, deny, perms);
    }

    @Test
    void thePersonaGroupHoldsTheEndpointPermission() {
        assertTrue(new GroupPermissionAdapter().has(persona, Permissions.PROCESS_WRITE),
                "precondition: the persona group grants the command's endpoint permission");
    }

    @Test
    void anUnmappedCommandTypeIsRefusedEvenOnAGrantedObject() {
        // the old default (process.read, or any permission at all) is gone: fail closed
        assertFalse(bridge.mayReplay(persona, "process.not-in-the-contract", "pi-granted"));
        assertFalse(bridge.mayReplay(tenantAdmin, "process.not-in-the-contract", "pi-granted"));
    }

    @Test
    void anObjectDenyRefusesTheReplayEvenWithThePersonaGroup() {
        assertFalse(bridge.mayReplay(persona, CMD, "pi-denied"), "deny wins on replay (decision 124)");
    }

    @Test
    void noGrantOnTheObjectRefusesTheReplayEvenWithThePersonaGroup() {
        assertFalse(bridge.mayReplay(persona, CMD, "pi-ungranted"), "the persona group is no override (decision 124)");
    }

    @Test
    void aGrantedObjectStillReplays() {
        assertTrue(bridge.mayReplay(persona, CMD, "pi-granted"));
    }

    @Test
    void tenantAdministratorsAndCommandsWithoutAResourceAreUnchanged() {
        assertTrue(bridge.mayReplay(tenantAdmin, CMD, "pi-denied"), "administrators skip the object re-check as before");
        assertTrue(bridge.mayReplay(persona, CMD, null), "no stored resource: the endpoint gate alone decides");
    }
}
