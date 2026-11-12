<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Error\SangamApiException;
use Sangam\Sdk\Error\StaleLeaseException;
use Sangam\Sdk\Error\TransportException;
use Sangam\Sdk\Log\CallbackLogger;
use Sangam\Sdk\Log\Level;
use Sangam\Sdk\Model\TypedValue;
use Sangam\Sdk\SangamClient;
use Sangam\Sdk\Testing\SangamTestEngine;
use Sangam\Sdk\Worker\Backoff;
use Sangam\Sdk\Worker\OutcomeUnknownException;
use Sangam\Sdk\Worker\SangamWorker;
use Sangam\Sdk\Worker\TaskContext;
use Sangam\Sdk\Worker\TopicSubscription;

/**
 * M-023 / R18 / AC-08: contract tests T01-T04 and T18 (SANGAM.md II §16) for the PHP SDK, run against the R18 test
 * engine (Sangam\Sdk\Testing\SangamTestEngine) through the real SangamClient and SangamWorker (inline mode).
 */
final class ConformanceTest extends TestCase
{
    private const VECTORS = __DIR__ . '/../../testing/t18-vectors.json';

    /** @var list<array{0: string, 1: array<string, mixed>}> */
    private array $events = [];

    private function client(SangamTestEngine $engine): SangamClient
    {
        $sink = new CallbackLogger(function (Level $l, string $event, string $message, array $fields = []): void {
            $this->events[] = [$event, $fields];
        });
        return $engine->client($sink);
    }

    private function worker(SangamClient $client, string $workerId = 'worker-1'): SangamWorker
    {
        return new SangamWorker($client, $workerId, 1, 1, 10, 1 / 3, new Backoff(1, 2, 1.0, 0.0),
            new Backoff(1, 2, 1.0, 0.0), 1000, SangamWorker::MODE_INLINE);
    }

    /** Stops $worker at the first fetch after $done() holds (the inline loop returns to fetch after each item). */
    private static function stopWhen(SangamTestEngine $engine, SangamWorker $worker, \Closure $done): void
    {
        $fetches = 0;
        $engine->onCall = static function (array $c) use ($worker, $done, &$fetches): void {
            if (str_ends_with($c['path'], '/fetch-and-lock') && ($done() || ++$fetches > 200)) {
                $worker->stop();
            }
        };
    }

    /** @return list<array<string, mixed>> */
    private function events(string $name): array
    {
        return array_values(array_map(static fn(array $e): array => $e[1],
            array_filter($this->events, static fn(array $e): bool => $e[0] === $name)));
    }

    // ------------------------------------------------------------------ T01

    public function testT01DuplicateStartIsOneInstanceAndAConflictingPayloadIsRejected(): void
    {
        $engine = new SangamTestEngine();
        $client = $engine->client();
        $vars = ['amount' => TypedValue::decimal('125000.00')];
        $first = $client->processes()->start('p1_bill', 'BILL-1', $vars, idempotencyKey: 'start-1');
        self::assertFalse($client->lastResponse()?->replayed() ?? true);
        $again = $client->processes()->start('p1_bill', 'BILL-1', $vars, idempotencyKey: 'start-1');
        self::assertTrue($client->lastResponse()?->replayed() ?? false);
        self::assertSame($first['id'], $again['id']);
        self::assertCount(1, $engine->instances);
        try {
            $client->processes()->start('p1_bill', 'BILL-1', ['amount' => TypedValue::decimal('1.00')], idempotencyKey: 'start-1');
            self::fail('expected 422 IDEMPOTENCY_KEY_REUSED');
        } catch (SangamApiException $e) {
            self::assertSame(422, $e->status);
            self::assertSame('IDEMPOTENCY_KEY_REUSED', $e->problem->code);
        }
        self::assertCount(1, $engine->instances);
    }

    // ------------------------------------------------------------------ T02

    public function testT02ALostCompleteAnswerIsRetriedWithTheSameKeyAndCompletesOnce(): void
    {
        $engine = new SangamTestEngine();
        $wid = $engine->addWork('pay', ['amount' => ['type' => 'Decimal', 'value' => '12.50']]);
        $engine->loseNextResponse('complete');
        $worker = $this->worker($this->client($engine));
        self::stopWhen($engine, $worker, static fn(): bool => $engine->state($wid) === 'COMPLETED');
        $worker->subscribe(new TopicSubscription('pay', static fn(): array => ['paid' => true], 60_000));
        $worker->run();
        $calls = $engine->callsOf('complete');
        self::assertGreaterThanOrEqual(2, count($calls), 'the lost answer must be retried');
        self::assertCount(1, array_unique(array_column($calls, 'idempotencyKey')), 'every retry carries the same key');
        self::assertSame(1, $engine->completions($wid));
        self::assertCount(1, $this->events('work.complete'));
    }

