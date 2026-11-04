// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.bpmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.persistence.deploy.Deployer;
import org.operaton.bpm.engine.impl.persistence.entity.ResourceEntity;
import org.sangam.ports.support.PortsProblem;
import org.sangam.ports.support.SecureXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pluggable pre-deploy validator (FC-46, Flowable {@code flowable-process-validation} / {@code flowable-case-validation};
 * the R44 profile and R43 closure check). Errors stop the deployment; warnings are reported. It runs as an Operaton
 * pre-deployer ({@link #deployer()}), so every deployment path (REST, app bundle, packs) is covered, and standalone
 * via {@link #validate}.
 *
 * <p>Built-in rules (complementing WS-ENGINE's parse/transform listeners, which already reject script tasks D-46,
 * CMMN event listeners D-08 unless FC-55 is on, business-rule tasks without a binding D-12, and DOCTYPE D-51 at
 * engine deploy; this validator runs <em>before</em> the engine so a bundle gets the whole report at once):
 * <ul>
 *   <li>D-51: DOCTYPE/ENTITY declarations → error (the hardened parser refuses to read them anyway).</li>
 *   <li>D-09: {@code adHocSubProcess} → error unless patch P-1 is installed.</li>
 *   <li>D-52: complex gateway, standard loop, multiple/parallel-multiple events, data stores → error (not executable on
 *       Operaton).</li>
 *   <li>FC-13: skip expressions on scopes → error.</li>
 *   <li>Flowable namespace attributes left in a model → warning (use {@code FlowableModelImporter}).</li>
 *   <li>Custom rules: {@link Rule} (EC-21 / R48 profiles, pack-specific rules).</li>
 * </ul>
 */
public class SangamModelValidator {

    public enum Severity { ERROR, WARNING }

    public record Finding(Severity severity, String code, String resource, String elementId, String message) {
    }

    /** A custom rule over the parsed DOM. */
    @FunctionalInterface
    public interface Rule {
        void check(String resource, Document doc, List<Finding> out);
    }

    public record Options(boolean adHocEnabled) {
    }

    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private static final String ACTIVITI_NS = "http://activiti.org/bpmn";
    private static final Set<String> UNSUPPORTED_BPMN = Set.of("complexGateway", "standardLoopCharacteristics", "dataStoreReference");

    private final Options options;
    private final List<Rule> rules = new ArrayList<>();

    public SangamModelValidator(Options options) {
        this.options = options;
    }

    public SangamModelValidator addRule(Rule r) {
        rules.add(r);
        return this;
    }

    public List<Finding> validate(Map<String, byte[]> resources) {
        List<Finding> out = new ArrayList<>();
        resources.forEach((name, bytes) -> out.addAll(validate(name, bytes)));
        return out;
    }

    public List<Finding> validate(String resource, byte[] bytes) {
        List<Finding> out = new ArrayList<>();
        if (resource == null || bytes == null) {
            return out;
        }
        String lower = resource.toLowerCase(Locale.ROOT);
        boolean bpmn = lower.endsWith(".bpmn") || lower.endsWith(".bpmn20.xml") || lower.endsWith(".bpmn.xml");
        boolean cmmn = lower.endsWith(".cmmn") || lower.endsWith(".cmmn11.xml") || lower.endsWith(".cmmn.xml");
        boolean dmn = lower.endsWith(".dmn") || lower.endsWith(".dmn11.xml") || lower.endsWith(".dmn.xml");
        if (!(bpmn || cmmn || dmn)) {
            return out;
        }
        if (SecureXml.hasDoctype(bytes)) {
            out.add(new Finding(Severity.ERROR, "D-51", resource, null, "DOCTYPE/ENTITY declarations are not allowed"));
            return out;
        }
        Document doc;
        try {
            doc = SecureXml.parse(bytes);
        } catch (IllegalArgumentException e) {
            out.add(new Finding(Severity.ERROR, e instanceof PortsProblem p ? p.code() : "XML", resource, null, e.getMessage()));
            return out;
        }
        walk(doc.getDocumentElement(), resource, bpmn, cmmn, out);
        for (Rule r : rules) {
            r.check(resource, doc, out);
        }
        return out;
    }

    private void walk(Element el, String resource, boolean bpmn, boolean cmmn, List<Finding> out) {
        String local = el.getLocalName() == null ? el.getTagName() : el.getLocalName();
        String id = el.getAttribute("id");
        if (bpmn) {
            if ("adHocSubProcess".equals(local) && !options.adHocEnabled()) {
                out.add(new Finding(Severity.ERROR, "D-09", resource, id, "ad-hoc sub-process needs patch P-1 (SangamBpmnParseFactory)"));
            }
            if (UNSUPPORTED_BPMN.contains(local)) {
                out.add(new Finding(Severity.ERROR, "D-52", resource, id(el), local + " is not executable on Operaton"));
            }
            if (isEventElement(local) && countEventDefinitions(el) > 1) {
                out.add(new Finding(Severity.ERROR, "D-52", resource, id, "multiple/parallel-multiple events are not executable on Operaton"));
            }
            if (!SangamBpmn.attrDom(el, SangamBpmn.SKIP_EXPRESSION).isEmpty()
                    && Set.of("subProcess", "callActivity", "adHocSubProcess", "transaction").contains(local)) {
                out.add(new Finding(Severity.ERROR, "FC-13", resource, id, "skip expressions are supported on tasks only"));
            }
        }
        for (int i = 0; i < el.getAttributes().getLength(); i++) {
            Node a = el.getAttributes().item(i);
            if (FLOWABLE_NS.equals(a.getNamespaceURI()) || ACTIVITI_NS.equals(a.getNamespaceURI())) {
                out.add(new Finding(Severity.WARNING, "IMPORT", resource, id(el), "Flowable/Activiti attribute " + a.getNodeName()
                        + " is ignored by Operaton; run FlowableModelImporter"));
                break;
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element c) {
                walk(c, resource, bpmn, cmmn, out);
            }
        }
    }

    private static boolean isExtension(Element el) {
        String ns = el.getNamespaceURI();
        return ns != null && (ns.contains("operaton.org") || ns.contains("camunda.org"));
    }

    private static boolean isEventElement(String local) {
        return Set.of("startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent").contains(local);
    }

    private static int countEventDefinitions(Element el) {
        int n = 0;
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element c) {
                String l = c.getLocalName() == null ? c.getTagName() : c.getLocalName();
                if (l.endsWith("EventDefinition")) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String operatonAttr(Element el, String name) {
        String v = el.getAttributeNS("http://operaton.org/schema/1.0/bpmn", name);
        return v.isEmpty() ? el.getAttributeNS("http://camunda.org/schema/1.0/bpmn", name) : v;
    }

    private static String id(Element el) {
        Node n = el;
        while (n instanceof Element e) {
            if (!e.getAttribute("id").isEmpty()) {
                return e.getAttribute("id");
            }
            n = n.getParentNode();
        }
        return null;
    }

    /** Pre-deployer: throws on any error finding, so nothing of the deployment is stored. */
    public Deployer deployer() {
        return deployment -> {
            List<Finding> errors = new ArrayList<>();
            Map<String, ResourceEntity> res = deployment.getResources();
            if (res != null) {
                for (ResourceEntity r : res.values()) {
                    for (Finding f : validate(r.getName(), r.getBytes())) {
                        if (f.severity() == Severity.ERROR) {
                            errors.add(f);
                        }
                    }
                }
            }
            if (!errors.isEmpty()) {
                StringBuilder sb = new StringBuilder("deployment rejected by the Sangam validator (R44):");
                errors.forEach(f -> sb.append("\n  [").append(f.code()).append("] ").append(f.resource())
                        .append(f.elementId() == null ? "" : "#" + f.elementId()).append(": ").append(f.message()));
                throw new ProcessEngineException(sb.toString());
            }
        };
    }
}
