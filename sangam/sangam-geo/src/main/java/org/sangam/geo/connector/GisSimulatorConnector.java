// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.connector;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.geo.model.BBox;
import org.sangam.geo.model.GeoJson;
import org.sangam.geo.model.GeoMath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Air-gapped simulator (ARCHITECTURE §1.9, SIMULATOR profile) answering the WFS {@code getFeature} /
 * {@code getCapabilities} and WMS {@code getMapUrl} / {@code getFeatureInfo} operations from in-memory feature types.
 * Lets packs and demos exercise GIS imports and lookups without any outbound call.
 */
public final class GisSimulatorConnector implements SangamConnector {

    private final String id;
    private final String type;
    private final Map<String, List<GeoJson.Feature>> featureTypes = new ConcurrentHashMap<>();

    public GisSimulatorConnector(String id, String type) {
        this.id = id;
        this.type = type == null ? WfsConnector.TYPE : type;
    }

    /** Loads a feature type from a GeoJSON FeatureCollection. */
    public GisSimulatorConnector load(String typeName, String featureCollectionJson) {
        featureTypes.put(typeName, List.copyOf(GeoJson.features(featureCollectionJson)));
        return this;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(id, type, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("getFeature", "getCapabilities", "getMapUrl", "getFeatureInfo"), ConnectorProfile.SIMULATOR);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest req) {
        try {
            return switch (req.operation()) {
                case "getCapabilities" -> ConnectorResult.Success.of(Map.of("featureTypes",
                        featureTypes.keySet().stream().sorted().map(n -> Map.of("name", n)).toList()), "simulator");
                case "getMapUrl" -> ConnectorResult.Success.of(Map.of("url", "simulator://wms/" + req.str("layers", "")), "simulator");
                case "getFeatureInfo" -> {
                    BBox box = WfsConnector.bbox(req.input().get("bbox"));
                    yield ConnectorResult.Success.of(Map.of("features", select(req.str("layers"), box == null ? null
                            : new BBox(box.center().lat(), box.center().lon(), box.center().lat(), box.center().lon()), 10)), "simulator");
                }
                case "getFeature", "invoke" -> {
                    String typeName = req.required("typeName");
                    if (!featureTypes.containsKey(typeName)) {
                        yield ConnectorResult.BusinessFailure.of("OGC_EXCEPTION", "unknown feature type " + typeName);
                    }
                    int count = Integer.parseInt(req.str("count", "1000"));
                    List<Map<String, Object>> fs = select(typeName, WfsConnector.bbox(req.input().get("bbox")), count);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("typeName", typeName);
                    out.put("numberReturned", fs.size());
                    out.put("features", fs);
                    yield ConnectorResult.Success.of(out, "simulator");
                }
                default -> ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", req.operation());
            };
        } catch (RuntimeException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
    }

    private List<Map<String, Object>> select(String typeName, BBox box, int count) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GeoJson.Feature f : featureTypes.getOrDefault(typeName, List.of())) {
            boolean hit = box == null || (box.heightDeg() == 0 && box.widthDeg() == 0
                    ? GeoMath.contains(f.geometry(), box.center()) : f.geometry().bbox().intersects(box));
            if (hit) {
                out.add(GeoJson.feature(f.id(), f.geometry(), f.properties()));
                if (out.size() >= count) {
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        ConnectorResult r = invoke(request);
        return r instanceof ConnectorResult.Success s ? LookupResult.Found.success(s.output(), s.providerRef())
                : new LookupResult.NotFound();
    }
}
