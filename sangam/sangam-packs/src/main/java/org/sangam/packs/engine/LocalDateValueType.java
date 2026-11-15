// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.engine;

import java.io.Serial;
import java.time.LocalDate;
import java.util.Map;

import org.operaton.bpm.engine.variable.impl.type.PrimitiveValueTypeImpl;
import org.operaton.bpm.engine.variable.impl.value.PrimitiveTypeValueImpl;
import org.operaton.bpm.engine.variable.value.PrimitiveValue;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Decision 333: the engine local-date value type, built as decision 138 built {@link DecimalValueType}. Value type
 * name {@value #NAME}, Java type {@link LocalDate}; the persisted type id (the serializer name in {@code TYPE_}) is
 * {@link LocalDateValueSerializer#NAME}.
 *
 * <p>Typed values: {@link #localDate(LocalDate)} / {@link #localDate(LocalDate, boolean)}. {@link #createValue} (a
 * typed value built from a type name) accepts what {@link LocalDateText#exact} accepts: a {@code LocalDate} or strict
 * format v1 text. There is <b>no</b> conversion from another typed value: a {@code string} variable stays a string
 * (decision 333: nothing is converted silently), and a {@code date} (an instant) is not a calendar date without a
 * zone.
 */
public final class LocalDateValueType extends PrimitiveValueTypeImpl {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The value type name callers see ({@code TypedValue.getType().getName()}). */
    public static final String NAME = "sangam-local-date";

    /** The single instance. */
    public static final LocalDateValueType LOCAL_DATE = new LocalDateValueType();

    private LocalDateValueType() {
        super(NAME, LocalDate.class);
    }

    /** A local-date typed value; {@code null} is a typed null. Refuses a year outside the format range. */
    public static PrimitiveValue<LocalDate> localDate(LocalDate value) {
        return localDate(value, false);
    }

    /** A local-date typed value, transient when {@code isTransient}. */
    public static PrimitiveValue<LocalDate> localDate(LocalDate value, boolean isTransient) {
        if (value != null) {
            LocalDateText.format(value);
        }
        LocalDateValue v = new LocalDateValue(value);
        v.setTransient(isTransient);
        return v;
    }

    @Override
    public TypedValue createValue(Object value, Map<String, Object> valueInfo) {
        return localDate(value == null ? null : LocalDateText.exact(value), Boolean.TRUE.equals(isTransient(valueInfo)));
    }

    @Override
    public boolean canConvertFromTypedValue(TypedValue typedValue) {
        return false;
    }

    @Override
    public TypedValue convertFromTypedValue(TypedValue typedValue) {
        throw unsupportedConversion(typedValue == null ? null : typedValue.getType());
    }

    /** Resolves to the single instance on deserialization (the engine compares types by name and Java type). */
    @Serial
    private Object readResolve() {
        return LOCAL_DATE;
    }

    /** The typed value: an Operaton primitive value of this type. */
    static final class LocalDateValue extends PrimitiveTypeValueImpl<LocalDate> {
        @Serial
        private static final long serialVersionUID = 1L;

        LocalDateValue(LocalDate value) {
            super(value, LOCAL_DATE);
        }
    }
}
