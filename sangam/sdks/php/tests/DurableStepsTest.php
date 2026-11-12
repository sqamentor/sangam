<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Log\CallbackLogger;
use Sangam\Sdk\Log\Level;
use Sangam\Sdk\Log\Log;
use Sangam\Sdk\Model\ExternalWork;
use Sangam\Sdk\Model\TypedValue;
use Sangam\Sdk\SangamClient;
use Sangam\Sdk\Testing\SangamTestEngine;
use Sangam\Sdk\Worker\OutcomeUnknownException;
use Sangam\Sdk\Worker\Step;
use Sangam\Sdk\Worker\StepNoEffectException;
use Sangam\Sdk\Worker\StepOptions;
use Sangam\Sdk\Worker\TaskCancelledException;
use Sangam\Sdk\Worker\TaskContext;

/**
 * M-025 TaskContext::step() (R37 / E02) against the test engine's step journal through the real client: record and
 * replay, the crash window (INTENT) per capability class, FAILED on no effect, and lease loss.
 */
final class DurableStepsTest extends TestCase
{
    /** @var list<string> */
    private array $events = [];
    private SangamTestEngine $engine;
    private SangamClient $client;
    private Log $log;
    private string $workId;

    protected function setUp(): void
    {
        $this->events = [];
        $sink = new CallbackLogger(function (Level $l, string $event): void {
            $this->events[] = $event;
        });
        $this->engine = new SangamTestEngine();
        $this->client = $this->engine->client($sink);
        $this->log = new Log($sink);
        $this->workId = $this->engine->addWork('pay');
    }

    /** A new lease on the work (the previous one, if any, is unlocked first). */
    private function lease(?TaskContext $previous = null): TaskContext
    {
        if ($previous !== null) {
            [$s] = $this->engine->handle('POST', '/work/' . $this->workId . '/unlock', null, ['workerId' => 'w1',
                'lockVersion' => $previous->lockVersion(), 'attemptId' => $previous->attemptId()]);
            self::assertSame(204, $s);
        }
        [$status, , $body] = $this->engine->handle('POST', '/work/fetch-and-lock', null,
            ['workerId' => 'w1', 'topics' => [['name' => 'pay']]]);
        self::assertSame(200, $status);
        self::assertCount(1, $body);
        return new TaskContext(ExternalWork::fromArray($body[0]), 'w1', $this->client, $this->log);
    }

    /** @return list<array<string, mixed>> */
    private function journal(): array
    {
        [$s, , $rows] = $this->engine->handle('GET', '/work/' . $this->workId . '/steps', null, null);
        self::assertSame(200, $s);
        return $rows;
    }

    public function testRecordsIntentThenSucceededAndReplaysWithoutCallingAgain(): void
    {
        $ctx = $this->lease();
        $calls = 0;
        $seen = null;
        $fn = function (Step $step) use (&$calls, &$seen): array {
            $calls++;
            $seen = $step;
            $step->setProviderRef('PAY-1');
            return ['paymentId' => 'PAY-1'];
        };
        self::assertSame(['paymentId' => 'PAY-1'], $ctx->step('charge', $fn));
        self::assertSame(1, $calls);
        self::assertNull($seen->previousState());
        self::assertSame($ctx->operationId() . ':charge', $seen->idempotencyKey());
        $puts = array_values(array_filter($this->engine->calls, static fn(array $c): bool => $c['method'] === 'PUT'));
        self::assertSame(['INTENT', 'SUCCEEDED'], array_map(static fn(array $c): string => $c['body']['state'], $puts));
        self::assertSame('w1', $puts[1]['body']['workerId']);
        self::assertSame($ctx->lockVersion(), $puts[1]['body']['lockVersion']);
        self::assertSame($ctx->attemptId(), $puts[1]['body']['attemptId']);
        self::assertSame('PAY-1', $puts[1]['body']['providerRef']);
        [$row] = $this->journal();
        self::assertSame(['SUCCEEDED', 'PAY-1', ['paymentId' => 'PAY-1']], [$row['state'], $row['providerRef'], $row['result']]);

        $again = $this->lease($ctx);
        self::assertSame(['paymentId' => 'PAY-1'], $again->step('charge', $fn));
        self::assertSame(1, $calls);
        self::assertContains('step.replayed', $this->events);
    }

