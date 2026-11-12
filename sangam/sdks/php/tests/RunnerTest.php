<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Error\GoneException;
use Sangam\Sdk\Error\Problem;
use Sangam\Sdk\Error\SangamApiException;
use Sangam\Sdk\Error\StaleLeaseException;
use Sangam\Sdk\Http\RetryPolicy;
use Sangam\Sdk\Log\CallbackLogger;
use Sangam\Sdk\Log\Level;
use Sangam\Sdk\Log\Log;
use Sangam\Sdk\Model\ExternalWork;
use Sangam\Sdk\Model\WorkOperation;
use Sangam\Sdk\SangamClient;
use Sangam\Sdk\Tests\Support\FakeSender;
use Sangam\Sdk\Worker\Backoff;
use Sangam\Sdk\Worker\BpmnError;
use Sangam\Sdk\Worker\Outcome;
use Sangam\Sdk\Worker\OutcomeUnknownException;
use Sangam\Sdk\Worker\Runner;
use Sangam\Sdk\Worker\SangamWorker;
use Sangam\Sdk\Worker\TaskCancelledException;
use Sangam\Sdk\Worker\TaskContext;
use Sangam\Sdk\Worker\TaskFailedException;
use Sangam\Sdk\Worker\TopicSubscription;

/**
 * Worker runtime: outcome mapping, SL-16 stored-outcome completion, the report loop (retries, fencing, deadline)
 * and one inline worker loop, all over the HttpSender seam (FakeSender): no network, no ext-curl.
 */
final class RunnerTest extends TestCase
{
    private const SUCCEEDED = [
        'state' => 'SUCCEEDED', 'attempts' => 2, 'capabilityClass' => 'A_IDEMPOTENT_WITH_LOOKUP', 'mayDispatch' => false,
        'storedOutcome' => ['output' => ['paymentRef' => 'PAY-77', 'amount' => ['type' => 'Decimal', 'value' => '12.50']],
            'providerRef' => 'PAY-77', 'duplicate' => false],
    ];

    /** @var list<array{0: string, 1: array<string, mixed>}> */
    private array $events = [];
    private FakeSender $sender;
    private CallbackLogger $sink;
    private SangamClient $client;
    private Log $log;
    private Runner $runner;

    protected function setUp(): void
    {
        $this->events = [];
        $this->sink = new CallbackLogger(function (Level $l, string $event, string $message, array $fields = []): void {
            $this->events[] = [$event, $fields];
        });
        $this->sender = new FakeSender();
        // the transport does not retry: the worker's own report retry is what is under test
        $this->client = new SangamClient('http://sangam.test', logger: $this->sink, retryPolicy: RetryPolicy::none(),
            sender: $this->sender);
        $this->log = new Log($this->sink);
        $this->runner = new Runner($this->client, 'worker-1', new Backoff(1, 2, 1.0, 0.0), $this->log);
    }

    /** @param array<string, mixed> $over */
    private static function item(array $over = []): array
    {
        return array_merge([
            'id' => 'wk-1', 'topic' => 'pay', 'lockVersion' => 3, 'attemptId' => 'att-1', 'operationId' => 'op-1',
            'lockExpiresAt' => gmdate('Y-m-d\TH:i:s\Z', time() + 60), 'retries' => 3, 'variables' => [],
        ], $over);
    }

    /** @param array<string, mixed> $over */
    private function ctx(array $over = []): TaskContext
    {
        return new TaskContext(ExternalWork::fromArray(self::item($over)), 'worker-1', $this->client, $this->log);
    }

    private static function sub(callable $handler, bool $irreversible = false, ?Backoff $retryBackoff = null): TopicSubscription
    {
        return new TopicSubscription('pay', $handler, 60_000, null, 3, $retryBackoff, $irreversible);
    }

    private function logged(string $event): bool
    {
        foreach ($this->events as [$e]) {
            if ($e === $event) {
                return true;
            }
        }
        return false;
    }

