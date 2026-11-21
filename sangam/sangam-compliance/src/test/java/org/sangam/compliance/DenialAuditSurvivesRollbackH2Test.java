// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.compliance.config.ComplianceConfig;
import org.sangam.compliance.consent.ChildSafeguards.ProcessingFlag;
import org.sangam.compliance.jurisdiction.JurisdictionPack;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.lawful.PurposeDefinition;
import org.sangam.compliance.ports.CompliancePorts.PrincipalCategory;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

/**
 * M-009 (XII.4 #3): compliance denial audits survive the rollback of the refused operation, without the SL-43 pattern
 * (no second connection of the caller's pool) and without waiting on a {@code ROOT_SEQ} row the caller holds.
 * H2 in PostgreSQL mode; the caller is a plain connection with an open transaction, the denial pool a separate
 * connection factory on the same database.
 */
class DenialAuditSurvivesRollbackH2Test {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "t1";
    static final String PRINCIPAL = "person-1";
    static final JurisdictionRegistry PACKS = JurisdictionRegistry.discover();
    static final String PACK = "EU-GDPR";

    String url;
    Connection keepAlive;
    final AtomicInteger denialTransactions = new AtomicInteger();

    @BeforeEach
    void db() throws Exception {
        // LOCK_TIMEOUT bounds a wait on a row the caller holds, so a regression fails fast instead of hanging
        url = "jdbc:h2:mem:m009" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        ComplianceSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    /** The leaf pool: every unit of work on a new connection of its own, counted. */
    Transactions leaf() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                denialTransactions.incrementAndGet();
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
                denialTransactions.incrementAndGet();
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

    /** The caller's transaction must never be used for the separate denial: fail loudly if anything asks it. */
    static final Transactions CALLER_POOL_UNUSED = new Transactions() {
        @Override
        public <T> T inTransaction(Function<Connection, T> work) {
            throw new AssertionError("a denial must not open a transaction on the caller's pool (SL-43)");
        }

        @Override
        public <T> T read(ReadWork<T> work) {
            throw new AssertionError("a denial must not open a transaction on the caller's pool (SL-43)");
        }
    };

    SangamCompliance compliance(Transactions denialTx) {
        ComplianceConfig.Provider config = t -> ComplianceConfig.fromProperties(PACKS, Map.of("jurisdiction", PACK));
        SangamCompliance.Ports ports = new SangamCompliance.Ports(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        return new SangamCompliance(D, CALLER_POOL_UNUSED, config, ports, denialTx, org.sangam.compliance.PrincipalKey.of(new byte[32]));
    }

    Connection caller() throws Exception {
        Connection c = DriverManager.getConnection(url, "sa", "");
        c.setAutoCommit(false);
        return c;
    }

    long events(String chainId, String type) {
        return Jdbc.one(keepAlive, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? AND event_type=?",
                rs -> rs.getLong(1), TENANT, chainId, type).orElse(0L);
    }

    static PurposeDefinition advertisingPurpose() {
        return new PurposeDefinition("ADS", Map.of("en", "Ads"), null, null, "CONSENT", null, null, null, false, null,
                null, null, Set.of(ProcessingFlag.TARGETED_ADVERTISING), null, null, null, null, null, null, 1, true, null);
    }

    @Test
    void denialChainIsDistinctFromThePrincipalChainAndFitsChainId() {
        String denial = ComplianceAudit.denialChain(TENANT, PRINCIPAL);
        String principal = ComplianceAudit.principalChain(TENANT, PRINCIPAL);
        assertTrue(denial.startsWith("cmp:d:"));
        assertNotEquals(principal, denial);
        // same principal hash, so an auditor finds a principal's denials from the principal chain id
        assertEquals(principal.substring("cmp:p:".length()), denial.substring("cmp:d:".length()));
        assertTrue(denial.length() <= 64, "chain_id is VARCHAR(64)");
    }

    @Test
    void purposeBindingDenialSurvivesTheCallersRollback() throws Exception {
        SangamCompliance cmp = compliance(leaf());
        try (Connection c = caller()) {
            ComplianceException e = assertThrows(ComplianceException.class, () ->
                    cmp.purposeBinding.require(c, TENANT, PRINCIPAL, "NOT_REGISTERED", null, Set.of()));
            assertEquals(ComplianceException.Code.PURPOSE_NOT_PERMITTED, e.code());
            c.rollback();
        }
        assertEquals(1, events(ComplianceAudit.denialChain(TENANT, PRINCIPAL), ComplianceAudit.PURPOSE_BINDING_DENIED));
        assertEquals(0, events(ComplianceAudit.principalChain(TENANT, PRINCIPAL), ComplianceAudit.PURPOSE_BINDING_DENIED));
        assertEquals(1, denialTransactions.get());
    }

    @Test
    void childSafeguardDenialSurvivesTheCallersRollback() throws Exception {
        SangamCompliance cmp = compliance(leaf());
        JurisdictionPack pack = PACKS.require(PACK);
        try (Connection c = caller()) {
            ComplianceException e = assertThrows(ComplianceException.class, () ->
                    cmp.children.check(c, pack, TENANT, PRINCIPAL, PrincipalCategory.CHILD, advertisingPurpose(), Set.of()));
            assertEquals(ComplianceException.Code.CHILD_PROCESSING_FORBIDDEN, e.code());
            c.rollback();
        }
        assertEquals(1, events(ComplianceAudit.denialChain(TENANT, PRINCIPAL), ComplianceAudit.CHILD_PROCESSING_BLOCKED));
        assertEquals(1, denialTransactions.get());
    }

    /**
     * The ROOT_SEQ hazard the README named: the caller already appended to the principal's chain in the same
     * transaction (it holds that chain's ROOT_SEQ row). The separate denial transaction must not wait for it; it writes
     * the denial-only chain. Red if the denial went to the principal chain: the leaf append waits on the caller's row
     * until LOCK_TIMEOUT and fails.
     */
    @Test
    void denialDoesNotWaitOnAChainTheCallerHolds() throws Exception {
        SangamCompliance cmp = compliance(leaf());
        try (Connection c = caller()) {
            cmp.audit.principal(c, TENANT, PRINCIPAL, ComplianceAudit.CONSENT_GIVEN, Map.of("purpose", "X"));
            ComplianceException e = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThrows(ComplianceException.class, () ->
                            cmp.purposeBinding.require(c, TENANT, PRINCIPAL, "NOT_REGISTERED", null, Set.of())));
            assertEquals(ComplianceException.Code.PURPOSE_NOT_PERMITTED, e.code());
            c.rollback();
        }
        assertEquals(1, events(ComplianceAudit.denialChain(TENANT, PRINCIPAL), ComplianceAudit.PURPOSE_BINDING_DENIED));
        assertEquals(0, events(ComplianceAudit.principalChain(TENANT, PRINCIPAL), ComplianceAudit.CONSENT_GIVEN),
                "the caller's own consent event rolled back with it");
    }

    @Test
    void repeatedDenialsChainOnTheDenialChainAndVerify() throws Exception {
        SangamCompliance cmp = compliance(leaf());
        for (int i = 0; i < 3; i++) {
            try (Connection c = caller()) {
                assertThrows(ComplianceException.class, () ->
                        cmp.purposeBinding.require(c, TENANT, PRINCIPAL, "NOT_REGISTERED", null, Set.of()));
                c.rollback();
            }
        }
        String chain = ComplianceAudit.denialChain(TENANT, PRINCIPAL);
        assertEquals(3, events(chain, ComplianceAudit.PURPOSE_BINDING_DENIED));
        assertTrue(cmp.integrity.verifyChain(keepAlive, TENANT, chain).ok());
    }

    /** Without a denial pool (tests, embedders) the event is in-line on the denial chain and rolls back with the caller. */
    @Test
    void withoutADenialPoolTheEventIsInLine() throws Exception {
        SangamCompliance cmp = compliance(null);
        try (Connection c = caller()) {
            assertThrows(ComplianceException.class, () ->
                    cmp.purposeBinding.require(c, TENANT, PRINCIPAL, "NOT_REGISTERED", null, Set.of()));
            assertEquals(1, Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?",
                    rs -> rs.getLong(1), TENANT, ComplianceAudit.denialChain(TENANT, PRINCIPAL)).orElse(0L));
            c.rollback();
        }
        assertEquals(0, events(ComplianceAudit.denialChain(TENANT, PRINCIPAL), ComplianceAudit.PURPOSE_BINDING_DENIED));
        assertEquals(0, denialTransactions.get());
    }
}
