// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.lock;

import java.util.HashMap;
import java.util.Map;

import org.operaton.bpm.engine.impl.db.entitymanager.DbEntityManager;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.CachedDbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.DbEntityState;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContextListener;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TaskEntity;

/**
 * <b>L2 backstop at flush (A-11).</b> Commands that do not go through the Sangam adapter (Operaton REST, delegates
 * calling engine services, internal engine commands) did not lock their roots first. Operaton calls
 * {@code onCommandContextClose} <b>before</b> it flushes the entity cache (read from the 2.2.0 bytecode of
 * {@code CommandContext.tryCloseInternal}: fireCommandContextClose, then flushSessions, then commit). This listener
 * collects the roots of every changed execution or task in the cache and locks them (ascending) right before the
 * flush, so two such commands on one root serialize their flush and commit; the slower one then fails its
 * optimistic check against committed state and is retried, instead of interleaving.
 *
 * <p>Brand-new roots (a transient process instance whose id is its own root id) are skipped: nobody else can see them.
 * Internal classes used: DbEntityManager cache API (see PATCHES.md, internal API list).
 */
public final class RootLockFlushListener implements CommandContextListener {

    private final RootLock rootLock;
    private final String defaultTenant;

    public RootLockFlushListener(RootLock rootLock, String defaultTenant) {
        this.rootLock = rootLock;
        this.defaultTenant = defaultTenant;
    }

    @Override
    public void onCommandContextClose(CommandContext cc) {
        DbEntityManager em = null;
        for (Session s : cc.getSessions().values()) {
            if (s instanceof DbEntityManager m) {
                em = m;
            }
        }
        if (em == null) {
            return; // the command touched no engine entity
        }
        Map<String, String> roots = new HashMap<>();
        String metricKey = null;
        for (CachedDbEntity cached : em.getDbEntityCache().getCachedEntities()) {
            DbEntityState st = cached.getEntityState();
            boolean changed = st != DbEntityState.PERSISTENT || cached.isDirty();
            if (!changed) {
                continue;
            }
            if (cached.getEntity() instanceof ExecutionEntity e) {
                String root = e.getRootProcessInstanceId();
                if (root == null || (st == DbEntityState.TRANSIENT && root.equals(e.getId()))) {
                    continue;
                }
                roots.putIfAbsent(root, tenant(e.getTenantId()));
                if (metricKey == null) {
                    metricKey = keyOf(e.getProcessDefinitionId());
                }
            } else if (cached.getEntity() instanceof TaskEntity t && t.getProcessInstanceId() != null
                    && st != DbEntityState.TRANSIENT) {
                ExecutionEntity pi = t.getProcessInstance();
                if (pi != null && pi.getRootProcessInstanceId() != null) {
                    roots.putIfAbsent(pi.getRootProcessInstanceId(), tenant(t.getTenantId()));
                }
            }
        }
        rootLock.lock(cc, roots, metricKey);
    }

    @Override
    public void onCommandFailed(CommandContext cc, Throwable t) {
        // nothing to undo: the locks go with the rolled-back transaction
    }

    private String tenant(String t) {
        return t == null ? defaultTenant : t;
    }

    /** Operaton process definition ids are {@code key:version:uuid}. */
    static String keyOf(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        int i = processDefinitionId.indexOf(':');
        return i < 0 ? processDefinitionId : processDefinitionId.substring(0, i);
    }
}
