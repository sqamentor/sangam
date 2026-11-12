// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.VariableMaskPort;

/**
 * Owner decisions 87 and 98: the one masking step of every variable read in sangam-rest. Each variable map is masked
 * for its owner: the owning process instance and its process definition key ({@link VariableMaskPort#PROCESS_INSTANCE}),
 * or, for a CMMN case instance without a process, the case instance and its case definition key. The field rules and
 * the permission check are the security module's ({@link VariableMaskPort}); without it every read passes unchanged and
 * no owner is looked up. A variable with no owning definition (a standalone task) has no rule to apply and is returned
 * as it is. Applied to the ports by {@link VariableMaskingPorts}; plain Java (no host framework).
 */
public final class VariableMasking {

    /** The instance a variable map belongs to and the definition key its field rules are classified under. */
    public record Owner(String resourceType, String instanceId, String definitionKey) {
        public Owner {
            Objects.requireNonNull(resourceType, "resourceType");
            Objects.requireNonNull(instanceId, "instanceId");
        }
    }

    /** Resolves owners from engine ids; null when the instance or task is unknown or has no owning definition. */
    public interface Owners {
        Owner ofProcessInstance(String processInstanceId);

        Owner ofCaseInstance(String caseInstanceId);

        Owner ofTask(String taskId);
    }

    private static final Owners NO_OWNERS = new Owners() {
        @Override
        public Owner ofProcessInstance(String processInstanceId) {
            return null;
        }

        @Override
        public Owner ofCaseInstance(String caseInstanceId) {
            return null;
        }

        @Override
        public Owner ofTask(String taskId) {
            return null;
        }
    };

    private final Supplier<VariableMaskPort> port;
    private final Owners owners;

    /** @param port the security module's masks, or a supplier of null when it is not bound (resolved per read) */
    public VariableMasking(Supplier<VariableMaskPort> port, Owners owners) {
        this.port = Objects.requireNonNull(port, "port");
        this.owners = owners == null ? NO_OWNERS : owners;
    }

    /** Masks nothing. */
    public static VariableMasking none() {
        return new VariableMasking(() -> null, NO_OWNERS);
    }

    /** Whether a mask implementation is bound; when false every method returns its input. */
    public boolean active() {
        return port.get() != null;
    }

    public Owners owners() {
        return owners;
    }

    public Map<String, Object> processVariables(RequestContext ctx, String processInstanceId, Map<String, Object> vars) {
        return empty(vars) || !active() ? vars : mask(ctx, owners.ofProcessInstance(processInstanceId), vars);
    }

    public Map<String, Object> caseVariables(RequestContext ctx, String caseInstanceId, Map<String, Object> vars) {
        return empty(vars) || !active() ? vars : mask(ctx, owners.ofCaseInstance(caseInstanceId), vars);
    }

    public Map<String, Object> taskVariables(RequestContext ctx, String taskId, Map<String, Object> vars) {
        return empty(vars) || !active() ? vars : mask(ctx, owners.ofTask(taskId), vars);
    }

    /** One map of one owner; a null owner or definition key leaves it unchanged. */
    public Map<String, Object> mask(RequestContext ctx, Owner owner, Map<String, Object> vars) {
        return maskAll(ctx, java.util.Collections.singletonList(owner), java.util.Collections.singletonList(vars)).get(0);
    }

    /**
     * Maps of possibly many owners, in order: one port call per (resource type, definition key), so a page is masked
     * set-based (M-086). An entry with a null owner, definition key or map is returned unchanged.
     */
    public List<Map<String, Object>> maskAll(RequestContext ctx, List<Owner> rowOwners, List<Map<String, Object>> vars) {
        if (rowOwners.size() != vars.size()) {
            throw new IllegalArgumentException("one owner per variable map");
        }
        List<Map<String, Object>> out = new ArrayList<>(vars);
        VariableMaskPort p = port.get();
        if (p == null) {
            return out;
        }
        Map<List<String>, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < vars.size(); i++) {
            Owner o = rowOwners.get(i);
            if (o != null && o.definitionKey() != null && !empty(vars.get(i))) {
                groups.computeIfAbsent(List.of(o.resourceType(), o.definitionKey()), k -> new ArrayList<>()).add(i);
            }
        }
        for (Map.Entry<List<String>, List<Integer>> g : groups.entrySet()) {
            List<String> ids = new ArrayList<>();
            List<Map<String, Object>> maps = new ArrayList<>();
            for (int i : g.getValue()) {
                ids.add(rowOwners.get(i).instanceId());
                maps.add(vars.get(i));
            }
            List<Map<String, Object>> masked = p.mask(ctx, g.getKey().get(0), g.getKey().get(1), ids, maps);
            if (masked == null || masked.size() != maps.size()) {
                throw new IllegalStateException("the variable mask returned " + (masked == null ? "nothing" : masked.size()
                        + " maps") + " for " + maps.size());
            }
            for (int j = 0; j < g.getValue().size(); j++) {
                out.set(g.getValue().get(j), masked.get(j));
            }
        }
        return out;
    }

    /**
     * Single-variable rows (history): whether each row's value is masked. The rows of one owner are masked together as
     * one map of their names, so a name is decided once per owner whatever its value.
     */
    public List<Boolean> maskedRows(RequestContext ctx, List<Owner> rowOwners, List<String> names) {
        List<Boolean> out = new ArrayList<>(java.util.Collections.nCopies(names.size(), Boolean.FALSE));
        if (port.get() == null) {
            return out;
        }
        Map<Owner, Map<String, Object>> byOwner = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            Owner o = rowOwners.get(i);
            if (o != null && names.get(i) != null) {
                byOwner.computeIfAbsent(o, k -> new LinkedHashMap<>()).put(names.get(i), Boolean.TRUE);
            }
        }
        List<Owner> distinct = new ArrayList<>(byOwner.keySet());
        List<Map<String, Object>> masked = maskAll(ctx, distinct, new ArrayList<>(byOwner.values()));
        Map<Owner, Map<String, Object>> result = new LinkedHashMap<>();
        for (int i = 0; i < distinct.size(); i++) {
            result.put(distinct.get(i), masked.get(i));
        }
        for (int i = 0; i < names.size(); i++) {
            Map<String, Object> m = rowOwners.get(i) == null ? null : result.get(rowOwners.get(i));
            out.set(i, m != null && names.get(i) != null && !Boolean.TRUE.equals(m.get(names.get(i))));
        }
        return out;
    }

    private static boolean empty(Map<String, Object> vars) {
        return vars == null || vars.isEmpty();
    }
}
