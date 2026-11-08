// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.sangam.ai.agent.AgentRuntime;
import org.sangam.ai.classify.DocumentClassifier;
import org.sangam.ai.draft.DraftService;
import org.sangam.ai.rag.KnowledgeBaseService;
import org.sangam.rest.modules.ModulePaging;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's view of sangam-ai: agent runs, approvals, knowledge bases, the draft list and classification review, with
 * the field names the screens use. Each route applies the same checks as {@link AiController}.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiUiController {

    private final AiApi api;
    private final PermissionPort permissions;

    public AiUiController(AiApi api, PermissionPort permissions) {
        this.api = api;
        this.permissions = permissions;
    }

    public record AgentRunView(String runId, String agentKey, String status, String businessKey, Instant startedAt, int stepsUsed, long tokensUsed,
                               String principalId, String result) {
    }

    public record AgentStepView(int seq, String kind, Object tool, Object status, Object tokens, Instant at, Map<String, Object> payload) {
    }

    public record ApprovalView(String approvalId, String runId, String tool, String summary, String status, Instant requestedAt, String requestedBy,
                               String argsJson, String argsHash, String decidedBy, String reason) {
    }

    public record DraftRow(String draftId, String packageId, String kind, String status, String createdBy, Instant createdAt) {
    }

    public record DecisionRequest(String reason) {
    }

    public record PublishKbRequest(String key, String text, Map<String, Object> metadata) {
    }

    public record ReviewRequest(String decision, String label) {
    }

    @GetMapping("/agent-runs")
    public ResponseEntity<List<AgentRunView>> runs(RequestContext ctx, @RequestParam(required = false) String agentKey,
            @RequestParam(required = false) String status, @RequestParam(required = false) String businessKey,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, AiController.USE);
        String owner = permissions.has(ctx, AiController.READ) ? null : ctx.principalId();
        List<AgentRunView> rows = api.runs().list(ctx.tenantId(), owner, 500).stream()
                .filter(r -> agentKey == null || agentKey.isBlank() || agentKey.equals(r.agentKey()))
                .filter(r -> status == null || status.isBlank() || status.equalsIgnoreCase(r.state().name()))
                .filter(r -> businessKey == null || businessKey.isBlank() || businessKey.equals(r.businessKey()))
                .map(this::view).toList();
        return ModulePaging.slice(rows, page, size);
    }

    @GetMapping("/agent-runs/{runId}")
    public AgentRunView run(RequestContext ctx, @PathVariable String runId) {
        permissions.require(ctx, AiController.USE);
        return view(visibleRun(ctx, runId));
    }

    @GetMapping("/agent-runs/{runId}/steps")
    public ResponseEntity<List<AgentStepView>> steps(RequestContext ctx, @PathVariable String runId) {
        permissions.require(ctx, AiController.USE);
        visibleRun(ctx, runId);
        return Lists.ok(api.runtime().stepViews(runId).stream().map(s -> new AgentStepView(s.seq(), s.kind(), first(s.payload(), "tool", "toolName", "name"),
                s.payload().get("status"), first(s.payload(), "tokens", "tokensUsed"), s.at(), s.payload())).toList());
    }

    @GetMapping("/agent-approvals")
    public ResponseEntity<List<ApprovalView>> approvals(RequestContext ctx, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, AiController.REVIEW);
        String state = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        return ModulePaging.slice(api.runtime().approvals(ctx.tenantId(), state).stream().map(AiUiController::view).toList(), page, size);
    }

    @PostMapping("/agent-approvals/{approvalId}/approve")
    public ApprovalView approve(RequestContext ctx, @PathVariable String approvalId, @RequestBody(required = false) DecisionRequest b) {
        return decide(ctx, approvalId, true, b);
    }

    @PostMapping("/agent-approvals/{approvalId}/reject")
    public ApprovalView reject(RequestContext ctx, @PathVariable String approvalId, @RequestBody(required = false) DecisionRequest b) {
        return decide(ctx, approvalId, false, b);
    }

    /** The arguments of an approval never change, so the stored hash is the hash of what the screen showed. */
    private ApprovalView decide(RequestContext ctx, String approvalId, boolean approve, DecisionRequest b) {
        permissions.require(ctx, AiController.REVIEW);
        AgentRuntime.ApprovalView current = api.runtime().approvals(ctx.tenantId(), null).stream()
                .filter(a -> a.approval().approvalId().equals(approvalId)).findFirst()
                .orElseThrow(() -> ApiProblem.notFound("agent approval", approvalId));
        AgentRuntime.Approval done = api.runtime().decide(ctx.tenantId(), approvalId, ctx.principalId(), approve, current.approval().argsHash(), b == null ? null : b.reason());
        return view(new AgentRuntime.ApprovalView(done, current.requestedAt()));
    }

    @GetMapping("/knowledge-bases")
    public ResponseEntity<List<KnowledgeBaseService.KbInfo>> knowledgeBases(RequestContext ctx) {
        permissions.require(ctx, AiController.ADMIN);
        return Lists.ok(api.knowledge().list(ctx.tenantId()));
    }

    /** One step publish: a new version holding this text, activated once ingested. */
    @PostMapping("/knowledge-bases")
    public ResponseEntity<KnowledgeBaseService.KbInfo> publish(RequestContext ctx, @RequestBody PublishKbRequest b) {
        permissions.require(ctx, AiController.ADMIN);
        if (b.key() == null || b.key().isBlank() || b.text() == null || b.text().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "key and text are required");
        }
        int version = api.knowledge().newVersion(ctx.tenantId(), b.key());
        api.knowledge().ingest(ctx.tenantId(), b.key(), version, b.key(), Integer.toString(version), b.text());
        api.knowledge().activate(ctx.tenantId(), b.key(), version);
        KnowledgeBaseService.KbInfo info = api.knowledge().list(ctx.tenantId()).stream().filter(k -> k.key().equals(b.key())).findFirst().orElseThrow();
        return Lists.created("/api/v1/ai/knowledge-bases/" + b.key(), "ai-kb:" + b.key(), info);
    }

    @PostMapping("/knowledge-bases/{key}/retire")
    public ResponseEntity<Void> retire(RequestContext ctx, @PathVariable String key, @RequestBody(required = false) DecisionRequest b) {
        permissions.require(ctx, AiController.ADMIN);
        String reason = b == null || b.reason() == null || b.reason().isBlank() ? null : b.reason().trim();
        if (reason == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "a reason is required to retire a knowledge base");
        }
        if (!api.knowledge().retire(ctx.tenantId(), key, ctx.principalId(), reason)) {
            throw ApiProblem.notFound("active knowledge base", key);
        }
        return ResponseEntity.noContent().build();
    }

    /** {@code packageId} comes from the stored draft (one lookup per row; the summary query does not carry it). */
    @GetMapping("/drafts")
    public ResponseEntity<List<DraftRow>> drafts(RequestContext ctx, @RequestParam(required = false) String status,
            @RequestParam(required = false) String packageId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, AiController.USE);
        String owner = permissions.has(ctx, AiController.REVIEW) ? null : ctx.principalId();
        String state = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        return ModulePaging.slice(api.drafts().list(ctx.tenantId(), owner, state).stream()
                .map(d -> new DraftRow(d.draftId(), api.drafts().find(ctx.tenantId(), d.draftId()).map(x -> x.packageId()).orElse(null),
                        d.kind().name(), d.state().name(), d.createdBy(), d.createdAt()))
                .filter(r -> packageId == null || packageId.isBlank() || packageId.equals(r.packageId())).toList(), page, size);
    }

    @GetMapping("/classifications")
    public ResponseEntity<List<DocumentClassifier.Row>> classifications(RequestContext ctx, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, AiController.REVIEW);
        return ModulePaging.slice(api.classifier().list(ctx.tenantId()).stream()
                .filter(r -> status == null || status.isBlank() || status.equalsIgnoreCase(r.status())).toList(), page, size);
    }

    /** ACCEPT records the current or given label as correct. RETURN records a corrected label; a table has no "returned" state to hold one without. */
    @PostMapping("/classifications/{id}/review")
    public DocumentClassifier.Row review(RequestContext ctx, @PathVariable String id, @RequestBody ReviewRequest b) {
        permissions.require(ctx, AiController.REVIEW);
        DocumentClassifier.Row row = api.classifier().list(ctx.tenantId()).stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiProblem.notFound("classification", id));
        String decision = String.valueOf(b.decision()).toUpperCase(Locale.ROOT);
        boolean hasLabel = b.label() != null && !b.label().isBlank();
        if (!decision.equals("ACCEPT") && !decision.equals("RETURN")) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "decision must be ACCEPT or RETURN");
        }
        if (decision.equals("RETURN") && !hasLabel) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "RETURN needs the corrected label");
        }
        String label = hasLabel ? b.label() : row.label();
        api.classifier().correct(ctx.tenantId(), id, label, ctx.principalId());
        return api.classifier().list(ctx.tenantId()).stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiProblem.notFound("classification", id));
    }

    private AgentRunView view(AgentRuntime.Run r) {
        return new AgentRunView(r.runId(), r.agentKey(), r.state().name(), r.businessKey(), api.runtime().startedAt(r.runId()).orElse(null),
                r.stepsUsed(), r.tokensUsed(), r.principalId(), r.result());
    }

    private static ApprovalView view(AgentRuntime.ApprovalView v) {
        AgentRuntime.Approval a = v.approval();
        return new ApprovalView(a.approvalId(), a.runId(), a.toolName(), a.toolName(), a.state(), v.requestedAt(), a.requestedBy(), a.argsJson(), a.argsHash(), a.decidedBy(), a.reason());
    }

    private AgentRuntime.Run visibleRun(RequestContext ctx, String id) {
        return api.runtime().find(id)
                .filter(r -> r.tenantId().equals(ctx.tenantId()))
                .filter(r -> r.principalId().equals(ctx.principalId()) || permissions.has(ctx, AiController.READ))
                .orElseThrow(() -> ApiProblem.notFound("agent run", id));
    }

    private static Object first(Map<String, Object> m, String... keys) {
        Map<String, Object> safe = m == null ? new HashMap<>() : m;
        for (String k : keys) {
            if (safe.get(k) != null) {
                return safe.get(k);
            }
        }
        return null;
    }
}
