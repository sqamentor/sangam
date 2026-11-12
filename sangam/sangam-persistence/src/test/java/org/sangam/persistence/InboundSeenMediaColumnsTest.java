// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * W3-C REQUEST 2026-11-12T14:29:02Z: changeset 0043-13 adds the inbound message identity and media-fetch columns of
 * CH_INBOUND_SEEN (all NULL-able, as sangam-channels ChannelsSchema) and the index CH_INBOUND_SEEN_MEDIA; the old
 * four-column insert of the mail inbox stays valid.
 */
class InboundSeenMediaColumnsTest {

    static final List<String> COLUMNS = List.of("EXTERNAL_ID", "PRINCIPAL_ID", "MEDIA_REF", "MEDIA_STATE",
            "MEDIA_ATTEMPT", "MEDIA_NEXT_AT", "MEDIA_OWNER", "MEDIA_EPOCH", "MEDIA_DOC", "MEDIA_ERROR");

    @Test
    void theMediaColumnsAndIndexExistAfterTheFullChangelog() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "sa", "")) {
            SangamSchema.apply(c, SangamSchema.MASTER_CHANGELOG, null);
            for (String col : COLUMNS) {
                try (ResultSet rs = c.getMetaData().getColumns(null, null, "CH_INBOUND_SEEN", col)) {
                    assertTrue(rs.next(), col);
                    assertEquals("YES", rs.getString("IS_NULLABLE"), col);
                }
            }
            try (ResultSet rs = c.getMetaData().getColumns(null, null, "CH_INBOUND_SEEN", "MEDIA_STATE")) {
                assertTrue(rs.next());
                assertEquals(64, rs.getInt("COLUMN_SIZE"));
            }
            List<String> indexed = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getIndexInfo(null, null, "CH_INBOUND_SEEN", false, false)) {
                while (rs.next()) {
                    if ("CH_INBOUND_SEEN_MEDIA".equals(rs.getString("INDEX_NAME"))) {
                        indexed.add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
            assertEquals(List.of("MEDIA_STATE", "MEDIA_NEXT_AT"), indexed);

            c.createStatement().execute("INSERT INTO CH_INBOUND_SEEN(tenant_id, channel, message_id, seen_at)"
                    + " VALUES ('t1', 'mail', 'm-1', CURRENT_TIMESTAMP)");
            c.createStatement().execute("INSERT INTO CH_INBOUND_SEEN(tenant_id, channel, message_id, seen_at,"
                    + " external_id, media_state, media_attempt, media_next_at, media_epoch) VALUES ('t1', 'wa', 'm-2',"
                    + " CURRENT_TIMESTAMP, 'wamid.1', 'PENDING', 0, CURRENT_TIMESTAMP, 1)");
            try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM CH_INBOUND_SEEN"
                    + " WHERE media_state IS NULL")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "the old insert leaves the media state NULL (no media)");
            }
        }
    }
}
