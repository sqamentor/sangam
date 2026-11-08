<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Error;

/** A non-2xx answer from the Sangam API, with the parsed problem+json body (a synthetic one when absent). */
class SangamApiException extends SangamException
{
    public function __construct(
        public readonly int $status,
        public readonly Problem $problem,
        string $message,
    ) {
        parent::__construct($message, $status);
    }

    /** 408, 425, 429, 500, 502, 503, 504: the same request (same Idempotency-Key) may be repeated. */
    public function isRetryable(): bool
    {
        return in_array($this->status, [408, 425, 429, 500, 502, 503, 504], true);
    }
}
