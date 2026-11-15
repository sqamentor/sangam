// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.gov.DltDeliveryReports;
import org.sangam.integration.connector.gov.DltDeliveryStatusPoller;
import org.sangam.integration.connector.gov.DltRegistrationStore;
import org.sangam.integration.connector.gov.DltSmsConnector;
import org.sangam.integration.publish.WebhookSignature;
import org.sangam.integration.schema.IntegrationSchema;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DltPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.support.TxSupport;
import org.sangam.rest.web.DltController;
import org.sangam.rest.web.ProblemHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Owner decisions 304 and 305 through the server's own wiring ({@link DltWiring}), the real controller, the bridge,
 * sangam-integration's finished classes and the RFC 9457 problem handler, on H2.
 * <ul>
 *   <li>304: a valid signed delivery report is stored and writes NOTIFICATION_DELIVERED exactly once; the same report
 *       again writes nothing more; a missing or wrong signature is 401 WEBHOOK_SIGNATURE_INVALID and writes nothing;
 *       the status-lookup fallback is scheduled.</li>
 *   <li>305: the stored registration is the registry the DLT SMS connector validates against; the admin routes are
 *       guarded by dlt.registration.read / .write; registering a template writes DLT_TEMPLATE_REGISTERED once, with
 *       the actor; start-up configuration only seeds; another node's change arrives with the refresh.</li>
 * </ul>
 * Before this wiring the route had no controller, {@code DltDeliveryReports}, {@code DltDeliveryStatusPoller} and
 * {@code DltRegistrationStore} were constructed nowhere, and the connector validated against an empty registry.
 */
