import pathlib, sys
R = pathlib.Path(sys.argv[1]); M = R / 'sangam-rest/src/main/java/org/sangam/rest/modules'
def rep(p, a, b):
    s = p.read_text(encoding='utf-8')
    if b in s: return
    assert a in s, (str(p), a)
    p.write_text(s.replace(a, b, 1), encoding='utf-8')

# --- configurations and advice
rep(M/'ai/AiRestConfiguration.java', '@Import({AiController.class, AiProblemAdvice.class})', '@Import({AiController.class, AiUiController.class, AiProblemAdvice.class})')
rep(M/'ai/AiProblemAdvice.java', 'assignableTypes = AiController.class', 'assignableTypes = {AiController.class, AiUiController.class}')
rep(M/'ops/OpsRestConfiguration.java', '@Import(OpsController.class)', '@Import({OpsController.class, OpsUiController.class})')
rep(M/'modeller/ModellerRestConfiguration.java', '@Import({ModellerController.class, ModellerProblemAdvice.class})', '@Import({ModellerController.class, ServiceMocksController.class, ModellerProblemAdvice.class})')
rep(M/'modeller/ModellerProblemAdvice.java', 'assignableTypes = ModellerController.class', 'assignableTypes = {ModellerController.class, ServiceMocksController.class}')

# --- AI controller field adapters
A = M/'ai/AiController.java'
rep(A, 'public record GenerateDraftRequest(String kind, String prompt, String current, Map<String, Object> context) {', 'public record GenerateDraftRequest(String packageId, String kind, String prompt, String current, Map<String, Object> context) {')
rep(A, 'public record ReviewDraftRequest(String contentHash, String note) {', 'public record ReviewDraftRequest(String contentHash, String note, String reason) {\n        String text() {\n            return note != null ? note : reason;\n        }')
rep(A, 'public record ProposeRequest(String intent, Map<String, Object> variables) {', 'public record ProposeRequest(String intent, String goal, Map<String, Object> variables) {')
rep(A, 'api.playground().propose(ctx.tenantId(), b.intent(),', 'api.playground().propose(ctx.tenantId(), b.intent() != null ? b.intent() : b.goal(),')
rep(A, 'return api.drafts().approve(ctx.tenantId(), id, ctx.principalId(), b.contentHash(), b.note(), (', 'return api.drafts().approve(ctx.tenantId(), id, ctx.principalId(), reviewedHash(ctx, id, b), b.text(), (')
rep(A, 'return api.drafts().reject(ctx.tenantId(), id, ctx.principalId(), b.contentHash(), b.note());', 'return api.drafts().reject(ctx.tenantId(), id, ctx.principalId(), reviewedHash(ctx, id, b), b.text());')
rep(A, '    private static String resource(DraftService.Kind kind) {', '''    /** A draft's content never changes after generation, so without a presented hash the stored one is the hash of what the screen showed. */
    private String reviewedHash(RequestContext ctx, String id, ReviewDraftRequest b) {
        return b.contentHash() != null ? b.contentHash() : api.drafts().find(ctx.tenantId(), id)
                .orElseThrow(() -> ApiProblem.notFound("draft", id)).contentHash();
    }

    private static String resource(DraftService.Kind kind) {''')
rep(A, 'String.valueOf(v).toUpperCase(Locale.ROOT));', 'String.valueOf(v).toUpperCase(Locale.ROOT).replace("FORM", "APP"));')

# --- channels
C = M/'channels/ChannelsController.java'
rep(C, 'b.retentionClass(), b.participants()', 'b.retentionClass() == null ? "standard" : b.retentionClass(), b.participants()')
rep(C, '    @PostMapping("/conversations/{id}/participants")', '''    /** Only conversations the caller participates in. */
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationService.Conversation>> list(RequestContext ctx, @RequestParam(required = false) String caseRef,
            @RequestParam(defaultValue = "100") int limit) {
        permissions.require(ctx, READ);
        return Lists.ok(conversations.list(ctx.tenantId(), ctx.principalId(), caseRef, Math.min(Math.max(limit, 1), 500)));
    }

    @PostMapping("/conversations/{id}/participants")''')
