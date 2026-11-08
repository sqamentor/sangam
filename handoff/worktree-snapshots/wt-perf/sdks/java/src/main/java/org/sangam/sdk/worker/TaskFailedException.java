// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.time.Duration;

/**
 * A technical failure where the handler asserts that no external effect happened, so a retry is safe.
 * {@code retries} / {@code retryTimeout} override the subscription defaults when not null; {@code retries = 0}
 * raises an incident (VI.3.1 fail(retries=0) → INCIDENT).
 */
public class TaskFailedException extends Exception {

    private final String details;
    private final Integer retries;
    private final Duration retryTimeout;

    public TaskFailedException(String message) {
        this(message, null, null, null, null);
    }

    public TaskFailedException(String message, Throwable cause) {
        this(message, null, null, null, cause);
    }

    public TaskFailedException(String message, String details, Integer retries, Duration retryTimeout, Throwable cause) {
        super(message, cause);
        this.details = details;
        this.retries = retries;
        this.retryTimeout = retryTimeout;
    }

    public String details() { return details; }
    public Integer retries() { return retries; }
    public Duration retryTimeout() { return retryTimeout; }
}
