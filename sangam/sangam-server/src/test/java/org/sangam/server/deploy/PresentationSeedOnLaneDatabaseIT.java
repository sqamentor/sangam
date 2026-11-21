// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

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
 * EV-M-0932: the presentation seed passed on H2 ({@link PresentationSeedAgainstServerTest}) while on demo.ifms.dev
 * (PostgreSQL, demo-0c3ef79140fa) {@code PUT /admin/org-levels} answered 200 with levels OFFICE and the next
 * {@code POST /admin/org-units} answered 409 UNKNOWN_LEVEL, on every rerun: the stored answer to the loader's fixed
 * Idempotency-Key, first given on demo-021498ea60d1 before any level type existed. This runs the live steps (2c seed,
 * 3a funds, 3b smoke, 4a cases: {@link DemoLoaderSequenceAgainstServerTest}) against the server
 * exactly as deployed there: the IT lane's PostgreSQL (a fresh database), the demo fragment's env names and profiles
 * ({@code postgres,oidc}, production on), GOI.FIN created only by FirstAdminBootstrap (profile null), the schema applied
 * by a first start and the serving start in {@code SANGAM_LIQUIBASE_MODE=validate} over the custodian's attestation.
 * No fixture beyond what the fragment and the API create.
 * <p>
 * Register E-25: the image as deployed (the fragment without the two pack settings) has no pack for GOI.FIN; a restart
 * from the fragment deploys them through {@code SANGAM_PACKS_DEPLOY_ON_START} and {@code SANGAM_PACKS_TENANTS}, and a
 * further start changes nothing ({@link PacksOfBootstrapTenantAgainstServerTest#liveLike}). Only here is the fragment's
 * own profile at work: under {@code postgres} nothing deploys packs at start unless the settings say so.
 */
class PresentationSeedOnLaneDatabaseIT {

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    /**
     * The servers of this test run in the JVM of the lane's shared acceptance server. A server start takes over
     * process-wide installations (the tenant quotas, the pack runtime's ports, the sandbox tenants) and its close
     * does not hand them back to an earlier server (QuotaWiring resets the quotas to none). So they are noted before
     * and put back after: in CI run 44809374237 this class ran before TenantQuotaIT, whose two engine-entry checks
     * then found no quota installed (expected 413, was 201).
     */
    @Test
    void theSeedLoadsOnPostgresAsDeployed() throws Exception {
        String lane = AcceptanceDatabase.name();
        Assumptions.assumeTrue(lane.startsWith("postgres"), "the demo fragment is PostgreSQL only; lane " + lane);
        org.sangam.core.quota.TenantQuotas quotas = org.sangam.core.quota.TenantQuotas.current();
        var ports = org.sangam.packs.port.PackRuntime.ports();
        java.util.Set<String> sandbox = org.sangam.packs.sim.DemoSandbox.tenants();
        try {
            theLiveStepsOnTheLaneDatabase();
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(sandbox);
            org.sangam.packs.port.PackRuntime.install(ports);
            org.sangam.core.quota.TenantQuotas.install(quotas);
        }
    }

    private void theLiveStepsOnTheLaneDatabase() throws Exception {
        var c = AcceptanceDatabase.container();
        String database = "demo_seed_" + Long.toHexString(System.nanoTime());
        // production refuses the database password shipped in the profile (SL-103), which is the lane's own: this
        // database gets its own owner with a password made here, as the deployer injects one on demo.ifms.dev
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
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir, url, owner, password,
                "postgres,oidc");
        // The demo host's history and then the way that needs no new image (register E-25), as on the H2 stand-in:
        // start 1 applies the schema as the migrate-only one-shot does (the one-shot itself ends with System.exit, so
        // this start does what it does, LiquibaseMode.applySystemProperties: the product changelog is applied and
        // Operaton creates its schema in this run only, schema-update=true) and is the one-time key bootstrap; every
        // later start is the serving start of demo.ifms.dev (validate mode, bootstrap off, over the custodian's
        // attestation). Starts 1 and 2 are the image as deployed now (steps 2c, 3a, 3b); start 3 is a restart from the
        // fragment, whose two pack settings deploy the packs of GOI.FIN (steps 3b, 4a, the per-case stop list);
        // start 4 changes nothing.
        PacksOfBootstrapTenantAgainstServerTest.liveLike(
                new PacksOfBootstrapTenantAgainstServerTest.Host(dir, keys, "postgres", "oidc"), "PostgreSQL lane, validate start");
    }
}
