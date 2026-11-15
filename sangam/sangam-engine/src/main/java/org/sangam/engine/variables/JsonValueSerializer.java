// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.variables;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.AbstractObjectValueSerializer;
import org.sangam.packs.engine.LocalDateText;

/**
 * JSON object variables without Java serialization (FLOWABLE-INTO-OPERATON FC-28, FLOWABLE-INTO-OPERATON D-47, R11, R33). Maps and lists of JSON-compatible values
 * (String, Boolean, numbers, nested maps/lists, null) are stored as UTF-8 JSON in the variable's byte array with
 * data format {@value #FORMAT}; they read back as {@code LinkedHashMap} / {@code ArrayList}. Any other object type is
 * refused (Java serialization stays disabled, FLOWABLE-INTO-OPERATON D-47), so no class name from the database is ever instantiated.
 *
 * <p>R11 limits: nesting depth 64, string length 10 MiB, document size 16 MiB; default typing is never enabled.
 * R33: decimals read as {@link BigDecimal} (money is never a binary float); integers as Integer/Long, or
 * {@link BigInteger} beyond 64 bits.
 *
 * <p>Decision 333: a {@link LocalDate} inside a map or list is carried as the R33 typed JSON object
 * {@code {"type":"LocalDate","value":"2026-03-31"}} ({@link LocalDateText} format v1, never a bare string) and reads
 * back as a {@code LocalDate}. On read, an object with exactly the two members {@code type} = {@value #LOCAL_DATE_TYPE}
 * and {@code value} is that typed date (the R33 typed-value shape, as the REST layer reads it); its text must be format
 * v1, else the read is refused ({@code LOCAL_DATE_INVALID}). The same shape handed in as a plain map is checked the
 * same way on write, so nothing is stored that cannot be read back. A date outside years 0000-9999 is refused
 * ({@code LOCAL_DATE_OUT_OF_RANGE}). Every other leaf keeps its JSON form (a nested {@code BigDecimal} stays a JSON
 * number).
 */
public class JsonValueSerializer extends AbstractObjectValueSerializer {

    public static final String NAME = "sangam-json";
    public static final String FORMAT = "application/json";
    private static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    /** The R33 type name of a nested local date. */
    public static final String LOCAL_DATE_TYPE = "LocalDate";

    private static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64).maxStringLength(10 * 1024 * 1024).build())
                    .build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .addModule(new SimpleModule("sangam-json-local-date").addSerializer(LocalDate.class, new TypedLocalDate()))
            .build();

    public JsonValueSerializer() {
        super(FORMAT);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected String getTypeNameForDeserialized(Object deserializedObject) {
        return deserializedObject instanceof Map ? "java.util.Map" : "java.util.List";
    }

    @Override
    protected byte[] serializeToByteArray(Object deserializedObject) throws Exception {
        typedLeaves(deserializedObject, 0, false);
        byte[] b = MAPPER.writeValueAsBytes(deserializedObject);
        if (b.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("R11: JSON variable larger than " + MAX_DOCUMENT_BYTES + " bytes");
        }
        return b;
    }

    @Override
    protected Object deserializeFromByteArray(byte[] object, String objectTypeName) throws Exception {
        if ("java.util.Map".equals(objectTypeName)) {
            return typedLeaves(MAPPER.readValue(object, LinkedHashMap.class), 0, true);
        }
        if ("java.util.List".equals(objectTypeName)) {
            return typedLeaves(MAPPER.readValue(object, ArrayList.class), 0, true);
        }
        throw new IllegalArgumentException("sangam-json: unsupported stored type " + objectTypeName);
    }

    @Override
    protected boolean isSerializationTextBased() {
        return true;
    }

    @Override
    protected boolean canSerializeValue(Object value) {
        return (value instanceof Map || value instanceof List) && jsonCompatible(value, 0);
    }

    private static boolean jsonCompatible(Object v, int depth) {
        if (depth > 64) {
            return false;
        }
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Number || v instanceof LocalDate) {
            return true;
        }
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String) || !jsonCompatible(e.getValue(), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (!jsonCompatible(o, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** True for the R33 typed local-date shape: exactly {@code type} = {@value #LOCAL_DATE_TYPE} and {@code value}. */
    static boolean isTypedLocalDate(Map<?, ?> m) {
        return m.size() == 2 && LOCAL_DATE_TYPE.equals(m.get("type")) && m.containsKey("value");
    }

    /**
     * Walks a JSON tree. On write ({@code read == false}) it checks every typed local-date shape (its text must be
     * format v1) and every {@link LocalDate} leaf (its year must be in range); on read it replaces every typed
     * local-date shape by its {@link LocalDate}. Returns the (possibly replaced) node.
     */
    @SuppressWarnings("unchecked")
    static Object typedLeaves(Object v, int depth, boolean read) {
        if (depth > 64) {
            throw new IllegalArgumentException("R11: JSON variable nested deeper than 64");
        }
        if (v instanceof LocalDate d) {
            LocalDateText.format(d);
            return d;
        }
        if (v instanceof Map<?, ?> m) {
            if (isTypedLocalDate(m)) {
                Object text = m.get("value");
                if (!(text instanceof String s)) {
                    throw new LocalDateText.LocalDateFormatException("LOCAL_DATE_INVALID", "a typed LocalDate needs"
                            + " ISO text (uuuu-MM-dd) as its value, not " + (text == null ? "null" : text.getClass().getName()));
                }
                LocalDate d = LocalDateText.parse(s);
                return read ? d : m;
            }
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) m).entrySet()) {
                Object x = typedLeaves(e.getValue(), depth + 1, read);
                if (read && x != e.getValue()) {
                    e.setValue(x);
                }
            }
            return m;
        }
        if (v instanceof List<?> l) {
            List<Object> list = (List<Object>) l;
            for (int i = 0; i < list.size(); i++) {
                Object x = typedLeaves(list.get(i), depth + 1, read);
                if (read && x != list.get(i)) {
                    list.set(i, x);
                }
            }
            return l;
        }
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                typedLeaves(o, depth + 1, read);
            }
        }
        return v;
    }

    /** Writes a {@link LocalDate} as {@code {"type":"LocalDate","value":"uuuu-MM-dd"}}. */
    private static final class TypedLocalDate extends StdSerializer<LocalDate> {
        TypedLocalDate() {
            super(LocalDate.class);
        }

        @Override
        public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider provider) throws java.io.IOException {
            gen.writeStartObject();
            gen.writeStringField("type", LOCAL_DATE_TYPE);
            gen.writeStringField("value", LocalDateText.format(value));
            gen.writeEndObject();
        }
    }
}