<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Http;

use Sangam\Sdk\Error\TransportException;

/**
 * The wire: sends one fully prepared HTTP request and returns the raw answer. HttpTransport keeps everything else
 * (base URL, auth with one refresh on 401, Idempotency-Key, retries, problem+json decoding), so an injected sender
 * (a proxy-aware client, a test fake) changes only how bytes travel. The default is CurlSender (ext-curl).
 */
interface HttpSender
{
    /**
     * @param array<string, string> $headers request headers (name => value); Content-Type is set when $payload is
     *                                       a body, and absent for a mutation without a body ($payload === '')
     * @param string|null $payload null for GET; '' for a mutation without a body
     * @param (\Closure(): bool)|null $abort returns true to abort the transfer (worker shutdown)
     * @return array{0: int, 1: array<string, string>, 2: string} status, response headers with lower-cased names,
     *                                                             raw body
     * @throws TransportException on a network failure; curlErrno 42 (isAborted()) when $abort stopped the call
     */
    public function send(string $method, string $url, array $headers, ?string $payload, int $timeoutMs,
                         int $connectTimeoutMs, ?\Closure $abort): array;
}
