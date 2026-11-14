// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import java.util.List;
import java.util.Objects;

/**
 * M-135: the classified plan of one Flowable process instance, in target (Operaton) terms. A {@link Status#SUPPORTED}
 * plan is everything the apply step needs; a {@link Status#REJECTED} plan lists why it cannot move (it stays on
 * Flowable). The plan is stored as JSON with the run item, so dates are epoch milliseconds and variable values exact
 * text with their type.
 *
 * @param startActivities target activity ids to start (wait states only), sorted
 * @param messageNames    names of the message and signal subscriptions the instance holds, sorted (a multiset)
 * @param fingerprint     the source instance fingerprint at classification ({@link FlowableSnapshot.Instance#fingerprint()})
 */
public record InstancePlan(String sourceInstanceId, String sourceDefinitionId, String definitionKey, int sourceVersion,
                           String tenantId, String targetDefinitionId, String businessKey, boolean suspended,
                           List<String> startActivities, List<TaskState> tasks, List<TimerState> timers,
                           List<String> messageNames, List<VariableValue> variables, Status status,
                           List<Finding> findings, String fingerprint) {

    public InstancePlan {
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        startActivities = startActivities == null ? List.of() : List.copyOf(startActivities);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        timers = timers == null ? List.of() : List.copyOf(timers);
        messageNames = messageNames == null ? List.of() : List.copyOf(messageNames);
        variables = variables == null ? List.of() : List.copyOf(variables);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public enum Status { SUPPORTED, REJECTED }

    /** REJECT stops the instance; WARN moves it and is reported for review. */
    public enum Severity { REJECT, WARN }

    /** One classification finding with a stable code ({@link StateClassifier} lists them). */
    public record Finding(Severity severity, String code, String elementId, String message) {
    }

    /** A user task to recreate exactly: identity, delegation, priority, due date, candidates, task-local variables. */
    public record TaskState(String activityId, String name, String assignee, String owner, String delegation,
                            int priority, Long dueDateMillis, List<String> candidateUsers, List<String> candidateGroups,
                            List<VariableValue> localVariables) {
        public TaskState {
            candidateUsers = candidateUsers == null ? List.of() : List.copyOf(candidateUsers);
            candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
            localVariables = localVariables == null ? List.of() : List.copyOf(localVariables);
        }
    }

    /** A timer whose original due date is kept (an overdue timer stays overdue). */
    public record TimerState(String activityId, long dueDateMillis, String repeat) {
    }

    /**
     * A variable value as exact text. Types: {@code string}, {@code long}, {@code integer}, {@code short},
     * {@code double}, {@code boolean}, {@code date} (epoch milliseconds), {@code null}.
     */
    public record VariableValue(String name, String type, String text) {

        /** The Java value the engine stores. */
        public Object value() {
            if (text == null || "null".equals(type)) {
                return null;
            }
            return switch (type) {
                case "string" -> text;
                case "long" -> Long.parseLong(text);
                case "integer" -> Integer.parseInt(text);
                case "short" -> Short.parseShort(text);
                case "double" -> Double.parseDouble(text);
                case "boolean" -> Boolean.parseBoolean(text);
                case "date" -> new java.util.Date(Long.parseLong(text));
                default -> throw new IllegalArgumentException("unsupported variable type " + type);
            };
        }

        /** The same value read back from the engine, as {@link VariableValue} text (for reconciliation). */
        public static VariableValue of(String name, Object value) {
            if (value == null) {
                return new VariableValue(name, "null", null);
            }
            return switch (value) {
                case String s -> new VariableValue(name, "string", s);
                case Long l -> new VariableValue(name, "long", l.toString());
                case Integer i -> new VariableValue(name, "integer", i.toString());
                case Short s -> new VariableValue(name, "short", s.toString());
                case Double d -> new VariableValue(name, "double", d.toString());
                case Boolean b -> new VariableValue(name, "boolean", b.toString());
                case java.util.Date d -> new VariableValue(name, "date", Long.toString(d.getTime()));
                default -> new VariableValue(name, value.getClass().getName(), value.toString());
            };
        }
    }
}
