// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.cmmn;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.sangam.modeller.cmmn.CmmnConversion.Diagnostic;
import org.sangam.modeller.cmmn.CmmnConversion.Severity;
import org.sangam.modeller.cmmn.CmmnConversion.StateMapping;
import org.sangam.modeller.cmmn.CmmnConversion.Status;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * R45 / E16 bounded CMMN 1.1 to BPMN 2.0 conversion (M-127, T43). No universal automatic conversion is promised: only
 * the subset below is converted, every other construct is an ERROR diagnostic and the model is left to manual handling
 * or to the run-off of its cases on the CMMN engine (run-off stays: sangam-flowable-ports {@code CaseLifecycleService}).
 *
 * <p><b>Supported subset</b> (each case becomes one executable BPMN process with the case id; each plan item keeps its
 * id as the BPMN activity id, so the state-migration mapping is one to one):
 * <ul>
 *   <li>plan items of {@code humanTask} (to {@code userTask}, Operaton assignment and form attributes and task listeners
 *       carried over), {@code processTask} (to {@code callActivity}, binding attributes and {@code in}/{@code out}
 *       mappings carried over), {@code decisionTask} (to {@code businessRuleTask}), {@code task} (blocking: to a
 *       {@code userTask} with a WARNING; non-blocking: a plain {@code task}), {@code milestone} (to a none
 *       intermediate throw event) and {@code stage} (to an embedded {@code subProcess}, recursively);</li>
 *   <li>at most one entry criterion per plan item, whose sentry has only plan-item on-parts on items of the same stage
 *       with the standard event {@code complete} ({@code occur} for a milestone) and no if-part: the item starts when
 *       all those items finished (a parallel join when there are several); items without an entry criterion start in
 *       parallel when the stage starts; the stage ends when every item finished.</li>
 * </ul>
 * <b>Unsupported</b> (ERROR, no BPMN): CMMN other than 1.1, case tasks, event listeners, plan fragments, discretionary
 * items (planning tables), exit criteria, several entry criteria on one item (OR semantics), if-parts, case-file
 * on-parts and task inputs/outputs, other standard events, cross-stage or cyclic sentries, repetition and
 * manual-activation rules, non-blocking human and process tasks, case-execution and variable listeners, any other
 * extension element. <b>Converted with a WARNING</b> (behaviour to review): required rules (every converted item is
 * effectively required), stages without auto-complete (a manual stage completion is not modelled), a case file model
 * (its items become plain process variables), case roles, dropped extension attributes and blocking plain tasks.
 *
 * <p>The draft has no diagram interchange (the modeller lays it out) and is never deployed by this class.
 */
public final class CmmnToBpmnConverter {

    public static final String CMMN11_NS = "http://www.omg.org/spec/CMMN/20151109/MODEL";
    public static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    public static final String OPERATON_BPMN_NS = "http://operaton.org/schema/1.0/bpmn";
    static final Set<String> CMMN_EXTENSION_NS = Set.of("http://camunda.org/schema/1.0/cmmn",
            "http://operaton.org/schema/1.0/cmmn");

