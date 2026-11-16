// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;

/**
 * RM-52: exports the engine history of one ended process tree (one tenant) as an engine-neutral ZIP
 * ({@code docs/records/RM-52-HISTORY-ARCHIVE-EXPORT-SPEC.md} section 2), read with plain SQL so that no engine is
 * needed to write or read it:
 * <ul>
 *   <li>{@code tables/<TABLE>.jsonl}: every row of each engine history table that refers to the tree (by
 *       {@code ROOT_PROC_INST_ID_}, else by {@code PROC_INST_ID_} of a member), the {@code ACT_GE_BYTEARRAY} rows of the
 *       tree and every byte array those rows reference, the tree's {@code HRM_*} read-model rows and its
 *       {@code PROCESS_TRACE} rows. One JSON object per row, the column names as the database reports them; timestamps
 *       ISO-8601 UTC, numbers as exact decimal text, byte arrays as {@code {"base64":..,"sha256":..}};</li>
 *   <li>{@code models/<deployment>/<resource>}: the deployed model of each process and decision definition used, byte
 *       for byte;</li>
 *   <li>{@code manifest.json}: tenant, root, members, the tree's last end time, the schema version, the history level
 *       of each definition and per file its row count and SHA-256.</li>
 * </ul>
 * Deterministic: tables, rows (by primary key), columns and ZIP entries are in a fixed order with a fixed entry time,
 * and the manifest carries no export time (the time goes on the audit event), so two exports of the same tree are
 * identical byte for byte.
 */
public final class HistoryArchiveExporter {

    /** Format version of the archive. */
    public static final int FORMAT = 1;

    /** The engine history tables of the spec, in archive order. */
    public static final List<String> HISTORY_TABLES = List.of("ACT_HI_PROCINST", "ACT_HI_ACTINST", "ACT_HI_TASKINST",
            "ACT_HI_VARINST", "ACT_HI_DETAIL", "ACT_HI_IDENTITYLINK", "ACT_HI_COMMENT", "ACT_HI_ATTACHMENT",
            "ACT_HI_OP_LOG", "ACT_HI_INCIDENT", "ACT_HI_JOB_LOG", "ACT_HI_EXT_TASK_LOG", "ACT_HI_DECINST",
            "ACT_HI_DEC_IN", "ACT_HI_DEC_OUT", "ACT_HI_CASEINST", "ACT_HI_CASEACTINST");

    /** Columns that reference an {@code ACT_GE_BYTEARRAY} row. */
    static final Set<String> BYTE_REFS = Set.of("BYTEARRAY_ID_", "BYTE_ARRAY_ID_", "CONTENT_ID_", "ERROR_DETAILS_ID_",
            "ERROR_STACK_ID_", "EXCEPTION_STACK_ID_", "STACK_TRACE_ID_");

    private static final long ENTRY_TIME = 315532800000L;   // 1980-01-01T00:00:00Z, the earliest ZIP time
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /** A refused export: nothing was written. */
    public static final class ExportRefusedException extends RuntimeException {
        private final String code;

        public ExportRefusedException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The archive of one tree: its files ({@code manifest.json} included), before signing and zipping. */
    public record Archive(String tenantId, String rootId, Map<String, byte[]> files, byte[] manifest,
                          String manifestSha256, Map<String, Long> rowCounts, Instant lastEnd) {
    }

    private final String prefix;
    private final String sangamVersion;

    /** @param tablePrefix the engine's table prefix ({@code ""} when none) */
    public HistoryArchiveExporter(String tablePrefix, String sangamVersion) {
        this.prefix = tablePrefix == null ? "" : tablePrefix;
        this.sangamVersion = sangamVersion == null ? "unknown" : sangamVersion;
    }

