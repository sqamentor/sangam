// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.conversation;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.channels.ChannelsSchema;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.DocumentStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.time.Instant;
import java.time.Period;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** NG-12: trigram search index, Last-Event-ID replay data, scheduled retention (M-071, M-040 data side). H2; written, not run (Wave 3). */
class ConversationIndexAndRetentionH2Test {

    Transactions tx;
    ConversationService s;
    final Map<String, byte[]> docs = new HashMap<>();

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            ChannelsSchema.create(c, Dialect.POSTGRESQL);
        }
        tx = Transactions.of(ds);
        DocumentStore store = new DocumentStore() {
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
        s = new ConversationService(Dialect.POSTGRESQL, tx, new AuditChain(Dialect.POSTGRESQL), store, (t, r, m) -> { }, (n, b) -> true, (t, p) -> true);
    }

    private int grams(String messageId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM CH_MESSAGE_GRAM WHERE message_id=?", rs -> rs.getInt(1), messageId).orElse(0));
    }

    @Test
    void trigramsAreDistinctLowerCasedAndCollationSafe() {
        assertEquals(3, SearchGrams.of("Abcab").size(), "abc, bca, cab");
        assertEquals(SearchGrams.of("ABC"), SearchGrams.of("abc"));
        assertTrue(SearchGrams.of("ab").isEmpty());
        assertTrue(SearchGrams.of(null).isEmpty());
        assertEquals(Set.of("616263"), SearchGrams.of("abc"), "hex of the UTF-8 bytes");
        assertEquals(2, SearchGrams.of("नमस्").size(), "code points, not bytes: न म स ् gives two trigrams");
        assertEquals(SearchGrams.MAX_QUERY_GRAMS, SearchGrams.forQuery("abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJ").size());
    }

    @Test
    void indexedSearchFindsExactlyWhatTheSubstringScanFinds() {
        var conv = s.create("t", "alice", "topic", "case-1", "SHORT", Set.of("bob"));
        var other = s.create("t", "carol", "private", null, "SHORT", Set.of());
        var m1 = s.send("t", conv.id(), "alice", "Hello 100% World", List.of(), "k1");
        s.send("t", conv.id(), "bob", "Pipeline worked_fine", List.of(), "k2");
        s.send("t", conv.id(), "alice", "आवेदन स्वीकृत हुआ", List.of(), "k3");
        s.send("t", other.id(), "carol", "hello from elsewhere", List.of(), "k4");
        s.send("t", conv.id(), "bob", "xabcabx", List.of(), "k5");
        assertTrue(grams(m1.id()) > 0, "a sent message is indexed");

        assertEquals(1, s.search("t", "bob", "hello", 10).size(), "only the conversations bob is in");
        assertEquals(1, s.search("t", "bob", "LO 100% w", 10).size(), "case-insensitive, spaces and % inside the needle");
        assertEquals(1, s.search("t", "bob", "d_f", 10).size(), "an underscore is literal, not a wildcard");
        assertEquals(0, s.search("t", "bob", "dxf", 10).size());
        assertEquals(1, s.search("t", "bob", "स्वीकृत", 10).size(), "non-Latin text is indexed by code point");
        assertEquals(1, s.search("t", "bob", "abcab", 10).size());
        assertEquals(0, s.search("t", "bob", "abcabc", 10).size(), "abc, bca and cab are all indexed for xabcabx, but the substring is not there");
        assertEquals(1, s.search("t", "bob", "lo", 10).size(), "short needles use the scan");
        assertEquals(4, s.search("t", "bob", "", 10).size(), "an empty needle still lists everything");
        assertEquals(0, s.search("other-tenant", "bob", "hello", 10).size());
    }

    @Test
    void purgeRemovesTheIndexRowsToo() {
        var conv = s.create("t", "alice", "topic", null, "SHORT", Set.of());
        var m = s.send("t", conv.id(), "alice", "to be purged", List.of(), null);
        assertTrue(grams(m.id()) > 0);
        assertEquals(1, s.purgeBefore("t", "SHORT", Instant.now().plusSeconds(60), "ops"));
        assertEquals(0, grams(m.id()));
        assertTrue(s.search("t", "alice", "purged", 10).isEmpty());
    }

    @Test
    void sinceReplaysStrictlyAfterTheLastEventId() {
        var conv = s.create("t", "alice", "topic", null, "SHORT", Set.of("bob"));
        var a = s.send("t", conv.id(), "alice", "one", List.of(), "1");
        var b = s.send("t", conv.id(), "alice", "two", List.of(), "2");
        var c = s.send("t", conv.id(), "bob", "three", List.of(), "3");
        List<ConversationService.Message> after = s.since("t", conv.id(), "bob", a.id(), 10);
        assertEquals(Set.of(b.id(), c.id()), Set.of(after.get(0).id(), after.get(1).id()));
        assertEquals(2, after.size());
        assertFalse(after.stream().anyMatch(x -> x.id().equals(a.id())), "the Last-Event-ID message itself is not repeated");
        assertTrue(s.since("t", conv.id(), "bob", c.id(), 10).stream().noneMatch(x -> x.id().equals(c.id())));
        assertEquals(3, s.since("t", conv.id(), "bob", "unknown-id", 10).size(), "an unknown id falls back to the newest messages");
        assertEquals(2, s.since("t", conv.id(), "bob", null, 2).size());
        assertThrows(ConversationService.AccessDenied.class, () -> s.since("t", conv.id(), "eve", a.id(), 10));
    }

    @Test
    void messagesFromReadsByDatabaseTimeForTheLiveCatchUp() {
        var conv = s.create("t", "alice", "topic", null, "SHORT", Set.of("bob"));
        var a = s.send("t", conv.id(), "alice", "one", List.of(), "1");
        var b = s.send("t", conv.id(), "alice", "two", List.of(), "2");
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE CH_MESSAGE SET created_at=? WHERE id=?", Instant.parse("2026-01-01T00:00:00Z"), a.id()));
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE CH_MESSAGE SET created_at=? WHERE id=?", Instant.parse("2026-01-01T00:00:05Z"), b.id()));
        assertEquals(List.of(a.id(), b.id()), s.messagesFrom("t", conv.id(), "bob", null, 10).stream().map(ConversationService.Message::id).toList());
        assertEquals(List.of(b.id()), s.messagesFrom("t", conv.id(), "bob", Instant.parse("2026-01-01T00:00:05Z"), 10).stream()
                .map(ConversationService.Message::id).toList(), "inclusive");
        assertEquals(1, s.messagesFrom("t", conv.id(), "bob", null, 1).size());
        assertTrue(s.messagesFrom("t", conv.id(), "bob", Instant.parse("2026-01-01T00:00:06Z"), 10).isEmpty());
        assertThrows(ConversationService.AccessDenied.class, () -> s.messagesFrom("t", conv.id(), "eve", null, 10));
    }

    @Test
    void scheduledRetentionAppliesEachClassWithAPeriod() {
        var shortConv = s.create("t", "alice", "short", null, "SHORT", Set.of());
        var keepConv = s.create("t", "alice", "keep", null, "KEEP", Set.of());
        s.send("t", shortConv.id(), "alice", "old news", List.of(), null);
        s.send("t", keepConv.id(), "alice", "kept", List.of(), null);
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE CH_MESSAGE SET created_at=? WHERE conversation_id IN (?,?)",
                Instant.parse("2000-01-01T00:00:00Z"), shortConv.id(), keepConv.id()));

        ConversationRetention retention = new ConversationRetention(s, Dialect.POSTGRESQL, tx, tenant -> Map.of("SHORT", Period.ofYears(1)));
        Map<String, Integer> removed = retention.runOnce("t");
        assertEquals(Map.of("SHORT", 1), removed, "KEEP has no period and is kept");
        assertEquals(1, s.history("t", keepConv.id(), "alice", 10).size());
        assertTrue(s.history("t", shortConv.id(), "alice", 10).isEmpty());
        assertEquals(Map.of("SHORT", 0), retention.runOnce("t"), "a second run removes nothing");
        assertTrue(new ConversationRetention(s, Dialect.POSTGRESQL, tx, tenant -> Map.of()).runOnce("t").isEmpty());

        assertEquals(Instant.parse("2025-02-28T00:00:00Z"), ConversationRetention.cutoff(Instant.parse("2026-02-28T00:00:00Z"), Period.ofYears(1)));
        assertEquals(Period.ofYears(7), ConversationRetention.years("7"));
        assertThrows(IllegalArgumentException.class, () -> ConversationRetention.years(0));
    }
}
