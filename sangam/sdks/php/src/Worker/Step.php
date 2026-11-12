<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * The handle a durable step's body and lookup receive (TaskContext::step(), M-025): the step's name, its stable
 * idempotency key and a slot for the provider's reference.
 */
final class Step
{
    private ?string $providerRef = null;

    /** @internal created by DurableSteps */
    public function __construct(
        private readonly string $name,
        private readonly string $operationId,
        private readonly ?string $previousState,
    ) {
    }

    /** The step name, unique within the work's operation. */
    public function name(): string
    {
        return $this->name;
    }

    /** The work's stable operationId (R36). */
    public function operationId(): string
    {
        return $this->operationId;
    }

    /**
     * Idempotency key for the remote call: operationId . ':' . name. Stable across retries, redelivery and lease
     * expiry, and different for each step of the same operation.
     */
    public function idempotencyKey(): string
    {
        return $this->operationId . ':' . $this->name;
    }

    /**
     * What an earlier attempt recorded for this step: null on its first run, 'INTENT' when that attempt's outcome is
     * unknown, 'FAILED' when it failed without effect.
     */
    public function previousState(): ?string
    {
        return $this->previousState;
    }

    /** Records the provider's reference with the outcome (for example a payment id); later lookups can use it. */
    public function setProviderRef(?string $ref): void
    {
        $this->providerRef = $ref;
    }

    public function providerRef(): ?string
    {
        return $this->providerRef;
    }
}
