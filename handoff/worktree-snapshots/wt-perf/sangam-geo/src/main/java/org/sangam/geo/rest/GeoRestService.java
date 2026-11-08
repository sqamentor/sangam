// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.rest;

import org.sangam.core.integration.ConnectorResult;
import org.sangam.geo.GeoException;
import org.sangam.geo.GeoService;
import org.sangam.geo.boundary.BoundaryDataLoader;
import org.sangam.geo.boundary.BoundaryLevel;
import org.sangam.geo.boundary.GeoFeature;
import org.sangam.geo.boundary.JurisdictionLayer;
import org.sangam.geo.connector.ExternalLayer;
import org.sangam.geo.connector.ExternalLayerGateway;
import org.sangam.geo.geofence.Geofence;
import org.sangam.geo.geofence.GeofenceResult;
import org.sangam.geo.model.GeoJson;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geometries;
import org.sangam.geo.model.Geometry;
import org.sangam.geo.rules.Nearest;
import org.sangam.geo.store.GeoStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link GeoRestPort} over {@link GeoService}. Reads are open to any authenticated caller of the tenant; writes
 * (layers, features, fences, external layers) require {@code adminGroup} when one is configured — endpoint policy
 * (VI.4) is still applied by sangam-security in front of the controller.
 */
public final class GeoRestService implements GeoRestPort {

    private final GeoService geo;
    private final ExternalLayerGateway gateway;
    private final String adminGroup;

    /** @param gateway may be null (external queries then fail with NO_CONNECTOR) */
    public GeoRestService(GeoService geo, ExternalLayerGateway gateway, String adminGroup) {
        this.geo = Objects.requireNonNull(geo);
        this.gateway = gateway;
        this.adminGroup = adminGroup;
    }

    private void admin(GeoCaller c) {
        if (adminGroup != null && !c.groups().contains(adminGroup)) {
            throw new GeoException("FORBIDDEN", "requires group " + adminGroup);
        }
    }

    // ------------------------------------------------------------------ layers

    @Override
    public List<Map<String, Object>> listLayers(GeoCaller caller) {
        return geo.layers(caller.tenantId()).stream().map(GeoRestService::layerView).toList();
    }

    @Override
    public Map<String, Object> defineLayer(GeoCaller caller, Map<String, Object> body) {
        admin(caller);
        JurisdictionLayer l = BoundaryDataLoader.layer(GeoJson.MAPPER.valueToTree(body));
        return layerView(geo.defineLayer(caller.tenantId(), l, caller.principalId()));
    }

    @Override
    public Map<String, Object> getLayer(GeoCaller caller, String layer) {
        return layerView(geo.layer(caller.tenantId(), layer).orElseThrow(() -> new GeoException("NOT_FOUND", "unknown layer " + layer)));
    }

    @Override
    public Map<String, Object> importBoundaries(GeoCaller caller, String layer, Map<String, Object> body) {
        admin(caller);
        BoundaryDataLoader.Package pkg = BoundaryDataLoader.read(GeoJson.MAPPER.valueToTree(body));
        if (pkg.layer() != null && !pkg.layer().code().equals(layer)) {
            throw new GeoException("LAYER_MISMATCH", "package layer " + pkg.layer().code() + " does not match path " + layer);
        }
        GeoService.ImportReport r = geo.importPackage(caller.tenantId(), pkg, layer, caller.principalId());
        return report(r);
    }

    private static Map<String, Object> report(GeoService.ImportReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layer", r.layer());
        m.put("created", r.created());
        m.put("versioned", r.versioned());
        m.put("unchanged", r.unchanged());
        m.put("warnings", r.warnings());
        return m;
    }

