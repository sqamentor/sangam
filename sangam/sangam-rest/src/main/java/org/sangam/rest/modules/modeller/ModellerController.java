// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.sangam.modeller.action.ActionModels;
import org.sangam.modeller.diff.ModelDiff;
import org.sangam.modeller.landscape.ProcessLandscape;
import org.sangam.modeller.lease.EditLeases;
import org.sangam.modeller.simulation.TokenWalk;
import org.sangam.modeller.template.ElementTemplates;
import org.sangam.modeller.test.BulkModelTests;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.sangam.rest.web.Lists;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1}: process landscape, token walk, edit leases (a model file is its package id), element templates,
 * action models, stored test reports and bundles. The extension catalogue is {@link ExtensionCatalogueController}.
 */
@RestController
@RequestMapping("/api/v1")
public class ModellerController {

    public static final String ACTION_PUBLISH = "modeller.action.publish";
    public static final String ACTION_INVOKE = "modeller.action.invoke";
    public static final String LEASE_TAKEOVER = "modeller.lease.takeover";

    private final PackagePort packages;
    private final PermissionPort permissions;
    private final EditLeases leases;
    private final ElementTemplates templates;
    private final ActionModels actions;
    private final BulkModelTests tests;

    public ModellerController(PackagePort packages, PermissionPort permissions, EditLeases leases, ElementTemplates templates,
                              ActionModels actions, BulkModelTests tests) {
        this.packages = packages;
        this.permissions = permissions;
        this.leases = leases;
        this.templates = templates;
        this.actions = actions;
        this.tests = tests;
    }

    /** {@code xml}, {@code state} and {@code choice} are the step-by-step form; {@code state} comes from the previous answer. */
    public record TokenWalkRequest(String bpmnXml, String xml, String file, String processId, Map<String, List<String>> choices, Integer maxSteps,
                                   Map<String, Object> state, String choice) {
    }

    public record TokenWalkChoice(String flowId, String name) {
    }

    public record TokenWalkView(TokenWalk.Status status, List<TokenWalk.Move> moves, List<String> visited, TokenWalk.Choice pending, List<String> warnings,
                                List<String> current, List<TokenWalkChoice> choices, boolean done, Map<String, Object> state) {
    }

    public record LeaseView(String fileId, String holder, Long fence, java.time.Instant expiresAt, String requestedBy, boolean mine, boolean editable) {
    }

    public record LandscapeNode(String id, String name, String kind) {
    }

    public record LandscapeEdge(String from, String to, String kind, String callActivityId, String binding, String version) {
    }

    public record LandscapeView(List<LandscapeNode> nodes, List<LandscapeEdge> edges, List<String> unparsable, List<List<String>> cycles) {
    }

    public record TemplateView(String key, String id, String name, ElementTemplates.Kind kind, int version, List<String> appliesTo, Map<String, Object> document) {
    }

    public record ActionView(String key, int version, String name, String label, ActionModels.Scope scope, String scopeType, String permission, String formKey,
                             ActionModels.TargetType targetType, String target, String processKey, boolean active) {
    }

    public record RunActionRequest(String subjectRef, Map<String, Object> input, String reason) {
    }

    public record FenceRequest(Long fence, String packageId) {
    }

    public record TakeoverRequest(String formerDraft, String reason) {
    }

    public record TemplateRequest(ElementTemplates.Kind kind, Map<String, Object> document, String key, String name, String content) {
    }

    public record ApplyTemplateRequest(String elementType, Map<String, Object> values) {
    }

    public record PublishActionRequest(String key, String label, String name, String scope, String scopeType, String permission,
                                       String formKey, Object form, ActionModels.TargetType targetType, String target, String processKey) {
    }

    public record InvokeActionRequest(String targetRef, Map<String, Object> input, String clientKey) {
    }

    public record ExportRequest(String packageRevision, List<BulkModelTests.TestCase> cases, List<Map<String, Object>> mocks) {
    }

