// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.dialect.SecurityDialect;

/**
 * SL-132 (the SL-137 design applied to sangam-security): {@code AUTH_DENIED}, {@code SOD_VIOLATION_BLOCKED} and
 * {@code POWER_LIMIT_EXCEEDED} are written by {@link SecurityAuditService#denial} to a denial-only chain through a leaf
 * transaction. H2 in PostgreSQL mode; the caller is a plain JDBC connection whose transaction is rolled back, as the
 * refused command's is.
 * <ul>
 *   <li>the denial survives the caller's rollback, on the denial chain of the chain it belongs to, and nothing is
 *       written to that chain itself;</li>
 *   <li>the denial does not wait for the caller's {@code ROOT_SEQ} row lock on the root chain or on
 *       {@code sec:<tenant>} (before SL-132 the separate transaction waited for it, the self-deadlock the old README
 *       caveat described);</li>
 *   <li>exactly one leaf transaction per denial, and none for a non-denial event;</li>
 *   <li>{@code policy_version} is the one the caller's transaction sees;</li>
 *   <li>the chain id is the documented formula, which the REST adapter's {@code DenialAudit.chainOf} repeats.</li>
 * </ul>
 */
class SecurityDenialChainH2Test {

    static final String T = "t1";
    static final SecurityIdentity ACTOR = new SecurityIdentity(T, "p-anil", "anil", Set.of(), Set.of(), Set.of(),
            Set.of(), "OIDC", null, null, false, Map.of());

    String url;
    Connection keepAlive;
    final AtomicInteger leafTransactions = new AtomicInteger();

    @BeforeEach
    void db() throws Exception {
        url = "jdbc:h2:mem:denial" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, Dialect.POSTGRESQL);   // AUDIT_EVENT, AUDIT_PAYLOAD, ROOT_SEQ, AUTH_POLICY_VERSION
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    /** The leaf pool: its own connection per transaction, counted. */
    Transactions leaf() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                leafTransactions.incrementAndGet();
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    Connection caller() throws Exception {
        Connection c = DriverManager.getConnection(url, "sa", "");
        c.setAutoCommit(false);
        return c;
    }

    long events(String chainId, String type) {
        return Jdbc.one(keepAlive, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? AND event_type=?",
                rs -> rs.getLong(1), T, chainId, type).orElse(0L);
    }

    long allEvents(String chainId) {
        return Jdbc.one(keepAlive, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?",
                rs -> rs.getLong(1), T, chainId).orElse(0L);
    }

    static Map<String, Object> payload(String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("outcome", "DENIED");
        p.put("reason", reason);
        return p;
    }

