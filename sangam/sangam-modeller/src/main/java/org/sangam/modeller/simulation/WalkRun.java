// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.simulation;

import org.operaton.bpm.model.bpmn.instance.Activity;
import org.operaton.bpm.model.bpmn.instance.Association;
import org.operaton.bpm.model.bpmn.instance.BoundaryEvent;
import org.operaton.bpm.model.bpmn.instance.CompensateEventDefinition;
import org.operaton.bpm.model.bpmn.instance.EndEvent;
import org.operaton.bpm.model.bpmn.instance.Error;
import org.operaton.bpm.model.bpmn.instance.ErrorEventDefinition;
import org.operaton.bpm.model.bpmn.instance.Escalation;
import org.operaton.bpm.model.bpmn.instance.EscalationEventDefinition;
import org.operaton.bpm.model.bpmn.instance.EventBasedGateway;
import org.operaton.bpm.model.bpmn.instance.EventDefinition;
import org.operaton.bpm.model.bpmn.instance.ExclusiveGateway;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.InclusiveGateway;
import org.operaton.bpm.model.bpmn.instance.IntermediateThrowEvent;
import org.operaton.bpm.model.bpmn.instance.ParallelGateway;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.bpmn.instance.StartEvent;
import org.operaton.bpm.model.bpmn.instance.SubProcess;
import org.operaton.bpm.model.bpmn.instance.TerminateEventDefinition;
import org.sangam.modeller.simulation.TokenWalk.Choice;
import org.sangam.modeller.simulation.TokenWalk.Move;
import org.sangam.modeller.simulation.TokenWalk.Result;
import org.sangam.modeller.simulation.TokenWalk.Status;
import org.sangam.modeller.simulation.TokenWalk.Via;
import org.sangam.modeller.simulation.WalkScope.Done;
import org.sangam.modeller.simulation.WalkScope.Kind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One run of {@link TokenWalk#walk}: the token queue, the scopes and the choices consumed so far. */
final class WalkRun {

    private record Token(FlowNode node, WalkScope scope, int epoch) {
        boolean alive() {
            return epoch == scope.epoch && scope.alive();
        }
    }

    /** One compensation to run: a handler activity (through its association) or a compensation event sub-process. */
    private record Handler(String via, String from, FlowNode target, SubProcess eventSubProcess, WalkScope inner) {
    }

    private final WalkModel m;
    private final Map<String, List<String>> choices;
    private final int maxSteps;
    private final Map<String, Integer> used = new HashMap<>();
    private final List<Move> moves = new ArrayList<>();
    private final List<String> visited = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Deque<Token> tokens = new ArrayDeque<>();
    private int step;

    WalkRun(WalkModel m, Map<String, List<String>> choices, int maxSteps) {
        this.m = m;
        this.choices = choices == null ? Map.of() : choices;
        this.maxSteps = maxSteps;
        warnings.addAll(m.routingWarnings);
        this.choices.forEach((key, values) -> {
            if (m.isEventSubProcessStart(key)) {
                for (String at : values) {
                    if (m.node(at) == null) {
                        throw new IllegalArgumentException("event sub-process start " + key + " names no flow node " + at);
                    }
                }
            }
        });
    }

    Result run() {
        push(m.start, new WalkScope(Kind.PROCESS, null, m.process.getId(), null));
        while (!tokens.isEmpty()) {
            if (step >= maxSteps) {
                return new Result(Status.STEP_LIMIT, moves, visited, null, warnings);
            }
            Token t = tokens.poll();
            if (!t.alive()) {
                continue;
            }
            visited.add(t.node().getId());
            Choice pending = arrive(t);
            if (pending != null) {
                return new Result(Status.NEEDS_CHOICE, moves, visited, pending, warnings);
            }
        }
        choices.forEach((key, values) -> {
            if (m.isEventSubProcessStart(key) && used.getOrDefault(key, 0) < values.size()) {
                warnings.add("event sub-process start " + key + ": no token reached " + values.get(used.getOrDefault(key, 0))
                        + ", so the event did not occur there");
            }
        });
        return new Result(Status.COMPLETED, moves, visited, null, warnings);
    }

    /** Handles a token that arrived at its node; returns the element waiting for a choice, or null. */
    private Choice arrive(Token t) {
        FlowNode n = t.node();
        WalkScope s = t.scope();
        if (fireTriggers(n, s)) {
            return null;
        }
        if (n instanceof Activity a) {
            return activity(t, a);
        }
        if (n instanceof EndEvent || n instanceof IntermediateThrowEvent) {
            if (throwEvent(t)) {
                return null;
            }
        }
        List<SequenceFlow> out = m.out(n);
        List<SequenceFlow> taken = out;
        if ((n instanceof ExclusiveGateway || n instanceof EventBasedGateway || n instanceof InclusiveGateway) && out.size() > 1) {
            String pick = next(n.getId());
            if (pick == null) {
                return new Choice(n.getId(), out.stream().map(SequenceFlow::getId).toList());
            }
            taken = out.stream().filter(f -> pick.equals(f.getId())).toList();
            if (taken.isEmpty()) {
                throw new IllegalArgumentException("gateway " + n.getId() + " has no outgoing flow " + pick);
            }
        }
        follow(n, s, taken);
        consume(t);
        return null;
    }

    /** An activity with boundary events waits for a choice: its own id (it completes) or a boundary event's id. */
    private Choice activity(Token t, Activity a) {
        WalkScope s = t.scope();
        List<BoundaryEvent> bs = m.boundaries(a.getId());
        while (!bs.isEmpty()) {
            String pick = next(a.getId());
            if (pick == null) {
                List<String> options = new ArrayList<>();
                options.add(a.getId());
                bs.forEach(b -> options.add(b.getId()));
                return new Choice(a.getId(), options);
            }
            if (pick.equals(a.getId())) {
                break;
            }
            BoundaryEvent b = bs.stream().filter(x -> pick.equals(x.getId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("activity " + a.getId() + " has no boundary event " + pick));
            move(null, a.getId(), b.getId(), Via.BOUNDARY);
            visited.add(b.getId());
            follow(b, s, m.out(b));
            if (b.cancelActivity()) {
                consume(t);
                return null;
            }
        }
        if (a instanceof SubProcess sp) {
            enter(t, sp);
            return null;
        }
        if (!a.isForCompensation()) {
            s.done.add(new Done(a, null));
        }
        follow(a, s, m.out(a));
        consume(t);
        return null;
    }

    private void enter(Token t, SubProcess sp) {
        WalkScope s = t.scope();
        WalkScope inner = new WalkScope(Kind.SUB_PROCESS, s, sp.getId(), sp);
        s.live++;
        consume(t);
        StartEvent start = WalkModel.startOf(sp);
        if (start == null) {
            warnings.add("sub-process " + sp.getId() + " has no start event");
            complete(inner);
        } else {
            push(start, inner);
        }
    }

    /** Error, escalation, compensation and terminate throws; false for a plain event. */
    private boolean throwEvent(Token t) {
        FlowNode n = t.node();
        if (n instanceof EndEvent && WalkModel.definition(n, TerminateEventDefinition.class) != null) {
            WalkScope s = t.scope();
            s.interrupt();
            complete(s);
            return true;
        }
        ErrorEventDefinition error = n instanceof EndEvent ? WalkModel.definition(n, ErrorEventDefinition.class) : null;
        if (error != null) {
            propagate(t, error, true);
            return true;
        }
        EscalationEventDefinition escalation = WalkModel.definition(n, EscalationEventDefinition.class);
        if (escalation != null) {
            propagate(t, escalation, false);
            return true;
        }
        CompensateEventDefinition compensate = WalkModel.definition(n, CompensateEventDefinition.class);
        if (compensate != null) {
            compensate(t, compensate);
            return true;
        }
        return false;
    }

    /**
     * Finds the innermost catcher of a thrown error or escalation: an event sub-process of the scope, else a boundary
     * event on the sub-process the scope runs, then the enclosing scope. An error always interrupts; an escalation
     * interrupts when its catcher does, and otherwise the thrower carries on.
     */
    private void propagate(Token t, EventDefinition thrown, boolean error) {
        FlowNode n = t.node();
        WalkScope from = null;
        for (WalkScope c = t.scope(); c != null; from = c, c = c.parent) {
            for (SubProcess esp : m.eventSubProcesses(c.containerId)) {
                StartEvent start = WalkModel.startOf(esp);
                if (start == null || (from != null && esp.getId().equals(from.containerId)) || !catches(start, thrown)) {
                    continue;
                }
                move(null, n.getId(), start.getId(), Via.EVENT_SUB_PROCESS);
                boolean interrupting = error || start.isInterrupting();
                if (interrupting) {
                    c.interrupt();
                }
                startEventSubProcess(c, esp, start, c);
                if (!interrupting) {
                    carryOn(t);
                }
                return;
            }
            if (c.kind == Kind.SUB_PROCESS) {
                for (BoundaryEvent b : m.boundaries(c.activator.getId())) {
                    if (!catches(b, thrown)) {
                        continue;
                    }
                    move(null, n.getId(), b.getId(), Via.BOUNDARY);
                    visited.add(b.getId());
                    WalkScope outer = c.parent;
                    follow(b, outer, m.out(b));
                    if (error || b.cancelActivity()) {
                        c.cancelled = true;
                        unit(outer);
                    } else {
                        carryOn(t);
                    }
                    return;
                }
            }
        }
        warnings.add((error ? "error " + code(((ErrorEventDefinition) thrown).getError())
                : "escalation " + code(((EscalationEventDefinition) thrown).getEscalation()))
                + " thrown at " + n.getId() + " is not caught; the walk continues as after a none event");
        carryOn(t);
    }

    /** After a throw that does not end the thrower: an intermediate event continues, an end event ends its token. */
    private void carryOn(Token t) {
        if (t.node() instanceof IntermediateThrowEvent) {
            follow(t.node(), t.scope(), m.out(t.node()));
        }
        consume(t);
    }

    private boolean catches(FlowNode catcher, EventDefinition thrown) {
        if (thrown instanceof ErrorEventDefinition te) {
            ErrorEventDefinition d = WalkModel.definition(catcher, ErrorEventDefinition.class);
            return d != null && (d.getError() == null || same(code(d.getError()), code(te.getError()), d.getError(), te.getError()));
        }
        EscalationEventDefinition te = (EscalationEventDefinition) thrown;
        EscalationEventDefinition d = WalkModel.definition(catcher, EscalationEventDefinition.class);
        return d != null && (d.getEscalation() == null
                || same(code(d.getEscalation()), code(te.getEscalation()), d.getEscalation(), te.getEscalation()));
    }

    /** Codes compare when both are set; otherwise the referenced elements must be the same one. */
    private static boolean same(String a, String b, Object x, Object y) {
        return a != null && b != null ? a.equals(b) : Objects.equals(x, y);
    }

    private static String code(Error e) {
        return e == null ? null : e.getErrorCode() != null ? e.getErrorCode() : e.getId();
    }

    private static String code(Escalation e) {
        return e == null ? null : e.getEscalationCode() != null ? e.getEscalationCode() : e.getId();
    }

    /**
     * Runs the compensation handlers of the activities that completed in the scope (or of the one named by
     * {@code activityRef}), newest first, each once. A completed sub-process without a handler of its own is
     * compensated through its inner activities, or through its compensation event sub-process. The throw event waits
     * for every handler before it continues or ends.
     */
    private void compensate(Token t, CompensateEventDefinition def) {
        FlowNode n = t.node();
        WalkScope s = t.scope();
        List<Handler> handlers = new ArrayList<>();
        collect(s.compensates, def.getActivity() == null ? null : def.getActivity().getId(), handlers);
        if (handlers.isEmpty()) {
            warnings.add("compensation thrown at " + n.getId() + " finds no completed activity with a handler");
            carryOn(t);
            return;
        }
        WalkScope comp = new WalkScope(Kind.COMPENSATION, s, null, n);
        s.live++;
        consume(t);
        for (Handler h : handlers) {
            move(h.via(), h.from(), h.target().getId(), Via.COMPENSATION);
            if (h.eventSubProcess() != null) {
                startEventSubProcess(comp, h.eventSubProcess(), (StartEvent) h.target(), h.inner());
            } else {
                push(h.target(), comp);
            }
        }
    }

    private void collect(WalkScope scope, String only, List<Handler> out) {
        for (int i = scope.done.size() - 1; i >= 0; i--) {
            Done d = scope.done.get(i);
            String id = d.activity().getId();
            if (only != null && !only.equals(id)) {
                continue;
            }
            BoundaryEvent cb = m.compensationBoundary(id);
            Association as = cb == null ? null : m.handlerAssociation(cb.getId());
            if (as != null) {
                out.add(new Handler(as.getId(), cb.getId(), (FlowNode) as.getTarget(), null, null));
                scope.done.remove(i);
                continue;
            }
            if (d.inner() == null) {
                continue;
            }
            SubProcess handler = m.eventSubProcesses(id).stream()
                    .filter(esp -> {
                        StartEvent st = WalkModel.startOf(esp);
                        return st != null && WalkModel.definition(st, CompensateEventDefinition.class) != null;
                    }).findFirst().orElse(null);
            if (handler != null) {
                out.add(new Handler(null, id, WalkModel.startOf(handler), handler, d.inner()));
                scope.done.remove(i);
            } else {
                collect(d.inner(), null, out);
            }
        }
    }

    /** Client-supplied event sub-process triggers: the start event id maps to the nodes at which the event occurs. */
    private boolean fireTriggers(FlowNode n, WalkScope s) {
        for (WalkScope host = s; host != null; host = host.parent) {
            for (SubProcess esp : m.eventSubProcesses(host.containerId)) {
                StartEvent start = WalkModel.startOf(esp);
                List<String> at = start == null ? null : choices.get(start.getId());
                int i = at == null ? 0 : used.getOrDefault(start.getId(), 0);
                if (at == null || i >= at.size() || !at.get(i).equals(n.getId())) {
                    continue;
                }
                used.put(start.getId(), i + 1);
                move(null, n.getId(), start.getId(), Via.EVENT_SUB_PROCESS);
                if (start.isInterrupting()) {
                    host.interrupt();
                    startEventSubProcess(host, esp, start, host);
                    return true;
                }
                startEventSubProcess(host, esp, start, host);
            }
        }
        return false;
    }

    private void startEventSubProcess(WalkScope host, SubProcess esp, StartEvent start, WalkScope compensates) {
        WalkScope inner = new WalkScope(Kind.EVENT_SUB_PROCESS, host, esp.getId(), esp);
        inner.compensates = compensates;
        host.live++;
        push(start, inner);
    }

    private void complete(WalkScope c) {
        if (c.ended || !c.alive()) {
            return;
        }
        c.ended = true;
        if (c.joinWaiting()) {
            warnings.add("a parallel join in " + (c.activator == null ? m.process.getId() : c.activator.getId())
                    + " still waits for tokens when its scope ends");
        }
        WalkScope outer = c.parent;
        switch (c.kind) {
            case PROCESS -> {
            }
            case SUB_PROCESS -> {
                Activity a = (Activity) c.activator;
                if (!a.isForCompensation()) {
                    outer.done.add(new Done(a, c));
                }
                follow(a, outer, m.out(a));
                unit(outer);
            }
            case EVENT_SUB_PROCESS -> unit(outer);
            case COMPENSATION -> {
                if (c.activator instanceof IntermediateThrowEvent) {
                    follow(c.activator, outer, m.out(c.activator));
                }
                unit(outer);
            }
        }
    }

    private void follow(FlowNode from, WalkScope s, List<SequenceFlow> flows) {
        for (SequenceFlow f : flows) {
            FlowNode target = f.getTarget();
            move(f.getId(), from.getId(), target.getId(), Via.FLOW);
            int arriving = m.incoming(target.getId());
            if (target instanceof ParallelGateway && arriving > 1) {
                int k = s.joins.merge(target.getId(), 1, Integer::sum);
                if (k < arriving) {
                    continue;
                }
                s.joins.put(target.getId(), 0);
            }
            push(target, s);
        }
    }

    private void move(String flowId, String from, String to, Via via) {
        moves.add(new Move(++step, flowId, from, to, via));
    }

    private void push(FlowNode node, WalkScope s) {
        tokens.add(new Token(node, s, s.epoch));
        s.live++;
    }

    private void consume(Token t) {
        if (t.alive()) {
            unit(t.scope());
        }
    }

    /** One live unit of the scope (a token or a child scope) has ended. */
    private void unit(WalkScope s) {
        s.live--;
        if (s.live <= 0) {
            complete(s);
        }
    }

    private String next(String elementId) {
        List<String> c = choices.getOrDefault(elementId, List.of());
        int i = used.getOrDefault(elementId, 0);
        if (i >= c.size()) {
            return null;
        }
        used.put(elementId, i + 1);
        return c.get(i);
    }
}