    /** Invokes the handler and reports its outcome with a deadline $ttl seconds from now; returns the context. */
    private function invokeAndReport(callable $handler, array $over = [], bool $irreversible = false, float $ttl = 60.0,
                         ?Backoff $retryBackoff = null): TaskContext
    {
        $ctx = $this->ctx($over);
        $sub = self::sub($handler, $irreversible, $retryBackoff);
        $this->runner->report($ctx, $sub, $this->runner->invoke($ctx, $sub), microtime(true) + $ttl);
        return $ctx;
    }

    // ---------------------------------------------------------------- stored outcome (SL-16)

    public function testStoredOutcomeContract(): void
    {
        self::assertSame(
            ['variables' => ['paymentRef' => 'PAY-77', 'amount' => ['type' => 'Decimal', 'value' => '12.50']], 'reference' => 'PAY-77'],
            Runner::storedOutcomeCompletion(WorkOperation::fromArray(self::SUCCEEDED)),
        );
        self::assertSame(['variables' => [], 'reference' => null],
            Runner::storedOutcomeCompletion(array_merge(self::SUCCEEDED, ['storedOutcome' => null])));
        self::assertSame(['variables' => [], 'reference' => null],
            Runner::storedOutcomeCompletion(array_merge(self::SUCCEEDED, ['storedOutcome' => ['output' => [1, 2]]])));
        self::assertNull(Runner::storedOutcomeCompletion(null));
        self::assertNull(Runner::storedOutcomeCompletion(['state' => 'INTENT', 'attempts' => 2, 'mayDispatch' => true]));
        self::assertNull(Runner::storedOutcomeCompletion(['state' => 'UNKNOWN_OUTCOME', 'attempts' => 1, 'mayDispatch' => false]));
        self::assertNull(Runner::storedOutcomeCompletion(['state' => 'FAILED', 'attempts' => 1, 'mayDispatch' => false]));
    }

    public function testCompletesFromTheStoredOutcomeWithoutCallingTheHandler(): void
    {
        $called = 0;
        $this->invokeAndReport(function () use (&$called): array {
            $called++;
            return ['paymentRef' => 'NEW'];
        }, ['operation' => self::SUCCEEDED]);
        self::assertSame(0, $called);
        $calls = $this->sender->callsOf('complete');
        self::assertCount(1, $calls);
        self::assertSame('/work/wk-1/complete', $calls[0]['path']);
        self::assertSame('op-1:complete:3', $calls[0]['headers']['Idempotency-Key']);
        self::assertSame([
            'workerId' => 'worker-1', 'lockVersion' => 3, 'attemptId' => 'att-1',
            'variables' => ['paymentRef' => 'PAY-77', 'amount' => ['type' => 'Decimal', 'value' => '12.50']],
            'outcome' => ['reference' => 'PAY-77'],
        ], $calls[0]['body']);
        self::assertTrue($this->logged('work.replayed'));
    }

    public function testRunsTheHandlerWhileTheOperationMayBeDispatched(): void
    {
        $ctx = $this->ctx(['operation' => ['state' => 'INTENT', 'attempts' => 2, 'mayDispatch' => true]]);
        $o = $this->runner->invoke($ctx, self::sub(static fn(TaskContext $c): array => ['attempts' => $c->operation()?->attempts]));
        self::assertSame('complete', $o->kind);
        self::assertSame(['attempts' => 2], $o->variables);
        self::assertNull($ctx->outcomeReference());
    }

    public function testHandlerSetOutcomeReferenceIsSentAsOutcomeReference(): void
    {
        $this->invokeAndReport(static function (TaskContext $c): array {
            $c->setOutcomeReference('BANK-REF-1');
            return ['status' => 'PAID'];
        });
        $body = $this->sender->callsOf('complete')[0]['body'];
        self::assertSame(['reference' => 'BANK-REF-1'], $body['outcome']);
        self::assertSame(['status' => 'PAID'], $body['variables']);
    }

    public function testNoOutcomeIsSentWithoutAReference(): void
    {
        $this->invokeAndReport(static fn(): array => ['ok' => true]);
        self::assertArrayNotHasKey('outcome', $this->sender->callsOf('complete')[0]['body']);
    }

