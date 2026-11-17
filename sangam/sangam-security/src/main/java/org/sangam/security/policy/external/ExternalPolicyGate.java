// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.sangam.core.engine.IllegalRemoteCallInLockScope;
import org.sangam.core.engine.RemoteCalls;

/**
 * The framework-independent decision at command entry for the external deny-only policy check (owner decision 166,
 * M-083). The host calls {@link #evaluate} once per command, after authentication and before the idempotency claim,
 * the command transaction and any L2 lock; the gate itself refuses to make the remote call while a lock is held
 * ({@link RemoteCalls#assertNoLockHeld}, decision 86).
 *
 * <p>Deny-only by construction: the verdict is either {@link Proceed} (the command goes on to Sangam's own
 * authorisation, which still decides and may still refuse) or {@link Refuse}. Nothing here can grant what Sangam's own
 * authorisation denies.
 *
 * <table>
 *   <caption>Verdict per requirement and answer</caption>
 *   <tr><th>requirement</th><th>ALLOW_CONTINUE</th><th>DENY</th><th>unavailable</th></tr>
 *   <tr><td>REQUIRED</td><td>proceed</td><td>refuse (DENIED)</td><td>refuse (UNAVAILABLE_REFUSED)</td></tr>
 *   <tr><td>BEST_EFFORT</td><td>proceed</td><td>refuse (DENIED)</td><td>proceed (UNAVAILABLE_CONTINUED)</td></tr>
 *   <tr><td>NONE</td><td colspan="3">proceed without a call (SKIPPED)</td></tr>
 * </table>
 */
public final class ExternalPolicyGate {

    /** What happened for one command; also the metric tag. */
    public enum Result {
        SKIPPED, ALLOWED, DENIED, UNAVAILABLE_REFUSED, UNAVAILABLE_CONTINUED
    }

    /** The verdict for one command. */
    public sealed interface Verdict permits Proceed, Refuse {
        Result result();
    }

    /** The command continues to Sangam's own checks. */
    public record Proceed(Result result) implements Verdict {
    }

    /**
     * The command is refused before it starts.
     *
     * @param reasonCode the service's safe reason token for DENIED, the {@link ExternalPolicyUnavailable.Reason} name
     *                   for UNAVAILABLE_REFUSED
     * @param policyId   the service's safe policy id, or null
     */
    public record Refuse(Result result, String reasonCode, String policyId) implements Verdict {
    }

    /** Metrics hook of the host (plain Java, so the gate stays framework-free). */
    @FunctionalInterface
    public interface Observer {
        Observer NONE = (commandType, result, elapsed) -> { };

        void observed(String commandType, Result result, Duration elapsed);
    }

    private final ExternalPolicyCheck check;
    private final ExternalPolicyCommands commands;
    private final Observer observer;
    private final LongSupplier nanoTime;

    public ExternalPolicyGate(ExternalPolicyCheck check, ExternalPolicyCommands commands, Observer observer) {
        this(check, commands, observer, System::nanoTime);
    }

    ExternalPolicyGate(ExternalPolicyCheck check, ExternalPolicyCommands commands, Observer observer,
                       LongSupplier nanoTime) {
        if (check == null || commands == null) {
            throw new IllegalArgumentException("an external policy check and its command table are required");
        }
        this.check = check;
        this.commands = commands;
        this.observer = observer == null ? Observer.NONE : observer;
        this.nanoTime = nanoTime;
    }

    public ExternalPolicyRequirement requirement(String commandType) {
        return commands.requirement(commandType);
    }

    /**
     * Decides one command.
     *
     * @throws IllegalRemoteCallInLockScope when called while this thread holds an L2 root lock (a defect of
     *                                                the caller: the check belongs at command entry, decision 86)
     */
    public Verdict evaluate(ExternalPolicyRequest request) {
        String commandType = request.commandType();
        ExternalPolicyRequirement requirement = commands.requirement(commandType);
        if (requirement == ExternalPolicyRequirement.NONE) {
            observer.observed(commandType, Result.SKIPPED, Duration.ZERO);
            return new Proceed(Result.SKIPPED);
        }
        RemoteCalls.assertNoLockHeld("external policy check " + commandType);
        long start = nanoTime.getAsLong();
        Verdict verdict = decide(request, requirement);
        observer.observed(commandType, verdict.result(), Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - start)));
        return verdict;
    }

    private Verdict decide(ExternalPolicyRequest request, ExternalPolicyRequirement requirement) {
        ExternalPolicyDecision decision;
        try {
            decision = check.check(request);
            if (decision == null) {
                throw new ExternalPolicyUnavailable(ExternalPolicyUnavailable.Reason.BAD_RESPONSE, "no decision");
            }
        } catch (ExternalPolicyUnavailable e) {
            return unavailable(requirement, e.reason());
        } catch (IllegalRemoteCallInLockScope e) {
            throw e;
        } catch (RuntimeException e) {
            // A failing check implementation is no answer: the same fail-closed rule as an unreachable service.
            return unavailable(requirement, ExternalPolicyUnavailable.Reason.CHECK_FAILED);
        }
        if (decision.denied()) {
            return new Refuse(Result.DENIED, decision.reasonCode(), decision.policyId());
        }
        return new Proceed(Result.ALLOWED);
    }

    private static Verdict unavailable(ExternalPolicyRequirement requirement, ExternalPolicyUnavailable.Reason reason) {
        if (requirement == ExternalPolicyRequirement.REQUIRED) {
            return new Refuse(Result.UNAVAILABLE_REFUSED, reason.name(), null);
        }
        return new Proceed(Result.UNAVAILABLE_CONTINUED);
    }
}
