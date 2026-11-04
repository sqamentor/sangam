<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * Handler registration for one topic (BPMN operaton:topic).
 * lockDurationMs: lease per fetch (VI.3.2 rule 3: default 5 min, max 24 h); heartbeat at lease/3.
 * retries: retries for the first failure when the item carries none. irreversible: treat unexpected handler
 * errors as outcome-unknown (sangam-api.yaml 0.1.0 has no per-item effect flag, so this is the only source).
 * localVariables / processDefinitionKey / businessKey narrow the fetch (TopicRequest).
 */
final class TopicSubscription
{
    public const MAX_LOCK_MS = 86_400_000;

    public readonly \Closure $handler;
    public readonly Backoff $retryBackoff;

    /** @param list<string>|null $variables variables to fetch (null = all) */
    public function __construct(
        public readonly string $topic,
        TaskHandler|callable $handler,
        public readonly int $lockDurationMs = 300_000,
        public readonly ?array $variables = null,
        public readonly int $retries = 3,
        ?Backoff $retryBackoff = null,
        public readonly bool $irreversible = false,
        public readonly bool $localVariables = false,
        public readonly ?string $processDefinitionKey = null,
        public readonly ?string $businessKey = null,
    ) {
        if ($lockDurationMs > self::MAX_LOCK_MS) {
            throw new \InvalidArgumentException('lockDurationMs exceeds 24 h (VI.3)');
        }
        if ($lockDurationMs < 3000) {
            throw new \InvalidArgumentException('lockDurationMs must be at least 3 s');
        }
        if ($retries < 0) {
            throw new \InvalidArgumentException('retries must be >= 0');
        }
        $this->handler = $handler instanceof TaskHandler
            ? \Closure::fromCallable([$handler, 'handle'])
            : \Closure::fromCallable($handler);
        $this->retryBackoff = $retryBackoff ?? Backoff::retryDefaults();
    }

    /** TopicRequest body for fetch-and-lock. @return array<string, mixed> */
    public function toTopicRequest(): array
    {
        $t = ['name' => $this->topic, 'lockDurationMs' => $this->lockDurationMs];
        if ($this->variables !== null) {
            $t['variables'] = $this->variables;
        }
        if ($this->localVariables) {
            $t['localVariables'] = true;
        }
        if ($this->processDefinitionKey !== null) {
            $t['processDefinitionKey'] = $this->processDefinitionKey;
        }
        if ($this->businessKey !== null) {
            $t['businessKey'] = $this->businessKey;
        }
        return $t;
    }
}
