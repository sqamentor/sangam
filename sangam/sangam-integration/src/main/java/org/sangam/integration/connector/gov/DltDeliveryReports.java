// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.publish.WebhookSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owner decision 304: delivery reports of the DLT SMS gateway ({@link DltSmsConnector}), by signed webhook
 * ({@link #receive}) or by the status-lookup fallback ({@link DltDeliveryStatusPoller}), both through {@link #apply}.
 * <ul>
 *   <li><b>Authenticated and signed.</b> The request carries {@value WebhookSignature#HEADER}
 *       {@code t=<epoch seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>} ({@link WebhookSignature}); the secret is
 *       the connector's configured reference ({@link Settings#secretRefs}). Unsigned, wrongly signed, or signed outside
 *       the tolerance (a replay of a captured request) is refused with 401; a connector without a configured secret
 *       refuses everything (fail closed).</li>
 *   <li><b>Idempotent per report and per message.</b> Each report is recorded once by its {@code reportId} (else the
 *       SHA-256 of the report), so a report sent again has no effect. Each message (operationId) has one
 *       {@code NOTIFICATION_DELIVERY} row whose state only moves forward (accepted, sent, then one final state:
 *       delivered, undelivered, failed, rejected or expired); a report that would move it back, or a second final
 *       report, is recorded and ignored, so reports may arrive in any order.</li>
 *   <li><b>NOTIFICATION_DELIVERED</b> is written exactly once per message, in the transaction that moves its row to
 *       DELIVERED, on the root chain of the send (from {@code CONNECTOR_CALL}). Payload: operationId, connector,
 *       channel SMS, messageId, templateId, reportedAt, source (WEBHOOK or LOOKUP). No mobile number.</li>
 * </ul>
 */
public final class DltDeliveryReports {

    private static final Logger LOG = LoggerFactory.getLogger(DltDeliveryReports.class);

    public static final String EVENT = "NOTIFICATION_DELIVERED";
    /** Problem codes of the route (contract receiveDltDeliveryReports). */
    public static final String SIGNATURE_INVALID = "WEBHOOK_SIGNATURE_INVALID";
    public static final String REPORT_INVALID = "DLT_DELIVERY_REPORT_INVALID";
    public static final String DELIVERED = "DELIVERED";
    /** States of a delivery that never change again. */
    public static final Set<String> FINAL = Set.of(DELIVERED, "UNDELIVERED", "FAILED", "REJECTED", "EXPIRED");
    static final int FINAL_RANK = 3;

    /** Where a report came from. */
    public enum Source { WEBHOOK, LOOKUP }

    /** What one report did. */
    public enum Applied { DELIVERED, ADVANCED, STALE, DUPLICATE, UNKNOWN_MESSAGE }

    /** What the REST layer answers: status, headers and body (for 401, 404 and 422 the body starts with the problem code). */
    public record Answer(int status, Map<String, String> headers, String body) {
        public Answer {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? "" : body;
        }
    }

    /**
     * @param secretRefs signing secret reference per connector id, or per {@code tenant:connectorId} (which wins)
     * @param tolerance  accepted distance of the signature's timestamp from now; null = 5 minutes
     * @param maxReports reports per request; 0 = 500
     * @param maxBytes   request body size; 0 = 1 MiB
     */
    public record Settings(Map<String, String> secretRefs, Duration tolerance, int maxReports, int maxBytes) {
        public Settings {
            secretRefs = secretRefs == null ? Map.of() : Map.copyOf(secretRefs);
            tolerance = tolerance == null ? WebhookSignature.DEFAULT_TOLERANCE : tolerance;
            maxReports = maxReports <= 0 ? 500 : maxReports;
            maxBytes = maxBytes <= 0 ? 1 << 20 : maxBytes;
        }

        String secretRef(String tenantId, String connectorId) {
            String r = secretRefs.get(tenantId + ":" + connectorId);
            return r != null ? r : secretRefs.get(connectorId);
        }
    }

    /** One delivery report: the message by {@code clientRef} (the operationId) or by the gateway's {@code messageId}. */
    public record Report(String reportId, String clientRef, String messageId, String status, String reason, String reportedAt) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final ConnectorRegistry registry;
    private final ConnectorCallJournal journal;
    private final AuditChain audit;
    private final SecretResolver secrets;
    private final Settings settings;
    private final Clock clock;

    public DltDeliveryReports(Dialect dialect, Transactions tx, ConnectorRegistry registry, ConnectorCallJournal journal,
                              AuditChain audit, SecretResolver secrets, Settings settings, Clock clock) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.audit = Objects.requireNonNull(audit, "audit: NOTIFICATION_DELIVERED must be written");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.settings = settings == null ? new Settings(null, null, 0, 0) : settings;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ------------------------------------------------------------------ webhook

    /** A signed delivery-report request for one DLT SMS connector of a tenant: one report object or an array of them. */
    public Answer receive(String tenantId, String connectorId, Map<String, String> headers, byte[] body) {
        Optional<SangamConnector> conn = registry.find(tenantId, connectorId);
        if (conn.isEmpty() || !DltSmsConnector.TYPE.equals(conn.get().descriptor().type())) {
            return new Answer(404, Map.of(), "NOT_FOUND: no DLT SMS connector " + connectorId);
        }
        if (!authentic(tenantId, connectorId, headers, body)) {
            return new Answer(401, Map.of(), SIGNATURE_INVALID + ": delivery report not authenticated");
        }
        if (body.length > settings.maxBytes()) {
            return new Answer(422, Map.of(), REPORT_INVALID + ": more than " + settings.maxBytes() + " bytes");
        }
        List<Report> reports;
        try {
            reports = parse(body);
        } catch (IllegalArgumentException e) {
            return new Answer(422, Map.of(), REPORT_INVALID + ": " + e.getMessage());
        }
        int accepted = 0, duplicates = 0, ignored = 0;
        for (Report r : reports) {
            switch (apply(tenantId, connectorId, r, Source.WEBHOOK)) {
                case DELIVERED, ADVANCED -> accepted++;
                case DUPLICATE -> duplicates++;
                case STALE, UNKNOWN_MESSAGE -> ignored++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("duplicates", duplicates);
        out.put("ignored", ignored);
        return new Answer(200, Map.of("Content-Type", "application/json"), Json.write(out));
    }

    /** Fails closed: no secret configured or resolvable, no signature, a wrong one, or one outside the tolerance. */
    private boolean authentic(String tenantId, String connectorId, Map<String, String> headers, byte[] body) {
        String ref = settings.secretRef(tenantId, connectorId);
        if (ref == null || ref.isBlank() || body == null) {
            return false;
        }
        Optional<String> secret;
        try {
            secret = secrets.resolve(ref.trim());
        } catch (RuntimeException e) {
            LOG.warn("delivery-report secret of connector {} cannot be resolved: {}", connectorId, e.getClass().getSimpleName());
            return false;
        }
        if (secret.isEmpty() || secret.get().isBlank()) {
            return false;
        }
        String signature = null;
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (h.getKey() != null && h.getKey().equalsIgnoreCase(WebhookSignature.HEADER)) {
                    signature = h.getValue();
                }
            }
        }
        return WebhookSignature.verify(secret.get().getBytes(StandardCharsets.UTF_8), signature, body, settings.tolerance(),
                clock.instant().getEpochSecond());
    }

    private List<Report> parse(byte[] body) {
        JsonNode root = body == null || body.length == 0 ? null : Json.tree(body);
        if (root == null || !(root.isObject() || root.isArray())) {
            throw new IllegalArgumentException("a delivery report is a JSON object or an array of them");
        }
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodes::add);
        } else {
            nodes.add(root);
        }
        if (nodes.isEmpty() || nodes.size() > settings.maxReports()) {
            throw new IllegalArgumentException("1 to " + settings.maxReports() + " reports per request");
        }
        List<Report> out = new ArrayList<>(nodes.size());
        for (JsonNode n : nodes) {
            out.add(report(n));
        }
        return out;
    }

    private static Report report(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException("each delivery report is a JSON object");
        }
        String status = text(n, "status");
        String clientRef = text(n, "clientRef");
        String messageId = text(n, "messageId");
        if (status == null || rank(status) < 0) {
            throw new IllegalArgumentException("unknown delivery status " + status);
        }
        if (clientRef == null && messageId == null) {
            throw new IllegalArgumentException("a delivery report names clientRef or messageId");
        }
        String id = text(n, "reportId");
        if (id == null) {
            id = "sha256:" + HexFormat.of().formatHex(Hashes.sha256(Json.write(n)));
        }
        return new Report(id, clientRef, messageId, status.toUpperCase(Locale.ROOT), text(n, "reason"), text(n, "reportedAt"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    // ------------------------------------------------------------------ state

    /** Order of the delivery states: 1 accepted, 2 sent, {@value #FINAL_RANK} final; -1 for an unknown word. */
    public static int rank(String status) {
        if (status == null) {
            return -1;
        }
        String s = status.toUpperCase(Locale.ROOT);
        if (FINAL.contains(s)) {
            return FINAL_RANK;
        }
        return switch (s) {
            case "ACCEPTED", "SUBMITTED" -> 1;
            case "SENT" -> 2;
            default -> -1;
        };
    }

    /**
     * Applies one report in its own transaction. {@code report.reportId()} null skips the per-report record (the
     * lookup fallback, whose answers carry no id).
     */
    public Applied apply(String tenantId, String connectorId, Report report, Source source) {
        return tx.inTransaction(c -> apply(c, tenantId, connectorId, report, source));
    }

    private Applied apply(Connection c, String tenantId, String connectorId, Report report, Source source) {
        Optional<ConnectorCallJournal.Call> call = call(c, tenantId, connectorId, report);
        if (call.isEmpty()) {
            LOG.info("delivery report for an unknown message of connector {} ignored", connectorId);
            return Applied.UNKNOWN_MESSAGE;
        }
        ConnectorCallJournal.Call sent = call.get();
        if (report.reportId() != null && !Jdbc.insertIfAbsent(c, dialect,
                "INSERT INTO NOTIFICATION_DELIVERY_REPORT(tenant_id, report_id, operation_id, received_at) VALUES (?,?,?,"
                        + dialect.now() + ")", tenantId, report.reportId(), sent.operationId())) {
            return Applied.DUPLICATE;
        }
        enrol(c, sent, source);
        int current = Jdbc.one(c, dialect.lockingSelect("state_rank", "NOTIFICATION_DELIVERY", "tenant_id=? AND operation_id=?"),
                rs -> rs.getInt(1), tenantId, sent.operationId()).orElseThrow();
        int next = rank(report.status());
        if (next <= current) {
            Jdbc.update(c, "UPDATE NOTIFICATION_DELIVERY SET reports=reports+1, updated_at=" + dialect.now()
                    + " WHERE tenant_id=? AND operation_id=?", tenantId, sent.operationId());
            return Applied.STALE;
        }
        String messageId = report.messageId() != null ? report.messageId() : sent.providerRef();
        Jdbc.update(c, "UPDATE NOTIFICATION_DELIVERY SET state=?, state_rank=?, reason=?, reported_at=?, message_id=COALESCE(?, message_id),"
                        + " source=?, reports=reports+1, next_lookup_at=" + (next == FINAL_RANK ? "NULL" : "next_lookup_at") + ", updated_at="
                        + dialect.now() + " WHERE tenant_id=? AND operation_id=?",
                report.status(), next, truncate(report.reason()), truncate(report.reportedAt()), messageId, source.name(),
                tenantId, sent.operationId());
        if (!DELIVERED.equals(report.status())) {
            return Applied.ADVANCED;
        }
        audit.append(c, tenantId, sent.rootId(), "NOTIFICATION_DELIVERED", payload(sent, messageId, report, source));
        return Applied.DELIVERED;
    }

    /** The send the report is about: by clientRef (operationId), else by the gateway's messageId; same tenant and connector. */
    private Optional<ConnectorCallJournal.Call> call(Connection c, String tenantId, String connectorId, Report r) {
        Optional<UUID> op = Optional.empty();
        if (r.clientRef() != null) {
            try {
                op = Optional.of(UUID.fromString(r.clientRef()));
            } catch (IllegalArgumentException e) {
                op = Optional.empty();
            }
        }
        if (op.isEmpty() && r.messageId() != null) {
            op = Jdbc.one(c, "SELECT operation_id FROM CONNECTOR_CALL WHERE tenant_id=? AND connector_id=? AND provider_ref=?",
                    rs -> UUID.fromString(rs.getString(1)), tenantId, connectorId, r.messageId());
        }
        return op.flatMap(id -> journal.find(c, id))
                .filter(x -> tenantId.equals(x.tenantId()) && connectorId.equals(x.connectorId())
                        && DltSmsConnector.OP_SEND.equals(x.operation()));
    }

    /** The message's delivery row, created at its first report (due for the fallback at once until final) or by the fallback's enrolment. */
    void enrol(Connection c, ConnectorCallJournal.Call sent, Source source) {
        Jdbc.insertIfAbsent(c, dialect,
                "INSERT INTO NOTIFICATION_DELIVERY(tenant_id, operation_id, connector_id, root_id, message_id, state, state_rank,"
                        + " reason, reported_at, source, reports, lookups, next_lookup_at, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,'ACCEPTED',1,NULL,NULL,?,0,0," + dialect.now() + "," + dialect.now() + "," + dialect.now() + ")",
                sent.tenantId(), sent.operationId(), sent.connectorId(), sent.rootId(), sent.providerRef(), source.name());
    }

    private static Map<String, Object> payload(ConnectorCallJournal.Call sent, String messageId, Report r, Source source) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("operationId", sent.operationId().toString());
        p.put("connector", sent.connectorId());
        p.put("channel", "SMS");
        p.put("messageId", messageId);
        Object template = sent.requestJson() == null ? null : Json.readMap(sent.requestJson()).get("templateId");
        p.put("templateId", template == null ? null : String.valueOf(template));
        p.put("reportedAt", r.reportedAt());
        p.put("source", source.name());
        return p;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 255 ? s : s.substring(0, 255);
    }
}
