// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.schedule;

import org.sangam.field.template.FieldTemplate;
import org.sangam.geo.geofence.Geofence;
import org.sangam.geo.model.GeoPoint;

import java.util.Map;

/**
 * Serialisable fence of an assignment: a circle around the target, or a stored geo feature / fence reference
 * (resolved by sangam-geo through the GeoPort). Built from the template's geofence rule and the target location.
 */
public record FenceSpec(String kind, Double lat, Double lon, Double radiusMeters, String featureRef, Double toleranceMeters,
                        Double maxAccuracyMeters, boolean required) {

    public static FenceSpec from(FieldTemplate.GeofenceRule rule, GeoPoint target) {
        if (rule == null) {
            return null;
        }
        String ref = rule.featureRef() != null ? rule.featureRef() : rule.fenceId();
        if (ref != null && !ref.isBlank()) {
            return new FenceSpec("FEATURE", null, null, null, ref, rule.toleranceMeters(), rule.maxAccuracyMeters(), rule.required());
        }
        if (rule.radiusMeters() != null && target != null) {
            return new FenceSpec("CIRCLE", target.lat(), target.lon(), rule.radiusMeters(), null, rule.toleranceMeters(),
                    rule.maxAccuracyMeters(), rule.required());
        }
        return null;
    }

    public Geofence toGeofence(String id) {
        double tol = toleranceMeters == null ? 0 : toleranceMeters;
        double acc = maxAccuracyMeters == null ? 0 : maxAccuracyMeters;
        if ("FEATURE".equals(kind)) {
            return new Geofence(id, Geofence.Kind.FEATURE, null, null, 0, featureRef, tol, acc, Map.of());
        }
        return new Geofence(id, Geofence.Kind.CIRCLE, null, new GeoPoint(lat, lon), radiusMeters, null, tol, acc, Map.of());
    }
}
