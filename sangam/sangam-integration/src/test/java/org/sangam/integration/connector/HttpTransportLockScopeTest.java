// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.engine.tx.LockScope;
import org.sangam.integration.sim.SimHttpServer;

/** SANGAM.md VI.1 rule 5, owner decision 86: no outbound HTTP call while an L2 root lock is held. */
class HttpTransportLockScopeTest {

    static final class CountingServer extends SimHttpServer {
        final AtomicInteger hits = new AtomicInteger();

        CountingServer() {
            super("lock-scope-stub");
            route("GET", "/ping", r -> {
                hits.incrementAndGet();
                return Response.json(200, "{\"ok\":true}");
            });
        }
    }

    private CountingServer server;

    @BeforeEach
    void start() {
        server = new CountingServer().start();
    }

    @AfterEach
    void stop() {
        while (LockScope.isLockHeld()) {
            LockScope.exit();
        }
        server.close();
    }

    @Test
    void aCallWithNoLockHeldIsSent() {
        HttpTransport.Outcome o = HttpTransport.forSimulator().execute("t1",
                HttpTransport.Call.get(server.uri("/ping"), Map.of()), 0);
        assertEquals(200, assertInstanceOf(HttpTransport.Outcome.Response.class, o).status());
        assertEquals(1, server.hits.get());
    }

    @Test
    void aCallWhileL2IsHeldFailsClearlyAndSendsNothing() {
        LockScope.enter();
        LockScope.IllegalRemoteCallInLockScope e = assertThrows(LockScope.IllegalRemoteCallInLockScope.class,
                () -> HttpTransport.forSimulator().execute("t1",
                        HttpTransport.Call.get(server.uri("/ping?token=s3cr3t"), Map.of()), 0));
        assertTrue(e.getMessage().startsWith("IllegalRemoteCallInLockScope: HTTP GET http://"), e.getMessage());
        assertTrue(e.getMessage().contains("outbox or an external task"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t") || e.getMessage().contains("/ping"),
                "the message names the origin only: " + e.getMessage());
        assertEquals(0, server.hits.get(), "nothing reached the server");
    }

    @Test
    void theCallWorksAgainOnceTheCommandEnds() {
        LockScope.enter();
        LockScope.enter();
        LockScope.exit();
        assertThrows(LockScope.IllegalRemoteCallInLockScope.class, () -> HttpTransport.forSimulator().execute("t1",
                HttpTransport.Call.get(server.uri("/ping"), Map.of()), 0), "a nested command still holds the lock");
        LockScope.exit();
        assertInstanceOf(HttpTransport.Outcome.Response.class, HttpTransport.forSimulator().execute("t1",
                HttpTransport.Call.get(server.uri("/ping"), Map.of()), 0));
    }
}
