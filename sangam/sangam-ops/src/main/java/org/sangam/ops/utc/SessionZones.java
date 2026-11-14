// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import java.sql.Connection;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * Owner decision 240: the session time zone of the tool's own connection, for the columns a database stores converted
 * by that zone (MySQL and MariaDB {@code timestamp}: {@link UtcInventory#MYSQL_SESSION_ZONED}). With a run's declared
 * historical session zone set, such a column reads back as the wall clock the engine wrote; with {@link #UTC} set it
 * reads and writes the stored instant. Every other dialect stores zone-aware types and has no session-zoned column.
 */
interface SessionZones {

    /** The session zone that reads and writes stored instants. */
    String UTC = "+00:00";

    /** The {@code TABLE.COLUMN} names stored converted by the session zone; empty when the dialect has none. */
    Set<String> zonedColumns();

    /** The connection's current session zone. */
    String current(Connection c);

    /** Sets the connection's session zone (a named zone needs the server's time-zone tables, otherwise it fails). */
    void set(Connection c, String zone);

    /** MySQL and MariaDB: {@code @@session.time_zone}. */
    SessionZones MYSQL = new SessionZones() {
        @Override
        public Set<String> zonedColumns() {
            return UtcInventory.MYSQL_SESSION_ZONED;
        }

        @Override
        public String current(Connection c) {
            return Jdbc.one(c, "SELECT @@session.time_zone", rs -> rs.getString(1)).orElseThrow();
        }

        @Override
        public void set(Connection c, String zone) {
            Jdbc.update(c, "SET time_zone = ?", zone);
        }
    };

    /** Dialects without session-zoned columns. */
    SessionZones NONE = new SessionZones() {
        @Override
        public Set<String> zonedColumns() {
            return Set.of();
        }

        @Override
        public String current(Connection c) {
            throw new IllegalStateException("this dialect has no session-zoned column");
        }

        @Override
        public void set(Connection c, String zone) {
            throw new IllegalStateException("this dialect has no session-zoned column");
        }
    };

    static SessionZones of(Dialect dialect) {
        return dialect == Dialect.MYSQL ? MYSQL : NONE;
    }

    /** The session zone text for {@code zone}: the region id, or {@code +HH:MM} for an offset ({@code Z} as +00:00). */
    static String text(ZoneId zone) {
        if (zone instanceof ZoneOffset o) {
            return o.getTotalSeconds() == 0 ? UTC : o.getId();
        }
        return zone.getId();
    }

    /**
     * A declared historical session zone: a region with its rules or a whole-minute offset (MySQL sessions also use
     * offsets such as {@code +05:30}); a seconds offset is refused.
     */
    static ZoneId requireDeclarable(ZoneId zone) {
        if (zone instanceof ZoneOffset o && o.getTotalSeconds() % 60 != 0) {
            throw new IllegalArgumentException("a session zone offset has whole minutes: " + zone);
        }
        if (zone != null && !(zone instanceof ZoneOffset) && zone.normalized() instanceof ZoneOffset
                && !zone.getId().equals("UTC") && !zone.getId().equals("Etc/UTC")) {
            throw new IllegalArgumentException("declare the offset itself (+05:30) or a named zone, not " + zone);
        }
        return zone;
    }
}
