// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.dynamic;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.migration.MigrationPlan;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.links.InstanceLinkService;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;
import org.sangam.ports.support.PortsProblem;
import org.sangam.ports.support.SecureXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dynamic BPMN injection into one running instance (FLOWABLE-INTO-OPERATON FC-15; Flowable {@code InjectUserTaskInProcessInstanceCmd},
 * {@code InjectEmbeddedSubProcessInProcessInstanceCmd}, {@code InjectParallelUserTaskCmd}), by the gap list's route:
 * derive a model variant, deploy it, migrate that one instance, start the injected element.
 *
 * <ol>
 *   <li>The instance's BPMN is read and the new element is added to the process (unconnected: it runs in parallel to
 *       the current tokens, like Flowable's injected parallel tasks). Fragment ids are prefixed so they cannot clash.</li>
 *   <li>The variant gets a derived key {@code <key>__dyn<N>} so the original key's latest version, and therefore new
 *       starts, are not affected (repository inflation is bounded to injected instances; R43 governance).</li>
 *   <li>The {@link InjectionPolicy} decides (R43/R49 review); then, in one engine transaction: deploy the variant,
 *       migrate the instance with {@code mapEqualActivities}, start the injected element with a modification, write
 *       {@code DYN_INJECTION} and an {@code INSTANCE_LINK} ({@code dynamic-injection}). Any failure rolls all back.</li>
 * </ol>
 * The variant has no diagram shapes for the injected element; viewers show it from the activity-instance tree.
 * Runtime patching without redeploy (Flowable {@code DynamicBpmnService}, FLOWABLE-INTO-OPERATON FC-16) is not replicated by decision.
 */
public class DynamicInjectionService {

    /** Governance hook: throw to refuse. */
    @FunctionalInterface
    public interface InjectionPolicy {
        void check(String tenantId, String processInstanceId, String kind, Map<String, Object> spec, String requestedBy);

        InjectionPolicy ALLOW_ALL = (t, p, k, s, u) -> { };
    }

    /** A user task to inject. */
    public record UserTaskSpec(String name, String assignee, List<String> candidateGroups, String formKey, String dueDate) {
    }

    public record Injection(String id, String processInstanceId, String derivedDefinitionId, String injectedActivityId) {
    }

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String OPERATON_NS = "http://operaton.org/schema/1.0/bpmn";
    /**
     * Intra-fragment references that must follow the id prefix. {@code activityRef} (compensate event definitions) is
     * included: without it a compensation throw inside an injected fragment would point at an unprefixed id, which is
     * either missing (deploy failure) or, worse, an activity of the host process (FW-09/FW-34: wrong work compensated).
     */
    private static final Set<String> REF_ATTRS = Set.of("sourceRef", "targetRef", "attachedToRef", "default", "activityRef");
    /** References to root-level definitions (message, error, signal, escalation) that a fragment cannot bring along. */
    private static final Set<String> ROOT_REF_ATTRS = Set.of("messageRef", "errorRef", "signalRef", "escalationRef");
    /** Robustness guard: injections per process instance (each one is a new definition, R43 repository inflation). */
    public static final int MAX_INJECTIONS_PER_INSTANCE = 20;

    private final ProcessEngine engine;
    private final Dialect dialect;
    private final InstanceLinkService links;
    private final InjectionPolicy policy;

    public DynamicInjectionService(ProcessEngine engine, Dialect dialect, InstanceLinkService links, InjectionPolicy policy) {
        this.engine = engine;
        this.dialect = dialect;
        this.links = links;
        this.policy = policy;
    }

