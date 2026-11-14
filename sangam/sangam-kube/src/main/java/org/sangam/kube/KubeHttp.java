// SPDX-License-Identifier: Apache-2.0
package org.sangam.kube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * {@link Kube} over the plain Kubernetes REST API with the JDK HTTP client (no Kubernetes client library; M-090, owner
 * decision 155: one shared client for the operator and the server's backup console).
 *
 * <p>In a pod ({@link #inCluster(String)}) the API server is
 * {@code https://$KUBERNETES_SERVICE_HOST:$KUBERNETES_SERVICE_PORT}, trusted through the service-account CA
 * ({@code ca.crt}), and every request carries the service-account token. The token file is read again for every
 * request, because bound service-account tokens are rotated by the kubelet. The token is never logged.
 */
public final class KubeHttp implements Kube {

    /** Sends HTTP. Replaced in tests. */
    public interface Http {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    static final Path SA_DIR = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ERROR_CHARS = 500;

    private final Http http;
    private final String base;
    private final Supplier<String> token;
    private final String fieldManager;

    /** {@code fieldManager} names the writer in server-side apply (for example {@code sangam-operator}). */
    public KubeHttp(Http http, String base, Supplier<String> token, String fieldManager) {
        if (fieldManager == null || fieldManager.isBlank()) {
            throw new IllegalArgumentException("a field manager name is required");
        }
        this.http = http;
        this.base = base.replaceAll("/+$", "");
        this.token = token;
        this.fieldManager = fieldManager;
    }

    /** The client for the pod's own service account. */
    public static KubeHttp inCluster(String fieldManager) throws IOException {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        if (host == null || host.isBlank() || port == null || port.isBlank()) {
            throw new IOException("not in a pod: KUBERNETES_SERVICE_HOST / KUBERNETES_SERVICE_PORT are not set");
        }
        HttpClient client = HttpClient.newBuilder().sslContext(trusting(SA_DIR.resolve("ca.crt")))
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        String h = host.contains(":") ? "[" + host + "]" : host;   // IPv6 service address
        Path tokenFile = SA_DIR.resolve("token");
        return new KubeHttp(r -> client.send(r, HttpResponse.BodyHandlers.ofString()), "https://" + h + ":" + port,
                () -> readToken(tokenFile), fieldManager);
    }

    /** The namespace of the pod's service account. */
    public static String podNamespace() throws IOException {
        return Files.readString(SA_DIR.resolve("namespace"), StandardCharsets.UTF_8).trim();
    }

    static String readToken(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the service-account token", e);
        }
    }

    /** An SSL context that trusts exactly the certificates in the PEM file (the cluster CA). */
    static SSLContext trusting(Path caPem) throws IOException {
        try (InputStream in = Files.newInputStream(caPem)) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
            if (i == 0) {
                throw new IOException("no certificate in " + caPem);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot load the cluster CA " + caPem, e);
        }
    }

    @Override
    public JsonNode get(String path) throws IOException {
        HttpResponse<String> r = send(request(path).GET());
        if (r.statusCode() == 404) {
            return null;
        }
        return body(r);
    }

    @Override
    public JsonNode list(String path) throws IOException {
        JsonNode n = body(send(request(path).GET()));
        return n.path("items");
    }

    @Override
    public JsonNode create(String collectionPath, ObjectNode body) throws IOException {
        HttpResponse<String> r = send(request(collectionPath).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
        if (r.statusCode() == 409) {
            return null;
        }
        return body(r);
    }

    @Override
    public JsonNode apply(String path, ObjectNode body) throws IOException {
        // Server-side apply accepts JSON, which is valid YAML, under the apply-patch content type.
        String q = (path.contains("?") ? "&" : "?") + "fieldManager=" + URLEncoder.encode(fieldManager, StandardCharsets.UTF_8)
                + "&force=true";
        return body(send(request(path + q).header("Content-Type", "application/apply-patch+yaml")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))));
    }

    @Override
    public JsonNode patchStatus(String path, ObjectNode status) throws IOException {
        ObjectNode patch = JSON.createObjectNode();
        patch.set("status", status);
        return body(send(request(path + "/status").header("Content-Type", "application/merge-patch+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(patch)))));
    }

    private HttpRequest.Builder request(String path) {
        if (!path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("API path must be absolute: " + path);
        }
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT).header("Accept", "application/json")
                .header("Authorization", "Bearer " + token.get());
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws IOException {
        try {
            return http.send(b.build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static JsonNode body(HttpResponse<String> r) throws IOException {
        int s = r.statusCode();
        if (s < 200 || s >= 300) {
            String b = r.body() == null ? "" : r.body();
            String message = b;
            try {
                JsonNode n = JSON.readTree(b);
                if (n != null && n.hasNonNull("message")) {
                    message = n.get("message").asText();
                }
            } catch (IOException ignored) {
                // not a Status object: keep the raw text
            }
            throw new KubeException(s, message.length() > MAX_ERROR_CHARS ? message.substring(0, MAX_ERROR_CHARS) : message);
        }
        String b = r.body();
        return b == null || b.isBlank() ? JSON.createObjectNode() : JSON.readTree(b);
    }
}
