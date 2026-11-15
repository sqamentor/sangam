// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.packs.IntegrationConnectorGateway;
import org.sangam.integration.packs.PackConnectorBindings;
import org.sangam.ledger.LedgerConnector;
import org.sangam.rest.adapter.jdbc.RestBackedDataObjects;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.LowCodePort.DataObjectDef;
import org.sangam.rest.port.LowCodePort.DataObjectField;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.RestBackedRecords;

/**
 * Owner decisions 320 and 321, the case found in the code-scanning triage, from the data-object rules down to the
 * connector: a REST-backed data object whose {@code endpointRef} is a pack connector id bound to the ledger's
 * {@code closePeriod} (as {@code LedgerWiring} binds {@code ledger.closePeriod}). Listing its records is an API read
 * ({@code GET /data-objects/{key}/records}): it is refused with 422 CONNECTOR_OPERATION_NOT_READ_ONLY and the ledger
 * operation is not invoked; the definition is refused for the same reason; the balance read still lists.
 *
 * <p>The connector is the real {@link LedgerConnector}'s own {@code readOnly} answer in front of a recorder (no ledger
 * service: an invoke would be recorded, never run). The gateway, the bindings, the seam and the data-object rules are
 * the real ones; the HTTP layer and the definition's storage ({@code JdbcLowCodeAdapter}) are not part of this test.
 */
class RestBackedRecordsReadOnlyTest {

    static final RequestContext CTX = new RequestContext("t1", "reader-1", Set.of(), null, null, null, null, "127.0.0.1",
            "OIDC");

    final List<ConnectorRequest> invoked = new ArrayList<>();
    final LedgerConnector ledger = new LedgerConnector(null, null, null);
    /** Registry id {@code ledger}: the ledger connector's descriptor and readOnly answer; invokes are recorded. */
    final SangamConnector recordingLedger = new SangamConnector() {
        @Override
        public ConnectorDescriptor descriptor() {
            return ledger.descriptor();
        }

        @Override
        public boolean readOnly(ConnectorRequest request) {
            return ledger.readOnly(request);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            invoked.add(request);
            return ConnectorResult.Success.of(Map.of("records", List.of(Map.of("id", "a-1", "name", "Works")), "total", 1), null);
        }
    };

    final PackConnectorBindings bindings = new PackConnectorBindings()
            .bind("ledger.closePeriod", "ledger", "closePeriod")
            .bind("ledger.releaseHolds", "ledger", "releaseHolds")
            .bind("ledger.available", "ledger", "available");
    final IntegrationConnectorGateway gateway = new IntegrationConnectorGateway(null, null, null, bindings,
            new ConnectorRegistry().register(recordingLedger), null, null, null, null, null);
    final RestBackedRecordsGateway seam = new RestBackedRecordsGateway(gateway, gateway);
    final RestBackedDataObjects rules = new RestBackedDataObjects(() -> seam, null, null, 10_000);

    static DataObjectDef object(String endpointRef) {
        return new DataObjectDef("d1", "periods", "Periods", "REST", endpointRef, 1,
                List.of(new DataObjectField("name", "string", true, null, false)));
    }

    @Test
    void listingADataObjectBoundToClosePeriodIsRefusedAndThePeriodIsNotClosed() {
        for (String write : List.of("ledger.closePeriod", "ledger.releaseHolds")) {
            ApiProblem p = assertThrows(ApiProblem.class,
                    () -> rules.list(CTX, object(write), null, new Page.PageRequest(0, 20), false), write);
            assertEquals(422, p.status(), write);
            assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", p.code(), write);
            assertNull(p.retryAfterSeconds(), "permanent, not the retryable DATA_OBJECT_CONNECTOR_FAILED");
        }
        assertTrue(invoked.isEmpty(), "no ledger operation was invoked");
    }

    @Test
    void theSeamReportsTheGatewaysRefusalCode() {
        RestBackedRecords.ConnectorProblem e = assertThrows(RestBackedRecords.ConnectorProblem.class,
                () -> seam.list("t1", "ledger.closePeriod", null, 0, 20));
        assertEquals(RestBackedRecords.NOT_READ_ONLY, e.reasonCode());
        assertEquals(SangamConnector.OPERATION_NOT_READ_ONLY, RestBackedRecords.NOT_READ_ONLY, "one code on both sides");
        assertTrue(invoked.isEmpty());
    }

    @Test
    void theDefinitionIsRefusedForTheSameBinding() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> rules.requireReadOnlyList(CTX, object("ledger.closePeriod")));
        assertEquals(422, p.status());
        assertEquals("CONNECTOR_OPERATION_NOT_READ_ONLY", p.code());
        assertNotNull(seam.listRefusal("t1", "ledger.releaseHolds"));
        rules.requireReadOnlyList(CTX, object("ledger.available"));
        assertNull(seam.listRefusal("t1", "ledger.available"));
        assertNull(seam.listRefusal("t1", "erp.not-bound-yet"), "nothing can be said before the binding exists");
        assertTrue(invoked.isEmpty(), "a definition calls nothing");
    }

    @Test
    void aReadOnlyBindingStillLists() {
        Page<Map<String, Object>> page = rules.list(CTX, object("ledger.available"), null, new Page.PageRequest(0, 20), false);
        assertEquals(List.of(Map.of("id", "a-1", "name", "Works")), page.items());
        assertEquals(1, invoked.size());
        assertEquals("available", invoked.get(0).operation());
    }
}
