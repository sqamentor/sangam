<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * The handler cannot know whether the downstream effect happened (for example a payment call timed out after the
 * request was sent). The worker reports outcome-unknown; the engine opens an UNKNOWN_OUTCOME reconciliation item
 * and never repeats the effect automatically (VI.3.2 rules 4-5, AC-03).
 */
class OutcomeUnknownException extends \Exception
{
    /** @param array<string, mixed>|null $variables evidence for the reconciliation item */
    public function __construct(
        string $reason,
        public readonly ?string $details = null,
        public readonly ?array $variables = null,
        ?\Throwable $previous = null,
    ) {
        parent::__construct($reason, 0, $previous);
    }
}
