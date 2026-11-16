// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
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
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.dialect.SecurityDialect;

/**
 * R7UI-S12: the module-wide object check (id {@code '*'}) on a table-backed type. The case and programme adapters ask
 * "may this caller act in the module" with id {@code '*'}; the object check ran the row predicate with
 * {@code id = '*'}, which no row matches, so every non-system identity got 403 on COMMITTEE, CASE_REFERENCE, PROGRAMME
 * and CASE_FILE, also an administrator granted everything. Now {@code '*'} is decided by the policy: a grant on
 * {@code '*'} (of the type or of type {@code '*'}) confers it, a row or scope grant never does, and deny wins.
 */
class ModuleWideTableTypeCheckH2Test {

    static final List<String> TYPES = List.of(ModuleResourceTypes.COMMITTEE, ModuleResourceTypes.CASE_REFERENCE,
            ModuleResourceTypes.PROGRAMME, ModuleResourceTypes.CASE_FILE);

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
        long readAdmin = Permission.READ.bit() | Permission.ADMIN.bit();
        tx.inTransaction(c -> {
            Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64),"
                    + " resource_type VARCHAR(255), expression TEXT)");
            Jdbc.update(c, "CREATE TABLE CF_COMMITTEE (tenant_id VARCHAR(64), committee_id VARCHAR(64), org_unit VARCHAR(64),"
                    + " committee_kind VARCHAR(64), status VARCHAR(32))");
            Jdbc.update(c, "INSERT INTO CF_COMMITTEE VALUES ('t','c1','u1','STANDING','ACTIVE')");
            Jdbc.update(c, "CREATE TABLE CF_REFERENCE (tenant_id VARCHAR(64), reference_id VARCHAR(64), org_unit VARCHAR(64),"
                    + " ref_kind VARCHAR(64), case_id VARCHAR(64), assigned_seat VARCHAR(64), assigned_person VARCHAR(64),"
                    + " source_category VARCHAR(64), status VARCHAR(32))");
            Jdbc.update(c, "CREATE TABLE PG_PROGRAMME (tenant_id VARCHAR(64), programme_id VARCHAR(64), parent_id VARCHAR(64),"
                    + " kind VARCHAR(64), owner_org VARCHAR(64), status VARCHAR(32))");
            Jdbc.update(c, "CREATE TABLE CF_CASE (tenant_id VARCHAR(64), case_id VARCHAR(64), org_unit VARCHAR(64),"
                    + " case_type VARCHAR(64), owner_seat VARCHAR(64), confidentiality VARCHAR(32), status VARCHAR(32),"
                    + " opened_by VARCHAR(64), business_key VARCHAR(255))");
            // the dev bootstrap's shape: everything on every type
            AuthorizationPolicy.grant(c, Dialect.POSTGRESQL, "t", "group:admins", ResourceRegistry.ANY_TYPE, "*", readAdmin);
            // a type-wide grant
            AuthorizationPolicy.grant(c, Dialect.POSTGRESQL, "t", "group:secretaries", ModuleResourceTypes.COMMITTEE, "*",
                    readAdmin);
            // row and scope grants only
            AuthorizationPolicy.grant(c, Dialect.POSTGRESQL, "t", "group:members", ModuleResourceTypes.COMMITTEE, "c1",
                    readAdmin);
            AuthorizationPolicy.grant(c, Dialect.POSTGRESQL, "t", "group:members", ModuleResourceTypes.COMMITTEE, "unit:u1",
                    readAdmin);
            // granted everything, but READ on COMMITTEE '*' denied
            AuthorizationPolicy.grant(c, Dialect.POSTGRESQL, "t", "group:audited", ResourceRegistry.ANY_TYPE, "*", readAdmin);
            AuthorizationPolicy.deny(c, Dialect.POSTGRESQL, "t", "group:audited", ModuleResourceTypes.COMMITTEE, "*",
                    Permission.READ.bit());
            return null;
        });
    }

    static SecurityIdentity who(String tenant, String person, String... groups) {
        return new SecurityIdentity(tenant, person, person, Set.of(groups), Set.of(), Set.of(), Set.of(), "OIDC", null,
                null, false, Map.of());
    }

    boolean allow(SecurityIdentity w, String type, Permission p, String id) {
        return tx.inTransaction(c -> compiler.allow(c, w, type, p, id));
    }

    Set<String> allowedIds(SecurityIdentity w, String type, Permission p, List<String> ids) {
        return tx.inTransaction(c -> compiler.allowedIds(c, w, type, p, ids));
    }

    @Test
    void aPrincipalGrantedStarHoldsModuleLevelReadAndAdminOnEveryTableType() {
        SecurityIdentity admin = who("t", "dev-admin", "admins");
        List<String> refused = new ArrayList<>();
        for (String type : TYPES) {
            for (Permission p : List.of(Permission.READ, Permission.ADMIN)) {
                if (!allow(admin, type, p, "*")) {
                    refused.add("allow " + type + " " + p);
                }
                if (!allowedIds(admin, type, p, List.of("*")).contains("*")) {
                    refused.add("allowedIds " + type + " " + p);
                }
            }
        }
        assertEquals(List.of(), refused);
        assertTrue(allow(who("t", "sec", "secretaries"), ModuleResourceTypes.COMMITTEE, Permission.ADMIN, "*"),
                "a grant on '*' of the type itself");
        assertFalse(allow(who("t", "sec", "secretaries"), ModuleResourceTypes.PROGRAMME, Permission.READ, "*"),
                "a type-wide grant covers only its type");
        assertFalse(allow(admin, ModuleResourceTypes.COMMITTEE, Permission.APPROVE, "*"), "another permission bit");
        assertFalse(allow(who("u", "dev-admin", "admins"), ModuleResourceTypes.COMMITTEE, Permission.READ, "*"),
                "another tenant");
    }

    @Test
    void rowAndScopeGrantsNeverImplyStar() {
        SecurityIdentity member = who("t", "mo", "members");
        assertTrue(allow(member, ModuleResourceTypes.COMMITTEE, Permission.READ, "c1"), "the row grant itself holds");
        assertFalse(allow(member, ModuleResourceTypes.COMMITTEE, Permission.READ, "*"));
        assertFalse(allow(member, ModuleResourceTypes.COMMITTEE, Permission.ADMIN, "*"));
        assertEquals(Set.of("c1"), allowedIds(member, ModuleResourceTypes.COMMITTEE, Permission.READ, List.of("*", "c1")));
        assertFalse(allow(who("t", "cy", "gardeners"), ModuleResourceTypes.COMMITTEE, Permission.READ, "*"), "no grant");
    }

    @Test
    void aDenyOnStarWins() {
        SecurityIdentity audited = who("t", "au", "audited");
        assertFalse(allow(audited, ModuleResourceTypes.COMMITTEE, Permission.READ, "*"), "deny wins over the '*' grant");
        assertEquals(Set.of(), allowedIds(audited, ModuleResourceTypes.COMMITTEE, Permission.READ, List.of("*")));
        assertTrue(allow(audited, ModuleResourceTypes.COMMITTEE, Permission.ADMIN, "*"), "the deny names READ only");
        assertTrue(allow(audited, ModuleResourceTypes.PROGRAMME, Permission.READ, "*"), "the deny names COMMITTEE only");
    }
}
