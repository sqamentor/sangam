// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.inbound;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.connector.file.FileTransport;
import org.sangam.ports.events.InboundEventProcessor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Polled inbound channels for files, SFTP, SQL tables and Azure Service Bus. Each adapter reads, hands the message to the event
 * registry's {@link InboundEventProcessor} (whose inbox removes duplicates by message id), and only then removes or acknowledges
 * it, so a crash gives a re-read, not a loss. Call {@code pollOnce} from a scheduler or a job.
 */
public final class PolledChannels {

    public record Summary(int accepted, int duplicates, int rejected) {
        Summary plus(InboundEventProcessor.Outcome o) {
            return switch (o) {
                case ACCEPTED -> new Summary(accepted + 1, duplicates, rejected);
                case DUPLICATE -> new Summary(accepted, duplicates + 1, rejected);
                case REJECTED -> new Summary(accepted, duplicates, rejected + 1);
            };
        }
    }

    /** {@code receive} of the processor, as a function for tests and alternative pipelines. */
    @FunctionalInterface
    public interface Receiver {
        InboundEventProcessor.Outcome receive(String tenant, String channel, String raw, Map<String, String> headers, String messageId);

        static Receiver of(InboundEventProcessor p) {
            return (t, c, raw, h, id) -> p.receive(t, c, raw, h, id).outcome();
        }
    }

    private PolledChannels() {
    }

    // ---- file and SFTP -------------------------------------------------------------------------

    /**
     * Reads files from a directory through a {@link FileTransport}: {@code LocalDirectoryTransport} for a file channel and
     * {@code SftpTransport} for an SFTP channel. Handled files move to {@code doneDir}, rejected ones to {@code errorDir}. The
     * message id is channel-unique: file name plus content hash, so a replaced file with new content is new.
     */
    public record FileChannel(String tenantId, String channel, FileTransport transport, String inDir, String doneDir, String errorDir, Pattern namePattern, Receiver receiver) {
        public Summary pollOnce(int max) throws IOException {
            Summary s = new Summary(0, 0, 0);
            int n = 0;
            for (String name : transport.list(inDir)) {
                if (n >= max || !namePattern.matcher(name).matches()) {
                    continue;
                }
                n++;
                byte[] content = transport.read(inDir, name);
                String id = name + ":" + org.sangam.core.layer.Hashes.hex(org.sangam.core.layer.Hashes.sha256(content));
                InboundEventProcessor.Outcome o = receiver.receive(tenantId, channel, new String(content, StandardCharsets.UTF_8), Map.of("fileName", name), id);
                transport.move(inDir, name, o == InboundEventProcessor.Outcome.REJECTED ? errorDir : doneDir);
                s = s.plus(o);
            }
            return s;
        }
    }

    // ---- SQL -----------------------------------------------------------------------------------

    /**
     * Polls a customer table by an increasing cursor column. {@code selectSql} is operator configuration, must be read-only and
     * must have one parameter (the last cursor), ordered ascending by the cursor; the first column is the cursor, and all columns
     * form the JSON event. The cursor moves only after the row is handed over. Runs on a separate connection pool for the source
     * database when {@code source} differs from the product database.
     */
    public record SqlChannel(String tenantId, String channel, Transactions source, Transactions product, Dialect productDialect, String selectSql, String initialCursor, Receiver receiver) {
        public SqlChannel {
            String low = selectSql.strip().toLowerCase(java.util.Locale.ROOT);
            if (!low.startsWith("select") || low.contains(";")) {
                throw new IllegalArgumentException("SQL channel needs one SELECT statement");
            }
        }

        public Summary pollOnce(int max) {
            String cursor = product.inTransaction(c -> Jdbc.one(c, "SELECT cursor_val FROM CH_POLL_CURSOR WHERE tenant_id=? AND channel=?", rs -> rs.getString(1), tenantId, channel)
                    .orElse(initialCursor));
            List<Map<String, Object>> rows = source.inTransaction(c -> Jdbc.query(c, selectSql, SqlChannel::row, cursor));
            Summary s = new Summary(0, 0, 0);
            int n = 0;
            for (Map<String, Object> row : rows) {
                if (n++ >= max) {
                    break;
                }
                String at = String.valueOf(row.values().iterator().next());
                s = s.plus(receiver.receive(tenantId, channel, Json.write(row), Map.of(), channel + ":" + at));
                saveCursor(at);
            }
            return s;
        }

