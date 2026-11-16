<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

use Sangam\Sdk\Log\Log;
use Sangam\Sdk\Model\ExternalWork;
use Sangam\Sdk\Model\TypedValue;
use Sangam\Sdk\Model\WorkOperation;
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
    private ?string $outcomeReference = null;
    private readonly Log $log;

    /** @param (\Closure(TaskContext, bool): void)|null $heartbeat inline-mode heartbeat (null in fork mode) */
    public function __construct(
        private readonly ExternalWork $work,
        private readonly string $workerId,
        private readonly SangamClient $client,
        Log $log,
        private readonly ?\Closure $heartbeat = null,
    ) {
        if ($work->id === null || $work->attemptId === null || $work->lockVersion === null) {
            // A-1: a handler runs only after the LOCKED confirmation (work id, lockVersion and attemptId)
            throw new \InvalidArgumentException('work item without its LOCKED confirmation (id, lockVersion, attemptId)');
        }
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

    /** W3C traceparent the engine stored for this task (ExternalWork.traceContext, M-062), or null. */
    public function traceparent(): ?string
    {
        return $this->work->traceContext?->traceparent;
    }

    /** W3C tracestate of the task's trace context, or null. */
    public function tracestate(): ?string
    {
        return $this->work->traceContext?->tracestate;
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

    /**
     * The server-recorded operation-ledger entry of operationId (SL-16): state, attempts, capabilityClass,
     * mayDispatch, storedOutcome. Null from servers that do not send it.
     */
    public function operation(): ?WorkOperation
    {
        return $this->work->operation;
    }

    /**
     * Downstream reference of this work's effect (for example a payment reference). Set it in the handler; it is sent
     * as outcome.reference on complete and recorded as the operation-ledger providerRef (SL-16).
     */
    public function setOutcomeReference(?string $reference): void
    {
        $this->outcomeReference = $reference;
    }

    public function outcomeReference(): ?string
    {
        return $this->outcomeReference;
    }

    /** @return array<string, mixed> the VariableMap as sent ([] when the server sent none) */
    public function variables(): array
    {
        return $this->work->variables ?? [];
    }

    /**
     * A variable with R33 TypedValue unwrapped: {"type":"Decimal","value":"12.50"} -> "12.50" (exact numeric
     * string; PHP has no decimal type), Long -> int, Integer -> int, Boolean -> bool, Bytes -> binary string,
     * Date/LocalDate -> ISO string, Json -> value. Plain values are returned as they are.
     */
    public function variable(string $name, mixed $default = null): mixed
    {
        $vars = $this->variables();
        if (!array_key_exists($name, $vars)) {
            return $default;
        }
        $v = TypedValue::unwrap($vars[$name]);
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

    /**
     * Runs one side-effecting step of this work at most once per recorded outcome (M-025, R37 / E02), journalled under
     * the work's operationId on GET/PUT /work/{id}/step?stepName= and fenced by this lease.
     *
     * $fn receives the Step: send Step::idempotencyKey() (operationId . ':' . name) to the remote API as its
     * idempotency key and report the provider's reference with Step::setProviderRef(). Then:
     * - $fn returns: the step is recorded SUCCEEDED with the returned value; a later attempt replays that value
     *   (R33 typed values unwrapped like variable()) without calling $fn again;
     * - $fn throws StepNoEffectException: the call certainly had no effect; the step is recorded FAILED and may run
     *   again on a later attempt; the exception propagates;
     * - $fn throws anything else: the outcome is uncertain; the step stays INTENT and the exception propagates.
     *
     * NOT exactly-once for remote APIs: a crash (or lost lease) between the remote call and the SUCCEEDED record
     * leaves INTENT (the crash window of R37). A later attempt then asks the lookup of $options, runs again only
     * for an idempotent call, or throws OutcomeUnknownException (the default, capability class (c)), so the worker
     * reports outcome-unknown and reconciliation decides; it never re-issues the call blindly.
     *
     * @template T
     * @param string $name 1-128 printable ASCII characters, unique within the work's operation
     * @param callable(Step): T $fn
     * @return T|mixed the value of $fn, or the recorded value on replay
     * @throws \InvalidArgumentException a bad step name
     * @throws OutcomeUnknownException   INTENT from an earlier attempt, neither looked up nor idempotent
     * @throws StepNoEffectException     from $fn, after the FAILED record
     * @throws TaskCancelledException    the lease was lost (409 STALE_LEASE / 410 from the journal)
     */
    public function step(string $name, callable $fn, ?StepOptions $options = null): mixed
    {
        return DurableSteps::run($this, $name, $fn, $options);
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
