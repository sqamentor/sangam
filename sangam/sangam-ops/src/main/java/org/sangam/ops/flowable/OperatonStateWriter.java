// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import org.sangam.ops.flowable.InstancePlan.TaskState;
import org.sangam.ops.flowable.InstancePlan.TimerState;
import org.sangam.ops.flowable.InstancePlan.VariableValue;

import java.util.List;
import java.util.Optional;

/**
 * M-135: the target side of the migration, as a framework-independent port. {@link OperatonEngineStateWriter} is the
 * Operaton implementation; the pipeline and its tests know only this interface.
 */
public interface OperatonStateWriter {

    /** The deployed target definitions and their activities (classification input). */
    interface TargetModel {

        /** The definition id of {@code key} at exactly {@code version} in {@code tenantId} (null = no tenant). */
        Optional<String> definitionId(String key, int version, String tenantId);

        /** What {@code activityId} is in that definition; {@link ActivityKind#MISSING} when absent. */
        ActivityFacts activity(String definitionId, String activityId);
    }

    /** The kinds of target activity that decide whether a source wait state can be recreated without effects. */
    enum ActivityKind {
        USER_TASK, RECEIVE_TASK, CATCH_EVENT, EVENT_GATEWAY, EXTERNAL_TASK, BOUNDARY_EVENT, CALL_ACTIVITY,
        /** inside an event subprocess (its scope cannot be recreated as it was). */
        IN_EVENT_SUBPROCESS,
        /** a multi-instance activity or inside one. */
        IN_MULTI_INSTANCE,
        /** any other activity: it would run (and cause effects) as soon as it starts, unless asyncBefore. */
        OTHER,
        MISSING
    }

    /**
     * @param asyncBefore          starting before the activity creates a job instead of running it
     * @param customTaskListeners  a user task declares its own task listeners; recreating its assignee, candidates and
     *                             dates through the task API would fire them (assignment or update), so such a task is
     *                             rejected rather than risk a repeated notification or other effect
     */
    record ActivityFacts(ActivityKind kind, boolean asyncBefore, boolean customTaskListeners) {
        public static ActivityFacts missing() {
            return new ActivityFacts(ActivityKind.MISSING, false, false);
        }
    }

    /** The runtime state of a migrated target instance, in plan terms (reconciliation and rollback checks). */
    record TargetState(boolean exists, boolean suspended, List<String> activeActivities, List<TaskState> tasks,
                       List<TimerState> timers, List<String> messageNames, List<VariableValue> variables,
                       long finishedTasks, long executedJobs) {
        public static TargetState absent() {
            return new TargetState(false, false, List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0);
        }
    }

    /**
     * The target instance already created for {@code provenance} (a retry after a lost answer adopts it instead of
     * creating a second one).
     */
    Optional<String> findMigrated(String provenance);

    /**
     * Creates the target instance of a SUPPORTED plan in ONE engine transaction: start at the wait activities without
     * custom listeners or IO mappings, then tasks, candidates, task-local variables, timer due dates and suspension.
     * Any failure rolls the whole instance back. The provenance is stored on the instance.
     *
     * @return the target process instance id
     */
    String apply(InstancePlan plan, String provenance);

    TargetState state(String targetInstanceId);

    /** Deletes a migrated instance without listeners or IO mappings (rollback). */
    void delete(String targetInstanceId, String reason);
}
