package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.sangam.assembly.engine.EngineAssembly;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.engine.SangamEngineSettings;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * W4-F REQUEST 06:32:00Z, owner decisions 276, 286 and 287 (c): the Spring host runs the engine area's own checks of
 * {@link EngineAssembly} and hands them to the four-argument {@code requireCapabilities}. Written NOT_RUN.
 */
class EngineConfigurationCustomizerChecksTest {

    /** A pool that cannot hand out a connection: the database guard fails on it. */
    private static DataSource unreachable() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("no database in this test");
                    }
                    if ("toString".equals(method.getName())) {
                        return "unreachable";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void noSchemaBeanMeansTheHostRanNoSchemaStep() {
        assertFalse(EngineConfigurationCustomizer.runSchemaFirst(new StaticListableBeanFactory()));
    }

    @Test
    void eachSchemaBeanCountsAsTheHostSchemaStep() {
        for (String name : EngineConfigurationCustomizer.SCHEMA_BEANS) {
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean(name, new Object());
            assertTrue(EngineConfigurationCustomizer.runSchemaFirst(beans), name);
        }
        assertEquals(List.of("sangamLiquibase", "sangamLiquibaseTask", "sangamDevSchema"),
                EngineConfigurationCustomizer.SCHEMA_BEANS);
    }

    @Test
    void noProductSchemaAndNoHostSchemaStepIsRefusedAtStartUp() {
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setApplyProductSchema(false);

        EngineAssembly.EngineChecks engine = EngineConfigurationCustomizer.engineChecks(settings, false, unreachable());

        assertTrue(engine.missing().contains(Capabilities.SCHEMA_BEFORE_ENGINE), engine.missing().toString());
        assertTrue(engine.failures().stream().anyMatch(f -> String.valueOf(f.getMessage())
                .contains("apply-product-schema=false")), engine.failures().toString());
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class,
                () -> EngineAssembly.requireCapabilities(false, List.of(),
                        List.of(Capabilities.CONFIG_SOURCE, Capabilities.SHARED_TRANSACTION), engine));
        assertTrue(refused.missing().contains(Capabilities.SCHEMA_BEFORE_ENGINE), refused.missing().toString());
        assertTrue(Arrays.stream(refused.getSuppressed()).anyMatch(f -> String.valueOf(f.getMessage())
                .contains("apply-product-schema=false")), Arrays.toString(refused.getSuppressed()));
    }

    @Test
    void theHostSchemaStepSuppliesSchemaBeforeEngine() {
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setApplyProductSchema(false);

        EngineAssembly.EngineChecks engine = EngineConfigurationCustomizer.engineChecks(settings, true, unreachable());

        assertTrue(engine.supplied().contains(Capabilities.SCHEMA_BEFORE_ENGINE), engine.supplied().toString());
        assertFalse(engine.missing().contains(Capabilities.SCHEMA_BEFORE_ENGINE), engine.missing().toString());
    }

    @Test
    void theDatabaseGuardRunsOnTheRawEnginePool() {
        SangamEngineSettings settings = new SangamEngineSettings();

        EngineAssembly.EngineChecks engine = EngineConfigurationCustomizer.engineChecks(settings, true, unreachable());

        assertTrue(engine.needed().contains(Capabilities.DATABASE_CHECKS), engine.needed().toString());
        assertTrue(engine.missing().contains(Capabilities.DATABASE_CHECKS), engine.missing().toString());
        assertTrue(engine.failures().stream().anyMatch(f -> f.getCause() instanceof SQLException),
                engine.failures().toString());
        CapabilityMissingException refused = assertThrows(CapabilityMissingException.class,
                () -> EngineAssembly.requireCapabilities(false, List.of(),
                        List.of(Capabilities.CONFIG_SOURCE, Capabilities.SHARED_TRANSACTION), engine));
        assertTrue(refused.missing().contains(Capabilities.DATABASE_CHECKS), refused.missing().toString());
    }

    @Test
    void jobLeasesAreNeededOnlyWithExecutorPartitioning() {
        SangamEngineSettings off = new SangamEngineSettings();
        assertFalse(EngineConfigurationCustomizer.engineChecks(off, true, unreachable()).needed()
                .contains(Capabilities.JOB_LEASES));

        SangamEngineSettings on = new SangamEngineSettings();
        on.setExecutorPartitioning(true);
        EngineAssembly.EngineChecks engine = EngineConfigurationCustomizer.engineChecks(on, true, unreachable());
        assertTrue(engine.needed().contains(Capabilities.JOB_LEASES), engine.needed().toString());
        assertTrue(engine.supplied().contains(Capabilities.JOB_LEASES), engine.supplied().toString());
    }
}
