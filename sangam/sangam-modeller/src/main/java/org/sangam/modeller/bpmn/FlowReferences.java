// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.bpmn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The {@code incoming} and {@code outgoing} references of the flow nodes of a generated BPMN document. A sequence flow
 * names its nodes ({@code sourceRef}, {@code targetRef}); BPMN 2.0 interchange also lists, on each flow node, the flows
 * that reach and leave it, and model readers answer "what follows this node" from that list (the Operaton model API's
 * {@code FlowNode#getIncoming} and {@code getOutgoing} read nothing else). A generated draft carries both, like a model
 * saved by a modeller.
 */
public final class FlowReferences {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** Children that precede {@code incoming} in a flow node (BPMN 2.0 schema: tBaseElement, tFlowElement). */
    private static final Set<String> BEFORE_INCOMING = Set.of("documentation", "extensionElements", "auditing",
            "monitoring", "categoryValueRef", "incoming");

    private static final Set<String> BEFORE_OUTGOING = Set.of("documentation", "extensionElements", "auditing",
            "monitoring", "categoryValueRef", "incoming", "outgoing");

    private FlowReferences() {
    }

    /**
     * Adds, for every sequence flow of {@code doc}, an {@code incoming} element to its target and an {@code outgoing}
     * element to its source, in document order of the flows and at the place the BPMN schema gives them. A flow whose
     * source or target is not an element of the document is left alone (the model's validation reports it).
     */
    public static void write(Document doc) {
        Map<String, Element> byId = new HashMap<>();
        List<Element> flows = new ArrayList<>();
        collect(doc.getDocumentElement(), byId, flows);
        for (Element flow : flows) {
            add(byId.get(flow.getAttribute("targetRef")), "incoming", BEFORE_INCOMING, flow.getAttribute("id"));
        }
        for (Element flow : flows) {
            add(byId.get(flow.getAttribute("sourceRef")), "outgoing", BEFORE_OUTGOING, flow.getAttribute("id"));
        }
    }

    private static void collect(Element e, Map<String, Element> byId, List<Element> flows) {
        if (BPMN_NS.equals(e.getNamespaceURI())) {
            if ("sequenceFlow".equals(e.getLocalName())) {
                flows.add(e);
            } else if (e.hasAttribute("id")) {
                byId.put(e.getAttribute("id"), e);
            }
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child) {
                collect(child, byId, flows);
            }
        }
    }

    private static void add(Element node, String name, Set<String> leading, String flowId) {
        if (node == null || flowId.isEmpty()) {
            return;
        }
        String prefix = node.getPrefix();
        Element ref = node.getOwnerDocument().createElementNS(BPMN_NS, prefix == null ? name : prefix + ":" + name);
        ref.setTextContent(flowId);
        Node before = null;
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child
                    && !(BPMN_NS.equals(child.getNamespaceURI()) && leading.contains(child.getLocalName()))) {
                before = child;
                break;
            }
        }
        node.insertBefore(ref, before);
    }
}