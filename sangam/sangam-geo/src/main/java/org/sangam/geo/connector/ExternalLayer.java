// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.connector;

import org.sangam.geo.GeoException;

import java.util.Map;

/**
 * Reference to a layer published by an external GIS (N13 {@code Layer}): a WMS layer for map display by the client,
 * or a WFS feature type that Sangam can query or import into a local jurisdiction layer. Sangam never renders maps.
 *
 * @param connectorId  id of the {@code SangamConnector} that reaches the service (registry entry, per tenant)
 * @param importLayer  local layer code that WFS imports write to (null = query only)
 */
public record ExternalLayer(String ref, Protocol protocol, String connectorId, String baseUrl, String remoteName,
                            String serviceVersion, String srs, String outputFormat, String importLayer,
                            Map<String, Object> attributes, boolean active) {

    public enum Protocol { WMS, WFS }

    public ExternalLayer {
        if (ref == null || ref.isBlank() || protocol == null || remoteName == null || remoteName.isBlank()) {
            throw new GeoException("INVALID_EXTERNAL_LAYER", "ref, protocol and remoteName are required");
        }
        if (baseUrl == null || !(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
            throw new GeoException("INVALID_EXTERNAL_LAYER", "baseUrl must be an http(s) URL");
        }
        connectorId = connectorId == null || connectorId.isBlank() ? (protocol == Protocol.WMS ? WmsConnector.TYPE : WfsConnector.TYPE)
                : connectorId;
        serviceVersion = serviceVersion == null ? (protocol == Protocol.WMS ? "1.3.0" : "2.0.0") : serviceVersion;
        srs = srs == null ? "EPSG:4326" : srs;
        outputFormat = outputFormat == null ? (protocol == Protocol.WMS ? "image/png" : "application/json") : outputFormat;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