rep(C, '    @PostMapping("/opt-ins")', '''    @GetMapping("/opt-ins")
    public ResponseEntity<List<OptInRegistry.OptIn>> optIns(RequestContext ctx, @RequestParam(required = false) String channel) {
        permissions.require(ctx, ADMIN);
        return Lists.ok(optIns.list(ctx.tenantId(), channel));
    }

    @PostMapping("/opt-ins")''')

# --- modeller
D = M/'modeller/ModellerController.java'
s = D.read_text(encoding='utf-8')
if 'TokenWalkView' not in s:
    a = s.index('    public record TokenWalkRequest('); b = s.index('    public record FenceRequest(')
    s = s[:a] + '''    /** {@code xml}, {@code state} and {@code choice} are the step-by-step form; {@code state} comes from the previous answer. */
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

''' + s[b:]
    s = s.replace('public record FenceRequest(long fence) {', 'public record FenceRequest(Long fence, String packageId) {')
    s = s.replace('public record TemplateRequest(ElementTemplates.Kind kind, Map<String, Object> document) {', 'public record TemplateRequest(ElementTemplates.Kind kind, Map<String, Object> document, String key, String name, String content) {')
    s = s.replace('public record PublishActionRequest(String key, String label, ActionModels.Scope scope, String scopeType, String permission,\n                                       String formKey, ActionModels.TargetType targetType, String target) {',
                  'public record PublishActionRequest(String key, String label, String name, String scope, String scopeType, String permission,\n                                       String formKey, Object form, ActionModels.TargetType targetType, String target, String processKey) {')
    # landscape
    s = s.replace('public ProcessLandscape.Graph landscape(', 'public LandscapeView landscape(')
    s = s.replace('        return ProcessLandscape.build(models);', '''        ProcessLandscape.Graph g = ProcessLandscape.build(models);
        return new LandscapeView(g.processes().stream().map(p -> new LandscapeNode(p, p, "PROCESS")).toList(),
                g.edges().stream().map(e -> new LandscapeEdge(e.fromProcess(), e.target(), e.kind().name(), e.callActivityId(), e.binding(), e.version())).toList(),
                g.unparsable(), g.cycles());''')
    # token walk
    a = s.index('    public TokenWalk.Result tokenWalk('); b = s.index('    @GetMapping("/models/{file}/lease")')
    s = s[:a] + '''    @SuppressWarnings("unchecked")
    public TokenWalkView tokenWalk(RequestContext ctx, @RequestBody TokenWalkRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_READ);
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
            choices.put(String.valueOf(pendingGateway), List.of(b.choice()));
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

''' + s[b:]
    # leases
    a = s.index('    @PostMapping("/models/{file}/lease/heartbeat")'); b = s.index('    @PostMapping("/models/{file}/lease/request-takeover")')
    s = s[:a] + '''    /** The console's name for {@link #acquire}; a model file is its package id, so a body {@code packageId} is not needed. */
    @PostMapping("/models/{file}/lease/acquire")
    public LeaseView acquireView(RequestContext ctx, @PathVariable String file, @RequestBody(required = false) FenceRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        EditLeases.Outcome o = leases.acquire(ctx.tenantId(), file, ctx.principalId());
        return view(ctx, o.lease(), o.editable());
    }

    @PostMapping("/models/{file}/lease/heartbeat")
    public LeaseView heartbeat(RequestContext ctx, @PathVariable String file, @RequestBody(required = false) FenceRequest b) {
        permissions.require(ctx, Permissions.PACKAGE_AUTHOR);
        leases.heartbeat(ctx.tenantId(), file, ctx.principalId(), fence(ctx, file, b));
        EditLeases.Lease l = leases.current(ctx.tenantId(), file).orElse(null);
        return view(ctx, l, l != null && ctx.principalId().equals(l.holder()));
    }

    @PostMapping("/models/{file}/lease/release")
    public ResponseEntity<Void> release(RequestContext ctx, @PathVariable String file, @RequestBody(required = false) FenceRequest b) {
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

''' + s[b:]
    # templates
    a = s.index('    @GetMapping("/templates")'); b = s.index('    @PostMapping("/templates/{id}/apply")')
    s = s[:a] + '''    @GetMapping("/templates")
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
            doc.putAll(org.sangam.core.layer.Json.readMap(b.content()));
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

''' + s[b:]
    # actions
    a = s.index('    @GetMapping("/actions")'); b = s.index('    @PostMapping("/actions/{key}/invoke")')
    s = s[:a] + '''    /** With a scope: what the caller may invoke on the object. Without: every active action, for those who publish them. */
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

    /** The console's form of invoke. The operation is idempotent per request key. */
    @PostMapping("/actions/{key}/run")
    public ActionModels.Invocation runAction(RequestContext ctx, @PathVariable String key, @RequestBody RunActionRequest b) {
        permissions.require(ctx, ACTION_INVOKE);
        if (b.subjectRef() == null || b.subjectRef().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "subjectRef is required");
        }
        String clientKey = ctx.idempotencyKey() != null ? ctx.idempotencyKey() : java.util.UUID.randomUUID().toString();
        return ModellerRequestScope.with(ctx, () -> actions.invoke(ctx.tenantId(), ctx.principalId(), key, b.subjectRef(), b.input() == null ? Map.of() : b.input(), clientKey));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/actions/{key}")
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

''' + s[b:]
    D.write_text(s, encoding='utf-8')

