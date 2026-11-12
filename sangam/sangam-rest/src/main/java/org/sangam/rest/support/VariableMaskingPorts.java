// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.port.VariableMaskPort;

/**
 * Owner decisions 87 and 98: applies {@link VariableMasking} to the result of every variable read of the engine ports,
 * whichever module implements them (the sangam-rest adapters or a server override), so no controller and no adapter
 * carries its own copy. The wrapped reads:
 * <ul>
 *   <li>{@link TaskPort#variables}, the card variables of {@link TaskPort#list} rows, the variables of
 *       {@link TaskPort#get} (the detail's map and its task's card variables; the payload hash is the engine's and is
 *       not changed);</li>
 *   <li>{@link ProcessPort#variables} (both forms) and the map {@link ProcessPort#setVariables} returns;</li>
 *   <li>{@link CasePort#variables};</li>
 *   <li>the rows of {@link HistoryPort#variables} (a masked row keeps its name and type, its value is {@value #MASK}).</li>
 * </ul>
 * Every other method is passed through unchanged. The proxy implements every interface of the wrapped object.
 */
public final class VariableMaskingPorts {

    /** The masked value ({@code FieldMaskService.MASK}). */
    public static final String MASK = "***";

    private static final Set<Class<?>> PORTS = Set.of(TaskPort.class, ProcessPort.class, CasePort.class, HistoryPort.class);

    private VariableMaskingPorts() {
    }

    /** Whether {@code bean} is an engine port whose variable reads are masked (and not wrapped already). */
    public static boolean applies(Object bean) {
        if (bean == null || (Proxy.isProxyClass(bean.getClass())
                && Proxy.getInvocationHandler(bean) instanceof Handler)) {
            return false;
        }
        return PORTS.stream().anyMatch(p -> p.isInstance(bean));
    }

    /**
     * The object behind a masking proxy, else {@code bean} itself: for an internal lookup inside another operation
     * (it computes with the engine's values and returns no variables to the caller), never for a read a client sees.
     */
    public static Object unwrap(Object bean) {
        return bean != null && Proxy.isProxyClass(bean.getClass()) && Proxy.getInvocationHandler(bean) instanceof Handler h
                ? h.target : bean;
    }

