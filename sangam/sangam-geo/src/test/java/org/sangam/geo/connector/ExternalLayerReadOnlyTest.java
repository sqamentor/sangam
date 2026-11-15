// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Transactions;
import org.sangam.geo.GeoException;
import org.sangam.geo.GeoService;
import org.sangam.geo.index.SpatialIndex;

/**
 * Owner decisions 320 and 321: the external-layer query ({@code POST /external-layers/{ref}/query}, where the caller
 * names the operation and the registered layer names the connector) is an API read. The gateway invokes only a request
 * the connector itself declares read-only; anything else is refused with CONNECTOR_OPERATION_NOT_READ_ONLY (422) and
 * the connector is not invoked. The WFS, WMS and simulator connectors declare exactly the reads they run.
 */
class ExternalLayerReadOnlyTest {

    /** A registered connector of another kind: one read, one state change; it records every invoke. */
    static final class Books implements SangamConnector {
        final List<String> invoked = new ArrayList<>();
        final boolean declares;

        Books(boolean declares) {
            this.declares = declares;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("books", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                    List.of("balance", "closePeriod"), ConnectorProfile.LIVE);
        }

        @Override
        public boolean readOnly(ConnectorRequest request) {
            return declares && "balance".equals(request.operation());
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            invoked.add(request.operation());
            return ConnectorResult.Success.of(Map.of("ok", true), null);
        }
    }

    /** A gateway over one registered, active layer that names {@code connector}; the store is never reached. */
    static ExternalLayerGateway gateway(SangamConnector connector) {
        ExternalLayer layer = new ExternalLayer("rivers", ExternalLayer.Protocol.WFS, "c1",
                "https://gis.example.org/geoserver/ows", "hydro:rivers", null, null, null, null, Map.of(), true);
        Transactions tx = (Transactions) Proxy.newProxyInstance(ExternalLayerReadOnlyTest.class.getClassLoader(),
                new Class<?>[]{Transactions.class}, (p, m, a) -> Optional.of(layer));
        SpatialIndex index = (SpatialIndex) Proxy.newProxyInstance(ExternalLayerReadOnlyTest.class.getClassLoader(),
                new Class<?>[]{SpatialIndex.class}, (p, m, a) -> {
                    throw new AssertionError("the index is not used by an external-layer query");
                });
        return new ExternalLayerGateway(new GeoService(tx, index, null, null), (tenant, id) -> connector);
    }

    @Test
    void aStateChangingOperationNamedByTheCallerIsRefusedAndNotInvoked() {
        Books books = new Books(true);
        GeoException e = assertThrows(GeoException.class, () -> gateway(books).query("t1", "rivers", "closePeriod", Map.of()));
        assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", e.code());
        assertEquals(SangamConnector.OPERATION_NOT_READ_ONLY, e.code());
        assertEquals(422, e.status(), "a permanent refusal, not GIS_UNAVAILABLE");
        assertTrue(books.invoked.isEmpty(), "the write was not invoked");
    }

    @Test
    void aReadOnlyRequestIsInvoked() {
        Books books = new Books(true);
        assertInstanceOf(ConnectorResult.Success.class, gateway(books).query("t1", "rivers", "balance", Map.of()));
        assertEquals(List.of("balance"), books.invoked);
    }

    @Test
    void aConnectorThatDeclaresNothingIsRefused() {
        SangamConnector legacy = new SangamConnector() {
            @Override
            public ConnectorDescriptor descriptor() {
                return new ConnectorDescriptor("legacy", "x", CapabilityClass.C_NEITHER, List.of(), ConnectorProfile.LIVE);
            }

            @Override
            public ConnectorResult invoke(ConnectorRequest request) {
                throw new AssertionError("never invoked from an API read");
            }
        };
        GeoException e = assertThrows(GeoException.class, () -> gateway(legacy).query("t1", "rivers", null, Map.of()));
        assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", e.code());
    }

    @Test
    void theSimulatorAnswersThroughTheGatewayForItsReads() {
        GisSimulatorConnector sim = new GisSimulatorConnector("gis-sim-wfs", "wfs");
        assertInstanceOf(ConnectorResult.Success.class, gateway(sim).query("t1", "rivers", "getCapabilities", Map.of()));
        GeoException e = assertThrows(GeoException.class, () -> gateway(sim).query("t1", "rivers", "transaction", Map.of()));
        assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", e.code());
    }

    @Test
    void theGisConnectorsDeclareExactlyTheReadsTheyRun() {
        GisHttp.Config cfg = new GisHttp.Config("https://gis.example.org/ows", null, null, null, 0);
        Map<SangamConnector, Map<String, Boolean>> table = new LinkedHashMap<>();
        table.put(new WfsConnector("wfs", cfg, "hydro:rivers", null), Map.of("getFeature", true, "getCapabilities", true,
                "invoke", true, "transaction", false, "getMapUrl", false, "closePeriod", false));
        table.put(new WmsConnector("wms", cfg, "hydro"), Map.of("getMapUrl", true, "getFeatureInfo", true,
                "getCapabilities", true, "invoke", true, "getFeature", false, "closePeriod", false));
        table.put(new GisSimulatorConnector("sim", "wfs"), Map.of("getFeature", true, "getCapabilities", true,
                "getMapUrl", true, "getFeatureInfo", true, "invoke", true, "transaction", false, "closePeriod", false));
        table.forEach((connector, rows) -> {
            for (String operation : connector.descriptor().operations()) {
                assertTrue(rows.containsKey(operation), connector.descriptor().id() + " has a row for " + operation);
            }
            rows.forEach((operation, readOnly) -> assertEquals(readOnly, connector.readOnly(new ConnectorRequest("t1",
                    UUID.randomUUID(), "a1", 1, null, null, operation, Map.of(), null)),
                    connector.descriptor().id() + " " + operation));
        });
    }
}
