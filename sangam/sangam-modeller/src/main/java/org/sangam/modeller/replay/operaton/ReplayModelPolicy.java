// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay.operaton;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.sangam.modeller.replay.ReplayFixture.ModelKind;

/**
 * M-136 (decision 151): what the Operaton replay adapter can capture and control in a model. Anything else makes the
 * scenario unsupported (capture) or is refused (replay); it is never run and compared approximately.
 *
 * <ul>
 *   <li>Code the capture points do not see: script tasks and scripts ({@code script} elements, {@code operaton:script},
 *       a {@code language} or {@code scriptFormat} attribute, as on a script condition),
 *       Java delegates and bean expressions ({@code class}, {@code delegateExpression}, {@code expression} attributes
 *       of the Operaton namespace, on tasks and listeners), connectors ({@code operaton:connector}). They can read
 *       time, random numbers or live systems without a capture hook. External tasks are the supported way out.</li>
 *   <li>Job execution with no captured stimulus: asynchronous continuations ({@code asyncBefore}, {@code asyncAfter},
 *       {@code async}). The replay runs jobs only when a recorded timer firing or lease expiry asks for it.</li>
 *   <li>CMMN and form models: the observation covers BPMN process instances only, and a form resource has no
 *       definition version a replay could pin.</li>
 * </ul>
 * BPMN, DMN and form models are read with DOCTYPE refused (the engine refuses one at deploy as well).
 */
public final class ReplayModelPolicy {

    /** The extension namespaces Operaton reads (its own and the Camunda 7 one it still accepts). */
    static final Set<String> EXTENSION_NS = Set.of("http://operaton.org/schema/1.0/bpmn", "http://camunda.org/schema/1.0/bpmn");
    private static final Set<String> CODE_ATTRIBUTES = Set.of("class", "delegateExpression", "expression");
    private static final Set<String> ASYNC_ATTRIBUTES = Set.of("asyncBefore", "asyncAfter", "async");

    private ReplayModelPolicy() {
    }

    /** The reasons the model is outside what the adapter controls; empty when it can be captured and replayed. */
    public static List<String> check(String resource, ModelKind kind, byte[] content) {
        if (kind == ModelKind.CMMN || kind == ModelKind.FORM) {
            return List.of("model " + resource + ": " + kind + " is not replayed by the Operaton replay adapter (BPMN "
                    + "processes and DMN decisions only; a " + kind + " resource has no definition version to pin)");
        }
        if (kind != ModelKind.BPMN) {
            return List.of(); // DMN: FEEL clock reads are captured, and a decision runs no other code
        }
        Document doc;
        try {
            doc = parse(content);
        } catch (Exception e) {
            return List.of("model " + resource + ": not readable BPMN XML (" + e.getMessage() + ")");
        }
        Set<String> out = new LinkedHashSet<>();
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            String where = "model " + resource + ": " + e.getLocalName() + idOf(e);
            if ("scriptTask".equals(e.getLocalName()) || "script".equals(e.getLocalName())
                    || !e.getAttribute("language").isBlank() || !e.getAttribute("scriptFormat").isBlank()) {
                out.add(where + " runs a script, which the capture cannot control");
            }
            if (extension(e.getNamespaceURI()) && "connector".equals(e.getLocalName())) {
                out.add(where + " calls a connector outside the capture");
            }
            NamedNodeMap attrs = e.getAttributes();
            for (int a = 0; a < attrs.getLength(); a++) {
                Attr attr = (Attr) attrs.item(a);
                if (!extension(attr.getNamespaceURI())) {
                    continue;
                }
                String name = attr.getLocalName();
                if (CODE_ATTRIBUTES.contains(name)) {
                    out.add(where + " runs code (" + name + "=\"" + attr.getValue() + "\") the capture does not see");
                } else if (ASYNC_ATTRIBUTES.contains(name) && "true".equalsIgnoreCase(attr.getValue().trim())) {
                    out.add(where + " is asynchronous (" + name + "): a job with no captured stimulus");
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Set.of refuses a null probe; an element or attribute without a namespace is not an extension. */
    private static boolean extension(String namespace) {
        return namespace != null && EXTENSION_NS.contains(namespace);
    }

    private static String idOf(Element e) {
        String id = e.getAttribute("id");
        return id.isEmpty() ? "" : " '" + id + "'";
    }

    private static Document parse(byte[] content) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setExpandEntityReferences(false);
        f.setXIncludeAware(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        Document d = b.parse(new ByteArrayInputStream(content));
        Node root = d.getDocumentElement();
        if (root == null) {
            throw new IllegalArgumentException("empty document");
        }
        return d;
    }
}
