// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.BusinessRuleTask;
import org.operaton.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.operaton.bpm.model.bpmn.instance.ScriptTask;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;
import org.operaton.bpm.model.bpmn.instance.UserTask;
import org.operaton.bpm.model.dmn.DmnModelInstance;
import org.operaton.bpm.model.dmn.instance.Decision;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PackageGitRemote;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PackageToolsPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Modeller tooling over {@link PackagePort} packages:
 * <ul>
 *   <li><b>validate</b>: parse every artifact with the Operaton model APIs and apply the Sangam profile (ADR-D-8 no
 *       in-engine scripts, GP-09 multi-instance limit, executable flag) plus the X.2.4.6 closure check (every form
 *       key and decision reference resolves inside the package);</li>
 *   <li><b>tests</b>: {@code *.test.json} resources ({@code {"decision","cases":[{"name","inputs","expected"}]}})
 *       evaluated offline against the package's DMN (R80);</li>
 *   <li><b>Git</b> (R69, M-100): the configuration (remote, branch, credential reference only) is stored; pull and push
 *       run through the host's {@link PackageGitRemote} ({@link PackageGitOperations}: fast-forward only, sync state,
 *       audit) and answer {@code 501 CAPABILITY_NOT_INSTALLED} while the host binds none;</li>
 *   <li><b>comments</b>: stored per package in the document store (kind {@code package:comment}, ref = package id),
 *       audited {@code PACKAGE_COMMENTED};</li>
 *   <li><b>assessment</b> (EC-21): classification of foreign BPMN/DMN/CMMN constructs, parsed with an XXE-safe
 *       DOM parser so Camunda, Flowable and Activiti namespaces are accepted; a CMMN file also gets the CMMN to BPMN
 *       conversion verdict ({@link CmmnConversionRows}, M-127) when sangam-modeller is installed.</li>
 * </ul>
 */
public final class OperatonPackageToolsAdapter implements PackageToolsPort {

    static final Set<String> TEXT_EXTENSIONS = Set.of(".bpmn", ".dmn", ".cmmn", ".form", ".json", ".xml",
            ".yaml", ".yml", ".txt");
    private static final int MI_LIMIT = 1000;
    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** BPMN 2.0 elements Operaton executes or accepts as diagram/model structure. */
    private static final Set<String> SUPPORTED_BPMN = Set.of("definitions", "process", "collaboration", "participant",
            "messageFlow", "laneSet", "lane", "flowNodeRef", "childLaneSet", "startEvent", "endEvent",
            "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent", "task", "userTask", "serviceTask",
            "sendTask", "receiveTask", "manualTask", "businessRuleTask", "callActivity", "subProcess", "transaction",
            "exclusiveGateway", "inclusiveGateway", "parallelGateway", "eventBasedGateway", "complexGateway",
            "sequenceFlow", "conditionExpression", "incoming", "outgoing", "messageEventDefinition",
            "timerEventDefinition", "signalEventDefinition", "errorEventDefinition", "escalationEventDefinition",
            "compensateEventDefinition", "conditionalEventDefinition", "linkEventDefinition", "terminateEventDefinition",
            "cancelEventDefinition", "timeDate", "timeDuration", "timeCycle", "condition", "message", "signal", "error",
            "escalation", "extensionElements", "documentation", "textAnnotation", "text", "association", "group",
            "category", "categoryValue", "dataObject", "dataObjectReference", "dataStore", "dataStoreReference",
            "dataInputAssociation", "dataOutputAssociation", "sourceRef", "targetRef", "ioSpecification", "dataInput",
            "dataOutput", "inputSet", "outputSet", "dataInputRefs", "dataOutputRefs", "property",
            "multiInstanceLoopCharacteristics", "loopCardinality", "completionCondition", "standardLoopCharacteristics",
            "itemDefinition", "interface", "operation", "inMessageRef", "outMessageRef", "resource", "potentialOwner",
            "humanPerformer", "resourceAssignmentExpression", "formalExpression", "script");
    /** Flowable/Activiti attributes with a direct Operaton equivalent. */
    private static final Set<String> MAPPABLE_ATTRS = Set.of("class", "expression", "delegateExpression", "assignee",
            "candidateGroups", "candidateUsers", "formKey", "async", "asyncBefore", "asyncAfter", "exclusive",
            "resultVariable", "resultVariableName", "dueDate", "priority", "initiator", "collection", "elementVariable",
            "calledElementBinding", "calledElementVersion", "decisionRef", "type", "topic", "historyTimeToLive",
            "versionTag", "isStartableInTasklist");
    private static final Set<String> VENDOR_NS = Set.of("http://camunda.org/schema/1.0/bpmn",
            "http://flowable.org/bpmn", "http://activiti.org/bpmn", "http://operaton.org/schema/1.0/bpmn");

