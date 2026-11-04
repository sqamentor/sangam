// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.boundary;

import org.sangam.geo.GeoException;

import java.util.List;
import java.util.Map;

/**
 * A jurisdiction / boundary layer, defined entirely by data: any country's administrative tiers, service territories,
 * beats, delivery zones, collection territories, protected areas. A layer has an ordered list of levels (root first);
 * a feature at level i has a parent at level i-1 in the same layer. Flat layers have one level.
 *
 * @param kind          ADMIN (hierarchical jurisdiction), ZONE (service/delivery territories), PROTECTED (overlap
 *                      checks), FENCE (geofence library), REFERENCE (points of interest) — free text, data-defined
 * @param names         language tag → display name
 * @param orgUnitLinked true when features carry an org-unit reference ("office by location", C04)
 * @param indexPrecision optional fixed geohash precision for the portable index (0 = adaptive)
 */
public record JurisdictionLayer(String code, String kind, Map<String, String> names, List<BoundaryLevel> levels,
                                boolean orgUnitLinked, int indexPrecision, Map<String, Object> attributes) {

    public JurisdictionLayer {
        if (code == null || !code.matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new GeoException("INVALID_LAYER", "layer code must be 1-64 chars [A-Za-z0-9_.:-]");
        }
        kind = kind == null || kind.isBlank() ? "ADMIN" : kind;
        names = names == null ? Map.of() : Map.copyOf(names);
        levels = levels == null || levels.isEmpty() ? List.of(new BoundaryLevel(code, 0, Map.of(), null)) : List.copyOf(levels);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        indexPrecision = Math.max(0, Math.min(12, indexPrecision));
    }

    public BoundaryLevel level(String levelCode) {
        return levels.stream().filter(l -> l.code().equals(levelCode)).findFirst()
                .orElseThrow(() -> new GeoException("UNKNOWN_LEVEL", "level " + levelCode + " not in layer " + code));
    }

    public int depthOf(String levelCode) {
        return level(levelCode).depth();
    }

    public BoundaryLevel deepest() {
        return levels.stream().max((a, b) -> Integer.compare(a.depth(), b.depth())).orElseThrow();
    }
}