# --- 835 entry
P = M/'connectorshealth/HealthHooksPort.java'
rep(P, '    /** {@code POST /api/v1/hooks/fhir/{channel}};', '''    /** A received 835: inbox messages written, or the poison item recorded when the file is unreadable. */
    record RemittanceResult(int messages, Long poisonItemId) {
    }

    /** Routes one 835 interchange for the caller's tenant. */
    RemittanceResult remittance835(String tenantId, String principal, String x12);

    /** {@code POST /api/v1/hooks/fhir/{channel}};''')
H = M/'connectorshealth/HealthHooksController.java'
rep(H, '    private final HealthHooksPort hooks;\n\n    public HealthHooksController(HealthHooksPort hooks) {\n        this.hooks = hooks;\n    }',
'''    private final HealthHooksPort hooks;
    private final org.sangam.rest.port.PermissionPort permissions;

    public HealthHooksController(HealthHooksPort hooks, org.sangam.rest.port.PermissionPort permissions) {
        this.hooks = hooks;
        this.permissions = permissions;
    }

    /** An authenticated hand-over of a received 835 (an SFTP collector or an operator). Always the caller's tenant. */
    @PostMapping(value = "/api/v1/connectors-health/x12/835", consumes = {"application/edi-x12", MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<HealthHooksPort.RemittanceResult> remittance835(org.sangam.rest.port.RequestContext ctx, @RequestBody String x12) {
        permissions.require(ctx, org.sangam.rest.support.Permissions.OPS_WRITE);
        HealthHooksPort.RemittanceResult r = hooks.remittance835(ctx.tenantId(), ctx.principalId(), x12);
        return ResponseEntity.status(r.poisonItemId() != null ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.ACCEPTED).body(r);
    }''')
B = R/'sangam-server/src/main/java/org/sangam/server/bridge/HealthHooksBridge.java'
rep(B, 'private final CdsHooksService cds;\n\n    public HealthHooksBridge(Map<String, FhirSubscriptionReceiver> channels, CdsHooksService cds) {\n        this.channels = Map.copyOf(channels);\n        this.cds = cds;\n    }',
'''private final CdsHooksService cds;
    private final Remittance835Router remittance;

    public HealthHooksBridge(Map<String, FhirSubscriptionReceiver> channels, CdsHooksService cds, Remittance835Router remittance) {
        this.channels = Map.copyOf(channels);
        this.cds = cds;
        this.remittance = remittance;
    }

    @Override
    public RemittanceResult remittance835(String tenantId, String principal, String x12) {
        if (remittance == null) {
            throw ApiProblem.notInstalled("835 routing", "the integration inbox and engine");
        }
        return remittance.route(tenantId, principal, x12);
    }''')
