// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * M-092: the schema status of this installation, read with plain JDBC (no framework), for
 * {@code GET /ops/schema-status} and {@code sangam upgrade check --server}.
 *
 * <ul>
 *   <li>Product changesets: the rows of Liquibase's {@code DATABASECHANGELOG} from the product master changelog, in
 *       execution order. The engine patches ({@code sangam-engine-patches.xml}, run on every start) and the optional
 *       history partitioning changelog are not product changesets and are left out.</li>
 *   <li>Engine: Operaton's {@code ACT_GE_PROPERTY} {@code schema.version}.</li>
 *   <li>Lock: {@code DATABASECHANGELOGLOCK} row 1 (a migration is running, or a crashed one left the lock).</li>
 * </ul>
 * The same SQL runs on PostgreSQL, Oracle, SQL Server, MySQL and H2 (unquoted upper-case names, as Liquibase and
 * Operaton create them).
 */
public final class SchemaStatusReader {

    static final String ENGINE_PATCHES = "sangam-engine-patches";
    static final String HISTORY_PARTITIONING = "history-partitioning";

    public record Changelog(int applied, String lastId, String lastAuthor, Instant lastExecutedAt, List<String> ids) {
    }

    public record Engine(String schemaVersion) {
    }

    /** {@code SchemaStatus} of the contract. */
    public record Status(Changelog product, Engine engine, boolean liquibaseLocked, String lockedBy, Instant lockGrantedAt) {
    }

    private SchemaStatusReader() {
    }

    public static Status read(Connection c) throws SQLException {
        Lock lock = lock(c);
        return new Status(product(c), new Engine(engineVersion(c)), lock.locked(), lock.by(), lock.grantedAt());
    }

    /** True when a changelog file name belongs to the product master changelog. */
    static boolean isProduct(String fileName) {
        String f = fileName == null ? "" : fileName.replace('\\', '/');
        return !f.contains(ENGINE_PATCHES) && !f.contains(HISTORY_PARTITIONING);
    }

    private static Changelog product(Connection c) throws SQLException {
        List<String> ids = new ArrayList<>();
        String lastId = "";
        String lastAuthor = "";
        Instant lastAt = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ID, AUTHOR, FILENAME, DATEEXECUTED FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED, DATEEXECUTED");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!isProduct(rs.getString(3))) {
                    continue;
                }
                lastId = rs.getString(1);
                lastAuthor = rs.getString(2);
                Timestamp at = rs.getTimestamp(4);
                lastAt = at == null ? null : at.toInstant();
                ids.add(lastId);
            }
        }
        return new Changelog(ids.size(), lastId, lastAuthor, lastAt, List.copyOf(ids));
    }

    private static String engineVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT VALUE_ FROM ACT_GE_PROPERTY WHERE NAME_ = ?")) {
            ps.setString(1, "schema.version");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1) != null ? rs.getString(1) : "";
            }
        }
    }

    private record Lock(boolean locked, String by, Instant grantedAt) {
    }

    /** Row 1 of DATABASECHANGELOGLOCK; no row means not locked. Holder and time only while locked. */
    private static Lock lock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT LOCKED, LOCKEDBY, LOCKGRANTED FROM DATABASECHANGELOGLOCK WHERE ID = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || !rs.getBoolean(1)) {
                return new Lock(false, null, null);
            }
            Timestamp at = rs.getTimestamp(3);
            return new Lock(true, rs.getString(2), at == null ? null : at.toInstant());
        }
    }
}
