// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * SL-142 / owner decision 61: parked inbound messages are a cell-operator matter (role CELL_ADMIN), listed across
 * tenants and replayed through the transport; PoisonInbound's outcomes map to 404 / 409 / 503 and a missing gateway
 * URL refuses the replay instead of closing the item.
 */
class CellParkedInboundControllerTest {

    static final RequestContext CELL_OP = ctx(Set.of("CELL_ADMIN"));
    static final RequestContext TENANT_ADMIN = ctx(Set.of("sangam-admin"));

    static RequestContext ctx(Set<String> groups) {
        return new RequestContext(null, "cell-op", groups, null, null, null, null, null, "token");
    }

    static PoisonInbound.Item item(long id, String tenant) {
        return new PoisonInbound.Item(id, tenant, id + 100, "kafka:payments", "m-" + id, "PARKED: tenant MOVED", "OPEN",
                Instant.parse("2026-11-12T00:00:00Z"), true);
    }

    static CellParkedInboundController controller(PoisonInbound poison, PoisonInbound.ParkedReplay transport) {
        StaticListableBeanFactory f = new StaticListableBeanFactory(transport == null
                ? Map.of("poison", poison) : Map.of("poison", poison, "transport", transport));
        return new CellParkedInboundController(f.getBeanProvider(PoisonInbound.class),
                f.getBeanProvider(PoisonInbound.ParkedReplay.class));
    }

    @Test
    void theCellOperatorListsEveryTenantAndMayNarrowToOne() {
        PoisonInbound poison = mock(PoisonInbound.class);
        when(poison.listParked()).thenReturn(List.of(item(1, "T1"), item(2, "T2")));
        CellParkedInboundController c = controller(poison, p -> { });
        assertEquals(2, c.list(CELL_OP, null).size());
        assertEquals(List.of(2L), c.list(CELL_OP, "T2").stream().map(PoisonInbound.Item::itemId).toList());
    }

    @Test
    void aTenantAdministratorIsNotACellOperator() {
        PoisonInbound poison = mock(PoisonInbound.class);
        CellParkedInboundController c = controller(poison, p -> { });
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.list(TENANT_ADMIN, null)).status());
        assertEquals(403, assertThrows(ApiProblem.class, () -> c.replay(TENANT_ADMIN, 1,
                new CellParkedInboundController.ReasonRequest("x"))).status());
        verify(poison, never()).replayParked(anyLong(), any(), anyString(), anyString());
    }

    @Test
    void replayGoesThroughTheOneParkStoreWithTheActorAndReason() {
        PoisonInbound poison = mock(PoisonInbound.class);
        PoisonInbound.ParkedReplay transport = p -> { };
        CellParkedInboundController c = controller(poison, transport);
        assertEquals(204, c.replay(CELL_OP, 7, new CellParkedInboundController.ReasonRequest(" moved to cell-b "))
                .getStatusCode().value());
        verify(poison).replayParked(7L, transport, "cell-op", "moved to cell-b");
    }

    @Test
    void withoutAGatewayUrlTheReplayIsRefusedAndTheItemStaysOpen() {
        PoisonInbound poison = mock(PoisonInbound.class);
        CellParkedInboundController c = controller(poison, null);
        ApiProblem p = assertThrows(ApiProblem.class, () -> c.replay(CELL_OP, 7,
                new CellParkedInboundController.ReasonRequest("x")));
        assertEquals(503, p.status());
        assertEquals("REPLAY_NOT_CONFIGURED", p.code());
        verify(poison, never()).replayParked(anyLong(), any(), anyString(), anyString());
        assertEquals(400, assertThrows(ApiProblem.class, () -> controller(poison, q -> { }).replay(CELL_OP, 7,
                new CellParkedInboundController.ReasonRequest(" "))).status(), "a reason is required");
    }

    @Test
    void poisonInboundOutcomesMapToProblems() {
        PoisonInbound poison = mock(PoisonInbound.class);
        when(poison.parked(5L)).thenThrow(new IllegalArgumentException("no POISON_INBOUND item 5"));
        when(poison.parked(6L)).thenThrow(new IllegalStateException("409 NOT_PARKED: item 6 is a poison message"));
        doThrow(new IllegalStateException("replay of parked item 8 failed; the item stays open: HTTP 421"))
                .when(poison).replayParked(eq(8L), any(), anyString(), anyString());
        CellParkedInboundController c = controller(poison, p -> { });
        assertEquals(404, assertThrows(ApiProblem.class, () -> c.get(CELL_OP, 5)).status());
        ApiProblem conflict = assertThrows(ApiProblem.class, () -> c.get(CELL_OP, 6));
        assertEquals(409, conflict.status());
        assertEquals("NOT_PARKED", conflict.code());
        ApiProblem failed = assertThrows(ApiProblem.class, () -> c.replay(CELL_OP, 8,
                new CellParkedInboundController.ReasonRequest("x")));
        assertEquals(503, failed.status());
        assertEquals("REPLAY_FAILED", failed.code());
    }
}
