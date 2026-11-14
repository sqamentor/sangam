// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * W4-J's request (decision 236): the task view names, read only, the slot and document the DSC gate captured at task
 * creation. The variable names are the guard's (org.sangam.packs.core.DscSignatureGuardListener SLOT and
 * REQUIRED_DOCUMENT).
 */
class TaskDscDocumentTest {

    @Test
    void theCapturedSlotAndDocumentAreExposed() {
        TaskPort.DscDocument d = TaskPort.DscDocument.of(Map.of("dscDocumentSlot", "notingDocumentId",
                "dscRequiredDocumentId", "doc-7"));
        assertEquals(new TaskPort.DscDocument("notingDocumentId", "doc-7"), d);
        assertEquals("dscDocumentSlot", TaskPort.DscDocument.SLOT_VARIABLE);
        assertEquals("dscRequiredDocumentId", TaskPort.DscDocument.DOCUMENT_VARIABLE);
    }

    @Test
    void anEmptySlotAtCreationGivesTheSlotWithoutADocument() {
        Map<String, Object> local = new HashMap<>();
        local.put("dscDocumentSlot", "sanctionDocumentId");
        local.put("dscRequiredDocumentId", null);
        assertEquals(new TaskPort.DscDocument("sanctionDocumentId", null), TaskPort.DscDocument.of(local));
    }

    @Test
    void aTaskWithoutADscStepHasNone() {
        assertNull(TaskPort.DscDocument.of(Map.of()));
        assertNull(TaskPort.DscDocument.of(null));
        assertNull(TaskPort.DscDocument.of(Map.of("dscDocumentSlot", " ")));
    }

    @Test
    void theOldTaskDetailConstructorLeavesItEmpty() {
        TaskPort.TaskDetail t = new TaskPort.TaskDetail(null, Map.of(), null, 0, null, null, null, null, null, null,
                null, null, null, null, List.of(), List.of(), null);
        assertNull(t.dscDocument());
    }
}
