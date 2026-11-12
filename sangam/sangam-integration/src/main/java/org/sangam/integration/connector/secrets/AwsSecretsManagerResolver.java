// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.secrets;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.http.AwsRequestSigner;
import org.sangam.ports.events.channel.AwsSigV4;

/**
 * AWS Secrets Manager over plain HTTP (M-075; no AWS SDK): {@code GetSecretValue} as a JSON 1.1 call
 * ({@code X-Amz-Target: secretsmanager.GetSecretValue}) signed with the product's one SigV4 signer ({@link AwsSigV4}, through {@link AwsRequestSigner}) (service {@code secretsmanager}).
 *
 * <p>{@code secret://a/b} reads the secret named {@code <namePrefix>a/b} (Secrets Manager names allow {@code /}) and
 * returns its {@code SecretString}. A {@code ResourceNotFoundException} is "not found"; a binary-only secret, any other
 * error answer or a transport failure is an error. The access key, secret key and optional session token are
 * references read from the bootstrap resolver (a mounted file or the environment) on every read.
 */
public final class AwsSecretsManagerResolver implements SecretResolver {

    public static final String SERVICE = "secretsmanager";

    /**
     * @param region          AWS region, e.g. {@code ap-south-1}
     * @param endpoint        override (a VPC endpoint); null = {@code https://secretsmanager.<region>.amazonaws.com}
     * @param namePrefix      prefix of every secret name (e.g. {@code sangam/prod/}); may be empty
     * @param accessKeyRef    reference of the access key id in the bootstrap resolver
     * @param secretKeyRef    reference of the secret access key
     * @param sessionTokenRef reference of the session token (temporary credentials); may be null
     */
    public record Settings(String region, URI endpoint, String namePrefix, String accessKeyRef, String secretKeyRef,
                           String sessionTokenRef) {
        public Settings {
            if (region == null || region.isBlank()) {
                throw new IllegalStateException("sangam.secrets.aws.region is required");
            }
            endpoint = endpoint == null ? URI.create("https://secretsmanager." + region.trim() + ".amazonaws.com") : endpoint;
            namePrefix = namePrefix == null ? "" : namePrefix;
        }
    }

    private final Settings settings;
    private final SecretResolver bootstrap;
    private final HttpTransport transport;
    private final Clock clock;

    public AwsSecretsManagerResolver(Settings settings, SecretResolver bootstrap, boolean production) {
        this(settings, bootstrap, ProviderCalls.transport(List.of(ProviderCalls.origin(settings.endpoint())), false,
                production, null), Clock.systemUTC());
    }

    AwsSecretsManagerResolver(Settings settings, SecretResolver bootstrap, HttpTransport transport, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        credentials();   // a missing bootstrap credential fails at start-up
    }

    @Override
    public Optional<String> resolve(String reference) {
        Optional<SecretRef> ref = SecretRef.parse(reference);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        byte[] body = Json.bytes(Map.of("SecretId", settings.namePrefix() + ref.get().path()));
        URI uri = URI.create(settings.endpoint().toString().replaceAll("/+$", "") + "/");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-amz-json-1.1");
        h.put("X-Amz-Target", "secretsmanager.GetSecretValue");
        h.putAll(AwsRequestSigner.sign("POST", uri, h, body, settings.region().trim(), SERVICE, credentials(), clock.instant()));
        HttpTransport.Outcome o = transport.execute(ProviderCalls.PLATFORM, HttpTransport.Call.post(uri, h, body, true), 0);
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            throw ProviderCalls.failure("aws secrets manager", o);
        }
        if (!r.is2xx()) {
            String type = r.header("x-amzn-ErrorType").orElse("") + " " + new String(r.body(), StandardCharsets.UTF_8);
            if (r.status() == 400 && type.contains("ResourceNotFoundException")) {
                return Optional.empty();
            }
            throw ProviderCalls.failure("aws secrets manager", o);
        }
        JsonNode tree = Json.tree(r.body());
        JsonNode v = tree == null ? null : tree.get("SecretString");
        if (v == null || !v.isTextual()) {
            throw new IllegalStateException("aws secrets manager: secret for " + reference
                    + " has no SecretString (binary secrets are not supported)");
        }
        return Optional.of(v.asText());
    }

    private AwsSigV4.Credentials credentials() {
        String session = settings.sessionTokenRef() == null || settings.sessionTokenRef().isBlank() ? null
                : ProviderCalls.bootstrap(bootstrap, "sangam.secrets.aws.session-token-ref", settings.sessionTokenRef());
        return new AwsSigV4.Credentials(
                ProviderCalls.bootstrap(bootstrap, "sangam.secrets.aws.access-key-ref", settings.accessKeyRef()),
                ProviderCalls.bootstrap(bootstrap, "sangam.secrets.aws.secret-key-ref", settings.secretKeyRef()), session);
    }
}
