// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.sql.Connection;
import java.util.UUID;

import org.sangam.core.api.ExpiryPolicy;

/**
 * An {@link ExpiryPolicy} (A-10) that also receives the business identity of the expired work: its operationId
 * (T35/R36), the declared capability class (A-2/A-8) and the tenant. The engine adapter calls
 * {@link #onExpired(ExpiryPolicy.ExpiredWork, Context, Connection)} when the policy implements this interface.
 */
public interface OperationAwareExpiryPolicy extends ExpiryPolicy {

    /**
     * @param capabilityClass the topic/activity declaration ({@code a}, {@code b}, {@code c}, {@code irreversible});
     *                        null when undeclared (A-8 default: class c)
     */
    record Context(String tenantId, String businessKey, String rootId, UUID operationId, String capabilityClass,
                   int attemptNo) {
    }

    Decision onExpired(ExpiredWork work, Context context, Connection connection);

    @Override
    default Decision onExpired(ExpiredWork work, Connection connection) {
        return onExpired(work, new Context(null, null, null, null, null, 0), connection);
    }
}
