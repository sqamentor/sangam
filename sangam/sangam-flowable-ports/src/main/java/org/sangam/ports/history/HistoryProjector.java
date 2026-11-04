// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.history;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.support.Json;

import java.sql.Connection;
import java.time.Instant;

/**
 * CQRS history read model (FC-25, FC-20, D-41; tables {@code HRM_*}). Registered with the relay for destination
 * {@link PortsHistoryEventHandler#DESTINATION} (ordered per root). Each outbox row is applied exactly once:
 * {@code HRM_CHECKPOINT(projector, tenant, root)} keeps the last applied {@code root_seq}; a redelivered or older row
 * is skipped in the same transaction that would have applied it.
 *
 * <p>FC-20: open activity instances are rows with {@code state='open'} in {@code HRM_ACTIVITY_INSTANCE}; Flowable's
 * runtime {@code ACT_RU_ACTINST} table is deliberately not added to the engine (B-21). Instance-to-instance links
 * are in {@code INSTANCE_LINK} (written synchronously, FC-31/32), which the read model joins on.
 */
public class HistoryProjector implements Publisher {

    public static final String NAME = "history-read-model";

    private final Dialect dialect;
    private final Transactions tx;

    public HistoryProjector(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) {
        if (!PortsHistoryEventHandler.EVENT_TYPE.equals(event.eventType())) {
            return;
        }
        tx.inTransaction(c -> {
            if (!advance(c, event.tenantId(), event.rootId(), event.rootSeq())) {
                return null;
            }
            for (JsonNode e : Json.tree(event.payload())) {
                apply(c, e);
            }
            return null;
        });
    }

    /** Moves the checkpoint forward; false when this root_seq was already applied. */
    boolean advance(Connection c, String tenant, String root, long seq) {
        var last = Jdbc.one(c, dialect.lockingSelect("last_seq", "HRM_CHECKPOINT", "projector = ? AND tenant_id = ? AND root_id = ?"),
                rs -> rs.getLong(1), NAME, tenant, root);
        if (last.isEmpty()) {
            if (!Jdbc.insertIfAbsent(c, dialect, "INSERT INTO HRM_CHECKPOINT(projector, tenant_id, root_id, last_seq) VALUES (?,?,?,?)",
                    NAME, tenant, root, seq)) {
                return advance(c, tenant, root, seq);
            }
            return true;
        }
        if (last.get() >= seq) {
            return false;
        }
        Jdbc.update(c, "UPDATE HRM_CHECKPOINT SET last_seq = ? WHERE projector = ? AND tenant_id = ? AND root_id = ?", seq, NAME, tenant, root);
        return true;
    }

    void apply(Connection c, JsonNode e) {
        switch (e.path("kind").asText()) {
            case "process" -> upsertInstance(c, e, "process", e.path("processInstanceId").asText(null),
                    e.path("processDefinitionKey").asText(null), e.path("processDefinitionId").asText(null),
                    processState(e), e.path("superProcessInstanceId").asText(e.path("superCaseInstanceId").asText(null)),
                    e.path("rootProcessInstanceId").asText(null));
            case "case" -> upsertInstance(c, e, "case", e.path("caseInstanceId").asText(null),
                    e.path("caseDefinitionKey").asText(null), null, e.path("state").asText(null),
                    e.path("superCaseInstanceId").asText(e.path("superProcessInstanceId").asText(null)), null);
            case "activity" -> upsertActivity(c, e, e.path("activityInstanceId").asText(null), e.path("processInstanceId").asText(null),
                    e.path("activityId").asText(null), e.path("activityType").asText(null), e.path("parentActivityInstanceId").asText(null),
                    activityState(e));
            case "caseActivity" -> upsertActivity(c, e, e.path("id").asText(null), e.path("caseInstanceId").asText(null),
                    e.path("caseActivityId").asText(null), e.path("caseActivityType").asText(null), null, e.path("state").asText("available"));
            case "task" -> upsertTask(c, e);
            default -> {
                // variables and others are not projected (values never enter the stream)
            }
        }
    }

