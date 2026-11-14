// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.util.Objects;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Security-context seam of the Quarkus host (design note section 10.1, decision 279): {@link RequestContextSecurity} on
 * the ArC request context ({@link BoundPrincipal}). An optional bean: without it (an operator's
 * {@code quarkus.arc.exclude-types}) no call could reach a port with a bound principal, so {@code security-context} is
 * missing and the start is refused.
 */
@Singleton
public class QuarkusSecurityContext {

    private final RequestContextSecurity<Object> binding;

    @Inject
    public QuarkusSecurityContext(BoundPrincipal bound) {
        this.binding = new RequestContextSecurity<>(Objects.requireNonNull(bound, "bound"), Object.class);
    }

    public RequestContextSecurity<Object> binding() {
        return binding;
    }
}
