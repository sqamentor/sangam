// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.geo.boundary.BoundaryDataLoader;
import org.sangam.geo.boundary.BoundaryDataLoader.FeatureInput;
import org.sangam.geo.boundary.BoundaryLevel;
import org.sangam.geo.boundary.GeoFeature;
import org.sangam.geo.boundary.JurisdictionLayer;
import org.sangam.geo.connector.ExternalLayer;
import org.sangam.geo.geofence.Geofence;
import org.sangam.geo.geofence.GeofenceChecker;
import org.sangam.geo.geofence.GeofenceResult;
import org.sangam.geo.index.SpatialIndex;
import org.sangam.geo.index.SpatialIndexes;
import org.sangam.geo.model.BBox;
import org.sangam.geo.model.GeoJson;
import org.sangam.geo.model.GeoMath;
import org.sangam.geo.model.GeoPoint;
import org.sangam.geo.model.Geohash;
import org.sangam.geo.model.Geometry;
import org.sangam.geo.rules.Nearest;
import org.sangam.geo.store.GeoStore;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The N13 service: jurisdiction layers as data, versioned features, point-in-polygon resolution (deepest level or a
 * given level, plus the full path), nearest-N, overlap checks, geofences and geometry as a typed attribute of any
 * registry object. Every query narrows candidates through the {@link SpatialIndex} and then evaluates the exact
 * predicate in Java, so all four Tier-1 databases give the same answer (A-15).
 *
 * <p>Writes run in the caller-supplied {@link Transactions} and append to the core {@link AuditChain} (chain root
 * {@code geo:<layer>}) when an audit chain is given.
 */
public final class GeoService {

    /** Default cap on the radius of a nearest-N search when the caller does not give one. */
    public static final double DEFAULT_MAX_SEARCH_M = 500_000;
    private static final int CACHE_LIMIT = 20_000;

    private final Transactions tx;
    private final GeoStore store = new GeoStore();
    private final SpatialIndex index;
    private final AuditChain audit;
    private final Clock clock;
    private final Map<String, GeoFeature> cache = new ConcurrentHashMap<>();

    public GeoService(Transactions tx, SpatialIndex index, AuditChain audit, Clock clock) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.index = Objects.requireNonNull(index, "index");
        this.audit = audit;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Selects the index ({@code auto | portable | postgis}) and runs its one-time setup. */
    public static GeoService create(Transactions tx, Dialect dialect, String indexMode, AuditChain audit, Clock clock) {
        SpatialIndex idx = tx.inTransaction(c -> {
            SpatialIndex s = SpatialIndexes.select(c, dialect, indexMode);
            s.ensure(c);
            return s;
        });
        return new GeoService(tx, idx, audit, clock);
    }

    public String indexName() {
        return index.name();
    }

    // ================================================================== layers

    public JurisdictionLayer defineLayer(String tenantId, JurisdictionLayer layer, String actor) {
        return tx.inTransaction(c -> {
            store.upsertLayer(c, tenantId, layer, actor, now());
            audit(c, tenantId, "geo:" + layer.code(), "GEO_LAYER_DEFINED", Map.of("layer", layer.code(), "kind", layer.kind(),
                    "levels", layer.levels().stream().map(BoundaryLevel::code).toList(), "actor", nz(actor)));
            return layer;
        });
    }

    public Optional<JurisdictionLayer> layer(String tenantId, String code) {
        return tx.inTransaction(c -> store.layer(c, tenantId, code));
    }

    public List<JurisdictionLayer> layers(String tenantId) {
        return tx.inTransaction(c -> store.layers(c, tenantId));
    }

    private JurisdictionLayer requireLayer(Connection c, String tenantId, String code) {
        return store.layer(c, tenantId, code).orElseThrow(() -> new GeoException("NOT_FOUND", "unknown layer " + code));
    }

    // ================================================================== features

    public record ImportReport(String layer, int created, int versioned, int unchanged, List<String> warnings) {
    }

    /** Stores a new feature or a new version of an existing code (the previous version is closed). */
    public GeoFeature putFeature(String tenantId, String layerCode, FeatureInput in, String actor) {
        return tx.inTransaction(c -> {
            JurisdictionLayer layer = requireLayer(c, tenantId, layerCode);
            List<String> warnings = new ArrayList<>();
            Outcome o = put(c, tenantId, layer, in, actor, warnings);
            return o.feature;
        });
    }

