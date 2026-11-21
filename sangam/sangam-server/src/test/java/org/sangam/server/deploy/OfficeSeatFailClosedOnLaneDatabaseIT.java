// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.server.acceptance.AcceptanceDatabase;

/**
 * P-6 finding 4 on the IT lane's PostgreSQL ({@link OfficeSeatFailClosedAgainstServerTest#scenario}): the server as
 * deployed on demo.ifms.dev (profiles {@code postgres,oidc}, production on, a fresh database with its own owner, the
 * schema applied by the bootstrap start), the same two SO officers in two offices and the same cases.
 */
class OfficeSeatFailClosedOnLaneDatabaseIT {

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    /**
     * As {@link PresentationSeedOnLaneDatabaseIT}: a server start in this JVM takes over process-wide installations
     * (tenant quotas, the pack runtime's ports, the sandbox tenants), so they are noted before and put back after.
     */
    @Test
    void aRoleStepWithoutAResolvableSeatIsOfferedToNobodyOnPostgres() throws Exception {
        String lane = AcceptanceDatabase.name();
        Assumptions.assumeTrue(lane.startsWith("postgres"), "the demo fragment is PostgreSQL only; lane " + lane);
        org.sangam.core.quota.TenantQuotas quotas = org.sangam.core.quota.TenantQuotas.current();
        var ports = org.sangam.packs.port.PackRuntime.ports();
        java.util.Set<String> sandbox = org.sangam.packs.sim.DemoSandbox.tenants();
        try {
            onTheLaneDatabase();
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(sandbox);
            org.sangam.packs.port.PackRuntime.install(ports);
            org.sangam.core.quota.TenantQuotas.install(quotas);
        }
    }

    private void onTheLaneDatabase() throws Exception {
        var c = AcceptanceDatabase.container();
        String database = "office_fc_" + Long.toHexString(System.nanoTime());
        String owner = database + "_app";
        byte[] secret = new byte[24];
        new java.security.SecureRandom().nextBytes(secret);
        String password = java.util.HexFormat.of().formatHex(secret);
        try (Connection admin = DriverManager.getConnection(AcceptanceDatabase.jdbcUrl(), AcceptanceDatabase.adminUsername(),
                AcceptanceDatabase.adminPassword()); Statement s = admin.createStatement()) {
            s.execute("CREATE ROLE " + owner + " LOGIN PASSWORD '" + password + "'");
            s.execute("CREATE DATABASE " + database + " OWNER " + owner);
        }
        // production refuses a loopback database URL: the container's port on this machine's own address
        String url = "jdbc:postgresql://" + DemoComposeFragmentStartupTest.nonLoopbackIPv4() + ":" + c.getMappedPort(5432)
                + "/" + database;
        Path host = Files.createDirectories(dir.resolve("pg"));
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(host, url, owner, password,
                "postgres,oidc");
        OfficeSeatFailClosedAgainstServerTest.scenario(
                new PacksOfBootstrapTenantAgainstServerTest.Host(host, keys, "postgres", "oidc"), "PostgreSQL lane");
    }
}
