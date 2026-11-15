// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.operaton.bpm.dmn.feel.impl.scala.function.CustomFunction;
import org.operaton.bpm.dmn.feel.impl.scala.function.FeelCustomFunctionProvider;

/**
 * Decision 158 (M-126): the FEEL built-in function that DMN 1.6 adds and the pinned FEEL engine does not have.
 *
 * <p><b>Evidence.</b> DMN 1.6 (formal/25-12-02), Table 73 "Semantics of conversion functions", adds
 * {@code date and time(date, time, timezone)}: "date is a date or date time; time is a time without timezone; timezone
 * is a string denoting a timezone offset or a IANA zone identifier"; it "creates a date time from the given date, time
 * and timezone". The pinned engine (org.camunda.feel:feel-engine 1.22.1, {@code ConversionBuiltinFunctions}) has the
 * one-argument and the two-argument {@code date and time} only: a three-argument call ended in "No function found
 * with name 'date and time' and 3 parameters".
 *
 * <p><b>How it is added.</b> The engine picks a function by name and number of arguments among the built-ins and the
 * functions of its provider ({@code EvalContext.function}), so a function of the same name with three parameters is an
 * overload: the two built-in forms are untouched. {@link SangamFeelEngine} puts this provider behind the host's
 * providers on every Sangam FEEL engine, so the specification function is not replaced by a host function of the same
 * name (Operaton keeps one custom function per name).
 *
 * <p><b>Semantics.</b>
 * <ul>
 *   <li>{@code date}: a date, or a date and time whose time component (and zone) is ignored, as for the two-argument
 *       form;</li>
 *   <li>{@code time}: a time without a timezone; a time with an offset or a zone is outside the domain;</li>
 *   <li>{@code timezone}: {@code Z}, an offset {@code +hh:mm} or {@code -hh:mm}, or an identifier of the IANA Time Zone
 *       Database as the JVM knows it ({@link ZoneId#getAvailableZoneIds()}); nothing else (no {@code UTC+1},
 *       no abbreviation);</li>
 *   <li>a local time that does not exist in the zone (a daylight-saving gap) or exists twice is resolved the way
 *       {@link ZonedDateTime#of(LocalDate, LocalTime, ZoneId)} does and the engine's own literals do.</li>
 * </ul>
 * An argument outside its domain is null in native FEEL; here it is a failure ({@value #CODE}), like every other
 * evaluation failure of a Sangam FEEL engine (ADR-D-26).
 */
public final class Dmn16Functions implements FeelCustomFunctionProvider {

    public static final String DATE_AND_TIME = "date and time";
    public static final String CODE = "SANGAM-FEEL-DATE-AND-TIME";

    static final Dmn16Functions INSTANCE = new Dmn16Functions();

    private static final Pattern OFFSET = Pattern.compile("Z|[+-]\\d{2}:\\d{2}");

    private final CustomFunction dateAndTime = CustomFunction.create().setParams("date", "time", "timezone")
            .setFunction(Dmn16Functions::dateAndTime).build();

    private Dmn16Functions() {
    }

    @Override
    public Optional<CustomFunction> resolveFunction(String functionName) {
        return DATE_AND_TIME.equals(functionName) ? Optional.of(dateAndTime) : Optional.empty();
    }

    @Override
    public Collection<String> getFunctionNames() {
        return List.of(DATE_AND_TIME);
    }

    /** {@code date and time(date, time, timezone)}; the arguments as the engine's value mappers give them. */
    static ZonedDateTime dateAndTime(List<Object> args) {
        if (args == null || args.size() != 3) {
            throw refused("three arguments (date, time, timezone) are required");
        }
        return ZonedDateTime.of(date(args.get(0)), time(args.get(1)), zone(args.get(2)));
    }

    private static LocalDate date(Object v) {
        return switch (v) {
            case LocalDate d -> d;
            case LocalDateTime d -> d.toLocalDate();
            case ZonedDateTime d -> d.toLocalDate();
            case OffsetDateTime d -> d.toLocalDate();
            case null, default -> throw refused("date must be a date or a date and time, found " + describe(v));
        };
    }

    private static LocalTime time(Object v) {
        if (v instanceof LocalTime t) {
            return t;
        }
        throw refused("time must be a time without a timezone, found " + describe(v));
    }

    private static ZoneId zone(Object v) {
        if (!(v instanceof String s)) {
            throw refused("timezone must be a string, found " + describe(v));
        }
        try {
            if (OFFSET.matcher(s).matches()) {
                return ZoneOffset.of(s);
            }
            if (ZoneId.getAvailableZoneIds().contains(s)) {
                return ZoneId.of(s);
            }
        } catch (DateTimeException e) {
            throw refused("timezone \"" + s + "\" is not valid: " + e.getMessage());
        }
        throw refused("timezone \"" + s + "\" is neither an offset (Z, +hh:mm, -hh:mm) nor an IANA zone identifier");
    }

    private static String describe(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName() + " " + v;
    }

    private static IllegalArgumentException refused(String why) {
        return new IllegalArgumentException(CODE + ": date and time(date, time, timezone): " + why);
    }
}
