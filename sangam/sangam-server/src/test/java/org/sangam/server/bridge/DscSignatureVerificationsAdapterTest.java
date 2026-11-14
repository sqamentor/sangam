// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.packs.port.SignatureVerifications;
import org.sangam.server.config.PacksWiring;

/**
 * Decisions 236 and 237: the adapter passes tenant, task, document and principal to the DSC flow unchanged (the same
 * forms both sides use) and never asks with a blank one; without the DSC flow the packs keep the refuse-all default.
 */
class DscSignatureVerificationsAdapterTest {

    @Test
    void theFourValuesReachTheFlowUnchanged() {
        List<List<String>> asked = new ArrayList<>();
        SignatureVerifications flow = (t, task, doc, who) -> {
            asked.add(List.of(t, task, doc, who));
            return "asha".equals(who) && "doc-1".equals(doc);
        };
        DscSignatureVerificationsAdapter a = new DscSignatureVerificationsAdapter(flow);
        assertTrue(a.hasVerifiedSignature("t1", "task-9", "doc-1", "asha"));
        assertFalse(a.hasVerifiedSignature("t1", "task-9", "doc-2", "asha"), "another document");
        assertFalse(a.hasVerifiedSignature("t1", "task-9", "doc-1", "user:asha"), "no form is mapped or loosened");
        assertEquals(List.of(List.of("t1", "task-9", "doc-1", "asha"), List.of("t1", "task-9", "doc-2", "asha"),
                List.of("t1", "task-9", "doc-1", "user:asha")), asked);
    }

    @Test
    void aBlankValueIsNeverAskedAndAnswersFalse() {
        List<String> asked = new ArrayList<>();
        DscSignatureVerificationsAdapter a = new DscSignatureVerificationsAdapter((t, task, doc, who) -> {
            asked.add(doc);
            return true;
        });
        assertFalse(a.hasVerifiedSignature(null, "task", "doc", "asha"));
        assertFalse(a.hasVerifiedSignature("t1", " ", "doc", "asha"));
        assertFalse(a.hasVerifiedSignature("t1", "task", "", "asha"));
        assertFalse(a.hasVerifiedSignature("t1", "task", "doc", null));
        assertTrue(asked.isEmpty(), "the flow is never asked with a blank value");
    }

    @Test
    void aFailingFlowPropagatesSoThePackRefuses() {
        DscSignatureVerificationsAdapter a = new DscSignatureVerificationsAdapter((t, task, doc, who) -> {
            throw new IllegalStateException("database down");
        });
        assertThrows(IllegalStateException.class, () -> a.hasVerifiedSignature("t1", "task", "doc", "asha"),
                "the pack gate turns an exception into 422 DSC_SIGNATURE_NOT_VERIFIED");
    }

    @Test
    void theStatusIsTheFlowsStatusNameForName() {
        for (org.sangam.integration.dsc.DscSigningFlow.SignatureStatus s
                : org.sangam.integration.dsc.DscSigningFlow.SignatureStatus.values()) {
            assertEquals(s.name(), DscSignatureVerificationsAdapter.map(s).name(), s.name());
        }
        assertEquals(SignatureVerifications.Status.values().length,
                org.sangam.integration.dsc.DscSigningFlow.SignatureStatus.values().length, "one-to-one");
        assertEquals(SignatureVerifications.Status.NOT_SIGNED, DscSignatureVerificationsAdapter.map(null));
    }

    @Test
    void theStatusQueryReachesTheFlowAndABlankValueIsNotSignedWithoutAsking() {
        List<String> asked = new ArrayList<>();
        DscSignatureVerificationsAdapter a = new DscSignatureVerificationsAdapter(new SignatureVerifications() {
            @Override
            public boolean hasVerifiedSignature(String t, String task, String doc, String who) {
                asked.add("has " + doc);
                return false;
            }

            @Override
            public Status status(String t, String task, String doc, String who) {
                asked.add("status " + t + " " + task + " " + doc + " " + who);
                return Status.ENROLMENT_REVOKED;
            }
        });
        assertEquals(SignatureVerifications.Status.ENROLMENT_REVOKED, a.status("t1", "task-9", "doc-1", "asha"));
        assertEquals(List.of("status t1 task-9 doc-1 asha"), asked);
        asked.clear();
        assertEquals(SignatureVerifications.Status.NOT_SIGNED, a.status("t1", "task-9", " ", "asha"));
        assertEquals(SignatureVerifications.Status.NOT_SIGNED, a.status(null, "task-9", "doc-1", "asha"));
        assertEquals(SignatureVerifications.Status.NOT_SIGNED, a.status("t1", "task-9", "doc-1", ""));
        assertTrue(asked.isEmpty(), "the flow is never asked with a blank value");
    }

    @Test
    void withoutTheDscFlowThePortStaysAtRefuseAll() {
        assertSame(SignatureVerifications.DENY, PacksWiring.signatures(null),
                "DSC off or sangam-esign absent: no DscSigningFlow bean, every DSC step is refused");
    }
}
