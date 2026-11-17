// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sangam.core.engine.IllegalRemoteCallInLockScope;
import org.sangam.engine.tx.LockScope;

/**
 * Owner decision 166 (M-083): the gate's verdict table. Deny wins for every checked command; an allow only continues
 * (it is never a grant); an unavailable service refuses a REQUIRED command and lets a BEST_EFFORT one continue; a
 * failing or silent check counts as unavailable; NONE makes no call; and the remote call is never made while an L2 lock
 * is held (decision 86). Written for the test phase (not run here).
 */
class ExternalPolicyGateTest {

    static final List<String> KNOWN = List.of("task.complete", "process.start", "process.cancel");

    static ExternalPolicyRequest request(String commandType) {
        return new ExternalPolicyRequest("t1", "u1", "OIDC", Set.of("clerk"), commandType, "POST",
                "/tasks/{taskId}/complete", Map.of("taskId", "task-1"));
    }

    static ExternalPolicyCommands commands(List<String> required, List<String> bestEffort) {
        return ExternalPolicyCommands.of(KNOWN, required, bestEffort);
    }

    static ExternalPolicyCheck unavailable(ExternalPolicyUnavailable.Reason reason) {
        return r -> {
            throw new ExternalPolicyUnavailable(reason, "test");
        };
    }

    @Test
    void aDenyRefusesARequiredAndABestEffortCommand() {
        ExternalPolicyCheck deny = r -> ExternalPolicyDecision.deny("OUTSIDE_OFFICE_HOURS", "policy-7");
        for (ExternalPolicyCommands c : List.of(commands(List.of("*"), List.of()), commands(List.of(), List.of("*")))) {
            var v = new ExternalPolicyGate(deny, c, null).evaluate(request("task.complete"));
            var refuse = assertInstanceOf(ExternalPolicyGate.Refuse.class, v);
            assertEquals(ExternalPolicyGate.Result.DENIED, refuse.result());
            assertEquals("OUTSIDE_OFFICE_HOURS", refuse.reasonCode());
            assertEquals("policy-7", refuse.policyId());
        }
    }

    @Test
    void anAllowOnlyContinuesAndIsNeverAGrant() {
        var v = new ExternalPolicyGate(r -> ExternalPolicyDecision.allowContinue("p"), commands(List.of("*"), List.of()), null)
                .evaluate(request("task.complete"));
        var proceed = assertInstanceOf(ExternalPolicyGate.Proceed.class, v);
        assertEquals(ExternalPolicyGate.Result.ALLOWED, proceed.result());
        // the verdict type has no "granted" form: Proceed hands the command to Sangam's own checks, which still decide
        assertEquals(2, ExternalPolicyGate.Verdict.class.getPermittedSubclasses().length);
        assertEquals(List.of(ExternalPolicyDecision.Outcome.ALLOW_CONTINUE, ExternalPolicyDecision.Outcome.DENY),
                List.of(ExternalPolicyDecision.Outcome.values()));
    }

    @Test
    void anUnavailableServiceRefusesARequiredCommandFailClosed() {
        for (ExternalPolicyUnavailable.Reason reason : ExternalPolicyUnavailable.Reason.values()) {
            var v = new ExternalPolicyGate(unavailable(reason), commands(List.of("task.complete"), List.of()), null)
                    .evaluate(request("task.complete"));
            var refuse = assertInstanceOf(ExternalPolicyGate.Refuse.class, v, reason.name());
            assertEquals(ExternalPolicyGate.Result.UNAVAILABLE_REFUSED, refuse.result());
            assertEquals(reason.name(), refuse.reasonCode());
            assertNull(refuse.policyId());
        }
    }

    @Test
    void aTimeoutRefusesARequiredCommand() {
        var v = new ExternalPolicyGate(unavailable(ExternalPolicyUnavailable.Reason.TIMEOUT),
                commands(List.of("*"), List.of()), null).evaluate(request("process.start"));
        assertEquals(new ExternalPolicyGate.Refuse(ExternalPolicyGate.Result.UNAVAILABLE_REFUSED, "TIMEOUT", null), v);
    }

