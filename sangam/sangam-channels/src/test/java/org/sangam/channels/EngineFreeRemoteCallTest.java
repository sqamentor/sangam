// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.engine.RemoteCalls;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.sim.SimHttpServer;

/**
 * sangam-channels, like the pdf-validator and OCR sidecars and sangam-cli, carries sangam-integration without
 * sangam-engine. Remote calls there must not link engine classes (the 5bf011a4 NoClassDefFoundError
 * org/sangam/engine/tx/LockScope) and, with no engine, no L2 lock can be held, so the call goes out.
 */
class EngineFreeRemoteCallTest {

    @Test
    void theEngineIsNotOnThisClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.sangam.engine.tx.LockScope"));
        assertEquals(0, RemoteCalls.probeCount());
    }

    static final class PingServer extends SimHttpServer {
        PingServer() {
            super("engine-free-stub");
            route("GET", "/ping", r -> Response.json(200, "{\"ok\":true}"));
        }
    }

    @Test
    void anHttpCallWorksWithoutTheEngine() {
        try (PingServer server = new PingServer().start()) {
            HttpTransport.Outcome o = HttpTransport.forSimulator().execute("t1",
                    HttpTransport.Call.get(server.uri("/ping"), Map.of()), 0);
            assertEquals(200, assertInstanceOf(HttpTransport.Outcome.Response.class, o).status());
        }
    }
}
