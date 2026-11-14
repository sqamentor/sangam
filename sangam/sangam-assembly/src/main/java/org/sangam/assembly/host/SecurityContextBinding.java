// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.function.Supplier;

/**
 * Security-context seam (design note section 10.1). The host turns its authenticated request (token, session, mTLS)
 * into the product principal and binds it for the duration of one call only. Every authorization decision, tenant
 * resolution and the engine security plugin stay in the product; a host never decides a permission.
 *
 * @param <P> the product principal type the host binds (the REST layer's principal for an HTTP host)
 */
@FunctionalInterface
public interface SecurityContextBinding<P> {

    /** Runs {@code call} with {@code principal} bound, and unbinds it afterwards whatever the outcome. */
    <T> T callAs(P principal, Supplier<T> call);
}
