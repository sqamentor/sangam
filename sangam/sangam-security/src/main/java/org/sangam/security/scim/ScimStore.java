// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import org.sangam.core.layer.Jdbc;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * SCIM provisioning state in {@code SEC_SCIM_RESOURCE} (owner decision 154, ADR-D-154; table from the W4-G generator):
 * which persons and groups SCIM manages, the keys that carry the RFC 7644 uniqueness of {@code userName} and
 * {@code externalId}, the core attributes Sangam keeps verbatim, and the ETag counter. Persons, groups and memberships
 * themselves stay in {@code SEC_PERSON}, {@code SEC_GROUP} and {@code SEC_GROUP_MEMBER}; postings are never written.
 *
 * <p>{@code name_key} and {@code external_key} are NOT NULL on purpose: SQL Server allows one NULL per unique index, so
 * a nullable userName or externalId could not carry the 409 on all five dialects. A user's name key is its lower-case
 * userName (userName is case-insensitive, RFC 7643 section 4.1.1); a group's is {@code id:<resource_id>} (displayName
 * is not unique). The external key is {@code x:<externalId>} when set (externalId is case-exact), else
 * {@code id:<resource_id>}.
 */
public final class ScimStore {

    public static final String USER = "USER";
    public static final String GROUP = "GROUP";
    /** The external key holds {@code x:} plus externalId within the 255-character column. */
    public static final int MAX_EXTERNAL_ID = 253;

    private static final String COLUMNS = "resource_type, resource_id, user_name, display_name, external_id, attributes,"
            + " version, created_at, updated_at";

    public record Row(String type, String id, String userName, String displayName, String externalId, String attributes,
                      long version, Instant created, Instant updated) {
    }

    public record Page(int total, List<Row> rows) {
    }

    private final SecurityDialect dialect;

    public ScimStore(SecurityDialect dialect) {
        this.dialect = dialect;
    }

    static String nameKey(String type, String id, String userName) {
        return USER.equals(type) ? userName.toLowerCase(Locale.ROOT) : "id:" + id;
    }

    static String externalKey(String id, String externalId) {
        return externalId == null ? "id:" + id : "x:" + externalId;
    }

    public Optional<Row> find(Connection c, String tenant, String type, String id) {
        return Jdbc.one(c, "SELECT " + COLUMNS + " FROM SEC_SCIM_RESOURCE WHERE tenant_id=? AND resource_type=? AND resource_id=?",
                ScimStore::row, tenant, type, id);
    }

    /** The resource of this type that already holds the userName or externalId, other than {@code exceptId}. */
    public Optional<String> holderOf(Connection c, String tenant, String type, String exceptId, String userName, String externalId) {
        if (userName != null && USER.equals(type)) {
            Optional<String> byName = Jdbc.one(c, "SELECT resource_id FROM SEC_SCIM_RESOURCE WHERE tenant_id=? AND resource_type=?"
                    + " AND name_key=?", rs -> rs.getString(1), tenant, type, nameKey(type, null, userName));
            if (byName.isPresent() && !byName.get().equals(exceptId)) {
                return byName;
            }
        }
        if (externalId != null) {
            Optional<String> byExt = Jdbc.one(c, "SELECT resource_id FROM SEC_SCIM_RESOURCE WHERE tenant_id=? AND resource_type=?"
                    + " AND external_key=?", rs -> rs.getString(1), tenant, type, externalKey(null, externalId));
            if (byExt.isPresent() && !byExt.get().equals(exceptId)) {
                return byExt;
            }
        }
        return Optional.empty();
    }

    /** Inserts version 1; false when the id, the userName or the externalId is taken (a concurrent insert included). */
    public boolean insert(Connection c, String tenant, String type, String id, String userName, String displayName,
                          String externalId, String attributes, Instant now) {
        return Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_SCIM_RESOURCE(tenant_id, resource_type, resource_id, user_name,"
                        + " display_name, name_key, external_id, external_key, attributes, version, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                tenant, type, id, userName, displayName, nameKey(type, id, userName), externalId, externalKey(id, externalId),
                attributes, 1L, now, now);
    }

