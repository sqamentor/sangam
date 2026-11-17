// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <b>Engine schema patch P-2 (MySQL only; FLOWABLE-INTO-OPERATON D-04).</b> Operaton 2.2.0 creates its MySQL tables with
 * {@code DEFAULT CHARSET=utf8 COLLATE utf8_bin}, i.e. utf8mb3: 4-byte characters (emoji, CJK extension B) cannot be
 * stored in engine string columns. This patch converts every {@code ACT_*} table whose collation is utf8mb3 to
 * {@code utf8mb4 / utf8mb4_bin} (still binary, so ids stay case-sensitive).
 *
 * <p><b>Foreign keys (SL-94).</b> MySQL 8.x requires both columns of a string foreign key to have the same character set
 * and collation, and with {@code foreign_key_checks=1} it refuses an {@code ALTER TABLE ... CONVERT TO CHARACTER SET}
 * that would break this (error 3780, {@code ER_FK_INCOMPATIBLE_COLUMNS}). One {@code ALTER TABLE} changes one table, so
 * no conversion order works for an FK whose two tables both need converting (the parent first breaks the pair just as
 * the child first does; Operaton 2.2.0 has 42 such FKs, self-references included). The patch therefore:
 * <ol>
 * <li>groups the tables to convert into FK-connected components (string-column FKs only; integer FKs are not touched by
 * the conversion) and converts or skips a whole component, never a part of one;</li>
 * <li>reads every affected FK definition from {@code information_schema} (name, columns in order, referenced table and
 * columns, {@code ON UPDATE}/{@code ON DELETE} rules) and journals it in {@code ACT_GE_PROPERTY} (committed) before any
 * DDL, so a crash between the drop and the re-create cannot lose an FK: the next run re-creates every journaled FK that
 * is missing;</li>
 * <li>drops those FKs, converts the tables, and re-creates each FK with its identical name and definition with
 * {@code foreign_key_checks} left on, so MySQL re-validates every existing row while adding it (InnoDB copies the table
 * for an FK added with checks on); then removes the journal rows.</li>
 * </ol>
 * The backing index of each FK is kept by {@code DROP FOREIGN KEY} and reused by the re-created FK.
 *
 * <p>Index byte limit: InnoDB DYNAMIC rows allow 3072 bytes per index key. Before converting, the widest index of each
 * table is recomputed at 4 bytes per character; a table whose widest index would exceed the limit is <b>skipped</b>
 * together with every table FK-linked to it, and reported (never half-converted). A component is also skipped when one
 * of its FKs references (or is referenced by) a column outside the conversion that is not already
 * {@code utf8mb4_bin}.
 *
 * <p>Idempotent; run after every engine create/upgrade. On a database that is already utf8mb4 with an empty journal it
 * reads {@code information_schema} only and executes no DDL or DML.
 */
public final class MysqlUtf8mb4Patch {

    /** InnoDB key limit with innodb_large_prefix (default since 5.7.7, only mode in 8.x). */
    public static final int MAX_INDEX_BYTES = 3072;

    /** {@code ACT_GE_PROPERTY.NAME_} prefix of a journaled FK definition (one row per FK while it is dropped). */
    public static final String JOURNAL_PREFIX = "sangam.p2.fk.";

    static final String JOURNAL_TABLE = "ACT_GE_PROPERTY";
    private static final int JOURNAL_NAME_MAX = 64;
    private static final int JOURNAL_VALUE_MAX = 300;
    private static final String TARGET_CHARSET = "utf8mb4";
    private static final String TARGET_COLLATION = "utf8mb4_bin";

    private MysqlUtf8mb4Patch() {
    }

    /**
     * Outcome: tables converted, tables skipped with the reason, FKs dropped and re-created (or re-created from the
     * journal of an interrupted earlier run).
     */
    public record Result(List<String> converted, Map<String, String> skipped, List<String> foreignKeysRecreated) {
    }

