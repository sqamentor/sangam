// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sangam.connectors-health.*}: the health connectors per tenant and the FHIR subscription channels.
 * <pre>
 * sangam.connectors-health.tenants.&lt;tenant&gt;.mode=simulator|live
 * sangam.connectors-health.tenants.&lt;tenant&gt;.egress=https://payer.example:443,https://ch.example:443   # live egress rules
 * sangam.connectors-health.tenants.&lt;tenant&gt;.fhir.base-url / auth-mode / token-uri / client-id / scope / secret-ref /
 *                                            private-key-ref / key-id
 * sangam.connectors-health.tenants.&lt;tenant&gt;.x12.base-url / sender-id / receiver-id / production / realtime-path /
 *                                           batch-path / status-path / authorization-ref
 * sangam.connectors-health.tenants.&lt;tenant&gt;.cds.base-url / client-iss / private-key-ref / key-id
 * sangam.connectors-health.fhir-channels.&lt;channel&gt;.tenant / server / secret-header / secret-ref /
 *                                           event-types.&lt;topic or resource type&gt;=&lt;Sangam event type&gt;
 * </pre>
 * Secrets and keys are references ({@code secret://...}, resolved by the integration {@code SecretResolver}); a live
 * tenant with no egress rule cannot reach any host (air-gapped default).
 */
@ConfigurationProperties("sangam.connectors-health")
public class HealthConnectorsProperties {

    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();
    private Map<String, Channel> fhirChannels = new LinkedHashMap<>();

    public Map<String, TenantConfig> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, TenantConfig> tenants) {
        this.tenants = tenants;
    }

    public Map<String, Channel> getFhirChannels() {
        return fhirChannels;
    }

    public void setFhirChannels(Map<String, Channel> fhirChannels) {
        this.fhirChannels = fhirChannels;
    }

    public static class TenantConfig {
        private String mode = "simulator";
        private List<String> egress = new ArrayList<>();
        private Fhir fhir = new Fhir();
        private X12 x12 = new X12();
        private Cds cds = new Cds();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public List<String> getEgress() {
            return egress;
        }

        public void setEgress(List<String> egress) {
            this.egress = egress;
        }

        public Fhir getFhir() {
            return fhir;
        }

        public void setFhir(Fhir fhir) {
            this.fhir = fhir;
        }

        public X12 getX12() {
            return x12;
        }

        public void setX12(X12 x12) {
            this.x12 = x12;
        }

        public Cds getCds() {
            return cds;
        }

        public void setCds(Cds cds) {
            this.cds = cds;
        }
    }

    public static class Fhir {
        private String baseUrl;
        private String authMode = "CLIENT_SECRET";
        private String tokenUri;
        private String clientId;
        private String scope = "system/*.rs system/*.cud";
        private String secretRef;
        private String privateKeyRef;
        private String keyId;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getAuthMode() {
            return authMode;
        }

        public void setAuthMode(String authMode) {
            this.authMode = authMode;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getSecretRef() {
            return secretRef;
        }

        public void setSecretRef(String secretRef) {
            this.secretRef = secretRef;
        }

        public String getPrivateKeyRef() {
            return privateKeyRef;
        }

        public void setPrivateKeyRef(String privateKeyRef) {
            this.privateKeyRef = privateKeyRef;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }
    }

    public static class X12 {
        private String baseUrl;
        private String senderId;
        private String receiverId;
        private boolean production;
        private String realtimePath;
        private String batchPath;
        private String statusPath;
        private String authorizationRef;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSenderId() {
            return senderId;
        }

        public void setSenderId(String senderId) {
            this.senderId = senderId;
        }

        public String getReceiverId() {
            return receiverId;
        }

        public void setReceiverId(String receiverId) {
            this.receiverId = receiverId;
        }

        public boolean isProduction() {
            return production;
        }

        public void setProduction(boolean production) {
            this.production = production;
        }

        public String getRealtimePath() {
            return realtimePath;
        }

        public void setRealtimePath(String realtimePath) {
            this.realtimePath = realtimePath;
        }

        public String getBatchPath() {
            return batchPath;
        }

        public void setBatchPath(String batchPath) {
            this.batchPath = batchPath;
        }

        public String getStatusPath() {
            return statusPath;
        }

        public void setStatusPath(String statusPath) {
            this.statusPath = statusPath;
        }

        public String getAuthorizationRef() {
            return authorizationRef;
        }

        public void setAuthorizationRef(String authorizationRef) {
            this.authorizationRef = authorizationRef;
        }
    }

    public static class Cds {
        private String baseUrl;
        private String clientIss;
        private String privateKeyRef;
        private String keyId;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getClientIss() {
            return clientIss;
        }

        public void setClientIss(String clientIss) {
            this.clientIss = clientIss;
        }

        public String getPrivateKeyRef() {
            return privateKeyRef;
        }

        public void setPrivateKeyRef(String privateKeyRef) {
            this.privateKeyRef = privateKeyRef;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }
    }

    public static class Channel {
        private String tenant;
        private String server;
        private String secretHeader = "Authorization";
        private String secretRef;
        private Map<String, String> eventTypes = new LinkedHashMap<>();

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
        }

        public String getSecretHeader() {
            return secretHeader;
        }

        public void setSecretHeader(String secretHeader) {
            this.secretHeader = secretHeader;
        }

        public String getSecretRef() {
            return secretRef;
        }

        public void setSecretRef(String secretRef) {
            this.secretRef = secretRef;
        }

        public Map<String, String> getEventTypes() {
            return eventTypes;
        }

        public void setEventTypes(Map<String, String> eventTypes) {
            this.eventTypes = eventTypes;
        }
    }
}
