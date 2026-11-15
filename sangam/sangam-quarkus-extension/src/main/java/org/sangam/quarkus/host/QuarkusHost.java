// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;

import org.sangam.assembly.engine.EngineAssembly;
import org.sangam.assembly.host.AttributionTenant;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.Capability;
import org.sangam.assembly.host.ConnectionBinding;
import org.sangam.assembly.host.HostSeams;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.assembly.host.SangamRuntime;
import org.sangam.assembly.integration.LifecycleAuthorization;
import org.sangam.assembly.integration.OutboxDestinations;
import org.sangam.assembly.integration.RelayWakeup;
import org.sangam.core.layer.Dialect;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.integration.lifecycle.LifecyclePorts;
import org.sangam.rest.support.TxSupport;

/**
 * The optional Quarkus host (M-143, owner decision 186): its seams handed to the plain-Java assembly, nothing decided
 * here. Decision 279: each seam is an optional CDI bean, taken through {@link Instance}; a seam whose bean is absent (an
 * operator's {@code quarkus.arc.exclude-types}) is not handed over, so its capability is missing and the start is
 * refused with {@code CAPABILITY_MISSING}.
 *
 * <ul>
 *   <li>config source: {@link QuarkusConfigSource} over the MicroProfile config;</li>
 *   <li>lifecycle: {@link QuarkusLifecycle} ({@link SangamRuntime#start()} on start-up,
 *       {@link SangamRuntime#stop(Duration)} with {@value #DRAIN_PROPERTY} on shutdown);</li>
 *   <li>scheduling: {@link QuarkusScheduling}, the assembly's standalone scheduler, stopped last by the runtime;</li>
 *   <li>security context: {@link QuarkusSecurityContext}, {@link RequestContextSecurity} on the ArC request context;</li>
 *   <li>shared transaction: {@link JtaTransactions} over the JTA transaction manager and the default Agroal data source,
 *       the same two objects the engine gets ({@link SangamEngineConfiguration}); withdrawn by the data source switches
 *       ({@link #sharesEngineConnection}).</li>
 * </ul>
 *
 * Decision 278: no HTTP binding and no OIDC over HTTP (Spring host only in 0.1.0). Every required capability the host
 * does not supply makes {@link SangamRuntime#start()} (and before it the engine, {@link EngineCapabilityGuard}) refuse
 * with {@code CAPABILITY_MISSING} naming each one: no silent downgrade. Deployment approval: NOT APPROVED (decision 137).
 */
@Singleton
public class QuarkusHost {

    public static final String HOST_NAME = "quarkus";

    /** How long in-flight work may drain on shutdown. */
    public static final String DRAIN_PROPERTY = "sangam.shutdown.drain";

    /** Quarkus' switch for JTA integration of the default data source ({@code enabled}, {@code xa}, {@code disabled}). */
    static final String DATASOURCE_TRANSACTIONS = "quarkus.datasource.jdbc.transactions";

    /** The data source the Operaton Quarkus extension would pick; this host always hands the engine the default one. */
    static final String OPERATON_DATASOURCE = "quarkus.operaton.datasource";

    static final String DEFAULT_DATASOURCE = "<default>";

    private final SangamProperties properties;
    private final JtaTransactions transactions;
    private final HostSeams seams;
    private final SangamRuntime runtime;
    private final SangamEngineSettings engineSettings;
    private final String attributionTenant;
    private final OutboxDestinations outboxDestinations;
    private JtaTxSupport txSupport;

    /** The outbox destinations this host has a publisher for beyond the fixed list: none in 0.1.0. */
    static final OutboxDestinations.Routes OUTBOX_ROUTES = OutboxDestinations.Routes.none();

    @Inject
    public QuarkusHost(Instance<QuarkusConfigSource> config, Instance<QuarkusLifecycle> lifecycle,
                       Instance<QuarkusScheduling> scheduling, Instance<QuarkusSecurityContext> security,
                       DataSource dataSource, TransactionManager transactionManager) {
        this(new Seams(present(config), lifecycle.isResolvable(), scheduling.isResolvable(), present(security)),
                dataSource, transactionManager);
    }

    /** Every seam present (tests). */
    QuarkusHost(SangamProperties properties, DataSource dataSource, TransactionManager transactionManager,
                BoundPrincipal bound) {
        this(Seams.all(properties, bound), dataSource, transactionManager);
    }

    QuarkusHost(Seams supplied, DataSource dataSource, TransactionManager transactionManager) {
        // without the config-source seam the host reads nothing: empty settings, and the start is refused
        QuarkusConfigSource config = supplied.config() != null ? supplied.config()
                : new QuarkusConfigSource(SangamProperties.empty(), List.of(), List.of());
        this.properties = config.properties();
        // decision 317: no broker inbox on this host before phase 5; a switched-on one refuses the start by name
        BrokerInboxRefusal.refuseIfEnabled(properties);
        // SL-86: the one attribution-tenant rule, the same plain-Java resolver the Spring host reads (the reserved
        // shared key refuses the start naming sangam.tenant.default, before the engine is built)
        this.attributionTenant = AttributionTenant.resolve(properties);
        // phase 3, blocker 2: the outbox destination rule of the assembly with this host's routes; it delivers neither
        // the roll-up stream nor the history projection sinks, so settings asking for them refuse the start by name
        this.outboxDestinations = OutboxDestinations.of(HOST_NAME, properties, OUTBOX_ROUTES);
        this.engineSettings = QuarkusEngineSettings.from(properties, config.names(), config.profiles());
        this.transactions = new JtaTransactions(transactionManager, dataSource);
        HostSeams.Builder b = HostSeams.builder(HOST_NAME)
                .connection(ConnectionBinding.of(dataSource, transactions, sharesEngineConnection(properties)));
        if (supplied.config() != null) {
            b.properties(properties, false);
        }
        if (supplied.lifecycle()) {
            b.lifecycleHook();
        }
        if (!supplied.scheduling()) {
            b.withoutScheduler();
        }
        if (supplied.security() != null) {
            b.securityContext(supplied.security().binding());
        }
        this.seams = b.build();
        // phase 1b: the engine area (UTC, database checks, schema, Jackson line, job leases) is checked by the engine
        // step itself (SangamEngineConfiguration, EngineCapabilityGuard); the runtime checks everything else
        this.runtime = seams.runtime(hostChecked(), List.of(), List.of());
    }