    /** The formula, computed independently of the production code. */
    static String expectedDenialChain(String tenant, String chainId) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest((tenant + "\u0000" + chainId).getBytes(StandardCharsets.UTF_8));
        return "sec:d:" + HexFormat.of().formatHex(d).substring(0, 32);
    }

    @Test
    void theDenialChainIdIsTheDocumentedFormulaAndNeverTheChainItself() throws Exception {
        String d = SecurityAuditService.denialChain(T, "sec:" + T);
        assertEquals(expectedDenialChain(T, "sec:" + T), d);
        assertEquals(38, d.length(), "fits chain_id (64)");
        assertTrue(d.startsWith(SecurityAuditService.DENIAL_PREFIX));
        assertNotEquals(SecurityAuditService.securityChain(T), d);
        String longRoot = "BK/".repeat(60);
        assertEquals(38, SecurityAuditService.denialChain(T, longRoot).length(), "a long business key still fits");
        assertNotEquals(SecurityAuditService.denialChain(T, "case-1"), SecurityAuditService.denialChain("t2", "case-1"),
                "the tenant is part of the key");
        assertNotEquals(SecurityAuditService.denialChain(T, "case-1"), SecurityAuditService.denialChain(T, "case-2"));
    }

    @Test
    void anAuthDeniedWithoutARootSurvivesTheRollbackOnTheTenantDenialChain() throws Exception {
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2, leaf());
        try (Connection c = caller()) {
            audit.denial(c, ACTOR, SecurityEventType.AUTH_DENIED, null, payload("ACTION_NOT_PERMITTED"));
            c.rollback();   // the refused command
        }
        assertEquals(1, events(SecurityAuditService.denialChain(T, "sec:" + T), "AUTH_DENIED"));
        assertEquals(0, allEvents("sec:" + T), "nothing on the tenant security chain");
        assertEquals(1, leafTransactions.get(), "one leaf transaction per denial");
    }

    @Test
    void aRootScopedDenialGoesToTheRootsDenialChainAndSurvives() throws Exception {
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2, leaf());
        try (Connection c = caller()) {
            audit.denial(c, ACTOR, SecurityEventType.SOD_VIOLATION_BLOCKED, "case-1", payload("SOD_VIOLATION"));
            audit.denial(c, ACTOR, SecurityEventType.POWER_LIMIT_EXCEEDED, "case-1", payload("over the limit"));
            c.rollback();
        }
        String d = SecurityAuditService.denialChain(T, "case-1");
        assertEquals(1, events(d, "SOD_VIOLATION_BLOCKED"));
        assertEquals(1, events(d, "POWER_LIMIT_EXCEEDED"));
        assertEquals(0, allEvents("case-1"), "nothing on the root chain");
        assertEquals(2, leafTransactions.get(), "one event per real attempt, no collapsing");
        List<Long> seqs = Jdbc.query(keepAlive, "SELECT seq FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? ORDER BY seq",
                rs -> rs.getLong(1), T, d);
        assertEquals(List.of(1L, 2L), seqs, "a gapless chain of its own");
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            assertTrue(new AuditChain(Dialect.POSTGRESQL).verify(c, T, d).ok(), "the denial chain verifies");
        }
    }

    @Test
    void theDenialDoesNotWaitForTheCallersLockOnTheRootChainOrTheSecurityChain() throws Exception {
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2, leaf());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection c = caller()) {
            // the caller appended in-line first: it now holds both ROOT_SEQ rows until it ends
            AuditChain chain = new AuditChain(Dialect.POSTGRESQL);
            chain.append(c, T, "case-1", "TASK_CLAIMED", Map.of("task_id", "k1"));
            chain.append(c, T, "sec:" + T, "GRANT_CHANGED", Map.of("grant", "g1"));
            Future<?> denials = pool.submit(() -> {
                audit.denial(c, ACTOR, SecurityEventType.SOD_VIOLATION_BLOCKED, "case-1", payload("SOD_VIOLATION"));
                audit.denial(c, ACTOR, SecurityEventType.AUTH_DENIED, null, payload("SEAT_NOT_HELD"));
                return null;
            });
            denials.get(10, TimeUnit.SECONDS);   // with the old chains this waited for c (H2 LOCK_TIMEOUT 2 s, then failed)
            c.rollback();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, events(SecurityAuditService.denialChain(T, "case-1"), "SOD_VIOLATION_BLOCKED"));
        assertEquals(1, events(SecurityAuditService.denialChain(T, "sec:" + T), "AUTH_DENIED"));
        assertEquals(0, allEvents("case-1"), "the caller's own events rolled back");
        assertEquals(0, allEvents("sec:" + T));
    }

    @Test
    void thePolicyVersionIsTheOneTheCallersTransactionSees() throws Exception {
        Jdbc.update(keepAlive, "INSERT INTO AUTH_POLICY_VERSION(tenant_id, policy_version) VALUES (?, 3)", T);
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2, leaf());
        try (Connection c = caller()) {
            Jdbc.update(c, "UPDATE AUTH_POLICY_VERSION SET policy_version=7 WHERE tenant_id=?", T);
            audit.denial(c, ACTOR, SecurityEventType.AUTH_DENIED, null, payload("ACTION_NOT_PERMITTED"));
            c.rollback();
        }
        String text = Jdbc.one(keepAlive, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                        + " WHERE e.tenant_id=? AND e.chain_id=?", rs -> rs.getString(1), T,
                SecurityAuditService.denialChain(T, "sec:" + T)).orElseThrow();
        assertTrue(text.contains("\"policy_version\":7"), text);
        assertTrue(text.contains("\"principal_id\":\"p-anil\""), "the actor envelope is kept: " + text);
        assertTrue(text.contains("\"reason\":\"ACTION_NOT_PERMITTED\""), text);
    }

    @Test
    void withoutADenialTransactionTheEventIsInLineOnTheDenialChain() throws Exception {
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2, null);
        String d = SecurityAuditService.denialChain(T, "case-9");
        try (Connection c = caller()) {
            audit.denial(c, ACTOR, SecurityEventType.SOD_VIOLATION_BLOCKED, "case-9", payload("SOD_VIOLATION"));
            assertEquals(1, Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?",
                    rs -> rs.getLong(1), T, d).orElse(0L), "in the caller's transaction");
            c.rollback();
        }
        assertEquals(0, allEvents(d), "in-line, so it rolls back with the caller");
        assertEquals(0, leafTransactions.get());
    }

    @Test
    void otherEventsStayOnTheirChainAndUseNoLeafTransaction() throws Exception {
        SecurityAuditService audit = new SecurityAuditService(SecurityDialect.H2, leaf());
        try (Connection c = caller()) {
            audit.record(c, ACTOR, SecurityEventType.AUTHORITY_DECISION, "case-1", payload("within the limit"));
            audit.security(c, ACTOR, SecurityEventType.AUTH_LOGIN, Map.of());
            c.commit();
        }
        assertEquals(1, events("case-1", "AUTHORITY_DECISION"));
        assertEquals(1, events("sec:" + T, "AUTH_LOGIN"));
        assertEquals(0, leafTransactions.get());
    }
}
