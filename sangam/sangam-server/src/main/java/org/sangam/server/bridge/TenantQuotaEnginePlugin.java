// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cmd.StartProcessInstanceAtActivitiesCmd;
import org.operaton.bpm.engine.impl.cmd.StartProcessInstanceCmd;
import org.operaton.bpm.engine.impl.cmmn.cmd.CreateCaseInstanceCmd;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.CachedDbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.DbEntityState;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.persistence.entity.VariableInstanceEntity;
import org.sangam.core.quota.QuotaLimit;
import org.sangam.core.quota.TenantQuotas;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.tenant.TenantContext;

/**
 * SL-43 engine entry point: per-tenant quotas on every engine command, whatever the caller (sangam-rest,
 * engine-rest, Java API, a delegate). Process and case starts ({@code StartProcessInstanceCmd},
 * {@code StartProcessInstanceAtActivitiesCmd}, {@code CreateCaseInstanceCmd}) take one unit of
 * {@link QuotaLimit#STARTS_PER_SECOND} before they run; at the end of the outermost command, every new or changed
 * variable value in the command's entity cache is checked against {@link QuotaLimit#VARIABLE_BYTES} before the flush,
 * so an oversized value rolls the whole command back. The tenant is the bound identity ({@link TenantContext}); calls
 * without one (engine bootstrap, platform jobs) are not counted. Message-triggered starts from the inbox are not
 * counted here: the inbox has its own back-pressure (VI.2.4) and a refused correlation would only be retried.
 */
public final class TenantQuotaEnginePlugin implements ProcessEnginePlugin {

    private final Supplier<TenantQuotas> quotas;

    public TenantQuotaEnginePlugin(Supplier<TenantQuotas> quotas) {
        this.quotas = quotas;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        List<CommandInterceptor> post = cfg.getCustomPostCommandInterceptorsTxRequired() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomPostCommandInterceptorsTxRequired());
        post.add(new QuotaInterceptor(quotas));
        cfg.setCustomPostCommandInterceptorsTxRequired(post);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl cfg) {
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine engine) {
    }

    /** Runs inside the command context (post interceptor), before its flush. */
    static final class QuotaInterceptor extends CommandInterceptor {

        private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
        private final Supplier<TenantQuotas> quotas;

        QuotaInterceptor(Supplier<TenantQuotas> quotas) {
            this.quotas = quotas;
        }

        @Override
        public <T> T execute(Command<T> command) {
            String tenant = tenant();
            TenantQuotas q = tenant == null ? TenantQuotas.NONE : quotas.get();
            if (tenant != null && isStart(command)) {
                q.acquire(tenant, QuotaLimit.STARTS_PER_SECOND, 1);
            }
            int[] depth = DEPTH.get();
            depth[0]++;
            try {
                T result = next.execute(command);
                if (tenant != null && depth[0] == 1) {
                    checkVariables(q, tenant);
                }
                return result;
            } finally {
                depth[0]--;
            }
        }

        static boolean isStart(Command<?> command) {
            return command instanceof StartProcessInstanceCmd || command instanceof StartProcessInstanceAtActivitiesCmd
                    || command instanceof CreateCaseInstanceCmd;
        }

        private static void checkVariables(TenantQuotas q, String tenant) {
            long max = q.limit(tenant, QuotaLimit.VARIABLE_BYTES);
            if (max < 0) {
                return;
            }
            CommandContext ctx = Context.getCommandContext();
            if (ctx == null) {
                return;
            }
            for (CachedDbEntity cached : ctx.getDbEntityManager().getDbEntityCache().getCachedEntities()) {
                if (!(cached.getEntity() instanceof VariableInstanceEntity v)) {
                    continue;
                }
                DbEntityState s = cached.getEntityState();
                boolean written = s == DbEntityState.TRANSIENT || (s == DbEntityState.PERSISTENT && cached.isDirty());
                if (written) {
                    long size = size(v);
                    if (!TenantQuotas.within(max, size)) {
                        q.checkSize(tenant, QuotaLimit.VARIABLE_BYTES, size);   // throws, audited
                    }
                }
            }
        }

        static long size(VariableInstanceEntity v) {
            long n = 0;
            if (v.getTextValue() != null) {
                n += v.getTextValue().getBytes(StandardCharsets.UTF_8).length;
            }
            if (v.getTextValue2() != null) {
                n += v.getTextValue2().getBytes(StandardCharsets.UTF_8).length;
            }
            if (v.getByteArrayValueId() != null) {
                byte[] b = v.getByteArrayValue();
                n += b == null ? 0 : b.length;
            }
            return n;
        }

        private static String tenant() {
            SecurityIdentity id = TenantContext.current().orElse(null);
            if (id == null || id.tenantId() == null || id.tenantId().isBlank() || "*".equals(id.tenantId())) {
                return null;
            }
            return id.tenantId();
        }
    }
}
