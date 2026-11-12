// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-073 (R42, X.2.3.4 rule 4): signed Merkle checkpoints seal the chains that changed, link to each other, verify, and
 * detect a rewritten chain, a truncated chain, a forged leaf, a forged signature, an untrusted key and a deleted
 * checkpoint. Chains written before any checkpoint are accepted as not checkpointed.
 */
class AuditCheckpointsH2Test {

    static final Dialect D = Dialect.POSTGRESQL;

    record Keys(AuditCheckpoints.Signer signer, AuditCheckpoints.KeyLookup lookup) {
    }

    static Keys keys() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        return new Keys(AuditCheckpoints.ed25519(priv, pub), AuditCheckpoints.ed25519Keys(List.of(pub)));
    }

    static Transactions db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            AuditCheckpoints.create(c, D);
        }
        return Transactions.of(ds);
    }

    static void append(Transactions tx, AuditChain chain, String root, int n) {
        for (int i = 0; i < n; i++) {
            int k = i;
            tx.inTransaction(c -> chain.append(c, "t", root, "E", Map.of("i", k)));
        }
    }

    @Test
    void merkleRootHasTheRfc6962Shape() {
        AuditCheckpoints.Leaf a = new AuditCheckpoints.Leaf("a", 1, Hashes.sha256("a"));
        AuditCheckpoints.Leaf b = new AuditCheckpoints.Leaf("b", 2, Hashes.sha256("b"));
        AuditCheckpoints.Leaf c = new AuditCheckpoints.Leaf("c", 3, Hashes.sha256("c"));
        byte[] la = AuditCheckpoints.leafHash(a);
        byte[] lb = AuditCheckpoints.leafHash(b);
        byte[] lc = AuditCheckpoints.leafHash(c);
        assertArrayEquals(la, AuditCheckpoints.merkleRoot(List.of(a)));
        byte[] ab = Hashes.sha256(new byte[]{1}, la, lb);
        assertArrayEquals(ab, AuditCheckpoints.merkleRoot(List.of(a, b)));
        assertArrayEquals(Hashes.sha256(new byte[]{1}, ab, lc), AuditCheckpoints.merkleRoot(List.of(a, b, c)));
        assertFalse(java.util.Arrays.equals(AuditCheckpoints.merkleRoot(List.of(a, b)), AuditCheckpoints.merkleRoot(List.of(b, a))));
    }

    @Test
    void checkpointsSealChangedChainsLinkAndVerify() throws Exception {
        Transactions tx = db();
        AuditChain chain = new AuditChain(D);
        Keys k = keys();
        AuditCheckpoints cps = new AuditCheckpoints(tx, D, k.signer(),
                new AuditCheckpoints.Settings(Duration.ofHours(1), 2, Duration.ofHours(1)));

        append(tx, chain, "legacy", 1);   // stays unsealed when no checkpoint runs before the next assertion
        assertEquals(new AuditCheckpoints.ChainCheck(true, false, null, null, null),
                tx.inTransaction(c -> cps.verifyChain(c, "t", "legacy")), "a chain written before checkpoints is accepted");

        append(tx, chain, "r1", 3);
        append(tx, chain, "r2", 2);
        assertTrue(cps.due("t"), "first checkpoint: events exist");
        AuditCheckpoints.Checkpoint one = cps.checkpoint("t").orElseThrow();
        assertEquals(1, one.checkpointNo());
        assertEquals(3, one.chainsCount(), "legacy, r1 and r2");
        assertNull(one.prevCheckpointHash());
        assertNull(one.windowFrom());
        assertTrue(cps.checkpoint("t").isEmpty(), "nothing changed: no checkpoint");
        assertFalse(cps.due("t"));

        append(tx, chain, "r1", 2);   // 2 new entries = maxEntries
        assertTrue(cps.due("t"));
        AuditCheckpoints.Checkpoint two = cps.checkpointIfDue("t").orElseThrow();
        assertEquals(2, two.checkpointNo());
        assertEquals(1, two.chainsCount(), "only r1 changed");
        assertArrayEquals(one.checkpointHash(), two.prevCheckpointHash());
        assertEquals(one.periodEnd().minus(Duration.ofHours(1)), two.windowFrom());

        AuditCheckpoints.Verification v = tx.inTransaction(c -> cps.verifyCheckpoints(c, "t", k.lookup()));
        assertEquals(new AuditCheckpoints.Verification(true, 2, null, null), v);
        AuditCheckpoints.ChainCheck r1 = tx.inTransaction(c -> cps.verifyChain(c, "t", "r1"));
        assertTrue(r1.ok() && r1.checkpointed());
        assertEquals(5L, r1.sealedSeq());
        assertEquals(2L, r1.checkpointNo());
        assertEquals(List.of("t"), cps.tenants());
    }

    @Test
    void tamperingIsDetected() throws Exception {
        Transactions tx = db();
        AuditChain chain = new AuditChain(D);
        Keys k = keys();
        AuditCheckpoints cps = new AuditCheckpoints(tx, D, k.signer(), null);
        append(tx, chain, "r1", 3);
        append(tx, chain, "r2", 2);
        cps.checkpoint("t").orElseThrow();
        append(tx, chain, "r2", 1);
        cps.checkpoint("t").orElseThrow();

        // a whole-chain rewrite changes the sealed head
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE AUDIT_EVENT SET hash=? WHERE chain_id='r1' AND seq=3", Hashes.sha256("x")));
        AuditCheckpoints.ChainCheck rewritten = tx.inTransaction(c -> cps.verifyChain(c, "t", "r1"));
        assertFalse(rewritten.ok());
        assertTrue(rewritten.reason().contains("differs"), rewritten.reason());

        // a truncated tail removes the sealed head
        tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM AUDIT_EVENT WHERE chain_id='r2' AND seq=3"));
        assertTrue(tx.inTransaction(c -> cps.verifyChain(c, "t", "r2")).reason().contains("truncated"));

        assertTrue(tx.inTransaction(c -> cps.verifyCheckpoints(c, "t", k.lookup())).ok(), "the checkpoints themselves are intact");

        // a forged leaf no longer produces the signed root
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE AUDIT_CHECKPOINT SET head_hash=? WHERE chain_id='r1'", Hashes.sha256("x")));
        AuditCheckpoints.Verification forgedLeaf = tx.inTransaction(c -> cps.verifyCheckpoints(c, "t", k.lookup()));
        assertEquals(1L, forgedLeaf.firstBadCheckpoint());
        assertTrue(forgedLeaf.reason().contains("Merkle root"), forgedLeaf.reason());

        // an untrusted key
        AuditCheckpoints.KeyLookup stranger = keys().lookup();
        assertTrue(tx.inTransaction(c -> cps.verifyCheckpoints(c, "t", stranger)).reason().contains("not trusted"));

        // a deleted checkpoint breaks the numbering
        tx.inTransaction(c -> Jdbc.update(c, "DELETE FROM AUDIT_MERKLE_CHECKPOINT WHERE checkpoint_no=1"));
        AuditCheckpoints.Verification gap = tx.inTransaction(c -> cps.verifyCheckpoints(c, "t", k.lookup()));
        assertEquals(1L, gap.firstBadCheckpoint());
        assertTrue(gap.reason().contains("missing"), gap.reason());
    }

    @Test
    void aForgedSignatureOrStatementIsDetected() throws Exception {
        Transactions tx = db();
        AuditChain chain = new AuditChain(D);
        Keys k = keys();
        AuditCheckpoints cps = new AuditCheckpoints(tx, D, k.signer(), null);
        append(tx, chain, "r1", 1);
        AuditCheckpoints.Checkpoint cp = cps.checkpoint("t").orElseThrow();
        byte[] sig = cp.signature().clone();
        sig[0] ^= 1;
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE AUDIT_MERKLE_CHECKPOINT SET signature=?", sig));
        assertEquals("signature does not verify", tx.inTransaction(c -> cps.verifyCheckpoints(c, "t", k.lookup())).reason());
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE AUDIT_MERKLE_CHECKPOINT SET signature=?, chains_count=7", cp.signature()));
        assertEquals("statement does not match its columns",
                tx.inTransaction(c -> cps.verifyCheckpoints(c, "t", k.lookup())).reason());
    }

    @Test
    void exportWritesOutsideTheDatabaseOnceAndTheExportIsCompared(@TempDir Path dir) throws Exception {
        Transactions tx = db();
        AuditChain chain = new AuditChain(D);
        AuditCheckpoints cps = new AuditCheckpoints(tx, D, keys().signer(), null);
        append(tx, chain, "r1", 2);
        AuditCheckpoints.Checkpoint cp = cps.checkpoint("t").orElseThrow();
        assertEquals(1, cps.exportPending("t", AuditCheckpoints.directory(dir)));
        assertEquals(0, cps.exportPending("t", AuditCheckpoints.directory(dir)), "exported once");
        Path file = dir.resolve("t").resolve(String.format("%019d.json", 1));
        assertEquals(AuditCheckpoints.exportDocument(cp), Files.readString(file));
        assertTrue(cps.latest("t").orElseThrow().exportedAt() != null);
        boolean same = tx.inTransaction(c -> cps.matchesExport(c, "t", 1, cp.checkpointHash()));
        boolean other = tx.inTransaction(c -> cps.matchesExport(c, "t", 1, Hashes.sha256("other")));
        boolean absent = tx.inTransaction(c -> cps.matchesExport(c, "t", 2, cp.checkpointHash()));
        assertTrue(same);
        assertFalse(other);
        assertFalse(absent, "a checkpoint not in the database");
    }

    @Test
    void aVerifyOnlyInstanceWritesNothing() throws Exception {
        AuditCheckpoints verifyOnly = new AuditCheckpoints(db(), D, null, null);
        assertThrows(IllegalStateException.class, () -> verifyOnly.checkpoint("t"));
        assertThrows(IllegalArgumentException.class, () -> AuditCheckpoints.ed25519("bad", "bad"));
    }
}
