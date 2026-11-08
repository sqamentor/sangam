// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

import java.util.Set;

/**
 * Which command types require an {@code Idempotency-Key} (X.2.2.1 "Required or optional").
 *
 * <p>The <b>government profile</b> requires it for start, correlate, complete/approve/reject/return, cancel, delete and
 * deployment commands; a missing key gets {@code 400 IDEMPOTENCY_KEY_REQUIRED}. Elsewhere it is optional.
 */
public record IdempotencyProfile(String name, Set<String> requiredCommandTypes, Set<String> requiredPrefixes) {

    public IdempotencyProfile {
        requiredCommandTypes = requiredCommandTypes == null ? Set.of() : Set.copyOf(requiredCommandTypes);
        requiredPrefixes = requiredPrefixes == null ? Set.of() : Set.copyOf(requiredPrefixes);
    }

    public static IdempotencyProfile optional() {
        return new IdempotencyProfile("default", Set.of(), Set.of());
    }

    public static IdempotencyProfile government() {
        return new IdempotencyProfile("government",
                Set.of("process.start", "case.start", "message.correlate", "task.complete", "task.approve", "task.reject",
                        "task.return", "instance.cancel", "instance.delete", "instance.erase"),
                Set.of("deployment."));
    }

    public boolean requires(String commandType) {
        if (requiredCommandTypes.contains(commandType)) {
            return true;
        }
        for (String p : requiredPrefixes) {
            if (commandType.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
