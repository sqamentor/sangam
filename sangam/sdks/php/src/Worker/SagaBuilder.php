<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * Builds one Saga step (M-026): Saga::step($name)->action(...)->compensation(...)->build().
 *
 * The action handler is fn(TaskContext): ?array (like TaskHandler); its result variables are completed together with
 * the record variable. The compensation handler is fn(CompensationContext): ?array; its result variables, errors and
 * outcome-unknown follow TaskHandler.
 */
final class SagaBuilder
{
    private ?string $actionTopic = null;
    private ?\Closure $actionHandler = null;
    private ?string $compensationTopic = null;
    private ?\Closure $compensationHandler = null;
    private int $lockDurationMs = 300_000;
    private int $retries = 3;
    private bool $irreversible = false;
    /** @var list<string>|null */
    private ?array $variables = null;

    public function __construct(private readonly string $name)
    {
        if (preg_match('/^[A-Za-z0-9._-]{1,64}$/D', $name) !== 1) {
            throw new \InvalidArgumentException('saga step name must be 1-64 of [A-Za-z0-9._-]: ' . $name);
        }
    }

    /**
     * The forward action: topic and handler.
     *
     * @param callable(TaskContext): (array<string, mixed>|null) $handler
     */
    public function action(string $topic, callable $handler): self
    {
        $this->actionTopic = $topic;
        $this->actionHandler = \Closure::fromCallable($handler);
        return $this;
    }

    /**
     * The compensation: topic and handler.
     *
     * @param callable(CompensationContext): (array<string, mixed>|null) $handler
     */
    public function compensation(string $topic, callable $handler): self
    {
        $this->compensationTopic = $topic;
        $this->compensationHandler = \Closure::fromCallable($handler);
        return $this;
    }

    /** Lease per fetch for both topics (default 5 min). */
    public function lockDurationMs(int $ms): self
    {
        $this->lockDurationMs = $ms;
        return $this;
    }

    /** Retries for the first failure of either handler (default 3). */
    public function retries(int $retries): self
    {
        $this->retries = $retries;
        return $this;
    }

    /** The forward action has an irreversible effect (VI.3.2 rule 5): unexpected errors become outcome-unknown. */
    public function irreversible(bool $irreversible = true): self
    {
        $this->irreversible = $irreversible;
        return $this;
    }

    /**
     * Variables to fetch for both handlers (null = all); the record variable is always added to the compensation's.
     *
     * @param list<string>|null $variables
     */
    public function variables(?array $variables): self
    {
        $this->variables = $variables === null ? null : array_values($variables);
        return $this;
    }

    public function build(): Saga
    {
        if ($this->actionHandler === null || $this->compensationHandler === null
            || $this->actionTopic === null || $this->compensationTopic === null) {
            throw new \LogicException('saga step ' . $this->name . ' needs an action and a compensation');
        }
        if ($this->actionTopic === $this->compensationTopic) {
            throw new \LogicException('saga step ' . $this->name . ': action and compensation need their own topics');
        }
        $step = $this->name;
        $action = $this->actionHandler;
        $compensation = $this->compensationHandler;
        $forward = new TopicSubscription(
            $this->actionTopic,
            static function (TaskContext $ctx) use ($step, $action): array {
                $vars = $action($ctx);
                if ($vars !== null && !is_array($vars)) {
                    throw new \UnexpectedValueException('saga action must return an array of variables or null');
                }
                return Saga::withRecord($step, $ctx, $vars);
            },
            $this->lockDurationMs,
            $this->variables,
            $this->retries,
            null,
            $this->irreversible,
        );
        $compVars = null;
        if ($this->variables !== null) {
            $compVars = $this->variables;
            $compVars[] = Saga::recordVariable($step);
        }
        $undo = new TopicSubscription(
            $this->compensationTopic,
            static fn(TaskContext $ctx): mixed => $compensation(
                new CompensationContext($ctx, Saga::recordOf($step, $ctx), $step)),
            $this->lockDurationMs,
            $compVars,
            $this->retries,
        );
        return new Saga($step, $forward, $undo);
    }
}
