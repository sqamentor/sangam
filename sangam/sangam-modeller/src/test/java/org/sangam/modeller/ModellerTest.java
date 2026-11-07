// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller;

import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.modeller.action.ActionModels;
import org.sangam.modeller.auth.TokenExchange;
import org.sangam.modeller.catalogue.ExtensionCatalogue;
import org.sangam.modeller.landscape.ProcessLandscape;
import org.sangam.modeller.lease.EditLeases;
import org.sangam.modeller.mock.ServiceMocks;
import org.sangam.modeller.simulation.TokenWalk;
import org.sangam.modeller.template.ElementTemplates;
import org.sangam.modeller.test.BulkModelTests;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModellerTest {

    Transactions tx;
    AuditChain audit = new AuditChain(Dialect.POSTGRESQL);
    static final Dialect D = Dialect.POSTGRESQL;
    static final String NS = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"urn:x\"";

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, D);
            ModellerSchema.create(c, D);
        }
        tx = Transactions.of(ds);
    }

    @Test
    void landscapeFindsMissingDynamicAndCycles() {
        String a = "<definitions " + NS + "><process id='a' isExecutable='true'><callActivity id='c1' calledElement='b'/><callActivity id='c2' calledElement='gone'/><callActivity id='c3' calledElement='${x}'/></process></definitions>";
        String b = "<definitions " + NS + "><process id='b' isExecutable='true'><subProcess id='s'><callActivity id='c4' calledElement='a'/></subProcess></process></definitions>";
        var g = ProcessLandscape.build(List.of(new ProcessLandscape.Model("p", "a", a), new ProcessLandscape.Model("p", "b", b), new ProcessLandscape.Model("p", "bad", "nope")));
        assertEquals(Set.of("a", "b"), g.processes());
        assertEquals(List.of("p/bad"), g.unparsable());
        assertEquals(ProcessLandscape.Kind.RESOLVED, g.edges().get(0).kind());
        assertEquals(ProcessLandscape.Kind.MISSING, g.edges().get(1).kind());
        assertEquals(ProcessLandscape.Kind.DYNAMIC, g.edges().get(2).kind());
        assertEquals("b", g.edges().get(3).fromProcess());
        assertEquals(1, g.cycles().size());
    }

    static final String WALK = "<definitions " + NS + "><process id='p' isExecutable='true'>"
            + "<startEvent id='s'><outgoing>f1</outgoing></startEvent><sequenceFlow id='f1' sourceRef='s' targetRef='x'/>"
            + "<exclusiveGateway id='x'><incoming>f1</incoming><outgoing>f2</outgoing><outgoing>f3</outgoing></exclusiveGateway>"
            + "<sequenceFlow id='f2' sourceRef='x' targetRef='fork'/><sequenceFlow id='f3' sourceRef='x' targetRef='e'/>"
            + "<parallelGateway id='fork'/><sequenceFlow id='a' sourceRef='fork' targetRef='t1'/><sequenceFlow id='b' sourceRef='fork' targetRef='t2'/>"
            + "<userTask id='t1'/><userTask id='t2'/><sequenceFlow id='c' sourceRef='t1' targetRef='join'/><sequenceFlow id='d' sourceRef='t2' targetRef='join'/>"
            + "<parallelGateway id='join'/><sequenceFlow id='z' sourceRef='join' targetRef='e'/><endEvent id='e'/></process></definitions>";

    @Test
    void tokenWalkAsksForChoiceForksAndJoins() {
        TokenWalk.Result r = TokenWalk.walk(WALK, null, Map.of(), 100);
        assertEquals(TokenWalk.Status.NEEDS_CHOICE, r.status());
        assertEquals(List.of("f2", "f3"), r.pending().options());
        r = TokenWalk.walk(WALK, "p", Map.of("x", List.of("f2")), 100);
        assertEquals(TokenWalk.Status.COMPLETED, r.status());
        assertEquals(1, r.visited().stream().filter("join"::equals).count(), "the join fires once");
        assertEquals(1, r.visited().stream().filter("e"::equals).count());
        assertEquals(TokenWalk.Status.STEP_LIMIT, TokenWalk.walk(WALK, "p", Map.of("x", List.of("f2")), 2).status());
        assertThrows(IllegalArgumentException.class, () -> TokenWalk.walk(WALK, "p", Map.of("x", List.of("nope")), 100));
    }

    @Test
    void leaseGivesOneEditorAndFencesLateSave() {
        EditLeases l = new EditLeases(D, tx, audit, Duration.ofMinutes(5));
        var a = l.acquire("t", "f", "alice");
        assertTrue(a.editable());
        assertFalse(l.acquire("t", "f", "bob").editable());
        assertTrue(l.acquire("t", "f", "alice").editable());
        assertThrows(SecurityException.class, () -> l.takeover("t", "f", "bob", false, "", "r"));
        var b = l.takeover("t", "f", "bob", true, "<draft/>", "alice is away");
        assertEquals("bob", b.holder());
        assertTrue(b.fence() > a.lease().fence());
        assertThrows(EditLeases.StaleEditor.class, () -> tx.inTransaction(c -> {
            l.checkFence(c, "t", "f", "alice", a.lease().fence());
            return null;
        }));
        assertThrows(EditLeases.StaleEditor.class, () -> l.heartbeat("t", "f", "alice", a.lease().fence()));
        tx.inTransaction(c -> {
            l.checkFence(c, "t", "f", "bob", b.fence());
            return null;
        });
        l.release("t", "f", "bob", b.fence());
        assertTrue(l.acquire("t", "f", "carol").editable());
    }

    @Test
    void templatesVersionAndValidate() {
        ElementTemplates ts = new ElementTemplates(D, tx);
        Map<String, Object> doc = Map.of("id", "notify", "appliesTo", List.of("bpmn:ServiceTask"),
                "fields", List.of(Map.of("name", "topic", "binding", "operaton:topic", "required", true, "pattern", "[a-z.]+")));
        assertEquals(1, ts.publish("t", ElementTemplates.Kind.ELEMENT, doc, "al").version());
        var v2 = ts.publish("t", ElementTemplates.Kind.ELEMENT, doc, "al");
        assertEquals(2, v2.version());
        assertEquals(2, ts.find("t", "notify", null).orElseThrow().version());
        assertEquals(1, ts.find("t", "notify", 1).orElseThrow().version());
        assertEquals(1, ts.latest("t", ElementTemplates.Kind.ELEMENT).size());
        assertTrue(ElementTemplates.apply(v2, "bpmn:ServiceTask", Map.of("topic", "a.b")).errors().isEmpty());
        assertEquals("a.b", ElementTemplates.apply(v2, "bpmn:ServiceTask", Map.of("topic", "a.b")).properties().get("operaton:topic"));
        assertFalse(ElementTemplates.apply(v2, "bpmn:UserTask", Map.of("topic", "a")).errors().isEmpty());
        assertFalse(ElementTemplates.apply(v2, "bpmn:ServiceTask", Map.of("topic", "BAD")).errors().isEmpty());
        assertFalse(ElementTemplates.apply(v2, "bpmn:ServiceTask", Map.of()).errors().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ts.publish("t", ElementTemplates.Kind.ELEMENT, Map.of("id", "x", "fields", List.of(Map.of("name", "n", "binding", "evil:x"))), "al"));
        var fromTask = ElementTemplates.fromTask("saved", "bpmn:UserTask", Map.of("operaton:assignee", "x"));
        assertEquals(1, ts.publish("t", ElementTemplates.Kind.ELEMENT, fromTask, "al").fields().size());
    }

    static SangamConnector real(AtomicInteger calls) {
        return new SangamConnector() {
            public ConnectorDescriptor descriptor() {
                return new ConnectorDescriptor("svc", "http", null, List.of(), ConnectorProfile.LIVE);
            }

            public ConnectorResult invoke(ConnectorRequest r) {
                calls.incrementAndGet();
                return ConnectorResult.Success.of(Map.of("real", true), null);
            }
        };
    }

    static ConnectorRequest req(Map<String, Object> in) {
        return new ConnectorRequest("t", UUID.randomUUID(), "a", 1, "r", null, "pay", in, null);
    }

    @Test
    void mocksNeverOnProductionAndNeverFallThrough() {
        AtomicInteger calls = new AtomicInteger();
        List<ServiceMocks.Rule> rules = List.of(new ServiceMocks.Rule("svc", "pay", Map.of("k", "1"), Map.of("m", true), null, null),
                new ServiceMocks.Rule("svc", "pay", Map.of("k", "2"), Map.of(), "DENIED", null));
        assertThrows(IllegalStateException.class, () -> new ServiceMocks(true, rules).wrap(real(calls)));
        ServiceMocks m = new ServiceMocks(false, rules);
        SangamConnector w = m.wrap(real(calls));
        assertEquals(true, ((ConnectorResult.Success) w.invoke(req(Map.of("k", "1")))).output().get("m"));
        assertEquals("DENIED", ((ConnectorResult.BusinessFailure) w.invoke(req(Map.of("k", "2")))).code());
        assertEquals("MOCK_NOT_DEFINED", ((ConnectorResult.BusinessFailure) w.invoke(req(Map.of("k", "3")))).code());
        assertEquals(0, calls.get());
        m.setServiceEnabled("svc", false);
        w.invoke(req(Map.of()));
        assertEquals(1, calls.get());
        m.setServiceEnabled("svc", true);
        m.setGlobalEnabled(false);
        w.invoke(req(Map.of()));
        assertEquals(2, calls.get());
    }

    @Test
    void mockRulesPersistWithToggle() {
        var store = new org.sangam.modeller.mock.ServiceMockStore(D, tx);
        store.save("t", new ServiceMocks.Rule("svc", "pay", Map.of("k", "1"), Map.of("m", true), null, Duration.ofMillis(5)), true, "al");
        store.save("t", new ServiceMocks.Rule("svc", "pay", Map.of(), Map.of("m", false), null, null), true, "al");
        assertEquals(1, store.list("t").size(), "one rule per operation");
        assertNull(store.list("t").get(0).rule().delay());
        AtomicInteger calls = new AtomicInteger();
        SangamConnector w = store.load("t", false).wrap(real(calls));
        assertEquals(false, ((ConnectorResult.Success) w.invoke(req(Map.of()))).output().get("m"));
        store.setServiceEnabled("t", "svc", false);
        store.load("t", false).wrap(real(calls)).invoke(req(Map.of()));
        assertEquals(1, calls.get(), "a disabled service calls the real connector");
        assertTrue(store.list("other").isEmpty());
        assertThrows(IllegalStateException.class, () -> store.load("t", true).wrap(real(calls)));
    }

    @Test
    void bulkRunContinuesAfterErrorsAndBundleIsSealed() {
        BulkModelTests b = new BulkModelTests(D, tx);
        List<BulkModelTests.TestCase> cases = List.of(new BulkModelTests.TestCase("ok", "p", Map.of()), new BulkModelTests.TestCase("boom", "p", Map.of()), new BulkModelTests.TestCase("judge", "p", Map.of()));
        var r = b.run("t", "pkg", cases, (t, c) -> switch (c.id()) {
            case "boom" -> throw new IllegalStateException("x");
            case "judge" -> new BulkModelTests.CaseResult("judge", BulkModelTests.Status.NOT_JUDGED, false, 1, "off");
            default -> new BulkModelTests.CaseResult(c.id(), BulkModelTests.Status.PASSED, true, 1, null);
        }, Duration.ofMinutes(1), "al");
        assertEquals(List.of(1, 1, 1), List.of(r.passed(), r.failed(), r.notJudged()));
        assertEquals(1, b.reports("t", "pkg").size());
        String bundle = BulkModelTests.exportBundle("pkg", "3", cases, List.of(Map.of("service", "s")));
        assertEquals("pkg", BulkModelTests.verifyBundle(bundle).get("packageId"));
        assertThrows(IllegalArgumentException.class, () -> BulkModelTests.verifyBundle(bundle.replace("\"pkg\"", "\"other\"")));
    }

    @Test
    void actionsCheckPermissionOnServerAndAreIdempotent() {
        AtomicInteger runs = new AtomicInteger();
        ActionModels a = new ActionModels(D, tx, audit, (t, u, p, r) -> u.equals("alice"), (t, op, ac, ref, in) -> {
            runs.incrementAndGet();
            return Map.of("done", ref);
        });
        a.publish("t", "escalate", "Escalate", ActionModels.Scope.CASE, "claim", "case.escalate", null, ActionModels.TargetType.BPMN, "escalation", "admin");
        var v2 = a.publish("t", "escalate", "Escalate!", ActionModels.Scope.CASE, "claim", "case.escalate", null, ActionModels.TargetType.BPMN, "escalation", "admin");
        assertEquals(2, v2.version());
        assertEquals(1, a.available("t", "alice", ActionModels.Scope.CASE, "CLAIM", "c1", x -> true).size());
        assertTrue(a.available("t", "bob", ActionModels.Scope.CASE, "claim", "c1", x -> true).isEmpty());
        assertThrows(SecurityException.class, () -> a.invoke("t", "bob", "escalate", "c1", Map.of(), "k"));
        var first = a.invoke("t", "alice", "escalate", "c1", Map.of(), "k");
        var again = a.invoke("t", "alice", "escalate", "c1", Map.of(), "k");
        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(first.operationId(), again.operationId());
        assertEquals(1, runs.get());
        assertThrows(IllegalArgumentException.class, () -> a.publish("t", "x", "x", ActionModels.Scope.TASK, null, "p", null, ActionModels.TargetType.REST, "http://evil", "a"));
    }

    @Test
    void catalogueNeedsSignatureLicenceAndChecksum() throws Exception {
        var kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] artifact = "pack".getBytes();
        byte[] index = ("{\"entries\":[{\"id\":\"p\",\"version\":\"1\",\"kind\":\"pack\",\"publisher\":\"x\",\"licence\":\"Apache-2.0\",\"minPlatform\":\"1.2\",\"sha256\":\"" + Hashes.hex(Hashes.sha256(artifact)) + "\"}]}").getBytes();
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(index);
        String sig = Base64.getEncoder().encodeToString(s.sign());
        var e = ExtensionCatalogue.read(index, sig, kp.getPublic()).get(0);
        assertTrue(ExtensionCatalogue.check(e, artifact, Set.of("Apache-2.0"), "1.10").isEmpty());
        assertEquals(1, ExtensionCatalogue.check(e, artifact, Set.of("MIT"), "1.10").size());
        assertEquals(1, ExtensionCatalogue.check(e, artifact, Set.of("Apache-2.0"), "1.1").size());
        assertEquals(1, ExtensionCatalogue.check(e, "x".getBytes(), Set.of("Apache-2.0"), "2").size());
        index[0] = ' ';
        assertThrows(ExtensionCatalogue.CatalogueException.class, () -> ExtensionCatalogue.read(index, sig, kp.getPublic()));
    }

    @Test
    void tokenExchangeRefusesNoUserAndCachesPerUser() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String[] body = new String[1];
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", x -> {
            body[0] = new String(x.getRequestBody().readAllBytes());
            byte[] out = ("{\"access_token\":\"tok" + hits.incrementAndGet() + "\",\"expires_in\":300}").getBytes();
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        server.start();
        try {
            TokenExchange te = new TokenExchange(new TokenExchange.Config(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"), "cid", "sec"),
                    HttpTransport.forSimulator(), SecretResolver.of(Map.of()), Clock.systemUTC());
            assertThrows(TokenExchange.ExchangeFailed.class, () -> te.exchange("t", null, null, "aud"));
            var a = te.exchange("t", "alice", "s1", "aud");
            assertTrue(body[0].contains("token-exchange") && body[0].contains("audience=aud"));
            assertEquals(a.accessToken(), te.exchange("t", "alice", "s1", "aud").accessToken());
            assertNotEquals(a.accessToken(), te.exchange("t", "bob", "s2", "aud").accessToken());
            assertNotEquals(a.accessToken(), te.exchange("t", "alice", "s1", "other").accessToken());
            assertEquals("alice", a.actingUser());
        } finally {
            server.stop(0);
        }
    }
}
