// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import java.util.Objects;
import java.util.Optional;

import org.sangam.integration.lifecycle.LifecyclePorts;

/**
 * The authorizer of the instance lifecycle and erasure services (extraction phase 3, blocker 4; owner decision 374).
 * The maintenance sweeps, the connector worker and the lifecycle commands all run through those services, so the
 * assembly never builds them without a permission check from a bound security module: a host whose security module
 * is not bound is refused by name ({@value #CODE}), and there is no allow-everything authorizer in the assembly.
 *
 * <p>The Spring host's {@code IntegrationWiring.sangamLifecycleAuthorizer} still keeps its own no-op for a server
 * without sangam-security (the REST layer's endpoint permissions are its only check); that rule is not moved here,
 * and no other host may rely on it.
 */
public final class LifecycleAuthorization {

    /** The stable code of the refusal (decision 374). */
    public static final String CODE = "SECURITY_MODULE_NOT_BOUND";

    private LifecycleAuthorization() {
    }

    /**
     * The security module's authorizer.
     *
     * @param hostName the host, named in the refusal
     * @param bound    the authorizer of the bound security module, empty when none is bound
     * @throws IllegalStateException starting with {@value #CODE} when no security module is bound
     */
    public static LifecyclePorts.Authorizer require(String hostName, Optional<LifecyclePorts.Authorizer> bound) {
        Objects.requireNonNull(hostName, "hostName");
        Objects.requireNonNull(bound, "bound");
        return bound.orElseThrow(() -> new IllegalStateException(CODE + ": the " + hostName + " host has no security"
                + " module bound, so the instance lifecycle, erasure and maintenance services have no permission check;"
                + " the start is refused (decision 374: never an allow-everything authorizer)"));
    }
}
