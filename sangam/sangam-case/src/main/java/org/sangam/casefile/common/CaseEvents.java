// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.common;

import org.sangam.casefile.support.Db;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The module's event journal: every state change of a case, meeting, committee, reference, action item or deadline is
 * one {@code CF_EVENT} row (the case timeline's own half) and, when an outbox is configured, one outbox event on
 * destination {@value #DESTINATION} in the same transaction (VI.2, exactly once to subscribers: notifications,
 * dashboards, the audit chain). Event ids are UUIDv7, so id order is time order.
 */
public class CaseEvents {

    public static final String DESTINATION = "case-events";

    public static final String CASE = "CASE";
    public static final String MEETING = "MEETING";
    public static final String COMMITTEE = "COMMITTEE";
    public static final String REFERENCE = "REFERENCE";
    public static final String ACTION = "ACTION";
    public static final String DEADLINE = "DEADLINE";

    public record Event(String eventId, String scopeType, String scopeId, String eventType, Instant occurredAt,
                        String actor, String summary, Map<String, Object> payload, String source) {
    }

    private final Dialect dialect;
    private final Outbox outbox;

    /** @param outboxOrNull {@code null}: journal only (no outbox publication) */
    public CaseEvents(Dialect dialect, Outbox outboxOrNull) {
        this.dialect = dialect;
        this.outbox = outboxOrNull;
    }

    public String record(Connection c, String tenant, String scopeType, String scopeId, String eventType, String actor,
                         String summary, Map<String, Object> payload) {
        return record(c, tenant, scopeType, scopeId, eventType, actor, summary, payload, "CASEFILE", null);
    }

    /** @param occurredAtOrNull an outside date (a hearing held earlier, a letter received on a date); null = now */
    public String record(Connection c, String tenant, String scopeType, String scopeId, String eventType, String actor,
                         String summary, Map<String, Object> payload, String source, Instant occurredAtOrNull) {
        String id = Db.newId();
        String t = Db.tenant(tenant);
        String json = Db.json(payload);
        String shortSummary = summary == null ? null : summary.length() > 255 ? summary.substring(0, 255) : summary;
        if (occurredAtOrNull == null) {
            Jdbc.update(c, "INSERT INTO CF_EVENT(tenant_id, event_id, scope_type, scope_id, event_type, occurred_at, actor, summary,"
                    + " payload_json, source) VALUES (?,?,?,?,?," + dialect.now() + ",?,?,?,?)",
                    t, id, scopeType, scopeId, eventType, actor, shortSummary, json, source);
        } else {
            Jdbc.update(c, "INSERT INTO CF_EVENT(tenant_id, event_id, scope_type, scope_id, event_type, occurred_at, actor, summary,"
                    + " payload_json, source) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    t, id, scopeType, scopeId, eventType, occurredAtOrNull, actor, shortSummary, json, source);
        }
        if (outbox != null) {
            Map<String, Object> envelope = Db.kv("eventId", id, "scopeType", scopeType, "scopeId", scopeId, "eventType", eventType,
                    "actor", actor, "summary", shortSummary, "payload", payload == null ? Map.of() : payload);
            outbox.append(c, t, scopeType.toLowerCase() + ":" + scopeId, "casefile." + eventType, Db.json(envelope),
                    List.of(Outbox.Destination.unordered(DESTINATION)));
        }
        return id;
    }

    public List<Event> forScope(Connection c, String tenant, String scopeType, String scopeId, int limit) {
        int n = Math.max(1, Math.min(limit, 2000));
        return Jdbc.query(c, "SELECT * FROM CF_EVENT WHERE tenant_id=? AND scope_type=? AND scope_id=? ORDER BY occurred_at DESC, event_id DESC",
                CaseEvents::row, Db.tenant(tenant), scopeType, scopeId).stream().limit(n).toList();
    }

    /** Moves the journal of one scope to another (case merge). */
    public int move(Connection c, String tenant, String scopeType, String fromId, String toId) {
        return Jdbc.update(c, "UPDATE CF_EVENT SET scope_id=? WHERE tenant_id=? AND scope_type=? AND scope_id=?",
                toId, Db.tenant(tenant), scopeType, fromId);
    }

    static Event row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Event(rs.getString("event_id"), rs.getString("scope_type"), rs.getString("scope_id"), rs.getString("event_type"),
                Jdbc.instant(rs, "occurred_at"), rs.getString("actor"), rs.getString("summary"), Db.map(rs.getString("payload_json")),
                rs.getString("source"));
    }
}
