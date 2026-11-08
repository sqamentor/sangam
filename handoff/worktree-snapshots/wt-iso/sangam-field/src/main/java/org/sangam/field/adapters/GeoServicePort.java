// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.adapters;

import org.sangam.field.ports.FieldPorts.GeoPort;
import org.sangam.geo.GeoService;
import org.sangam.geo.geofence.Geofence;
import org.sangam.geo.geofence.GeofenceResult;
import org.sangam.geo.model.GeoPoint;

import java.util.Objects;
import java.util.Optional;

/** {@link GeoPort} over sangam-geo's {@link GeoService}: FEATURE fences resolve against stored jurisdiction layers. */
public final class GeoServicePort implements GeoPort {

    private final GeoService geo;

    public GeoServicePort(GeoService geo) {
        this.geo = Objects.requireNonNull(geo, "geo");
    }

    @Override
    public GeofenceResult checkFence(String tenantId, Geofence fence, GeoPoint point, Double accuracyMeters) {
        return geo.checkFence(tenantId, fence, point, accuracyMeters);
    }

    @Override
    public Optional<String> jurisdictionAt(String tenantId, String layer, GeoPoint point) {
        return geo.resolve(tenantId, layer, point).map(f -> f.code());
    }
}
