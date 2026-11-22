// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Jdbc;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;

/**
 * Register P-3 (CodeQL alert 596, CSRF report bucket 4; owner decision 2026-11-20: move the settle off the GET).
 * {@code ConnectorGateway.lookup} marks an UNKNOWN operation of a process instance {@code RETRY_REQUESTED} when the
 * external system has no record of it, so that the process's next submit dispatches it again. The data-object record
 * operation {@code GET} read its outcome through the same method, so a GET could make that change (the case: an
 * operationId shared between a process step and a data-object create). An API read now asks
 * {@link ApiReadGateway#lookupReadOnly}, which gives the outcome and never changes the row; the pack path keeps
 * {@code lookup} and its settle.
 */
class ApiReadLookupNeverSettlesH2Test {

    /** A class (a) connector whose outcome lookup answers what the test sets. */
    static final class Records implements SangamConnector {
        LookupResult answer = new LookupResult.NotFound();
        final List<ConnectorRequest> lookedUp = new ArrayList<>();

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("records-http", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                    List.of("create"), ConnectorProfile.LIVE);
        }

        @Override
        public org.sangam.core.integration.ConnectorResult invoke(ConnectorRequest request) {
            throw new AssertionError("an outcome lookup never invokes the operation again");
        }

        @Override
        public LookupResult lookup(ConnectorRequest request) {
            lookedUp.add(request);
            return answer;
        }
    }

    private final PackH2Fixture f;
    private final Records connector = new Records();
    private final ConnectorCallJournal journal = new ConnectorCallJournal(PackH2Fixture.D);
    private final IntegrationConnectorGateway gateway;

    ApiReadLookupNeverSettlesH2Test() throws SQLException {
        f = new PackH2Fixture("apireadlookup");
        gateway = new IntegrationConnectorGateway(f.tx(), null, f.store, new PackConnectorBindings(),
                new ConnectorRegistry().register(connector), journal, f.queue,
                new OutboxWriter(PackH2Fixture.D, (t, e) -> List.of()), BackpressureGate.open(), null);
    }

    @AfterEach
    void close() throws SQLException {
        f.close();
    }

    /** An UNKNOWN operation whose connector call is on record, as after a dispatch that timed out. */
    private PackOperationStore.Row unknown(String processInstanceId, String businessKey) {
        PackOperationStore.Row r = f.operation(processInstanceId, businessKey, PackOperationStore.Status.UNKNOWN);
        f.tx().inTransaction(c -> journal.recordAttempt(c, r.ledgerOperationId(), PackH2Fixture.TENANT, "records-http", "create",
                r.rootId(), r.businessKey(), "{\"record\":{\"name\":\"a\"}}"));
        return f.row(r.operationId());
    }

    /** Every row of PACK_OPERATION with every column. */
    private List<String> table() {
        return f.tx().inTransaction(c -> Jdbc.query(c, "SELECT * FROM PACK_OPERATION ORDER BY operation_id", rs -> {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                row.append(rs.getMetaData().getColumnName(i)).append('=').append(rs.getString(i)).append(';');
            }
            return row.toString();
        }));
    }

    @Test
    void anApiReadOfAProcessOperationTheExternalSystemDoesNotKnowLeavesTheRowUnknown() {
        PackOperationStore.Row r = unknown("pi-1", "invoice:42");
        List<String> before = table();

        ConnectorResult got = gateway.lookupReadOnly(PackH2Fixture.TENANT, "records", r.operationId());

        assertEquals(1, connector.lookedUp.size(), "the external system was asked");
        assertEquals(before, table(), "an API read changed PACK_OPERATION");
        assertEquals(PackOperationStore.Status.UNKNOWN, f.row(r.operationId()).status());
        assertEquals(r, f.row(r.operationId()));
        assertEquals(OperationStatus.UNKNOWN, got.status(), "nothing was settled, so the outcome is still unknown: " + got);
    }

    @Test
    void theProcessPathStillRequestsTheRetry() {
        PackOperationStore.Row r = unknown("pi-1", "invoice:42");

        ConnectorResult got = gateway.lookup(PackH2Fixture.TENANT, "records", r.operationId());

        assertEquals(OperationStatus.FAILED, got.status());
        assertEquals("NOT_FOUND", got.reasonCode());
        PackOperationStore.Row after = f.row(r.operationId());
        assertEquals(PackOperationStore.Status.RETRY_REQUESTED, after.status(), "the pack's next submit dispatches it");
        assertEquals("NOT_FOUND", after.reasonCode());
    }

    @Test
    void aDataObjectOperationGetsTheSameAnswerOnBothPathsAndStaysUnknown() {
        // decision 290: no process instance re-submits, so neither path settles; reconciliation re-dispatches
        PackOperationStore.Row r = unknown(null, "data-object:leave");
        List<String> before = table();

        ConnectorResult read = gateway.lookupReadOnly(PackH2Fixture.TENANT, "records", r.operationId());
        ConnectorResult pack = gateway.lookup(PackH2Fixture.TENANT, "records", r.operationId());

        assertEquals(OperationStatus.FAILED, read.status());
        assertEquals("NOT_FOUND", read.reasonCode());
        assertEquals(pack, read);
        assertEquals(before, table());
    }

    @Test
    void anApiReadGivesEveryOtherOutcomeAsTheLookupDoesAndChangesNothing() {
        PackOperationStore.Row found = unknown("pi-2", "invoice:43");
        PackOperationStore.Row pending = f.operation("pi-3", "invoice:44", PackOperationStore.Status.PENDING);
        PackOperationStore.Row done = f.operation(null, "data-object:leave", PackOperationStore.Status.PENDING);
        f.tx().inTransaction(c -> f.store.settle(c, PackH2Fixture.TENANT, done.operationId(), PackOperationStore.Status.COMPLETED,
                null, "ext-9", "{\"record\":{\"id\":\"rec-9\"}}"));
        List<String> before = table();

        connector.answer = new LookupResult.Unavailable("down");
        assertEquals(OperationStatus.PENDING, gateway.lookupReadOnly(PackH2Fixture.TENANT, "records", found.operationId()).status());
        // no connector call on record: a pending operation is pending, nothing is asked
        int asked = connector.lookedUp.size();
        assertEquals(OperationStatus.PENDING, gateway.lookupReadOnly(PackH2Fixture.TENANT, "records", pending.operationId()).status());
        assertEquals(asked, connector.lookedUp.size());
        ConnectorResult completed = gateway.lookupReadOnly(PackH2Fixture.TENANT, "records", done.operationId());
        assertEquals(OperationStatus.COMPLETED, completed.status());
        assertEquals("ext-9", completed.externalRef());
        assertEquals(gateway.lookup(PackH2Fixture.TENANT, "records", done.operationId()), completed);
        ConnectorResult none = gateway.lookupReadOnly(PackH2Fixture.TENANT, "records", UUID.randomUUID().toString());
        assertEquals("NOT_FOUND", none.reasonCode());
        assertTrue(none.status() == OperationStatus.FAILED, none.toString());

        assertEquals(before, table(), "an API read changed PACK_OPERATION");
    }
}
