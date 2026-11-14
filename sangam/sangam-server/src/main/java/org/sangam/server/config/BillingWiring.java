// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.sangam.billing.BillingConnector;
import org.sangam.billing.BillingService;
import org.sangam.billing.DocumentNumbers;
import org.sangam.billing.PaymentConfirmationPolicy;
import org.sangam.billing.JdbcUnverifiedPaymentStore;
import org.sangam.billing.TariffEngine;
import org.sangam.billing.UnverifiedPayment;
import org.sangam.billing.UnverifiedPaymentAuthority;
import org.sangam.billing.UnverifiedPaymentPolicy;
import org.sangam.billing.UnverifiedPayments;
import org.sangam.billing.numbering.RegistryDocumentNumbers;
import org.sangam.billing.party.RegistryPayeeDirectory;
import org.sangam.config.api.PayeeConnector;
import org.sangam.config.profile.ProfileResolver;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.ledger.LedgerPorts;
import org.sangam.ledger.LedgerService;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.numbering.NumberingService;
import org.sangam.registry.party.PartyService;
import org.sangam.rest.modules.ledger.LedgerTenantDefinitions;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.BillingAccountSource;
import org.sangam.server.bridge.BillingPaymentPort;
import org.sangam.server.bridge.ConfigConnectorBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-billing (README: tariffs, invoices, collection, receipts, refunds, penalties on RECEIVABLE ledgers):
 * <ul>
 *   <li>{@link TariffEngine} with the ledger's DMN {@code DecisionPort}; {@link BillingService} on the ledger, the
 *       registry numbering ({@link RegistryDocumentNumbers}: GAP_FREE {@code INVOICE} / {@code RECEIPT} series, one
 *       {@code BIL_DOCUMENT} row per document, SL-130 / SL-131) and {@link BillingPaymentPort} (payment gateway /
 *       bank / counter connectors through the connector ledger).</li>
 *   <li>{@link BillingConnector} (registry id {@code billing}) for the pack ids {@code billing.accounts},
 *       {@code .invoice}, {@code .generate} (= invoice), {@code .collect}, {@code .arrears}, {@code .penalty},
 *       {@code .waive}; accounts from {@link BillingAccountSource}.</li>
 *   <li>Payees: {@link RegistryPayeeDirectory} over the sangam-registry party registry (bean from the registry wiring),
 *       exposed as the {@code registry.party} connector ({@link PayeeConnector}: {@code payee.resolve},
 *       {@code payee.register}).</li>
 * </ul>
 * Requires the config, ledger and registry modules. Properties: {@code sangam.modules.billing.enabled},
 * {@code sangam.billing.channels.<channel>.connector|verify-operation|refund-operation},
 * {@code sangam.billing.trusted-channels} (default empty: every collection is rail-verified; a non-empty list in
 * production needs {@code sangam.billing.trusted-channels-acknowledged=true}, owner decision 136; a trusted channel is
 * refused by every collection call and its payments go only through {@link UnverifiedPayments}, decision 141, with the
 * per-channel policy {@code sangam.billing.unverified.channels.<channel>.*} and
 * {@code sangam.billing.unverified.reconcile-minutes}, default 60),
 * {@code sangam.billing.payee.identifier-type} (default {@code BANK_ACCOUNT}), {@code sangam.billing.payee.zone}
 * (optional override; default: the tenant's zone).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${sangam.modules.billing.enabled:true} and ${sangam.modules.ledger.enabled:true}"
        + " and ${sangam.modules.config.enabled:true} and ${sangam.modules.registry.enabled:true}")
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

    /**
     * RM-03 (owner decisions 136 and 141): the trusted channels come from the server configuration only, default empty,
     * and a production start with a non-empty list and no acknowledgement is refused. A trusted channel is refused by
     * every collection call; its payments are recorded only by {@link #sangamUnverifiedPayments}.
     */
    @Bean
    public PaymentConfirmationPolicy sangamBillingPaymentPolicy(Environment env) {
        return PaymentConfirmationPolicy.fromConfiguration(env.getProperty(PaymentConfirmationPolicy.TRUSTED_CHANNELS),
                env.getProperty(PaymentConfirmationPolicy.TRUSTED_CHANNELS_ACKNOWLEDGED, Boolean.class, false),
                ProductionEnvironment.isProduction(env));
    }

    /**
     * Decision 141: the operating policy of each trusted channel; a channel whose policy is not fully stated is disabled
     * (logged), never enabled with defaults.
     */
    @Bean
    public UnverifiedPaymentPolicy sangamUnverifiedPaymentPolicy(PaymentConfirmationPolicy sangamBillingPaymentPolicy,
                                                                 Environment env) {
        UnverifiedPaymentPolicy p = UnverifiedPaymentPolicy.from(sangamBillingPaymentPolicy.trustedChannels(),
                env::getProperty);
        p.unresolved().forEach((channel, why) -> LOG.warn("trusted channel {} is disabled for unverified acceptance:"
                + " operating policy unresolved ({})", channel, why));
        return p;
    }

    /**
     * Decision 141: the unverified-payment application service. The scoped authorisation is the host's
     * {@link UnverifiedPaymentAuthority} bean (sangam-security); without one every acceptance and resolution is refused.
     * Records and their audit events commit together ({@link #sangamUnverifiedPaymentStore}, {@code AUDIT_PENDING});
     * the billing service pauses accrual in the same store (decision 195), which the service checks at construction.
     */
    @Bean
    public UnverifiedPayments sangamUnverifiedPayments(BillingService billing, UnverifiedPaymentPolicy policy,
                                                       ObjectProvider<UnverifiedPaymentAuthority> authority,
                                                       JdbcUnverifiedPaymentStore sangamUnverifiedPaymentStore,
                                                       BillingService.PaymentPort payments,
                                                       PaymentConfirmationPolicy sangamBillingPaymentPolicy) {
        UnverifiedPaymentAuthority a = authority.getIfAvailable();
        if (a == null && !sangamBillingPaymentPolicy.trustedChannels().isEmpty()) {
            LOG.warn("no UnverifiedPaymentAuthority bean: trusted channels {} accept nothing unverified",
                    sangamBillingPaymentPolicy.trustedChannels());
        }
        return new UnverifiedPayments(billing, policy, a, sangamUnverifiedPaymentStore, payments,
                ConfigWiring.engineClock());
    }

    /**
     * Decisions 141 and 195: unverified payments, their audit events and the accrual paused while they are pending, one
     * store shared by {@link #sangamBillingService} (the penalty levy) and {@link #sangamUnverifiedPayments}.
     */
    @Bean
    public JdbcUnverifiedPaymentStore sangamUnverifiedPaymentStore(TxSupport tx, PendingAuditDrainer drainer) {
        return new JdbcUnverifiedPaymentStore(tx.newTransactions(), tx.dialect(), drainer);
    }

    /**
     * Decision 141 reconciliation and ageing, on the leader every {@code sangam.billing.unverified.reconcile-minutes}
     * (default 60): pending payments are checked against their rails (an exact confirmation resolves VERIFIED and
     * allocates), interrupted allocations are completed, and every pending payment older than its channel's alert age is
     * logged at WARN (the ageing alert).
     */
    @Bean
    public SmartLifecycle sangamUnverifiedPaymentReconciliation(UnverifiedPayments payments, TxSupport tx,
                                                                RestProperties rest, Environment env) {
        long minutes = Math.max(1, env.getProperty("sangam.billing.unverified.reconcile-minutes", Long.class, 60L));
        LeaderElection leader = new LeaderElection(tx.dialect(), tx.newTransactions(), rest.getNode(),
                Duration.ofMinutes(Math.max(3, minutes * 2)));
        return new SmartLifecycle() {
            private static final String LEADER = "sangam-billing-unverified";
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
                    if (leader.tryAcquire(LEADER) < 0) {
                        return;
                    }
                    for (String tenant : payments.tenantsWithWork(1000)) {
                        for (UnverifiedPayments.Reconciled r : payments.reconcile(tenant, 500)) {
                            if (!"NOT_CONFIRMED".equals(r.outcome()) && !"NO_RAIL".equals(r.outcome())) {
                                LOG.info("unverified payment {} of tenant {}: {}{}", r.id(), tenant, r.outcome(),
                                        r.detail() == null ? "" : " (" + r.detail() + ")");
                            }
                        }
                        for (UnverifiedPayment p : payments.ageing(tenant, 500)) {
                            LOG.warn("ageing alert: unverified payment {} of tenant {} (channel {}, office {}, ref {},"
                                    + " {} {}) pending {} days", p.id(), tenant, p.channel(), p.office(),
                                    p.paymentRef(), p.amount().toPlainString(), p.amount().currencyCode(),
                                    p.ageDays(payments.now()));
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.warn("unverified payment reconciliation failed: {}", e.toString());
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

    /**
     * SL-130 / SL-131 (M-002): invoice and receipt numbers are issued by the registry {@link NumberingService}
     * (GAP_FREE, the allocation recorded with the document as ref_type / ref_id) through {@code BIL_DOCUMENT}, keyed
     * by the document identity, so a retried or concurrent call gets the stored number and receipt plan instead of a
     * second number or a second allocation. {@code newTransactions()}: each issue commits on its own, as the counter,
     * the allocation and the document row must.
     */
    @Bean
    public BillingService sangamBillingService(LedgerService ledger, TariffEngine tariffs, TxSupport tx,
                                               NumberingService numberingService,
                                               BillingService.PaymentPort payments,
                                               PaymentConfirmationPolicy sangamBillingPaymentPolicy,
                                               JdbcUnverifiedPaymentStore sangamUnverifiedPaymentStore,
                                               org.sangam.core.time.BusinessClock businessClock) {
        return new BillingService(ledger, tariffs, documentNumbers(tx, numberingService), payments,
                sangamBillingPaymentPolicy, sangamUnverifiedPaymentStore, businessClock);
    }

    /** The durable document numbers of {@link #sangamBillingService} (package-private for the wiring test). */
    static DocumentNumbers documentNumbers(TxSupport tx, NumberingService numberingService) {
        return new RegistryDocumentNumbers(tx.newTransactions(), tx.dialect(), numberingService);
    }

    @Bean
    public BillingConnector sangamBillingConnector(BillingService billing, LedgerService ledger,
            ProfileResolver profiles, LedgerTenantDefinitions definitions, TxSupport tx) {
        // SL-127: a billing step without a period takes the tenant's current fiscal period (the ledger connector's
        // resolver), refused when no fiscal year is configured; never an invented calendar year
        return new BillingConnector(billing, ledger, profiles, new BillingAccountSource(tx, definitions),
                definitions.periodResolver(), ConfigWiring.engineClock());
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
            String forcedZone = env.getProperty("sangam.billing.payee.zone");   // optional override; default: tenant zone
            ZoneId zone = forcedZone == null || forcedZone.isBlank() ? null : ZoneId.of(forcedZone);
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
