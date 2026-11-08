<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * A technical failure where the handler asserts that no external effect happened, so a retry is safe.
 * $retries / $retryTimeoutMs override the subscription defaults when not null; retries 0 raises an incident.
 */
class TaskFailedException extends \Exception
{
    public function __construct(
        string $message,
        public readonly ?string $details = null,
        public readonly ?int $retries = null,
        public readonly ?int $retryTimeoutMs = null,
        ?\Throwable $previous = null,
    ) {
        parent::__construct($message, 0, $previous);
    }
}
