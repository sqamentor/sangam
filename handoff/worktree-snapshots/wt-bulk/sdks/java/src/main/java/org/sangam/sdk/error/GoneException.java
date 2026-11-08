// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.error;

/**
 * 410 {@code GONE_INSTANCE_DELETED} (VI.5, R23): the instance was cancelled/deleted and a tombstone matched.
 * The worker abandons the work item; the server has already audited the late call.
 */
public final class GoneException extends SangamApiException {
    public GoneException(Problem problem, String message) {
        super(410, problem, message);
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