W = R/'sangam-server/src/main/java/org/sangam/server/config/HealthConnectorsWiring.java'
rep(W, 'ObjectProvider<InboxService> inbox, ObjectProvider<SecretResolver> secrets, Environment env) {\n        Map<String, FhirSubscriptionReceiver> receivers',
 'ObjectProvider<InboxService> inbox, ObjectProvider<SecretResolver> secrets, Environment env,\n            ObjectProvider<org.operaton.bpm.engine.ProcessEngine> engine, ObjectProvider<org.sangam.integration.inbox.UniqueStartGuard> uniqueStart,\n            ObjectProvider<org.sangam.integration.inbox.PoisonInbound> poison) {\n        Map<String, FhirSubscriptionReceiver> receivers')
rep(W, 'return new HealthHooksBridge(receivers, cds);', '''org.sangam.server.bridge.Remittance835Router remittance = null;
        if (in != null && engine.getIfAvailable() != null && uniqueStart.getIfAvailable() != null && poison.getIfAvailable() != null) {
            in.registerHandler(org.sangam.server.bridge.Remittance835Router.EVENT_TYPE,
                    org.sangam.server.bridge.Remittance835Router.handler(engine.getIfAvailable(), uniqueStart.getIfAvailable()));
            remittance = new org.sangam.server.bridge.Remittance835Router(in, poison.getIfAvailable());
        }
        return new HealthHooksBridge(receivers, cds, remittance);''')

# --- contract
def app(f, code, marker):
    p = R/'sangam-rest/api/routes'/f; s = p.read_text(encoding='utf-8')
    if marker not in s: p.write_text(s.rstrip() + '\n\n' + code + '\n', encoding='utf-8')
