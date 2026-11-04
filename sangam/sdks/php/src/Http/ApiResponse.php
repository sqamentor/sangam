<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Http;

/** Decoded response: status, lower-cased headers, decoded JSON body (or null) and the raw body. */
final class ApiResponse
{
    /** @param array<string, string> $headers */
    public function __construct(
        public readonly int $status,
        public readonly array $headers,
        public readonly mixed $body,
        public readonly string $raw,
    ) {
    }

    public function header(string $name): ?string
    {
        return $this->headers[strtolower($name)] ?? null;
    }

    /** True when the server answered from its idempotency store (Idempotent-Replayed: true, R41). */
    public function replayed(): bool
    {
        return strtolower((string) $this->header('Idempotent-Replayed')) === 'true';
    }
}
