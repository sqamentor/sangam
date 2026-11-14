// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.billing.UnverifiedPaymentAuthority;
import org.sangam.billing.UnverifiedPaymentAuthority.Caller;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.billing.UnverifiedPaymentPermissions;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.ModuleResourceTypes;
import org.sangam.security.policy.Permission;
import org.sangam.security.tenant.TenantContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Decision 141 through the server's binding of sangam-billing's {@link UnverifiedPaymentAuthority}: the identity
 * evaluated is only the request's resolved identity ({@link TenantContext}); a caller that does not match it (forged
 * principal, other tenant, seat not held), no identity at all (pack, connector, job), the system identity and an unknown
 * action are refused; an explicit channel grant permits exactly its channel and office. Full product schema on H2.
 * Written for the test phase, not run.
 */
class UnverifiedPaymentAuthorityBridgeH2Test {

    static final String T = "T-D141";

    private UnverifiedPaymentAuthority bridge;

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:d141" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(h2);
        SangamSecurity security;
        try (Connection c = h2.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
            c.setAutoCommit(true);
            Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                    + " condition_id, cond_key, policy_version) VALUES (?, 'user:al', ?, ?, 0, ?, NULL, '-', 0)",
                    T, ModuleResourceTypes.BILLING, UnverifiedPaymentPermissions.resourceId("upi", "off-1"),
                    Permission.ACCEPT_UNVERIFIED.bit());
            Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                    + " condition_id, cond_key, policy_version) VALUES (?, 'role:billing-admin', ?, '*', 0, ?, NULL, '-', 0)",
                    T, ModuleResourceTypes.BILLING, Permission.ACCEPT_UNVERIFIED.bit() | Permission.ADMIN.bit());
        }
        TxSupport tx = new SpringTxSupport(h2, new DataSourceTransactionManager(h2), TxSupport.detectDialect(h2));
        bridge = new UnverifiedPaymentAuthorityBridge(security, tx);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static SecurityIdentity person(String tenant, String person, Set<String> seats, String... roles) {
        return new SecurityIdentity(tenant, person, "sub-" + person, Set.of(), Set.of(roles), seats, Set.of(), "OIDC",
                "10.0.0.1", null, false, Map.of());
    }

    private boolean accept(Caller caller, String office, String channel) {
        return bridge.permits(caller, UnverifiedPaymentAuthority.ACCEPT_UNVERIFIED, T, office, channel);
    }

    @Test
    void theAuthenticatedHolderOfAnExplicitChannelGrantIsPermitted() {
        TenantContext.set(person(T, "al", Set.of()));
        assertTrue(accept(new Caller("al", T, null), "off-1", "upi"));
        assertTrue(accept(new Caller("sub-al", T, null), "off-1", "upi"), "the principal may be the token subject");
        assertFalse(accept(new Caller("al", T, null), "off-2", "upi"), "wrong office");
        assertFalse(accept(new Caller("al", T, null), "off-1", "cash"), "wrong channel");
        assertFalse(bridge.permits(new Caller("al", T, null), UnverifiedPaymentAuthority.RESOLVE_UNVERIFIED, T, "off-1",
                "upi"), "accepting does not imply resolving");
    }

    @Test
    void withoutARequestIdentityNothingIsPermitted() {
        assertFalse(accept(new Caller("al", T, null), "off-1", "upi"), "pack, connector, job or another thread");
    }

    @Test
    void aForgedCallerIsRefused() {
        TenantContext.set(person(T, "bo", Set.of()));
        assertFalse(accept(new Caller("al", T, null), "off-1", "upi"), "the caller names someone else than the request");
        TenantContext.set(person(T, "al", Set.of()));
        assertFalse(accept(new Caller("al", "T-OTHER", null), "off-1", "upi"), "another tenant");
        assertFalse(accept(new Caller("al", T, "seat-not-held"), "off-1", "upi"), "a seat the person does not hold");
        TenantContext.set(person(T, "al", Set.of("seat-1")));
        assertTrue(accept(new Caller("al", T, "seat-1"), "off-1", "upi"), "a seat the person holds");
    }

    @Test
    void theSystemIdentityAndGenericGrantsAreRefused() {
        TenantContext.set(SecurityIdentity.system(T, "job"));
        assertFalse(accept(new Caller("job", T, null), "off-1", "upi"));
        TenantContext.set(person(T, "ba", Set.of(), "billing-admin"));
        assertFalse(accept(new Caller("ba", T, null), "off-1", "upi"), "a BILLING '*' grant never confers it");
    }

    @Test
    void anUnknownActionIsRefused() {
        TenantContext.set(person(T, "al", Set.of()));
        assertFalse(bridge.permits(new Caller("al", T, null), "ADMIN", T, "off-1", "upi"));
        assertFalse(bridge.permits(null, UnverifiedPaymentAuthority.ACCEPT_UNVERIFIED, T, "off-1", "upi"));
    }
}
