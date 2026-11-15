// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * India DLT SMS, {@code sangam.integration.dlt.*} (owner decisions 304 and 305; {@link DltWiring}).
 *
 * <pre>
 * # decision 304: the signing secret of the delivery-report webhook, per DLT SMS connector (a reference, never a value)
 * sangam.integration.dlt.delivery-reports.secrets.main.connector=sms-dlt
 * sangam.integration.dlt.delivery-reports.secrets.main.tenant=acme            # optional: only this tenant's connector
 * sangam.integration.dlt.delivery-reports.secrets.main.secret-ref=secret://dlt/webhook
 * sangam.integration.dlt.delivery-reports.tolerance=5m
 * # decision 304: the status-lookup fallback for messages whose report did not arrive
 * sangam.integration.dlt.delivery-status.enabled=true
 * sangam.integration.dlt.delivery-status.interval=5m
 * # decision 305: start-up configuration only seeds the stored registration (never overwrites a stored entry)
 * sangam.integration.dlt.registration.refresh-interval=1m
 * sangam.integration.dlt.registration.seed.acme.entity-id=1101000000000000001
 * sangam.integration.dlt.registration.seed.acme.headers[0]=GOVSVC
 * sangam.integration.dlt.registration.seed.acme.templates[0].template-id=1107000000000000001
 * sangam.integration.dlt.registration.seed.acme.templates[0].header=GOVSVC
 * sangam.integration.dlt.registration.seed.acme.templates[0].body=Application {#var#} received - GOVSVC
 * sangam.integration.dlt.registration.seed.acme.urls[0]=https://services.example.gov.in/
 * </pre>
 * A connector without a configured secret refuses every delivery report (401, fail closed).
 */
@ConfigurationProperties("sangam.integration.dlt")
public class DltProperties {

    private final DeliveryReports deliveryReports = new DeliveryReports();
    private final DeliveryStatus deliveryStatus = new DeliveryStatus();
    private final Registration registration = new Registration();

    public DeliveryReports getDeliveryReports() {
        return deliveryReports;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public Registration getRegistration() {
        return registration;
    }

    /** The signed delivery-report webhook. */
    public static class DeliveryReports {
        private Map<String, Secret> secrets = new LinkedHashMap<>();
        /** Accepted distance of the signature's timestamp from the server clock. */
        private Duration tolerance = Duration.ofMinutes(5);

        public Map<String, Secret> getSecrets() {
            return secrets;
        }

        public void setSecrets(Map<String, Secret> secrets) {
            this.secrets = secrets;
        }

        public Duration getTolerance() {
            return tolerance;
        }

        public void setTolerance(Duration tolerance) {
            this.tolerance = tolerance;
        }
    }

    /** The signing secret of one DLT SMS connector's delivery reports. */
    public static class Secret {
        /** The connector id (required). */
        private String connector;
        /** Only this tenant's connector; unset: the connector of every tenant that has no entry of its own. */
        private String tenant;
        /** {@code secret://...}; never the secret itself. */
        private String secretRef;

        public String getConnector() {
            return connector;
        }

        public void setConnector(String connector) {
            this.connector = connector;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getSecretRef() {
            return secretRef;
        }

        public void setSecretRef(String secretRef) {
            this.secretRef = secretRef;
        }
    }

    /** The status-lookup fallback ({@code DltDeliveryStatusPoller}). */
    public static class DeliveryStatus {
        private boolean enabled = true;
        /** Time between two passes of the poller on a node. */
        private Duration interval = Duration.ofMinutes(5);
        /** Wait after the send before the first lookup (the webhook usually reports first). */
        private Duration firstLookupAfter = Duration.ofMinutes(15);
        /** Wait between two lookups of one message. */
        private Duration lookupEvery = Duration.ofMinutes(30);
        /** Sends older than this are not looked up. */
        private Duration window = Duration.ofDays(3);
        private int maxLookups = 12;
        private int batchSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getFirstLookupAfter() {
            return firstLookupAfter;
        }

        public void setFirstLookupAfter(Duration firstLookupAfter) {
            this.firstLookupAfter = firstLookupAfter;
        }

        public Duration getLookupEvery() {
            return lookupEvery;
        }

        public void setLookupEvery(Duration lookupEvery) {
            this.lookupEvery = lookupEvery;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public int getMaxLookups() {
            return maxLookups;
        }

        public void setMaxLookups(int maxLookups) {
            this.maxLookups = maxLookups;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    /** The stored per-tenant registration. */
    public static class Registration {
        /** How often a node loads the registrations other nodes changed. */
        private Duration refreshInterval = Duration.ofMinutes(1);
        /** Seed per tenant id. */
        private Map<String, Seed> seed = new LinkedHashMap<>();

        public Duration getRefreshInterval() {
            return refreshInterval;
        }

        public void setRefreshInterval(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
        }

        public Map<String, Seed> getSeed() {
            return seed;
        }

        public void setSeed(Map<String, Seed> seed) {
            this.seed = seed;
        }
    }

    /** Start-up registration of one tenant: added where absent, never overwriting a stored entry. */
    public static class Seed {
        private String entityId;
        private List<String> headers = new ArrayList<>();
        private List<Template> templates = new ArrayList<>();
        private List<String> urls = new ArrayList<>();

        public String getEntityId() {
            return entityId;
        }

        public void setEntityId(String entityId) {
            this.entityId = entityId;
        }

        public List<String> getHeaders() {
            return headers;
        }

        public void setHeaders(List<String> headers) {
            this.headers = headers;
        }

        public List<Template> getTemplates() {
            return templates;
        }

        public void setTemplates(List<Template> templates) {
            this.templates = templates;
        }

        public List<String> getUrls() {
            return urls;
        }

        public void setUrls(List<String> urls) {
            this.urls = urls;
        }
    }

    /** One seeded content template. */
    public static class Template {
        private String templateId;
        private String header;
        private String body;

        public String getTemplateId() {
            return templateId;
        }

        public void setTemplateId(String templateId) {
            this.templateId = templateId;
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }
    }
}