    /** Imports a boundary package (any country, any hierarchy), parents first, in one transaction. */
    public ImportReport importPackage(String tenantId, BoundaryDataLoader.Package pkg, String layerCodeIfNoLayer, String actor) {
        return tx.inTransaction(c -> {
            JurisdictionLayer layer;
            if (pkg.layer() != null) {
                store.upsertLayer(c, tenantId, pkg.layer(), actor, now());
                layer = pkg.layer();
            } else {
                layer = requireLayer(c, tenantId, layerCodeIfNoLayer);
            }
            int created = 0, versioned = 0, unchanged = 0;
            List<String> warnings = new ArrayList<>();
            final JurisdictionLayer l = layer;
            List<FeatureInput> ordered = new ArrayList<>(pkg.features());
            ordered.sort(Comparator.comparingInt(in -> in.level() == null ? 0 : depth(l, in.level())));
            for (FeatureInput in : ordered) {
                Outcome o = put(c, tenantId, layer, in, actor, warnings);
                switch (o.kind) {
                    case "created" -> created++;
                    case "versioned" -> versioned++;
                    default -> unchanged++;
                }
            }
            audit(c, tenantId, "geo:" + layer.code(), "GEO_LAYER_IMPORTED", Map.of("layer", layer.code(), "created", created,
                    "versioned", versioned, "unchanged", unchanged, "warnings", warnings.size(), "actor", nz(actor)));
            return new ImportReport(layer.code(), created, versioned, unchanged, List.copyOf(warnings));
        });
    }

    private record Outcome(GeoFeature feature, String kind) {
    }

    private Outcome put(Connection c, String tenantId, JurisdictionLayer layer, FeatureInput in, String actor, List<String> warnings) {
        String levelCode = in.level() == null ? layer.levels().get(0).code() : in.level();
        BoundaryLevel level = layer.level(levelCode);
        if (in.geometry() == null) {
            throw new GeoException("INVALID_GEOMETRY", "feature " + in.code() + " has no geometry");
        }
        String parentId = null;
        if (level.parentLevel() != null) {
            if (in.parentCode() == null || in.parentCode().isBlank()) {
                throw new GeoException("MISSING_PARENT", "feature " + in.code() + " at level " + levelCode + " needs a parent at "
                        + level.parentLevel());
            }
            GeoFeature parent = store.current(c, tenantId, layer.code(), level.parentLevel(), in.parentCode())
                    .orElseThrow(() -> new GeoException("UNKNOWN_PARENT", "parent " + in.parentCode() + " not found at level "
                            + level.parentLevel()));
            parentId = parent.id();
            GeoPoint ct = in.geometry().centroid();
            if (parent.geometry().isAreal() && !parent.geometry().contains(ct)) {
                warnings.add(in.code() + ": centroid lies outside parent " + parent.code());
            }
        }
        Instant now = now();
        Optional<GeoFeature> cur = store.current(c, tenantId, layer.code(), levelCode, in.code());
        if (cur.isPresent() && sameContent(cur.get(), in, parentId)) {
            return new Outcome(cur.get(), "unchanged");
        }
        int version = cur.map(f -> f.version() + 1).orElse(1);
        cur.ifPresent(f -> {
            store.closeCurrent(c, tenantId, layer.code(), levelCode, in.code(), now);
            index.remove(c, tenantId, layer.code(), f.id());
            cache.remove(key(tenantId, f.id()));
        });
        Geometry g = in.geometry();
        GeoPoint ct = g.centroid();
        GeoFeature f = new GeoFeature(layer.code() + ":" + levelCode + ":" + in.code() + ":v" + version, layer.code(), levelCode,
                in.code(), parentId, in.names(), in.properties(), g, g.bbox(), ct, Geohash.encode(ct, 9), in.orgUnitRef(), version,
                now, null);
        store.insertFeature(c, tenantId, f, actor, now);
        index.index(c, tenantId, layer.code(), f.id(), g, layer.indexPrecision());
        audit(c, tenantId, "geo:" + layer.code(), cur.isPresent() ? "GEO_FEATURE_VERSIONED" : "GEO_FEATURE_CREATED",
                Map.of("featureId", f.id(), "code", f.code(), "level", levelCode, "version", version, "geometryHash",
                        org.sangam.core.layer.Hashes.hex(org.sangam.core.layer.Hashes.sha256(GeoJson.write(g))), "actor", nz(actor)));
        return new Outcome(f, cur.isPresent() ? "versioned" : "created");
    }

    private static boolean sameContent(GeoFeature cur, FeatureInput in, String parentId) {
        return GeoJson.write(cur.geometry()).equals(GeoJson.write(in.geometry())) && Objects.equals(cur.parentId(), parentId)
                && cur.names().equals(in.names()) && cur.properties().equals(in.properties())
                && Objects.equals(cur.orgUnitRef(), in.orgUnitRef());
    }

