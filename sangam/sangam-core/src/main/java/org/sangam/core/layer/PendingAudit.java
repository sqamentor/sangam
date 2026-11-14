// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable pending audit (SL-133, M-003; R42): class-B events recorded by a writer that must not take the root's
 * {@code ROOT_SEQ} lock, appended to the root's {@link AuditChain} later by a drain.
 *
 * <p><b>Why.</b> A JavaDelegate connector call runs inside the job's transaction C1, which holds the root's
 * {@code ROOT_SEQ} row lock (the L2 surrogate, taken by the engine's job guard). The connector invoker writes its
 * ledger in its own transaction C2 on a second pool connection, so the intent commits before the remote call (R37).
 * Appending the audit event in C2 needs the same row lock: C2 waits for C1 and C1 waits for the thread blocked in C2
 * (a self-deadlock, until the lock timeout). Appending it in C1 instead loses the dispatch and failed-attempt events
 * when C1 rolls back.
 *
 * <p><b>How.</b> {@link #record} inserts the event into {@code AUDIT_PENDING} inside the caller's transaction (C2),
 * atomically with the ledger and without touching {@code ROOT_SEQ}. {@link #drain} runs later in its own
 * transaction, on a thread that holds no lock: it takes the root's {@code ROOT_SEQ} lock <i>first</i> (so it waits for
 * C1 to end, as any other writer of the root does), reads the root's pending rows in {@code pending_id} order,
 * appends each to the chain and deletes it, all in one transaction. A crash before commit leaves the rows for the next
 * drain; two drains of one root serialise on the lock, and the second finds nothing. The rows of one root are written
 * while some transaction of that root holds its lock, so their {@code pending_id} order is their write order.
 *
 * <p>The appended payload is the recorded payload plus {@code recordedAt} (the database time of {@link #record}); the
 * envelope's {@code occurred_at} is the time of the append, as for every chain event.
 */
public final class PendingAudit {

    /** Portable DDL of {@code AUDIT_PENDING} (the Liquibase changelog holds the same table, changeset 0038-01). */
    public static final List<String> TEMPLATES = List.of(
            """
            CREATE TABLE AUDIT_PENDING (
              pending_id  {identity} PRIMARY KEY,
              tenant_id   {str64} NOT NULL,
              chain_id    {str64} NOT NULL,
              event_type  {str64} NOT NULL,
              payload     {text} NOT NULL,
              recorded_at {ts} NOT NULL,
              attempts    {int} NOT NULL,
              last_error  {str}
            )""",
            "CREATE INDEX AUDIT_PENDING_CHAIN ON AUDIT_PENDING (tenant_id, chain_id, pending_id)",
            "CREATE INDEX AUDIT_PENDING_DUE ON AUDIT_PENDING (recorded_at)");

    /** A root (chain) with pending rows. */
    public record ChainRef(String tenantId, String chainId) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR = 255;

    private final Transactions tx;
    private final Dialect dialect;
    private final AuditChain chain;

    /**
     * @param tx      plain transactions on the product pool (never the engine transaction)
     * @param dialect the product database dialect
     * @param chain   the audit chain the drain appends to
     */
    public PendingAudit(Transactions tx, Dialect dialect, AuditChain chain) {
        this.tx = java.util.Objects.requireNonNull(tx, "tx");
        this.dialect = java.util.Objects.requireNonNull(dialect, "dialect");
        this.chain = java.util.Objects.requireNonNull(chain, "chain");
    }

    public static List<String> ddl(Dialect d) {
        return TEMPLATES.stream().map(t -> d.expand(t) + (t.startsWith("CREATE TABLE") ? d.createTableSuffix() : "")).toList();
    }

    /** Creates {@code AUDIT_PENDING} on {@code c} (tests and the spike schema; production uses the changelog). */
    public static void create(Connection c, Dialect d) {
        try (Statement st = c.createStatement()) {
            for (String sql : ddl(d)) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("AUDIT_PENDING schema", e);
        }
    }

    /**
     * Records one event of {@code chainId} inside the caller's transaction. Takes no {@code ROOT_SEQ} lock, so it is
     * safe on a connection other than the one holding the root's lock.
     */
    public void record(Connection c, String tenant, String chainId, String eventType, Map<String, ?> payload) {
        AuditChain.requireTenant(tenant, eventType);
        Jdbc.update(c, "INSERT INTO AUDIT_PENDING(tenant_id, chain_id, event_type, payload, recorded_at, attempts)"
                        + " VALUES (?,?,?,?," + dialect.now() + ",0)",
                tenant, chainId, eventType, CanonicalJson.write(payload == null ? Map.of() : payload));
    }

    /** Pending rows of one chain (committed ones, as seen by a new transaction). */
    public long count(String tenant, String chainId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_PENDING WHERE tenant_id=? AND chain_id=?",
                rs -> rs.getLong(1), tenant, chainId).orElse(0L));
    }

    /**
     * Appends every pending row of the chain to it, in {@code pending_id} order, and deletes them, in one transaction
     * that takes the root's {@code ROOT_SEQ} lock before anything else. Call it from a thread that holds no lock of
     * this root (never from inside the transaction that holds it: that is the deadlock this class exists to avoid).
     *
     * @return the number of events appended (0 when nothing was pending)
     */
    public int drain(String tenant, String chainId) {
        if (count(tenant, chainId) == 0) {
            return 0;   // nothing to do: never wait for the root's lock in vain
        }
        return tx.inTransaction(c -> {
            RootSeq.lock(c, dialect, tenant, chainId);   // L2 first; waits while a job of this root holds it
            record Row(long id, String type, String payload, Instant recordedAt) {
            }
            List<Row> rows = Jdbc.query(c, "SELECT pending_id, event_type, payload, recorded_at FROM AUDIT_PENDING"
                            + " WHERE tenant_id=? AND chain_id=? ORDER BY pending_id",
                    rs -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), Jdbc.instant(rs, "recorded_at")),
                    tenant, chainId);
            for (Row r : rows) {
                Map<String, Object> payload = parse(r.payload());
                if (r.recordedAt() != null) {
                    payload.putIfAbsent("recordedAt", r.recordedAt().truncatedTo(ChronoUnit.MICROS).toString());
                }
                chain.append(c, tenant, chainId, r.type(), payload);
                if (Jdbc.update(c, "DELETE FROM AUDIT_PENDING WHERE pending_id=?", r.id()) != 1) {
                    // the root lock serialises drains, so another drain cannot have taken it: fail and roll back
                    throw new IllegalStateException("pending audit row " + r.id() + " vanished during the drain of " + chainId);
                }
            }
            return rows.size();
        });
    }

    /** Notes a failed drain on the chain's rows (operator visibility); its own short transaction, no root lock. */
    public void recordFailure(String tenant, String chainId, String message) {
        String m = message == null ? "drain failed" : message;
        String text = m.length() > MAX_ERROR ? m.substring(0, MAX_ERROR) : m;
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE AUDIT_PENDING SET attempts=attempts+1, last_error=?"
                + " WHERE tenant_id=? AND chain_id=?", text, tenant, chainId));
    }

    /**
     * Chains with a row recorded at least {@code minAge} ago (database clock), oldest first, at most {@code limit}:
     * the sweep that catches rows whose drain signal was lost (crash, restart, a drain that timed out).
     */
    public List<ChainRef> due(Duration minAge, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        long ageMs = minAge == null ? 0 : Math.max(0, minAge.toMillis());
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT " + Dialect.limitMarker(limit) + "tenant_id, chain_id, MIN(pending_id) AS first_id"
                        + " FROM AUDIT_PENDING WHERE recorded_at <= " + dialect.nowPlusMillisParam()
                        + " GROUP BY tenant_id, chain_id ORDER BY first_id",
                rs -> new ChainRef(rs.getString(1), rs.getString(2)), -ageMs));
    }

    private static Map<String, Object> parse(String json) {
        try {
            Map<String, Object> m = JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return m == null ? new LinkedHashMap<>() : m;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("pending audit payload is not a JSON object", e);
        }
    }
}
