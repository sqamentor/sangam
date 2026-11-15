// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.simulation;

import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.Activity;
import org.operaton.bpm.model.bpmn.instance.Association;
import org.operaton.bpm.model.bpmn.instance.BaseElement;
import org.operaton.bpm.model.bpmn.instance.BoundaryEvent;
import org.operaton.bpm.model.bpmn.instance.CatchEvent;
import org.operaton.bpm.model.bpmn.instance.CompensateEventDefinition;
import org.operaton.bpm.model.bpmn.instance.EventDefinition;
import org.operaton.bpm.model.bpmn.instance.ExclusiveGateway;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.bpmn.instance.StartEvent;
import org.operaton.bpm.model.bpmn.instance.SubProcess;
import org.operaton.bpm.model.bpmn.instance.ThrowEvent;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;
import org.sangam.core.engine.RoutingPolicy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The read-only indexes one token walk needs, built once from the BPMN text: flows (from the flows themselves, since the
 * {@code <incoming>} and {@code <outgoing>} child elements are optional in BPMN), boundary events per activity,
 * compensation handlers (association from a compensation boundary event to an activity with
 * {@code isForCompensation}), and event sub-processes per scope (the process or sub-process that contains them).
 */
final class WalkModel {

    final Process process;
    final StartEvent start;
    final List<String> routingWarnings = new ArrayList<>();
    private final Map<String, FlowNode> nodes = new HashMap<>();
    private final Map<String, List<SequenceFlow>> outgoing = new HashMap<>();
    private final Map<String, Integer> incoming = new HashMap<>();
    private final Map<String, List<BoundaryEvent>> boundaries = new HashMap<>();
    private final Map<String, BoundaryEvent> compensationBoundary = new HashMap<>();
    private final Map<String, Association> handlerAssociation = new HashMap<>();
    private final Map<String, List<SubProcess>> eventSubProcesses = new HashMap<>();

    private WalkModel(BpmnModelInstance model, String processIdOrNull) {
        process = model.getModelElementsByType(Process.class).stream()
                .filter(p -> processIdOrNull == null || processIdOrNull.equals(p.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such process: " + processIdOrNull));
        start = startOf(process);
        if (start == null) {
            throw new IllegalArgumentException("the process has no start event");
        }
        for (FlowNode n : model.getModelElementsByType(FlowNode.class)) {
            nodes.put(n.getId(), n);
        }
        for (SequenceFlow f : model.getModelElementsByType(SequenceFlow.class)) {
            outgoing.computeIfAbsent(f.getSource().getId(), k -> new ArrayList<>()).add(f);
            incoming.merge(f.getTarget().getId(), 1, Integer::sum);
        }
        for (BoundaryEvent b : model.getModelElementsByType(BoundaryEvent.class)) {
            if (b.getAttachedTo() == null) {
                continue;
            }
            if (definition(b, CompensateEventDefinition.class) != null) {
                compensationBoundary.put(b.getAttachedTo().getId(), b);
            } else {
                boundaries.computeIfAbsent(b.getAttachedTo().getId(), k -> new ArrayList<>()).add(b);
            }
        }
        for (Association a : model.getModelElementsByType(Association.class)) {
            if (a.getSource() instanceof BoundaryEvent b && a.getTarget() instanceof Activity h && h.isForCompensation()) {
                handlerAssociation.put(b.getId(), a);
            }
        }
        for (SubProcess sp : model.getModelElementsByType(SubProcess.class)) {
            if (sp.triggeredByEvent() && sp.getParentElement() instanceof BaseElement container) {
                eventSubProcesses.computeIfAbsent(container.getId(), k -> new ArrayList<>()).add(sp);
            }
        }
        // ADR-D-25: the same strict-XOR deploy rule the engine applies (shared org.sangam.core.engine.RoutingPolicy)
        for (ExclusiveGateway g : model.getModelElementsByType(ExclusiveGateway.class)) {
            List<RoutingPolicy.FlowSpec> specs = new ArrayList<>();
            for (SequenceFlow f : out(g)) {
                specs.add(new RoutingPolicy.FlowSpec(f.getId(), f.getConditionExpression() != null,
                        f.getAttributeValueNs(RoutingPolicy.NS_URI, RoutingPolicy.ATTR_PRIORITY)));
            }
            for (String p : RoutingPolicy.validate(g.getId(), g.getAttributeValueNs(RoutingPolicy.NS_URI, RoutingPolicy.ATTR_POLICY),
                    g.getAttributeValueNs(RoutingPolicy.NS_URI, RoutingPolicy.ATTR_POLICY_VERSION), specs,
                    g.getDefault() == null ? null : g.getDefault().getId())) {
                routingWarnings.add("ADR-D-25: " + p + " (deployment will reject this model)");
            }
        }
    }

    static WalkModel read(String bpmnXml, String processIdOrNull) {
        BpmnModelInstance model = Bpmn.readModelFromStream(new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
        return new WalkModel(model, processIdOrNull);
    }

    /** The first start event that is a direct child of a process or sub-process, or null. */
    static StartEvent startOf(ModelElementInstance container) {
        return container.getChildElementsByType(StartEvent.class).stream().findFirst().orElse(null);
    }

    /** The first event definition of the given type on a catch or throw event, or null. */
    static <T extends EventDefinition> T definition(FlowNode node, Class<T> type) {
        Collection<EventDefinition> defs = node instanceof CatchEvent c ? c.getEventDefinitions()
                : node instanceof ThrowEvent t ? t.getEventDefinitions() : List.of();
        return defs.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    FlowNode node(String id) {
        return nodes.get(id);
    }

    List<SequenceFlow> out(FlowNode node) {
        return outgoing.getOrDefault(node.getId(), List.of());
    }

    int incoming(String nodeId) {
        return incoming.getOrDefault(nodeId, 0);
    }

    /** Boundary events on the activity that an event can trigger (every kind except compensation). */
    List<BoundaryEvent> boundaries(String activityId) {
        return boundaries.getOrDefault(activityId, List.of());
    }

    BoundaryEvent compensationBoundary(String activityId) {
        return compensationBoundary.get(activityId);
    }

    Association handlerAssociation(String boundaryId) {
        return handlerAssociation.get(boundaryId);
    }

    /** Event sub-processes declared directly inside the process or sub-process {@code containerId}. */
    List<SubProcess> eventSubProcesses(String containerId) {
        return containerId == null ? List.of() : eventSubProcesses.getOrDefault(containerId, List.of());
    }

    /** Whether the element is the start event of an event sub-process. */
    boolean isEventSubProcessStart(String id) {
        return nodes.get(id) instanceof StartEvent s && s.getParentElement() instanceof SubProcess sp && sp.triggeredByEvent();
    }
}
