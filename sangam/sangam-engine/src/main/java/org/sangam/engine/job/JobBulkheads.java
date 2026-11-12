// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * M-054 (SANGAM.md §4.4, C7 "semaphore per job type"): a bounded number of concurrent executions per job handler type
 * on one node. A permit is taken on the job executor's thread <b>before</b> the job's command opens, so a waiting job
 * holds no database connection and no transaction; the wait is bounded and ends early when the executor stops.
 *
 * <p>Types without a limit are not counted. The limits come from {@code SangamEngineSettings.jobBulkheads}.
 */
public final class JobBulkheads {

    /** A taken permit; {@link #release()} is idempotent. */
    public interface Permit {
        void release();
    }

    private static final Permit UNLIMITED = () -> { };
    /** The executor's active flag is re-checked at least this often while a job waits. */
    static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private final Map<String, Semaphore> limits;
    private final long waitNanos;

    /**
     * @param limits job handler type -> maximum concurrent executions (each at least 1)
     * @param wait   the longest a job waits for a permit ({@code 0} = do not wait)
     * @throws IllegalArgumentException on a blank type, a missing or non-positive limit, or a negative or missing wait
     */
    public JobBulkheads(Map<String, Integer> limits, Duration wait) {
        if (wait == null || wait.isNegative()) {
            throw new IllegalArgumentException("sangam.engine.job-bulkhead-wait must be zero or positive: " + wait);
        }
        Map<String, Semaphore> m = new LinkedHashMap<>();
        if (limits != null) {
            for (Map.Entry<String, Integer> e : limits.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    throw new IllegalArgumentException("sangam.engine.job-bulkheads: blank job handler type");
                }
                if (e.getValue() == null || e.getValue() < 1) {
                    throw new IllegalArgumentException("sangam.engine.job-bulkheads." + e.getKey()
                            + " must be at least 1: " + e.getValue());
                }
                m.put(e.getKey().trim(), new Semaphore(e.getValue()));
            }
        }
        this.limits = Map.copyOf(m);
        this.waitNanos = wait.toNanos();
    }

    /** {@code true} when no type has a limit. */
    public boolean isEmpty() {
        return limits.isEmpty();
    }

    /** {@code true} when {@code jobHandlerType} has a limit. */
    public boolean limits(String jobHandlerType) {
        return jobHandlerType != null && limits.containsKey(jobHandlerType);
    }

    /** Free permits of {@code jobHandlerType}, or {@code -1} when the type has no limit. */
    public int available(String jobHandlerType) {
        Semaphore s = jobHandlerType == null ? null : limits.get(jobHandlerType);
        return s == null ? -1 : s.availablePermits();
    }

    /**
     * Takes a permit for {@code jobHandlerType}, waiting up to the configured wait while {@code active} stays true.
     *
     * @return the permit; a no-op permit for a type without a limit (or {@code null} type); {@code null} when no
     *         permit was free in time, the executor stopped ({@code active} false) or the thread was interrupted
     *         (the interrupt flag is kept)
     */
    public Permit acquire(String jobHandlerType, BooleanSupplier active) {
        Semaphore s = jobHandlerType == null ? null : limits.get(jobHandlerType);
        if (s == null) {
            return UNLIMITED;
        }
        try {
            if (s.tryAcquire()) {
                return once(s);
            }
            long deadline = System.nanoTime() + waitNanos;
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0 || !active.getAsBoolean()) {
                    return null;
                }
                if (s.tryAcquire(Math.min(left, SLICE_NANOS), TimeUnit.NANOSECONDS)) {
                    return once(s);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Permit once(Semaphore s) {
        return new Permit() {
            private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void release() {
                if (released.compareAndSet(false, true)) {
                    s.release();
                }
            }
        };
    }
}
