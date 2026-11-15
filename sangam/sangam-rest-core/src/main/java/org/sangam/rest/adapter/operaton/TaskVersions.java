// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.util.Map;

import org.sangam.rest.port.ApiProblem;

/**
 * The optimistic checks of a decision on a case: what the caller saw must still be what the engine holds (K9 case
 * version and form version, K4 material payload hash). Shared by the task, process and instance-handler adapters.
 */
final class TaskVersions {

    private TaskVersions() {
    }

    /** K9 + K4: case version, form version and material payload hash must match what the caller saw. */
    static void check(Map<String, Object> vars, Long caseVersion, String payloadHash, String formVersion) {
        long current = caseVersion(vars);
        if (caseVersion != null && caseVersion != current) {
            throw ApiProblem.conflict("FORM_VERSION_STALE", "This case changed since you opened it (version "
                    + caseVersion + " to " + current + ")").with("currentVersion", current);
        }
        Object fv = vars.get(OperatonTaskAdapter.FORM_VERSION);
        if (formVersion != null && fv != null && !formVersion.equals(String.valueOf(fv))) {
            throw ApiProblem.conflict("FORM_VERSION_STALE", "The form version of this task is " + fv)
                    .with("currentFormVersion", String.valueOf(fv));
        }
        if (payloadHash != null) {
            String now = PayloadHash.hash(vars);
            if (!now.equalsIgnoreCase(payloadHash)) {
                throw ApiProblem.conflict("APPROVAL_INVALIDATED",
                        "The material payload differs from the one you reviewed; reload before deciding")
                        .with("currentPayloadHash", now);
            }
        }
    }

    /** The case version of an instance: the {@code sangamCaseVersion} variable, 1 when absent or unreadable. */
    static long caseVersion(Map<String, ?> vars) {
        Object v = vars == null ? null : vars.get(OperatonTaskAdapter.CASE_VERSION);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }
}
