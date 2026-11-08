// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.ai.a2a.A2a;
import org.sangam.ai.agent.AgentDefinition;
import org.sangam.ai.agent.AgentRuntime;
import org.sangam.ai.classify.DocumentClassifier;
import org.sangam.ai.draft.DraftService;
import org.sangam.ai.expr.ExpressionPlayground;
import org.sangam.ai.mcp.McpServer;
import org.sangam.ai.rag.KnowledgeBaseService;
import org.sangam.core.layer.AuthorizationPolicy;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.web.Lists;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent runs and approvals, knowledge base admin, drafts, expression help, classification review, MCP and A2A. Every
 * call is scoped to the caller's tenant; a run is visible to its owner and to {@code ai.read}.
 */
@RestController
@RequestMapping("/api/v1")
public class AiController {

    public static final String USE = "ai.use";
    public static final String READ = "ai.read";
    public static final String REVIEW = "ai.review";
    public static final String ADMIN = "ai.admin";

    private final AiApi api;
    private final PermissionPort permissions;
    private final ObjectProvider<PackagePort> packages;

    /** Browser origins allowed to call the MCP and A2A hosts ({@code sangam.ai.http-allowed-origins}); empty refuses every Origin. */
    private final java.util.Set<String> allowedOrigins;

    public AiController(AiApi api, PermissionPort permissions, ObjectProvider<PackagePort> packages,
                        @org.springframework.beans.factory.annotation.Value("${sangam.ai.http-allowed-origins:}") List<String> allowedOrigins) {
        this.api = api;
        this.permissions = permissions;
        this.packages = packages;
        this.allowedOrigins = allowedOrigins == null ? java.util.Set.of()
                : allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * DNS-rebinding guard for the MCP and A2A hosts. A request with no Origin is a non-browser client and passes; a browser
     * request passes only from a listed origin.
     */
    private void checkOrigin(HttpServletRequest req) {
        String origin = req.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw ApiProblem.forbidden("ORIGIN_NOT_ALLOWED", "origin " + origin + " may not call this endpoint");
        }
    }

    public record StartRunRequest(String agentKey, String input, String businessKey) {
    }

    public record RunView(String runId, String agentKey, String state, int stepsUsed, long tokensUsed, String rootId, String businessKey,
                          String principalId, String result) {
        static RunView of(AgentRuntime.Run r) {
            return new RunView(r.runId(), r.agentKey(), r.state().name(), r.stepsUsed(), r.tokensUsed(), r.rootId(), r.businessKey(),
                    r.principalId(), r.result());
        }
    }

    public record ReasonRequest(String reason) {
    }

    public record DecideRequest(boolean approve, String argsHash, String reason) {
    }

    public record IngestRequest(String documentId, String docVersion, String text) {
    }

    public record KbVersion(int version) {
    }

    public record IngestResult(int chunks) {
    }

    public record KbSearchRequest(String query, Integer k) {
    }

    public record GenerateDraftRequest(String packageId, String kind, String prompt, String current, Map<String, Object> context) {
    }

    public record ReviewDraftRequest(String contentHash, String note, String reason) {
        String text() {
            return note != null ? note : reason;
        }
    }

    public record EvaluateRequest(String expression, Map<String, Object> variables) {
    }

    public record ProposeRequest(String intent, String goal, Map<String, Object> variables) {
    }

    public record Proposal(String expression) {
    }

    public record ClassifyRequest(String key, int version, List<String> labels, List<String> fields, BigDecimal threshold, String sourceRef, String text) {
    }

    public record CorrectRequest(String label) {
    }

    @PostMapping("/ai/runs")
    public ResponseEntity<RunView> start(RequestContext ctx, @RequestBody StartRunRequest b) {
        permissions.require(ctx, USE);
        AgentDefinition def = api.agents().apply(b.agentKey()).orElseThrow(() -> ApiProblem.notFound("agent", b.agentKey()));
        String id = api.runtime().start(def, ctx.tenantId(), ctx.principalId(), b.input(), null, b.businessKey());
        return Lists.created("/api/v1/ai/runs/" + id, "ai-run:" + id, RunView.of(api.runtime().find(id).orElseThrow()));
    }

    @GetMapping("/ai/runs")
    public ResponseEntity<List<RunView>> runs(RequestContext ctx, @RequestParam(required = false) Integer limit) {
        permissions.require(ctx, USE);
        String owner = permissions.has(ctx, READ) ? null : ctx.principalId();
        int n = limit == null ? 100 : Math.min(Math.max(limit, 1), 500);
        return Lists.ok(api.runs().list(ctx.tenantId(), owner, n).stream().map(RunView::of).toList());
    }

