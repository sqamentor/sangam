// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.util.function.Supplier;

import org.sangam.core.security.SecurityIdentity;

/**
 * Binds a security identity around engine job execution (timers, async continuations, retries, batch jobs), so security
 * checks inside jobs always have a tenant and never run without an identity (VI.4 rule 7: background work runs as the
 * system principal of the tenant and records {@code onBehalfOf}).
 *
 * <p>sangam-security's {@code TenantContext::runAs} has exactly this shape; sangam-server installs it with
 * {@code settings.setJobIdentityBinder(TenantContext::runAs)}. The engine does not depend on sangam-security (it brings
 * Spring Security). The identity is passed explicitly: the engine hands it to {@link #runAs} and also attaches it to the
 * job's own engine command (sangam-engine {@code JobIdentitySupport.current()}), so this core port holds no
 * thread-bound state (M-097, SL-151). Without an installed binder the engine binds only that command-scoped identity
 * and logs a warning at start-up.
 */
@FunctionalInterface
public interface JobIdentityBinder {

    <T> T runAs(SecurityIdentity identity, Supplier<T> work);

    /** Fallback binder: only the engine's command-scoped binding. */
    JobIdentityBinder LOCAL_ONLY = new JobIdentityBinder() {
        @Override
        public <T> T runAs(SecurityIdentity identity, Supplier<T> work) {
            return work.get();
        }
    };
}