    /** {@code bean} behind a proxy that masks its variable reads with the masking {@code masking} supplies. */
    public static Object wrap(Object bean, Supplier<VariableMasking> masking) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> c = bean.getClass(); c != null; c = c.getSuperclass()) {
            collect(c, interfaces);
        }
        return Proxy.newProxyInstance(bean.getClass().getClassLoader(), interfaces.toArray(Class<?>[]::new),
                new Handler(bean, masking));
    }

    private static void collect(Class<?> c, Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) {
            if (out.add(i)) {
                collect(i, out);
            }
        }
    }

    private static final class Handler implements InvocationHandler {
        private final Object target;
        private final Supplier<VariableMasking> masking;

        Handler(Object target, Supplier<VariableMasking> masking) {
            this.target = target;
            this.masking = masking;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> m.invoke(target, args);
                };
            }
            Object out;
            try {
                out = m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            VariableMasking vm = masking.get();
            if (out == null || vm == null || !vm.active() || args == null || !(args[0] instanceof RequestContext ctx)) {
                return out;
            }
            return masked(vm, ctx, m, args, out);
        }
    }

    @SuppressWarnings("unchecked")
    static Object masked(VariableMasking vm, RequestContext ctx, Method m, Object[] args, Object out) {
        Class<?> port = m.getDeclaringClass();
        String name = m.getName();
        if (port == TaskPort.class) {
            if (name.equals("variables") && args.length == 2) {
                return vm.taskVariables(ctx, (String) args[1], (Map<String, Object>) out);
            }
            if (name.equals("list") && out instanceof Page<?> page) {
                return tasks(vm, ctx, (Page<TaskPort.Task>) page);
            }
            if (name.equals("get") && out instanceof TaskPort.TaskDetail d) {
                return detail(vm, ctx, d);
            }
        } else if (port == ProcessPort.class) {
            if ((name.equals("variables") || name.equals("setVariables")) && args.length >= 2) {
                return vm.processVariables(ctx, (String) args[1], (Map<String, Object>) out);
            }
        } else if (port == CasePort.class) {
            if (name.equals("variables") && args.length == 2) {
                return vm.caseVariables(ctx, (String) args[1], (Map<String, Object>) out);
            }
        } else if (port == HistoryPort.class) {
            if (name.equals("variables") && out instanceof Page<?> page) {
                HistoryPort.HistoryFilter f = args.length > 1 && args[1] instanceof HistoryPort.HistoryFilter h ? h : null;
                return history(vm, ctx, (Page<HistoryPort.HistoricVariable>) page, f);
            }
        }
        return out;
    }

    /** The owner of a task row: its process instance and definition key, else its case instance. */
    static VariableMasking.Owner owner(VariableMasking vm, TaskPort.Task t) {
        if (t.processInstanceId() != null) {
            return t.processDefinitionKey() != null
                    ? new VariableMasking.Owner(VariableMaskPort.PROCESS_INSTANCE, t.processInstanceId(), t.processDefinitionKey())
                    : vm.owners().ofProcessInstance(t.processInstanceId());
        }
        return t.caseInstanceId() == null ? null : vm.owners().ofCaseInstance(t.caseInstanceId());
    }

    static Page<TaskPort.Task> tasks(VariableMasking vm, RequestContext ctx, Page<TaskPort.Task> page) {
        List<TaskPort.Task> rows = page.items();
        List<VariableMasking.Owner> owners = new ArrayList<>(rows.size());
        List<Map<String, Object>> vars = new ArrayList<>(rows.size());
        for (TaskPort.Task t : rows) {
            owners.add(t.variables() == null || t.variables().isEmpty() ? null : owner(vm, t));
            vars.add(t.variables());
        }
        List<Map<String, Object>> masked = vm.maskAll(ctx, owners, vars);
        List<TaskPort.Task> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            out.add(masked.get(i) == rows.get(i).variables() ? rows.get(i) : rows.get(i).withVariables(masked.get(i)));
        }
        return new Page<>(out, page.total(), page.capped());
    }

    static TaskPort.TaskDetail detail(VariableMasking vm, RequestContext ctx, TaskPort.TaskDetail d) {
        if (d.task() == null) {
            return d;
        }
        VariableMasking.Owner o = owner(vm, d.task());
        TaskPort.TaskDetail out = d;
        if (d.variables() != null && !d.variables().isEmpty()) {
            out = with(out, "variables", vm.mask(ctx, o, d.variables()));
        }
        if (d.task().variables() != null && !d.task().variables().isEmpty()) {
            out = with(out, "task", d.task().withVariables(vm.mask(ctx, o, d.task().variables())));
        }
        return out;
    }

    static Page<HistoryPort.HistoricVariable> history(VariableMasking vm, RequestContext ctx,
                                                      Page<HistoryPort.HistoricVariable> page, HistoryPort.HistoryFilter f) {
        List<HistoryPort.HistoricVariable> rows = page.items();
        List<VariableMasking.Owner> owners = new ArrayList<>(rows.size());
        List<String> names = new ArrayList<>(rows.size());
        Map<String, VariableMasking.Owner> seen = new java.util.HashMap<>();
        for (HistoryPort.HistoricVariable v : rows) {
            VariableMasking.Owner o = null;
            if (v.processInstanceId() != null) {
                o = seen.computeIfAbsent("p:" + v.processInstanceId(), k -> vm.owners().ofProcessInstance(v.processInstanceId()));
            } else if (f != null && f.caseInstanceId() != null) {
                o = seen.computeIfAbsent("c:" + f.caseInstanceId(), k -> vm.owners().ofCaseInstance(f.caseInstanceId()));
            }
            owners.add(o);
            names.add(v.name());
        }
        List<Boolean> masked = vm.maskedRows(ctx, owners, names);
        List<HistoryPort.HistoricVariable> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            out.add(masked.get(i) ? with(rows.get(i), "value", MASK) : rows.get(i));
        }
        return new Page<>(out, page.total(), page.capped());
    }

    /** A copy of record {@code r} with one component replaced (canonical constructor). */
    @SuppressWarnings("unchecked")
    static <R extends Record> R with(R r, String component, Object value) {
        RecordComponent[] cs = r.getClass().getRecordComponents();
        Object[] values = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        boolean found = false;
        try {
            for (int i = 0; i < cs.length; i++) {
                types[i] = cs[i].getType();
                if (cs[i].getName().equals(component)) {
                    values[i] = value;
                    found = true;
                } else {
                    values[i] = cs[i].getAccessor().invoke(r);
                }
            }
            if (!found) {
                throw new IllegalArgumentException(r.getClass().getSimpleName() + " has no component " + component);
            }
            Constructor<?> ctor = r.getClass().getDeclaredConstructor(types);
            return (R) ctor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot copy " + r.getClass().getSimpleName(), e);
        }
    }
}
