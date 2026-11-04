// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.admin;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthEffectiveMaintainer;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;
import org.sangam.security.support.Sql;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * Worker principals bound to tenant and topics (R26, K14, VI.4 rule 7, IJ-01). A worker name supplied by the client is
 * not enough: the authenticated service principal must hold {@code WORK} on {@code WORKER_TOPIC/<topic>}. The binding is
 * a grant source {@code worker:<principal>:<topic>}; workers see only the variables granted to their topic
 * (checked by the worker API through the same predicate).
 */
public final class WorkerBindingService {

    private final SecurityDialect dialect;
    private final AuthorizationService authz;
    private final AuthEffectiveMaintainer maintainer;
    private final SecurityAuditService audit;

    public WorkerBindingService(SecurityDialect dialect, AuthorizationService authz, AuthEffectiveMaintainer maintainer,
                                SecurityAuditService audit) {
        this.dialect = dialect;
        this.authz = authz;
        this.maintainer = maintainer;
        this.audit = audit;
    }

    public void bind(Connection c, SecurityIdentity admin, String workerPersonId, String topic) {
        authz.require(c, admin, ResourceRegistry.WORKER_TOPIC, Permission.CHANGE_GRANT, topic);
        String t = admin.tenantId();
        Sql.insertIfAbsent(c, dialect, "INSERT INTO SEC_WORKER_BINDING(tenant_id, worker_person_id, topic, created_by, created_at)"
                + " VALUES (?,?,?,?,?)", t, workerPersonId, topic, admin.personId(), Sql.dbNow(c, dialect));
        long v = maintainer.replaceSource(c, t, source(workerPersonId, topic), List.of(new AuthEffectiveMaintainer.GrantRow(
                SecurityIdentity.USER + workerPersonId, ResourceRegistry.WORKER_TOPIC, topic, false,
                Permission.mask(Permission.WORK, Permission.READ_VARIABLE), null)));
        audit.security(c, admin, SecurityEventType.WORKER_BOUND, Map.of("worker", workerPersonId, "topic", topic, "new_policy_version", v));
    }

    public void unbind(Connection c, SecurityIdentity admin, String workerPersonId, String topic) {
        authz.require(c, admin, ResourceRegistry.WORKER_TOPIC, Permission.CHANGE_GRANT, topic);
        String t = admin.tenantId();
        Jdbc.update(c, "DELETE FROM SEC_WORKER_BINDING WHERE tenant_id=? AND worker_person_id=? AND topic=?", t, workerPersonId, topic);
        long v = maintainer.removeSource(c, t, source(workerPersonId, topic));
        audit.security(c, admin, SecurityEventType.WORKER_UNBOUND, Map.of("worker", workerPersonId, "topic", topic, "new_policy_version", v));
    }

    /** R26: rejects a fetch, complete or fail call from a principal not bound to the topic. */
    public void requireTopic(Connection c, SecurityIdentity worker, String topic) {
        authz.require(c, worker, ResourceRegistry.WORKER_TOPIC, Permission.WORK, topic);
    }

    public boolean mayWork(Connection c, SecurityIdentity worker, String topic) {
        return authz.allow(c, worker, ResourceRegistry.WORKER_TOPIC, Permission.WORK, topic, Map.of());
    }

    public List<String> topicsOf(Connection c, String tenant, String workerPersonId) {
        return Jdbc.query(c, "SELECT topic FROM SEC_WORKER_BINDING WHERE tenant_id=? AND worker_person_id=? ORDER BY topic",
                rs -> rs.getString(1), tenant, workerPersonId);
    }

    private static String source(String worker, String topic) {
        return "worker:" + worker + ":" + topic;
    }
}
