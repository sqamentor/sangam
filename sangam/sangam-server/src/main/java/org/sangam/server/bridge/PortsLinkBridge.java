// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.links.InstanceLinkService;
import org.sangam.ports.support.EngineTx;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.LinkPort;
import org.sangam.rest.port.RequestContext;

/**
 * {@link LinkPort} on sangam-flowable-ports' {@code INSTANCE_LINK} (FC-31 references, FC-32 hierarchy across BPMN,
 * CMMN and tasks, filled by the ports history handler; FC-59 reactivation links).
 *
 * <p>Mapping: REST scope types {@code bpmn | cmmn | task} are the ports' {@code process | case | task}. Links are
 * reported from the queried scope's side: an outgoing {@code child} link is {@code CHILD}, an incoming one is
 * {@code PARENT}; other link types are upper-cased ({@code reference} → {@code REFERENCE}).
 */
public final class PortsLinkBridge implements LinkPort {

    private static final Set<String> CREATABLE = Set.of("REFERENCE", "SIBLING", "REACTIVATED_FROM");

    private final InstanceLinkService links;
    private final ProcessEngine engine;
    private final AuditPort audit;

    public PortsLinkBridge(InstanceLinkService links, ProcessEngine engine, AuditPort audit) {
        this.links = links;
        this.engine = engine;
        this.audit = audit;
    }

    @Override
    public List<EntityLink> links(RequestContext ctx, String scopeType, String scopeId, String linkType) {
        String type = toPorts(scopeType);
        List<EntityLink> out = new ArrayList<>();
        for (InstanceLinkService.Link l : PortsErrors.call(() -> links.outgoing(ctx.tenantId(), type, scopeId))) {
            add(out, l, "child".equals(l.linkType()) ? "CHILD" : l.linkType().toUpperCase(Locale.ROOT), linkType);
        }
        for (InstanceLinkService.Link l : PortsErrors.call(() -> links.incoming(ctx.tenantId(), type, scopeId))) {
            add(out, l, "child".equals(l.linkType()) ? "PARENT" : l.linkType().toUpperCase(Locale.ROOT), linkType);
        }
        return out;
    }

    @Override
    public EntityLink create(RequestContext ctx, CreateLinkRequest r) {
        String lt = r.linkType().toUpperCase(Locale.ROOT);
        if (!CREATABLE.contains(lt)) {
            throw ApiProblem.badRequest("VALIDATION_FAILED",
                    "Only REFERENCE, SIBLING and REACTIVATED_FROM links can be created; hierarchy links are derived");
        }
        String src = toPorts(r.sourceScopeType());
        String tgt = toPorts(r.targetScopeType());
        String portsType = lt.toLowerCase(Locale.ROOT).replace('_', '-');
        PortsErrors.call(() -> links.addReferenceTyped(ctx.tenantId(), src, r.sourceId(), tgt, r.targetId(), portsType,
                ctx.principalId()));
        InstanceLinkService.Link created = PortsErrors.call(() -> links.outgoing(ctx.tenantId(), src, r.sourceId())).stream()
                .filter(l -> l.linkType().equals(portsType) && l.targetType().equals(tgt) && l.targetId().equals(r.targetId()))
                .findFirst().orElseThrow(() -> new ApiProblem(500, "LINK_NOT_STORED", "the link was not stored"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("link_id", created.id());
        p.put("link_type", lt);
        p.put("source", src + ":" + r.sourceId());
        p.put("target", tgt + ":" + r.targetId());
        audit.record(ctx, r.sourceId(), "INSTANCE_LINKED", p);
        return map(created, lt);
    }

    @Override
    public void delete(RequestContext ctx, String linkId, String reason) {
        long id;
        try {
            id = Long.parseLong(linkId);
        } catch (NumberFormatException e) {
            throw ApiProblem.notFound("link", linkId);
        }
        record Row(String linkType, String sourceType, String sourceId, String targetType, String targetId) {
        }
        int n = PortsErrors.call(() -> EngineTx.transactions(engine).inTransaction(c -> {
            Row row = Jdbc.one(c, "SELECT link_type, source_type, source_id, target_type, target_id FROM INSTANCE_LINK"
                            + " WHERE id = ? AND tenant_id = ?",
                    rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                    id, ctx.tenantId()).orElseThrow(() -> ApiProblem.notFound("link", linkId));
            if ("child".equals(row.linkType())) {
                throw ApiProblem.conflict("LINK_DERIVED", "Hierarchy links are derived from the engine and cannot be deleted");
            }
            return links.unlink(c, ctx.tenantId(), row.linkType(), row.sourceType(), row.sourceId(), row.targetType(),
                    row.targetId());
        }));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("link_id", linkId);
        p.put("reason", reason);
        p.put("deleted", n);
        audit.record(ctx, "sec:" + ctx.tenantId(), "INSTANCE_UNLINKED", p);
    }

    private static void add(List<EntityLink> out, InstanceLinkService.Link l, String type, String filter) {
        if (filter == null || filter.equalsIgnoreCase(type)) {
            out.add(map(l, type));
        }
    }

    private static EntityLink map(InstanceLinkService.Link l, String type) {
        return new EntityLink(Long.toString(l.id()), l.tenantId(), type, toRest(l.sourceType()), l.sourceId(),
                toRest(l.targetType()), l.targetId(), l.hierarchyType(), l.createdAt(), l.createdBy());
    }

    static String toPorts(String restScope) {
        if (restScope == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "scopeType is required: bpmn | cmmn | task");
        }
        return switch (restScope.toLowerCase(Locale.ROOT)) {
            case "bpmn", "process" -> "process";
            case "cmmn", "case" -> "case";
            case "task" -> "task";
            default -> throw ApiProblem.badRequest("VALIDATION_FAILED", "scopeType must be bpmn | cmmn | task");
        };
    }

    static String toRest(String portsScope) {
        return switch (portsScope == null ? "" : portsScope) {
            case "process" -> "bpmn";
            case "case" -> "cmmn";
            default -> portsScope;
        };
    }
}
