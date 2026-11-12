// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.ExtensionElements;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.xml.instance.DomElement;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;

/**
 * M-059: the handlers of a deployed process definition, read through Operaton's public repository and model API and
 * parsed with the same rules as at deploy ({@link InstanceHandlers#parse}). A definition never changes once deployed,
 * so the result is cached per definition id (bounded, least recently used out first).
 */
public final class InstanceHandlerRegistry {

    static final int CACHE_SIZE = 1_024;

    private final RepositoryService repository;
    private final InstanceHandlerEvaluator evaluator;
    private final Map<String, InstanceHandlers.Definitions> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, InstanceHandlers.Definitions> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public InstanceHandlerRegistry(RepositoryService repository, InstanceHandlerEvaluator evaluator) {
        this.repository = repository;
        this.evaluator = evaluator;
    }

    public InstanceHandlerEvaluator evaluator() {
        return evaluator;
    }

    /** The handlers of {@code processDefinitionId} ({@link InstanceHandlers.Definitions#NONE} when it declares none). */
    public InstanceHandlers.Definitions of(String processDefinitionId) {
        synchronized (cache) {
            InstanceHandlers.Definitions d = cache.get(processDefinitionId);
            if (d != null) {
                return d;
            }
        }
        InstanceHandlers.Definitions parsed = load(processDefinitionId);
        synchronized (cache) {
            cache.put(processDefinitionId, parsed);
        }
        return parsed;
    }

    private InstanceHandlers.Definitions load(String processDefinitionId) {
        ProcessDefinition def = repository.getProcessDefinition(processDefinitionId);
        BpmnModelInstance model = repository.getBpmnModelInstance(processDefinitionId);
        ModelElementInstance p = model == null ? null : model.getModelElementById(def.getKey());
        if (!(p instanceof Process process)) {
            return InstanceHandlers.Definitions.NONE;
        }
        ExtensionElements ext = process.getExtensionElements();
        if (ext == null) {
            return InstanceHandlers.Definitions.NONE;
        }
        return InstanceHandlers.parse(def.getKey(), node(ext.getDomElement()),
                id -> model.getModelElementById(id) instanceof FlowNode, evaluator);
    }

    /** The model API's DOM element as an {@link InstanceHandlers.Node}. */
    static InstanceHandlers.Node node(DomElement e) {
        return new InstanceHandlers.Node() {
            @Override
            public String uri() {
                return e.getNamespaceURI();
            }

            @Override
            public String localName() {
                return e.getLocalName();
            }

            @Override
            public String attr(String name) {
                String v = e.getAttribute(name);
                return v == null || v.isEmpty() && !e.hasAttribute(name) ? null : v;
            }

            @Override
            public List<InstanceHandlers.Node> children() {
                List<InstanceHandlers.Node> out = new ArrayList<>();
                for (DomElement c : e.getChildElements()) {
                    out.add(node(c));
                }
                return out;
            }
        };
    }
}
