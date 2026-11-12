// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

/**
 * The side-effecting body of one durable step ({@link TaskContext#step}). Send {@link Step#idempotencyKey()} to the
 * remote API as its idempotency key, and report the provider's reference with {@link Step#providerRef(String)}.
 *
 * <ul>
 *   <li>Return normally: the step is recorded {@code SUCCEEDED} with the returned value; later attempts replay it.</li>
 *   <li>Throw {@link StepNoEffectException}: the call certainly had no effect (refused before it was sent, a business
 *       rejection); the step is recorded {@code FAILED} and may run again on a later attempt.</li>
 *   <li>Throw anything else: the outcome is uncertain, the step stays {@code INTENT}; a later attempt looks the
 *       outcome up ({@link StepOptions#lookup}), runs again only for an idempotent call
 *       ({@link StepOptions#idempotent}), or reports outcome-unknown (R37: never a blind re-issue).</li>
 * </ul>
 */
@FunctionalInterface
public interface StepCall<T> {
    T run(Step step) throws Exception;
}
