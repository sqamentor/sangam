// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.sync;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldAudit;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.evidence.PacketHasher;
import org.sangam.field.support.FieldSql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Field device registration (offline sync step 1). A device is bound to one principal (and optionally one dispatch
 * unit), registers its public key for packet signatures and receives the queue policy and a queue key id. Revoked or
 * lost devices are refused on every sync and pull; with {@code wipeOnRevoke} the client crypto-erases its queue.
 */
public final class DeviceRegistry {

    public enum Status { ACTIVE, REVOKED, LOST }

    public record Device(String tenantId, String deviceId, String principalId, String unitId, String platform, String appVersion,
                         String publicKey, String keyAlg, Status status, QueuePolicy policy, String queueKeyId, long lastSeq,
                         String lastPacketHash, Instant registeredAt, Instant lastSeenAt, String statusReason) {

        public Map<String, Object> view() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("deviceId", deviceId);
            m.put("principalId", principalId);
            m.put("unitId", unitId);
            m.put("platform", platform);
            m.put("appVersion", appVersion);
            m.put("keyAlg", keyAlg);
            m.put("hasKey", publicKey != null);
            m.put("status", status.name());
            m.put("policy", policy);
            m.put("queueKeyId", queueKeyId);
            m.put("lastSeq", lastSeq);
            m.put("lastPacketHash", lastPacketHash);
            m.put("registeredAt", registeredAt);
            m.put("lastSeenAt", lastSeenAt);
            m.put("statusReason", statusReason);
            return m;
        }
    }

    public record Registration(String deviceId, String unitId, String platform, String appVersion, String publicKey, String keyAlg) {
    }

    private final Transactions tx;
    private final Dialect dialect;
    private final FieldAudit audit;
    private final QueuePolicy defaultPolicy;
    private final Clock clock;

    public DeviceRegistry(Transactions tx, Dialect dialect, FieldAudit audit, QueuePolicy defaultPolicy, Clock clock) {
        this.tx = tx;
        this.dialect = dialect;
        this.audit = audit;
        this.defaultPolicy = defaultPolicy == null ? QueuePolicy.defaults() : defaultPolicy;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Registers (or re-registers the same principal's) device. A device id bound to another principal is refused. */
    public Device register(String tenantId, String principalId, Registration r) {
        if (r.publicKey() != null && !r.publicKey().isBlank()) {
            PacketHasher.requireValidKey(r.publicKey(), r.keyAlg());
        }
        String id = r.deviceId() == null || r.deviceId().isBlank() ? FieldSql.newId() : r.deviceId();
        return tx.inTransaction(c -> {
            Instant now = clock.instant();
            var existing = find(c, tenantId, id);
            if (existing.isPresent()) {
                Device d = existing.get();
                if (!d.principalId().equals(principalId)) {
                    throw new FieldException("FORBIDDEN", "device " + id + " is registered to another principal");
                }
                if (d.status() != Status.ACTIVE) {
                    throw new FieldException("DEVICE_REVOKED", "device " + id + " is " + d.status());
                }
                Jdbc.update(c, "UPDATE FLD_DEVICE SET platform = ?, app_version = ?, unit_id = ?, last_seen_at = ? WHERE tenant_id = ? "
                        + "AND device_id = ?", r.platform(), r.appVersion(), r.unitId(), now, tenantId, id);
                if (r.publicKey() != null && !r.publicKey().isBlank() && d.publicKey() == null) {
                    Jdbc.update(c, "UPDATE FLD_DEVICE SET public_key = ?, key_alg = ? WHERE tenant_id = ? AND device_id = ?",
                            r.publicKey(), r.keyAlg() == null ? "EC" : r.keyAlg(), tenantId, id);
                }
                return find(c, tenantId, id).orElseThrow();
            }
            Jdbc.update(c, "INSERT INTO FLD_DEVICE (tenant_id, device_id, principal_id, unit_id, platform, app_version, public_key, key_alg, "
                            + "status, policy_json, queue_key_id, last_seq, registered_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                    tenantId, id, principalId, r.unitId(), r.platform(), r.appVersion(), blankToNull(r.publicKey()),
                    r.publicKey() == null || r.publicKey().isBlank() ? null : (r.keyAlg() == null ? "EC" : r.keyAlg()), Status.ACTIVE,
                    FieldJson.write(defaultPolicy), "qk-" + FieldSql.newId(), now, now);
            audit.record(c, tenantId, "device:" + id, FieldAudit.DEVICE_REGISTERED, principalId,
                    Map.of("deviceId", id, "platform", String.valueOf(r.platform()), "signed", r.publicKey() != null));
            return find(c, tenantId, id).orElseThrow();
        });
    }

    public Device setStatus(String tenantId, String deviceId, Status status, String reason, String actor) {
        return tx.inTransaction(c -> {
            Device d = require(c, tenantId, deviceId);
            Jdbc.update(c, "UPDATE FLD_DEVICE SET status = ?, status_reason = ?, status_changed_at = ? WHERE tenant_id = ? AND device_id = ?",
                    status, reason, clock.instant(), tenantId, deviceId);
            audit.record(c, tenantId, "device:" + deviceId, FieldAudit.DEVICE_STATUS, actor,
                    Map.of("from", d.status().name(), "to", status.name(), "reason", reason == null ? "" : reason));
            return require(c, tenantId, deviceId);
        });
    }

    public Device setPolicy(String tenantId, String deviceId, QueuePolicy policy) {
        return tx.inTransaction(c -> {
            require(c, tenantId, deviceId);
            Jdbc.update(c, "UPDATE FLD_DEVICE SET policy_json = ? WHERE tenant_id = ? AND device_id = ?", FieldJson.write(policy),
                    tenantId, deviceId);
            return require(c, tenantId, deviceId);
        });
    }

    public Device get(String tenantId, String deviceId) {
        return tx.inTransaction(c -> require(c, tenantId, deviceId));
    }

    public List<Device> listForPrincipal(String tenantId, String principalId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT * FROM FLD_DEVICE WHERE tenant_id = ? AND principal_id = ? ORDER BY registered_at",
                DeviceRegistry::map, tenantId, principalId));
    }

    /** The device for a sync call: must exist, be ACTIVE and belong to the caller. */
    public Device requireActive(Connection c, String tenantId, String deviceId, String principalId) {
        Device d = find(c, tenantId, deviceId).orElseThrow(() -> new FieldException("DEVICE_UNKNOWN", "device not registered: " + deviceId));
        if (d.status() != Status.ACTIVE) {
            throw new FieldException("DEVICE_REVOKED", "device " + deviceId + " is " + d.status());
        }
        if (principalId != null && !principalId.equals(d.principalId())) {
            throw new FieldException("FORBIDDEN", "device " + deviceId + " belongs to another principal");
        }
        return d;
    }

    /** Advances the device chain head after an accepted packet (highest seq wins; hash of that packet). */
    public void advance(Connection c, String tenantId, String deviceId, long seq, String packetHash) {
        Instant now = clock.instant();
        Jdbc.update(c, "UPDATE FLD_DEVICE SET last_seq = ?, last_packet_hash = ?, last_seen_at = ? WHERE tenant_id = ? AND device_id = ? "
                + "AND last_seq < ?", seq, packetHash, now, tenantId, deviceId, seq);
        Jdbc.update(c, "UPDATE FLD_DEVICE SET last_seen_at = ? WHERE tenant_id = ? AND device_id = ?", now, tenantId, deviceId);
    }

    /** Records contact (pull) without moving the chain head. */
    public void touch(Connection c, String tenantId, String deviceId) {
        Jdbc.update(c, "UPDATE FLD_DEVICE SET last_seen_at = ? WHERE tenant_id = ? AND device_id = ?", clock.instant(), tenantId, deviceId);
    }

    Device require(Connection c, String tenantId, String deviceId) {
        return find(c, tenantId, deviceId).orElseThrow(() -> FieldException.notFound("device", deviceId));
    }

    java.util.Optional<Device> find(Connection c, String tenantId, String deviceId) {
        return Jdbc.one(c, "SELECT * FROM FLD_DEVICE WHERE tenant_id = ? AND device_id = ?", DeviceRegistry::map, tenantId, deviceId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Device map(ResultSet rs) throws SQLException {
        String pol = rs.getString("policy_json");
        return new Device(rs.getString("tenant_id"), rs.getString("device_id"), rs.getString("principal_id"), rs.getString("unit_id"),
                rs.getString("platform"), rs.getString("app_version"), rs.getString("public_key"), rs.getString("key_alg"),
                Status.valueOf(rs.getString("status")), pol == null ? QueuePolicy.defaults() : FieldJson.read(pol, QueuePolicy.class),
                rs.getString("queue_key_id"), rs.getLong("last_seq"), rs.getString("last_packet_hash"), Jdbc.instant(rs, "registered_at"),
                Jdbc.instant(rs, "last_seen_at"), rs.getString("status_reason"));
    }

    Dialect dialect() {
        return dialect;
    }
}
