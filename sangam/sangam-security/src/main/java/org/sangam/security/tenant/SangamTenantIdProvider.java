// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.tenant;

import org.operaton.bpm.engine.impl.cfg.multitenancy.TenantIdProvider;
import org.operaton.bpm.engine.impl.cfg.multitenancy.TenantIdProviderCaseInstanceContext;
import org.operaton.bpm.engine.impl.cfg.multitenancy.TenantIdProviderHistoricDecisionInstanceContext;
import org.operaton.bpm.engine.impl.cfg.multitenancy.TenantIdProviderProcessInstanceContext;
import org.sangam.core.security.SecurityIdentity;

/**
 * Operaton {@link TenantIdProvider}: every new process, case and decision instance gets the tenant of the
 * authenticated identity (VI.4 rule 6), so every engine row carries {@code TENANT_ID_} and the VI.4 predicate applies.
 *
 * <p>Rules: a definition deployed to a tenant keeps that tenant (Operaton passes it through and does not call the
 * provider); a shared definition (no tenant) started by a tenant user is bound to the caller's tenant (FLOWABLE-INTO-OPERATON FC-33: fallback
 * from the tenant to shared definitions); a sub-instance inherits the parent's tenant. Without a bound identity
 * (e.g. a timer start of a shared definition) no tenant is assigned: such rows match no VI.4 predicate (the predicate
 * requires {@code tenant = :tenant}), so they are invisible to every tenant (fail closed) and a warning is logged.
 * Packs should deploy definitions per tenant so this path is not used.
 */
public final class SangamTenantIdProvider implements TenantIdProvider {

    @Override
    public String provideTenantIdForProcessInstance(TenantIdProviderProcessInstanceContext ctx) {
        if (ctx.getSuperExecution() != null && ctx.getSuperExecution().getTenantId() != null) {
            return ctx.getSuperExecution().getTenantId();
        }
        if (ctx.getSuperCaseExecution() != null && ctx.getSuperCaseExecution().getTenantId() != null) {
            return ctx.getSuperCaseExecution().getTenantId();
        }
        return tenant();
    }

    @Override
    public String provideTenantIdForCaseInstance(TenantIdProviderCaseInstanceContext ctx) {
        if (ctx.getSuperExecution() != null && ctx.getSuperExecution().getTenantId() != null) {
            return ctx.getSuperExecution().getTenantId();
        }
        if (ctx.getSuperCaseExecution() != null && ctx.getSuperCaseExecution().getTenantId() != null) {
            return ctx.getSuperCaseExecution().getTenantId();
        }
        return tenant();
    }

    @Override
    public String provideTenantIdForHistoricDecisionInstance(TenantIdProviderHistoricDecisionInstanceContext ctx) {
        if (ctx.getExecution() != null && ctx.getExecution().getTenantId() != null) {
            return ctx.getExecution().getTenantId();
        }
        if (ctx.getCaseExecution() != null && ctx.getCaseExecution().getTenantId() != null) {
            return ctx.getCaseExecution().getTenantId();
        }
        return tenant();
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SangamTenantIdProvider.class);

    private static String tenant() {
        SecurityIdentity id = TenantContext.current().orElse(null);
        if (id == null) {
            LOG.warn("instance of a shared definition started without an identity: no tenant assigned (invisible, fail closed)");
            return null;
        }
        return id.tenantId();
    }
}