    public function testReplayUnwrapsTypedValues(): void
    {
        $ctx = $this->lease();
        $ctx->step('amount', static fn(Step $s): TypedValue => TypedValue::decimal('12.50'));
        $again = $this->lease($ctx);
        self::assertSame('12.50', $again->step('amount', static fn(Step $s): mixed => throw new \LogicException('not again')));
    }

    public function testIntentFromAnEarlierAttemptIsOutcomeUnknownByDefault(): void
    {
        $ctx = $this->lease();
        try {
            $ctx->step('charge', static fn(Step $s): mixed => throw new \RuntimeException('timeout after send'));
            self::fail('expected the error to propagate');
        } catch (\RuntimeException $e) {
            self::assertSame('timeout after send', $e->getMessage());
        }
        self::assertSame('INTENT', $this->journal()[0]['state']);
        $again = $this->lease($ctx);
        try {
            $again->step('charge', static fn(Step $s): mixed => throw new \LogicException('must not run'));
            self::fail('expected OutcomeUnknownException');
        } catch (OutcomeUnknownException $e) {
            self::assertStringContainsString('step charge of operation ' . $again->operationId(), $e->getMessage());
            self::assertStringContainsString('(R37)', $e->getMessage());
            self::assertSame(['step' => 'charge', 'idempotencyKey' => $again->operationId() . ':charge'], $e->variables);
        }
        self::assertSame('INTENT', $this->journal()[0]['state']);
    }

    public function testIdempotentCallRunsAgainWithTheSameKey(): void
    {
        $ctx = $this->lease();
        $keys = [];
        try {
            $ctx->step('charge', function (Step $s) use (&$keys): never {
                $keys[] = $s->idempotencyKey();
                throw new \RuntimeException('lost');
            }, StepOptions::idempotentCall());
        } catch (\RuntimeException) {
        }
        $again = $this->lease($ctx);
        $prev = null;
        $v = $again->step('charge', function (Step $s) use (&$keys, &$prev): string {
            $keys[] = $s->idempotencyKey();
            $prev = $s->previousState();
            return 'ok';
        }, StepOptions::idempotentCall());
        self::assertSame('ok', $v);
        self::assertSame('INTENT', $prev);
        self::assertCount(2, $keys);
        self::assertSame($keys[0], $keys[1]);
        self::assertSame('SUCCEEDED', $this->journal()[0]['state']);
    }

    public function testLookupValueIsRecordedWithoutCallingAgain(): void
    {
        $ctx = $this->lease();
        try {
            $ctx->step('charge', static fn(Step $s): mixed => throw new \RuntimeException('lost'));
        } catch (\RuntimeException) {
        }
        $again = $this->lease($ctx);
        $opts = StepOptions::withLookup(static function (Step $s): array {
            $s->setProviderRef('PAY-9');
            return ['paymentId' => 'PAY-9'];
        });
        $v = $again->step('charge', static fn(Step $s): mixed => throw new \LogicException('must not run'), $opts);
        self::assertSame(['paymentId' => 'PAY-9'], $v);
        [$row] = $this->journal();
        self::assertSame(['SUCCEEDED', 'PAY-9'], [$row['state'], $row['providerRef']]);
        self::assertContains('step.looked-up', $this->events);
    }