    private static String processState(JsonNode e) {
        String s = e.path("state").asText(null);
        if (s != null) {
            return s.toLowerCase(java.util.Locale.ROOT);
        }
        return e.path("endTime").isNull() || e.path("endTime").isMissingNode() ? "active" : "completed";
    }

    private static String activityState(JsonNode e) {
        if (e.path("endTime").isNull() || e.path("endTime").isMissingNode()) {
            return "open";
        }
        return e.path("canceled").asBoolean(false) ? "canceled" : "completed";
    }

    private void upsertInstance(Connection c, JsonNode e, String scope, String id, String defKey, String defId, String state,
                                String superId, String rootId) {
        if (id == null) {
            return;
        }
        Instant start = ts(e, "startTime");
        Instant end = ts(e, "endTime");
        int n = Jdbc.update(c, "UPDATE HRM_PROCESS_INSTANCE SET state = ?, end_time = ?, business_key = COALESCE(?, business_key),"
                + " last_seq = last_seq + 1 WHERE id = ?", state, end, text(e, "businessKey"), id);
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO HRM_PROCESS_INSTANCE(id, tenant_id, scope_type, definition_key, definition_id, business_key, state,"
                            + " super_id, root_id, start_time, end_time, last_seq) VALUES (?,?,?,?,?,?,?,?,?,?,?,1)",
                    id, tenantOf(e), scope, defKey, defId, text(e, "businessKey"), state, superId, rootId, start, end);
        }
    }

    private void upsertActivity(Connection c, JsonNode e, String id, String instanceId, String activityId, String type,
                                String parentId, String state) {
        if (id == null) {
            return;
        }
        int n = Jdbc.update(c, "UPDATE HRM_ACTIVITY_INSTANCE SET state = ?, end_time = ? WHERE id = ?", state, ts(e, "endTime"), id);
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO HRM_ACTIVITY_INSTANCE(id, tenant_id, process_instance_id, activity_id, activity_type, parent_id,"
                            + " state, start_time, end_time) VALUES (?,?,?,?,?,?,?,?,?)",
                    id, tenantOf(e), instanceId, activityId, type, parentId, state, ts(e, "startTime"), ts(e, "endTime"));
        }
    }

    private void upsertTask(Connection c, JsonNode e) {
        String id = text(e, "taskId");
        if (id == null) {
            return;
        }
        String type = e.path("eventType").asText();
        String state = switch (type) {
            case "complete" -> "completed";
            case "delete" -> "deleted";
            default -> e.path("assignee").isNull() || e.path("assignee").isMissingNode() ? "created" : "assigned";
        };
        int n = Jdbc.update(c, "UPDATE HRM_TASK SET name = ?, assignee = ?, owner_id = ?, state = ?, delete_reason = ?, end_time = ? WHERE id = ?",
                text(e, "name"), text(e, "assignee"), text(e, "owner"), state, text(e, "deleteReason"), ts(e, "endTime"), id);
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO HRM_TASK(id, tenant_id, process_instance_id, case_instance_id, task_def_key, name, assignee, owner_id,"
                            + " state, delete_reason, create_time, end_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, tenantOf(e), text(e, "processInstanceId"), text(e, "caseInstanceId"), text(e, "taskDefinitionKey"),
                    text(e, "name"), text(e, "assignee"), text(e, "owner"), state, text(e, "deleteReason"), ts(e, "startTime"), ts(e, "endTime"));
        }
    }

    /** Read-model rows use "" for "no tenant" (as the other product tables), so tenant filters always match. */
    private static String tenantOf(JsonNode e) {
        String t = text(e, "tenantId");
        return t == null ? "" : t;
    }

    private static String text(JsonNode e, String f) {
        JsonNode n = e.get(f);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Instant ts(JsonNode e, String f) {
        String s = text(e, f);
        return s == null ? null : Instant.parse(s);
    }
}
