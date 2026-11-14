// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.LowCodePort.DataObjectDef;
import org.sangam.rest.port.LowCodePort.DataObjectField;
import org.sangam.rest.port.LowCodePort.RecordOperation;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.RestBackedRecords;

/**
 * EC-06, owner decisions 265 and 266: REST-backed list over the connector query {@code list} with {q, offset, limit},
 * records reduced to id plus the definition fields and masked as for DB; create answered PENDING with a new
 * operationId, recorded and audited before the submit; the outcome readable only for this data object's own
 * operations; 501 without the connector seam, 503 DATA_OBJECT_CONNECTOR_FAILED when the connector gives no usable
 * answer.
 */
class RestBackedDataObjectsTest {

    static final RequestContext CTX = new RequestContext("t1", "user-1", Set.of(), null, null, "key-1", null,
            "127.0.0.1", "OIDC");
    static final DataObjectDef VENDORS = new DataObjectDef("d1", "vendors", "Vendors", "REST", "erp.vendors", 1,
            List.of(new DataObjectField("name", "string", true, null, false),
                    new DataObjectField("pan", "string", false, null, true)));

    final List<String> calls = new ArrayList<>();
    final List<Map<String, ?>> audited = new ArrayList<>();
    final Map<String, String> log = new LinkedHashMap<>();   // operationId -> data object key

    RestBackedRecords.Listing listing;
    RestBackedRecords.Outcome submitted = new RestBackedRecords.Outcome("PENDING", null, null, null, Map.of());
    RestBackedRecords.Outcome lookedUp;
    RuntimeException failure;

    final RestBackedRecords connector = new RestBackedRecords() {
        @Override
        public Listing list(String tenantId, String endpointRef, String q, int offset, int limit) {
            calls.add("list " + tenantId + " " + endpointRef + " " + q + " " + offset + " " + limit);
            if (failure != null) {
                throw failure;
            }
            return listing;
        }

        @Override
        public Outcome submit(String tenantId, String endpointRef, String operationId, String dataObjectKey,
                              Map<String, Object> record) {
            calls.add("submit " + tenantId + " " + endpointRef + " " + operationId + " " + dataObjectKey + " "
                    + record + " logged=" + log.containsKey(operationId) + " audited=" + audited.size());
            if (failure != null) {
                throw failure;
            }
            return submitted;
        }

        @Override
        public Outcome lookup(String tenantId, String endpointRef, String operationId) {
            calls.add("lookup " + tenantId + " " + endpointRef + " " + operationId);
            return lookedUp;
        }
    };

    AuditPort audit() {
        return (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[]{AuditPort.class},
                (p, m, a) -> {
                    if (!"record".equals(m.getName())) {
                        throw new AssertionError("not used: " + m.getName());
                    }
                    audited.add(Map.of("chain", a[1], "type", a[2], "payload", a[3]));
                    return "audit-1";
                });
    }

    RestBackedDataObjects rules(Supplier<RestBackedRecords> seam, long countCap) {
        return new RestBackedDataObjects(seam, new RestBackedDataObjects.OperationLog() {
            @Override
            public void submitted(RequestContext ctx, String operationId, String dataObjectKey, String endpointRef) {
                log.put(operationId, dataObjectKey);
            }

            @Override
            public boolean submitted(RequestContext ctx, String operationId, String dataObjectKey) {
                return dataObjectKey.equals(log.get(operationId));
            }
        }, audit(), countCap);
    }

