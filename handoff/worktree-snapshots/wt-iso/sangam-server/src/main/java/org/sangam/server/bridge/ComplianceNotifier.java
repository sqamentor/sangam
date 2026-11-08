// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.compliance.ports.CompliancePorts;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.relay.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CompliancePorts.PrincipalNotifier} on the integration outbox: {@link #send} writes one outbox event to
 * destination {@value #DESTINATION} in the caller's transaction (the message is sent only if the compliance change
 * commits); the relay then delivers it with {@link #publisher()} through the DLT SMS and e-mail connectors, exactly once
 * per (event, channel) by operation identity.
 *
 * <p>Addresses come from the message attributes: {@code mobile} + {@code templateId} (DLT-registered template,
 * TRAI TCCCPR) for SMS, {@code email} for e-mail; {@code variables} are the DLT template variables. A message with no
 * usable address fails its delivery, which the relay retries and finally parks (visible under {@code /ops/outbox}).
 */
public final class ComplianceNotifier implements CompliancePorts.PrincipalNotifier {

    public static final String DESTINATION = "compliance-notify";
    public static final String EVENT_TYPE = "sangam.compliance.principal-message";

    private static final Logger LOG = LoggerFactory.getLogger(ComplianceNotifier.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutboxWriter outbox;
    private final ComplianceConnectors connectors;
    private final String smsConnector;
    private final String emailConnector;

    public ComplianceNotifier(OutboxWriter outbox, ComplianceConnectors connectors, String smsConnector,
                              String emailConnector) {
        this.outbox = outbox;
        this.connectors = connectors;
        this.smsConnector = smsConnector;
        this.emailConnector = emailConnector;
    }

    @Override
    public String send(Connection c, CompliancePorts.PrincipalMessage m) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", m.tenantId());
        payload.put("principalRef", m.principalRef());
        payload.put("kind", m.kind());
        payload.put("language", m.language() == null ? null : String.valueOf(m.language()));
        payload.put("subject", m.subject());
        payload.put("body", m.body());
        payload.put("attributes", m.attributes() == null ? Map.of() : m.attributes());
        String json;
        try {
            json = JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("principal message is not serialisable", e);
        }
        Outbox.Appended a = outbox.append(c, m.tenantId(), ComplianceConnectors.id64("cmpn:", m.principalRef()),
                EVENT_TYPE, json, List.of(Outbox.Destination.unordered(DESTINATION)));
        return a.eventId().toString();
    }

    /** Relay publisher for {@value #DESTINATION}; register it on the integration {@code PublisherRegistry}. */
    public Publisher publisher() {
        return this::deliver;
    }

    @SuppressWarnings("unchecked")
    private void deliver(String destination, Outbox.OutboxEvent event) throws Exception {
        Map<String, Object> p = JSON.readValue(event.payload(), Map.class);
        String tenant = String.valueOf(p.get("tenantId"));
        Map<String, Object> attrs = p.get("attributes") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String eventId = event.eventId().toString();
        boolean sent = false;
        String mobile = str(attrs.get("mobile"));
        String template = str(attrs.getOrDefault("templateId", attrs.get("dltTemplateId")));
        if (mobile != null && template != null) {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("mobile", mobile);
            in.put("templateId", template);
            in.put("variables", attrs.getOrDefault("variables", Map.of()));
            in.put("text", p.get("body"));
            sent |= outcome(connectors.call(tenant, eventId, "sms", smsConnector, "send", in), eventId, "sms");
        }
        String email = str(attrs.get("email"));
        if (email != null) {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("to", email);
            in.put("subject", p.getOrDefault("subject", p.get("kind")));
            in.put("text", p.get("body"));
            sent |= outcome(connectors.call(tenant, eventId, "email", emailConnector, "send", in), eventId, "email");
        }
        if (!sent) {
            throw new IllegalStateException("principal message " + eventId + " (" + p.get("kind")
                    + ") has no deliverable address: needs mobile+templateId or email in its attributes");
        }
    }

    private static boolean outcome(ComplianceConnectors.Result r, String eventId, String channel) {
        if (r.ok()) {
            return true;
        }
        if (r.retry()) {
            throw new IllegalStateException("retry " + channel + " for " + eventId + ": " + r.message());
        }
        // Final failure or unknown outcome: the ledger / reconciliation item holds it; no blind resend (VI.3 rule 5).
        LOG.warn("compliance {} message {} not delivered: {} {}", channel, eventId, r.code(), r.message());
        return "OUTCOME_UNKNOWN".equals(r.code());
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
