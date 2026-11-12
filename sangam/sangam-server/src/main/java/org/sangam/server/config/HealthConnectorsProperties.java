// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.sangam.rest.modules.connectorshealth.CdsClientVerifier;
import org.sangam.security.oidc.ClientJwtVerifier;
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
 * sangam.connectors-health.cds-service.base-url=https://sangam.example.org   # public URL, the JWT aud prefix
 * sangam.connectors-health.cds-service.clock-skew=PT60S / max-token-lifetime=PT5M
 * sangam.connectors-health.cds-service.clients.&lt;name&gt;.issuer / jwks-uri | jwks / algorithms=RS384,ES384
 * </pre>
 * {@code cds-service} (M-006) is the inbound side: the EHRs allowed to call this server's {@code /cds-services}, each
 * with its own JWKS ({@link ClientJwtVerifier}). The tenant {@code cds.*} block is the outbound side (Sangam calling a
 * CDS service).
 * Secrets and keys are references ({@code secret://...}, resolved by the integration {@code SecretResolver}); a live
 * tenant with no egress rule cannot reach any host (air-gapped default).
 */
@ConfigurationProperties("sangam.connectors-health")
public class HealthConnectorsProperties {

    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();
    private Map<String, Channel> fhirChannels = new LinkedHashMap<>();
    private CdsService cdsService = new CdsService();

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

    public CdsService getCdsService() {
        return cdsService;
    }

    public void setCdsService(CdsService cdsService) {
        this.cdsService = cdsService;
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

    /**
     * M-006: the CDS Hooks 2.0 clients (EHRs) allowed to call {@code /cds-services}, each verified against its own
     * JWKS. No client = every call is refused.
     */
    public static class CdsService {
        private String baseUrl;
        private Duration clockSkew = Duration.ofSeconds(60);
        private Duration maxTokenLifetime = Duration.ofMinutes(5);
        private Map<String, CdsClient> clients = new LinkedHashMap<>();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getClockSkew() {
            return clockSkew;
        }

        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
        }

        public Duration getMaxTokenLifetime() {
            return maxTokenLifetime;
        }

        public void setMaxTokenLifetime(Duration maxTokenLifetime) {
            this.maxTokenLifetime = maxTokenLifetime;
        }

        public Map<String, CdsClient> getClients() {
            return clients;
        }

        public void setClients(Map<String, CdsClient> clients) {
            this.clients = clients;
        }

        /**
         * The JWT audience of a call: the configured public base URL followed by the endpoint path. Never derived from
         * the request (a Host header chosen by the caller would defeat the audience check).
         */
        public String audience(String endpointPath) {
            return baseUrl.replaceAll("/+$", "") + endpointPath;
        }

        /** The registered clients for {@link ClientJwtVerifier}; each needs its issuer and one key source. */
        public List<ClientJwtVerifier.Client> verifierClients() {
            List<ClientJwtVerifier.Client> out = new ArrayList<>();
            for (Map.Entry<String, CdsClient> e : clients.entrySet()) {
                CdsClient c = e.getValue();
                try {
                    out.add(new ClientJwtVerifier.Client(c.getIssuer(), c.getJwksUri(), c.getJwks(),
                            c.getAlgorithms() == null ? null : new LinkedHashSet<>(c.getAlgorithms())));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalStateException("sangam.connectors-health.cds-service.clients." + e.getKey() + ": "
                            + ex.getMessage(), ex);
                }
            }
            return out;
        }

        /**
         * The {@link CdsClientVerifier} of these settings, or null when no client is registered (the controller then
         * refuses every call). Fails at start-up when clients exist but {@code base-url} is not set.
         */
        public CdsClientVerifier clientVerifier() {
            return clientVerifier(null);
        }

        /**
         * As {@link #clientVerifier()}, with the {@code jti} replay guard of the verifier: SL-138 owner decision, the
         * cluster-wide {@code JdbcReplayGuard} (null: the verifier's node-local guard, tests only).
         */
        public CdsClientVerifier clientVerifier(ClientJwtVerifier.ReplayGuard replay) {
            if (clients.isEmpty()) {
                return null;
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("sangam.connectors-health.cds-service.base-url is required with"
                        + " registered CDS clients (the public URL that clients put in aud)");
            }
            ClientJwtVerifier verifier = new ClientJwtVerifier(verifierClients(), clockSkew, maxTokenLifetime, null, replay);
            return (token, endpointPath) -> {
                try {
                    return verifier.verify(token, audience(endpointPath)).issuer();
                } catch (ClientJwtVerifier.Rejected e) {
                    throw new CdsClientVerifier.Rejected(e.reason().name(), e.getMessage());
                }
            };
        }
    }

    public static class CdsClient {
        private String issuer;
        private String jwksUri;
        private String jwks;
        private List<String> algorithms = new ArrayList<>();

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getJwks() {
            return jwks;
        }

        public void setJwks(String jwks) {
            this.jwks = jwks;
        }

        public List<String> getAlgorithms() {
            return algorithms;
        }

        public void setAlgorithms(List<String> algorithms) {
            this.algorithms = algorithms;
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
