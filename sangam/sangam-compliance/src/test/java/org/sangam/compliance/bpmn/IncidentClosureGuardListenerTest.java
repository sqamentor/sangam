// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.bpmn;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;

/**
 * Coordinator ruling on SANGAM.md OJ-06 step 8 and W4-M finding 2026-11-13T07:51:04Z: completing the incident follow-up
 * task towards closing is refused with {@code 422 INCIDENT_CLOSURE_INCOMPLETE} unless the root cause and the lessons
 * learned are given; a follow-up completion and other task events are not checked. The task is a dynamic proxy that
 * answers only the event name and variables.
 */
class IncidentClosureGuardListenerTest {

    static DelegateTask task(String event, Map<String, Object> vars) {
        Map<String, Object> v = new HashMap<>(vars);
        v.putIfAbsent("incidentId", "inc-1");
        return (DelegateTask) Proxy.newProxyInstance(DelegateTask.class.getClassLoader(), new Class<?>[] {DelegateTask.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getEventName" -> event;
                    case "getVariable" -> v.get((String) args[0]);
                    case "toString" -> "task(" + event + ", " + v + ")";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    static Map<String, Object> vars(Object followUpNeeded, Object rootCause, Object lessons) {
        Map<String, Object> m = new HashMap<>();
        m.put("followUpNeeded", followUpNeeded);
        m.put("rootCause", rootCause);
        m.put("lessonsLearned", lessons);
        return m;
    }

    final IncidentClosureGuardListener guard = new IncidentClosureGuardListener();

    @Test
    void closingWithoutRootCauseOrLessonsIsRefusedWith422() {
        Object[][] cases = {
                {false, null, "patch window"}, {false, " ", "patch window"}, {null, "unpatched framework", null},
                {"false", "unpatched framework", ""}, {false, null, null}};
        for (Object[] c : cases) {
            ProcessEngineException e = assertThrows(ProcessEngineException.class,
                    () -> guard.notify(task(TaskListener.EVENTNAME_COMPLETE, vars(c[0], c[1], c[2]))));
            assertTrue(e.getMessage().startsWith("422 INCIDENT_CLOSURE_INCOMPLETE: "), e.getMessage());
            assertTrue(e.getMessage().contains("inc-1"), e.getMessage());
            boolean noRoot = c[1] == null || c[1].toString().isBlank();
            boolean noLessons = c[2] == null || c[2].toString().isBlank();
            assertTrue(noRoot == e.getMessage().contains("rootCause"), "names exactly the missing fields: " + e.getMessage());
            assertTrue(noLessons == e.getMessage().contains("lessonsLearned"), "names exactly the missing fields: " + e.getMessage());
        }
    }

    @Test
    void closingWithBothFieldsCompletes() {
        assertDoesNotThrow(() -> guard.notify(task(TaskListener.EVENTNAME_COMPLETE,
                vars(false, "unpatched framework", "monthly patch window"))));
        assertDoesNotThrow(() -> guard.notify(task(TaskListener.EVENTNAME_COMPLETE,
                vars(null, "unpatched framework", "monthly patch window"))), "no choice is the gateway's default: close");
    }

    @Test
    void aFollowUpCompletionAndOtherEventsAreNotChecked() {
        assertDoesNotThrow(() -> guard.notify(task(TaskListener.EVENTNAME_COMPLETE, vars(true, null, null))));
        assertDoesNotThrow(() -> guard.notify(task(TaskListener.EVENTNAME_COMPLETE, vars("true", null, null))));
        assertDoesNotThrow(() -> guard.notify(task(TaskListener.EVENTNAME_CREATE, vars(false, null, null))));
    }

    @Test
    void theFollowUpChoiceIsReadAsTheGatewayReadsIt() {
        assertTrue(IncidentClosureGuardListener.followUpChosen(Boolean.TRUE));
        assertTrue(IncidentClosureGuardListener.followUpChosen("true"));
        assertFalse(IncidentClosureGuardListener.followUpChosen(Boolean.FALSE));
        assertFalse(IncidentClosureGuardListener.followUpChosen(null));
        assertFalse(IncidentClosureGuardListener.followUpChosen("no"));
    }
}
