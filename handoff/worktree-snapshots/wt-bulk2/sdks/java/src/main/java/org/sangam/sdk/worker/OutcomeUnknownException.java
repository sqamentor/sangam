// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.util.Map;

/**
 * The handler cannot know whether the downstream effect happened (for example a payment call timed out after
 * the request was sent). The worker reports {@code outcome-unknown}; the engine opens an UNKNOWN_OUTCOME
 * reconciliation item and never repeats the effect automatically (VI.3.2 rules 4–5, AC-03).
 */
public class OutcomeUnknownException extends Exception {

    private final String details;
    private final Map<String, Object> variables;

    public OutcomeUnknownException(String reason) {
        this(reason, null, null, null);
    }

    public OutcomeUnknownException(String reason, Throwable cause) {
        this(reason, null, null, cause);
    }

    public OutcomeUnknownException(String reason, String details, Map<String, Object> variables, Throwable cause) {
        super(reason, cause);
        this.details = details;
        this.variables = variables;
    }

    public String details() {
        return details;
    }

    /** Sent as {@code evidence} (VariableMap) on the reconciliation item: provider reference, request handle, ... */
    public Map<String, Object> variables() {
        return variables;
    }
}
