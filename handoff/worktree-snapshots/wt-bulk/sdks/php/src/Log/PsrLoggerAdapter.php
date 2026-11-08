<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Log;

/**
 * Forwards to any PSR-3 logger (Monolog, Laravel, Symfony) by duck typing, so the SDK needs no psr/log
 * dependency. The event name goes into the context as "event"; the exception as "exception" (PSR-3 rule).
 */
final class PsrLoggerAdapter implements SangamLogger
{
    public function __construct(private readonly object $psrLogger)
    {
        if (!method_exists($psrLogger, 'log')) {
            throw new \InvalidArgumentException('object has no PSR-3 log() method');
        }
    }

    public function log(Level $level, string $event, string $message, array $fields = [], ?\Throwable $error = null): void
    {
        $context = ['event' => $event] + $fields;
        if ($error !== null) {
            $context['exception'] = $error;
        }
        $this->psrLogger->log($level->value, $message, $context);
    }
}
