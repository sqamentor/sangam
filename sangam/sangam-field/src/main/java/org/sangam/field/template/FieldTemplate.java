// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.sangam.field.FieldException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An inspection / verification / survey template defined entirely in configuration (N05 {@code Checklist} /
 * {@code FormVersion}, C03 forms flagged offline-capable). Every label is an i18n map (language tag → text); the
 * module never interprets the domain meaning of an item.
 *
 * <pre>{@code
 * { "key": "site-visit", "title": {"en": "Site visit"}, "offlineCapable": true,
 *   "sections": [ { "id": "s1", "title": {"en": "General"}, "items": [
 *       { "id": "q1", "type": "BOOLEAN", "label": {"en": "Signage displayed"}, "required": true, "weight": 2, "critical": false },
 *       { "id": "p1", "type": "PHOTO", "label": {"en": "Front photo"}, "minPhotos": 1, "required": true } ] } ],
 *   "scoring": { "method": "WEIGHTED_SUM", "grades": [ {"code": "A", "minScore": 85}, {"code": "B", "minScore": 60}, {"code": "C", "minScore": 0} ],
 *                "criticalFailGrade": "FAIL" },
 *   "geofence": { "radiusMeters": 200, "toleranceMeters": 10, "maxAccuracyMeters": 50, "required": true },
 *   "evidence": { "maxPhotoAgeMinutes": 120, "maxClockSkewSeconds": 300, "exifRequired": false, "exifMaxDistanceM": 100,
 *                 "rejectMockLocation": true, "rejectDuplicatePhoto": true } }
 * }</pre>
 */
