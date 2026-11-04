<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * Handles one locked work item; a plain callable fn(TaskContext): ?array works as well. Runs only after the
 * LOCKED confirmation (A-1).
 *
 * - return an array (or null): complete with those variables;
 * - throw BpmnError: bpmn-error, routed by the model;
 * - throw OutcomeUnknownException: outcome-unknown, an UNKNOWN_OUTCOME reconciliation item (VI.3.2 rule 4);
 * - throw TaskFailedException: fail with the given or default retries/back-off;
 * - throw TaskCancelledException after a cancel request: cancel-ack (otherwise unlock);
 * - any other Throwable: fail with retries, except on subscriptions marked irreversible, where it is
 *   reported as outcome-unknown (VI.3.2 rule 5: no blind retries of non-idempotent effects).
 *
 * Downstream calls should send $ctx->operationId() as their idempotency key (R36).
 */
interface TaskHandler
{
    /** @return array<string, mixed>|null */
    public function handle(TaskContext $context): ?array;
}
