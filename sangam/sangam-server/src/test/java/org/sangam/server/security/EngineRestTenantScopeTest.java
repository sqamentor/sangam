// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** SL-73: the fail-closed allowlist of {@code /engine-rest} resources for a tenant identity. */
class EngineRestTenantScopeTest {

    @Test
    void tenantScopedFamiliesAreAllowed() {
        for (String p : List.of("/task", "/task/abc/variables", "/task/create", "/process-instance", "/process-definition/key/k/start",
                "/deployment/create", "/execution/x", "/variable-instance", "/job", "/job-definition", "/incident", "/batch",
                "/message", "/signal", "/external-task/fetchAndLock", "/migration/generate", "/modification/execute",
                "/case-instance", "/decision-definition/d/evaluate", "/event-subscription", "/history/process-instance",
                "/history/task", "/history/user-operation", "/history/detail", "/engine", "/engine/default/task")) {
            assertTrue(EngineRestTenantScope.allowed(p), p);
        }
    }

    @Test
    void resourcesWithoutATenantOrOfTheWholeEngineAreRefused() {
        for (String p : List.of("", "/", "/authorization", "/authorization/check", "/filter", "/filter/f1/list",
                "/user", "/user/u/profile", "/group", "/tenant", "/identity/groups", "/metrics", "/telemetry/data",
                "/schema/log", "/history/cleanup", "/history/cleanup/configuration", "/history/process-definition/x/statistics",
                "/history/process-definition/cleanable-process-instance-report", "/history/batch/cleanable-batch-report",
                "/deployment/registered", "/task/report/candidate-group-count", "/external-task/topic-names",
                "/condition", "/engine/default", "/engine/default/authorization", "/version", "/unknown")) {
            assertFalse(EngineRestTenantScope.allowed(p), p);
        }
    }

    @Test
    void encodedOrAmbiguousPathsAreDecodedOrRefused() {
        assertFalse(EngineRestTenantScope.allowed("/au%74horization"), "decoded before matching, as Jersey routes it");
        assertTrue(EngineRestTenantScope.allowed("/%74ask"), "decoded before matching");
        assertFalse(EngineRestTenantScope.allowed("/task/../authorization"));
        assertFalse(EngineRestTenantScope.allowed("/task;x=1"));
        assertFalse(EngineRestTenantScope.allowed("/task?x=1"));
        assertFalse(EngineRestTenantScope.allowed("/ta sk"), "not a valid URI path");
        assertFalse(EngineRestTenantScope.allowed("/%zz"), "not a valid escape");
    }
}
