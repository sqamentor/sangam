package org.sangam.spike.sim.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PostgreSQL always runs (Docker is required). Oracle Free and SQL Server run only with
 * {@code SPIKE_DB_ALL=true}: they are large images and memory-hungry on a 32 GB machine.
 */
class DatabasesTest {

    private static void requireDocker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    private static int selectOne(Databases.Running db) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(db.selectOne())) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    @Test
    void unknownNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Databases.start("db2"));
    }

    @Test
    void postgresSelectOneAndOneAtATime() throws Exception {
        requireDocker();
        try (Databases.Running db = Databases.start("postgres")) {
            assertEquals("postgresql", db.dialect());
            assertTrue(db.jdbcUrl().startsWith("jdbc:postgresql://"), db.jdbcUrl());
            assertEquals(1, selectOne(db));
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> Databases.start("sqlserver"));
            assertTrue(e.getMessage().contains("still running"));
        }
        assertNull(Databases.current());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = Databases.ENV_ALL, matches = "true")
    void oracleSelectOne() throws Exception {
        requireDocker();
        try (Databases.Running db = Databases.start("oracle")) {
            assertEquals("oracle", db.dialect());
            assertTrue(db.jdbcUrl().startsWith("jdbc:oracle:"), db.jdbcUrl());
            assertEquals(1, selectOne(db));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = Databases.ENV_ALL, matches = "true")
    void sqlServerSelectOne() throws Exception {
        requireDocker();
        try (Databases.Running db = Databases.start("sqlserver")) {
            assertEquals("sqlserver", db.dialect());
            assertTrue(db.jdbcUrl().startsWith("jdbc:sqlserver://"), db.jdbcUrl());
            assertEquals(1, selectOne(db));
        }
    }
}
