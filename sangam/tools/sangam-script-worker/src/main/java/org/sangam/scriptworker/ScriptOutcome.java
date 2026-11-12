// SPDX-License-Identifier: Apache-2.0
package org.sangam.scriptworker;

import java.util.Map;

/** How one script execution ended; {@link ScriptTaskHandler} maps each case onto the lease protocol (VI.3). */
public sealed interface ScriptOutcome {

    /** The script finished; {@code variables} complete the work item. */
    record Completed(Map<String, Object> variables) implements ScriptOutcome {
    }

    /** The script raised a BPMN error on purpose; the model routes it. */
    record BpmnErrorRaised(String code, String message, Map<String, Object> variables) implements ScriptOutcome {
    }

    /** The script threw: deterministic, so an incident instead of retries. */
    record ScriptError(String message, String details) implements ScriptOutcome {
    }

    /** CPU, memory, process, wall-time or output limit hit: an incident, the limits are configuration. */
    record LimitExceeded(String message, String details) implements ScriptOutcome {
    }

    /**
     * The sandbox did not run the script (cannot start, or ended without the harness's answer). A script has no
     * network and no lasting file system, so it had no outside effect: a retry is safe.
     */
    record SandboxFailure(String message, String details) implements ScriptOutcome {
    }

    /** The server asked to cancel, or the lease was lost: the jail was killed. */
    record Cancelled(String reason) implements ScriptOutcome {
    }
}
