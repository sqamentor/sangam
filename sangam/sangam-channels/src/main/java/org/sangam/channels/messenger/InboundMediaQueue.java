// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

/**
 * The durable queue of inbound media (SANGAM.md VI.2: inbound is parked, never dropped). A webhook message's media
 * reference is written onto its own channel inbox row ({@code CH_INBOUND_SEEN}) by {@link #enqueue}, in the transaction
 * that inserts the row, so the message and its pending media commit together.
 *
 * <p>States of {@code media_state}: {@code PENDING} (due at {@code media_next_at}) → {@code CLAIMED} (by
 * {@code media_owner}, epoch {@code media_epoch}, lease until {@code media_next_at}) → {@code DONE}, back to
 * {@code PENDING} for a retry, or {@code PARKED} after the last failure. Claims use {@link Dialect#skipLockedSelect} like
 * the core inbox and relay, so nodes share the rows; a claim whose lease ran out (its node stopped) is due again and
 * claimed with the next epoch. Every finishing write is fenced by the epoch it claimed with, so a stale worker changes
 * nothing. Times are database time.
 */
public final class InboundMediaQueue {

    /** A claimed row. {@code stored} is the media already stored by an earlier attempt, or null. */
    public record Pending(String tenantId, String channel, String messageId, String externalId, String principalId, String mediaRef,
                          int attempt, long epoch, MediaDownloads.StoredMedia stored) {
    }

    private static final int MAX_ERROR = 2000;
    private static final String KEY = " WHERE tenant_id=? AND channel=? AND message_id=?";
    private static final String FENCE = KEY + " AND media_state='CLAIMED' AND media_epoch=?";

    private final Dialect dialect;
    private final Transactions tx;
    private final String node;
    private final Duration lease;

    /** @param lease how long a claim holds before another node may take the row; longer than any download and scan */
    public InboundMediaQueue(Dialect dialect, Transactions tx, String node, Duration lease) {
        if (node == null || node.isBlank() || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("node and a positive lease are required");
        }
        this.dialect = dialect;
        this.tx = tx;
        this.node = node;
        this.lease = lease;
    }

    /**
     * Records the pending media on the inbox row the caller has just inserted, in the caller's transaction.
     *
     * @return false when there is no such row
     */
    public static boolean enqueue(Connection c, Dialect d, String tenantId, String channel, String messageId, String externalId,
                                  String principalId, String mediaRef) {
        return Jdbc.update(c, "UPDATE CH_INBOUND_SEEN SET external_id=?, principal_id=?, media_ref=?, media_state='PENDING', media_attempt=0,"
                + " media_next_at=" + d.now() + ", media_owner=NULL, media_epoch=0, media_doc=NULL, media_error=NULL" + KEY,
                externalId, principalId, mediaRef, tenantId, channel, messageId) == 1;
    }

    /** Claims up to {@code batch} due rows (pending and due, or claimed with an expired lease) in its own transaction. */
    public List<Pending> claim(int batch) {
        return tx.inTransaction(c -> {
            String where = "s.media_state IN ('PENDING','CLAIMED') AND s.media_next_at <= " + dialect.now();
            List<String[]> keys = Jdbc.query(c, dialect.skipLockedSelect("s.tenant_id, s.channel, s.message_id", "CH_INBOUND_SEEN", "s", where,
                    "s.media_next_at, s.message_id", batch), rs -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
            List<Pending> out = new ArrayList<>();
            for (String[] k : keys) {
                Jdbc.update(c, "UPDATE CH_INBOUND_SEEN SET media_state='CLAIMED', media_owner=?, media_epoch=media_epoch+1, media_next_at="
                        + dialect.nowPlusMillisParam() + KEY, node, lease.toMillis(), k[0], k[1], k[2]);
                out.add(Jdbc.one(c, "SELECT tenant_id, channel, message_id, external_id, principal_id, media_ref, media_attempt, media_epoch,"
                                + " media_doc FROM CH_INBOUND_SEEN" + KEY,
                        rs -> new Pending(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                                rs.getInt(7), rs.getLong(8), stored(rs.getString(9))), k[0], k[1], k[2]).orElseThrow());
            }
            return out;
        });
    }

    /**
     * Marks the row delivered, as a write of the caller's transaction (the one that hands the media to the bot).
     *
     * @return false when the claim was lost (lease expired and taken over); the caller must then not deliver
     */
    public boolean complete(Connection c, Pending p) {
        return Jdbc.update(c, "UPDATE CH_INBOUND_SEEN SET media_state='DONE', media_owner=NULL, media_next_at=NULL, media_error=NULL" + FENCE,
                p.tenantId(), p.channel(), p.messageId(), p.epoch()) == 1;
    }

    /** Back to {@code PENDING}, due after {@code wait}, with the attempt counted (own transaction). */
    public boolean retry(Pending p, Duration wait, String reason, MediaDownloads.StoredMedia stored) {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE CH_INBOUND_SEEN SET media_state='PENDING', media_owner=NULL, media_attempt=media_attempt+1,"
                        + " media_next_at=" + dialect.nowPlusMillisParam() + ", media_doc=?, media_error=?" + FENCE,
                wait.toMillis(), stored == null ? null : Json.write(doc(stored)), clip(reason), p.tenantId(), p.channel(), p.messageId(),
                p.epoch()) == 1);
    }

    /**
     * Parks the row after its last failure, as a write of the caller's transaction (the one that also writes the audit entry).
     *
     * @return false when the claim was lost
     */
    public boolean park(Connection c, Pending p, String reason) {
        return Jdbc.update(c, "UPDATE CH_INBOUND_SEEN SET media_state='PARKED', media_owner=NULL, media_next_at=NULL, media_error=?" + FENCE,
                clip(reason), p.tenantId(), p.channel(), p.messageId(), p.epoch()) == 1;
    }

    /**
     * At start: rows this node had claimed before it stopped are due at once instead of after their lease. Only safe while
     * no other running process uses the same node name, which is the rule for node names.
     */
    public int resumeOwn() {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE CH_INBOUND_SEEN SET media_state='PENDING', media_owner=NULL, media_next_at="
                + dialect.now() + " WHERE media_state='CLAIMED' AND media_owner=?", node));
    }

    /** Rows waiting or in work (pending or claimed), all tenants. */
    public long backlog() {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM CH_INBOUND_SEEN WHERE media_state IN ('PENDING','CLAIMED')",
                rs -> rs.getLong(1)).orElse(0L));
    }

    public String node() {
        return node;
    }

    private static Map<String, Object> doc(MediaDownloads.StoredMedia s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentKey", s.documentKey());
        m.put("fileName", s.fileName());
        m.put("contentType", s.contentType());
        m.put("sizeBytes", s.sizeBytes());
        m.put("sha256", s.sha256());
        return m;
    }

    private static MediaDownloads.StoredMedia stored(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, Object> m = Json.readMap(json);
        Object size = m.get("sizeBytes");
        return new MediaDownloads.StoredMedia((String) m.get("documentKey"), (String) m.get("fileName"), (String) m.get("contentType"),
                size instanceof Number n ? n.longValue() : 0L, (String) m.get("sha256"));
    }

    private static String clip(String reason) {
        String r = reason == null ? "" : reason;
        return r.length() > MAX_ERROR ? r.substring(0, MAX_ERROR) : r;
    }
}
