// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RuntimeService;
import org.sangam.connectors.health.x12.X12;
import org.sangam.connectors.health.x12.X12Decoders;
import org.sangam.integration.connector.Json;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboundMessage;
import org.sangam.integration.inbox.InboxHandler;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.integration.inbox.UniqueStartGuard;
import org.sangam.rest.modules.connectorshealth.HealthHooksPort;

/**
 * Routes a received 835. Each claim becomes one inbox message, deduplicated on trace number plus patient control number.
 * The claim case waiting on {@code sangam.ushealth.remit} with that {@code patientControlNumber} receives the file;
 * otherwise the later-remittance process starts once per (trace number, patient control number), and posts only that
 * claim. An unreadable file goes to {@link PoisonInbound}.
 */
public final class Remittance835Router {

    public static final String EVENT_TYPE = "sangam.ushealth.remit";
    public static final String REMITTANCE_PROCESS = "sangam-sec-ushealth-remittance";
    private static final String SOURCE = "x12.835";
    private static final String CLAIM_VARIABLE = "patientControlNumber";

    private final InboxService inbox;
    private final PoisonInbound poison;

    public Remittance835Router(InboxService inbox, PoisonInbound poison) {
        this.inbox = inbox;
        this.poison = poison;
    }

    @SuppressWarnings("unchecked")
    public HealthHooksPort.RemittanceResult route(String tenantId, String principal, String x12) {
        ChannelBinding binding = ChannelBinding.of(SOURCE, tenantId, principal);
        String fileId = "835:" + sha256(x12);
        List<Map<String, Object>> remits = new ArrayList<>();
        try {
            X12.Interchange ic = X12.parse(x12);
            if (!ic.errors().isEmpty()) {
                return poison(binding, fileId, x12, "envelope errors: " + ic.errors());
            }
            for (Map<String, Object> decoded : X12Decoders.decodeAll(ic)) {
                if ("835".equals(decoded.get("type"))) {
                    remits.add(decoded);
                }
            }
        } catch (RuntimeException e) {
            return poison(binding, fileId, x12, "not a readable X12 interchange: " + e.getMessage());
        }
        if (remits.isEmpty()) {
            return poison(binding, fileId, x12, "no 835 transaction");
        }
        List<InboundMessage> messages = new ArrayList<>();
        for (Map<String, Object> remit : remits) {
            Object trace = remit.get("traceNumber");
            List<Map<String, Object>> claims = remit.get("claims") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            if (trace == null || String.valueOf(trace).isBlank() || claims.isEmpty()) {
                return poison(binding, fileId, x12, "835 without a TRN02 trace number or CLP claims");
            }
            for (Map<String, Object> claim : claims) {
                Object ref = claim.get(CLAIM_VARIABLE);
                if (ref == null || String.valueOf(ref).isBlank()) {
                    return poison(binding, fileId, x12, "CLP without a patient control number");
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("x12", x12);
                payload.put("eraTraceNumber", String.valueOf(trace));
                payload.put(CLAIM_VARIABLE, String.valueOf(ref));
                messages.add(new InboundMessage(trace + ":" + ref, EVENT_TYPE, String.valueOf(ref), tenantId, Json.write(payload)));
            }
        }
        messages.forEach(m -> inbox.receive(binding, m));
        return new HealthHooksPort.RemittanceResult(messages.size(), null);
    }

    private HealthHooksPort.RemittanceResult poison(ChannelBinding binding, String fileId, String x12, String reason) {
        return new HealthHooksPort.RemittanceResult(0, poison.record(binding, fileId, EVENT_TYPE, x12, reason));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs in the inbox processing transaction, which is the engine transaction. */
    public static InboxHandler handler(ProcessEngine engine, UniqueStartGuard uniqueStart) {
        return (Connection c, InboxHandler.Envelope m) -> {
            RuntimeService rs = engine.getRuntimeService();
            Map<String, Object> vars = new LinkedHashMap<>(Json.readMap(m.dataJson()));
            vars.put("sangamInboxSource", m.source());
            vars.put("sangamInboxMessageId", m.messageId());
            vars.put("sangamEventType", m.eventType());
            long waiting = rs.createExecutionQuery().messageEventSubscriptionName(EVENT_TYPE)
                    .processVariableValueEquals(CLAIM_VARIABLE, m.correlationKey()).tenantIdIn(m.tenantId()).count();
            if (waiting > 0) {
                Map<String, Object> delivered = new LinkedHashMap<>(vars);
                delivered.remove(CLAIM_VARIABLE);
                rs.createMessageCorrelation(EVENT_TYPE).tenantId(m.tenantId()).processInstanceVariableEquals(CLAIM_VARIABLE, m.correlationKey())
                        .setVariables(delivered).correlateAll();
                return InboxHandler.Disposition.PROCESSED;
            }
            String key = vars.get("eraTraceNumber") + ":" + m.correlationKey();
            if (uniqueStart.claim(c, m.tenantId(), REMITTANCE_PROCESS, key, m.source(), m.messageId())) {
                String id = rs.createProcessInstanceByKey(REMITTANCE_PROCESS).businessKey(key).processDefinitionTenantId(m.tenantId()).setVariables(vars).execute().getId();
                uniqueStart.bind(c, m.tenantId(), REMITTANCE_PROCESS, key, id);
            }
            return InboxHandler.Disposition.PROCESSED;
        };
    }
}
