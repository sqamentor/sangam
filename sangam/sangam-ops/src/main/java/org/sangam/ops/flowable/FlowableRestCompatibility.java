// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * M-135: classification of the Flowable REST API (flowable-rest, {@code /service/...}) against the Sangam REST API, for
 * clients that move with the migrated instances. Sangam does not serve the Flowable paths; a client is rewritten
 * against the mapped path. Each entry is one of:
 *
 * <ul>
 *   <li>{@link Level#MAPPED}: the same operation exists; only the path and body shape change.</li>
 *   <li>{@link Level#PARTIAL}: the operation exists with a narrower or different contract (see the note).</li>
 *   <li>{@link Level#UNSUPPORTED}: no equivalent; the client must change its design.</li>
 * </ul>
 *
 * The Sangam paths are taken from {@code sangam-rest/api/sangam-api.yaml}; {@code FlowableRestCompatibilityTest}
 * checks that every mapped path is present there. A Flowable request that matches no entry is UNSUPPORTED.
 */
public final class FlowableRestCompatibility {

    public enum Level { MAPPED, PARTIAL, UNSUPPORTED }

    /**
     * @param flowablePath the Flowable path template, {@code {x}} for a path variable
     * @param sangamPath   the Sangam path template, null when UNSUPPORTED
     */
    public record Entry(String method, String flowablePath, Level level, String sangamMethod, String sangamPath, String note) {
        public Entry {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(flowablePath, "flowablePath");
            Objects.requireNonNull(level, "level");
            if ((level == Level.UNSUPPORTED) != (sangamPath == null)) {
                throw new IllegalArgumentException(flowablePath + ": an UNSUPPORTED entry has no Sangam path, every other one has");
            }
        }

        boolean matches(String m, String path) {
            if (!method.equalsIgnoreCase(m)) {
                return false;
            }
            String[] a = flowablePath.split("/");
            String[] b = path.split("/");
            if (a.length != b.length) {
                return false;
            }
            for (int i = 0; i < a.length; i++) {
                if (!(a[i].startsWith("{") && a[i].endsWith("}") && !b[i].isEmpty()) && !a[i].equals(b[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final String RT = "/service/runtime";
    private static final String RP = "/service/repository";
    private static final String HI = "/service/history";
    private static final String MG = "/service/management";

    public static final List<Entry> ENTRIES = List.of(
            // runtime: tasks
            new Entry("GET", RT + "/tasks", Level.MAPPED, "GET", "/tasks", "query parameters differ; paging is page/size"),
            new Entry("GET", RT + "/tasks/{taskId}", Level.MAPPED, "GET", "/tasks/{id}", null),
            new Entry("POST", RT + "/tasks/{taskId}", Level.PARTIAL, "POST", "/tasks/{id}/complete",
                    "Flowable's action body (complete, claim, delegate, resolve) becomes one Sangam path per action; "
                            + "'resolve' has no own path (complete the delegated task)"),
            new Entry("PUT", RT + "/tasks/{taskId}", Level.PARTIAL, "POST", "/tasks/{id}/claim",
                    "only the assignee change is carried (claim, unclaim, delegate); name, priority and due date are not editable"),
            new Entry("GET", RT + "/tasks/{taskId}/variables", Level.MAPPED, "GET", "/tasks/{id}/variables", null),
            new Entry("POST", RT + "/tasks/{taskId}/variables", Level.MAPPED, "PUT", "/tasks/{id}/variables",
                    "variable types follow the Sangam variable contract (decimal, not double, for money)"),
            new Entry("GET", RT + "/tasks/{taskId}/identitylinks", Level.MAPPED, "GET", "/tasks/{id}/candidates", null),
            new Entry("POST", RT + "/tasks/{taskId}/identitylinks", Level.MAPPED, "POST", "/tasks/{id}/candidates", null),
            new Entry("GET", RT + "/tasks/{taskId}/comments", Level.MAPPED, "GET", "/tasks/{id}/comments", null),
            new Entry("POST", RT + "/tasks/{taskId}/comments", Level.MAPPED, "POST", "/tasks/{id}/comments", null),
            new Entry("GET", RT + "/tasks/{taskId}/subtasks", Level.UNSUPPORTED, null, null,
                    "Sangam has no sub-tasks; instances with sub-tasks are rejected by the migration (SUBTASK)"),
            new Entry("POST", RT + "/tasks", Level.UNSUPPORTED, null, null, "standalone tasks are not part of the product"),
            // runtime: process instances
            new Entry("POST", RT + "/process-instances", Level.MAPPED, "POST", "/process-instances",
                    "start by definition key or id; tenant comes from the caller's token, never from the body"),
            new Entry("GET", RT + "/process-instances", Level.MAPPED, "GET", "/process-instances", null),
            new Entry("GET", RT + "/process-instances/{processInstanceId}", Level.MAPPED, "GET", "/process-instances/{id}", null),
            new Entry("DELETE", RT + "/process-instances/{processInstanceId}", Level.PARTIAL, "POST", "/process-instances/{id}/cancel",
                    "a cancellation with a mandatory reason, audited; no hard delete"),
            new Entry("PUT", RT + "/process-instances/{processInstanceId}", Level.PARTIAL, "PUT", "/process-instances/{id}/suspension",
                    "only activate and suspend; the Flowable 'name' update has no equivalent"),
            new Entry("GET", RT + "/process-instances/{processInstanceId}/variables", Level.MAPPED, "GET",
                    "/process-instances/{id}/variables", null),
            new Entry("PUT", RT + "/process-instances/{processInstanceId}/variables", Level.MAPPED, "PUT",
                    "/process-instances/{id}/variables", null),
            new Entry("POST", RT + "/process-instances/{processInstanceId}/change-state", Level.PARTIAL, "POST",
                    "/process-instances/{id}/modify", "Operaton modification instructions; 'moveExecutionToActivityId' becomes "
                            + "cancel plus startBefore"),
            new Entry("GET", RT + "/executions", Level.PARTIAL, "GET", "/process-instances/{id}/activity-tree",
                    "executions are not exposed; the activity-instance tree of one instance replaces them"),
            new Entry("PUT", RT + "/executions/{executionId}", Level.PARTIAL, "POST", "/messages",
                    "'messageEventReceived' and 'signalEventReceived' become message correlation or a signal by name"),
            new Entry("POST", RT + "/signals", Level.MAPPED, "POST", "/signals", null),
            new Entry("GET", RT + "/event-subscriptions", Level.UNSUPPORTED, null, null, "subscriptions are not exposed"),
            // repository
            new Entry("GET", RP + "/deployments", Level.MAPPED, "GET", "/deployments", null),
            new Entry("POST", RP + "/deployments", Level.PARTIAL, "POST", "/deployments",
                    "BPMN and DMN only; Flowable-specific extensions are converted by the model importer first"),
            new Entry("GET", RP + "/process-definitions", Level.MAPPED, "GET", "/process-definitions", null),
            new Entry("GET", RP + "/process-definitions/{processDefinitionId}/resourcedata", Level.MAPPED, "GET",
                    "/process-definitions/by-id/xml", "the definition id is the id query parameter (decision 393)"),
            new Entry("PUT", RP + "/process-definitions/{processDefinitionId}", Level.PARTIAL, "PUT",
                    "/process-definitions/by-id/suspension", "only suspend and activate; category change has no equivalent; the definition id"
                            + " is the id query parameter (decision 393)"),
            // decisions and forms
            new Entry("POST", "/service/dmn-rule/execute", Level.MAPPED, "POST", "/decisions/{key}/evaluate",
                    "the decision key moves into the path"),
            new Entry("GET", "/service/form/form-definitions", Level.PARTIAL, "GET", "/forms/by-key",
                    "forms are read one key at a time; Flowable form JSON is not served"),
            // history
            new Entry("GET", HI + "/historic-process-instances", Level.MAPPED, "GET", "/history/process-instances", null),
            new Entry("GET", HI + "/historic-activity-instances", Level.MAPPED, "GET", "/history/activity-instances", null),
            new Entry("GET", HI + "/historic-task-instances", Level.MAPPED, "GET", "/history/tasks", null),
            new Entry("GET", HI + "/historic-variable-instances", Level.MAPPED, "GET", "/history/variables", null),
            new Entry("POST", "/service/query/historic-process-instances", Level.PARTIAL, "GET", "/history/process-instances",
                    "query bodies become query parameters; variable-value filters are narrower"),
            // management
            new Entry("GET", MG + "/jobs", Level.MAPPED, "GET", "/ops/jobs", null),
            new Entry("POST", MG + "/jobs/{jobId}", Level.MAPPED, "POST", "/ops/jobs/{id}/execute", null),
            new Entry("GET", MG + "/jobs/{jobId}/exception-stacktrace", Level.MAPPED, "GET", "/ops/jobs/{id}/stacktrace", null),
            new Entry("GET", MG + "/deadletter-jobs", Level.PARTIAL, "GET", "/ops/incidents",
                    "dead-letter jobs are failed jobs with an incident; retry is PUT /ops/jobs/{id}/retries"),
            new Entry("POST", MG + "/deadletter-jobs/{jobId}", Level.PARTIAL, "PUT", "/ops/jobs/{id}/retries",
                    "'move' becomes setting retries on the failed job"),
            new Entry("GET", MG + "/timer-jobs", Level.PARTIAL, "GET", "/ops/jobs", "timer jobs are jobs filtered by type"),
            new Entry("GET", MG + "/engine", Level.UNSUPPORTED, null, null, "engine internals are not exposed"),
            new Entry("GET", MG + "/tables", Level.UNSUPPORTED, null, null, "database tables are not exposed"),
            new Entry("GET", MG + "/properties", Level.UNSUPPORTED, null, null, "engine properties are not exposed"),
            // identity and CMMN
            new Entry("GET", "/service/identity/users", Level.UNSUPPORTED, null, null,
                    "users and groups come from the identity provider (OIDC), not the engine"),
            new Entry("GET", "/service/identity/groups", Level.UNSUPPORTED, null, null,
                    "users and groups come from the identity provider (OIDC), not the engine"),
            new Entry("GET", "/service/cmmn-runtime/case-instances", Level.PARTIAL, "GET", "/case-instances",
                    "Sangam has its own case API; Flowable case instances and case-linked process instances are not "
                            + "migrated (CASE_LINKED) and stay on Flowable"));

    private FlowableRestCompatibility() {
    }

    /** The entry for a concrete Flowable request; empty means UNSUPPORTED. */
    public static Optional<Entry> classify(String method, String path) {
        String p = path.toLowerCase(Locale.ROOT).contains("://") ? path.substring(path.indexOf('/', path.indexOf("://") + 3)) : path;
        int q = p.indexOf('?');
        String bare = q < 0 ? p : p.substring(0, q);
        String norm = bare.endsWith("/") && bare.length() > 1 ? bare.substring(0, bare.length() - 1) : bare;
        return ENTRIES.stream().filter(e -> e.matches(method, norm)).findFirst();
    }

    public static Level level(String method, String path) {
        return classify(method, path).map(Entry::level).orElse(Level.UNSUPPORTED);
    }
}
