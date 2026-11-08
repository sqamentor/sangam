// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.Locale;

/**
 * {@code SANGAM_LIQUIBASE_MODE} (deploy README section 10). Read from the environment variable, the system property
 * {@code sangam.liquibase.mode}, or the command-line argument {@code --sangam.liquibase.mode=...}.
 * <ul>
 *   <li>{@code startup} (default): apply pending changesets at start, then serve;</li>
 *   <li>{@code validate}: fail start-up if any changeset is pending;</li>
 *   <li>{@code migrate-only}: apply the product changelog, let Operaton create/upgrade its schema, exit 0;</li>
 *   <li>{@code update-sql}: write the pending SQL to stdout or {@code SANGAM_LIQUIBASE_SQL_FILE}, exit 0;</li>
 *   <li>{@code release-locks}: force-release the Liquibase lock, exit 0.</li>
 * </ul>
 */
public enum LiquibaseMode {
    STARTUP, VALIDATE, MIGRATE_ONLY, UPDATE_SQL, RELEASE_LOCKS;

    public static final String ENV = "SANGAM_LIQUIBASE_MODE";
    public static final String PROPERTY = "sangam.liquibase.mode";

    public static LiquibaseMode parse(String value) {
        if (value == null || value.isBlank()) {
            return STARTUP;
        }
        String v = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(ENV + "=" + value
                    + " is not one of startup | validate | migrate-only | update-sql | release-locks");
        }
    }

    /** Resolves the mode before the Spring context exists (needed to adjust engine properties for one-shot runs). */
    public static LiquibaseMode resolve(String[] args) {
        String prefix = "--" + PROPERTY + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return parse(a.substring(prefix.length()));
            }
        }
        String sys = System.getProperty(PROPERTY);
        return parse(sys != null ? sys : System.getenv(ENV));
    }

    /** A one-shot mode runs a schema task and exits 0 without serving traffic. */
    public boolean oneShot() {
        return this == MIGRATE_ONLY || this == UPDATE_SQL || this == RELEASE_LOCKS;
    }

    /**
     * System properties for one-shot runs (they outrank application.yaml): no job executor, no listening ports in the
     * way of the serving pods; for {@code migrate-only} the Operaton schema is created/updated in this run.
     */
    public void applySystemProperties() {
        System.setProperty(PROPERTY, name().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (!oneShot()) {
            return;
        }
        System.setProperty("operaton.bpm.job-execution.enabled", "false");
        System.setProperty("server.port", "0");
        System.setProperty("management.server.port", "0");
        if (this == MIGRATE_ONLY) {
            System.setProperty("operaton.bpm.database.schema-update", "true");
        }
    }
}
