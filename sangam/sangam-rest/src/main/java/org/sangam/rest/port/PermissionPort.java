// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.Set;

/**
 * Endpoint-level permission checks and the R41 replay re-check (X.2.2.4 step 4). Object-level checks (VI.4 rules 1–5)
 * are made by the adapters inside the command transaction. Implemented by sangam-security; the default adapter maps
 * groups to permissions.
 */
public interface PermissionPort {

    /** Permission names, e.g. {@code ops.write}; see {@code org.sangam.rest.support.Permissions}. */
    boolean has(RequestContext ctx, String permission);

    Set<String> permissions(RequestContext ctx);

    /** The caller still holds the command permission and READ on the stored resource (no cache). */
    boolean mayReplay(RequestContext ctx, String commandType, String resourceRef);

    default void require(RequestContext ctx, String permission) {
        if (!has(ctx, permission)) {
            throw ApiProblem.notPermitted(permission);
        }
    }
}
