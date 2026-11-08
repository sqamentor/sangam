// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.util.Optional;
import java.util.function.Supplier;

import org.sangam.core.security.SecurityIdentity;

/**
 * Binds a security identity around engine job execution (timers, async continuations, retries, batch jobs), so security
 * checks inside jobs always have a tenant and never run without an identity (VI.4 rule 7: background work runs as the
 * system principal of the tenant and records {@code onBehalfOf}).
 *
 * <p>sangam-security's {@code TenantContext::runAs} has exactly this shape; sangam-server installs it with
 * {@code settings.setJobIdentityBinder(TenantContext::runAs)}. The engine does not depend on sangam-security (it brings
 * Spring Security). Without an installed binder the engine still binds the identity in {@link #current()}, and logs a
 * warning at start-up.
 */
@FunctionalInterface
public interface JobIdentityBinder {

    <T> T runAs(SecurityIdentity identity, Supplier<T> work);

    /** Engine-local binding, always set while a job runs (also when a real binder is installed). */
    ThreadLocal<SecurityIdentity> CURRENT = new ThreadLocal<>();

    /** The identity of the job running on this thread, if any. */
    static Optional<SecurityIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Fallback binder: only the engine-local binding. */
    JobIdentityBinder LOCAL_ONLY = new JobIdentityBinder() {
        @Override
        public <T> T runAs(SecurityIdentity identity, Supplier<T> work) {
            return work.get();
        }
    };
}
