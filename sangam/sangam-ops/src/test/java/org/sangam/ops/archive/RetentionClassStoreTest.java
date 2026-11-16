// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.sangam.core.ports.DocumentStore;

/**
 * RM-52 (decision 386(1)): the archive store enforces its records-retention class. An archive is stored only under its
 * class and with a retention marker; neither it nor its marker can be deleted before the retain-until time, an object
 * without a marker is never deleted, and the one early removal is an erasure's supersede by a stored replacement in the
 * same folder.
 */
class RetentionClassStoreTest {

    static final String KEY = "t1/records/RECORDS_HISTORY/history/root-1/aaaa.zip";
    static final String NEXT = "t1/records/RECORDS_HISTORY/history/root-1/bbbb.zip";

    /** A plain in-memory store with the document-store contract (put fails on an existing key). */
    static final class Memory implements DocumentStore {
        final Map<String, byte[]> objects = new TreeMap<>();

        @Override
        public String type() {
            return "memory";
        }

        @Override
        public StoredObject put(String key, InputStream content, String contentType) throws IOException {
            if (objects.containsKey(key)) {
                throw new IOException("exists: " + key);
            }
            byte[] b = content.readAllBytes();
            objects.put(key, b);
            try {
                return new StoredObject(key, b.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Optional<InputStream> get(String key) {
            byte[] b = objects.get(key);
            return b == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(b));
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }
    }

    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-16T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anArchiveIsKeptForItsClassAndThenMayGo() throws Exception {
        Memory memory = new Memory();
        MovableClock clock = new MovableClock();
        RetentionClassStore store = new RetentionClassStore(memory, "RECORDS_HISTORY", 3650, clock);
        store.put(KEY, bytes("archive"), "application/zip");
        assertTrue(memory.exists(KEY + RetentionClassStore.MARKER), "the marker is stored with the archive");
        assertEquals(clock.now.plus(Duration.ofDays(3650)), store.retainUntil(KEY).orElseThrow());

        RetentionClassStore.RetentionRefusedException active = assertThrows(
                RetentionClassStore.RetentionRefusedException.class, () -> store.delete(KEY));
        assertEquals("RETENTION_ACTIVE", active.code());
        assertEquals("RETENTION_MARKER_PROTECTED", assertThrows(RetentionClassStore.RetentionRefusedException.class,
                () -> store.delete(KEY + RetentionClassStore.MARKER)).code());
        assertTrue(memory.exists(KEY) && memory.exists(KEY + RetentionClassStore.MARKER), "nothing was deleted");

        clock.now = clock.now.plus(Duration.ofDays(3650));
        store.delete(KEY);
        assertFalse(memory.exists(KEY));
        assertFalse(memory.exists(KEY + RetentionClassStore.MARKER));
    }

    @Test
    void onlyKeysOfTheClassAreStoredAndAnObjectWithoutAMarkerIsKept() throws Exception {
        Memory memory = new Memory();
        RetentionClassStore store = new RetentionClassStore(memory, "RECORDS_HISTORY", 3650, new MovableClock());
        for (String key : new String[] {"t1/records/OTHER/history/root-1/a.zip", "t1/history/root-1/a.zip",
                "t1/records/RECORDS_HISTORY", KEY + RetentionClassStore.MARKER}) {
            assertThrows(RetentionClassStore.RetentionRefusedException.class,
                    () -> store.put(key, bytes("x"), "application/zip"), key);
        }
        assertTrue(memory.objects.isEmpty(), "nothing outside the class was stored");
        memory.put(KEY, bytes("written past the class"), "application/zip");
        assertEquals("RETENTION_UNKNOWN", assertThrows(RetentionClassStore.RetentionRefusedException.class,
                () -> store.delete(KEY)).code());
        assertTrue(memory.exists(KEY));
    }

    @Test
    void anErasureSupersedesOnlyWithAStoredReplacementInTheSameFolder() throws Exception {
        Memory memory = new Memory();
        RetentionClassStore store = new RetentionClassStore(memory, "RECORDS_HISTORY", 3650, new MovableClock());
        store.put(KEY, bytes("archive with erased data"), "application/zip");
        assertThrows(RetentionClassStore.RetentionRefusedException.class, () -> store.supersede(KEY, NEXT),
                "no replacement stored yet");
        String elsewhere = "t1/records/RECORDS_HISTORY/history/root-2/cccc.zip";
        store.put(elsewhere, bytes("another tree"), "application/zip");
        assertThrows(RetentionClassStore.RetentionRefusedException.class, () -> store.supersede(KEY, elsewhere),
                "another tree's archive is no replacement");
        assertThrows(RetentionClassStore.RetentionRefusedException.class, () -> store.supersede(KEY, KEY));
        assertTrue(memory.exists(KEY));

        store.put(NEXT, bytes("scrubbed archive"), "application/zip");
        assertTrue(store.supersede(KEY, NEXT));
        assertFalse(memory.exists(KEY) || memory.exists(KEY + RetentionClassStore.MARKER));
        assertTrue(memory.exists(NEXT) && memory.exists(NEXT + RetentionClassStore.MARKER));
        assertFalse(store.supersede(KEY, NEXT), "already gone");
    }

    @Test
    void theArchivesStoreThroughTheClassOfTheirSettings() {
        Memory memory = new Memory();
        RetentionClassStore other = new RetentionClassStore(memory, "STATE_ARCHIVE_30Y", 10950, new MovableClock());
        assertThrows(IllegalArgumentException.class, () -> new RetentionClassStore(other, "RECORDS_HISTORY", 3650,
                new MovableClock()), "never wrapped twice");
        assertThrows(IllegalArgumentException.class, () -> new HistoryArchives(other,
                new org.sangam.core.layer.AuditChain(org.sangam.core.layer.Dialect.POSTGRESQL), null, null,
                new HistoryArchives.Settings("RECORDS_HISTORY", 3650, "test", "")));
    }
}