    // ---------------------------------------------------------------- outcome mapping

    public function testBpmnErrorBody(): void
    {
        $this->invokeAndReport(static function (): never {
            throw new BpmnError('INSUFFICIENT_FUNDS', 'balance too low', ['balance' => 3]);
        });
        $c = $this->sender->callsOf('bpmn-error')[0];
        self::assertSame('op-1:bpmn-error:3', $c['headers']['Idempotency-Key']);
        self::assertSame(['workerId' => 'worker-1', 'lockVersion' => 3, 'attemptId' => 'att-1',
            'errorCode' => 'INSUFFICIENT_FUNDS', 'errorMessage' => 'balance too low', 'variables' => ['balance' => 3]],
            $c['body']);
    }

    public function testOutcomeUnknownBody(): void
    {
        $this->invokeAndReport(static function (): never {
            throw new OutcomeUnknownException('bank timed out after send', 'socket read timeout', ['sent' => true]);
        });
        $b = $this->sender->callsOf('outcome-unknown')[0]['body'];
        self::assertSame("bank timed out after send\nsocket read timeout", $b['detail']);
        self::assertSame(['sent' => true], $b['evidence']);
    }

    public function testTaskFailedOverridesRetriesAndTimeout(): void
    {
        $this->invokeAndReport(static function (): never {
            throw new TaskFailedException('downstream 503', 'd', 0, 1234);
        });
        $b = $this->sender->callsOf('fail')[0]['body'];
        self::assertSame(['downstream 503', 'd', 0, 1234],
            [$b['errorMessage'], $b['errorDetails'], $b['retries'], $b['retryTimeoutMs']]);
    }

    public function testUnexpectedErrorFailsWithOneRetryLess(): void
    {
        $this->invokeAndReport(static function (): never {
            throw new \RuntimeException('boom');
        }, ['retries' => 3], false, 60.0, new Backoff(10, 10, 1.0, 0.0));
        $b = $this->sender->callsOf('fail')[0]['body'];
        self::assertSame(['boom', 2, 10], [$b['errorMessage'], $b['retries'], $b['retryTimeoutMs']]);
    }

    public function testUnexpectedErrorOnIrreversibleTopicIsOutcomeUnknown(): void
    {
        $this->invokeAndReport(static function (): never {
            throw new \RuntimeException('connection reset');
        }, [], true);
        $b = $this->sender->callsOf('outcome-unknown')[0]['body'];
        self::assertStringStartsWith('unexpected error on irreversible topic: connection reset', $b['detail']);
        self::assertSame([], $this->sender->callsOf('fail'));
    }

    public function testAStrayEffectMemberDoesNotMakeAnItemIrreversible(): void
    {
        // the server sends no per-item effect (WorkPort.ExternalWork); only the subscription decides
        $this->invokeAndReport(static function (): never {
            throw new \RuntimeException('boom');
        }, ['effect' => 'irreversible']);
        self::assertCount(1, $this->sender->callsOf('fail'));
        self::assertSame([], $this->sender->callsOf('outcome-unknown'));
    }

    public function testCancelledWithoutServerRequestUnlocks(): void
    {
        $o = $this->runner->invoke($this->ctx(), self::sub(static function (): never {
            throw new TaskCancelledException('stop');
        }));
        self::assertSame('unlock', $o->kind);
    }

    public function testNonArrayHandlerResultFails(): void
    {
        $o = $this->runner->invoke($this->ctx(), self::sub(static fn(): string => 'oops'));
        self::assertSame('fail', $o->kind);
    }

    public function testExternalWorkTypesTheOperation(): void
    {
        $w = ExternalWork::fromArray(['id' => 'wk-1', 'topic' => 'pay', 'operation' => self::SUCCEEDED]);
        self::assertInstanceOf(WorkOperation::class, $w->operation);
        self::assertSame(['SUCCEEDED', 2, 'A_IDEMPOTENT_WITH_LOOKUP', false, self::SUCCEEDED['storedOutcome']],
            [$w->operation->state, $w->operation->attempts, $w->operation->capabilityClass,
                $w->operation->mayDispatch, $w->operation->storedOutcome]);
    }

