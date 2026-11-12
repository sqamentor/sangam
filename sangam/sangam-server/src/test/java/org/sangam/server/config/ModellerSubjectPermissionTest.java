// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.modeller.action.ActionModels;
import org.sangam.rest.modules.modeller.ModellerRequestScope;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.CasePort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * SL-166 f3 / owner decision 64: an action runs only when its caller holds the permission AND can open the subject of the
 * action's scope through the port the UI reads it with; unknown or hidden subjects (404 / 403) are "not permitted", other
 * failures are not swallowed, and the subject is never looked up for a caller without the permission.
 */
class ModellerSubjectPermissionTest {

    static final RequestContext ALICE = new RequestContext("T1", "alice", Set.of("users"), null, null, null, null, null, "token");

    final PermissionPort permissions = mock(PermissionPort.class);
    final CasePort cases = mock(CasePort.class);
    final ProcessPort processes = mock(ProcessPort.class);
    final TaskPort tasks = mock(TaskPort.class);

    ModellerWiring.SubjectPermission permission(boolean withCasePort) {
        Map<String, Object> beans = new HashMap<>(Map.of("processes", processes, "tasks", tasks));
        if (withCasePort) {
            beans.put("cases", cases);
        }
        StaticListableBeanFactory f = new StaticListableBeanFactory(beans);
        return new ModellerWiring.SubjectPermission(permissions, f.getBeanProvider(CasePort.class),
                f.getBeanProvider(ProcessPort.class), f.getBeanProvider(TaskPort.class));
    }

    boolean ask(ModellerWiring.SubjectPermission p, ActionModels.Scope scope, String ref) {
        return ModellerRequestScope.with(ALICE, () -> p.allowed("T1", "alice", "case.escalate", scope, ref));
    }

    @Test
    void eachScopeChecksItsOwnSubject() {
        when(permissions.has(ALICE, "case.escalate")).thenReturn(true);
        when(cases.get(ALICE, "c1")).thenReturn(mock(CasePort.CaseInstance.class));
        when(processes.get(ALICE, "p1")).thenReturn(mock(ProcessPort.ProcessInstance.class));
        when(tasks.get(ALICE, "t1")).thenReturn(mock(TaskPort.TaskDetail.class));
        ModellerWiring.SubjectPermission p = permission(true);

        assertTrue(ask(p, ActionModels.Scope.CASE, "c1"));
        assertTrue(ask(p, ActionModels.Scope.PROCESS, "p1"));
        assertTrue(ask(p, ActionModels.Scope.TASK, "t1"));
        verify(cases).get(ALICE, "c1");
        verify(processes).get(ALICE, "p1");
        verify(tasks).get(ALICE, "t1");
    }

    @Test
    void anUnknownOrHiddenSubjectIsNotPermitted() {
        when(permissions.has(ALICE, "case.escalate")).thenReturn(true);
        when(cases.get(ALICE, "other-tenant")).thenThrow(ApiProblem.notFound("case", "other-tenant"));
        when(tasks.get(ALICE, "not-mine")).thenThrow(ApiProblem.forbidden("TASK_NOT_VISIBLE", "not a candidate"));
        ModellerWiring.SubjectPermission p = permission(true);

        assertFalse(ask(p, ActionModels.Scope.CASE, "other-tenant"));
        assertFalse(ask(p, ActionModels.Scope.TASK, "not-mine"));
        assertFalse(ask(p, ActionModels.Scope.CASE, " "), "a blank subject is never permitted");
        assertFalse(ask(p, null, "c1"), "neither is a missing scope");
    }

    @Test
    void withoutThePermissionTheSubjectIsNeverLookedUp() {
        when(permissions.has(any(), anyString())).thenReturn(false);
        ModellerWiring.SubjectPermission p = permission(true);
        assertFalse(ask(p, ActionModels.Scope.CASE, "c1"));
        verify(cases, never()).get(any(), anyString());
        assertFalse(p.allowed("T1", "alice", "case.escalate", ActionModels.Scope.CASE, "c1"), "no request scope, no permission");
        assertFalse(ModellerRequestScope.with(ALICE, () -> p.allowed("T2", "alice", "case.escalate", ActionModels.Scope.CASE, "c1")),
                "another tenant than the request's");
    }

    @Test
    void otherFailuresAreNotTurnedIntoADenial() {
        when(permissions.has(ALICE, "case.escalate")).thenReturn(true);
        when(processes.get(eq(ALICE), anyString())).thenThrow(ApiProblem.unavailable("ENGINE_DOWN", "engine unavailable", 5));
        ModellerWiring.SubjectPermission p = permission(false);

        assertEquals(503, assertThrows(ApiProblem.class, () -> ask(p, ActionModels.Scope.PROCESS, "p1")).status());
        assertThrows(ApiProblem.class, () -> ask(p, ActionModels.Scope.CASE, "c1"), "no case module: not installed, not a silent yes");
    }
}
