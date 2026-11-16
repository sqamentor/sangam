// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.error.SangamApiException;
import org.sangam.sdk.generated.model.WorkStepRequest;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.BpmnErrorRequest;
import org.sangam.sdk.model.CompleteWorkRequest;
import org.sangam.sdk.model.ExtendLockRequest;
import org.sangam.sdk.model.ExternalWork;
import org.sangam.sdk.model.FailWorkRequest;
import org.sangam.sdk.model.OutcomeUnknownRequest;
import org.sangam.sdk.model.FetchAndLockRequest;
import org.sangam.sdk.model.FetchTopic;
import org.sangam.sdk.model.UnlockRequest;

/**
 * SL-174: the step routes and the lease calls on work that is not open answer exactly what the server answers. The
 * expected problems come from {@code sdks/testing/work-step-problems.json}, which the server's
 * {@code WorkStepJournalH2Test} and {@code WorkCallProblemsVectorH2Test} check against the server work adapters too, so
 * the engine and the server are compared through one table.
 */
class WorkStepProblemsVectorTest {

    private static final ObjectMapper JSON = HttpTransport.defaultMapper();
    private static final Path VECTORS = Paths.get("..", "testing", "work-step-problems.json");

    @Test
    void stepRoutesOnWorkThatIsNotOpenAnswerTheServersProblem() throws IOException {
        JsonNode file = JSON.readTree(Files.readString(VECTORS));
        String step = file.get("stepName").asText();
        int checked = 0;
        for (JsonNode c : file.get("cases")) {
            try (SangamTestEngine e = SangamTestEngine.start()) {
                SangamClient client = e.client();
                String id = e.addWork("pay", Map.of());
                ExternalWork w = client.work().fetchAndLock(new FetchAndLockRequest("w1",
                        List.of(new FetchTopic("pay", 60_000L, null, null, null, null)), 1, 0L, null)).get(0);
                String target = id;
                switch (c.get("scenario").asText()) {
                    case "UNKNOWN" -> target = file.get("unknownWorkId").asText();
                    case "COMPLETED" -> client.work().complete(id,
                            new CompleteWorkRequest("w1", w.lockVersion(), w.attemptId(), Map.of(), null, null), "k-c");
                    case "DELETED" -> e.deleteInstance(id);
                    case "RELEASED" -> client.work().unlock(id, new UnlockRequest("w1", w.lockVersion(), w.attemptId()), "k-u");
                    case "UNKNOWN_OUTCOME" -> awaitReconciliation(e, client, id, w);
                    default -> throw new AssertionError("unknown scenario " + c.get("scenario"));
                }
                String work = target;
                expect(c, "GET", () -> client.api().work().getWorkStep(work, step, "w1", w.lockVersion(), w.attemptId(), null));
                expect(c, "PUT", () -> client.api().work().recordWorkStep(work,
                        new WorkStepRequest("w1", w.lockVersion(), w.attemptId(), "INTENT", null, null, null), step, null));
                checked++;
            }
        }
        assertTrue(checked >= 5, "every vector ran");
    }

    @Test
    void leaseCallsOnWorkThatIsNotOpenAnswerTheServersProblem() throws IOException {
        JsonNode file = JSON.readTree(Files.readString(VECTORS));
        int checked = 0;
        for (JsonNode c : file.get("workCalls")) {
            try (SangamTestEngine e = SangamTestEngine.start()) {
                SangamClient client = e.client();
                String id = e.addWork("pay", Map.of());
                ExternalWork w = client.work().fetchAndLock(new FetchAndLockRequest("w1",
                        List.of(new FetchTopic("pay", 60_000L, null, null, null, null)), 1, 0L, null)).get(0);
                switch (c.get("scenario").asText()) {
                    case "INCIDENT" -> {
                        client.work().fail(id, new FailWorkRequest("w1", w.lockVersion(), w.attemptId(), "no retries left",
                                null, 0, 0L), "k-f0");
                        assertEquals("INCIDENT", e.state(id));
                    }
                    case "UNKNOWN_OUTCOME" -> awaitReconciliation(e, client, id, w);
                    default -> throw new AssertionError("unknown scenario " + c.get("scenario"));
                }
                long lv = w.lockVersion();
                String at = w.attemptId();
                Supplier<?> call = switch (c.get("call").asText()) {
                    case "complete" -> () -> {
                        client.work().complete(id, new CompleteWorkRequest("w1", lv, at, Map.of(), null, null), "k1");
                        return null;
                    };
                    case "fail" -> () -> {
                        client.work().fail(id, new FailWorkRequest("w1", lv, at, "again", null, 1, 0L), "k2");
                        return null;
                    };
                    case "bpmn-error" -> () -> {
                        client.work().bpmnError(id, new BpmnErrorRequest("w1", lv, at, "E1", null, Map.of()), "k3");
                        return null;
                    };
                    case "unlock" -> () -> {
                        client.work().unlock(id, new UnlockRequest("w1", lv, at), "k4");
                        return null;
                    };
                    case "extend-lock" -> () -> client.work().extendLock(id, new ExtendLockRequest("w1", lv, at, 60_000L), "k5");
                    case "outcome-unknown" -> () -> client.work().outcomeUnknown(id,
                            new OutcomeUnknownRequest("w1", lv, at, "unsure", Map.of()), "k6");
                    default -> throw new AssertionError("unknown call " + c.get("call"));
                };
                expect(c, c.get("call").asText(), call);
                checked++;
            }
        }
        assertTrue(checked >= 12, "every work-call vector ran");
    }

    /** The UNKNOWN_OUTCOME scenario: the worker reports an unknown outcome; the work awaits reconciliation. */
    private static void awaitReconciliation(SangamTestEngine e, SangamClient client, String id, ExternalWork w) {
        client.work().outcomeUnknown(id, new OutcomeUnknownRequest("w1", w.lockVersion(), w.attemptId(), "unsure", Map.of()),
                "k-o");
        assertEquals("UNKNOWN_OUTCOME", e.state(id));
    }

    private static void expect(JsonNode c, String route, Supplier<?> call) {
        String what = c.get("id").asText() + " " + route;
        SangamApiException ex = assertThrows(SangamApiException.class, call::get, what);
        assertEquals(c.get("status").asInt(), ex.status(), what);
        assertEquals(c.get("code").asText(), ex.problem().code, what);
        for (Iterator<Map.Entry<String, JsonNode>> it = c.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            if (!List.of("id", "scenario", "call", "status", "code").contains(f.getKey())) {
                assertEquals(f.getValue().asText(), String.valueOf(ex.problem().extensions().get(f.getKey())),
                        what + " " + f.getKey());
            }
        }
    }
}
