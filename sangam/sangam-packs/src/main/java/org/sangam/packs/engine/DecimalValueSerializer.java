// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.engine;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.engine.impl.AbstractQueryVariableValueCondition;
import org.operaton.bpm.engine.impl.QueryOperator;
import org.operaton.bpm.engine.impl.QueryVariableValue;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.variable.serializer.PrimitiveValueSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.ValueFields;
import org.operaton.bpm.engine.variable.impl.value.UntypedValueImpl;
import org.operaton.bpm.engine.variable.value.PrimitiveValue;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Decision 138: stores {@link BigDecimal} process variables losslessly. Persisted type id (the serializer name in
 * {@code TYPE_}) {@value #NAME}; value in {@code TEXT_} as {@link DecimalText} format v1 (canonical
 * {@code BigDecimal.toString()}: value and scale round-trip); {@code TEXT2_}, {@code LONG_}, {@code DOUBLE_} and the
 * byte array stay {@code NULL}. No float or Double is involved at any step.
 *
 * <p><b>Selection.</b> Registered as the first custom <i>pre</i> serializer, so every {@code BigDecimal} passed to
 * {@code setVariable} (untyped) and every {@link DecimalValueType} typed value uses it; before decision 138 a
 * top-level {@code BigDecimal} had no serializer (Java serialization is off). A {@code Double} is never converted:
 * it keeps Operaton's {@code double} type (decision 138 inventory: sangam-engine {@code DoubleVariableInventory}). An untyped
 * {@code null} is not taken (it stays Operaton's {@code null} type); a typed null decimal is stored as this type with
 * {@code TEXT_ NULL}.
 *
 * <p><b>Queries.</b> {@code variableValueEquals} / {@code variableValueNotEquals} with a {@code BigDecimal} match
 * value <b>and scale</b> ({@code 1.50} does not equal {@code 1.5}), because the stored text is compared. Range
 * operators ({@code >}, {@code >=}, {@code <}, {@code <=}) are <b>refused</b> with {@code DECIMAL_RANGE_QUERY}: the
 * engine would compare {@code TEXT_} as text, which is not numeric order. The operator is read from the query
 * condition being filled (Operaton has no other hook); if that field cannot be read the query is refused, never run
 * unchecked (pinned by {@code DecimalValueSerializerH2Test}).
 *
 * <p><b>Placement.</b> In sangam-packs (the lowest module with operaton-engine) so pack test engines and every engine
 * host can register it (W4-K and W4-E agreement, 2026-11-12).
 *
 * <p><b>Hosts.</b> Every engine that reads these rows must register this serializer under the same name:
 * {@code SangamProcessEnginePlugin.preInit} does it; a host that builds an engine without the plugin calls
 * {@link #register(ProcessEngineConfigurationImpl)} before the engine is built.
 */
public final class DecimalValueSerializer extends PrimitiveValueSerializer<PrimitiveValue<BigDecimal>> {

    /** The persisted type id, versioned with the text format. Never reuse it for another format. */
    public static final String NAME = "sangam-decimal-v" + DecimalText.FORMAT_VERSION;

    private static final Field WRAPPED_QUERY_VALUE = queryValueField();

    public DecimalValueSerializer() {
        super(DecimalValueType.DECIMAL);
    }

    /**
     * Adds this serializer as the first custom pre serializer of {@code cfg} unless one with {@link #NAME} is already
     * there. Call before the engine is built.
     */
    public static void register(ProcessEngineConfigurationImpl cfg) {
        List<TypedValueSerializer<?>> pre = new ArrayList<>();
        if (cfg.getCustomPreVariableSerializers() != null) {
            pre.addAll(cfg.getCustomPreVariableSerializers());
        }
        if (pre.stream().noneMatch(s -> NAME.equals(s.getName()))) {
            pre.add(0, new DecimalValueSerializer());
        }
        cfg.setCustomPreVariableSerializers(pre);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected boolean canWriteValue(TypedValue value) {
        if (value == null) {
            return false;
        }
        Object v = value.getValue();
        if (v instanceof BigDecimal) {
            return true;
        }
        // a typed null of this type; an untyped null stays Operaton's null type
        return v == null && value.getType() instanceof DecimalValueType;
    }

    @Override
    public PrimitiveValue<BigDecimal> convertToTypedValue(UntypedValueImpl untypedValue) {
        Object v = untypedValue.getValue();
        return DecimalValueType.decimal(v == null ? null : DecimalText.exact(v), untypedValue.isTransient());
    }

    @Override
    public void writeValue(PrimitiveValue<BigDecimal> value, ValueFields valueFields) {
        refuseRangeQuery(valueFields);
        BigDecimal d = value.getValue();
        valueFields.setTextValue(d == null ? null : DecimalText.format(d));
        valueFields.setTextValue2(null);
        valueFields.setLongValue(null);
        valueFields.setDoubleValue(null);
        valueFields.setByteArrayValue(null);
    }

    @Override
    public PrimitiveValue<BigDecimal> readValue(ValueFields valueFields, boolean asTransientValue) {
        String text = valueFields.getTextValue();
        return DecimalValueType.decimal(text == null ? null : DecimalText.parseCanonical(text), asTransientValue);
    }

    /** Refuses a range comparison on decimal text (see the class comment). */
    static void refuseRangeQuery(ValueFields valueFields) {
        if (!(valueFields instanceof AbstractQueryVariableValueCondition condition)) {
            return;
        }
        QueryOperator op = operatorOf(condition);
        if (op != QueryOperator.EQUALS && op != QueryOperator.NOT_EQUALS) {
            throw new DecimalText.DecimalFormatException("DECIMAL_RANGE_QUERY", "a decimal variable can be queried "
                    + "only with equals / not equals (value and scale); operator " + op + " would compare text");
        }
    }

    /** The operator of a query condition, or {@code null} when it cannot be read (then the query is refused). */
    static QueryOperator operatorOf(AbstractQueryVariableValueCondition condition) {
        if (WRAPPED_QUERY_VALUE == null) {
            return null;
        }
        try {
            QueryVariableValue q = (QueryVariableValue) WRAPPED_QUERY_VALUE.get(condition);
            return q == null ? null : q.getOperator();
        } catch (IllegalAccessException | ClassCastException e) {
            return null;
        }
    }

    /** True when the query operator can be read (pinned by a test; an upgrade that breaks it fails the build). */
    public static boolean operatorReadable() {
        return WRAPPED_QUERY_VALUE != null;
    }

    private static Field queryValueField() {
        try {
            Field f = AbstractQueryVariableValueCondition.class.getDeclaredField("wrappedQueryValue");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }
}
