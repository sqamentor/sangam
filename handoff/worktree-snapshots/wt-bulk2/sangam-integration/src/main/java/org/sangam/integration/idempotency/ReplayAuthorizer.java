// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

/**
 * Replay re-checks (X.2.2.4 step 4, T59), implemented by WS-SECURITY: before a stored response is returned, the caller
 * must still hold the command permission on the target and READ on {@code resourceRef} (object check, no cache), and
 * the current field masks are re-applied to the stored body.
 */
public interface ReplayAuthorizer {

    /** Object check without cache. */
    boolean mayReplay(String tenantId, String principalId, String commandType, String resourceRef);

    /** Applies the current field masks (VI.4 rule 4) to a stored response body. */
    byte[] mask(String tenantId, String principalId, String resourceRef, byte[] storedBody);

    /** Development only: allows every replay, no masking. */
    ReplayAuthorizer ALLOW_ALL = new ReplayAuthorizer() {
        @Override
        public boolean mayReplay(String tenantId, String principalId, String commandType, String resourceRef) {
            return true;
        }

        @Override
        public byte[] mask(String tenantId, String principalId, String resourceRef, byte[] storedBody) {
            return storedBody;
        }
    };
}