    /** Extension attribute renames per definition kind (CMMN extension local name to Operaton BPMN local name). */
    static final Map<String, Map<String, String>> ATTRIBUTES = Map.of(
            "humanTask", Map.of("assignee", "assignee", "candidateUsers", "candidateUsers", "candidateGroups",
                    "candidateGroups", "formKey", "formKey", "dueDate", "dueDate", "followUpDate", "followUpDate",
                    "priority", "priority"),
            "processTask", Map.of("processBinding", "calledElementBinding", "processVersion", "calledElementVersion",
                    "processTenantId", "calledElementTenantId"),
            "decisionTask", Map.of("decisionBinding", "decisionRefBinding", "decisionVersion", "decisionRefVersion",
                    "decisionTenantId", "decisionRefTenantId", "resultVariable", "resultVariable", "mapDecisionResult",
                    "mapDecisionResult"));
    /** Extension elements carried over per definition kind (same structure in the Operaton BPMN namespace). */
    static final Map<String, Set<String>> EXTENSIONS = Map.of("humanTask", Set.of("taskListener"),
            "processTask", Set.of("in", "out"));
    static final Set<String> SUPPORTED_KINDS = Set.of("humanTask", "processTask", "decisionTask", "task", "milestone",
            "stage");
    static final Set<String> DEFINITION_KINDS = Set.of("humanTask", "processTask", "caseTask", "decisionTask", "task",
            "milestone", "stage", "timerEventListener", "userEventListener", "eventListener", "planFragment");
    /** BPMN elements a migrated instance can wait in (the target of an ACTIVE plan item). */
    static final Set<String> WAIT_STATES = Set.of("userTask", "callActivity", "subProcess");
    /** Standard attributes understood (or deliberately ignored) on a definition. */
    static final Set<String> STANDARD_ATTRIBUTES = Set.of("id", "name", "isBlocking", "processRef", "decisionRef",
            "autoComplete", "description");

    private CmmnToBpmnConverter() {
    }

    /** One plan item of a stage, after analysis. */
    record Item(String id, String name, String kind, Element definition, List<String> deps, Stage nested) {
    }

    /** One stage (or the case plan model): its id, element and plan items in document order. */
    record Stage(String id, Element element, List<Item> items) {
    }

    /** Converts the CMMN document {@code cmmn}; never throws for a model problem (it becomes a diagnostic). */
    public static CmmnConversion convert(String resource, byte[] cmmn) {
        List<Diagnostic> diag = new ArrayList<>();
        Document in;
        try {
            in = parse(cmmn);
        } catch (Exception e) {
            diag.add(new Diagnostic(Severity.ERROR, "CMMN-UNREADABLE", "", "not a readable XML document: " + e.getMessage()));
            return new CmmnConversion(resource, Status.MANUAL, null, diag, List.of());
        }
        Element root = in.getDocumentElement();
        if (!CMMN11_NS.equals(root.getNamespaceURI()) || !"definitions".equals(root.getLocalName())) {
            diag.add(new Diagnostic(Severity.ERROR, "CMMN-VERSION", "", "only CMMN 1.1 (" + CMMN11_NS
                    + ") definitions are converted; found " + root.getNamespaceURI()));
            return new CmmnConversion(resource, Status.MANUAL, null, diag, List.of());
        }
        Set<String> ids = new HashSet<>();
        collectIds(root, ids);
        Map<String, Stage> cases = new LinkedHashMap<>();
        for (Element c : children(root, "case")) {
            Element plan = first(c, "casePlanModel");
            if (plan == null) {
                diag.add(new Diagnostic(Severity.ERROR, "CMMN-NO-PLAN", c.getAttribute("id"), "the case has no casePlanModel"));
                continue;
            }
            if (first(c, "caseFileModel") != null && !children(first(c, "caseFileModel"), "caseFileItem").isEmpty()) {
                diag.add(new Diagnostic(Severity.WARNING, "CMMN-CASE-FILE", c.getAttribute("id"), "case file items "
                        + "become plain process variables of the same names; check every reader and writer"));
            }
            if (first(c, "caseRoles") != null) {
                diag.add(new Diagnostic(Severity.WARNING, "CMMN-CASE-ROLES", c.getAttribute("id"),
                        "case roles are not converted; express them as task candidate groups"));
            }
            cases.put(c.getAttribute("id"), analyseStage(plan, diag));
        }
        if (cases.isEmpty() && diag.stream().noneMatch(d -> d.severity() == Severity.ERROR)) {
            diag.add(new Diagnostic(Severity.ERROR, "CMMN-NO-CASE", "", "the definitions hold no case"));
        }
        if (diag.stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
            return new CmmnConversion(resource, Status.MANUAL, null, diag, List.of());
        }
        Document out = newDocument();
        Element defs = out.createElementNS(BPMN_NS, "bpmn:definitions");
        defs.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:bpmn", BPMN_NS);
        defs.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:operaton", OPERATON_BPMN_NS);
        defs.setAttribute("id", fresh(ids, "converted_" + (root.getAttribute("id").isBlank() ? "definitions" : root.getAttribute("id"))));
        String tns = root.getAttribute("targetNamespace");
        defs.setAttribute("targetNamespace", tns.isBlank() ? "http://sangam.org/converted" : tns);
        out.appendChild(defs);
        List<StateMapping> mapping = new ArrayList<>();
        for (Element c : children(root, "case")) {
            Stage plan = cases.get(c.getAttribute("id"));
            if (plan == null) {
                continue;
            }
            Element process = out.createElementNS(BPMN_NS, "bpmn:process");
            String caseId = c.getAttribute("id");
            process.setAttribute("id", caseId);
            if (!c.getAttribute("name").isBlank()) {
                process.setAttribute("name", c.getAttribute("name"));
            }
            process.setAttribute("isExecutable", "true");
            String ttl = extensionAttribute(c, "historyTimeToLive");
            if (ttl != null) {
                process.setAttributeNS(OPERATON_BPMN_NS, "operaton:historyTimeToLive", ttl);
            } else {
                diag.add(new Diagnostic(Severity.WARNING, "CMMN-NO-HISTORY-TTL", caseId, "the case has no "
                        + "historyTimeToLive; set operaton:historyTimeToLive on the process before deploying"));
            }
            Element doc = out.createElementNS(BPMN_NS, "bpmn:documentation");
            doc.setTextContent("Converted from CMMN case " + caseId + " (R45 bounded conversion); review required.");
            process.appendChild(doc);
            defs.appendChild(process);
            buildStage(plan, process, caseId, caseId, ids, mapping, diag);
        }
        diag.add(new Diagnostic(Severity.INFO, "BPMN-NO-DIAGRAM", "", "the draft has no diagram; open it in the "
                + "modeller to lay it out"));
        return new CmmnConversion(resource, Status.REVIEW_REQUIRED, serialize(out), diag, mapping);
    }

