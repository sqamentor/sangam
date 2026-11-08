// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SangamSecurityException;
import org.sangam.packs.spi.PackDescriptor;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.persistence.SangamSchema;
import org.sangam.security.SangamSecurity;
import org.sangam.security.dialect.SecurityDialect;

/**
 * SL-14 on a real H2 security schema: the onboarding SECURITY port defines the tenant's org levels, then seeds pack
 * seats into office units at the level each seat names. Before the fix the seeding created every office with the
 * hard-coded level type {@code OFFICE}, which no tenant defines, and failed with {@code unknown level type OFFICE}.
 */
class OnboardingOrgLevelsH2Test {

    private static final List<String> DEFAULT = List.of("HEAD_OFFICE", "REGIONAL_OFFICE", "FIELD_OFFICE");

    private SangamSecurity security;
    private Transactions tx;
    private OnboardingBridges.Security port;

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ol" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamSchema.apply(ds);
        try (Connection c = ds.getConnection()) {
            security = new SangamSecurity(SecurityDialect.detect(c), null);
        }
        tx = Transactions.of(ds);
        port = new OnboardingBridges.Security(security, tx, () -> true, () -> true);
    }

    private static PackDescriptor pack(PackDescriptor.SeedSeat... seats) {
        return new PackDescriptor("test.pack", "1.0.0", Map.of("en", "t"), "test", "", List.of(), List.of(),
                List.of(new PackDescriptor.SeedRole("R", Map.of("en", "R"))), List.of(seats), List.of(), List.of(),
                List.of(), List.of(), Map.of());
    }

    private String levelOf(String tenant, String unit) {
        return tx.inTransaction(c -> security.orgUnits().unitAt(c, tenant, unit, Instant.now()).orElseThrow().levelType());
    }

    @Test
    void seatsAreSeededAtTheLevelTheyNameAfterTheLevelsAreDefined() {
        String t = "T-OL1";
        port.defineOrgLevels(t, DEFAULT, "maker");
        int created = port.installPackSeeds(t, List.of(pack(
                new PackDescriptor.SeedSeat("HEAD", "R", "HQ-1", "HEAD_OFFICE", null, Map.of("en", "Head")),
                new PackDescriptor.SeedSeat("CLERK", "R", "FIELD-1", "FIELD_OFFICE", "HEAD", Map.of("en", "Clerk")))),
                "maker");
        assertEquals(2, created);
        assertEquals("HEAD_OFFICE", levelOf(t, "HQ-1"));
        assertEquals("FIELD_OFFICE", levelOf(t, "FIELD-1"));
        assertEquals(DEFAULT, port.orgLevels(t));
        assertTrue(port.orgUnitsExist(t));
    }

    /** Every shipped pack with seats installs into one tenant on the shipped default hierarchy. */
    @Test
    void everyShippedPackSeedsOnTheDefaultHierarchy() {
        String t = "T-OL2";
        List<PackDescriptor> packs = PackRegistry.discover(getClass().getClassLoader()).all().stream()
                .map(p -> p.descriptor()).filter(d -> !d.seats().isEmpty()).toList();
        assertTrue(packs.size() > 10, "packs with seats are on the class path");
        port.defineOrgLevels(t, DEFAULT, "maker");
        int created = port.installPackSeeds(t, packs, "maker");
        assertTrue(created > 0);
    }

    @Test
    void definingTheSameLevelsAgainIsIdempotentButDifferentLevelsAreRefusedOnceUnitsExist() {
        String t = "T-OL3";
        port.defineOrgLevels(t, DEFAULT, "maker");
        port.installPackSeeds(t, List.of(pack(new PackDescriptor.SeedSeat("S", "R", "U", "HEAD_OFFICE", null,
                Map.of("en", "S")))), "maker");
        port.defineOrgLevels(t, DEFAULT, "maker"); // re-run of the SECURITY step
        SangamSecurityException.AdminConflict e = assertThrows(SangamSecurityException.AdminConflict.class,
                () -> port.defineOrgLevels(t, List.of("OTHER"), "maker"));
        assertTrue(e.getMessage().contains("fixed"), e.getMessage());
    }

    /** The failure the fix removes: a seat level the tenant has not defined (here the former hard-coded OFFICE). */
    @Test
    void aSeatLevelOutsideTheTenantLevelsFailsWithUnknownLevel() {
        String t = "T-OL4";
        port.defineOrgLevels(t, DEFAULT, "maker");
        RuntimeException e = assertThrows(RuntimeException.class, () -> port.installPackSeeds(t, List.of(pack(
                new PackDescriptor.SeedSeat("S", "R", "U", "OFFICE", null, Map.of("en", "S")))), "maker"));
        assertTrue(e.getMessage().contains("unknown level type OFFICE"), e.getMessage());
    }
}
