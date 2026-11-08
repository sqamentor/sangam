// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.sim;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sangam.packs.port.PackAuditEvent;
import org.sangam.packs.port.PackAuditSink;

/** Default audit sink: one JSON log line per event (logger {@code sangam.audit.pack}) plus a bounded recent buffer. */
public class LoggingAuditSink implements PackAuditSink {

    private static final Logger LOG = LoggerFactory.getLogger("sangam.audit.pack");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int KEEP = 10_000;

    private final Deque<PackAuditEvent> recent = new ArrayDeque<>();

    @Override
    public void record(PackAuditEvent e) {
        synchronized (recent) {
            if (recent.size() >= KEEP) {
                recent.removeFirst();
            }
            recent.addLast(e);
        }
        if (LOG.isInfoEnabled()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type());
            m.put("tenant", e.tenantId());
            m.put("businessKey", e.businessKey());
            m.put("processInstanceId", e.processInstanceId());
            m.put("actorPerson", e.actorPersonId());
            m.put("actorSeat", e.actorSeatId());
            m.put("actedFor", e.actedForSeatId());
            m.put("authorityBasis", e.authorityBasis());
            m.put("package", e.packageVersion());
            m.put("payloadHash", e.payloadHash());
            m.put("operationId", e.operationId());
            m.put("details", e.details());
            m.put("at", e.at() == null ? null : e.at().toString());
            try {
                LOG.info(JSON.writeValueAsString(m));
            } catch (JsonProcessingException ex) {
                LOG.info("{} {}", e.type(), e.businessKey());
            }
        }
    }

    /** Events recorded so far (newest last), bounded. */
    public List<PackAuditEvent> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    /** Recent events of one case, in order. */
    public List<PackAuditEvent> timeline(String businessKey) {
        return recent().stream().filter(e -> businessKey.equals(e.businessKey())).toList();
    }
}
