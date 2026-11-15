// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * The dedup lookup {@link RollupWriter#alreadySeen} runs one constant statement with a collection parameter
 * ({@link RollupWriter#SEEN_IN_SQL}); ids are bound, in chunks of {@link RollupWriter#ID_CHUNK}. It must answer the
 * same for lists far larger than a chunk, at the chunk boundaries, and for ids that look like SQL.
 */
class RollupWriterSeenLookupH2Test {

    @Test
    void theStatementIsOneConstantWithACollectionParameter() {
        assertEquals("SELECT event_id FROM RU_EVENT_SEEN WHERE event_id IN (:ids)", RollupWriter.SEEN_IN_SQL);
    }

    @Test
    void aLookupFarLargerThanAChunkFindsExactlyTheSeenIds() {
        RollupTestDb db = new RollupTestDb();
        RollupWriter writer = new RollupWriter(db.jdbc);
        int total = RollupWriter.ID_CHUNK * 12 + 7;
        List<String> asked = new ArrayList<>();
        Set<String> expected = new TreeSet<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String id = "ev-" + i;
            asked.add(id);
            // every third id is already projected; the chunk edges (99, 100, 101 ...) fall on both sides
            if (i % 3 == 0) {
                expected.add(id);
                rows.add(new Object[]{id, "c1", "t1", 1L});
            }
        }
        writer.insertSeen(rows);
        assertEquals(expected, new TreeSet<>(writer.alreadySeen(asked)));
        assertEquals(total, asked.size());
        assertTrue(expected.size() > RollupWriter.ID_CHUNK, "more hits than one chunk holds");
    }

    @Test
    void exactlyOneChunkAndOneMoreBothWork() {
        RollupTestDb db = new RollupTestDb();
        RollupWriter writer = new RollupWriter(db.jdbc);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i <= RollupWriter.ID_CHUNK; i++) {
            ids.add("e" + i);
        }
        writer.insertSeen("e0", "c1", "t1", 1L);
        writer.insertSeen("e" + RollupWriter.ID_CHUNK, "c1", "t1", 1L);
        assertEquals(Set.of("e0"), writer.alreadySeen(ids.subList(0, RollupWriter.ID_CHUNK)));
        assertEquals(Set.of("e0", "e" + RollupWriter.ID_CHUNK), writer.alreadySeen(ids));
        assertEquals(Set.of(), writer.alreadySeen(List.of()));
        assertEquals(Set.of(), writer.alreadySeen(List.of("unknown")));
    }

    @Test
    void anIdThatLooksLikeSqlIsOnlyAValue() {
        RollupTestDb db = new RollupTestDb();
        RollupWriter writer = new RollupWriter(db.jdbc);
        writer.insertSeen("real", "c1", "t1", 1L);
        String hostile = "x') OR ('1'='1";
        assertFalse(writer.alreadySeen(List.of(hostile, "?", ":ids", "a,b")).contains("real"));
        writer.insertSeen(hostile, "c1", "t1", 1L);
        assertEquals(Set.of(hostile), writer.alreadySeen(List.of(hostile, ":ids")));
        assertEquals(2, db.count("SELECT COUNT(*) FROM RU_EVENT_SEEN"));
    }
}
