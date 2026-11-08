// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.config.admin.ConfigAdminService;
import org.sangam.config.profile.LayerModel;
import org.sangam.config.profile.ProfileValidator;
import org.sangam.config.store.JdbcConfigStore;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.onboarding.api.OnboardingRun;
import org.sangam.onboarding.api.OnboardingRequest.ConnectorBindingSpec;
import org.sangam.onboarding.bundle.BundleService;
import org.sangam.onboarding.bundle.BundleSigner;
import org.sangam.onboarding.deps.PackDependencyResolver;
import org.sangam.onboarding.deps.PackRequirementsCatalog;
import org.sangam.onboarding.flow.OnboardingFlowEngine;
import org.sangam.onboarding.flow.OnboardingService;
import org.sangam.onboarding.flow.OnboardingStepDelegate;
import org.sangam.onboarding.flow.OnboardingStore;
import org.sangam.onboarding.port.OnboardingPorts;
import org.sangam.onboarding.profile.ProfilePlanner;
import org.sangam.onboarding.readiness.ReadinessChecker;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.registry.party.PartyService;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.server.bridge.ComplianceConfigProvider;
import org.sangam.server.bridge.OnboardingBridges;
import org.sangam.server.bridge.PacksTenantDeployer;
import org.sangam.server.plugins.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires sangam-onboarding. Properties: {@code sangam.modules.onboarding.enabled}; {@code sangam.onboarding.languages};
 * {@code sangam.onboarding.control-plane.url} / {@code .token} (an ADMIN token; absent = single-cell);
 * {@code sangam.onboarding.bundle.key-id}, {@code .private-key} (base64 PKCS#8), {@code .trusted-keys.<id>} (base64
 * X.509). Unsigned bundles are allowed only on the {@code h2}/{@code dev} profiles.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"sangam.modules.onboarding.enabled", "sangam.modules.security.enabled",
        "sangam.modules.integration.enabled", "sangam.modules.packs.enabled", "sangam.modules.config.enabled",
        "sangam.modules.engine.enabled"}, havingValue = "true", matchIfMissing = true)
public class OnboardingWiring {

    private static final Logger LOG = LoggerFactory.getLogger(OnboardingWiring.class);

