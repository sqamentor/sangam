// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.links;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reference links and entity links (FC-31 Flowable {@code referenceId/referenceType}; FC-32 Flowable
 * {@code EntityLinkService}; table {@code INSTANCE_LINK}).
 *
 * <p>Link types used by Sangam: {@code child} (parent → child, hierarchy {@code root|parent|grand-parent}),
 * {@code reference} (sibling/reference link set by the product), {@code reactivation} (FC-59), {@code cut-over}
 * (FC-19 run-off), {@code event-delivery} (event → instance), {@code dynamic-injection} (FC-15). Scope types:
 * {@code process}, {@code case}, {@code task}, {@code event}, {@code document}.
 *
 * <p>Writes run in the caller's transaction ({@code inEngineTransaction}, Q1). Reads must be wrapped by the VI.4
 * predicate of WS-SECURITY before they reach a user; this service returns raw rows for that purpose.
 */
public class InstanceLinkService {

    public record Link(long id, String tenantId, String linkType, String hierarchyType, String sourceType, String sourceId,
                       String targetType, String targetId, String rootType, String rootId, String createdBy, Instant createdAt) {
    }

    private final Dialect dialect;
    private final Transactions tx;

    public InstanceLinkService(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    /** Idempotent: an identical link is not inserted twice. Returns true when a row was inserted. */
    public boolean link(Connection c, String tenant, String linkType, String hierarchyType, String sourceType, String sourceId,
                        String targetType, String targetId, String createdBy) {
        return link(c, tenant, linkType, hierarchyType, sourceType, sourceId, targetType, targetId, null, null, createdBy);
    }

    public boolean link(Connection c, String tenant, String linkType, String hierarchyType, String sourceType, String sourceId,
                        String targetType, String targetId, String rootType, String rootId, String createdBy) {
        return Jdbc.insertIfAbsent(c, dialect, "INSERT INTO INSTANCE_LINK(tenant_id, link_type, hierarchy_type, source_type, source_id,"
                        + " target_type, target_id, root_type, root_id, created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                tenant == null ? "" : tenant, linkType, hierarchyType, sourceType, sourceId, targetType, targetId, rootType, rootId, createdBy);
    }

    /** Product API: a reference/sibling link between two instances (FC-31), in its own transaction. */
    public boolean addReference(String tenant, String sourceType, String sourceId, String targetType, String targetId, String user) {
        return tx.inTransaction(c -> link(c, tenant, "reference", null, sourceType, sourceId, targetType, targetId, user));
    }

    /** Product API: a link of an explicit type, in its own transaction. */
    public boolean addReferenceTyped(String tenant, String sourceType, String sourceId, String targetType, String targetId,
                                     String linkType, String user) {
        return tx.inTransaction(c -> link(c, tenant, linkType, null, sourceType, sourceId, targetType, targetId, user));
    }

    public int unlink(Connection c, String tenant, String linkType, String sourceType, String sourceId, String targetType, String targetId) {
        return Jdbc.update(c, "DELETE FROM INSTANCE_LINK WHERE tenant_id = ? AND link_type = ? AND source_type = ? AND source_id = ?"
                + " AND target_type = ? AND target_id = ?", tenant == null ? "" : tenant, linkType, sourceType, sourceId, targetType, targetId);
    }

    public List<Link> outgoing(String tenant, String sourceType, String sourceId) {
        return tx.inTransaction(c -> query(c, "tenant_id = ? AND source_type = ? AND source_id = ?", tenant == null ? "" : tenant, sourceType, sourceId));
    }

    public List<Link> incoming(String tenant, String targetType, String targetId) {
        return tx.inTransaction(c -> query(c, "tenant_id = ? AND target_type = ? AND target_id = ?", tenant == null ? "" : tenant, targetType, targetId));
    }

    /** Every link under one root (whole hierarchy of a root process or case, FC-32). */
    public List<Link> byRoot(String tenant, String rootType, String rootId) {
        return tx.inTransaction(c -> query(c, "tenant_id = ? AND root_type = ? AND root_id = ?", tenant == null ? "" : tenant, rootType, rootId));
    }

    /** Transitive closure of {@code child} links below a node (bounded depth, cycle-safe). */
    public Set<String> descendants(String tenant, String scopeType, String id, int maxDepth) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> frontier = new ArrayList<>();
        frontier.add(new String[]{scopeType, id});
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<String[]> next = new ArrayList<>();
            for (String[] node : frontier) {
                for (Link l : outgoing(tenant, node[0], node[1])) {
                    if ("child".equals(l.linkType()) && seen.add(l.targetType() + ":" + l.targetId())) {
                        next.add(new String[]{l.targetType(), l.targetId()});
                    }
                }
            }
            frontier = next;
        }
        return seen;
    }

    private List<Link> query(Connection c, String where, Object... params) {
        return Jdbc.query(c, "SELECT id, tenant_id, link_type, hierarchy_type, source_type, source_id, target_type, target_id, root_type,"
                        + " root_id, created_by, created_at FROM INSTANCE_LINK WHERE " + where + " ORDER BY id",
                rs -> new Link(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                        Jdbc.instant(rs, "created_at")), params);
    }
}