    /**
     * One foreign key as {@code information_schema} defines it. {@code schema}/{@code table}/{@code columns}: the
     * referencing side; {@code refSchema}/{@code refTable}/{@code refColumns}: the referenced side, column lists in
     * ordinal order.
     */
    public record ForeignKey(String schema, String name, String table, List<String> columns, String refSchema,
                             String refTable, List<String> refColumns, String updateRule, String deleteRule) {

        public ForeignKey {
            columns = List.copyOf(columns);
            refColumns = List.copyOf(refColumns);
        }

        /** {@code ACT_GE_PROPERTY.NAME_} of this FK's journal row. */
        public String journalName() {
            return JOURNAL_PREFIX + name;
        }

        /** {@code ACT_GE_PROPERTY.VALUE_} of this FK's journal row (tab-separated, no column name contains a tab). */
        public String journalValue() {
            return String.join("\t", schema, table, String.join(",", columns), refSchema, refTable,
                    String.join(",", refColumns), updateRule, deleteRule);
        }

        static ForeignKey fromJournal(String journalName, String value) {
            String[] f = value.split("\t", -1);
            if (!journalName.startsWith(JOURNAL_PREFIX) || f.length != 8) {
                throw new IllegalStateException("P-2: unreadable FK journal row " + journalName + " = " + value);
            }
            return new ForeignKey(f[0], journalName.substring(JOURNAL_PREFIX.length()), f[1], List.of(f[2].split(",")),
                    f[3], f[4], List.of(f[5].split(",")), f[6], f[7]);
        }

        /** The {@code ALTER TABLE ... ADD CONSTRAINT} that re-creates exactly this FK. */
        String addSql() {
            return "alter table " + q(schema) + "." + q(table) + " add constraint " + q(name) + " foreign key ("
                    + cols(columns) + ") references " + q(refSchema) + "." + q(refTable) + " (" + cols(refColumns)
                    + ") on delete " + rule(deleteRule) + " on update " + rule(updateRule);
        }

        String dropSql() {
            return "alter table " + q(schema) + "." + q(table) + " drop foreign key " + q(name);
        }

        private static String cols(List<String> cs) {
            StringBuilder sb = new StringBuilder();
            for (String c : cs) {
                sb.append(sb.isEmpty() ? "" : ", ").append(q(c));
            }
            return sb.toString();
        }

        private static String rule(String r) {
            String u = r.toUpperCase(java.util.Locale.ROOT);
            return switch (u) {
                case "RESTRICT", "CASCADE", "SET NULL", "NO ACTION", "SET DEFAULT" -> u;
                default -> throw new IllegalStateException("P-2: unknown referential rule '" + r + "'");
            };
        }
    }

    /**
     * Which tables are converted and which FKs are dropped and re-created around the conversion.
     *
     * @param convert  tables to convert, in name order
     * @param recreate string-column FKs with at least one end in {@code convert}
     * @param skipped  skipped tables with the reason
     */
    public record Plan(List<String> convert, List<ForeignKey> recreate, Map<String, String> skipped) {
    }

