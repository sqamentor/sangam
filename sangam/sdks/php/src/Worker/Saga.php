<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

use Sangam\Sdk\Model\TypedValue;

/**
 * Saga and compensation worker helper (M-026, §3.2, R36): registers one forward action and its compensation as two
 * external-task handlers, and carries the forward action's identity to the compensation.
 *
 * In the model, the forward step is a service task with topic $actionTopic, and its compensation handler (attached
 * through a compensation boundary event, isForCompensation="true") is a service task with topic $compensationTopic.
 * When the forward action completes, this helper adds the process variable recordVariable($name) holding a
 * SagaRecord (the forward operationId, provider reference and work id) as an R33 Json value. When the engine
 * compensates, the compensation handler gets that record in its CompensationContext and reuses the forward
 * operationId: the undo call's idempotency key is derived from it, so compensating the same forward action twice
 * undoes it once.
 *
 * One record per step name and process scope: a step repeated in a loop or multi-instance body needs its own name
 * per iteration, or a compensation that reads the iteration's data from local variables.
 *
 *     Saga::step('charge')
 *         ->action('payment.charge', fn(TaskContext $ctx): array => ['chargeId' => charge($ctx)])
 *         ->compensation('payment.refund', function (CompensationContext $c): array {
 *             refund($c->forwardProviderRef(), $c->idempotencyKey());
 *             return [];
 *         })
 *         ->build()
 *         ->registerOn($worker);
 */
final class Saga
{
    /** Prefix of the record variables. */
    public const RECORD_PREFIX = 'sangamSaga_';

    /** @internal created by SagaBuilder::build() */
    public function __construct(
        private readonly string $name,
        private readonly TopicSubscription $action,
        private readonly TopicSubscription $compensation,
    ) {
    }

    /** Starts a saga step named $name (letters, digits, '.', '-', '_'; at most 64 characters). */
    public static function step(string $name): SagaBuilder
    {
        return new SagaBuilder($name);
    }

    /** The process variable that holds the record of step $name: sangamSaga_<name with '.' and '-' as '_'>. */
    public static function recordVariable(string $name): string
    {
        return self::RECORD_PREFIX . str_replace(['.', '-'], '_', $name);
    }

    public function name(): string
    {
        return $this->name;
    }

    /** The forward action's subscription. */
    public function action(): TopicSubscription
    {
        return $this->action;
    }

    /** The compensation's subscription. */
    public function compensation(): TopicSubscription
    {
        return $this->compensation;
    }

    /** @return list<TopicSubscription> both subscriptions, forward action first */
    public function subscriptions(): array
    {
        return [$this->action, $this->compensation];
    }

    /** Subscribes both handlers on $worker. */
    public function registerOn(SangamWorker $worker): SangamWorker
    {
        $worker->subscribe($this->action);
        return $worker->subscribe($this->compensation);
    }

    /**
     * The forward handler's variables plus the step's record (R33 Json typed value).
     *
     * @internal
     * @param array<string, mixed>|null $vars
     * @return array<string, mixed>
     */
    public static function withRecord(string $step, TaskContext $ctx, ?array $vars): array
    {
        $out = $vars ?? [];
        $record = new SagaRecord($step, $ctx->operationId(), $ctx->outcomeReference(), $ctx->workId());
        $out[self::recordVariable($step)] = TypedValue::json($record->toArray());
        return $out;
    }

    /**
     * The record of $step from the compensation work's variables, or null when absent.
     *
     * @internal
     */
    public static function recordOf(string $step, TaskContext $ctx): ?SagaRecord
    {
        $raw = $ctx->variables()[self::recordVariable($step)] ?? null;
        if ($raw === null) {
            return null;
        }
        $value = TypedValue::unwrap($raw);
        if (is_string($value)) {
            // a Json value the engine handed back as serialized text
            $value = json_decode($value, true, 512, JSON_BIGINT_AS_STRING);
        }
        return is_array($value) ? SagaRecord::fromArray($value) : null;
    }
}
