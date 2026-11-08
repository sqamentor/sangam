// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.support.EngineTx;

/** FC-02/FC-03 event and channel definition registry: versioning, lifecycle, validation. */
class EventRegistryH2Test {

    private PortsH2Engine h2;
    private EventRegistry reg;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        reg = new EventRegistry(Dialect.POSTGRESQL, EngineTx.transactions(h2.engine), Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private static String event(String key, String extra) {
        return "{\"key\":\"" + key + "\",\"payload\":[{\"name\":\"amount\",\"type\":\"long\"}],"
                + "\"correlationParameters\":[{\"name\":\"orderId\",\"type\":\"string\"}]" + extra + "}";
    }

    @Test
    void identicalContentIsTheSameVersionChangedContentTheNext() {
        assertEquals(1, reg.deployEvent("t1", event("paid", ""), null).version());
        assertEquals(1, reg.deployEvent("t1", event("paid", ""), null).version(), "same SHA-256: no new version");
        assertEquals(2, reg.deployEvent("t1", event("paid", ",\"ttlSeconds\":60"), null).version());
        assertEquals(2, reg.event("t1", "paid").orElseThrow().version());
        assertEquals(60L, reg.event("t1", "paid").orElseThrow().definition().ttlSeconds());
        assertTrue(reg.event("t2", "paid").isEmpty(), "tenant isolation");
        assertEquals(List.of(2, 1), reg.eventVersions("t1", "paid").stream().map(EventRegistry.VersionInfo::version).toList());
    }

    @Test
    void retireFallsBackToOlderActiveRestoreAndDeleteRules() {
        reg.deployEvent("t1", event("paid", ""), null);
        reg.deployEvent("t1", event("paid", ",\"ttlSeconds\":60"), null);
        assertEquals(1, reg.retireEvent("t1", "paid", 2, "admin"));
        assertEquals(1, reg.event("t1", "paid").orElseThrow().version(), "older active version is current again");
        assertThrows(IllegalStateException.class, () -> reg.deleteEvent("t1", "paid", 1), "active version cannot be deleted");
        reg.restoreEvent("t1", "paid", 2);
        assertEquals(2, reg.event("t1", "paid").orElseThrow().version());
        assertThrows(IllegalArgumentException.class, () -> reg.restoreEvent("t1", "paid", 2), "not retired");
        assertEquals(2, reg.retireEvent("t1", "paid", null, "admin"), "all active versions");
        assertTrue(reg.event("t1", "paid").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reg.retireEvent("t1", "paid", null, "admin"));
        assertEquals(2, reg.deleteEvent("t1", "paid", null));
        assertTrue(reg.eventVersions("t1", "paid").isEmpty());
    }

    @Test
    void versionNumbersAreNeverReusedAfterDeleteAll() {
        reg.deployEvent("t1", event("paid", ""), null);
        reg.deployEvent("t1", event("paid", ",\"ttlSeconds\":60"), null);
        reg.retireEvent("t1", "paid", null, "admin");
        assertEquals(2, reg.deleteEvent("t1", "paid", null));
        assertEquals(3, reg.deployEvent("t1", event("paid", ""), null).version(), "v1 and v2 are gone but never reused");
        reg.deployChannel("t1", "{\"key\":\"c\",\"channelType\":\"webhook\"}", null);
        reg.retireChannel("t1", "c", null, "admin");
        reg.deleteChannel("t1", "c", null);
        assertEquals(2, reg.deployChannel("t1", "{\"key\":\"c\",\"channelType\":\"webhook\"}", null).version());
        assertEquals(1, reg.deployEvent("t2", event("paid", ""), null).version(), "per tenant");
    }

    @Test
    void concurrentDeploysOfOneKeyGetDistinctVersions() throws Exception {
        int n = 6;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            int ttl = 100 + i;
            futures.add(pool.submit(() -> {
                start.await();
                return reg.deployEvent("t1", event("race", ",\"ttlSeconds\":" + ttl), null).version();
            }));
        }
        start.countDown();
        java.util.Set<Integer> versions = new java.util.TreeSet<>();
        for (var f : futures) {
            versions.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(java.util.Set.of(1, 2, 3, 4, 5, 6), versions);
    }

    @Test
    void retireIsRefusedWhileEventsAreInFlight() throws Exception {
        reg.deployEvent("t1", event("paid", ""), null);
        try (var c = h2.connection(); var st = c.createStatement()) {
            st.execute("INSERT INTO INBOX(tenant_id, source, message_id, correlation_key, payload, state, received_at, claim_epoch,"
                    + " next_attempt_at, expires_at) VALUES ('t1','evt:ch','m1','paid','{}','BUFFERED',CURRENT_TIMESTAMP,0,"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
        assertEquals(1, reg.eventInFlight("t1", "paid").inbox());
        assertThrows(EventRegistry.InFlightException.class, () -> reg.retireEvent("t1", "paid", null, "admin"));
        assertThrows(EventRegistry.InFlightException.class, () -> reg.deleteEvent("t1", "paid", null));
    }

    @Test
    void validationRejectsBadDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> reg.deployEvent("t1", event("bad key!", ""), null));
        assertThrows(IllegalArgumentException.class, () -> reg.deployEvent("t1", event("e", ",\"startUniqueReference\":\"nope\""), null),
                "unique reference must be a correlation parameter");
        assertThrows(IllegalArgumentException.class, () -> reg.deployEvent("t1", event("e", ",\"ttlSeconds\":99999999999"), null));
        assertThrows(IllegalArgumentException.class, () -> reg.deployEvent("t1",
                "{\"key\":\"e\",\"payload\":[{\"name\":\"\"}]}", null));
        reg.deployEvent("t1", event("e", ",\"startUniqueReference\":\"orderId\""), null);

        assertThrows(IllegalArgumentException.class, () -> reg.deployChannel("t1", "{\"key\":\"c\",\"channelType\":\"ftp\"}", null));
        assertThrows(IllegalArgumentException.class, () -> reg.deployChannel("t1", "{\"key\":\"c\",\"channelType\":\"http\",\"type\":\"inbound\"}", null));
        assertThrows(IllegalArgumentException.class, () -> reg.deployChannel("t1",
                "{\"key\":\"c\",\"channelType\":\"webhook\",\"type\":\"outbound\"}", null), "outbound webhook needs url");
        assertThrows(IllegalArgumentException.class, () -> reg.deployChannel("t1",
                "{\"key\":\"c\",\"channelType\":\"kafka\",\"type\":\"inbound\"}", null), "kafka needs topics");
        assertThrows(IllegalArgumentException.class, () -> reg.deployChannel("t1",
                "{\"key\":\"c\",\"channelType\":\"webhook\",\"allowPayloadTenant\":true}", null), "shared channel needs allowedTenants");
        assertThrows(IllegalArgumentException.class, () -> reg.deployChannel("t1",
                "{\"key\":\"o\",\"channelType\":\"webhook\",\"type\":\"outbound\",\"url\":\"http://127.0.0.1/x\",\"serializerType\":\"xml\"}",
                null), "unsupported outbound serializer is a deploy error");
        assertEquals(1, reg.deployChannel("t1", "{\"key\":\"c\",\"channelType\":\"webhook\"}", null).version());
        assertEquals(1, reg.allChannels().size());
    }
}
