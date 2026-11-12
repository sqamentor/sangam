<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Model;

/**
 * A locked unit of external work (ARCHITECTURE section 4, VI.3). The handler runs only after this LOCKED
 * confirmation with lockVersion and attemptId (A-1). operationId is stable across retries and lease expiry (R36)
 * and is the idempotency key for downstream calls; attemptId changes per lease (A-2).
 *
 * $variables is a VariableMap: entries may be R33 TypedValue arrays ({"type":"Decimal","value":"12.50"}); use
 * TaskContext::variable() to read them unwrapped. $errorMessage is the previous attempt's failure message.
 * $priority is int64 (PHP int). The properties are exactly sangam-api.yaml ExternalWork; none is required on the
 * wire, so every one is nullable and an absent member stays null (no defaults). The worker starts an item only when
 * id, lockVersion and attemptId are present (A-1). The server sends no per-item
 * effect flag (WorkPort.ExternalWork); irreversibility comes from TopicSubscription::$irreversible.
 */
final class ExternalWork
{
    /** @param array<string, mixed>|null $variables */
    public function __construct(
        public readonly ?string $id,
        public readonly ?string $topic,
        public readonly ?string $processInstanceId,
        public readonly ?string $activityInstanceId,
        public readonly ?string $activityId,
        public readonly ?string $processDefinitionKey,
        public readonly ?string $businessKey,
        public readonly ?string $tenantId,
        public readonly ?array $variables,
        public readonly ?int $lockVersion,
        public readonly ?string $attemptId,
        public readonly ?string $operationId,
        public readonly ?string $lockExpiresAt,
        public readonly ?int $retries,
        public readonly ?int $priority,
        public readonly ?string $errorMessage = null,
        /**
         * SL-16 WorkOperation: server-recorded ledger entry of operationId. mayDispatch false with state SUCCEEDED:
         * complete from storedOutcome without calling the downstream again. Null from older servers.
         */
        public readonly ?WorkOperation $operation = null,
        /** M-062: W3C trace context the engine stored for the task; null when none was stored. */
        public readonly ?TraceContext $traceContext = null,
    ) {
    }

    /** @param array<string, mixed> $d */
    public static function fromArray(array $d): self
    {
        $s = static fn(string $k): ?string => isset($d[$k]) && is_scalar($d[$k]) ? (string) $d[$k] : null;
        $i = static fn(string $k): ?int => isset($d[$k]) && is_numeric($d[$k]) ? (int) $d[$k] : null;
        return new self(
            $s('id'),
            $s('topic'),
            $s('processInstanceId'),
            $s('activityInstanceId'),
            $s('activityId'),
            $s('processDefinitionKey'),
            $s('businessKey'),
            $s('tenantId'),
            isset($d['variables']) && is_array($d['variables']) ? $d['variables'] : null,
            $i('lockVersion'),
            $s('attemptId'),
            $s('operationId'),
            $s('lockExpiresAt'),
            $i('retries'),
            $i('priority'),
            $s('errorMessage'),
            isset($d['operation']) && is_array($d['operation']) ? WorkOperation::fromArray($d['operation']) : null,
            isset($d['traceContext']) && is_array($d['traceContext']) ? TraceContext::fromArray($d['traceContext']) : null,
        );
    }

    /** lockExpiresAt as a Unix timestamp (float seconds), or null. */
    public function lockExpiresAtTs(): ?float
    {
        return Time::parse($this->lockExpiresAt);
    }

    /** R36 operationId, falling back to the work id when the server sent none. */
    public function operationIdOrId(): string
    {
        return $this->operationId ?? (string) $this->id;
    }
}
