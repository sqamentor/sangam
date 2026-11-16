// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.testing;

import static org.sangam.sdk.testing.EngineModel.notFound;
import static org.sangam.sdk.testing.EngineModel.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.sangam.sdk.testing.EngineModel.Problem;
import org.sangam.sdk.testing.EngineModel.Work;

/**
 * The step journal of {@link SangamTestEngine} (M-025, R37): {@code GET/PUT /work/{id}/step?stepName=} and
 * {@code GET /work/{id}/steps}, keyed by (operationId, step name) and fenced by the lease like the server
 * ({@code WorkStepJournal} + {@code WorkPort.inFencedWork}). Same transitions: INTENT on a new, INTENT or FAILED step;
 * SUCCEEDED/FAILED need an INTENT (409 STEP_INTENT_REQUIRED); SUCCEEDED is final (409 STEP_ALREADY_RECORDED); an
 * identical repeat is a no-op. The engine calls it while it holds its lock. Package-private.
 */
final class EngineSteps {

    private static final class Row {
        String operationId;
        String name;
        String workId;
        String state;
        JsonNode result;
        String providerRef;
        String error;
        String attemptId;
        String recordedAt;
    }

    /** operationId -> step name -> row, in recording order. */
    private final Map<String, LinkedHashMap<String, Row>> steps = new LinkedHashMap<>();
    private final Map<String, Work> works;
    private final EngineWorkCalls fence;
    private final Clock clock;
    private final ObjectMapper json;

    EngineSteps(Map<String, Work> works, EngineWorkCalls fence, Clock clock, ObjectMapper json) {
        this.works = works;
        this.fence = fence;
        this.clock = clock;
        this.json = json;
    }

    JsonNode get(String workId, String name, String workerId, long lockVersion, String attemptId) {
        Work w = fenced(workId, workerId, lockVersion, attemptId, name);
        Row r = steps.getOrDefault(w.operationId, new LinkedHashMap<>()).get(name);
        if (r == null) throw notFound("work step", name);
        return view(r);
    }

    JsonNode put(String workId, String name, JsonNode body) {
        String attemptId = text(body, "attemptId");
        Work w = fenced(workId, text(body, "workerId"), body.path("lockVersion").asLong(), attemptId, name);
        String state = text(body, "state");
        if (!"INTENT".equals(state) && !"SUCCEEDED".equals(state) && !"FAILED".equals(state)) {
            throw new Problem(400, "VALIDATION_FAILED", null, "state must be INTENT, SUCCEEDED or FAILED");
        }
        JsonNode result = body.hasNonNull("result") ? body.get("result") : null;
        String ref = text(body, "providerRef");
        String error = text(body, "error");
        LinkedHashMap<String, Row> op = steps.computeIfAbsent(w.operationId, k -> new LinkedHashMap<>());
        Row r = op.get(name);
        if (r == null) {
            if (!"INTENT".equals(state)) throw intentRequired(name, null);
            r = new Row();
            r.operationId = w.operationId;
            r.name = name;
            op.put(name, r);
        } else {
            if (identical(r, state, attemptId, result, ref, error)) return view(r);
            if ("SUCCEEDED".equals(r.state)) {
                throw new Problem(409, "STEP_ALREADY_RECORDED", null, "step " + name + " already SUCCEEDED");
            }
            if (!"INTENT".equals(state) && !"INTENT".equals(r.state)) throw intentRequired(name, r.state);
        }
        r.workId = workId;
        r.state = state;
        r.result = result;
        r.providerRef = ref;
        r.error = error;
        r.attemptId = attemptId;
        r.recordedAt = clock.instant().toString();
        return view(r);
    }

    JsonNode list(String workId) {
        Work w = works.get(workId);
        List<Row> rows = new ArrayList<>();
        // as WorkStepController.list: the operation of open work, else the rows this work id recorded
        if (w != null && w.exists() && w.operationId != null) {
            rows.addAll(steps.getOrDefault(w.operationId, new LinkedHashMap<>()).values());
        } else {
            steps.values().forEach(m -> m.values().stream().filter(r -> workId.equals(r.workId)).forEach(rows::add));
            if (rows.isEmpty()) throw notFound("external work", workId);
        }
        ArrayNode out = json.createArrayNode();
        rows.forEach(r -> out.add(view(r)));
        return out;
    }

    private Work fenced(String workId, String workerId, long lockVersion, String attemptId, String name) {
        if (name == null || name.isEmpty() || name.length() > 128 || !name.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) {
            throw new Problem(400, "VALIDATION_FAILED", null, "stepName must be 1-128 printable ASCII characters");
        }
        if (attemptId == null || attemptId.isBlank()) {
            throw new Problem(400, "VALIDATION_FAILED", null, "attemptId is required");
        }
        Work w = works.get(workId);
        // as OperatonWorkAdapter.inFencedWork: a deleted instance (tombstone) is 410, any other ended work 404
        if (w != null && w.tombstone != null && !w.exists()) throw EngineModel.gone(workId, null);
        if (w == null || !w.exists()) throw notFound("external work", workId);
        fence.fence(w, workerId, lockVersion, attemptId);
        return w;
    }

    private static boolean identical(Row r, String state, String attemptId, JsonNode result, String ref, String error) {
        if (!r.state.equals(state)) return false;
        if ("SUCCEEDED".equals(state)) return Objects.equals(r.result, result) && Objects.equals(r.providerRef, ref);
        return Objects.equals(r.attemptId, attemptId) && Objects.equals(r.result, result)
                && Objects.equals(r.providerRef, ref) && Objects.equals(r.error, error);
    }

    private static Problem intentRequired(String name, String current) {
        return new Problem(409, "STEP_INTENT_REQUIRED", null, "step " + name + " has no INTENT"
                + (current == null ? "" : " (it is " + current + ")"));
    }

    private JsonNode view(Row r) {
        ObjectNode o = json.createObjectNode();
        o.put("operationId", r.operationId);
        o.put("name", r.name);
        o.put("state", r.state);
        o.set("result", r.result);
        o.put("providerRef", r.providerRef);
        o.put("error", r.error);
        o.put("attemptId", r.attemptId);
        o.put("recordedAt", r.recordedAt);
        return o;
    }
}
