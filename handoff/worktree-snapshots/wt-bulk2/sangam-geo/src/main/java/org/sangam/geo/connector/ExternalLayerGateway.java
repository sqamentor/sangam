// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.connector;

import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.Hashes;
import org.sangam.geo.GeoException;
import org.sangam.geo.GeoService;
import org.sangam.geo.boundary.BoundaryDataLoader;
import org.sangam.geo.boundary.BoundaryDataLoader.FeatureInput;
import org.sangam.geo.model.GeoJson;
import org.sangam.geo.model.Geometry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Queries a registered {@link ExternalLayer} through its connector and, for WFS layers with an {@code importLayer},
 * imports the returned features into a local jurisdiction layer (versioned, audited). The connector is resolved per
 * tenant by the integrator's {@code ConnectorRegistry} (bound as {@code connectorResolver}). These are reads; the
 * operationId is a UUIDv5 of the query so a repeated query is recognisable in the connector journal.
 */
public final class ExternalLayerGateway {

    private static final UUID NS = UUID.fromString("6f1b5a9e-3c1d-5e8a-9b0f-2a7c4d6e8f10");

    private final GeoService geo;
    private final BiFunction<String, String, SangamConnector> connectorResolver;

    /** @param connectorResolver (tenantId, connectorId) → connector */
    public ExternalLayerGateway(GeoService geo, BiFunction<String, String, SangamConnector> connectorResolver) {
        this.geo = Objects.requireNonNull(geo);
        this.connectorResolver = Objects.requireNonNull(connectorResolver);
    }

    public ConnectorResult query(String tenantId, String layerRef, String operation, Map<String, Object> input) {
        ExternalLayer x = geo.external(tenantId, layerRef).orElseThrow(() -> new GeoException("NOT_FOUND", "no external layer " + layerRef));
        if (!x.active()) {
            throw new GeoException("INACTIVE_LAYER", "external layer " + layerRef + " is inactive");
        }
        SangamConnector conn = connectorResolver.apply(tenantId, x.connectorId());
        if (conn == null) {
            throw new GeoException("NO_CONNECTOR", "connector " + x.connectorId() + " is not configured");
        }
        Map<String, Object> in = new LinkedHashMap<>(input == null ? Map.of() : input);
        in.putIfAbsent("baseUrl", x.baseUrl());
        if (x.protocol() == ExternalLayer.Protocol.WFS) {
            in.putIfAbsent("typeName", x.remoteName());
            in.putIfAbsent("outputFormat", x.outputFormat());
        } else {
            in.putIfAbsent("layers", x.remoteName());
            in.putIfAbsent("format", x.outputFormat());
        }
        String op = operation == null ? (x.protocol() == ExternalLayer.Protocol.WFS ? "getFeature" : "getMapUrl") : operation;
        UUID opId = Hashes.uuidV5(NS, tenantId + "|" + layerRef + "|" + op + "|" + new java.util.TreeMap<>(in));
        return conn.invoke(new ConnectorRequest(tenantId, opId, "geo-" + opId, 1, "geo:external:" + layerRef, layerRef, op, in,
                Instant.now().plusSeconds(60)));
    }

    /**
     * Fetches WFS features and imports them into the layer's {@code importLayer}. Property names for code/level/parent
     * come from the external layer attributes ({@code codeProperty}, {@code levelProperty}, {@code parentProperty},
     * {@code nameProperty}).
     */
    @SuppressWarnings("unchecked")
    public GeoService.ImportReport importFeatures(String tenantId, String layerRef, Map<String, Object> input, String actor) {
        ExternalLayer x = geo.external(tenantId, layerRef).orElseThrow(() -> new GeoException("NOT_FOUND", "no external layer " + layerRef));
        if (x.protocol() != ExternalLayer.Protocol.WFS || x.importLayer() == null) {
            throw new GeoException("NOT_IMPORTABLE", "only WFS layers with an importLayer can be imported");
        }
        ConnectorResult r = query(tenantId, layerRef, "getFeature", input);
        if (!(r instanceof ConnectorResult.Success s)) {
            throw new GeoException("GIS_QUERY_FAILED", "WFS query failed: " + r);
        }
        String codeProp = String.valueOf(x.attributes().getOrDefault("codeProperty", "code"));
        String levelProp = (String) x.attributes().get("levelProperty");
        String parentProp = (String) x.attributes().get("parentProperty");
        String nameProp = String.valueOf(x.attributes().getOrDefault("nameProperty", "name"));
        String fixedLevel = (String) x.attributes().get("level");
        List<FeatureInput> inputs = new ArrayList<>();
        for (Object o : (List<Object>) s.output().getOrDefault("features", List.of())) {
            Map<String, Object> f = (Map<String, Object>) o;
            Map<String, Object> props = (Map<String, Object>) f.getOrDefault("properties", Map.of());
            Object code = props.get(codeProp) != null ? props.get(codeProp) : f.get("id");
            if (code == null) {
                continue;
            }
            Geometry g = GeoJson.fromObject(f.get("geometry"));
            Map<String, String> names = props.get(nameProp) == null ? Map.of() : Map.of("und", String.valueOf(props.get(nameProp)));
            inputs.add(new FeatureInput(levelProp != null && props.get(levelProp) != null ? String.valueOf(props.get(levelProp)) : fixedLevel,
                    String.valueOf(code), parentProp == null || props.get(parentProp) == null ? null : String.valueOf(props.get(parentProp)),
                    names, Map.of("source", layerRef), g, null));
        }
        return geo.importPackage(tenantId, new BoundaryDataLoader.Package(null, inputs), x.importLayer(), actor);
    }
}
