// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import jakarta.enterprise.context.RequestScoped;

/**
 * The principal bound for one request (ArC request context); written only by {@link RequestContextSecurity}.
 */
@RequestScoped
public class BoundPrincipal {

    private Object principal;

    /** The bound principal, or {@code null} when none is bound in this request. */
    public Object get() {
        return principal;
    }

    /** Public because a client proxy must reach it; only {@link RequestContextSecurity#callAs} calls it. */
    public void set(Object principal) {
        this.principal = principal;
    }
}
