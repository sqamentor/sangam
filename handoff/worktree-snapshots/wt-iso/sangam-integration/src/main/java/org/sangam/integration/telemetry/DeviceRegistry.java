// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Device/Sensor registry over {@code TELEMETRY_DEVICE} (N12). A device belongs to one tenant, may be pinned to one
 * telemetry channel ({@code channel_key}: readings for it arriving on any other channel are refused) and links to an
 * N03 asset and an N13 location by reference. Sensors are not registered individually: a rule names the sensor.
 *
 * <p>The device row doubles as the per-device lock ({@link #lock}) under which {@link TelemetryRuleHandler} evaluates
 * rules, so episode creation for one device is serialised on every database.
 */
public final class DeviceRegistry {

    public record Device(String tenantId, String deviceId, String assetRef, String locationRef, String channelKey,
                         boolean enabled, Instant lastSeenAt, Instant createdAt, Instant updatedAt) {
        /** True when the device may send on {@code channelKey} (unpinned devices may send on any channel). */
        public boolean allowsChannel(String channelKey) {
            return this.channelKey == null || this.channelKey.equals(channelKey);
        }
    }

    private static final String COLUMNS =
            "tenant_id, device_id, asset_ref, location_ref, channel_key, enabled, last_seen_at, created_at, updated_at";

    private final Dialect dialect;
    private final Transactions tx;

    public DeviceRegistry(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    /** Registers a device if absent (in the caller's transaction). Returns true when it was created. */
    public boolean register(Connection c, String tenant, String deviceId, String assetRef, String locationRef, String channelKey,
                            boolean enabled) {
        requireId(tenant, "tenant");
        requireId(deviceId, "deviceId");
        return Jdbc.insertIfAbsent(c, dialect, "INSERT INTO TELEMETRY_DEVICE(" + COLUMNS + ") VALUES (?,?,?,?,?,?,NULL,"
                + dialect.now() + "," + dialect.now() + ")", tenant, deviceId, assetRef, locationRef, channelKey, enabled);
    }

    public boolean register(String tenant, String deviceId, String assetRef, String locationRef, String channelKey) {
        return tx.inTransaction(c -> register(c, tenant, deviceId, assetRef, locationRef, channelKey, true));
    }

    /** Updates the links and channel pin of an existing device. Returns false when the device does not exist. */
    public boolean update(String tenant, String deviceId, String assetRef, String locationRef, String channelKey) {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE TELEMETRY_DEVICE SET asset_ref=?, location_ref=?, channel_key=?, updated_at="
                + dialect.now() + " WHERE tenant_id=? AND device_id=?", assetRef, locationRef, channelKey, tenant, deviceId) == 1);
    }

    /** Enables or disables a device; readings of a disabled device are refused at ingestion and ignored by rules. */
    public boolean setEnabled(String tenant, String deviceId, boolean enabled) {
        return tx.inTransaction(c -> Jdbc.update(c, "UPDATE TELEMETRY_DEVICE SET enabled=?, updated_at=" + dialect.now()
                + " WHERE tenant_id=? AND device_id=?", enabled, tenant, deviceId) == 1);
    }

    public Optional<Device> find(Connection c, String tenant, String deviceId) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM TELEMETRY_DEVICE WHERE tenant_id=? AND device_id=?",
                DeviceRegistry::map, tenant, deviceId);
    }

    public Optional<Device> find(String tenant, String deviceId) {
        return tx.inTransaction(c -> find(c, tenant, deviceId));
    }

    /** Locks the device row until the caller's transaction ends (blocking row lock). */
    public Optional<Device> lock(Connection c, String tenant, String deviceId) {
        return Jdbc.one(c, dialect.lockingSelect(COLUMNS, "TELEMETRY_DEVICE", "tenant_id=? AND device_id=?"),
                DeviceRegistry::map, tenant, deviceId);
    }

    /** Records that the device was heard from, at the database clock. */
    public void touch(Connection c, String tenant, String deviceId) {
        Jdbc.update(c, "UPDATE TELEMETRY_DEVICE SET last_seen_at=" + dialect.now() + " WHERE tenant_id=? AND device_id=?",
                tenant, deviceId);
    }

    public List<Device> list(String tenant) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT " + COLUMNS + " FROM TELEMETRY_DEVICE WHERE tenant_id=? ORDER BY device_id",
                DeviceRegistry::map, tenant));
    }

    /** Devices not heard from since {@code cutoff} (or never), for a "silent device" report. */
    public List<Device> silentSince(String tenant, Instant cutoff) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT " + COLUMNS + " FROM TELEMETRY_DEVICE WHERE tenant_id=? AND enabled=1"
                + " AND (last_seen_at IS NULL OR last_seen_at < ?) ORDER BY device_id", DeviceRegistry::map, tenant, cutoff));
    }

    private static Device map(ResultSet rs) throws SQLException {
        return new Device(rs.getString("tenant_id"), rs.getString("device_id"), rs.getString("asset_ref"), rs.getString("location_ref"),
                rs.getString("channel_key"), rs.getInt("enabled") != 0, Jdbc.instant(rs, "last_seen_at"), Jdbc.instant(rs, "created_at"),
                Jdbc.instant(rs, "updated_at"));
    }

    private static void requireId(String v, String name) {
        if (v == null || v.isBlank() || v.length() > 64) {
            throw new IllegalArgumentException(name + " must be 1..64 characters");
        }
    }
}
