// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SL-39: outbound webhook subscriptions, {@code sangam.integration.webhooks.*}. Each entry {@code <name>} is published
 * on the outbox destination {@code webhook:<name>} (list it in {@code sangam.integration.default-destinations}, as
 * {@code webhook:<name>:ordered} for per-root ordering).
 *
 * <pre>
 * sangam.integration.webhooks.subscriptions.crm.url=https://crm.example.org/hooks/sangam
 * sangam.integration.webhooks.subscriptions.crm.secret-ref=secret://webhooks/crm
 * sangam.integration.webhooks.disable-after=50
 * sangam.integration.webhooks.hold-while-disabled=72h
 * </pre>
 */
@ConfigurationProperties("sangam.integration.webhooks")
public class WebhooksProperties {

    private Map<String, Subscription> subscriptions = new LinkedHashMap<>();
    /** Consecutive failed attempts that disable a destination (audited); re-enable through the ops API. */
    private int disableAfter = 50;
    /** How long deliveries of a disabled destination are held before they are dead-lettered. */
    private Duration holdWhileDisabled = Duration.ofHours(72);
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** Per attempt; GitHub treats a receiver slower than 10 s as a failed delivery. */
    private Duration requestTimeout = Duration.ofSeconds(10);

    public static class Subscription {
        private String url;
        private String secretRef;
        /** Tenant allowed to use it ({@code *} = every tenant); the egress allow-list entry is scoped to it. */
        private String tenant = "*";
        /** Allows private / loopback addresses (development only; refused on production profiles). */
        private boolean allowPrivate;
        private Map<String, String> headers = new LinkedHashMap<>();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getSecretRef() {
            return secretRef;
        }

        public void setSecretRef(String secretRef) {
            this.secretRef = secretRef;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public boolean isAllowPrivate() {
            return allowPrivate;
        }

        public void setAllowPrivate(boolean allowPrivate) {
            this.allowPrivate = allowPrivate;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }

    public Map<String, Subscription> getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(Map<String, Subscription> subscriptions) {
        this.subscriptions = subscriptions;
    }

    public int getDisableAfter() {
        return disableAfter;
    }

    public void setDisableAfter(int disableAfter) {
        this.disableAfter = disableAfter;
    }

    public Duration getHoldWhileDisabled() {
        return holdWhileDisabled;
    }

    public void setHoldWhileDisabled(Duration holdWhileDisabled) {
        this.holdWhileDisabled = holdWhileDisabled;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
