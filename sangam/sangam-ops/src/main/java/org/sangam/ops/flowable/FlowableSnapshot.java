// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * M-135: the read-only inventory of a Flowable runtime: one {@link Instance} per process instance of the migrated
 * tenant, with everything the classification and the apply step need, plus the row counts of every runtime table.
 * Lists are sorted by id, so equal state gives an equal {@link Instance#fingerprint()} input.
 *
 * @param instances  the process instances of the source tenant
 * @param tableCounts row counts of every table in {@link FlowableTables#ALL} (whole source, all tenants)
 * @param outOfScope rows the migration does not move, with the reason (standalone tasks, definition-level timers)
 */
public record FlowableSnapshot(List<Instance> instances, Map<String, Long> tableCounts, List<String> outOfScope) {

    public FlowableSnapshot {
        instances = List.copyOf(instances);
        tableCounts = Map.copyOf(tableCounts);
        outOfScope = List.copyOf(outOfScope);
    }

    /**
     * One Flowable process instance.
     *
     * @param timeProblems source timestamps that are ambiguous or do not exist in the source zone (DST); such an
     *                     instance is rejected, never resolved by a guess (decision 150 applied to this migration)
     * @param listeners    the listeners the deployed source model declares (decision 169): ending the instance on
     *                     Flowable after cut-over fires them
     */
    public record Instance(String id, String definitionId, String definitionKey, int definitionVersion, String tenantId,
                           String businessKey, boolean suspended, String superExecutionId, String callbackType,
                           boolean hasSubInstance, List<Execution> executions, List<Task> tasks, List<Job> jobs,
                           List<Subscription> subscriptions, List<Variable> variables, List<String> timeProblems,
                           FlowableModelListeners.Found listeners) {
        public Instance {
            Objects.requireNonNull(listeners, "listeners");
            executions = List.copyOf(executions);
            tasks = List.copyOf(tasks);
            jobs = List.copyOf(jobs);
            subscriptions = List.copyOf(subscriptions);
            variables = List.copyOf(variables);
            timeProblems = List.copyOf(timeProblems);
        }

        /** SHA-256 (hex) of the canonical form; any change of the instance's runtime state changes it. */
        public String fingerprint() {
            return org.sangam.core.layer.Hashes.hex(org.sangam.core.layer.Hashes.sha256(toString()));
        }
    }

    /** An execution; {@code activityId} null for the process instance root. */
    public record Execution(String id, String parentId, String activityId, boolean active, boolean scope,
                            boolean multiInstanceRoot, boolean eventScope) {
    }

    /** A user task with its candidates (from ACT_RU_IDENTITYLINK, type candidate). */
    public record Task(String id, String executionId, String taskDefinitionKey, String name, String assignee,
                       String owner, String delegation, int priority, Instant dueDate, String parentTaskId,
                       String scopeType, List<String> candidateUsers, List<String> candidateGroups) {
        public Task {
            candidateUsers = List.copyOf(candidateUsers);
            candidateGroups = List.copyOf(candidateGroups);
        }
    }

    /** Which Flowable job table a job came from. */
    public enum JobKind { ASYNC, TIMER, SUSPENDED_ASYNC, SUSPENDED_TIMER, DEADLETTER, EXTERNAL }

    /**
     * A job of any table; {@code lockExpiry} and {@code dueDate} null when unset. {@code elementId} is ELEMENT_ID_, or
     * for a {@code trigger-timer} job without it (rows written before Flowable 6.5.0.1) the activity id from
     * HANDLER_CFG_, read as Flowable's {@code TimerEventHandler.getActivityIdFromConfiguration} reads it.
     */
    public record Job(String id, JobKind kind, String executionId, String elementId, String lockOwner,
                      Instant lockExpiry, int retries, String exceptionMessage, Instant dueDate, String repeat,
                      String handlerType) {

        public boolean timer() {
            return kind == JobKind.TIMER || kind == JobKind.SUSPENDED_TIMER;
        }
    }

    /** An event subscription: message, signal or compensate. */
    public record Subscription(String eventType, String eventName, String executionId, String activityId) {
    }

    /**
     * A variable. {@code text} is the exact value as text ({@code null} for a null value); {@code binary} when the
     * value lives in ACT_GE_BYTEARRAY, which the migration never reads (always so for {@code longString}, and for
     * {@code json} or {@code longJson} above the engine's text limit: {@code LongStringType.java:45-51},
     * {@code JsonType.java:121-141}).
     */
    public record Variable(String name, String type, String executionId, String taskId, String text, boolean binary) {
    }
}
