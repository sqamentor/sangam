// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.resources;

import java.util.Map;
import java.util.Set;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.RequestContext;

/**
 * The sangam-security identity of a REST caller for the resources ports (every port method takes it first; the VI.4
 * predicates use its roles and seats). sangam-server binds a resolver that returns the identity resolved for the
 * request (organisation-model seats and roles); the default builds a token identity from the {@link RequestContext}.
 */
@FunctionalInterface
public interface ResourcesIdentity {

    SecurityIdentity of(RequestContext ctx);

    /** Token identity: tenant and principal from the authenticated context, groups as groups and roles. */
    ResourcesIdentity FROM_CONTEXT = ctx -> new SecurityIdentity(ctx.tenantId(), ctx.principalId(), ctx.principalId(),
            ctx.groups(), ctx.groups(), ctx.seatId() == null ? Set.of() : Set.of(ctx.seatId()),
            ctx.actingForSeatId() == null ? Set.of() : Set.of(ctx.actingForSeatId()), ctx.authMethod(), ctx.clientIp(),
            null, false, Map.of());
}
