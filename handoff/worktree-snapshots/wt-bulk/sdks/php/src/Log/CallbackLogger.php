<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Log;

/** Adapts a closure fn(Level, string $event, string $message, array $fields, ?Throwable): void. */
final class CallbackLogger implements SangamLogger
{
    private readonly \Closure $callback;

    public function __construct(callable $callback)
    {
        $this->callback = \Closure::fromCallable($callback);
    }

    public function log(Level $level, string $event, string $message, array $fields = [], ?\Throwable $error = null): void
    {
        ($this->callback)($level, $event, $message, $fields, $error);
    }
}
