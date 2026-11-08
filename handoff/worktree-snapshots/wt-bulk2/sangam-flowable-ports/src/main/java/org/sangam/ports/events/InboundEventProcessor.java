// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.events.model.EventDefinition;
import org.sangam.ports.events.pipeline.InboundEventPipeline;
import org.sangam.ports.events.pipeline.InboundEventPipeline.EventInstance;
import org.sangam.ports.support.Json;
import org.sangam.ports.support.PortsProblem;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Entry point for every inbound channel adapter (webhook, JMS, Kafka, RabbitMQ): runs the channel's pipeline and
 * writes the event into the core {@link Inbox} (R38) with source {@code evt:<channelKey>} in one short transaction.
 * The adapter acknowledges the broker only after {@link #receive} returns; a redelivery is a {@code DUPLICATE} and
 * is acknowledged again. Dispatch to processes and cases happens later in {@link EventDispatcher} (so an event that
 * arrives before its subscription is buffered, FLOWABLE-INTO-OPERATON D-33).
 *
 * <p>Robustness (FLOWABLE-WEAKNESS-AUDIT FW-02, FW-28): a raw payload larger than {@link #maxPayloadBytes()} (default
 * {@value #DEFAULT_MAX_PAYLOAD_BYTES} bytes) is {@code REJECTED} before parsing; <em>every</em> payload failure of the
 * pipeline (bad JSON, wrong node types, coercion, Jackson stream constraints) is a {@code REJECTED} result with a
 * problem code, never an exception, so a broker adapter acknowledges a poison message instead of redelivering it for
 * ever. Message ids, event keys, tenants and business keys longer than {@value #MAX_ID_CHARS} characters are rejected.
 * Infrastructure failures (database, I/O) still throw, so the broker redelivers.
 */
public class InboundEventProcessor {

    public static final String SOURCE_PREFIX = "evt:";
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;
    public static final int MAX_ID_CHARS = 255;

    public enum Outcome { ACCEPTED, DUPLICATE, REJECTED }

    public record Result(Outcome outcome, String eventKey, String messageId, String reason) {
        /** Problem code of a {@code REJECTED} result ({@code PAYLOAD_TOO_LARGE}, {@code TENANT_MISMATCH}, {@code INVALID_INPUT} ...). */
        public String code() {
            if (outcome != Outcome.REJECTED || reason == null) {
                return null;
            }
            int i = reason.indexOf(':');
            return i > 0 && reason.substring(0, i).matches("[A-Z_]+") ? reason.substring(0, i) : "INVALID_INPUT";
        }
    }

    /** What the inbox row carries (the dispatcher's input). */
    public record StoredEvent(String channelKey, String tenantId, String eventKey, String messageId, Map<String, Object> payload,
                              Map<String, Object> correlation, String businessKey) {
    }

    private final EventRegistry registry;
    private final Inbox inbox;
    private final EventLog log;
    private final Transactions tx;
    private final int maxPayloadBytes;

    public InboundEventProcessor(EventRegistry registry, Inbox inbox, EventLog log, Transactions tx) {
        this(registry, inbox, log, tx, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    public InboundEventProcessor(EventRegistry registry, Inbox inbox, EventLog log, Transactions tx, int maxPayloadBytes) {
        this.registry = registry;
        this.inbox = inbox;
        this.log = log;
        this.tx = tx;
        this.maxPayloadBytes = maxPayloadBytes <= 0 ? DEFAULT_MAX_PAYLOAD_BYTES : maxPayloadBytes;
    }

    public int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    public Result receive(String tenant, String channelKey, String raw, Map<String, String> headers, String transportMessageId) {
        EventRegistry.DeployedChannel channel = registry.channel(tenant, channelKey)
                .orElseThrow(() -> new IllegalArgumentException("unknown channel " + channelKey + " in tenant " + tenant));
        InboundEventPipeline pipeline = InboundEventPipeline.json(channel.definition(), tenant,
                k -> registry.event(tenant, k).map(EventRegistry.DeployedEvent::definition).orElse(null));
        String transportId = transportMessageId != null && transportMessageId.length() > MAX_ID_CHARS ? null : transportMessageId;
        EventInstance ev;
        EventDefinition def;
        try {
            checkSize(raw);
            if (transportMessageId != null && transportId == null) {
                throw PortsProblem.invalid("transport message id longer than " + MAX_ID_CHARS + " characters");
            }
            ev = pipeline.process(raw, headers, transportId);
            PortsProblem.requireText(ev.messageId(), "message id", MAX_ID_CHARS);
            PortsProblem.requireText(ev.eventKey(), "event key", MAX_ID_CHARS);
            if (ev.tenantId() != null && ev.tenantId().length() > MAX_ID_CHARS) {
                throw PortsProblem.invalid("tenant id longer than " + MAX_ID_CHARS + " characters");
            }
            if (ev.businessKey() != null && ev.businessKey().length() > MAX_ID_CHARS) {
                throw PortsProblem.invalid("business key longer than " + MAX_ID_CHARS + " characters");
            }
            def = registry.event(ev.tenantId(), ev.eventKey()).map(EventRegistry.DeployedEvent::definition)
                    .orElseThrow(() -> new IllegalArgumentException("event " + ev.eventKey() + " not deployed in " + ev.tenantId()));
        } catch (RuntimeException e) {
            if (isInfrastructure(e)) {
                throw e;
            }
            String code = e instanceof PortsProblem p ? p.code() : "INVALID_INPUT";
            String reason = code + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            tx.inTransaction(c -> {
                log.log(c, tenant, EventLog.Direction.INBOUND, channelKey, null, transportId, null, "REJECTED", reason,
                        raw != null && raw.length() <= maxPayloadBytes ? raw : null);
                return null;
            });
            return new Result(Outcome.REJECTED, null, transportId, reason);
        }
        StoredEvent stored = new StoredEvent(channelKey, ev.tenantId(), ev.eventKey(), ev.messageId(), ev.payload(),
                ev.correlationValues(), ev.businessKey());
        String json = Json.write(stored);
        Inbox.ReceiveResult r = tx.inTransaction(c -> {
            Inbox.ReceiveResult res = inbox.receive(c, ev.tenantId(), SOURCE_PREFIX + channelKey, ev.messageId(), ev.eventKey(),
                    json, Duration.ofSeconds(def.ttlOrDefault()));
            log.log(c, ev.tenantId(), EventLog.Direction.INBOUND, channelKey, ev.eventKey(), ev.messageId(), ev.businessKey(),
                    res.name(), null, json);
            return res;
        });
        return new Result(r == Inbox.ReceiveResult.ACCEPTED ? Outcome.ACCEPTED : Outcome.DUPLICATE, ev.eventKey(), ev.messageId(), null);
    }

    private void checkSize(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw PortsProblem.invalid("empty event payload");
        }
        if (raw.length() > maxPayloadBytes) {
            throw PortsProblem.tooLarge("PAYLOAD_TOO_LARGE", "event payload", raw.length(), maxPayloadBytes);
        }
        if (raw.length() > maxPayloadBytes / 4) {
            long bytes = raw.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxPayloadBytes) {
                throw PortsProblem.tooLarge("PAYLOAD_TOO_LARGE", "event payload", bytes, maxPayloadBytes);
            }
        }
    }

    /** Database / transport failures are not the payload's fault: rethrown so the broker redelivers. */
    private static boolean isInfrastructure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException || t instanceof java.io.UncheckedIOException) {
                return true;
            }
        }
        return false;
    }
}
