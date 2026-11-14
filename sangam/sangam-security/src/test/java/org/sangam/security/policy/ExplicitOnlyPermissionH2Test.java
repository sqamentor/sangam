// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.billing.UnverifiedPaymentPermissions;
import org.sangam.security.dialect.SecurityDialect;

/**
 * Decision 141 on H2 (PostgreSQL mode): ACCEPT_UNVERIFIED and RESOLVE_UNVERIFIED are explicit-only. Only a grant on the
 * concrete type BILLING and the concrete channel id {@code payment-channel:<channel>@<office>} of the caller's tenant
 * confers them; a wildcard id or type grant never does (a wildcard deny still denies), validation rejects such grants,
 * the system identity and API keys never hold them, and a generic billing permission never implies them. Written for
 * the test phase, not run. Oracle and SQL Server: {@code ExplicitOnlyPermissionDialectIT} in sangam-server (NOT_RUN).
 */
class ExplicitOnlyPermissionH2Test {

    static final String T = "t";
    static final String U = "u";
    static final String BILLING = ModuleResourceTypes.BILLING;
    static final String UPI_1 = UnverifiedPaymentPermissions.resourceId("upi", "off-1");
    static final String UPI_2 = UnverifiedPaymentPermissions.resourceId("upi", "off-2");
    static final String CASH_1 = UnverifiedPaymentPermissions.resourceId("cash", "off-1");

    private Connection c;
    private PolicyCompiler compiler;
    private AuthorizationService authz;
    private UnverifiedPaymentPermissions payments;

