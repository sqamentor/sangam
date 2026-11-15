// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.OperationStatus;

/**
 * {@code failedWhenInvalid} of {@link ConnectorCallDelegate}: a validation query that answers {@code valid=false} is
 * a FAILED result of the step with the code of its first error finding, and the answer's data is kept. The bill's
 * document check ({@code bl_check}) is generated with it: it shares the result prefix {@code doc} with the type lookup
 * ({@code bl_type}), and without it the check of an unknown type wrote COMPLETED over the lookup's FAILED /
 * TYPE_UNKNOWN (sangam-server {@code DocumentTypeAdoptionTest} is the end-to-end proof).
 */
class ConnectorCallInvalidAnswerTest {

    static Map<String, Object> finding(String severity, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("severity", severity);
        m.put("code", code);
        return m;
    }

    static ConnectorResult answer(Object valid, List<Map<String, Object>> findings) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", valid);
        if (findings != null) {
            data.put("findings", findings);
        }
        return ConnectorResult.completed("ref-1", data);
    }

    @Test
    void anInvalidAnswerIsAFailedResultWithItsFirstErrorFinding() {
        ConnectorResult in = answer(false, List.of(finding("WARNING", "LATE"), finding("ERROR", "TYPE_UNKNOWN"),
                finding("ERROR", "FIELD_REQUIRED")));
        ConnectorResult r = ConnectorCallDelegate.failedWhenInvalid(in);
        assertEquals(OperationStatus.FAILED, r.status());
        assertEquals("TYPE_UNKNOWN", r.reasonCode(), "the first error, not the warning before it");
        assertEquals(in.data(), r.data(), "valid=false and the findings are still written");
        assertEquals("ref-1", r.externalRef());
    }

    @Test
    void anInvalidAnswerWithoutAnErrorFindingHasTheGenericCode() {
        assertEquals(ConnectorCallDelegate.INVALID, ConnectorCallDelegate.failedWhenInvalid(answer(false, null)).reasonCode());
        assertEquals(ConnectorCallDelegate.INVALID,
                ConnectorCallDelegate.failedWhenInvalid(answer(false, List.of(finding("WARNING", "LATE")))).reasonCode());
        assertEquals("X", ConnectorCallDelegate.failedWhenInvalid(answer(false, List.of(finding(null, "X")))).reasonCode(),
                "a finding without a severity counts as an error");
        assertEquals(OperationStatus.FAILED, ConnectorCallDelegate.failedWhenInvalid(answer(false, null)).status());
    }

    @Test
    void everyOtherResultIsUnchanged() {
        for (ConnectorResult r : List.of(answer(true, List.of()), answer(null, null), answer("false", null),
                ConnectorResult.failed("TYPE_UNKNOWN", "no"), ConnectorResult.pending(null),
                ConnectorResult.unknown("?"))) {
            assertSame(r, ConnectorCallDelegate.failedWhenInvalid(r), r.toString());
        }
    }

    @Test
    void theBillsDocumentCheckIsGeneratedWithIt() throws Exception {
        String bpmn;
        try (InputStream in = getClass().getResourceAsStream("/packs/ifms-bill/sangam-ifms-bill.bpmn")) {
            assertNotNull(in, "the generated bill model");
            bpmn = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int check = bpmn.indexOf("id=\"bl_check\"");
        int type = bpmn.indexOf("id=\"bl_type\"");
        assertTrue(check > 0 && type > 0);
        String checkTask = bpmn.substring(check, bpmn.indexOf("</serviceTask>", check));
        String typeTask = bpmn.substring(type, bpmn.indexOf("</serviceTask>", type));
        assertTrue(checkTask.contains("name=\"resultPrefix\"") && typeTask.contains("name=\"resultPrefix\""));
        assertTrue(checkTask.contains("name=\"failedWhenInvalid\""), checkTask);
        assertTrue(!typeTask.contains("name=\"failedWhenInvalid\""), "the lookup answers a business failure itself");
    }
}