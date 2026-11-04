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
 * $effect is not part of sangam-api.yaml 0.1.0; it is parsed when present but ignored - irreversibility comes from
 * TopicSubscription::$irreversible. $priority is int64 (PHP int).
 */
final class ExternalWork
{
    /** @param array<string, mixed> $variables */
    public function __construct(
        public readonly ?string $id,
        public readonly ?string $topic,
        public readonly ?string $processInstanceId,
        public readonly ?string $activityInstanceId,
        public readonly ?string $activityId,
        public readonly ?string $processDefinitionKey,
        public readonly ?string $businessKey,
        public readonly ?string $tenantId,
        public readonly array $variables,
        public readonly int $lockVersion,
        public readonly ?string $attemptId,
        public readonly ?string $operationId,
        public readonly ?string $lockExpiresAt,
        public readonly ?int $retries,
        public readonly ?string $effect,
        public readonly ?int $priority,
        public readonly ?string $errorMessage = null,
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
            isset($d['variables']) && is_array($d['variables']) ? $d['variables'] : [],
            $i('lockVersion') ?? 0,
            $s('attemptId'),
            $s('operationId'),
            $s('lockExpiresAt'),
            $i('retries'),
            $s('effect'),
            $i('priority'),
            $s('errorMessage'),
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
