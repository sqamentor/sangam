// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.feel;

import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.context.CoreExecutionContext;
import org.operaton.bpm.engine.impl.identity.Authentication;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;

import java.util.List;
import java.util.function.Supplier;

/**
 * Tenant of the current FEEL/BPMN evaluation. FEEL functions have no tenant parameter, so the tenant is taken, in
 * order, from: an explicit scope ({@link #runAs}), the executing process/case instance, the single authenticated tenant
 * of the command, and finally the configured default (single-tenant deployments).
 */
public final class GeoTenant {

    private static final ThreadLocal<String> SCOPE = new ThreadLocal<>();

    private GeoTenant() {
    }

    public static <T> T runAs(String tenantId, Supplier<T> work) {
        String prev = SCOPE.get();
        SCOPE.set(tenantId);
        try {
            return work.get();
        } finally {
            if (prev == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(prev);
            }
        }
    }

    public static String current(String defaultTenant) {
        String t = SCOPE.get();
        if (t != null) {
            return t;
        }
        try {
            CoreExecutionContext<?> ec = Context.getCoreExecutionContext();
            if (ec != null && ec.getExecution() != null && ec.getExecution().getTenantId() != null) {
                return ec.getExecution().getTenantId();
            }
            CommandContext cc = Context.getCommandContext();
            if (cc != null) {
                Authentication a = cc.getAuthentication();
                List<String> ids = a == null ? null : a.getTenantIds();
                if (ids != null && ids.size() == 1) {
                    return ids.get(0);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // not inside an Operaton command
        }
        return defaultTenant;
    }
}
