// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay.operaton;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.operaton.bpm.engine.variable.value.TypedValue;
import org.sangam.engine.replay.LocalDateInZone;
import org.sangam.modeller.replay.ReplayFixtures;
import org.sangam.modeller.replay.ReplayFixture.Value;
import org.sangam.modeller.replay.ReplayFixture.ValueType;

/**
 * M-136 (decision 151): engine values to fixture {@link Value}s and back, one mapping for the capture, the replay and
 * the observation, so both sides of a comparison see a value the same way.
 *
 * <table>
 *   <caption>Value contract</caption>
 *   <tr><th>Engine value</th><th>Fixture value</th><th>Given back to the engine as</th></tr>
 *   <tr><td>null</td><td>NULL</td><td>null</td></tr>
 *   <tr><td>String, Boolean</td><td>STRING, BOOLEAN</td><td>String, Boolean</td></tr>
 *   <tr><td>Long, Integer, Short, Byte</td><td>LONG</td><td>Long (the capture points already widen to Long)</td></tr>
 *   <tr><td>BigDecimal, BigInteger</td><td>DECIMAL, canonical {@code BigDecimal.toString()}, at most 1000
 *       characters</td><td>BigDecimal (decision 138 lossless decimal variable)</td></tr>
 *   <tr><td>Date, Instant</td><td>INSTANT</td><td>Date (Operaton's date type)</td></tr>
 *   <tr><td>{@link LocalDateInZone}, LocalDate (with the tenant zone)</td><td>LOCAL_DATE with its zone</td>
 *       <td>LocalDate</td></tr>
 *   <tr><td>ZonedDateTime in a region zone, unambiguous local time (UTC offset as the named zone {@code UTC})</td>
 *       <td>LOCAL_DATE_TIME with its zone</td><td>ZonedDateTime</td></tr>
 * </table>
 *
 * Anything else (double, float, objects, JSON, files, an ambiguous or fixed-offset date-time) has no exact fixture form:
 * {@link #toValue} returns null and the caller names it (capture: unsupported; replay: refused), never an approximation.
 */
public final class EngineValues {

    private static final int MAX_DECIMAL_TEXT = 1000;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private EngineValues() {
    }

    /**
     * The fixture value of an engine value, or null when it has none.
     *
     * @param localDateZone the zone a bare {@link LocalDate} belongs to (the tenant's business zone)
     */
    public static Value toValue(Object raw, ZoneId localDateZone) {
        Object v = raw instanceof TypedValue tv ? tv.getValue() : raw;
        if (v == null) {
            return new Value(ValueType.NULL, null, null);
        }
        if (v instanceof String s) {
            return Value.of(ValueType.STRING, s);
        }
        if (v instanceof Boolean b) {
            return Value.of(ValueType.BOOLEAN, b.toString());
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return Value.of(ValueType.LONG, Long.toString(((Number) v).longValue()));
        }
        if (v instanceof BigInteger b) {
            return decimal(new BigDecimal(b));
        }
        if (v instanceof BigDecimal d) {
            return decimal(d);
        }
        if (v instanceof Date d) {
            return Value.of(ValueType.INSTANT, d.toInstant().toString());
        }
        if (v instanceof Instant i) {
            return Value.of(ValueType.INSTANT, i.toString());
        }
        if (v instanceof LocalDateInZone d) {
            return new Value(ValueType.LOCAL_DATE, d.date().toString(), named(d.zone()).getId());
        }
        if (v instanceof LocalDate d) {
            return localDateZone == null ? null : new Value(ValueType.LOCAL_DATE, d.toString(), named(localDateZone).getId());
        }
        if (v instanceof ZonedDateTime z) {
            ZoneId zone = z.getZone();
            if (zone instanceof ZoneOffset o && !ZoneOffset.UTC.equals(o)) {
                return null; // a fixed offset has no historical rules; the fixture needs a named zone
            }
            zone = named(zone);
            LocalDateTime local = z.withZoneSameInstant(zone).toLocalDateTime();
            if (zone.getRules().getValidOffsets(local).size() != 1) {
                return null; // a local time in a gap or overlap does not name one instant
            }
            return new Value(ValueType.LOCAL_DATE_TIME, local.toString(), zone.getId());
        }
        return null;
    }

    /**
     * The engine value of a fixture value (see the class table).
     *
     * @throws IllegalArgumentException for JSON and BYTES_SHA256, which a replay cannot give back exactly
     */
    public static Object toEngine(Value v) {
        if (v == null || v.type() == ValueType.NULL) {
            return null;
        }
        return switch (v.type()) {
            case STRING -> v.value();
            case BOOLEAN -> Boolean.valueOf(v.value());
            case LONG -> Long.valueOf(v.value());
            case DECIMAL -> new BigDecimal(v.value());
            case INSTANT -> Date.from(Instant.parse(v.value()));
            case LOCAL_DATE -> LocalDate.parse(v.value());
            case LOCAL_DATE_TIME -> ZonedDateTime.of(LocalDateTime.parse(v.value()), ZoneId.of(v.zone()));
            case JSON, BYTES_SHA256, NULL -> throw new IllegalArgumentException("decision 151: a " + v.type()
                    + " value cannot be given back to the engine exactly");
        };
    }

    /** Engine variables of fixture values, in the fixture's order. */
    public static Map<String, Object> toEngine(Map<String, Value> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k, toEngine(v)));
        return out;
    }

    /** Lower-case hex SHA-256 of the canonical text of {@code values}: one {@code name=TYPE value [zone]} line per key, sorted. */
    public static String sha256(Map<String, Value> values) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(values).forEach((k, v) -> sb.append(k).append('=').append(v.type())
                .append(v.value() == null ? "" : " " + v.value()).append(v.zone() == null ? "" : " [" + v.zone() + "]")
                .append('\n'));
        return ReplayFixtures.sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Value decimal(BigDecimal d) {
        String s = d.toString();
        return s.length() > MAX_DECIMAL_TEXT ? null : Value.of(ValueType.DECIMAL, s);
    }

    /** UTC (offset {@code Z} or a UTC alias) as the named zone {@code UTC}, the form a fixture accepts. */
    static ZoneId named(ZoneId zone) {
        return ZoneOffset.UTC.equals(zone) || ZoneOffset.UTC.equals(zone.normalized()) ? UTC : zone;
    }
}
