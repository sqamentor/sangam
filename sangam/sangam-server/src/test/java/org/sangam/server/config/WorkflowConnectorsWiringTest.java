// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.arazzo.ArazzoOperationStep;
import org.sangam.integration.connector.contract.ApiContractStore;
import org.sangam.integration.connector.temporal.TemporalConnector;
import org.sangam.integration.packs.PackConnectorBindings;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * Server wiring of TemporalConnector and ArazzoOperationStep (W4-H REQUEST 00:50:03Z, integrator ruling 00:50:58Z): off by
 * default (nothing configured, nothing registered), bound per tenant from properties, and a connector that cannot be
 * built stops start-up naming the tenant and connector.
 */
class WorkflowConnectorsWiringTest {

    static final SecretResolver SECRETS = SecretResolver.of(Map.of("secret://temporal/key", "k-1"));

    /** An API contract store that is never called by the wiring itself. */
    static final ApiContractStore STORE = (ApiContractStore) Proxy.newProxyInstance(
            ApiContractStore.class.getClassLoader(), new Class<?>[]{ApiContractStore.class}, (p, m, a) -> {
                throw new UnsupportedOperationException(m.getName());
            });

    static WorkflowConnectorsWiring.TemporalEndpoint temporal(String baseUrl, String namespace, String apiKeyRef) {
        WorkflowConnectorsWiring.TemporalEndpoint e = new WorkflowConnectorsWiring.TemporalEndpoint();
        e.setBaseUrl(baseUrl);
        e.setNamespace(namespace);
        e.setApiKeyRef(apiKeyRef);
        return e;
    }

    static StaticListableBeanFactory beans(ConnectorRegistry registry, ApiContractStore store) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (registry != null) {
            bf.addBean("registry", registry);
        }
        if (store != null) {
            bf.addBean("contracts", store);
        }
        bf.addBean("secrets", SECRETS);
        return bf;
    }

    static int register(MockEnvironment env, StaticListableBeanFactory bf) {
        return WorkflowConnectorsWiring.register(env, bf.getBeanProvider(ConnectorRegistry.class),
                bf.getBeanProvider(PackConnectorBindings.class), bf.getBeanProvider(SecretResolver.class),
                bf.getBeanProvider(ApiContractStore.class));
    }

    @Test
    void nothingIsRegisteredByDefault() {
        ConnectorRegistry registry = new ConnectorRegistry();
        assertEquals(0, register(new MockEnvironment(), beans(registry, null)));
        assertTrue(registry.descriptors().isEmpty());
    }

    @Test
    void configuredConnectorsAreRegisteredPerTenant() {
        ConnectorRegistry registry = new ConnectorRegistry();
        MockEnvironment env = new MockEnvironment()
                .withProperty("sangam.connectors.temporal.tenants.t1.temporal.base-url", "https://temporal.example:7243")
                .withProperty("sangam.connectors.temporal.tenants.t1.temporal.namespace", "orders")
                .withProperty("sangam.connectors.temporal.tenants.t1.temporal.api-key-ref", "secret://temporal/key")
                .withProperty("sangam.connectors.temporal.tenants.t1.temporal.allowed-workflow-types", "Ship,Bill")
                .withProperty("sangam.connectors.arazzo.tenants.t1.arazzo.contracts.petstore", "petstore-http");
        assertEquals(2, register(env, beans(registry, STORE)));
        assertEquals(TemporalConnector.TYPE, registry.require("t1", "temporal").descriptor().type());
        assertEquals(CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, registry.require("t1", "temporal").descriptor().capabilityClass());
        assertEquals(ArazzoOperationStep.TYPE, registry.require("t1", "arazzo").descriptor().type());
        assertTrue(registry.find("t2", "temporal").isEmpty(), "registered for its tenant only");
    }

    @Test
    void anArazzoStepWithoutAContractStoreStopsStartUp() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("sangam.connectors.arazzo.tenants.t1.arazzo.contracts.petstore", "petstore-http");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> register(env, beans(new ConnectorRegistry(), null)));
        assertTrue(e.getMessage().contains("API contract store"), e.getMessage());
    }

    @Test
    void aTemporalConnectorNeedsABaseUrlANamespaceAndHttpsWithASecretKeyInProduction() {
        TemporalConnector c = WorkflowConnectorsWiring.temporal("t1", "temporal",
                temporal("https://temporal.example", "orders", "secret://temporal/key"), SECRETS, true);
        assertEquals("temporal", c.descriptor().id());
        assertEquals(List.of(TemporalConnector.OP_START), c.descriptor().operations());

        IllegalStateException noUrl = assertThrows(IllegalStateException.class, () -> WorkflowConnectorsWiring.temporal(
                "t1", "temporal", temporal(null, "orders", null), SECRETS, false));
        assertTrue(noUrl.getMessage().startsWith("connector t1/temporal: base-url"), noUrl.getMessage());
        assertThrows(IllegalStateException.class, () -> WorkflowConnectorsWiring.temporal("t1", "temporal",
                temporal("https://temporal.example", " ", null), SECRETS, false), "namespace is required");
        assertThrows(IllegalStateException.class, () -> WorkflowConnectorsWiring.temporal("t1", "temporal",
                temporal("http://temporal.internal:7243", "orders", null), SECRETS, true), "https on production");
        assertThrows(IllegalStateException.class, () -> WorkflowConnectorsWiring.temporal("t1", "temporal",
                temporal("https://temporal.example", "orders", "literal-key"), SECRETS, true),
                "a literal API key is refused on a production profile");
        assertThrows(IllegalStateException.class, () -> WorkflowConnectorsWiring.temporal("t1", "temporal",
                temporal("https://temporal.example", "orders", "secret://temporal/missing"), SECRETS, false),
                "an unresolvable API key reference");
    }

    @Test
    void anArazzoStepResolvesTheNamedHttpConnectorOfItsTenantAtCallTime() {
        ConnectorRegistry registry = new ConnectorRegistry();
        WorkflowConnectorsWiring.ArazzoEndpoint e = new WorkflowConnectorsWiring.ArazzoEndpoint();
        e.setContracts(Map.of("petstore", "petstore-http"));
        ArazzoOperationStep step = WorkflowConnectorsWiring.arazzo("t1", "arazzo", e, STORE, registry);
        assertEquals("arazzo", step.descriptor().id());

        WorkflowConnectorsWiring.ArazzoEndpoint empty = new WorkflowConnectorsWiring.ArazzoEndpoint();
        assertThrows(IllegalStateException.class, () -> WorkflowConnectorsWiring.arazzo("t1", "arazzo", empty, STORE,
                registry), "contracts is required");
        WorkflowConnectorsWiring.ArazzoEndpoint blank = new WorkflowConnectorsWiring.ArazzoEndpoint();
        blank.setContracts(Map.of("petstore", " "));
        assertThrows(IllegalStateException.class, () -> WorkflowConnectorsWiring.arazzo("t1", "arazzo", blank, STORE,
                registry));
        // an unpinned ref is refused before any lookup, so the proxy store is never called
        ConnectorResult r = step.invoke(new ConnectorRequest("t1", UUID.randomUUID(), "a1", 1, "root", null, "invoke",
                Map.of("operationRef", "petstore#getPet", "packageId", "p1"), null));
        assertTrue(r instanceof ConnectorResult.BusinessFailure, String.valueOf(r));
        assertEquals("OPERATION_REF_NOT_PINNED", ((ConnectorResult.BusinessFailure) r).code());
    }
}
