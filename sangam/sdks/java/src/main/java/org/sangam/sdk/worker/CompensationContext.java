// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

/**
 * What a saga compensation handler sees (M-026): the compensation work's own {@link TaskContext} and the record of
 * the forward action it undoes. The undo call reuses the forward operationId (R36): send
 * {@link #idempotencyKey()} so that compensating the same forward action twice undoes it once.
 */
public final class CompensationContext {

    private final TaskContext task;
    private final SagaRecord forward;
    private final String step;

    CompensationContext(TaskContext task, SagaRecord forward, String step) {
        this.task = task;
        this.forward = forward;
        this.step = step;
    }

    /** The compensation work item (lease, variables, durable steps, logger, client). */
    public TaskContext task() { return task; }

    /**
     * The forward action's record, or null when the instance holds none (the forward action did not complete through
     * {@link Saga}, so there is nothing this helper knows to undo).
     */
    public SagaRecord forward() { return forward; }

    /** The forward action's operationId (R36), or null without a record. */
    public String forwardOperationId() { return forward == null ? null : forward.operationId(); }

    /** The forward action's provider reference (e.g. the payment to refund), or null. */
    public String forwardProviderRef() { return forward == null ? null : forward.providerRef(); }

    /**
     * Idempotency key of the undo call: {@code forwardOperationId + ":compensate:" + step}; without a record, the
     * compensation work's own operationId.
     */
    public String idempotencyKey() {
        return forward == null ? task.operationId() : forward.operationId() + ":compensate:" + step;
    }
}
