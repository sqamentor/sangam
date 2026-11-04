<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Error;

/**
 * 409 stale-lease (VI.3.2 rule 2, R20): our lockVersion is no longer current and the server changed nothing.
 * The worker abandons the item; another attempt owns it.
 */
final class StaleLeaseException extends SangamApiException
{
    public function __construct(Problem $problem, string $message)
    {
        parent::__construct(409, $problem, $message);
    }

    public function isRetryable(): bool
    {
        return false;
    }
}
