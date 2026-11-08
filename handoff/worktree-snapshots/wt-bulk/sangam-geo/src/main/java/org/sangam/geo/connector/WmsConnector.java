// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.connector;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.geo.GeoException;
import org.sangam.geo.model.BBox;
import org.sangam.geo.model.GeoJson;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OGC Web Map Service connector (WMS 1.3.0). Sangam does not render maps: {@code getMapUrl} only builds a signed-free
 * GetMap URL for the client's map widget (no network call); {@code getFeatureInfo} asks the service which features are
 * at a pixel (JSON info format) — for example to read a land-use class at a point; {@code getCapabilities} lists layers.
 */
public final class WmsConnector implements SangamConnector {

    public static final String TYPE = "gis-wms";

    private final String id;
    private final GisHttp http;
    private final String defaultLayers;

    public WmsConnector(String id, GisHttp.Config config, String defaultLayers) {
        this.id = id == null ? TYPE : id;
        this.http = new GisHttp(config);
        this.defaultLayers = defaultLayers;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(id, TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("getMapUrl", "getFeatureInfo", "getCapabilities"), ConnectorProfile.LIVE);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest req) {
        try {
            return switch (req.operation()) {
                case "getMapUrl" -> ConnectorResult.Success.of(Map.of("url", mapUri(req).toString()), null);
                case "getFeatureInfo", "invoke" -> featureInfo(req);
                case "getCapabilities" -> capabilities(req);
                default -> ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", "WMS operation " + req.operation());
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

    private Map<String, String> base(ConnectorRequest req, String request) {
        BBox box = WfsConnector.bbox(req.input().get("bbox"));
        if (box == null) {
            throw new IllegalArgumentException("bbox is required");
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("service", "WMS");
        p.put("version", "1.3.0");
        p.put("request", request);
        p.put("layers", req.str("layers", defaultLayers));
        p.put("styles", req.str("styles", ""));
        p.put("crs", "EPSG:4326");
        // WMS 1.3.0 with EPSG:4326: axis order lat,lon.
        p.put("bbox", box.minLat() + "," + box.minLon() + "," + box.maxLat() + "," + box.maxLon());
        p.put("width", String.valueOf(clamp(req.str("width", "512"))));
        p.put("height", String.valueOf(clamp(req.str("height", "512"))));
        if (p.get("layers") == null) {
            throw new IllegalArgumentException("layers is required");
        }
        return p;
    }

    private static int clamp(String v) {
        return Math.max(1, Math.min(4096, Integer.parseInt(v)));
    }

    private URI mapUri(ConnectorRequest req) {
        Map<String, String> p = base(req, "GetMap");
        p.put("format", req.str("format", "image/png"));
        p.put("transparent", req.str("transparent", "true"));
        return http.uri(req.str("baseUrl"), p);
    }

    private ConnectorResult featureInfo(ConnectorRequest req) {
        Map<String, String> p = base(req, "GetFeatureInfo");
        p.put("query_layers", req.str("queryLayers", p.get("layers")));
        p.put("info_format", req.str("infoFormat", "application/json"));
        p.put("i", req.required("i"));
        p.put("j", req.required("j"));
        p.put("feature_count", req.str("featureCount", "10"));
        URI uri = http.uri(req.str("baseUrl"), p);
        ConnectorResult denied = http.egressCheck(uri);
        if (denied != null) {
            return denied;
        }
        try {
            GisHttp.Response r = http.get(uri, p.get("info_format"), req.remainingMillis(http.config().timeout().toMillis()));
            if (r.status() / 100 != 2) {
                return GisHttp.statusFailure(r);
            }
            String exc = GisHttp.ogcException(r);
            if (exc != null) {
                return ConnectorResult.BusinessFailure.of("OGC_EXCEPTION", exc);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            if (r.contentType().contains("json")) {
                out.put("features", GeoJson.features(r.text()).stream()
                        .map(f -> GeoJson.feature(f.id(), f.geometry(), f.properties())).toList());
            } else {
                out.put("text", GisHttp.truncate(r.text(), 64 * 1024));
            }
            return ConnectorResult.Success.of(out, uri.getHost());
        } catch (GeoException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_RESPONSE", e.getMessage());
        } catch (Exception e) {
            return GisHttp.transportFailure(e);
        }
    }

    private ConnectorResult capabilities(ConnectorRequest req) {
        URI uri = http.uri(req.str("baseUrl"), Map.of("service", "WMS", "version", "1.3.0", "request", "GetCapabilities"));
        ConnectorResult denied = http.egressCheck(uri);
        if (denied != null) {
            return denied;
        }
        try {
            GisHttp.Response r = http.get(uri, "text/xml", req.remainingMillis(http.config().timeout().toMillis()));
            if (r.status() / 100 != 2) {
                return GisHttp.statusFailure(r);
            }
            return ConnectorResult.Success.of(Map.of("layers", GisHttp.capabilityEntries(GisHttp.parseXml(r.body()), "Layer")),
                    uri.getHost());
        } catch (org.xml.sax.SAXException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_RESPONSE", "capabilities are not well-formed XML");
        } catch (Exception e) {
            return GisHttp.transportFailure(e);
        }
    }
}
