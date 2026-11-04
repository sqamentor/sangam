<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Http;

use Sangam\Sdk\Auth\NoAuth;
use Sangam\Sdk\Auth\TokenProvider;
use Sangam\Sdk\Error\GoneException;
use Sangam\Sdk\Error\Problem;
use Sangam\Sdk\Error\SangamApiException;
use Sangam\Sdk\Error\StaleLeaseException;
use Sangam\Sdk\Error\TransportException;
use Sangam\Sdk\Log\Log;

/**
 * The single HTTP path of the SDK (ext-curl): base URL + /api/v1, Authorization with one refresh on 401,
 * Idempotency-Key on every mutation (R41), RFC 9457 problem+json decoding, jittered retries of safe calls (R28).
 */
final class HttpTransport
{
    public const API_PREFIX = '/api/v1';
    public const SDK_VERSION = '0.1.0';

    private readonly string $base;
    private readonly TokenProvider $tokens;
    private readonly RetryPolicy $retry;
    private readonly string $userAgent;
    /** @var array<string, string> */
    private array $defaultHeaders = [];
    private ?ApiResponse $last = null;

    public function __construct(
        string $baseUrl,
        ?TokenProvider $tokens,
        ?RetryPolicy $retry,
        private readonly int $requestTimeoutMs,
        private readonly int $connectTimeoutMs,
        private readonly Log $log,
        ?string $userAgent = null,
        ?string $seat = null,
        ?string $actingForSeat = null,
    ) {
        $b = rtrim($baseUrl, '/');
        if (str_ends_with($b, self::API_PREFIX)) {
            $b = substr($b, 0, -strlen(self::API_PREFIX));
        }
        $this->base = $b;
        $this->tokens = $tokens ?? new NoAuth();
        $this->retry = $retry ?? new RetryPolicy();
        $this->userAgent = $userAgent ?? 'sangam-sdk-php/' . self::SDK_VERSION;
        $this->setSeat($seat, $actingForSeat);
    }

    /**
     * Client-level seat context: X-Sangam-Seat (IX.5 rule 1) and X-Sangam-Acting-For-Seat (IX.5 rule 4),
     * sent on every call unless the request sets its own. null clears a header.
     */
    public function setSeat(?string $seat, ?string $actingForSeat = null): void
    {
        unset($this->defaultHeaders['X-Sangam-Seat'], $this->defaultHeaders['X-Sangam-Acting-For-Seat']);
        if ($seat !== null) {
            $this->defaultHeaders['X-Sangam-Seat'] = $seat;
        }
        if ($actingForSeat !== null) {
            $this->defaultHeaders['X-Sangam-Acting-For-Seat'] = $actingForSeat;
        }
    }

    /** The last successful response of this transport (status, headers incl. Idempotent-Replayed). */
    public function lastResponse(): ?ApiResponse
    {
        return $this->last;
    }

    public function log(): Log
    {
        return $this->log;
    }

    public function execute(ApiRequest $req): ApiResponse
    {
        $idemKey = $req->idempotencyKey;
        if ($idemKey === null && $req->isMutation()) {
            $idemKey = self::uuid4();
        }
        $safe = !$req->isMutation() || $idemKey !== null;
        $maxAttempts = ($req->retry && $safe) ? $this->retry->maxAttempts : 1;
        $refreshedAuth = false;
        $attempt = 0;
        while (true) {
            $attempt++;
            try {
                [$status, $headers, $raw] = $this->sendOnce($req, $idemKey);
            } catch (TransportException $e) {
                if ($e->isAborted() || $attempt >= $maxAttempts) {
                    throw $e;
                }
                $d = $this->retry->delayMs($attempt);
                $this->log->debug('http.retry', 'retrying after transport error: ' . $e->getMessage(),
                    ['method' => $req->method, 'path' => $req->path, 'attempt' => $attempt, 'delayMs' => $d]);
                usleep($d * 1000);
                continue;
            }
            if ($status === 401 && !$refreshedAuth) {
                $refreshedAuth = true;
                $this->tokens->invalidate();
                $attempt--; // an auth refresh does not consume a retry
                continue;
            }
            if (intdiv($status, 100) === 2) {
                $body = null;
                $ct = $headers['content-type'] ?? '';
                if ($raw !== '' && ($ct === '' || str_contains($ct, 'json'))) {
                    $body = Json::decode($raw);
                }
                return $this->last = new ApiResponse($status, $headers, $body, $raw);
            }
            $ex = $this->toException($req, $status, $raw);
            if ($ex->isRetryable() && $attempt < $maxAttempts) {
                $d = self::retryAfterMs($headers) ?? $this->retry->delayMs($attempt);
                $this->log->debug('http.retry', 'retrying after HTTP ' . $status,
                    ['method' => $req->method, 'path' => $req->path, 'attempt' => $attempt, 'delayMs' => $d]);
                usleep($d * 1000);
                continue;
            }
            throw $ex;
        }
    }

