// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.sangam.rest.web.CommandCatalog;
import org.sangam.security.policy.external.ExternalPolicyCommands;
import org.sangam.security.policy.external.ExternalPolicyGate;
import org.sangam.security.policy.external.ExternalPolicyRequirement;
import org.sangam.security.policy.external.HttpExternalPolicyCheck;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * Owner decision 166 (M-083) wiring: off by default (no bean, so no filter and no remote call), the filter sits after
 * Spring Security and the deprecation filter and before the idempotency filter, the endpoint must be https (plain http
 * only outside production and only when asked for), timeouts are bounded, the default makes every command required
 * (fail closed), and a misspelt or ambiguous command list stops the start-up. Written for the test phase (not run
 * here).
 */
class ExternalPolicyWiringTest {

    static final String P = ExternalPolicyWiring.PREFIX;

    static CommandCatalog catalog() {
        Map<String, Object> complete = Map.of("x-sangam-command-type", "task.complete", "x-sangam-idempotent", true);
        Map<String, Object> start = Map.of("x-sangam-command-type", "process.start", "x-sangam-idempotent", true);
        return new CommandCatalog(Map.of("paths", Map.of(
                "/tasks/{taskId}/complete", Map.of("post", complete),
                "/processes", Map.of("post", start))));
    }

    @Test
    void offByDefaultNothingIsCreatedSoNoCommandIsChecked() {
        ConditionalOnProperty c = ExternalPolicyWiring.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(c);
        assertEquals(P, c.prefix());
        assertEquals("true", c.havingValue());
        assertFalse(c.matchIfMissing());
        new ApplicationContextRunner().withUserConfiguration(ExternalPolicyWiring.class)
                .withBean(CommandCatalog.class, ExternalPolicyWiringTest::catalog)
                .run(ctx -> assertTrue(ctx.getBeansOfType(ExternalPolicyGate.class).isEmpty()));
        new ApplicationContextRunner().withUserConfiguration(ExternalPolicyWiring.class)
                .withBean(CommandCatalog.class, ExternalPolicyWiringTest::catalog)
                .withPropertyValues(P + ".enabled=false", P + ".endpoint=https://pdp.example/v1/data/sangam")
                .run(ctx -> assertTrue(ctx.getBeansOfType(ExternalPolicyGate.class).isEmpty()));
    }

