// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.sql.Connection;

/**
 * Version allocation for the event registry's definitions ({@code EVT_EVENT_DEF}, {@code EVT_CHANNEL_DEF}) on the
 * high-water mark table {@code EVT_DEFINITION_SEQ}. Shared by every writer of those tables (the ports'
 * {@code EventRegistry} and the REST JDBC adapter), so a version number is never handed out twice: not after every
 * version of a key was deleted ({@code MAX(version)+1} restarted at 1) and not to two concurrent deployers (the mark
 * row is locked until the caller's transaction ends). Rows that predate the mark are taken into account.
 *
 * <p>The mark row of a {@code (tenant, kind, key)} is also the serialisation point of every deployer of that key:
 * {@link #lockKind} row-locks it until the caller's transaction ends. Kinds: {@code event} and {@code channel} (the event
 * registry tables, versions allocated by {@link #next}) and {@link #PACK} (SL-116: pack deploy and rollback of one pack
 * in one tenant scope; pack versions are semantic versions kept by the engine, so for this kind the row is a lock only
 * and {@code last_version} stays 0).
 */
public final class DefinitionVersions {

    /** {@code def_kind} of a pack: the lock of the pack's deploy and rollback in one tenant scope (SL-116). */
    public static final String PACK = "pack";

    private static final java.util.Set<String> KINDS = java.util.Set.of("event", "channel", PACK);

    private DefinitionVersions() {
    }

    /** {@code def_kind} of a definition table: {@code event} or {@code channel}. */
    public static String kindOf(String table) {
        return switch (table) {
            case "EVT_EVENT_DEF" -> "event";
            case "EVT_CHANNEL_DEF" -> "channel";
            default -> throw new IllegalArgumentException("not an event registry table: " + table);
        };
    }

    /** Creates (if absent) and row-locks the key's mark; returns the last version handed out. */
    public static int lock(Connection c, Dialect dialect, String table, String tenant, String key) {
        return lockKind(c, dialect, kindOf(table), tenant, key);
    }

    /**
     * SL-116: creates (if absent) and row-locks the pack's mark in the tenant scope ({@code null} or blank = shared,
     * stored as {@link TenantKey#SHARED}) until the caller's transaction ends. A second caller blocks on the row (bounded
     * by the session's lock wait; Oracle {@code FOR UPDATE WAIT 2}) and then sees what the first one committed.
     */
    public static void lockPack(Connection c, Dialect dialect, String tenantOrNull, String packId) {
        lockKind(c, dialect, PACK, TenantKey.of(tenantOrNull), packId);
    }

    /** Creates (if absent) and row-locks the mark of {@code (tenant, kind, key)}; returns its last version. */
    public static int lockKind(Connection c, Dialect dialect, String kind, String tenant, String key) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("not a definition kind: " + kind);
        }
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO EVT_DEFINITION_SEQ(tenant_id, def_kind, def_key, last_version) VALUES (?,?,?,0)",
                tenant, kind, key);
        return Jdbc.one(c, dialect.lockingSelect("last_version", "EVT_DEFINITION_SEQ", "tenant_id = ? AND def_kind = ? AND def_key = ?"),
                rs -> rs.getInt(1), tenant, kind, key).orElseThrow();
    }

    /** Allocates the next version of {@code key} in the caller's transaction. */
    public static int next(Connection c, Dialect dialect, String table, String keyCol, String tenant, String key) {
        int mark = lock(c, dialect, table, tenant, key);
        int existing = Jdbc.one(c, "SELECT MAX(version) FROM " + table + " WHERE tenant_id = ? AND " + keyCol + " = ?",
                rs -> rs.getInt(1), tenant, key).orElse(0);
        int next = Math.max(mark, existing) + 1;
        Jdbc.update(c, "UPDATE EVT_DEFINITION_SEQ SET last_version = ? WHERE tenant_id = ? AND def_kind = ? AND def_key = ?",
                next, tenant, kindOf(table), key);
        return next;
    }
}
