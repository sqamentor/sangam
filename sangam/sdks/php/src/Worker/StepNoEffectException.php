<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * Thrown by a durable step's body (TaskContext::step()) when the remote call certainly had no effect (it was refused
 * before it was sent, or the remote system rejected it as a business failure). The step is recorded FAILED with the
 * message, so a later attempt may run it again; the exception then propagates to the handler (R37).
 */
class StepNoEffectException extends \Exception
{
    public function __construct(string $message, ?\Throwable $previous = null)
    {
        parent::__construct($message, 0, $previous);
    }
}
