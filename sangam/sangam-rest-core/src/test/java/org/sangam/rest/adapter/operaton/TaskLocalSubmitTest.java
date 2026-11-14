// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Owner decisions 236 and 237: a submitted {@code dscDocumentId} leaves the process-scope submission and is set
 * task-locally on completion; every other submitted value stays where it was.
 */
class TaskLocalSubmitTest {

    @Test
    void dscDocumentIdIsTakenOutOfTheProcessScopeSubmission() {
        Map<String, Object> submit = new HashMap<>(Map.of("dscDocumentId", "doc-7", "amount", "12.50",
                "sangamDecision", "approve"));
        Map<String, Object> locals = OperatonTaskAdapter.takeTaskLocals(submit);
        assertEquals(Map.of("dscDocumentId", "doc-7"), locals);
        assertFalse(submit.containsKey("dscDocumentId"), "never written on the process scope");
        assertEquals(Map.of("amount", "12.50", "sangamDecision", "approve"), submit);
    }

    @Test
    void aSubmissionWithoutTheDocumentIsUnchanged() {
        Map<String, Object> submit = new HashMap<>(Map.of("remarks", "ok"));
        assertTrue(OperatonTaskAdapter.takeTaskLocals(submit).isEmpty());
        assertEquals(Map.of("remarks", "ok"), submit);
    }

    @Test
    void onlyTheDscDocumentIsTaskLocal() {
        assertEquals(Set.of("dscDocumentId"), OperatonTaskAdapter.TASK_LOCAL_SUBMIT,
                "acting identity locals are set by the server and can never come from the submission");
    }
}
