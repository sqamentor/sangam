// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.util.Map;
import java.util.Optional;

import org.sangam.sdk.error.GoneException;
import org.sangam.sdk.error.SangamApiException;
import org.sangam.sdk.error.StaleLeaseException;
import org.sangam.sdk.generated.api.WorkApi;
import org.sangam.sdk.generated.model.WorkStep;
import org.sangam.sdk.generated.model.WorkStepRequest;
import org.sangam.sdk.model.TypedValue;

/**
 * The algorithm of {@link TaskContext#step} (M-025, R37 / E02) on the step journal routes {@code GET/PUT
 * /work/{id}/steps/{stepName}}, fenced by the context's lease:
 *
 * <ol>
 *   <li>read the step: {@code SUCCEEDED} replays the recorded result without calling again;</li>
 *   <li>{@code INTENT} left by an earlier attempt (the crash window): ask the lookup if any (a value is recorded
 *       {@code SUCCEEDED} and returned; empty means no effect), else run again only for an idempotent call, else throw
 *       {@link OutcomeUnknownException};</li>
 *   <li>record {@code INTENT}, run the call, record {@code SUCCEEDED} with the result, or {@code FAILED} on
 *       {@link StepNoEffectException}; any other exception leaves {@code INTENT} (uncertain) and propagates.</li>
 * </ol>
 * A 409 STALE_LEASE or 410 from the journal marks the lease lost and throws {@link TaskCancelledException}.
 * NOT exactly-once for remote APIs: a crash between the remote call and the {@code SUCCEEDED} record leaves
 * {@code INTENT}, which step 2 resolves without a blind re-issue.
 */
final class DurableSteps {

    static final String INTENT = "INTENT";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";

    private DurableSteps() {
    }

    static <T> T run(TaskContext ctx, String name, Class<T> type, StepOptions<T> options, StepCall<T> call)
            throws Exception {
        requireName(name);
        StepOptions<T> opts = options == null ? StepOptions.defaults() : options;
        WorkApi api = ctx.client().api().work();
        WorkStep recorded = read(ctx, api, name);
        String previous = recorded == null ? null : recorded.state();
        Step step = new Step(name, ctx.operationId(), previous);
        if (SUCCEEDED.equals(previous)) {
            ctx.logger().info("step.replayed", "step already succeeded; replaying its recorded result", Map.of("step", name));
            return decode(ctx, recorded.result(), type);
        }
        if (INTENT.equals(previous)) {
            if (opts.lookupOrNull() != null) {
                Optional<T> found = opts.lookupOrNull().lookup(step);
                if (found != null && found.isPresent()) {
                    T value = found.get();
                    write(ctx, api, name, SUCCEEDED, value, step.providerRef(), null);
                    ctx.logger().info("step.looked-up", "earlier attempt took effect; recorded from lookup", Map.of("step", name));
                    return value;
                }
            } else if (!opts.isIdempotent()) {
                throw new OutcomeUnknownException("step " + name + " of operation " + ctx.operationId()
                        + " was started by an earlier attempt and its outcome is unknown (R37)",
                        "step=" + name, Map.of("step", name, "idempotencyKey", step.idempotencyKey()), null);
            }
        }
        write(ctx, api, name, INTENT, null, null, null);
        T value;
        try {
            value = call.run(step);
        } catch (StepNoEffectException e) {
            write(ctx, api, name, FAILED, null, step.providerRef(), String.valueOf(e.getMessage()));
            throw e;
        }
        write(ctx, api, name, SUCCEEDED, value, step.providerRef(), null);
        return value;
    }

    static void requireName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) {
            throw new IllegalArgumentException("step name must be 1-128 characters");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch > 0x7e) {
                throw new IllegalArgumentException("step name must be printable ASCII");
            }
        }
    }

    private static WorkStep read(TaskContext ctx, WorkApi api, String name) {
        try {
            return api.getWorkStep(ctx.workId(), name, ctx.workerId(), ctx.lockVersion(), ctx.attemptId(), null);
        } catch (StaleLeaseException | GoneException e) {
            throw leaseLost(ctx, name, e);
        } catch (SangamApiException e) {
            if (e.status() == 404) {
                return null;   // never recorded: run it
            }
            throw e;
        }
    }

    private static void write(TaskContext ctx, WorkApi api, String name, String state, Object result, String providerRef,
                              String error) {
        try {
            api.recordWorkStep(ctx.workId(), name, new WorkStepRequest(ctx.workerId(), ctx.lockVersion(),
                    ctx.attemptId(), state, result, providerRef, error), null);
        } catch (StaleLeaseException | GoneException e) {
            throw leaseLost(ctx, name, e);
        }
    }

    private static TaskCancelledException leaseLost(TaskContext ctx, String name, RuntimeException cause) {
        ctx.leaseLost = true;
        TaskCancelledException e = new TaskCancelledException("lease lost while recording step " + name);
        e.initCause(cause);
        return e;
    }

    private static <T> T decode(TaskContext ctx, Object raw, Class<T> type) {
        if (raw == null || type == null || type == Void.class) {
            return null;
        }
        return TypedValue.unwrap(raw, type, ctx.json());
    }
}
