// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.engine;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.Map;

import org.operaton.bpm.engine.variable.impl.type.PrimitiveValueTypeImpl;
import org.operaton.bpm.engine.variable.impl.value.PrimitiveTypeValueImpl;
import org.operaton.bpm.engine.variable.type.ValueType;
import org.operaton.bpm.engine.variable.value.PrimitiveValue;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Decision 138: the engine decimal value type. Value type name {@value #NAME}, Java type {@link BigDecimal}; the
 * persisted type id (the serializer name in {@code TYPE_}) is {@link DecimalValueSerializer#NAME}.
 *
 * <p>Typed values: {@link #decimal(BigDecimal)} / {@link #decimal(BigDecimal, boolean)}. {@link #createValue} (used
 * when a typed value is built from a type name, for example by a REST variable mapper) accepts what
 * {@link DecimalText#exact} accepts: {@code BigDecimal}, integral numbers, strict decimal text; never a
 * {@code Double}. Conversion from another typed value exists only from Operaton's exact integral types
 * ({@code integer}, {@code long}, {@code short}); {@code double} is refused (decision 138: a binary float is never
 * turned into money).
 */
public final class DecimalValueType extends PrimitiveValueTypeImpl {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The value type name callers see ({@code TypedValue.getType().getName()}). */
    public static final String NAME = "sangam-decimal";

    /** The single instance. */
    public static final DecimalValueType DECIMAL = new DecimalValueType();

    private DecimalValueType() {
        super(NAME, BigDecimal.class);
    }

    /** A decimal typed value; {@code null} is a typed null. Refuses a value whose text exceeds the format limit. */
    public static PrimitiveValue<BigDecimal> decimal(BigDecimal value) {
        return decimal(value, false);
    }

    /** A decimal typed value, transient when {@code isTransient}. */
    public static PrimitiveValue<BigDecimal> decimal(BigDecimal value, boolean isTransient) {
        if (value != null) {
            DecimalText.format(value);
        }
        DecimalValue v = new DecimalValue(value);
        v.setTransient(isTransient);
        return v;
    }

    @Override
    public TypedValue createValue(Object value, Map<String, Object> valueInfo) {
        return decimal(value == null ? null : DecimalText.exact(value), Boolean.TRUE.equals(isTransient(valueInfo)));
    }

    @Override
    public boolean canConvertFromTypedValue(TypedValue typedValue) {
        if (typedValue == null || typedValue.getType() == null) {
            return false;
        }
        ValueType t = typedValue.getType();
        return t == ValueType.INTEGER || t == ValueType.LONG || t == ValueType.SHORT;
    }

    @Override
    public TypedValue convertFromTypedValue(TypedValue typedValue) {
        if (!canConvertFromTypedValue(typedValue)) {
            throw unsupportedConversion(typedValue == null ? null : typedValue.getType());
        }
        Object v = typedValue.getValue();
        return decimal(v == null ? null : DecimalText.exact(v), typedValue.isTransient());
    }

    /** Resolves to the single instance on deserialization (the engine compares types by name and Java type). */
    @Serial
    private Object readResolve() {
        return DECIMAL;
    }

    /** The typed value: an Operaton primitive value of this type. */
    static final class DecimalValue extends PrimitiveTypeValueImpl<BigDecimal> {
        @Serial
        private static final long serialVersionUID = 1L;

        DecimalValue(BigDecimal value) {
            super(value, DECIMAL);
        }
    }
}
