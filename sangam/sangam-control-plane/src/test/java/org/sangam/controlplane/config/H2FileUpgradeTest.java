// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.Timestamp;
import java.util.Map;

import javax.sql.DataSource;

import liquibase.integration.spring.SpringLiquibase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * UP-L7: the control plane and rollup keep their data in H2 file databases (application.yaml), so moving the H2 pin
 * must not strand a database an older release wrote. H2 2.3.232 (the previous pin, copied from Maven Central by
 * maven-dependency-plugin into target/h2-previous and loaded in its own class loader) builds the real control-plane
 * schema with Liquibase and writes rows; the H2 on the test class path (the current pin) then opens the same file,
 * finds every changeset applied with matching checksums, reads the rows back unchanged and writes to it.
 */
class H2FileUpgradeTest {

    private static final String PREVIOUS = "2.3.232";

    @TempDir
    Path dir;

    @Test
    void fileWrittenByPreviousH2IsReadAndWrittenByCurrentH2() throws Exception {
        Path jar = Path.of("target", "h2-previous", "h2-" + PREVIOUS + ".jar");
        assertTrue(Files.isRegularFile(jar), "missing " + jar.toAbsolutePath() + " (maven-dependency-plugin copy)");
        String url = "jdbc:h2:file:" + dir.resolve("control-plane").toAbsolutePath()
                + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE";
        byte[] blob = {0, 1, 2, (byte) 0xFE, (byte) 0xFF};
        Timestamp at = Timestamp.valueOf("2026-11-18 10:15:30.123456");
        int changesets;

        try (URLClassLoader old = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Driver oldDriver = (Driver) old.loadClass("org.h2.Driver").getDeclaredConstructor().newInstance();
            JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(oldDriver, url, "sa", ""));
            assertEquals(PREVIOUS, jdbc.queryForObject("SELECT H2VERSION()", String.class));
            liquibase(jdbc.getDataSource());
            changesets = jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Integer.class);
            assertTrue(changesets > 0);
            jdbc.execute("CREATE TABLE UPGRADE_PROBE (id BIGINT PRIMARY KEY, name VARCHAR(64), amount NUMERIC(19,4),"
                    + " at TIMESTAMP, flag BOOLEAN, data VARBINARY(16), doc JSON)");
            jdbc.execute("CREATE INDEX UPGRADE_PROBE_NAME ON UPGRADE_PROBE(name)");
            jdbc.update("INSERT INTO UPGRADE_PROBE VALUES (?, ?, ?, ?, ?, ?, ? FORMAT JSON)", 1L, "सङ्गम",
                    new BigDecimal("12345.6789"), at, true, blob, "{\"k\":[1,2]}");
            jdbc.execute("SHUTDOWN");
        }

        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", ""));
        String current = jdbc.queryForObject("SELECT H2VERSION()", String.class);
        assertNotEquals(PREVIOUS, current);
        liquibase(jdbc.getDataSource());
        assertEquals(changesets, jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Integer.class),
                "no changeset re-applied after the H2 upgrade");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT name, amount, at, flag, data, CAST(doc AS VARCHAR) AS doc FROM UPGRADE_PROBE WHERE id = 1");
        assertEquals("सङ्गम", row.get("NAME"));
        assertEquals(0, new BigDecimal("12345.6789").compareTo((BigDecimal) row.get("AMOUNT")));
        assertEquals(at, row.get("AT"));
        assertEquals(Boolean.TRUE, row.get("FLAG"));
        assertArrayEquals(blob, (byte[]) row.get("DATA"));
        assertEquals("{\"k\":[1,2]}", row.get("DOC"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM UPGRADE_PROBE WHERE name = 'सङ्गम'", Integer.class));
        jdbc.update("INSERT INTO UPGRADE_PROBE (id, name) VALUES (2, 'after-upgrade')");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM UPGRADE_PROBE", Integer.class));
        jdbc.execute("SHUTDOWN");
    }

    private static void liquibase(DataSource ds) throws Exception {
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(ds);
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.setChangeLog("classpath:db/changelog/controlplane/cp-master.xml");
        lb.afterPropertiesSet();
    }
}
