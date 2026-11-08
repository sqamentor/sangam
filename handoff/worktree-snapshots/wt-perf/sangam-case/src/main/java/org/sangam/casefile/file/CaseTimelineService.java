// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.file;

import org.sangam.casefile.common.CaseEvents;
import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One timeline per case file (N04): the file's own journal ({@code CF_EVENT}, including the journals of cases merged
 * into it) merged with the history of every process and CMMN case instance working on it (read model, FLOWABLE-INTO-OPERATON FC-25, through
 * {@link CaseFilePorts.ProcessHistorySource}), newest first.
 */
public class CaseTimelineService {

    /** source: CASEFILE (journal) or ENGINE (activity history). */
    public record Entry(Instant at, String source, String type, String summary, String actor, String scopeId,
                        Map<String, Object> detail) {
    }

    private final CaseEvents events;
    private final CaseContentsService contents;
    private final CaseFilePorts.ProcessHistorySource history;

    public CaseTimelineService(CaseEvents events, CaseContentsService contents, CaseFilePorts.ProcessHistorySource history) {
        this.events = events;
        this.contents = contents;
        this.history = history == null ? CaseFilePorts.ProcessHistorySource.NONE : history;
    }

    public List<Entry> timeline(Connection c, String tenant, String caseId, int limit, boolean includeEngineHistory) {
        int n = Math.max(1, Math.min(limit, 2000));
        String t = Db.tenant(tenant);
        List<String> scopes = new ArrayList<>();
        scopes.add(caseId);
        scopes.addAll(Jdbc.query(c, "SELECT case_id FROM CF_CASE WHERE tenant_id=? AND merged_into=?", rs -> rs.getString(1), t, caseId));
        List<Entry> out = new ArrayList<>();
        for (String scope : scopes) {
            for (CaseEvents.Event e : events.forScope(c, t, CaseEvents.CASE, scope, n)) {
                out.add(new Entry(e.occurredAt(), e.source(), e.eventType(), e.summary(), e.actor(), scope, e.payload()));
            }
        }
        if (includeEngineHistory) {
            List<String> ids = contents.instanceIds(c, t, caseId);
            if (!ids.isEmpty()) {
                for (CaseFilePorts.HistoryEntry h : history.history(t, ids, n)) {
                    Instant at = h.endedAt() != null ? h.endedAt() : h.startedAt();
                    String type = h.endedAt() != null ? "ACTIVITY_COMPLETED" : "ACTIVITY_STARTED";
                    out.add(new Entry(at, "ENGINE", type, h.activityName() == null ? h.activityId() : h.activityName(), h.assignee(),
                            h.instanceId(), Db.kv("activityId", h.activityId(), "activityType", h.activityType(),
                            "startedAt", h.startedAt() == null ? null : h.startedAt().toString())));
                }
            }
        }
        out.sort(Comparator.comparing(Entry::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }
}
