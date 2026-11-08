// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.sangam.billing.BillingConnector;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.rest.adapter.jdbc.JdbcIdempotencyAdapter;
import org.sangam.rest.port.IdempotencyPort;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.LedgerDefiningConnector;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.server.bridge.PackGapConnectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Binds the remaining pack connector ids and schedules idempotency cleanup (X.2.2.4 step 5).
 * {@code registry.beneficiary} is always live. {@code ledger.post}, {@code billing.revise} and {@code billing.credit}
 * need inputs the shipped BPMN steps do not all pass, so while the simulator fallback is on (h2) they stay on the
 * simulator; site bindings ({@code sangam.packs.bindings.*}) always win.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class PackGapWiring {

    private static final Logger LOG = LoggerFactory.getLogger(PackGapWiring.class);

    @Bean
    public InitializingBean sangamPackGapBindings(ConnectorRegistry registry, PackConnectorBindings bindings,
            DocumentStore store, ObjectProvider<BillingConnector> billing, ObjectProvider<LedgerTenantDefinitions> ledgers,
            PackBindingsProperties properties, Environment env) {
        return () -> {
            if (bindings.binding("registry.beneficiary").isEmpty()) {
                registry.register(new PackGapConnectors.BeneficiaryCheck(store));
                bindings.bind("registry.beneficiary", "registry.beneficiary", null);
            }
            boolean simulator = properties.getSimulatorFallback() != null ? properties.getSimulatorFallback()
                    : Arrays.asList(env.getActiveProfiles()).contains("h2");
            if (simulator) {
                return;
            }
            LedgerTenantDefinitions defs = ledgers.getIfAvailable();
            BillingConnector bill = billing.getIfAvailable();
            if (bill != null && defs != null) {
                registry.register(new PackGapConnectors.BillingAdjustments(new LedgerDefiningConnector(bill, defs)));
                if (bindings.binding("billing.revise").isEmpty()) {
                    bindings.bind("billing.revise", "billing.adjustments", "revise");
                }
                if (bindings.binding("billing.credit").isEmpty()) {
                    bindings.bind("billing.credit", "billing.adjustments", "credit");
                }
            }
            if (defs != null && bindings.binding("ledger.post").isEmpty()) {
                registry.register(new PackGapConnectors.RemittancePosting(new org.sangam.server.bridge.LedgerDefiningConnector(
                        new org.sangam.ledger.LedgerConnector(defs.ledger(), defs.periodResolver(), ConfigWiring.engineClock()), defs)));
                bindings.bind("ledger.post", "ledger.remittance", "post");
            }
        };
    }

    /** Hourly on the leader: compacts expired idempotency responses and deletes expired keys. */
    @Bean
    public SmartLifecycle sangamIdempotencyCleanup(ObjectProvider<IdempotencyPort> port, TxSupport tx, RestProperties rest,
                                                   Environment env) {
        long minutes = env.getProperty("sangam.rest.idempotency-cleanup-minutes", Long.class, 60L);
        LeaderElection leader = new LeaderElection(tx.dialect(), tx.newTransactions(), rest.getNode(),
                java.time.Duration.ofMinutes(Math.max(3, minutes * 2)));
        return new SmartLifecycle() {
            private static final String LEADER = "sangam-idempotency-cleanup";
            private ScheduledExecutorService executor;

            @Override
            public void start() {
                executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, LEADER);
                    t.setDaemon(true);
                    return t;
                });
                executor.scheduleWithFixedDelay(this::tick, 5, minutes, TimeUnit.MINUTES);
            }

            private void tick() {
                try {
                    if (leader.tryAcquire(LEADER) >= 0 && port.getIfAvailable() instanceof JdbcIdempotencyAdapter a) {
                        LOG.info("idempotency cleanup touched {} rows", a.cleanup());
                    }
                } catch (RuntimeException e) {
                    LOG.warn("idempotency cleanup failed: {}", e.toString());
                }
            }

            @Override
            public void stop() {
                if (executor != null) {
                    executor.shutdownNow();
                    executor = null;
                }
            }

            @Override
            public boolean isRunning() {
                return executor != null;
            }
        };
    }
}
