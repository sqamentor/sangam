// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.history.HistoricCaseInstance;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.history.HistoricVariableInstance;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.LinkPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Entity links (FC-31, FC-32, FC-59). PARENT / CHILD / ROOT links are derived from Operaton's own hierarchy columns
 * ({@code superProcessInstanceId}, {@code rootProcessInstanceId}, {@code superCaseInstanceId}) through the history
 * service, so they stay visible after the instances end. REACTIVATED_FROM is derived from the case variable written by
 * case reactivation. Explicit REFERENCE / SIBLING links are persisted in the product table {@code INSTANCE_LINK}
 * (identity id = the REST link id; {@code hierarchy_type} holds the optional {@code referenceType}).
 */
public class OperatonLinkAdapter implements LinkPort {

    private static final int CHILD_LIMIT = 500;
    private static final Set<String> EXPLICIT_TYPES = Set.of("REFERENCE", "SIBLING");
    private static final Set<String> SCOPES = Set.of("bpmn", "cmmn", "task");

    private final RuntimeService runtime;
    private final HistoryService history;
    private final RestProperties props;
    private static final String LINK_COLS = "id, tenant_id, link_type, hierarchy_type, source_type, source_id,"
            + " target_type, target_id, created_by, created_at";

    private final TxSupport tx;

    public OperatonLinkAdapter(RuntimeService runtime, HistoryService history, RestProperties props, TxSupport tx) {
        this.tx = tx;
        this.runtime = runtime;
        this.history = history;
        this.props = props;
    }

    @Override
    public List<EntityLink> links(RequestContext ctx, String scopeType, String scopeId, String linkType) {
        String scope = normalizeScope(scopeType);
        if (scopeId == null || scopeId.isBlank()) {
            throw ApiProblem.badRequest("SCOPE_ID_REQUIRED", "scopeId is required");
        }
        List<EntityLink> out = new ArrayList<>();
        switch (scope) {
            case "bpmn" -> deriveForProcess(ctx, scopeId, out);
            case "cmmn" -> deriveForCase(ctx, scopeId, out);
            default -> {
                // tasks have no engine hierarchy; explicit links only
            }
        }
        String t = tenant(ctx);
        List<EntityLink> stored = tx.withConnection(c -> Jdbc.query(c, "SELECT " + LINK_COLS + " FROM INSTANCE_LINK"
                + " WHERE tenant_id=? AND ((source_type=? AND source_id=?) OR (target_type=? AND target_id=?))",
                OperatonLinkAdapter::row, t, scope, scopeId, scope, scopeId));
        for (EntityLink l : stored) {
            boolean source = l.sourceScopeType().equals(scope) && l.sourceId().equals(scopeId);
            boolean target = l.targetScopeType().equals(scope) && l.targetId().equals(scopeId);
            if (source) {
                out.add(l);
            } else if (target) {
                // seen from the other end: same link, reversed direction
                out.add(new EntityLink(l.id(), l.tenantId(), l.linkType(), l.targetScopeType(), l.targetId(),
                        l.sourceScopeType(), l.sourceId(), l.referenceType(), l.createdAt(), l.createdBy()));
            }
        }
        if (linkType != null) {
            String lt = linkType.toUpperCase(Locale.ROOT);
            out.removeIf(l -> !l.linkType().equals(lt));
        }
        return out;
    }

