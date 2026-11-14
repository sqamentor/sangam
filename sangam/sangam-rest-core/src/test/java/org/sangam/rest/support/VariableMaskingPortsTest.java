// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.port.VariableMaskPort;

/**
 * Decisions 87 and 98: the one masking step applied to the engine ports. A recording mask hides {@code salary} for
 * every instance except {@code pi-ok} under definition {@code payroll}; owners are the owning process instance and its
 * definition key, or the case instance for a CMMN case. Covers every wrapped read, the set-based grouping (one mask
 * call per resource type and definition key), the pass-through without a security module (no owner lookups), and that
 * other methods, ownerless rows and internal lookups ({@code unwrap}) are untouched. Written for the test phase (not
 * run here).
 */
class VariableMaskingPortsTest {

    static final RequestContext CTX = new RequestContext("t1", "u1", Set.of("clerk"), null, null, null, null, null, "OIDC");

    record Call(String resourceType, String definitionKey, List<String> ids) {
    }

    /** Hides "salary" unless the owning instance is pi-ok. */
    static final class RecordingMask implements VariableMaskPort {
        final List<Call> calls = new ArrayList<>();

        @Override
        public List<Map<String, Object>> mask(RequestContext ctx, String resourceType, String definitionKey,
                                              List<String> instanceIds, List<Map<String, Object>> variables) {
            calls.add(new Call(resourceType, definitionKey, List.copyOf(instanceIds)));
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < variables.size(); i++) {
                Map<String, Object> m = new LinkedHashMap<>(variables.get(i));
                if (m.containsKey("salary") && !"pi-ok".equals(instanceIds.get(i))) {
                    m.put("salary", VariableMaskingPorts.MASK);
                }
                out.add(m);
            }
            return out;
        }
    }

    /** Owners: pi-* under payroll, case c-1 under the case definition claims, task t-* in pi-<n>. */
    static final class FakeOwners implements VariableMasking.Owners {
        int lookups;

        @Override
        public VariableMasking.Owner ofProcessInstance(String id) {
            lookups++;
            return new VariableMasking.Owner(VariableMaskPort.PROCESS_INSTANCE, id, "payroll");
        }

        @Override
        public VariableMasking.Owner ofCaseInstance(String id) {
            lookups++;
            return "c-1".equals(id) ? new VariableMasking.Owner(VariableMaskPort.CASE_INSTANCE, id, "claims") : null;
        }

        @Override
        public VariableMasking.Owner ofTask(String taskId) {
            lookups++;
            return taskId.equals("standalone") ? null : ofProcessInstance("pi-" + taskId.substring(2));
        }
    }

    final RecordingMask mask = new RecordingMask();
    final FakeOwners owners = new FakeOwners();
    final VariableMasking masking = new VariableMasking(() -> mask, owners);

    static Map<String, Object> vars() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("salary", 50000);
        m.put("name", "Asha");
        return m;
    }

    /** A port stub answering each method by name; every other call is an error. */
    @SuppressWarnings("unchecked")
    static <P> P stub(Class<P> port, Map<String, Function<Object[], Object>> answers) {
        return (P) Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (p, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return m.getName().equals("equals") ? p == args[0] : m.getName().equals("hashCode") ? 1 : "stub";
            }
            if (m.isDefault()) {
                return java.lang.reflect.InvocationHandler.invokeDefault(p, m, args);
            }
            Function<Object[], Object> a = answers.get(m.getName());
            if (a == null) {
                throw new UnsupportedOperationException(m.getName());
            }
            return a.apply(args);
        });
    }

    @SuppressWarnings("unchecked")
    static <P> P wrapped(P port) {
        return (P) VariableMaskingPorts.wrap(port, () -> null);
    }

    @SuppressWarnings("unchecked")
    <P> P masked(P port) {
        return (P) VariableMaskingPorts.wrap(port, () -> masking);
    }

    static TaskPort.Task task(String id, String pi, String key, String caseId, Map<String, Object> vars) {
        return new TaskPort.Task(id, "n", null, "approve", pi, key, caseId, null, null, null, null, "t1", null, null, null,
                null, null, "CREATED", 50, null, null, vars);
    }

    /** A record with the given components and null / zero / false elsewhere. */
    @SuppressWarnings("unchecked")
    static <R extends Record> R record(Class<R> type, Map<String, Object> values) throws Exception {
        RecordComponent[] cs = type.getRecordComponents();
        Object[] args = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        for (int i = 0; i < cs.length; i++) {
            types[i] = cs[i].getType();
            Object v = values.get(cs[i].getName());
            if (v == null && types[i].isPrimitive()) {
                v = types[i] == boolean.class ? false : types[i] == long.class ? 0L : 0;
            }
            args[i] = v;
        }
        return (R) type.getDeclaredConstructor(types).newInstance(args);
    }

    @Test
    void processVariablesAndTheSetVariablesResultAreMaskedForTheirInstance() {
        ProcessPort raw = stub(ProcessPort.class, Map.of("variables", a -> vars(), "setVariables", a -> vars()));
        ProcessPort p = masked(raw);
        assertEquals("***", p.variables(CTX, "pi-1").get("salary"));
        assertEquals("Asha", p.variables(CTX, "pi-1").get("name"));
        assertEquals(50000, p.variables(CTX, "pi-ok").get("salary"));
        assertEquals("***", p.variables(CTX, "pi-1", "act-1").get("salary"), "the activity-scope form too");
        assertEquals("***", p.setVariables(CTX, "pi-1", Map.of("x", 1)).get("salary"));
        assertEquals(new Call(VariableMaskPort.PROCESS_INSTANCE, "payroll", List.of("pi-1")), mask.calls.get(0));
    }

    @Test
    void taskVariablesUseTheTaskOwningProcessInstance() {
        TaskPort p = masked(stub(TaskPort.class, Map.of("variables", a -> vars())));
        assertEquals("***", p.variables(CTX, "t-1").get("salary"));
        assertEquals(50000, p.variables(CTX, "t-ok").get("salary"));
        assertEquals(50000, p.variables(CTX, "standalone").get("salary"), "no owning definition, no rule applies");
    }

    @Test
    void taskListCardVariablesAreMaskedSetBasedPerDefinition() {
        List<TaskPort.Task> rows = List.of(task("a", "pi-1", "payroll", null, vars()), task("b", "pi-ok", "payroll", null,
                vars()), task("c", "pi-2", "leave", null, vars()), task("d", "pi-3", "payroll", null, null));
        TaskPort p = masked(stub(TaskPort.class, Map.of("list", a -> new Page<>(rows, 4, false))));
        Page<TaskPort.Task> out = p.list(CTX, null, null);
        assertEquals("***", out.items().get(0).variables().get("salary"));
        assertEquals(50000, out.items().get(1).variables().get("salary"));
        assertEquals("***", out.items().get(2).variables().get("salary"));
        assertEquals(null, out.items().get(3).variables(), "a row without card variables is unchanged");
        assertEquals(4, out.total());
        assertEquals(2, mask.calls.size(), "one call per (resource type, definition key)");
        assertEquals(List.of("pi-1", "pi-ok"), mask.calls.get(0).ids());
        assertEquals(0, owners.lookups, "task rows carry their definition key: no lookup");
    }

    @Test
    void taskDetailVariablesAndCardVariablesAreMaskedAndTheHashIsTheEngines() throws Exception {
        TaskPort.TaskDetail d = record(TaskPort.TaskDetail.class, Map.of("task", task("x", "pi-1", "payroll", null, vars()),
                "variables", vars(), "payloadHash", "h-1"));
        TaskPort p = masked(stub(TaskPort.class, Map.of("get", a -> d)));
        TaskPort.TaskDetail out = p.get(CTX, "x");
        assertEquals("***", out.variables().get("salary"));
        assertEquals("***", out.task().variables().get("salary"));
        assertEquals("h-1", out.payloadHash());
    }

    @Test
    void caseVariablesUseTheCaseOwner() {
        CasePort p = masked(stub(CasePort.class, Map.of("variables", a -> vars())));
        assertEquals("***", p.variables(CTX, "c-1").get("salary"));
        assertEquals(new Call(VariableMaskPort.CASE_INSTANCE, "claims", List.of("c-1")), mask.calls.get(0));
        assertEquals(50000, p.variables(CTX, "c-unknown").get("salary"), "no owner, no rule");
    }

    @Test
    void historyRowsHideTheValueOfAMaskedNameOnly() {
        List<HistoryPort.HistoricVariable> rows = List.of(
                new HistoryPort.HistoricVariable("1", "salary", "Integer", 50000, "pi-1", null, null, "CREATED"),
                new HistoryPort.HistoricVariable("2", "name", "String", "Asha", "pi-1", null, null, "CREATED"),
                new HistoryPort.HistoricVariable("3", "salary", "Integer", 40000, "pi-1", "act-2", null, "CREATED"),
                new HistoryPort.HistoricVariable("4", "salary", "Integer", 70000, "pi-ok", null, null, "CREATED"));
        HistoryPort p = masked(stub(HistoryPort.class, Map.of("variables", a -> new Page<>(rows, 4, false))));
        Page<HistoryPort.HistoricVariable> out = p.variables(CTX, null, null);
        assertEquals("***", out.items().get(0).value());
        assertEquals("Integer", out.items().get(0).type(), "name and type are kept");
        assertEquals("Asha", out.items().get(1).value());
        assertEquals("***", out.items().get(2).value(), "every row of a masked name, whatever its scope");
        assertEquals(70000, out.items().get(3).value());
        assertEquals(1, mask.calls.size());
        assertEquals(2, owners.lookups, "one owner lookup per instance of the page");
    }

    @Test
    void withoutASecurityModuleNothingIsMaskedAndNoOwnerIsLookedUp() {
        VariableMasking off = new VariableMasking(() -> null, owners);
        @SuppressWarnings("unchecked")
        ProcessPort p = (ProcessPort) VariableMaskingPorts.wrap(
                stub(ProcessPort.class, Map.of("variables", a -> vars())), () -> off);
        assertEquals(50000, p.variables(CTX, "pi-1").get("salary"));
        assertEquals(0, owners.lookups);
        assertEquals(50000, wrapped(stub(ProcessPort.class, Map.of("variables", a -> vars()))).variables(CTX, "pi-1")
                .get("salary"), "no masking bean at all");
        assertTrue(mask.calls.isEmpty());
    }

    @Test
    void otherMethodsPassThroughAndInternalLookupsSeeTheTarget() {
        TaskPort raw = stub(TaskPort.class, Map.of("variables", a -> vars()));
        TaskPort p = masked(raw);
        assertTrue(VariableMaskingPorts.applies(raw));
        assertFalse(VariableMaskingPorts.applies(p), "never wrapped twice");
        assertSame(raw, VariableMaskingPorts.unwrap(p));
        assertSame(raw, VariableMaskingPorts.unwrap(raw));
        assertFalse(VariableMaskingPorts.applies(new Object()));
        assertEquals(50000, ((TaskPort) VariableMaskingPorts.unwrap(p)).variables(CTX, "t-1").get("salary"));
    }
}
