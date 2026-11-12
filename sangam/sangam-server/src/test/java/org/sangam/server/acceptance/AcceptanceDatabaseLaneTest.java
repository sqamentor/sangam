// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SL-112: lane to profile mapping and the explicit Db2 refusal (no container is started). */
class AcceptanceDatabaseLaneTest {

    @Test
    void lanesMapToTheirProfileAndDialectFamily() {
        assertEquals("mysql", AcceptanceDatabase.nameOf("mariadb"), "MariaDB runs the mysql profile");
        assertEquals("sqlserver", AcceptanceDatabase.nameOf("sqlserver2025"));
        for (String tier1 : new String[] {"postgres", "oracle", "sqlserver", "mysql"}) {
            assertEquals(tier1, AcceptanceDatabase.nameOf(tier1));
        }
    }

    @Test
    void db2WithoutTheDriverRefusesNamingLic3() {
        ClassLoader empty = new URLClassLoader(new URL[0], null);
        String m = AcceptanceDatabase.db2Refusal(empty).getMessage();
        assertTrue(m.contains("LIC-3") && m.contains(AcceptanceDatabase.DB2_DRIVER), m);
    }

    @Test
    void db2WithTheDriverStillRefusesBecauseTheServerHasNoDb2Profile() {
        ClassLoader withDriver = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (AcceptanceDatabase.DB2_DRIVER.equals(name)) {
                    return Object.class; // stands in for the customer's driver class
                }
                throw new ClassNotFoundException(name);
            }
        };
        String m = AcceptanceDatabase.db2Refusal(withDriver).getMessage();
        assertTrue(m.contains("no db2 profile") && m.contains("SL-112"), m);
    }

    @Test
    void theMariaDbLaneUsesTheMatrixImageAndTheServerDriver() {
        assertEquals("mariadb:11.4", AcceptanceDatabase.MariaDbContainer.IMAGE, "same image as db-matrix.yml");
        AcceptanceDatabase.MariaDbContainer c = new AcceptanceDatabase.MariaDbContainer();
        assertEquals("org.mariadb.jdbc.Driver", c.getDriverClassName());
        assertEquals("sangam", c.getDatabaseName());
        assertTrue(c.getExposedPorts().contains(3306));
        assertEquals("sangam", c.getEnvMap().get("MARIADB_ROOT_PASSWORD"), "root password = container password");
    }
}