    @Test
    void enabledWithoutAnEndpointStopsTheStartUp() {
        new ApplicationContextRunner().withUserConfiguration(ExternalPolicyWiring.class)
                .withBean(CommandCatalog.class, ExternalPolicyWiringTest::catalog)
                .withPropertyValues(P + ".enabled=true")
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure());
                    Throwable root = ctx.getStartupFailure();
                    StringBuilder messages = new StringBuilder();
                    for (Throwable t = root; t != null; t = t.getCause()) {
                        messages.append(t.getMessage()).append('\n');
                    }
                    assertTrue(messages.toString().contains(P + ".endpoint is required"), messages.toString());
                });
    }

    @Test
    void theFilterRunsAfterAuthenticationAndBeforeTheIdempotencyClaim() {
        // Boot 4: the security filter chain order constant is on SecurityFilterProperties (-100)
        assertTrue(ExternalPolicyWiring.FILTER_ORDER > SecurityFilterProperties.DEFAULT_FILTER_ORDER);
        assertTrue(ExternalPolicyWiring.FILTER_ORDER > SangamRestAutoConfiguration.IDEMPOTENCY_FILTER_ORDER - 10,
                "after the deprecation filter, so a retired call is answered 410 without a policy call");
        assertTrue(ExternalPolicyWiring.FILTER_ORDER < SangamRestAutoConfiguration.IDEMPOTENCY_FILTER_ORDER);
    }

    @Test
    void theEndpointMustBeHttpsAndPlainHttpIsRefusedInProduction() {
        assertThrows(IllegalStateException.class, () -> ExternalPolicyWiring.settings(new MockEnvironment()
                .withProperty(P + ".endpoint", "http://pdp:8181/v1/data/sangam")));
        HttpExternalPolicyCheck.Settings dev = ExternalPolicyWiring.settings(new MockEnvironment()
                .withProperty("sangam.security.production", "false")
                .withProperty(P + ".endpoint", "http://127.0.0.1:8181/v1/data/sangam")
                .withProperty(P + ".allow-plain-http", "true"));
        assertTrue(dev.allowPlainHttp());
        assertThrows(IllegalStateException.class, () -> ExternalPolicyWiring.settings(new MockEnvironment()
                .withProperty("sangam.security.production", "true")
                .withProperty(P + ".endpoint", "http://127.0.0.1:8181/v1/data/sangam")
                .withProperty(P + ".allow-plain-http", "true")));
        assertThrows(IllegalStateException.class, () -> ExternalPolicyWiring.settings(new MockEnvironment()
                .withProperty(P + ".endpoint", "https://pdp.example/v1").withProperty(P + ".timeout", "0s")));
    }

    @Test
    void timeoutsHaveDefaultsAndReadTheBootFormat() {
        HttpExternalPolicyCheck.Settings d = ExternalPolicyWiring.settings(new MockEnvironment()
                .withProperty(P + ".endpoint", "https://pdp.example/v1/data/sangam"));
        assertEquals(Duration.ofSeconds(1), d.connectTimeout());
        assertEquals(Duration.ofSeconds(2), d.timeout());
        HttpExternalPolicyCheck.Settings s = ExternalPolicyWiring.settings(new MockEnvironment()
                .withProperty(P + ".endpoint", "https://pdp.example/v1/data/sangam")
                .withProperty(P + ".connect-timeout", "300ms").withProperty(P + ".timeout", "750ms"));
        assertEquals(Duration.ofMillis(300), s.connectTimeout());
        assertEquals(Duration.ofMillis(750), s.timeout());
    }

    @Test
    void everyCommandIsRequiredByDefaultAndTheListsAreChecked() {
        ExternalPolicyCommands all = ExternalPolicyWiring.commands(new MockEnvironment(), catalog());
        assertEquals(ExternalPolicyRequirement.REQUIRED, all.requirement("task.complete"));
        assertEquals(ExternalPolicyRequirement.REQUIRED, all.requirement("process.start"));

        ExternalPolicyCommands mixed = ExternalPolicyWiring.commands(new MockEnvironment()
                .withProperty(P + ".best-effort-commands", "process.start"), catalog());
        assertEquals(ExternalPolicyRequirement.REQUIRED, mixed.requirement("task.complete"));
        assertEquals(ExternalPolicyRequirement.BEST_EFFORT, mixed.requirement("process.start"));

        ExternalPolicyCommands bestEffort = ExternalPolicyWiring.commands(new MockEnvironment()
                .withProperty(P + ".best-effort-commands", "*"), catalog());
        assertEquals(ExternalPolicyRequirement.BEST_EFFORT, bestEffort.requirement("task.complete"));

        ExternalPolicyCommands one = ExternalPolicyWiring.commands(new MockEnvironment()
                .withProperty(P + ".required-commands", "task.complete"), catalog());
        assertEquals(ExternalPolicyRequirement.NONE, one.requirement("process.start"));

        assertThrows(IllegalStateException.class, () -> ExternalPolicyWiring.commands(new MockEnvironment()
                .withProperty(P + ".required-commands", "task.compelte"), catalog()));
        assertThrows(IllegalStateException.class, () -> ExternalPolicyWiring.commands(new MockEnvironment()
                .withProperty(P + ".required-commands", "*").withProperty(P + ".best-effort-commands", "*"), catalog()));
    }

    @Test
    void theTokenFileIsReadAtEachCallAndMustExist(@TempDir Path dir) throws Exception {
        assertThrows(IllegalStateException.class, () -> ExternalPolicyWiring.tokenFile(dir.resolve("missing")));
        Path file = dir.resolve("token");
        Files.writeString(file, "first-test-value\n");
        var supplier = ExternalPolicyWiring.tokenFile(file);
        assertEquals("first-test-value", supplier.get());
        Files.writeString(file, "rotated-test-value");
        assertEquals("rotated-test-value", supplier.get());
    }
}
