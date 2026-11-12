<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Log\CallbackLogger;
use Sangam\Sdk\Log\Level;
use Sangam\Sdk\Log\Log;
use Sangam\Sdk\Log\NullLogger;
use Sangam\Sdk\Model\ExternalWork;
use Sangam\Sdk\Model\TypedValue;
use Sangam\Sdk\SangamClient;
use Sangam\Sdk\Testing\SangamTestEngine;
use Sangam\Sdk\Worker\CompensationContext;
use Sangam\Sdk\Worker\Saga;
use Sangam\Sdk\Worker\SagaRecord;
use Sangam\Sdk\Worker\SangamWorker;
use Sangam\Sdk\Worker\TaskContext;

/** M-026 saga helper (R36): two subscriptions, the forward record variable and the compensation's context. */
final class SagaTest extends TestCase
{
    private SangamClient $client;

    protected function setUp(): void
    {
        $this->client = (new SangamTestEngine())->client(new NullLogger());
    }

    /** @param array<string, mixed> $variables */
    private function ctx(string $topic, string $operationId, array $variables = []): TaskContext
    {
        $work = ExternalWork::fromArray(['id' => 'wk-' . $operationId, 'topic' => $topic, 'lockVersion' => 1,
            'attemptId' => 'att-1', 'operationId' => $operationId, 'variables' => $variables,
            'lockExpiresAt' => gmdate('Y-m-d\TH:i:s\Z', time() + 60)]);
        return new TaskContext($work, 'w1', $this->client, new Log(new NullLogger()));
    }

    /** The JSON a variable becomes on the wire and back (what the next fetch hands the compensation). */
    private static function wire(mixed $v): mixed
    {
        return json_decode((string) json_encode($v), true);
    }

    private static function saga(?array $variables = null): Saga
    {
        $b = Saga::step('pay.charge-1')
            ->action('payment.charge', static function (TaskContext $ctx): array {
                $ctx->setOutcomeReference('PAY-77');
                return ['chargeId' => 'C1'];
            })
            ->compensation('payment.refund', static fn(CompensationContext $c): array => [
                'refundKey' => $c->idempotencyKey(), 'refundOf' => $c->forwardProviderRef()]);
        if ($variables !== null) {
            $b->variables($variables);
        }
        return $b->lockDurationMs(60_000)->retries(2)->irreversible()->build();
    }

    public function testRecordVariableName(): void
    {
        self::assertSame('sangamSaga_pay_charge_1', Saga::recordVariable('pay.charge-1'));
        self::assertSame('sangamSaga_a_b', Saga::recordVariable('a_b'));
    }

    public function testBuildsTwoSubscriptionsWithTheOptions(): void
    {
        $saga = self::saga();
        self::assertSame('pay.charge-1', $saga->name());
        [$a, $c] = $saga->subscriptions();
        self::assertSame(['payment.charge', 'payment.refund'], [$a->topic, $c->topic]);
        self::assertSame([60_000, 2, true], [$a->lockDurationMs, $a->retries, $a->irreversible]);
        self::assertSame([60_000, 2, false], [$c->lockDurationMs, $c->retries, $c->irreversible]);
        self::assertNull($a->variables);
        self::assertNull($c->variables);
    }

    public function testRestrictedVariablesAddTheRecordToTheCompensation(): void
    {
        $saga = self::saga(['amount']);
        self::assertSame(['amount'], $saga->action()->variables);
        self::assertSame(['amount', 'sangamSaga_pay_charge_1'], $saga->compensation()->variables);
    }

    public function testForwardCompletesWithTheRecord(): void
    {
        $saga = self::saga();
        $ctx = $this->ctx('payment.charge', 'op-1');
        $vars = ($saga->action()->handler)($ctx);
        self::assertSame('C1', $vars['chargeId']);
        $record = $vars['sangamSaga_pay_charge_1'];
        self::assertInstanceOf(TypedValue::class, $record);
        self::assertSame(TypedValue::JSON, $record->type);
        self::assertSame(['step' => 'pay.charge-1', 'operationId' => 'op-1', 'providerRef' => 'PAY-77',
            'workId' => 'wk-op-1'], $record->value);
    }

