// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.util.Optional;

/**
 * Outcome lookup for a step whose earlier attempt left an {@code INTENT} (capability classes (a) and (b), R36/R37):
 * ask the remote system, by {@link Step#idempotencyKey()}, whether the call took effect.
 *
 * <ul>
 *   <li>A value: the call took effect; it is recorded {@code SUCCEEDED} and returned without calling again.</li>
 *   <li>Empty: the call certainly did not take effect; the step runs again.</li>
 *   <li>An exception: the outcome is still unknown; the step stays {@code INTENT} and the exception propagates.</li>
 * </ul>
 */
@FunctionalInterface
public interface StepLookup<T> {
    Optional<T> lookup(Step step) throws Exception;
}
