// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.engine.job.PartitionAllocation;
import org.sangam.engine.job.PartitionResize;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.ExecutorPartitionPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;

/**
 * Decision 146 bridge: the engine's map and resizes map field for field onto the contract, the server chooses the
 * resize id and passes the authenticated principal as actor, the host pages the list, and every
 * {@link PartitionResize.Code} is the contract's status with its code kept.
 */
class ExecutorPartitionBridgeTest {

    static final RequestContext CTX = new RequestContext("platform", "ops-admin", Set.of("sangam-admin"), null, null,
            "key-1", null, "127.0.0.1", "OIDC");
    static final Instant AT = Instant.parse("2026-11-13T00:00:00Z");

    final List<String> calls = new ArrayList<>();

    static PartitionResize.Resize resize(String id, PartitionResize.State state, List<PartitionAllocation.Move> moves) {
        return new PartitionResize.Resize(id, state, 4, 6, 7, 8, "MM-1", moves.isEmpty() ? null : 2, moves, "ops-admin",
                "peak", AT, null, null, null, null);
    }

    ExecutorPartitionBridge bridge(PartitionResize.ResizeException failure, List<PartitionResize.Resize> stored) {
        return new ExecutorPartitionBridge(new ExecutorPartitionBridge.Resizes() {
            @Override
            public PartitionResize.PartitionMap map() {
                if (failure != null) {
                    throw failure;
                }
                return new PartitionResize.PartitionMap("c1", "TRANSITION", 7, 8L, 4, 16, "CONTIGUOUS-1",
                        List.of(new PartitionResize.PartitionView(1, "ACTIVE", "node-a", 3, AT,
                                List.of(new PartitionResize.SlotRange(0, 3), new PartitionResize.SlotRange(8, 9))),
                                new PartitionResize.PartitionView(5, "UNOWNED", null, 0, null, List.of())),
                        resize("r-0", PartitionResize.State.IN_PROGRESS,
                                List.of(new PartitionAllocation.Move(8, 9, 1, 5))));
            }

            @Override
            public PartitionResize.Resize request(String id, String actor, String reason, int count, Long epoch) {
                calls.add(id + ":" + actor + ":" + reason + ":" + count + ":" + epoch);
                if (failure != null) {
                    throw failure;
                }
                return resize(id, PartitionResize.State.REQUESTED, List.of());
            }

            @Override
            public Optional<PartitionResize.Resize> get(String id) {
                return stored.stream().filter(r -> r.id().equals(id)).findFirst();
            }

            @Override
            public List<PartitionResize.Resize> list(PartitionResize.State state) {
                calls.add("list:" + state);
                return stored;
            }
        }, 1000, () -> "resize-uuid");
    }

    @Test
    void theMapMapsFieldForField() {
        ExecutorPartitionPort.ExecutorPartitionMap m = bridge(null, List.of()).map(CTX);
        assertEquals("c1", m.clusterId());
        assertEquals("TRANSITION", m.state());
        assertEquals(7, m.currentMapEpoch());
        assertEquals(8L, m.targetMapEpoch());
        assertEquals(4, m.partitionCount());
        assertEquals(16, m.slotCount());
        assertEquals("CONTIGUOUS-1", m.algorithmVersion());
        assertEquals(new ExecutorPartitionPort.ExecutorPartitionView(1, "ACTIVE", "node-a", 3, AT, List.of(
                new ExecutorPartitionPort.ExecutorSlotRange(0, 3), new ExecutorPartitionPort.ExecutorSlotRange(8, 9))),
                m.partitions().get(0));
        assertEquals(List.of(), m.partitions().get(1).slots());
        assertNull(m.partitions().get(1).ownerNode());
        assertEquals("r-0", m.activeResize().id());
        assertEquals(List.of(new ExecutorPartitionPort.ExecutorSlotMove(8, 9, 1, 5)), m.activeResize().movedSlots());
    }

    @Test
    void theServerChoosesTheIdAndTheActorIsTheAuthenticatedPrincipal() {
        ExecutorPartitionPort.ExecutorPartitionResize r = bridge(null, List.of()).resize(CTX,
                new ExecutorPartitionPort.ExecutorPartitionResizeRequest(6, "peak", 7L));
        assertEquals(List.of("resize-uuid:ops-admin:peak:6:7"), calls);
        assertEquals("resize-uuid", r.id());
        assertEquals("REQUESTED", r.state());
        assertEquals(4, r.fromPartitionCount());
        assertEquals(6, r.toPartitionCount());
        assertNull(r.movedSlotCount(), "absent while REQUESTED");
        assertEquals(List.of(), r.movedSlots());
        assertTrue(r.id().length() <= 64, "inside the engine's 1..64");
    }

    @Test
    void everyEngineCodeIsTheContractStatusWithItsCodeKept() {
        record Case(PartitionResize.Code code, int status) {
        }
        for (Case c : List.of(new Case(PartitionResize.Code.VALIDATION_FAILED, 400),
                new Case(PartitionResize.Code.INVALID_TRANSITION, 409), new Case(PartitionResize.Code.REVISION_CONFLICT,
                        409), new Case(PartitionResize.Code.NOT_INITIALISED, 409),
                new Case(PartitionResize.Code.NOT_FOUND, 404))) {
            PartitionResize.ResizeException e = new PartitionResize.ResizeException(c.code(), "why");
            ApiProblem p = assertThrows(ApiProblem.class, () -> bridge(e, List.of()).resize(CTX,
                    new ExecutorPartitionPort.ExecutorPartitionResizeRequest(6, "peak", null)));
            assertEquals(c.status(), p.status(), c.code().name());
            assertEquals(c.code().name(), p.code());
            assertEquals("why", p.detail());
        }
        ApiProblem notInitialised = assertThrows(ApiProblem.class, () -> bridge(new PartitionResize.ResizeException(
                PartitionResize.Code.NOT_INITIALISED, "no executor map"), List.of()).map(CTX));
        assertEquals(409, notInitialised.status());
        assertEquals("NOT_INITIALISED", notInitialised.code());
    }

    @Test
    void getIs404ForAnUnknownIdAndTheHostPagesTheList() {
        List<PartitionResize.Resize> stored = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            stored.add(resize("r-" + i, PartitionResize.State.COMPLETED, List.of()));
        }
        ExecutorPartitionBridge b = bridge(null, stored);
        assertEquals("r-3", b.get(CTX, "r-3").id());
        ApiProblem missing = assertThrows(ApiProblem.class, () -> b.get(CTX, "nope"));
        assertEquals(404, missing.status());

        Page<ExecutorPartitionPort.ExecutorPartitionResize> page = b.list(CTX, "COMPLETED", Page.PageRequest.of(1, 2));
        assertEquals(List.of("r-2", "r-3"), page.items().stream().map(ExecutorPartitionPort.ExecutorPartitionResize::id)
                .toList());
        assertEquals(5, page.total());
        assertEquals("list:COMPLETED", calls.get(calls.size() - 1));
        b.list(CTX, null, Page.PageRequest.of(null, null));
        assertEquals("list:null", calls.get(calls.size() - 1));
        assertEquals(400, assertThrows(ApiProblem.class, () -> b.list(CTX, "DONE", Page.PageRequest.of(0, 10)))
                .status());
    }
}
