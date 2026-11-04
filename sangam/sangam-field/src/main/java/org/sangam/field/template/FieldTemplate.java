// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.template;

import org.sangam.field.FieldException;

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
     */
    public record Item(String id, ItemType type, Map<String, String> label, boolean required, List<Option> options, Double weight,
                       boolean critical, int minPhotos, VisibleIf visibleIf, Double min, Double max, Object expected,
                       String findingCode, String severity, Map<String, Object> attributes) {

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

    /** Label in a language, falling back to the first label present. */
    public static String label(Map<String, String> labels, String lang) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        String v = lang == null ? null : labels.get(lang);
        return v != null ? v : labels.values().iterator().next();
    }
}
