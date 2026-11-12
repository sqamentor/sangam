// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort.RuleEvidence;
import org.sangam.rest.port.WorkspacePort;

/**
 * The audit extras of a task detail (UI S08/S09/S10), split out of {@link OperatonTaskAdapter} (X2-04 class-size
 * ratchet; behaviour unchanged): the instance and business-key chains are read in one audited read (SG-15: exactly one
 * {@code AUDIT_VIEWED} for the request), then folded into {@code changedSinceLastSeen} and {@code ruleEvidence}.
 */
final class TaskAuditExtras {

    /** The bound of the audit extras per chain: the first 10 pages of {@link #chainEvents}. */
    static final int CHAIN_EVENTS_LIMIT = 10 * Page.PageRequest.MAX_SIZE;

    private final AuditPort audit;

    TaskAuditExtras(AuditPort audit) {
        this.audit = audit;
    }

    /** Events of the instance and business-key chains for the audit extras; one audited read (see the class comment). */
    Map<String, List<AuditPort.AuditEntry>> read(RequestContext ctx, String taskId, String pi, String bk) {
        List<String> chains = new ArrayList<>();
        if (pi != null) {
            chains.add(pi);
        }
        if (bk != null && !bk.equals(pi)) {
            chains.add(bk);
        }
        if (chains.isEmpty()) {
            return Map.of();
        }
        if (audit instanceof org.sangam.rest.adapter.jdbc.ChainViews views) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("view", "task");
            view.put("taskId", taskId);
            try {
                return views.viewChains(ctx, chains, CHAIN_EVENTS_LIMIT, view);
            } catch (ApiProblem e) {
                return Map.of();   // an unreadable audit yields no extras, as before
            }
        }
        Map<String, List<AuditPort.AuditEntry>> out = new LinkedHashMap<>();
        for (String chain : chains) {
            out.put(chain, chainEvents(ctx, chain));   // another AuditPort: one search per chain, as before
        }
        return out;
    }

    /** {@link #changedSinceLastSeen(RequestContext, List)} over the instance chain of {@code events}; none without one. */
    static List<WorkspacePort.FieldChange> changedSinceLastSeen(RequestContext ctx,
                                                                Map<String, List<AuditPort.AuditEntry>> events, String pi) {
        return pi == null ? List.of() : changedSinceLastSeen(ctx, events.getOrDefault(pi, List.of()));
    }

    /**
     * Fields changed since the caller last acted on this case: the material changes recorded by every
     * CASE_RESUBMITTED / CASE_RETURNED event after the caller's last TASK_COMPLETED on this chain (or all of them when
     * the caller has not acted yet). From/to values come from the {@code old_values}/{@code new_values} the resubmit
     * records for material fields.
     */
    static List<WorkspacePort.FieldChange> changedSinceLastSeen(RequestContext ctx, List<AuditPort.AuditEntry> events) {
        int start = 0;
        for (int i = 0; i < events.size(); i++) {
            AuditPort.AuditEntry e = events.get(i);
            if ("TASK_COMPLETED".equals(e.type()) && ctx.principalId().equals(e.actor())) {
                start = i + 1;
            }
        }
        Map<String, WorkspacePort.FieldChange> changes = new LinkedHashMap<>();
        for (AuditPort.AuditEntry e : events.subList(start, events.size())) {
            if (!"CASE_RESUBMITTED".equals(e.type()) && !"CASE_RETURNED".equals(e.type())) {
                continue;
            }
            fieldChanges(e, changes);
        }
        return new ArrayList<>(changes.values());
    }

    /** Folds one CASE_RESUBMITTED / CASE_RETURNED event into the field changes (keeps the first "from", last "to"). */
    static void fieldChanges(AuditPort.AuditEntry e, Map<String, WorkspacePort.FieldChange> into) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        Map<?, ?> oldValues = p.get("old_values") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> newValues = p.get("new_values") instanceof Map<?, ?> m ? m : Map.of();
        for (String f : OperatonTaskAdapter.listOf(p.get("changed_fields"))) {
            WorkspacePort.FieldChange prev = into.get(f);
            into.put(f, new WorkspacePort.FieldChange(f, f, prev != null ? prev.from() : oldValues.get(f),
                    newValues.get(f), e.actor(), e.occurredAt()));
        }
    }

    /** {@link #ruleEvidence(List)} over the business-key chain of {@code events}; none without a business key. */
    static List<RuleEvidence> ruleEvidence(Map<String, List<AuditPort.AuditEntry>> events, String businessKey) {
        return businessKey == null ? List.of() : ruleEvidence(events.getOrDefault(businessKey, List.of()));
    }

    /** Routing / authority decisions recorded on the business-key chain (X.2.3.2 decision_ref). */
    static List<RuleEvidence> ruleEvidence(List<AuditPort.AuditEntry> events) {
        List<RuleEvidence> out = new ArrayList<>();
        for (AuditPort.AuditEntry e : events) {
            if (!"ROUTING_DECISION".equals(e.type()) && !"AUTHORITY_DECISION".equals(e.type())) {
                continue;
            }
            Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
            Object key = p.get("decision_key") != null ? p.get("decision_key") : p.get("decisionKey");
            Object version = p.get("decision_version") != null ? p.get("decision_version") : p.get("version");
            Object result = p.get("output_digest") != null ? p.get("output_digest") : p.get("outputDigest");
            out.add(new RuleEvidence(key == null ? e.type() : String.valueOf(key),
                    version == null ? null : String.valueOf(version), result == null ? null : String.valueOf(result)));
        }
        return out;
    }

    /** All class-B events of one chain, oldest first (bounded at 10 pages). An unreadable audit yields no extras. */
    List<AuditPort.AuditEntry> chainEvents(RequestContext ctx, String chainId) {
        List<AuditPort.AuditEntry> out = new ArrayList<>();
        try {
            for (int pageNo = 0; pageNo < 10; pageNo++) {
                Page<AuditPort.AuditEntry> p = audit.search(ctx, new AuditPort.AuditFilter(chainId, null, null, null,
                        null), new Page.PageRequest(pageNo, Page.PageRequest.MAX_SIZE));
                out.addAll(p.items());
                if (p.items().size() < Page.PageRequest.MAX_SIZE) {
                    break;
                }
            }
        } catch (ApiProblem e) {
            return List.of();
        }
        out.sort(java.util.Comparator.comparing(AuditPort.AuditEntry::occurredAt,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        return out;
    }
}