        private void saveCursor(String at) {
            product.inTransaction(c -> {
                Instant now = Jdbc.dbNow(c, productDialect);
                if (Jdbc.update(c, "UPDATE CH_POLL_CURSOR SET cursor_val=?, updated_at=? WHERE tenant_id=? AND channel=?", at, now, tenantId, channel) == 0) {
                    Jdbc.update(c, "INSERT INTO CH_POLL_CURSOR(tenant_id,channel,cursor_val,updated_at) VALUES (?,?,?,?)", tenantId, channel, at, now);
                }
                return null;
            });
        }

        private static Map<String, Object> row(ResultSet rs) throws SQLException {
            Map<String, Object> m = new LinkedHashMap<>();
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                Object v = rs.getObject(i);
                m.put(md.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), v == null || v instanceof Number || v instanceof String || v instanceof Boolean ? v : v.toString());
            }
            return m;
        }
    }

    // ---- Azure Service Bus ---------------------------------------------------------------------

    /**
     * Azure Service Bus over its public REST API, with no vendor SDK: peek-lock with {@code POST {entity}/messages/head}, complete
     * with {@code DELETE} on the lock location. A SAS token is signed per call. The broker redelivers a message whose lock expires.
     * Abandon and dead-lettering stay with the broker's delivery-count rule, since a REJECTED message is not completed.
     */
    public record ServiceBusChannel(String tenantId, String channel, URI namespace, String entityPath, String sasKeyName, String sasKeyRef, HttpTransport transport, SecretResolver secrets,
                                    Function<Instant, Instant> tokenExpiry, Receiver receiver) {

        public Summary pollOnce(int max) {
            Summary s = new Summary(0, 0, 0);
            for (int i = 0; i < max; i++) {
                URI head = URI.create(base() + "/messages/head?timeout=1");
                HttpTransport.Outcome o = transport.execute(tenantId, HttpTransport.Call.post(head, auth(), new byte[0], false), 0);
                if (!(o instanceof HttpTransport.Outcome.Response r)) {
                    throw new IllegalStateException("Service Bus unreachable: " + o);
                }
                if (r.status() == 204) {
                    return s;
                }
                if (r.status() != 201) {
                    throw new IllegalStateException("Service Bus HTTP " + r.status());
                }
                var props = Json.tree(r.header("BrokerProperties").orElse("{}").getBytes(StandardCharsets.UTF_8));
                String id = props == null ? null : props.path("MessageId").asText(null);
                InboundEventProcessor.Outcome outcome = receiver.receive(tenantId, channel, r.bodyString(), Map.of(), id);
                s = s.plus(outcome);
                if (outcome != InboundEventProcessor.Outcome.REJECTED) {
                    String lock = r.header("Location").orElseThrow(() -> new IllegalStateException("peek-lock answer has no Location"));
                    HttpTransport.Outcome d = transport.execute(tenantId, new HttpTransport.Call("DELETE", URI.create(lock), auth(), null, null, true), 0);
                    if (!(d instanceof HttpTransport.Outcome.Response dr) || !dr.is2xx()) {
                        throw new IllegalStateException("could not complete message " + id + "; the broker will redeliver it and the inbox removes the duplicate");
                    }
                }
            }
            return s;
        }

        private String base() {
            return namespace.toString().replaceAll("/+$", "") + "/" + entityPath;
        }

        private Map<String, String> auth() {
            String resource = URLEncoder.encode(base(), StandardCharsets.UTF_8);
            long expiry = tokenExpiry.apply(Instant.now()).getEpochSecond();
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secrets.require(sasKeyRef).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                String sig = Base64.getEncoder().encodeToString(mac.doFinal((resource + "\n" + expiry).getBytes(StandardCharsets.UTF_8)));
                Map<String, String> h = new LinkedHashMap<>();
                h.put("Authorization", "SharedAccessSignature sr=" + resource + "&sig=" + URLEncoder.encode(sig, StandardCharsets.UTF_8) + "&se=" + expiry + "&skn=" + sasKeyName);
                return h;
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
