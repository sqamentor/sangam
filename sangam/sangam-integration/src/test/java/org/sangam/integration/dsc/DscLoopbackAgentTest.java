// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Owner decision 165 and the coordinator ruling on the agent address: the DSC loopback agent is IPv4 loopback only. It
 * binds 127.0.0.1, answers a {@code Host} of {@code 127.0.0.1:<port>} or {@code localhost:<port>}, and refuses
 * {@code [::1]} and IPv4-mapped forms with {@code 421 HOST_NOT_ALLOWED}.
 */
class DscLoopbackAgentTest {

    private static final String ORIGIN = "https://sangam.example.org";

    private static final DscToken EMPTY_TOKEN = new DscToken() {
        @Override
        public List<Entry> entries() {
            return List.of();
        }

        @Override
        public byte[] sign(String certificateSha256, String algorithm, byte[] data) throws GeneralSecurityException {
            throw new GeneralSecurityException("no key in this test");
        }
    };

    private static DscLoopbackAgent start() throws IOException {
        return DscLoopbackAgent.start(new DscLoopbackAgent.Config(0, Set.of(ORIGIN)), EMPTY_TOKEN, r -> false);
    }

    /** Sends a raw GET /v1/status to 127.0.0.1 with the given Host header and returns the HTTP status code. */
    private static int status(int port, String host) throws IOException {
        try (Socket s = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port)) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            out.write(("GET /v1/status HTTP/1.1\r\nHost: " + host + "\r\nOrigin: " + ORIGIN
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String head = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            assertTrue(head.startsWith("HTTP/1.1 "), "an HTTP answer: " + head);
            return Integer.parseInt(head.substring(9, 12));
        }
    }

    @Test
    void bindsTheIpv4LoopbackAddressOnly() throws IOException {
        assertEquals("127.0.0.1", DscLoopbackAgent.BIND_ADDRESS.getHostAddress());
        try (DscLoopbackAgent agent = start()) {
            assertEquals(200, status(agent.port(), "127.0.0.1:" + agent.port()));
            assertThrows(IOException.class, () -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(InetAddress.getByName("::1"), agent.port()), 2_000);
                }
            }, "nothing answers on [::1] at the agent's port");
        }
    }

    @Test
    void answersOn127001AndLocalhost() throws IOException {
        try (DscLoopbackAgent agent = start()) {
            int p = agent.port();
            assertEquals(200, status(p, "127.0.0.1:" + p));
            assertEquals(200, status(p, "localhost:" + p));
            assertEquals(200, status(p, "LOCALHOST:" + p), "host names are case-insensitive");
        }
    }

    @Test
    void refusesIpv6AndMappedHostHeaders() throws IOException {
        try (DscLoopbackAgent agent = start()) {
            int p = agent.port();
            assertEquals(421, status(p, "[::1]:" + p));
            assertEquals(421, status(p, "[0:0:0:0:0:0:0:1]:" + p));
            assertEquals(421, status(p, "[::ffff:127.0.0.1]:" + p));
            assertEquals(421, status(p, "[::ffff:7f00:1]:" + p));
            assertEquals(421, status(p, "127.0.0.1"), "the port is required");
            assertEquals(421, status(p, "127.0.0.1:" + (p == 65535 ? 1 : p + 1)), "another port");
            assertEquals(421, status(p, "evil.example:" + p), "DNS rebinding");
            assertEquals(421, status(p, "127.1:" + p), "a short IPv4 form is not the canonical address");
        }
    }

    @Test
    void hostCheckAcceptsOnlyTheTwoIpv4Forms() {
        assertTrue(DscLoopbackAgent.hostIsLoopback("127.0.0.1:8765", 8765));
        assertTrue(DscLoopbackAgent.hostIsLoopback("localhost:8765", 8765));
        assertFalse(DscLoopbackAgent.hostIsLoopback("[::1]:8765", 8765));
        assertFalse(DscLoopbackAgent.hostIsLoopback("[::ffff:127.0.0.1]:8765", 8765));
        assertFalse(DscLoopbackAgent.hostIsLoopback("[::ffff:7f00:1]:8765", 8765));
        assertFalse(DscLoopbackAgent.hostIsLoopback("localhost.:8765", 8765));
        assertFalse(DscLoopbackAgent.hostIsLoopback(null, 8765));
    }

    @Test
    void peerCheckAcceptsOnly127001() throws IOException {
        assertTrue(DscLoopbackAgent.peerIsLoopback(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 50000)));
        assertFalse(DscLoopbackAgent.peerIsLoopback(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 2}), 50000)),
                "only the exact bind address");
        byte[] v6Loopback = new byte[16];
        v6Loopback[15] = 1;
        assertFalse(DscLoopbackAgent.peerIsLoopback(
                new InetSocketAddress(InetAddress.getByAddress(v6Loopback), 50000)), "::1 is refused");
        assertFalse(DscLoopbackAgent.peerIsLoopback(null));
    }
}
