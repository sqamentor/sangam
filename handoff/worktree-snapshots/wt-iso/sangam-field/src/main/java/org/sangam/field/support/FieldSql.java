// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.support;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.geo.model.GeoPoint;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** Shared JDBC helpers: coordinate encoding, nullable column reads, per-tenant sequences and the change feed. */
public final class FieldSql {

    private FieldSql() {
    }

    public static String newId() {
        return Hashes.uuidV7().toString();
    }

    public static Long e7(Double degrees) {
        return degrees == null ? null : Math.round(degrees * 1e7);
    }

    public static Long e7lat(GeoPoint p) {
        return p == null ? null : Math.round(p.lat() * 1e7);
    }

    public static Long e7lon(GeoPoint p) {
        return p == null ? null : Math.round(p.lon() * 1e7);
    }

    public static GeoPoint point(ResultSet rs, String latCol, String lonCol) throws SQLException {
        Long la = lng(rs, latCol), lo = lng(rs, lonCol);
        return la == null || lo == null ? null : new GeoPoint(la / 1e7, lo / 1e7);
    }

    public static Long lng(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static Boolean bool(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v != 0;
    }

    public static Long round(Double d) {
        return d == null || d.isNaN() ? null : Math.round(d);
    }

    /**
     * Allocates the next value of a per-tenant sequence. The UPDATE takes the row lock, so concurrent writers are
     * serialised and values are gap-free within committed transactions (portable across the four dialects).
     */
    public static long nextSeq(Connection c, Dialect d, String tenantId, String name) {
        int n = Jdbc.update(c, "UPDATE FLD_SEQ SET next_value = next_value + 1 WHERE tenant_id = ? AND seq_name = ?", tenantId, name);
        if (n == 0) {
            Jdbc.insertIfAbsent(c, d, "INSERT INTO FLD_SEQ (tenant_id, seq_name, next_value) VALUES (?, ?, 0)", tenantId, name);
            Jdbc.update(c, "UPDATE FLD_SEQ SET next_value = next_value + 1 WHERE tenant_id = ? AND seq_name = ?", tenantId, name);
        }
        return Jdbc.one(c, "SELECT next_value FROM FLD_SEQ WHERE tenant_id = ? AND seq_name = ?", rs -> rs.getLong(1), tenantId, name)
                .orElseThrow();
    }

    /** Appends to the device change feed (pull cursor). {@code principalId} null = visible to every device of the tenant. */
    public static long recordChange(Connection c, Dialect d, String tenantId, String kind, String entityId, String principalId,
                                    String op, Instant now) {
        long seq = nextSeq(c, d, tenantId, "change");
        Jdbc.update(c, "INSERT INTO FLD_CHANGE (tenant_id, change_seq, entity_kind, entity_id, principal_id, op, changed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", tenantId, seq, kind, entityId, principalId, op, now);
        return seq;
    }
}
