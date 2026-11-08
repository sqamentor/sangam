// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.error;

/**
 * 409 {@code stale-lease} (VI.3.2 rule 2, R20): the lockVersion we sent is no longer the current one, so the
 * server changed nothing. The worker must abandon the work item; another attempt now owns it.
 */
public final class StaleLeaseException extends SangamApiException {
    public StaleLeaseException(Problem problem, String message) {
        super(409, problem, message);
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
