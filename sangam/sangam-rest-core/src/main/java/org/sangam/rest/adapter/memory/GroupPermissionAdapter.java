// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.memory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;

/**
 * {@link PermissionPort} that maps the caller's groups to endpoint permissions
 * ({@link Permissions#DEFAULT_GROUP_PERMISSIONS} by default); {@code sangam-admin} holds every permission ({@code *}).
 * <b>Development default; replaced by sangam-security</b>, which evaluates the VI.4 policy (deny wins, A-13) and,
 * for replays, the uncached object check (X.2.2.4 step 4).
 */
public final class GroupPermissionAdapter implements PermissionPort {

    public static final String ALL = "*";

    private final Map<String, Set<String>> groupPermissions;

    public GroupPermissionAdapter(Map<String, Set<String>> groupPermissions) {
        this.groupPermissions = Map.copyOf(groupPermissions);
    }

    public GroupPermissionAdapter() {
        this(Permissions.DEFAULT_GROUP_PERMISSIONS);
    }

    @Override
    public boolean has(RequestContext ctx, String permission) {
        Set<String> p = permissions(ctx);
        return p.contains(ALL) || p.contains(permission);
    }

    @Override
    public Set<String> permissions(RequestContext ctx) {
        if (ctx == null) {
            return Set.of();
        }
        if (ctx.inGroup(Permissions.GROUP_ADMIN)) {
            return Set.of(ALL);
        }
        Set<String> out = new HashSet<>();
        for (String g : ctx.groups()) {
            Set<String> p = groupPermissions.get(g);
            if (p != null) {
                out.addAll(p);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * Idempotency claims are scoped by tenant, so a replay is offered only inside the caller's tenant; the caller must
     * still hold at least one permission (a fully revoked principal gets 403 without the stored body).
     */
    @Override
    public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
        return ctx != null && ctx.tenantId() != null && !permissions(ctx).isEmpty();
    }
}