    /**
     * Writes the new state when the stored version is still {@code expectedVersion} and bumps it; false when another
     * writer changed the row first (optimistic lock, the ETag).
     */
    public boolean update(Connection c, String tenant, String type, String id, long expectedVersion, String userName,
                          String displayName, String externalId, String attributes, Instant now) {
        return Jdbc.update(c, "UPDATE SEC_SCIM_RESOURCE SET user_name=?, display_name=?, name_key=?, external_id=?, external_key=?,"
                        + " attributes=?, version=?, updated_at=? WHERE tenant_id=? AND resource_type=? AND resource_id=? AND version=?",
                userName, displayName, nameKey(type, id, userName), externalId, externalKey(id, externalId), attributes,
                expectedVersion + 1, now, tenant, type, id, expectedVersion) == 1;
    }

    /** Bumps the version only (a membership change of a group, which has no column of its own here). */
    public boolean touch(Connection c, String tenant, String type, String id, long expectedVersion, Instant now) {
        return Jdbc.update(c, "UPDATE SEC_SCIM_RESOURCE SET version=?, updated_at=? WHERE tenant_id=? AND resource_type=?"
                + " AND resource_id=? AND version=?", expectedVersion + 1, now, tenant, type, id, expectedVersion) == 1;
    }

    public boolean delete(Connection c, String tenant, String type, String id, long expectedVersion) {
        return Jdbc.update(c, "DELETE FROM SEC_SCIM_RESOURCE WHERE tenant_id=? AND resource_type=? AND resource_id=? AND version=?",
                tenant, type, id, expectedVersion) == 1;
    }

    /** Ids of this type in the tenant; used to tell SCIM groups from groups managed elsewhere. */
    public List<String> ids(Connection c, String tenant, String type) {
        return Jdbc.query(c, "SELECT resource_id FROM SEC_SCIM_RESOURCE WHERE tenant_id=? AND resource_type=? ORDER BY resource_id",
                rs -> rs.getString(1), tenant, type);
    }

    /**
     * One page of a filtered list, ordered by creation then id (stable across pages). {@code startIndex} is 1-based
     * (RFC 7644 section 3.4.2.4); {@code count} 0 returns the total only.
     */
    public Page list(Connection c, String tenant, String type, List<ScimFilter.Clause> filter, int startIndex, int count) {
        StringBuilder where = new StringBuilder(" FROM SEC_SCIM_RESOURCE WHERE tenant_id=? AND resource_type=?");
        List<Object> params = new ArrayList<>(List.of(tenant, type));
        for (ScimFilter.Clause f : filter) {
            where.append(" AND ");
            predicate(type, f, where, params);
        }
        int total = Jdbc.one(c, "SELECT COUNT(*)" + where, rs -> rs.getInt(1), params.toArray()).orElse(0);
        if (count <= 0 || startIndex > total) {
            return new Page(total, List.of());
        }
        String select = dialect.page("SELECT " + COLUMNS + where + " ORDER BY created_at, resource_id", startIndex - 1, count);
        return new Page(total, Jdbc.query(c, select, ScimStore::row, params.toArray()));
    }

    private void predicate(String type, ScimFilter.Clause f, StringBuilder where, List<Object> params) {
        String column;
        String value = f.value();
        switch (f.attribute()) {
            case USER_NAME -> {
                if (!USER.equals(type)) {
                    throw ScimException.invalidFilter("Groups have no userName");
                }
                column = "name_key";     // lower-case userName
                value = value.toLowerCase(Locale.ROOT);
            }
            case EXTERNAL_ID -> column = "external_id";   // case-exact
            case DISPLAY_NAME -> {
                column = "LOWER(display_name)";          // case-insensitive
                value = value.toLowerCase(Locale.ROOT);
            }
            default -> throw ScimException.invalidFilter("unsupported attribute");
        }
        if (f.op() == ScimFilter.Op.EQ) {
            where.append(column).append("=?");
            params.add(value);
        } else {
            where.append(column).append(" LIKE ? ESCAPE '!'");
            params.add(ScimFilter.likePattern(f.op(), value, dialect == SecurityDialect.SQLSERVER));
        }
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("user_name"),
                rs.getString("display_name"), rs.getString("external_id"), rs.getString("attributes"),
                rs.getLong("version"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
    }
}
