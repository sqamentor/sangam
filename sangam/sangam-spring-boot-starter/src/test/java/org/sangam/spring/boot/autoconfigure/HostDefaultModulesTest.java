// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.example.sangamhost.SampleHostApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;

/**
 * M-014 (owner decision 2026-11-12): a host with nothing but the starter, a data source and a development profile
 * starts with the core on (engine, audit and integration, security, REST, ported engine features) and the packs and
 * domain modules off; it enables a module by property. Sangam's defaults are the lowest-precedence property source.
 */
class HostDefaultModulesTest {

    static ConfigurableApplicationContext start(String... extra) {
        String[] base = {
                // no configuration file of the host: only Sangam's defaults and these properties
                "spring.config.location=optional:classpath:/no-host-config/",
                "spring.datasource.url=jdbc:h2:mem:host-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL",
                "spring.datasource.username=sa",
                "server.port=0",
                "sangam.tenant.default=dev",
                // decision 143: every installation states its environment; this one is a development host
                "sangam.security.production=false"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        // the "dev" profile allows the ephemeral development key-encryption key (SecurityKeysWiring)
        return new SpringApplicationBuilder(SampleHostApplication.class).profiles("dev").properties(all).run();
    }

    @Test
    void coreOnPacksAndDomainModulesOff() {
        try (ConfigurableApplicationContext ctx = start()) {
            assertTrue(ctx.containsBean("sangamEngineAdapter"), "engine");
            assertTrue(ctx.containsBean("sangamIntegrationAuditChain"), "audit and integration");
            assertTrue(ctx.containsBean("sangamSecurity"), "security");
            assertTrue(ctx.containsBean("sangamTaskPort"), "REST");
            for (String off : new String[] {"sangamBillingService", "sangamLedgerService", "sangamRegistryNumbering",
                    "sangamComplianceConfig"}) {
                assertFalse(ctx.containsBean(off), off + " is off until the host enables its module");
            }
            var sources = ctx.getEnvironment().getPropertySources();
            PropertySource<?> last = sources.stream().reduce((a, b) -> b).orElseThrow();
            assertEquals(SangamDefaultsEnvironmentPostProcessor.SOURCE_NAME, last.getName(), "lowest precedence");
            assertEquals("false", ctx.getEnvironment().getProperty("sangam.modules.billing.enabled"));
        }
    }

    @Test
    void aHostEnablesAModuleByProperty() {
        try (ConfigurableApplicationContext ctx = start("sangam.modules.config.enabled=true",
                "sangam.modules.ledger.enabled=true", "sangam.modules.registry.enabled=true",
                "sangam.modules.billing.enabled=true")) {
            assertTrue(ctx.containsBean("sangamBillingService"), "the host's setting wins over Sangam's default");
        }
    }
}
