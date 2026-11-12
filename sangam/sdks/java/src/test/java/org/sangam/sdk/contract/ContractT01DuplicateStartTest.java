// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.error.SangamApiException;
import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.ApiResponse;
import org.sangam.sdk.model.ProcessInstance;
import org.sangam.sdk.model.StartProcessRequest;
import org.sangam.sdk.model.TypedValue;
import org.sangam.sdk.testing.SangamTestEngine;

/**
 * T01 (SANGAM.md II §16, R41): duplicate process-start requests produce one intended instance; the repeated response
 * is the first one (Idempotent-Replayed: true); a conflicting payload under the same key is rejected (422).
 */
class ContractT01DuplicateStartTest {

    private static StartProcessRequest start(String amount) {
        return new StartProcessRequest("p1_bill", "BILL-2026-0001", Map.of("amount", TypedValue.decimal(amount)),
                null, null, null, null);
    }

    private static ApiResponse<ProcessInstance> post(SangamClient c, StartProcessRequest r, String key) {
        return c.transport().execute(ApiRequest.post("/process-instances").body(r).idempotencyKey(key), ProcessInstance.class);
    }

    @Test
    void t01_sameKeySamePayloadIsOneInstanceAndReplayed() {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            SangamClient c = engine.client();
            ApiResponse<ProcessInstance> first = post(c, start("125000.00"), "start-BILL-2026-0001");
            ApiResponse<ProcessInstance> second = post(c, start("125000.00"), "start-BILL-2026-0001");
            assertEquals(201, first.status());
            assertNull(first.header("Idempotent-Replayed"));
            assertEquals(201, second.status());
            assertEquals("true", second.header("Idempotent-Replayed"));
            assertEquals(first.body().id(), second.body().id());
            assertEquals(1, engine.instances().size());
        }
    }

    @Test
    void t01_typedClientStartIsAlsoStartOnce() {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            SangamClient c = engine.client();
            ProcessInstance a = c.processes().start(start("10.00"), "k-typed");
            ProcessInstance b = c.processes().start(start("10.00"), "k-typed");
            assertEquals(a.id(), b.id());
            assertEquals(List.of(a.id()), engine.instances().stream().map(SangamTestEngine.Instance::id).toList());
        }
    }

    @Test
    void t01_conflictingPayloadUnderTheSameKeyIsRejected() {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            SangamClient c = engine.client();
            post(c, start("125000.00"), "start-BILL-2026-0002");
            SangamApiException e = assertThrows(SangamApiException.class,
                    () -> post(c, start("999.00"), "start-BILL-2026-0002"));
            assertEquals(422, e.status());
            assertEquals("IDEMPOTENCY_KEY_REUSED", e.problem().code);
            assertEquals(1, engine.instances().size(), "the conflicting request changed nothing");
        }
    }

    @Test
    void t01_differentKeysAreDifferentBusinessActions() {
        try (SangamTestEngine engine = SangamTestEngine.start()) {
            SangamClient c = engine.client();
            ProcessInstance a = post(c, start("1.00"), "k-a").body();
            ProcessInstance b = post(c, start("1.00"), "k-b").body();
            assertNotEquals(a.id(), b.id());
            assertEquals(2, engine.instances().size());
        }
    }
}
