// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.sangam.cell.registration.CellDescriptor;
import org.sangam.cell.registration.ControlPlaneClient;
import org.sangam.integration.relay.TenantAdmission;
import org.sangam.server.bridge.CellTenantStateStore;
import org.sangam.server.bridge.DedicatedTenant;
import org.sangam.server.bridge.DedicatedTenantFilter;

/**
 * M-016 (decision 51) on a cell: the broker inbox parks a message of another tenant on a dedicated cell (never stores
 * it in this tenant's database, never redelivers it forever); the cell registers as dedicated, and the registration
 * counts only when the control plane echoes the dedication (fail-closed).
 */
class DedicatedCellAdmissionTest {

    private static CellTenantStateStore.TenantState state(String tenant, CellTenantStateStore.State s) {
        return new CellTenantStateStore.TenantState(tenant, s, null, null, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void anotherTenantsMessageIsParkedOnADedicatedCell() {
        DedicatedTenant acme = DedicatedTenant.of("acme");
        TenantAdmission.Decision d = CellWiring.inboundDecision(acme, "other",
                t -> { throw new AssertionError("the move state is not consulted for another tenant"); });
        assertEquals(TenantAdmission.Action.PARK, d.action());
        assertEquals(DedicatedTenantFilter.CODE, d.tenantState());
        assertNull(d.targetCellOrNull());
    }

    @Test
    void theBoundTenantKeepsTheMoveStateDecision() {
        DedicatedTenant acme = DedicatedTenant.of("acme");
        assertSame(TenantAdmission.Decision.ADMIT,
                CellWiring.inboundDecision(acme, "acme", t -> state(t, CellTenantStateStore.State.ACTIVE)));
        assertSame(TenantAdmission.Decision.REDELIVER,
                CellWiring.inboundDecision(acme, "acme", t -> state(t, CellTenantStateStore.State.FROZEN)));
    }

    @Test
    void aSharedCellIsUnchanged() {
        assertSame(TenantAdmission.Decision.ADMIT,
                CellWiring.inboundDecision(null, "other", t -> state(t, CellTenantStateStore.State.ACTIVE)));
        assertNull(CellWiring.dedicatedTenantOf(null));
        assertEquals("acme", CellWiring.dedicatedTenantOf(DedicatedTenant.of("acme")));
    }

    @Test
    void theDescriptorNormalisesTheDedication() {
        assertFalse(descriptor(null).dedicated());
        assertFalse(descriptor(" ").dedicated());
        assertEquals("acme", descriptor(" acme ").dedicatedTenant());
    }

    private static CellDescriptor descriptor(String dedicatedTenant) {
        return new CellDescriptor("in-acme", "r1", "IN", "IN", "https://acme.example", null, "h2", 500, 1, "test",
                dedicatedTenant);
    }

    /** A control plane stub answering every PUT with {@code answer}; returns the last request body. */
    private static String register(CellDescriptor d, String answer) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            new ControlPlaneClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null,
                    Duration.ofMinutes(1)).register(d);
        } finally {
            server.stop(0);
        }
        return seen.get();
    }

    @Test
    void aDedicatedCellRegistersOnlyWhenTheControlPlaneRecordsTheDedication() throws Exception {
        String sent = register(descriptor("acme"), "{\"cellId\":\"in-acme\",\"dedicatedTenant\":\"ACME\"}");
        assertTrue(sent.contains("\"dedicatedTenant\":\"acme\""), sent);

        IOException ignored = assertThrows(IOException.class,
                () -> register(descriptor("acme"), "{\"cellId\":\"in-acme\"}"));
        assertTrue(ignored.getMessage().contains("stays unregistered"), ignored.getMessage());
        IOException other = assertThrows(IOException.class,
                () -> register(descriptor("acme"), "{\"cellId\":\"in-acme\",\"dedicatedTenant\":\"OTHER\"}"));
        assertTrue(other.getMessage().contains("OTHER"), other.getMessage());
    }

    @Test
    void aSharedCellSendsNoDedicationAndNeedsNoEcho() throws Exception {
        String sent = assertDoesNotThrow(() -> register(descriptor(null), "{\"cellId\":\"in-acme\"}"));
        assertFalse(sent.contains("dedicatedTenant"), sent);
    }
}