    public function testLookupNullRunsTheCallAgainAndALookupErrorPropagates(): void
    {
        $ctx = $this->lease();
        try {
            $ctx->step('charge', static fn(Step $s): mixed => throw new \RuntimeException('lost'));
        } catch (\RuntimeException) {
        }
        $again = $this->lease($ctx);
        try {
            $again->step('charge', static fn(Step $s): string => 'x',
                StepOptions::withLookup(static fn(Step $s): mixed => throw new \DomainException('provider down')));
            self::fail('expected the lookup error');
        } catch (\DomainException $e) {
            self::assertSame('provider down', $e->getMessage());
        }
        self::assertSame('INTENT', $this->journal()[0]['state']);
        $third = $this->lease($again);
        $v = $third->step('charge', static fn(Step $s): string => 'ran',
            StepOptions::defaults()->lookup(static fn(Step $s): mixed => null));
        self::assertSame('ran', $v);
        self::assertSame('SUCCEEDED', $this->journal()[0]['state']);
    }

    public function testNoEffectIsRecordedFailedAndMayRunAgain(): void
    {
        $ctx = $this->lease();
        try {
            $ctx->step('charge', static fn(Step $s): mixed => throw new StepNoEffectException('rejected: limit'));
            self::fail('expected StepNoEffectException');
        } catch (StepNoEffectException $e) {
            self::assertSame('rejected: limit', $e->getMessage());
        }
        [$row] = $this->journal();
        self::assertSame(['FAILED', 'rejected: limit'], [$row['state'], $row['error']]);
        $again = $this->lease($ctx);
        $prev = null;
        self::assertSame(7, $again->step('charge', function (Step $s) use (&$prev): int {
            $prev = $s->previousState();
            return 7;
        }));
        self::assertSame('FAILED', $prev);
    }

    public function testStepsAreJournalledInRecordingOrderUnderTheOperation(): void
    {
        $ctx = $this->lease();
        $ctx->step('b-second', static fn(Step $s): int => 2);
        $ctx->step('a-first', static fn(Step $s): int => 1);
        self::assertSame(['b-second', 'a-first'], array_column($this->journal(), 'name'));
        self::assertSame([$ctx->operationId()], array_values(array_unique(array_column($this->journal(), 'operationId'))));
    }

    /** @return iterable<string, array{0: string}> */
    public static function badNames(): iterable
    {
        yield 'empty' => [''];
        yield 'too long' => [str_repeat('a', 129)];
        yield 'control' => ["a\x01b"];
        yield 'delete' => ["a\x7fb"];
        yield 'non-ascii' => ["caf\u{e9}"];
    }

    #[DataProvider('badNames')]
    public function testRejectsBadNamesBeforeAnyCall(string $name): void
    {
        $ctx = $this->lease();
        $before = count($this->engine->calls);
        try {
            $ctx->step($name, static fn(Step $s): int => 1);
            self::fail('expected InvalidArgumentException');
        } catch (\InvalidArgumentException) {
        }
        self::assertCount($before, $this->engine->calls);
    }

    public function testAcceptsA128CharacterPrintableName(): void
    {
        $ctx = $this->lease();
        $name = str_repeat('x', 120) . ' ~!/?#%';
        self::assertSame(128, strlen($name . 'z'));
        self::assertSame(1, $ctx->step($name . 'z', static fn(Step $s): int => 1));
    }

    public function testStaleLeaseMarksTheLeaseLost(): void
    {
        $ctx = $this->lease();
        $this->lease($ctx); // another attempt now holds the lease
        try {
            $ctx->step('charge', static fn(Step $s): mixed => throw new \LogicException('must not run'));
            self::fail('expected TaskCancelledException');
        } catch (TaskCancelledException $e) {
            self::assertSame('lease lost while recording step charge', $e->getMessage());
            self::assertNotNull($e->getPrevious());
        }
        self::assertTrue($ctx->isLeaseLost());
    }

    public function testDeletedInstanceMarksTheLeaseLost(): void
    {
        $ctx = $this->lease();
        $this->engine->deleteInstance($this->workId);
        try {
            $ctx->step('charge', static fn(Step $s): int => 1);
            self::fail('expected TaskCancelledException');
        } catch (TaskCancelledException) {
        }
        self::assertTrue($ctx->isLeaseLost());
    }
}
