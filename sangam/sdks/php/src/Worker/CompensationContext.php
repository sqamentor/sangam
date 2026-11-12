<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * What a saga compensation handler sees (M-026): the compensation work's own TaskContext and the record of the
 * forward action it undoes. The undo call reuses the forward operationId (R36): send idempotencyKey() so that
 * compensating the same forward action twice undoes it once.
 */
final class CompensationContext
{
    /** @internal created by Saga */
    public function __construct(
        private readonly TaskContext $task,
        private readonly ?SagaRecord $forward,
        private readonly string $step,
    ) {
    }

    /** The compensation work item (lease, variables, durable steps, logger, client). */
    public function task(): TaskContext
    {
        return $this->task;
    }

    /**
     * The forward action's record, or null when the instance holds none (the forward action did not complete
     * through Saga, so there is nothing this helper knows to undo).
     */
    public function forward(): ?SagaRecord
    {
        return $this->forward;
    }

    /** The forward action's operationId (R36), or null without a record. */
    public function forwardOperationId(): ?string
    {
        return $this->forward?->operationId;
    }

    /** The forward action's provider reference (for example the payment to refund), or null. */
    public function forwardProviderRef(): ?string
    {
        return $this->forward?->providerRef;
    }

    /**
     * Idempotency key of the undo call: forwardOperationId . ':compensate:' . step; without a record, the
     * compensation work's own operationId.
     */
    public function idempotencyKey(): string
    {
        return $this->forward === null
            ? $this->task->operationId()
            : $this->forward->operationId . ':compensate:' . $this->step;
    }
}
