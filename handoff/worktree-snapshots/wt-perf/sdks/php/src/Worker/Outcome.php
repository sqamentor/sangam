<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * @internal Result of one handler invocation: kind is complete | bpmn-error | outcome-unknown | fail | cancel-ack | unlock.
 */
final class Outcome
{
    /** @param array<string, mixed>|null $variables */
    public function __construct(
        public readonly string $kind,
        public readonly ?array $variables = null,
        public readonly ?\Throwable $error = null,
    ) {
    }
}
