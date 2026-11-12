// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.sangam.core.layer.Dialect;

/**
 * The database-free part of {@link IsolatedProductTables}: which namespace a run uses on each Tier-1 database, the
 * administrator statements that create it, the per-test reset and the paging clause the specs need. Unit-tested by
 * {@link IsolationPlanTest}; the statements that need a live database (Oracle's grant copy, the schema apply) stay in
 * {@link IsolatedProductTables}.
 *
 * <p>Namespace per database, chosen so that an unqualified table name resolves <b>only</b> inside it (no silent
 * fallback to the shared tables of the running server):
 * <ul>
 *   <li>PostgreSQL: a schema; the pool's {@code currentSchema} is that schema alone (no {@code public});</li>
 *   <li>SQL Server: a database with the same RCSI settings as the server's (unqualified names never cross databases);</li>
 *   <li>MySQL: a database, granted to the server's own user (unqualified names resolve in the current database only);</li>
 *   <li>Oracle: a user/schema with the server user's tablespace, system privileges and roles; the pool logs in as it.</li>
 * </ul>
 */
final class IsolationPlan {

    /** Unquoted identifier valid on every Tier-1 database (Oracle 23 allows 128 bytes; 30 keeps older limits). */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,29}");
    /** An Oracle system privilege name as listed by DBA_SYS_PRIVS (words of capitals and underscores). */
    private static final Pattern PRIVILEGE = Pattern.compile("[A-Z][A-Z_]*( [A-Z][A-Z_]*)*");

    private IsolationPlan() {
    }

    /** The product-layer dialect of an acceptance database name ({@code AcceptanceDatabase.name()}). */
    static Dialect dialectOf(String db) {
        return switch (db) {
            case "postgres" -> Dialect.POSTGRESQL;
            case "sqlserver" -> Dialect.SQLSERVER;
            case "mysql" -> Dialect.MYSQL;
            case "oracle" -> Dialect.ORACLE;
            default -> throw new IllegalArgumentException("unknown acceptance database '" + db + "'");
        };
    }

    /** The run's namespace: {@code acc_<run>}; upper case on Oracle (unquoted identifiers fold to upper case there). */
    static String namespace(Dialect dialect, String run) {
        String ns = identifier("acc_" + run);
        return dialect == Dialect.ORACLE ? ns.toUpperCase(Locale.ROOT) : ns.toLowerCase(Locale.ROOT);
    }

    /**
     * Administrator statements that create the namespace on PostgreSQL, SQL Server and MySQL. SQL Server repeats the
     * settings {@code AcceptanceDatabase} gives the server's own database (RCSI and snapshot isolation on); MySQL grants
     * the new database to {@code appUser} exactly as the image grants the server's database to it.
     */
    static List<String> createNamespace(Dialect dialect, String ns, String appUser) {
        identifier(ns);
        return switch (dialect) {
            case POSTGRESQL -> List.of("CREATE SCHEMA " + ns);
            case SQLSERVER -> List.of("CREATE DATABASE " + ns,
                    "ALTER DATABASE " + ns + " SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE",
                    "ALTER DATABASE " + ns + " SET ALLOW_SNAPSHOT_ISOLATION ON");
            case MYSQL -> List.of("CREATE DATABASE `" + ns + "`",
                    "GRANT ALL PRIVILEGES ON `" + ns + "`.* TO '" + identifier(appUser) + "'@'%'");
            case ORACLE -> throw new IllegalArgumentException("Oracle namespaces are users: use createOracleUser");
        };
    }

    /**
     * Oracle: the namespace user, created with the server user's default tablespace and given the server user's own
     * system privileges and roles (read from DBA_SYS_PRIVS / DBA_ROLE_PRIVS by the caller), so the product changelog
     * runs with the rights it has on the server's schema.
     */
    static List<String> createOracleUser(String ns, String password, String tablespace, List<String> sysPrivileges,
                                         List<String> roles) {
        identifier(ns);
        identifier(tablespace);
        if (password == null || password.isEmpty() || password.indexOf('"') >= 0) {
            throw new IllegalArgumentException("the Oracle password must be non-empty and contain no double quote");
        }
        List<String> out = new ArrayList<>();
        out.add("CREATE USER " + ns + " IDENTIFIED BY \"" + password + "\" DEFAULT TABLESPACE " + tablespace
                + " QUOTA UNLIMITED ON " + tablespace);
        for (String p : sysPrivileges) {
            if (!PRIVILEGE.matcher(p).matches()) {
                throw new IllegalArgumentException("not a system privilege name: " + p);
            }
            out.add("GRANT " + p + " TO " + ns);
        }
        for (String r : roles) {
            out.add("GRANT " + identifier(r) + " TO " + ns);
        }
        return out;
    }

    /** PostgreSQL JDBC URL whose search path is {@code schema} alone. */
    static String withCurrentSchema(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + identifier(schema);
    }

    /**
     * Per-test reset of {@code tables}, identities restarted where TRUNCATE does it: PostgreSQL in one statement
     * ({@code RESTART IDENTITY}); SQL Server and MySQL per table (TRUNCATE TABLE resets IDENTITY / AUTO_INCREMENT);
     * Oracle per table (TRUNCATE leaves an identity column's next value as it is). No product table has a foreign key
     * (none in the baselines), so the order does not matter.
     */
    static List<String> truncate(Dialect dialect, List<String> tables) {
        if (tables.isEmpty()) {
            throw new IllegalArgumentException("no tables to truncate");
        }
        tables.forEach(IsolationPlan::identifier);
        if (dialect == Dialect.POSTGRESQL) {
            return List.of("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY");
        }
        return tables.stream().map(t -> "TRUNCATE TABLE " + t).toList();
    }

    /**
     * Paging clause appended after an {@code ORDER BY}: {@code LIMIT .. OFFSET} on PostgreSQL and MySQL, the SQL:2008
     * {@code OFFSET .. ROWS FETCH NEXT .. ROWS ONLY} on SQL Server and Oracle (neither has LIMIT).
     */
    static String page(Dialect dialect, int limit, int offset) {
        if (limit <= 0 || offset < 0) {
            throw new IllegalArgumentException("limit must be positive and offset non-negative");
        }
        return switch (dialect) {
            case POSTGRESQL, MYSQL -> " LIMIT " + limit + " OFFSET " + offset;
            case SQLSERVER, ORACLE -> " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        };
    }

    private static String identifier(String s) {
        if (s == null || !IDENTIFIER.matcher(s).matches()) {
            throw new IllegalArgumentException("not a plain identifier: " + s);
        }
        return s;
    }
}