    private void deriveForProcess(RequestContext ctx, String id, List<EntityLink> out) {
        HistoricProcessInstance h = OperatonSupport.call(() ->
                history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult());
        if (h == null) {
            throw ApiProblem.notFound("process instance", id);
        }
        OperatonSupport.checkTenant(ctx, h.getTenantId(), "process instance", id);
        String t = tenant(ctx);
        Instant at = OperatonSupport.instant(h.getStartTime());
        if (h.getSuperProcessInstanceId() != null) {
            out.add(derived(t, "PARENT", "bpmn", id, "bpmn", h.getSuperProcessInstanceId(), at));
        }
        if (h.getSuperCaseInstanceId() != null) {
            out.add(derived(t, "PARENT", "bpmn", id, "cmmn", h.getSuperCaseInstanceId(), at));
        }
        if (h.getRootProcessInstanceId() != null && !h.getRootProcessInstanceId().equals(id)) {
            out.add(derived(t, "ROOT", "bpmn", id, "bpmn", h.getRootProcessInstanceId(), at));
        }
        for (HistoricProcessInstance c : OperatonSupport.call(() -> history.createHistoricProcessInstanceQuery()
                .superProcessInstanceId(id).listPage(0, CHILD_LIMIT))) {
            out.add(derived(t, "CHILD", "bpmn", id, "bpmn", c.getId(), OperatonSupport.instant(c.getStartTime())));
        }
        for (HistoricCaseInstance c : OperatonSupport.call(() -> history.createHistoricCaseInstanceQuery()
                .superProcessInstanceId(id).listPage(0, CHILD_LIMIT))) {
            out.add(derived(t, "CHILD", "bpmn", id, "cmmn", c.getId(), OperatonSupport.instant(c.getCreateTime())));
        }
    }

    private void deriveForCase(RequestContext ctx, String id, List<EntityLink> out) {
        HistoricCaseInstance h = OperatonSupport.call(() ->
                history.createHistoricCaseInstanceQuery().caseInstanceId(id).singleResult());
        if (h == null) {
            throw ApiProblem.notFound("case instance", id);
        }
        OperatonSupport.checkTenant(ctx, h.getTenantId(), "case instance", id);
        String t = tenant(ctx);
        Instant at = OperatonSupport.instant(h.getCreateTime());
        if (h.getSuperCaseInstanceId() != null) {
            out.add(derived(t, "PARENT", "cmmn", id, "cmmn", h.getSuperCaseInstanceId(), at));
        }
        if (h.getSuperProcessInstanceId() != null) {
            out.add(derived(t, "PARENT", "cmmn", id, "bpmn", h.getSuperProcessInstanceId(), at));
        }
        for (HistoricCaseInstance c : OperatonSupport.call(() -> history.createHistoricCaseInstanceQuery()
                .superCaseInstanceId(id).listPage(0, CHILD_LIMIT))) {
            out.add(derived(t, "CHILD", "cmmn", id, "cmmn", c.getId(), OperatonSupport.instant(c.getCreateTime())));
        }
        for (HistoricProcessInstance c : OperatonSupport.call(() -> history.createHistoricProcessInstanceQuery()
                .superCaseInstanceId(id).listPage(0, CHILD_LIMIT))) {
            out.add(derived(t, "CHILD", "cmmn", id, "bpmn", c.getId(), OperatonSupport.instant(c.getStartTime())));
        }
        for (HistoricVariableInstance v : OperatonSupport.call(() -> history.createHistoricVariableInstanceQuery()
                .caseInstanceId(id).variableName(OperatonCaseAdapter.REACTIVATED_FROM).list())) {
            if (v.getValue() != null) {
                out.add(derived(t, "REACTIVATED_FROM", "cmmn", id, "cmmn", String.valueOf(v.getValue()), at));
            }
        }
    }

