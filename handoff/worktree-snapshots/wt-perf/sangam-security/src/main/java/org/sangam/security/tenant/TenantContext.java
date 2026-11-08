// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.tenant;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The identity of the current request or job, bound to the thread for the duration of one call. It is set by the
 * security filter (HTTP), by the worker API (service principal, R26) and by job execution (system principal with
 * {@code onBehalfOf}, VI.4 rule 7). Engine listeners and the tenant id provider read it. Virtual threads are fine:
 * the value is bound and cleared around one unit of work, never pooled.
 */
public final class TenantContext {

    private static final ThreadLocal<SecurityIdentity> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<SecurityIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static SecurityIdentity require() {
        SecurityIdentity id = CURRENT.get();
        if (id == null) {
            throw new SangamSecurityException.Unauthenticated("UNAUTHENTICATED", "no authenticated identity bound");
        }
        return id;
    }

    public static void set(SecurityIdentity identity) {
        CURRENT.set(identity);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code work} with {@code identity} bound, restoring the previous binding afterwards. */
    public static <T> T runAs(SecurityIdentity identity, Supplier<T> work) {
        SecurityIdentity previous = CURRENT.get();
        CURRENT.set(identity);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