    public function testT02OutcomeUnknownOpensReconciliationAndIsNeverReoffered(): void
    {
        $engine = new SangamTestEngine();
        $wid = $engine->addWork('pay');
        $worker = $this->worker($this->client($engine));
        $fetchesAfter = 0;
        $engine->onCall = static function (array $c) use ($engine, $worker, $wid, &$fetchesAfter): void {
            if (str_ends_with($c['path'], '/fetch-and-lock') && $engine->state($wid) === 'UNKNOWN_OUTCOME') {
                $engine->advance(10 * 60_000);
                if (++$fetchesAfter >= 3) {
                    $worker->stop();
                }
            }
        };
        $worker->subscribe(new TopicSubscription('pay', static function (): array {
            throw new OutcomeUnknownException('bank timeout after debit request');
        }, 60_000));
        $worker->run();
        self::assertSame('UNKNOWN_OUTCOME', $engine->state($wid));
        self::assertContains($wid, array_column($engine->reconciliationItems, 'workId'));
        self::assertSame(1, $engine->snapshot($wid)['attempts'], 'UNKNOWN_OUTCOME work is never handed out again');
    }

    public function testT02AnUnexpectedErrorOnAnIrreversibleTopicIsOutcomeUnknown(): void
    {
        $engine = new SangamTestEngine(['pay-irreversible']);
        $wid = $engine->addWork('pay-irreversible');
        $worker = $this->worker($this->client($engine));
        self::stopWhen($engine, $worker, static fn(): bool => $engine->state($wid) === 'UNKNOWN_OUTCOME');
        $worker->subscribe(new TopicSubscription('pay-irreversible', static function (): array {
            throw new \RuntimeException('connection reset after send');
        }, 60_000, irreversible: true));
        $worker->run();
        self::assertSame('UNKNOWN_OUTCOME', $engine->state($wid));
        self::assertSame([], $engine->callsOf('fail'));
    }

    // ------------------------------------------------------------------ T03

    public function testT03TheStaleWorkerGetsStaleLeaseAndTheNewOwnerCompletes(): void
    {
        $engine = new SangamTestEngine();
        $wid = $engine->addWork('pay');
        $a = $engine->client();
        $b = $engine->client();
        [$wa] = $a->work()->fetchAndLock('worker-a', [['name' => 'pay', 'lockDurationMs' => 10_000]], null, null);
        $engine->advance(10_001);
        [$wb] = $b->work()->fetchAndLock('worker-b', [['name' => 'pay', 'lockDurationMs' => 10_000]], null, null);
        self::assertSame($wid, $wb->id);
        self::assertGreaterThan($wa->lockVersion, $wb->lockVersion);
        self::assertSame($wa->operationId, $wb->operationId);
        try {
            $a->work()->complete($wid, 'worker-a', (int) $wa->lockVersion, $wa->attemptId, ['by' => 'a'], null);
            self::fail('expected STALE_LEASE');
        } catch (StaleLeaseException $e) {
            self::assertFalse($e->isRetryable());
            self::assertSame('STALE_LEASE', $e->problem->code);
        }
        self::assertSame('LOCKED', $engine->state($wid));
        self::assertSame('worker-b', $engine->snapshot($wid)['workerId']);
        $b->work()->complete($wid, 'worker-b', (int) $wb->lockVersion, $wb->attemptId, ['by' => 'b'], null);
        self::assertSame(1, $engine->completions($wid));
        self::assertSame(['by' => 'b'], $engine->snapshot($wid)['output']);
    }

    public function testT03TheWorkerDropsAStaleResultWithoutRetrying(): void
    {
        $engine = new SangamTestEngine();
        $wid = $engine->addWork('pay');
        $other = $engine->client();
        $worker = $this->worker($this->client($engine), 'worker-a');
        $wb = null;
        self::stopWhen($engine, $worker, static fn(): bool => $engine->completions($wid) > 0 || $engine->callsOf('complete') !== []);
        $worker->subscribe(new TopicSubscription('pay', static function () use ($engine, $other, &$wb): array {
            // while the handler runs the lease expires and worker-b takes the work over
            $engine->advance(30_001);
            [$wb] = $other->work()->fetchAndLock('worker-b', [['name' => 'pay']], null, null);
            return ['by' => 'a'];
        }, 30_000));
        $worker->run();
        self::assertNotNull($wb);
        $aCompletes = array_filter($engine->callsOf('complete'), static fn(array $c): bool => ($c['body']['workerId'] ?? null) === 'worker-a');
        self::assertLessThanOrEqual(1, count($aCompletes), 'a 409 STALE_LEASE is never retried');
        self::assertNotEmpty($this->events('work.abandoned'));
        self::assertSame('worker-b', $engine->snapshot($wid)['workerId']);
        $other->work()->complete($wid, 'worker-b', (int) $wb->lockVersion, $wb->attemptId, null, null);
        self::assertSame(1, $engine->completions($wid));
    }

    // ------------------------------------------------------------------ T04

