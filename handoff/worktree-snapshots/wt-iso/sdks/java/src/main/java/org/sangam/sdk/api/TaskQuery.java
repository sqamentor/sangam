// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Filters for {@code GET /tasks} (sangam-api.yaml listTasks). The server always applies the caller's VI.4 policy too. */
public final class TaskQuery {

    private final Map<String, Object> params = new LinkedHashMap<>();

    public static TaskQuery create() {
        return new TaskQuery();
    }

    public TaskQuery assignee(String v) { return put("assignee", v); }
    /** Assigned to me or I am a candidate. */
    public TaskQuery mine(boolean v) { return put("mine", v); }
    public TaskQuery unassigned(boolean v) { return put("unassigned", v); }
    public TaskQuery candidateGroup(String v) { return put("candidateGroup", v); }
    public TaskQuery seatId(String v) { return put("seatId", v); }
    public TaskQuery processInstanceId(String v) { return put("processInstanceId", v); }
    public TaskQuery caseInstanceId(String v) { return put("caseInstanceId", v); }
    public TaskQuery businessKey(String v) { return put("businessKey", v); }
    public TaskQuery processDefinitionKey(String v) { return put("processDefinitionKey", v); }
    public TaskQuery taskDefinitionKey(String v) { return put("taskDefinitionKey", v); }
    public TaskQuery state(String v) { return put("state", v); }
    public TaskQuery sortBy(String v) { return put("sortBy", v); }
    /** {@code asc} or {@code desc}. */
    public TaskQuery sortOrder(String v) { return put("sortOrder", v); }
    /** 0-based page index. */
    public TaskQuery page(int v) { return put("page", v); }
    /** Page size, 1..500 (default 50). */
    public TaskQuery size(int v) { return put("size", v); }
    /** Any other server-side filter. */
    public TaskQuery param(String name, Object v) { return put(name, v); }

    private TaskQuery put(String k, Object v) {
        if (v == null) params.remove(k);
        else params.put(k, v);
        return this;
    }

    Map<String, Object> params() {
        return params;
    }
}
