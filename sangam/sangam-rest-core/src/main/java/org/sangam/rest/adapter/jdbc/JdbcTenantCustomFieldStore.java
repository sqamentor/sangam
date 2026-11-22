// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.layer.Jdbc;
import org.sangam.rest.extension.TenantCustomFieldRegistry.ConcurrentChangeException;
import org.sangam.rest.extension.TenantCustomFieldRegistry.FieldDefinition;
import org.sangam.rest.extension.TenantCustomFieldRegistry.Store;
import org.sangam.rest.support.TxSupport;

/**
 * SL-40: tenant custom field versions in the product table {@code TENANT_CUSTOM_FIELD} (changeset 0034-01;
 * PK {@code tenant_id, object_type, field_key, version}), shared by every node.
 * <ul>
 *   <li>Every statement carries the tenant predicate.</li>
 *   <li>Writes are an insert of a new version or the closing of one ({@code effective_to}, {@code retired_by},
 *       {@code retired_at}); nothing is deleted or rewritten.</li>
 *   <li>Concurrency: two definitions of the same version collide on the primary key, and a close only applies while
 *       the stored {@code effective_to} is still the one read; both are refused with
 *       {@link ConcurrentChangeException} and the transaction rolls back.</li>
 *   <li>Dates are SQL {@code DATE} ({@link Date#valueOf}, {@link ResultSet#getDate}); the schema fragment is JSON text;
 *       {@code created_at}/{@code retired_at} are database time (A-1).</li>
 * </ul>
 * Runs in the caller's Spring transaction ({@link TxSupport#withConnection}), so the registry's audit row commits with it.
 */
public final class JdbcTenantCustomFieldStore implements Store {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };
    private static final String COLS = "object_type, field_key, version, label, required_flag, read_only_flag,"
            + " schema_json, effective_from, effective_to";

    private final TxSupport tx;

    public JdbcTenantCustomFieldStore(TxSupport tx) {
        this.tx = tx;
    }

    @Override
    public List<FieldDefinition> versions(String tenantId, String objectType, String key) {
        return tx.withReadConnection(c -> Jdbc.query(c, "SELECT " + COLS + " FROM TENANT_CUSTOM_FIELD"
                        + " WHERE tenant_id=? AND object_type=? AND field_key=? ORDER BY version",
                JdbcTenantCustomFieldStore::map, tenantId, objectType, key));
    }

    @Override
    public List<FieldDefinition> inForce(String tenantId, String objectType, LocalDate on) {
        Date d = Date.valueOf(on);
        return tx.withConnection(c -> Jdbc.query(c, "SELECT " + COLS + " FROM TENANT_CUSTOM_FIELD"
                        + " WHERE tenant_id=? AND object_type=? AND effective_from<=?"
                        + " AND (effective_to IS NULL OR effective_to>?) ORDER BY field_key",
                JdbcTenantCustomFieldStore::map, tenantId, objectType, d, d));
    }

    @Override
    public List<FieldDefinition> history(String tenantId) {
        return tx.withReadConnection(c -> Jdbc.query(c, "SELECT " + COLS + " FROM TENANT_CUSTOM_FIELD"
                        + " WHERE tenant_id=? ORDER BY object_type, field_key, version",
                JdbcTenantCustomFieldStore::map, tenantId));
    }

    @Override
    public void insert(String tenantId, String actor, FieldDefinition f) {
        String sql = "INSERT INTO TENANT_CUSTOM_FIELD(tenant_id, object_type, field_key, version, label, required_flag,"
                + " read_only_flag, schema_json, effective_from, effective_to, created_by, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?," + tx.dialect().now() + ")";
        tx.withConnection(c -> {
            try {
                return Jdbc.update(c, sql, tenantId, f.objectType(), f.key(), f.version(), f.label(), f.required(),
                        f.readOnly(), json(f.schema()), Date.valueOf(f.effectiveFrom()),
                        f.effectiveTo() == null ? null : Date.valueOf(f.effectiveTo()), actor(actor));
            } catch (Jdbc.LayerSqlException e) {
                if (tx.dialect().isUniqueViolation(e.sql())) {
                    throw new ConcurrentChangeException(f.key() + " v" + f.version() + " on " + f.objectType()
                            + " was defined concurrently");
                }
                throw e;
            }
        });
    }

    @Override
    public void close(String tenantId, String actor, FieldDefinition current, LocalDate effectiveTo) {
        String expected = current.effectiveTo() == null ? " AND effective_to IS NULL" : " AND effective_to=?";
        String sql = "UPDATE TENANT_CUSTOM_FIELD SET effective_to=?, retired_by=?, retired_at=" + tx.dialect().now()
                + " WHERE tenant_id=? AND object_type=? AND field_key=? AND version=?" + expected;
        Object[] params = current.effectiveTo() == null
                ? new Object[]{Date.valueOf(effectiveTo), actor(actor), tenantId, current.objectType(), current.key(),
                        current.version()}
                : new Object[]{Date.valueOf(effectiveTo), actor(actor), tenantId, current.objectType(), current.key(),
                        current.version(), Date.valueOf(current.effectiveTo())};
        int n = tx.withConnection(c -> Jdbc.update(c, sql, params));
        if (n != 1) {
            throw new ConcurrentChangeException(current.key() + " v" + current.version() + " on " + current.objectType()
                    + " was changed concurrently");
        }
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return tx.inTx(work);
    }

    /** A shared table can gain rows from any node at any time: never known empty without reading it. */
    @Override
    public boolean knownEmpty() {
        return false;
    }

    private static String actor(String actor) {
        return actor == null || actor.isBlank() ? "system" : actor;
    }

    static FieldDefinition map(ResultSet rs) throws SQLException {
        Date to = rs.getDate("effective_to");
        return new FieldDefinition(rs.getString("object_type"), rs.getString("field_key"), rs.getInt("version"),
                rs.getString("label"), rs.getInt("required_flag") != 0, rs.getInt("read_only_flag") != 0,
                schema(rs.getString("schema_json")), rs.getDate("effective_from").toLocalDate(),
                to == null ? null : to.toLocalDate());
    }

    static String json(Map<String, Object> schema) {
        try {
            return JSON.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("CUSTOM_FIELD_INVALID: schema is not serialisable as JSON", e);
        }
    }

    static Map<String, Object> schema(String json) {
        try {
            return Collections.unmodifiableMap(JSON.readValue(json, MAP));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TENANT_CUSTOM_FIELD.schema_json is not a JSON object", e);
        }
    }
}
