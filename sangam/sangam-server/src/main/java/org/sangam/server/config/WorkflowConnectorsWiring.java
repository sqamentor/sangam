// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.arazzo.ArazzoOperationStep;
import org.sangam.integration.connector.contract.ApiContractStore;
import org.sangam.integration.connector.temporal.TemporalConnector;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.server.bridge.ConfigConnectorBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Workflow-to-workflow connectors, registered per tenant on the integration {@link ConnectorRegistry} exactly like
 * {@link ConnectorBreadthWiring}: nothing is configured by default (so nothing is registered), and a configured connector
 * that cannot be built stops start-up with a message naming the tenant and connector.
 *
 * <ul>
 *   <li>{@code sangam.connectors.temporal.tenants.<tenant>.<id>} (owner decisions 163 and 216, M-070,
 *       {@link TemporalConnector}, type {@value TemporalConnector#TYPE}): {@code base-url} (the Temporal HTTP API),
 *       {@code namespace}, {@code api-key-ref} (optional Bearer key by reference), {@code default-workflow-type},
 *       {@code default-task-queue}, {@code allowed-workflow-types}, {@code shorthand-payloads}, {@code timeout},
 *       {@code await-timeout}, {@code allow-private}, {@code connect-timeout}, {@code read-timeout} (default 60 s, longer
 *       than the connector's 30 s long poll), {@code max-response-bytes}, {@code pack-bindings}. LIVE transport through
 *       an egress policy that admits exactly the base URL's origin.</li>
 *   <li>{@code sangam.connectors.arazzo.tenants.<tenant>.<id>} (owner decision 159, M-069, {@link ArazzoOperationStep},
 *       type {@value ArazzoOperationStep#TYPE}): {@code contracts.<contractId>} names the tenant's HTTP connector that
 *       calls that contract's API (its base URL, credentials and egress); {@code pack-bindings}. The step resolves the
 *       pinned operation from the server's {@link ApiContractStore}; a contract with no HTTP connector named here is
 *       refused at run time with {@code CONNECTOR_NOT_CONFIGURED}.</li>
 * </ul>
 * On a production profile the Temporal base URL must be https and the API key a {@code secret://} reference.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowConnectorsWiring {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowConnectorsWiring.class);
    static final String TEMPORAL = "sangam.connectors.temporal.tenants";
    static final String ARAZZO = "sangam.connectors.arazzo.tenants";

    /** One Temporal cluster namespace. */
    public static class TemporalEndpoint {
        private String baseUrl;
        private String namespace;
        private String apiKeyRef;
        private String defaultWorkflowType;
        private String defaultTaskQueue;
        private List<String> allowedWorkflowTypes = List.of();
        private boolean shorthandPayloads;
        private Duration timeout;
        private Duration awaitTimeout;
        private boolean allowPrivate;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(60);
        private long maxResponseBytes = 16L * 1024 * 1024;
        private Map<String, String> packBindings = new LinkedHashMap<>();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { baseUrl = v; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String v) { namespace = v; }
        public String getApiKeyRef() { return apiKeyRef; }
        public void setApiKeyRef(String v) { apiKeyRef = v; }
        public String getDefaultWorkflowType() { return defaultWorkflowType; }
        public void setDefaultWorkflowType(String v) { defaultWorkflowType = v; }
        public String getDefaultTaskQueue() { return defaultTaskQueue; }
        public void setDefaultTaskQueue(String v) { defaultTaskQueue = v; }
        public List<String> getAllowedWorkflowTypes() { return allowedWorkflowTypes; }
        public void setAllowedWorkflowTypes(List<String> v) { allowedWorkflowTypes = v; }
        public boolean isShorthandPayloads() { return shorthandPayloads; }
        public void setShorthandPayloads(boolean v) { shorthandPayloads = v; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration v) { timeout = v; }
        public Duration getAwaitTimeout() { return awaitTimeout; }
        public void setAwaitTimeout(Duration v) { awaitTimeout = v; }
        public boolean isAllowPrivate() { return allowPrivate; }
        public void setAllowPrivate(boolean v) { allowPrivate = v; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration v) { connectTimeout = v; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration v) { readTimeout = v; }
        public long getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(long v) { maxResponseBytes = v; }
        public Map<String, String> getPackBindings() { return packBindings; }
        public void setPackBindings(Map<String, String> v) { packBindings = v; }
    }

    /** One Arazzo operation step connector: contract id to the tenant's HTTP connector id. */
    public static class ArazzoEndpoint {
        private Map<String, String> contracts = new LinkedHashMap<>();
        private Map<String, String> packBindings = new LinkedHashMap<>();

        public Map<String, String> getContracts() { return contracts; }
        public void setContracts(Map<String, String> v) { contracts = v; }
        public Map<String, String> getPackBindings() { return packBindings; }
        public void setPackBindings(Map<String, String> v) { packBindings = v; }
    }

    @Bean
    public InitializingBean sangamWorkflowConnectorRegistration(Environment env, ObjectProvider<ConnectorRegistry> registry,
            ObjectProvider<PackConnectorBindings> bindings, ObjectProvider<SecretResolver> secrets,
            ObjectProvider<ApiContractStore> contracts) {
        return () -> register(env, registry, bindings, secrets, contracts);
    }

    /** Registers what is configured; returns how many connectors were registered (0 when nothing is configured). */
    static int register(Environment env, ObjectProvider<ConnectorRegistry> registry,
                        ObjectProvider<PackConnectorBindings> bindings, ObjectProvider<SecretResolver> secrets,
                        ObjectProvider<ApiContractStore> contracts) {
        Map<String, Map<String, TemporalEndpoint>> temporal = ConnectorBreadthWiring.bind(env, TEMPORAL, TemporalEndpoint.class);
        Map<String, Map<String, ArazzoEndpoint>> arazzo = ConnectorBreadthWiring.bind(env, ARAZZO, ArazzoEndpoint.class);
        if (temporal.isEmpty() && arazzo.isEmpty()) {
            return 0;
        }
        boolean production = ProductionEnvironment.isProduction(env);
        SecretResolver resolver = secrets.getIfAvailable(SecretResolver::fromEnvironment);
        ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
        int n = 0;
        for (var t : temporal.entrySet()) {
            for (var e : t.getValue().entrySet()) {
                register(binder, t.getKey(), temporal(t.getKey(), e.getKey(), e.getValue(), resolver, production),
                        e.getValue().getPackBindings());
                n++;
            }
        }
        if (!arazzo.isEmpty()) {
            ApiContractStore store = contracts.getIfAvailable();
            if (store == null) {
                throw new IllegalStateException(ARAZZO + " is configured but no API contract store is bound");
            }
            ConnectorRegistry reg = registry.getIfAvailable();
            for (var t : arazzo.entrySet()) {
                for (var e : t.getValue().entrySet()) {
                    register(binder, t.getKey(), arazzo(t.getKey(), e.getKey(), e.getValue(), store, reg),
                            e.getValue().getPackBindings());
                    n++;
                }
            }
        }
        LOG.info("Temporal and Arazzo connectors registered: {}", n);
        return n;
    }

    private static void register(ConfigConnectorBinder binder, String tenant, SangamConnector c, Map<String, String> packs) {
        if (!binder.register(tenant, c)) {
            throw new IllegalStateException("sangam.connectors (temporal/arazzo) is configured but sangam-integration "
                    + "(the connector registry) is not available");
        }
        if (packs != null && !packs.isEmpty()) {
            Map<String, String> ops = new LinkedHashMap<>();
            packs.forEach((k, v) -> ops.put(k, v == null || v.isBlank() ? null : v));
            binder.bind(c.descriptor().id(), ops);
        }
    }

    static TemporalConnector temporal(String tenant, String id, TemporalEndpoint e, SecretResolver secrets,
                                      boolean production) {
        String where = "connector " + tenant + "/" + id;
        URI base;
        try {
            if (e.getBaseUrl() == null || e.getBaseUrl().isBlank()) {
                throw new IllegalArgumentException("base-url is required");
            }
            base = URI.create(e.getBaseUrl().trim());
            if (base.getScheme() == null || base.getHost() == null) {
                throw new IllegalArgumentException("base-url needs scheme://host");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(where + ": " + ex.getMessage(), ex);
        }
        if (production && !"https".equalsIgnoreCase(base.getScheme())) {
            throw new IllegalStateException(where + ": base-url must be https on a production profile");
        }
        String apiKeyRef = e.getApiKeyRef() == null || e.getApiKeyRef().isBlank() ? null : e.getApiKeyRef().trim();
        if (apiKeyRef != null) {
            if (production && !apiKeyRef.toLowerCase(Locale.ROOT).startsWith("secret://")) {
                throw new IllegalStateException(where + ": api-key-ref must be a secret:// reference on a production profile");
            }
            try {
                String v = secrets.require(apiKeyRef);
                if (v == null || v.isBlank()) {
                    throw new IllegalStateException(where + ": api-key-ref resolves to an empty value");
                }
            } catch (IllegalStateException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new IllegalStateException(where + ": api-key-ref cannot be resolved (" + ex.getMessage() + ")");
            }
        }
        String origin = base.getScheme().toLowerCase(Locale.ROOT) + "://" + base.getHost()
                + (base.getPort() == -1 ? "" : ":" + base.getPort());
        try {
            EgressPolicy egress = EgressPolicy.of(List.of(EgressPolicy.Rule.parse(tenant, origin, e.isAllowPrivate())), 0,
                    e.getMaxResponseBytes() <= 0 ? 16L * 1024 * 1024 : e.getMaxResponseBytes());
            HttpTransport transport = new HttpTransport(egress, null, e.getConnectTimeout(), e.getReadTimeout(),
                    RetryPolicy.none());
            return new TemporalConnector(new TemporalConnector.Config(id, base, e.getNamespace(), transport, secrets,
                    apiKeyRef, e.getDefaultWorkflowType(), e.getDefaultTaskQueue(),
                    new LinkedHashSet<>(e.getAllowedWorkflowTypes() == null ? List.of() : e.getAllowedWorkflowTypes()),
                    e.isShorthandPayloads(), e.getTimeout(), e.getAwaitTimeout(), ConnectorProfile.LIVE, null));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException(where + ": " + ex.getMessage(), ex);
        }
    }

    static ArazzoOperationStep arazzo(String tenant, String id, ArazzoEndpoint e, ApiContractStore store,
                                      ConnectorRegistry registry) {
        String where = "connector " + tenant + "/" + id;
        Map<String, String> byContract = new LinkedHashMap<>();
        (e.getContracts() == null ? Map.<String, String>of() : e.getContracts()).forEach((contract, connector) -> {
            if (contract == null || contract.isBlank() || connector == null || connector.isBlank()) {
                throw new IllegalStateException(where + ": contracts.<contractId> must name an HTTP connector id");
            }
            byContract.put(contract.trim(), connector.trim());
        });
        if (byContract.isEmpty()) {
            throw new IllegalStateException(where + ": contracts is required (contract id to HTTP connector id)");
        }
        if (registry == null) {
            throw new IllegalStateException(where + ": the connector registry is not available");
        }
        // looked up at call time, so the HTTP connectors may be registered after this step
        Function<String, SangamConnector> http = contract -> {
            String connector = byContract.get(contract);
            return connector == null ? null : registry.find(tenant, connector).orElse(null);
        };
        return new ArazzoOperationStep(id, store, http, ConnectorProfile.LIVE);
    }
}