    RestBackedDataObjects rules() {
        return rules(() -> connector, 10_000);
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static ApiProblem connectorFailed(Runnable r) {
        ApiProblem p = assertThrows(ApiProblem.class, r::run);
        assertEquals(503, p.status());
        assertEquals(RestBackedDataObjects.CONNECTOR_FAILED, p.code());
        return p;
    }

    // ------------------------------------------------------------------ list (decision 266)

    @Test
    void theListAsksForQOffsetLimitAndKeepsOnlyIdAndDefinitionFieldsMasked() {
        listing = new RestBackedRecords.Listing(List.of(row("id", "v-1", "name", "Acme", "pan", "ABCDE1234F",
                "internalScore", 9)), 7);
        Page<Map<String, Object>> page = rules().list(CTX, VENDORS, "acme", new Page.PageRequest(1, 2), false);
        assertEquals(List.of("list t1 erp.vendors acme 2 2"), calls);
        assertEquals(List.of(row("id", "v-1", "name", "Acme", "pan", "***")), page.items(),
                "unknown fields dropped, sensitive masked");
        assertEquals(7, page.total());
        assertFalse(page.capped());

        Page<Map<String, Object>> admin = rules().list(CTX, VENDORS, null, new Page.PageRequest(0, 2), true);
        assertEquals("ABCDE1234F", admin.items().get(0).get("pan"), "unmasked for the groups that see DB values");
        assertEquals("list t1 erp.vendors null 0 2", calls.get(1));
    }

    @Test
    void withoutATotalTheTotalIsWhatWasSeenAndAFullPageIsCapped() {
        listing = new RestBackedRecords.Listing(List.of(row("name", "A"), row("name", "B"), row("name", "C")), null);
        Page<Map<String, Object>> full = rules().list(CTX, VENDORS, null, new Page.PageRequest(2, 2), false);
        assertEquals(List.of(row("name", "A"), row("name", "B")), full.items(), "never more than the limit");
        assertEquals(6, full.total());
        assertTrue(full.capped(), "the page came back full: more may exist");

        listing = new RestBackedRecords.Listing(List.of(row("name", "A")), null);
        Page<Map<String, Object>> last = rules().list(CTX, VENDORS, null, new Page.PageRequest(2, 2), false);
        assertEquals(5, last.total());
        assertFalse(last.capped());
    }

    @Test
    void aTotalAboveTheCountCapIsCapped() {
        listing = new RestBackedRecords.Listing(List.of(), "250");
        Page<Map<String, Object>> page = rules(() -> connector, 100).list(CTX, VENDORS, null,
                new Page.PageRequest(0, 50), false);
        assertEquals(100, page.total());
        assertTrue(page.capped());
    }

    @Test
    void anUnusableListAnswerIs503WithItsReason() {
        listing = new RestBackedRecords.Listing("not an array", null);
        assertEquals(RestBackedDataObjects.OUTPUT_INVALID, connectorFailed(() -> rules().list(CTX, VENDORS, null,
                new Page.PageRequest(0, 10), false)).extensions().get("reasonCode"));
        listing = new RestBackedRecords.Listing(List.of("text"), null);
        assertEquals(RestBackedDataObjects.OUTPUT_INVALID, connectorFailed(() -> rules().list(CTX, VENDORS, null,
                new Page.PageRequest(0, 10), false)).extensions().get("reasonCode"));
        listing = new RestBackedRecords.Listing(List.of(), -1);
        assertEquals(RestBackedDataObjects.OUTPUT_INVALID, connectorFailed(() -> rules().list(CTX, VENDORS, null,
                new Page.PageRequest(0, 10), false)).extensions().get("reasonCode"));
        listing = new RestBackedRecords.Listing(List.of(), "1.5");
        assertEquals(RestBackedDataObjects.OUTPUT_INVALID, connectorFailed(() -> rules().list(CTX, VENDORS, null,
                new Page.PageRequest(0, 10), false)).extensions().get("reasonCode"));

        failure = new RestBackedRecords.ConnectorProblem("CONNECTOR_NOT_CONFIGURED", "no binding");
        ApiProblem p = connectorFailed(() -> rules().list(CTX, VENDORS, null, new Page.PageRequest(0, 10), false));
        assertEquals("CONNECTOR_NOT_CONFIGURED", p.extensions().get("reasonCode"));
        assertEquals(RestBackedDataObjects.RETRY_AFTER_SECONDS, p.retryAfterSeconds());
    }

    // ------------------------------------------------------------------ create (decision 265)

    @Test
    void aCreateIsSubmittedWithANewOperationIdLoggedAndAuditedFirstAndAnsweredPending() {
        RecordOperation a = rules().create(CTX, VENDORS, row("name", "Acme", "pan", "ABCDE1234F"));
        assertEquals("PENDING", a.status());
        assertEquals("vendors", a.dataObjectKey());
        assertNull(a.record(), "the record comes only with COMPLETED");
        assertNull(a.failureCode());
        assertEquals(a.operationId(), UUID.fromString(a.operationId()).toString(), "a UUID, the ledger id itself");
        assertEquals("vendors", log.get(a.operationId()));
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).startsWith("submit t1 erp.vendors " + a.operationId() + " vendors "));
        assertTrue(calls.get(0).endsWith(" logged=true audited=1"), "recorded and audited before the submit");
        assertEquals(1, audited.size());
        assertEquals("data:vendors", audited.get(0).get("chain"));
        assertEquals("DATA_OBJECT_RECORD_CREATE_ACCEPTED", audited.get(0).get("type"), "decision 289");
        assertEquals(RestBackedDataObjects.ACCEPTED_EVENT, audited.get(0).get("type"));
        Map<?, ?> payload = (Map<?, ?>) audited.get(0).get("payload");
        assertEquals(a.operationId(), payload.get("operationId"));
        assertEquals("PENDING", payload.get("status"));
        assertEquals("REST", payload.get("backing"));

