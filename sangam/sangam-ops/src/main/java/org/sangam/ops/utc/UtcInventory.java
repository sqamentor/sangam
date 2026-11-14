// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decisions 150 and 203: the classified timestamp columns the UTC conversion tool may touch. The product part is the
 * generated {@code db/timestamp-inventory.csv} of sangam-persistence ({@link #product()}); the engine owner's Operaton
 * {@code ACT_*} columns join through {@link #productAndEngine()}. Only {@link ColumnClass#EVENT_INSTANT} columns are
 * converted; every other class is refused by name.
 */
public final class UtcInventory {

    /** Classpath resource written by {@code sangam-persistence/tools/generate_changelogs.py}. */
    public static final String RESOURCE = "db/timestamp-inventory.csv";
    /** Classpath resource of sangam-engine {@code EngineTimestampInventory.RESOURCE}: the Operaton {@code ACT_*} rows. */
    public static final String ENGINE_RESOURCE = "db/engine-timestamp-inventory.csv";

    /**
     * The engine columns Operaton 2.2.0 declares {@code timestamp} on MySQL and MariaDB (the other engine columns are
     * {@code datetime}): the server stores them converted from the session time zone of the writing connection, which
     * the tool cannot know for past writes, so they are refused on {@code Dialect.MYSQL} (decision 150). Copy of
     * sangam-engine {@code EngineTimestampInventory.MYSQL_SESSION_ZONED} (sangam-ops cannot compile against
     * sangam-engine); the two sets are equal by review, a cross-check test is requested from the engine owner.
     */
    public static final Set<String> MYSQL_SESSION_ZONED = Set.of("ACT_HI_IDENTITYLINK.TIMESTAMP_",
            "ACT_HI_OP_LOG.TIMESTAMP_", "ACT_HI_INCIDENT.CREATE_TIME_", "ACT_HI_INCIDENT.END_TIME_",
            "ACT_HI_EXT_TASK_LOG.TIMESTAMP_");
    static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /** What a stored time means, and so whether the tool may convert it. */
    public enum ColumnClass {
        /** An instant (zone-aware column type, or MySQL DATETIME(6) holding UTC): the only class converted. */
        EVENT_INSTANT,
        /** A local business date-time: shown in its own zone, never shifted. */
        LOCAL_BUSINESS,
        /** A calendar date: never converted. */
        DATE_ONLY,
        /** In a table holding a hash, digest or signature: refused until a preservation strategy is decided. */
        PROTECTED,
        /** The tool's own run and snapshot tables. */
        TOOL
    }

    /**
     * One inventoried column.
     *
     * @param table     table name (upper case)
     * @param column    column name (upper case)
     * @param token     the generator type token ({@code {ts}} or {@code {date}}), or the engine owner's type name
     * @param cls       the class
     * @param changeset the changeset that created the column, or the owner's reference
     */
    public record Column(String table, String column, String token, ColumnClass cls, String changeset) {
        public Column {
            table = identifier(table, "table");
            column = identifier(column, "column");
            if (cls == null) {
                throw new IllegalArgumentException("class is required: " + table + "." + column);
            }
        }
    }

    private final Map<String, Column> columns;

    private UtcInventory(Map<String, Column> columns) {
        this.columns = Map.copyOf(columns);
    }

    /** The product inventory from the classpath; fails when sangam-persistence (and so the resource) is absent. */
    public static UtcInventory product() {
        return parse(resource(RESOURCE, "sangam-persistence"));
    }

    /**
     * The product inventory plus the engine's 66 Operaton {@code ACT_*} rows ({@link #ENGINE_RESOURCE}, written and kept
     * equal to Operaton's create scripts by sangam-engine {@code EngineTimestampInventory}). sangam-ops reaches
     * sangam-engine only at run time (sangam-integration declares it {@code provided}), so the resource is read by name;
     * absent, this fails: the engine rows are never silently left out (decision 150, W4-E REQUEST 01:36:38Z).
     */
    public static UtcInventory productAndEngine() {
        return product().withEngine(resource(ENGINE_RESOURCE, "sangam-engine"));
    }

    /**
     * This inventory plus engine rows in the CSV form: every row must be an {@code ACT_*} table, type token
     * {@code timestamp} and class {@link ColumnClass#EVENT_INSTANT}, as the engine owner writes them.
     */
    public UtcInventory withEngine(String engineCsv) {
        List<Column> engine = parse(engineCsv).columns();
        if (engine.isEmpty()) {
            throw new IllegalArgumentException("the engine inventory has no row");
        }
        for (Column c : engine) {
            if (!c.table().startsWith("ACT_") || !"timestamp".equals(c.token()) || c.cls() != ColumnClass.EVENT_INSTANT) {
                throw new IllegalArgumentException("not an engine inventory row: " + c);
            }
        }
        return with(engine);
    }

    private static String resource(String name, String module) {
        try (InputStream in = UtcInventory.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is not on the classpath (" + module + " missing):"
                        + " no column is convertible without the inventory");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + name, e);
        }
    }

    /** Parses the CSV form {@code table,column,token,class,changeset} (header line first). */
    public static UtcInventory parse(String csv) {
        List<String> lines = csv.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty() || !lines.get(0).equals("table,column,token,class,changeset")) {
            throw new IllegalArgumentException("not a timestamp inventory: header missing");
        }
        List<Column> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            if (f.length != 5) {
                throw new IllegalArgumentException("inventory line has " + f.length + " fields: " + line);
            }
            out.add(new Column(f[0], f[1], f[2], ColumnClass.valueOf(f[3]), f[4]));
        }
        return new UtcInventory(Map.of()).with(out);
    }

    /** This inventory plus {@code more}; a column listed twice is refused. */
    public UtcInventory with(Collection<Column> more) {
        Map<String, Column> all = new LinkedHashMap<>(columns);
        for (Column c : more) {
            if (all.putIfAbsent(key(c.table(), c.column()), c) != null) {
                throw new IllegalArgumentException("column listed twice: " + c.table() + "." + c.column());
            }
        }
        return new UtcInventory(all);
    }

    public Optional<Column> find(String table, String column) {
        return Optional.ofNullable(columns.get(key(identifier(table, "table"), identifier(column, "column"))));
    }

    /** Every inventoried column of {@code table}. */
    public List<Column> columnsOf(String table) {
        String t = identifier(table, "table");
        return columns.values().stream().filter(c -> c.table().equals(t)).toList();
    }

    /** Every inventoried column, in listing order. */
    public List<Column> columns() {
        return List.copyOf(columns.values());
    }

    public int size() {
        return columns.size();
    }

    static String identifier(String name, String what) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid " + what + " name: " + name);
        }
        return name.toUpperCase(Locale.ROOT);
    }

    private static String key(String table, String column) {
        return table + "." + column;
    }
}
