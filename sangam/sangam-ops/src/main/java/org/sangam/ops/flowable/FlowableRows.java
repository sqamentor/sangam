// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * M-135: read-only access to a Flowable runtime schema. A row is a map from the upper-case column name to its value:
 * {@link String}, {@link Number}, {@link Boolean}, {@link LocalDateTime} (a timestamp without zone, as Flowable stores
 * it; the {@link FlowableInventory} applies the explicit source zone), {@link OffsetDateTime} or null. Binary columns
 * read as null; the one binary value read is a definition's deployed BPMN resource ({@link #resource}), to find the
 * listeners that ending an instance on Flowable fires ({@link FlowableModelListeners}). A table missing from the source
 * (an older Flowable) reads as empty.
 *
 * <p>Two sources: {@link #jdbc(DataSource)} for a live (frozen) Flowable database, and {@link #fixture(InputStream)}
 * for a recorded or synthetic corpus ({@code {"tables": {"ACT_RU_EXECUTION": [ {...}, ... ], ...}, "resources":
 * {"<deployment id>/<resource name>": "<BPMN XML>"}}}, timestamps as ISO local date-times).
 */
public interface FlowableRows {

    /**
     * The bytes of deployed resource {@code name} of {@code deploymentId} (ACT_GE_BYTEARRAY.BYTES_ where DEPLOYMENT_ID_
     * and NAME_ match), or null when there is none.
     */
    byte[] resource(String deploymentId, String name);

    /** Rows of {@code table} whose {@code column} is one of {@code values} (empty when {@code values} is empty). */
    List<Map<String, Object>> where(String table, String column, Collection<String> values);

    /** Every row of {@code table} (used for root executions and process definitions only). */
    List<Map<String, Object>> all(String table);

    /** Row count of {@code table}, 0 when the table does not exist. */
    long count(String table);

    /** IN lists are split so no statement exceeds Oracle's 1000-element limit. */
    int IN_CHUNK = 500;

    static FlowableRows jdbc(DataSource source) {
        return new Jdbc(source);
    }

    static FlowableRows fixture(InputStream json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = new ObjectMapper().readValue(json, Map.class);
            Object tables = doc.get("tables");
            if (!(tables instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("a Flowable fixture needs an object \"tables\"");
            }
            Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object r : (List<?>) e.getValue()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    ((Map<?, ?>) r).forEach((k, v) -> row.put(String.valueOf(k).toUpperCase(Locale.ROOT), v));
                    rows.add(row);
                }
                out.put(String.valueOf(e.getKey()).toUpperCase(Locale.ROOT), rows);
            }
            Map<String, byte[]> resources = new LinkedHashMap<>();
            if (doc.get("resources") instanceof Map<?, ?> res) {
                res.forEach((k, v) -> resources.put(String.valueOf(k),
                        String.valueOf(v).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            return new Fixture(out, resources);
        } catch (IOException e) {
            throw new IllegalArgumentException("unreadable Flowable fixture: " + e.getMessage(), e);
        }
    }

    /** In-memory rows (a corpus file or a test). */
    final class Fixture implements FlowableRows {
        private final Map<String, List<Map<String, Object>>> tables;
        private final Map<String, byte[]> resources;

        public Fixture(Map<String, List<Map<String, Object>>> tables) {
            this(tables, Map.of());
        }

        /** @param resources deployed resources by {@code <deployment id>/<resource name>} */
        public Fixture(Map<String, List<Map<String, Object>>> tables, Map<String, byte[]> resources) {
            this.tables = Map.copyOf(tables);
            this.resources = Map.copyOf(resources);
        }

        @Override
        public byte[] resource(String deploymentId, String name) {
            byte[] b = resources.get(deploymentId + "/" + name);
            return b == null ? null : b.clone();
        }

        @Override
        public List<Map<String, Object>> where(String table, String column, Collection<String> values) {
            Set<String> wanted = new HashSet<>(values);
            return all(table).stream().filter(r -> r.get(column) != null && wanted.contains(String.valueOf(r.get(column))))
                    .toList();
        }

        @Override
        public List<Map<String, Object>> all(String table) {
            return tables.getOrDefault(table, List.of());
        }

        @Override
        public long count(String table) {
            return all(table).size();
        }
    }

    /** Plain JDBC, SELECT only. Table and column names come from {@link FlowableTables}, never from input. */
    final class Jdbc implements FlowableRows {
        private final DataSource source;
        private Set<String> existing;

        Jdbc(DataSource source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        @Override
        public List<Map<String, Object>> where(String table, String column, Collection<String> values) {
            checkName(table);
            checkName(column);
            List<String> list = new ArrayList<>(new java.util.LinkedHashSet<>(values));
            List<Map<String, Object>> out = new ArrayList<>();
            if (list.isEmpty() || !exists(table)) {
                return out;
            }
            for (int i = 0; i < list.size(); i += IN_CHUNK) {
                List<String> chunk = list.subList(i, Math.min(list.size(), i + IN_CHUNK));
                String marks = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
                out.addAll(select("SELECT * FROM " + table + " WHERE " + column + " IN (" + marks + ")", chunk));
            }
            return out;
        }

        @Override
        public List<Map<String, Object>> all(String table) {
            checkName(table);
            return exists(table) ? select("SELECT * FROM " + table, List.of()) : List.of();
        }

        @Override
        public byte[] resource(String deploymentId, String name) {
            if (deploymentId == null || name == null || !exists(FlowableTables.BYTEARRAY)) {
                return null;
            }
            String sql = "SELECT " + FlowableTables.BYTES + " FROM " + FlowableTables.BYTEARRAY + " WHERE "
                    + FlowableTables.DEPLOYMENT_ID + " = ? AND " + FlowableTables.NAME + " = ?";
            try (Connection c = source.getConnection()) {
                c.setReadOnly(true);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, deploymentId);
                    ps.setString(2, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getBytes(1) : null;
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read the Flowable resource " + name + ": " + e.getMessage(), e);
            }
        }

        @Override
        public long count(String table) {
            checkName(table);
            if (!exists(table)) {
                return 0;
            }
            try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            } catch (SQLException e) {
                throw new IllegalStateException("cannot count " + table + ": " + e.getMessage(), e);
            }
        }

        private List<Map<String, Object>> select(String sql, List<String> params) {
            try (Connection c = source.getConnection()) {
                c.setReadOnly(true);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setString(i + 1, params.get(i));
                    }
                    List<Map<String, Object>> out = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData md = rs.getMetaData();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= md.getColumnCount(); i++) {
                                row.put(md.getColumnLabel(i).toUpperCase(Locale.ROOT), value(rs, md, i));
                            }
                            out.add(row);
                        }
                    }
                    return out;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read the Flowable source: " + e.getMessage(), e);
            }
        }

        /** Timestamps without zone stay wall-clock values: the zone is applied explicitly by the inventory. */
        private static Object value(ResultSet rs, ResultSetMetaData md, int i) throws SQLException {
            return switch (md.getColumnType(i)) {
                case Types.TIMESTAMP, Types.DATE -> rs.getObject(i, LocalDateTime.class);
                case Types.TIMESTAMP_WITH_TIMEZONE -> rs.getObject(i, OffsetDateTime.class);
                case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> null;
                case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> rs.getString(i);
                default -> {
                    Object v = rs.getObject(i);
                    yield v instanceof Number || v instanceof Boolean || v == null ? v : v.toString();
                }
            };
        }

        private synchronized boolean exists(String table) {
            if (existing == null) {
                Set<String> s = new HashSet<>();
                try (Connection c = source.getConnection(); ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                    while (rs.next()) {
                        s.add(rs.getString("TABLE_NAME").toUpperCase(Locale.ROOT));
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("cannot list the Flowable source tables: " + e.getMessage(), e);
                }
                existing = s;
            }
            return existing.contains(table.toUpperCase(Locale.ROOT));
        }

        private static void checkName(String name) {
            if (!name.matches("[A-Z0-9_]+")) {
                throw new IllegalArgumentException("invalid identifier " + name);
            }
        }
    }
}
