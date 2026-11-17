// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.lifecycle;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * SL-132: erasure of an instance tree also erases the payloads on each instance's related audit chains (the
 * {@code sec:d:} denial chain, named by the {@link LifecyclePorts.RelatedAuditChains} port the server supplies from
 * sangam-security). Chains of other roots, and their denial chains, are kept; every chain still verifies.
 */
class ErasureDenialChainsTest {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String T = "T1";

    String url;
    Connection keepAlive;
    final AuditChain audit = new AuditChain(D);

    /** A stand-in for the server's port: one denial chain per chain (the real id is SecurityAuditService.denialChain). */
    static final LifecyclePorts.RelatedAuditChains DENIALS = (tenant, chain) -> List.of("den:" + tenant + ":" + chain);

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:erasedenial" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
    }

    @AfterEach
    void tearDown() throws Exception {
        keepAlive.close();
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
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

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.read(c);
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

    ErasureService service(LifecyclePorts.RelatedAuditChains related) {
        LifecyclePorts.EngineInstancePort engine = new LifecyclePorts.EngineInstancePort() {
            @Override
            public void businessCancel(Connection c, String instanceId, String reason) {
            }

            @Override
            public void technicalDelete(Connection c, String instanceId, String reason) {
            }

            @Override
            public List<String> instanceTree(Connection c, String instanceId) {
                return "r1".equals(instanceId) ? List.of("r1", "r1-sub") : List.of(instanceId);
            }
        };
        LifecyclePorts.Authorizer allow = (c, tenant, principal, permission, instance) -> { };
        return related == null
                ? new ErasureService(D, tx(), audit, new LegalHoldStore(D), new ConnectorCallJournal(D), new DeadLetterStore(D, tx()),
                        engine, null, null, allow)
                : new ErasureService(D, tx(), audit, new LegalHoldStore(D), new ConnectorCallJournal(D), new DeadLetterStore(D, tx()),
                        engine, null, null, allow, related);
    }

    String event(String chain) {
        return tx().inTransaction(c -> audit.append(c, T, chain, "SOD_VIOLATION_BLOCKED", Map.of("actor", "u1", "note", "personal")).eventId());
    }

    boolean erased(String eventId) {
        return tx().inTransaction(c -> Jdbc.one(c, "SELECT erased_at FROM AUDIT_PAYLOAD WHERE event_id=?",
                rs -> rs.getTimestamp(1) != null, eventId).orElseThrow());
    }

    @Test
    void theDenialChainsOfTheTreeAreErasedWithIt() {
        String root = event("r1");
        String sub = event("r1-sub");
        String rootDenial = event("den:T1:r1");
        String subDenial = event("den:T1:r1-sub");
        String other = event("r2");
        String otherDenial = event("den:T1:r2");

        ErasureService.Result r = service(DENIALS).erase(T, "r1", List.of(), "dpo-1", "DSR erasure");

        assertTrue(erased(root));
        assertTrue(erased(sub));
        assertTrue(erased(rootDenial), "the root's denial chain is erased with it");
        assertTrue(erased(subDenial), "the sub-instance's denial chain too");
        assertTrue(!erased(other) && !erased(otherDenial), "another root and its denials are kept");
        assertEquals(4, r.scrubbed().get("auditPayloads"));
        tx().inTransaction(c -> {
            for (String chain : List.of("r1", "r1-sub", "den:T1:r1", "den:T1:r1-sub")) {
                assertTrue(audit.verify(c, T, chain).ok(), chain + " still verifies after erasure");
            }
            return null;
        });
        String payload = tx().inTransaction(c -> Jdbc.one(c, "SELECT payload FROM AUDIT_PAYLOAD WHERE event_id=?",
                rs -> rs.getString(1) == null ? "" : rs.getString(1), rootDenial).orElseThrow());
        assertEquals("", payload, "the denial payload is gone");
    }

    @Test
    void auditChainsListsEachInstanceThenItsRelatedChainsOnce() {
        assertEquals(List.of("r1", "den:T1:r1", "r1-sub", "den:T1:r1-sub"),
                service(DENIALS).auditChains(T, List.of("r1", "r1-sub", "r1")));
        assertEquals(List.of("r1"), service(null).auditChains(T, List.of("r1")), "without the port: the instance chains only");
    }

    @Test
    void withoutThePortTheDenialChainIsNotTouched() {
        String rootDenial = event("den:T1:r1");
        event("r1");
        service(null).erase(T, "r1", List.of(), "dpo-1", "DSR erasure");
        assertNotNull(rootDenial);
        assertTrue(!erased(rootDenial), "the old constructor names no related chains");
        assertNull(LifecyclePorts.RelatedAuditChains.NONE.of(T, "r1").stream().findFirst().orElse(null));
    }
}
