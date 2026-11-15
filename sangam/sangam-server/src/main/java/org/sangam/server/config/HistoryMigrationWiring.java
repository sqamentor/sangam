// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.history.HistoricInstanceMigration;
import org.sangam.server.bridge.HistoryMigrationBridge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Owner decision 258 (FLOWABLE-INTO-OPERATON FC-18): the history migration route over the engine's {@link HistoricInstanceMigration}. The
 * server's engine always carries the SangamProcessEnginePlugin, so the bridge is built at start-up (an engine without
 * it is refused there, as {@link HistoricInstanceMigration} requires).
 */
@Configuration(proxyBeanMethods = false)
public class HistoryMigrationWiring {

    @Bean
    @ConditionalOnMissingBean(HistoryMigrationBridge.class)
    public HistoryMigrationBridge sangamHistoryMigrationBridge(OperatonEngineAdapter sangamEngineAdapter) {
        return new HistoryMigrationBridge(HistoryMigrationBridge.Migrations.of(
                new HistoricInstanceMigration(sangamEngineAdapter)));
    }
}
