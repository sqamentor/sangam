// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.programme.ports.ProgrammePorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The case and programme modules' {@code Notifier} ports on the integration outbox and connectors (class (b), exactly
 * once): {@link #send} writes one outbox event to {@link #destination()} in the caller's transaction, so a meeting
 * notice, deadline warning or defaulter reminder is sent only if the change commits; the relay delivers it with
 * {@link #publisher()} through the DLT SMS and e-mail connectors, one operation per (event, channel).
 *
 * <p>Addresses come from the message attributes: {@code mobile} + {@code templateId} (DLT-registered template) for SMS,
 * {@code email} for e-mail. A recipient seat or person without an address resolver yields no deliverable address: the
 * delivery fails, is retried and finally parked (visible under {@code /ops/outbox}).
 */
public final class CaseModuleNotifier implements CaseFilePorts.Notifier, ProgrammePorts.Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(CaseModuleNotifier.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String module;
    private final OutboxWriter outbox;
    private final ComplianceConnectors connectors;
    private final String smsConnector;
    private final String emailConnector;

    public CaseModuleNotifier(String module, OutboxWriter outbox, ComplianceConnectors connectors, String smsConnector,
                              String emailConnector) {
        this.module = module;
        this.outbox = outbox;
        this.connectors = connectors;
        this.smsConnector = smsConnector;
        this.emailConnector = emailConnector;
    }

    /** {@code <module>-notify}, e.g. {@code case-notify}, {@code programme-notify}. */
    public String destination() {
        return module + "-notify";
    }

    @Override
    public String send(Connection c, CaseFilePorts.Message m) {
        return write(c, m.tenantId(), m.recipientRef(), m.kind(), m.subject(), m.body(), m.attributes());
    }

    @Override
    public String send(Connection c, ProgrammePorts.Reminder r) {
        Map<String, Object> attrs = new LinkedHashMap<>(r.attributes() == null ? Map.of() : r.attributes());
        attrs.putIfAbsent("orgUnit", r.orgUnit());
        attrs.putIfAbsent("subjectRef", r.subjectRef());
        return write(c, r.tenantId(), r.recipientRef(), r.kind(), r.kind(), String.valueOf(attrs.getOrDefault("text", r.kind())),
                attrs);
    }

    private String write(Connection c, String tenant, String recipient, String kind, String subject, String body,
                         Map<String, Object> attributes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenant);
        payload.put("recipientRef", recipient);
        payload.put("kind", kind);
        payload.put("subject", subject);
        payload.put("body", body);
        payload.put("attributes", attributes == null ? Map.of() : attributes);
        String json;
        try {
            json = JSON.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException(module + " message is not serialisable", e);
        }
        Outbox.Appended a = outbox.append(c, tenant, ComplianceConnectors.id64(module + "n:", String.valueOf(recipient)),
                "sangam." + module + ".message", json, List.of(Outbox.Destination.unordered(destination())));
        return a.eventId().toString();
    }

    /** Relay publisher for {@link #destination()}; register it on the integration {@code PublisherRegistry}. */
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
            throw new IllegalStateException(module + " message " + eventId + " (" + p.get("kind") + " to "
                    + p.get("recipientRef") + ") has no deliverable address: needs mobile+templateId or email attributes");
        }
    }

    private boolean outcome(ComplianceConnectors.Result r, String eventId, String channel) {
        if (r.ok()) {
            return true;
        }
        if (r.retry()) {
            throw new IllegalStateException("retry " + channel + " for " + eventId + ": " + r.message());
        }
        // Final failure or unknown outcome: the ledger / reconciliation item holds it; no blind resend (VI.3 rule 5).
        LOG.warn("{} {} message {} not delivered: {} {}", module, channel, eventId, r.code(), r.message());
        return "OUTCOME_UNKNOWN".equals(r.code());
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