    public record BundleRequest(String bundle) {
    }

    @GetMapping("/landscape")
    public LandscapeView landscape(RequestContext ctx) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        List<ProcessLandscape.Model> models = new ArrayList<>();
        Page<PackagePort.Package> page = packages.list(ctx, null, null, Page.PageRequest.of(0, 500));
        for (PackagePort.Package listed : page.items()) {
            PackagePort.Package p = listed.resources() != null ? listed : packages.get(ctx, listed.id());
            if (p.resources() != null) {
                p.resources().forEach((name, content) -> {
                    if (name.endsWith(".bpmn") || name.endsWith(".bpmn20.xml")) {
                        models.add(new ProcessLandscape.Model(p.id(), name, content));
                    }
                });
            }
        }
        ProcessLandscape.Graph g = ProcessLandscape.build(models);
        return new LandscapeView(g.processes().stream().map(p -> new LandscapeNode(p, p, "PROCESS")).toList(),
                g.edges().stream().map(e -> new LandscapeEdge(e.fromProcess(), e.target(), e.kind().name(), e.callActivityId(), e.binding(), e.version())).toList(),
                g.unparsable(), g.cycles());
    }

    @PostMapping("/models/token-walk")
    public TokenWalkView tokenWalk(RequestContext ctx, @RequestBody TokenWalkRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return walk(b);
    }

    /** The walk of {@link #tokenWalk}, without the permission check. */
    @SuppressWarnings("unchecked")
    static TokenWalkView walk(TokenWalkRequest b) {
        int steps = b.maxSteps() == null ? 1000 : Math.min(Math.max(b.maxSteps(), 1), 10_000);
        String xml = b.bpmnXml() != null ? b.bpmnXml() : b.xml();
        if (xml == null || xml.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "bpmnXml or xml is required");
        }
        Map<String, List<String>> choices = new java.util.LinkedHashMap<>(b.choices() == null ? Map.of() : b.choices());
        Object pendingGateway = null;
        if (b.state() != null) {
            if (b.state().get("choices") instanceof Map<?, ?> earlier) {
                ((Map<String, Object>) earlier).forEach((k, v) -> choices.put(k, v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(String.valueOf(v))));
            }
            pendingGateway = b.state().get("pendingGateway");
        }
        if (b.choice() != null && pendingGateway != null) {
            // appended: an element visited again (a loop, a non-interrupting boundary event) keeps its earlier choices,
            // since every step walks again from the start with all choices
            List<String> earlier = choices.getOrDefault(String.valueOf(pendingGateway), List.of());
            List<String> all = new ArrayList<>(earlier);
            all.add(b.choice());
            choices.put(String.valueOf(pendingGateway), all);
        }
        TokenWalk.Result r = TokenWalk.walk(xml, b.processId(), choices, steps);
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("choices", choices);
        List<TokenWalkChoice> options = List.of();
        if (r.pending() != null) {
            state.put("pendingGateway", r.pending().gatewayId());
            options = r.pending().options().stream().map(o -> new TokenWalkChoice(o, o)).toList();
        }
        List<String> current = r.pending() != null ? List.of(r.pending().gatewayId())
                : r.visited() == null || r.visited().isEmpty() ? List.of() : List.of(r.visited().get(r.visited().size() - 1));
        return new TokenWalkView(r.status(), r.moves(), r.visited(), r.pending(), r.warnings(), current, options, r.status() != TokenWalk.Status.NEEDS_CHOICE, state);
    }

    /** M-125 {@code ModelDiffRequest}: a file name and the two texts (null or empty = new or deleted file). */
    public record ModelDiffRequest(String resource, String before, String after) {
    }

    /** Contract limits of {@code ModelDiffRequest}. */
    static final int MAX_RESOURCE_LENGTH = 255;
    static final int MAX_MODEL_BYTES = 5 * 1024 * 1024;

    /**
     * M-125 {@code diffModels}: semantic diff of two versions of one model file ({@link ModelDiff}); reads only, stores
     * nothing. The answer is {@code ModelDiffReport} ({@link ModelDiff.Result#toJson()}).
     */
    @PostMapping("/models/diff")
    public Map<String, Object> diffModels(RequestContext ctx, @RequestBody ModelDiffRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return diff(b);
    }

    /** The checks and the diff of {@link #diffModels}, without the permission check. */
    static Map<String, Object> diff(ModelDiffRequest b) {
        if (b == null || b.resource() == null || b.resource().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "resource is required");
        }
        if (b.resource().length() > MAX_RESOURCE_LENGTH) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "resource is longer than " + MAX_RESOURCE_LENGTH);
        }
        byte[] before = utf8(b.before(), "before");
        byte[] after = utf8(b.after(), "after");
        try {
            return ModelDiff.compare(b.resource(), before, after).toJson();
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("MODEL_UNREADABLE", e.getMessage());
        }
    }

    private static byte[] utf8(String text, String side) {
        if (text == null) {
            return null;
        }
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_MODEL_BYTES) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", side + " is larger than 5 MiB");
        }
        return bytes;
    }

    @GetMapping("/models/by-file/lease")
    public ResponseEntity<EditLeases.Lease> lease(RequestContext ctx, @RequestParam("file") String file) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return leases.current(ctx.tenantId(), file).map(ResponseEntity::ok)
                .orElseThrow(() -> ApiProblem.notFound("edit lease", file));
    }

    @PostMapping("/models/by-file/lease")
    public EditLeases.Outcome acquire(RequestContext ctx, @RequestParam("file") String file) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        return leases.acquire(ctx.tenantId(), file, ctx.principalId());
    }

    /** The console's name for {@link #acquire}; a model file is its package id, so a body {@code packageId} is not needed. */
    @PostMapping("/models/by-file/lease/acquire")
    public LeaseView acquireView(RequestContext ctx, @RequestParam("file") String file, @RequestBody(required = false) FenceRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        EditLeases.Outcome o = leases.acquire(ctx.tenantId(), file, ctx.principalId());
        return view(ctx, o.lease(), o.editable());
    }

    @PostMapping("/models/by-file/lease/heartbeat")
    public LeaseView heartbeat(RequestContext ctx, @RequestParam("file") String file, @RequestBody(required = false) FenceRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        leases.heartbeat(ctx.tenantId(), file, ctx.principalId(), fence(ctx, file, b));
        EditLeases.Lease l = leases.current(ctx.tenantId(), file).orElse(null);
        return view(ctx, l, l != null && ctx.principalId().equals(l.holder()));
    }

    @PostMapping("/models/by-file/lease/release")
    public ResponseEntity<Void> release(RequestContext ctx, @RequestParam("file") String file, @RequestBody(required = false) FenceRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        leases.release(ctx.tenantId(), file, ctx.principalId(), fence(ctx, file, b));
        return ResponseEntity.noContent().build();
    }

    /** Without a presented fence, the caller's own current lease supplies it; anyone else's lease still fails the fence check. */
    private long fence(RequestContext ctx, String file, FenceRequest b) {
        if (b != null && b.fence() != null) {
            return b.fence();
        }
        return leases.current(ctx.tenantId(), file).filter(l -> ctx.principalId().equals(l.holder())).map(EditLeases.Lease::fence)
                .orElseThrow(EditLeases.StaleEditor::new);
    }

    private LeaseView view(RequestContext ctx, EditLeases.Lease l, boolean editable) {
        return l == null ? new LeaseView(null, null, null, null, null, false, editable)
                : new LeaseView(l.fileId(), l.holder(), l.fence(), l.expiresAt(), l.requestedBy(), ctx.principalId().equals(l.holder()), editable);
    }

    @PostMapping("/models/by-file/lease/request-takeover")
    public ResponseEntity<Void> requestTakeover(RequestContext ctx, @RequestParam("file") String file) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        leases.requestTakeover(ctx.tenantId(), file, ctx.principalId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/models/by-file/lease/takeover")
    public EditLeases.Lease takeover(RequestContext ctx, @RequestParam("file") String file, @RequestBody TakeoverRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        return leases.takeover(ctx.tenantId(), file, ctx.principalId(), permissions.has(ctx, LEASE_TAKEOVER), b.formerDraft(), b.reason());
    }

    @GetMapping("/templates")
    public ResponseEntity<List<TemplateView>> templates(RequestContext ctx, @RequestParam(required = false) ElementTemplates.Kind kind) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        List<ElementTemplates.Kind> kinds = kind != null ? List.of(kind) : List.of(ElementTemplates.Kind.values());
        return Lists.ok(kinds.stream().flatMap(k -> templates.latest(ctx.tenantId(), k).stream()).map(ModellerController::view).toList());
    }

    @GetMapping("/templates/{key}")
    public TemplateView template(RequestContext ctx, @PathVariable String key, @RequestParam(required = false) Integer version) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return view(templates.find(ctx.tenantId(), key, version).orElseThrow(() -> ApiProblem.notFound("template", key)));
    }

    /** Either {@code document}, or {@code key}, {@code name} and {@code content}: JSON content is the document, other text is kept under {@code content}. */
    @PostMapping("/templates")
    public TemplateView publishTemplate(RequestContext ctx, @RequestBody TemplateRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        if (b.document() != null) {
            doc.putAll(b.document());
        } else if (b.content() != null && b.content().trim().startsWith("{")) {
            doc.putAll(org.sangam.integration.connector.Json.readMap(b.content()));
        } else if (b.content() != null) {
            doc.put("content", b.content());
        }
        if (b.key() != null) {
            doc.put("id", b.key());
        }
        if (b.name() != null) {
            doc.put("name", b.name());
        }
        return view(templates.publish(ctx.tenantId(), b.kind(), doc, ctx.principalId()));
    }

    private static TemplateView view(ElementTemplates.Template t) {
        Object name = t.document() == null ? null : t.document().get("name");
        return new TemplateView(t.id(), t.id(), name == null ? t.id() : String.valueOf(name), t.kind(), t.version(), t.appliesTo(), t.document());
    }

    @PostMapping("/templates/{id}/apply")
    public ElementTemplates.Applied applyTemplate(RequestContext ctx, @PathVariable String id, @RequestParam(required = false) Integer version,
            @RequestBody ApplyTemplateRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        ElementTemplates.Template t = templates.find(ctx.tenantId(), id, version)
                .orElseThrow(() -> ApiProblem.notFound("template", id));
        return ElementTemplates.apply(t, b.elementType(), b.values() == null ? Map.of() : b.values());
    }

    /** With a scope: what the caller may invoke on the object. Without: every active action, for those who publish them. */
    @GetMapping("/actions")
    public ResponseEntity<List<ActionView>> actions(RequestContext ctx, @RequestParam(required = false) String scope,
            @RequestParam(required = false) String scopeType, @RequestParam(required = false) String targetRef) {
        if (scope == null || scope.isBlank()) {
            permissions.require(ctx, ACTION_PUBLISH);
            return Lists.ok(actions.listActive(ctx.tenantId()).stream().map(ModellerController::view).toList());
        }
        permissions.require(ctx, ACTION_INVOKE);
        ActionModels.Scope s = scope(scope);
        return Lists.ok(ModellerRequestScope.with(ctx, () -> actions.available(ctx.tenantId(), ctx.principalId(), s, scopeType, targetRef, a -> true))
                .stream().map(ModellerController::view).toList());
    }

    @PostMapping("/actions")
    public ActionView publishAction(RequestContext ctx, @RequestBody PublishActionRequest b) {
        permissions.require(ctx, ACTION_PUBLISH);
        String form = b.formKey();
        if (form == null && b.form() instanceof String text) {
            form = text;
        } else if (form == null && b.form() instanceof Map<?, ?> m && m.get("formKey") != null) {
            form = String.valueOf(m.get("formKey"));
        }
        String target = b.target() != null ? b.target() : b.processKey();
        ActionModels.TargetType type = b.targetType() != null ? b.targetType() : ActionModels.TargetType.BPMN;
        return view(actions.publish(ctx.tenantId(), b.key(), b.label() != null ? b.label() : b.name(), scope(b.scope()), b.scopeType(), b.permission(), form, type,
                target, ctx.principalId()));
    }

    /**
     * The console's form of invoke. The operation is idempotent per request key. SL-167 / owner decision 62: the request's
     * {@code reason} is recorded in the ACTION_INVOKED audit entry.
     */
    @PostMapping("/actions/{key}/run")
    public ActionModels.Invocation runAction(RequestContext ctx, @PathVariable String key, @RequestBody RunActionRequest b) {
        permissions.require(ctx, ACTION_INVOKE);
        if (b.subjectRef() == null || b.subjectRef().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "subjectRef is required");
        }
        String clientKey = ctx.idempotencyKey() != null ? ctx.idempotencyKey() : java.util.UUID.randomUUID().toString();
        return ModellerRequestScope.with(ctx, () -> actions.invoke(ctx.tenantId(), ctx.principalId(), key, b.subjectRef(),
                b.input() == null ? Map.of() : b.input(), clientKey, b.reason()));
    }

    @DeleteMapping("/actions/{key}")
    public ResponseEntity<Void> retireAction(RequestContext ctx, @PathVariable String key, @RequestParam String reason) {
        permissions.require(ctx, ACTION_PUBLISH);
        if (reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This action requires a reason");
        }
        if (!actions.retire(ctx.tenantId(), key, ctx.principalId(), reason)) {
            throw ApiProblem.notFound("action", key);
        }
        return ResponseEntity.noContent().build();
    }

    private static ActionView view(ActionModels.Action a) {
        return new ActionView(a.key(), a.version(), a.label(), a.label(), a.scope(), a.scopeType(), a.permission(), a.formKey(), a.targetType(), a.target(),
                a.targetType() == ActionModels.TargetType.BPMN ? a.target() : null, true);
    }

    private static ActionModels.Scope scope(String v) {
        try {
            return ActionModels.Scope.valueOf(String.valueOf(v).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "scope must be CASE, PROCESS or TASK");
        }
    }

    @PostMapping("/actions/{key}/invoke")
    public ActionModels.Invocation invokeAction(RequestContext ctx, @PathVariable String key, @RequestBody InvokeActionRequest b) {
        permissions.require(ctx, ACTION_INVOKE);
        return ModellerRequestScope.with(ctx, () -> actions.invoke(ctx.tenantId(), ctx.principalId(), key, b.targetRef(),
                b.input() == null ? Map.of() : b.input(), b.clientKey() != null ? b.clientKey() : ctx.idempotencyKey()));
    }

    @GetMapping("/packages/{id}/tests/reports")
    public ResponseEntity<List<Map<String, Object>>> reports(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return Lists.ok(tests.reports(ctx.tenantId(), id));
    }

    @PostMapping("/packages/{id}/tests/export")
    public Map<String, Object> export(RequestContext ctx, @PathVariable String id, @RequestBody ExportRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        String revision = b.packageRevision() != null ? b.packageRevision() : Long.toString(packages.get(ctx, id).revision());
        return Map.of("bundle", BulkModelTests.exportBundle(id, revision, b.cases() == null ? List.of() : b.cases(),
                b.mocks() == null ? List.of() : b.mocks()));
    }

    @PostMapping("/packages/tests/verify-bundle")
    public Map<String, Object> verifyBundle(RequestContext ctx, @RequestBody BundleRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
        return BulkModelTests.verifyBundle(b.bundle());
    }
}
