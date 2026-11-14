// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;

/** Validation and audit helpers shared by the JDBC default adapters. */
final class JdbcSupport {

    private JdbcSupport() {
    }

    static String newId() {
        return Hashes.uuidV7().toString();
    }

    static ApiProblem invalid(String field, String detail) {
        return ApiProblem.badRequest("VALIDATION_FAILED", detail).with("field", field);
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field, field + " is required");
        }
        return value;
    }

    static <T> T requiredObj(T value, String field) {
        if (value == null) {
            throw invalid(field, field + " is required");
        }
        return value;
    }

    static String oneOf(String value, String field, Set<String> allowed) {
        required(value, field);
        if (!allowed.contains(value)) {
            throw invalid(field, field + " must be one of " + allowed);
        }
        return value;
    }

    static void decimal(String value, String field) {
        if (value == null) {
            return;
        }
        try {
            new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw invalid(field, field + " must be a decimal string");
        }
    }

    static boolean isAdmin(RequestContext ctx) {
        return ctx.inGroup(Permissions.GROUP_ADMIN) || ctx.inGroup(Permissions.GROUP_SYSTEM);
    }

    static String tenant(RequestContext ctx) {
        if (ctx.tenantId() == null || ctx.tenantId().isBlank()) {
            throw ApiProblem.forbidden("TENANT_REQUIRED", "The authenticated identity carries no tenant");
        }
        return ctx.tenantId();
    }

    static String securityChain(RequestContext ctx) {
        return "sec:" + tenant(ctx);
    }

    /** Builds an audit payload, dropping null values. */
    static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }

    static void checkRevision(long expected, long actual, String what, String id) {
        if (expected != actual) {
            throw ApiProblem.conflict("REVISION_CONFLICT",
                    what + " " + id + " is at revision " + actual + ", not " + expected).with("currentRevision", actual);
        }
    }
}
