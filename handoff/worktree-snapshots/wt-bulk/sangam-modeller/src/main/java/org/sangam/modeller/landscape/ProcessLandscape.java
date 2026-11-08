// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.landscape;

import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.CallActivity;
import org.operaton.bpm.model.bpmn.instance.Process;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Process landscape: a read-only graph of call-activity dependencies over the models the caller may read. The caller filters models by
 * R19 before this runs, so a hidden model appears only as a missing target and exposes no content. An expression target such as
 * {@code ${x}} cannot be resolved statically and is reported as dynamic.
 */
public final class ProcessLandscape {

    public record Model(String packageId, String resourceName, String bpmnXml) {
    }

    public record Edge(String fromProcess, String callActivityId, String target, String binding, String version, Kind kind) {
    }

    public enum Kind { RESOLVED, MISSING, DYNAMIC }

    public record Graph(Set<String> processes, List<Edge> edges, List<String> unparsable, List<List<String>> cycles) {
    }

    private ProcessLandscape() {
    }

    public static Graph build(Collection<Model> models) {
        Set<String> processes = new TreeSet<>();
        List<String> bad = new ArrayList<>();
        Map<String, BpmnModelInstance> parsed = new LinkedHashMap<>();
        for (Model m : models) {
            try {
                BpmnModelInstance inst = Bpmn.readModelFromStream(new ByteArrayInputStream(m.bpmnXml().getBytes(StandardCharsets.UTF_8)));
                parsed.put(m.packageId() + "/" + m.resourceName(), inst);
                inst.getModelElementsByType(Process.class).forEach(p -> processes.add(p.getId()));
            } catch (RuntimeException e) {
                bad.add(m.packageId() + "/" + m.resourceName());
            }
        }
        List<Edge> edges = new ArrayList<>();
        for (BpmnModelInstance inst : parsed.values()) {
            for (CallActivity ca : inst.getModelElementsByType(CallActivity.class)) {
                var parent = ca.getParentElement();
                while (parent != null && !(parent instanceof Process)) {
                    parent = parent.getParentElement();
                }
                String from = parent == null ? "?" : ((Process) parent).getId();
                String target = ca.getCalledElement();
                Kind kind = target == null || target.contains("${") || target.contains("#{") ? Kind.DYNAMIC : processes.contains(target) ? Kind.RESOLVED : Kind.MISSING;
                edges.add(new Edge(from, ca.getId(), target, ca.getOperatonCalledElementBinding(), ca.getOperatonCalledElementVersion(), kind));
            }
        }
        return new Graph(processes, edges, bad, cycles(edges));
    }

    private static List<List<String>> cycles(List<Edge> edges) {
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        edges.stream().filter(e -> e.kind() == Kind.RESOLVED).forEach(e -> adj.computeIfAbsent(e.fromProcess(), x -> new LinkedHashSet<>()).add(e.target()));
        List<List<String>> out = new ArrayList<>();
        Set<String> done = new java.util.HashSet<>();
        for (String s : adj.keySet()) {
            walk(s, adj, new ArrayList<>(), done, out);
        }
        return out;
    }

    private static void walk(String n, Map<String, Set<String>> adj, List<String> path, Set<String> done, List<List<String>> out) {
        int at = path.indexOf(n);
        if (at >= 0) {
            out.add(List.copyOf(path.subList(at, path.size())));
            return;
        }
        if (!done.add(n)) {
            return;
        }
        path.add(n);
        adj.getOrDefault(n, Set.of()).forEach(t -> walk(t, adj, path, done, out));
        path.remove(path.size() - 1);
    }
}
