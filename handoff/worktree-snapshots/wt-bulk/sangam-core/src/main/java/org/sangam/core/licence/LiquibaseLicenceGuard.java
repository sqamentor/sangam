// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.licence;

/**
 * LIC-7 / ADR-D-7 (SG-26): Sangam is licensed and tested with Liquibase 4.x (Apache-2.0); Liquibase 5.x is FSL-licensed
 * and not allowed. A consumer whose own dependency management (for example the Spring Boot 4 BOM, which manages
 * liquibase 5.x) overrides Sangam's 4.33.0 pin would otherwise run Liquibase 5 silently. Every Sangam schema entry point
 * (sangam-persistence, server, control plane, roll-up) calls {@link #check()} before Liquibase touches a database.
 * The version is read reflectively so sangam-core does not depend on Liquibase.
 */
public final class LiquibaseLicenceGuard {

    /** The only allowed Liquibase major version (ADR-D-7: 5.x only after legal review). */
    public static final int ALLOWED_MAJOR = 4;

    private LiquibaseLicenceGuard() {
    }

    /** @throws IllegalStateException when Liquibase is on the class path and is not major {@value #ALLOWED_MAJOR} */
    public static void check() {
        String version;
        try {
            Class<?> util = Class.forName("liquibase.util.LiquibaseUtil");
            version = String.valueOf(util.getMethod("getBuildVersion").invoke(null));
        } catch (ClassNotFoundException e) {
            return; // no Liquibase, nothing can run
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read the Liquibase version (LIC-7 check)", e);
        }
        check(version);
    }

    /** Checks an explicit version string; package-visible for tests. */
    public static void check(String version) {
        if (major(version) != ALLOWED_MAJOR) {
            throw new IllegalStateException("Liquibase " + version + " is on the class path; Sangam allows only "
                    + ALLOWED_MAJOR + ".x (LIC-7, ADR-D-7: 5.x is FSL-licensed). Your build's dependency management "
                    + "overrides Sangam's pin: import org.sangam:sangam-bom before other BOMs, or set the Spring Boot "
                    + "property liquibase.version=4.33.0.");
        }
    }

    static int major(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        String v = version.trim();
        int dot = v.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? v : v.substring(0, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
