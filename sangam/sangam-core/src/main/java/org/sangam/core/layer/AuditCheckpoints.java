// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Signed Merkle checkpoints over the audit chains of a tenant (M-073; R42, SANGAM.md X.2.3.4 rule 4).
 *
 * <p><b>What a checkpoint is.</b> Checkpoint {@code n} of a tenant seals every chain that changed since the chain was
 * last sealed: one leaf {@code (chain_id, last_seq, last_hash)} per chain (an {@code AUDIT_CHECKPOINT} row), sorted by
 * chain id. Its statement is the canonical JSON of {@code (tenant, n, window, period_end, merkle_root, chains_count,
 * prev_checkpoint_hash, key_id, algorithm)}; {@code checkpoint_hash = SHA-256(statement)} and the next checkpoint
 * carries it as {@code prev_checkpoint_hash}, so the checkpoints form their own chain. The statement is signed with
 * the audit-checkpoint Ed25519 key (Q-04: a key of its own per purpose and environment, held through the secret
 * provider; this class receives only a {@link Signer}). An {@link Exporter} writes each checkpoint outside the
 * database (a WORM store, the SIEM), and {@code exported_at} records it.
 *
 * <p><b>Merkle tree.</b> RFC 6962 shape: leaf hash {@code SHA-256(0x00 || JCS({chain_id, last_seq, last_hash}))},
 * node hash {@code SHA-256(0x01 || left || right)}, split at the largest power of two below the leaf count.
 *
 * <p><b>Off the hot path.</b> The checkpointer takes no {@code ROOT_SEQ} lock: it reads committed chain heads from
 * {@code AUDIT_EVENT} (plain reads; the product databases read without blocking writers). The chains that changed are
 * found by event time ({@code AUDIT_EVENT_TIME} index): every chain with an event since {@code period_end - overlap} of
 * the previous checkpoint, minus the (chain, seq) pairs already sealed. The overlap covers transactions that commit
 * after a checkpoint with an earlier {@code occurred_at} (PostgreSQL stamps the transaction start); keep it at least
 * as long as the longest transaction.
 *
 * <p><b>When.</b> {@link #due}: no checkpoint yet and the tenant has events, or {@link Settings#interval()} has passed
 * since the last one (default one hour), or {@link Settings#maxEntries()} events were written since (default 10,000)
 * (Q-04). Times are database times (A-1).
 *
 * <p><b>Verification.</b> {@link #verifyCheckpoints} checks the numbering, the statement rebuilt from the columns, the
 * hash links, every signature and every Merkle root; {@link #verifyChain} checks that a chain still holds the head its
 * latest checkpoint sealed (a rewritten or truncated chain fails). A chain written before checkpoints existed, or not
 * yet sealed, has no leaf and passes with {@code checkpointed = false}.
 */
public final class AuditCheckpoints {

    public static final String ALGORITHM = "Ed25519";
    static final String VERSION = "sangam-audit-checkpoint-v1";

    /** Portable DDL of the checkpoint tables in their current shape (changesets 0006-03 + 0038-04/05). */
    public static final List<String> TEMPLATES = List.of(
            """
            CREATE TABLE AUDIT_CHECKPOINT (
              tenant_id     {str64} NOT NULL,
              chain_id      {str64} NOT NULL,
              up_to_seq     {long} NOT NULL,
              head_hash     {bytes32} NOT NULL,
              merkle_root   {bytes32},
              signature     {blob},
              key_id        {str64},
              created_at    {ts} NOT NULL,
              exported_at   {ts},
              checkpoint_no {long},
              PRIMARY KEY (tenant_id, chain_id, up_to_seq)
            )""",
            "CREATE INDEX AUDIT_CHECKPOINT_NO ON AUDIT_CHECKPOINT (tenant_id, checkpoint_no)",
            """
            CREATE TABLE AUDIT_MERKLE_CHECKPOINT (
              tenant_id            {str64} NOT NULL,
              checkpoint_no        {long} NOT NULL,
              window_from          {ts},
              period_end           {ts} NOT NULL,
              merkle_root          {bytes32} NOT NULL,
              chains_count         {int} NOT NULL,
              prev_checkpoint_hash {bytes32},
              signed_statement     {text} NOT NULL,
              checkpoint_hash      {bytes32} NOT NULL,
              sig_algorithm        {str64} NOT NULL,
              key_id               {str64} NOT NULL,
              signature            {blob} NOT NULL,
              created_at           {ts} NOT NULL,
              exported_at          {ts},
              exported_to          {str},
              PRIMARY KEY (tenant_id, checkpoint_no)
            )""");

    /** Signs checkpoint statements with the audit-checkpoint key. */
    public interface Signer {
        String keyId();

        byte[] sign(byte[] statement);
    }

    /** Public keys by key id, for verification (the current key and the retired ones still trusted). */
    @FunctionalInterface
    public interface KeyLookup {
        Optional<PublicKey> publicKey(String keyId);
    }

    /** Writes a checkpoint outside the database; returns where (stored in {@code exported_to}). */
    @FunctionalInterface
    public interface Exporter {
        String export(Checkpoint checkpoint) throws IOException;
    }

    /**
     * @param interval   a checkpoint at least this often while the tenant writes events (Q-04 default: 1 hour)
     * @param maxEntries ... or after this many events (Q-04 default: 10,000)
     * @param overlap    how far before the previous {@code period_end} to look for changed chains (default 1 hour)
     */
    public record Settings(Duration interval, long maxEntries, Duration overlap) {
        public Settings {
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(overlap, "overlap");
            if (interval.isNegative() || interval.isZero() || maxEntries <= 0 || overlap.isNegative()) {
                throw new IllegalArgumentException("interval and maxEntries must be positive, overlap not negative");
            }
        }

        public static Settings defaults() {
            return new Settings(Duration.ofHours(1), 10_000, Duration.ofHours(1));
        }
    }

    /** One sealed chain head. */
    public record Leaf(String chainId, long lastSeq, byte[] lastHash) {
    }

    /** A stored checkpoint. */
    public record Checkpoint(String tenantId, long checkpointNo, Instant windowFrom, Instant periodEnd, byte[] merkleRoot,
                             int chainsCount, byte[] prevCheckpointHash, String statement, byte[] checkpointHash,
                             String algorithm, String keyId, byte[] signature, Instant exportedAt) {
    }

    /** Result of {@link #verifyCheckpoints}. */
    public record Verification(boolean ok, long checkpointsChecked, Long firstBadCheckpoint, String reason) {
    }

    /** Result of {@link #verifyChain}. */
    public record ChainCheck(boolean ok, boolean checkpointed, Long sealedSeq, Long checkpointNo, String reason) {
    }

    private static final class LostRace extends RuntimeException {
        LostRace() {
            super(null, null, false, false);
        }
    }

    private final Transactions tx;
    private final Dialect dialect;
    private final Signer signer;
    private final Settings settings;

    /**
     * @param signer the audit-checkpoint signer; null for a verify-only instance (no checkpoints are written)
     */
    public AuditCheckpoints(Transactions tx, Dialect dialect, Signer signer, Settings settings) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.signer = signer;
        this.settings = settings == null ? Settings.defaults() : settings;
    }

    public static List<String> ddl(Dialect d) {
        return TEMPLATES.stream().map(t -> d.expand(t) + (t.startsWith("CREATE TABLE") ? d.createTableSuffix() : "")).toList();
    }

    /** Creates both checkpoint tables on {@code c} (tests; production uses the changelog). */
    public static void create(Connection c, Dialect d) {
        try (Statement st = c.createStatement()) {
            for (String sql : ddl(d)) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("audit checkpoint schema", e);
        }
    }

    // ------------------------------------------------------------------------------------------------ keys

    /** The key id of a public key: the first 16 hex characters of SHA-256 of its encoding (as the registry signer). */
    public static String keyId(PublicKey key) {
        return Hashes.hex(Hashes.sha256(key.getEncoded())).substring(0, 16);
    }

    /** An Ed25519 signer from base64 PKCS#8 (private) and X.509 (public) encodings, resolved from the secret provider. */
    public static Signer ed25519(String pkcs8Base64, String x509Base64) {
        try {
            KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8Base64.trim())));
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(x509Base64.trim())));
            String id = keyId(pub);
            return new Signer() {
                @Override
                public String keyId() {
                    return id;
                }

                @Override
                public byte[] sign(byte[] statement) {
                    try {
                        Signature s = Signature.getInstance(ALGORITHM);
                        s.initSign(priv);
                        s.update(statement);
                        return s.sign();
                    } catch (GeneralSecurityException e) {
                        throw new IllegalStateException("audit checkpoint signing failed", e);
                    }
                }
            };
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid Ed25519 audit-checkpoint key material", e);
        }
    }

    /** Trusted Ed25519 public keys (base64 X.509), looked up by their {@link #keyId}. */
    public static KeyLookup ed25519Keys(Collection<String> x509Base64) {
        Map<String, PublicKey> byId = new LinkedHashMap<>();
        try {
            KeyFactory kf = KeyFactory.getInstance(ALGORITHM);
            for (String b64 : x509Base64) {
                PublicKey k = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim())));
                byId.put(keyId(k), k);
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid Ed25519 public key", e);
        }
        Map<String, PublicKey> keys = Map.copyOf(byId);
        return id -> Optional.ofNullable(keys.get(id));
    }

    // ------------------------------------------------------------------------------------------------ Merkle

    public static byte[] leafHash(Leaf l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chain_id", l.chainId());
        m.put("last_seq", l.lastSeq());
        m.put("last_hash", Hashes.hex(l.lastHash()));
        return Hashes.sha256(new byte[]{0}, CanonicalJson.write(m).getBytes(StandardCharsets.UTF_8));
    }

    /** The RFC 6962 Merkle tree hash of the leaves, in the given order ({@code SHA-256("")} when empty). */
    public static byte[] merkleRoot(List<Leaf> leaves) {
        if (leaves.isEmpty()) {
            return Hashes.sha256(new byte[0]);
        }
        List<byte[]> hashes = new ArrayList<>(leaves.size());
        for (Leaf l : leaves) {
            hashes.add(leafHash(l));
        }
        return tree(hashes, 0, hashes.size());
    }

    private static byte[] tree(List<byte[]> h, int from, int to) {
        int n = to - from;
        if (n == 1) {
            return h.get(from);
        }
        int k = Integer.highestOneBit(n - 1);   // the largest power of two below n
        return Hashes.sha256(new byte[]{1}, tree(h, from, from + k), tree(h, from + k, to));
    }

    static String statement(String tenant, long no, Instant windowFrom, Instant periodEnd, byte[] root, int count,
                            byte[] prevHash, String keyId, String algorithm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        m.put("tenant_id", tenant);
        m.put("checkpoint_no", no);
        m.put("window_from", windowFrom == null ? null : windowFrom.toString());
        m.put("period_end", periodEnd.toString());
        m.put("merkle_root", Hashes.hex(root));
        m.put("chains_count", count);
        m.put("prev_checkpoint_hash", prevHash == null ? null : Hashes.hex(prevHash));
        m.put("key_id", keyId);
        m.put("algorithm", algorithm);
        return CanonicalJson.write(m);
    }

    // ------------------------------------------------------------------------------------------------ writing

    /** The latest checkpoint of the tenant. */
    public Optional<Checkpoint> latest(String tenant) {
        return tx.inTransaction(c -> latest(c, tenant));
    }

    private static final String COLUMNS = "tenant_id, checkpoint_no, window_from, period_end, merkle_root, chains_count,"
            + " prev_checkpoint_hash, signed_statement, checkpoint_hash, sig_algorithm, key_id, signature, exported_at";

    private static Checkpoint map(java.sql.ResultSet rs) throws SQLException {
        return new Checkpoint(rs.getString(1), rs.getLong(2), Jdbc.instant(rs, "window_from"), Jdbc.instant(rs, "period_end"),
                rs.getBytes(5), rs.getInt(6), rs.getBytes(7), rs.getString(8), rs.getBytes(9), rs.getString(10),
                rs.getString(11), rs.getBytes(12), Jdbc.instant(rs, "exported_at"));
    }

    private static Optional<Checkpoint> latest(Connection c, String tenant) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM AUDIT_MERKLE_CHECKPOINT WHERE tenant_id=? AND checkpoint_no ="
                + " (SELECT MAX(checkpoint_no) FROM AUDIT_MERKLE_CHECKPOINT WHERE tenant_id=?)", AuditCheckpoints::map, tenant, tenant);
    }

    /** Whether a checkpoint of the tenant is due (Q-04: interval or entry count). */
    public boolean due(String tenant) {
        return tx.inTransaction(c -> {
            Optional<Checkpoint> prev = latest(c, tenant);
            if (prev.isEmpty()) {
                return Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=?", rs -> rs.getLong(1), tenant)
                        .orElse(0L) > 0;
            }
            Instant now = Jdbc.dbNow(c, dialect);
            long since = Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND occurred_at > ?",
                    rs -> rs.getLong(1), tenant, prev.get().periodEnd()).orElse(0L);
            if (since == 0) {
                return false;   // nothing new to seal
            }
            return since >= settings.maxEntries()
                    || Duration.between(prev.get().periodEnd(), now).compareTo(settings.interval()) >= 0;
        });
    }

    /** Writes a checkpoint when {@link #due}; empty when not due, when nothing changed, or when another node won. */
    public Optional<Checkpoint> checkpointIfDue(String tenant) {
        return due(tenant) ? checkpoint(tenant) : Optional.empty();
    }

    /** Tenants that have audit events (read without locks), for a scheduler. */
    public List<String> tenants() {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT DISTINCT tenant_id FROM ROOT_SEQ WHERE next_audit_seq > 1"
                + " ORDER BY tenant_id", rs -> rs.getString(1)));
    }

    /** One scheduler pass: {@link #checkpointIfDue} for every tenant with events. */
    public List<Checkpoint> runDue() {
        List<Checkpoint> out = new ArrayList<>();
        for (String t : tenants()) {
            checkpointIfDue(t).ifPresent(out::add);
        }
        return out;
    }

    /**
     * Writes the next checkpoint of the tenant now: the chains changed since the previous one, signed. Empty when no
     * chain changed or another node wrote this number first.
     */
    public Optional<Checkpoint> checkpoint(String tenant) {
        if (signer == null) {
            throw new IllegalStateException("no audit-checkpoint signer configured (verify-only instance)");
        }
        try {
            return tx.inTransaction(c -> write(c, tenant));
        } catch (LostRace e) {
            return Optional.empty();
        }
    }

    private Optional<Checkpoint> write(Connection c, String tenant) {
        Instant now = Jdbc.dbNow(c, dialect).truncatedTo(ChronoUnit.MICROS);
        Optional<Checkpoint> prev = latest(c, tenant);
        Instant from = prev.map(p -> p.periodEnd().minus(settings.overlap())).orElse(null);
        String heads = "SELECT e.chain_id, e.seq, e.hash FROM AUDIT_EVENT e WHERE e.tenant_id=?"
                + " AND e.seq = (SELECT MAX(x.seq) FROM AUDIT_EVENT x WHERE x.tenant_id=e.tenant_id AND x.chain_id=e.chain_id)"
                + (from == null ? "" : " AND e.chain_id IN (SELECT y.chain_id FROM AUDIT_EVENT y WHERE y.tenant_id=? AND y.occurred_at >= ?)")
                + " AND NOT EXISTS (SELECT 1 FROM AUDIT_CHECKPOINT k WHERE k.tenant_id=e.tenant_id AND k.chain_id=e.chain_id"
                + " AND k.up_to_seq=e.seq) ORDER BY e.chain_id";
        Jdbc.RowMapper<Leaf> leafMap = rs -> new Leaf(rs.getString(1), rs.getLong(2), rs.getBytes(3));
        List<Leaf> leaves = from == null ? Jdbc.query(c, heads, leafMap, tenant) : Jdbc.query(c, heads, leafMap, tenant, tenant, from);
        if (leaves.isEmpty()) {
            return Optional.empty();
        }
        leaves = new ArrayList<>(leaves);
        leaves.sort((a, b) -> a.chainId().compareTo(b.chainId()));   // Java order, independent of the database collation
        long no = prev.map(p -> p.checkpointNo() + 1).orElse(1L);
        byte[] root = merkleRoot(leaves);
        byte[] prevHash = prev.map(Checkpoint::checkpointHash).orElse(null);
        String statement = statement(tenant, no, from, now, root, leaves.size(), prevHash, signer.keyId(), ALGORITHM);
        byte[] bytes = statement.getBytes(StandardCharsets.UTF_8);
        byte[] hash = Hashes.sha256(bytes);
        byte[] signature = signer.sign(bytes);
        boolean inserted;
        try {
            inserted = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO AUDIT_MERKLE_CHECKPOINT(tenant_id, checkpoint_no, window_from,"
                            + " period_end, merkle_root, chains_count, prev_checkpoint_hash, signed_statement, checkpoint_hash,"
                            + " sig_algorithm, key_id, signature, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenant, no, from, now, root, leaves.size(), prevHash, statement, hash, ALGORITHM, signer.keyId(), signature, now);
        } catch (Jdbc.LayerSqlException e) {
            if (dialect.isUniqueViolation(e.sql())) {
                throw new LostRace();
            }
            throw e;
        }
        if (!inserted) {
            throw new LostRace();   // another node wrote checkpoint `no`: roll back, nothing of ours is kept
        }
        List<Object[]> rows = new ArrayList<>(leaves.size());
        for (Leaf l : leaves) {
            rows.add(new Object[]{tenant, l.chainId(), l.lastSeq(), l.lastHash(), root, signer.keyId(), now, no});
        }
        Jdbc.batch(c, "INSERT INTO AUDIT_CHECKPOINT(tenant_id, chain_id, up_to_seq, head_hash, merkle_root, key_id, created_at,"
                + " checkpoint_no) VALUES (?,?,?,?,?,?,?,?)", rows);
        return Optional.of(new Checkpoint(tenant, no, from, now, root, leaves.size(), prevHash, statement, hash, ALGORITHM,
                signer.keyId(), signature, null));
    }

    /**
     * Exports every checkpoint of the tenant not exported yet, in order, and records {@code exported_at} and where.
     * Stops at the first failure (the rest stays pending for the next run).
     *
     * @return checkpoints exported
     */
    public int exportPending(String tenant, Exporter exporter) {
        List<Checkpoint> pending = tx.inTransaction(c -> Jdbc.query(c, "SELECT " + COLUMNS + " FROM AUDIT_MERKLE_CHECKPOINT"
                + " WHERE tenant_id=? AND exported_at IS NULL ORDER BY checkpoint_no", AuditCheckpoints::map, tenant));
        int n = 0;
        for (Checkpoint cp : pending) {
            String where;
            try {
                where = exporter.export(cp);
            } catch (IOException e) {
                throw new IllegalStateException("export of audit checkpoint " + cp.checkpointNo() + " of " + tenant + " failed", e);
            }
            String to = where == null ? null : (where.length() > 255 ? where.substring(0, 255) : where);
            tx.inTransaction(c -> Jdbc.update(c, "UPDATE AUDIT_MERKLE_CHECKPOINT SET exported_at=" + dialect.now()
                    + ", exported_to=? WHERE tenant_id=? AND checkpoint_no=?", to, tenant, cp.checkpointNo()));
            n++;
        }
        return n;
    }

    /** The exported form of a checkpoint: its signed statement, signature, hash and key id (canonical JSON). */
    public static String exportDocument(Checkpoint cp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statement", cp.statement());
        m.put("checkpoint_hash", Hashes.hex(cp.checkpointHash()));
        m.put("algorithm", cp.algorithm());
        m.put("key_id", cp.keyId());
        m.put("signature", Base64.getEncoder().encodeToString(cp.signature()));
        return CanonicalJson.write(m);
    }

    /**
     * An exporter writing {@code <dir>/<tenant>/<checkpoint_no>.json} (write then atomic rename; an existing file is
     * never overwritten). Point it at storage under a different administrative account (X.2.3.4 rule 4).
     */
    public static Exporter directory(Path dir) {
        return cp -> {
            Path tenantDir = dir.resolve(safe(cp.tenantId()));
            Files.createDirectories(tenantDir);
            Path target = tenantDir.resolve(String.format("%019d.json", cp.checkpointNo()));
            if (Files.exists(target)) {
                if (!Files.readString(target, StandardCharsets.UTF_8).equals(exportDocument(cp))) {
                    throw new IOException("a different checkpoint " + cp.checkpointNo() + " was already exported to " + target);
                }
                return target.toString();
            }
            Path tmp = tenantDir.resolve(target.getFileName() + ".tmp");
            Files.writeString(tmp, exportDocument(cp), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return target.toString();
        };
    }

    private static String safe(String tenant) {
        return tenant.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ------------------------------------------------------------------------------------------------ verification

    /** Verifies every checkpoint of the tenant: numbering, statement, hash links, signatures, Merkle roots. */
    public Verification verifyCheckpoints(Connection c, String tenant, KeyLookup keys) {
        List<Checkpoint> all = Jdbc.query(c, "SELECT " + COLUMNS + " FROM AUDIT_MERKLE_CHECKPOINT WHERE tenant_id=?"
                + " ORDER BY checkpoint_no", AuditCheckpoints::map, tenant);
        byte[] prevHash = null;
        long expected = 1;
        long n = 0;
        for (Checkpoint cp : all) {
            long no = cp.checkpointNo();
            if (no != expected) {
                return new Verification(false, n, expected, "checkpoint " + expected + " is missing (found " + no + ")");
            }
            if (!Arrays.equals(prevHash, cp.prevCheckpointHash())) {
                return new Verification(false, n, no, "prev_checkpoint_hash does not link to checkpoint " + (no - 1));
            }
            String rebuilt = statement(tenant, no, cp.windowFrom(), cp.periodEnd(), cp.merkleRoot(), cp.chainsCount(),
                    cp.prevCheckpointHash(), cp.keyId(), cp.algorithm());
            if (!rebuilt.equals(cp.statement())) {
                return new Verification(false, n, no, "statement does not match its columns");
            }
            byte[] bytes = cp.statement().getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(Hashes.sha256(bytes), cp.checkpointHash())) {
                return new Verification(false, n, no, "checkpoint hash mismatch");
            }
            Optional<PublicKey> key = keys.publicKey(cp.keyId());
            if (key.isEmpty()) {
                return new Verification(false, n, no, "signing key " + cp.keyId() + " is not trusted");
            }
            if (!ALGORITHM.equals(cp.algorithm()) || !verifySignature(key.get(), bytes, cp.signature())) {
                return new Verification(false, n, no, "signature does not verify");
            }
            record Row(Leaf leaf, byte[] root) {
            }
            List<Row> rows = Jdbc.query(c, "SELECT chain_id, up_to_seq, head_hash, merkle_root FROM AUDIT_CHECKPOINT"
                            + " WHERE tenant_id=? AND checkpoint_no=?",
                    rs -> new Row(new Leaf(rs.getString(1), rs.getLong(2), rs.getBytes(3)), rs.getBytes(4)), tenant, no);
            List<Leaf> leaves = new ArrayList<>();
            for (Row r : rows) {
                if (!Arrays.equals(r.root(), cp.merkleRoot())) {
                    return new Verification(false, n, no, "leaf of chain " + r.leaf().chainId() + " names another root");
                }
                leaves.add(r.leaf());
            }
            leaves.sort((a, b) -> a.chainId().compareTo(b.chainId()));
            if (leaves.size() != cp.chainsCount() || !Arrays.equals(merkleRoot(leaves), cp.merkleRoot())) {
                return new Verification(false, n, no, "leaves do not produce the signed Merkle root");
            }
            prevHash = cp.checkpointHash();
            expected++;
            n++;
        }
        return new Verification(true, n, null, null);
    }

    /**
     * Whether the chain still holds the head its latest checkpoint sealed. Run with {@link AuditChain#verify}, which
     * checks the links; this check catches a chain rewritten or truncated as a whole, head included.
     */
    public ChainCheck verifyChain(Connection c, String tenant, String chainId) {
        Optional<Object[]> sealed = Jdbc.one(c, "SELECT up_to_seq, head_hash, checkpoint_no FROM AUDIT_CHECKPOINT"
                        + " WHERE tenant_id=? AND chain_id=? AND up_to_seq = (SELECT MAX(up_to_seq) FROM AUDIT_CHECKPOINT"
                        + " WHERE tenant_id=? AND chain_id=?)",
                rs -> new Object[]{rs.getLong(1), rs.getBytes(2), rs.getObject(3) == null ? null : rs.getLong(3)},
                tenant, chainId, tenant, chainId);
        if (sealed.isEmpty()) {
            return new ChainCheck(true, false, null, null, null);   // written before checkpoints, or not sealed yet
        }
        long seq = (Long) sealed.get()[0];
        byte[] head = (byte[]) sealed.get()[1];
        Long no = (Long) sealed.get()[2];
        Optional<byte[]> stored = Jdbc.one(c, "SELECT hash FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? AND seq=?",
                rs -> rs.getBytes(1), tenant, chainId, seq);
        if (stored.isEmpty()) {
            return new ChainCheck(false, true, seq, no, "event " + seq + " sealed by checkpoint " + no + " is missing (truncated)");
        }
        if (!Arrays.equals(stored.get(), head)) {
            return new ChainCheck(false, true, seq, no, "event " + seq + " differs from the head sealed by checkpoint " + no);
        }
        return new ChainCheck(true, true, seq, no, null);
    }

    /**
     * Compares the database with an exported checkpoint (the copy outside the database): the checkpoint must still
     * exist with the same hash. A deleted or rewritten tail of checkpoints fails here.
     */
    public boolean matchesExport(Connection c, String tenant, long checkpointNo, byte[] exportedHash) {
        return Jdbc.one(c, "SELECT checkpoint_hash FROM AUDIT_MERKLE_CHECKPOINT WHERE tenant_id=? AND checkpoint_no=?",
                rs -> rs.getBytes(1), tenant, checkpointNo).map(h -> Arrays.equals(h, exportedHash)).orElse(false);
    }

    private static boolean verifySignature(PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
