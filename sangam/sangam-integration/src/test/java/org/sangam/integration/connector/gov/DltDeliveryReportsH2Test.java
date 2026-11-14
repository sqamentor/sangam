// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.publish.WebhookSignature;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * Owner decision 304: DLT SMS delivery reports by signed webhook and by the status-lookup fallback. Unsigned, wrongly
 * signed or stale (replayed) requests are refused; a report sent again has no effect; reports in any order move the
 * message's state only forward; NOTIFICATION_DELIVERED is written exactly once per message, whichever path reports it.
 */
class DltDeliveryReportsH2Test {

    private static final Dialect D = Dialect.POSTGRESQL;
    private static final String TENANT = "T1";
    private static final String ROOT = "case-1";
    private static final String SECRET = "test-signing-value";
    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");

    private final String url = "jdbc:h2:mem:dltdlr" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final Connection keepAlive;
    private final ConnectorCallJournal journal = new ConnectorCallJournal(D);
    private final Sms sms = new Sms("sms-1", CapabilityClass.B_LOOKUP_ONLY);
    private final ConnectorRegistry registry = new ConnectorRegistry().register(sms).register(new Sms("sms-c", CapabilityClass.C_NEITHER));
    private final DltDeliveryReports reports;

    DltDeliveryReportsH2Test() throws SQLException {
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        reports = new DltDeliveryReports(D, tx(), registry, journal, new AuditChain(D),
                SecretResolver.of(Map.of("dlr-ref", SECRET)),
                new DltDeliveryReports.Settings(Map.of("sms-1", "dlr-ref"), Duration.ofMinutes(5), 0, 0), Clock.fixed(NOW, ZoneOffset.UTC));
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

    /** A DLT SMS connector stand-in: the type the intake checks, and a status lookup answering as told. */
    private static final class Sms implements SangamConnector {
        private final String id;
        private final CapabilityClass cls;
        volatile LookupResult lookup = new LookupResult.Unavailable("not yet");

        Sms(String id, CapabilityClass cls) {
            this.id = id;
            this.cls = cls;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor(id, DltSmsConnector.TYPE, cls, List.of(DltSmsConnector.OP_SEND), ConnectorProfile.SIMULATOR);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LookupResult lookup(ConnectorRequest request) {
            return lookup;
        }
    }

    /** An accepted send of {@code sms-1}: its CONNECTOR_CALL row, as the invoker leaves it. */
    private UUID sent(String messageId) {
        UUID op = UUID.randomUUID();
        tx().inTransaction(c -> {
            journal.recordAttempt(c, op, TENANT, "sms-1", DltSmsConnector.OP_SEND, ROOT, "notice:1",
                    "{\"templateId\":\"1107000000000000001\",\"mobile\":\"9876543210\"}");
            journal.recordResult(c, op, ConnectorCallJournal.LastResult.SUCCESS, messageId, null);
            return null;
        });
        return op;
    }

    private static Map<String, String> signed(byte[] body, long epochSeconds, String secret) {
        return Map.of("X-Sangam-Signature", WebhookSignature.header(epochSeconds, body, List.of(secret)));
    }

    private DltDeliveryReports.Answer post(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return reports.receive(TENANT, "sms-1", signed(body, NOW.getEpochSecond(), SECRET), body);
    }

    private static String report(String reportId, UUID op, String status) {
        return "{\"reportId\":\"" + reportId + "\",\"clientRef\":\"" + op + "\",\"status\":\"" + status
                + "\",\"reportedAt\":\"2026-11-13T07:59:00Z\"}";
    }

    private long delivered() {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?",
                rs -> rs.getString(1), TENANT, "NOTIFICATION_DELIVERED")).size();
    }

    private String state(UUID op) {
        return tx().inTransaction(c -> Jdbc.one(c, "SELECT state FROM NOTIFICATION_DELIVERY WHERE tenant_id=? AND operation_id=?",
                rs -> rs.getString(1), TENANT, op)).orElse(null);
    }

    @Test
    void unsignedWronglySignedOrStaleReportsAreRefused() {
        UUID op = sent("msg-1");
        byte[] body = report("r-1", op, "DELIVERED").getBytes(StandardCharsets.UTF_8);
        assertEquals(401, reports.receive(TENANT, "sms-1", Map.of(), body).status(), "unsigned");
        assertEquals(401, reports.receive(TENANT, "sms-1", signed(body, NOW.getEpochSecond(), "another-value"), body).status(),
                "signed with another secret");
        assertEquals(401, reports.receive(TENANT, "sms-1", signed(body, NOW.minusSeconds(600).getEpochSecond(), SECRET), body).status(),
                "a captured request replayed after the tolerance");
        byte[] other = report("r-1", op, "UNDELIVERED").getBytes(StandardCharsets.UTF_8);
        assertEquals(401, reports.receive(TENANT, "sms-1", signed(body, NOW.getEpochSecond(), SECRET), other).status(),
                "a signature of another body");
        assertEquals(0, delivered());
        assertEquals(null, state(op), "nothing recorded");
    }