    public void retireFeature(String tenantId, String featureId, String actor) {
        tx.inTransaction(c -> {
            GeoFeature f = store.feature(c, tenantId, featureId).orElseThrow(() -> new GeoException("NOT_FOUND", "no feature " + featureId));
            store.retire(c, tenantId, featureId, now());
            index.remove(c, tenantId, f.layer(), featureId);
            cache.remove(key(tenantId, featureId));
            audit(c, tenantId, "geo:" + f.layer(), "GEO_FEATURE_RETIRED", Map.of("featureId", featureId, "actor", nz(actor)));
            return null;
        });
    }

    public Optional<GeoFeature> feature(String tenantId, String featureId) {
        GeoFeature hit = cache.get(key(tenantId, featureId));
        if (hit != null) {
            return Optional.of(hit);
        }
        return tx.inTransaction(c -> store.feature(c, tenantId, featureId)).map(f -> remember(tenantId, f));
    }

    /** Current version of a code in a layer (any level; the deepest level wins if a code repeats across levels). */
    public Optional<GeoFeature> featureByCode(String tenantId, String layer, String code) {
        return tx.inTransaction(c -> {
            JurisdictionLayer l = requireLayer(c, tenantId, layer);
            return store.currentByCode(c, tenantId, layer, code).stream()
                    .max(Comparator.comparingInt(f -> depth(l, f.level())));
        });
    }

    public List<GeoFeature> features(String tenantId, String layer, String level, String parentId, boolean includeHistory,
                                     int offset, int limit) {
        return tx.inTransaction(c -> store.list(c, tenantId, layer, level, parentId, !includeHistory, offset, limit));
    }

    /** Root-first ancestry of a feature, including the feature itself. */
    public List<GeoFeature> lineage(String tenantId, String featureId) {
        List<GeoFeature> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String id = featureId;
        while (id != null && seen.add(id)) {
            Optional<GeoFeature> f = feature(tenantId, id);
            if (f.isEmpty()) {
                break;
            }
            chain.add(0, f.get());
            id = f.get().parentId();
        }
        return chain;
    }

    // ================================================================== point-in-polygon rules

    /** All active features of the layer that contain the point, deepest level first. */
    public List<GeoFeature> containing(String tenantId, String layer, GeoPoint p) {
        return tx.inTransaction(c -> {
            JurisdictionLayer l = requireLayer(c, tenantId, layer);
            List<GeoFeature> cands = load(c, tenantId, index.containingCandidates(c, tenantId, layer, p));
            List<GeoFeature> hits = new ArrayList<>();
            for (GeoFeature f : cands) {
                if (f.geometry().contains(p)) {
                    hits.add(f);
                }
            }
            hits.sort(Comparator.<GeoFeature>comparingInt(f -> -depth(l, f.level())).thenComparing(GeoFeature::code));
            return hits;
        });
    }

