// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.PolicyCompiler;
import org.sangam.security.policy.ResourceRegistry;

/**
 * M-086 (R19, VI.4 rule 4, K11): the set-based list projection {@link FieldMaskService#maskAll} equals calling the
 * per-row {@link FieldMaskService#mask} for each row, for any policy set, rule set, row list and caller:
 * <ul>
 *   <li>the masked rows are equal (same fields masked, same order, a copy of every row);</li>
 *   <li>the {@code SENSITIVE_READ} events are the same: same count, same order, same payload.</li>
 * </ul>
 * Generated: grants and denies on ids, on {@code '*'} and on the definition and business-key scopes, for users, groups
 * and roles; conditional rows whose condition compiles, whose condition the compiler drops (a grant then never matches,
 * a deny always applies), and the case-variant and unknown ids; a table-backed type ({@code HISTORIC_PROCESS_INSTANCE})
 * and a virtual type ({@code TENANT}); a signed-in caller, a caller with no grant, and the system identity.
 * H2 in PostgreSQL mode, as the server runs it. Each try runs on one connection: the per-row run and the set run each
 * from the same savepoint, then everything is rolled back. Runs in the test phase only (not run here).
 */
class FieldMaskSetEquivalencePropertiesTest {

    static final String T = "t";
    static final String HPI = ResourceRegistry.HISTORIC_PROCESS_INSTANCE;
    static final String VIRTUAL = ResourceRegistry.TENANT;
    static final String SCOPE = "claim-form";
    static final List<String> FIELDS = List.of("amount", "aadhaar", "remarks");
    static final List<String> TABLE_IDS = List.of("p1", "p2", "p3", "p4", "p5", "P1", "zz", "q1");
    static final List<String> VIRTUAL_IDS = List.of("t", "u", "T", "x");
    static final List<String> PRINCIPALS = List.of("user:al", "group:clerks", "role:auditor", "user:bo", "group:other");
    static final List<String> TABLE_RESOURCE_IDS = List.of("p1", "p2", "p3", "*", "def:claim", "def:leave", "bk:BK-1", "bk:BK-4");
    static final List<String> VIRTUAL_RESOURCE_IDS = List.of("t", "u", "*");
    /** c1 compiles (state), c2 compiles (start user is null), c3 names no stored column: dropped by the compiler. */
    static final Map<String, String> CONDITIONS = Map.of(
            "c1", "{\"attr\":\"state\",\"op\":\"eq\",\"value\":\"ACTIVE\"}",
            "c2", "{\"attr\":\"startUserId\",\"op\":\"isNull\"}",
            "c3", "{\"attr\":\"noSuchColumn\",\"op\":\"eq\",\"value\":\"x\"}");
    static final AtomicLong VERSION = new AtomicLong(1_000_000);

    static Connection c;
    static FieldMaskService masks;

    record Policy(String principal, String resourceId, boolean deny, long perms, String conditionId) {
    }

    record Rule(String field, Permission required) {
    }

    record Scenario(boolean virtual, List<Policy> policies, List<Rule> rules, List<Map<String, Object>> rows, int caller) {
    }

