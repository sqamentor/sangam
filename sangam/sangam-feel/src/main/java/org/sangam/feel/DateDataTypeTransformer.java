// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

import java.time.LocalDate;
import java.util.Objects;

import org.operaton.bpm.dmn.engine.impl.spi.type.DmnDataTypeTransformer;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * ADR-D-26: DMN {@code typeRef="date"} is the FEEL date type (DMN 1.3 section 10.3.2.3.4), a calendar date without a
 * time. Operaton's own {@code date} transformer knows only date-time values ({@code java.util.Date}, an ISO date-time
 * string, {@code ZonedDateTime}, {@code LocalDateTime}) and refuses the {@link LocalDate} every FEEL date evaluates
 * to, so an input expression such as {@code date(asOfDate)} could not be typed {@code date} at all, and a date range
 * entry ({@code [date("2026-04-01")..date("2027-03-31")]}) could not be compared with it. This transformer keeps a
 * FEEL date as the {@link LocalDate} it is (no time or zone is invented); every other value goes to the transformer
 * it replaces, unchanged.
 */
public final class DateDataTypeTransformer implements DmnDataTypeTransformer {

    public static final String TYPE = "date";

    private final DmnDataTypeTransformer dateTime;

    /** {@code dateTime}: the transformer registered for {@value #TYPE} before this one (Operaton's date-time form). */
    public DateDataTypeTransformer(DmnDataTypeTransformer dateTime) {
        this.dateTime = Objects.requireNonNull(dateTime, "dateTime");
    }

    @Override
    public TypedValue transform(Object value) {
        if (value instanceof LocalDate date) {
            return Variables.untypedValue(date);
        }
        return dateTime.transform(value);
    }
}