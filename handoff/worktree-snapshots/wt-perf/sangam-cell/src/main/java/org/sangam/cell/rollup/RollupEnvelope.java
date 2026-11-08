// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.rollup;

import java.util.LinkedHashMap;
import java.util.Map;

import org.sangam.core.layer.Outbox;

/**
 * Wire contract of the national roll-up stream (SCALE-ARCHITECTURE.md section 8): CloudEvents 1.0, structured JSON.
 * Extensions:
 * <ul>
 *   <li>{@code sangamcell}: publishing cell id;</li>
 *   <li>{@code sangamtenant}: tenant code;</li>
 *   <li>{@code sangamtenantpath}: hierarchy path from the root, {@code /}-separated (for example
 *       {@code IN/RJ/RJ-JPR/RJ-JPR-H0042}); the roll-up projects every prefix of it;</li>
 *   <li>{@code sangamroot}, {@code sangamrootseq}: per-root order (VI.2);</li>
 *   <li>{@code sangamoperationid}: on cross-cell business messages (exactly-once at the receiver, R36).</li>
 * </ul>
 * Consumers deduplicate on {@code id} (the outbox event id).
 */
public record RollupEnvelope(String id, String source, String type, String time, String cell, String tenant,
                             String tenantPath, String root, long rootSeq, String operationId,
                             Map<String, Object> data) {

    public static final String SPEC_VERSION = "1.0";

    public static RollupEnvelope of(String cell, String tenantPath, Outbox.OutboxEvent e, String timeIso,
                                    Map<String, Object> data, String operationId) {
        return new RollupEnvelope(e.eventId().toString(), "sangam://cell/" + cell, e.eventType(), timeIso, cell,
                e.tenantId(), tenantPath, e.rootId(), e.rootSeq(), operationId, data);
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("specversion", SPEC_VERSION);
        m.put("id", id);
        m.put("source", source);
        m.put("type", type);
        m.put("time", time);
        m.put("subject", root);
        m.put("datacontenttype", "application/json");
        m.put("sangamcell", cell);
        m.put("sangamtenant", tenant);
        m.put("sangamtenantpath", tenantPath);
        m.put("sangamroot", root);
        m.put("sangamrootseq", rootSeq);
        if (operationId != null) {
            m.put("sangamoperationid", operationId);
        }
        m.put("data", data == null ? Map.of() : data);
        return MiniJson.write(m);
    }

    /** Parses a structured CloudEvent produced by {@link #toJson()}. */
    @SuppressWarnings("unchecked")
    public static RollupEnvelope parse(String json) {
        Map<String, Object> m = MiniJson.readObject(json);
        Object seq = m.get("sangamrootseq");
        Object data = m.get("data");
        return new RollupEnvelope(str(m, "id"), str(m, "source"), str(m, "type"), str(m, "time"), str(m, "sangamcell"),
                str(m, "sangamtenant"), str(m, "sangamtenantpath"), str(m, "sangamroot"),
                seq == null ? 0L : Long.parseLong(seq.toString()), str(m, "sangamoperationid"),
                data instanceof Map ? (Map<String, Object>) data : Map.of());
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }
}
