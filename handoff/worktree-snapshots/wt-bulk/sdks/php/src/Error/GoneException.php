<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Error;

/** 410 GONE_INSTANCE_DELETED (VI.5, R23): the instance was deleted; a tombstone matched. The item is abandoned. */
final class GoneException extends SangamApiException
{
    public function __construct(Problem $problem, string $message)
    {
        parent::__construct(410, $problem, $message);
    }

    public function isRetryable(): bool
    {
        return false;
    }
}