    public function testT04ALostFetchAnswerKeepsTheWorkLockedUntilTheLeaseExpires(): void
    {
        $engine = new SangamTestEngine();
        $wid = $engine->addWork('pay');
        $client = $engine->client();
        $engine->loseNextResponse('fetch-and-lock');
        try {
            $client->work()->fetchAndLock('worker-a', [['name' => 'pay', 'lockDurationMs' => 20_000]], null, null);
            self::fail('expected a transport error');
        } catch (TransportException) {
            // the grant happened; only the answer was lost
        }
        self::assertSame('LOCKED', $engine->state($wid));
        self::assertSame([], $client->work()->fetchAndLock('worker-b', [['name' => 'pay']], null, null));
        $engine->advance(19_999);
        self::assertSame([], $client->work()->fetchAndLock('worker-b', [['name' => 'pay']], null, null));
        $engine->advance(1);
        [$w] = $client->work()->fetchAndLock('worker-b', [['name' => 'pay']], null, null);
        self::assertSame($wid, $w->id);
        self::assertGreaterThan(1, $w->lockVersion);
    }

    public function testT04AHeartBeatingLeaseHolderIsNeverReoffered(): void
    {
        $engine = new SangamTestEngine();
        $wid = $engine->addWork('pay');
        $client = $engine->client();
        [$w] = $client->work()->fetchAndLock('worker-a', [['name' => 'pay', 'lockDurationMs' => 9_000]], null, null);
        for ($i = 0; $i < 5; $i++) {
            $engine->advance(6_000);
            $ls = $client->work()->extendLock($wid, 'worker-a', (int) $w->lockVersion, $w->attemptId, 9_000, null);
            self::assertSame($w->lockVersion, $ls->lockVersion);
            self::assertSame([], $client->work()->fetchAndLock('worker-b', [['name' => 'pay']], null, null));
        }
        $client->work()->complete($wid, 'worker-a', (int) $w->lockVersion, $w->attemptId, null, null);
        self::assertSame(1, $engine->completions($wid));
    }

    // ------------------------------------------------------------------ T18

    /** @return list<array{name: string, wire: mixed, value: ?string, kind: string}> */
    private static function vectors(): array
    {
        $d = json_decode((string) file_get_contents(self::VECTORS), true, 512, JSON_BIGINT_AS_STRING | JSON_THROW_ON_ERROR);
        return $d['vectors'];
    }

    /** @return iterable<string, array{0: array{name: string, wire: mixed, value: ?string, kind: string}}> */
    public static function vectorCases(): iterable
    {
        foreach (self::vectors() as $v) {
            yield $v['name'] => [$v];
        }
    }

    private static function same(string $kind, ?string $expected, mixed $actual): void
    {
        match ($kind) {
            'null' => self::assertNull($actual),
            // exact numeric string: same number and scale
            'decimal', 'biginteger' => self::assertSame($expected, $actual),
            'long' => self::assertSame($expected, (string) $actual),
            'integer' => self::assertSame((int) $expected, $actual),
            'boolean' => self::assertSame($expected === 'true', $actual),
            'string' => self::assertSame($expected, $actual),
            // same instant
            'date' => self::assertSame((new \DateTimeImmutable((string) $expected))->format('U.u'),
                TypedValue::toDateTime(['type' => 'Date', 'value' => $actual])?->format('U.u')),
            'localdate' => self::assertSame($expected, $actual),
            default => self::fail('unknown kind ' . $kind),
        };
    }

    private static function reEncode(string $kind, mixed $v): mixed
    {
        return match ($kind) {
            'null' => null,
            'decimal' => TypedValue::decimal((string) $v),
            'biginteger' => TypedValue::bigInteger((string) $v),
            'long' => TypedValue::long(is_int($v) ? $v : (string) $v),
            'integer' => TypedValue::integer((int) $v),
            'boolean' => TypedValue::boolean((bool) $v),
            'string' => TypedValue::string((string) $v),
            'date' => TypedValue::date((string) $v),
            'localdate' => TypedValue::localDate((string) $v),
            default => throw new \InvalidArgumentException('unknown kind ' . $kind),
        };
    }

    /** @param array{name: string, wire: mixed, value: ?string, kind: string} $v */
    #[DataProvider('vectorCases')]
    public function testT18VectorSurvivesDecodeAndReEncode(array $v): void
    {
        $decoded = TypedValue::unwrap($v['wire']);
        self::same($v['kind'], $v['value'], $decoded);
        $wire = json_decode((string) json_encode(self::reEncode($v['kind'], $decoded)), true, 512, JSON_BIGINT_AS_STRING);
        self::same($v['kind'], $v['value'], TypedValue::unwrap($wire));
    }

    public function testT18EveryVectorSurvivesARoundTripThroughAWorker(): void
    {
        $vectors = self::vectors();
        $engine = new SangamTestEngine();
        $vars = [];
        foreach ($vectors as $v) {
            $vars[$v['name']] = $v['wire'];
        }
        $wid = $engine->addWork('t18', $vars);
        $worker = $this->worker($this->client($engine));
        self::stopWhen($engine, $worker, static fn(): bool => $engine->state($wid) === 'COMPLETED');
        $worker->subscribe(new TopicSubscription('t18', static fn(TaskContext $ctx): array => $ctx->work()->variables ?? [], 60_000));
        $worker->run();
        $out = $engine->snapshot($wid)['output'];
        foreach ($vectors as $v) {
            self::assertArrayHasKey($v['name'], $out, $v['name']);
            self::same($v['kind'], $v['value'], TypedValue::unwrap($out[$v['name']]));
        }
    }
}
