<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Model;

/** Heartbeat answer (LockState): cancelRequested tells the worker to stop and acknowledge (VI.3.2 rule 3). */
final class LockState
{
    public function __construct(
        public readonly ?string $id,
        public readonly ?int $lockVersion,
        public readonly ?string $lockExpiresAt,
        public readonly bool $cancelRequested,
    ) {
    }

    public static function fromArray(mixed $d): self
    {
        if (!is_array($d)) {
            return new self(null, null, null, false);
        }
        return new self(
            isset($d['id']) && is_scalar($d['id']) ? (string) $d['id'] : null,
            isset($d['lockVersion']) && is_numeric($d['lockVersion']) ? (int) $d['lockVersion'] : null,
            isset($d['lockExpiresAt']) && is_string($d['lockExpiresAt']) ? $d['lockExpiresAt'] : null,
            (bool) ($d['cancelRequested'] ?? false),
        );
    }
}
