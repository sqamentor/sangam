// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.boundary;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.geo.GeoException;
import org.sangam.geo.model.GeoJson;
import org.sangam.geo.model.Geometry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a boundary data package (any country, any hierarchy) into a layer definition plus feature inputs, ordered
 * parents first. Format:
 * <pre>
 * {
 *   "layer": { "code": "admin", "kind": "ADMIN", "names": {"en": "Administrative units"}, "orgUnitLinked": true,
 *              "levels": [ {"code": "L1", "names": {"en": "Region"}}, {"code": "L2", "names": {"en": "District"}} ] },
 *   "codeProperty": "code", "levelProperty": "level", "parentProperty": "parent", "nameProperties": {"en": "name"},
 *   "orgUnitProperty": "orgUnit",
 *   "features": { "type": "FeatureCollection", "features": [ ... GeoJSON ... ] }
 * }
 * </pre>
 * Property names are configurable so an upstream GIS export can be loaded without editing it. A feature's parent is
 * given by parent code; codes are unique within a level.
 */
public final class BoundaryDataLoader {

    private BoundaryDataLoader() {
    }

    /** One feature to store; {@code parentCode} is resolved against the level above at import time. */
    public record FeatureInput(String level, String code, String parentCode, Map<String, String> names,
                               Map<String, Object> properties, Geometry geometry, String orgUnitRef) {
        public FeatureInput {
            names = names == null ? Map.of() : Map.copyOf(names);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    public record Package(JurisdictionLayer layer, List<FeatureInput> features) {
    }

    public static Package read(String json) {
        try {
            return read(GeoJson.MAPPER.readTree(json));
        } catch (GeoException e) {
            throw e;
        } catch (Exception e) {
            throw new GeoException("INVALID_BOUNDARY_DATA", "cannot read boundary package: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Package read(JsonNode root) {
        JurisdictionLayer layer = root.hasNonNull("layer") ? layer(root.get("layer")) : null;
        String codeProp = root.path("codeProperty").asText("code");
        String levelProp = root.path("levelProperty").asText("level");
        String parentProp = root.path("parentProperty").asText("parent");
        String orgProp = root.path("orgUnitProperty").asText("orgUnit");
        Map<String, String> nameProps = root.hasNonNull("nameProperties")
                ? GeoJson.MAPPER.convertValue(root.get("nameProperties"), Map.class) : Map.of("en", "name");
        String defaultLevel = layer == null ? null : layer.levels().get(0).code();
        List<FeatureInput> out = new ArrayList<>();
        JsonNode fc = root.has("features") ? root.get("features") : root;
        for (GeoJson.Feature f : GeoJson.features(fc)) {
            String code = f.prop(codeProp);
            if (code == null) {
                code = f.id();
            }
            if (code == null || code.isBlank()) {
                throw new GeoException("INVALID_BOUNDARY_DATA", "feature without '" + codeProp + "'");
            }
            String level = f.prop(levelProp) == null ? defaultLevel : f.prop(levelProp);
            Map<String, String> names = new LinkedHashMap<>();
            nameProps.forEach((lang, prop) -> {
                String v = f.prop(prop);
                if (v != null) {
                    names.put(lang, v);
                }
            });
            Map<String, Object> props = new LinkedHashMap<>(f.properties());
            props.remove(codeProp);
            props.remove(levelProp);
            props.remove(parentProp);
            props.remove(orgProp);
            nameProps.values().forEach(props::remove);
            out.add(new FeatureInput(level, code, f.prop(parentProp), names, props, f.geometry(), f.prop(orgProp)));
        }
        if (layer != null) {
            out.sort((a, b) -> Integer.compare(depth(layer, a.level()), depth(layer, b.level())));
        }
        return new Package(layer, out);
    }

    private static int depth(JurisdictionLayer layer, String level) {
        return level == null ? 0 : layer.levels().stream().filter(l -> l.code().equals(level)).map(BoundaryLevel::depth)
                .findFirst().orElse(Integer.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    public static JurisdictionLayer layer(JsonNode n) {
        List<BoundaryLevel> levels = new ArrayList<>();
        JsonNode ls = n.path("levels");
        String prev = null;
        int depth = 0;
        for (JsonNode l : ls) {
            String code = l.path("code").asText();
            Map<String, String> names = l.hasNonNull("names") ? GeoJson.MAPPER.convertValue(l.get("names"), Map.class) : Map.of();
            levels.add(new BoundaryLevel(code, depth++, names, prev));
            prev = code;
        }
        Map<String, String> names = n.hasNonNull("names") ? GeoJson.MAPPER.convertValue(n.get("names"), Map.class) : Map.of();
        Map<String, Object> attrs = n.hasNonNull("attributes") ? GeoJson.MAPPER.convertValue(n.get("attributes"), Map.class) : Map.of();
        return new JurisdictionLayer(n.path("code").asText(null), n.path("kind").asText("ADMIN"), names, levels,
                n.path("orgUnitLinked").asBoolean(false), n.path("indexPrecision").asInt(0), attrs);
    }
}