class DltWiringH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";
    private static final String SECRET_REF = "secret://dlt/webhook";
    private static final String SECRET = "delivery-report-signing-value";
    private static final String ROUTE = "/api/v1/hooks/sms-dlt/" + TENANT + "/";
    private static final RequestContext ADMIN = new RequestContext(TENANT, "user:tenant-admin", Set.of(), null, null, null,
            null, null, "OIDC");

    private final JdbcDataSource ds = new JdbcDataSource();
    private final Connection keepAlive;
    private final Transactions tx;
    private final TxSupport support;
    private final AuditChain chain = new AuditChain(D);
    private final ConnectorCallJournal journal = new ConnectorCallJournal(D);
    private final Sms sms = new Sms("sms-dlt", CapabilityClass.B_LOOKUP_ONLY);
    private final ConnectorRegistry registry = new ConnectorRegistry().register(sms)
            .register(new Sms("sms-no-secret", CapabilityClass.C_NEITHER));
    private final DltWiring wiring = new DltWiring();
    private final DltProperties props = new DltProperties();
    private final DltRegistrationStore store;
    private final DltDeliveryReports reports;
    private final DltPort port;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    DltWiringH2Test() throws Exception {
        ds.setURL("jdbc:h2:mem:dltwiring" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        keepAlive = ds.getConnection();
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        tx = Transactions.of(ds);
        support = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), D);
        DltProperties.Secret secret = new DltProperties.Secret();
        secret.setConnector("sms-dlt");
        secret.setSecretRef(SECRET_REF);
        props.getDeliveryReports().getSecrets().put("main", secret);
        store = wiring.sangamDltRegistrationStore(support, ds, chain);
        reports = wiring.sangamDltDeliveryReports(support, ds, registry, journal, chain,
                provider(SecretResolver.class, SecretResolver.of(Map.of(SECRET_REF, SECRET))), props);
        port = wiring.sangamDltPort(reports, store, provider(SimulatorProfile.class, null));
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
        keepAlive.close();
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T beanOrNull) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (beanOrNull != null) {
            beans.addBean("bean", beanOrNull);
        }
        return beans.getBeanProvider(type);
    }

    /** A DLT SMS connector stand-in: the type the intake checks; counts how often the poller asks for its descriptor. */
    private static final class Sms implements SangamConnector {
        private final String id;
        private final CapabilityClass cls;
        final AtomicInteger described = new AtomicInteger();

        Sms(String id, CapabilityClass cls) {
            this.id = id;
            this.cls = cls;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            described.incrementAndGet();
            return new ConnectorDescriptor(id, DltSmsConnector.TYPE, cls, List.of(DltSmsConnector.OP_SEND), ConnectorProfile.SIMULATOR);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LookupResult lookup(ConnectorRequest request) {
            return new LookupResult.Unavailable("not asked in this test");
        }
    }

    /** Grants exactly the permissions given. */
    private static PermissionPort permissions(String... held) {
        Set<String> set = Set.of(held);
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return set.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return set;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return true;
            }
        };
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new DltController(port, permissions()))
                .setControllerAdvice(new ProblemHandler()).build();
    }

    /** An accepted send through {@code connectorId}: its CONNECTOR_CALL row, as the invoker leaves it. */
    private UUID sent(String connectorId, String messageId) {
        UUID op = UUID.randomUUID();
        tx.inTransaction(c -> {
            journal.recordAttempt(c, op, TENANT, connectorId, DltSmsConnector.OP_SEND, "case-1", "notice:1",
                    "{\"templateId\":\"1107000000000000001\",\"mobile\":\"9876543210\"}");
            journal.recordResult(c, op, ConnectorCallJournal.LastResult.SUCCESS, messageId, null);
            return null;
        });
        return op;
    }

    private static String report(String reportId, UUID op, String status) {
        return "{\"reportId\":\"" + reportId + "\",\"clientRef\":\"" + op + "\",\"status\":\"" + status
                + "\",\"reportedAt\":\"2026-11-13T07:59:00Z\"}";
    }

    private static String signature(String body, String secret) {
        return WebhookSignature.header(Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8), List.of(secret));
    }

    private MockHttpServletResponse deliver(String connectorId, String body, String signatureOrNull) throws Exception {
        var request = post(ROUTE + connectorId + "/delivery-reports").contentType("application/json")
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (signatureOrNull != null) {
            request.header(WebhookSignature.HEADER, signatureOrNull);
        }
        return mvc().perform(request).andReturn().getResponse();
    }

    private static JsonNode problem(MockHttpServletResponse res, int status, String code) throws Exception {
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(status, res.getStatus(), body);
        assertNotNull(res.getContentType(), "a content type is set");
        assertTrue(res.getContentType().startsWith("application/problem+json"), res.getContentType());
        JsonNode json = Json.tree(body);
        assertEquals(code, json.path("code").asText(), body);
        assertEquals(status, json.path("status").asInt(), body);
        return json;
    }

    private long events(String type) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?",
                rs -> rs.getString(1), TENANT, type)).size();
    }

    private long rows(String table) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM " + table, rs -> rs.getLong(1))).orElseThrow();
    }

    private String state(UUID op) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT state FROM NOTIFICATION_DELIVERY WHERE tenant_id=? AND operation_id=?",
                rs -> rs.getString(1), TENANT, op)).orElse(null);
    }

    // ---------------------------------------------------------------- decision 304: the signed webhook

    @Test
    void aValidSignedReportIsStoredAndWritesNotificationDeliveredExactlyOnce() throws Exception {
        UUID op = sent("sms-dlt", "msg-1");
        String body = report("r-1", op, "DELIVERED");
        String signature = signature(body, SECRET);

        MockHttpServletResponse first = deliver("sms-dlt", body, signature);
        assertEquals(200, first.getStatus(), first.getContentAsString());
        JsonNode answer = Json.tree(first.getContentAsString());
        assertEquals(1, answer.path("accepted").asInt());
        assertEquals(0, answer.path("duplicates").asInt());
        assertEquals(0, answer.path("ignored").asInt());
        assertEquals("DELIVERED", state(op), "the report is stored");
        assertEquals(1, events("NOTIFICATION_DELIVERED"));

        // the captured request replayed inside the tolerance: acknowledged as a duplicate, nothing written again
        MockHttpServletResponse replay = deliver("sms-dlt", body, signature);
        assertEquals(200, replay.getStatus(), replay.getContentAsString());
        assertEquals(1, Json.tree(replay.getContentAsString()).path("duplicates").asInt());
        assertEquals(0, Json.tree(replay.getContentAsString()).path("accepted").asInt());
        assertEquals(1, events("NOTIFICATION_DELIVERED"), "a replay does not write twice");
        assertEquals(1, rows("NOTIFICATION_DELIVERY_REPORT"));

        // a second report of the same delivery under another report id: still one event for the message
        String again = report("r-2", op, "DELIVERED");
        assertEquals(200, deliver("sms-dlt", again, signature(again, SECRET)).getStatus());
        assertEquals(1, events("NOTIFICATION_DELIVERED"), "once per message, not once per report");
    }

    @Test
    void aBadOrMissingSignatureIsRefusedWithTheDocumentedProblemAndWritesNothing() throws Exception {
        UUID op = sent("sms-dlt", "msg-2");
        String body = report("r-9", op, "DELIVERED");

        JsonNode wrong = problem(deliver("sms-dlt", body, signature(body, "another-value")), 401, "WEBHOOK_SIGNATURE_INVALID");
        assertEquals("https://sangam.dev/problems/webhook-signature-invalid", wrong.path("type").asText());
        problem(deliver("sms-dlt", body, null), 401, "WEBHOOK_SIGNATURE_INVALID");
        problem(deliver("sms-dlt", body, signature(report("r-9", op, "UNDELIVERED"), SECRET)), 401,
                "WEBHOOK_SIGNATURE_INVALID");
        long stale = Instant.now().minusSeconds(3600).getEpochSecond();
        problem(deliver("sms-dlt", body, WebhookSignature.header(stale, body.getBytes(StandardCharsets.UTF_8), List.of(SECRET))),
                401, "WEBHOOK_SIGNATURE_INVALID");
        // a connector without a configured signing secret refuses everything, also a report signed with some secret
        UUID other = sent("sms-no-secret", "msg-3");
        String otherBody = report("r-10", other, "DELIVERED");
        problem(deliver("sms-no-secret", otherBody, signature(otherBody, SECRET)), 401, "WEBHOOK_SIGNATURE_INVALID");

        assertEquals(0, events("NOTIFICATION_DELIVERED"));
        assertEquals(0, rows("NOTIFICATION_DELIVERY"), "nothing stored");
        assertEquals(0, rows("NOTIFICATION_DELIVERY_REPORT"));
        assertNull(state(op));
    }

    @Test
    void anUnknownConnectorIs404AndAMalformedReportIs422WithoutTheSendersText() throws Exception {
        String body = report("r-1", UUID.randomUUID(), "DELIVERED");
        problem(deliver("no-such-connector", body, signature(body, SECRET)), 404, "NOT_FOUND");

        String malformed = "{\"clientRef\":\"x\",\"status\":\"<script>alert(1)</script>\"}";
        MockHttpServletResponse res = deliver("sms-dlt", malformed, signature(malformed, SECRET));
        problem(res, 422, "DLT_DELIVERY_REPORT_INVALID");
        assertFalse(res.getContentAsString().contains("script"), "the sender's text is not echoed");
        assertEquals(0, rows("NOTIFICATION_DELIVERY"));
    }

    @Test
    void aLiteralSigningSecretInTheConfigurationIsRefusedAtStart() {
        DltProperties literal = new DltProperties();
        DltProperties.Secret s = new DltProperties.Secret();
        s.setConnector("sms-dlt");
        s.setSecretRef("the-secret-itself");
        literal.getDeliveryReports().getSecrets().put("main", s);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> DltWiring.secretRefs(literal));
        assertTrue(refused.getMessage().contains("sangam.integration.dlt.delivery-reports.secrets.main.secret-ref"),
                refused.getMessage());
        assertFalse(refused.getMessage().contains("the-secret-itself"), "the value is never printed");

        DltProperties scoped = new DltProperties();
        DltProperties.Secret one = new DltProperties.Secret();
        one.setConnector("sms-dlt");
        one.setTenant("acme");
        one.setSecretRef("secret://dlt/acme");
        scoped.getDeliveryReports().getSecrets().put("acme", one);
        assertEquals(Map.of("acme:sms-dlt", "secret://dlt/acme"), DltWiring.secretRefs(scoped));
        assertEquals(Map.of("sms-dlt", SECRET_REF), DltWiring.secretRefs(props));
    }

    // ---------------------------------------------------------------- decision 304: the status-lookup fallback

    @Test
    void theStatusLookupFallbackIsScheduledWithItsInterval() throws Exception {
        DltDeliveryStatusPoller poller = wiring.sangamDltDeliveryStatusPoller(support, ds, registry, journal, reports, props);
        assertEquals(Duration.ofMinutes(5), props.getDeliveryStatus().getInterval(), "the documented default");
        assertEquals(DltDeliveryStatusPoller.Settings.defaults(), DltWiring.pollerSettings(props));

        props.getDeliveryStatus().setInterval(Duration.ofMillis(20));
        props.getRegistration().setRefreshInterval(Duration.ofMillis(20));
        DltWiring.Scheduler scheduler = wiring.sangamDltScheduler(store, poller, props,
                provider(SimulatorProfile.class, null), new MockEnvironment());
        closeables.add(scheduler);
        assertTrue(scheduler.started() && scheduler.polls());
        int before = sms.described.get();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (sms.described.get() < before + 3 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(sms.described.get() >= before + 3, "runOnce runs again and again on the schedule (it lists the DLT SMS"
                + " connectors with a lookup at the start of every pass)");
    }

    @Test
    void theScheduleFollowsTheBackgroundSwitchAndTheFallbackSwitch() {
        DltDeliveryStatusPoller poller = wiring.sangamDltDeliveryStatusPoller(support, ds, registry, journal, reports, props);
        DltWiring.Scheduler off = wiring.sangamDltScheduler(store, poller, props, provider(SimulatorProfile.class, null),
                new MockEnvironment().withProperty("sangam.integration.background.enabled", "false"));
        closeables.add(off);
        assertFalse(off.started(), "no background services on this node");

        props.getDeliveryStatus().setEnabled(false);
        DltWiring.Scheduler noFallback = wiring.sangamDltScheduler(store, poller, props,
                provider(SimulatorProfile.class, null), new MockEnvironment());
        closeables.add(noFallback);
        assertTrue(noFallback.started(), "the registration refresh still runs");
        assertFalse(noFallback.polls());
        assertEquals(0, noFallback.pollOnce());

        props.getDeliveryStatus().setEnabled(true);
        props.getDeliveryStatus().setInterval(Duration.ZERO);
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> wiring.sangamDltScheduler(store,
                poller, props, provider(SimulatorProfile.class, null), new MockEnvironment()));
        assertTrue(refused.getMessage().contains("sangam.integration.dlt.delivery-status.interval"), refused.getMessage());
    }

    // ---------------------------------------------------------------- decision 305: the stored registration

    @Test
    void eachRegistrationRouteIsGuardedByItsPermission() {
        DltController none = new DltController(port, permissions("tenant.admin", "admin.write"));
        for (Runnable call : List.<Runnable>of(() -> none.registration(ADMIN),
                () -> none.setEntity(ADMIN, new DltPort.EntityRequest("1101000000000000001")),
                () -> none.addHeader(ADMIN, new DltPort.HeaderRequest("GOVSVC")),
                () -> none.addTemplate(ADMIN, new DltPort.Template("1107000000000000001", "GOVSVC", "Application {#var#} received")),
                () -> none.addUrlPrefix(ADMIN, new DltPort.UrlPrefixRequest("https://services.example.gov.in/")))) {
            ApiProblem p = assertThrows(ApiProblem.class, call::run);
            assertEquals(403, p.status());
            assertEquals("ACTION_NOT_PERMITTED", p.code());
        }
        assertEquals(0, rows("DLT_REGISTRATION"), "a refused caller stores nothing");
        assertEquals(0, events("DLT_TEMPLATE_REGISTERED"));

        DltController readOnly = new DltController(port, permissions(Permissions.DLT_REGISTRATION_READ));
        assertEquals(new DltPort.Registration(null, List.of(), List.of(), List.of(), 0), readOnly.registration(ADMIN),
                "a tenant with nothing registered");
        assertEquals(403, assertThrows(ApiProblem.class,
                () -> readOnly.addHeader(ADMIN, new DltPort.HeaderRequest("GOVSVC"))).status());

        DltController writer = new DltController(port, permissions(Permissions.DLT_REGISTRATION_WRITE));
        assertEquals(new DltPort.RegistrationChange(true, 1), writer.setEntity(ADMIN, new DltPort.EntityRequest("1101000000000000001")));
        assertEquals(new DltPort.RegistrationChange(true, 2), writer.addHeader(ADMIN, new DltPort.HeaderRequest("GOVSVC")));
        assertEquals(new DltPort.RegistrationChange(true, 3),
                writer.addUrlPrefix(ADMIN, new DltPort.UrlPrefixRequest("https://services.example.gov.in/")));
        assertEquals(403, assertThrows(ApiProblem.class, () -> writer.registration(ADMIN)).status(),
                "the write permission does not open the read");
        DltPort.Registration stored = readOnly.registration(ADMIN);
        assertEquals("1101000000000000001", stored.entityId());
        assertEquals(List.of("GOVSVC"), stored.headers());
        assertEquals(List.of("https://services.example.gov.in/"), stored.urlPrefixes());
        assertEquals(3, stored.revision());
    }

    @Test
    void registeringATemplateThroughTheRouteWritesDltTemplateRegisteredOnceWithTheActor() {
        DltController writer = new DltController(port, permissions(Permissions.DLT_REGISTRATION_WRITE,
                Permissions.DLT_REGISTRATION_READ));
        DltPort.Template template = new DltPort.Template("1107000000000000001", "GOVSVC", "Application {#var#} received - GOVSVC");
        long before = events("DLT_TEMPLATE_REGISTERED");

        DltPort.RegistrationChange first = writer.addTemplate(ADMIN, template);
        assertTrue(first.changed());
        assertEquals(before + 1, events("DLT_TEMPLATE_REGISTERED"));
        List<String> payloads = tx.inTransaction(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p"
                + " ON p.event_id = e.event_id WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type='DLT_TEMPLATE_REGISTERED'",
                rs -> rs.getString(1), TENANT, DltRegistrationStore.CHAIN));
        assertEquals(1, payloads.size(), "on the tenant's registry chain");
        assertTrue(payloads.get(0).contains("1107000000000000001") && payloads.get(0).contains("user:tenant-admin"),
                payloads.get(0));

        DltPort.RegistrationChange same = writer.addTemplate(ADMIN, template);
        assertFalse(same.changed(), "already registered unchanged");
        assertEquals(first.revision(), same.revision());
        assertEquals(before + 1, events("DLT_TEMPLATE_REGISTERED"), "no second event");

        assertEquals(List.of(template), writer.registration(ADMIN).templates());
        // the connector's registry is the store's: the template is known to it at once
        assertTrue(store.registry().find(TENANT).orElseThrow().template("1107000000000000001").isPresent());
        // another tenant sees nothing of it
        RequestContext other = new RequestContext("T2", "user:other-admin", Set.of(), null, null, null, null, null, "OIDC");
        assertEquals(List.of(), writer.registration(other).templates());

        ApiProblem invalid = assertThrows(ApiProblem.class, () -> writer.addTemplate(ADMIN,
                new DltPort.Template("1107000000000000002", "GOVSVC", "Visit https://{#var#} now")));
        assertEquals(422, invalid.status());
        assertEquals("TEMPLATE_INVALID", invalid.code());
        assertEquals("HEADER_INVALID", assertThrows(ApiProblem.class,
                () -> writer.addHeader(ADMIN, new DltPort.HeaderRequest("no"))).code());
        assertEquals("ENTITY_INVALID", assertThrows(ApiProblem.class,
                () -> writer.setEntity(ADMIN, new DltPort.EntityRequest(null))).code());
        assertEquals("URL_INVALID", assertThrows(ApiProblem.class,
                () -> writer.addUrlPrefix(ADMIN, new DltPort.UrlPrefixRequest("ftp://x"))).code());
        assertEquals(before + 1, events("DLT_TEMPLATE_REGISTERED"), "a refused entry writes nothing");
    }

    @Test
    void startUpConfigurationOnlySeedsAndAnotherNodesChangeArrivesWithTheRefresh() throws Exception {
        DltProperties.Seed seed = new DltProperties.Seed();
        seed.setEntityId("1101000000000000001");
        seed.setHeaders(List.of("GOVSVC"));
        DltProperties.Template t = new DltProperties.Template();
        t.setTemplateId("1107000000000000001");
        t.setHeader("GOVSVC");
        t.setBody("Application {#var#} received - GOVSVC");
        seed.setTemplates(List.of(t));
        props.getRegistration().getSeed().put(TENANT, seed);

        wiring.sangamDltRegistrationSeed(store, props, provider(SimulatorProfile.class, null)).run(null);
        assertTrue(store.registry().find(TENANT).orElseThrow().template("1107000000000000001").isPresent(), "seeded and loaded");
        long seeded = events("DLT_TEMPLATE_REGISTERED");
        assertEquals(3, seeded, "entity, header and template, each with its event");

        // an administrator replaces the template; the next start does not put the configured text back
        new DltController(port, permissions(Permissions.DLT_REGISTRATION_WRITE)).addTemplate(ADMIN,
                new DltPort.Template("1107000000000000001", "GOVSVC", "Application {#var#} accepted - GOVSVC"));
        wiring.sangamDltRegistrationSeed(store, props, provider(SimulatorProfile.class, null)).run(null);
        assertEquals("Application {#var#} accepted - GOVSVC",
                store.snapshot(TENANT).orElseThrow().templates().get(0).body(), "a stored entry is never overwritten");
        assertEquals(seeded + 1, events("DLT_TEMPLATE_REGISTERED"));

        // an invalid configured entry stops the start
        DltProperties bad = new DltProperties();
        DltProperties.Seed badSeed = new DltProperties.Seed();
        badSeed.setHeaders(List.of("no"));
        bad.getRegistration().getSeed().put(TENANT, badSeed);
        assertThrows(DltRegistrationStore.InvalidRegistration.class,
                () -> wiring.sangamDltRegistrationSeed(store, bad, provider(SimulatorProfile.class, null)).run(null));

        // another node (its own store on the same database) registers a header; this node gets it with the refresh
        DltRegistrationStore otherNode = wiring.sangamDltRegistrationStore(support, ds, chain);
        otherNode.registerHeader(TENANT, "user:other-node", "GOVTAX");
        assertFalse(store.registry().find(TENANT).orElseThrow().headers().contains("GOVTAX"));
        DltDeliveryStatusPoller poller = wiring.sangamDltDeliveryStatusPoller(support, ds, registry, journal, reports, props);
        DltWiring.Scheduler scheduler = wiring.sangamDltScheduler(store, poller, props,
                provider(SimulatorProfile.class, null),
                new MockEnvironment().withProperty("sangam.integration.background.enabled", "false"));
        closeables.add(scheduler);
        assertEquals(1, scheduler.refreshOnce());
        assertTrue(store.registry().find(TENANT).orElseThrow().headers().contains("GOVTAX"));
        assertEquals(0, scheduler.refreshOnce(), "nothing moved since");
    }

    @Test
    void theConnectorRegistryOfTheServerValidatesAgainstTheStoredRegistration() {
        try (SimulatorProfile sims = new SimulatorProfile()) {
            ConnectorRegistry built = new IntegrationWiring().sangamConnectorRegistry(provider(SimulatorProfile.class, sims),
                    new MockEnvironment().withProperty("sangam.tenant.default", TENANT), store);
            DltSmsConnector connector = (DltSmsConnector) built.find(TENANT, "sms-dlt-sim").orElseThrow();
            assertSame(store.registry(), connector.config().registry(),
                    "the stored registration, not an empty in-memory registry");
        }
    }
}
