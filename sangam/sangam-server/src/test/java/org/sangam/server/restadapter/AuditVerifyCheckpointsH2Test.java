// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * M-073 in {@code JdbcAuditAdapter.verify} (additive): with checkpoints bound, a chain whose links verify is also
 * checked against the head its latest signed checkpoint sealed, and the result carries one {@code checkpoints:<tenant>}
 * entry for the tenant's checkpoints. Without checkpoints bound, verification is unchanged.
 */
class AuditVerifyCheckpointsH2Test {

    static final String T = "t1";
    static final RequestContext CTX = new RequestContext(T, "auditor", Set.of("sangam-auditor"), null, null, null, null,
            null, "OIDC");

    TxSupport tx;
    JdbcAuditAdapter audit;
    AuditCheckpoints writer;
    String trustedKey;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m073" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            AuditCheckpoints.create(c, Dialect.POSTGRESQL);
        }
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), Dialect.POSTGRESQL);
        audit = new JdbcAuditAdapter(tx, null, null, new RestProperties());
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        trustedKey = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        writer = new AuditCheckpoints(tx.newTransactions(), Dialect.POSTGRESQL, AuditCheckpoints.ed25519(priv, trustedKey),
                null);
        for (int i = 0; i < 3; i++) {
            audit.record(CTX, "root-1", "TASK_COMPLETED", Map.of("n", i));
        }
        assertTrue(writer.checkpoint(T).isPresent(), "checkpoint 1 seals root-1 at seq 3");
    }

    void bind(List<String> keys) {
        audit.setCheckpoints(new AuditCheckpoints(tx.newTransactions(), Dialect.POSTGRESQL, null, null),
                keys == null ? null : AuditCheckpoints.ed25519Keys(keys));
    }

    static AuditPort.ChainVerification entry(AuditPort.VerifyResult r, String chain) {
        return r.chains().stream().filter(v -> chain.equals(v.chainId())).findFirst().orElseThrow();
    }

    @Test
    void intactChainsAndCheckpointsVerify() {
        bind(List.of(trustedKey));
        AuditPort.VerifyResult r = audit.verify(CTX, "root-1", null);
        assertTrue(r.ok(), r.toString());
        assertEquals(3, entry(r, "root-1").eventsChecked());
        AuditPort.ChainVerification cps = entry(r, JdbcAuditAdapter.CHECKPOINTS_PREFIX + T);
        assertTrue(cps.ok());
        assertEquals(1, cps.eventsChecked(), "one checkpoint checked");
    }

    @Test
    void aChainRemovedAsAWholeIsCaughtByItsCheckpoint() {
        tx.withConnection(c -> {
            Jdbc.update(c, "DELETE FROM AUDIT_PAYLOAD WHERE event_id IN (SELECT event_id FROM AUDIT_EVENT WHERE chain_id='root-1')");
            Jdbc.update(c, "DELETE FROM AUDIT_EVENT WHERE chain_id='root-1'");
            Jdbc.update(c, "DELETE FROM ROOT_SEQ WHERE root_id='root-1'");
            return null;
        });
        assertTrue(audit.verify(CTX, "root-1", null).ok(), "without checkpoints an emptied chain links trivially");
        bind(List.of(trustedKey));
        AuditPort.VerifyResult r = audit.verify(CTX, "root-1", null);
        assertFalse(r.ok());
        AuditPort.ChainVerification v = entry(r, "root-1");
        assertFalse(v.ok());
        assertEquals(3L, v.firstBadSeq(), "the sealed head");
        assertTrue(v.reason().contains("truncated"), v.reason());
    }

    @Test
    void checkpointsSignedByAnUntrustedKeyFail() throws Exception {
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        bind(List.of(Base64.getEncoder().encodeToString(other.getPublic().getEncoded())));
        AuditPort.VerifyResult r = audit.verify(CTX, "root-1", null);
        assertFalse(r.ok());
        assertTrue(entry(r, "root-1").ok(), "the chain itself is intact");
        assertFalse(entry(r, JdbcAuditAdapter.CHECKPOINTS_PREFIX + T).ok());
    }

    @Test
    void withoutTrustedKeysThereIsNoCheckpointEntryAndWithoutCheckpointsNothingChanges() {
        bind(null);
        AuditPort.VerifyResult r = audit.verify(CTX, "root-1", null);
        assertTrue(r.ok());
        assertTrue(r.chains().stream().noneMatch(v -> v.chainId().startsWith(JdbcAuditAdapter.CHECKPOINTS_PREFIX)));
        JdbcAuditAdapter plain = new JdbcAuditAdapter(tx, null, null, new RestProperties());
        assertEquals(List.of("root-1"), plain.verify(CTX, "root-1", null).chains().stream()
                .map(AuditPort.ChainVerification::chainId).toList());
    }
}
