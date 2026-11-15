// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.core.env.Environment;

/**
 * Decision 334: a start whose engine only checks its schema ({@code operaton.bpm.database.schema-update=false}, the
 * default of the server and, outside a validated development environment, of the starter) on a database without the
 * Operaton tables is refused by name, {@value #CODE}, before anything is created or changed, instead of the engine's
 * raw {@code ENGINE-03057} after the product changelog has already run. One check for the server and every starter
 * host: {@code SchemaBootstrap} calls it before its Liquibase or development schema step of a serving start,
 * {@link EngineConfigurationCustomizer} before the engine is configured.
 *
 * <p>The setting is read as the Operaton starter applies it: {@code operaton.bpm.database.schema-update} (the
 * starter's default when unset is {@value #STARTER_DEFAULT}, {@code DatabaseProperty}), overridden by the generic
 * engine property {@code databaseSchemaUpdate} when that is given. Only {@code false} is a check-only start; every
 * other value lets the engine create its tables. An explicit {@code true} is honoured in production as in the server
 * (the h2 profile, {@code SANGAM_ENGINE_SCHEMA_UPDATE}, the {@code migrate-only} run); it is the operator's decision.
 *
 * <p>The probe is the table Operaton's own check-only start looks for ({@value #PROBE_TABLE}, its
 * {@code isEngineTablePresent}), with the configured table prefix and schema; without a configured schema every schema
 * counts, as in Operaton's probe. A database that cannot be asked is
 * refused as well (fail closed).
 */
public final class EngineSchemaGuard {

    /** The problem code of the refusal. */
    public static final String CODE = "ENGINE_SCHEMA_MISSING";
    static final String SCHEMA_UPDATE = "operaton.bpm.database.schema-update";
    static final String TABLE_PREFIX = "operaton.bpm.database.table-prefix";
    static final String SCHEMA_NAME = "operaton.bpm.database.schema-name";
    static final String GENERIC = "operaton.bpm.generic-properties.properties.";
    /** The Operaton starter's own default of {@value #SCHEMA_UPDATE}. */
    static final String STARTER_DEFAULT = "true";
    /** The table Operaton's check-only start probes. */
    static final String PROBE_TABLE = "ACT_RU_EXECUTION";

    private EngineSchemaGuard() {
    }

    /** Refused start: the engine tables are missing and this start does not create them. */
    public static final class EngineSchemaMissingException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        EngineSchemaMissingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The value the engine will use for its schema operation at start. */
    static String schemaUpdate(Environment env) {
        for (String generic : new String[] {GENERIC + "database-schema-update", GENERIC + "databaseSchemaUpdate"}) {
            String v = env.getProperty(generic);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        String v = env.getProperty(SCHEMA_UPDATE);
        return v == null || v.isBlank() ? STARTER_DEFAULT : v.trim();
    }

    /** True when the engine will only check its schema at start (it creates nothing). */
    public static boolean checkOnly(Environment env) {
        return "false".equalsIgnoreCase(schemaUpdate(env));
    }

    /**
     * Refuses the start with {@value #CODE} when the engine only checks its schema and its tables are not in
     * {@code dataSource}; does nothing otherwise.
     */
    public static void require(Environment env, DataSource dataSource) {
        if (!checkOnly(env)) {
            return;
        }
        String prefix = env.getProperty(TABLE_PREFIX, "");
        String schema = env.getProperty(SCHEMA_NAME);
        boolean present;
        try {
            present = enginePresent(dataSource, prefix, schema);
        } catch (SQLException | RuntimeException e) {
            throw new EngineSchemaMissingException(CODE + ": cannot check for the Operaton engine tables ("
                    + PROBE_TABLE + ") before the start; the start is refused (" + e.getMessage() + ")", e);
        }
        if (!present) {
            throw new EngineSchemaMissingException(CODE + ": the Operaton engine tables (" + prefix + PROBE_TABLE
                    + ") are not in this database, and this start does not create them (" + SCHEMA_UPDATE
                    + "=false: check only, the default outside a validated development environment). Nothing was"
                    + " created or changed. Create the schema first with the one-shot migration run"
                    + " SANGAM_LIQUIBASE_MODE=migrate-only (product changelog and engine schema, then exit; deploy"
                    + " README, section 1.3) or apply the SQL of SANGAM_LIQUIBASE_MODE=update-sql, then start again.",
                    null);
        }
    }

    /** Whether the probe table exists, with the configured prefix ({@code SCHEMA.} or {@code PRE_}) and schema. */
    static boolean enginePresent(DataSource dataSource, String tablePrefix, String schemaName) throws SQLException {
        String prefix = tablePrefix == null ? "" : tablePrefix.trim();
        String schema = schemaName == null || schemaName.isBlank() ? null : schemaName.trim();
        int dot = prefix.lastIndexOf('.');
        if (dot >= 0) {
            if (schema == null && dot > 0) {
                schema = prefix.substring(0, dot);
            }
            prefix = prefix.substring(dot + 1);
        }
        String table = prefix + PROBE_TABLE;
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            for (String s : variants(schema)) {
                for (String t : variants(table)) {
                    try (ResultSet rs = md.getTables(null, s, t, new String[] {"TABLE"})) {
                        if (rs.next()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** As given, upper case and lower case (the dialects store unquoted names in either); null stays null. */
    private static Set<String> variants(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) {
            out.add(null);
            return out;
        }
        out.add(name);
        out.add(name.toUpperCase(Locale.ROOT));
        out.add(name.toLowerCase(Locale.ROOT));
        return out;
    }
}
