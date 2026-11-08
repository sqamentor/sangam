// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

/**
 * A command was retried after lock timeouts, deadlocks, optimistic-lock or serialization failures and still did not
 * commit (VI.1 rule 7, R3). REST maps it to {@code 409 CONFLICT_RETRY_EXHAUSTED}. Nothing was committed.
 */
public class ConflictRetryExhaustedException extends RuntimeException {

    private final int attempts;

    public ConflictRetryExhaustedException(String command, int attempts, Throwable last) {
        super("CONFLICT_RETRY_EXHAUSTED: " + command + " did not commit after " + attempts + " attempts", last);
        this.attempts = attempts;
    }

    public int attempts() {
        return attempts;
    }
}
