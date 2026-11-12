// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.engine.deploy.TenantIsolationException;
import org.sangam.rest.port.ApiProblem;

/**
 * SL-45: the 403 of a tenant isolation refusal for a shared definition (no target tenant, e.g. a deployment without a
 * tenant by an identity of one tenant). {@link ApiProblem} copies its extensions with {@code Map.copyOf}, so the null
 * {@code targetTenant} made building the problem throw: {@code /api} answered 500, and on {@code /engine-rest} Jersey
 * logged "exception mapper failure" and the request ended as 500 (then 401 on the error dispatch).
 */
class ProblemHandlerIsolationTest {

    @Test
    void aRefusalWithoutATargetTenantIsStillA403WithItsCodeAndMessage() {
        TenantIsolationException refused = new TenantIsolationException("deployment 'x'", "tenant-a", null);
        ApiProblem p = ProblemHandler.tenantRefusal(new IllegalStateException("wrapped by the engine", refused));
        assertNotNull(p);
        assertEquals(403, p.status());
        assertEquals(TenantIsolationException.CODE, p.code());
        assertTrue(p.detail().startsWith("SL-13 tenant isolation"), p.detail());
        assertEquals(Map.of("element", "deployment 'x'", "tenant", "tenant-a"), p.extensions(),
                "a member without a value is left out");
    }

    @Test
    void aRefusalWithATargetTenantNamesIt() {
        ApiProblem p = ProblemHandler.isolationProblem(new TenantIsolationException("callActivity c", "tenant-a",
                "tenant-b"));
        assertEquals(Map.of("element", "callActivity c", "tenant", "tenant-a", "targetTenant", "tenant-b"),
                p.extensions());
    }
}
