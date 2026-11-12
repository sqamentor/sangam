// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-117: the compliance (and case/AI/field/ops/channel) schedule tenants are the explicit list plus the tenant
 * directory. A failed directory read used to be swallowed at DEBUG; with the explicit list resolving to empty (the
 * {@code ${sangam.tenant.default:dev}} fallback never applied, the property is always defined and empty) nothing was
 * scheduled and nothing was logged. The directory was also read in the system context of the raw, empty
 * {@code sangam.tenant.default}.
 */
class ScheduleTenantsTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(ScheduleTenants.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void aDirectoryFailureIsLoggedAtWarnWithItsCauseAndTheExplicitTenantsStillRun() {
        IllegalStateException cause = new IllegalStateException("directory down");
        MockEnvironment env = new MockEnvironment().withProperty("sangam.compliance.tenants", "RJ");
        List<String> out = compliance(env, provider(admin((ctx, page) -> {
            throw cause;
        })));

        assertEquals(List.of("RJ"), out);
        List<ILoggingEvent> warns = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warns.size(), "exactly one WARN per failed read: " + logs.list);
        ILoggingEvent warn = warns.get(0);
        assertTrue(warn.getFormattedMessage().contains("sangam.compliance.tenants"), warn.getFormattedMessage());
        assertTrue(warn.getFormattedMessage().contains("next cycle retries"), warn.getFormattedMessage());
        assertNotNull(warn.getThrowableProxy(), "the cause is logged");
        assertEquals("directory down", warn.getThrowableProxy().getMessage());
    }

    @Test
    void aFailedDirectoryIsRetriedOnTheNextRead() {
        AtomicInteger calls = new AtomicInteger();
        ObjectProvider<AdminPort> admin = provider(admin((ctx, page) -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("first read fails");
            }
            return Page.slice(List.of(tenant("RJ", "ACTIVE")), page, Long.MAX_VALUE);
        }));
        MockEnvironment env = new MockEnvironment();

        assertEquals(List.of(), compliance(env, admin));
        assertEquals(List.of("RJ"), compliance(env, admin), "nothing is cached: the next cycle retries");
    }

    @Test
    void theScheduleIsTheUnionOfTheExplicitListAndTheActiveDirectoryTenants() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.compliance.tenants", "RJ, extra,,  ");
        List<AdminPort.Tenant> directory = List.of(tenant("RJ", "ACTIVE"), tenant("MP", "DRAFT"),
                tenant("UP", "SUSPENDED"), tenant("GJ", "ACTIVE"));
        List<String> out = compliance(env, provider(admin((ctx, page) ->
                Page.slice(directory, page, Long.MAX_VALUE))));

        assertEquals(List.of("RJ", "extra", "MP", "GJ"), out, "deduplicated, suspended and blank entries dropped");
        assertTrue(logs.list.stream().noneMatch(e -> e.getLevel() == Level.WARN), logs.list::toString);
    }

    @Test
    void withoutAnExplicitListOrADirectoryTheScheduleIsEmptyAndDevIsNotInvented() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.compliance.tenants", "")
                .withProperty("sangam.tenant.default", "");
        assertEquals(List.of(), compliance(env, new DefaultListableBeanFactory().getBeanProvider(AdminPort.class)));
    }

    @Test
    void theDirectoryIsReadAsTheSystemPrincipalOfTheAttributionTenant() {
        List<RequestContext> seen = new ArrayList<>();
        ObjectProvider<AdminPort> admin = provider(admin((ctx, page) -> {
            seen.add(ctx);
            return Page.slice(List.of(), page, Long.MAX_VALUE);
        }));

        MockEnvironment unset = new MockEnvironment().withProperty("sangam.tenant.default", "");
        compliance(unset, admin);
        MockEnvironment rj = new MockEnvironment().withProperty("sangam.tenant.default", " RJ ");
        compliance(rj, admin);

        assertEquals(2, seen.size());
        assertEquals(AttributionTenant.resolve(unset), seen.get(0).tenantId(), "never the raw, empty property");
        assertEquals("default", seen.get(0).tenantId());
        assertEquals(AttributionTenant.resolve(rj), seen.get(1).tenantId());
        assertEquals("RJ", seen.get(1).tenantId());
        assertEquals("system:compliance", seen.get(0).principalId());
        assertEquals("SYSTEM", seen.get(0).authMethod());
        assertEquals("default", ScheduleTenants.systemContext(unset, "system:scheduler").tenantId());
    }

    @Test
    void aReservedHomeTenantIsADirectoryFailureThatIsLogged() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.tenant.default", "-")
                .withProperty("sangam.compliance.tenants", "RJ");
        List<String> out = compliance(env, provider(admin((ctx, page) ->
                Page.slice(List.of(tenant("MP", "ACTIVE")), page, Long.MAX_VALUE))));
        assertEquals(List.of("RJ"), out);
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN), logs.list::toString);
    }

    @Test
    void everyDirectoryPageIsRead() {
        List<AdminPort.Tenant> directory = new ArrayList<>();
        for (int i = 0; i < ScheduleTenants.PAGE_SIZE + 3; i++) {
            directory.add(tenant(String.format("T%04d", i), "ACTIVE"));
        }
        List<String> out = caseTenants(new MockEnvironment(), provider(admin((ctx, page) ->
                Page.slice(directory, page, 100))), "sangam.field.tenants");
        assertEquals(ScheduleTenants.PAGE_SIZE + 3, out.size(), "not only the first page");
    }

    @Test
    void anAdapterThatIgnoresThePageIndexDoesNotLoopForever() {
        List<AdminPort.Tenant> directory = new ArrayList<>();
        for (int i = 0; i < ScheduleTenants.PAGE_SIZE; i++) {
            directory.add(tenant("T" + i, "ACTIVE"));
        }
        AtomicInteger calls = new AtomicInteger();
        List<String> out = ScheduleTenants.directory(admin((ctx, page) -> {
            calls.incrementAndGet();
            return Page.of(directory, directory.size(), false);
        }), ScheduleTenants.systemContext(new MockEnvironment(), "system:scheduler"));
        assertEquals(ScheduleTenants.PAGE_SIZE, out.size());
        assertEquals(2, calls.get(), "the second page added nothing new");
    }

    @Test
    void caseWiringUsesTheSameReader() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.ops.tenants", "RJ");
        List<String> out = caseTenants(env, provider(admin((ctx, page) -> {
            throw new IllegalStateException("down");
        })), "sangam.ops.tenants");
        assertEquals(List.of("RJ"), out);
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("sangam.ops.tenants")), logs.list::toString);
    }

    @Test
    void applicationYamlHasNoMisleadingDevFallback() throws Exception {
        String yaml;
        try (InputStream in = ScheduleTenantsTest.class.getResourceAsStream("/application.yaml")) {
            assertNotNull(in, "application.yaml on the classpath");
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(yaml.contains("tenants: ${SANGAM_COMPLIANCE_TENANTS:}"), "explicit list optional, no fallback");
        assertFalse(yaml.contains("${SANGAM_COMPLIANCE_TENANTS:${"), "no nested fallback to sangam.tenant.default/dev");
    }

    @Test
    void providerHelperReturnsThePort() {
        AdminPort port = admin((ctx, page) -> Page.of(List.of(), 0, false));
        assertSame(port, provider(port).getIfAvailable());
    }

    @Test
    void aPersistingDirectoryFailureWarnsAtOnceThenAtMostEveryIntervalIsAlwaysCountedAndRecoveryLogsInfo() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScheduleTenantsMonitorTest.MutableClock clock = new ScheduleTenantsMonitorTest.MutableClock(Instant.parse("2026-11-12T00:00:00Z"));
        ScheduleTenantsMonitor monitor = new ScheduleTenantsMonitor("ai-worker", "x", meters, Duration.ofMinutes(5), clock);
        AtomicBoolean down = new AtomicBoolean(true);
        ObjectProvider<AdminPort> admin = provider(admin((ctx, page) -> {
            if (down.get()) {
                throw new IllegalStateException("directory down");
            }
            return Page.slice(List.of(tenant("RJ", "ACTIVE")), page, Long.MAX_VALUE);
        }));
        MockEnvironment env = new MockEnvironment().withProperty("sangam.ai.worker.tenants", "MP");

        assertEquals(List.of("MP"), CaseWiring.tenants(env, admin, "sangam.ai.worker.tenants", monitor)); // first: WARN
        clock.now = clock.now.plusSeconds(5);
        CaseWiring.tenants(env, admin, "sangam.ai.worker.tenants", monitor);                             // 5 s: no WARN
        clock.now = clock.now.plusSeconds(5);
        CaseWiring.tenants(env, admin, "sangam.ai.worker.tenants", monitor);                             // 10 s: no WARN
        assertEquals(1, level(Level.WARN).size(), "a 5 s cycle does not flood the log: " + logs.list);
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        CaseWiring.tenants(env, admin, "sangam.ai.worker.tenants", monitor);                             // interval: WARN
        assertEquals(2, level(Level.WARN).size(), logs.list::toString);
        assertTrue(level(Level.WARN).get(0).getFormattedMessage().contains("ai-worker"), logs.list::toString);
        assertEquals(4.0, failures(meters, "ai-worker"), "every failure is counted");

        down.set(false);
        assertEquals(List.of("MP", "RJ"), CaseWiring.tenants(env, admin, "sangam.ai.worker.tenants", monitor));
        CaseWiring.tenants(env, admin, "sangam.ai.worker.tenants", monitor);
        List<ILoggingEvent> infos = level(Level.INFO);
        assertEquals(1, infos.size(), "one INFO on recovery, not one per read: " + logs.list);
        assertTrue(infos.get(0).getFormattedMessage().contains("readable again"), infos.get(0).getFormattedMessage());
        assertEquals(4.0, failures(meters, "ai-worker"));

        down.set(true);
        clock.now = clock.now.plusSeconds(5);
        CaseWiring.tenants(env, admin, "sangam.ai.worker.tenants", monitor);                             // fails again: WARN at once
        assertEquals(3, level(Level.WARN).size(), logs.list::toString);
    }

    @Test
    void directoryFailuresAreCountedPerSchedulerInOneMeterFamily() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScheduleTenantsMonitor compliance = new ScheduleTenantsMonitor("compliance", "x", meters);
        ScheduleTenantsMonitor channels = new ScheduleTenantsMonitor("channels", "x", meters);
        ObjectProvider<AdminPort> down = provider(admin((ctx, page) -> {
            throw new IllegalStateException("down");
        }));
        MockEnvironment env = new MockEnvironment();
        ComplianceWiring.tenants(env, down, compliance);
        ComplianceWiring.tenants(env, down, channels);
        ComplianceWiring.tenants(env, down, channels);

        assertEquals(1.0, failures(meters, "compliance"));
        assertEquals(2.0, failures(meters, "channels"));
        assertEquals(2, meters.find(ScheduleTenantsMonitor.DIRECTORY_FAILURES_METRIC).counters().size());
        assertEquals(2, level(Level.WARN).size(), "the throttle is per scheduler: each warns on its first failure");
    }

    // ------------------------------------------------------------------ helpers

    private List<ILoggingEvent> level(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    private static double failures(SimpleMeterRegistry meters, String scheduler) {
        return meters.get(ScheduleTenantsMonitor.DIRECTORY_FAILURES_METRIC).tag(ScheduleTenantsMonitor.TAG, scheduler)
                .counter().count();
    }

    /** A fresh monitor per read: the first failure of a schedule always warns. */
    private static List<String> compliance(Environment env, ObjectProvider<AdminPort> admin) {
        return ComplianceWiring.tenants(env, admin, new ScheduleTenantsMonitor("compliance", "x", new SimpleMeterRegistry()));
    }

    private static List<String> caseTenants(Environment env, ObjectProvider<AdminPort> admin, String property) {
        return CaseWiring.tenants(env, admin, property, new ScheduleTenantsMonitor("case", "x", new SimpleMeterRegistry()));
    }

    private static ObjectProvider<AdminPort> provider(AdminPort port) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("admin", port);
        return bf.getBeanProvider(AdminPort.class);
    }

    /** An AdminPort that serves only {@code tenants(ctx, filter, page)}. */
    private static AdminPort admin(BiFunction<RequestContext, Page.PageRequest, Page<AdminPort.Tenant>> tenants) {
        return (AdminPort) Proxy.newProxyInstance(AdminPort.class.getClassLoader(), new Class<?>[]{AdminPort.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "tenants" -> tenants.apply((RequestContext) args[0], (Page.PageRequest) args[2]);
                    case "toString" -> "fake AdminPort";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static AdminPort.Tenant tenant(String id, String status) {
        return new AdminPort.Tenant(id, id, status, "STANDARD", "SHARED", "en", List.of("en"), Map.of(), null, 0);
    }
}
