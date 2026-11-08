// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.bpmn;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.sangam.core.security.SecurityIdentity;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Service registry and engine helpers for the BPMN delegates of this module. The integrator registers the services once
 * at start-up ({@code SangamResources#registerForBpmn}); delegates are referenced from BPMN as
 * {@code operaton:class="org.sangam.resources.bpmn.HoldBookingDelegate"} (or as Spring beans by
 * {@code operaton:delegateExpression}). Delegates run inside the engine command and write on the engine connection, so
 * product rows commit or roll back with the engine state.
 */
public final class ResourcesRuntime {

    private static final Map<Class<?>, Object> SERVICES = new ConcurrentHashMap<>();
    private static volatile Function<DelegateExecution, SecurityIdentity> identityResolver = ResourcesRuntime::defaultIdentity;

    private ResourcesRuntime() {
    }

    public static <T> void register(Class<T> type, T service) {
        SERVICES.put(type, service);
    }

    public static <T> T service(Class<T> type) {
        Object s = SERVICES.get(type);
        if (s == null) {
            throw new IllegalStateException("resources service not registered: " + type.getSimpleName());
        }
        return type.cast(s);
    }

    /** Overrides how a delegate obtains the acting identity (default: system principal of the instance's tenant). */
    public static void identityResolver(Function<DelegateExecution, SecurityIdentity> resolver) {
        identityResolver = resolver;
    }

    public static SecurityIdentity identity(DelegateExecution execution) {
        return identityResolver.apply(execution);
    }

    /** The JDBC connection of the current Operaton command. */
    public static Connection connection() {
        CommandContext cc = Context.getCommandContext();
        if (cc == null) {
            throw new IllegalStateException("no Operaton command context: call only from engine delegates");
        }
        return cc.getDbSqlSession().getSqlSession().getConnection();
    }

    /** Reads a process variable as a string; {@code null} when absent. */
    public static String str(DelegateExecution e, String name) {
        Object v = e.getVariable(name);
        return v == null ? null : v.toString();
    }

    public static String required(DelegateExecution e, String name) {
        String v = str(e, name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("process variable '" + name + "' is required");
        }
        return v;
    }

    public static Optional<String> opt(DelegateExecution e, String name) {
        return Optional.ofNullable(str(e, name));
    }

    private static SecurityIdentity defaultIdentity(DelegateExecution e) {
        String tenant = e.getTenantId();
        if (tenant == null) {
            Object t = e.getVariable("tenantId");
            tenant = t == null ? null : t.toString();
        }
        if (tenant == null) {
            throw new IllegalStateException("the process instance has no tenant; cannot act on resources");
        }
        Object initiator = e.getVariable("initiator");
        SecurityIdentity sys = SecurityIdentity.system(tenant, initiator == null ? null : initiator.toString());
        return new SecurityIdentity(sys.tenantId(), sys.personId(), sys.subject(), Set.of(), sys.roles(), Set.of(), Set.of(),
                sys.authMethod(), null, sys.onBehalfOf(), true, Map.of());
    }
}
