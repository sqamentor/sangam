// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.assembly.engine.EngineAssembly.EngineChecks;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.engine.SangamEngineSettings;

/**
 * Extraction phase 1b (owner decisions 276, 286 and 287): the engine area's own capability checks as host-neutral
 * code. Written for the test phase (not run); the test JVM runs on UTC with the pinned Jackson line.
 */
class EngineChecksTest {

    @Test
    void withoutExecutorPartitioningTheJobLeasesAreNotNeeded() {
        // decision 286: the default (partitioning off) is unchanged on every host
        SangamEngineSettings settings = new SangamEngineSettings();
        assertFalse(settings.isExecutorPartitioning());
        EngineChecks checks = EngineAssembly.checkBeforeDatabase(settings, false);
        assertFalse(checks.needed().contains(Capabilities.JOB_LEASES));
        assertFalse(checks.supplied().contains(Capabilities.JOB_LEASES));
        assertFalse(EngineAssembly.engineNeeded(false).contains(Capabilities.JOB_LEASES));
        assertEquals(List.of(), checks.missing());
    }

    @Test
    void withExecutorPartitioningTheJobLeasesAreNeededAndTheEngineSuppliesThem() {
        // decision 286: needed only now; the engine's JobExecutorSetup supplies them (and refuses M-056 at postInit)
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setExecutorPartitioning(true);
        EngineChecks checks = EngineAssembly.checkBeforeDatabase(settings, false);
        assertTrue(checks.needed().contains(Capabilities.JOB_LEASES));
        assertTrue(checks.supplied().contains(Capabilities.JOB_LEASES));
        assertTrue(EngineAssembly.engineNeeded(true).contains(Capabilities.JOB_LEASES));
        assertEquals(List.of(), checks.missing());
    }

    @Test
    void theDatabaseFreeChecksPassOnAUtcJvmWithThePinnedJackson() {
        EngineChecks checks = EngineAssembly.checkBeforeDatabase(new SangamEngineSettings(), false);
        assertEquals(Set.of(Capabilities.UTC_JVM, Capabilities.JACKSON_FEEL, Capabilities.SCHEMA_BEFORE_ENGINE),
                checks.needed());
        assertEquals(checks.needed(), checks.supplied());
        assertEquals(List.of(), checks.failures());
    }

    @Test
    void noProductSchemaStepMeansTheSchemaBeforeTheEngineIsMissing() {
        SangamEngineSettings off = new SangamEngineSettings();
        off.setApplyProductSchema(false);
        EngineChecks none = EngineAssembly.checkBeforeDatabase(off, false);
        assertEquals(List.of(Capabilities.SCHEMA_BEFORE_ENGINE), none.missing());
        assertTrue(none.failures().getFirst().getMessage().contains("sangam.engine.apply-product-schema=false"));
        // the host's own schema step (the Spring host's Liquibase beans) supplies it instead
        assertEquals(List.of(), EngineAssembly.checkBeforeDatabase(off, true).missing());
    }

    @Test
    void theDatabaseChecksRunOnTheDataSourceTheEngineIsHanded() {
        SangamEngineSettings settings = new SangamEngineSettings();
        EngineChecks readCommitted = EngineAssembly.checkDatabase(settings,
                dataSource(Connection.TRANSACTION_READ_COMMITTED));
        assertEquals(Set.of(Capabilities.DATABASE_CHECKS), readCommitted.supplied());
        // decision 287 (c): a pool that hands the engine another isolation level is refused with the guard's message
        EngineChecks serializable = EngineAssembly.checkDatabase(settings,
                dataSource(Connection.TRANSACTION_SERIALIZABLE));
        assertEquals(List.of(Capabilities.DATABASE_CHECKS), serializable.missing());
        assertTrue(serializable.failures().getFirst().getMessage().contains("must run READ COMMITTED"),
                serializable.failures().getFirst().getMessage());
    }

    @Test
    void oneRefusalNamesEveryMissingCapabilityAndKeepsEachReason() {
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setApplyProductSchema(false);
        EngineChecks checks = EngineAssembly.checkBeforeDatabase(settings, false)
                .and(EngineAssembly.checkDatabase(settings, dataSource(Connection.TRANSACTION_SERIALIZABLE)));
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class,
                () -> EngineAssembly.requireCapabilities(false, List.of(),
                        List.of(Capabilities.CONFIG_SOURCE, Capabilities.SHARED_TRANSACTION), checks));
        assertEquals(List.of(Capabilities.DATABASE_CHECKS, Capabilities.SCHEMA_BEFORE_ENGINE), refused.missing());
        assertTrue(refused.getMessage().startsWith(CapabilityMissingException.CODE), refused.getMessage());
        assertTrue(Arrays.stream(refused.getSuppressed())
                .anyMatch(t -> String.valueOf(t.getMessage()).contains("must run READ COMMITTED")));
        assertTrue(Arrays.stream(refused.getSuppressed())
                .anyMatch(t -> String.valueOf(t.getMessage()).contains("apply-product-schema=false")));
    }

    @Test
    void everythingPassedLeavesOnlyTheHostsOwnNeeds() {
        SangamEngineSettings settings = new SangamEngineSettings();
        EngineChecks checks = EngineAssembly.checkBeforeDatabase(settings, false)
                .and(EngineAssembly.checkDatabase(settings, dataSource(Connection.TRANSACTION_READ_COMMITTED)));
        assertTrue(EngineAssembly.requireCapabilities(false, List.of(),
                List.of(Capabilities.CONFIG_SOURCE, Capabilities.SHARED_TRANSACTION), checks)
                .missingRequired().isEmpty());
        // the three-argument check is unchanged (the Spring host until it switches): no engine-area need
        assertTrue(EngineAssembly.requireCapabilities(false, List.of(),
                List.of(Capabilities.CONFIG_SOURCE, Capabilities.SHARED_TRANSACTION)).missingRequired().isEmpty());
    }

    @Test
    void checksNotRunCountAsMissing() {
        assertEquals(List.of(Capabilities.UTC_JVM, Capabilities.DATABASE_CHECKS, Capabilities.SCHEMA_BEFORE_ENGINE,
                Capabilities.JACKSON_FEEL), EngineChecks.notRun(false).missing());
        assertTrue(EngineChecks.notRun(true).missing().contains(Capabilities.JOB_LEASES));
        assertEquals(EngineAssembly.ENGINE_AREA, Set.copyOf(EngineChecks.notRun(true).missing()));
    }

    /** An Oracle-like data source whose connections report {@code isolation}. */
    private static DataSource dataSource(int isolation) {
        DatabaseMetaData md = (DatabaseMetaData) Proxy.newProxyInstance(EngineChecksTest.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class}, (p, m, a) -> switch (m.getName()) {
                    case "getDatabaseProductName" -> "Oracle";
                    case "getDatabaseProductVersion" -> "Oracle Database 23ai";
                    case "getDriverName" -> "test driver";
                    default -> null;
                });
        Connection c = (Connection) Proxy.newProxyInstance(EngineChecksTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, m, a) -> switch (m.getName()) {
                    case "getMetaData" -> md;
                    case "getTransactionIsolation" -> isolation;
                    case "isClosed", "getAutoCommit" -> Boolean.FALSE;
                    default -> null;
                });
        return (DataSource) Proxy.newProxyInstance(EngineChecksTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (p, m, a) -> "getConnection".equals(m.getName()) ? c : null);
    }
}
