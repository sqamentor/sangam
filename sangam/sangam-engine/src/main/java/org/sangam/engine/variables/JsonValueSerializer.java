// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.variables;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.operaton.bpm.engine.impl.variable.serializer.AbstractObjectValueSerializer;

/**
 * JSON object variables without Java serialization (FC-28, D-47, R11, R33). Maps and lists of JSON-compatible values
 * (String, Boolean, numbers, nested maps/lists, null) are stored as UTF-8 JSON in the variable's byte array with
 * data format {@value #FORMAT}; they read back as {@code LinkedHashMap} / {@code ArrayList}. Any other object type is
 * refused (Java serialization stays disabled, D-47), so no class name from the database is ever instantiated.
 *
 * <p>R11 limits: nesting depth 64, string length 10 MiB, document size 16 MiB; default typing is never enabled.
 * R33: decimals read as {@link BigDecimal} (money is never a binary float); integers as Integer/Long, or
 * {@link BigInteger} beyond 64 bits.
 */
public class JsonValueSerializer extends AbstractObjectValueSerializer {

    public static final String NAME = "sangam-json";
    public static final String FORMAT = "application/json";
    private static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;

    private static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64).maxStringLength(10 * 1024 * 1024).build())
                    .build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
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
        byte[] b = MAPPER.writeValueAsBytes(deserializedObject);
        if (b.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("R11: JSON variable larger than " + MAX_DOCUMENT_BYTES + " bytes");
        }
        return b;
    }

    @Override
    protected Object deserializeFromByteArray(byte[] object, String objectTypeName) throws Exception {
        if ("java.util.Map".equals(objectTypeName)) {
            return MAPPER.readValue(object, LinkedHashMap.class);
        }
        if ("java.util.List".equals(objectTypeName)) {
            return MAPPER.readValue(object, ArrayList.class);
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
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Number) {
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
}
