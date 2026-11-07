// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One sensor observation (N12). Values are {@link BigDecimal} so thresholds compare exactly (no binary floating point).
 *
 * @param deviceId   registered device id ({@code TELEMETRY_DEVICE.device_id}), 1..64 characters
 * @param sensor     sensor / channel on the device, e.g. {@code temperature}, {@code waterLevel}, 1..64 characters
 * @param value      measured value
 * @param unit       unit as sent by the device (informational; rules do not convert units)
 * @param observedAt device-side observation time
 * @param quality    {@code GOOD} (default), {@code UNCERTAIN} or {@code BAD}; rules ignore {@code BAD} readings
 * @param attributes free-form extra fields (battery, rssi, ...), passed to DMN rules as variables
 */
public record TelemetryReading(String deviceId, String sensor, BigDecimal value, String unit, Instant observedAt,
                               String quality, Map<String, Object> attributes) {

    public static final String QUALITY_GOOD = "GOOD";
    public static final String QUALITY_UNCERTAIN = "UNCERTAIN";
    public static final String QUALITY_BAD = "BAD";
    public static final int MAX_ID_CHARS = 64;

    public TelemetryReading {
        requireId(deviceId, "deviceId");
        requireId(sensor, "sensor");
        Objects.requireNonNull(value, "value");
        if (value.toPlainString().length() > MAX_ID_CHARS) {
            throw new IllegalArgumentException("value has more than " + MAX_ID_CHARS + " digits");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        quality = quality == null || quality.isBlank() ? QUALITY_GOOD : quality.trim().toUpperCase(java.util.Locale.ROOT);
        if (!quality.equals(QUALITY_GOOD) && !quality.equals(QUALITY_UNCERTAIN) && !quality.equals(QUALITY_BAD)) {
            throw new IllegalArgumentException("quality must be GOOD, UNCERTAIN or BAD: " + quality);
        }
        if (unit != null && unit.length() > MAX_ID_CHARS) {
            throw new IllegalArgumentException("unit longer than " + MAX_ID_CHARS + " characters");
        }
        attributes = attributes == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public boolean bad() {
        return QUALITY_BAD.equals(quality);
    }

    /** JSON-ready map (the {@code data} of the inbox envelope). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", deviceId);
        m.put("sensor", sensor);
        m.put("value", value);
        m.put("unit", unit);
        m.put("observedAt", observedAt.toString());
        m.put("quality", quality);
        if (!attributes.isEmpty()) {
            m.put("attributes", attributes);
        }
        return m;
    }

    /** Parses the map form ({@link #toMap()}); throws {@link IllegalArgumentException} for a malformed reading. */
    public static TelemetryReading fromMap(Map<String, Object> m) {
        return fromMap(m, null, null);
    }

    /**
     * Parses a reading; {@code deviceIdOrNull}/{@code sensorOrNull} come from the transport (e.g. the MQTT topic) and
     * must equal the body's fields when the body carries them.
     */
    @SuppressWarnings("unchecked")
    public static TelemetryReading fromMap(Map<String, Object> m, String deviceIdOrNull, String sensorOrNull) {
        if (m == null) {
            throw new IllegalArgumentException("reading is not a JSON object");
        }
        String device = pick(m, "deviceId", deviceIdOrNull);
        String sensor = pick(m, "sensor", sensorOrNull);
        Object attrs = m.get("attributes");
        if (attrs != null && !(attrs instanceof Map)) {
            throw new IllegalArgumentException("attributes must be a JSON object");
        }
        return new TelemetryReading(device, sensor, decimal(m.get("value")), text(m.get("unit")), instant(m.get("observedAt")),
                text(m.get("quality")), (Map<String, Object>) attrs);
    }

    private static String pick(Map<String, Object> m, String field, String transport) {
        String body = text(m.get(field));
        if (transport != null && body != null && !transport.equals(body)) {
            throw new IllegalArgumentException(field + " in the body (" + body + ") differs from the transport (" + transport + ")");
        }
        return transport != null ? transport : body;
    }

    static BigDecimal decimal(Object v) {
        if (v == null) {
            throw new IllegalArgumentException("value is required");
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof java.math.BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("value is not a finite number");
            }
            return BigDecimal.valueOf(d);
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("value is not a number: " + s);
            }
        }
        if (v instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;   // digital inputs (door open, pump running)
        }
        throw new IllegalArgumentException("value is not a number");
    }

    /** ISO-8601 instant or epoch milliseconds. */
    static Instant instant(Object v) {
        if (v == null) {
            throw new IllegalArgumentException("observedAt is required");
        }
        if (v instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        String s = v.toString().trim();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("observedAt is not an ISO-8601 instant or epoch milliseconds: " + s);
            }
        }
    }

    private static String text(Object v) {
        return v == null ? null : v.toString();
    }

    private static void requireId(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (v.length() > MAX_ID_CHARS) {
            throw new IllegalArgumentException(name + " longer than " + MAX_ID_CHARS + " characters");
        }
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (Character.isISOControl(ch) || ch == '/' || ch == '+' || ch == '#') {
                throw new IllegalArgumentException(name + " contains a forbidden character");
            }
        }
    }
}
