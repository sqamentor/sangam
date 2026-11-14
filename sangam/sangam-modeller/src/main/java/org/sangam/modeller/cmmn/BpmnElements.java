// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.cmmn;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.modeller.cmmn.CmmnConversion.Diagnostic;
import org.sangam.modeller.cmmn.CmmnToBpmnConverter.Item;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** The BPMN element of one supported plan item (see {@link CmmnToBpmnConverter} for the mapping). */
final class BpmnElements {

    private BpmnElements() {
    }

    static Element element(Document out, Item it, List<Diagnostic> diag) {
        Element def = it.definition();
        String type = switch (it.kind()) {
            case "humanTask" -> "userTask";
            case "task" -> "false".equals(def.getAttribute("isBlocking")) ? "task" : "userTask";
            case "processTask" -> "callActivity";
            case "decisionTask" -> "businessRuleTask";
            case "milestone" -> "intermediateThrowEvent";
            case "stage" -> "subProcess";
            default -> throw new IllegalStateException("not a supported kind: " + it.kind());
        };
        Element e = out.createElementNS(CmmnToBpmnConverter.BPMN_NS, "bpmn:" + type);
        e.setAttribute("id", it.id());
        if (it.name() != null && !it.name().isBlank()) {
            e.setAttribute("name", it.name());
        }
        if ("processTask".equals(it.kind())) {
            e.setAttribute("calledElement", def.getAttribute("processRef"));
        }
        if ("decisionTask".equals(it.kind())) {
            e.setAttributeNS(CmmnToBpmnConverter.OPERATON_BPMN_NS, "operaton:decisionRef", def.getAttribute("decisionRef"));
        }
        Map<String, String> renames = CmmnToBpmnConverter.ATTRIBUTES.getOrDefault(it.kind(), Map.of());
        NamedNodeMap attrs = def.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String ns = a.getNamespaceURI();
            String local = a.getLocalName() == null ? a.getName() : a.getLocalName();
            if (ns != null && CmmnToBpmnConverter.CMMN_EXTENSION_NS.contains(ns) && renames.containsKey(local)) {
                e.setAttributeNS(CmmnToBpmnConverter.OPERATON_BPMN_NS, "operaton:" + renames.get(local), a.getValue());
            }
        }
        Set<String> carried = CmmnToBpmnConverter.EXTENSIONS.getOrDefault(it.kind(), Set.of());
        Element ext = CmmnToBpmnConverter.first(def, "extensionElements");
        if (ext != null && !carried.isEmpty()) {
            Element target = null;
            for (Element x : CmmnToBpmnConverter.children(ext, null)) {
                if (CmmnToBpmnConverter.CMMN_EXTENSION_NS.contains(x.getNamespaceURI())
                        && carried.contains(x.getLocalName())) {
                    if (target == null) {
                        target = out.createElementNS(CmmnToBpmnConverter.BPMN_NS, "bpmn:extensionElements");
                        e.appendChild(target);
                    }
                    target.appendChild(copy(out, x));
                }
            }
        }
        return e;
    }

    /** Deep copy of an extension element into the Operaton BPMN namespace (same local names and attributes). */
    static Element copy(Document out, Element src) {
        String ns = CmmnToBpmnConverter.CMMN_EXTENSION_NS.contains(src.getNamespaceURI())
                ? CmmnToBpmnConverter.OPERATON_BPMN_NS : src.getNamespaceURI();
        String qn = CmmnToBpmnConverter.OPERATON_BPMN_NS.equals(ns) ? "operaton:" + src.getLocalName() : src.getTagName();
        Element e = out.createElementNS(ns, qn);
        NamedNodeMap attrs = src.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            if (a.getName().startsWith("xmlns")) {
                continue;
            }
            if (a.getNamespaceURI() == null) {
                e.setAttribute(a.getName(), a.getValue());
            } else if (CmmnToBpmnConverter.CMMN_EXTENSION_NS.contains(a.getNamespaceURI())) {
                e.setAttributeNS(CmmnToBpmnConverter.OPERATON_BPMN_NS, "operaton:" + a.getLocalName(), a.getValue());
            } else {
                e.setAttributeNS(a.getNamespaceURI(), a.getName(), a.getValue());
            }
        }
        for (Node n = src.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) {
                e.appendChild(copy(out, c));
            } else if (n.getNodeType() == Node.TEXT_NODE) {
                e.appendChild(out.createTextNode(n.getNodeValue()));
            } else if (n.getNodeType() == Node.CDATA_SECTION_NODE) {
                e.appendChild(out.createCDATASection(n.getNodeValue()));
            }
        }
        return e;
    }
}