    private static boolean dev(Environment env) {
        return Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.equals("h2") || p.equals("dev"))
                && !env.getProperty("sangam.security.production", Boolean.class, false);
    }

    @Bean
    public OnboardingStore sangamOnboardingStore(TxSupport tx, Environment env) {
        if (env.getProperty("sangam.persistence.dev-schema", Boolean.class, false)) {
            ConfigWiring.devSchema(tx, "ONB_RUN", () -> tx.withConnection(c -> {
                try {
                    OnboardingStore.create(c, tx.dialect());
                    return null;
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("cannot create ONB_RUN", e);
                }
            }));
        }
        return new OnboardingStore.Jdbc(tx.dataSource());
    }

    @Bean
    public BundleService sangamOnboardingBundles(JdbcConfigStore store, Environment env) {
        Map<String, String> trusted = Binder.get(env).bind("sangam.onboarding.bundle.trusted-keys",
                Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        BundleSigner signer = new BundleSigner(env.getProperty("sangam.onboarding.bundle.key-id", "default"),
                env.getProperty("sangam.onboarding.bundle.private-key"), trusted);
        return new BundleService(store, signer, dev(env));
    }

    @Bean
    public OnboardingStepDelegate sangamOnboardingStep(OnboardingService service) {
        return new OnboardingStepDelegate(service);
    }

    @Bean
    public OnboardingFlowEngine sangamOnboardingFlow(ProcessEngine engine) {
        return new OnboardingFlowEngine(engine);
    }

    @Bean
    public PackDependencyResolver sangamPackDependencyResolver(PackRegistry registry, PluginManager plugins) {
        List<ClassLoader> loaders = new ArrayList<>();
        loaders.add(OnboardingWiring.class.getClassLoader());
        loaders.addAll(plugins.classLoaders());
        return new PackDependencyResolver(registry, PackRequirementsCatalog.scan(loaders),
                PluginWiring.platform(OnboardingWiring.class.getClassLoader()));
    }

    @Bean
    public OnboardingService.Ports sangamOnboardingPorts(SangamSecurity security, DataSource ds, Environment env,
            ProcessEngine engine, PackDeployer deployer, PacksTenantDeployer tenantDeployer, ConnectorRegistry connectors,
            PackConnectorBindings bindings, JdbcConfigStore configStore, ObjectProvider<PartyService> parties, ObjectProvider<org.sangam.resources.SangamResources> resources,
            ObjectProvider<ComplianceConfigProvider> compliance, AuditPort audit, PluginManager plugins) {
        Transactions tx = Transactions.of(ds);
        List<ClassLoader> loaders = new ArrayList<>();
        loaders.add(OnboardingWiring.class.getClassLoader());
        loaders.addAll(plugins.classLoaders());
        ComplianceConfigProvider cp = compliance.getIfAvailable();
        return new OnboardingService.Ports(new OnboardingBridges.Tenants(security, tx),
                new OnboardingBridges.Placement(env.getProperty("sangam.onboarding.control-plane.url"),
                        env.getProperty("sangam.onboarding.control-plane.token"), env.getProperty("sangam.cell.id")),
                new OnboardingBridges.Packs(engine, deployer, tenantDeployer),
                new OnboardingBridges.Connectors(connectors, bindings,
                        () -> env.getProperty("sangam.packs.simulator-fallback", Boolean.class,
                                Arrays.asList(env.getActiveProfiles()).contains("h2"))),
                new OnboardingBridges.Security(security, tx, () -> SecurityKeysWiring.masterKey(env) != null,
                        () -> notBlank(env, "sangam.registry.signer.private-key")
                                || notBlank(env, "sangam.onboarding.bundle.private-key")),
                new OnboardingBridges.Seeds(configStore, parties.getIfAvailable(), loaders, resources.getIfAvailable(), tx),
                cp == null ? null : new OnboardingBridges.Compliance(cp), new OnboardingBridges.Audit(audit));
    }

    private static boolean notBlank(Environment env, String key) {
        String v = env.getProperty(key);
        return v != null && !v.isBlank();
    }

    @Bean
    public OnboardingService sangamOnboardingService(OnboardingStore store, OnboardingFlowEngine flow,
            OnboardingService.Ports ports, PackDependencyResolver resolver, JdbcConfigStore configStore, LayerModel layers,
            ProfileValidator validator, ConfigAdminService admin, PackRegistry packs, Environment env) {
        Set<String> languages = new LinkedHashSet<>();
        for (String l : env.getProperty("sangam.onboarding.languages", "").split(",")) {
            if (!l.isBlank()) {
                languages.add(l.trim());
            }
        }
        packs.all().forEach(p -> languages.addAll(p.descriptor().name().keySet()));
        OnboardingPorts.LanguagePort lang = () -> languages;
        return new OnboardingService(store, flow, ports, resolver, new ProfilePlanner(configStore, layers, validator),
                new ReadinessChecker(ports.connectors(), ports.security(), lang, ports.tenants()), admin, configStore,
                ConfigWiring.engineClock());
    }

    /** Restores what lives in memory per tenant; also called when a tenant is activated after a move to this cell. */
    @Bean
    public OnboardingRestorer sangamOnboardingRestorer(OnboardingStore store, OnboardingService.Ports ports) {
        return tenant -> {
            for (OnboardingRun r : store.list(tenant)) {
                if (r.status() != OnboardingRun.Status.REJECTED && r.status() != OnboardingRun.Status.CANCELLED) {
                    restore(r, ports);
                    return;
                }
            }
        };
    }

    /** True while the tenant has a run that is not finished; its process instance has no tenant and cannot move. */
    @FunctionalInterface
    public interface OnboardingMoveGuard {
        boolean openRun(String tenant);
    }

    @Bean
    public OnboardingMoveGuard sangamOnboardingMoveGuard(OnboardingStore store) {
        return tenant -> store.list(tenant).stream().anyMatch(r -> r.status() != OnboardingRun.Status.LIVE
                && r.status() != OnboardingRun.Status.REJECTED && r.status() != OnboardingRun.Status.CANCELLED);
    }

    /** Restores one tenant's connector bindings, pack selection and compliance jurisdiction from its newest run. */
    @FunctionalInterface
    public interface OnboardingRestorer {
        void restore(String tenant);
    }

    private static void restore(OnboardingRun r, OnboardingService.Ports ports) {
        String tenant = r.tenantId();
        try {
            Object order = r.step(org.sangam.onboarding.api.StepId.PACKS).output().get("order");
            if (order instanceof List<?> l && !l.isEmpty()) {
                Set<String> ids = new LinkedHashSet<>();
                l.forEach(x -> ids.add(String.valueOf(x)));
                ports.packs().select(tenant, ids);
            }
            if (r.step(org.sangam.onboarding.api.StepId.CONNECTORS).status() == OnboardingRun.StepStatus.DONE) {
                for (ConnectorBindingSpec b : r.request().connectors()) {
                    ports.connectors().bind(tenant, b, "restore");
                }
            }
            if (ports.compliance() != null && !r.request().compliance().jurisdictions().isEmpty()) {
                ports.compliance().enable(tenant, r.request().compliance().jurisdictions(),
                        r.request().compliance().settings(), "restore");
            }
        } catch (RuntimeException e) {
            LOG.error("Cannot restore the onboarding state of tenant {}: {}", tenant, e.toString());
        }
    }

    @Bean
    public ApplicationRunner sangamOnboardingStartup(OnboardingFlowEngine flow, OnboardingStore store,
                                                     OnboardingRestorer restorer) {
        return args -> {
            flow.deploy();
            Set<String> tenants = new LinkedHashSet<>();
            store.list(null).forEach(r -> tenants.add(r.tenantId()));
            tenants.forEach(restorer::restore);
        };
    }
}
