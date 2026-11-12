// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Owner decision 97 (Q-W3H-5 A): a shared instance refuses a tenant recorded with isolation DATABASE or SCHEMA; the
 * recorded value is cached per tenant for a short time; a failing lookup is never turned into "served".
 */
class SharedInstanceIsolationTest {

    @Test
    void databaseAndSchemaAreNotServedOnASharedInstance() {
        assertTrue(SharedInstanceIsolation.servedOnShared(null), "no record");
        assertTrue(SharedInstanceIsolation.servedOnShared("SHARED"));
        assertFalse(SharedInstanceIsolation.servedOnShared("DATABASE"));
        assertFalse(SharedInstanceIsolation.servedOnShared("SCHEMA"));
        assertFalse(SharedInstanceIsolation.servedOnShared(" database "), "compared trimmed and upper-case");
    }

    @Test
    void theRecordedIsolationDecides() {
        Map<String, String> directory = Map.of("t-shared", "SHARED", "t-db", "DATABASE", "t-schema", "SCHEMA");
        SharedInstanceIsolation s = new SharedInstanceIsolation(directory::get);
        assertTrue(s.serves("t-shared"));
        assertTrue(s.serves("unknown"), "no record: the later layers answer as before");
        assertFalse(s.serves("t-db"));
        assertFalse(s.serves("t-schema"));
        assertTrue(s.serves(null));
        assertTrue(s.serves(" "));
        assertTrue(s.serves("*"), "the platform wildcard is no tenant");
    }

    @Test
    void theIsolationIsCachedForTheTtlThenReadAgain() {
        Map<String, String> directory = new HashMap<>(Map.of("t1", "SHARED"));
        AtomicInteger reads = new AtomicInteger();
        AtomicLong now = new AtomicLong(1_000);
        SharedInstanceIsolation s = new SharedInstanceIsolation(id -> {
            reads.incrementAndGet();
            return directory.get(id);
        }, now::get);
        assertTrue(s.serves("t1"));
        directory.put("t1", "DATABASE");
        assertTrue(s.serves("t1"), "cached");
        assertEquals(1, reads.get());
        now.addAndGet(SharedInstanceIsolation.TTL.toMillis());
        assertFalse(s.serves("t1"), "read again after the TTL");
        assertEquals(2, reads.get());
    }

    @Test
    void aFailingLookupIsNotServedAndNotCached() {
        AtomicInteger reads = new AtomicInteger();
        SharedInstanceIsolation s = new SharedInstanceIsolation(id -> {
            if (reads.incrementAndGet() == 1) {
                throw new IllegalStateException("directory down");
            }
            return "SHARED";
        });
        assertThrows(IllegalStateException.class, () -> s.serves("t1"));
        assertTrue(s.serves("t1"), "the failure was not cached");
        assertEquals(2, reads.get());
    }
}
