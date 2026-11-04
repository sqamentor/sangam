// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.connector;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.geo.GeoException;
import org.sangam.geo.model.BBox;
import org.sangam.geo.model.GeoJson;
import org.w3c.dom.Document;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OGC Web Feature Service connector (WFS 2.0.0 / 1.1.0, GeoJSON output) through the core connector SPI (R71).
 * Operations:
 * <ul>
 *   <li>{@code getFeature}: {@code typeName}, optional {@code bbox} ({@code [minLon,minLat,maxLon,maxLat]} or
 *       {@code {minLat,minLon,maxLat,maxLon}}), {@code count}, {@code startIndex}, {@code propertyName}; returns
 *       {@code features} (GeoJSON Feature maps) and {@code numberReturned};</li>
 *   <li>{@code getCapabilities}: returns {@code featureTypes} [{name, title}].</li>
 * </ul>
 * Reads only: capability class (a) — the operationId is irrelevant to the provider, a retry is always safe, and lookup
 * simply repeats the read.
 */
public final class WfsConnector implements org.sangam.core.integration.SangamConnector {

    public static final String TYPE = "gis-wfs";

    private final String id;
    private final GisHttp http;
    private final String defaultTypeName;
    private final String version;

    public WfsConnector(String id, GisHttp.Config config, String defaultTypeName, String version) {
        this.id = id == null ? TYPE : id;
        this.http = new GisHttp(config);
        this.defaultTypeName = defaultTypeName;
        this.version = version == null ? "2.0.0" : version;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(id, TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, List.of("getFeature", "getCapabilities"),
                ConnectorProfile.LIVE);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        try {
            return switch (request.operation()) {
                case "getCapabilities" -> capabilities(request);
                case "getFeature", "invoke" -> getFeature(request);
                default -> ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", "WFS operation " + request.operation());
            };
        } catch (GeoException | IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        ConnectorResult r = invoke(request);
        return switch (r) {
            case ConnectorResult.Success s -> LookupResult.Found.success(s.output(), s.providerRef());
            case ConnectorResult.BusinessFailure b -> LookupResult.Found.failed(b.code(), b.output());
            case ConnectorResult.TransientFailure t -> new LookupResult.Unavailable(t.message());
            case ConnectorResult.OutcomeUnknown u -> new LookupResult.Unavailable(u.reason());
        };
    }

    private ConnectorResult getFeature(ConnectorRequest req) {
        String typeName = req.str("typeName", defaultTypeName);
        if (typeName == null) {
            throw new IllegalArgumentException("typeName is required");
        }
        boolean v2 = version.startsWith("2");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("service", "WFS");
        p.put("version", version);
        p.put("request", "GetFeature");
        p.put(v2 ? "typeNames" : "typeName", typeName);
        p.put("outputFormat", req.str("outputFormat", "application/json"));
        p.put("srsName", "EPSG:4326");
        BBox box = bbox(req.input().get("bbox"));
        if (box != null) {
            // EPSG:4326 axis order in WFS 2.0 / 1.1 is lat,lon; the CRS suffix makes it explicit.
            p.put("bbox", box.minLat() + "," + box.minLon() + "," + box.maxLat() + "," + box.maxLon() + ",urn:ogc:def:crs:EPSG::4326");
        }
        String count = req.str("count");
        if (count != null) {
            p.put(v2 ? "count" : "maxFeatures", String.valueOf(Math.min(10_000, Integer.parseInt(count))));
        }
        p.put("startIndex", req.str("startIndex"));
        p.put("propertyName", req.str("propertyName"));
        URI uri = http.uri(req.str("baseUrl"), p);
        ConnectorResult denied = http.egressCheck(uri);
        if (denied != null) {
            return denied;
        }
        try {
            GisHttp.Response r = http.get(uri, "application/json", req.remainingMillis(http.config().timeout().toMillis()));
            if (r.status() / 100 != 2) {
                return GisHttp.statusFailure(r);
            }
            String exc = GisHttp.ogcException(r);
            if (exc != null) {
                return ConnectorResult.BusinessFailure.of("OGC_EXCEPTION", exc);
            }
            List<Map<String, Object>> features = new ArrayList<>();
            for (GeoJson.Feature f : GeoJson.features(r.text())) {
                features.add(GeoJson.feature(f.id(), f.geometry(), f.properties()));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("typeName", typeName);
            out.put("numberReturned", features.size());
            out.put("features", features);
            return ConnectorResult.Success.of(out, uri.getHost());
        } catch (GeoException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_RESPONSE", e.getMessage());
        } catch (Exception e) {
            return GisHttp.transportFailure(e);
        }
    }

    private ConnectorResult capabilities(ConnectorRequest req) {
        URI uri = http.uri(req.str("baseUrl"), Map.of("service", "WFS", "version", version, "request", "GetCapabilities"));
        ConnectorResult denied = http.egressCheck(uri);
        if (denied != null) {
            return denied;
        }
        try {
            GisHttp.Response r = http.get(uri, "application/xml", req.remainingMillis(http.config().timeout().toMillis()));
            if (r.status() / 100 != 2) {
                return GisHttp.statusFailure(r);
            }
            Document d = GisHttp.parseXml(r.body());
            return ConnectorResult.Success.of(Map.of("featureTypes", GisHttp.capabilityEntries(d, "FeatureType")), uri.getHost());
        } catch (org.xml.sax.SAXException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_RESPONSE", "capabilities are not well-formed XML");
        } catch (Exception e) {
            return GisHttp.transportFailure(e);
        }
    }

    @SuppressWarnings("unchecked")
    static BBox bbox(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> l && l.size() == 4) {
            return new BBox(num(l.get(1)), num(l.get(0)), num(l.get(3)), num(l.get(2)));
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> mm = (Map<String, Object>) m;
            return new BBox(num(mm.get("minLat")), num(mm.get("minLon")), num(mm.get("maxLat")), num(mm.get("maxLon")));
        }
        if (v instanceof String s) {
            String[] p = s.split(",");
            if (p.length == 4) {
                return new BBox(Double.parseDouble(p[1]), Double.parseDouble(p[0]), Double.parseDouble(p[3]), Double.parseDouble(p[2]));
            }
        }
        throw new IllegalArgumentException("bbox must be [minLon,minLat,maxLon,maxLat]");
    }

    private static double num(Object o) {
        return org.sangam.geo.model.Geometries.num(o);
    }
}
