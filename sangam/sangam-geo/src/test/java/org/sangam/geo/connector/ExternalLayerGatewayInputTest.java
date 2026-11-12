// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The egress URL of an external-layer query is always the registered one (X5 close-out, SSRF hardening). */
class ExternalLayerGatewayInputTest {

    private static ExternalLayer layer(ExternalLayer.Protocol protocol) {
        return new ExternalLayer("rivers", protocol, null, "https://gis.example.org/geoserver/ows", "hydro:rivers",
                null, null, null, null, Map.of(), true);
    }

    @Test
    void aCallerSuppliedBaseUrlIsReplacedByTheRegisteredOne() {
        Map<String, Object> input = new HashMap<>();
        input.put("baseUrl", "http://169.254.169.254/latest/meta-data");
        input.put("bbox", "0,0,1,1");

        Map<String, Object> in = ExternalLayerGateway.requestInput(layer(ExternalLayer.Protocol.WFS), input);

        assertEquals("https://gis.example.org/geoserver/ows", in.get("baseUrl"));
        assertEquals("0,0,1,1", in.get("bbox"), "other caller parameters pass through");
    }

    @Test
    void theLayerDefaultsFillWhatTheCallerDidNotSend() {
        Map<String, Object> wfs = ExternalLayerGateway.requestInput(layer(ExternalLayer.Protocol.WFS), null);
        assertEquals("https://gis.example.org/geoserver/ows", wfs.get("baseUrl"));
        assertEquals("hydro:rivers", wfs.get("typeName"));
        assertEquals("application/json", wfs.get("outputFormat"));

        Map<String, Object> wms = ExternalLayerGateway.requestInput(layer(ExternalLayer.Protocol.WMS), Map.of("format", "image/jpeg"));
        assertEquals("hydro:rivers", wms.get("layers"));
        assertEquals("image/jpeg", wms.get("format"), "a caller's format choice is kept");
    }
}
