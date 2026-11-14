// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Metrics;

import org.sangam.rest.adapter.jdbc.DenialAudit;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.web.CommandCatalog;
import org.sangam.security.policy.external.ExternalPolicyCommands;
import org.sangam.security.policy.external.ExternalPolicyGate;
import org.sangam.security.policy.external.HttpExternalPolicyCheck;
import org.sangam.server.bridge.ExternalPolicyFilter;
import org.sangam.server.bridge.ExternalPolicyTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Owner decision 166 (M-083): the external deny-only policy check at command entry, off by default. With
 * {@code sangam.security.external-policy.enabled=true} the server registers {@link ExternalPolicyFilter} between the
 * deprecation filter and the {@code IdempotencyFilter} ({@link #FILTER_ORDER}), so the remote call is made after
 * authentication and before any idempotency claim, command transaction or L2 lock (decision 86).
 *
 * <p>Keys (prefix {@value #PREFIX}):
 * <ul>
 *   <li>{@code enabled}: false by default; without it nothing here is created and no command is checked;</li>
 *   <li>{@code endpoint}: the policy service URL, https (required);</li>
 *   <li>{@code allow-plain-http}: accept an http endpoint (loopback or mesh sidecar); refused in production
 *       ({@link ProductionEnvironment#isProduction});</li>
 *   <li>{@code connect-timeout} (default 1 s) and {@code timeout} (overall deadline of one check, default 2 s), each
 *       above zero and at most 60 s;</li>
 *   <li>{@code required-commands}: command types (or {@code *}) that fail closed when the service is unavailable;
 *       default {@code *} (every command), unless {@code best-effort-commands} holds {@code *};</li>
 *   <li>{@code best-effort-commands}: command types (or {@code *}) that continue when the service is unavailable
 *       (a DENY still refuses them); default none;</li>
 *   <li>{@code token-file}: a file holding the bearer token for the service, read at each call (rotation without
 *       restart); its content is never logged;</li>
 *   <li>{@code retry-after-seconds}: {@code Retry-After} of {@code 503 POLICY_UNAVAILABLE}, default 5.</li>
 * </ul>
 * Command types are those of the contract's command catalogue, the external-worker calls ({@code work.complete},
 * {@code work.fetch-and-lock}, ...) and {@code engine-rest} for every state-changing Operaton REST call (decision 200,
 * {@link ExternalPolicyTargets}); {@code *} covers all of them. An unknown name, a name in both lists or {@code *} in
 * both lists stops the start-up. Production behaviour is never weaker than without this feature: when it is off nothing
 * changes, and when it is on it can only refuse.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = ExternalPolicyWiring.PREFIX, name = "enabled", havingValue = "true")
public class ExternalPolicyWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalPolicyWiring.class);

    public static final String PREFIX = "sangam.security.external-policy";

    /** After the deprecation filter (90), before the idempotency filter (100). */
    public static final int FILTER_ORDER = SangamRestAutoConfiguration.IDEMPOTENCY_FILTER_ORDER - 5;

    /** Decision 200: REST commands, external-worker calls and /engine-rest, all from the contract plus one fixed type. */
    @Bean
    public ExternalPolicyTargets sangamExternalPolicyTargets(CommandCatalog catalog) {
        return new ExternalPolicyTargets(catalog);
    }

    @Bean
    public ExternalPolicyGate sangamExternalPolicyGate(Environment env, ExternalPolicyTargets sangamExternalPolicyTargets) {
        HttpExternalPolicyCheck.Settings settings = settings(env);
        ExternalPolicyCommands commands = commands(env, sangamExternalPolicyTargets.commandTypes());
        String tokenFile = env.getProperty(PREFIX + ".token-file");
        Supplier<String> token = tokenFile == null || tokenFile.isBlank() ? null : tokenFile(Path.of(tokenFile.strip()));
        LOG.info("decision 166: external deny-only policy check on, service {} ({}), credential {}",
                settings.origin(), commands.none() ? "no command checked" : "per-command requirement applies",
                token == null ? "none" : "from a token file");
        return new ExternalPolicyGate(new HttpExternalPolicyCheck(settings, token), commands, ExternalPolicyWiring::observe);
    }

    @Bean
    public FilterRegistrationBean<ExternalPolicyFilter> sangamExternalPolicyFilter(Environment env,
            ExternalPolicyTargets sangamExternalPolicyTargets, ExternalPolicyGate sangamExternalPolicyGate,
            DenialAudit sangamRestDenialAudit) {
        int retryAfter = env.getProperty(PREFIX + ".retry-after-seconds", Integer.class, 5);
        FilterRegistrationBean<ExternalPolicyFilter> reg = new FilterRegistrationBean<>(
                new ExternalPolicyFilter(sangamExternalPolicyTargets, sangamExternalPolicyGate,
                        sangamRestDenialAudit::record, retryAfter));
        // decision 200: Sangam REST (commands and worker calls) and Operaton REST
        reg.addUrlPatterns(CommandCatalog.BASE + "/*", ExternalPolicyTargets.ENGINE_REST_PATH + "/*");
        reg.setOrder(FILTER_ORDER);
        reg.setName("sangamExternalPolicyFilter");
        return reg;
    }

    static HttpExternalPolicyCheck.Settings settings(Environment env) {
        String endpoint = env.getProperty(PREFIX + ".endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(PREFIX + ".endpoint is required when " + PREFIX + ".enabled=true");
        }
        boolean plainHttp = env.getProperty(PREFIX + ".allow-plain-http", Boolean.class, false);
        if (plainHttp && ProductionEnvironment.isProduction(env)) {
            throw new IllegalStateException(PREFIX + ".allow-plain-http is refused in production; use an https endpoint");
        }
        URI uri;
        try {
            uri = URI.create(endpoint.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PREFIX + ".endpoint is not a URL");
        }
        if (uri.getScheme() != null && uri.getScheme().toLowerCase(Locale.ROOT).equals("http") && !plainHttp) {
            throw new IllegalStateException(PREFIX + ".endpoint uses http; set " + PREFIX
                    + ".allow-plain-http=true (not in production) or use https");
        }
        Binder binder = Binder.get(env);
        try {
            return new HttpExternalPolicyCheck.Settings(uri,
                    binder.bind(PREFIX + ".connect-timeout", Duration.class).orElse(Duration.ofSeconds(1)),
                    binder.bind(PREFIX + ".timeout", Duration.class).orElse(Duration.ofSeconds(2)), plainHttp);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PREFIX + ": " + e.getMessage());
        }
    }

    static ExternalPolicyCommands commands(Environment env, CommandCatalog catalog) {
        return commands(env, new ExternalPolicyTargets(catalog).commandTypes());
    }

    /** @param known every command type the check can name (catalogue commands, work.*, engine-rest; decision 200) */
    static ExternalPolicyCommands commands(Environment env, java.util.Collection<String> known) {
        Binder binder = Binder.get(env);
        List<String> bestEffort = binder.bind(PREFIX + ".best-effort-commands", Bindable.listOf(String.class))
                .orElse(List.of());
        List<String> required = binder.bind(PREFIX + ".required-commands", Bindable.listOf(String.class))
                .orElseGet(() -> bestEffort.contains(ExternalPolicyCommands.ALL) ? List.of()
                        : List.of(ExternalPolicyCommands.ALL));
        try {
            return ExternalPolicyCommands.of(known, required, bestEffort);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PREFIX + ": " + e.getMessage());
        }
    }

    /** Reads the token at each call; the path may be logged, the content never. */
    static Supplier<String> tokenFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(PREFIX + ".token-file does not name a readable file");
        }
        return () -> {
            try {
                return Files.readString(file, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new UncheckedIOException("the external policy token file cannot be read", e);
            }
        };
    }

    static void observe(String commandType, ExternalPolicyGate.Result result, Duration elapsed) {
        String r = result.name().toLowerCase(Locale.ROOT);
        Metrics.counter("sangam.security.external.policy.checks", "command", commandType, "result", r).increment();
        if (result != ExternalPolicyGate.Result.SKIPPED) {
            Metrics.timer("sangam.security.external.policy.latency", "result", r).record(elapsed);
        }
    }
}
