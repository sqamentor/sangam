// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Handler registration for one topic.
 *
 * @param topic          topic name (BPMN {@code operaton:topic})
 * @param handler        the handler
 * @param lockDuration   lease per fetch (VI.3.2 rule 3: default 5 min, max 24 h); heartbeat at lease/3
 * @param variables      variables to fetch (null = all)
 * @param retries        retries for the first failure when the work item carries none
 * @param retryBackoff   back-off used for {@code retryTimeoutMs} on fail
 * @param localVariables       fetch only local variables of the activity
 * @param processDefinitionKey only work of this process definition
 * @param businessKey          only work of this business key
 * @param irreversible   treat unexpected handler exceptions as outcome-unknown even if the server does not mark
 *                       the topic {@code effect=irreversible}
 */
public record TopicSubscription(String topic, TaskHandler handler, Duration lockDuration, List<String> variables,
                                int retries, Backoff retryBackoff, boolean irreversible,
                                Boolean localVariables, String processDefinitionKey, String businessKey) {

    public static final Duration MAX_LOCK = Duration.ofHours(24);

    public TopicSubscription {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(handler, "handler");
        if (lockDuration == null) lockDuration = Duration.ofMinutes(5);
        if (lockDuration.compareTo(MAX_LOCK) > 0) throw new IllegalArgumentException("lockDuration exceeds 24 h (VI.3)");
        if (lockDuration.toMillis() < 3_000) throw new IllegalArgumentException("lockDuration must be at least 3 s");
        if (retries < 0) throw new IllegalArgumentException("retries must be >= 0");
        if (retryBackoff == null) retryBackoff = Backoff.retryDefaults();
        variables = variables == null ? null : List.copyOf(variables);
    }

    public static Builder topic(String topic, TaskHandler handler) {
        return new Builder(topic, handler);
    }

    public static final class Builder {
        private final String topic;
        private final TaskHandler handler;
        private Duration lockDuration = Duration.ofMinutes(5);
        private List<String> variables;
        private int retries = 3;
        private Backoff retryBackoff = Backoff.retryDefaults();
        private boolean irreversible;
        private Boolean localVariables;
        private String processDefinitionKey;
        private String businessKey;

        Builder(String topic, TaskHandler handler) {
            this.topic = topic;
            this.handler = handler;
        }

        public Builder lockDuration(Duration v) { this.lockDuration = v; return this; }
        public Builder variables(List<String> v) { this.variables = v; return this; }
        public Builder retries(int v) { this.retries = v; return this; }
        public Builder retryBackoff(Backoff v) { this.retryBackoff = v; return this; }
        public Builder irreversible(boolean v) { this.irreversible = v; return this; }
        public Builder localVariables(boolean v) { this.localVariables = v; return this; }
        public Builder processDefinitionKey(String v) { this.processDefinitionKey = v; return this; }
        public Builder businessKey(String v) { this.businessKey = v; return this; }

        public TopicSubscription build() {
            return new TopicSubscription(topic, handler, lockDuration, variables, retries, retryBackoff, irreversible,
                    localVariables, processDefinitionKey, businessKey);
        }
    }
}