    @BeforeContainer
    static void db() throws Exception {
        c = DriverManager.getConnection("jdbc:h2:mem:mask" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE", "sa", "");
        Schema.create(c, Dialect.POSTGRESQL);   // AUTH_EFFECTIVE, AUTH_POLICY_VERSION, AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ
        Jdbc.update(c, "CREATE TABLE AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64),"
                + " resource_type VARCHAR(64), expression TEXT)");
        Jdbc.update(c, "CREATE TABLE SEC_FIELD_RULE (tenant_id VARCHAR(64) NOT NULL, resource_type VARCHAR(64) NOT NULL,"
                + " scope_key VARCHAR(255) NOT NULL, field_name VARCHAR(255) NOT NULL, required_perm VARCHAR(32) NOT NULL,"
                + " PRIMARY KEY (tenant_id, resource_type, scope_key, field_name))");
        Jdbc.update(c, "CREATE TABLE ACT_HI_PROCINST (ID_ VARCHAR(64), TENANT_ID_ VARCHAR(64), PROC_DEF_KEY_ VARCHAR(255),"
                + " BUSINESS_KEY_ VARCHAR(255), PROC_INST_ID_ VARCHAR(64), START_USER_ID_ VARCHAR(255), STATE_ VARCHAR(255))");
        Jdbc.update(c, "INSERT INTO ACT_HI_PROCINST VALUES ('p1','t','claim','BK-1','p1',NULL,'ACTIVE'),"
                + " ('p2','t','claim','BK-2','p2','al','ACTIVE'), ('p3','t','leave','BK-3','p3',NULL,'COMPLETED'),"
                + " ('p4','t','leave','BK-4','p4','bo','ACTIVE'), ('p5','t','claim','BK-1','p5',NULL,'COMPLETED'),"
                + " ('q1','u','claim','BK-1','q1',NULL,'ACTIVE')");
        Jdbc.update(c, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES ('t', 0)");
        c.commit();
        c.setAutoCommit(false);
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2);
        masks = new FieldMaskService(new AuthorizationService(
                new PolicyCompiler(SecurityDialect.H2, new ResourceRegistry(), Duration.ZERO), audit), audit);
    }

    @AfterContainer
    static void close() throws Exception {
        c.close();
    }

    static SecurityIdentity caller(int i) {
        return switch (i) {
            case 0 -> new SecurityIdentity(T, "al", "al", Set.of("clerks"), Set.of("auditor"), Set.of(), Set.of(), "OIDC",
                    null, null, false, Map.of());
            case 1 -> new SecurityIdentity(T, "bo", "bo", Set.of("other"), Set.of(), Set.of(), Set.of(), "OIDC", null,
                    null, false, Map.of());
            case 2 -> new SecurityIdentity(T, "cy", "cy", Set.of(), Set.of(), Set.of(), Set.of(), "OIDC", null, null,
                    false, Map.of());
            default -> SecurityIdentity.system(T, "test");
        };
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Arbitraries.of(false, true).flatMap(virtual -> {
            List<String> resourceIds = virtual ? VIRTUAL_RESOURCE_IDS : TABLE_RESOURCE_IDS;
            List<String> rowIds = virtual ? VIRTUAL_IDS : TABLE_IDS;
            Arbitrary<Long> perms = Arbitraries.of(Permission.READ_VARIABLE.bit(), Permission.READ_SENSITIVE.bit(),
                    Permission.READ_VARIABLE.bit() | Permission.READ_SENSITIVE.bit(), Permission.READ.bit());
            Arbitrary<Policy> policy = Combinators.combine(Arbitraries.of(PRINCIPALS), Arbitraries.of(resourceIds),
                    Arbitraries.of(false, false, true), perms, Arbitraries.of("", "", "c1", "c2", "c3"))
                    .as((p, r, d, m, cond) -> new Policy(p, r, d, m, cond.isEmpty() ? null : cond));
            Arbitrary<Rule> rule = Combinators.combine(Arbitraries.of(FIELDS),
                    Arbitraries.of(Permission.READ_VARIABLE, Permission.READ_SENSITIVE)).as(Rule::new);
            Arbitrary<Map<String, Object>> row = Combinators.combine(Arbitraries.of(rowIds),
                    Arbitraries.subsetOf(FIELDS)).as((id, fields) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", id);
                        for (String f : FIELDS) {
                            if (fields.contains(f)) {
                                m.put(f, f + "-of-" + id);
                            }
                        }
                        m.put("note", "kept");
                        return m;
                    });
            return Combinators.combine(policy.list().ofMaxSize(10), rule.list().ofMaxSize(4).uniqueElements(Rule::field),
                    row.list().ofMaxSize(40), Arbitraries.integers().between(0, 3))
                    .as((ps, rs, rows, who) -> new Scenario(virtual, ps, rs, rows, who));
        });
    }