    public function testCompensationReusesTheForwardOperation(): void
    {
        $saga = self::saga();
        $vars = ($saga->action()->handler)($this->ctx('payment.charge', 'op-1'));
        $undoCtx = $this->ctx('payment.refund', 'op-2', self::wire($vars));
        $out = ($saga->compensation()->handler)($undoCtx);
        self::assertSame(['refundKey' => 'op-1:compensate:pay.charge-1', 'refundOf' => 'PAY-77'], $out);
        $record = Saga::recordOf('pay.charge-1', $undoCtx);
        self::assertInstanceOf(SagaRecord::class, $record);
        self::assertSame('op-1', $record->operationId);
        self::assertSame('wk-op-1', $record->workId);
    }

    public function testCompensationWithoutARecordUsesItsOwnOperation(): void
    {
        $seen = null;
        $saga = Saga::step('ship')
            ->action('ship.do', static fn(TaskContext $ctx): ?array => null)
            ->compensation('ship.undo', static function (CompensationContext $c) use (&$seen): array {
                $seen = $c;
                return [];
            })
            ->build();
        ($saga->compensation()->handler)($this->ctx('ship.undo', 'op-9'));
        self::assertInstanceOf(CompensationContext::class, $seen);
        self::assertNull($seen->forward());
        self::assertNull($seen->forwardOperationId());
        self::assertNull($seen->forwardProviderRef());
        self::assertSame('op-9', $seen->idempotencyKey());
        self::assertSame('op-9', $seen->task()->operationId());
        $vars = ($saga->action()->handler)($this->ctx('ship.do', 'op-3'));
        self::assertSame(['sangamSaga_ship'], array_keys($vars));
    }

    public function testRecordGivenAsSerializedJsonIsRead(): void
    {
        $json = (string) json_encode(['step' => 's', 'operationId' => 'op-5', 'providerRef' => null, 'workId' => 'w']);
        $ctx = $this->ctx('t', 'op-6', ['sangamSaga_s' => ['type' => 'Json', 'value' => $json]]);
        self::assertSame('op-5', Saga::recordOf('s', $ctx)?->operationId);
        self::assertNull(Saga::recordOf('s', $this->ctx('t', 'op-6', ['sangamSaga_s' => ['type' => 'Json',
            'value' => ['step' => 's']]])));
    }

    public function testBuildErrors(): void
    {
        foreach (['', str_repeat('a', 65), 'a b', 'a/b'] as $bad) {
            try {
                Saga::step($bad);
                self::fail('expected InvalidArgumentException for ' . $bad);
            } catch (\InvalidArgumentException) {
            }
        }
        try {
            Saga::step('x')->action('t', static fn(TaskContext $c): array => [])->build();
            self::fail('expected LogicException');
        } catch (\LogicException $e) {
            self::assertStringContainsString('needs an action and a compensation', $e->getMessage());
        }
        try {
            Saga::step('x')->action('t', static fn(TaskContext $c): array => [])
                ->compensation('t', static fn(CompensationContext $c): array => [])->build();
            self::fail('expected LogicException');
        } catch (\LogicException $e) {
            self::assertStringContainsString('their own topics', $e->getMessage());
        }
    }

    public function testRegisterOnSubscribesBothTopics(): void
    {
        $topics = [];
        $logger = new CallbackLogger(static function (Level $l, string $event, string $m, array $fields = []) use (&$topics): void {
            if ($event === 'worker.subscribed') {
                $topics[] = $fields['topic'];
            }
        });
        $worker = new SangamWorker($this->client, 'w1', mode: SangamWorker::MODE_INLINE, logger: $logger);
        self::assertSame($worker, self::saga()->registerOn($worker));
        self::assertSame(['payment.charge', 'payment.refund'], $topics);
    }
}
