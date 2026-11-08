<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * Exponential back-off with jitter: min(max, initial * factor^(n-1)) +/- jitter fraction. Used for fetch errors,
 * result-report retries and the retryTimeoutMs sent with fail (R28 default R3/PT1M with jitter).
 */
final class Backoff
{
    public function __construct(
        public readonly int $initialMs,
        public readonly int $maxMs,
        public readonly float $factor = 2.0,
        public readonly float $jitter = 0.2,
    ) {
    }

    public static function fetchDefaults(): self
    {
        return new self(500, 60000);
    }

    /** R28 default: first retry after about one minute, growing, capped at 10 minutes. */
    public static function retryDefaults(): self
    {
        return new self(60000, 600000);
    }

    public static function reportDefaults(): self
    {
        return new self(500, 15000);
    }

    /** Delay in ms for the $n-th consecutive failure (1-based). */
    public function delayMs(int $n): int
    {
        $capped = min($this->initialMs * ($this->factor ** max(0, $n - 1)), (float) $this->maxMs);
        $j = $capped * $this->jitter;
        $v = $capped + ($j > 0 ? (mt_rand() / mt_getrandmax() * 2 - 1) * $j : 0.0);
        return max(0, (int) $v);
    }
}
