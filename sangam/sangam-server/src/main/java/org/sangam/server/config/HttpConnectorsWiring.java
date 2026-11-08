// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.http.HttpRestConnector;
import org.sangam.integration.connector.http.LiveHttpConnectors;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.server.bridge.ConfigConnectorBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SL-17: LIVE generic HTTP/REST connectors from {@link HttpConnectorsProperties} ({@code sangam.connectors.http.*}),
 * built by {@link LiveHttpConnectors} (egress guard, secrets by reference, mTLS, OAuth2) and registered per tenant on
 * the integration {@link ConnectorRegistry}; their {@code pack-bindings} are bound in {@link PackConnectorBindings}.
 * A connector that cannot be built fails startup (never a silently missing connector); on a production profile
 * (an active profile containing {@code prod}) a non-https URL or a missing/literal credential is refused.
 */
@Configuration(proxyBeanMethods = false)
public class HttpConnectorsWiring {

    private static final Logger LOG = LoggerFactory.getLogger(HttpConnectorsWiring.class);

    @Bean
    public HttpConnectorsProperties sangamHttpConnectorsProperties() {
        return new HttpConnectorsProperties();
    }

    @Bean
    public InitializingBean sangamHttpConnectorRegistration(HttpConnectorsProperties props, Environment env,
            ObjectProvider<ConnectorRegistry> registry, ObjectProvider<PackConnectorBindings> bindings,
            ObjectProvider<SecretResolver> secrets) {
        return () -> {
            if (props.getTenants().isEmpty()) {
                return;
            }
            boolean production = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.toLowerCase(Locale.ROOT).contains("prod"));
            SecretResolver resolver = secrets.getIfAvailable(SecretResolver::fromEnvironment);
            ConfigConnectorBinder binder = new ConfigConnectorBinder(registry, bindings);
            int n = 0;
            for (Map.Entry<String, Map<String, HttpConnectorsProperties.Endpoint>> t : props.getTenants().entrySet()) {
                for (Map.Entry<String, HttpConnectorsProperties.Endpoint> e : t.getValue().entrySet()) {
                    HttpRestConnector c = LiveHttpConnectors.build(t.getKey(), spec(e.getKey(), e.getValue()), resolver,
                            production);
                    if (!binder.register(t.getKey(), c)) {
                        throw new IllegalStateException("sangam.connectors.http is configured but sangam-integration "
                                + "(the connector registry) is not available");
                    }
                    if (!e.getValue().getPackBindings().isEmpty()) {
                        Map<String, String> ops = new LinkedHashMap<>();
                        e.getValue().getPackBindings().forEach((k, v) -> ops.put(k, v == null || v.isBlank() ? null : v));
                        binder.bind(e.getKey(), ops);
                    }
                    n++;
                }
            }
            LOG.info("LIVE HTTP connectors registered: {}", n);
        };
    }

    static LiveHttpConnectors.Spec spec(String id, HttpConnectorsProperties.Endpoint e) {
        HttpConnectorsProperties.Auth a = e.getAuth();
        String type = a.getType() == null ? "none" : a.getType().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (type.equals("OAUTH2")) {
            type = "OAUTH2_CLIENT_CREDENTIALS";
        }
        LiveHttpConnectors.AuthType at;
        try {
            at = LiveHttpConnectors.AuthType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("sangam.connectors.http connector " + id + ": unknown auth.type " + a.getType());
        }
        HttpConnectorsProperties.Tls t = e.getTls();
        return new LiveHttpConnectors.Spec(id, e.getBaseUrl(), e.getMethod(), e.getCapabilityClass(),
                e.getIdempotencyHeader(), e.getLookupPath(),
                new LiveHttpConnectors.Auth(at, a.getSecretRef(), a.getTokenUrl(), a.getClientId(), a.getClientSecretRef(),
                        a.getScope()),
                new LiveHttpConnectors.Tls(t.getKeyStore(), t.getKeyStorePasswordRef(), t.getTrustStore(),
                        t.getTrustStorePasswordRef()),
                e.getConnectTimeout(), e.getReadTimeout(), e.getMaxAttempts(), e.getEgress(), e.isAllowPrivate(),
                e.getDefaultHeaders(), e.getResponse().getOutput(), e.getResponse().getProviderRef());
    }
}
