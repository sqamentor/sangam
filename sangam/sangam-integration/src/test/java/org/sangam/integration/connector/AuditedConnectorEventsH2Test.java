// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.gov.DigiLockerPushConnector;
import org.sangam.integration.connector.gov.DigiLockerRequesterConnector;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * Owner decision 299: DOCUMENT_FETCHED and DOCUMENT_PUSHED are written where the work happens, by the invoker in the
 * transaction that records the connector's successful outcome, once per operation; the DigiLocker connectors name them
 * with the payload X.4.0.3 describes (URI and issuer), without file content or the holder's DigiLocker id.
 */
class AuditedConnectorEventsH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";
    private static final String ROOT = "case-1";

    private final String url = "jdbc:h2:mem:auditedconn" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final Connection keepAlive;

    AuditedConnectorEventsH2Test() throws SQLException {
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws SQLException {
        keepAlive.close();
    }

    private Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private List<String> events() {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? ORDER BY seq",
                rs -> rs.getString(1), TENANT));
    }

    /** A pull-like connector that succeeds or fails as told and names one event per success. */
    private static final class Fake implements SangamConnector, AuditedConnector {
        private final boolean succeed;

        Fake(boolean succeed) {
            this.succeed = succeed;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("fake", "fake", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, List.of("pull"),
                    ConnectorProfile.SIMULATOR);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            return succeed ? new ConnectorResult.Success(Map.of("uri", "in.gov.x-1"), "ref-1", false)
                    : ConnectorResult.BusinessFailure.of("CONSENT_INVALID", "refused");
        }

        @Override
        public List<AuditedConnector.Event> auditEvents(String operation, Map<String, Object> input, Map<String, Object> output) {
            return List.of(new AuditedConnector.Event("DOCUMENT_FETCHED", Map.of("uri", output.get("uri"))));
        }
    }

    private ConnectorInvoker invoker(SangamConnector connector) {
        ReconciliationQueue queue = new ReconciliationQueue(D, null);
        return new ConnectorInvoker(tx(), new OperationLedger(D, queue), new ConnectorCallJournal(D),
                new ConnectorRegistry().register(connector), new AuditChain(D));
    }

    private static ConnectorInvoker.Invocation pull(UUID op) {
        return new ConnectorInvoker.Invocation(TENANT, ROOT, "activity-1", 0, null, "case:1", "fake", "pull", Map.of(),
                "attempt-1", 0L, Instant.now().plusSeconds(30), op);
    }

    @Test
    void theEventIsWrittenWithTheSuccessfulOutcomeOnceAndNotOnReplay() {
        ConnectorInvoker inv = invoker(new Fake(true));
        UUID op = UUID.randomUUID();
        assertInstanceOf(ConnectorInvoker.Completed.class, inv.invoke(pull(op)));
        assertEquals(List.of("OPERATION_DISPATCHED", "OPERATION_OUTCOME", "DOCUMENT_FETCHED"), events(),
                "in the outcome's transaction, on the root's chain");
        ConnectorInvoker.Outcome again = inv.invoke(pull(op));
        assertTrue(again.replayed(), "a settled operation is replayed");
        assertEquals(1, events().stream().filter("DOCUMENT_FETCHED"::equals).count(), "once per operation");
    }

    @Test
    void aFailedOperationWritesNoDocumentEvent() {
        ConnectorInvoker inv = invoker(new Fake(false));
        assertInstanceOf(ConnectorInvoker.Failed.class, inv.invoke(pull(UUID.randomUUID())));
        assertFalse(events().contains("DOCUMENT_FETCHED"));
    }

    @Test
    void aPullNamesOneFetchPerFetchedFileWithUriAndIssuer() {
        DigiLockerRequesterConnector c = new DigiLockerRequesterConnector(new DigiLockerRequesterConnector.Config("dl-req",
                URI.create("http://127.0.0.1:1"), null, "client", "secret-ref", URI.create("http://127.0.0.1:1/cb"), null,
                HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR, null, 0, null, 0, null));
        Map<String, Object> output = Map.of(
                "issued", List.of(Map.of("uri", "in.gov.rev-INCER-1", "issuer", "Revenue Department", "doctype", "INCER")),
                "files", List.of(
                        Map.of("uri", "in.gov.rev-INCER-1", "sha256", "ab12", "size", 10, "contentType", "application/pdf",
                                "contentBase64", "JVBERi0="),
                        Map.of("uri", "in.gov.rev-CASTE-2", "error", "HTTP_404")));
        List<AuditedConnector.Event> events = c.auditEvents(DigiLockerRequesterConnector.OP_PULL, Map.of(), output);
        assertEquals(1, events.size(), "a file that came back with an error was not fetched");
        AuditedConnector.Event e = events.get(0);
        assertEquals("DOCUMENT_FETCHED", e.type());
        assertEquals("in.gov.rev-INCER-1", e.payload().get("uri"));
        assertEquals("Revenue Department", e.payload().get("issuer"));
        assertEquals("INCER", e.payload().get("docType"));
        assertEquals("ab12", e.payload().get("sha256"));
        assertFalse(e.payload().containsKey("contentBase64"), "no file content in the audit");
        assertTrue(c.auditEvents(DigiLockerRequesterConnector.OP_VERIFY_ADULT, Map.of(), Map.of("adult", true)).isEmpty());
    }

    @Test
    void aPushNamesOnePushWithoutTheHoldersId() {
        DigiLockerPushConnector c = new DigiLockerPushConnector(new DigiLockerPushConnector.Config("dl-push",
                URI.create("http://127.0.0.1:1"), "in.gov.dept", HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR, null));
        List<AuditedConnector.Event> events = c.auditEvents(DigiLockerPushConnector.OP_PUSH,
                Map.of("digiLockerId", "holder-123", "uri", "in.gov.dept-CERTI-1", "docType", "CERTI"),
                Map.of("uri", "in.gov.dept-CERTI-1", "docType", "CERTI", "duplicate", false, "txn", "t-1"));
        assertEquals(1, events.size());
        AuditedConnector.Event e = events.get(0);
        assertEquals("DOCUMENT_PUSHED", e.type());
        assertEquals("in.gov.dept", e.payload().get("issuerOrgId"));
        assertEquals("in.gov.dept-CERTI-1", e.payload().get("uri"));
        assertEquals("CERTI", e.payload().get("docType"));
        assertEquals(false, e.payload().get("duplicate"));
        assertFalse(e.payload().containsValue("holder-123"), "the holder's DigiLocker id is not written");
        assertTrue(c.auditEvents("other", Map.of(), Map.of("uri", "x")).isEmpty());
    }
}
