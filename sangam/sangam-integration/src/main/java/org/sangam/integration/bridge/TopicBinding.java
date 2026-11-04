// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Binds an external-task topic to a connector call (R71 service registry → BPMN service task; Flowable FC-06/FC-07
 * HTTP/mail task as external work).
 *
 * @param topic            external-task topic, e.g. {@code sangam.ifms.pay}
 * @param connectorId      registry id of the connector
 * @param operation        connector operation
 * @param inputVariables   process variable → connector input key; empty = pass the variable map named
 *                         {@code connectorInput} (or all variables when absent)
 * @param fixedInput       constant input merged under the variables (template ids, paths)
 * @param resultVariable   variable that receives the connector output map (default {@code <topic>.result})
 * @param retrySafeKeyVar  variable whose value is the retry-safe key of R36 (a model-level loop that legitimately
 *                         repeats an effect); null = none
 * @param lockDuration     lease per fetch (VI.3.2 rule 3; default 5 min, max 24 h)
 * @param maxRetries       retries for transient failures before an incident (R28 default 3)
 * @param retryBackoff     back-off between transient retries
 */
public record TopicBinding(String topic, String connectorId, String operation, Map<String, String> inputVariables,
                           Map<String, Object> fixedInput, String resultVariable, String retrySafeKeyVar,
                           Duration lockDuration, int maxRetries, Duration retryBackoff, List<String> fetchVariables) {

    public TopicBinding {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(connectorId, "connectorId");
        inputVariables = inputVariables == null ? Map.of() : Map.copyOf(inputVariables);
        fixedInput = fixedInput == null ? Map.of() : Map.copyOf(fixedInput);
        resultVariable = resultVariable == null ? topic.replace('.', '_') + "_result" : resultVariable;
        lockDuration = lockDuration == null ? Duration.ofMinutes(5) : lockDuration;
        if (lockDuration.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("lease longer than 24 h (VI.3.2 rule 3)");
        }
        maxRetries = maxRetries <= 0 ? 3 : maxRetries;
        retryBackoff = retryBackoff == null ? Duration.ofMinutes(1) : retryBackoff;
        fetchVariables = fetchVariables == null ? List.of() : List.copyOf(fetchVariables);
    }

    public static TopicBinding of(String topic, String connectorId, String operation) {
        return new TopicBinding(topic, connectorId, operation, null, null, null, null, null, 0, null, null);
    }
}