public record FieldTemplate(String key, int version, Map<String, String> title, boolean offlineCapable, List<Section> sections,
                            Scoring scoring, GeofenceRule geofence, EvidenceRules evidence, Map<String, Object> attributes) {

    public enum ItemType { BOOLEAN, CHOICE, MULTI_CHOICE, NUMBER, TEXT, DATE, PHOTO, SIGNATURE, GEO_POINT }

    public enum Method { WEIGHTED_SUM, DMN, NONE }

    public record Option(String value, Map<String, String> label, Double score) {
    }

    /** Show the item only when another item's answer equals {@code equals} (or is in {@code in}). */
    public record VisibleIf(String item, Object equals, List<Object> in) {
    }

    /**
     * @param expected     the compliant answer (BOOLEAN default true; CHOICE an option value)
     * @param findingCode  code of the finding raised when the item is non-compliant (data, feeds penalty DMN / CAPA)
     * @param severity     finding severity (data: e.g. MINOR / MAJOR); critical items default to CRITICAL
     * @param min          inclusive lower bound of a NUMBER answer, an exact decimal (owner decision 230), see {@link Bound}
     * @param max          inclusive upper bound of a NUMBER answer, an exact decimal (owner decision 230), see {@link Bound}
     * @param scale        NUMBER only, optional (owner decision 242): the most decimal places a NUMBER answer and the
     *                     bounds may have, {@value #MIN_SCALE}..{@value #MAX_SCALE}; null = no extra limit. Written only
     *                     when set, so a template without it keeps its content hash
     */
    public record Item(String id, ItemType type, Map<String, String> label, boolean required, List<Option> options, Double weight,
                       boolean critical, int minPhotos, VisibleIf visibleIf,
                       @JsonSerialize(using = Bound.Writer.class) @JsonDeserialize(using = Bound.Reader.class) BigDecimal min,
                       @JsonSerialize(using = Bound.Writer.class) @JsonDeserialize(using = Bound.Reader.class) BigDecimal max,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer scale,
                       Object expected, String findingCode, String severity, Map<String, Object> attributes) {

        /** Owner decision 242: the allowed range of {@link #scale()}. */
        public static final int MIN_SCALE = 0;
        public static final int MAX_SCALE = 10;

        public Item {
            // one value, one form: 12.50 and 12.5 are the same bound and hash the same
            min = Bound.normalise(min);
            max = Bound.normalise(max);
        }

        /**
         * Owner decision 242: true when {@code d} has no more decimal places than {@link #scale()} allows (always true
         * without a scale). Decimal places are counted on the canonical form, so {@code 12.50} counts as {@code 12.5}.
         */
        public boolean withinScale(BigDecimal d) {
            return scale == null || d == null || decimalPlaces(d) <= scale;
        }

        /** Decimal places of the canonical form of {@code d}: {@code 12.50} has 1, {@code 100} and {@code 0} have 0. */
        public static int decimalPlaces(BigDecimal d) {
            return Math.max(0, Bound.normalise(d).scale());
        }

        public double weightOrDefault() {
            if (weight != null) {
                return weight;
            }
            return switch (type) {
                case BOOLEAN, CHOICE, MULTI_CHOICE, NUMBER -> 1;
                default -> 0;
            };
        }

        public boolean scored() {
            return weightOrDefault() > 0 && switch (type) {
                case BOOLEAN, CHOICE, MULTI_CHOICE, NUMBER -> true;
                default -> false;
            };
        }
    }

    /**
     * Owner decision 230: a NUMBER bound is an exact decimal. It is written as canonical decimal text (a JSON string,
     * {@code stripTrailingZeros().toPlainString()}, zero as {@code "0"}: the form decision 212 uses for answers), so the
     * stored template and its content hash never depend on a binary double. It is read from a JSON number (exactly as
     * written, from its text) or from canonical decimal text; other text ({@code "12.50"}, {@code "+3"}, {@code "1E+2"},
     * {@code "abc"}) is refused. A bound that reached the server as a binary double (a body already parsed into a map)
     * keeps that double's shortest decimal form; authors who need more digits send the bound as text.
     */
    public static final class Bound {

        private Bound() {
        }

        static BigDecimal normalise(BigDecimal d) {
            return d == null ? null : d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
        }

        /** Canonical decimal text of a bound (same as {@link ChecklistValidator#canonical(BigDecimal)}). */
        public static String text(BigDecimal d) {
            return ChecklistValidator.canonical(d);
        }

        /** The bound written as {@code text}, or an error when the text is not canonical decimal text. */
        public static BigDecimal parse(String text) {
            BigDecimal d;
            try {
                d = new BigDecimal(text);
            } catch (NumberFormatException | NullPointerException e) {
                throw new IllegalArgumentException("bound is not a decimal: \"" + text + "\"");
            }
            if (!text.equals(text(d))) {
                throw new IllegalArgumentException("bound text not canonical: \"" + text + "\", expected \"" + text(d) + "\"");
            }
            return d;
        }

        public static final class Writer extends JsonSerializer<BigDecimal> {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(text(value));
            }
        }

        public static final class Reader extends JsonDeserializer<BigDecimal> {
            @Override
            public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                JsonToken t = p.currentToken();
                if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
                    return p.getDecimalValue();
                }
                if (t == JsonToken.VALUE_STRING) {
                    try {
                        return parse(p.getText());
                    } catch (IllegalArgumentException e) {
                        return (BigDecimal) ctxt.handleWeirdStringValue(BigDecimal.class, p.getText(), e.getMessage());
                    }
                }
                return (BigDecimal) ctxt.handleUnexpectedToken(BigDecimal.class, p);
            }
        }
    }

    public record Section(String id, Map<String, String> title, List<Item> items) {
        public Section {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record Grade(String code, double minScore) {
    }

    /**
     * @param criticalFailGrade grade forced when a critical item is non-compliant (null = no override)
     * @param passScore         minimum score for {@code passed=true} (default 50)
     */
    public record Scoring(Method method, String decisionKey, List<Grade> grades, String criticalFailGrade, Double passScore) {
    }

    /** Fence for the visit: a circle around the target, a stored feature, or a stored geo fence id. */
    public record GeofenceRule(Double radiusMeters, String featureRef, String fenceId, Double toleranceMeters,
                               Double maxAccuracyMeters, boolean required) {
    }

    public record EvidenceRules(Integer maxPhotoAgeMinutes, Integer maxClockSkewSeconds, boolean exifRequired,
                                Double exifMaxDistanceM, boolean rejectMockLocation, boolean rejectDuplicatePhoto,
                                Double maxAccuracyMeters) {
        public static EvidenceRules lenient() {
            return new EvidenceRules(null, null, false, null, false, false, null);
        }
    }

    public FieldTemplate {
        if (key == null || key.isBlank()) {
            throw new FieldException("INVALID_TEMPLATE", "template key is required");
        }
        sections = sections == null ? List.of() : List.copyOf(sections);
        title = title == null ? Map.of() : Map.copyOf(title);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        evidence = evidence == null ? EvidenceRules.lenient() : evidence;
        scoring = scoring == null ? new Scoring(Method.WEIGHTED_SUM, null, List.of(), null, null) : scoring;
    }

    public FieldTemplate withVersion(int v) {
        return new FieldTemplate(key, v, title, offlineCapable, sections, scoring, geofence, evidence, attributes);
    }

    public List<Item> items() {
        List<Item> all = new ArrayList<>();
        sections.forEach(s -> all.addAll(s.items()));
        return all;
    }

    public Item item(String id) {
        return items().stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
    }

    /** Structural validation at publish time. */
    public void validateDefinition() {
        Set<String> ids = new HashSet<>();
        for (Item i : items()) {
            if (i.id() == null || i.id().isBlank() || !ids.add(i.id())) {
                throw new FieldException("INVALID_TEMPLATE", "item ids must be present and unique: " + i.id());
            }
            if (i.type() == null) {
                throw new FieldException("INVALID_TEMPLATE", "item type is required: " + i.id());
            }
            if ((i.type() == ItemType.CHOICE || i.type() == ItemType.MULTI_CHOICE) && (i.options() == null || i.options().isEmpty())) {
                throw new FieldException("INVALID_TEMPLATE", "choice item needs options: " + i.id());
            }
            validateScale(i);
        }
        for (Item i : items()) {
            if (i.visibleIf() != null && !ids.contains(i.visibleIf().item())) {
                throw new FieldException("INVALID_TEMPLATE", "visibleIf refers to an unknown item: " + i.id());
            }
        }
        if (scoring.method() == Method.DMN && (scoring.decisionKey() == null || scoring.decisionKey().isBlank())) {
            throw new FieldException("INVALID_TEMPLATE", "DMN scoring needs a decisionKey");
        }
    }

    /**
     * Owner decision 242: a scale is a NUMBER-only setting in {@value Item#MIN_SCALE}..{@value Item#MAX_SCALE}, and the
     * bounds may not have more decimal places than it allows.
     */
    private static void validateScale(Item i) {
        if (i.scale() == null) {
            return;
        }
        if (i.type() != ItemType.NUMBER) {
            throw new FieldException("INVALID_TEMPLATE", "scale is only for NUMBER items: " + i.id());
        }
        if (i.scale() < Item.MIN_SCALE || i.scale() > Item.MAX_SCALE) {
            throw new FieldException("INVALID_TEMPLATE", "scale must be " + Item.MIN_SCALE + ".." + Item.MAX_SCALE
                    + ": " + i.id() + " has " + i.scale());
        }
        if (!i.withinScale(i.min())) {
            throw new FieldException("INVALID_TEMPLATE", "min " + Bound.text(i.min()) + " has more than " + i.scale()
                    + " decimal places: " + i.id());
        }
        if (!i.withinScale(i.max())) {
            throw new FieldException("INVALID_TEMPLATE", "max " + Bound.text(i.max()) + " has more than " + i.scale()
                    + " decimal places: " + i.id());
        }
    }

    /** Label in a language, falling back to the first label present. */
    public static String label(Map<String, String> labels, String lang) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        String v = lang == null ? null : labels.get(lang);
        return v != null ? v : labels.values().iterator().next();
    }
}
