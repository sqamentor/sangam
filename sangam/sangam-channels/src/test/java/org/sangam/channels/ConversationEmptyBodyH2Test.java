// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.channels.conversation.ConversationService;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression SL-87: an attachment-only message ({@code body: ""}) was bound as '' into the NOT NULL column
 * CH_MESSAGE.body. Oracle stores '' as NULL, so the send failed with ORA-01400 (it worked on PostgreSQL). The column is
 * nullable now (changeset 0035-01): an empty body is stored as NULL on every database and read back as "", and a
 * message with neither text nor attachments is refused (422 CHANNELS_REJECTED through ChannelsProblemAdvice).
 *
 * <p>Runs in H2's PostgreSQL mode: H2's Oracle mode cannot run the audit chain (no ON CONFLICT; its duplicate-key code
 * is 23505, not ORA-00001). The stored-NULL assertion is the Oracle property; the Oracle IT is the definitive proof.
 */
class ConversationEmptyBodyH2Test {

    @Test
    void attachmentOnlyMessageIsStoredAsNullAndReadAsEmpty() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
            ChannelsSchema.create(c, Dialect.POSTGRESQL);
        }
        ConversationService s = new ConversationService(Dialect.POSTGRESQL, Transactions.of(ds), new AuditChain(Dialect.POSTGRESQL),
                new ChannelsTest.MemoryStore(), (t, r, m) -> { }, (n, b) -> true, (t, p) -> true);
        var conv = s.create("t", "alice", "topic", "case-1", "SHORT", Set.of("bob"));
        var file = s.upload("t", conv.id(), "alice", "scan.pdf", "application/pdf", new byte[]{1, 2, 3});

        ConversationService.Message sent = s.send("t", conv.id(), "alice", "", List.of(file), "k1");
        assertEquals("", sent.body(), "the returned message has an empty body");
        ConversationService.Message read = s.history("t", conv.id(), "bob", 10).get(0);
        assertEquals("", read.body(), "a stored NULL body reads back as empty");
        assertEquals(1, read.attachments().size());
        assertEquals(1, s.search("t", "bob", "", 10).size(), "an empty search still finds the attachment-only message");

        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT body FROM CH_MESSAGE")) {
            rs.next();
            assertNull(rs.getString(1), "an empty body is stored as NULL ('' is NULL on Oracle)");
        }

        assertThrows(IllegalArgumentException.class, () -> s.send("t", conv.id(), "alice", "", List.of(), "k2"),
                "neither text nor attachments is refused");
        assertThrows(IllegalArgumentException.class, () -> s.send("t", conv.id(), "alice", null, List.of(), "k3"));
        assertEquals("hi", s.send("t", conv.id(), "alice", "hi", List.of(), "k4").body(), "a text-only message is unchanged");
    }
}