    @Property(tries = 300)
    void theSetProjectionEqualsThePerRowMasks(@ForAll("scenarios") Scenario s) throws Exception {
        String type = s.virtual() ? VIRTUAL : HPI;
        SecurityIdentity who = caller(s.caller());
        try {
            install(s, type);
            Savepoint start = c.setSavepoint();
            List<Map<String, Object>> perRow = new ArrayList<>();
            for (Map<String, Object> row : s.rows()) {
                perRow.add(masks.mask(c, who, type, String.valueOf(row.get("id")), SCOPE, row));
            }
            List<String> perRowEvents = sensitiveReads();
            c.rollback(start);
            List<Map<String, Object>> set = masks.maskAll(c, who, type, SCOPE, s.rows(), "id");
            List<String> setEvents = sensitiveReads();
            assertEquals(perRow, set, "masked rows");
            assertEquals(perRowEvents, setEvents, "SENSITIVE_READ events (count, order, payload)");
        } finally {
            c.rollback();
        }
    }

    @Property(tries = 200)
    void allowedIdsEqualsTheObjectCheckPerId(@ForAll("scenarios") Scenario s) throws Exception {
        String type = s.virtual() ? VIRTUAL : HPI;
        SecurityIdentity who = caller(s.caller());
        AuthorizationService authz = new AuthorizationService(
                new PolicyCompiler(SecurityDialect.H2, new ResourceRegistry(), Duration.ZERO), new SecurityAuditService(SecurityDialect.H2));
        try {
            install(s, type);
            List<String> ids = s.rows().stream().map(r -> String.valueOf(r.get("id"))).toList();
            for (Permission p : List.of(Permission.READ_VARIABLE, Permission.READ_SENSITIVE)) {
                Set<String> set = authz.allowedIds(c, who, type, p, ids);
                for (String id : ids) {
                    assertEquals(authz.allow(c, who, type, p, id, Map.of()), set.contains(id), p + " on " + id);
                }
                assertEquals(true, ids.containsAll(set), "only requested ids");
            }
        } finally {
            c.rollback();
        }
    }

    /** Writes the scenario's grants, denies, conditions and rules, with a policy version no earlier try used. */
    static void install(Scenario s, String type) {
        for (Map.Entry<String, String> e : CONDITIONS.entrySet()) {
            Jdbc.update(c, "INSERT INTO AUTH_CONDITION(tenant_id, condition_id, resource_type, expression) VALUES (?,?,?,?)",
                    T, e.getKey(), type, e.getValue());
        }
        for (Policy p : s.policies()) {
            String condKey = p.conditionId() == null ? "-" : p.conditionId();
            int deny = p.deny() ? 1 : 0;
            long existing = Jdbc.one(c, "SELECT perms FROM AUTH_EFFECTIVE WHERE tenant_id=? AND principal_id=? AND resource_type=?"
                            + " AND resource_id=? AND deny_flag=? AND cond_key=?", rs -> rs.getLong(1),
                    T, p.principal(), type, p.resourceId(), deny, condKey).orElse(-1L);
            if (existing < 0) {
                Jdbc.update(c, "INSERT INTO AUTH_EFFECTIVE(tenant_id, principal_id, resource_type, resource_id, deny_flag, perms,"
                        + " condition_id, cond_key, policy_version) VALUES (?,?,?,?,?,?,?,?,0)",
                        T, p.principal(), type, p.resourceId(), deny, p.perms(), p.conditionId(), condKey);
            } else {
                Jdbc.update(c, "UPDATE AUTH_EFFECTIVE SET perms=? WHERE tenant_id=? AND principal_id=? AND resource_type=?"
                        + " AND resource_id=? AND deny_flag=? AND cond_key=?",
                        existing | p.perms(), T, p.principal(), type, p.resourceId(), deny, condKey);
            }
        }
        for (Rule r : s.rules()) {
            Jdbc.update(c, "INSERT INTO SEC_FIELD_RULE(tenant_id, resource_type, scope_key, field_name, required_perm)"
                    + " VALUES (?,?,?,?,?)", T, type, SCOPE, r.field(), r.required().name());
        }
        Jdbc.update(c, "UPDATE AUTH_POLICY_VERSION SET policy_version=? WHERE tenant_id=?", VERSION.incrementAndGet(), T);
    }

    static List<String> sensitiveReads() {
        return Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                + " WHERE e.tenant_id=? AND e.event_type='SENSITIVE_READ' ORDER BY e.seq", rs -> rs.getString(1), T);
    }
}