    /** {@code ACT_*} tables still in a utf8mb3 collation. */
    public static List<String> utf8mb3Tables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name from information_schema.tables "
                + "where table_schema = database() and upper(table_name) like 'ACT\\_%' and table_type = 'BASE TABLE' "
                + "and (table_collation like 'utf8\\_%' or table_collation like 'utf8mb3%') order by table_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Widest index of {@code table} in bytes if its character columns were utf8mb4 (4 bytes per character). */
    public static int widestIndexBytesAsUtf8mb4(Connection c, String table) throws SQLException {
        Map<String, Integer> perIndex = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("select s.index_name, s.sub_part, col.data_type, "
                + "col.character_maximum_length, col.numeric_precision "
                + "from information_schema.statistics s join information_schema.columns col "
                + "on col.table_schema = s.table_schema and col.table_name = s.table_name and col.column_name = s.column_name "
                + "where s.table_schema = database() and s.table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String index = rs.getString(1);
                    long subPart = rs.getLong(2);
                    boolean hasSubPart = !rs.wasNull();
                    String type = rs.getString(3);
                    long chars = rs.getLong(4);
                    int bytes;
                    if ("varchar".equalsIgnoreCase(type) || "char".equalsIgnoreCase(type)) {
                        bytes = (int) ((hasSubPart ? subPart : chars) * 4 + 2);
                    } else if (type != null && type.toLowerCase().contains("int")) {
                        bytes = 8;
                    } else {
                        bytes = 8; // datetime/decimal and others: small fixed widths
                    }
                    perIndex.merge(index, bytes, Integer::sum);
                }
            }
        }
        return perIndex.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * Every FK with at least one string column whose referencing or referenced table is in the current database, as
     * {@code information_schema.KEY_COLUMN_USAGE} and {@code REFERENTIAL_CONSTRAINTS} define it.
     */
    public static List<ForeignKey> characterForeignKeys(Connection c) throws SQLException {
        Map<String, Builder> byKey = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("select k.constraint_schema, k.constraint_name, k.table_name, "
                + "k.column_name, k.referenced_table_schema, k.referenced_table_name, k.referenced_column_name, "
                + "r.update_rule, r.delete_rule, col.character_set_name "
                + "from information_schema.key_column_usage k "
                + "join information_schema.referential_constraints r on r.constraint_schema = k.constraint_schema "
                + "and r.constraint_name = k.constraint_name and r.table_name = k.table_name "
                + "join information_schema.columns col on col.table_schema = k.table_schema "
                + "and col.table_name = k.table_name and col.column_name = k.column_name "
                + "where k.referenced_table_name is not null "
                + "and (k.table_schema = database() or k.referenced_table_schema = database()) "
                + "order by k.constraint_schema, k.table_name, k.constraint_name, k.ordinal_position");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString(1) + "." + rs.getString(3) + "." + rs.getString(2);
                Builder b = byKey.computeIfAbsent(key, k -> new Builder());
                b.schema = str(rs, 1);
                b.name = str(rs, 2);
                b.table = str(rs, 3);
                b.columns.add(str(rs, 4));
                b.refSchema = str(rs, 5);
                b.refTable = str(rs, 6);
                b.refColumns.add(str(rs, 7));
                b.updateRule = str(rs, 8);
                b.deleteRule = str(rs, 9);
                b.character |= rs.getString(10) != null;
            }
        }
        List<ForeignKey> out = new ArrayList<>();
        for (Builder b : byKey.values()) {
            if (b.character) {
                out.add(new ForeignKey(b.schema, b.name, b.table, b.columns, b.refSchema, b.refTable, b.refColumns,
                        b.updateRule, b.deleteRule));
            }
        }
        return out;
    }

    /** FK definitions journaled by a run that dropped them (normally none: the rows live only during a run). */
    public static List<ForeignKey> journal(Connection c) throws SQLException {
        List<ForeignKey> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select NAME_, VALUE_ from " + JOURNAL_TABLE
                + " where NAME_ like ? order by NAME_")) {
            ps.setString(1, JOURNAL_PREFIX.replace("_", "\\_") + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(ForeignKey.fromJournal(rs.getString(1), rs.getString(2)));
                }
            }
        }
        return out;
    }

    /**
     * The conversion plan (pure). Tables are linked by every FK of {@code foreignKeys} whose two ends are both in
     * {@code candidates}; a component is skipped when one of its tables has an index wider than
     * {@link #MAX_INDEX_BYTES} at utf8mb4, or when one of its FKs is in {@code incompatibleOutside} (an FK to or from
     * a table outside the candidates whose columns are not {@code utf8mb4_bin}).
     *
     * @param candidates          tables of the current schema still in utf8mb3
     * @param currentSchema       the schema of {@code candidates}
     * @param widestIndexBytes    per candidate, its widest index at 4 bytes per character
     * @param foreignKeys         string-column FKs touching the candidates (present ones and journaled missing ones)
     * @param incompatibleOutside names of the FKs whose end outside the candidates is not utf8mb4_bin
     */
    public static Plan plan(Collection<String> candidates, String currentSchema, Map<String, Integer> widestIndexBytes,
                            Collection<ForeignKey> foreignKeys, Set<String> incompatibleOutside) {
        Set<String> cand = new TreeSet<>(candidates);
        Map<String, String> parent = new HashMap<>();
        for (String t : cand) {
            parent.put(t, t);
        }
        for (ForeignKey fk : foreignKeys) {
            String a = candidateEnd(cand, currentSchema, fk.schema(), fk.table());
            String b = candidateEnd(cand, currentSchema, fk.refSchema(), fk.refTable());
            if (a != null && b != null) {
                parent.put(find(parent, a), find(parent, b));
            }
        }
        Map<String, String> blockedRoot = new TreeMap<>(); // component root -> reason
        for (String t : cand) {
            int w = widestIndexBytes.getOrDefault(t, 0);
            if (w > MAX_INDEX_BYTES) {
                blockedRoot.putIfAbsent(find(parent, t), t + ": widest index " + w + " bytes at utf8mb4 > "
                        + MAX_INDEX_BYTES);
            }
        }
        for (ForeignKey fk : foreignKeys) {
            if (incompatibleOutside.contains(fk.name())) {
                String a = candidateEnd(cand, currentSchema, fk.schema(), fk.table());
                String inside = a != null ? a : candidateEnd(cand, currentSchema, fk.refSchema(), fk.refTable());
                if (inside != null) {
                    blockedRoot.putIfAbsent(find(parent, inside), "FK " + fk.name() + " links " + inside
                            + " to a column outside the conversion that is not " + TARGET_COLLATION);
                }
            }
        }
        List<String> convert = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        for (String t : cand) {
            String reason = blockedRoot.get(find(parent, t));
            if (reason == null) {
                convert.add(t);
            } else {
                skipped.put(t, reason);
            }
        }
        Set<String> converting = new TreeSet<>(convert);
        List<ForeignKey> recreate = new ArrayList<>();
        for (ForeignKey fk : foreignKeys) {
            String a = candidateEnd(cand, currentSchema, fk.schema(), fk.table());
            String b = candidateEnd(cand, currentSchema, fk.refSchema(), fk.refTable());
            if ((a != null && converting.contains(a)) || (b != null && converting.contains(b))) {
                recreate.add(fk);
            }
        }
        return new Plan(convert, recreate, skipped);
    }

    /** Converts every convertible utf8mb3 {@code ACT_*} table (see the class comment); DDL auto-commits on MySQL. */
    public static Result apply(Connection c) throws SQLException {
        String schema = currentSchema(c);
        List<ForeignKey> journaled = journal(c);
        List<String> candidates = utf8mb3Tables(c);
        List<ForeignKey> present = characterForeignKeys(c);
        Set<String> presentNames = new LinkedHashSet<>();
        for (ForeignKey fk : present) {
            presentNames.add(fkKey(fk));
        }
        List<ForeignKey> missingJournaled = new ArrayList<>();
        for (ForeignKey fk : journaled) {
            if (!presentNames.contains(fkKey(fk))) {
                missingJournaled.add(fk);
            }
        }
        if (candidates.isEmpty() && journaled.isEmpty()) {
            return new Result(List.of(), Map.of(), List.of());
        }
        Set<String> cand = new TreeSet<>(candidates);
        List<ForeignKey> touching = new ArrayList<>();
        for (ForeignKey fk : present) {
            if (touches(cand, schema, fk)) {
                touching.add(fk);
            }
        }
        for (ForeignKey fk : missingJournaled) {
            if (touches(cand, schema, fk)) {
                touching.add(fk);
            }
        }
        Map<String, Integer> widest = new HashMap<>();
        for (String t : candidates) {
            widest.put(t, widestIndexBytesAsUtf8mb4(c, t));
        }
        Set<String> incompatibleOutside = new TreeSet<>();
        for (ForeignKey fk : touching) {
            boolean childIn = candidateEnd(cand, schema, fk.schema(), fk.table()) != null;
            boolean parentIn = candidateEnd(cand, schema, fk.refSchema(), fk.refTable()) != null;
            if (childIn != parentIn) {
                boolean ok = childIn ? columnsAreTarget(c, fk.refSchema(), fk.refTable(), fk.refColumns())
                        : columnsAreTarget(c, fk.schema(), fk.table(), fk.columns());
                if (!ok) {
                    incompatibleOutside.add(fk.name());
                }
            }
        }
        Plan plan = plan(candidates, schema, widest, touching, incompatibleOutside);

        List<ForeignKey> toDrop = new ArrayList<>();
        for (ForeignKey fk : plan.recreate()) {
            if (presentNames.contains(fkKey(fk))) {
                toDrop.add(fk);
            }
        }
        // every FK that must exist at the end: the ones dropped now plus the missing ones of an interrupted run
        Map<String, ForeignKey> toRecreate = new LinkedHashMap<>();
        for (ForeignKey fk : toDrop) {
            toRecreate.put(fkKey(fk), fk);
        }
        for (ForeignKey fk : missingJournaled) {
            toRecreate.putIfAbsent(fkKey(fk), fk);
        }

        writeJournal(c, toDrop, journaled);
        for (ForeignKey fk : toDrop) {
            exec(c, fk.dropSql());
        }
        for (String table : plan.convert()) {
            exec(c, "alter table " + q(table) + " convert to character set " + TARGET_CHARSET + " collate "
                    + TARGET_COLLATION);
        }
        List<String> recreated = new ArrayList<>();
        SQLException failure = null;
        for (ForeignKey fk : toRecreate.values()) {
            try {
                exec(c, fk.addSql()); // foreign_key_checks stays on: MySQL validates every existing row here
                recreated.add(fk.name());
            } catch (SQLException e) {
                // keep re-creating the others; this definition stays journaled and the run fails below
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        Set<String> nowPresent = new TreeSet<>();
        for (ForeignKey fk : characterForeignKeys(c)) {
            nowPresent.add(fkKey(fk));
        }
        List<ForeignKey> done = new ArrayList<>();
        for (ForeignKey fk : toRecreate.values()) {
            if (nowPresent.contains(fkKey(fk))) {
                done.add(fk);
            } else if (failure == null) {
                failure = new SQLException("P-2: foreign key " + fk.name() + " was not re-created");
            }
        }
        clearJournal(c, done, journaled, nowPresent);
        if (failure != null) {
            failure.addSuppressed(new SQLException("P-2: the definitions of the foreign keys not re-created stay "
                    + "journaled in " + JOURNAL_TABLE + " (" + JOURNAL_PREFIX + "*) and are re-created by the next run"));
            throw failure;
        }
        return new Result(plan.convert(), plan.skipped(), recreated);
    }

    private static void writeJournal(Connection c, List<ForeignKey> toDrop, List<ForeignKey> journaled)
            throws SQLException {
        if (toDrop.isEmpty()) {
            return;
        }
        Set<String> already = new TreeSet<>();
        for (ForeignKey fk : journaled) {
            already.add(fk.journalName());
        }
        Set<String> names = new TreeSet<>();
        for (ForeignKey fk : toDrop) {
            // checked for every FK before the first write, so a definition that cannot be journaled stops the run
            // before any FK is dropped
            if (fk.journalName().length() > JOURNAL_NAME_MAX || fk.journalValue().length() > JOURNAL_VALUE_MAX) {
                throw new SQLException("P-2: foreign key " + fk.schema() + "." + fk.name()
                        + " cannot be journaled (name or definition too long); nothing was changed");
            }
            if (!names.add(fk.journalName())) {
                throw new SQLException("P-2: two foreign keys named " + fk.name() + " in different schemas; "
                        + "nothing was changed");
            }
        }
        try (PreparedStatement ps = c.prepareStatement("insert into " + JOURNAL_TABLE
                + " (NAME_, VALUE_, REV_) values (?, ?, 1)")) {
            for (ForeignKey fk : toDrop) {
                if (!already.contains(fk.journalName())) {
                    ps.setString(1, fk.journalName());
                    ps.setString(2, fk.journalValue());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
        commit(c);
    }

    private static void clearJournal(Connection c, Collection<ForeignKey> recreated, List<ForeignKey> journaled,
                                     Set<String> nowPresent) throws SQLException {
        Set<String> rows = new TreeSet<>();
        for (ForeignKey fk : recreated) {
            rows.add(fk.journalName());
        }
        for (ForeignKey fk : journaled) {
            if (nowPresent.contains(fkKey(fk))) {
                rows.add(fk.journalName()); // a run interrupted after its re-create, before its journal clean-up
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("delete from " + JOURNAL_TABLE + " where NAME_ = ?")) {
            for (String r : rows) {
                ps.setString(1, r);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        commit(c);
    }

    private static boolean columnsAreTarget(Connection c, String schema, String table, List<String> columns)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select character_set_name, collation_name "
                + "from information_schema.columns where table_schema = ? and table_name = ? and column_name = ?")) {
            for (String col : columns) {
                ps.setString(1, schema);
                ps.setString(2, table);
                ps.setString(3, col);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    String cs = rs.getString(1);
                    if (cs != null && !(TARGET_CHARSET.equalsIgnoreCase(cs)
                            && TARGET_COLLATION.equalsIgnoreCase(rs.getString(2)))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean touches(Set<String> cand, String schema, ForeignKey fk) {
        return candidateEnd(cand, schema, fk.schema(), fk.table()) != null
                || candidateEnd(cand, schema, fk.refSchema(), fk.refTable()) != null;
    }

    private static String candidateEnd(Set<String> cand, String currentSchema, String schema, String table) {
        return Objects.equals(currentSchema, schema) && cand.contains(table) ? table : null;
    }

    private static String find(Map<String, String> parent, String t) {
        String p = parent.get(t);
        while (!p.equals(parent.get(p))) {
            p = parent.get(p);
        }
        parent.put(t, p);
        return p;
    }

    private static String fkKey(ForeignKey fk) {
        return fk.schema() + "." + fk.name();
    }

    private static String currentSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("select database()")) {
            rs.next();
            String db = rs.getString(1);
            if (db == null) {
                throw new SQLException("P-2: the connection has no current database");
            }
            return db;
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void commit(Connection c) throws SQLException {
        if (!c.getAutoCommit()) {
            c.commit();
        }
    }

    private static String q(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static String str(ResultSet rs, int i) throws SQLException {
        return rs.getString(i);
    }

    private static final class Builder {
        String schema;
        String name;
        String table;
        final List<String> columns = new ArrayList<>();
        String refSchema;
        String refTable;
        final List<String> refColumns = new ArrayList<>();
        String updateRule;
        String deleteRule;
        boolean character;
    }
}
