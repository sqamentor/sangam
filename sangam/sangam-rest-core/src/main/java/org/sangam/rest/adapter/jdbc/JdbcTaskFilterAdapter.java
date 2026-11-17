// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.TaskFilterPort;
import org.sangam.rest.support.TxSupport;

/**
 * Owner decision 404: {@link TaskFilterPort} on the product table {@code TEAM_TASK_FILTER} (changeset 0047-01, PK
 * {@code tenant_id, filter_id}). Every statement carries {@code tenant_id=?}; a blank tenant is refused before any
 * statement (fail closed). Updates and deletes apply only at the expected row version; {@code created_at} is
 * database time (A-1). Runs in the caller's
 * transaction ({@link TxSupport#withConnection}), so the audit row commits with the insert.
 */
public final class JdbcTaskFilterAdapter implements TaskFilterPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final String COLS = "filter_id, name, owner_id, query_json, properties_json, created_by, created_at,"
            + " row_version";

    private final TxSupport tx;

    public JdbcTaskFilterAdapter(TxSupport tx) {
        this.tx = tx;
    }

    @Override
    public List<TaskFilter> list(String tenantId) {
        String t = tenant(tenantId);
        return tx.withConnection(c -> Jdbc.query(c, "SELECT " + COLS + " FROM TEAM_TASK_FILTER WHERE tenant_id=?"
                + " ORDER BY name, filter_id", JdbcTaskFilterAdapter::map, t));
    }

    @Override
    public Optional<TaskFilter> get(String tenantId, String id) {
        String t = tenant(tenantId);
        return tx.withConnection(c -> Jdbc.one(c, "SELECT " + COLS + " FROM TEAM_TASK_FILTER"
                + " WHERE tenant_id=? AND filter_id=?", JdbcTaskFilterAdapter::map, t, id));
    }

    @Override
    public TaskFilter create(String tenantId, String actor, String name, String owner, Map<String, Object> query,
                             Map<String, Object> properties) {
        String t = tenant(tenantId);
        String id = UUID.randomUUID().toString();
        String sql = "INSERT INTO TEAM_TASK_FILTER(tenant_id, filter_id, name, owner_id, query_json, properties_json,"
                + " row_version, created_by, created_at) VALUES (?,?,?,?,?,?,1,?," + tx.dialect().now() + ")";
        return tx.withConnection(c -> {
            Jdbc.update(c, sql, t, id, name, owner, json(query), properties == null ? null : json(properties),
                    actor == null || actor.isBlank() ? "system" : actor);
            return Jdbc.one(c, "SELECT " + COLS + " FROM TEAM_TASK_FILTER WHERE tenant_id=? AND filter_id=?",
                    JdbcTaskFilterAdapter::map, t, id).orElseThrow();
        });
    }

    @Override
    public Optional<TaskFilter> update(String tenantId, String id, int expectedVersion, String name, String owner,
                                       Map<String, Object> query, Map<String, Object> properties) {
        String t = tenant(tenantId);
        return tx.withConnection(c -> {
            int n = Jdbc.update(c, "UPDATE TEAM_TASK_FILTER SET name=?, owner_id=?, query_json=?, properties_json=?,"
                    + " row_version=row_version+1 WHERE tenant_id=? AND filter_id=? AND row_version=?", name, owner,
                    json(query), properties == null ? null : json(properties), t, id, expectedVersion);
            Optional<TaskFilter> now = Jdbc.one(c, "SELECT " + COLS + " FROM TEAM_TASK_FILTER WHERE tenant_id=?"
                    + " AND filter_id=?", JdbcTaskFilterAdapter::map, t, id);
            if (n == 0 && now.isPresent()) {
                throw stale(id, now.get().version());
            }
            return now;
        });
    }

    @Override
    public Optional<TaskFilter> delete(String tenantId, String id, int expectedVersion) {
        String t = tenant(tenantId);
        return tx.withConnection(c -> {
            Optional<TaskFilter> cur = Jdbc.one(c, "SELECT " + COLS + " FROM TEAM_TASK_FILTER WHERE tenant_id=?"
                    + " AND filter_id=?", JdbcTaskFilterAdapter::map, t, id);
            if (cur.isEmpty()) {
                return cur;
            }
            int n = Jdbc.update(c, "DELETE FROM TEAM_TASK_FILTER WHERE tenant_id=? AND filter_id=? AND row_version=?",
                    t, id, expectedVersion);
            if (n == 0) {
                throw stale(id, cur.get().version());
            }
            return cur;
        });
    }

    private static ApiProblem stale(String id, int current) {
        return ApiProblem.conflict("REVISION_CONFLICT", "Task list " + id + " is at version " + current
                + "; reload it and try again").with("currentVersion", current);
    }

    private static String tenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("a task list always belongs to one tenant");
        }
        return tenantId;
    }

    static TaskFilter map(ResultSet rs) throws SQLException {
        String props = rs.getString("properties_json");
        return new TaskFilter(rs.getString("filter_id"), rs.getString("name"), rs.getString("owner_id"),
                read(rs.getString("query_json")), props == null ? Map.of() : read(props), rs.getString("created_by"),
                Jdbc.instant(rs, "created_at"), rs.getInt("row_version"));
    }

    public static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(Objects.requireNonNull(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("TASK_FILTER_INVALID: not serialisable as JSON", e);
        }
    }

    private static Map<String, Object> read(String json) {
        try {
            return Collections.unmodifiableMap(JSON.readValue(json, MAP));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TEAM_TASK_FILTER holds a value that is not a JSON object", e);
        }
    }
}