    /**
     * Exports the tree of {@code rootId}. Refused ({@link ExportRefusedException}) when the root is unknown, not a root,
     * of another tenant, or when a member has not ended.
     *
     * @param historyLevels the history level of each definition key, as configured ({@code key -> level}); recorded
     */
    public Archive export(Connection c, String tenantId, String rootId, Map<String, String> historyLevels) {
        try {
            return doExport(c, tenantId, rootId, historyLevels == null ? Map.of() : historyLevels);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("history archive of tree " + rootId + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * The row count of each table the archive of {@code rootId} would hold now (members' history tables only): a
     * stored archive whose counts are lower is stale.
     */
    public Map<String, Long> currentCounts(Connection c, String rootId) {
        try {
            List<String> members = members(c, rootId).stream().map(m -> m.id).toList();
            Map<String, Long> out = new TreeMap<>();
            Map<String, Set<String>> columns = new LinkedHashMap<>();
            for (String t : HISTORY_TABLES) {
                Set<String> cols = columns(c, prefix + t);
                if (cols.isEmpty()) {
                    continue;
                }
                Selection s = selection(cols, members);
                if (s == null) {
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + prefix + t + " WHERE " + s.where)) {
                    bind(ps, s.where.startsWith("ROOT") ? List.of(rootId) : members);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        out.put(t, rs.getLong(1));
                    }
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("history counts of tree " + rootId + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * The longest {@code historyTimeToLive} (days) of the process and decision definitions the tree used; 0 when none
     * sets one. The archive class must keep the archive longer (decision 386(1)).
     */
    public long longestHistoryTimeToLiveDays(Connection c, String rootId) {
        long out = 0;
        try {
            for (String sql : List.of("SELECT MAX(d.HISTORY_TTL_) FROM " + prefix + "ACT_RE_PROCDEF d JOIN " + prefix
                            + "ACT_HI_PROCINST h ON h.PROC_DEF_ID_=d.ID_ WHERE h.ROOT_PROC_INST_ID_=?",
                    "SELECT MAX(d.HISTORY_TTL_) FROM " + prefix + "ACT_RE_DECISION_DEF d JOIN " + prefix
                            + "ACT_HI_DECINST h ON h.DEC_DEF_ID_=d.ID_ WHERE h.ROOT_PROC_INST_ID_=?")) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, rootId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out = Math.max(out, rs.getLong(1));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("history time to live of tree " + rootId + " failed: " + e.getMessage(), e);
        }
        return out;
    }

    private record Member(String id, String tenant, String root, String definitionId, String definitionKey,
                          Timestamp end) {
    }

    private record Selection(String where) {
    }

    private List<Member> members(Connection c, String rootId) throws SQLException {
        List<Member> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT ID_, TENANT_ID_, ROOT_PROC_INST_ID_, PROC_DEF_ID_,"
                + " PROC_DEF_KEY_, END_TIME_ FROM " + prefix + "ACT_HI_PROCINST WHERE ROOT_PROC_INST_ID_=? OR ID_=?"
                + " ORDER BY ID_")) {
            ps.setString(1, rootId);
            ps.setString(2, rootId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Member(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getTimestamp(6)));
                }
            }
        }
        return out;
    }

    private Archive doExport(Connection c, String tenantId, String rootId, Map<String, String> levels)
            throws SQLException, IOException {
        List<Member> members = members(c, rootId);
        Member root = members.stream().filter(m -> m.id.equals(rootId)).findFirst().orElseThrow(
                () -> new ExportRefusedException("HISTORY_TREE_UNKNOWN", "no historic process instance " + rootId));
        if (!rootId.equals(root.root)) {
            throw new ExportRefusedException("HISTORY_TREE_NOT_ROOT", rootId + " is a member of tree " + root.root);
        }
        for (Member m : members) {
            if (!java.util.Objects.equals(tenantId, m.tenant)) {
                throw new ExportRefusedException("HISTORY_TREE_TENANT", "member " + m.id + " is not of tenant " + tenantId);
            }
            if (m.end == null) {
                throw new ExportRefusedException("HISTORY_TREE_NOT_ENDED", "member " + m.id + " has not ended");
            }
        }
        List<String> ids = members.stream().map(Member::id).toList();
        Map<String, byte[]> files = new TreeMap<>();
        Map<String, Long> counts = new TreeMap<>();
        Set<String> byteRefs = new TreeSet<>();
        for (String t : HISTORY_TABLES) {
            Set<String> cols = columns(c, prefix + t);
            Selection s = cols.isEmpty() ? null : selection(cols, ids);
            if (s == null) {
                continue;
            }
            List<Object> params = s.where.startsWith("ROOT") ? List.of(rootId) : new ArrayList<>(ids);
            Rows rows = rows(c, prefix + t, s.where, params, cols, byteRefs);
            files.put("tables/" + t + ".jsonl", rows.bytes);
            counts.put(t, rows.count);
        }
        // byte arrays: the tree's own (ROOT_PROC_INST_ID_) and every one a history row references
        Set<String> geCols = columns(c, prefix + "ACT_GE_BYTEARRAY");
        if (!geCols.isEmpty()) {
            Map<String, String> lines = new TreeMap<>();
            if (geCols.contains("ROOT_PROC_INST_ID_")) {
                collect(c, "SELECT * FROM " + prefix + "ACT_GE_BYTEARRAY WHERE ROOT_PROC_INST_ID_=?", List.of(rootId),
                        lines);
            }
            List<String> refs = new ArrayList<>(byteRefs);
            for (int i = 0; i < refs.size(); i += 500) {
                List<String> chunk = refs.subList(i, Math.min(refs.size(), i + 500));
                collect(c, "SELECT * FROM " + prefix + "ACT_GE_BYTEARRAY WHERE ID_ IN (" + marks(chunk.size()) + ")",
                        new ArrayList<>(chunk), lines);
            }
            files.put("tables/ACT_GE_BYTEARRAY.jsonl", join(lines.values()));
            counts.put("ACT_GE_BYTEARRAY", (long) lines.size());
        }
        // the read model and the trace rows, where the tables exist
        for (String t : tablesStartingWith(c, "HRM_")) {
            Set<String> cols = columns(c, t);
            String col = cols.contains("ROOT_ID") ? "ROOT_ID" : cols.contains("ROOT_PROC_INST_ID") ? "ROOT_PROC_INST_ID"
                    : cols.contains("PROCESS_INSTANCE_ID") ? "PROCESS_INSTANCE_ID" : null;
            if (col == null) {
                continue;
            }
            List<Object> params = col.startsWith("ROOT") ? List.of(rootId) : new ArrayList<>(ids);
            String where = col.startsWith("ROOT") ? col + "=?" : col + " IN (" + marks(ids.size()) + ")";
            Rows rows = rows(c, t, where, params, cols, null);
            files.put("tables/" + t + ".jsonl", rows.bytes);
            counts.put(t, rows.count);
        }
        Set<String> trace = columns(c, "PROCESS_TRACE");
        if (trace.contains("PROCESS_INSTANCE_ID")) {
            Rows rows = rows(c, "PROCESS_TRACE", "PROCESS_INSTANCE_ID IN (" + marks(ids.size()) + ")",
                    new ArrayList<>(ids), trace, null);
            files.put("tables/PROCESS_TRACE.jsonl", rows.bytes);
            counts.put("PROCESS_TRACE", rows.count);
        }
        // the deployed models of the process and decision definitions used
        List<Map<String, Object>> models = new ArrayList<>();
        Set<String> procDefs = new TreeSet<>();
        members.forEach(m -> procDefs.add(m.definitionId));
        for (String def : procDefs) {
            model(c, "ACT_RE_PROCDEF", def, files, models);
        }
        if (!columns(c, prefix + "ACT_HI_DECINST").isEmpty()) {
            Set<String> decDefs = new TreeSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT DEC_DEF_ID_ FROM " + prefix
                    + "ACT_HI_DECINST WHERE ROOT_PROC_INST_ID_=?")) {
                ps.setString(1, rootId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        decDefs.add(rs.getString(1));
                    }
                }
            }
            for (String def : decDefs) {
                model(c, "ACT_RE_DECISION_DEF", def, files, models);
            }
        }
        Instant lastEnd = members.stream().map(m -> m.end.toInstant()).max(Instant::compareTo).orElseThrow();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("tenant", tenantId);
        manifest.put("root", rootId);
        manifest.put("members", ids);
        manifest.put("lastEnd", ISO.format(lastEnd));
        manifest.put("sangamVersion", sangamVersion);
        manifest.put("engineSchemaVersion", schemaVersion(c));
        Map<String, String> treeLevels = new TreeMap<>();
        members.forEach(m -> treeLevels.put(m.definitionKey, levels.getOrDefault(m.definitionKey, "configured default")));
        manifest.put("historyLevels", treeLevels);
        manifest.put("models", models);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", f.getKey());
            e.put("sha256", Hashes.hex(Hashes.sha256(f.getValue())));
            e.put("bytes", (long) f.getValue().length);
            String table = f.getKey().startsWith("tables/") ? f.getKey().substring(7, f.getKey().length() - 6) : null;
            if (table != null) {
                e.put("rows", counts.get(table));
            }
            entries.add(e);
        }
        manifest.put("files", entries);
        byte[] manifestBytes = CanonicalJson.write(manifest).getBytes(StandardCharsets.UTF_8);
        files.put("manifest.json", manifestBytes);
        return new Archive(tenantId, rootId, java.util.Collections.unmodifiableMap(files), manifestBytes,
                Hashes.hex(Hashes.sha256(manifestBytes)), Map.copyOf(counts), lastEnd);
    }