app('ai.py', r'''# The console's routes (AiUiController).
_U = {"required": False, "content": {"application/json": {"schema": typ('obj')}}}
_P = [q('page', 'int'), q('size', 'int')]
op('get', '/ai/agent-runs', 'aiUiListRuns', 'AI', 'List agent runs (console)', 'Own runs; all of the tenant with ai.read.', _R, params=[q('agentKey'), q('status'), q('businessKey')] + _P, resp=(200, _LIST), perm='ai.use')
op('get', '/ai/agent-runs/{runId}', 'aiUiGetRun', 'AI', 'Get an agent run (console)', 'Visible to its owner and to ai.read.', _R, params=[pp('runId')], resp=(200, _ANY), perm='ai.use')
op('get', '/ai/agent-runs/{runId}/steps', 'aiUiRunSteps', 'AI', 'Steps of an agent run', 'Visible to its owner and to ai.read.', _R, params=[pp('runId')], resp=(200, _LIST), perm='ai.use')
op('get', '/ai/agent-approvals', 'aiUiListApprovals', 'AI', 'List approvals', 'The tenant only.', _R, params=[q('status')] + _P, resp=(200, _LIST), perm='ai.review')
op('post', '/ai/agent-approvals/{approvalId}/approve', 'aiUiApprove', 'AI', 'Approve a tool call', 'Not by the requester.', _R, params=[pp('approvalId')], body=_U, resp=(200, _ANY), cmd='ai.approval.approve', perm='ai.review')
op('post', '/ai/agent-approvals/{approvalId}/reject', 'aiUiReject', 'AI', 'Reject a tool call', 'Not by the requester.', _R, params=[pp('approvalId')], body=_U, resp=(200, _ANY), cmd='ai.approval.reject', perm='ai.review')
op('get', '/ai/knowledge-bases', 'aiUiListKb', 'AI', 'List knowledge bases', 'Active version, else newest.', _R, resp=(200, _LIST), perm='ai.admin')
op('post', '/ai/knowledge-bases', 'aiUiPublishKb', 'AI', 'Publish a knowledge base', 'A new version with this text, activated.', _R, body=_OBJ, resp=(201, _ANY), cmd='ai.kb.publish', perm='ai.admin', created=True)
op('post', '/ai/knowledge-bases/{key}/retire', 'aiUiRetireKb', 'AI', 'Retire a knowledge base', 'Retrieval stops.', _R, params=[pp('key')], body=_U, resp=(204, None), cmd='ai.kb.retire', perm='ai.admin')
op('get', '/ai/drafts', 'aiUiListDrafts', 'AI', 'List drafts', 'Own drafts; all of the tenant with ai.review.', _R, params=[q('status')] + _P, resp=(200, _LIST), perm='ai.use')
op('get', '/ai/classifications', 'aiUiListClassifications', 'AI', 'List classifications', 'The tenant only.', _R, params=[q('status')] + _P, resp=(200, _LIST), perm='ai.review')
op('post', '/ai/classifications/{id}/review', 'aiUiReviewClassification', 'AI', 'Review a classification', 'ACCEPT or RETURN.', _R, params=[pp('id')], body=_OBJ, resp=(200, _ANY), cmd='ai.classification.review', perm='ai.review')''', 'aiUiListRuns')
app('ops.py', r'''# The console's routes (OpsUiController).
_OP = [q('page', 'int'), q('size', 'int')]
_OB = {"required": True, "content": {"application/json": {"schema": typ('obj')}}}
op('get', '/search', 'uiSearch', _T, 'Global search', 'Flat rows, filtered by the policy compiler.', _OR, params=[q('q', required=True)] + _OP, resp=(200, _OLIST), perm='search.use')
op('get', '/ops/usage', 'uiOpsUsage', _T, 'Usage metrics', 'The caller tenant; last 30 days by default.', _OR, params=[q('from', 'date'), q('to', 'date')], resp=(200, _OLIST), perm='ops.tenant.read')
op('get', '/ops/outliers', 'uiOpsOutliers', _T, 'Slow-path associations', 'Over server allow-listed variables only.', _OR, params=[q('processDefinitionKey'), q('activityId')], resp=(200, _OLIST), perm='ops.tenant.read')
op('get', '/ops/variables', 'uiOpsVariables', _T, 'Tenant variables', 'As rows.', _OR, params=[q('scope'), q('name')] + _OP, resp=(200, _OLIST), perm='ops.tenant.read')
op('put', '/ops/variables', 'uiOpsSetVariable', _T, 'Set a variable', 'TENANT scope only.', _OR, body=_OB, resp=(204, None), cmd='ops.variable.set.ui', perm='ops.tenant.admin')
op('delete', '/ops/variables/{id}', 'uiOpsDeleteVariable', _T, 'Delete a variable', 'Needs a reason.', _OR, params=[pp('id'), q('reason', required=True)], resp=(204, None), cmd='ops.variable.delete.ui', perm='ops.tenant.admin')
op('get', '/ops/secrets', 'uiOpsSecrets', _T, 'Secrets', 'Metadata only.', _OR, params=_OP, resp=(200, _OLIST), perm='ops.tenant.admin')
op('put', '/ops/secrets/{name}', 'uiOpsPutSecret', _T, 'Set a secret', 'The value is write-only.', _OR, params=[pp('name')], body=_OB, resp=(204, None), cmd='ops.secret.put.ui', perm='ops.tenant.admin')
op('delete', '/ops/secrets/{name}', 'uiOpsDeleteSecret', _T, 'Delete a secret', 'Needs a reason.', _OR, params=[pp('name'), q('reason', required=True)], resp=(204, None), cmd='ops.secret.delete.ui', perm='ops.tenant.admin')
op('get', '/ops/sequences', 'uiOpsSequences', _T, 'Sequences', 'All sequence tables.', _OR, resp=(200, _OLIST), perm='ops.tenant.read')
op('post', '/ops/sequences/{name}/advance', 'uiOpsAdvance', _T, 'Advance a sequence', 'Forward only.', _OR, params=[pp('name')], body=_OB, resp=(204, None), cmd='ops.sequence.advance.ui', perm='ops.tenant.admin')
op('get', '/ops/index-jobs', 'uiOpsIndexJobs', _T, 'Index jobs', 'The caller tenant.', _OR, params=_OP, resp=(200, _OLIST), perm='ops.tenant.read')
op('post', '/ops/index-jobs', 'uiOpsStartIndexJob', _T, 'Rebuild or check the index', 'REBUILD needs ops.tenant.admin.', _OR, body=_OB, resp=(200, _OANY), cmd='ops.index.start.ui', perm='ops.tenant.read')
op('get', '/ops/migration-status', 'uiOpsMigration', _T, 'Migration checkpoints', 'Platform operators.', _OR, params=[q('source')] + _OP, resp=(200, _OLIST), perm='ops.read')
op('get', '/ops/retention/plan', 'uiOpsRetentionPlan', _T, 'Preview retention', 'With a hash of the plan.', _OR, resp=(200, _OANY), perm='ops.tenant.read')
op('post', '/ops/retention/execute', 'uiOpsRetentionExecute', _T, 'Execute retention', 'Refused when the plan changed since the preview.', _OR, body=_OB, resp=(200, _OANY), cmd='ops.retention.execute.ui', perm='ops.tenant.admin')''', 'uiSearch')
app('channels.py', r'''op("get", CC, "listConversations", "Channels", "List conversations", "Only conversations the caller participates in; caseRef narrows to a case.", params=[q("caseRef"), q("limit", "int")], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm="channels.read")
op("get", "/channels/opt-ins", "listChannelOptIns", "Channels", "List opt-ins", "The tenant's mappings, without evidence.", params=[q("channel")], resp=(200, {"application/json": {"schema": {"type": "array", "items": {}}}}), perm="channels.admin")''', 'listConversations')
app('modeller.py', r'''_MB = {"required": True, "content": {"application/json": {"schema": typ('obj')}}}
_MU = {"required": False, "content": {"application/json": {"schema": typ('obj')}}}
_ML = {"application/json": {"schema": {"type": "array", "items": {}}}}
op("get", "/templates/{key}", "getElementTemplate", "Modeller", "Get a template", "Latest version unless one is named.", MR, params=[pp("key"), q("version", "int")], resp=(200, "object"), perm="package.read")
op("post", ML + "/acquire", "acquireModelLeaseUi", "Modeller", "Acquire the edit lease (console)", "Same as POST on the lease.", MR, params=[pp("file")], body=_MU, resp=(200, "object"), cmd="modeller.lease.acquire.ui", perm="package.author")
op("post", "/actions/{key}/run", "runActionModel", "Modeller", "Run an action", "Permission checked on the subject; idempotent per request key.", MR, params=[pp("key")], body=_MB, resp=(200, "object"), cmd="modeller.action.run", perm="modeller.action.invoke")
op("delete", "/actions/{key}", "retireActionModel", "Modeller", "Retire an action", "Needs a reason; audited.", MR, params=[pp("key"), q("reason", required=True)], resp=(204, None), cmd="modeller.action.retire", perm="modeller.action.publish")
op("get", "/service-mocks", "listServiceMocks", "Modeller", "List service mocks", "Not on a production profile.", MR, params=[q("page", "int"), q("size", "int")], resp=(200, _ML), perm="ops.read")
op("post", "/service-mocks", "createServiceMock", "Modeller", "Add a service mock", "This node, until restart.", MR, body=_MB, resp=(201, "object"), cmd="modeller.mock.create", perm="ops.write")
op("put", "/service-mocks/global", "setServiceMocksGlobal", "Modeller", "Toggle all mocks", "This node.", MR, body=_MB, resp=(200, "object"), cmd="modeller.mock.global", perm="ops.write")
op("patch", "/service-mocks/{id}", "toggleServiceMock", "Modeller", "Toggle a mock's service", "Affects every rule of the service.", MR, params=[pp("id")], body=_MB, resp=(200, "object"), cmd="modeller.mock.toggle", perm="ops.write")''', 'runActionModel')
app('connectorshealth.py', r'''op("post", "/connectors-health/x12/835", "receiveRemittance835", TAGS[-1][0], "Hand over a received 835", "Routes each claim to its waiting case, else starts the later-remittance process once per trace number. An unreadable file becomes a poison item (422).", body={"required": True, "content": {"text/plain": {"schema": {"type": "string"}}, "application/edi-x12": {"schema": {"type": "string"}}}}, resp=(202, "object"), cmd="connectorshealth.remittance.receive", perm="ops.write")''', 'receiveRemittance835')
print('ok')
