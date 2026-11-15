// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.sangam.persistence.SangamSchema;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * M-108 (decisions 156 and 323) on a real YugabyteDB YSQL server: the db-matrix lane {@code yugabytedb}
 * ({@code -Dsangam.db=yugabytedb}, profile sangam-db-it; experimental, report-only, never gating). It checks on the
 * server what the unit test {@link YugabyteDbMappingTest} fakes: the {@code version()} marker, the refusal without the
 * experimental mode, READ COMMITTED in effect with {@code --yb_enable_read_committed_isolation=true}, and the product
 * changelog applied as {@code postgresql}; and it probes the open M-108 point, whether identity values follow the
 * insert order across connections ({@code PendingAudit} relies on that order).
 *
 * <p>The image comes only from {@code SANGAM_IT_IMAGE_YUGABYTEDB} and must be pinned by digest; an unpinned or missing
 * image fails the lane (never a tag-only pull). Written, NOT_RUN locally (no image downloads).
 */
class YugabyteDbIT {

    static final String IMAGE_VARIABLE = "SANGAM_IT_IMAGE_YUGABYTEDB";
    static final Pattern PINNED = Pattern.compile("^[^@\\s]+:[^@\\s]+@sha256:[0-9a-f]{64}$");

    static YugabyteContainer yb;
    static PGSimpleDataSource ds;

    /**
     * YugabyteDB through Testcontainers' JDBC base and the PostgreSQL driver: one node started by {@code yugabyted} in
     * the foreground, READ COMMITTED enabled on the tserver, YSQL on 5433 with the image's default {@code yugabyte}
     * user and database. The JDBC base waits until the test query answers.
     */
    static final class YugabyteContainer extends JdbcDatabaseContainer<YugabyteContainer> {
        static final int YSQL_PORT = 5433;

        YugabyteContainer(DockerImageName image) {
            super(image);
            addExposedPort(YSQL_PORT);
            setCommand("bin/yugabyted", "start", "--background=false",
                    "--tserver_flags=yb_enable_read_committed_isolation=true");
        }

        @Override
        public String getDriverClassName() {
            return "org.postgresql.Driver";
        }

        @Override
        public String getJdbcUrl() {
            return "jdbc:postgresql://" + getHost() + ":" + getMappedPort(YSQL_PORT) + "/" + getDatabaseName();
        }

        @Override
        public String getDatabaseName() {
            return "yugabyte";
        }

        @Override
        public String getUsername() {
            return "yugabyte";
        }

        @Override
        public String getPassword() {
            return "yugabyte";
        }

        @Override
        protected String getTestQueryString() {
            return "SELECT 1";
        }
    }

    @BeforeAll
    static void start() {
        String db = System.getProperty("sangam.db", "");
        assumeTrue("yugabytedb".equals(db.trim().toLowerCase(Locale.ROOT)), "YugabyteDB lane only (sangam.db=" + db + ")");
        String image = System.getenv(IMAGE_VARIABLE);
        if (image == null || !PINNED.matcher(image.trim()).matches()) {
            throw new IllegalStateException(IMAGE_VARIABLE + " must name the YugabyteDB image pinned by digest "
                    + "(<repo>:<tag>@sha256:<64 hex>), found " + (image == null ? "nothing" : "'" + image + "'"));
        }
        yb = new YugabyteContainer(DockerImageName.parse(image.trim()));
        yb.withStartupTimeout(Duration.ofMinutes(5));
        yb.start();
        ds = new PGSimpleDataSource();
        ds.setUrl(yb.getJdbcUrl());
        ds.setUser(yb.getUsername());
        ds.setPassword(yb.getPassword());
        SangamSchema.apply(new IsolationGuardDataSource(ds));
    }

    @AfterAll
    static void stop() {
        if (yb != null) {
            yb.stop();
        }
    }

    @Test
    void theDriverSaysPostgresqlAndTheVersionTextSaysYugabyteDb() throws Exception {
        try (Connection c = ds.getConnection()) {
            assertEquals("PostgreSQL", c.getMetaData().getDatabaseProductName());
            String version = EngineDialect.serverVersion(c);
            assertTrue(EngineDialect.isYugabyteDbVersion(version), version);
        }
    }

    @Test
    void theGuardRefusesYugabyteDbWithoutTheExperimentalMode() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DatabaseGuard.check(new IsolationGuardDataSource(ds), true, "mysql", false, false));
        assertTrue(e.getMessage().startsWith(DatabaseGuard.YUGABYTEDB_EXPERIMENTAL_OFF), e.getMessage());
    }

    @Test
    void theGuardAdmitsYugabyteDbWithReadCommittedInEffect() {
        DatabaseGuard.Findings f = DatabaseGuard.check(new IsolationGuardDataSource(ds), true, "mysql", false, true);
        assertTrue(f.experimentalYugabyteDb());
        assertEquals(EngineDialect.POSTGRESQL, f.dialect());
        assertEquals("postgres", f.operatonDatabaseType());
    }

    @Test
    void theProductChangelogAppliesAsPostgresql() throws Exception {
        try (Connection c = ds.getConnection()) {
            for (String table : SangamSchema.PRODUCT_TABLES) {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = current_schema() AND lower(table_name) = ?")) {
                    ps.setString(1, table.toLowerCase(Locale.ROOT));
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next() && rs.getInt(1) == 1, table + " was not created");
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE id = '0001-01-sangam_lease'");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next() && rs.getInt(1) == 1, "the dbms:postgresql changesets ran");
            }
        }
    }

    /**
     * The open M-108 point, probed: rows written one after the other on two connections must come back in that order
     * by their identity value, as {@code PendingAudit} reads {@code AUDIT_PENDING} by {@code pending_id}. A failure here
     * is the evidence that YugabyteDB's per-connection sequence cache breaks that order on this server.
     */
    @Test
    void identityValuesFollowTheInsertOrderAcrossConnections() throws Exception {
        String chain = "m108-order-probe";
        List<String> written = new ArrayList<>();
        try (Connection a = ds.getConnection(); Connection b = ds.getConnection()) {
            a.setAutoCommit(true);
            b.setAutoCommit(true);
            for (int i = 0; i < 6; i++) {
                Connection c = i % 2 == 0 ? a : b;
                String type = "probe-" + i;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO AUDIT_PENDING(tenant_id, chain_id,"
                        + " event_type, payload, recorded_at, attempts) VALUES ('t-m108', ?, ?, '{}', now(), 0)")) {
                    ps.setString(1, chain);
                    ps.setString(2, type);
                    ps.executeUpdate();
                }
                written.add(type);
            }
            List<String> read = new ArrayList<>();
            try (PreparedStatement ps = a.prepareStatement("SELECT event_type FROM AUDIT_PENDING WHERE tenant_id = 't-m108'"
                    + " AND chain_id = ? ORDER BY pending_id")) {
                ps.setString(1, chain);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        read.add(rs.getString(1));
                    }
                }
            }
            assertEquals(written, read, "M-108 open point: identity order across connections differs from insert order");
        }
    }
}
