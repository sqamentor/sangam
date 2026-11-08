// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.operaton.bpm.engine.MismatchingMessageCorrelationException;
import org.operaton.bpm.engine.RuntimeService;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.field.FieldModule;
import org.sangam.field.ports.FieldPorts;
import org.sangam.integration.relay.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The field module's events on the integration outbox (VI.2) and their engine side (sangam-field README §2, §5):
 * <ul>
 *   <li>{@link #events()} writes every module event ({@code field.assignment.*}, {@code field.visit.submitted},
 *       {@code dispatch.job.*}, {@code dispatch.offer.created}) to destination {@value #DESTINATION} in the caller's
 *       transaction;</li>
 *   <li>{@link #correlator()} is the in-process consumer the relay runs first: {@code field.visit.submitted} correlates
 *       message {@code field.visit.submitted} on the assignment's business key (the waiting inspection process resumes
 *       with {@code fieldPassed}/{@code fieldGrade}); {@code dispatch.job.closed} correlates {@code dispatch.job.closed}
 *       on the job's business key (with {@code dispatchOutcome}). No waiting instance is not an error.</li>
 * </ul>
 */
public final class FieldEngineEvents {

    public static final String DESTINATION = "field-events";

    private static final Logger LOG = LoggerFactory.getLogger(FieldEngineEvents.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutboxWriter writer;
    private final Outbox outbox;
    private final RuntimeService runtime;

    /** @param writer the integration outbox writer, or null to use the core {@link Outbox} directly */
    public FieldEngineEvents(OutboxWriter writer, Dialect dialect, RuntimeService runtime) {
        this.writer = writer;
        this.outbox = new Outbox(dialect);
        this.runtime = runtime;
    }

    public FieldPorts.FieldEvents events() {
        return (c, tenantId, type, entityId, payload) -> {
            Map<String, Object> body = new LinkedHashMap<>(payload == null ? Map.of() : payload);
            body.put("tenantId", tenantId);
            body.put("entityId", entityId);
            body.put("type", type);
            String json;
            try {
                json = JSON.writeValueAsString(body);
            } catch (Exception e) {
                throw new IllegalArgumentException("field event is not serialisable", e);
            }
            String root = "field:" + entityId;
            var dest = List.of(Outbox.Destination.unordered(DESTINATION));
            if (writer != null) {
                writer.append(c, tenantId, root, type, json, dest);
            } else {
                outbox.append(c, tenantId, root, type, json, dest);
            }
        };
    }

    /** Engine message correlation for submitted visits and closed dispatch jobs. */
    @SuppressWarnings("unchecked")
    public Publisher correlator() {
        return (destination, event) -> {
            String type = event.eventType();
            if (!"field.visit.submitted".equals(type) && !"dispatch.job.closed".equals(type)) {
                return;
            }
            Map<String, Object> p = JSON.readValue(event.payload(), Map.class);
            String tenant = String.valueOf(p.get("tenantId"));
            String businessKey;
            Map<String, Object> vars = new LinkedHashMap<>();
            if ("field.visit.submitted".equals(type)) {
                businessKey = str(p.get("businessKey"));
                vars.put("fieldAssignmentId", p.get("entityId"));
                vars.put("fieldPassed", p.get("passed"));
                vars.put("fieldGrade", p.get("grade"));
            } else {
                FieldModule m = module;
                businessKey = m == null ? null : m.dispatch.getJob(tenant, String.valueOf(p.get("entityId"))).businessKey();
                vars.put("dispatchJobId", p.get("entityId"));
                vars.put("dispatchOutcome", p.get("outcome"));
            }
            if (businessKey == null || businessKey.isBlank() || "null".equals(businessKey)) {
                return;
            }
            try {
                runtime.createMessageCorrelation(type).tenantId(tenant).processInstanceBusinessKey(businessKey)
                        .setVariables(vars).correlateAll();
            } catch (MismatchingMessageCorrelationException e) {
                LOG.debug("no process waits for {} on {}: {}", type, businessKey, e.getMessage());
            }
        };
    }

    private volatile FieldModule module;

    /** Set once the module is built (the job lookup needs it). */
    public void module(FieldModule module) {
        this.module = module;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
