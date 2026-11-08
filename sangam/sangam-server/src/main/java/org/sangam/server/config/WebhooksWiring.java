// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.publish.HttpWebhookPublisher;
import org.sangam.integration.publish.WebhookHealth;
import org.sangam.integration.relay.PublisherRegistry;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SL-39: outbound webhooks from {@link WebhooksProperties}. One {@link HttpWebhookPublisher} serves the destination
 * prefix {@code webhook:*}; each subscription's URL is its own egress allow-list entry (private addresses refused
 * unless {@code allow-private}, never on a production profile); the transport makes one attempt per publish (the
 * outbox relay owns retries, backoff with jitter and dead letters); {@link WebhookHealth} disables a failing
 * destination (audited) and is the re-enable path for operators.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sangam.modules.integration.enabled", havingValue = "true", matchIfMissing = true)
public class WebhooksWiring {

    private static final Logger LOG = LoggerFactory.getLogger(WebhooksWiring.class);
    public static final String PREFIX = "webhook:";

    @Bean
    public WebhooksProperties sangamWebhooksProperties() {
        return new WebhooksProperties();
    }

    @Bean
    public WebhookHealth sangamWebhookHealth(TxSupport tx, DataSource ds, AuditChain sangamIntegrationAuditChain,
                                             WebhooksProperties props) {
        return new WebhookHealth(tx.dialect(), Transactions.of(ds), sangamIntegrationAuditChain, props.getDisableAfter());
    }

    @Bean
    public InitializingBean sangamWebhookRegistration(WebhooksProperties props, PublisherRegistry publishers,
            WebhookHealth health, ObjectProvider<SecretResolver> secrets, Environment env) {
        return () -> {
            if (props.getSubscriptions().isEmpty()) {
                return;
            }
            boolean production = Arrays.stream(env.getActiveProfiles()).anyMatch(p -> p.toLowerCase(Locale.ROOT).contains("prod"));
            Map<String, HttpWebhookPublisher.Endpoint> endpoints = new LinkedHashMap<>();
            List<EgressPolicy.Rule> rules = new ArrayList<>();
            props.getSubscriptions().forEach((name, s) -> {
                HttpWebhookPublisher.Endpoint ep = endpoint(name, s, production);
                endpoints.put(PREFIX + name, ep);
                URI u = ep.url();
                rules.add(new EgressPolicy.Rule(s.getTenant() == null || s.getTenant().isBlank() ? "*" : s.getTenant(),
                        u.getScheme(), u.getHost(), u.getPort(), u.getPath(), s.isAllowPrivate()));
            });
            HttpTransport transport = new HttpTransport(EgressPolicy.of(rules, 0, 1024 * 1024), null,
                    props.getConnectTimeout(), props.getRequestTimeout(), RetryPolicy.none());
            publishers.register(PREFIX + "*", new HttpWebhookPublisher(transport, endpoints::get,
                    secrets.getIfAvailable(SecretResolver::fromEnvironment), health, props.getHoldWhileDisabled(),
                    "urn:sangam:" + env.getProperty("sangam.installation.id", "sangam"), Clock.systemUTC()));
            LOG.info("Outbound webhooks registered: {}", endpoints.keySet());
        };
    }

    static HttpWebhookPublisher.Endpoint endpoint(String name, WebhooksProperties.Subscription s, boolean production) {
        if (s.getUrl() == null || s.getUrl().isBlank()) {
            throw new IllegalStateException("sangam.integration.webhooks.subscriptions." + name + ".url is required");
        }
        URI u = URI.create(s.getUrl().trim());
        if (u.getScheme() == null || u.getHost() == null) {
            throw new IllegalStateException("webhook " + name + ": url must be absolute: " + s.getUrl());
        }
        if (s.getSecretRef() == null || s.getSecretRef().isBlank()) {
            throw new IllegalStateException("webhook " + name + ": secret-ref is required (unsigned webhooks are refused)");
        }
        if (production) {
            if (!"https".equalsIgnoreCase(u.getScheme())) {
                throw new IllegalStateException("webhook " + name + ": production profiles require https");
            }
            if (!s.getSecretRef().startsWith("secret://")) {
                throw new IllegalStateException("webhook " + name + ": production profiles require a secret:// reference");
            }
            if (s.isAllowPrivate()) {
                throw new IllegalStateException("webhook " + name + ": allow-private is refused on production profiles");
            }
        }
        return new HttpWebhookPublisher.Endpoint(u, s.getSecretRef(), s.getHeaders());
    }
}
