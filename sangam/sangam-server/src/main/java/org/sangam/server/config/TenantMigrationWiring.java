// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.List;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.ports.SangamPortsPlugin;
import org.sangam.ports.tenancy.ChangeTenantService;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.TenantMigrationPort;
import org.sangam.server.bridge.TenantMigrationBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Owner decision 381(3): binds the decision 145 tenant migration (inventory and audited repair) to the server's engine:
 * its own data source and table prefix for the read-only scan, and {@link ChangeTenantService} with the engine's
 * {@code change-tenant} links for the cut-over. No product-row rewriter is registered yet: product rows of the old
 * instance (ledger, outbox, documents) keep its id, and the change-tenant link joins the two ids.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.ports.enabled", havingValue = "true", matchIfMissing = true)
public class TenantMigrationWiring {

    @Bean
    @ConditionalOnMissingBean(TenantMigrationPort.class)
    public TenantMigrationPort sangamTenantMigration(ProcessEngine engine, SangamPortsPlugin sangamPortsPlugin,
                                                     AuditPort audit) {
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
        if (sangamPortsPlugin.links() == null) {
            throw new IllegalStateException("SangamPortsPlugin was not registered with the engine (no link service)");
        }
        return new TenantMigrationBridge(cfg.getDataSource(), cfg.getDatabaseTablePrefix(),
                new ChangeTenantService(engine, sangamPortsPlugin.links(), List.of()), audit);
    }
}
