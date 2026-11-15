// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.operaton.bpm.engine.impl.AbstractQueryVariableValueCondition;
import org.operaton.bpm.engine.impl.QueryOperator;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.variable.serializer.PrimitiveValueSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.ValueFields;
import org.operaton.bpm.engine.variable.impl.value.UntypedValueImpl;
import org.operaton.bpm.engine.variable.value.PrimitiveValue;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Decision 333: stores {@link LocalDate} process variables as a type of their own, the way decision 138 stores
 * decimals ({@link DecimalValueSerializer}). Persisted type id (the serializer name in {@code TYPE_}) {@value #NAME};
 * value in {@code TEXT_} as {@link LocalDateText} format v1 ({@code uuuu-MM-dd}); {@code TEXT2_}, {@code LONG_},
 * {@code DOUBLE_} and the byte array stay {@code NULL}. No Java serialization is involved.
 *
 * <p><b>Selection.</b> Registered as a custom <i>pre</i> serializer ahead of the generic ones, so every
 * {@code LocalDate} passed to {@code setVariable} (untyped, for example a DMN {@code date} output) and every
 * {@link LocalDateValueType} typed value uses it; before decision 333 a top-level {@code LocalDate} had no serializer
 * (Java serialization is off). A {@code String} is never converted: a date stored as a string stays a string. An
 * untyped {@code null} is not taken (it stays Operaton's {@code null} type); a typed null date is stored as this type
 * with {@code TEXT_ NULL}.
 *
 * <p><b>Queries.</b> Format v1 is fixed-width with fixed separators, so the engine's {@code TEXT_} comparison is
 * chronological: equals, not equals, {@code >}, {@code >=}, {@code <} and {@code <=} with a {@code LocalDate} are
 * supported. {@code LIKE} / {@code NOT LIKE} are <b>refused</b> ({@code LOCAL_DATE_QUERY_OPERATOR}): a date is not text
 * to pattern-match. The operator is read from the query condition being filled, as {@link DecimalValueSerializer}
 * reads it (one reflective read, PATCHES.md B-37); if it cannot be read the query is refused, never run unchecked.
 *
 * <p><b>Placement and hosts.</b> As {@link DecimalValueSerializer}: in sangam-packs so pack test engines register it;
 * {@code SangamProcessEnginePlugin.preInit} registers it for every Sangam engine; a host that builds an engine
 * without the plugin calls {@link #register(ProcessEngineConfigurationImpl)} before the engine is built. Every engine
 * that reads these rows must register it under the same name.
 */
public final class LocalDateValueSerializer extends PrimitiveValueSerializer<PrimitiveValue<LocalDate>> {

    /** The persisted type id, versioned with the text format. Never reuse it for another format. */
    public static final String NAME = "sangam-local-date-v" + LocalDateText.FORMAT_VERSION;

    /** The query operators a local date supports: text order is chronological order. */
    public static final Set<QueryOperator> QUERY_OPERATORS = EnumSet.of(QueryOperator.EQUALS,
            QueryOperator.NOT_EQUALS, QueryOperator.GREATER_THAN, QueryOperator.GREATER_THAN_OR_EQUAL,
            QueryOperator.LESS_THAN, QueryOperator.LESS_THAN_OR_EQUAL);

    public LocalDateValueSerializer() {
        super(LocalDateValueType.LOCAL_DATE);
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
            pre.add(0, new LocalDateValueSerializer());
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
        if (v instanceof LocalDate) {
            return true;
        }
        // a typed null of this type; an untyped null stays Operaton's null type
        return v == null && value.getType() instanceof LocalDateValueType;
    }

    @Override
    public PrimitiveValue<LocalDate> convertToTypedValue(UntypedValueImpl untypedValue) {
        Object v = untypedValue.getValue();
        return LocalDateValueType.localDate(v == null ? null : LocalDateText.exact(v), untypedValue.isTransient());
    }

    @Override
    public void writeValue(PrimitiveValue<LocalDate> value, ValueFields valueFields) {
        refuseUnsupportedQuery(valueFields);
        LocalDate d = value.getValue();
        valueFields.setTextValue(d == null ? null : LocalDateText.format(d));
        valueFields.setTextValue2(null);
        valueFields.setLongValue(null);
        valueFields.setDoubleValue(null);
        valueFields.setByteArrayValue(null);
    }

    @Override
    public PrimitiveValue<LocalDate> readValue(ValueFields valueFields, boolean asTransientValue) {
        String text = valueFields.getTextValue();
        return LocalDateValueType.localDate(text == null ? null : LocalDateText.parse(text), asTransientValue);
    }

    /** Refuses a pattern query (or an unreadable operator) on a local date (see the class comment). */
    static void refuseUnsupportedQuery(ValueFields valueFields) {
        if (!(valueFields instanceof AbstractQueryVariableValueCondition condition)) {
            return;
        }
        QueryOperator op = DecimalValueSerializer.operatorOf(condition);
        if (op == null || !QUERY_OPERATORS.contains(op)) {
            throw new LocalDateText.LocalDateFormatException("LOCAL_DATE_QUERY_OPERATOR", "a date variable can be queried"
                    + " with equals, not equals, >, >=, < and <= only; operator " + op + " is not a date comparison");
        }
    }
}
