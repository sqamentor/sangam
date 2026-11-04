// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.billing.BillingConnector;
import org.sangam.billing.BillingService;
import org.sangam.billing.TariffEngine;
import org.sangam.billing.party.RegistryPayeeDirectory;
import org.sangam.config.api.PayeeConnector;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.ledger.LedgerPorts;
import org.sangam.ledger.LedgerService;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.party.PartyService;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.BillingAccountSource;
import org.sangam.server.bridge.BillingPaymentPort;
import org.sangam.server.bridge.ConfigConnectorBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-billing (README: tariffs, invoices, collection, receipts, refunds, penalties on RECEIVABLE ledgers):
 * <ul>
 *   <li>{@link TariffEngine} with the ledger's DMN {@code DecisionPort}; {@link BillingService} on the ledger, the
 *       config number series ({@code CFG_SERIES}) and {@link BillingPaymentPort} (payment gateway / bank / counter
 *       connectors through the connector ledger).</li>
 *   <li>{@link BillingConnector} (registry id {@code billing}) for the pack ids {@code billing.accounts},
 *       {@code .invoice}, {@code .generate} (= invoice), {@code .collect}, {@code .arrears}, {@code .penalty},
 *       {@code .waive}; accounts from {@link BillingAccountSource}.</li>
 *   <li>Payees: {@link RegistryPayeeDirectory} over the sangam-registry party registry (bean from the registry wiring),
 *       exposed as the {@code registry.party} connector ({@link PayeeConnector}: {@code payee.resolve},
 *       {@code payee.register}).</li>
 * </ul>
 * Requires the config and ledger modules. Properties: {@code sangam.modules.billing.enabled},
 * {@code sangam.billing.channels.<channel>.connector|verify-operation|refund-operation},
 * {@code sangam.billing.trusted-channels} (default {@code counter,cash}),
 * {@code sangam.billing.payee.identifier-type} (default {@code BANK_ACCOUNT}), {@code sangam.billing.payee.zone}
 * (default {@code Asia/Kolkata}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${sangam.modules.billing.enabled:true} and ${sangam.modules.ledger.enabled:true}"
        + " and ${sangam.modules.config.enabled:true}")
public class BillingWiring {

    private static final Logger LOG = LoggerFactory.getLogger(BillingWiring.class);

    @Bean
    public TariffEngine sangamTariffEngine(LedgerPorts.DecisionPort decisions) {
        return new TariffEngine(decisions);
    }

    @Bean
    public BillingService.PaymentPort sangamBillingPayments(ObjectProvider<ConnectorInvoker> invoker, Environment env) {
        return new BillingPaymentPort(invoker.getIfAvailable(), env);
    }

    @Bean
    public BillingService sangamBillingService(LedgerService ledger, TariffEngine tariffs, JdbcConfigStore series,
                                               BillingService.PaymentPort payments) {
        return new BillingService(ledger, tariffs, series, payments);
    }

    @Bean
    public BillingConnector sangamBillingConnector(BillingService billing, LedgerService ledger,
            ProfileResolver profiles, LedgerTenantDefinitions definitions, TxSupport tx) {
        return new BillingConnector(billing, ledger, profiles, new BillingAccountSource(tx, definitions),
                ConfigWiring.engineClock());
    }

    @Bean
    public InitializingBean sangamBillingConnectorRegistration(BillingConnector billing,
            LedgerTenantDefinitions definitions, ObjectProvider<PartyService> parties, TxSupport tx,
            ObjectProvider<ConnectorRegistry> registry, ObjectProvider<PackConnectorBindings> bindings, Environment env) {
        return () -> {
            ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
            // Billing runs on tenant ledgers: register their definitions before the first billing call.
            if (binder.register(new org.sangam.server.bridge.LedgerDefiningConnector(billing, definitions))) {
                Map<String, String> ids = new LinkedHashMap<>();
                for (String op : new String[]{"accounts", "invoice", "collect", "arrears", "penalty", "waive"}) {
                    ids.put("billing." + op, op);
                }
                ids.put("billing.generate", "invoice");
                LOG.info("billing connector registered; {} pack connector ids bound", binder.bind("billing", ids));
            }
            PartyService party = parties.getIfAvailable();
            if (party == null) {
                LOG.warn("No sangam-registry PartyService: the registry.party payee connector is not registered");
                return;
            }
            ZoneId zone = ZoneId.of(env.getProperty("sangam.billing.payee.zone", "Asia/Kolkata"));
            RegistryPayeeDirectory payees = new RegistryPayeeDirectory(tx.dataSource(), party,
                    env.getProperty("sangam.billing.payee.identifier-type", "BANK_ACCOUNT"),
                    tenant -> new RegistryContext(tenant, "system:payment", null, "payment", null, zone));
            if (binder.register(new PayeeConnector(payees))) {
                Map<String, String> ids = new LinkedHashMap<>();
                ids.put("registry.party", null);
                LOG.info("payee connector registry.party registered; {} pack id bound", binder.bind("registry.party", ids));
            }
        };
    }
}
