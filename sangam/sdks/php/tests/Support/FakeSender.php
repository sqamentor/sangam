<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests\Support;

use Sangam\Sdk\Http\HttpSender;

/**
 * In-process fake of the Sangam API on the HttpSender seam: records every request, answers fetch-and-lock from a
 * queue (then []) and every other call from scripted replies (then 204). HttpTransport (auth, Idempotency-Key,
 * retries, problem+json mapping) runs unchanged on top of it.
 */
final class FakeSender implements HttpSender
{
    /** @var list<array{method: string, path: string, action: string, headers: array<string, string>, body: mixed}> */
    public array $calls = [];
    /** @var (\Closure(array{method: string, path: string, action: string, headers: array<string, string>, body: mixed}): void)|null */
    public ?\Closure $onCall = null;
    /** @var array<string, list<array{0: int, 1: array<string, mixed>|null}>> */
    private array $replies = [];
    /** @var list<string> */
    private array $batches = [];
    private int $fetches = 0;

    /** Scripted answers for POST /work/{id}/{action} (or any last path segment), used in order. */
    public function reply(string $action, int $status, ?array $body = null): self
    {
        $this->replies[$action][] = [$status, $body];
        return $this;
    }

    /** The next fetch-and-lock answers with these work items (wire arrays). */
    public function enqueue(array ...$items): self
    {
        $this->batches[] = (string) json_encode(array_values($items), JSON_UNESCAPED_SLASHES);
        return $this;
    }

    /** @return list<array{method: string, path: string, action: string, headers: array<string, string>, body: mixed}> */
    public function callsOf(string $action): array
    {
        return array_values(array_filter($this->calls, static fn(array $c): bool => $c['action'] === $action));
    }

    public function send(string $method, string $url, array $headers, ?string $payload, int $timeoutMs,
                         int $connectTimeoutMs, ?\Closure $abort): array
    {
        $path = (string) parse_url($url, PHP_URL_PATH);
        if (str_starts_with($path, '/api/v1')) {
            $path = substr($path, strlen('/api/v1'));
        }
        $call = [
            'method' => $method,
            'path' => $path,
            'action' => substr($path, (int) strrpos($path, '/') + 1),
            'headers' => $headers,
            'body' => $payload === null || $payload === '' ? null : json_decode($payload, true),
        ];
        $this->calls[] = $call;
        if ($this->onCall !== null) {
            ($this->onCall)($call);
        }
        if ($call['action'] === 'fetch-and-lock') {
            if (++$this->fetches > 100) {
                throw new \LogicException('runaway worker loop: more than 100 fetches');
            }
            $batch = array_shift($this->batches) ?? '[]';
            return [200, ['content-type' => 'application/json'], $batch];
        }
        $queued = $this->replies[$call['action']] ?? [];
        if ($queued === []) {
            return [204, [], ''];
        }
        [$status, $body] = array_shift($this->replies[$call['action']]);
        $type = $status >= 400 ? 'application/problem+json' : 'application/json';
        return [$status, ['content-type' => $type], $body === null ? '' : (string) json_encode($body)];
    }
}
