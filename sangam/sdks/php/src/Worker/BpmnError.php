<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/** A business error that the BPMN model routes (error boundary event). Not retried. */
class BpmnError extends \Exception
{
    /** @param array<string, mixed>|null $variables */
    public function __construct(
        public readonly string $errorCode,
        string $message = '',
        public readonly ?array $variables = null,
        ?\Throwable $previous = null,
    ) {
        parent::__construct($message, 0, $previous);
    }
}
