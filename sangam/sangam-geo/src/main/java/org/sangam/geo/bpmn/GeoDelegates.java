// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.bpmn;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.geo.GeoException;
import org.sangam.geo.GeoService;
import org.sangam.geo.boundary.GeoFeature;
import org.sangam.geo.geofence.GeofenceResult;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geometries;
import org.sangam.geo.rules.Nearest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BPMN service-task delegates for spatial rules, used with {@code operaton:delegateExpression} (Spring beans built with a
 * {@link GeoService}). Configuration is read from (local) variables so a BPMN input mapping sets it per task — the
 * beans are stateless and thread-safe:
 * <ul>
 *   <li>point: {@code geoLat}/{@code geoLon} (fallback {@code latitude}/{@code longitude});</li>
 *   <li>{@code geoLayer}, optional {@code geoLevel}; result variable {@code geoResultVar} (default per delegate);</li>
 *   <li>fence: {@code geoFenceId}, optional {@code geoAccuracyM}; {@code geoFailOnOutside=true} throws BPMN error
 *       {@code GEOFENCE_OUTSIDE} (and {@code GEOFENCE_INDETERMINATE} when {@code geoFailOnIndeterminate=true});</li>
 *   <li>nearest: {@code geoObjectType} (registry objects) or {@code geoLayer} (features), {@code geoN},
 *       {@code geoMaxKm}, {@code geoTags} (comma list).</li>
 * </ul>
 * A point outside every polygon gives a {@code null} result, or BPMN error {@code NO_JURISDICTION} when
 * {@code geoRequired=true} (route to a manual triage task).
 */
public final class GeoDelegates {

    private GeoDelegates() {
    }

    static GeoPoint point(DelegateExecution e) {
        Object lat = first(e, "geoLat", "latitude");
        Object lon = first(e, "geoLon", "longitude");
        if (lat == null || lon == null) {
            Object loc = e.getVariable("geoLocation");
            if (loc != null) {
                return Geometries.from(loc).centroid();
            }
            throw new BpmnError("NO_LOCATION", "geoLat/geoLon (or geoLocation) not set");
        }
        return new GeoPoint(Geometries.num(lat), Geometries.num(lon));
    }

    private static Object first(DelegateExecution e, String a, String b) {
        Object v = e.getVariable(a);
        return v != null ? v : e.getVariable(b);
    }

    static String str(DelegateExecution e, String name, String fallback) {
        Object v = e.getVariable(name);
        return v == null || v.toString().isBlank() ? fallback : v.toString();
    }

    static boolean flag(DelegateExecution e, String name) {
        Object v = e.getVariable(name);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }

    static String tenant(DelegateExecution e, String defaultTenant) {
        return e.getTenantId() != null ? e.getTenantId() : defaultTenant;
    }

    /** Sets {@code <resultVar>} (code), {@code <resultVar>Path} (codes) and {@code <resultVar>OrgUnit}. */
    public static final class Jurisdiction implements JavaDelegate {
        private final GeoService geo;
        private final String defaultTenant;

        public Jurisdiction(GeoService geo, String defaultTenant) {
            this.geo = Objects.requireNonNull(geo);
            this.defaultTenant = defaultTenant;
        }

        @Override
        public void execute(DelegateExecution e) {
            String layer = str(e, "geoLayer", null);
            if (layer == null) {
                throw new BpmnError("NO_LAYER", "geoLayer not set");
            }
            String level = str(e, "geoLevel", null);
            String out = str(e, "geoResultVar", "jurisdiction");
            String tenant = tenant(e, defaultTenant);
            GeoPoint p = point(e);
            GeoFeature f = (level == null ? geo.resolve(tenant, layer, p) : geo.resolve(tenant, layer, level, p)).orElse(null);
            if (f == null && flag(e, "geoRequired")) {
                throw new BpmnError("NO_JURISDICTION", "no feature of layer " + layer + " contains the point");
            }
            e.setVariable(out, f == null ? null : f.code());
            e.setVariable(out + "OrgUnit", f == null ? null : f.orgUnitRef());
            List<String> path = new ArrayList<>();
            if (f != null) {
                geo.lineage(tenant, f.id()).forEach(x -> path.add(x.code()));
            }
            e.setVariable(out + "Path", path);
        }
    }

