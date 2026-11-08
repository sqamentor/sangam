<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Http;

/**
 * Exponential back-off with jitter for transport retries (R28). Only safe calls are retried: GET, or a mutation
 * that carries an Idempotency-Key (R41) - and the transport gives every mutation one.
 */
final class RetryPolicy
{
    public function __construct(
        public readonly int $maxAttempts = 4,
        public readonly int $initialDelayMs = 200,
        public readonly int $maxDelayMs = 5000,
        public readonly float $multiplier = 2.0,
    ) {
        if ($maxAttempts < 1) {
            throw new \InvalidArgumentException('maxAttempts must be >= 1');
        }
        if ($multiplier < 1.0) {
            throw new \InvalidArgumentException('multiplier must be >= 1');
        }
    }

    public static function none(): self
    {
        return new self(1, 0, 0, 1.0);
    }

    /** Delay in ms before retry number $retry (1-based), jittered in [cap/2, cap]. */
    public function delayMs(int $retry): int
    {
        $cap = (int) min($this->initialDelayMs * ($this->multiplier ** max(0, $retry - 1)), $this->maxDelayMs);
        if ($cap <= 0) {
            return 0;
        }
        return random_int(intdiv($cap, 2), $cap);
    }
}
