// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.sangam.integration.connector.Json;
import org.sangam.ports.events.InboundEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Submits alarm-episode transitions to the WS-PORTS event registry ({@link InboundEventProcessor#receive}), which
 * stores them in the inbox under {@code evt:<channelKey>} for the {@code EventDispatcher} to start a process or
 * correlate a message (episode → process start or message).
 *
 * <ul>
 *   <li>Idempotent: the transport message id is {@code episodeId + ":" + transition} (also sent as the
 *       {@code message-id} header and the {@code messageId} payload field, for channels with a
 *       {@code messageIdJsonPointer}), so a retried reading after a rollback yields {@code DUPLICATE}.</li>
 *   <li>The event key is sent as the {@code eventKey} payload field and the {@code x-sangam-event-key} header: the
 *       channel's {@code channelEventKeyDetection} must use {@code jsonField: "eventKey"} or that header.</li>
 *   <li>{@code businessKey} = episode id (set {@code businessKeyField: "businessKey"} on the event definitions), so
 *       a "cleared" message correlates to the process the "opened" event started.</li>
 *   <li>A {@code REJECTED} result (or an unknown channel) is logged and recorded on the episode
 *       ({@code last_event_outcome}, {@code last_event_error}); it never throws. Infrastructure failures propagate.</li>
 * </ul>
 * The processor runs its own transaction ({@code Transactions} given to it). With a plain-pool {@code Transactions}
 * the event commits before the episode change: if the episode transaction then rolls back, the retry reproduces the
 * same transport id and the second submission is a {@code DUPLICATE} (at-least-once, never twice).
 */
public final class AlarmEventEmitter {

    private static final Logger LOG = LoggerFactory.getLogger(AlarmEventEmitter.class);

    public static final String HEADER_EVENT_KEY = "x-sangam-event-key";
    public static final String HEADER_EPISODE_ID = "x-sangam-episode-id";

    /** Outcome recorded on the episode. */
    public record Emission(String transition, String eventKey, String outcome, String error) {
        public boolean rejected() {
            return "REJECTED".equals(outcome);
        }
    }

    private final InboundEventProcessor processor;

    public AlarmEventEmitter(InboundEventProcessor processor) {
        this.processor = processor;
    }

    /**
     * Submits one transition and records the outcome on {@code e} (in memory; the caller persists the episode).
     *
     * @param device   registry entry for asset/location references, or null
     * @param extra    additional payload fields (e.g. {@code actor}), or null
     * @param now      database time of the transition
     */
    public Emission emit(AlarmEpisode e, ThresholdRule rule, String eventKey, String transition, DeviceRegistry.Device device,
                         String unit, Map<String, Object> extra, Instant now) {
        if (eventKey == null || eventKey.isBlank()) {
            Emission none = new Emission(transition, null, "NO_EVENT", null);
            record(e, none);
            return none;
        }
        String messageId = e.episodeId() + ":" + transition;
        Map<String, Object> p = payload(e, rule, eventKey, transition, messageId, device, unit, now);
        if (extra != null) {
            extra.forEach(p::putIfAbsent);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("message-id", messageId);
        headers.put(HEADER_EVENT_KEY, eventKey);
        headers.put(HEADER_EPISODE_ID, e.episodeId());
        Emission out;
        try {
            InboundEventProcessor.Result r = processor.receive(e.tenantId(), rule.channelKey(), Json.write(p), headers, messageId);
            out = new Emission(transition, eventKey, r.outcome().name(), r.reason());
            if (r.outcome() == InboundEventProcessor.Outcome.REJECTED) {
                LOG.warn("alarm event {} of episode {} (rule {}, channel {}) rejected by the event registry: {}", eventKey,
                        e.episodeId(), rule.ruleId(), rule.channelKey(), r.reason());
            }
        } catch (IllegalArgumentException misconfigured) {
            // unknown channel / event not deployed: a configuration error, not a reason to retry the reading for ever
            out = new Emission(transition, eventKey, "REJECTED", "CONFIGURATION: " + misconfigured.getMessage());
            LOG.warn("alarm event {} of episode {} (rule {}) could not be submitted to channel {}: {}", eventKey, e.episodeId(),
                    rule.ruleId(), rule.channelKey(), misconfigured.getMessage());
        }
        record(e, out);
        return out;
    }

    private static void record(AlarmEpisode e, Emission em) {
        e.lastEvent = em.transition();
        e.lastEventOutcome = em.outcome();
        e.lastEventError = em.error();
    }

    static Map<String, Object> payload(AlarmEpisode e, ThresholdRule rule, String eventKey, String transition, String messageId,
                                       DeviceRegistry.Device device, String unit, Instant now) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("eventKey", eventKey);
        p.put("messageId", messageId);
        p.put("transition", transition);
        p.put("businessKey", e.episodeId());
        p.put("episodeId", e.episodeId());
        p.put("deviceId", e.deviceId());
        p.put("sensor", e.sensor());
        p.put("ruleId", e.ruleId());
        p.put("severity", e.severity());
        p.put("state", e.state().name());
        p.put("value", e.lastValue());
        p.put("peak", e.peakValue());
        p.put("unit", unit);
        p.put("breachCount", e.breachCount());
        p.put("reopenCount", e.reopenCount());
        p.put("firstBreachAt", AlarmEpisode.text(e.firstBreachAt()));
        p.put("openedAt", AlarmEpisode.text(e.openedAt()));
        p.put("clearedAt", AlarmEpisode.text(e.clearedAt()));
        p.put("closedAt", AlarmEpisode.text(e.closedAt()));
        p.put("ackDueAt", AlarmEpisode.text(e.ackDueAt()));
        p.put("acknowledged", e.acknowledged());
        p.put("ackedBy", e.ackedBy());
        p.put("closed", e.state() == AlarmEpisode.State.CLOSED);
        p.put("assetRef", device == null ? null : device.assetRef());
        p.put("locationRef", device == null ? null : device.locationRef());
        p.put("ruleComparator", rule.dmn() ? "DMN" : rule.comparator().name());
        p.put("ruleLimit", rule.limit());
        p.put("ruleUpperLimit", rule.upperLimit());
        p.put("occurredAt", AlarmEpisode.text(now));
        return p;
    }
}
