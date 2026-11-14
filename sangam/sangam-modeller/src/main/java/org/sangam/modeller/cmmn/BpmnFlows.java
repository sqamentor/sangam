// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.cmmn;

import java.util.Set;

import org.w3c.dom.Element;

/** Adds generated flow nodes and sequence flows to one BPMN scope (process or sub-process) with ids that never clash. */
final class BpmnFlows {

    private final Element scope;
    private final Set<String> ids;
    private final String prefix;
    private int flows;

    BpmnFlows(Element scope, Set<String> ids, String prefix) {
        this.scope = scope;
        this.ids = ids;
        this.prefix = prefix;
    }

    /** Appends a {@code bpmn:<type>} with a fresh id derived from {@code wantedId}; returns the id. */
    String node(String type, String wantedId, String name) {
        Element e = scope.getOwnerDocument().createElementNS(CmmnToBpmnConverter.BPMN_NS, "bpmn:" + type);
        String id = CmmnToBpmnConverter.fresh(ids, wantedId);
        e.setAttribute("id", id);
        if (name != null && !name.isBlank()) {
            e.setAttribute("name", name);
        }
        scope.appendChild(e);
        return id;
    }

    /** Appends an unconditional sequence flow. */
    void flow(String from, String to) {
        Element f = scope.getOwnerDocument().createElementNS(CmmnToBpmnConverter.BPMN_NS, "bpmn:sequenceFlow");
        f.setAttribute("id", CmmnToBpmnConverter.fresh(ids, prefix + "_flow_" + (++flows)));
        f.setAttribute("sourceRef", from);
        f.setAttribute("targetRef", to);
        scope.appendChild(f);
    }
}
