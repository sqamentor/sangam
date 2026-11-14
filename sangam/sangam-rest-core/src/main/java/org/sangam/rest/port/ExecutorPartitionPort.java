// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;

/**
 * M-056, owner decision 146: the executor partition map and a controlled change of the partition count (contract
 * {@code routes/executor.py}, operations {@code getExecutorPartitionMap}, {@code resizeExecutorPartitions},
 * {@code listExecutorPartitionResizes} and {@code getExecutorPartitionResize}). The rules live in the engine
 * ({@code org.sangam.engine.job.PartitionResize}); the port carries the REST shapes only. Errors: 400
 * {@code VALIDATION_FAILED}, 409 {@code INVALID_TRANSITION}, {@code REVISION_CONFLICT} or {@code NOT_INITIALISED}, 404
 * {@code NOT_FOUND}.
 */
public interface ExecutorPartitionPort {

    /** Contract schema {@code ExecutorSlotRange}. */
    record ExecutorSlotRange(int first, int last) {
    }

    /** Contract schema {@code ExecutorPartitionView}. */
    record ExecutorPartitionView(int partitionId, String state, String ownerNode, long ownerEpoch,
                                 Instant leaseExpiresAt, List<ExecutorSlotRange> slots) {
    }

    /** Contract schema {@code ExecutorSlotMove}. */
    record ExecutorSlotMove(int first, int last, int fromPartition, int toPartition) {
    }

    /** Contract schema {@code ExecutorPartitionResize}. */
    record ExecutorPartitionResize(String id, String state, int fromPartitionCount, int toPartitionCount,
                                   long fromMapEpoch, long toMapEpoch, String algorithmVersion, Integer movedSlotCount,
                                   List<ExecutorSlotMove> movedSlots, String actor, String reason, Instant requestedAt,
                                   Instant startedAt, Instant completedAt, String failure, Long handoverPauseMs) {
    }

    /** Contract schema {@code ExecutorPartitionMap}. */
    record ExecutorPartitionMap(String clusterId, String state, long currentMapEpoch, Long targetMapEpoch,
                                int partitionCount, int slotCount, String algorithmVersion,
                                List<ExecutorPartitionView> partitions, ExecutorPartitionResize activeResize) {
    }

    /** Contract schema {@code ExecutorPartitionResizeRequest}. */
    record ExecutorPartitionResizeRequest(Integer partitionCount, String reason, Long expectedMapEpoch) {
    }

    /** The committed map, read in one transaction; 409 NOT_INITIALISED without an executor map. */
    ExecutorPartitionMap map(RequestContext ctx);

    /** Accepts a resize (state REQUESTED); the resize id is chosen by the server. */
    ExecutorPartitionResize resize(RequestContext ctx, ExecutorPartitionResizeRequest request);

    /** Resizes newest first, {@code movedSlots} empty; {@code state} null for all. Paged by the host. */
    Page<ExecutorPartitionResize> list(RequestContext ctx, String state, Page.PageRequest page);

    /** One resize with its moved slot ranges; 404 NOT_FOUND for an unknown id. */
    ExecutorPartitionResize get(RequestContext ctx, String id);
}