    public Injection injectUserTask(String processInstanceId, UserTaskSpec spec, String requestedBy) {
        if (spec == null) {
            throw PortsProblem.invalid("user task spec is required");
        }
        // FW-33: Flowable copies the spec into model attributes verbatim, so an API caller can plant ${...} / #{...}
        // expressions that run with engine privileges. Spec values are data here: expressions are refused.
        noExpression("name", spec.name());
        noExpression("assignee", spec.assignee());
        noExpression("formKey", spec.formKey());
        noExpression("dueDate", spec.dueDate());
        if (spec.candidateGroups() != null) {
            if (spec.candidateGroups().size() > 100) {
                throw new PortsProblem("LIMIT_EXCEEDED", 422, "more than 100 candidate groups");
            }
            spec.candidateGroups().forEach(g -> noExpression("candidateGroups", g));
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", spec.name());
        s.put("assignee", spec.assignee());
        s.put("candidateGroups", spec.candidateGroups());
        s.put("formKey", spec.formKey());
        s.put("dueDate", spec.dueDate());
        return inject(processInstanceId, "user-task", s, requestedBy, (doc, process, newId) -> {
            Element ut = doc.createElementNS(BPMN_NS, prefixOf(process) + "userTask");
            ut.setAttribute("id", newId);
            ut.setAttribute("name", spec.name() == null ? "Injected task" : spec.name());
            if (spec.assignee() != null) {
                ut.setAttributeNS(OPERATON_NS, "operaton:assignee", spec.assignee());
            }
            if (spec.candidateGroups() != null && !spec.candidateGroups().isEmpty()) {
                ut.setAttributeNS(OPERATON_NS, "operaton:candidateGroups", String.join(",", spec.candidateGroups()));
            }
            if (spec.formKey() != null) {
                ut.setAttributeNS(OPERATON_NS, "operaton:formKey", spec.formKey());
            }
            if (spec.dueDate() != null) {
                ut.setAttributeNS(OPERATON_NS, "operaton:dueDate", spec.dueDate());
            }
            appendFlowElement(process, ut);
        });
    }

    /**
     * Injects an embedded sub-process whose content is the flow of {@code fragmentBpmn}'s first process (Flowable
     * dynamic sub-process). The fragment needs one none start event and at least one end event.
     */
    public Injection injectEmbeddedSubProcess(String processInstanceId, String name, byte[] fragmentBpmn, String requestedBy) {
        noExpression("name", name);
        Document fragment = SecureXml.parse(fragmentBpmn);
        Element fragProcess = (Element) fragment.getElementsByTagNameNS(BPMN_NS, "process").item(0);
        if (fragProcess == null) {
            throw new PortsProblem("FRAGMENT_INVALID", 422, "fragment has no process");
        }
        validateFragment(fragProcess);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("fragmentSha256", Hashes.hex(Hashes.sha256(fragmentBpmn)));
        return inject(processInstanceId, "embedded-sub-process", s, requestedBy, (doc, process, newId) -> {
            Element sp = doc.createElementNS(BPMN_NS, prefixOf(process) + "subProcess");
            sp.setAttribute("id", newId);
            sp.setAttribute("name", name == null ? "Injected sub-process" : name);
            NodeList kids = fragProcess.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                Node k = kids.item(i);
                if (k instanceof Element e && !"laneSet".equals(e.getLocalName())) {
                    Element copy = (Element) doc.importNode(e, true);
                    prefixIds(copy, newId + "_");
                    sp.appendChild(copy);
                }
            }
            appendFlowElement(process, sp);
        });
    }

    @FunctionalInterface
    interface Mutation {
        void apply(Document doc, Element process, String newActivityId);
    }

