// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.h2.jdbcx.JdbcDataSource;
import org.sangam.channels.ChannelsSchema;
import org.sangam.channels.bot.BotBridge;
import org.sangam.channels.messenger.InboundMediaQueue;
import org.sangam.channels.messenger.MediaDownloads;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.DocumentStore;

/** H2 database, document store, bot and failure log shared by the media worker tests. */
final class MediaQueueFixture {

    static final List<Map<String, Object>> ENTRIES = List.of(Map.of("key", "chat", "channel", "SLACK", "tokenRef", "sl"));

    final Transactions tx;
    final Map<String, byte[]> docs = new ConcurrentHashMap<>();
    final List<Map<String, Object>> botVars = new CopyOnWriteArrayList<>();
    final List<String> failures = new CopyOnWriteArrayList<>();

    final DocumentStore store = new DocumentStore() {
        public String type() {
            return "mem";
        }

        public StoredObject put(String key, InputStream c, String t) throws IOException {
            byte[] b = c.readAllBytes();
            docs.put(key, b);
            return new StoredObject(key, b.length, "hash");
        }

        public Optional<InputStream> get(String key) {
            return Optional.ofNullable(docs.get(key)).map(ByteArrayInputStream::new);
        }

        public boolean exists(String key) {
            return docs.containsKey(key);
        }

        public void delete(String key) {
            docs.remove(key);
        }
    };

    final BotBridge bot = new BotBridge(new BotBridge.ProcessGateway() {
        @Override
        public boolean correlate(String tenantId, String messageName, String businessKey, Map<String, Object> variables) {
            botVars.add(variables);
            return true;
        }

        @Override
        public void start(String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
        }
    }, Map.of("*", "bot"));

    MediaQueueFixture() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            ChannelsSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
    }

    /** What the webhook commits: the inbox row and its pending media, in one transaction. */
    void inbox(String messageId, String mediaRef) {
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO CH_INBOUND_SEEN(tenant_id,channel,message_id,seen_at) VALUES (?,?,?,CURRENT_TIMESTAMP)", "t", "SLACK", messageId);
            if (!InboundMediaQueue.enqueue(c, Dialect.POSTGRESQL, "t", "SLACK", messageId, "U1", "alice", mediaRef)) {
                throw new IllegalStateException("no inbox row");
            }
            return null;
        });
    }

    String state(String messageId) {
        return column("media_state", messageId);
    }

    String column(String column, String messageId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT " + column + " FROM CH_INBOUND_SEEN WHERE tenant_id='t' AND channel='SLACK' AND message_id=?",
                rs -> rs.getString(1), messageId).orElse(null));
    }

    InboundMediaQueue queue(String node, Duration lease) {
        return new InboundMediaQueue(Dialect.POSTGRESQL, tx, node, lease);
    }

    ChannelMediaWorker worker(String node, Duration lease, ChannelMediaWorker.Download download, boolean clean, int threads) {
        return new ChannelMediaWorker(queue(node, lease), tx, t -> t.equals("t") ? ENTRIES : List.of(), download, store, (name, bytes) -> clean, bot,
                threads, Duration.ofMillis(50), List.of(Duration.ofMillis(10), Duration.ofMillis(10)),
                (c, media, reason) -> failures.add(media.messageId() + ":" + reason));
    }

    static MediaDownloads.Media media(String ref) {
        return new MediaDownloads.Media(ref.getBytes(StandardCharsets.UTF_8), "application/pdf", ref + ".pdf");
    }

    /** File names of the media the bot received, sorted. */
    @SuppressWarnings("unchecked")
    List<String> delivered() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> v : botVars) {
            out.add(String.valueOf(((Map<String, Object>) v.get("botMedia")).get("fileName")));
        }
        out.sort(null);
        return out;
    }

    static void await(BooleanSupplier done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