    /** @return array{0:int,1:array<string,string>,2:string} */
    private function sendOnce(ApiRequest $req, ?string $idemKey): array
    {
        $lines = [
            'Accept: application/json, application/problem+json',
            'User-Agent: ' . $this->userAgent,
            'Expect:',
        ];
        $auth = $this->tokens->authorization();
        if ($auth !== null) {
            $lines[] = 'Authorization: ' . $auth;
        }
        if ($idemKey !== null) {
            $lines[] = 'Idempotency-Key: ' . $idemKey;
        }
        foreach (array_merge($this->defaultHeaders, $req->headers) as $k => $v) {
            $lines[] = $k . ': ' . $v;
        }
        $payload = null;
        if ($req->rawBody !== null) {
            $lines[] = 'Content-Type: ' . $req->rawContentType;
            $payload = $req->rawBody;
        } elseif ($req->body !== null) {
            $lines[] = 'Content-Type: application/json';
            $payload = Json::encode($req->body);
        } elseif ($req->isMutation()) {
            // no body (for example claim/unclaim): Content-Length 0, and suppress curl's default form Content-Type
            $lines[] = 'Content-Type:';
            $payload = '';
        }

        $headers = [];
        $ch = curl_init($this->url($req));
        $opts = [
            CURLOPT_CUSTOMREQUEST => $req->method,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HTTPHEADER => $lines,
            CURLOPT_TIMEOUT_MS => $req->timeoutMs ?? $this->requestTimeoutMs,
            CURLOPT_CONNECTTIMEOUT_MS => $this->connectTimeoutMs,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_HEADERFUNCTION => static function ($ch, string $line) use (&$headers): int {
                if (str_starts_with($line, 'HTTP/')) {
                    $headers = []; // new status line (for example after 100 Continue)
                } else {
                    $parts = explode(':', $line, 2);
                    if (count($parts) === 2) {
                        $headers[strtolower(trim($parts[0]))] = trim($parts[1]);
                    }
                }
                return strlen($line);
            },
        ];
        if ($payload !== null) {
            $opts[CURLOPT_POSTFIELDS] = $payload;
        }
        if ($req->abort !== null) {
            $abort = $req->abort;
            $opts[CURLOPT_NOPROGRESS] = false;
            $opts[CURLOPT_XFERINFOFUNCTION] = static fn($ch, int $dt, int $dn, int $ut, int $un): int => $abort() ? 1 : 0;
        }
        curl_setopt_array($ch, $opts);
        $raw = curl_exec($ch);
        if ($raw === false) {
            $errno = curl_errno($ch);
            $err = curl_error($ch);
            curl_close($ch);
            throw new TransportException($req->method . ' ' . $req->path . ' failed: ' . $err, $errno);
        }
        $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        return [$status, $headers, (string) $raw];
    }

    private function url(ApiRequest $req): string
    {
        $url = $this->base . self::API_PREFIX . (str_starts_with($req->path, '/') ? '' : '/') . $req->path;
        $parts = [];
        foreach ($req->query as $k => $v) {
            foreach (is_array($v) ? $v : [$v] as $item) {
                if (is_bool($item)) {
                    $item = $item ? 'true' : 'false';
                }
                $parts[] = rawurlencode((string) $k) . '=' . rawurlencode((string) $item);
            }
        }
        return $parts === [] ? $url : $url . '?' . implode('&', $parts);
    }

    /** Percent-encodes one path segment (ids, keys, channel names). */
    public static function seg(string $s): string
    {
        return rawurlencode($s);
    }

    public static function uuid4(): string
    {
        $b = random_bytes(16);
        $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
        $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);
        $h = bin2hex($b);
        return substr($h, 0, 8) . '-' . substr($h, 8, 4) . '-' . substr($h, 12, 4) . '-'
            . substr($h, 16, 4) . '-' . substr($h, 20, 12);
    }

    private function toException(ApiRequest $req, int $status, string $raw): SangamApiException
    {
        $data = null;
        if ($raw !== '') {
            $d = json_decode($raw, true);
            $data = is_array($d) && !array_is_list($d) ? $d : null;
        }
        $p = $data !== null
            ? Problem::fromArray($data, $status)
            : new Problem(title: 'HTTP ' . $status, status: $status, detail: $raw === '' ? null : $raw);
        $msg = $req->method . ' ' . $req->path . ' -> ' . $status
            . ($p->title !== null ? ' ' . $p->title : '') . ($p->detail !== null ? ': ' . $p->detail : '');
        if ($status === 409 && $p->is('stale-lease')) {
            return new StaleLeaseException($p, $msg);
        }
        if ($status === 410) {
            return new GoneException($p, $msg);
        }
        return new SangamApiException($status, $p, $msg);
    }

    /** @param array<string, string> $headers */
    private static function retryAfterMs(array $headers): ?int
    {
        $v = $headers['retry-after'] ?? null;
        if ($v === null || !ctype_digit(trim($v))) {
            return null;
        }
        return min(60, (int) trim($v)) * 1000;
    }
}