    private Injection inject(String processInstanceId, String kind, Map<String, Object> spec, String requestedBy, Mutation mutation) {
        PortsProblem.requireText(processInstanceId, "processInstanceId", 64);
        ProcessInstance pi = engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (pi == null) {
            throw new ProcessEngineException("process instance " + processInstanceId + " not found");
        }
        if (pi.isSuspended()) {
            throw new IllegalStateException("process instance " + processInstanceId + " is suspended; activate it before injecting");
        }
        long done = EngineTx.execute(engine, cc -> Jdbc.one(EngineTx.connection(),
                "SELECT COUNT(*) FROM DYN_INJECTION WHERE process_instance_id = ?", rs -> rs.getLong(1), processInstanceId).orElse(0L));
        if (done >= MAX_INJECTIONS_PER_INSTANCE) {
            throw new PortsProblem("LIMIT_EXCEEDED", 422, "process instance " + processInstanceId + " already has " + done
                    + " injections (limit " + MAX_INJECTIONS_PER_INSTANCE + ")");
        }
        policy.check(pi.getTenantId(), processInstanceId, kind, spec, requestedBy);
        ProcessDefinition src = engine.getRepositoryService().getProcessDefinition(pi.getProcessDefinitionId());
        Document doc;
        try (InputStream in = engine.getRepositoryService().getProcessModel(src.getId())) {
            doc = SecureXml.parse(in.readAllBytes());
        } catch (IOException e) {
            throw new ProcessEngineException("cannot read model of " + src.getId(), e);
        }
        Element process = findProcess(doc, src.getKey());
        String baseKey = src.getKey().contains("__dyn") ? src.getKey().substring(0, src.getKey().indexOf("__dyn")) : src.getKey();
        String derivedKey = baseKey + "__dyn" + Long.toString(System.currentTimeMillis(), 36);
        String newId = "dyn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mutation.apply(doc, process, newId);
        process.setAttribute("id", derivedKey);
        NodeList planes = doc.getElementsByTagNameNS("*", "BPMNPlane");
        for (int i = 0; i < planes.getLength(); i++) {
            Element p = (Element) planes.item(i);
            if (src.getKey().equals(p.getAttribute("bpmnElement"))) {
                p.setAttribute("bpmnElement", derivedKey);
            }
        }
        byte[] xml = SecureXml.write(doc);
        return EngineTx.execute(engine, cc -> {
            var db = engine.getRepositoryService().createDeployment().name("dynamic:" + processInstanceId)
                    .source("sangam-dynamic-injection").addInputStream(derivedKey + ".bpmn", new java.io.ByteArrayInputStream(xml));
            if (pi.getTenantId() != null) {
                db.tenantId(pi.getTenantId());
            }
            // the deployment is not flushed yet (one engine command): take the definition from the deploy result,
            // a repository query here would not see it
            org.operaton.bpm.engine.repository.DeploymentWithDefinitions dep = db.deployWithResult();
            ProcessDefinition derived = dep.getDeployedProcessDefinitions().get(0);
            MigrationPlan plan = engine.getRuntimeService().createMigrationPlan(src.getId(), derived.getId()).mapEqualActivities().build();
            engine.getRuntimeService().newMigration(plan).processInstanceIds(processInstanceId).execute();
            engine.getRuntimeService().createProcessInstanceModification(processInstanceId).startBeforeActivity(newId).execute();
            String id = UUID.randomUUID().toString();
            Map<String, Object> full = new LinkedHashMap<>(spec);
            full.put("injectedActivityId", newId);
            Jdbc.update(EngineTx.connection(), "INSERT INTO DYN_INJECTION(id, tenant_id, process_instance_id, source_definition_id,"
                            + " derived_definition_id, kind, spec, approved_by, created_at) VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ")",
                    id, pi.getTenantId(), processInstanceId, src.getId(), derived.getId(), kind, Json.write(full), requestedBy);
            if (links != null) {
                links.link(EngineTx.connection(), pi.getTenantId(), "dynamic-injection", null, "process", processInstanceId,
                        "definition", derived.getId(), requestedBy);
            }
            return new Injection(id, processInstanceId, derived.getId(), newId);
        });
    }

    /**
     * FW-34 fragment rules: exactly one none start event at the top level, at least one end event, and no references to
     * root-level definitions the fragment cannot bring along (they would fail at deploy or bind to the host model's
     * definitions). Compensation inside the fragment stays inside it (activityRef is prefixed).
     */
    static void validateFragment(Element fragProcess) {
        int noneStarts = 0;
        int ends = 0;
        NodeList kids = fragProcess.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element e) {
                if ("startEvent".equals(e.getLocalName()) && !hasEventDefinition(e)) {
                    noneStarts++;
                }
                if ("endEvent".equals(e.getLocalName())) {
                    ends++;
                }
            }
        }
        if (noneStarts != 1 || ends < 1) {
            throw new PortsProblem("FRAGMENT_INVALID", 422, "fragment needs exactly one none start event and at least one end event"
                    + " (found " + noneStarts + " none start, " + ends + " end)");
        }
        NodeList all = fragProcess.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            for (String a : ROOT_REF_ATTRS) {
                if (e.hasAttribute(a)) {
                    throw new PortsProblem("FRAGMENT_INVALID", 422, "fragment element " + e.getAttribute("id") + " references a root"
                            + " definition (" + a + "); declare it in the host model, or use a fragment without it");
                }
            }
        }
    }

    private static boolean hasEventDefinition(Element e) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element c && c.getLocalName() != null && c.getLocalName().endsWith("EventDefinition")) {
                return true;
            }
        }
        return false;
    }

    static void noExpression(String field, String value) {
        if (value != null && (value.contains("${") || value.contains("#{"))) {
            throw new PortsProblem("EXPRESSION_NOT_ALLOWED", 422, field + " must be plain data; expressions are not accepted in"
                    + " injected elements (FW-33)");
        }
        if (value != null && value.length() > 4000) {
            throw PortsProblem.invalid(field + " is longer than 4000 characters");
        }
    }

    /** BPMN artifacts must follow every flow element of a process (XSD order); new elements go before the first one. */
    private static final Set<String> ARTIFACTS = Set.of("association", "textAnnotation", "group");

    static void appendFlowElement(Element process, Element el) {
        for (Node n = process.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && BPMN_NS.equals(e.getNamespaceURI()) && ARTIFACTS.contains(e.getLocalName())) {
                process.insertBefore(el, e);
                return;
            }
        }
        process.appendChild(el);
    }

    private static Element findProcess(Document doc, String key) {
        NodeList ps = doc.getElementsByTagNameNS(BPMN_NS, "process");
        for (int i = 0; i < ps.getLength(); i++) {
            Element p = (Element) ps.item(i);
            if (key.equals(p.getAttribute("id"))) {
                return p;
            }
        }
        throw new ProcessEngineException("process " + key + " not found in its own model");
    }

    private static String prefixOf(Element e) {
        return e.getPrefix() == null ? "" : e.getPrefix() + ":";
    }

    private static void prefixIds(Element e, String prefix) {
        if (e.hasAttribute("id")) {
            e.setAttribute("id", prefix + e.getAttribute("id"));
        }
        for (String a : REF_ATTRS) {
            if (e.hasAttribute(a)) {
                e.setAttribute(a, prefix + e.getAttribute(a));
            }
        }
        if ("incoming".equals(e.getLocalName()) || "outgoing".equals(e.getLocalName())) {
            e.setTextContent(prefix + e.getTextContent().trim());
        }
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element c) {
                prefixIds(c, prefix);
            }
        }
    }
}
