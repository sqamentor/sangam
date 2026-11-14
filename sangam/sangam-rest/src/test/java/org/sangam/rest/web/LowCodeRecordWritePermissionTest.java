// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.LowCodePort;
import org.sangam.rest.support.Permissions;

/**
 * Owner decision 284: creating a data-object record needs lowcode.record.write. A caller holding only lowcode.read is
 * refused 403 before the port is asked; the default sets grant it beside lowcode.admin and to the data-entry user.
 */
class LowCodeRecordWritePermissionTest extends LowCodeControllerRestBackedTest {

    @Test
    void aLowcodeReadOnlyCallerIsRefused403OnCreate() {
        creation = LowCodePort.RecordCreation.created(Map.of("id", "r-1"));
        ApiProblem p = assertThrows(ApiProblem.class, () -> controller(Permissions.LOWCODE_READ)
                .createRecord(CTX, "vendors", Map.of("name", "Acme")));
        assertEquals(403, p.status());
        assertTrue(calls.isEmpty(), "the port is not asked");
        ApiProblem admin = assertThrows(ApiProblem.class, () -> controller(Permissions.LOWCODE_READ,
                Permissions.LOWCODE_ADMIN).createRecord(CTX, "vendors", Map.of("name", "Acme")));
        assertEquals(403, admin.status(), "lowcode.admin does not imply it either");
        assertTrue(calls.isEmpty());
    }

    @Test
    void lowcodeRecordWriteAloneIsEnoughToCreate() {
        creation = LowCodePort.RecordCreation.created(Map.of("id", "r-1"));
        assertEquals(201, controller(Permissions.LOWCODE_RECORD_WRITE).createRecord(CTX, "vendors",
                Map.of("name", "Acme")).getStatusCode().value());
        assertEquals("lowcode.record.write", Permissions.LOWCODE_RECORD_WRITE);
    }

    @Test
    void theDefaultSetsGrantItBesideLowcodeAdminAndToTheDataEntryUser() {
        Map<String, Set<String>> d = Permissions.DEFAULT_GROUP_PERMISSIONS;
        for (Map.Entry<String, Set<String>> e : d.entrySet()) {
            if (e.getValue().contains(Permissions.LOWCODE_ADMIN)) {
                assertTrue(e.getValue().contains(Permissions.LOWCODE_RECORD_WRITE), e.getKey());
            }
        }
        assertTrue(d.get(Permissions.GROUP_USER).contains(Permissions.LOWCODE_RECORD_WRITE));
        assertTrue(d.get(Permissions.GROUP_OPERATOR).contains(Permissions.LOWCODE_RECORD_WRITE));
        assertFalse(d.get(Permissions.GROUP_AUDITOR).contains(Permissions.LOWCODE_RECORD_WRITE), "read-only role");
        assertFalse(d.get(Permissions.GROUP_CITIZEN).contains(Permissions.LOWCODE_RECORD_WRITE));
        assertFalse(d.get(Permissions.GROUP_INTEGRATION).contains(Permissions.LOWCODE_RECORD_WRITE));
    }
}