    @Override
    public EntityLink create(RequestContext ctx, CreateLinkRequest r) {
        if (r == null || r.linkType() == null || r.sourceId() == null || r.targetId() == null) {
            throw ApiProblem.badRequest("LINK_INCOMPLETE", "linkType, sourceId and targetId are required");
        }
        String type = r.linkType().toUpperCase(Locale.ROOT);
        if (!EXPLICIT_TYPES.contains(type)) {
            throw ApiProblem.unprocessable("LINK_TYPE_DERIVED",
                    "only REFERENCE and SIBLING links can be created; hierarchy links come from the engine");
        }
        String sourceScope = normalizeScope(r.sourceScopeType());
        String targetScope = normalizeScope(r.targetScopeType());
        requireVisible(ctx, sourceScope, r.sourceId());
        requireVisible(ctx, targetScope, r.targetId());
        String t = tenant(ctx);
        return tx.withConnection(c -> {
            var existing = Jdbc.one(c, "SELECT " + LINK_COLS + " FROM INSTANCE_LINK WHERE tenant_id=? AND link_type=?"
                    + " AND source_type=? AND source_id=? AND target_type=? AND target_id=?", OperatonLinkAdapter::row,
                    t, type, sourceScope, r.sourceId(), targetScope, r.targetId());
            if (existing.isPresent()) {
                return existing.get();
            }
            Instant now = Jdbc.dbNow(c, tx.dialect());
            long id = Jdbc.insertReturningId(c, tx.dialect(), "INSERT INTO INSTANCE_LINK(tenant_id, link_type,"
                    + " hierarchy_type, source_type, source_id, target_type, target_id, created_by, created_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)", "id", t, type, r.referenceType(), sourceScope, r.sourceId(),
                    targetScope, r.targetId(), ctx.principalId(), now);
            return new EntityLink(Long.toString(id), t, type, sourceScope, r.sourceId(), targetScope, r.targetId(),
                    r.referenceType(), now, ctx.principalId());
        });
    }

    @Override
    public void delete(RequestContext ctx, String linkId, String reason) {
        if (linkId != null && linkId.startsWith("derived:")) {
            throw ApiProblem.unprocessable("LINK_TYPE_DERIVED", "hierarchy links cannot be deleted");
        }
        long id;
        try {
            id = Long.parseLong(linkId);
        } catch (NumberFormatException | NullPointerException e) {
            throw ApiProblem.notFound("link", linkId);
        }
        final long linkKey = id;
        int n = tx.withConnection(c -> Jdbc.update(c, "DELETE FROM INSTANCE_LINK WHERE id=? AND tenant_id=?"
                + " AND link_type IN ('REFERENCE','SIBLING')", linkKey, tenant(ctx)));
        if (n == 0) {
            throw ApiProblem.notFound("link", linkId);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireVisible(RequestContext ctx, String scope, String id) {
        switch (scope) {
            case "bpmn" -> {
                HistoricProcessInstance h = OperatonSupport.call(() ->
                        history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult());
                if (h == null) {
                    throw ApiProblem.notFound("process instance", id);
                }
                OperatonSupport.checkTenant(ctx, h.getTenantId(), "process instance", id);
            }
            case "cmmn" -> {
                HistoricCaseInstance h = OperatonSupport.call(() ->
                        history.createHistoricCaseInstanceQuery().caseInstanceId(id).singleResult());
                if (h == null) {
                    throw ApiProblem.notFound("case instance", id);
                }
                OperatonSupport.checkTenant(ctx, h.getTenantId(), "case instance", id);
            }
            default -> {
                long n = OperatonSupport.call(() -> history.createHistoricTaskInstanceQuery().taskId(id).count());
                if (n == 0) {
                    throw ApiProblem.notFound("task", id);
                }
            }
        }
    }

    private static String normalizeScope(String scopeType) {
        String s = scopeType == null ? "bpmn" : scopeType.toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(s)) {
            throw ApiProblem.badRequest("INVALID_SCOPE_TYPE", "scopeType must be bpmn, cmmn or task");
        }
        return s;
    }

    private static EntityLink derived(String tenant, String type, String sourceScope, String sourceId,
                                      String targetScope, String targetId, Instant at) {
        return new EntityLink("derived:" + type + ":" + sourceId + ":" + targetId, tenant, type, sourceScope, sourceId,
                targetScope, targetId, null, at, "engine");
    }

    private static EntityLink row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EntityLink(Long.toString(rs.getLong("id")), rs.getString("tenant_id"), rs.getString("link_type"),
                rs.getString("source_type"), rs.getString("source_id"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("hierarchy_type"), Jdbc.instant(rs, "created_at"),
                rs.getString("created_by"));
    }

    private static String tenant(RequestContext ctx) {
        return ctx.tenantId() == null ? "" : ctx.tenantId();
    }

    /** The runtime service is kept for subclasses that add runtime-only link sources. */
    protected RuntimeService runtime() {
        return runtime;
    }
}
