<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

use Sangam\Sdk\Log\Log;
use Sangam\Sdk\Model\ExternalWork;
use Sangam\Sdk\Model\TypedValue;
use Sangam\Sdk\SangamClient;

/**
 * What a handler sees while it holds the lease: the work item and its identities, the lease state, cooperative
 * cancellation, a logger pre-filled with the work fields, and the client.
 *
 * Fork mode: the parent process heartbeats; cancel/lease-loss/shutdown arrive as signals and flip the flags here,
 * and LockState updates (lockVersion, lockExpiresAt) arrive through a pipe that heartbeat() drains.
 * Inline mode: call heartbeat() during long work; it extends the lease at most every lease/3.
 */
final class TaskContext
{
    private int $lockVersion;
    private ?float $lockExpiresAt;
    private float $lastExtendedAt;
    private bool $cancelRequested = false;
    private bool $leaseLost = false;
    private bool $shuttingDown = false;
    private readonly Log $log;

    /** @param (\Closure(TaskContext, bool): void)|null $heartbeat inline-mode heartbeat (null in fork mode) */
    public function __construct(
        private readonly ExternalWork $work,
        private readonly string $workerId,
        private readonly SangamClient $client,
        Log $log,
        private readonly ?\Closure $heartbeat = null,
    ) {
        $this->lockVersion = $work->lockVersion;
        $this->lockExpiresAt = $work->lockExpiresAtTs();
        $this->lastExtendedAt = microtime(true);
        $fields = ['workerId' => $workerId, 'workId' => $work->id, 'topic' => $work->topic,
            'operationId' => $work->operationId, 'attemptId' => $work->attemptId, 'lockVersion' => $work->lockVersion,
            'businessKey' => $work->businessKey, 'processInstanceId' => $work->processInstanceId];
        $this->log = $log->with(array_filter($fields, static fn($v) => $v !== null));
    }

    public function work(): ExternalWork
    {
        return $this->work;
    }

    public function workId(): string
    {
        return (string) $this->work->id;
    }

    public function topic(): ?string
    {
        return $this->work->topic;
    }

    public function businessKey(): ?string
    {
        return $this->work->businessKey;
    }

    public function processInstanceId(): ?string
    {
        return $this->work->processInstanceId;
    }

    public function workerId(): string
    {
        return $this->workerId;
    }

    /** R36 stable business-operation id (falls back to the work id). */
    public function operationId(): string
    {
        return $this->work->operationIdOrId();
    }

    /** Idempotency key to forward to downstream systems (= operationId, VI.3.2 rule 4). */
    public function idempotencyKey(): string
    {
        return $this->operationId();
    }

    /** Changes per lease (A-2). */
    public function attemptId(): ?string
    {
        return $this->work->attemptId;
    }

    /** Current fencing token. */
    public function lockVersion(): int
    {
        return $this->lockVersion;
    }

    public function lockExpiresAt(): ?float
    {
        return $this->lockExpiresAt;
    }

    /** @return array<string, mixed> */
    public function variables(): array
    {
        return $this->work->variables;
    }

    /**
     * A variable with R33 TypedValue unwrapped: {"type":"Decimal","value":"12.50"} -> "12.50" (exact numeric
     * string; PHP has no decimal type), Long -> int, Integer -> int, Boolean -> bool, Bytes -> binary string,
     * Date/LocalDate -> ISO string, Json -> value. Plain values are returned as they are.
     */
    public function variable(string $name, mixed $default = null): mixed
    {
        if (!array_key_exists($name, $this->work->variables)) {
            return $default;
        }
        $v = TypedValue::unwrap($this->work->variables[$name]);
        return $v ?? $default;
    }

    /** The variable exactly as received (TypedValue wire form kept). */
    public function rawVariable(string $name): mixed
    {
        return $this->work->variables[$name] ?? null;
    }

    /** A Decimal/BigInteger/Long/Integer or plain JSON number as an exact numeric string, or null. */
    public function decimal(string $name): ?string
    {
        $v = $this->variable($name);
        if (is_int($v) || (is_string($v) && is_numeric($v))) {
            return (string) $v;
        }
        if (is_float($v)) {
            // plain JSON number with a fraction: keep the shortest exact-looking representation
            return rtrim(rtrim(sprintf('%.12F', $v), '0'), '.');
        }
        return null;
    }

    /** A Date / LocalDate (typed or ISO string) as DateTimeImmutable, or null. */
    public function dateTime(string $name): ?\DateTimeImmutable
    {
        return TypedValue::toDateTime($this->work->variables[$name] ?? null);
    }

    /** True once the server reported cancelRequested (VI.3.1 CANCEL_REQUESTED). */
    public function isCancelRequested(): bool
    {
        $this->dispatchSignals();
        return $this->cancelRequested;
    }

    /** True once the lease is gone (409 stale-lease, 410 gone, or expiry while unreachable); results are discarded. */
    public function isLeaseLost(): bool
    {
        $this->dispatchSignals();
        return $this->leaseLost;
    }

    public function isShuttingDown(): bool
    {
        $this->dispatchSignals();
        return $this->shuttingDown;
    }

    /**
     * Inline mode: extends the lease if lease/3 has passed (or always with $force) and refreshes the cancel flag.
     * Fork mode: applies the LockState updates the parent sent through the pipe (the parent does the heartbeats).
     * Throws nothing; check isLeaseLost() / throwIfCancelled() after.
     */
    public function heartbeat(bool $force = false): void
    {
        if ($this->heartbeat !== null) {
            ($this->heartbeat)($this, $force);
        }
        $this->dispatchSignals();
    }

    /** Cooperative checkpoint: throws TaskCancelledException on cancel request, lease loss or shutdown. */
    public function throwIfCancelled(): void
    {
        $this->heartbeat();
        if ($this->cancelRequested) {
            throw new TaskCancelledException('cancel requested by server');
        }
        if ($this->leaseLost) {
            throw new TaskCancelledException('lease lost');
        }
        if ($this->shuttingDown) {
            throw new TaskCancelledException('worker shutting down');
        }
    }

    /** Logger whose lines carry workerId, workId, topic, operationId, attemptId, lockVersion, businessKey. */
    public function log(): Log
    {
        return $this->log;
    }

    public function client(): SangamClient
    {
        return $this->client;
    }

    // ---- runtime-internal state updates ----------------------------------------------------------------

    /** @internal */
    public function markExtended(?float $expiresAt, ?int $lockVersion, bool $cancelRequested): void
    {
        $this->lastExtendedAt = microtime(true);
        if ($expiresAt !== null) {
            $this->lockExpiresAt = $expiresAt;
        }
        if ($lockVersion !== null) {
            $this->lockVersion = $lockVersion;
        }
        if ($cancelRequested) {
            $this->cancelRequested = true;
        }
    }

    /** @internal */
    public function lastExtendedAt(): float
    {
        return $this->lastExtendedAt;
    }

    /** @internal */
    public function markCancelRequested(): void
    {
        $this->cancelRequested = true;
    }

    /** @internal */
    public function markLeaseLost(): void
    {
        $this->leaseLost = true;
    }

    /** @internal */
    public function markShuttingDown(): void
    {
        $this->shuttingDown = true;
    }

    private function dispatchSignals(): void
    {
        if (function_exists('pcntl_signal_dispatch')) {
            pcntl_signal_dispatch();
        }
    }
}
