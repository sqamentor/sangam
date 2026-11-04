<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Log;

/** Discards everything. */
final class NullLogger implements SangamLogger
{
    public function log(Level $level, string $event, string $message, array $fields = [], ?\Throwable $error = null): void
    {
    }
}
