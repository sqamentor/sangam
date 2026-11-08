<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Log;

/** Convenience wrapper with PSR-3-style method names (debug/info/warning/error) over a SangamLogger. */
final class Log
{
    /** @param array<string, mixed> $base fields merged into every line */
    public function __construct(private readonly SangamLogger $logger, private readonly array $base = [])
    {
    }

    /** @param array<string, mixed> $fields */
    public function with(array $fields): self
    {
        return new self($this->logger, array_merge($this->base, $fields));
    }

    public function sink(): SangamLogger
    {
        return $this->logger;
    }

    /** @param array<string, mixed> $fields */
    public function debug(string $event, string $message, array $fields = []): void
    {
        $this->logger->log(Level::Debug, $event, $message, array_merge($this->base, $fields));
    }

    /** @param array<string, mixed> $fields */
    public function info(string $event, string $message, array $fields = []): void
    {
        $this->logger->log(Level::Info, $event, $message, array_merge($this->base, $fields));
    }

    /** @param array<string, mixed> $fields */
    public function warning(string $event, string $message, array $fields = [], ?\Throwable $error = null): void
    {
        $this->logger->log(Level::Warning, $event, $message, array_merge($this->base, $fields), $error);
    }

    /** @param array<string, mixed> $fields */
    public function error(string $event, string $message, array $fields = [], ?\Throwable $error = null): void
    {
        $this->logger->log(Level::Error, $event, $message, array_merge($this->base, $fields), $error);
    }
}
