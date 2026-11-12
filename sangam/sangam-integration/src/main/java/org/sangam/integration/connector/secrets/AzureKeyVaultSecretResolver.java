// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import java.net.URI;
import java.time.Clock;
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
import org.sangam.integration.connector.http.HttpRestConnector;
import org.sangam.integration.connector.http.OAuth2ClientCredentials;

/**
 * Azure Key Vault secrets over plain HTTP (M-075; no Azure SDK). The access token comes from Microsoft Entra ID with the
 * OAuth 2.0 client-credentials grant (the shared {@link OAuth2ClientCredentials}, scope
 * {@value #SCOPE}, client secret as form fields); the secret is {@code GET <vault>/secrets/<name>?api-version=}
 * {@value #API_VERSION} and its {@code value} is returned.
 *
 * <p>Key Vault names allow only letters, digits and {@code -}: {@code secret://a/b} becomes the name {@code a--b}
 * (segments joined with {@code --}). A reference with a segment that has another character (a dot, an underscore)
 * cannot exist in Key Vault and is "not found". 404 is "not found"; any other error answer is an error. The client
 * secret is a reference read from the bootstrap resolver.
 */
public final class AzureKeyVaultSecretResolver implements SecretResolver {

    public static final String SCOPE = "https://vault.azure.net/.default";
    public static final String API_VERSION = "7.4";
    private static final Pattern NAME_SEGMENT = Pattern.compile("[A-Za-z0-9-]+");

    /**
     * @param vaultUrl        e.g. {@code https://my-vault.vault.azure.net}
     * @param tenantId        Entra ID tenant (directory) id
     * @param clientId        application (client) id
     * @param clientSecretRef reference of the client secret in the bootstrap resolver
     * @param authority       null = {@code https://login.microsoftonline.com} (sovereign clouds set their own)
     */
    public record Settings(URI vaultUrl, String tenantId, String clientId, String clientSecretRef, URI authority) {
        public Settings {
            Objects.requireNonNull(vaultUrl, "sangam.secrets.azure.vault-url");
            if (tenantId == null || tenantId.isBlank() || clientId == null || clientId.isBlank()) {
                throw new IllegalStateException("sangam.secrets.azure needs tenant-id and client-id");
            }
            authority = authority == null ? URI.create("https://login.microsoftonline.com") : authority;
        }

        URI tokenUri() {
            return URI.create(authority.toString().replaceAll("/+$", "") + "/" + tenantId + "/oauth2/v2.0/token");
        }
    }

    private final Settings settings;
    private final HttpTransport transport;
    private final HttpRestConnector.Authorizer token;

    public AzureKeyVaultSecretResolver(Settings settings, SecretResolver bootstrap, boolean production) {
        this(settings, bootstrap, ProviderCalls.transport(List.of(ProviderCalls.origin(settings.vaultUrl()),
                ProviderCalls.origin(settings.authority())), false, production, null));
    }

    AzureKeyVaultSecretResolver(Settings settings, SecretResolver bootstrap, HttpTransport transport) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.token = new OAuth2ClientCredentials(settings.tokenUri(), settings.clientId(),
                ProviderCalls.bootstrap(bootstrap, "sangam.secrets.azure.client-secret-ref", settings.clientSecretRef()),
                SCOPE, transport, Clock.systemUTC(), true);
    }

    @Override
    public Optional<String> resolve(String reference) {
        Optional<String> name = SecretRef.parse(reference).flatMap(r -> r.joined("--", NAME_SEGMENT));
        if (name.isEmpty() || name.get().length() > 127) {
            return Optional.empty();
        }
        String authorization;
        try {
            authorization = token.authorization(ProviderCalls.PLATFORM);
        } catch (HttpRestConnector.AuthUnavailableException e) {
            throw new IllegalStateException("azure key vault: no access token (" + e.getMessage() + ")");
        }
        URI uri = URI.create(settings.vaultUrl().toString().replaceAll("/+$", "") + "/secrets/" + name.get()
                + "?api-version=" + API_VERSION);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", authorization);
        h.put("Accept", "application/json");
        HttpTransport.Outcome o = transport.execute(ProviderCalls.PLATFORM, HttpTransport.Call.get(uri, h), 0);
        if (o instanceof HttpTransport.Outcome.Response r && r.status() == 404) {
            return Optional.empty();
        }
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            throw ProviderCalls.failure("azure key vault", o);
        }
        JsonNode tree = Json.tree(r.body());
        JsonNode v = tree == null ? null : tree.get("value");
        return v == null || !v.isTextual() ? Optional.empty() : Optional.of(v.asText());
    }
}
