// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a dry run: every problem every step and the readiness checklist would report, and what each step would
 * do. Nothing was changed.
 *
 * @param tenantId    tenant
 * @param ok          no ERROR problem (the real run would reach the checker)
 * @param problems    all problems, in step order
 * @param plan        per step: what it would do (packs in deployment order, profile documents, bindings ...)
 * @param readiness   the readiness checklist evaluated on the planned state
 * @param requestHash hash of the request that was checked
 */
public record DryRunReport(String tenantId, boolean ok, List<Problem> problems, Map<StepId, Map<String, Object>> plan,
                           OnboardingRun.ReadinessReport readiness, String requestHash) {
    public DryRunReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
        plan = plan == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(plan));
    }
}
