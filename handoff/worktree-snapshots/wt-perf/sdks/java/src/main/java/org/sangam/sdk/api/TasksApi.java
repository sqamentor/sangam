// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.CompleteTaskRequest;
import org.sangam.sdk.model.CompleteTaskResult;
import org.sangam.sdk.model.DelegateTaskRequest;
import org.sangam.sdk.model.Page;
import org.sangam.sdk.model.ReturnTaskRequest;
import org.sangam.sdk.model.TaskView;

import java.util.Map;

/**
 * User tasks {@code /api/v1/tasks}. Mutations take an optional Idempotency-Key (null = one generated per logical
 * call, reused on retries); the government profile requires it on task decisions (R41).
 */
public final class TasksApi extends ApiSupport {

    public TasksApi(HttpTransport http) {
        super(http);
    }

    public Page<TaskView> list(TaskQuery query) {
        return readPage(ApiRequest.get("/tasks").query(query == null ? Map.of() : query.params()), TaskView.class);
    }

    public TaskView get(String taskId) {
        return call(ApiRequest.get("/tasks/" + seg(taskId)), TaskView.class);
    }

    /** {@code GET /tasks/{id}/variables}: a VariableMap (plain JSON or R33 TypedValue entries). */
    public Map<String, Object> variables(String taskId) {
        return call(ApiRequest.get("/tasks/" + seg(taskId) + "/variables"), MAP);
    }

    /** Single winner; the loser gets 409 TASK_ALREADY_CLAIMED. No body. */
    public void claim(String taskId, String idempotencyKey) {
        call(ApiRequest.post("/tasks/" + seg(taskId) + "/claim").idempotencyKey(idempotencyKey), Void.class);
    }

    public void claim(String taskId) {
        claim(taskId, null);
    }

    public void unclaim(String taskId, String idempotencyKey) {
        call(ApiRequest.post("/tasks/" + seg(taskId) + "/unclaim").idempotencyKey(idempotencyKey), Void.class);
    }

    /**
     * Completes the task with a decision. {@code payloadHash} must equal the hash of the payload the user approved
     * (VI.4 rule 5): a mismatch gives 409 APPROVAL_INVALIDATED; SoD violations give 422.
     */
    public CompleteTaskResult complete(String taskId, CompleteTaskRequest request, String idempotencyKey) {
        return call(ApiRequest.post("/tasks/" + seg(taskId) + "/complete").body(request).idempotencyKey(idempotencyKey),
                CompleteTaskResult.class);
    }

    public void returnTask(String taskId, ReturnTaskRequest request, String idempotencyKey) {
        call(ApiRequest.post("/tasks/" + seg(taskId) + "/return").body(request).idempotencyKey(idempotencyKey), Void.class);
    }

    public void delegate(String taskId, DelegateTaskRequest request, String idempotencyKey) {
        call(ApiRequest.post("/tasks/" + seg(taskId) + "/delegate").body(request).idempotencyKey(idempotencyKey), Void.class);
    }
}