    // ---------------------------------------------------------------- report loop: retries and fencing

    public function testRetryableAnswerIsRetriedWithTheSameKeyUntilAccepted(): void
    {
        $this->sender->reply('complete', 503, ['title' => 'busy'])->reply('complete', 503)->reply('complete', 204);
        $this->invokeAndReport(static fn(): array => ['ok' => true]);
        $keys = array_map(static fn(array $c): string => $c['headers']['Idempotency-Key'], $this->sender->callsOf('complete'));
        self::assertSame(['op-1:complete:3', 'op-1:complete:3', 'op-1:complete:3'], $keys);
        $attempts = null;
        foreach ($this->events as [$e, $f]) {
            if ($e === 'work.complete') {
                $attempts = $f['attempts'] ?? null;
            }
        }
        self::assertSame(3, $attempts);
    }

    public function testStaleLeaseAbandonsAfterOneCall(): void
    {
        $this->sender->reply('complete', 409, ['code' => 'STALE_LEASE', 'title' => 'stale lease']);
        $ctx = $this->invokeAndReport(static fn(): array => ['ok' => true]);
        self::assertCount(1, $this->sender->callsOf('complete'));
        self::assertTrue($ctx->isLeaseLost());
        self::assertTrue($this->logged('work.abandoned'));
    }

    public function testNonRetryableAnswerIsNotRetried(): void
    {
        $this->sender->reply('complete', 400, ['title' => 'bad request']);
        $this->invokeAndReport(static fn(): array => ['ok' => true]);
        self::assertCount(1, $this->sender->callsOf('complete'));
        self::assertTrue($this->logged('work.report.failed'));
    }

    public function testNoRetryAfterTheLeaseDeadline(): void
    {
        $this->sender->reply('complete', 503)->reply('complete', 503);
        $this->invokeAndReport(static fn(): array => ['ok' => true], [], false, -1.0);
        self::assertCount(1, $this->sender->callsOf('complete'));
        self::assertTrue($this->logged('work.report.failed'));
    }

    // ---------------------------------------------------------------- inline worker loop

    public function testInlineWorkerFetchesRunsAndReportsOneItem(): void
    {
        $worker = new SangamWorker($this->client, 'worker-1', 1, 1, 50, 1 / 3, new Backoff(1, 2, 1.0, 0.0),
            new Backoff(1, 2, 1.0, 0.0), 1000, SangamWorker::MODE_INLINE, $this->sink);
        $this->sender->enqueue(self::item(['operation' => self::SUCCEEDED]));
        $this->sender->onCall = static function (array $c) use ($worker): void {
            if ($c['action'] === 'complete') {
                $worker->stop();
            }
        };
        $worker->subscribe(new TopicSubscription('pay', static fn(): array => ['paymentRef' => 'NEW'], 60_000));
        $worker->run();
        $fetch = $this->sender->callsOf('fetch-and-lock')[0]['body'];
        self::assertSame(['workerId' => 'worker-1', 'topics' => [['name' => 'pay', 'lockDurationMs' => 60_000]],
            'maxTasks' => 1, 'asyncResponseTimeoutMs' => 50], $fetch);
        $complete = $this->sender->callsOf('complete');
        self::assertCount(1, $complete);
        self::assertSame(['reference' => 'PAY-77'], $complete[0]['body']['outcome']);
    }

    public function testInlineWorkerNeverStartsAnItemWithoutItsLockVersion(): void
    {
        $worker = new SangamWorker($this->client, 'worker-1', 1, 1, 50, 1 / 3, new Backoff(1, 2, 1.0, 0.0),
            new Backoff(1, 2, 1.0, 0.0), 1000, SangamWorker::MODE_INLINE, $this->sink);
        $item = self::item();
        unset($item['lockVersion']);
        $this->sender->enqueue($item);
        $fetches = 0;
        $this->sender->onCall = static function (array $c) use ($worker, &$fetches): void {
            if ($c['action'] === 'fetch-and-lock' && ++$fetches >= 2) {
                $worker->stop();
            }
        };
        $called = 0;
        $worker->subscribe(new TopicSubscription('pay', static function () use (&$called): array {
            $called++;
            return [];
        }, 60_000));
        $worker->run();
        self::assertSame(0, $called);
        foreach (['complete', 'fail', 'bpmn-error', 'outcome-unknown', 'unlock', 'cancel-ack'] as $action) {
            self::assertCount(0, $this->sender->callsOf($action), $action);
        }
    }

