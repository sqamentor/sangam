<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Model;

/**
 * WorkOperation (sangam-api.yaml; SL-16, R37): the operation-ledger entry of a leased work item, recorded by the
 * server when it leased the work. mayDispatch false with state SUCCEEDED: the downstream effect already happened;
 * complete from storedOutcome {output, providerRef, duplicate} without calling it again. attempts > 1 is a repeat
 * attempt (class B: look the outcome up first). No member is required on the wire, so every property is nullable.
 */
final class WorkOperation
{
    /** @param array<string, mixed>|null $storedOutcome */
    public function __construct(
        /** INTENT | SUCCEEDED | FAILED | UNKNOWN_OUTCOME */
        public readonly ?string $state = null,
        public readonly ?int $attempts = null,
        /** A_IDEMPOTENT_WITH_LOOKUP | B_LOOKUP_ONLY | C_NEITHER */
        public readonly ?string $capabilityClass = null,
        public readonly ?bool $mayDispatch = null,
        public readonly ?array $storedOutcome = null,
    ) {
    }

    /** @param array<string, mixed> $d */
    public static function fromArray(array $d): self
    {
        return new self(
            isset($d['state']) && is_string($d['state']) ? $d['state'] : null,
            isset($d['attempts']) && is_numeric($d['attempts']) ? (int) $d['attempts'] : null,
            isset($d['capabilityClass']) && is_string($d['capabilityClass']) ? $d['capabilityClass'] : null,
            isset($d['mayDispatch']) && is_bool($d['mayDispatch']) ? $d['mayDispatch'] : null,
            isset($d['storedOutcome']) && is_array($d['storedOutcome']) ? $d['storedOutcome'] : null,
        );
    }

    public function succeeded(): bool
    {
        return $this->state === 'SUCCEEDED';
    }
}
