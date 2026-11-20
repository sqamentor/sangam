// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.server.acceptance.AcceptanceDatabase;
import org.sangam.server.config.KeyAttestation;
import org.springframework.context.ConfigurableApplicationContext;

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
 */
class PresentationSeedOnLaneDatabaseIT {

    private static final String ENV = "demo-ifms";

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    @Test
    void theSeedLoadsOnPostgresAsDeployed() throws Exception {
        String lane = AcceptanceDatabase.name();
        Assumptions.assumeTrue(lane.startsWith("postgres"), "the demo fragment is PostgreSQL only; lane " + lane);
        var c = AcceptanceDatabase.container();
        String database = "demo_seed_" + Long.toHexString(System.nanoTime());
        try (Connection admin = DriverManager.getConnection(AcceptanceDatabase.jdbcUrl(), AcceptanceDatabase.adminUsername(),
                AcceptanceDatabase.adminPassword()); Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + database);
        }
        // production refuses a loopback database URL: the container's port on this machine's own address
        String url = "jdbc:postgresql://" + DemoComposeFragmentStartupTest.nonLoopbackIPv4() + ":" + c.getMappedPort(5432)
                + "/" + database;
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir, url,
                AcceptanceDatabase.username(), AcceptanceDatabase.password(), "postgres,oidc");
        Path export = dir.resolve("key-fingerprints.txt");
        // the fragment does not set the log archive; keep this test's segments out of the module directory
        List<String> logs = List.of("--sangam.compliance.log-archive.root=" + dir.resolve("compliance-logs"));
        List<String> packsAtStart = new java.util.ArrayList<>(logs);
        packsAtStart.addAll(DemoLoaderSequenceAgainstServerTest.PACKS_AT_START);

        // start 1: the schema (as the migrate-only one-shot applies it) and the one-time key bootstrap
        try (ConfigurableApplicationContext first = DemoComposeFragmentStartupTest.start(keys, Map.of(
                "SANGAM_LIQUIBASE_MODE", "startup", "SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", export.toString(), "SANGAM_KEYS_ATTESTATION", "",
                "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", ""), logs, "postgres", "oidc")) {
            assertEquals(Boolean.TRUE, first.isActive());
        }

        // the custodian signs this environment's export (as DemoComposeFragmentStartupTest)
        List<KeyAttestation.Key> attested = Files.readAllLines(export, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split(" ", 4))
                .map(t -> new KeyAttestation.Key(t[1], t[3])).distinct().toList();
        assertFalse(attested.isEmpty());
        KeyPair custodian = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path attestation = dir.resolve("key-attestation.json");
        Files.writeString(attestation, KeyAttestation.sign(ENV, "2026-11-20T00:00:00Z", attested, custodian.getPrivate()),
                StandardCharsets.UTF_8);

        // start 2: the serving start of demo.ifms.dev: validate mode, bootstrap off, over the attestation
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, Map.of(
                "SANGAM_LIQUIBASE_MODE", "validate", "SANGAM_KEYS_BOOTSTRAP", "false",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", export.toString(), "SANGAM_KEYS_ATTESTATION", attestation.toString(),
                "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", Base64.getEncoder().encodeToString(custodian.getPublic().getEncoded())),
                logs, "postgres", "oidc")) {
            // the tenant as on demo.ifms.dev (the stored, compacted org unit refusal), then the live steps in order
            DemoLoaderSequenceAgainstServerTest.sequence(ctx, "PostgreSQL lane, validate start, as deployed",
                    List.of("2c seed", "3a funds", "3b smoke"));
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(java.util.Set.of());
        }

        // start 3: the same database and keys, with the packs deployed for GOI.FIN at start (configuration only)
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, Map.of(
                "SANGAM_LIQUIBASE_MODE", "validate", "SANGAM_KEYS_BOOTSTRAP", "false",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", export.toString(), "SANGAM_KEYS_ATTESTATION", attestation.toString(),
                "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", Base64.getEncoder().encodeToString(custodian.getPublic().getEncoded())),
                packsAtStart, "postgres", "oidc")) {
            DemoLoaderSequenceAgainstServerTest.sequence(ctx, "PostgreSQL lane, validate start, packs deployed at start",
                    List.of("3b smoke", "4a cases"));
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(java.util.Set.of());
        }
    }
}
