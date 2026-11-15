// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.variables;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.sangam.core.layer.Jdbc;
import org.sangam.packs.engine.DecimalText;
import org.sangam.packs.engine.LocalDateText;

/**
 * Decision 353 inventory, in the style of {@link DoubleVariableInventory}: which stored JSON variables
 * ({@value JsonValueSerializer#NAME}) hold an object of exactly the R33 typed shape {@code {"type":"LocalDate","value":...}}
 * or {@code {"type":"Decimal","value":...}} anywhere in their document, grouped by tenant, process definition key,
 * variable name and finding, in the runtime ({@code ACT_RU_VARIABLE}) and in history ({@code ACT_HI_VARINST}).
 *
 * <p>Why: {@link JsonValueSerializer} reads such an object as a {@code LocalDate} (decision 333, 353) or a
 * {@code BigDecimal} (decision 138, G24), so a map that a caller once stored with that literal shape now reads back as
 * a date or a decimal, and one whose text is not valid is refused on read. A row listed here may equally be one written
 * after those decisions from a real {@code LocalDate} / {@code BigDecimal}; the stored document does not tell the two
 * apart, so each listed variable is for its owner to review. Read-only: it changes nothing and converts nothing, and it
 * reads the stored bytes as plain JSON, never through the serializer.
 */
public final class TypedJsonShapeInventory {

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_.]*");
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64).maxStringLength(10 * 1024 * 1024).build())
                    .build())
            .build();

    /** Where a row was found. */
    public enum Source { RUNTIME, HISTORY }

    /** What a row holds. A row is counted once per finding it holds. */
    public enum Finding {
        /** A typed local-date shape whose value is format v1 text: reads as a {@code LocalDate}. */
        LOCAL_DATE,
        /** A typed local-date shape whose value is not format v1 text: the read is refused. */
        LOCAL_DATE_INVALID,
        /** A typed decimal shape whose value is decimal text: reads as a {@code BigDecimal}. */
        DECIMAL,
        /** A typed decimal shape whose value is not decimal text: the read is refused. */
        DECIMAL_INVALID,
        /** A document that is not readable JSON (or exceeds the R11 limits): the read is refused. */
        UNREADABLE
    }

    /** One group: {@code count} rows named {@code variableName} of definition {@code definitionKey} with {@code finding}. */
    public record Entry(Source source, String tenantId, String definitionKey, String variableName, Finding finding,
                        long count) {
    }

    private record Row(String tenantId, String definitionKey, String variableName, byte[] bytes) {
    }

    private TypedJsonShapeInventory() {
    }

    /**
     * Lists the JSON variables on {@code c} that hold a typed shape. {@code tablePrefix} is the engine's table prefix
     * ("" or a schema prefix such as {@code "ENGINE."}); only letters, digits, {@code _} and {@code .} are accepted.
     * Variables outside a process have a {@code null} definition key.
     */
    public static List<Entry> scan(Connection c, String tablePrefix) {
        String p = tablePrefix == null ? "" : tablePrefix;
        if (!PREFIX.matcher(p).matches()) {
            throw new IllegalArgumentException("table prefix may hold only letters, digits, '_' and '.'");
        }
        List<Entry> out = new ArrayList<>();
        out.addAll(group(Source.RUNTIME, Jdbc.query(c, "SELECT v.TENANT_ID_, d.KEY_, v.NAME_, b.BYTES_ FROM " + p
                        + "ACT_RU_VARIABLE v JOIN " + p + "ACT_GE_BYTEARRAY b ON b.ID_ = v.BYTEARRAY_ID_"
                        + " LEFT JOIN " + p + "ACT_RE_PROCDEF d ON d.ID_ = v.PROC_DEF_ID_ WHERE v.TYPE_ = ?",
                rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBytes(4)),
                JsonValueSerializer.NAME)));
        out.addAll(group(Source.HISTORY, Jdbc.query(c, "SELECT h.TENANT_ID_, h.PROC_DEF_KEY_, h.NAME_, b.BYTES_ FROM " + p
                        + "ACT_HI_VARINST h JOIN " + p + "ACT_GE_BYTEARRAY b ON b.ID_ = h.BYTEARRAY_ID_"
                        + " WHERE h.VAR_TYPE_ = ?",
                rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBytes(4)),
                JsonValueSerializer.NAME)));
        return List.copyOf(out);
    }

    private static List<Entry> group(Source source, List<Row> rows) {
        Map<List<Object>, Long> counts = new LinkedHashMap<>();
        for (Row r : rows) {
            for (Finding f : findings(r.bytes())) {
                counts.merge(java.util.Arrays.asList(r.tenantId(), r.definitionKey(), r.variableName(), f), 1L, Long::sum);
            }
        }
        List<Entry> out = new ArrayList<>();
        counts.forEach((k, n) -> out.add(new Entry(source, (String) k.get(0), (String) k.get(1), (String) k.get(2),
                (Finding) k.get(3), n)));
        out.sort(java.util.Comparator.comparing(Entry::tenantId, java.util.Comparator.nullsFirst(String::compareTo))
                .thenComparing(Entry::definitionKey, java.util.Comparator.nullsFirst(String::compareTo))
                .thenComparing(Entry::variableName, java.util.Comparator.nullsFirst(String::compareTo))
                .thenComparing(Entry::finding));
        return out;
    }

    /** The findings of one stored document (empty when it holds no typed shape). */
    static Set<Finding> findings(byte[] document) {
        Set<Finding> out = new TreeSet<>();
        JsonNode root;
        try {
            root = document == null ? null : JSON.readTree(document);
        } catch (java.io.IOException | RuntimeException e) {
            out.add(Finding.UNREADABLE);
            return out;
        }
        walk(root, out);
        return out;
    }

    private static void walk(JsonNode n, Set<Finding> out) {
        if (n == null) {
            return;
        }
        if (n.isObject()) {
            if (n.size() == 2 && n.has("value") && n.path("type").isTextual()) {
                String type = n.get("type").asText();
                if (JsonValueSerializer.LOCAL_DATE_TYPE.equals(type)) {
                    out.add(validDate(n.get("value")) ? Finding.LOCAL_DATE : Finding.LOCAL_DATE_INVALID);
                    return;
                }
                if (JsonValueSerializer.DECIMAL_TYPE.equals(type)) {
                    out.add(validDecimal(n.get("value")) ? Finding.DECIMAL : Finding.DECIMAL_INVALID);
                    return;
                }
            }
            n.elements().forEachRemaining(x -> walk(x, out));
        } else if (n.isArray()) {
            n.elements().forEachRemaining(x -> walk(x, out));
        }
    }

    private static boolean validDate(JsonNode v) {
        if (!v.isTextual()) {
            return false;
        }
        try {
            LocalDateText.parse(v.asText());
            return true;
        } catch (LocalDateText.LocalDateFormatException e) {
            return false;
        }
    }

    private static boolean validDecimal(JsonNode v) {
        if (!v.isTextual()) {
            return false;
        }
        try {
            DecimalText.parse(v.asText());
            return true;
        } catch (DecimalText.DecimalFormatException e) {
            return false;
        }
    }
}
