<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Model;

/**
 * TopicRequest (sangam-api.yaml): one topic of a fetch-and-lock. Only name is required; the server applies its
 * defaults (lease 5 min, all variables) to absent members, and toArray() never sends a null member.
 */
final class TopicRequest
{
    /** @param list<string>|null $variables variables to fetch (null = all) */
    public function __construct(
        public readonly string $name,
        public readonly ?int $lockDurationMs = null,
        public readonly ?array $variables = null,
        public readonly ?bool $localVariables = null,
        public readonly ?string $processDefinitionKey = null,
        public readonly ?string $businessKey = null,
    ) {
    }

    /** @return array<string, mixed> the wire object, without null members */
    public function toArray(): array
    {
        return array_filter([
            'name' => $this->name,
            'lockDurationMs' => $this->lockDurationMs,
            'variables' => $this->variables,
            'localVariables' => $this->localVariables,
            'processDefinitionKey' => $this->processDefinitionKey,
            'businessKey' => $this->businessKey,
        ], static fn(mixed $v): bool => $v !== null);
    }
}
