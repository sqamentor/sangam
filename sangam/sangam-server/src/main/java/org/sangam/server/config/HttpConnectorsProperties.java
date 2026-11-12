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
 *   .auth.type=aws-sigv4|function-key|gcp-id-token   (M-067 serverless providers)
 *   .auth.region / .auth.service / .auth.access-key-ref / .auth.secret-key-ref / .auth.session-token-ref   (aws-sigv4)
 *   .auth.secret-ref / .auth.header-name (default x-functions-key)   (function-key)
 *   .auth.audience / .auth.service-account-key-ref or .auth.metadata-url   (gcp-id-token)
 *   .callback.url / .callback.event-type / .callback.placement=body|headers / .callback.field   (async invoke)
 *   .response.failure-header=X-Amz-Function-Error
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
        private Callback callback = new Callback();

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
        public Callback getCallback() { return callback; }
        public void setCallback(Callback v) { callback = v; }
    }

    /** M-067 async invoke callback. */
    public static class Callback {
        private String url;
        private String eventType;
        private String placement = "body";
        private String field;

        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public String getEventType() { return eventType; }
        public void setEventType(String v) { eventType = v; }
        public String getPlacement() { return placement; }
        public void setPlacement(String v) { placement = v; }
        public String getField() { return field; }
        public void setField(String v) { field = v; }
    }

    public static class Auth {
        private String type = "none";
        private String secretRef;
        private String tokenUrl;
        private String clientId;
        private String clientSecretRef;
        private String scope;
        private String region;
        private String service;
        private String accessKeyRef;
        private String secretKeyRef;
        private String sessionTokenRef;
        private String headerName;
        private String audience;
        private String serviceAccountKeyRef;
        private String metadataUrl;

        public String getRegion() { return region; }
        public void setRegion(String v) { region = v; }
        public String getService() { return service; }
        public void setService(String v) { service = v; }
        public String getAccessKeyRef() { return accessKeyRef; }
        public void setAccessKeyRef(String v) { accessKeyRef = v; }
        public String getSecretKeyRef() { return secretKeyRef; }
        public void setSecretKeyRef(String v) { secretKeyRef = v; }
        public String getSessionTokenRef() { return sessionTokenRef; }
        public void setSessionTokenRef(String v) { sessionTokenRef = v; }
        public String getHeaderName() { return headerName; }
        public void setHeaderName(String v) { headerName = v; }
        public String getAudience() { return audience; }
        public void setAudience(String v) { audience = v; }
        public String getServiceAccountKeyRef() { return serviceAccountKeyRef; }
        public void setServiceAccountKeyRef(String v) { serviceAccountKeyRef = v; }
        public String getMetadataUrl() { return metadataUrl; }
        public void setMetadataUrl(String v) { metadataUrl = v; }

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
        private String failureHeader;

        public String getFailureHeader() { return failureHeader; }
        public void setFailureHeader(String v) { failureHeader = v; }
        public Map<String, String> getOutput() { return output; }
        public void setOutput(Map<String, String> v) { output = v; }
        public String getProviderRef() { return providerRef; }
        public void setProviderRef(String v) { providerRef = v; }
    }
}
