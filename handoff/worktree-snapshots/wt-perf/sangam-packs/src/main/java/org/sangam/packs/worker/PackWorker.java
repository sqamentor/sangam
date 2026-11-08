// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.worker;

import java.util.Map;

/**
 * Handler for an external-task topic used by pack models ({@code operaton:type="external"}). The handler is
 * transport-neutral: the Sangam worker API (fetch-and-lock with lockVersion fencing, VI.3) and the Tier-1 SDKs call it
 * through {@code sangam-rest}; {@link EmbeddedWorkerRunner} calls it in the developer profile.
 */
public interface PackWorker {

    /** Topic name, for example {@code sangam.docs.bulk}. */
    String topic();

    /** Capability class declaration of the topic (A-2): {@code a}, {@code b} or {@code c}. */
    default String capabilityClass() {
        return "c";
    }

    /**
     * Performs the work. The operationId is stable across attempts (VI.3 rule 4); the handler must pass it to the
     * external system as the idempotency key.
     *
     * @return variables to complete the task with
     * @throws WorkerBpmnError for a business error the model handles
     */
    Map<String, Object> handle(String tenantId, String businessKey, String operationId, Map<String, Object> variables);

    /** A business error for the model's error boundary event. */
    class WorkerBpmnError extends RuntimeException {
        private final String code;

        public WorkerBpmnError(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
