// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.simulation;

import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.EventBasedGateway;
import org.operaton.bpm.model.bpmn.instance.ExclusiveGateway;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.Gateway;
import org.operaton.bpm.model.bpmn.instance.InclusiveGateway;
import org.operaton.bpm.model.bpmn.instance.ParallelGateway;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.bpmn.instance.StartEvent;
import org.operaton.bpm.model.bpmn.instance.SubProcess;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Token-flow simulation as a pure model walk: no engine, no variables, no effects. The client passes choices for gateways and the walk
 * returns ordered token moves for the diagram to animate, or the gateway waiting for a choice. Parallel joins wait for all incoming
 * tokens. A walk stops at {@code maxSteps}, so loops terminate. Limits: an inclusive join continues per token instead of computing
 * reachability; boundary events, event sub-processes and compensation are not walked; a sub-process is entered at its start event.
 */
public final class TokenWalk {

    public record Move(int step, String flowId, String fromNode, String toNode) {
    }

    public record Choice(String gatewayId, List<String> options) {
    }

    public enum Status { COMPLETED, NEEDS_CHOICE, STEP_LIMIT }

    public record Result(Status status, List<Move> moves, List<String> visited, Choice pending, List<String> warnings) {
    }

    private TokenWalk() {
    }

    /** {@code choices}: gateway id to outgoing flow ids to take, consumed in order for repeated visits. */
    public static Result walk(String bpmnXml, String processIdOrNull, Map<String, List<String>> choices, int maxSteps) {
        BpmnModelInstance model = Bpmn.readModelFromStream(new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8)));
        Process process = model.getModelElementsByType(Process.class).stream().filter(p -> processIdOrNull == null || processIdOrNull.equals(p.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such process: " + processIdOrNull));
        StartEvent start = process.getChildElementsByType(StartEvent.class).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("the process has no start event"));

        // From the flows themselves: the <incoming> and <outgoing> child elements are optional in BPMN.
        Map<String, List<SequenceFlow>> outgoing = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        for (SequenceFlow f : model.getModelElementsByType(SequenceFlow.class)) {
            outgoing.computeIfAbsent(f.getSource().getId(), k -> new ArrayList<>()).add(f);
            incoming.merge(f.getTarget().getId(), 1, Integer::sum);
        }
        List<Move> moves = new ArrayList<>();
        List<String> visited = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        // ADR-D-25: the same strict-XOR deploy rule the engine applies (shared org.sangam.core.engine.RoutingPolicy)
        for (ExclusiveGateway g : model.getModelElementsByType(ExclusiveGateway.class)) {
            List<org.sangam.core.engine.RoutingPolicy.FlowSpec> specs = new ArrayList<>();
            for (SequenceFlow f : outgoing.getOrDefault(g.getId(), List.of())) {
                specs.add(new org.sangam.core.engine.RoutingPolicy.FlowSpec(f.getId(), f.getConditionExpression() != null,
                        f.getAttributeValueNs(org.sangam.core.engine.RoutingPolicy.NS_URI,
                                org.sangam.core.engine.RoutingPolicy.ATTR_PRIORITY)));
            }
            for (String p : org.sangam.core.engine.RoutingPolicy.validate(g.getId(),
                    g.getAttributeValueNs(org.sangam.core.engine.RoutingPolicy.NS_URI, org.sangam.core.engine.RoutingPolicy.ATTR_POLICY),
                    g.getAttributeValueNs(org.sangam.core.engine.RoutingPolicy.NS_URI,
                            org.sangam.core.engine.RoutingPolicy.ATTR_POLICY_VERSION), specs,
                    g.getDefault() == null ? null : g.getDefault().getId())) {
                warnings.add("ADR-D-25: " + p + " (deployment will reject this model)");
            }
        }
        Map<String, Integer> used = new HashMap<>();
        Map<String, Integer> joinArrivals = new HashMap<>();
        Deque<FlowNode> tokens = new ArrayDeque<>();
        tokens.add(start);
        int step = 0;
        while (!tokens.isEmpty()) {
            if (step >= maxSteps) {
                return new Result(Status.STEP_LIMIT, moves, visited, null, warnings);
            }
            FlowNode node = tokens.poll();
            visited.add(node.getId());
            if (node instanceof SubProcess sp) {
                sp.getChildElementsByType(StartEvent.class).stream().findFirst().ifPresentOrElse(tokens::add, () -> warnings.add("sub-process " + sp.getId() + " has no start event"));
            }
            List<SequenceFlow> out = outgoing.getOrDefault(node.getId(), List.of());
            List<SequenceFlow> taken;
            if (out.isEmpty()) {
                continue;
            }
            if (node instanceof ExclusiveGateway || node instanceof EventBasedGateway || node instanceof InclusiveGateway) {
                if (out.size() > 1) {
                    List<String> c = choices.getOrDefault(node.getId(), List.of());
                    int i = used.getOrDefault(node.getId(), 0);
                    if (i >= c.size()) {
                        return new Result(Status.NEEDS_CHOICE, moves, visited, new Choice(node.getId(), out.stream().map(SequenceFlow::getId).toList()), warnings);
                    }
                    used.put(node.getId(), i + 1);
                    String pick = c.get(i);
                    taken = out.stream().filter(f -> pick.equals(f.getId())).toList();
                    if (taken.isEmpty()) {
                        throw new IllegalArgumentException("gateway " + node.getId() + " has no outgoing flow " + pick);
                    }
                } else {
                    taken = out;
                }
            } else {
                taken = out;
            }
            for (SequenceFlow f : taken) {
                FlowNode target = f.getTarget();
                moves.add(new Move(++step, f.getId(), node.getId(), target.getId()));
                int arriving = incoming.getOrDefault(target.getId(), 0);
                if (target instanceof ParallelGateway && arriving > 1) {
                    int n = joinArrivals.merge(target.getId(), 1, Integer::sum);
                    if (n < arriving) {
                        continue;
                    }
                    joinArrivals.put(target.getId(), 0);
                }
                tokens.add(target);
            }
            if (node instanceof Gateway && taken.isEmpty()) {
                warnings.add("gateway " + node.getId() + " sends no token");
            }
        }
        return new Result(Status.COMPLETED, moves, visited, null, warnings);
    }
}
