// SPDX-License-Identifier: Apache-2.0
package org.sangam.field;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field audit events on the core hash-chained audit (R42, X.2.3). The root id is the assignment id (capture) or the
 * job id (dispatch), so the evidence of one visit or one response reads as one timeline. Writes happen in the
 * caller's transaction.
 */
public final class FieldAudit {

    public static final String TEMPLATE_PUBLISHED = "FIELD_TEMPLATE_PUBLISHED";
    public static final String ASSIGNMENT_CREATED = "FIELD_ASSIGNMENT_CREATED";
    public static final String ASSIGNMENT_STATUS = "FIELD_ASSIGNMENT_STATUS";
    public static final String SELECTION_DRAWN = "FIELD_RANDOM_SELECTION";
    public static final String DEVICE_REGISTERED = "FIELD_DEVICE_REGISTERED";
    public static final String DEVICE_STATUS = "FIELD_DEVICE_STATUS";
    public static final String PACKET_RECEIVED = "FIELD_PACKET_RECEIVED";
    public static final String EVIDENCE_STORED = "FIELD_EVIDENCE_STORED";
    public static final String INTEGRITY_FLAGGED = "FIELD_INTEGRITY_FLAGGED";
    public static final String CONFLICT_RAISED = "FIELD_SYNC_CONFLICT";
    public static final String CONFLICT_RESOLVED = "FIELD_SYNC_CONFLICT_RESOLVED";
    public static final String SCORED = "FIELD_VISIT_SCORED";
    public static final String JOB_CREATED = "DISPATCH_JOB_CREATED";
    public static final String JOB_STATUS = "DISPATCH_JOB_STATUS";
    public static final String OFFER = "DISPATCH_OFFER";
    public static final String UNIT_STATUS = "DISPATCH_UNIT_STATUS";
    public static final String TRIP = "DISPATCH_TRIP";

    /** Audit sink; {@link #NONE} when the deployment runs without the chain (tests, simulators). */
    public interface Sink {
        void append(Connection c, String tenantId, String rootId, String eventType, Map<String, ?> payload);
    }

    public static final Sink NONE = (c, t, r, e, p) -> { };

    private final Sink sink;

    public FieldAudit(Sink sink) {
        this.sink = sink == null ? NONE : sink;
    }

    public static FieldAudit chain(Dialect dialect) {
        AuditChain chain = new AuditChain(dialect);
        return new FieldAudit(chain::append);
    }

    public void record(Connection c, String tenantId, String rootId, String eventType, String actor, Map<String, ?> payload) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (actor != null) {
            p.put("actor", actor);
        }
        if (payload != null) {
            payload.forEach((k, v) -> {
                if (v != null) {
                    p.put(k, v);
                }
            });
        }
        sink.append(c, tenantId, rootId, eventType, p);
    }
}
