// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sangam.connectors.http.*}: LIVE generic HTTP/REST connectors per tenant (SL-17).
 * <pre>
 * sangam.connectors.http.tenants.&lt;tenant&gt;.&lt;connectorId&gt;.base-url=https://api.example.org/v1
 *   .method=POST                       .capability-class=a|b|c (default c)
 *   .idempotency-header=Idempotency-Key  .lookup-path=/operations/{operationId}
 *   .auth.type=none|basic|bearer|oauth2-client-credentials|mtls
 *   .auth.secret-ref=secret://x/y       (basic user:password, bearer token)
 *   .auth.token-url / .auth.client-id / .auth.client-secret-ref / .auth.scope   (oauth2)
 *   .tls.key-store / .tls.key-store-password-ref / .tls.trust-store / .tls.trust-store-password-ref (mtls, private CA)
 *   .connect-timeout=10s .read-timeout=30s .max-attempts=1
 *   .egress=https://api.example.org:443   (default: the base-url origin)   .allow-private=false
 *   .default-headers.&lt;name&gt;=&lt;value&gt;
 *   .response.output.&lt;name&gt;=/json/pointer   .response.provider-ref=/json/pointer
 *   .pack-bindings.&lt;packConnectorId&gt;=&lt;operation, empty = the request's own&gt;
 * </pre>
 */
@ConfigurationProperties("sangam.connectors.http")
public class HttpConnectorsProperties {

    private Map<String, Map<String, Endpoint>> tenants = new LinkedHashMap<>();

    public Map<String, Map<String, Endpoint>> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, Map<String, Endpoint>> tenants) {
        this.tenants = tenants;
    }

    public static class Endpoint {
        private String baseUrl;
        private String method = "POST";
        private String capabilityClass;
        private String idempotencyHeader = "Idempotency-Key";
        private String lookupPath;
        private Auth auth = new Auth();
        private Tls tls = new Tls();
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(30);
        private int maxAttempts = 1;
        private List<String> egress = new ArrayList<>();
        private boolean allowPrivate;
        private Map<String, String> defaultHeaders = new LinkedHashMap<>();
        private Response response = new Response();
        private Map<String, String> packBindings = new LinkedHashMap<>();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { baseUrl = v; }
        public String getMethod() { return method; }
        public void setMethod(String v) { method = v; }
        public String getCapabilityClass() { return capabilityClass; }
        public void setCapabilityClass(String v) { capabilityClass = v; }
        public String getIdempotencyHeader() { return idempotencyHeader; }
        public void setIdempotencyHeader(String v) { idempotencyHeader = v; }
        public String getLookupPath() { return lookupPath; }
        public void setLookupPath(String v) { lookupPath = v; }
        public Auth getAuth() { return auth; }
        public void setAuth(Auth v) { auth = v; }
        public Tls getTls() { return tls; }
        public void setTls(Tls v) { tls = v; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration v) { connectTimeout = v; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration v) { readTimeout = v; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { maxAttempts = v; }
        public List<String> getEgress() { return egress; }
        public void setEgress(List<String> v) { egress = v; }
        public boolean isAllowPrivate() { return allowPrivate; }
        public void setAllowPrivate(boolean v) { allowPrivate = v; }
        public Map<String, String> getDefaultHeaders() { return defaultHeaders; }
        public void setDefaultHeaders(Map<String, String> v) { defaultHeaders = v; }
        public Response getResponse() { return response; }
        public void setResponse(Response v) { response = v; }
        public Map<String, String> getPackBindings() { return packBindings; }
        public void setPackBindings(Map<String, String> v) { packBindings = v; }
    }

    public static class Auth {
        private String type = "none";
        private String secretRef;
        private String tokenUrl;
        private String clientId;
        private String clientSecretRef;
        private String scope;

        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getSecretRef() { return secretRef; }
        public void setSecretRef(String v) { secretRef = v; }
        public String getTokenUrl() { return tokenUrl; }
        public void setTokenUrl(String v) { tokenUrl = v; }
        public String getClientId() { return clientId; }
        public void setClientId(String v) { clientId = v; }
        public String getClientSecretRef() { return clientSecretRef; }
        public void setClientSecretRef(String v) { clientSecretRef = v; }
        public String getScope() { return scope; }
        public void setScope(String v) { scope = v; }
    }

    public static class Tls {
        private String keyStore;
        private String keyStorePasswordRef;
        private String trustStore;
        private String trustStorePasswordRef;

        public String getKeyStore() { return keyStore; }
        public void setKeyStore(String v) { keyStore = v; }
        public String getKeyStorePasswordRef() { return keyStorePasswordRef; }
        public void setKeyStorePasswordRef(String v) { keyStorePasswordRef = v; }
        public String getTrustStore() { return trustStore; }
        public void setTrustStore(String v) { trustStore = v; }
        public String getTrustStorePasswordRef() { return trustStorePasswordRef; }
        public void setTrustStorePasswordRef(String v) { trustStorePasswordRef = v; }
    }

    public static class Response {
        private Map<String, String> output = new LinkedHashMap<>();
        private String providerRef;

        public Map<String, String> getOutput() { return output; }
        public void setOutput(Map<String, String> v) { output = v; }
        public String getProviderRef() { return providerRef; }
        public void setProviderRef(String v) { providerRef = v; }
    }
}