    /** Sets {@code <resultVar>} (status name), {@code <resultVar>Inside} and {@code <resultVar>DistanceM}. */
    public static final class Fence implements JavaDelegate {
        private final GeoService geo;
        private final String defaultTenant;

        public Fence(GeoService geo, String defaultTenant) {
            this.geo = Objects.requireNonNull(geo);
            this.defaultTenant = defaultTenant;
        }

        @Override
        public void execute(DelegateExecution e) {
            String fenceId = str(e, "geoFenceId", null);
            if (fenceId == null) {
                throw new BpmnError("NO_FENCE", "geoFenceId not set");
            }
            Object acc = e.getVariable("geoAccuracyM");
            String out = str(e, "geoResultVar", "geofence");
            GeofenceResult r;
            try {
                r = geo.checkFence(tenant(e, defaultTenant), fenceId, point(e), acc == null ? null : Geometries.num(acc));
            } catch (GeoException ex) {
                throw new BpmnError("GEOFENCE_ERROR", ex.getMessage());
            }
            e.setVariable(out, r.status().name());
            e.setVariable(out + "Inside", r.inside());
            e.setVariable(out + "DistanceM", r.signedDistanceM());
            e.setVariable(out + "Reason", r.reason());
            if (r.status() == GeofenceResult.Status.OUTSIDE && flag(e, "geoFailOnOutside")) {
                throw new BpmnError("GEOFENCE_OUTSIDE", "point is outside fence " + fenceId);
            }
            if (r.status() == GeofenceResult.Status.INDETERMINATE && flag(e, "geoFailOnIndeterminate")) {
                throw new BpmnError("GEOFENCE_INDETERMINATE", r.reason());
            }
        }
    }

    /** Sets {@code <resultVar>} to a list of {id, distanceM} and {@code <resultVar>First} to the nearest id. */
    public static final class NearestN implements JavaDelegate {
        private final GeoService geo;
        private final String defaultTenant;

        public NearestN(GeoService geo, String defaultTenant) {
            this.geo = Objects.requireNonNull(geo);
            this.defaultTenant = defaultTenant;
        }

        @Override
        public void execute(DelegateExecution e) {
            String tenant = tenant(e, defaultTenant);
            GeoPoint p = point(e);
            int n = (int) Geometries.num(str(e, "geoN", "1"));
            double maxM = Geometries.num(str(e, "geoMaxKm", "0")) * 1000;
            String out = str(e, "geoResultVar", "nearest");
            List<Map<String, Object>> result = new ArrayList<>();
            String objectType = str(e, "geoObjectType", null);
            if (objectType != null) {
                String tags = str(e, "geoTags", "");
                Set<String> req = tags.isBlank() ? Set.of() : Set.of(tags.split("\\s*,\\s*"));
                for (Nearest.Ranked r : geo.nearestObjects(tenant, objectType, p, n, maxM, req)) {
                    result.add(entry(r.item().id(), r.distanceMeters()));
                }
            } else {
                String layer = str(e, "geoLayer", null);
                if (layer == null) {
                    throw new BpmnError("NO_LAYER", "geoObjectType or geoLayer must be set");
                }
                for (GeoService.NearestFeature f : geo.nearestFeatures(tenant, layer, p, n, maxM)) {
                    result.add(entry(f.feature().code(), f.distanceMeters()));
                }
            }
            e.setVariable(out, result);
            e.setVariable(out + "First", result.isEmpty() ? null : result.get(0).get("id"));
        }

        private static Map<String, Object> entry(String id, double d) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("distanceM", Math.round(d * 10) / 10.0);
            return m;
        }
    }
}
