// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Deploy-time rewrite of BPMN script tasks into the script-worker convention (M-061; ADR-D-8 script sandbox; owner
 * decisions 53 and 89). The engine runs no script (SangamBpmnParseListener rejects every script task), so just before
 * a package is deployed each {@code scriptTask} in a supported format becomes an external {@code serviceTask}:
 * <ul>
 *   <li>{@code operaton:type="external"}, {@code operaton:topic="sangam.script.<language>"};</li>
 *   <li>input parameters {@code sangamScriptB64} (the UTF-8 script text, Base64, so the expression language never
 *       evaluates {@code ${...}} inside it), {@code sangamScriptSha256} (lower-case hex SHA-256 of that text, checked
 *       by the worker) and, when the task had one, {@code sangamScriptResultVariable};</li>
 *   <li>everything else of the task is kept: id, name, documentation, flows, boundary events (by id), DI, async flags,
 *       loop characteristics, listeners and existing input/output mappings.</li>
 * </ul>
 * The stored package is not touched: only the bytes handed to the engine change, and the approved package hash covers
 * the script as authored. Any other format (groovy, nashorn, graal.js, jython, none ...) is refused with 422, as is a
 * script outside the package. The worker side of the convention is {@code tools/sangam-script-worker}
 * ({@code ScriptTopics}).
 */
public final class ScriptTaskDeployRewrite {

    public static final String TOPIC_PREFIX = "sangam.script.";
    public static final String VAR_SOURCE = "sangamScriptB64";
    public static final String VAR_SHA256 = "sangamScriptSha256";
    public static final String VAR_RESULT = "sangamScriptResultVariable";

    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String OPERATON_NS = "http://operaton.org/schema/1.0/bpmn";
    static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
    private static final String DEPLOYMENT_PREFIX = "deployment://";

    /** Engine-neutral format names only; engine dialects (Java interop, Python 2) are not mapped. */
    private static final Map<String, String> FORMATS = Map.of("javascript", "javascript", "js", "javascript",
            "ecmascript", "javascript", "python", "python", "python3", "python", "py", "python");

    private ScriptTaskDeployRewrite() {
    }

    /** The worker language of a BPMN {@code scriptFormat} (case-insensitive), or null when no worker runs it. */
    public static String language(String scriptFormat) {
        return scriptFormat == null ? null : FORMATS.get(scriptFormat.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * @param resources the package resources to deploy (name to bytes); not modified
     * @return a new map: BPMN resources with script tasks rewritten, every other resource byte-identical
     * @throws ApiProblem 422 when a script task cannot be rewritten
     */
    public static Map<String, byte[]> rewrite(Map<String, byte[]> resources) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> r : resources.entrySet()) {
            String lower = r.getKey().toLowerCase(Locale.ROOT);
            boolean bpmn = lower.endsWith(".bpmn") || lower.endsWith(".bpmn20.xml");
            byte[] bytes = r.getValue();
            out.put(r.getKey(), bpmn && bytes != null && contains(bytes, "scriptTask")
                    ? rewriteBpmn(r.getKey(), bytes, resources) : bytes);
        }
        return out;
    }

    private static byte[] rewriteBpmn(String name, byte[] bytes, Map<String, byte[]> resources) {
        Document doc;
        try {
            doc = parser().newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw ApiProblem.unprocessable("MODEL_INVALID", name + ": the BPMN cannot be parsed: " + e.getMessage());
        }
        List<Element> tasks = new ArrayList<>();
        collect(doc.getDocumentElement(), tasks);
        if (tasks.isEmpty()) {
            return bytes;   // the word occurred only in text or another namespace
        }
        for (Element t : tasks) {
            rewriteTask(name, doc, t, resources);
        }
        return serialize(name, doc);
    }

    private static void rewriteTask(String name, Document doc, Element task, Map<String, byte[]> resources) {
        String id = task.getAttribute("id");
        String where = name + " " + (id.isEmpty() ? "(no id)" : id);
        String format = task.getAttribute("scriptFormat");
        String lang = language(format);
        if (lang == null) {
            throw ApiProblem.unprocessable("SCRIPT_FORMAT_UNSUPPORTED", where + ": script format "
                    + (format.isEmpty() ? "(none)" : format) + " has no sandboxed runtime (javascript, python)");
        }
        String source = scriptText(where, task, resources);
        String resultVariable = vendorAttr(task, "resultVariable");
        if (resultVariable != null && !resultVariable.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw ApiProblem.unprocessable("SCRIPT_CONVENTION_CONFLICT", where + ": resultVariable " + resultVariable
                    + " is not a variable name");
        }

        // the element: scriptTask -> serviceTask (same namespace and prefix; attributes and children move with it)
        String prefix = task.getPrefix();
        Element svc = (Element) doc.renameNode(task, BPMN_NS, prefix == null ? "serviceTask" : prefix + ":serviceTask");
        svc.removeAttribute("scriptFormat");
        for (String ns : new String[] {OPERATON_NS, CAMUNDA_NS}) {
            svc.removeAttributeNS(ns, "resultVariable");
            svc.removeAttributeNS(ns, "resource");
        }
        for (Element s : children(svc, BPMN_NS, "script")) {
            svc.removeChild(s);
        }
        String op = operatonPrefix(doc, svc);
        svc.setAttributeNS(OPERATON_NS, op + ":type", "external");
        svc.setAttributeNS(OPERATON_NS, op + ":topic", TOPIC_PREFIX + lang);

        byte[] utf8 = source.getBytes(StandardCharsets.UTF_8);
        Map<String, String> params = new LinkedHashMap<>();
        params.put(VAR_SOURCE, Base64.getEncoder().encodeToString(utf8));
        params.put(VAR_SHA256, Hashes.hex(Hashes.sha256(utf8)));
        if (resultVariable != null) {
            params.put(VAR_RESULT, resultVariable);
        }
        addInputs(where, doc, svc, op, params);
    }