    /**
     * The seam beans the container holds ({@code null} or {@code false}: absent).
     *
     * @param config     config source
     * @param lifecycle  lifecycle hook
     * @param scheduling scheduling seam
     * @param security   security context
     */
    record Seams(QuarkusConfigSource config, boolean lifecycle, boolean scheduling, QuarkusSecurityContext security) {

        static Seams all(SangamProperties properties, BoundPrincipal bound) {
            return new Seams(new QuarkusConfigSource(properties, List.of(), List.of()), true, true,
                    new QuarkusSecurityContext(bound));
        }
    }

    private static <T> T present(Instance<T> bean) {
        return bean.isResolvable() ? bean.get() : null;
    }

    /**
     * True when engine and product rows can share one transaction: the default data source is JTA-integrated
     * ({@value #DATASOURCE_TRANSACTIONS} is not {@code disabled}) and the engine is not pointed at another data source
     * ({@value #OPERATON_DATASOURCE} unset or {@code <default>}); otherwise {@code shared-transaction} is missing.
     */
    static boolean sharesEngineConnection(SangamProperties properties) {
        String tx = properties.get(DATASOURCE_TRANSACTIONS, "enabled").trim();
        String engineDs = properties.get(OPERATON_DATASOURCE, DEFAULT_DATASOURCE).trim();
        return !"disabled".equalsIgnoreCase(tx) && DEFAULT_DATASOURCE.equals(engineDs);
    }

    public SangamProperties properties() {
        return properties;
    }

    public JtaTransactions transactions() {
        return transactions;
    }

    /**
     * Phase 3, blocker 3: the {@code TxSupport} of this host ({@link JtaTxSupport}) on the very transaction manager and
     * data source the engine gets, for the idempotency store and the audit port. Created on first use, because the
     * dialect is read from the database and the host itself touches no database before the engine step's checks.
     */
    public synchronized JtaTxSupport txSupport() {
        if (txSupport == null) {
            txSupport = new JtaTxSupport(transactions.transactionManager(), transactions.dataSource(),
                    TxSupport.detectDialect(transactions.dataSource()));
        }
        return txSupport;
    }

    /**
     * The attribution tenant of untenanted engine and integration work (SL-86, {@link AttributionTenant}), resolved
     * once from this host's configuration.
     */
    public String attributionTenant() {
        return attributionTenant;
    }

    /**
     * Phase 3, blocker 4 (owner decision 374): the authorizer the instance lifecycle, erasure and maintenance services
     * are built with. This host binds no security module before extraction phase 2, so this refuses with
     * {@value LifecycleAuthorization#CODE}; it never hands out an allow-everything authorizer.
     */
    public LifecyclePorts.Authorizer lifecycleAuthorizer() {
        return LifecycleAuthorization.require(HOST_NAME, Optional.empty());
    }

    /** Where every outbox event is delivered on this host ({@link OutboxDestinations}, {@link #OUTBOX_ROUTES}). */
    public OutboxDestinations outboxDestinations() {
        return outboxDestinations;
    }

    public HostSeams seams() {
        return seams;
    }

    /**
     * Owner decision 316: how this host receives the relay wake-up, always {@link RelayWakeup.Mode#POLLING}. The
     * PostgreSQL {@code LISTEN} session needs a physical connection opened outside the pool; the Spring host takes the
     * driver settings for it from its HikariCP pool, and the Agroal pool of this host offers no such connection. So
     * this host never tries to {@code LISTEN}: a relay cycle on this node wakes this node's subscribers in-JVM, a cycle
     * on another node is seen at the subscribers' next poll, and on PostgreSQL {@code pg_notify} is still sent (in a
     * JTA transaction of its own on the relay's thread) for nodes that listen. Only latency differs, never results.
     */
    public RelayWakeup relayWakeup(Dialect dialect) {
        return RelayWakeup.polling(dialect, transactions);
    }

    /** Every required capability outside {@link EngineAssembly#ENGINE_AREA}, in catalogue order. */
    static List<Capability> hostChecked() {
        return Capabilities.required().stream().filter(c -> !EngineAssembly.ENGINE_AREA.contains(c)).toList();
    }

    public SangamRuntime runtime() {
        return runtime;
    }

    /** The engine settings bound from the Quarkus configuration ({@link QuarkusEngineSettings}). */
    public SangamEngineSettings engineSettings() {
        return engineSettings;
    }
}
