// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.http.GcpTokens;
import org.sangam.integration.connector.http.HttpRestConnector;

/**
 * Google Cloud Secret Manager over plain HTTP (M-075; no Google SDK). The access token comes from the shared
 * {@link GcpTokens} (a service-account key held in the bootstrap resolver, JWT-bearer grant, scope
 * {@code cloud-platform}); the secret is {@code GET <endpoint>/v1/projects/<project>/secrets/<name>/versions/latest:access}
 * and its base64 {@code payload.data} is returned as UTF-8.
 *
 * <p>Secret ids allow letters, digits, {@code _} and {@code -}: {@code secret://a/b} becomes {@code <namePrefix>a__b}
 * (segments joined with {@code __}); a segment with a dot cannot exist and is "not found". 404 is "not found".
 */
public final class GcpSecretManagerResolver implements SecretResolver {

    private static final Pattern ID_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * @param project              project id
     * @param serviceAccountKeyRef reference of the service-account key JSON in the bootstrap resolver
     * @param namePrefix           prefix of every secret id; may be empty
     * @param endpoint             null = {@code https://secretmanager.googleapis.com}
     */
    public record Settings(String project, String serviceAccountKeyRef, String namePrefix, URI endpoint) {
        public Settings {
            if (project == null || project.isBlank()) {
                throw new IllegalStateException("sangam.secrets.gcp.project is required");
            }
            namePrefix = namePrefix == null ? "" : namePrefix;
            endpoint = endpoint == null ? URI.create("https://secretmanager.googleapis.com") : endpoint;
        }
    }

    private final Settings settings;
    private final HttpTransport transport;
    private final GcpTokens tokens;

    public GcpSecretManagerResolver(Settings settings, SecretResolver bootstrap, boolean production) {
        this(settings, bootstrap, transportFor(settings, bootstrap, production));
    }

    GcpSecretManagerResolver(Settings settings, SecretResolver bootstrap, HttpTransport transport) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transport = Objects.requireNonNull(transport, "transport");
        if (settings.serviceAccountKeyRef() == null || settings.serviceAccountKeyRef().isBlank()) {
            // a key file is multi-line JSON, so it is not checked as a one-line credential; GcpTokens parses it
            throw new IllegalStateException("sangam.secrets.gcp.service-account-key-ref is required");
        }
        this.tokens = GcpTokens.serviceAccount(settings.serviceAccountKeyRef(), bootstrap, transport, Clock.systemUTC());
    }

    private static HttpTransport transportFor(Settings s, SecretResolver bootstrap, boolean production) {
        if (s.serviceAccountKeyRef() == null || s.serviceAccountKeyRef().isBlank()) {
            throw new IllegalStateException("sangam.secrets.gcp.service-account-key-ref is required");
        }
        List<URI> origins = new ArrayList<>();
        origins.add(ProviderCalls.origin(s.endpoint()));
        URI tokenUri;
        try {
            tokenUri = GcpTokens.serviceAccount(s.serviceAccountKeyRef(), bootstrap, HttpTransport.forSimulator(), null)
                    .tokenEndpoint();
        } catch (HttpRestConnector.AuthUnavailableException e) {
            throw new IllegalStateException("sangam.secrets.gcp.service-account-key-ref: " + e.getMessage());
        }
        origins.add(ProviderCalls.origin(tokenUri));
        return ProviderCalls.transport(origins, false, production, null);
    }

    @Override
    public Optional<String> resolve(String reference) {
        Optional<String> id = SecretRef.parse(reference).flatMap(r -> r.joined("__", ID_SEGMENT))
                .map(n -> settings.namePrefix() + n);
        if (id.isEmpty() || id.get().length() > 255) {
            return Optional.empty();
        }
        String token;
        try {
            token = tokens.accessToken(ProviderCalls.PLATFORM, GcpTokens.CLOUD_PLATFORM_SCOPE);
        } catch (HttpRestConnector.AuthUnavailableException e) {
            throw new IllegalStateException("gcp secret manager: no access token (" + e.getMessage() + ")");
        }
        URI uri = URI.create(settings.endpoint().toString().replaceAll("/+$", "") + "/v1/projects/" + settings.project()
                + "/secrets/" + id.get() + "/versions/latest:access");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Accept", "application/json");
        HttpTransport.Outcome o = transport.execute(ProviderCalls.PLATFORM, HttpTransport.Call.get(uri, h), 0);
        if (o instanceof HttpTransport.Outcome.Response r && r.status() == 404) {
            return Optional.empty();
        }
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            throw ProviderCalls.failure("gcp secret manager", o);
        }
        JsonNode tree = Json.tree(r.body());
        JsonNode data = tree == null ? null : tree.path("payload").get("data");
        if (data == null || !data.isTextual()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new String(Base64.getDecoder().decode(data.asText()), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("gcp secret manager: payload of " + reference + " is not base64");
        }
    }
}
