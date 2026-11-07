// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Jdbc;
import org.sangam.integration.connector.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Processes stored telemetry readings (inbox rows with source {@code telemetry:<channel>}); registered with
 * {@code InboxService.registerSourceHandler("telemetry:", handler)} and run inside the inbox processing transaction.
 *
 * <ol>
 *   <li>Decode the inbox envelope {@code {eventType, principal, data}} ({@code data} = {@link TelemetryReading#toMap()}).
 *       Duplicate readings never get here: the inbox key (tenant, source, message id) de-duplicates them.</li>
 *   <li>Lock the device row ({@link DeviceRegistry#lock}): the per-device serialisation point. Unknown, disabled or
 *       foreign-channel devices are skipped. {@code last_seen_at} is touched; {@code BAD} quality readings stop here.</li>
 *   <li>For every enabled rule matching (device, sensor): lock the active episode, ignore a reading whose
 *       {@code observedAt} is not after the episode's last applied reading (out-of-order / replay), evaluate the rule
 *       (comparator with hysteresis, or DMN) and advance the debounce state machine (see {@link AlarmEpisode}).</li>
 *   <li>Transitions that matter to processes ({@code opened}, {@code cleared}, {@code reopened-n}, {@code cleared-n})
 *       are submitted through WS-PORTS by {@link AlarmEventEmitter}, idempotent by {@code episodeId:transition}.</li>
 * </ol>
 * Returns true for every reading (including skipped and malformed ones, which are logged): returning false would only
 * re-buffer a message that can never match. Infrastructure failures throw, so the inbox re-buffers and retries.
 */
public final class TelemetryRuleHandler implements Inbox.Handler {

    private static final Logger LOG = LoggerFactory.getLogger(TelemetryRuleHandler.class);

    public static final String SOURCE_PREFIX = "telemetry:";
    public static final String EVENT_TYPE = "telemetry.reading";

    private final Dialect dialect;
    private final DeviceRegistry devices;
    private final AlarmEpisodeStore episodes;
    private final RuleSet.Registry rules;
    private final AlarmEventEmitter emitter;
    private final RuleDecisionEvaluator decisions;

    public TelemetryRuleHandler(Dialect dialect, DeviceRegistry devices, AlarmEpisodeStore episodes, RuleSet.Registry rules,
                                AlarmEventEmitter emitter, RuleDecisionEvaluator decisionsOrNull) {
        this.dialect = dialect;
        this.devices = devices;
        this.episodes = episodes;
        this.rules = rules;
        this.emitter = emitter;
        this.decisions = decisionsOrNull;
    }

    @Override
    public boolean handle(Connection c, Inbox.Message m) {
        TelemetryReading reading = decode(m);
        if (reading == null) {
            return true;
        }
        process(c, m.tenantId(), channelOf(m.source()), reading, m.source() + "\n" + m.messageId());
        return true;
    }

    /** The telemetry channel key of an inbox source {@code telemetry:<channel>}. */
    public static String channelOf(String source) {
        return source != null && source.startsWith(SOURCE_PREFIX) ? source.substring(SOURCE_PREFIX.length()) : source;
    }

    @SuppressWarnings("unchecked")
    private static TelemetryReading decode(Inbox.Message m) {
        try {
            Map<String, Object> env = Json.readMap(m.payload());
            Object type = env.get("eventType");
            if (!EVENT_TYPE.equals(type)) {
                LOG.warn("telemetry inbox row {}/{} has event type {}, expected {}; skipped", m.source(), m.messageId(), type, EVENT_TYPE);
                return null;
            }
            Object data = env.get("data");
            if (!(data instanceof Map)) {
                LOG.warn("telemetry inbox row {}/{} has no reading; skipped", m.source(), m.messageId());
                return null;
            }
            return TelemetryReading.fromMap((Map<String, Object>) data);
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            LOG.warn("telemetry inbox row {}/{} is not a valid reading ({}); skipped", m.source(), m.messageId(), e.getMessage());
            return null;
        }
    }

    /**
     * Applies one reading in the caller's transaction (also usable directly, e.g. by tests or a replay tool).
     *
     * @param readingKey stable identity of the reading (inbox source + message id); seeds the id of an episode it opens
     */
    public void process(Connection c, String tenant, String channelKey, TelemetryReading r, String readingKey) {
        Optional<DeviceRegistry.Device> found = devices.lock(c, tenant, r.deviceId());
        if (found.isEmpty()) {
            LOG.info("telemetry reading for unregistered device {}/{} on channel {}; skipped", tenant, r.deviceId(), channelKey);
            return;
        }
        DeviceRegistry.Device device = found.get();
        if (!device.enabled()) {
            LOG.debug("telemetry reading for disabled device {}/{}; skipped", tenant, r.deviceId());
            return;
        }
        if (!device.allowsChannel(channelKey)) {
            LOG.warn("telemetry reading for device {}/{} arrived on channel {} but the device is pinned to {}; skipped", tenant,
                    r.deviceId(), channelKey, device.channelKey());
            return;
        }
        devices.touch(c, tenant, r.deviceId());
        if (r.bad()) {
            return;
        }
        List<ThresholdRule> matching = rules.get(tenant).matching(r.deviceId(), r.sensor());
        if (matching.isEmpty()) {
            return;
        }
        Instant now = Jdbc.dbNow(c, dialect);
        for (ThresholdRule rule : matching) {
            apply(c, tenant, rule, device, r, readingKey, now);
        }
    }

    private record Evaluation(ThresholdRule.Verdict verdict, String severity) {
    }

    private void apply(Connection c, String tenant, ThresholdRule rule, DeviceRegistry.Device device, TelemetryReading r,
                       String readingKey, Instant now) {
        Optional<AlarmEpisode> current = episodes.lockActive(c, tenant, r.deviceId(), r.sensor(), rule.ruleId());
        if (current.isPresent() && current.get().lastReadingAt() != null && !r.observedAt().isAfter(current.get().lastReadingAt())) {
            LOG.debug("reading {}/{} at {} is not newer than episode {} ({}); ignored", r.deviceId(), r.sensor(), r.observedAt(),
                    current.get().episodeId(), current.get().lastReadingAt());
            return;
        }
        Evaluation ev = evaluate(tenant, rule, device, r, current.map(AlarmEpisode::state).orElse(null));
        if (ev == null) {
            return;
        }
        if (current.isEmpty()) {
            if (ev.verdict() != ThresholdRule.Verdict.BREACH) {
                return;
            }
            Optional<Instant> latest = episodes.latestReadingAt(c, tenant, r.deviceId(), r.sensor(), rule.ruleId());
            if (latest.isPresent() && !r.observedAt().isAfter(latest.get())) {
                LOG.debug("late breaching reading {}/{} at {} (episodes already saw {}); ignored", r.deviceId(), r.sensor(),
                        r.observedAt(), latest.get());
                return;
            }
            AlarmEpisode e = episodes.newEpisode(episodeId(tenant, readingKey, rule.ruleId()), tenant, r.deviceId(), r.sensor(),
                    rule.ruleId(), now);
            e.severity = ev.severity();
            breach(e, rule, r, now);
            if (debounceMet(rule, e, r)) {
                open(e, rule, now);
                emitter.emit(e, rule, rule.openedEventKey(), "opened", device, r.unit(), null, now);
            }
            episodes.insert(c, e);
            return;
        }
        AlarmEpisode e = current.get();
        switch (e.state()) {
            case PENDING -> {
                if (ev.verdict() == ThresholdRule.Verdict.BREACH) {
                    e.severity = ev.severity();
                    breach(e, rule, r, now);
                    if (debounceMet(rule, e, r)) {
                        open(e, rule, now);
                        emitter.emit(e, rule, rule.openedEventKey(), "opened", device, r.unit(), null, now);
                    }
                } else {
                    observe(e, rule, r, now, false);
                    close(e, "DEBOUNCE_RESET", now);
                }
            }
            case OPEN -> {
                if (ev.verdict() == ThresholdRule.Verdict.BREACH) {
                    e.severity = ev.severity();
                    breach(e, rule, r, now);
                } else if (ev.verdict() == ThresholdRule.Verdict.HOLD) {
                    observe(e, rule, r, now, false);
                } else {
                    observe(e, rule, r, now, false);
                    e.state = AlarmEpisode.State.CLEARED;
                    e.clearedAt = now;
                    e.breachCount = 0;
                    e.firstBreachAt = null;
                    if (e.acknowledged()) {
                        close(e, "CLEARED_AFTER_ACK", now);
                    } else if (rule.autoCloseOnClear()) {
                        close(e, "AUTO_CLOSED_ON_CLEAR", now);
                    }
                    String transition = e.reopenCount() == 0 ? "cleared" : "cleared-" + (e.reopenCount() + 1);
                    emitter.emit(e, rule, rule.clearedEventKey(), transition, device, r.unit(), null, now);
                }
            }
            case CLEARED -> {
                if (ev.verdict() == ThresholdRule.Verdict.BREACH) {
                    e.severity = ev.severity();
                    breach(e, rule, r, now);
                    if (debounceMet(rule, e, r)) {
                        e.reopenCount++;
                        e.clearedAt = null;
                        open(e, rule, now);
                        emitter.emit(e, rule, rule.reopenEventKey(), "reopened-" + (e.reopenCount() + 1), device, r.unit(),
                                Map.of("reopened", true), now);
                    }
                } else {
                    // still clear (or in the hysteresis band): a re-breach must be consecutive again
                    observe(e, rule, r, now, false);
                    e.breachCount = 0;
                    e.firstBreachAt = null;
                }
            }
            case CLOSED -> {
                return;   // not returned by lockActive
            }
        }
        episodes.update(c, e);
    }

    /** Applies a breaching reading: counts it and updates last/peak. */
    private static void breach(AlarmEpisode e, ThresholdRule rule, TelemetryReading r, Instant now) {
        if (e.breachCount == 0 || e.firstBreachAt == null) {
            e.firstBreachAt = r.observedAt();
        }
        e.breachCount++;
        observe(e, rule, r, now, true);
    }

    private static void observe(AlarmEpisode e, ThresholdRule rule, TelemetryReading r, Instant now, boolean breaching) {
        e.lastValue = r.value();
        if (breaching) {
            e.peakValue = rule.worse(e.peakValue, r.value());
        }
        e.lastReadingAt = r.observedAt();
        e.updatedAt = now;
    }

    private static boolean debounceMet(ThresholdRule rule, AlarmEpisode e, TelemetryReading r) {
        if (e.breachCount < rule.debounceCount()) {
            return false;
        }
        Duration d = rule.debounceDuration();
        return d == null || Duration.between(e.firstBreachAt, r.observedAt()).compareTo(d) >= 0;
    }

    private static void open(AlarmEpisode e, ThresholdRule rule, Instant now) {
        e.state = AlarmEpisode.State.OPEN;
        if (e.openedAt == null) {
            e.openedAt = now;
        }
        if (rule.ackSla() != null && e.ackDueAt == null && !e.acknowledged()) {
            e.ackDueAt = now.plus(rule.ackSla());
        }
        e.updatedAt = now;
    }

    static void close(AlarmEpisode e, String reason, Instant now) {
        e.state = AlarmEpisode.State.CLOSED;
        e.closedAt = now;
        e.closeReason = reason;
        e.updatedAt = now;
    }

    private Evaluation evaluate(String tenant, ThresholdRule rule, DeviceRegistry.Device device, TelemetryReading r,
                                AlarmEpisode.State state) {
        if (!rule.dmn()) {
            return new Evaluation(rule.evaluate(r.value()), rule.severity());
        }
        if (decisions == null) {
            LOG.warn("rule {}/{} needs DMN decision {} but no decision evaluator is configured; skipped", tenant, rule.ruleId(),
                    rule.dmnDecisionKey());
            return null;
        }
        RuleDecisionEvaluator.Decision d;
        try {
            d = decisions.evaluate(tenant, rule.dmnDecisionKey(), variables(rule, device, r, state));
        } catch (RuntimeException e) {
            if (infrastructure(e)) {
                throw e;
            }
            LOG.warn("DMN decision {} of rule {}/{} failed for {}/{}; rule skipped: {}", rule.dmnDecisionKey(), tenant, rule.ruleId(),
                    r.deviceId(), r.sensor(), e.toString());
            return null;
        }
        String severity = d.severity() == null || d.severity().isBlank() ? rule.severity() : d.severity();
        if (severity.length() > 64) {
            severity = severity.substring(0, 64);
        }
        return new Evaluation(d.alarm() ? ThresholdRule.Verdict.BREACH : ThresholdRule.Verdict.CLEAR, severity);
    }

    /**
     * DMN input variables. Only engine primitive types are passed (no BigDecimal/Instant, which Operaton would have
     * to serialise as objects): {@code value} as Double plus the exact {@code valueText}, {@code observedAt} as Date.
     */
    static Map<String, Object> variables(ThresholdRule rule, DeviceRegistry.Device device, TelemetryReading r, AlarmEpisode.State state) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("deviceId", r.deviceId());
        v.put("sensor", r.sensor());
        v.put("value", r.value().doubleValue());
        v.put("valueText", r.value().toPlainString());
        v.put("unit", r.unit());
        v.put("quality", r.quality());
        v.put("observedAt", Date.from(r.observedAt()));
        v.put("assetRef", device == null ? null : device.assetRef());
        v.put("locationRef", device == null ? null : device.locationRef());
        v.put("ruleId", rule.ruleId());
        v.put("episodeState", state == null ? "NONE" : state.name());
        for (Map.Entry<String, Object> a : r.attributes().entrySet()) {
            Object val = a.getValue();
            if (val instanceof java.math.BigDecimal b) {
                v.put("attr_" + a.getKey(), b.doubleValue());
            } else if (val instanceof String || val instanceof Boolean || val instanceof Integer || val instanceof Long
                    || val instanceof Double) {
                v.put("attr_" + a.getKey(), val);
            } else if (val instanceof Number n) {
                v.put("attr_" + a.getKey(), n.doubleValue());
            }
        }
        return v;
    }

    /** Deterministic id of an episode opened by a given reading (stable across retries of that reading). */
    static String episodeId(String tenant, String readingKey, String ruleId) {
        return UUID.nameUUIDFromBytes((tenant + "\n" + readingKey + "\n" + ruleId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean infrastructure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException || t instanceof Jdbc.LayerSqlException) {
                return true;
            }
        }
        return false;
    }
}