    private static String scriptText(String where, Element task, Map<String, byte[]> resources) {
        List<Element> scripts = children(task, BPMN_NS, "script");
        String inline = scripts.isEmpty() ? null : scripts.get(0).getTextContent();
        String resource = vendorAttr(task, "resource");
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        if (resource == null || resource.isBlank()) {
            throw ApiProblem.unprocessable("SCRIPT_TASK_EMPTY", where + ": the script task has no script");
        }
        if (!resource.startsWith(DEPLOYMENT_PREFIX) || resource.contains("${") || resource.contains("#{")) {
            throw ApiProblem.unprocessable("SCRIPT_RESOURCE_OUTSIDE_PACKAGE", where + ": script resource " + resource
                    + " is not a deployment:// resource of the package (a classpath or expression script is not"
                    + " covered by the package hash)");
        }
        byte[] content = resources.get(resource.substring(DEPLOYMENT_PREFIX.length()));
        if (content == null) {
            throw ApiProblem.unprocessable("SCRIPT_RESOURCE_MISSING", where + ": script resource " + resource
                    + " is not in the package");
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    /** operaton:inputOutput (or an existing camunda:inputOutput) gets the convention's input parameters. */
    private static void addInputs(String where, Document doc, Element svc, String op, Map<String, String> params) {
        Element ext = children(svc, BPMN_NS, "extensionElements").stream().findFirst().orElse(null);
        if (ext == null) {
            String p = svc.getPrefix();
            ext = doc.createElementNS(BPMN_NS, p == null ? "extensionElements" : p + ":extensionElements");
            // BPMN order: documentation*, extensionElements?, then the rest
            Node after = null;
            for (Node c = svc.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof Element e && BPMN_NS.equals(e.getNamespaceURI()) && "documentation".equals(e.getLocalName())) {
                    after = c;
                }
            }
            svc.insertBefore(ext, after == null ? svc.getFirstChild() : after.getNextSibling());
        }
        Element io = null;
        String ioNs = OPERATON_NS;
        for (String ns : new String[] {OPERATON_NS, CAMUNDA_NS}) {
            List<Element> found = children(ext, ns, "inputOutput");
            if (!found.isEmpty()) {
                io = found.get(0);
                ioNs = ns;
                break;
            }
        }
        if (io == null) {
            io = doc.createElementNS(OPERATON_NS, op + ":inputOutput");
            ext.appendChild(io);
        }
        for (Element p : children(io, ioNs, "inputParameter")) {
            if (params.containsKey(p.getAttribute("name"))) {
                throw ApiProblem.unprocessable("SCRIPT_CONVENTION_CONFLICT", where + ": input parameter "
                        + p.getAttribute("name") + " is reserved for the script worker");
            }
        }
        Node firstOutput = children(io, ioNs, "outputParameter").stream().findFirst().orElse(null);
        String ioPrefix = io.getPrefix();
        for (Map.Entry<String, String> e : params.entrySet()) {
            Element in = doc.createElementNS(ioNs, ioPrefix == null ? "inputParameter" : ioPrefix + ":inputParameter");
            in.setAttribute("name", e.getKey());
            in.setTextContent(e.getValue());
            io.insertBefore(in, firstOutput);
        }
    }

    /** A prefix bound to the Operaton namespace at {@code at}; declares one on the root when none is. */
    private static String operatonPrefix(Document doc, Element at) {
        String p = at.lookupPrefix(OPERATON_NS);
        if (p != null) {
            return p;
        }
        Element root = doc.getDocumentElement();
        String candidate = "operaton";
        for (int i = 1; root.lookupNamespaceURI(candidate) != null || at.lookupNamespaceURI(candidate) != null; i++) {
            candidate = "operaton" + i;
        }
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + candidate, OPERATON_NS);
        return candidate;
    }

    private static String vendorAttr(Element e, String local) {
        for (String ns : new String[] {OPERATON_NS, CAMUNDA_NS}) {
            if (e.hasAttributeNS(ns, local)) {
                return e.getAttributeNS(ns, local);
            }
        }
        return null;
    }

    private static void collect(Element e, List<Element> out) {
        if (BPMN_NS.equals(e.getNamespaceURI()) && "scriptTask".equals(e.getLocalName())) {
            out.add(e);
            return;   // a script task has no nested flow nodes
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element child) {
                collect(child, out);
            }
        }
    }

    private static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean contains(byte[] bytes, String ascii) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(ascii);
    }

    /** XXE-safe, namespace-aware (DOCTYPE disallowed, no external entities), as the package tools' parser. */
    private static DocumentBuilderFactory parser() throws Exception {
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
        return f;
    }

    private static byte[] serialize(String name, Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.METHOD, "xml");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw ApiProblem.unprocessable("MODEL_INVALID", name + ": the rewritten BPMN cannot be written: " + e.getMessage());
        }
    }
}
