<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Http\HttpTransport;
use Sangam\Sdk\Log\Log;
use Sangam\Sdk\Log\NullLogger;
use Sangam\Sdk\Tests\Support\FakeSender;

/** M-062: W3C trace context propagation of the transport (off by default, never fails a call). */
final class TraceContextTest extends TestCase
{
    private const TP = '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01';

    private static function transport(?\Closure $trace): HttpTransport
    {
        return new HttpTransport('http://127.0.0.1:1', null, null, 1000, 1000, new Log(new NullLogger()), null, null,
            null, new FakeSender(), $trace);
    }

    public function testValidatesVersion00Traceparent(): void
    {
        self::assertTrue(HttpTransport::validTraceparent(self::TP));
        self::assertFalse(HttpTransport::validTraceparent(null));
        self::assertFalse(HttpTransport::validTraceparent('00-' . str_repeat('0', 32) . '-00f067aa0ba902b7-01'));
        self::assertFalse(HttpTransport::validTraceparent('00-4bf92f3577b34da6a3ce929d0e0e4736-' . str_repeat('0', 16) . '-01'));
        self::assertFalse(HttpTransport::validTraceparent(strtoupper(self::TP)));
        self::assertFalse(HttpTransport::validTraceparent(self::TP . "\n"));
        self::assertTrue(HttpTransport::validTracestate('congo=t61rcWkgMzE'));
        self::assertFalse(HttpTransport::validTracestate('a=' . str_repeat('x', 511)));
    }

    public function testNoContextNoHeaders(): void
    {
        self::assertSame([], self::transport(null)->traceHeaders(ApiRequest::get('/me')));
    }

    public function testContextIsSent(): void
    {
        $t = self::transport(fn (): array => ['traceparent' => self::TP, 'tracestate' => 'congo=t61rcWkgMzE']);
        self::assertSame(['traceparent' => self::TP, 'tracestate' => 'congo=t61rcWkgMzE'],
            $t->traceHeaders(ApiRequest::get('/me')));
    }

    public function testInvalidOrAbsentContextDropped(): void
    {
        self::assertSame([], self::transport(fn (): ?array => null)->traceHeaders(ApiRequest::get('/me')));
        self::assertSame([], self::transport(fn (): array => ['traceparent' => 'nope'])->traceHeaders(ApiRequest::get('/me')));
    }

    public function testFailingProviderNeverFailsTheCall(): void
    {
        $t = self::transport(function (): array {
            throw new \RuntimeException('exporter down');
        });
        self::assertSame([], $t->traceHeaders(ApiRequest::get('/me')));
    }

    public function testRequestLevelTraceparentWins(): void
    {
        $req = ApiRequest::get('/me')->header('TraceParent', '00-' . str_repeat('1', 32) . '-' . str_repeat('2', 16) . '-00');
        self::assertSame([], self::transport(fn (): array => ['traceparent' => self::TP])->traceHeaders($req));
    }
}
