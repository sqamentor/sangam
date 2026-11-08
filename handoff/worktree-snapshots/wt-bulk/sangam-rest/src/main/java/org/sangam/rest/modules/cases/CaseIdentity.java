// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.cases;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.sangam.casefile.rest.CaseApi;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.ports.support.Paging;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Caller identity, endpoint permissions and paging shared by the case-module controllers (N04/N16). The VI.4 object
 * checks run inside the module ({@code CaseRestAdapter} with {@code SecurityPorts.Authorization}); the endpoint gate
 * here is the coarse REST permission ({@link #READ}/{@link #WRITE}/{@link #ADMIN}).
 */
public final class CaseIdentity {

    public static final String READ = "case.read";
    public static final String WRITE = "case.write";
    public static final String ADMIN = "case.admin";

    /** Request attribute holding the sangam-security resolved identity (seats, roles); see the server filter. */
    static final String RESOLVED = SecurityIdentity.class.getName();

    private CaseIdentity() {
    }

    /** The authenticated caller after the endpoint permission check. */
    static CaseApi.ApiContext require(PermissionPort permissions, RequestContext ctx, String permission) {
        permissions.require(ctx, permission);
        return new CaseApi.ApiContext(identity(ctx));
    }

    /**
     * The sangam-security resolved identity (postings, acting-for seats, roles) when the security module is bound;
     * otherwise one built from the request context. The tenant is the request context's (VI.4 rule 6).
     */
    public static SecurityIdentity identity(RequestContext ctx) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && attrs.getAttribute(RESOLVED, RequestAttributes.SCOPE_REQUEST) instanceof SecurityIdentity id
                && ctx.tenantId().equals(id.tenantId())) {
            return id;
        }
        return new SecurityIdentity(ctx.tenantId(), ctx.principalId(), ctx.principalId(), ctx.groups(), Set.of(),
                ctx.seatId() == null ? Set.of() : Set.of(ctx.seatId()),
                ctx.actingForSeatId() == null ? Set.of() : Set.of(ctx.actingForSeatId()), ctx.authMethod(),
                ctx.clientIp(), null, false, Map.of());
    }

    static Paging.PageRequest page(Integer page, Integer size) {
        Page.PageRequest p = Page.PageRequest.of(page, size);
        return new Paging.PageRequest(p.page(), Math.min(p.size(), Paging.MAX_PAGE));
    }

    static <T> Page<T> page(Paging.PageResult<T> r) {
        return Page.of(r.items(), r.total(), r.capped());
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String value, String param) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", param + " must be one of "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiProblem.badRequest("REASON_REQUIRED", "This action requires a reason (R42)");
        }
        return reason;
    }

    static Instant at(Instant value) {
        return value == null ? Instant.now() : value;
    }
}
