// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.util.Map;

/** A business error that the BPMN model routes (error boundary event). Not retried. */
public class BpmnError extends Exception {

    private final String errorCode;
    private final Map<String, Object> variables;

    public BpmnError(String errorCode, String message) {
        this(errorCode, message, null);
    }

    public BpmnError(String errorCode, String message, Map<String, Object> variables) {
        super(message);
        this.errorCode = errorCode;
        this.variables = variables;
    }

    public String errorCode() {
        return errorCode;
    }

    public Map<String, Object> variables() {
        return variables;
    }
}