    // ------------------------------------------------------------------ analysis

    static Stage analyseStage(Element stage, List<Diagnostic> diag) {
        String stageId = stage.getAttribute("id");
        Map<String, Element> definitions = new HashMap<>();
        Map<String, Element> sentries = new HashMap<>();
        List<Element> planItems = new ArrayList<>();
        for (Element ch : children(stage, null)) {
            String n = ch.getLocalName();
            if (DEFINITION_KINDS.contains(n)) {
                definitions.put(ch.getAttribute("id"), ch);
            } else if ("sentry".equals(n)) {
                sentries.put(ch.getAttribute("id"), ch);
            } else if ("planItem".equals(n)) {
                planItems.add(ch);
            } else if ("planningTable".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-DISCRETIONARY", ch, "discretionary items (planning table) need a "
                        + "user's planning decision at run time"));
            } else if ("exitCriterion".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-EXIT", ch, "exit criteria terminate work early; model the "
                        + "cancellation explicitly (boundary or event sub-process) by hand"));
            } else if ("extensionElements".equals(n)) {
                checkExtensions(ch, Set.of(), diag);
            } else if (!"documentation".equals(n) && !"defaultControl".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-ELEMENT", ch, "'" + n + "' in a stage is not in the supported subset"));
            }
        }
        if (!"true".equals(stage.getAttribute("autoComplete"))) {
            diag.add(new Diagnostic(Severity.WARNING, "CMMN-STAGE-COMPLETION", stageId, "the stage has no "
                    + "autoComplete: a manual stage completion is not modelled; the BPMN scope ends when every item ended"));
        }
        checkControl(stage, diag);
        Map<String, Item> items = new LinkedHashMap<>();
        Map<String, String> kindOfItem = new HashMap<>();
        for (Element pi : planItems) {
            Element def = definitions.get(pi.getAttribute("definitionRef"));
            kindOfItem.put(pi.getAttribute("id"), def == null ? "" : def.getLocalName());
        }
        for (Element pi : planItems) {
            String id = pi.getAttribute("id");
            Element def = definitions.get(pi.getAttribute("definitionRef"));
            if (def == null) {
                diag.add(error("CMMN-DANGLING-REF", pi, "definitionRef '" + pi.getAttribute("definitionRef")
                        + "' names no definition in this stage"));
                continue;
            }
            String kind = def.getLocalName();
            if (!SUPPORTED_KINDS.contains(kind)) {
                diag.add(error("CMMN-UNSUPPORTED-" + kind.toUpperCase(java.util.Locale.ROOT), pi, "'" + kind
                        + "' plan items are not in the supported subset"));
                continue;
            }
            checkPlanItem(pi, def, kind, diag);
            List<String> deps = entryDependencies(pi, sentries, kindOfItem, diag);
            String name = pi.getAttribute("name").isBlank() ? def.getAttribute("name") : pi.getAttribute("name");
            Stage nested = "stage".equals(kind) ? analyseStage(def, diag) : null;
            items.put(id, new Item(id, name, kind, def, deps, nested));
        }
        checkCycles(stageId, items, diag);
        return new Stage(stageId, stage, List.copyOf(items.values()));
    }

    private static void checkPlanItem(Element pi, Element def, String kind, List<Diagnostic> diag) {
        for (Element ch : children(pi, null)) {
            String n = ch.getLocalName();
            if ("exitCriterion".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-EXIT", ch, "exit criteria are not in the supported subset"));
            } else if ("itemControl".equals(n)) {
                checkRules(ch, pi.getAttribute("id"), diag);
            } else if ("extensionElements".equals(n)) {
                checkExtensions(ch, Set.of(), diag);
            } else if (!"entryCriterion".equals(n) && !"documentation".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-ELEMENT", ch, "'" + n + "' on a plan item is not in the supported subset"));
            }
        }
        // a stage definition's own children (plan items, sentries, controls, extensions) are checked by analyseStage
        for (Element ch : "stage".equals(kind) ? List.<Element>of() : children(def, null)) {
            String n = ch.getLocalName();
            if ("defaultControl".equals(n)) {
                checkRules(ch, def.getAttribute("id"), diag);
            } else if ("extensionElements".equals(n)) {
                checkExtensions(ch, EXTENSIONS.getOrDefault(kind, Set.of()), diag);
            } else if ("planningTable".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-DISCRETIONARY", ch, "discretionary items are not in the supported subset"));
            } else if ("inputs".equals(n) || "outputs".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-CASE-FILE", ch, "task inputs/outputs bind case file items"));
            } else if (("processRefExpression".equals(n) || "decisionRefExpression".equals(n))) {
                diag.add(error("CMMN-UNSUPPORTED-REF-EXPRESSION", ch, "a computed process or decision reference is "
                        + "not converted"));
            } else if (!"documentation".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-ELEMENT", ch, "'" + n + "' in a " + kind + " is not in the supported subset"));
            }
        }
        if (("humanTask".equals(kind) || "processTask".equals(kind)) && "false".equals(def.getAttribute("isBlocking"))) {
            diag.add(error("CMMN-UNSUPPORTED-NON-BLOCKING", def, "a non-blocking " + kind + " completes before its "
                    + "work is done; no BPMN equivalent is chosen automatically"));
        }
        if ("processTask".equals(kind) && def.getAttribute("processRef").isBlank()
                && first(def, "processRefExpression") == null) {
            diag.add(error("CMMN-DANGLING-REF", def, "the process task has no processRef"));
        }
        if ("decisionTask".equals(kind) && def.getAttribute("decisionRef").isBlank()
                && first(def, "decisionRefExpression") == null) {
            diag.add(error("CMMN-DANGLING-REF", def, "the decision task has no decisionRef"));
        }
        if ("task".equals(kind) && !"false".equals(def.getAttribute("isBlocking"))) {
            diag.add(new Diagnostic(Severity.WARNING, "CMMN-BLOCKING-TASK", pi.getAttribute("id"), "a blocking plain "
                    + "task waits for a manual completion: converted to a userTask; assign it"));
        }
        if ("humanTask".equals(kind) && !def.getAttribute("performerRef").isBlank()) {
            diag.add(new Diagnostic(Severity.WARNING, "CMMN-ATTRIBUTE-DROPPED", def.getAttribute("id"),
                    "performerRef (a case role) is not converted"));
        }
        Map<String, String> renames = ATTRIBUTES.getOrDefault(kind, Map.of());
        NamedNodeMap attrs = def.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String ns = a.getNamespaceURI();
            String local = a.getLocalName() == null ? a.getName() : a.getLocalName();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(ns) || a.getName().startsWith("xmlns")) {
                continue;
            }
            boolean converted;
            if (ns == null) {
                converted = STANDARD_ATTRIBUTES.contains(local) || "performerRef".equals(local); // performerRef: warned above
            } else {
                converted = CMMN_EXTENSION_NS.contains(ns) && renames.containsKey(local);
            }
            if (!converted) {
                diag.add(new Diagnostic(Severity.WARNING, "CMMN-ATTRIBUTE-DROPPED", def.getAttribute("id"),
                        "attribute '" + a.getName() + "' is not converted"));
            }
        }
    }

    private static void checkControl(Element stage, List<Diagnostic> diag) {
        Element control = first(stage, "defaultControl");
        if (control != null) {
            checkRules(control, stage.getAttribute("id"), diag);
        }
    }

    private static void checkRules(Element control, String owner, List<Diagnostic> diag) {
        for (Element rule : children(control, null)) {
            switch (rule.getLocalName()) {
                case "repetitionRule" -> diag.add(error("CMMN-UNSUPPORTED-REPETITION", rule, "repetition creates new "
                        + "item instances while the case runs; model a loop by hand"));
                case "manualActivationRule" -> diag.add(error("CMMN-UNSUPPORTED-MANUAL-ACTIVATION", rule,
                        "manual activation needs a user's start decision; model it by hand"));
                case "requiredRule" -> diag.add(new Diagnostic(Severity.WARNING, "CMMN-REQUIRED-RULE", owner,
                        "every converted item is required: the BPMN scope waits for all of them"));
                default -> diag.add(error("CMMN-UNSUPPORTED-ELEMENT", rule, "'" + rule.getLocalName()
                        + "' in an item control is not in the supported subset"));
            }
        }
    }

    private static void checkExtensions(Element ext, Set<String> carried, List<Diagnostic> diag) {
        for (Element e : children(ext, null)) {
            String n = e.getLocalName();
            if (CMMN_EXTENSION_NS.contains(e.getNamespaceURI()) && carried.contains(n)) {
                continue;
            }
            String why = "caseExecutionListener".equals(n) || "variableListener".equals(n)
                    ? "case listeners run on case-execution events that a BPMN process does not raise"
                    : "unknown extension element";
            diag.add(error("CMMN-UNSUPPORTED-EXTENSION", e, "'" + n + "': " + why));
        }
    }

    /** The plan items this item waits for (its single entry criterion), with diagnostics for the rest. */
    private static List<String> entryDependencies(Element pi, Map<String, Element> sentries, Map<String, String> kindOfItem,
                                                  List<Diagnostic> diag) {
        List<Element> criteria = children(pi, "entryCriterion");
        if (criteria.isEmpty()) {
            return List.of();
        }
        if (criteria.size() > 1) {
            diag.add(error("CMMN-UNSUPPORTED-OR-ENTRY", pi, "several entry criteria (any one starts the item) are not "
                    + "in the supported subset"));
            return List.of();
        }
        Element sentry = sentries.get(criteria.get(0).getAttribute("sentryRef"));
        if (sentry == null) {
            diag.add(error("CMMN-DANGLING-REF", criteria.get(0), "sentryRef '" + criteria.get(0).getAttribute("sentryRef")
                    + "' names no sentry of this stage"));
            return List.of();
        }
        List<String> deps = new ArrayList<>();
        for (Element part : children(sentry, null)) {
            String n = part.getLocalName();
            if ("ifPart".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-IF-PART", part, "a sentry condition is re-evaluated on data changes; "
                        + "no BPMN equivalent is chosen automatically"));
            } else if ("caseFileItemOnPart".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-CASE-FILE", part, "case file on-parts are not in the supported subset"));
            } else if ("planItemOnPart".equals(n)) {
                String source = part.getAttribute("sourceRef");
                String event = first(part, "standardEvent") == null ? "" : first(part, "standardEvent").getTextContent().strip();
                String expected = "milestone".equals(kindOfItem.get(source)) ? "occur" : "complete";
                if (!kindOfItem.containsKey(source)) {
                    diag.add(error("CMMN-UNSUPPORTED-CROSS-STAGE", part, "the on-part source '" + source
                            + "' is not a plan item of the same stage"));
                } else if (!expected.equals(event)) {
                    diag.add(error("CMMN-UNSUPPORTED-EVENT", part, "standard event '" + event + "' (only '" + expected
                            + "' is converted for this source)"));
                } else if (!part.getAttribute("exitCriterionRef").isBlank() || !part.getAttribute("sentryRef").isBlank()) {
                    diag.add(error("CMMN-UNSUPPORTED-EXIT", part, "an on-part bound to an exit criterion"));
                } else if (!deps.contains(source)) {
                    deps.add(source);
                }
            } else if (!"documentation".equals(n) && !"extensionElements".equals(n)) {
                diag.add(error("CMMN-UNSUPPORTED-ELEMENT", part, "'" + n + "' in a sentry is not in the supported subset"));
            }
        }
        if (deps.isEmpty() && children(sentry, "ifPart").isEmpty()) {
            diag.add(error("CMMN-EMPTY-SENTRY", sentry, "the sentry has no on-part"));
        }
        return List.copyOf(deps);
    }

    private static void checkCycles(String stageId, Map<String, Item> items, List<Diagnostic> diag) {
        Map<String, Integer> state = new HashMap<>();
        for (String start : items.keySet()) {
            Deque<String[]> stack = new ArrayDeque<>();
            if (state.containsKey(start)) {
                continue;
            }
            stack.push(new String[] {start, "0"});
            state.put(start, 1);
            while (!stack.isEmpty()) {
                String[] top = stack.peek();
                Item it = items.get(top[0]);
                int next = Integer.parseInt(top[1]);
                List<String> deps = it == null ? List.of() : it.deps();
                if (next < deps.size()) {
                    top[1] = String.valueOf(next + 1);
                    String d = deps.get(next);
                    Integer s = state.get(d);
                    if (s == null && items.containsKey(d)) {
                        state.put(d, 1);
                        stack.push(new String[] {d, "0"});
                    } else if (s != null && s == 1) {
                        diag.add(new Diagnostic(Severity.ERROR, "CMMN-SENTRY-CYCLE", stageId, "the entry criteria of '"
                                + top[0] + "' and '" + d + "' wait for each other; none of them would ever start"));
                        return;
                    }
                } else {
                    state.put(top[0], 2);
                    stack.pop();
                }
            }
        }
    }

    // ------------------------------------------------------------------ building

    private static void buildStage(Stage stage, Element container, String caseId, String processId, Set<String> ids,
                                   List<StateMapping> mapping, List<Diagnostic> diag) {
        Document out = container.getOwnerDocument();
        BpmnFlows flows = new BpmnFlows(container, ids, stage.id());
        String start = flows.node("startEvent", stage.id() + "_start", null);
        String end = flows.node("endEvent", stage.id() + "_end", null);
        Map<String, String> entry = new HashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (Item it : stage.items()) {
            Element el = BpmnElements.element(out, it, diag);
            container.appendChild(el);
            if (it.nested() != null) {
                buildStage(it.nested(), el, caseId, processId, ids, mapping, diag);
            }
            mapping.add(new StateMapping(caseId, it.id(), it.kind(), processId, it.id(), el.getLocalName(),
                    WAIT_STATES.contains(el.getLocalName())));
            entry.put(it.id(), it.deps().size() > 1 ? flows.node("parallelGateway", it.id() + "_join", null) : it.id());
            for (String d : it.deps()) {
                dependents.computeIfAbsent(d, k -> new ArrayList<>()).add(it.id());
            }
        }
        for (Item it : stage.items()) {
            if (it.deps().size() > 1) {
                flows.flow(entry.get(it.id()), it.id());
            }
        }
        List<String> roots = stage.items().stream().filter(i -> i.deps().isEmpty()).map(Item::id).toList();
        List<String> sinks = stage.items().stream().filter(i -> !dependents.containsKey(i.id())).map(Item::id).toList();
        String sinkTarget = sinks.size() > 1 ? flows.node("parallelGateway", stage.id() + "_done", null) : end;
        if (sinks.size() > 1) {
            flows.flow(sinkTarget, end);
        }
        fanOut(flows, start, roots.isEmpty() ? List.of(end) : roots.stream().map(entry::get).toList(),
                stage.id() + "_fork");
        for (Item it : stage.items()) {
            // a dependent with one dependency is entered directly; with several, through its join gateway
            List<String> targets = dependents.containsKey(it.id())
                    ? dependents.get(it.id()).stream().map(entry::get).toList()
                    : List.of(sinkTarget);
            fanOut(flows, it.id(), targets, it.id() + "_split");
        }
    }

    private static void fanOut(BpmnFlows flows, String from, List<String> targets, String splitId) {
        if (targets.size() == 1) {
            flows.flow(from, targets.get(0));
            return;
        }
        String split = flows.node("parallelGateway", splitId, null);
        flows.flow(from, split);
        for (String t : targets) {
            flows.flow(split, t);
        }
    }

    // ------------------------------------------------------------------ helpers

    static Diagnostic error(String code, Element e, String message) {
        String id = e.getAttribute("id");
        if (id.isBlank() && e.getParentNode() instanceof Element p) {
            id = p.getAttribute("id");
        }
        return new Diagnostic(Severity.ERROR, code, id, message);
    }

    static String extensionAttribute(Element e, String local) {
        for (String ns : CMMN_EXTENSION_NS) {
            if (e.hasAttributeNS(ns, local)) {
                return e.getAttributeNS(ns, local);
            }
        }
        return null;
    }

    static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (localName == null || localName.equals(e.getLocalName()))) {
                out.add(e);
            }
        }
        return out;
    }

    static Element first(Element parent, String localName) {
        List<Element> c = children(parent, localName);
        return c.isEmpty() ? null : c.get(0);
    }

    private static void collectIds(Element e, Set<String> ids) {
        if (!e.getAttribute("id").isBlank()) {
            ids.add(e.getAttribute("id"));
        }
        for (Element c : children(e, null)) {
            collectIds(c, ids);
        }
    }

    /** {@code wanted}, or {@code wanted_2}, {@code wanted_3} ... when taken; the result is reserved. */
    static String fresh(Set<String> ids, String wanted) {
        String id = wanted;
        for (int n = 2; ids.contains(id); n++) {
            id = wanted + "_" + n;
        }
        ids.add(id);
        return id;
    }

    static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml == null ? new byte[0] : xml));
    }

    private static Document newDocument() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new IllegalStateException("no XML document builder", e);
        }
    }

    private static byte[] serialize(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(b));
            return b.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("cannot write the BPMN draft", e);
        }
    }
}