    private final PackagePort packages;
    private final AuditPort audit;
    private final RestProperties props;
    private final ObjectMapper json = new ObjectMapper();
    /** Git configuration per package (REST_DOCUMENT, doc_id = package id) and review comments (ref = package id). */
    static final String KIND_GIT = "package:git";
    static final String KIND_COMMENT = "package:comment";

    private final org.sangam.rest.support.DocumentStore docs;
    private final PackageGitOperations git;

    public OperatonPackageToolsAdapter(PackagePort packages, AuditPort audit, RestProperties props,
                                       org.sangam.rest.support.DocumentStore docs) {
        this(packages, audit, props, docs, null);
    }

    /** {@code gitRemote}: the host's Git transport, resolved per call (M-100); null, or answering null, binds none. */
    public OperatonPackageToolsAdapter(PackagePort packages, AuditPort audit, RestProperties props,
                                       org.sangam.rest.support.DocumentStore docs,
                                       java.util.function.Supplier<PackageGitRemote> gitRemote) {
        this.git = new PackageGitOperations(packages, audit, docs, gitRemote);
        this.docs = docs;
        this.packages = packages;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ validation (R44, X.2.4.6, FLOWABLE-INTO-OPERATON FC-46)

    @Override
    public List<ValidationIssue> validate(RequestContext ctx, String packageId) {
        Map<String, byte[]> res = resources(packages.get(ctx, packageId));
        List<ValidationIssue> issues = new ArrayList<>();
        if (res.isEmpty()) {
            issues.add(new ValidationIssue("ERROR", null, null, "The package has no resources", "PACKAGE_EMPTY"));
            return issues;
        }
        List<String[]> formRefs = new ArrayList<>();
        List<String[]> decisionRefs = new ArrayList<>();
        java.util.Set<String> decisionKeys = new java.util.HashSet<>();
        for (Map.Entry<String, byte[]> r : res.entrySet()) {
            String name = r.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".bpmn") || lower.endsWith(".bpmn20.xml")) {
                validateBpmn(name, r.getValue(), issues, formRefs, decisionRefs);
            } else if (lower.endsWith(".dmn") || lower.endsWith(".dmn11.xml")) {
                try {
                    DmnModelInstance m = DmnTesting.parse(new String(r.getValue(), StandardCharsets.UTF_8));
                    for (Decision d : m.getModelElementsByType(Decision.class)) {
                        decisionKeys.add(d.getId());
                    }
                    for (Decision d : DmnTesting.decisionTables(m)) {
                        DmnTesting.hitPolicy(d);
                    }
                } catch (ApiProblem p) {
                    issues.add(new ValidationIssue("ERROR", name, null, p.detail(), "MODEL_INVALID"));
                }
            } else if (lower.endsWith(".form") || lower.endsWith(".json")) {
                try {
                    json.readTree(r.getValue());
                } catch (IOException e) {
                    issues.add(new ValidationIssue("ERROR", name, null, "Not valid JSON: " + e.getMessage(),
                            "MODEL_INVALID"));
                }
            } else if (lower.endsWith(".cmmn")) {
                issues.add(new ValidationIssue("INFO", name, null,
                        "CMMN is validated by the engine at deployment (CMMN 1.1, retire-safe)", "CMMN_DEFERRED"));
            }
        }
        // X.2.4.6 closure check: references must resolve inside the package.
        for (String[] ref : formRefs) {
            String key = ref[2];
            boolean found = res.keySet().stream().anyMatch(n -> n.equals(key + ".form") || n.endsWith("/" + key + ".form")
                    || n.equals(key));
            if (!found) {
                issues.add(new ValidationIssue("ERROR", ref[0], ref[1], "Form " + key + " is not in the package",
                        "UNRESOLVED_REFERENCE"));
            }
        }
        for (String[] ref : decisionRefs) {
            if (!decisionKeys.contains(ref[2])) {
                issues.add(new ValidationIssue("ERROR", ref[0], ref[1], "Decision " + ref[2] + " is not in the package",
                        "UNRESOLVED_REFERENCE"));
            }
        }
        return issues;
    }

    private void validateBpmn(String name, byte[] content, List<ValidationIssue> issues, List<String[]> formRefs,
                              List<String[]> decisionRefs) {
        BpmnModelInstance model;
        try {
            model = Bpmn.readModelFromStream(new ByteArrayInputStream(content));
        } catch (RuntimeException e) {
            issues.add(new ValidationIssue("ERROR", name, null, "The BPMN cannot be parsed: " + e.getMessage(),
                    "MODEL_INVALID"));
            return;
        }
        for (org.operaton.bpm.model.bpmn.instance.Process p
                : model.getModelElementsByType(org.operaton.bpm.model.bpmn.instance.Process.class)) {
            if (!p.isExecutable()) {
                issues.add(new ValidationIssue("WARNING", name, p.getId(), "Process is not executable", "NOT_EXECUTABLE"));
            }
        }
        for (ScriptTask t : model.getModelElementsByType(ScriptTask.class)) {
            boolean inline = t.getScript() != null && t.getScript().getTextContent() != null
                    && !t.getScript().getTextContent().isBlank();
            if (inline || t.getOperatonResource() != null) {
                // the engine rejects every script task (SangamBpmnParseListener); a package deploy rewrites the
                // convertible ones for the script worker (ScriptTaskDeployRewrite, decision 89), the rest cannot run
                String lang = scriptLanguage(t.getScriptFormat());
                String resource = t.getOperatonResource();
                boolean inPackage = inline || resource.startsWith("deployment://");
                if (lang != null && inPackage) {
                    issues.add(new ValidationIssue("INFO", name, t.getId(), "Runs in the script worker (ADR-D-8 script"
                            + " sandbox): rewritten at deploy to an external task on topic " + SCRIPT_TOPIC_PREFIX + lang,
                            "SCRIPT_REWRITTEN_AT_DEPLOY"));
                } else {
                    issues.add(new ValidationIssue("ERROR", name, t.getId(), lang == null
                            ? "Scripts do not run in the engine (ADR-D-8 script sandbox): format " + t.getScriptFormat()
                                    + " has no sandboxed runtime; use javascript, python, an external task or a delegate"
                            : "Scripts do not run in the engine (ADR-D-8 script sandbox): script resource " + resource
                                    + " is outside the package; inline it or use deployment://", "SCRIPT_IN_ENGINE"));
                }
            }
        }
        for (ServiceTask t : model.getModelElementsByType(ServiceTask.class)) {
            String topic = t.getOperatonTopic();
            if ("external".equals(t.getOperatonType()) && topic != null && topic.startsWith(SCRIPT_TOPIC_PREFIX)) {
                String problem = scriptConventionProblem(topic, scriptInputs(t));
                if (problem != null) {
                    issues.add(new ValidationIssue("ERROR", name, t.getId(), problem, "SCRIPT_CONVENTION_INVALID"));
                }
            }
        }
        for (ServiceTask t : model.getModelElementsByType(ServiceTask.class)) {
            boolean external = "external".equals(t.getOperatonType()) && t.getOperatonTopic() != null;
            boolean delegate = t.getOperatonClass() != null || t.getOperatonDelegateExpression() != null
                    || t.getOperatonExpression() != null;
            if (!external && !delegate) {
                issues.add(new ValidationIssue("WARNING", name, t.getId(),
                        "Service task has neither an external topic nor a delegate", "SERVICE_TASK_UNBOUND"));
            }
        }
        for (UserTask t : model.getModelElementsByType(UserTask.class)) {
            String formKey = t.getOperatonFormKey();
            if (formKey == null || formKey.isBlank()) {
                issues.add(new ValidationIssue("INFO", name, t.getId(), "User task has no form key", "NO_FORM"));
            } else if (formKey.startsWith("sangam:")) {
                formRefs.add(new String[]{name, t.getId(), formKey.substring("sangam:".length())});
            } else if (formKey.endsWith(".form")) {
                String key = formKey.substring(formKey.lastIndexOf('/') + 1);
                formRefs.add(new String[]{name, t.getId(), key.substring(0, key.length() - ".form".length())});
            }
        }
        for (BusinessRuleTask t : model.getModelElementsByType(BusinessRuleTask.class)) {
            String ref = t.getOperatonDecisionRef();
            if (ref != null && !ref.contains("${") && !ref.contains("#{")) {
                decisionRefs.add(new String[]{name, t.getId(), ref});
            }
        }
        for (MultiInstanceLoopCharacteristics mi : model.getModelElementsByType(MultiInstanceLoopCharacteristics.class)) {
            if (mi.getLoopCardinality() == null || mi.getLoopCardinality().getTextContent() == null) {
                continue;
            }
            String text = mi.getLoopCardinality().getTextContent().trim();
            try {
                long n = Long.parseLong(text);
                if (n > MI_LIMIT) {
                    ModelElementInstance parent = mi.getParentElement();
                    String id = parent == null ? null : parent.getAttributeValue("id");
                    issues.add(new ValidationIssue("ERROR", name, id, "Multi-instance cardinality " + n + " exceeds "
                            + MI_LIMIT + "; use one instance per item with aggregation (GP-09)", "MI_LIMIT"));
                }
            } catch (NumberFormatException e) {
                // An expression: checked at run time against the same limit by sangam-engine.
            }
        }
    }

    // ------------------------------------------------------------------ model tests (R80)

    @Override
    public List<TestRun> runTests(RequestContext ctx, String packageId) {
        Map<String, byte[]> res = resources(packages.get(ctx, packageId));
        Map<String, String> dmnByDecision = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> r : res.entrySet()) {
            if (r.getKey().toLowerCase(Locale.ROOT).endsWith(".dmn")) {
                String xml = new String(r.getValue(), StandardCharsets.UTF_8);
                try {
                    for (Decision d : DmnTesting.parse(xml).getModelElementsByType(Decision.class)) {
                        dmnByDecision.put(d.getId(), xml);
                    }
                } catch (ApiProblem ignored) {
                    // reported by validate()
                }
            }
        }
        List<TestRun> runs = new ArrayList<>();
        for (Map.Entry<String, byte[]> r : res.entrySet()) {
            if (!r.getKey().toLowerCase(Locale.ROOT).endsWith(".test.json")) {
                continue;
            }
            JsonNode spec;
            try {
                spec = json.readTree(r.getValue());
            } catch (IOException e) {
                runs.add(new TestRun(r.getKey(), false, "Not valid JSON: " + e.getMessage(), 0));
                continue;
            }
            String decision = spec.path("decision").asText(null);
            String xml = decision == null ? null : dmnByDecision.get(decision);
            JsonNode cases = spec.path("cases");
            if (xml == null) {
                runs.add(new TestRun(r.getKey(), false, "Decision " + decision + " is not in the package", 0));
                continue;
            }
            int i = 0;
            for (JsonNode c : cases) {
                i++;
                String caseName = r.getKey() + "#" + c.path("name").asText("case-" + i);
                long start = System.nanoTime();
                try {
                    Map<String, Object> inputs = toMap(c.path("inputs"));
                    Map<String, Object> expected = toMap(c.path("expected"));
                    DmnTesting.Evaluation ev = DmnTesting.evaluate(xml, decision, inputs);
                    boolean passed = ev.error() == null && DmnTesting.matches(expected, ev.results());
                    String message = passed ? null : ev.error() != null ? ev.error()
                            : "expected " + expected + " but got " + DmnTesting.actual(ev);
                    runs.add(new TestRun(caseName, passed, message, (System.nanoTime() - start) / 1_000_000));
                } catch (RuntimeException e) {
                    runs.add(new TestRun(caseName, false, e.getMessage(), (System.nanoTime() - start) / 1_000_000));
                }
            }
        }
        return runs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return json.convertValue(node, Map.class);
    }

    // ------------------------------------------------------------------ Git (R69)

    @Override
    public GitStatus gitStatus(RequestContext ctx, String packageId) {
        packages.get(ctx, packageId);
        GitConfig c = docs.get(ctx.tenantId(), KIND_GIT, packageId).map(d -> d.as(GitConfig.class)).orElse(null);
        return git.status(ctx, packageId, c);
    }

    @Override
    public GitStatus configureGit(RequestContext ctx, String packageId, GitConfig config) {
        PackagePort.Package p = packages.get(ctx, packageId);
        if (config == null || config.remote() == null || config.remote().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "remote is required").with("field", "remote");
        }
        if (config.remote().contains("@") && config.remote().contains(":") && config.remote().startsWith("http")) {
            throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "Credentials must not be embedded in the remote URL; use credentialRef").with("field", "remote");
        }
        String provider = config.provider() == null ? "generic" : config.provider().toLowerCase(Locale.ROOT);
        if (!Set.of("github", "gitlab", "gitea", "generic").contains(provider)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "provider must be github, gitlab, gitea or generic")
                    .with("field", "provider");
        }
        GitConfig stored = new GitConfig(config.remote(), config.branch() == null ? "main" : config.branch(),
                config.credentialRef(), provider);
        docs.tx().runInTx(() -> {
            docs.upsert(ctx.tenantId(), KIND_GIT, packageId, p.key(), provider, null, packageId, stored,
                    ctx.principalId());
            audit.record(ctx, "pkg:" + p.key(), "PACKAGE_GIT_CONFIGURED", Map.of("packageId", packageId,
                    "remote", stored.remote(), "branch", stored.branch(), "provider", provider, "actor", ctx.actor()));
        });
        return gitStatus(ctx, packageId);
    }

    @Override
    public GitSyncResult gitSync(RequestContext ctx, String packageId, String direction, GitSyncRequest request) {
        return git.sync(ctx, packageId, direction, request);
    }

    // ------------------------------------------------------------------ comments (R69)

    @Override
    public List<PackageComment> comments(RequestContext ctx, String packageId) {
        packages.get(ctx, packageId);
        return docs.all(ctx.tenantId(), KIND_COMMENT, org.sangam.rest.support.DocumentStore.Filter.ref(packageId))
                .stream().map(d -> d.as(PackageComment.class)).toList();
    }

    @Override
    public PackageComment addComment(RequestContext ctx, String packageId, AddCommentRequest request) {
        PackagePort.Package p = packages.get(ctx, packageId);
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "text is required").with("field", "text");
        }
        if (request.text().length() > 10_000) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "text is limited to 10000 characters");
        }
        PackageComment c = new PackageComment(Hashes.uuidV7().toString(), ctx.principalId(), ctx.seatId(),
                ctx.actingForSeatId(), Instant.now(), request.text(), request.elementId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("packageId", packageId);
        payload.put("commentId", c.id());
        if (c.elementId() != null) {
            payload.put("elementId", c.elementId());
        }
        payload.put("text_sha256", Hashes.hex(Hashes.sha256(c.text())));
        payload.put("actor", ctx.actor());
        docs.tx().runInTx(() -> {
            docs.insert(ctx.tenantId(), KIND_COMMENT, c.id(), c.elementId(), null, ctx.principalId(), packageId, c,
                    ctx.principalId());
            audit.record(ctx, "pkg:" + p.key(), "PACKAGE_COMMENTED", payload);
        });
        return c;
    }

    // ------------------------------------------------------------------ assessment (EC-21)

    @Override
    public List<AssessmentRow> assess(RequestContext ctx, List<AssessmentFile> files) {
        if (files == null || files.isEmpty()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "at least one file is required");
        }
        List<AssessmentRow> rows = new ArrayList<>();
        for (AssessmentFile f : files) {
            String lower = f.name() == null ? "" : f.name().toLowerCase(Locale.ROOT);
            Document doc;
            try {
                doc = safeParser().parse(new ByteArrayInputStream(f.content()));
            } catch (Exception e) {
                rows.add(new AssessmentRow(f.name(), null, "document", "REJECTED", "Not well-formed XML: " + e.getMessage()));
                continue;
            }
            Element root = doc.getDocumentElement();
            if (lower.endsWith(".dmn") || "http://www.omg.org/spec/DMN/20151101/dmn.xsd".equals(root.getNamespaceURI())
                    || String.valueOf(root.getNamespaceURI()).contains("DMN")) {
                assessDmn(f.name(), root, rows);
            } else if (lower.endsWith(".cmmn") || String.valueOf(root.getNamespaceURI()).contains("CMMN")) {
                rows.add(new AssessmentRow(f.name(), root.getAttribute("id"), "cmmn:definitions", "SUPPORTED",
                        "CMMN 1.1 runs on Operaton; Flowable plan-item extensions need sangam-flowable-ports (FLOWABLE-INTO-OPERATON FC-55..58)"));
                if (CmmnConversionRows.AVAILABLE) { // M-127: can it move to BPMN? (sangam-modeller is optional)
                    CmmnConversionRows.add(f.name(), f.content(), root.getAttribute("id"), rows);
                }
            } else {
                walkBpmn(f.name(), root, rows);
            }
        }
        return rows;
    }

    private void walkBpmn(String file, Element e, List<AssessmentRow> rows) {
        String local = e.getLocalName() == null ? e.getNodeName() : e.getLocalName();
        boolean bpmn = BPMN_NS.equals(e.getNamespaceURI());
        String id = e.getAttribute("id").isEmpty() ? null : e.getAttribute("id");
        if (bpmn) {
            if ("adHocSubProcess".equals(local)) {
                rows.add(new AssessmentRow(file, id, "bpmn:adHocSubProcess", "PATTERN",
                        "ad-hoc via sangam-flowable-ports FLOWABLE-INTO-OPERATON FC-01"));
            } else if ("scriptTask".equals(local)) {
                String format = e.getAttribute("scriptFormat");
                String lang = scriptLanguage(format);
                rows.add(lang == null
                        ? new AssessmentRow(file, id, "bpmn:scriptTask", "REJECTED", "script format "
                                + (format.isEmpty() ? "(none)" : format) + " has no sandboxed runtime (ADR-D-8 script sandbox)")
                        : new AssessmentRow(file, id, "bpmn:scriptTask", "MAPPED", "out-of-process script worker"
                                + " (ADR-D-8 script sandbox): rewritten at deploy to an external task on topic "
                                + SCRIPT_TOPIC_PREFIX + lang));
            } else if (SUPPORTED_BPMN.contains(local)) {
                if (id != null) {
                    rows.add(new AssessmentRow(file, id, "bpmn:" + local, "SUPPORTED", null));
                }
            } else {
                rows.add(new AssessmentRow(file, id, "bpmn:" + local, "REJECTED", "not supported by the engine"));
            }
            NamedNodeMap attrs = e.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                String ns = a.getNamespaceURI();
                if (ns == null || !VENDOR_NS.contains(ns)) {
                    continue;
                }
                String attr = a.getLocalName();
                if (ns.contains("operaton")) {
                    continue;
                }
                if (ns.contains("camunda")) {
                    rows.add(new AssessmentRow(file, id, "camunda:" + attr, "MAPPED", "camunda → operaton namespace"));
                } else {
                    String prefix = ns.contains("flowable") ? "flowable:" : "activiti:";
                    if (MAPPABLE_ATTRS.contains(attr)) {
                        rows.add(new AssessmentRow(file, id, prefix + attr, "MAPPED", "maps to operaton:" + attr));
                    } else {
                        rows.add(new AssessmentRow(file, id, prefix + attr, "PATTERN",
                                note(attr)));
                    }
                }
            }
        } else if (e.getNamespaceURI() != null && VENDOR_NS.contains(e.getNamespaceURI())
                && !e.getNamespaceURI().contains("operaton")) {
            String prefix = e.getNamespaceURI().contains("camunda") ? "camunda:"
                    : e.getNamespaceURI().contains("flowable") ? "flowable:" : "activiti:";
            String parentId = e.getParentNode() instanceof Element p ? owner(p) : null;
            boolean camunda = prefix.equals("camunda:");
            rows.add(new AssessmentRow(file, parentId, prefix + local, camunda || Set.of("field", "taskListener",
                    "executionListener", "in", "out", "formProperty", "properties", "property").contains(local)
                    ? "MAPPED" : "PATTERN", camunda ? "camunda → operaton namespace" : note(local)));
            return;
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element child) {
                walkBpmn(file, child, rows);
            }
        }
    }

    private static String owner(Element e) {
        Node n = e;
        while (n instanceof Element el) {
            if (!el.getAttribute("id").isEmpty()) {
                return el.getAttribute("id");
            }
            n = el.getParentNode();
        }
        return null;
    }

    private static String note(String construct) {
        return switch (construct) {
            case "skipExpression" -> "skip expressions need a sangam parse listener (FLOWABLE-INTO-OPERATON FC-13)";
            case "triggerable" -> "use sangam:triggerable (FLOWABLE-INTO-OPERATON FC-12)";
            case "eventType", "eventCorrelationParameter", "channelKey" -> "event registry via sangam-flowable-ports (FLOWABLE-INTO-OPERATON FC-02)";
            case "variableAggregation" -> "multi-instance aggregation pattern (FLOWABLE-INTO-OPERATON FC-21)";
            default -> "no direct Operaton equivalent; review and map manually";
        };
    }

    private static void assessDmn(String file, Element root, List<AssessmentRow> rows) {
        List<Element> tables = new ArrayList<>();
        collect(root, "decisionTable", tables);
        if (tables.isEmpty()) {
            rows.add(new AssessmentRow(file, root.getAttribute("id"), "dmn:definitions", "SUPPORTED", null));
        }
        for (Element t : tables) {
            String hp = t.getAttribute("hitPolicy");
            String id = t.getParentNode() instanceof Element d ? d.getAttribute("id") : null;
            if ("PRIORITY".equals(hp) || "OUTPUT ORDER".equals(hp)) {
                rows.add(new AssessmentRow(file, id, "dmn:hitPolicy " + hp, "PATTERN",
                        "hit policy " + hp + " needs the sangam DMN hit-policy handlers (FLOWABLE-INTO-OPERATON FC-54)"));
            } else {
                rows.add(new AssessmentRow(file, id, "dmn:decisionTable" + (hp.isEmpty() ? "" : " " + hp), "SUPPORTED", null));
            }
        }
    }

    private static void collect(Element e, String localName, Collection<Element> out) {
        if (localName.equals(e.getLocalName())) {
            out.add(e);
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element child) {
                collect(child, localName, out);
            }
        }
    }

    /** XXE-safe, namespace-aware DOM parser (DOCTYPE disallowed, no external entities). */
    private static DocumentBuilder safeParser() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    // ------------------------------------------------------------------ script convention (M-061, ADR-D-8 script sandbox)
    // One source in this module: ScriptTaskDeployRewrite (the deploy-time rewrite, decision 89); the worker side is
    // tools/sangam-script-worker ScriptTopics (a separate deployable; no dependency).

    static final String SCRIPT_TOPIC_PREFIX = org.sangam.rest.adapter.jdbc.ScriptTaskDeployRewrite.TOPIC_PREFIX;
    static final String SCRIPT_VAR_SOURCE = org.sangam.rest.adapter.jdbc.ScriptTaskDeployRewrite.VAR_SOURCE;
    static final String SCRIPT_VAR_SHA256 = org.sangam.rest.adapter.jdbc.ScriptTaskDeployRewrite.VAR_SHA256;

    /** The worker language of a BPMN scriptFormat, or null; engine dialects (nashorn, graal.js, jython) are not mapped. */
    static String scriptLanguage(String scriptFormat) {
        return org.sangam.rest.adapter.jdbc.ScriptTaskDeployRewrite.language(scriptFormat);
    }

    private static Map<String, String> scriptInputs(ServiceTask t) {
        Map<String, String> out = new LinkedHashMap<>();
        if (t.getExtensionElements() == null) {
            return out;
        }
        for (ModelElementInstance x : t.getExtensionElements().getElements()) {
            if (x instanceof org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputOutput io) {
                for (org.operaton.bpm.model.bpmn.instance.operaton.OperatonInputParameter p : io.getOperatonInputParameters()) {
                    out.put(p.getOperatonName(), p.getTextContent());
                }
            }
        }
        return out;
    }

    /** Why an external task on a script topic would fail in the script worker, or null when it is well formed. */
    static String scriptConventionProblem(String topic, Map<String, String> inputs) {
        String lang = topic.substring(SCRIPT_TOPIC_PREFIX.length());
        if (!"javascript".equals(lang) && !"python".equals(lang)) {
            return "Topic " + topic + " names no script language the worker runs (javascript, python)";
        }
        String b64 = inputs.get(SCRIPT_VAR_SOURCE);
        if (b64 == null || b64.isBlank()) {
            return "Input parameter " + SCRIPT_VAR_SOURCE + " (the Base64 script) is missing";
        }
        byte[] source;
        try {
            source = Base64.getMimeDecoder().decode(b64.strip());
        } catch (IllegalArgumentException e) {
            return "Input parameter " + SCRIPT_VAR_SOURCE + " is not Base64";
        }
        String declared = inputs.get(SCRIPT_VAR_SHA256);
        if (declared == null || !Hashes.hex(Hashes.sha256(source)).equalsIgnoreCase(declared.strip())) {
            return "Input parameter " + SCRIPT_VAR_SHA256 + " does not match the script (integrity check)";
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    static Map<String, byte[]> resources(PackagePort.Package p) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (p.resources() == null) {
            return out;
        }
        p.resources().forEach((name, value) -> {
            String n = name.toLowerCase(Locale.ROOT);
            boolean text = TEXT_EXTENSIONS.stream().anyMatch(n::endsWith);
            out.put(name, text ? value.getBytes(StandardCharsets.UTF_8) : Base64.getDecoder().decode(value));
        });
        return out;
    }

    private static String key(RequestContext ctx, String packageId) {
        return ctx.tenantId() + "/" + packageId;
    }
}
