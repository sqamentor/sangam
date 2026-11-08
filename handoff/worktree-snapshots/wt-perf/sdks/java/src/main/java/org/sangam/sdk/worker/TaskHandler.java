// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.util.Map;

/**
 * Handles one locked work item. Runs on its own virtual thread, only after the LOCKED confirmation (A-1).
 *
 * <ul>
 *   <li>Return normally: the worker sends {@code complete} with the returned variables (null = none).</li>
 *   <li>Throw {@link BpmnError}: {@code bpmn-error}, routed by the model.</li>
 *   <li>Throw {@link OutcomeUnknownException}: {@code outcome-unknown}, opening an UNKNOWN_OUTCOME
 *       reconciliation item (VI.3.2 rule 4). Use it when a remote call timed out after it may have taken effect.</li>
 *   <li>Throw {@link TaskFailedException}: {@code fail} with the given or the default retries/back-off.</li>
 *   <li>Throw {@link TaskCancelledException} after {@link TaskContext#isCancelRequested()}: {@code cancel-ack}.</li>
 *   <li>Any other exception: {@code fail} with retries, except on {@code effect=irreversible} topics where it is
 *       reported as {@code outcome-unknown} (VI.3.2 rule 5: no blind retries of non-idempotent effects).</li>
 * </ul>
 *
 * Downstream calls should send {@link TaskContext#operationId()} as their idempotency key (R36).
 */
@FunctionalInterface
public interface TaskHandler {
    Map<String, Object> handle(TaskContext context) throws Exception;
}
