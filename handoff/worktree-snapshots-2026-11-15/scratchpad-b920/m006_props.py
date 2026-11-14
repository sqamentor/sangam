import io, os
os.chdir(r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam")


def edit(p, pairs):
    s = io.open(p, encoding='utf-8', newline='').read()
    for old, new in pairs:
        old = old.replace('\r\n', '\n'); new = new.replace('\r\n', '\n')
        if '\r\n' in s:
            old = old.replace('\n', '\r\n'); new = new.replace('\n', '\r\n')
        assert s.count(old) == 1, (p, old[:60], s.count(old))
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


edit('sangam-server/src/main/java/org/sangam/server/config/HealthConnectorsProperties.java', [
("""import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
""", """import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.sangam.rest.modules.connectorshealth.CdsClientVerifier;
import org.sangam.security.oidc.ClientJwtVerifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
"""),
(""" * sangam.connectors-health.fhir-channels.&lt;channel&gt;.tenant / server / secret-header / secret-ref /
 *                                           event-types.&lt;topic or resource type&gt;=&lt;Sangam event type&gt;
 * </pre>
""", """ * sangam.connectors-health.fhir-channels.&lt;channel&gt;.tenant / server / secret-header / secret-ref /
 *                                           event-types.&lt;topic or resource type&gt;=&lt;Sangam event type&gt;
 * sangam.connectors-health.cds-service.base-url=https://sangam.example.org   # public URL, the JWT aud prefix
 * sangam.connectors-health.cds-service.clock-skew=PT60S / max-token-lifetime=PT5M
 * sangam.connectors-health.cds-service.clients.&lt;name&gt;.issuer / jwks-uri | jwks / algorithms=RS384,ES384
 * </pre>
 * {@code cds-service} (M-006) is the inbound side: the EHRs allowed to call this server's {@code /cds-services}, each
 * with its own JWKS ({@link ClientJwtVerifier}). The tenant {@code cds.*} block is the outbound side (Sangam calling a
 * CDS service).
"""),
("""    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();
    private Map<String, Channel> fhirChannels = new LinkedHashMap<>();
""", """    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();
    private Map<String, Channel> fhirChannels = new LinkedHashMap<>();
    private CdsService cdsService = new CdsService();
"""),
("""    public void setFhirChannels(Map<String, Channel> fhirChannels) {
        this.fhirChannels = fhirChannels;
    }
""", """    public void setFhirChannels(Map<String, Channel> fhirChannels) {
        this.fhirChannels = fhirChannels;
    }

    public CdsService getCdsService() {
        return cdsService;
    }

    public void setCdsService(CdsService cdsService) {
        this.cdsService = cdsService;
    }
"""),
("""    public static class Channel {
        private String tenant;
""", """    /**
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
            if (clients.isEmpty()) {
                return null;
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("sangam.connectors-health.cds-service.base-url is required with"
                        + " registered CDS clients (the public URL that clients put in aud)");
            }
            ClientJwtVerifier verifier = new ClientJwtVerifier(verifierClients(), clockSkew, maxTokenLifetime, null, null);
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
"""),
])
print("ok")
