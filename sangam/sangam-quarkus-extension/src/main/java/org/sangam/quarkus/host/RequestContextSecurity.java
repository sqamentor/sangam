// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

import org.sangam.assembly.host.SecurityContextBinding;

/**
 * Security-context seam of the Quarkus host (design note section 10.1): the principal lives in the ArC request
 * context ({@link BoundPrincipal}). {@link #callAs} activates a request context when none is active (a job, a relay
 * run) and terminates it afterwards; inside an active one (an HTTP request) it binds the principal for the call and
 * restores the previous one afterwards, whatever the outcome. No call reaches a port without the principal bound.
 *
 * @param <P> the product principal type
 */
public final class RequestContextSecurity<P> implements SecurityContextBinding<P> {

    private final BoundPrincipal bound;
    private final Class<P> type;

    public RequestContextSecurity(BoundPrincipal bound, Class<P> type) {
        this.bound = Objects.requireNonNull(bound, "bound");
        this.type = Objects.requireNonNull(type, "type");
    }

    @Override
    public <T> T callAs(P principal, Supplier<T> call) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(call, "call");
        ManagedContext request = Arc.requireContainer().requestContext();
        boolean activated = !request.isActive();
        if (activated) {
            request.activate();
        }
        try {
            Object previous = bound.get();
            bound.set(principal);
            try {
                return call.get();
            } finally {
                bound.set(previous);
            }
        } finally {
            if (activated) {
                request.terminate();
            }
        }
    }

    /** The principal bound in the active request context; empty without one or outside a request context. */
    public Optional<P> current() {
        if (!Arc.requireContainer().requestContext().isActive()) {
            return Optional.empty();
        }
        Object p = bound.get();
        return type.isInstance(p) ? Optional.of(type.cast(p)) : Optional.empty();
    }
}
