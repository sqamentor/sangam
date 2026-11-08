<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Http;

/** One logical API call; build with ApiRequest::get('/tasks') etc. */
final class ApiRequest
{
    /** @var array<string, mixed> */
    public array $query = [];
    /** @var array<string, string> */
    public array $headers = [];
    public mixed $body = null;
    public ?string $rawBody = null;
    public ?string $rawContentType = null;
    public ?string $idempotencyKey = null;
    public ?int $timeoutMs = null;
    public bool $retry = true;
    /** @var (\Closure(): bool)|null returns true to abort the transfer (worker shutdown) */
    public ?\Closure $abort = null;

    private function __construct(public readonly string $method, public readonly string $path)
    {
    }

    public static function get(string $path): self
    {
        return new self('GET', $path);
    }

    public static function post(string $path): self
    {
        return new self('POST', $path);
    }

    public static function put(string $path): self
    {
        return new self('PUT', $path);
    }

    public static function patch(string $path): self
    {
        return new self('PATCH', $path);
    }

    public static function delete(string $path): self
    {
        return new self('DELETE', $path);
    }

    public function query(string $name, mixed $value): self
    {
        if ($value !== null) {
            $this->query[$name] = $value;
        }
        return $this;
    }

    /** @param array<string, mixed>|null $values */
    public function queryAll(?array $values): self
    {
        foreach ($values ?? [] as $k => $v) {
            $this->query((string) $k, $v);
        }
        return $this;
    }

    public function header(string $name, ?string $value): self
    {
        if ($value !== null) {
            $this->headers[$name] = $value;
        }
        return $this;
    }

    public function body(mixed $body): self
    {
        $this->body = $body;
        return $this;
    }

    public function rawBody(string $body, string $contentType): self
    {
        $this->rawBody = $body;
        $this->rawContentType = $contentType;
        return $this;
    }

    /** Idempotency-Key header (R41); null on a mutation = one generated per logical call. */
    public function idempotencyKey(?string $key): self
    {
        $this->idempotencyKey = $key;
        return $this;
    }

    public function timeoutMs(?int $ms): self
    {
        $this->timeoutMs = $ms;
        return $this;
    }

    /** Disable transport-level retries (long-poll fetch has its own loop). */
    public function noRetry(): self
    {
        $this->retry = false;
        return $this;
    }

    public function abortWhen(?\Closure $abort): self
    {
        $this->abort = $abort;
        return $this;
    }

    public function isMutation(): bool
    {
        return $this->method !== 'GET' && $this->method !== 'HEAD';
    }
}