    public function testAbsentLockVersionAndVariablesStayNullAndBlockTheContext(): void
    {
        $item = self::item();
        unset($item['lockVersion'], $item['variables']);
        $w = ExternalWork::fromArray($item);
        self::assertNull($w->lockVersion);
        self::assertNull($w->variables);
        $this->expectException(\InvalidArgumentException::class);
        new TaskContext($w, 'worker-1', $this->client, $this->log);
    }

    public function testContextReadsVariablesWhenTheServerSentNone(): void
    {
        $ctx = $this->ctx(['variables' => null]);
        self::assertSame([], $ctx->variables());
        self::assertSame('d', $ctx->variable('missing', 'd'));
    }

    public function testInlineWorkerUnlocksAnItemOfAnUnsubscribedTopic(): void
    {
        $worker = new SangamWorker($this->client, 'worker-1', 1, 1, 50, 1 / 3, new Backoff(1, 2, 1.0, 0.0),
            new Backoff(1, 2, 1.0, 0.0), 1000, SangamWorker::MODE_INLINE, $this->sink);
        $this->sender->enqueue(self::item(['topic' => 'other']));
        $this->sender->onCall = static function (array $c) use ($worker): void {
            if ($c['action'] === 'unlock') {
                $worker->stop();
            }
        };
        $worker->subscribe(new TopicSubscription('pay', static fn(): array => [], 60_000));
        $worker->run();
        $unlock = $this->sender->callsOf('unlock');
        self::assertCount(1, $unlock);
        self::assertSame('op-1:unlock:3', $unlock[0]['headers']['Idempotency-Key']);
    }

    // ---------------------------------------------------------------- HTTP error mapping

    /** @return iterable<string, array{int, ?array<string, mixed>, class-string}> */
    public static function errors(): iterable
    {
        yield 'stale-lease type' => [409, ['type' => 'https://sangam.dev/problems/stale-lease'], StaleLeaseException::class];
        yield 'STALE_LEASE code' => [409, ['code' => 'STALE_LEASE'], StaleLeaseException::class];
        yield 'other 409' => [409, ['code' => 'CONFLICT'], SangamApiException::class];
        yield 'gone' => [410, ['code' => 'GONE_INSTANCE_DELETED'], GoneException::class];
        yield 'plain 502' => [502, null, SangamApiException::class];
    }

    /** @param array<string, mixed>|null $body */
    #[DataProvider('errors')]
    public function testErrorMapping(int $status, ?array $body, string $class): void
    {
        $this->sender->reply('unlock', $status, $body);
        try {
            $this->client->work()->unlock('wk-1', 'w', 1, 'a', 'k');
            self::fail('expected ' . $class);
        } catch (SangamApiException $e) {
            self::assertSame($class, get_class($e));
            self::assertSame($status, $e->status);
        }
    }

    public function testRetryableStatuses(): void
    {
        $retryable = [];
        foreach ([400, 401, 403, 404, 408, 409, 422, 425, 429, 500, 501, 502, 503, 504] as $s) {
            if ((new SangamApiException($s, new Problem(status: $s), 'x'))->isRetryable()) {
                $retryable[] = $s;
            }
        }
        self::assertSame([408, 425, 429, 500, 502, 503, 504], $retryable);
    }

    public function testOutcomeIsAnInternalValueObject(): void
    {
        $o = new Outcome('complete', ['a' => 1]);
        self::assertSame(['complete', ['a' => 1], null], [$o->kind, $o->variables, $o->error]);
    }
}
