// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import liquibase.integration.spring.SpringLiquibase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.controlplane.audit.ControlPlaneAudit;
import org.sangam.controlplane.cells.Cell;
import org.sangam.controlplane.cells.CellDirectory;
import org.sangam.controlplane.config.ControlPlaneProperties;
import org.sangam.controlplane.registry.Tenant;
import org.sangam.controlplane.registry.TenantRegistry;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M-016 (decision 51) placement exclusivity on the real control-plane schema (H2, cp-master.xml incl. cp-0001-05/06):
 * a cell dedicated to a tenant hosts only that tenant; a tenant recorded with isolation DATABASE goes only to the cell
 * dedicated to it; SHARED and SCHEMA tenants go to shared cells (whose instances then refuse SCHEMA, decision 97). The
 * dedication comes with the cell registration (stored, echoed, changes refused while a hosted tenant would break it) and
 * the isolation with the tenant create (contract round 2).
 */
class PlacementDedicationH2Test {

    private JdbcTemplate jdbc;
    private TenantRegistry registry;
    private CellDirectory cells;
    private PlacementService placement;

    @BeforeEach
    void schema() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:cp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(ds);
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.setChangeLog("classpath:db/changelog/controlplane/cp-master.xml");
        lb.afterPropertiesSet();
        jdbc = new JdbcTemplate(ds);
        ControlPlaneAudit audit = new ControlPlaneAudit(jdbc);
        ControlPlaneProperties props = new ControlPlaneProperties(List.of(), null, null, null, null, null, null, null, null);
        registry = new TenantRegistry(jdbc, audit);
        cells = new CellDirectory(jdbc, audit, props);
        placement = new PlacementService(registry, cells, props);
        cell("in-a", null);
        cell("in-b", null);
    }

    private void cell(String id, String dedicatedTo) {
        register(id, dedicatedTo);
    }

    private Cell register(String id, String dedicatedTo) {
        return cells.register(id, new CellDirectory.Registration("r1", "IN", "IN", "https://" + id + ".example",
                null, "h2", 500, 2000, "test", dedicatedTo), "test");
    }

    private Tenant tenant(String code, String isolation) {
        return registry.create(new TenantRegistry.NewTenant(code, null, "ORG", code, "IN", "IN", null, 1.0, isolation),
                "test");
    }

    @Test
    void theRuleItself() {
        assertNull(PlacementService.dedication(null, "T1", TenantRegistry.SHARED));
        assertNull(PlacementService.dedication(" ", "T1", TenantRegistry.SCHEMA), "SCHEMA is placed on shared cells");
        assertNull(PlacementService.dedication("acme", "ACME", TenantRegistry.DATABASE), "codes compare upper-case");
        assertNull(PlacementService.dedication("ACME", "ACME", TenantRegistry.SHARED), "its own cell is always allowed");
        assertEquals("cell dedicated to tenant ACME", PlacementService.dedication("ACME", "OTHER", TenantRegistry.SHARED));
        assertEquals("cell dedicated to tenant ACME", PlacementService.dedication("ACME", "OTHER", TenantRegistry.DATABASE));
        assertTrue(PlacementService.dedication(null, "ACME", TenantRegistry.DATABASE).contains("isolation DATABASE"));
    }

    @Test
    void existingTenantsDefaultToSharedAndArePlacedAsBefore() {
        registry.create(new TenantRegistry.NewTenant("T1", null, "ORG", "T1", "IN", "IN", null, 1.0, null), "test");
        assertEquals(TenantRegistry.SHARED, registry.get("T1").isolation(), "absent = SHARED");
        assertEquals(TenantRegistry.SHARED, registry.isolation("T1"), "the column default");
        assertEquals("in-a", placement.place("T1", "test").cellId(), "most headroom, then cell id");
        assertTrue(cells.dedicatedTenant("in-a").isEmpty());
    }

    @Test
    void aDedicatedCellHostsOnlyItsTenant() {
        cell("in-d", "ACME");
        assertEquals(java.util.Optional.of("ACME"), cells.dedicatedTenant("in-d"));
        Tenant other = tenant("OTHER", TenantRegistry.SHARED);
        assertEquals("cell dedicated to tenant ACME", placement.reject(cells.get("in-d"), other));
        assertTrue(placement.candidates(other, null).stream().noneMatch(c -> "in-d".equals(c.cell().cellId())));
        assertTrue(List.of("in-a", "in-b").contains(placement.place("OTHER", "test").cellId()));
    }

    @Test
    void aDatabaseTenantGoesOnlyToItsDedicatedCell() {
        cell("in-d", "ACME");
        Tenant acme = tenant("ACME", TenantRegistry.DATABASE);
        assertTrue(placement.reject(cells.get("in-a"), acme).contains("isolation DATABASE"));
        assertEquals(List.of("in-d"), placement.candidates(acme, null).stream().map(c -> c.cell().cellId()).toList());
        assertEquals("in-d", placement.place("ACME", "test").cellId());
    }

    @Test
    void withoutItsDedicatedCellADatabaseTenantIsRefusedNotPlacedOnASharedCell() {
        tenant("ACME", TenantRegistry.DATABASE);
        PlacementService.NoCapacity e = assertThrows(PlacementService.NoCapacity.class,
                () -> placement.place("ACME", "test"));
        assertTrue(e.getMessage().contains("no cell dedicated to ACME"), e.getMessage());
        assertNull(registry.get("ACME").cellId(), "nothing assigned");
    }

    @Test
    void aMoveObeysTheSameRule() {
        cell("in-d", "ACME");
        cell("in-e", "ACME");
        Tenant acme = tenant("ACME", TenantRegistry.DATABASE);
        placement.place("ACME", "test");
        Tenant placed = registry.get("ACME");
        String target = placement.moveTarget(placed).map(c -> c.cell().cellId()).orElseThrow();
        assertTrue(List.of("in-d", "in-e").contains(target) && !target.equals(placed.cellId()),
                "a DATABASE tenant moves only to another cell dedicated to it: " + target);
        Cell shared = cells.get("in-a");
        assertTrue(placement.reject(shared, acme).contains("isolation DATABASE"));
    }

    @Test
    void theRegistrationStoresAndEchoesTheDedication() {
        Cell d = register("in-d", " acme ");
        assertEquals("ACME", d.dedicatedTenant(), "stored upper-case like tenant codes, echoed in the answer");
        assertEquals("ACME", cells.get("in-d").dedicatedTenant());
        assertEquals(java.util.Optional.of("ACME"), cells.dedicatedTenant("in-d"));
        assertNull(cells.get("in-a").dedicatedTenant(), "a shared cell");
        assertEquals("ACME", register("in-d", "ACME").dedicatedTenant(), "re-registration keeps it");
        assertThrows(IllegalArgumentException.class, () -> register("in-x", "*"));
    }

    @Test
    void aDedicationChangeIsRefusedWhileAHostedTenantWouldBreakIt() {
        tenant("OTHER", TenantRegistry.SHARED);
        String host = placement.place("OTHER", "test").cellId();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> register(host, "ACME"));
        assertTrue(e.getMessage().contains("OTHER"), e.getMessage());
        assertNull(cells.get(host).dedicatedTenant(), "unchanged");

        register("in-d", "ACME");
        tenant("ACME", TenantRegistry.DATABASE);
        assertEquals("in-d", placement.place("ACME", "test").cellId());
        IllegalStateException toShared = assertThrows(IllegalStateException.class, () -> register("in-d", null));
        assertTrue(toShared.getMessage().contains("ACME"), "a DATABASE tenant may not end up on a shared cell");
        assertEquals("ACME", cells.get("in-d").dedicatedTenant());
    }

    @Test
    void theIsolationIsSetAtCreateAndValidated() {
        assertEquals(TenantRegistry.DATABASE, tenant("T-DB", "database").isolation(), "stored upper-case");
        tenant("T-SC", "SCHEMA");
        assertEquals(TenantRegistry.SCHEMA, registry.get("T-SC").isolation(), "read back");
        assertEquals(TenantRegistry.DATABASE, registry.isolation("T-DB"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> tenant("T-BAD", "DEDICATED_DB"));
        assertTrue(e.getMessage().contains("SHARED, SCHEMA or DATABASE"), e.getMessage());
        assertTrue(registry.find("T-BAD").isEmpty(), "nothing created");
    }
}