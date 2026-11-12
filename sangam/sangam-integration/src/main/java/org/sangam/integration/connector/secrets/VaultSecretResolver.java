// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;

/**
 * HashiCorp Vault (or OpenBao) KV version 2 over plain HTTP (M-075; no client library).
 *
 * <p>{@code secret://a/b/c} reads the secret at path {@code a/b} and returns its field {@code c}; a one-segment reference
 * {@code secret://x} reads path {@code x}, field {@value #DEFAULT_FIELD}. The request is
 * {@code GET <url>/v1/<mount>/data/<pathPrefix><path>} with {@code X-Vault-Token} (and {@code X-Vault-Namespace} when
 * set); the answer's {@code data.data.<field>} must be a string. 404, or a missing field, is "not found"; any other
 * answer or a transport failure is an error. The Vault token is itself a reference read from the bootstrap resolver
 * (a mounted file or the environment) on every read, so a renewed token is used at once.
 */
public final class VaultSecretResolver implements SecretResolver {

    public static final String DEFAULT_FIELD = "value";

    /**
     * @param url          Vault address, e.g. {@code https://vault.internal:8200}
     * @param mount        KV v2 mount, default {@code secret}
     * @param pathPrefix   prefix added before the reference path (e.g. {@code sangam/prod}); may be empty
     * @param namespace    Vault Enterprise / OpenBao namespace; may be empty
     * @param tokenRef     reference of the Vault token in the bootstrap resolver
     * @param allowPrivate the Vault address may be a private address (an in-cluster Vault)
     */
    public record Settings(URI url, String mount, String pathPrefix, String namespace, String tokenRef,
                           boolean allowPrivate) {
        public Settings {
            Objects.requireNonNull(url, "vault url");
            mount = mount == null || mount.isBlank() ? "secret" : trimSlashes(mount);
            pathPrefix = pathPrefix == null ? "" : trimSlashes(pathPrefix);
        }
    }

    private final Settings settings;
    private final SecretResolver bootstrap;
    private final HttpTransport transport;

    public VaultSecretResolver(Settings settings, SecretResolver bootstrap, boolean production) {
        this(settings, bootstrap, ProviderCalls.transport(List.of(ProviderCalls.origin(settings.url())),
                settings.allowPrivate(), production, null));
    }

    VaultSecretResolver(Settings settings, SecretResolver bootstrap, HttpTransport transport) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.transport = Objects.requireNonNull(transport, "transport");
        ProviderCalls.bootstrap(bootstrap, "sangam.secrets.vault.token-ref", settings.tokenRef());
    }

    @Override
    public Optional<String> resolve(String reference) {
        Optional<SecretRef> parsed = SecretRef.parse(reference);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        SecretRef ref = parsed.get();
        String path = ref.segments().size() == 1 ? ref.path() : ref.parent();
        String field = ref.segments().size() == 1 ? DEFAULT_FIELD : ref.last();
        URI uri = URI.create(trimSlashes(settings.url().toString()) + "/v1/" + settings.mount() + "/data/"
                + (settings.pathPrefix().isEmpty() ? "" : settings.pathPrefix() + "/") + path);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Vault-Token", ProviderCalls.bootstrap(bootstrap, "sangam.secrets.vault.token-ref", settings.tokenRef()));
        if (settings.namespace() != null && !settings.namespace().isBlank()) {
            h.put("X-Vault-Namespace", settings.namespace());
        }
        h.put("Accept", "application/json");
        HttpTransport.Outcome o = transport.execute(ProviderCalls.PLATFORM, HttpTransport.Call.get(uri, h), 0);
        if (o instanceof HttpTransport.Outcome.Response r && r.status() == 404) {
            return Optional.empty();
        }
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            throw ProviderCalls.failure("vault", o);
        }
        JsonNode tree = Json.tree(r.body());
        JsonNode v = tree == null ? null : tree.path("data").path("data").path(field);
        return v == null || !v.isTextual() ? Optional.empty() : Optional.of(v.asText());
    }

    private static String trimSlashes(String s) {
        String t = s;
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
