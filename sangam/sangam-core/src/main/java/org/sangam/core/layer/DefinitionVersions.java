// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.sql.Connection;

/**
 * Version allocation for the event registry's definitions ({@code EVT_EVENT_DEF}, {@code EVT_CHANNEL_DEF}) on the
 * high-water mark table {@code EVT_DEFINITION_SEQ}. Shared by every writer of those tables (the ports'
 * {@code EventRegistry} and the REST JDBC adapter), so a version number is never handed out twice: not after every
 * version of a key was deleted ({@code MAX(version)+1} restarted at 1) and not to two concurrent deployers (the mark
 * row is locked until the caller's transaction ends). Rows that predate the mark are taken into account.
 */
public final class DefinitionVersions {

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
        String kind = kindOf(table);
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
