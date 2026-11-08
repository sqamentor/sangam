// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.feel;

import org.operaton.bpm.dmn.feel.impl.scala.function.CustomFunction;
import org.operaton.bpm.dmn.feel.impl.scala.function.FeelCustomFunctionProvider;
import org.sangam.geo.GeoException;
import org.sangam.geo.GeoService;
import org.sangam.geo.boundary.GeoFeature;
import org.sangam.geo.model.GeoMath;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geohash;
import org.sangam.geo.model.Geometries;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Spatial FEEL functions for DMN decisions and BPMN FEEL expressions (N13 "a FEEL function jurisdictionAt(lat, lon,
 * layer)"). Pure functions work without a database; the layer/fence functions use {@link GeoService} for the tenant
 * of the current evaluation ({@link GeoTenant}).
 *
 * <table>
 *   <tr><td>{@code jurisdictionAt(lat, lon, layer)}</td><td>code of the deepest feature containing the point, or null</td></tr>
 *   <tr><td>{@code jurisdictionAtLevel(lat, lon, layer, level)}</td><td>code at a given level, or null</td></tr>
 *   <tr><td>{@code jurisdictionPath(lat, lon, layer)}</td><td>root-first list of codes</td></tr>
 *   <tr><td>{@code jurisdictionOrgUnit(lat, lon, layer)}</td><td>org unit of the deepest containing feature ("office by location")</td></tr>
 *   <tr><td>{@code distanceKm(lat1, lon1, lat2, lon2)}</td><td>great-circle distance in km</td></tr>
 *   <tr><td>{@code pointInPolygon(lat, lon, geometry)}</td><td>geometry as WKT or GeoJSON text</td></tr>
 *   <tr><td>{@code withinFence(fenceId, lat, lon, accuracyM)}</td><td>true only when INSIDE</td></tr>
 *   <tr><td>{@code withinRadius(lat, lon, centreLat, centreLon, radiusM)}</td><td>circle test</td></tr>
 *   <tr><td>{@code nearestFeature(lat, lon, layer)}</td><td>context {code, distanceM} of the nearest feature, or null</td></tr>
 *   <tr><td>{@code geohash(lat, lon, precision)}</td><td>geohash string</td></tr>
 * </table>
 */
public final class GeoFeelFunctionProvider implements FeelCustomFunctionProvider {

    private final GeoService geo;
    private final String defaultTenant;
    private final Map<String, CustomFunction> functions = new LinkedHashMap<>();

    /** @param geo may be null: only the pure functions are then usable */
    public GeoFeelFunctionProvider(GeoService geo, String defaultTenant) {
        this.geo = geo;
        this.defaultTenant = defaultTenant;
        fn("distanceKm", a -> GeoMath.haversineMeters(pt(a, 0), pt(a, 2)) / 1000.0, "lat1", "lon1", "lat2", "lon2");
        fn("pointInPolygon", a -> Geometries.parse(str(a, 2)).contains(pt(a, 0)), "lat", "lon", "geometry");
        fn("withinRadius", a -> GeoMath.haversineMeters(pt(a, 0), pt(a, 2)) <= num(a, 4), "lat", "lon", "centreLat", "centreLon",
                "radiusM");
        fn("geohash", a -> Geohash.encode(pt(a, 0), (int) num(a, 2)), "lat", "lon", "precision");
        fn("jurisdictionAt", a -> service().resolve(tenant(), str(a, 2), pt(a, 0)).map(GeoFeature::code).orElse(null),
                "lat", "lon", "layer");
        fn("jurisdictionAtLevel", a -> service().resolve(tenant(), str(a, 2), str(a, 3), pt(a, 0)).map(GeoFeature::code).orElse(null),
                "lat", "lon", "layer", "level");
        fn("jurisdictionPath", a -> service().path(tenant(), str(a, 2), pt(a, 0)).stream().map(GeoFeature::code).toList(),
                "lat", "lon", "layer");
        fn("jurisdictionOrgUnit", a -> service().resolve(tenant(), str(a, 2), pt(a, 0)).map(GeoFeature::orgUnitRef).orElse(null),
                "lat", "lon", "layer");
        fn("withinFence", a -> {
            Double acc = a.size() > 3 && a.get(3) != null ? num(a, 3) : null;
            return service().checkFence(tenant(), str(a, 0), pt(a, 1), acc).inside();
        }, "fenceId", "lat", "lon", "accuracyM");
        fn("nearestFeature", a -> {
            List<GeoService.NearestFeature> n = service().nearestFeatures(tenant(), str(a, 2), pt(a, 0), 1, 0);
            if (n.isEmpty()) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", n.get(0).feature().code());
            m.put("distanceM", n.get(0).distanceMeters());
            return m;
        }, "lat", "lon", "layer");
    }

    private void fn(String name, Function<List<Object>, Object> body, String... params) {
        functions.put(name, CustomFunction.create().setParams(params).setFunction(args -> {
            try {
                return body.apply(args);
            } catch (GeoException e) {
                return null;
            }
        }).build());
    }

    @Override
    public Optional<CustomFunction> resolveFunction(String functionName) {
        return Optional.ofNullable(functions.get(functionName));
    }

    @Override
    public Collection<String> getFunctionNames() {
        return Set.copyOf(functions.keySet());
    }

    /** Direct (non-FEEL) evaluation, for tests and for Java callers sharing the same semantics. */
    public Object call(String name, List<Object> args) {
        CustomFunction f = functions.get(name);
        if (f == null) {
            throw new GeoException("UNKNOWN_FUNCTION", name);
        }
        return f.getFunction().apply(args);
    }

    private GeoService service() {
        if (geo == null) {
            throw new GeoException("GEO_UNAVAILABLE", "GeoService not configured");
        }
        return geo;
    }

    private String tenant() {
        return GeoTenant.current(defaultTenant);
    }

    private static GeoPoint pt(List<Object> a, int i) {
        return new GeoPoint(num(a, i), num(a, i + 1));
    }

    private static double num(List<Object> a, int i) {
        return Geometries.num(a.get(i));
    }

    private static String str(List<Object> a, int i) {
        Object o = a.get(i);
        if (o == null) {
            throw new GeoException("MISSING_ARGUMENT", "argument " + i + " is null");
        }
        return o.toString();
    }
}