    /** Writes the files as a ZIP with sorted entries and a fixed entry time. */
    public static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            z.setLevel(9);
            for (Map.Entry<String, byte[]> f : new TreeMap<>(files).entrySet()) {
                ZipEntry e = new ZipEntry(f.getKey());
                e.setTime(ENTRY_TIME);
                z.putNextEntry(e);
                z.write(f.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private void model(Connection c, String table, String definitionId, Map<String, byte[]> files,
                       List<Map<String, Object>> models) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT d.KEY_, d.VERSION_, d.VERSION_TAG_, d.DEPLOYMENT_ID_,"
                + " d.RESOURCE_NAME_, b.BYTES_ FROM " + prefix + table + " d LEFT JOIN " + prefix + "ACT_GE_BYTEARRAY b"
                + " ON b.DEPLOYMENT_ID_=d.DEPLOYMENT_ID_ AND b.NAME_=d.RESOURCE_NAME_ WHERE d.ID_=?")) {
            ps.setString(1, definitionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ExportRefusedException("HISTORY_MODEL_MISSING", "definition " + definitionId
                            + " is no longer deployed; its model cannot be archived");
                }
                byte[] bytes = bytes(rs.getObject(6));
                if (bytes == null) {
                    throw new ExportRefusedException("HISTORY_MODEL_MISSING", "definition " + definitionId
                            + " has no deployed resource " + rs.getString(5));
                }
                String path = "models/" + rs.getString(4) + "/" + rs.getString(5);
                files.put(path, bytes);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("definition", definitionId);
                m.put("key", rs.getString(1));
                m.put("version", rs.getLong(2));
                m.put("versionTag", rs.getString(3));
                m.put("path", path);
                models.add(m);
            }
        }
    }

    private static Selection selection(Set<String> cols, List<String> members) {
        if (cols.contains("ROOT_PROC_INST_ID_")) {
            return new Selection("ROOT_PROC_INST_ID_=?");
        }
        if (cols.contains("PROC_INST_ID_") && !members.isEmpty()) {
            return new Selection("PROC_INST_ID_ IN (" + marks(members.size()) + ")");
        }
        return null;
    }

    private record Rows(byte[] bytes, long count) {
    }

    private Rows rows(Connection c, String table, String where, List<?> params, Set<String> cols, Set<String> byteRefs)
            throws SQLException {
        Map<String, String> lines = new TreeMap<>();
        String order = cols.contains("ID_") ? " ORDER BY ID_" : cols.contains("ID") ? " ORDER BY ID" : "";
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table + " WHERE " + where + order)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = 0;
                while (rs.next()) {
                    Map<String, Object> row = row(rs, md);
                    if (byteRefs != null) {
                        for (String ref : BYTE_REFS) {
                            Object v = row.get(ref);
                            if (v instanceof String s && !s.isEmpty()) {
                                byteRefs.add(s);
                            }
                        }
                    }
                    String key = row.containsKey("ID_") ? String.valueOf(row.get("ID_"))
                            : row.containsKey("ID") ? String.valueOf(row.get("ID")) : CanonicalJson.write(row);
                    lines.put(key + "\u0000" + (n++), CanonicalJson.write(row));
                }
            }
        }
        // the key carries the read position only to keep rows apart; the order is the primary key
        return new Rows(join(lines.values()), lines.size());
    }

    private void collect(Connection c, String sql, List<?> params, Map<String, String> lines) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = row(rs, md);
                    lines.put(String.valueOf(row.get("ID_")), CanonicalJson.write(row));
                }
            }
        }
    }

    /** One row: column names upper-cased as the engine writes them, values in the archive's exact forms. */
    static Map<String, Object> row(ResultSet rs, ResultSetMetaData md) throws SQLException {
        Map<String, Object> row = new TreeMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = md.getColumnLabel(i).toUpperCase(Locale.ROOT);
            row.put(name, value(rs.getObject(i)));
        }
        return row;
    }

    static Object value(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp t) {
            return ISO.format(t.toInstant().atOffset(ZoneOffset.UTC));
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.time.temporal.TemporalAccessor ta) {
            return ta.toString();
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof BigDecimal b) {
            return b.toPlainString();
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString()).toPlainString();
        }
        if (v instanceof Clob clob) {
            try (Reader r = clob.getCharacterStream()) {
                StringBuilder b = new StringBuilder();
                char[] buf = new char[8192];
                for (int k; (k = r.read(buf)) > 0; ) {
                    b.append(buf, 0, k);
                }
                return b.toString();
            } catch (IOException e) {
                throw new SQLException(e);
            }
        }
        byte[] bytes = bytes(v);
        if (bytes != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("base64", Base64.getEncoder().encodeToString(bytes));
            m.put("sha256", Hashes.hex(Hashes.sha256(bytes)));
            return m;
        }
        return v.toString();
    }

    static byte[] bytes(Object v) throws SQLException {
        if (v instanceof byte[] b) {
            return b;
        }
        if (v instanceof Blob blob) {
            try (InputStream in = blob.getBinaryStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new SQLException(e);
            }
        }
        return null;
    }

    private static byte[] join(java.util.Collection<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            b.append(l).append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String marks(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static void bind(PreparedStatement ps, List<?> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /** Upper-case column names of {@code table}, empty when the table does not exist. */
    static Set<String> columns(Connection c, String table) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        DatabaseMetaData md = c.getMetaData();
        for (String name : List.of(table, table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet rs = md.getColumns(null, null, name, null)) {
                while (rs.next()) {
                    out.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                }
            }
            if (!out.isEmpty()) {
                break;
            }
        }
        return out;
    }

    private static Set<String> tablesStartingWith(Connection c, String start) throws SQLException {
        Set<String> out = new TreeSet<>();
        DatabaseMetaData md = c.getMetaData();
        for (String pattern : List.of(start + "%", start.toLowerCase(Locale.ROOT) + "%")) {
            try (ResultSet rs = md.getTables(null, null, pattern, new String[] {"TABLE"})) {
                while (rs.next()) {
                    String n = rs.getString("TABLE_NAME").toUpperCase(Locale.ROOT);
                    if (n.startsWith(start)) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }

    private String schemaVersion(Connection c) throws SQLException {
        if (columns(c, prefix + "ACT_GE_PROPERTY").isEmpty()) {
            return "unknown";
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT VALUE_ FROM " + prefix
                + "ACT_GE_PROPERTY WHERE NAME_='schema.version'"); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : "unknown";
        }
    }
}