    static Map<String, Object> layerView(JurisdictionLayer l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", l.code());
        m.put("kind", l.kind());
        m.put("names", l.names());
        List<Map<String, Object>> levels = new ArrayList<>();
        for (BoundaryLevel lv : l.levels()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", lv.code());
            x.put("depth", lv.depth());
            x.put("names", lv.names());
            x.put("parentLevel", lv.parentLevel());
            levels.add(x);
        }
        m.put("levels", levels);
        m.put("orgUnitLinked", l.orgUnitLinked());
        m.put("indexPrecision", l.indexPrecision());
        m.put("attributes", l.attributes());
        return m;
    }

    // ------------------------------------------------------------------ features

    @Override
    public List<Map<String, Object>> listFeatures(GeoCaller caller, String layer, String level, String parentId, boolean history,
                                                  int page, int size) {
        int s = Math.max(1, Math.min(500, size <= 0 ? 50 : size));
        return geo.features(caller.tenantId(), layer, level, parentId, history, Math.max(0, page) * s, s).stream()
                .map(GeoFeature::summary).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> putFeature(GeoCaller caller, String layer, Map<String, Object> body) {
        admin(caller);
        String code = str(body, "code");
        if (code == null) {
            throw new GeoException("INVALID_FEATURE", "code is required");
        }
        Geometry g = Geometries.from(body.get("geometry"));
        Map<String, String> names = body.get("names") instanceof Map<?, ?> n ? (Map<String, String>) n : Map.of();
        Map<String, Object> props = body.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        GeoFeature f = geo.putFeature(caller.tenantId(), layer, new BoundaryDataLoader.FeatureInput(str(body, "level"), code,
                str(body, "parentCode"), names, props, g, str(body, "orgUnitRef")), caller.principalId());
        return f.toGeoJson();
    }

    @Override
    public Map<String, Object> getFeature(GeoCaller caller, String featureId, boolean withGeometry) {
        GeoFeature f = geo.feature(caller.tenantId(), featureId).orElseThrow(() -> new GeoException("NOT_FOUND", "no feature " + featureId));
        return withGeometry ? f.toGeoJson() : f.summary();
    }

    @Override
    public List<Map<String, Object>> lineage(GeoCaller caller, String featureId) {
        return geo.lineage(caller.tenantId(), featureId).stream().map(GeoFeature::summary).toList();
    }

    @Override
    public void retireFeature(GeoCaller caller, String featureId) {
        admin(caller);
        geo.retireFeature(caller.tenantId(), featureId, caller.principalId());
    }

    // ------------------------------------------------------------------ rules

    @Override
    public Map<String, Object> resolve(GeoCaller caller, String layer, String level, double lat, double lon) {
        GeoPoint p = new GeoPoint(lat, lon);
        GeoFeature f = (level == null ? geo.resolve(caller.tenantId(), layer, p) : geo.resolve(caller.tenantId(), layer, level, p))
                .orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layer", layer);
        m.put("point", Map.of("lat", lat, "lon", lon));
        m.put("feature", f == null ? null : f.summary());
        m.put("path", f == null ? List.of() : geo.lineage(caller.tenantId(), f.id()).stream().map(GeoFeature::summary).toList());
        return m;
    }

    @Override
    public List<Map<String, Object>> nearest(GeoCaller caller, String layer, String objectType, double lat, double lon, int n,
                                             double maxKm, String tags) {
        GeoPoint p = new GeoPoint(lat, lon);
        int k = Math.max(1, Math.min(200, n <= 0 ? 5 : n));
        List<Map<String, Object>> out = new ArrayList<>();
        if (objectType != null) {
            Set<String> req = tags == null || tags.isBlank() ? Set.of() : new LinkedHashSet<>(List.of(tags.split("\\s*,\\s*")));
            for (Nearest.Ranked r : geo.nearestObjects(caller.tenantId(), objectType, p, k, maxKm * 1000, req)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("objectType", objectType);
                m.put("id", r.item().id());
                m.put("distanceM", r.distanceMeters());
                m.put("bearingDeg", r.bearingDeg());
                m.put("tags", r.item().tags());
                out.add(m);
            }
            return out;
        }
        if (layer == null) {
            throw new GeoException("INVALID_REQUEST", "layer or objectType is required");
        }
        for (GeoService.NearestFeature f : geo.nearestFeatures(caller.tenantId(), layer, p, k, maxKm * 1000)) {
            Map<String, Object> m = new LinkedHashMap<>(f.feature().summary());
            m.put("distanceM", f.distanceMeters());
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> overlaps(GeoCaller caller, Map<String, Object> body) {
        String layer = str(body, "layer");
        if (layer == null) {
            throw new GeoException("INVALID_REQUEST", "layer is required");
        }
        return geo.overlaps(caller.tenantId(), layer, Geometries.from(body.get("geometry"))).stream().map(GeoFeature::summary).toList();
    }

    // ------------------------------------------------------------------ fences

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> defineFence(GeoCaller caller, Map<String, Object> body) {
        admin(caller);
        String id = str(body, "id");
        if (id == null) {
            throw new GeoException("INVALID_FENCE", "id is required");
        }
        Geofence.Kind kind = Geofence.Kind.valueOf(String.valueOf(body.getOrDefault("kind", body.containsKey("featureRef") ? "FEATURE"
                : body.containsKey("radiusM") ? "CIRCLE" : "POLYGON")));
        GeoPoint center = body.get("center") == null ? null : Geometries.from(body.get("center")).centroid();
        Geometry g = kind == Geofence.Kind.POLYGON ? Geometries.from(body.get("geometry")) : null;
        Map<String, Object> attrs = body.get("attributes") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        Geofence f = new Geofence(id, kind, g, center, dbl(body, "radiusM"), str(body, "featureRef"), dbl(body, "toleranceM"),
                dbl(body, "maxAccuracyM"), attrs);
        return fenceView(geo.defineFence(caller.tenantId(), f, caller.principalId()));
    }

    @Override
    public Map<String, Object> getFence(GeoCaller caller, String fenceId) {
        return fenceView(geo.fence(caller.tenantId(), fenceId).orElseThrow(() -> new GeoException("NOT_FOUND", "no fence " + fenceId)));
    }

    @Override
    public void deactivateFence(GeoCaller caller, String fenceId) {
        admin(caller);
        geo.deactivateFence(caller.tenantId(), fenceId, caller.principalId());
    }

    @Override
    public Map<String, Object> checkFence(GeoCaller caller, String fenceId, Map<String, Object> body) {
        GeoPoint p = new GeoPoint(Geometries.num(body.get("lat")), Geometries.num(body.get("lon")));
        Double acc = body.get("accuracyM") == null ? null : Geometries.num(body.get("accuracyM"));
        GeofenceResult r = geo.checkFence(caller.tenantId(), fenceId, p, acc);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fenceId", r.fenceId());
        m.put("status", r.status().name());
        m.put("inside", r.inside());
        m.put("signedDistanceM", r.signedDistanceM());
        m.put("accuracyM", r.accuracyM());
        m.put("reason", r.reason());
        return m;
    }

    static Map<String, Object> fenceView(Geofence f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("kind", f.kind().name());
        m.put("geometry", f.geometry() == null ? null : GeoJson.toMap(f.geometry()));
        m.put("center", f.center() == null ? null : Map.of("lat", f.center().lat(), "lon", f.center().lon()));
        m.put("radiusM", f.radiusMeters());
        m.put("featureRef", f.featureRef());
        m.put("toleranceM", f.toleranceMeters());
        m.put("maxAccuracyM", f.maxAccuracyMeters());
        m.put("attributes", f.attributes());
        return m;
    }

    // ------------------------------------------------------------------ object geometry

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> putObjectGeometry(GeoCaller caller, String objectType, String objectId, Map<String, Object> body) {
        Geometry g = Geometries.from(body.get("geometry"));
        Set<String> tags = body.get("tags") instanceof List<?> l ? new LinkedHashSet<>((List<String>) l) : Set.of();
        String attribute = str(body, "attribute");
        geo.putObjectGeometry(caller.tenantId(), objectType, objectId, attribute, g, tags, caller.principalId());
        return getObjectGeometry(caller, objectType, objectId, attribute);
    }

    @Override
    public Map<String, Object> getObjectGeometry(GeoCaller caller, String objectType, String objectId, String attribute) {
        GeoStore.ObjectGeometry o = geo.objectGeometry(caller.tenantId(), objectType, objectId, attribute)
                .orElseThrow(() -> new GeoException("NOT_FOUND", "no geometry for " + objectType + "/" + objectId));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("objectType", o.objectType());
        props.put("attribute", o.attribute());
        props.put("tags", o.tags());
        props.put("centroid", Map.of("lat", o.centroid().lat(), "lon", o.centroid().lon()));
        return GeoJson.feature(o.objectId(), o.geometry(), props);
    }

    // ------------------------------------------------------------------ external GIS

    @Override
    public List<Map<String, Object>> listExternalLayers(GeoCaller caller) {
        return geo.externals(caller.tenantId()).stream().map(GeoRestService::externalView).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> registerExternalLayer(GeoCaller caller, Map<String, Object> body) {
        admin(caller);
        ExternalLayer x = new ExternalLayer(str(body, "ref"), ExternalLayer.Protocol.valueOf(String.valueOf(body.get("protocol"))),
                str(body, "connectorId"), str(body, "baseUrl"), str(body, "remoteName"), str(body, "serviceVersion"), str(body, "srs"),
                str(body, "outputFormat"), str(body, "importLayer"),
                body.get("attributes") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of(),
                !Boolean.FALSE.equals(body.get("active")));
        return externalView(geo.registerExternal(caller.tenantId(), x, caller.principalId()));
    }

    @Override
    public Map<String, Object> queryExternalLayer(GeoCaller caller, String ref, Map<String, Object> body) {
        Map<String, Object> in = new LinkedHashMap<>(body == null ? Map.of() : body);
        Object op = in.remove("operation");
        in.remove("baseUrl"); // the registered URL only: no caller-chosen egress
        ConnectorResult r = gateway().query(caller.tenantId(), ref, op == null ? null : op.toString(), in);
        return switch (r) {
            case ConnectorResult.Success s -> s.output();
            case ConnectorResult.BusinessFailure b -> throw new GeoException("GIS_" + b.code(), b.message());
            case ConnectorResult.TransientFailure t -> throw new GeoException("GIS_UNAVAILABLE", t.message());
            case ConnectorResult.OutcomeUnknown u -> throw new GeoException("GIS_UNAVAILABLE", u.reason());
        };
    }

    @Override
    public Map<String, Object> importExternalLayer(GeoCaller caller, String ref, Map<String, Object> body) {
        admin(caller);
        Map<String, Object> in = new LinkedHashMap<>(body == null ? Map.of() : body);
        in.remove("baseUrl");
        return report(gateway().importFeatures(caller.tenantId(), ref, in, caller.principalId()));
    }

    private ExternalLayerGateway gateway() {
        if (gateway == null) {
            throw new GeoException("NO_CONNECTOR", "no GIS connector gateway configured");
        }
        return gateway;
    }

    static Map<String, Object> externalView(ExternalLayer x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", x.ref());
        m.put("protocol", x.protocol().name());
        m.put("connectorId", x.connectorId());
        m.put("baseUrl", x.baseUrl());
        m.put("remoteName", x.remoteName());
        m.put("serviceVersion", x.serviceVersion());
        m.put("srs", x.srs());
        m.put("outputFormat", x.outputFormat());
        m.put("importLayer", x.importLayer());
        m.put("attributes", x.attributes());
        m.put("active", x.active());
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    private static double dbl(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? 0 : Geometries.num(v);
    }
}