    /** jurisdictionAt: the most specific (deepest level) feature of the layer containing the point. */
    public Optional<GeoFeature> resolve(String tenantId, String layer, GeoPoint p) {
        List<GeoFeature> hits = containing(tenantId, layer, p);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /** Feature of a given level containing the point. */
    public Optional<GeoFeature> resolve(String tenantId, String layer, String level, GeoPoint p) {
        return containing(tenantId, layer, p).stream().filter(f -> f.level().equals(level)).findFirst();
    }

    /** Root-first jurisdiction path for a point (for routing to the office of each tier). */
    public List<GeoFeature> path(String tenantId, String layer, GeoPoint p) {
        Optional<GeoFeature> leaf = resolve(tenantId, layer, p);
        return leaf.map(f -> lineage(tenantId, f.id())).orElse(List.of());
    }

    public record NearestFeature(GeoFeature feature, double distanceMeters) {
    }

    /** Nearest-N features of a layer (distance 0 for areal features containing the point). */
    public List<NearestFeature> nearestFeatures(String tenantId, String layer, GeoPoint p, int n, double maxMeters) {
        int want = Math.max(1, n);
        double cap = maxMeters > 0 ? maxMeters : DEFAULT_MAX_SEARCH_M;
        return tx.inTransaction(c -> {
            requireLayer(c, tenantId, layer);
            List<String> ids = index.nearestCandidates(c, tenantId, layer, p, want);
            List<NearestFeature> ranked;
            if (ids != null) {
                ranked = rankFeatures(load(c, tenantId, ids), p, cap);
            } else {
                double r = Math.min(cap, 1_000);
                while (true) {
                    ranked = rankFeatures(load(c, tenantId, store.idsByCentroidBox(c, tenantId, layer, BBox.around(p, r))), p, r);
                    if (ranked.size() >= want || r >= cap) {
                        break;
                    }
                    r = Math.min(cap, r * 4);
                }
            }
            return ranked.size() > want ? List.copyOf(ranked.subList(0, want)) : ranked;
        });
    }

    private static List<NearestFeature> rankFeatures(List<GeoFeature> fs, GeoPoint p, double maxMeters) {
        List<NearestFeature> out = new ArrayList<>();
        for (GeoFeature f : fs) {
            double d = f.geometry().distanceMeters(p);
            if (d <= maxMeters) {
                out.add(new NearestFeature(f, d));
            }
        }
        out.sort(Comparator.comparingDouble(NearestFeature::distanceMeters).thenComparing(x -> x.feature().id()));
        return out;
    }

    /** Overlap check: active features of the layer that share any point with the candidate geometry. */
    public List<GeoFeature> overlaps(String tenantId, String layer, Geometry candidate) {
        return tx.inTransaction(c -> {
            requireLayer(c, tenantId, layer);
            List<GeoFeature> out = new ArrayList<>();
            for (GeoFeature f : load(c, tenantId, index.intersectingCandidates(c, tenantId, layer, candidate.bbox()))) {
                if (GeoMath.intersects(f.geometry(), candidate)) {
                    out.add(f);
                }
            }
            out.sort(Comparator.comparing(GeoFeature::id));
            return out;
        });
    }

    // ================================================================== geometry as a typed attribute of any object

    public void putObjectGeometry(String tenantId, String objectType, String objectId, String attribute, Geometry g,
                                  Set<String> tags, String actor) {
        tx.inTransaction(c -> {
            store.upsertObjectGeometry(c, tenantId, objectType, objectId, attribute == null ? "location" : attribute, g, tags, actor, now());
            return null;
        });
    }

    public Optional<GeoStore.ObjectGeometry> objectGeometry(String tenantId, String objectType, String objectId, String attribute) {
        return tx.inTransaction(c -> store.objectGeometry(c, tenantId, objectType, objectId, attribute == null ? "location" : attribute));
    }

    public void deleteObjectGeometry(String tenantId, String objectType, String objectId) {
        tx.inTransaction(c -> {
            store.deleteObjectGeometry(c, tenantId, objectType, objectId);
            return null;
        });
    }

    /** Nearest-N registry objects of a type (offices, depots, facilities), optionally filtered by tags. */
    public List<Nearest.Ranked> nearestObjects(String tenantId, String objectType, GeoPoint p, int n, double maxMeters,
                                               Set<String> requiredTags) {
        int want = Math.max(1, n);
        double cap = maxMeters > 0 ? maxMeters : DEFAULT_MAX_SEARCH_M;
        return tx.inTransaction(c -> {
            double r = Math.min(cap, 1_000);
            List<Nearest.Ranked> ranked;
            while (true) {
                List<Nearest.Located> located = new ArrayList<>();
                for (GeoStore.ObjectGeometry o : store.objectsByCentroidBox(c, tenantId, objectType, BBox.around(p, r))) {
                    located.add(new Nearest.Located(o.objectId(), o.centroid(), o.tags(), Map.of("attribute", o.attribute())));
                }
                ranked = Nearest.rank(p, located, requiredTags, 0, r, null);
                if (ranked.size() >= want || r >= cap) {
                    break;
                }
                r = Math.min(cap, r * 4);
            }
            return ranked.size() > want ? List.copyOf(ranked.subList(0, want)) : ranked;
        });
    }

    // ================================================================== geofences

    public Geofence defineFence(String tenantId, Geofence fence, String actor) {
        return tx.inTransaction(c -> {
            store.upsertFence(c, tenantId, fence, actor, now());
            audit(c, tenantId, "geo:fence:" + fence.id(), "GEO_FENCE_DEFINED", Map.of("fenceId", fence.id(), "kind", fence.kind().name(),
                    "actor", nz(actor)));
            return fence;
        });
    }

    public Optional<Geofence> fence(String tenantId, String fenceId) {
        return tx.inTransaction(c -> store.fence(c, tenantId, fenceId));
    }

    public void deactivateFence(String tenantId, String fenceId, String actor) {
        tx.inTransaction(c -> {
            store.deactivateFence(c, tenantId, fenceId, now());
            audit(c, tenantId, "geo:fence:" + fenceId, "GEO_FENCE_DEACTIVATED", Map.of("fenceId", fenceId, "actor", nz(actor)));
            return null;
        });
    }

    public GeofenceResult checkFence(String tenantId, String fenceId, GeoPoint p, Double accuracyMeters) {
        Geofence f = fence(tenantId, fenceId).orElseThrow(() -> new GeoException("NOT_FOUND", "no fence " + fenceId));
        return checkFence(tenantId, f, p, accuracyMeters);
    }

    /**
     * Checks a point against a fence. A FEATURE fence's reference may be a stored fence id, a feature version id, or
     * {@code layer/code} (current version).
     */
    public GeofenceResult checkFence(String tenantId, Geofence fence, GeoPoint p, Double accuracyMeters) {
        Geofence f = fence;
        Set<String> seen = new HashSet<>();
        while (f.kind() == Geofence.Kind.FEATURE) {
            if (!seen.add(f.featureRef())) {
                throw new GeoException("INVALID_FENCE", "fence reference cycle at " + f.featureRef());
            }
            f = resolveFeatureFence(tenantId, f);
        }
        return GeofenceChecker.check(f, p, accuracyMeters);
    }

    private Geofence resolveFeatureFence(String tenantId, Geofence f) {
        String ref = f.featureRef();
        Optional<Geofence> stored = fence(tenantId, ref);
        if (stored.isPresent()) {
            Geofence s = stored.get();
            return new Geofence(s.id(), s.kind(), s.geometry(), s.center(), s.radiusMeters(), s.featureRef(),
                    Math.max(s.toleranceMeters(), f.toleranceMeters()), Math.min(s.maxAccuracyMeters(), f.maxAccuracyMeters()),
                    s.attributes());
        }
        Optional<GeoFeature> feat = feature(tenantId, ref);
        if (feat.isEmpty() && ref.contains("/")) {
            int i = ref.indexOf('/');
            feat = featureByCode(tenantId, ref.substring(0, i), ref.substring(i + 1));
        }
        GeoFeature g = feat.orElseThrow(() -> new GeoException("NOT_FOUND", "fence reference " + ref + " not found"));
        if (!g.geometry().isAreal()) {
            GeoPoint centre = g.geometry().centroid();
            double radius = f.toleranceMeters() > 0 ? f.toleranceMeters() : 50;
            return new Geofence(f.id(), Geofence.Kind.CIRCLE, null, centre, radius, ref, 0, f.maxAccuracyMeters(), f.attributes());
        }
        return f.resolved(g.geometry());
    }

    // ================================================================== external layers

    public ExternalLayer registerExternal(String tenantId, ExternalLayer layer, String actor) {
        return tx.inTransaction(c -> {
            store.upsertExternal(c, tenantId, layer, actor, now());
            audit(c, tenantId, "geo:external:" + layer.ref(), "GEO_EXTERNAL_LAYER_REGISTERED", Map.of("ref", layer.ref(),
                    "protocol", layer.protocol().name(), "baseUrl", layer.baseUrl(), "actor", nz(actor)));
            return layer;
        });
    }

    public Optional<ExternalLayer> external(String tenantId, String ref) {
        return tx.inTransaction(c -> store.external(c, tenantId, ref));
    }

    public List<ExternalLayer> externals(String tenantId) {
        return tx.inTransaction(c -> store.externals(c, tenantId));
    }

    // ================================================================== helpers

    private List<GeoFeature> load(Connection c, String tenantId, List<String> ids) {
        List<GeoFeature> out = new ArrayList<>(ids.size());
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            GeoFeature f = cache.get(key(tenantId, id));
            if (f != null) {
                out.add(f);
            } else {
                missing.add(id);
            }
        }
        for (GeoFeature f : store.features(c, tenantId, missing)) {
            out.add(remember(tenantId, f));
        }
        return out;
    }

    private GeoFeature remember(String tenantId, GeoFeature f) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key(tenantId, f.id()), f);
        return f;
    }

    /** Drops cached geometry (after an out-of-band Liquibase load, for example). */
    public void clearCache() {
        cache.clear();
    }

    private static String key(String tenantId, String id) {
        return tenantId + '\u0000' + id;
    }

    private static int depth(JurisdictionLayer l, String level) {
        return l.levels().stream().filter(x -> x.code().equals(level)).mapToInt(BoundaryLevel::depth).findFirst().orElse(0);
    }

    private Instant now() {
        return clock.instant();
    }

    private void audit(Connection c, String tenantId, String rootId, String type, Map<String, ?> payload) {
        if (audit != null) {
            audit.append(c, tenantId, rootId, type, new LinkedHashMap<>(payload));
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