    @Test
    void aConnectorWithoutASecretRefusesEverything() {
        byte[] body = report("r-1", sent("msg-1"), "DELIVERED").getBytes(StandardCharsets.UTF_8);
        assertEquals(401, reports.receive(TENANT, "sms-c", signed(body, NOW.getEpochSecond(), SECRET), body).status(), "fail closed");
        assertEquals(404, reports.receive(TENANT, "nope", signed(body, NOW.getEpochSecond(), SECRET), body).status());
    }

    @Test
    void deliveredIsWrittenOnceAndADuplicateReportHasNoEffect() {
        UUID op = sent("msg-1");
        assertEquals(200, post(report("r-1", op, "SENT")).status());
        assertEquals("SENT", state(op));
        DltDeliveryReports.Answer a = post(report("r-2", op, "DELIVERED"));
        assertEquals(200, a.status());
        assertTrue(a.body().contains("\"accepted\":1"));
        assertEquals(1, delivered());

        DltDeliveryReports.Answer dup = post(report("r-2", op, "DELIVERED"));
        assertTrue(dup.body().contains("\"duplicates\":1"), "the same report again");
        DltDeliveryReports.Answer again = post(report("r-3", op, "DELIVERED"));
        assertTrue(again.body().contains("\"ignored\":1"), "a second final report for the message");
        assertEquals(1, delivered(), "exactly once per message");

        String payload = tx().inTransaction(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p"
                + " ON p.event_id = e.event_id WHERE e.event_type='NOTIFICATION_DELIVERED'", rs -> rs.getString(1))).get(0);
        assertTrue(payload.contains(op.toString()));
        assertTrue(payload.contains("msg-1"));
        assertTrue(payload.contains("1107000000000000001"), "the template");
        assertFalse(payload.contains("9876543210"), "no mobile number in the audit");
    }

    @Test
    void reportsOutOfOrderOnlyMoveForward() {
        UUID op = sent("msg-1");
        post("[" + report("r-1", op, "DELIVERED") + "," + report("r-2", op, "SENT") + "," + report("r-3", op, "ACCEPTED") + "]");
        assertEquals("DELIVERED", state(op), "a late SENT or ACCEPTED does not move it back");
        assertEquals(1, delivered());

        UUID failed = sent("msg-2");
        post(report("f-1", failed, "UNDELIVERED"));
        post(report("f-2", failed, "DELIVERED"));
        assertEquals("UNDELIVERED", state(failed), "the first final state stays");
        assertEquals(1, delivered(), "no event for a message that was not delivered");
    }

    @Test
    void aReportCanNameTheGatewayMessageId() {
        UUID op = sent("msg-9");
        post("{\"reportId\":\"m-1\",\"messageId\":\"msg-9\",\"status\":\"DELIVERED\"}");
        assertEquals("DELIVERED", state(op));
        assertEquals(1, delivered());
    }

    @Test
    void malformedAndUnknownReports() {
        assertEquals(422, post("{\"clientRef\":\"x\",\"status\":\"MAYBE\"}").status(), "unknown status word");
        assertEquals(422, post("{\"status\":\"DELIVERED\"}").status(), "no message named");
        assertEquals(422, post("not json").status());
        DltDeliveryReports.Answer unknown = post(report("u-1", UUID.randomUUID(), "DELIVERED"));
        assertEquals(200, unknown.status());
        assertTrue(unknown.body().contains("\"ignored\":1"), "a message this connector did not send");
        assertEquals(0, delivered());
    }

    @Test
    void theLookupFallbackReportsDeliveryOnceAndTheWebhookAfterItChangesNothing() {
        UUID op = sent("msg-1");
        DltDeliveryStatusPoller poller = new DltDeliveryStatusPoller(D, tx(), registry, journal, reports,
                new DltDeliveryStatusPoller.Settings(Duration.ZERO, Duration.ofHours(1), Duration.ofDays(3), 3, 10));
        assertEquals(List.of("sms-1"), poller.lookupConnectors(), "only DLT SMS connectors with a lookup");
        assertEquals(1, poller.enrol(poller.lookupConnectors()));
        assertEquals(0, poller.enrol(poller.lookupConnectors()), "enrolled once");
        assertEquals("ACCEPTED", state(op));

        DltDeliveryStatusPoller.Due due = new DltDeliveryStatusPoller.Due(TENANT, op, "sms-1");
        poller.lookUp(List.of(due));
        assertEquals("ACCEPTED", state(op), "an unavailable lookup changes nothing");

        sms.lookup = LookupResult.Found.success(Map.of("status", "DELIVERED", "messageId", "msg-1"), "msg-1");
        poller.lookUp(List.of(due));
        assertEquals("DELIVERED", state(op));
        assertEquals(1, delivered());
        String source = tx().inTransaction(c -> Jdbc.one(c, "SELECT source FROM NOTIFICATION_DELIVERY WHERE operation_id=?",
                rs -> rs.getString(1), op)).orElseThrow();
        assertEquals("LOOKUP", source);

        assertTrue(post(report("late-1", op, "DELIVERED")).body().contains("\"ignored\":1"));
        poller.lookUp(List.of(due));
        assertEquals(1, delivered(), "once, whichever path reported first");
    }
}
