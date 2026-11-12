<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Http;

use Sangam\Sdk\Error\TransportException;

/** Default HttpSender on ext-curl (no redirects, per-call timeouts, abortable long polls). */
final class CurlSender implements HttpSender
{
    public function __construct()
    {
        if (!extension_loaded('curl')) {
            throw new \RuntimeException('the Sangam SDK needs ext-curl (or an injected HttpSender)');
        }
    }

    public function send(string $method, string $url, array $headers, ?string $payload, int $timeoutMs,
                         int $connectTimeoutMs, ?\Closure $abort): array
    {
        $lines = ['Expect:'];
        foreach ($headers as $k => $v) {
            $lines[] = $k . ': ' . $v;
        }
        if ($payload === '' && !self::hasHeader($headers, 'Content-Type')) {
            // mutation without a body (for example claim/unclaim): suppress curl's default form Content-Type
            $lines[] = 'Content-Type:';
        }
        $responseHeaders = [];
        $ch = curl_init($url);
        $opts = [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HTTPHEADER => $lines,
            CURLOPT_TIMEOUT_MS => $timeoutMs,
            CURLOPT_CONNECTTIMEOUT_MS => $connectTimeoutMs,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_HEADERFUNCTION => static function ($ch, string $line) use (&$responseHeaders): int {
                if (str_starts_with($line, 'HTTP/')) {
                    $responseHeaders = []; // new status line (for example after 100 Continue)
                } else {
                    $parts = explode(':', $line, 2);
                    if (count($parts) === 2) {
                        $responseHeaders[strtolower(trim($parts[0]))] = trim($parts[1]);
                    }
                }
                return strlen($line);
            },
        ];
        if ($payload !== null) {
            $opts[CURLOPT_POSTFIELDS] = $payload;
        }
        if ($abort !== null) {
            $opts[CURLOPT_NOPROGRESS] = false;
            $opts[CURLOPT_XFERINFOFUNCTION] = static fn($ch, int $dt, int $dn, int $ut, int $un): int => $abort() ? 1 : 0;
        }
        curl_setopt_array($ch, $opts);
        $raw = curl_exec($ch);
        if ($raw === false) {
            $errno = curl_errno($ch);
            $err = curl_error($ch);
            curl_close($ch);
            throw new TransportException($err, $errno); // HttpTransport prefixes method and API path
        }
        $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        return [$status, $responseHeaders, (string) $raw];
    }

    /** @param array<string, string> $headers */
    private static function hasHeader(array $headers, string $name): bool
    {
        foreach ($headers as $k => $_) {
            if (strcasecmp((string) $k, $name) === 0) {
                return true;
            }
        }
        return false;
    }
}
