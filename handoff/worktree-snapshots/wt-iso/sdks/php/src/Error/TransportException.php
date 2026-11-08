<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Error;

/** Network failure (connect, reset, timeout) after the configured retries were used up. */
final class TransportException extends SangamException
{
    public function __construct(string $message, public readonly int $curlErrno = 0, ?\Throwable $previous = null)
    {
        parent::__construct($message, 0, $previous);
    }

    /** True when the call was aborted on purpose (worker shutdown). */
    public function isAborted(): bool
    {
        return $this->curlErrno === 42; // CURLE_ABORTED_BY_CALLBACK
    }
}
