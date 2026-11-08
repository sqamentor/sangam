<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Log;

/**
 * Structured logging hook. Every SDK line is a stable $event name (for example "work.completed") plus key/value
 * $fields (workerId, workId, topic, operationId, attemptId, lockVersion, businessKey, ...). Implement it to send
 * lines to Monolog, JSON logs or metrics; PsrLoggerAdapter wraps any PSR-3 logger without a dependency.
 */
interface SangamLogger
{
    /** @param array<string, mixed> $fields */
    public function log(Level $level, string $event, string $message, array $fields = [], ?\Throwable $error = null): void;
}
