// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.simulation;

import org.operaton.bpm.model.bpmn.instance.Activity;
import org.operaton.bpm.model.bpmn.instance.FlowNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One scope instance of a token walk: the process, an entered sub-process, a triggered event sub-process, or the
 * compensation a throw event waits for. A scope counts its live units (tokens and child scopes); at zero it completes.
 * An interruption raises {@link #epoch}: tokens and child scopes of an older epoch are dead from then on.
 */
final class WalkScope {

    enum Kind { PROCESS, SUB_PROCESS, EVENT_SUB_PROCESS, COMPENSATION }

    /** An activity that completed in this scope, with the scope it ran (a sub-process) or null. */
    record Done(Activity activity, WalkScope inner) {
    }

    final Kind kind;
    final WalkScope parent;
    private final int parentEpoch;
    /** Id of the process or sub-process whose event sub-processes this scope hosts; null for compensation. */
    final String containerId;
    /** The sub-process, the event sub-process, or the compensation throw event; null for the process. */
    final FlowNode activator;
    /** The scope whose completed activities a compensation thrown inside this scope compensates. */
    WalkScope compensates;
    int epoch;
    int live;
    boolean cancelled;
    boolean ended;
    final List<Done> done = new ArrayList<>();
    final Map<String, Integer> joins = new HashMap<>();

    WalkScope(Kind kind, WalkScope parent, String containerId, FlowNode activator) {
        this.kind = kind;
        this.parent = parent;
        this.parentEpoch = parent == null ? 0 : parent.epoch;
        this.containerId = containerId;
        this.activator = activator;
        this.compensates = this;
    }

    boolean alive() {
        return !cancelled && (parent == null || (parentEpoch == parent.epoch && parent.alive()));
    }

    /** Cancels everything running in this scope (tokens, child scopes, join arrivals); the scope itself stays. */
    void interrupt() {
        epoch++;
        live = 0;
        joins.clear();
    }

    boolean joinWaiting() {
        return joins.values().stream().anyMatch(n -> n > 0);
    }
}
