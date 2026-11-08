// SPDX-License-Identifier: Apache-2.0
package org.sangam.onboarding.api;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State of one onboarding run (persisted after every step, so the flow is resumable).
 *
 * @param id                run id (also the process business key)
 * @param tenantId          the tenant being onboarded
 * @param status            run status
 * @param maker             who started (and amended) the run
 * @param checker           who approved or rejected go-live
 * @param revision          request revision; amendments increase it
 * @param request           the current request
 * @param requestHash       SHA-256 of the canonical request (the checker's approval binds to it, with the readiness hash)
 * @param processInstanceId engine process instance
 * @param steps             state per step
 * @param readiness         the last readiness report (null before READINESS ran)
 * @param createdAt         creation time
 * @param updatedAt         last change
 */
public record OnboardingRun(String id, String tenantId, Status status, String maker, String checker, int revision,
                            OnboardingRequest request, String requestHash, String processInstanceId,
                            Map<StepId, StepState> steps, ReadinessReport readiness, Instant createdAt,
                            Instant updatedAt) {

    public enum Status {
        /** The process runs the configuration steps. */
        RUNNING,
        /** Readiness found errors; the maker amends the request and re-checks. */
        WAITING_FOR_FIX,
        /** Readiness passed; waiting for the checker. */
        AWAITING_APPROVAL,
        /** Approved; the go-live step runs. */
        APPROVED,
        /** The tenant is live. */
        LIVE,
        /** The checker rejected go-live. */
        REJECTED,
        /** A step failed after its retries; resume after fixing the cause. */
        FAILED,
        /** Cancelled by an administrator. */
        CANCELLED
    }

    public enum StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    /**
     * @param status   step status
     * @param attempts executions so far
     * @param startedAt last start
     * @param endedAt   last end
     * @param output    what the step did (ids, counts, deployment ids)
     * @param problems  findings of the last execution
     * @param revision  request revision the step last completed for
     */
    public record StepState(StepStatus status, int attempts, Instant startedAt, Instant endedAt,
                            Map<String, Object> output, List<Problem> problems, int revision) {
        public StepState {
            output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public static StepState pending() {
            return new StepState(StepStatus.PENDING, 0, null, null, null, null, 0);
        }
    }

    public OnboardingRun {
        Map<StepId, StepState> m = new EnumMap<>(StepId.class);
        for (StepId s : StepId.values()) {
            m.put(s, steps == null || steps.get(s) == null ? StepState.pending() : steps.get(s));
        }
        steps = Collections.unmodifiableMap(m);
    }

    public StepState step(StepId s) {
        return steps.get(s);
    }

    public OnboardingRun withStatus(Status s, Instant now) {
        return new OnboardingRun(id, tenantId, s, maker, checker, revision, request, requestHash, processInstanceId,
                steps, readiness, createdAt, now);
    }

    public OnboardingRun withStep(StepId s, StepState state, Instant now) {
        Map<StepId, StepState> m = new EnumMap<>(steps);
        m.put(s, state);
        return new OnboardingRun(id, tenantId, status, maker, checker, revision, request, requestHash,
                processInstanceId, m, readiness, createdAt, now);
    }

    public OnboardingRun withReadiness(ReadinessReport r, Instant now) {
        return new OnboardingRun(id, tenantId, status, maker, checker, revision, request, requestHash,
                processInstanceId, steps, r, createdAt, now);
    }

    public OnboardingRun withProcess(String pid, Instant now) {
        return new OnboardingRun(id, tenantId, status, maker, checker, revision, request, requestHash, pid, steps,
                readiness, createdAt, now);
    }

    public OnboardingRun withRequest(OnboardingRequest r, String hash, String by, Instant now) {
        return new OnboardingRun(id, tenantId, status, by == null ? maker : by, checker, revision + 1, r, hash,
                processInstanceId, steps, readiness, createdAt, now);
    }

    public OnboardingRun withChecker(String who, Instant now) {
        return new OnboardingRun(id, tenantId, status, maker, who, revision, request, requestHash, processInstanceId,
                steps, readiness, createdAt, now);
    }

    /**
     * The go-live readiness report.
     *
     * @param ready      no ERROR problem
     * @param problems   every problem found
     * @param checks     check name to PASS / FAIL / WARN
     * @param hash       SHA-256 of the canonical report (the checker approves this hash)
     * @param checkedAt  when
     * @param revision   request revision checked
     */
    public record ReadinessReport(boolean ready, List<Problem> problems, Map<String, String> checks, String hash,
                                  Instant checkedAt, int revision) {
        public ReadinessReport {
            problems = problems == null ? List.of() : List.copyOf(problems);
            checks = checks == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(checks));
        }
    }
}