    @Test
    void anUnavailableServiceLetsABestEffortCommandContinue() {
        var v = new ExternalPolicyGate(unavailable(ExternalPolicyUnavailable.Reason.TRANSPORT),
                commands(List.of("*"), List.of("process.cancel")), null).evaluate(request("process.cancel"));
        assertEquals(new ExternalPolicyGate.Proceed(ExternalPolicyGate.Result.UNAVAILABLE_CONTINUED), v);
    }

    @Test
    void aFailingOrSilentCheckCountsAsUnavailable() {
        ExternalPolicyCheck throwing = r -> {
            throw new IllegalStateException("bug in the adapter");
        };
        var v1 = new ExternalPolicyGate(throwing, commands(List.of("*"), List.of()), null).evaluate(request("task.complete"));
        assertEquals(new ExternalPolicyGate.Refuse(ExternalPolicyGate.Result.UNAVAILABLE_REFUSED, "CHECK_FAILED", null), v1);
        var v2 = new ExternalPolicyGate(r -> null, commands(List.of("*"), List.of()), null).evaluate(request("task.complete"));
        assertEquals(new ExternalPolicyGate.Refuse(ExternalPolicyGate.Result.UNAVAILABLE_REFUSED, "BAD_RESPONSE", null), v2);
    }

    @Test
    void aCommandThatDoesNotUseTheCheckMakesNoCall() {
        AtomicInteger calls = new AtomicInteger();
        ExternalPolicyCheck counting = r -> {
            calls.incrementAndGet();
            return ExternalPolicyDecision.deny("X", null);
        };
        var v = new ExternalPolicyGate(counting, commands(List.of("task.complete"), List.of()), null)
                .evaluate(request("process.start"));
        assertEquals(new ExternalPolicyGate.Proceed(ExternalPolicyGate.Result.SKIPPED), v);
        assertEquals(0, calls.get());
    }

    @Test
    void theRemoteCallIsNeverMadeWhileAnL2LockIsHeld() {
        AtomicInteger calls = new AtomicInteger();
        ExternalPolicyCheck counting = r -> {
            calls.incrementAndGet();
            return ExternalPolicyDecision.allowContinue(null);
        };
        ExternalPolicyGate gate = new ExternalPolicyGate(counting, commands(List.of("*"), List.of()), null);
        LockScope.enter();
        try {
            assertThrows(IllegalRemoteCallInLockScope.class, () -> gate.evaluate(request("task.complete")));
        } finally {
            LockScope.exit();
        }
        assertEquals(0, calls.get());
        assertFalse(LockScope.isLockHeld());
        assertInstanceOf(ExternalPolicyGate.Proceed.class, gate.evaluate(request("task.complete")));
        assertEquals(1, calls.get());
    }

    @Test
    void aLockScopeViolationInsideTheCheckIsNotTurnedIntoAnAnswer() {
        ExternalPolicyCheck violating = r -> {
            throw new IllegalRemoteCallInLockScope("HTTP POST");
        };
        ExternalPolicyGate gate = new ExternalPolicyGate(violating, commands(List.of(), List.of("*")), null);
        assertThrows(IllegalRemoteCallInLockScope.class, () -> gate.evaluate(request("task.complete")));
    }

    @Test
    void theObserverSeesEveryResultWithItsDuration() {
        List<String> seen = new ArrayList<>();
        long[] clock = {0};
        ExternalPolicyCheck slow = r -> {
            clock[0] += 7_000_000;
            return ExternalPolicyDecision.allowContinue(null);
        };
        ExternalPolicyGate gate = new ExternalPolicyGate(slow, commands(List.of("task.complete"), List.of()),
                (c, result, elapsed) -> seen.add(c + "=" + result + "@" + elapsed.toMillis()), () -> clock[0]);
        gate.evaluate(request("task.complete"));
        gate.evaluate(request("process.start"));
        assertEquals(List.of("task.complete=ALLOWED@7", "process.start=SKIPPED@0"), seen);
    }
}
