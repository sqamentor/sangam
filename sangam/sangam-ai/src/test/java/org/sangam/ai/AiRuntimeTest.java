// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ai.agent.AgentDefinition;
import org.sangam.ai.agent.AgentRuntime;
import org.sangam.ai.agent.AgentTool;
import org.sangam.ai.classify.DocumentClassifier;
import org.sangam.ai.draft.DraftService;
import org.sangam.ai.expr.ExpressionPlayground;
import org.sangam.ai.judge.LlmJudge;
import org.sangam.ai.llm.Llm;
import org.sangam.ai.llm.LlmPort;
import org.sangam.ai.llm.SimulatedLlm;
import org.sangam.ai.mcp.McpServer;
import org.sangam.ai.rag.KnowledgeBaseService;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.AuthorizationPolicy;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AiRuntimeTest {

    Transactions tx;
    AuditChain audit = new AuditChain(Dialect.POSTGRESQL);

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            AiSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
    }

    static AgentDefinition def(String... tools) {
        return new AgentDefinition("a", 1, AgentDefinition.Type.ASSISTANT, "be helpful", List.of(tools), 5, 0, 1000, null, null);
    }

    static AgentTool tool(String name, boolean effect, AtomicInteger calls) {
        return new AgentTool() {
            public Llm.ToolSpec spec() {
                return new Llm.ToolSpec(name, name, Map.of("type", "object"));
            }

            public boolean effectful() {
                return effect;
            }

            public Result invoke(Context c, Map<String, Object> a) {
                calls.incrementAndGet();
                return new Done(Map.of("ok", true));
            }
        };
    }

    AgentRuntime runtime(LlmPort llm, AgentTool t, Duration lease) {
        return new AgentRuntime(Dialect.POSTGRESQL, tx, audit, llm, n -> Optional.ofNullable(t).filter(x -> x.spec().name().equals(n)), null, lease);
    }

    @Test
    void disabledLlmRefusesToStart() {
        AgentRuntime rt = runtime(LlmPort.disabled(), null, Duration.ofMinutes(1));
        assertThrows(Llm.DisabledException.class, () -> rt.start(def(), "t", "alice", "hi", null, null));
    }

    @Test
    void effectWaitsForHumanThenRunsOnce() {
        AtomicInteger calls = new AtomicInteger();
        SimulatedLlm llm = new SimulatedLlm().scriptToolCalls(new Llm.ToolCall("c1", "pay", Map.of("x", 1))).script("done");
        AgentRuntime rt = runtime(llm, tool("pay", true, calls), Duration.ofMinutes(1));
        String id = rt.start(def("pay"), "t", "alice", "pay it", null, null);
        assertEquals(AgentRuntime.State.WAITING_APPROVAL, rt.drive(id, "w1").state());
        assertEquals(0, calls.get());
        AgentRuntime.Approval a = rt.pendingApprovals("t").get(0);
        assertThrows(AgentRuntime.ApprovalException.class, () -> rt.decide("t", a.approvalId(), "alice", true, a.argsHash(), "self"));
        assertThrows(AgentRuntime.ApprovalException.class, () -> rt.decide("t", a.approvalId(), "bob", true, "wrong", "x"));
        rt.decide("t", a.approvalId(), "bob", true, a.argsHash(), "ok");
        AgentRuntime.Run r = rt.drive(id, "w2");
        assertEquals(AgentRuntime.State.COMPLETED, r.state());
        assertEquals("done", r.result());
        assertEquals(1, calls.get());
        assertTrue(tx.inTransaction(c -> audit.verify(c, "t", id)).ok());
    }

    @Test
    void toolOutsideAllowListIsRefused() {
        AtomicInteger calls = new AtomicInteger();
        SimulatedLlm llm = new SimulatedLlm().scriptToolCalls(new Llm.ToolCall("c1", "evil", Map.of())).script("ok");
        AgentRuntime rt = runtime(llm, tool("evil", false, calls), Duration.ofMinutes(1));
        String id = rt.start(def(), "t", "alice", "x", null, null);
        assertEquals(AgentRuntime.State.COMPLETED, rt.drive(id, "w").state());
        assertEquals(0, calls.get());
    }

    @Test
    void stepBudgetEndsTheRun() {
        SimulatedLlm llm = new SimulatedLlm();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            llm.scriptToolCalls(new Llm.ToolCall("c" + i, "read", Map.of()));
        }
        AgentRuntime rt = runtime(llm, tool("read", false, calls), Duration.ofMinutes(1));
        String id = rt.start(def("read"), "t", "alice", "x", null, null);
        AgentRuntime.Run r = rt.drive(id, "w");
        assertEquals(AgentRuntime.State.FAILED, r.state());
        assertTrue(r.result().startsWith("BUDGET_EXCEEDED"));
    }

    @Test
    void staleWorkerIsFenced() {
        // The tool lets the lease expire and a second worker take over, so the first worker's record must be refused.
        SimulatedLlm llm = new SimulatedLlm().scriptToolCalls(new Llm.ToolCall("c1", "slow", Map.of())).script("second");
        AtomicInteger calls = new AtomicInteger();
        AgentRuntime[] holder = new AgentRuntime[1];
        AgentTool slow = new AgentTool() {
            public Llm.ToolSpec spec() {
                return new Llm.ToolSpec("slow", "slow", Map.of("type", "object"));
            }

            public boolean effectful() {
                return false;
            }

            public Result invoke(Context c, Map<String, Object> a) {
                if (calls.incrementAndGet() == 1) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    holder[0].drive(c.runId(), "w2");
                }
                return new Done(Map.of());
            }
        };
        holder[0] = runtime(llm, slow, Duration.ofMillis(100));
        String id = holder[0].start(def("slow"), "t", "alice", "x", null, null);
        assertThrows(org.sangam.core.api.StaleLeaseException.class, () -> holder[0].step(id, "w1"));
    }

    @Test
    static org.sangam.core.security.SecurityIdentity who(String person, Set<String> roles) {
        return new org.sangam.core.security.SecurityIdentity("t", person, person, Set.of(), roles, Set.of(), Set.of(), "OIDC", null, null, false, Map.of());
    }

    /** H2 cannot infer the type of a parameter inside BITAND; this only adds a cast, the compiler's predicate is unchanged. */
    static org.sangam.core.layer.SqlPredicate h2(org.sangam.core.layer.SqlPredicate p) {
        return p;
    }

    /** The production binding: the sangam-security PolicyCompiler (H2 dialect), with only a parameter cast for H2. */
    KnowledgeBaseService policyBoundKb() {
        tx.inTransaction(c -> org.sangam.core.layer.Jdbc.update(c,
                "CREATE TABLE IF NOT EXISTS AUTH_CONDITION (tenant_id VARCHAR(64), condition_id VARCHAR(64), resource_type VARCHAR(255), expression TEXT)"));
        var compiler = new org.sangam.security.policy.PolicyCompiler(org.sangam.security.dialect.SecurityDialect.H2,
                new org.sangam.security.policy.ResourceRegistry(), Duration.ZERO);
        return new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 200, 20, 1000,
                (c, w) -> h2(new org.sangam.core.layer.SqlPredicate(compiler.visible(c, w, "document", org.sangam.security.policy.Permission.READ, "k").sql().replaceAll("BITAND\\(([a-z]+\\.perms), \\?\\)", "BITAND($1, CAST(? AS BIGINT))"), compiler.visible(c, w, "document", org.sangam.security.policy.Permission.READ, "k").params())));
    }

    void ingestTwo(KnowledgeBaseService kb) {
        int v = kb.newVersion("t", "kb");
        kb.ingest("t", "kb", v, "public-doc", "1", "alpha beta");
        kb.ingest("t", "kb", v, "secret-doc", "1", "alpha beta gamma");
        kb.activate("t", "kb", v);
    }

    void grant(String principal, String type, String id, boolean deny) {
        long bit = org.sangam.security.policy.Permission.READ.bit();
        tx.inTransaction(c -> deny ? AuthorizationPolicy.deny(c, Dialect.POSTGRESQL, "t", principal, type, id, bit)
                : AuthorizationPolicy.grant(c, Dialect.POSTGRESQL, "t", principal, type, id, bit));
    }

    @Test
    void retrievalFiltersBeforeRankingWithThePolicyCompiler() {
        KnowledgeBaseService kb = policyBoundKb();
        ingestTwo(kb);
        assertTrue(kb.retrieve(who("alice", Set.of()), "kb", "alpha", 5).isEmpty(), "no grant, no results");
        grant("user:alice", "document", "public-doc", false);
        var hits = kb.retrieve(who("alice", Set.of()), "kb", "alpha", 5);
        assertEquals(List.of("public-doc"), hits.stream().map(KnowledgeBaseService.Hit::documentId).toList());
    }

    @Test
    void typeWildcardDenyBlocksRetrieval() {
        KnowledgeBaseService kb = policyBoundKb();
        ingestTwo(kb);
        grant("user:alice", "document", "*", false);
        assertEquals(2, kb.retrieve(who("alice", Set.of("analyst")), "kb", "alpha", 5).size());
        // A-13: a deny on resource type * through a role wins over the user's document grant
        grant("role:analyst", "*", "*", true);
        assertTrue(kb.retrieve(who("alice", Set.of("analyst")), "kb", "alpha", 5).isEmpty());
        assertEquals(2, kb.retrieve(who("alice", Set.of()), "kb", "alpha", 5).size(), "the deny is on the role only");
    }

    @Test
    void storesNewSchemaColumns() {
        KnowledgeBaseService kb = new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 200, 20, 1000);
        int v1 = kb.newVersion("t", "kb", Map.of("title", "Rules"));
        kb.activate("t", "kb", v1);
        kb.activate("t", "kb", kb.newVersion("t", "kb"));
        assertTrue(kb.retire("t", "kb", "ops", "obsolete"));
        var info = kb.list("t").get(0);
        assertEquals("obsolete", info.retireReason());
        assertEquals("ops", info.retiredBy());
        assertEquals("superseded by version 2", tx.<String>inTransaction(c -> org.sangam.core.layer.Jdbc.one(c,
                "SELECT retire_reason FROM AI_KB_VERSION WHERE version=1", rs -> rs.getString(1)).orElseThrow()));
        assertEquals("Rules", tx.<String>inTransaction(c -> org.sangam.core.layer.Jdbc.one(c,
                "SELECT metadata_json FROM AI_KB_VERSION WHERE version=1", rs -> rs.getString(1)).orElseThrow()).replaceAll(".*\"title\":\"([^\"]+)\".*", "$1"));

        String bpmn = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"urn:x\" id=\"d\"><process id=\"p\" isExecutable=\"true\"><startEvent id=\"s\"/></process></definitions>";
        DraftService drafts = new DraftService(Dialect.POSTGRESQL, tx, audit, new SimulatedLlm().script(bpmn));
        DraftService.Draft d = drafts.generate("t", DraftService.Kind.BPMN, "make", null, null, "alice");
        assertEquals("pkg-7", drafts.approve("t", d.id(), "bob", d.contentHash(), "", (a, b, c, e, f) -> "pkg-7").packageId());
        assertEquals("pkg-7", drafts.find("t", d.id()).orElseThrow().packageId());

        DocumentClassifier.Template tpl = new DocumentClassifier.Template("mail", 1, List.of("a"), List.of(), new BigDecimal("0.8"));
        DocumentClassifier cl = new DocumentClassifier(Dialect.POSTGRESQL, tx, audit, new SimulatedLlm().script("{\"label\":\"a\",\"confidence\":0.1,\"fields\":{}}"));
        var r = cl.classify("t", tpl, "src", "x");
        assertEquals("PENDING_REVIEW", cl.list("t").get(0).status());
        cl.correct("t", r.id(), "a", "bob");
        var row = cl.list("t").get(0);
        assertEquals("REVIEWED", row.status());
        assertEquals("bob", row.reviewedBy());
        assertNotNull(row.reviewedAt());
    }

    @Test
    void noVisibilityBindingFailsClosed() {
        KnowledgeBaseService kb = new KnowledgeBaseService(Dialect.POSTGRESQL, tx, new SimulatedLlm(), 200, 20, 1000);
        ingestTwo(kb);
        grant("user:alice", "document", "*", false);
        assertTrue(kb.retrieve(who("alice", Set.of()), "kb", "alpha", 5).isEmpty());
    }

    @Test
    void draftNeedsValidationAndAnotherReviewerAndNeverDeploys() {
        String bpmn = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:x" id="d">
                  <process id="p" isExecutable="true"><startEvent id="s"/></process></definitions>""";
        SimulatedLlm llm = new SimulatedLlm().script("not xml").script("```xml\n" + bpmn + "\n```");
        DraftService drafts = new DraftService(Dialect.POSTGRESQL, tx, audit, llm);
        DraftService.Draft bad = drafts.generate("t", DraftService.Kind.BPMN, "make", null, null, "alice");
        assertEquals(DraftService.State.INVALID, bad.state());
        assertThrows(DraftService.ReviewException.class, () -> drafts.approve("t", bad.id(), "bob", bad.contentHash(), "", (a, b, c, d, e) -> "x"));
        DraftService.Draft good = drafts.generate("t", DraftService.Kind.BPMN, "make", null, null, "alice");
        assertEquals(DraftService.State.PENDING_REVIEW, good.state(), () -> good.validationErrors().toString());
        assertThrows(DraftService.ReviewException.class, () -> drafts.approve("t", good.id(), "alice", good.contentHash(), "", (a, b, c, d, e) -> "x"));
        AtomicInteger stored = new AtomicInteger();
        assertEquals(DraftService.State.APPROVED, drafts.approve("t", good.id(), "bob", good.contentHash(), "", (a, b, c, d, e) -> {
            stored.incrementAndGet();
            return "pkg";
        }).state());
        assertEquals(1, stored.get());
    }

    @Test
    void translationKeepsPlaceholders() {
        SimulatedLlm llm = new SimulatedLlm().script("{\"k\":\"Hallo\"}");
        DraftService drafts = new DraftService(Dialect.POSTGRESQL, tx, audit, llm);
        DraftService.Draft d = drafts.generate("t", DraftService.Kind.TRANSLATION, "de", null, Map.of("source", Map.of("k", "Hello {name}")), "a");
        assertEquals(DraftService.State.INVALID, d.state());
    }

    @Test
    void playgroundEvaluatesFeel() {
        ExpressionPlayground p = new ExpressionPlayground(Duration.ofSeconds(20), LlmPort.disabled());
        ExpressionPlayground.Evaluation e = p.evaluate("a + 1", Map.of("a", 2));
        assertTrue(e.ok(), e.error());
        assertEquals(3, ((Number) e.value()).intValue());
        assertFalse(p.evaluate("", Map.of()).ok());
    }

    @Test
    void lowConfidenceGoesToHuman() {
        DocumentClassifier.Template t = new DocumentClassifier.Template("mail", 1, List.of("a", "b"), List.of("x"), new BigDecimal("0.8"));
        SimulatedLlm llm = new SimulatedLlm().script("{\"label\":\"a\",\"confidence\":0.5,\"fields\":{}}").script("{\"label\":\"a\",\"confidence\":0.9,\"fields\":{\"x\":\"1\"}}").script("junk");
        DocumentClassifier cl = new DocumentClassifier(Dialect.POSTGRESQL, tx, audit, llm);
        assertEquals(DocumentClassifier.Route.HUMAN, cl.classify("t", t, "r", "x").route());
        DocumentClassifier.Classification auto = cl.classify("t", t, "r", "x");
        assertEquals(DocumentClassifier.Route.AUTO, auto.route());
        assertEquals(DocumentClassifier.Route.HUMAN, cl.classify("t", t, "r", "x").route());
        cl.correct("t", auto.id(), "b", "bob");
        assertEquals(new DocumentClassifier.Evaluation(1, 0), cl.evaluate("t", "mail", 1));
    }

    @Test
    void judgeNeverPassesWhenDisabled() {
        assertEquals(LlmJudge.Status.NOT_JUDGED, new LlmJudge(LlmPort.disabled()).judge("t", "c", "o", BigDecimal.ONE).status());
        LlmJudge.Verdict v = new LlmJudge(new SimulatedLlm().script("{\"score\":0.9,\"reasoning\":\"r\"}")).judge("t", "c", "o", new BigDecimal("0.5"));
        assertEquals(LlmJudge.Status.PASSED, v.status());
        assertFalse(v.deterministic());
    }

    @Test
    void mcpServerHidesWritesUntilEnabledAndUsesCallerToken() {
        String[] token = new String[1];
        McpServer off = new McpServer((m, p, q, b, t, r) -> {
            token[0] = t;
            return new McpServer.Reply(200, "[]");
        }, x -> false);
        McpServer.Caller caller = new McpServer.Caller("t", "tok");
        assertFalse(off.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", caller).contains("complete_task"));
        assertTrue(off.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"complete_task\",\"arguments\":{\"id\":\"1\",\"reason\":\"r\"}}}", caller).contains("unknown tool"));
        off.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_tasks\"}}", caller);
        assertEquals("tok", token[0]);
    }
}
