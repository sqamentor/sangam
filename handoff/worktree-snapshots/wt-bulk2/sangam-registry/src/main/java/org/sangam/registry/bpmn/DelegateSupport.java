// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.bpmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.sangam.core.layer.Hashes;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryModule;

import java.sql.Connection;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Shared plumbing of the registry's BPMN delegates. Registry rows are written on the engine command's own JDBC
 * connection, so they commit or roll back with the process state (same transaction). The context comes from the
 * execution: tenant = the process instance's tenant id (or variable {@code sangamTenant}), actor =
 * {@code sangamActor} (else {@code system:process}), on-behalf-of = {@code lastActorSeat}, operationId = UUIDv5 of
 * (business key, activity, process instance) (T35). Registry rule violations become the BPMN error
 * {@code REGISTRY_<CODE>} so a model can catch them on a boundary event.
 */
public final class DelegateSupport {

    private static final UUID OP_NS = UUID.fromString("8d3c2b1a-4e5f-4a6b-9c7d-1e2f3a4b5c6d");

    private DelegateSupport() {
    }

    public static RegistryModule module() {
        return RegistryModule.instance();
    }

    public static Connection connection() {
        CommandContext cc = Context.getCommandContext();
        if (cc == null) {
            throw new ProcessEngineException("registry delegates run only inside an Operaton command");
        }
        return cc.getDbSqlSession().getSqlSession().getConnection();
    }

    public static RegistryContext context(DelegateExecution ex) {
        String tenant = ex.getTenantId() != null ? ex.getTenantId() : str(ex, "sangamTenant");
        if (tenant == null) {
            throw new ProcessEngineException("registry delegate: no tenant (process tenant id or sangamTenant)");
        }
        String actor = str(ex, "sangamActor");
        String bk = ex.getProcessBusinessKey() == null ? ex.getProcessInstanceId() : ex.getProcessBusinessKey();
        String op = Hashes.uuidV5(OP_NS, bk + "|" + ex.getCurrentActivityId() + "|" + ex.getProcessInstanceId()).toString();
        String zone = str(ex, "sangamZone");
        return new RegistryContext(tenant, actor == null ? "system:process" : actor, str(ex, "lastActorSeat"),
                str(ex, "sangamPurpose"), op, zone == null ? null : ZoneId.of(zone));
    }

    /** Runs registry work on the engine connection, translating registry errors to BPMN errors. */
    public static <T> T run(DelegateExecution ex, BiFunction<Connection, RegistryContext, T> work) {
        try {
            return work.apply(connection(), context(ex));
        } catch (RegistryException e) {
            throw new BpmnError("REGISTRY_" + e.code().name(), e.getMessage());
        }
    }

    /** A field-injected expression's value, or the default. */
    public static String field(Expression e, DelegateExecution ex, String def) {
        if (e == null) {
            return def;
        }
        Object v = e.getValue(ex);
        return v == null || v.toString().isBlank() ? def : v.toString();
    }

    /** A field naming a variable: returns the variable's value (or the literal when no variable has that name). */
    public static Object valueOf(Expression e, DelegateExecution ex) {
        if (e == null) {
            return null;
        }
        Object v = e.getValue(ex);
        if (v instanceof String s && ex.hasVariable(s)) {
            return ex.getVariable(s);
        }
        return v;
    }

    public static String str(DelegateExecution ex, String var) {
        Object v = ex.getVariable(var);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(DelegateExecution ex, String var) {
        Object v = var == null ? null : ex.getVariable(var);
        if (v instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        if (v instanceof String s && s.trim().startsWith("{")) {
            return org.sangam.registry.support.Json.map(s);
        }
        return new LinkedHashMap<>();
    }

    /** Output variable name from a field, default {@code def}. */
    public static String out(Expression e, DelegateExecution ex, String def) {
        return field(e, ex, def);
    }
}