    @GetMapping("/ai/runs/{id}")
    public RunView run(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, USE);
        return RunView.of(visibleRun(ctx, id));
    }

    @PostMapping("/ai/runs/{id}/cancel")
    public RunView cancel(RequestContext ctx, @PathVariable String id, @RequestBody ReasonRequest b) {
        permissions.require(ctx, USE);
        visibleRun(ctx, id);
        api.runtime().cancel(ctx.tenantId(), id, ctx.principalId(), b.reason());
        return RunView.of(api.runtime().find(id).orElseThrow());
    }

    private AgentRuntime.Run visibleRun(RequestContext ctx, String id) {
        return api.runtime().find(id)
                .filter(r -> r.tenantId().equals(ctx.tenantId()))
                .filter(r -> r.principalId().equals(ctx.principalId()) || permissions.has(ctx, READ))
                .orElseThrow(() -> ApiProblem.notFound("agent run", id));
    }

    @GetMapping("/ai/approvals")
    public ResponseEntity<List<AgentRuntime.Approval>> approvals(RequestContext ctx) {
        permissions.require(ctx, REVIEW);
        return Lists.ok(api.runtime().pendingApprovals(ctx.tenantId()));
    }

    @PostMapping("/ai/approvals/{id}/decide")
    public AgentRuntime.Approval decide(RequestContext ctx, @PathVariable String id, @RequestBody DecideRequest b) {
        permissions.require(ctx, REVIEW);
        return api.runtime().decide(ctx.tenantId(), id, ctx.principalId(), b.approve(), b.argsHash(), b.reason());
    }

    @PostMapping("/ai/knowledge-bases/{kb}/versions")
    public KbVersion newVersion(RequestContext ctx, @PathVariable String kb) {
        permissions.require(ctx, ADMIN);
        return new KbVersion(api.knowledge().newVersion(ctx.tenantId(), kb));
    }

    @PostMapping("/ai/knowledge-bases/{kb}/versions/{version}/documents")
    public IngestResult ingest(RequestContext ctx, @PathVariable String kb, @PathVariable int version, @RequestBody IngestRequest b) {
        permissions.require(ctx, ADMIN);
        try {
            return new IngestResult(api.knowledge().ingest(ctx.tenantId(), kb, version, b.documentId(), b.docVersion(), b.text()));
        } catch (IllegalStateException e) {
            throw ApiProblem.conflict("KB_VERSION_NOT_BUILDING", e.getMessage());
        }
    }

    @PostMapping("/ai/knowledge-bases/{kb}/versions/{version}/activate")
    public ResponseEntity<Void> activate(RequestContext ctx, @PathVariable String kb, @PathVariable int version) {
        permissions.require(ctx, ADMIN);
        api.knowledge().activate(ctx.tenantId(), kb, version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ai/knowledge-bases/{kb}/search")
    public ResponseEntity<List<KnowledgeBaseService.Hit>> search(RequestContext ctx, @PathVariable String kb, @RequestBody KbSearchRequest b) {
        permissions.require(ctx, USE);
        int k = b.k() == null ? 5 : Math.min(Math.max(b.k(), 1), 50);
        return Lists.ok(api.knowledge().retrieve(org.sangam.rest.modules.cases.CaseIdentity.identity(ctx), kb, b.query(), k));
    }

    @PostMapping("/ai/drafts")
    public DraftService.Draft generate(RequestContext ctx, @RequestBody GenerateDraftRequest b) {
        permissions.require(ctx, USE);
        return api.drafts().generate(ctx.tenantId(), kind(b.kind()), b.prompt(), b.current(), b.context() == null ? Map.of() : b.context(), ctx.principalId());
    }

    @GetMapping("/ai/drafts/{id}")
    public DraftService.Draft draft(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, USE);
        return api.drafts().find(ctx.tenantId(), id)
                .filter(d -> d.createdBy().equals(ctx.principalId()) || permissions.has(ctx, REVIEW))
                .orElseThrow(() -> ApiProblem.notFound("draft", id));
    }

    /** Approval stores the content as a new package in its draft state with the reviewer's identity; it never deploys. */
    @PostMapping("/ai/drafts/{id}/approve")
    public DraftService.Draft approve(RequestContext ctx, @PathVariable String id, @RequestBody ReviewDraftRequest b) {
        permissions.require(ctx, REVIEW);
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        PackagePort port = packages.getIfAvailable();
        if (port == null) {
            throw ApiProblem.notInstalled("package drafts", "sangam-rest");
        }
        return api.drafts().approve(ctx.tenantId(), id, ctx.principalId(), reviewedHash(ctx, id, b), b.text(), (tenant, kind, content, approvedBy, draftId) -> {
            String key = "ai-draft-" + draftId;
            return port.create(ctx, new PackagePort.CreatePackageRequest(key, "AI draft " + draftId, Map.of(resource(kind), content))).id();
        });
    }

    @PostMapping("/ai/drafts/{id}/reject")
    public DraftService.Draft reject(RequestContext ctx, @PathVariable String id, @RequestBody ReviewDraftRequest b) {
        permissions.require(ctx, REVIEW);
        return api.drafts().reject(ctx.tenantId(), id, ctx.principalId(), reviewedHash(ctx, id, b), b.text());
    }

    /** A draft's content never changes after generation, so without a presented hash the stored one is the hash of what the screen showed. */
    private String reviewedHash(RequestContext ctx, String id, ReviewDraftRequest b) {
        return b.contentHash() != null ? b.contentHash() : api.drafts().find(ctx.tenantId(), id)
                .orElseThrow(() -> ApiProblem.notFound("draft", id)).contentHash();
    }

    private static String resource(DraftService.Kind kind) {
        return switch (kind) {
            case BPMN -> "draft.bpmn";
            case DMN -> "draft.dmn";
            case FEEL_EXPRESSION -> "draft.feel";
            case AGENT_PROMPT -> "draft.prompt.txt";
            case APP, DASHBOARD, TRANSLATION -> "draft." + kind.name().toLowerCase(Locale.ROOT) + ".json";
        };
    }

    private static DraftService.Kind kind(String v) {
        try {
            return DraftService.Kind.valueOf(String.valueOf(v).toUpperCase(Locale.ROOT).replace("FORM", "APP"));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "unknown draft kind " + v);
        }
    }

    @PostMapping("/ai/expressions/evaluate")
    public ExpressionPlayground.Evaluation evaluate(RequestContext ctx, @RequestBody EvaluateRequest b) {
        permissions.require(ctx, USE);
        return api.playground().evaluate(b.expression(), b.variables() == null ? Map.of() : b.variables());
    }

    @PostMapping("/ai/expressions/propose")
    public Proposal propose(RequestContext ctx, @RequestBody ProposeRequest b) {
        permissions.require(ctx, USE);
        return new Proposal(api.playground().propose(ctx.tenantId(), b.intent() != null ? b.intent() : b.goal(), b.variables() == null ? Map.of() : b.variables()));
    }

    @PostMapping("/ai/classifications")
    public DocumentClassifier.Classification classify(RequestContext ctx, @RequestBody ClassifyRequest b) {
        permissions.require(ctx, USE);
        return api.classifier().classify(ctx.tenantId(), new DocumentClassifier.Template(b.key(), b.version(), b.labels(), b.fields(), b.threshold()), b.sourceRef(), b.text());
    }

    @PostMapping("/ai/classifications/{id}/correct")
    public ResponseEntity<Void> correct(RequestContext ctx, @PathVariable String id, @RequestBody CorrectRequest b) {
        permissions.require(ctx, REVIEW);
        api.classifier().correct(ctx.tenantId(), id, b.label(), ctx.principalId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ai/classifications/evaluation")
    public DocumentClassifier.Evaluation evaluation(RequestContext ctx, @RequestParam String key, @RequestParam int version) {
        permissions.require(ctx, REVIEW);
        return api.classifier().evaluate(ctx.tenantId(), key, version);
    }

    /** MCP over HTTP. Tools loop back to this API with the caller's own token. A notification gets 202 with no body. */
    @PostMapping(value = "/ai/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> mcp(RequestContext ctx, @RequestBody String json, HttpServletRequest req) {
        checkOrigin(req);
        permissions.require(ctx, USE);
        String invalid = McpServer.validateHttpHeaders(json, req.getHeader("MCP-Protocol-Version"), req.getHeader("Mcp-Method"), req.getHeader("Mcp-Name"));
        if (invalid != null) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(invalid);
        }
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7) : null;
        String reply = api.mcp().handle(json, new McpServer.Caller(ctx.tenantId(), token));
        return reply == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(reply);
    }

    @GetMapping("/ai/a2a/{agent}/card")
    public Map<String, Object> card(RequestContext ctx, @PathVariable String agent, HttpServletRequest req) {
        checkOrigin(req);
        permissions.require(ctx, USE);
        return server(ctx, agent).agentCard();
    }

    @PostMapping(value = "/ai/a2a/{agent}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String a2a(RequestContext ctx, @PathVariable String agent, @RequestBody String json, HttpServletRequest req) {
        checkOrigin(req);
        permissions.require(ctx, USE);
        return server(ctx, agent).handle(ctx.principalId(), json, req.getHeader("A2A-Version"));
    }

    private A2a.Server server(RequestContext ctx, String agent) {
        return api.a2a().apply(ctx.tenantId(), agent).orElseThrow(() -> ApiProblem.notFound("agent", agent));
    }
}
