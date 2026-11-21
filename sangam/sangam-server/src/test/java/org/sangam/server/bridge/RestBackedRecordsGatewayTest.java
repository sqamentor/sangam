// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.packs.ApiReadGateway;
import org.sangam.packs.core.Vars;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;
import org.sangam.packs.port.ReconciliationRequest;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RestBackedRecords;

/**
 * EC-06, owner decisions 265 and 266: the seam calls the connector gateway with the agreed shapes. list is a query of
 * operation list with {q, offset, limit} (q left out when absent) and answers output.records and output.total; create
 * is a submit of operation create with the operationId, {dataObject, record}, its canonical hash, business key
 * data-object:key and no process instance; lookup passes through. No definite list answer is CONNECTOR_UNAVAILABLE.
 */
class RestBackedRecordsGatewayTest {

    final List<ConnectorRequest> queries = new ArrayList<>();
    final List<ConnectorRequest> submits = new ArrayList<>();
    final List<String> lookups = new ArrayList<>();
    /** Outcome lookups made through the API-read entry (register P-3). */
    final List<String> readLookups = new java.util.ArrayList<>();
    ConnectorResult answer;

    final ConnectorGateway gateway = new ConnectorGateway() {
        @Override
        public ConnectorResult query(ConnectorRequest request) {
            throw new AssertionError("decision 320: a list is an API read and never uses ConnectorGateway.query");
        }

        @Override
        public ConnectorResult submit(ConnectorRequest request) {
            submits.add(request);
            return answer;
        }

        @Override
        public ConnectorResult lookup(String tenantId, String connector, String operationId) {
            lookups.add(tenantId + " " + connector + " " + operationId);
            return answer;
        }

        @Override
        public ConnectorResult cancel(String tenantId, String connector, String operationId, String reason) {
            throw new AssertionError("not used");
        }

        @Override
        public String openReconciliation(ReconciliationRequest request) {
            throw new AssertionError("not used");
        }

        @Override
        public CapabilityClass capabilityOf(String connector) {
            throw new AssertionError("not used");
        }
    };

    /** The API-read entry (decision 320); the shapes asserted below are those of the list request it receives. */
    final ApiReadGateway reads = new ApiReadGateway() {
        @Override
        public ConnectorResult queryReadOnly(ConnectorRequest request) {
            queries.add(request);
            return answer;
        }

        @Override
        public java.util.Optional<String> readOnlyRefusal(ConnectorRequest request) {
            throw new AssertionError("not used");
        }

        @Override
        public ConnectorResult lookupReadOnly(String tenantId, String connector, String operationId) {
            readLookups.add(tenantId + " " + connector + " " + operationId);
            return answer;
        }
    };

    final RestBackedRecordsGateway seam = new RestBackedRecordsGateway(gateway, reads);

    @Test
    void theListIsAQueryOfOperationListWithQOffsetLimit() {
        answer = ConnectorResult.completed(null, Map.of("records", List.of(Map.of("name", "Acme")), "total", 41));
        RestBackedRecords.Listing l = seam.list("t1", "erp.vendors", "acme", 20, 10);
        assertEquals(List.of(Map.of("name", "Acme")), l.records());
        assertEquals(41, l.total());
        ConnectorRequest q = queries.get(0);
        assertEquals("t1", q.tenantId());
        assertEquals("erp.vendors", q.connector());
        assertEquals("list", q.operation());
        assertEquals(RestBackedRecords.LIST_OPERATION, q.operation());
        assertNull(q.operationId());
        assertNull(q.processInstanceId());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("q", "acme");
        expected.put("offset", 20);
        expected.put("limit", 10);
        assertEquals(expected, q.payload());
        assertEquals(Vars.hash(expected), q.payloadHash());

        seam.list("t1", "erp.vendors", null, 0, 50);
        assertEquals(Map.of("offset", 0, "limit", 50), queries.get(1).payload(), "q left out when absent");

        answer = ConnectorResult.completed(null, Map.of("records", List.of()));
        assertNull(seam.list("t1", "erp.vendors", null, 0, 50).total(), "total is optional");
    }

    @Test
    void aFailedListCarriesTheGatewayReasonAndNoDefiniteAnswerIsUnavailable() {
        answer = ConnectorResult.failed("CONNECTOR_NOT_CONFIGURED", "no integration connector bound to erp.vendors");
        assertEquals("CONNECTOR_NOT_CONFIGURED", assertThrows(RestBackedRecords.ConnectorProblem.class,
                () -> seam.list("t1", "erp.vendors", null, 0, 10)).reasonCode());
        for (ConnectorResult r : List.of(ConnectorResult.pending(null), ConnectorResult.unknown("timeout"))) {
            answer = r;
            assertEquals(RestBackedRecordsGateway.CONNECTOR_UNAVAILABLE, assertThrows(
                    RestBackedRecords.ConnectorProblem.class, () -> seam.list("t1", "erp.vendors", null, 0, 10))
                    .reasonCode(), r.status().name());
        }
    }

    @Test
    void theCreateIsASubmitOfOperationCreateWithoutAProcessInstance() {
        answer = ConnectorResult.pending(null);
        RestBackedRecords.Outcome o = seam.submit("t1", "erp.vendors", "6f1c1f0e-0000-4000-8000-000000000001",
                "vendors", Map.of("name", "Acme"));
        assertEquals("PENDING", o.status());
        ConnectorRequest s = submits.get(0);
        assertEquals("t1", s.tenantId());
        assertEquals("erp.vendors", s.connector());
        assertEquals(RestBackedRecords.CREATE_OPERATION, s.operation());
        assertEquals("create", s.operation());
        assertEquals("6f1c1f0e-0000-4000-8000-000000000001", s.operationId());
        assertEquals("data-object:vendors", s.businessKey());
        assertNull(s.processInstanceId());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("dataObject", "vendors");
        expected.put("record", Map.of("name", "Acme"));
        assertEquals(expected, s.payload());
        assertEquals(Vars.hash(expected), s.payloadHash());
    }

    @Test
    void aJsonFieldWithANonIntegralNumberIsRefusedBeforeTheSubmit() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> seam.submit("t1", "erp.vendors", "op-1", "vendors",
                Map.of("meta", Map.of("score", 1.5d))));
        assertEquals(400, p.status());
        assertEquals("VALIDATION_FAILED", p.code());
        assertTrue(submits.isEmpty());
    }

    @Test
    void theLookupPassesThroughWithEveryField() {
        answer = new ConnectorResult(OperationStatus.FAILED, "DUPLICATE_VENDOR", "exists", "ERP-1",
                Map.of("record", Map.of("id", "v-1")));
        RestBackedRecords.Outcome o = seam.lookup("t1", "erp.vendors", "op-1");
        // register P-3: the outcome GET is an API read; ConnectorGateway.lookup may settle RETRY_REQUESTED and is not used
        assertEquals(List.of("t1 erp.vendors op-1"), readLookups);
        assertEquals(List.of(), lookups, "the outcome of a data-object create is never read through ConnectorGateway.lookup");
        assertEquals("FAILED", o.status());
        assertEquals("DUPLICATE_VENDOR", o.reasonCode());
        assertEquals("exists", o.message());
        assertEquals("ERP-1", o.externalRef());
        assertEquals(Map.of("record", Map.of("id", "v-1")), o.output());

        answer = null;
        assertEquals(RestBackedRecordsGateway.CONNECTOR_UNAVAILABLE, assertThrows(
                RestBackedRecords.ConnectorProblem.class, () -> seam.lookup("t1", "erp.vendors", "op-1")).reasonCode());
    }
}
