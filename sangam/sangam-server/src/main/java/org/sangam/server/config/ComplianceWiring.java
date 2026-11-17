// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.core.api.ReadWork;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.compliance.PrincipalKey;
import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.adapters.IntegrationErasureAdapter;
import org.sangam.compliance.adapters.IntegrationLegalHoldAdapter;
import org.sangam.compliance.bpmn.ComplianceRuntime;
import org.sangam.compliance.jurisdiction.JurisdictionRegistry;
import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.lifecycle.ErasureService;
import org.sangam.integration.lifecycle.LegalHoldStore;
import org.sangam.integration.relay.LeaderElection;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.ComplianceAuthorityGateway;
import org.sangam.server.bridge.ComplianceConfigProvider;
import org.sangam.server.bridge.ComplianceConnectors;
import org.sangam.server.bridge.ComplianceLogArchiveStore;
import org.sangam.server.bridge.ComplianceNotifier;
import org.sangam.server.bridge.CompliancePersonalData;
import org.sangam.server.bridge.CompliancePurposePlugin;
import org.sangam.server.bridge.ComplianceScheduler;
import org.sangam.server.bridge.ComplianceWorkflowStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Wires sangam-compliance (its README "Integration notes" §2–§6):
 * <ul>
 *   <li>{@link SangamCompliance} on the engine transaction ({@code Transactions.of(engineAdapter)}; Spring-joined
 *       transactions when sangam-engine is disabled) with {@link ComplianceConfigProvider} (jurisdiction packs from
 *       {@code sangam.compliance.jurisdictions}, no default), then {@code ComplianceRuntime.install(...)};</li>
 *   <li>M-009: denial audits (purpose binding, child safeguards) commit on their own through the leaf pool
 *       {@code sangam-compliance-audit} ({@link ComplianceAuditPool}), so they survive the refused operation's
 *       rollback without taking a second connection from the pool the caller holds (SL-43);</li>
 *   <li>ports: legal holds → integration {@code LegalHoldStore}; erasure → integration {@code ErasureService}; personal data → the process-instance store ({@link CompliancePersonalData}, decision 361);
 *       principal messages → outbox + DLT SMS / e-mail connectors ({@link ComplianceNotifier}); supervisory and incident
 *       authorities → e-mail / HTTP connectors; workflow starts → the engine; log archive → a home-country filesystem
 *       store. The other ports stay at the module's "not configured" defaults (reported as gaps by the evidence report);</li>
 *   <li>{@link ComplianceScheduler}: leader-elected schedule ({@code sangam.compliance.schedule.enabled});</li>
 *   <li>{@link CompliancePurposePlugin}: purpose binding, national-id guard and retention tracking at process start/end;</li>
 *   <li>owner decision 369 (e): the {@link PrincipalKey} of the data-principal tokens and the one-time-code identifier
 *       hashes, from {@code sangam.compliance.principal-key-ref} (a {@code secret://} reference resolved through the
 *       {@link SecretResolver}; base64, at least 32 bytes). Unset: the server starts, and every operation that writes
 *       such a reference (consent, guardian verification, nomination, personal-data read audit, one-time code)
 *       refuses with {@code PRINCIPAL_KEY_NOT_CONFIGURED}; a literal key, an unknown secret or a short key stop the
 *       start by name.</li>
 * </ul>
 * The {@code ERASE_DATA} grant for {@code system:retention} is in {@code IntegrationWiring#sangamLifecycleAuthorizer}.
 * Disable with {@code sangam.modules.compliance.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.compliance.enabled", havingValue = "true", matchIfMissing = true)
public class ComplianceWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ComplianceWiring.class);

    @Bean
    public ComplianceConfigProvider sangamComplianceConfig(Environment env,
            ObjectProvider<org.sangam.server.plugins.PluginManager> plugins) {
        JurisdictionRegistry registry = JurisdictionRegistry.discover();
        plugins.ifAvailable(m -> m.jurisdictions().forEach(registry::add));
        LOG.info("Compliance jurisdiction packs installed: {}", registry.all().size());
        return new ComplianceConfigProvider(registry, env);
    }

    /** One source of go-live evidence: effective jurisdictions and pack items (plugin packs included). */
    @Bean
    public org.springframework.beans.factory.InitializingBean sangamComplianceEvidence(ComplianceConfigProvider provider,
            ObjectProvider<org.sangam.security.SangamSecurity> security) {
        return () -> security.ifAvailable(s -> s.tenants().useJurisdictions(
                code -> provider.registry().find(code).map(org.sangam.compliance.jurisdiction.JurisdictionPack::goLiveEvidence),
                tenant -> {
                    String code = provider.effectiveJurisdiction(tenant);
                    return code == null ? java.util.List.<String>of() : java.util.List.of(code);
                }));
    }

    /**
     * M-009 / SL-43: the leaf pool for denial audits. A denial is recorded while the caller holds a connection of the
     * main pool (engine command, REST transaction) and must commit on its own, because the refusal rolls the caller
     * back. A second connection from the main pool there is the SL-43 pool-locking deadlock once every slot is held by a
     * caller waiting for its second one. A connection of this pool is never held while another connection is
     * requested (the only work on it is one {@code AuditChain.append} on a denial-only chain), so waits for it cannot
     * form a cycle. Same database and settings as the main pool; {@code sangam.compliance.denial-audit.pool.maximum-size}
     * (default 2) connections.
     */
    static final class ComplianceAuditPool implements DisposableBean {
        final HikariDataSource pool;

        ComplianceAuditPool(HikariDataSource pool) {
            this.pool = pool;
        }

        @Override
        public void destroy() {
            pool.close();
        }
    }

    public static final String AUDIT_POOL_NAME = "sangam-compliance-audit";

    @Bean
    public ComplianceAuditPool sangamComplianceAuditPool(DataSource ds, Environment env) {
        return new ComplianceAuditPool(leafPool(ds,
                env.getProperty("sangam.compliance.denial-audit.pool.maximum-size", Integer.class, 2)));
    }

    /** A pool on the main pool's database and settings, separate from it (never the pool the callers hold). */
    static HikariDataSource leafPool(DataSource ds, int maximumSize) {
        if (!isHikari(ds)) {
            throw new IllegalStateException("M-009 compliance denial audits need a HikariCP main pool to derive their"
                    + " own pool from (found " + ds.getClass().getName() + "); or set"
                    + " sangam.modules.compliance.enabled=false");
        }
        return leafPool(ds, maximumSize, AUDIT_POOL_NAME);
    }

    /** Whether {@code ds} is (or wraps) a HikariCP pool that a leaf pool can be derived from. */
    static boolean isHikari(DataSource ds) {
        try {
            return ds.isWrapperFor(HikariDataSource.class);
        } catch (java.sql.SQLException e) {
            return false;
        }
    }

    /**
     * A leaf pool named {@code poolName}: the main HikariCP pool's database and settings, its own connections
     * ({@code maximumSize}, at least 1), auto-commit. Shared by the compliance (SL-137) and security (SL-132) denial
     * audits.
     */
    static HikariDataSource leafPool(DataSource ds, int maximumSize, String poolName) {
        HikariConfig cfg = new HikariConfig();
        try {
            ds.unwrap(HikariDataSource.class).copyStateTo(cfg);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot derive the " + poolName + " pool from the main DataSource", e);
        }
        cfg.setPoolName(poolName);
        int size = Math.max(1, maximumSize);
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(Math.min(1, size));
        cfg.setAutoCommit(true);
        return new HikariDataSource(cfg);
    }

    static final String PRINCIPAL_KEY_REF = "sangam.compliance.principal-key-ref";

    /** Owner decision 369 (e): the compliance principal key (class comment). */
    @Bean
    public PrincipalKey sangamCompliancePrincipalKey(Environment env, ObjectProvider<SecretResolver> secrets) {
        return principalKey(env, secrets.getIfAvailable(SecretResolver::fromEnvironment));
    }

    /**
     * The key behind {@value #PRINCIPAL_KEY_REF}; refuses a literal (keys are never configuration values). Unset, the
     * start is refused in production (owner decision 371 (5); {@link ProductionEnvironment#isProduction}, which
     * also counts a missing or contradictory environment setting as production); elsewhere every keyed operation is
     * refused by name with {@code PRINCIPAL_KEY_NOT_CONFIGURED} (decision 369 (e)).
     */
    static PrincipalKey principalKey(Environment env, SecretResolver secrets) {
        String ref = env.getProperty(PRINCIPAL_KEY_REF);
        if (ref == null || ref.isBlank()) {
            if (ProductionEnvironment.isProduction(env)) {
                throw new IllegalStateException(PRINCIPAL_KEY_REF + " (env SANGAM_COMPLIANCE_PRINCIPAL_KEY_REF) is"
                        + " required in production: a secret:// reference to the compliance principal key (base64 of at"
                        + " least 32 bytes) that keys the data-principal references of the compliance audit and the"
                        + " one-time-code hashes; see deploy/README.md section 1 (owner decision 371)");
            }
            LOG.warn("DEVELOPMENT: {} is not set: consents, guardian verifications, nominations, personal-data read audits and"
                    + " one-time codes are refused with PRINCIPAL_KEY_NOT_CONFIGURED (owner decision 369)", PRINCIPAL_KEY_REF);
            return PrincipalKey.unconfigured();
        }
        String r = ref.strip();
        if (!r.startsWith("secret://")) {
            throw new IllegalStateException(PRINCIPAL_KEY_REF + " must be a secret reference (secret://...), never the key");
        }
        String value = secrets.require(r);
        try {
            return PrincipalKey.fromBase64(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PRINCIPAL_KEY_REF + ": " + e.getMessage());
        }
    }

    @Bean
    public SangamCompliance sangamCompliance(TxSupport tx, ObjectProvider<EngineAdapter> engineAdapter,
            ComplianceConfigProvider config, ProcessEngine engine, ObjectProvider<ErasureService> erasure,
            ObjectProvider<OutboxWriter> outbox, ObjectProvider<ConnectorInvoker> invoker,
            ObjectProvider<PublisherRegistry> publishers, Environment env, ComplianceAuditPool auditPool,
            ObjectProvider<CompliancePorts.AdultIdentityVerifier> adultVerifier,
            ObjectProvider<CompliancePorts.GuardianshipVerifier> guardianshipVerifier,
            ObjectProvider<CompliancePorts.NationalIdVault> nationalIdVault, PrincipalKey principalKey) {
        Transactions ctx = transactions(tx, engineAdapter);

        CompliancePorts.LegalHoldPort holds = new IntegrationLegalHoldAdapter(new LegalHoldStore(tx.dialect()));
        ErasureService erasureService = erasure.getIfAvailable();
        CompliancePorts.ErasurePort erasurePort = erasureService == null ? null : new IntegrationErasureAdapter(erasureService);

        ConnectorInvoker inv = invoker.getIfAvailable();
        ComplianceConnectors connectors = inv == null ? null : new ComplianceConnectors(inv,
                env.getProperty("sangam.compliance.connector-timeout", Duration.class, Duration.ofMinutes(2)));
        ComplianceNotifier notifier = null;
        OutboxWriter writer = outbox.getIfAvailable();
        PublisherRegistry registry = publishers.getIfAvailable();
        if (connectors != null && writer != null && registry != null) {
            notifier = new ComplianceNotifier(writer, connectors,
                    env.getProperty("sangam.compliance.notify.sms-connector", "sms-dlt-sim"),
                    env.getProperty("sangam.compliance.notify.email-connector", "smtp"));
            registry.register(ComplianceNotifier.DESTINATION, notifier.publisher());
        }
        ComplianceAuthorityGateway authorities = connectors == null ? null : new ComplianceAuthorityGateway(connectors, config,
                env.getProperty("sangam.compliance.authority.email-connector", "smtp"),
                env.getProperty("sangam.compliance.authority.http-connector", "rest"));
        ComplianceLogArchiveStore archive = new ComplianceLogArchiveStore(
                Path.of(env.getProperty("sangam.compliance.log-archive.root", "data/compliance-logs")),
                env.getProperty("sangam.compliance.log-archive.country", ""));

        SangamCompliance.Ports ports = new SangamCompliance.Ports(holds, erasurePort, notifier,
                null /* principals: owned by packs / sangam-security person directory */,
                null /* authenticator: OTP / SSO against the published identifier */,
                new CompliancePersonalData(engine) /* owner decision 361: process-instance store + retention register */,
                // M-087 / M-072: any bean of the verifier ports (the DigiLocker requester publishes the adult verifier)
                adultVerifier.getIfUnique(), guardianshipVerifier.getIfUnique(), null /* processors */,
                new ComplianceWorkflowStarter(engine), authorities, authorities, archive,
                null /* geo */, nationalIdVault.getIfUnique() /* M-078 */, null /* securityEvidence */,
                null /* sntp: built-in client */);
        SangamCompliance compliance = new SangamCompliance(tx.dialect(), ctx, config, ports,
                Transactions.of(auditPool.pool), principalKey);
        ComplianceRuntime.install(compliance);
        LOG.info("sangam-compliance installed (erasure: {}, notifier: {}, authorities: {}, adult verifier: {},"
                + " guardianship verifier: {}, national-id vault: {})", erasurePort != null, notifier != null,
                authorities != null, ports.adultVerifier() != null, ports.guardianshipVerifier() != null,
                ports.nationalIdVault() != null);
        return compliance;
    }

    /**
     * M-078: the national-identifier data vault ({@code CMP_NATIONAL_ID_VAULT}), on when
     * {@code sangam.compliance.national-id-vault.hmac-pepper} (base64, at least 16 bytes; a secret) is set. Numbers are
     * encrypted with the tenant data keys of the {@code FieldEncryptor} bean, which must then exist.
     */
    @Bean
    @ConditionalOnProperty(name = "sangam.compliance.national-id-vault.hmac-pepper")
    public org.sangam.compliance.identity.EncryptedNationalIdVault sangamNationalIdVault(TxSupport tx,
            ObjectProvider<EngineAdapter> engineAdapter, ObjectProvider<org.sangam.core.security.FieldEncryptor> encryptor,
            ComplianceConfigProvider config, Environment env, PrincipalKey principalKey) {
        org.sangam.core.security.FieldEncryptor enc = encryptor.getIfAvailable();
        if (enc == null) {
            throw new IllegalStateException("sangam.compliance.national-id-vault.hmac-pepper is set but no FieldEncryptor"
                    + " bean exists to encrypt the numbers (sangam-security tenant data keys)");
        }
        byte[] pepper = java.util.Base64.getDecoder().decode(
                env.getRequiredProperty("sangam.compliance.national-id-vault.hmac-pepper").trim());
        return new org.sangam.compliance.identity.EncryptedNationalIdVault(tx.dialect(), transactions(tx, engineAdapter),
                enc, pepper, new org.sangam.compliance.ComplianceAudit(new org.sangam.core.layer.AuditChain(tx.dialect()), null,
                        principalKey),
                config);
    }

    /** Engine transactions when sangam-engine is on, else transactions joined to the current Spring one. */
    static Transactions transactions(TxSupport tx, ObjectProvider<EngineAdapter> engineAdapter) {
        EngineAdapter adapter = engineAdapter.getIfAvailable();
        return adapter != null ? Transactions.of(adapter) : joined(tx);
    }

    /** Engine hooks: appended after the ports plugin by {@code EngineConfigurationCustomizer}. */
    @Bean
    public CompliancePurposePlugin sangamCompliancePurposePlugin(ObjectProvider<SangamCompliance> compliance) {
        return new CompliancePurposePlugin(compliance);
    }

    @Bean
    @ConditionalOnProperty(name = "sangam.compliance.schedule.enabled", havingValue = "true", matchIfMissing = true)
    public ComplianceScheduler sangamComplianceScheduler(SangamCompliance compliance, TxSupport tx, DataSource ds,
            RestProperties rest, Environment env, ObjectProvider<AdminPort> admin, ComplianceConfigProvider provider,
            ObjectProvider<MeterRegistry> meters) {
        LeaderElection leader = new LeaderElection(tx.dialect(), Transactions.of(ds), rest.getNode(),
                Duration.ofMinutes(3));
        ScheduleTenantsMonitor monitor = ScheduleTenantsMonitor.of("compliance", "retention and DPDP erasure,"
                + " rights/breach/incident escalation, log sealing and audit-integrity verification do not run. Tenants come"
                + " from the tenant directory plus sangam.compliance.tenants (SANGAM_COMPLIANCE_TENANTS); only tenants with"
                + " an effective jurisdiction (sangam.compliance.jurisdictions, sangam.compliance.tenant-jurisdictions or"
                + " onboarding) are scheduled.", meters);
        return new ComplianceScheduler(compliance, leader, monitor.observing(
                () -> tenants(env, admin, monitor).stream().filter(t -> provider.effectiveJurisdiction(t) != null).toList()),
                env.getProperty("sangam.compliance.schedule.retention-batch", Integer.class, 500),
                env.getProperty("sangam.compliance.schedule.integrity-max-chains", Integer.class, 10_000));
    }

    /**
     * Active tenants from the tenant directory (AdminPort, as the system principal of the attribution tenant), plus
     * the optional extra tenants {@code sangam.compliance.tenants}. SL-117: one reader ({@link ScheduleTenants}); a
     * directory failure is logged at WARN and retried on the next cycle.
     */
    static List<String> tenants(Environment env, ObjectProvider<AdminPort> admin, ScheduleTenantsMonitor monitor) {
        return ScheduleTenants.resolve(env, admin, "sangam.compliance.tenants", "system:compliance", monitor);
    }

    /** Transactions that join the current Spring transaction (sangam-engine disabled). */
    private static Transactions joined(TxSupport tx) {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<java.sql.Connection, T> work) {
                return tx.withConnection(work);
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                return tx.withReadConnection(work);
            }
        };
    }
}