        RecordOperation again = rules().create(CTX, VENDORS, row("name", "Acme"));
        assertFalse(again.operationId().equals(a.operationId()), "every create is a new operation");
    }

    @Test
    void anImmediateOutcomeIsStillAnsweredPendingAndReadThroughTheOperation() {
        submitted = new RestBackedRecords.Outcome("COMPLETED", null, null, "ERP-9", Map.of("record", Map.of("id", "v-9")));
        assertEquals("PENDING", rules().create(CTX, VENDORS, row("name", "Acme")).status());
    }

    @Test
    void aRefusedSubmitIs503WithTheGatewayReason() {
        submitted = new RestBackedRecords.Outcome("FAILED", "IDEMPOTENCY_PAYLOAD_MISMATCH", "different payload", null,
                Map.of());
        assertEquals("IDEMPOTENCY_PAYLOAD_MISMATCH", connectorFailed(() -> rules().create(CTX, VENDORS,
                row("name", "Acme"))).extensions().get("reasonCode"));
        failure = new RestBackedRecords.ConnectorProblem("CONNECTOR_UNAVAILABLE", "down");
        assertEquals("CONNECTOR_UNAVAILABLE", connectorFailed(() -> rules().create(CTX, VENDORS,
                row("name", "Acme"))).extensions().get("reasonCode"));
    }

    // ------------------------------------------------------------------ operation (decision 265)

    @Test
    void theOutcomeIsReadOnlyForThisDataObjectsOwnOperation() {
        RecordOperation a = rules().create(CTX, VENDORS, row("name", "Acme"));
        lookedUp = new RestBackedRecords.Outcome("COMPLETED", null, null, "ERP-1",
                Map.of("record", row("id", "v-1", "name", "Acme", "pan", "ABCDE1234F", "extra", "x")));
        RecordOperation o = rules().operation(CTX, VENDORS, a.operationId(), false);
        assertEquals("COMPLETED", o.status());
        assertEquals("vendors", o.dataObjectKey());
        assertEquals(row("id", "v-1", "name", "Acme", "pan", "***"), o.record());
        assertNull(o.failureCode());
        assertEquals("lookup t1 erp.vendors " + a.operationId(), calls.get(calls.size() - 1));

        int before = calls.size();
        ApiProblem unknown = assertThrows(ApiProblem.class, () -> rules().operation(CTX, VENDORS,
                UUID.randomUUID().toString(), false));
        assertEquals(404, unknown.status());
        log.put("op-other", "contracts");
        assertEquals(404, assertThrows(ApiProblem.class, () -> rules().operation(CTX, VENDORS, "op-other", false))
                .status(), "an operation of another data object is not readable here");
        assertEquals(before, calls.size(), "no lookup for an operation this data object did not submit");
    }

    @Test
    void theGatewayStatesMapToTheContractStatesAndOnlyCompletedCarriesARecord() {
        RecordOperation a = rules().create(CTX, VENDORS, row("name", "Acme"));
        RestBackedDataObjects r = rules();

        lookedUp = new RestBackedRecords.Outcome("PENDING", null, null, null, Map.of("record", row("name", "Acme")));
        RecordOperation p = r.operation(CTX, VENDORS, a.operationId(), false);
        assertEquals("PENDING", p.status());
        assertNull(p.record());
        assertNull(p.failureCode());

        lookedUp = new RestBackedRecords.Outcome("FAILED", "DUPLICATE_VENDOR", "exists", null, Map.of());
        RecordOperation f = r.operation(CTX, VENDORS, a.operationId(), false);
        assertEquals("FAILED", f.status());
        assertEquals("DUPLICATE_VENDOR", f.failureCode());
        assertNull(f.record());

        lookedUp = new RestBackedRecords.Outcome("FAILED", null, "confirmed by lookup", null, Map.of());
        assertEquals(RestBackedDataObjects.DOWNSTREAM_FAILED, r.operation(CTX, VENDORS, a.operationId(), false)
                .failureCode(), "FAILED always carries a failureCode");

        lookedUp = new RestBackedRecords.Outcome("FAILED", "NOT_FOUND", "no record", null, Map.of());
        RecordOperation notYet = r.operation(CTX, VENDORS, a.operationId(), false);
        assertEquals("PENDING", notYet.status(), "the external system has no record of it yet");
        assertNull(notYet.failureCode());

        lookedUp = new RestBackedRecords.Outcome("UNKNOWN", "OUTCOME_UNKNOWN", "in reconciliation", null, Map.of());
        RecordOperation unknown = r.operation(CTX, VENDORS, a.operationId(), false);
        assertEquals("UNKNOWN", unknown.status());
        assertNull(unknown.failureCode());

        lookedUp = new RestBackedRecords.Outcome("CANCELLED", null, "no effect", null, Map.of());
        RecordOperation cancelled = r.operation(CTX, VENDORS, a.operationId(), false);
        assertEquals("FAILED", cancelled.status(), "the contract has no CANCELLED state");
        assertEquals("CANCELLED", cancelled.failureCode());

        lookedUp = new RestBackedRecords.Outcome("COMPLETED", null, null, null, Map.of());
        assertNull(r.operation(CTX, VENDORS, a.operationId(), false).record(), "no output.record, no record");

        lookedUp = new RestBackedRecords.Outcome("SOMETHING", null, null, null, Map.of());
        assertEquals(RestBackedDataObjects.OUTPUT_INVALID, connectorFailed(() -> r.operation(CTX, VENDORS,
                a.operationId(), false)).extensions().get("reasonCode"));
    }

    // ------------------------------------------------------------------ without sangam-integration

    @Test
    void withoutTheConnectorSeamEveryRestBackedCallIs501() {
        RestBackedDataObjects none = rules(() -> null, 10_000);
        for (Runnable r : List.<Runnable>of(
                none::requireInstalled,
                () -> none.list(CTX, VENDORS, null, new Page.PageRequest(0, 10), false),
                () -> none.create(CTX, VENDORS, row("name", "Acme")),
                () -> none.operation(CTX, VENDORS, "op-1", false))) {
            ApiProblem p = assertThrows(ApiProblem.class, r::run);
            assertEquals(501, p.status());
            assertEquals("CAPABILITY_NOT_INSTALLED", p.code());
        }
        assertTrue(calls.isEmpty());
        assertTrue(log.isEmpty(), "nothing recorded without the seam");
        assertTrue(audited.isEmpty());
    }
}
