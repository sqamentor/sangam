// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.HistoryPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;

/**
 * Decision 144 (integrator ruling 5, W4-M contract 03:26:38Z): GET /audit/events and /audit/verify read a system audit
 * scope only with {@code scope} and platform.audit.system.read; tenant audit.read never reaches it; an ill-formed
 * scope is 400; without scope nothing changes.
 */
class HistoryAuditControllerScopeTest {

    static final RequestContext CALLER = new RequestContext("t1", "asha", Set.of("sangam-platform"), "seat-1", null,
            null, "trace-1", "10.0.0.1", "OIDC");

    /** The audit port records the context of each call. */
    final List<RequestContext> seen = new ArrayList<>();
    final AuditPort audit = (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(),
            new Class<?>[]{AuditPort.class}, (p, m, a) -> {
                if (m.getName().equals("verify")) {
                    seen.add((RequestContext) a[0]);
                    return new AuditPort.VerifyResult(((RequestContext) a[0]).tenantId(), true, Instant.EPOCH, List.of());
                }
                if (m.getName().equals("search")) {
                    seen.add((RequestContext) a[0]);
                    return org.sangam.rest.port.Page.of(List.of(), 0, false);
                }
                throw new UnsupportedOperationException(m.getName());
            });

    /** A permission port holding exactly {@code held}. */
    static PermissionPort holding(String... held) {
        Set<String> s = Set.of(held);
        return new PermissionPort() {
            @Override
            public boolean has(RequestContext ctx, String permission) {
                return s.contains(permission);
            }

            @Override
            public Set<String> permissions(RequestContext ctx) {
                return s;
            }

            @Override
            public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
                return false;
            }
        };
    }

    HistoryAuditController controller(PermissionPort permissions) {
        return new HistoryAuditController((HistoryPort) Proxy.newProxyInstance(HistoryPort.class.getClassLoader(),
                new Class<?>[]{HistoryPort.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                }), audit, permissions);
    }

    @Test
    void withoutScopeTheCallersTenantIsReadWithAuditRead() {
        HistoryAuditController c = controller(holding(Permissions.AUDIT_READ));
        assertEquals("t1", c.verify(CALLER, null, null, null).tenantId());
        c.events(CALLER, null, null, null, null, null, null, null, null);
        assertEquals(List.of(CALLER, CALLER), seen, "the very same context, unchanged");
    }

    @Test
    void aScopeIsReadOnlyWithThePlatformPermission() {
        HistoryAuditController tenantAuditor = controller(holding(Permissions.AUDIT_READ));
        ApiProblem e = assertThrows(ApiProblem.class, () -> tenantAuditor.verify(CALLER, null, null, "system:platform"));
        assertEquals(403, e.status());
        assertThrows(ApiProblem.class, () -> tenantAuditor.events(CALLER, null, null, null, null, null, null, null,
                "system:platform"));
        assertTrue(seen.isEmpty(), "audit.read never reaches a system scope");

        HistoryAuditController platform = controller(holding(Permissions.AUDIT_SYSTEM_READ));
        assertEquals("system:platform", platform.verify(CALLER, null, null, "system:platform").tenantId());
        platform.events(CALLER, null, null, null, null, null, null, null, "system:platform");
        for (RequestContext r : seen) {
            assertEquals("system:platform", r.tenantId());
            assertEquals("asha", r.principalId(), "the caller's identity is kept");
            assertEquals(CALLER.groups(), r.groups());
            assertEquals("trace-1", r.traceId());
        }
        assertEquals(2, seen.size());
    }

    @Test
    void anIllFormedScopeIs400AndAPlatformCallerWithoutScopeStillNeedsAuditRead() {
        HistoryAuditController platform = controller(holding(Permissions.AUDIT_SYSTEM_READ));
        for (String bad : new String[]{"t2", "system:", "system:Platform", "SYSTEM:platform", "system:a b", ""}) {
            ApiProblem e = assertThrows(ApiProblem.class, () -> platform.verify(CALLER, null, null, bad), bad);
            assertEquals(400, e.status(), bad);
            assertEquals("VALIDATION_FAILED", e.code(), bad);
        }
        assertEquals(403, assertThrows(ApiProblem.class, () -> platform.verify(CALLER, null, null, null)).status());
        assertTrue(seen.isEmpty());
    }

    @Test
    void thePermissionIsTheContractString() {
        assertEquals("platform.audit.system.read", Permissions.AUDIT_SYSTEM_READ);
    }
}