    @BeforeEach
    void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:d141" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        Schema.create(c, Dialect.POSTGRESQL);   // AUTH_EFFECTIVE, AUTH_POLICY_VERSION, AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ
        Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64),"
                + " resource_type VARCHAR(64), expression TEXT)");
        for (String tenant : List.of(T, U)) {
            Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 1)", tenant);
        }
        long accept = Permission.ACCEPT_UNVERIFIED.bit();
        long resolve = Permission.RESOLVE_UNVERIFIED.bit();
        long all = Permission.mask(java.util.EnumSet.allOf(Permission.class));
        grant(T, "user:al", BILLING, UPI_1, accept, false);
        grant(T, "user:al", BILLING, CASH_1, resolve, false);
        grant(T, "user:bo", BILLING, "*", accept | resolve | Permission.READ.bit(), false);   // wildcard id
        grant(T, "user:cy", "*", "*", all, false);                                            // wildcard type and id
        grant(T, "user:cy", "*", UPI_1, all, false);                                          // wildcard type, exact id
        grant(T, "user:dd", BILLING, UPI_1, accept, false);
        grant(T, "user:dd", BILLING, "*", accept, true);                                      // a wildcard deny applies
        grant(T, "role:billing-admin", BILLING, "*", Permission.mask(billingAdmin()), false);   // the module template
        grant(U, "user:al", BILLING, UPI_2, accept, false);
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2);
        compiler = new PolicyCompiler(SecurityDialect.H2, new ResourceRegistry(), Duration.ZERO);
        authz = new AuthorizationService(compiler, audit);
        payments = new UnverifiedPaymentPermissions(authz);
    }

    /** The billing-admin role template's BILLING permissions ({@code '*'} id). */
    static Set<Permission> billingAdmin() {
        return new org.sangam.security.admin.ModuleDefaults(null, null, new ResourceRegistry()).roleTemplates()
                .get("billing-admin").get(BILLING);
    }

    @AfterEach
    void close() throws Exception {
        c.close();
    }

    private void grant(String tenant, String principal, String type, String id, long perms, boolean deny) {
        Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                + " condition_id, cond_key, policy_version) VALUES (?, ?, ?, ?, ?, ?, NULL, '-', 0)",
                tenant, principal, type, id, deny ? 1 : 0, perms);
    }

    static SecurityIdentity who(String tenant, String person, String authMethod, String... roles) {
        return new SecurityIdentity(tenant, person, person, Set.of(), Set.of(roles), Set.of(), Set.of(), authMethod, null,
                null, false, Map.of());
    }

    static SecurityIdentity person(String tenant, String person, String... roles) {
        return who(tenant, person, "OIDC", roles);
    }

    private boolean allow(SecurityIdentity w, Permission p, String id) {
        return compiler.allow(c, w, BILLING, p, id);
    }

    @Test
    void ordinalsAreAppendedAndTheActionsAreSensitiveAndExplicitOnly() {
        assertEquals(23, Permission.REVIEW_PACKAGE.ordinal(), "existing bits keep their positions");
        assertEquals(24, Permission.ACCEPT_UNVERIFIED.ordinal());
        assertEquals(25, Permission.RESOLVE_UNVERIFIED.ordinal());
        for (Permission p : List.of(Permission.ACCEPT_UNVERIFIED, Permission.RESOLVE_UNVERIFIED)) {
            assertTrue(p.sensitive(), p + " is re-checked without cache");
            assertTrue(p.explicitOnly());
        }
        assertFalse(Permission.ADMIN.explicitOnly());
    }

    @Test
    void anExplicitChannelGrantConfersExactlyItsScope() {
        SecurityIdentity al = person(T, "al");
        assertTrue(allow(al, Permission.ACCEPT_UNVERIFIED, UPI_1));
        assertFalse(allow(al, Permission.ACCEPT_UNVERIFIED, UPI_2), "another office");
        assertFalse(allow(al, Permission.ACCEPT_UNVERIFIED, CASH_1), "another channel");
        assertTrue(allow(al, Permission.RESOLVE_UNVERIFIED, CASH_1));
        assertFalse(allow(al, Permission.RESOLVE_UNVERIFIED, UPI_1), "accepting does not imply resolving");
    }

    @Test
    void wildcardGrantsNeverConferThem() {
        SecurityIdentity bo = person(T, "bo");
        assertFalse(allow(bo, Permission.ACCEPT_UNVERIFIED, UPI_1), "BILLING '*' grant");
        assertFalse(allow(bo, Permission.RESOLVE_UNVERIFIED, UPI_1));
        assertTrue(allow(bo, Permission.READ, UPI_1), "ordinary permissions still come from a wildcard");
        SecurityIdentity cy = person(T, "cy");
        assertFalse(allow(cy, Permission.ACCEPT_UNVERIFIED, UPI_1), "'*' type grants, also with the exact id");
        assertTrue(allow(cy, Permission.ADMIN, UPI_1));
        assertFalse(allow(person(T, "ba", "billing-admin"), Permission.ACCEPT_UNVERIFIED, UPI_1),
                "a generic billing permission never implies the exception");
    }

    @Test
    void theEndpointGateNeedsOneExplicitGrant() {
        assertTrue(compiler.holdsExplicitGrant(c, person(T, "al"), BILLING, Permission.ACCEPT_UNVERIFIED));
        assertTrue(compiler.holdsExplicitGrant(c, person(T, "al"), BILLING, Permission.RESOLVE_UNVERIFIED));
        assertFalse(compiler.holdsExplicitGrant(c, person(T, "bo"), BILLING, Permission.ACCEPT_UNVERIFIED), "'*' id only");
        assertFalse(compiler.holdsExplicitGrant(c, person(T, "ba", "billing-admin"), BILLING, Permission.ACCEPT_UNVERIFIED));
        assertFalse(compiler.holdsExplicitGrant(c, person(T, "zz"), BILLING, Permission.ACCEPT_UNVERIFIED), "no grant");
        assertFalse(compiler.holdsExplicitGrant(c, person(T, "al"), BILLING, Permission.ADMIN),
                "only explicit-only permissions use this gate");
        assertFalse(compiler.holdsExplicitGrant(c, person(T, "cy"), BILLING, Permission.ACCEPT_UNVERIFIED),
                "'*' type rows never count");
    }

    @Test
    void aWildcardDenyStillDenies() {
        assertFalse(allow(person(T, "dd"), Permission.ACCEPT_UNVERIFIED, UPI_1));
    }

    @Test
    void tenantsAreIsolated() {
        assertFalse(allow(person(T, "al"), Permission.ACCEPT_UNVERIFIED, UPI_2), "al's grant on upi@off-2 is tenant u's");
        assertTrue(allow(person(U, "al"), Permission.ACCEPT_UNVERIFIED, UPI_2));
        assertFalse(allow(person(U, "al"), Permission.ACCEPT_UNVERIFIED, UPI_1));
    }

    @Test
    void theSetFormDecidesAsTheObjectCheck() {
        for (String person : List.of("al", "bo", "cy", "dd")) {
            SecurityIdentity w = person(T, person);
            Set<String> ids = compiler.allowedIds(c, w, BILLING, Permission.ACCEPT_UNVERIFIED, List.of(UPI_1, UPI_2, CASH_1));
            for (String id : List.of(UPI_1, UPI_2, CASH_1)) {
                assertEquals(allow(w, Permission.ACCEPT_UNVERIFIED, id), ids.contains(id), person + " " + id);
            }
        }
    }

    @Test
    void theSystemIdentityNeverHoldsThem() {
        SecurityIdentity system = SecurityIdentity.system(T, "pack-runtime");
        assertFalse(authz.allow(c, system, BILLING, Permission.ACCEPT_UNVERIFIED, UPI_1, Map.of()));
        assertFalse(authz.allow(c, system, BILLING, Permission.RESOLVE_UNVERIFIED, UPI_1, Map.of()));
        assertTrue(authz.allow(c, system, BILLING, Permission.READ, UPI_1, Map.of()), "other actions unchanged");
        assertEquals(Set.of(), authz.allowedIds(c, system, BILLING, Permission.ACCEPT_UNVERIFIED, List.of(UPI_1)));
    }

    @Test
    void explicitOnlyActionsExistOnlyOnVirtualTypes() {
        grant(T, "user:ee", ResourceRegistry.PROCESS_INSTANCE, "pi-1", Permission.ACCEPT_UNVERIFIED.bit(), false);
        assertFalse(compiler.allow(c, person(T, "ee"), ResourceRegistry.PROCESS_INSTANCE, Permission.ACCEPT_UNVERIFIED,
                "pi-1"));
    }

    @Test
    void validationRejectsWildcardGrants() {
        long accept = Permission.ACCEPT_UNVERIFIED.bit();
        assertThrows(SangamSecurityException.PolicyRejected.class,
                () -> compiler.validate(BILLING, "*", accept, null, false), "wildcard id");
        assertThrows(SangamSecurityException.PolicyRejected.class,
                () -> compiler.validate(ResourceRegistry.ANY_TYPE, UPI_1, accept, null, false), "wildcard type");
        assertThrows(SangamSecurityException.PolicyRejected.class,
                () -> compiler.validate(BILLING, " ", accept, null, false), "blank id");
        assertThrows(SangamSecurityException.PolicyRejected.class,
                () -> compiler.validate(ResourceRegistry.PROCESS_INSTANCE, "pi-1", accept, null, false),
                "a type that does not define the action");
        assertDoesNotThrow(() -> compiler.validate(BILLING, UPI_1, accept, null, false));
        assertDoesNotThrow(() -> compiler.validate(BILLING, "*", accept, null, true), "a wildcard deny is allowed");
        assertDoesNotThrow(() -> compiler.validate(BILLING, "*", Permission.ADMIN.bit(), null, false),
                "ordinary permissions keep their wildcard grants");
    }

    @Test
    void roleTemplatesOnWildcardsCarryNoExplicitOnlyAction() {
        assertTrue(billingAdmin().stream().noneMatch(Permission::explicitOnly),
                "the generic billing set of the billing-admin template");
        assertTrue(billingAdmin().contains(Permission.ADMIN), "the template keeps the ordinary billing permissions");
        assertTrue(new ResourceRegistry().permissionsOf(BILLING).contains(Permission.ACCEPT_UNVERIFIED),
                "BILLING defines the action, so explicit channel grants validate");
    }

    // ------------------------------------------------------------------ UnverifiedPaymentPermissions

    @Test
    void theChannelResourceIdIsStrict() {
        assertEquals("payment-channel:upi@off-1", UPI_1);
        for (String[] bad : new String[][] {{"*", "off-1"}, {"upi", "*"}, {"up@i", "off-1"}, {"upi", "off:1"}, {"", "off-1"},
                {null, "off-1"}, {"upi", " off-1"}}) {
            assertThrows(IllegalArgumentException.class, () -> UnverifiedPaymentPermissions.resourceId(bad[0], bad[1]),
                    String.valueOf(List.of(String.valueOf(bad[0]), String.valueOf(bad[1]))));
        }
        assertEquals(java.util.Optional.of(Permission.ACCEPT_UNVERIFIED), UnverifiedPaymentPermissions.action("ACCEPT_UNVERIFIED"));
        assertEquals(java.util.Optional.empty(), UnverifiedPaymentPermissions.action("ADMIN"), "only the two actions");
    }

    @Test
    void permitsChecksTenantChannelOfficeAndCaller() {
        SecurityIdentity al = person(T, "al");
        assertTrue(payments.permits(c, al, Permission.ACCEPT_UNVERIFIED, T, "off-1", "upi", null));
        assertFalse(payments.permits(c, al, Permission.ACCEPT_UNVERIFIED, T, "off-2", "upi", null), "wrong office");
        assertFalse(payments.permits(c, al, Permission.ACCEPT_UNVERIFIED, T, "off-1", "cash", null), "wrong channel");
        assertFalse(payments.permits(c, al, Permission.ACCEPT_UNVERIFIED, U, "off-2", "upi", null),
                "an identity of tenant t cannot act in tenant u");
        assertFalse(payments.permits(c, SecurityIdentity.system(T, "job"), Permission.ACCEPT_UNVERIFIED, T, "off-1", "upi",
                null), "jobs, packs and connectors");
        assertFalse(payments.permits(c, who(T, "al", "API_KEY"), Permission.ACCEPT_UNVERIFIED, T, "off-1", "upi", null),
                "an API key is not an authenticated member of staff");
        // decision 201: signed-in staff only
        assertTrue(payments.permits(c, who(T, "al", "SAML"), Permission.ACCEPT_UNVERIFIED, T, "off-1", "upi", null));
        for (String method : new String[] {"DEV_TOKEN", "DEV_STATIC", "SERVICE", "SYSTEM_JOB"}) {
            assertFalse(payments.permits(c, who(T, "al", method), Permission.ACCEPT_UNVERIFIED, T, "off-1", "upi", null),
                    method);
        }
        assertFalse(payments.permits(c, who(T, "al", null), Permission.ACCEPT_UNVERIFIED, T, "off-1", "upi", null),
                "no authentication method");
        assertFalse(payments.permits(c, al, Permission.ADMIN, T, "off-1", "upi", null), "only explicit-only actions");
        assertFalse(payments.permits(c, al, Permission.ACCEPT_UNVERIFIED, T, "off-1", "*", null), "wildcard channel");
        assertFalse(payments.permits(c, person(T, "bo"), Permission.ACCEPT_UNVERIFIED, T, "off-1", "upi", null));
    }

    @Test
    void aRefusedCheckIsAudited() {
        long before = Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT", rs -> rs.getLong(1)).orElse(0L);
        assertFalse(payments.permits(c, person(T, "bo"), Permission.ACCEPT_UNVERIFIED, T, "off-1", "upi", null));
        long after = Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT", rs -> rs.getLong(1)).orElse(0L);
        assertTrue(after > before, "AUTH_DENIED recorded for the refused acceptance");
    }
}
