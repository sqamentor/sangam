// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish;

import org.sangam.core.layer.Outbox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CloudEvents 1.0 attributes for an outbox event (VI.2.3 rule 2: the CloudEvents {@code id} is {@code event_id}, so
 * consumers dedup on it). Extension attributes: {@code sangamtenant}, {@code sangamroot}, {@code sangamrootseq}
 * (per-root order), {@code sangamdestination}.
 */
public final class CloudEvents {

    public static final String SPEC_VERSION = "1.0";

    private CloudEvents() {
    }

    /** Attribute map (names without transport prefix). */
    public static Map<String, String> attributes(String source, String destination, Outbox.OutboxEvent e) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("specversion", SPEC_VERSION);
        a.put("id", e.eventId().toString());
        a.put("source", source);
        a.put("type", e.eventType());
        a.put("subject", e.rootId());
        a.put("datacontenttype", "application/json");
        a.put("sangamtenant", e.tenantId());
        a.put("sangamroot", e.rootId());
        a.put("sangamrootseq", Long.toString(e.rootSeq()));
        a.put("sangamdestination", destination);
        return a;
    }

    /** Structured-mode JSON envelope with {@code data} holding the payload JSON verbatim. */
    public static String structured(String source, String destination, Outbox.OutboxEvent e) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> a : attributes(source, destination, e).entrySet()) {
            sb.append('"').append(a.getKey()).append("\":\"").append(escape(a.getValue())).append("\",");
        }
        String data = e.payload() == null || e.payload().isBlank() ? "null" : e.payload();
        sb.append("\"data\":").append(data).append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
