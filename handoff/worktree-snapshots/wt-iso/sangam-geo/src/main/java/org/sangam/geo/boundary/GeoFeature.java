// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.boundary;

import org.sangam.geo.model.BBox;
import org.sangam.geo.model.GeoJson;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geometry;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stored feature of a layer: a boundary polygon, a zone, a fence, a reference point. Versioned (C05 style): a new
 * geometry for the same {@code code} closes the previous version ({@code validTo}) and creates a new id.
 *
 * @param id         stable version id ({@code layer:code:vN})
 * @param parentId   id of the parent feature (hierarchy), may be null
 * @param orgUnitRef link to an org unit so "office by location" works (C04)
 */
public record GeoFeature(String id, String layer, String level, String code, String parentId, Map<String, String> names,
                         Map<String, Object> properties, Geometry geometry, BBox bbox, GeoPoint centroid, String geohash,
                         String orgUnitRef, int version, Instant validFrom, Instant validTo) {

    public GeoFeature {
        names = names == null ? Map.of() : Map.copyOf(names);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public boolean activeAt(Instant at) {
        return (validFrom == null || !at.isBefore(validFrom)) && (validTo == null || at.isBefore(validTo));
    }

    /** View without geometry coordinates (lists, FEEL results). */
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("layer", layer);
        m.put("level", level);
        m.put("code", code);
        m.put("parentId", parentId);
        m.put("names", names);
        m.put("orgUnitRef", orgUnitRef);
        m.put("version", version);
        m.put("validFrom", validFrom == null ? null : validFrom.toString());
        m.put("validTo", validTo == null ? null : validTo.toString());
        m.put("centroid", centroid == null ? null : Map.of("lat", centroid.lat(), "lon", centroid.lon()));
        m.put("bbox", bbox == null ? null : java.util.List.of(bbox.minLon(), bbox.minLat(), bbox.maxLon(), bbox.maxLat()));
        m.put("properties", properties);
        return m;
    }

    /** GeoJSON Feature view. */
    public Map<String, Object> toGeoJson() {
        Map<String, Object> props = new LinkedHashMap<>(summary());
        props.remove("id");
        return GeoJson.feature(id, geometry, props);
    }
}
