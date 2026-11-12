<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * What a saga's forward action leaves for its compensation (M-026, R36): the forward work's stable operationId, its
 * provider reference and its work id. Stored as the process variable Saga::recordVariable($step) when the forward
 * action completes.
 */
final class SagaRecord
{
    public function __construct(
        public readonly ?string $step,
        public readonly string $operationId,
        public readonly ?string $providerRef,
        public readonly ?string $workId,
    ) {
    }

    /** @return array{step: ?string, operationId: string, providerRef: ?string, workId: ?string} */
    public function toArray(): array
    {
        return ['step' => $this->step, 'operationId' => $this->operationId, 'providerRef' => $this->providerRef,
            'workId' => $this->workId];
    }

    /**
     * The record of a decoded variable value, or null when it carries no operationId.
     *
     * @param array<array-key, mixed> $m
     */
    public static function fromArray(array $m): ?self
    {
        $op = self::str($m['operationId'] ?? null);
        if ($op === null) {
            return null;
        }
        return new self(self::str($m['step'] ?? null), $op, self::str($m['providerRef'] ?? null),
            self::str($m['workId'] ?? null));
    }

    private static function str(mixed $v): ?string
    {
        return is_scalar($v) ? (string) $v : null;
    }
}
