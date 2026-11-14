// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.sangam.engine.job.PartitionAllocation;
import org.sangam.engine.job.PartitionResize;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.ExecutorPartitionPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;

/**
 * Decision 146: {@link ExecutorPartitionPort} over the engine's {@link PartitionResize}. The bridge maps shapes and
 * errors only; every rule (counts, one active resize, epoch check, idempotency of the resize id, audit) is the
 * engine's. The resize id is chosen here (a UUID, 36 characters, inside the engine's 1..64); a retry of the same HTTP
 * request is answered by the R41 idempotency layer with the stored resize. The request runs on the host transaction's
 * connection (W4-E ACK 02:14:18Z) so the idempotency record commits with it. The actor is the authenticated platform
 * principal of the request. {@link PartitionResize.Code} maps to VALIDATION_FAILED 400, INVALID_TRANSITION,
 * REVISION_CONFLICT and NOT_INITIALISED 409 (codes kept), NOT_FOUND 404.
 */
public final class ExecutorPartitionBridge implements ExecutorPartitionPort {

    /** The engine calls; {@link #of(PartitionResize, TxSupport)} for the real engine. */
    public interface Resizes {
        PartitionResize.PartitionMap map();

        PartitionResize.Resize request(String resizeId, String actor, String reason, int partitionCount,
                                       Long expectedMapEpoch);

        Optional<PartitionResize.Resize> get(String resizeId);

        List<PartitionResize.Resize> list(PartitionResize.State state);

        static Resizes of(PartitionResize engine, TxSupport tx) {
            return new Resizes() {
                @Override
                public PartitionResize.PartitionMap map() {
                    return engine.map();
                }

                @Override
                public PartitionResize.Resize request(String resizeId, String actor, String reason, int partitionCount,
                                                      Long expectedMapEpoch) {
                    return tx.withConnection(c -> engine.request(c, resizeId, actor, reason, partitionCount,
                            expectedMapEpoch));
                }

                @Override
                public Optional<PartitionResize.Resize> get(String resizeId) {
                    return engine.get(resizeId);
                }

                @Override
                public List<PartitionResize.Resize> list(PartitionResize.State state) {
                    return engine.list(state);
                }
            };
        }
    }

    private final Resizes engine;
    private final long countCap;
    private final Supplier<String> ids;

    public ExecutorPartitionBridge(Resizes engine, long countCap) {
        this(engine, countCap, () -> UUID.randomUUID().toString());
    }

    ExecutorPartitionBridge(Resizes engine, long countCap, Supplier<String> ids) {
        this.engine = engine;
        this.countCap = countCap;
        this.ids = ids;
    }

    @Override
    public ExecutorPartitionMap map(RequestContext ctx) {
        PartitionResize.PartitionMap m = call(engine::map);
        return new ExecutorPartitionMap(m.clusterId(), m.state(), m.currentMapEpoch(), m.targetMapEpoch(),
                m.partitionCount(), m.slotCount(), m.algorithmVersion(), m.partitions().stream().map(p ->
                        new ExecutorPartitionView(p.partitionId(), p.state(), p.ownerNode(), p.ownerEpoch(),
                                p.leaseExpiresAt(), p.slots().stream().map(s -> new ExecutorSlotRange(s.first(),
                                        s.last())).toList())).toList(),
                m.activeResize() == null ? null : resize(m.activeResize()));
    }

    @Override
    public ExecutorPartitionResize resize(RequestContext ctx, ExecutorPartitionResizeRequest r) {
        if (r == null || r.partitionCount() == null) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "partitionCount is required");
        }
        String id = ids.get();
        return resize(call(() -> engine.request(id, ctx.principalId(), r.reason(), r.partitionCount(),
                r.expectedMapEpoch())));
    }

    @Override
    public Page<ExecutorPartitionResize> list(RequestContext ctx, String state, Page.PageRequest page) {
        PartitionResize.State s;
        try {
            s = state == null ? null : PartitionResize.State.valueOf(state);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "unknown resize state " + state);
        }
        List<ExecutorPartitionResize> all = call(() -> engine.list(s)).stream().map(ExecutorPartitionBridge::resize)
                .toList();
        return Page.slice(all, page, countCap);
    }

    @Override
    public ExecutorPartitionResize get(RequestContext ctx, String id) {
        return resize(call(() -> engine.get(id)).orElseThrow(() -> ApiProblem.notFound("executor partition resize", id)));
    }

    static ExecutorPartitionResize resize(PartitionResize.Resize r) {
        List<PartitionAllocation.Move> moves = r.movedSlots() == null ? List.of() : r.movedSlots();
        return new ExecutorPartitionResize(r.id(), r.state().name(), r.fromCount(), r.toCount(), r.fromMapEpoch(),
                r.toMapEpoch(), r.algorithmVersion(), r.movedSlotCount(), moves.stream().map(m -> new ExecutorSlotMove(
                        m.first(), m.last(), m.fromPartition(), m.toPartition())).toList(), r.actor(), r.reason(),
                r.requestedAt(), r.startedAt(), r.completedAt(), r.failure(), r.handoverPauseMs());
    }

    /** Runs an engine call, mapping {@link PartitionResize.ResizeException} to the contract's problem. */
    static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (PartitionResize.ResizeException e) {
            throw problem(e);
        }
    }

    static ApiProblem problem(PartitionResize.ResizeException e) {
        String detail = e.getMessage();
        String prefix = e.code().name() + ": ";
        if (detail != null && detail.startsWith(prefix)) {
            detail = detail.substring(prefix.length());
        }
        return switch (e.code()) {
            case VALIDATION_FAILED -> ApiProblem.badRequest("VALIDATION_FAILED", detail);
            case INVALID_TRANSITION, REVISION_CONFLICT, NOT_INITIALISED -> ApiProblem.conflict(e.code().name(), detail);
            case NOT_FOUND -> new ApiProblem(404, "NOT_FOUND", detail);
        };
    }
}
