// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.variables;

import java.nio.charset.StandardCharsets;

import org.operaton.bpm.engine.impl.variable.serializer.PrimitiveValueSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.ValueFields;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.impl.value.UntypedValueImpl;
import org.operaton.bpm.engine.variable.type.ValueType;
import org.operaton.bpm.engine.variable.value.StringValue;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Long text variables (D-07, FC-29, GP-38): Operaton stores strings in {@code TEXT_}, which is {@code varchar(4000)}
 * on PostgreSQL/MySQL, {@code nvarchar(4000)} on SQL Server and <b>{@code NVARCHAR2(2000)} on Oracle</b>; GP-38
 * failed with a PSQLException at 5,590 characters. This pre-serializer (registered first, so it wins over Operaton's
 * {@code StringValueSerializer}) stores a string above the database's limit in the variable's byte array
 * ({@code ACT_GE_BYTEARRAY}) and hands back an ordinary {@link StringValue}: callers see type {@code String}.
 * It is chosen automatically per value; short strings keep the normal serializer and stay queryable by value.
 *
 * <p>Limit: a long string is not queryable by value (the same on Flowable's {@code longString}). The serializer name
 * {@value #NAME} is stored in {@code TYPE_}, so every engine reading these rows must register this serializer.
 */
public class LargeStringValueSerializer extends PrimitiveValueSerializer<StringValue> {

    public static final String NAME = "sangam-large-string";

    private final int thresholdChars;

    public LargeStringValueSerializer(int thresholdChars) {
        super(ValueType.STRING);
        this.thresholdChars = thresholdChars;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected boolean canWriteValue(TypedValue value) {
        return value != null && value.getValue() instanceof String s && s.length() > thresholdChars;
    }

    @Override
    public StringValue convertToTypedValue(UntypedValueImpl untypedValue) {
        return Variables.stringValue((String) untypedValue.getValue(), untypedValue.isTransient());
    }

    @Override
    public void writeValue(StringValue value, ValueFields valueFields) {
        String s = value.getValue();
        valueFields.setTextValue(null);
        valueFields.setByteArrayValue(s == null ? null : s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public StringValue readValue(ValueFields valueFields, boolean asTransientValue) {
        byte[] bytes = valueFields.getByteArrayValue();
        return Variables.stringValue(bytes == null ? null : new String(bytes, StandardCharsets.UTF_8), asTransientValue);
    }

    public int thresholdChars() {
        return thresholdChars;
    }
}
