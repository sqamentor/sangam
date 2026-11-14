// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The waiting half of the worker long-poll without a parked request thread (M-053; R9, CF-3, FLOWABLE-INTO-OPERATON D-40, R22).
 *
 * <p>Before, {@code fetch-and-lock} slept in the servlet thread for up to {@code asyncResponseTimeoutMs} (30 s), so
 * every waiting worker held a request thread. Now the controller makes the first attempt in the request thread and,
 * when nothing was found, hands the request to this class and releases the thread (an async servlet response).
 * Each waiter then polls again on the same schedule as the old loop (100 ms, doubling to the 1 s cap; R1: this poll
 * stays authoritative), and at once when {@link #wakeUp()} is called (M-005: the relay's {@code pg_notify}, received
 * by every node). At the deadline it makes a last attempt and answers what it found, an empty list when nothing; the
 * response shapes are the synchronous ones.
 *
 * <ul>
 *   <li><b>Bounded per node.</b> At most {@link Settings#maxWaiters()} requests wait here; {@link #await} returns
 *       null above that and the caller waits the pre-M-053 way (in its thread), so nothing is refused.</li>
 *   <li><b>Bounded database work.</b> Polls run on {@link Settings#pollThreads()} platform threads, one poll per
 *       waiter at a time; a wake-up during a poll makes exactly one more poll right after it.</li>
 *   <li><b>Explicit context.</b> A poll runs on another thread than the request, so the request-thread state that
 *       the fetch path may read is captured on the request thread and bound around every poll: the MDC always, and
 *       whatever the {@link ContextCarrier}s capture (the server adds its tenant identity). Nothing is inherited.</li>
 *   <li><b>Never lost.</b> Work found after the request is gone (client disconnect, async timeout, shutdown) is
 *       handed to the caller's {@code undelivered} callback, in the captured context, so its leases can be released
 *       at once instead of waiting for the lock to expire.</li>
 * </ul>
 * Platform threads, not virtual ones: the supported minimum JDK is 21, where a virtual thread is pinned to its carrier
 * inside {@code synchronized} JDBC and engine code (JEP 491 removes that only from JDK 24 on).
 */
public final class WorkLongPoll implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkLongPoll.class);

    /**
     * @param maxWaiters    waiting requests per node; above it {@link #await} returns null
     * @param pollThreads   threads that run the polls
     * @param firstInterval first poll interval after the request's own attempt (doubles up to {@code maxInterval})
     * @param maxInterval   the authoritative poll interval (R1: 1 s)
     */
    public record Settings(int maxWaiters, int pollThreads, Duration firstInterval, Duration maxInterval) {
        public Settings {
            if (maxWaiters < 0) {
                throw new IllegalArgumentException("maxWaiters must not be negative");
            }
            if (pollThreads < 1) {
                throw new IllegalArgumentException("pollThreads must be at least 1");
            }
            Objects.requireNonNull(firstInterval, "firstInterval");
            Objects.requireNonNull(maxInterval, "maxInterval");
            if (firstInterval.toMillis() < 1 || maxInterval.compareTo(firstInterval) < 0) {
                throw new IllegalArgumentException("need 1 ms <= firstInterval <= maxInterval");
            }
        }

        /** The old loop's schedule (100 ms doubling to 1 s), 1000 waiters, 4 poll threads. */
        public static Settings defaults() {
            return new Settings(1000, 4, Duration.ofMillis(100), Duration.ofSeconds(1));
        }

        /**
         * X1-07: the one back-off rule of every worker wait (this long-poll and the adapters' in-thread fallback):
         * the next poll interval after {@code currentMs}, doubling up to {@code maxInterval}.
         */
        public long nextIntervalMillis(long currentMs) {
            return Math.min(maxInterval.toMillis(), Math.max(1, currentMs) * 2);
        }
    }

    /** Captures request-thread state on the request thread and binds it around a poll on another thread. */
    @FunctionalInterface
    public interface ContextCarrier {

        /** Called on the request thread. */
        Scope capture();

        /** The captured state; {@link #call} binds it for the duration of {@code work} and restores the thread after. */
        interface Scope {
            <T> T call(Supplier<T> work);
        }
    }

    /** Where a waiter's outcome goes. Each method returns false when the request is already gone. */
    public interface Outcome<T> {
        boolean result(List<T> items);

        boolean error(Throwable error);
    }

    /** A registered wait; {@link #cancel()} ends it without an outcome (the request completed or failed elsewhere). */
    public interface Waiter {
        void cancel();
    }

    private final Settings settings;
    private final List<ContextCarrier> carriers;
    private final ScheduledExecutorService timer;
    private final ExecutorService pollers;
    private final Set<Entry<?>> waiters = ConcurrentHashMap.newKeySet();
    private final AtomicInteger size = new AtomicInteger();
    private volatile boolean closed;

    public WorkLongPoll(Settings settings, List<ContextCarrier> carriers) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.carriers = carriers == null ? List.of() : List.copyOf(carriers);
        ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, daemon("sangam-work-longpoll-timer"));
        t.setRemoveOnCancelPolicy(true);
        this.timer = t;
        this.pollers = new ThreadPoolExecutor(settings.pollThreads(), settings.pollThreads(), 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemon("sangam-work-longpoll"));
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread th = new Thread(r, name + "-" + n.incrementAndGet());
            th.setDaemon(true);
            return th;
        };
    }

    public Settings settings() {
        return settings;
    }

    /** Requests waiting on this node. */
    public int waiting() {
        return size.get();
    }

    /**
     * Registers a waiter. Call on the request thread after its own attempt found nothing: the context is captured here.
     *
     * @param waitMs      time left until the request's deadline (&gt; 0)
     * @param poll        one fetch attempt that does not wait itself
     * @param outcome     receives the work found, the empty list at the deadline, or the poll's exception
     * @param undelivered receives work found after the request was gone (runs in the captured context)
     * @return the waiter, or null when this node already holds {@link Settings#maxWaiters()} waiters or is closed (the
     *         caller then waits in its own thread)
     */
    public <T> Waiter await(long waitMs, Supplier<List<T>> poll, Outcome<T> outcome, Consumer<List<T>> undelivered) {
        Objects.requireNonNull(poll, "poll");
        Objects.requireNonNull(outcome, "outcome");
        if (waitMs <= 0) {
            throw new IllegalArgumentException("waitMs must be positive");
        }
        if (closed) {
            return null;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        ContextCarrier.Scope scope = capture();
        while (true) {
            int n = size.get();
            if (n >= settings.maxWaiters()) {
                return null;
            }
            if (size.compareAndSet(n, n + 1)) {
                break;
            }
        }
        Entry<T> e = new Entry<>(deadline, scope, poll, outcome, undelivered);
        waiters.add(e);
        if (closed) {
            e.finish(List.of());
            return e;
        }
        e.schedule(Math.min(settings.firstInterval().toMillis(), waitMs));
        return e;
    }

    /** Wake-up hint (M-005): every waiter polls again now. Never throws; cheap (schedules only). */
    public void wakeUp() {
        for (Entry<?> e : waiters) {
            e.submit();
        }
    }

    private ContextCarrier.Scope capture() {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        List<ContextCarrier.Scope> scopes = new ArrayList<>(carriers.size());
        for (ContextCarrier c : carriers) {
            scopes.add(c.capture());
        }
        return new ContextCarrier.Scope() {
            @Override
            public <R> R call(Supplier<R> work) {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (mdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(mdc);
                }
                try {
                    Supplier<R> bound = work;
                    for (int i = scopes.size() - 1; i >= 0; i--) {
                        ContextCarrier.Scope s = scopes.get(i);
                        Supplier<R> inner = bound;
                        bound = () -> s.call(inner);
                    }
                    return bound.get();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            }
        };
    }

    /** Ends every waiter with an empty list (shutdown) and stops the threads. */
    @Override
    public void close() {
        closed = true;
        for (Entry<?> e : waiters) {
            e.finish(List.of());
        }
        timer.shutdownNow();
        pollers.shutdown();
        try {
            if (!pollers.awaitTermination(5, TimeUnit.SECONDS)) {
                pollers.shutdownNow();
            }
        } catch (InterruptedException ex) {
            pollers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private final class Entry<T> implements Waiter {
        private final long deadline;
        private final ContextCarrier.Scope scope;
        private final Supplier<List<T>> poll;
        private final Outcome<T> outcome;
        private final Consumer<List<T>> undelivered;
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicBoolean polling = new AtomicBoolean();
        private final AtomicBoolean again = new AtomicBoolean();
        private long interval;
        private ScheduledFuture<?> next;

        Entry(long deadline, ContextCarrier.Scope scope, Supplier<List<T>> poll, Outcome<T> outcome,
              Consumer<List<T>> undelivered) {
            this.deadline = deadline;
            this.scope = scope;
            this.poll = poll;
            this.outcome = outcome;
            this.undelivered = undelivered;
            this.interval = settings.firstInterval().toMillis();
        }

        /** The authoritative poll timer: one pending timer per waiter. */
        synchronized void schedule(long delayMs) {
            if (done.get()) {
                return;
            }
            if (next != null) {
                next.cancel(false);
            }
            try {
                next = timer.schedule(this::submit, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                finish(List.of());
            }
        }

        /** Runs one poll now, or exactly one more right after the running one. */
        void submit() {
            if (done.get()) {
                return;
            }
            if (!polling.compareAndSet(false, true)) {
                again.set(true);
                return;
            }
            try {
                pollers.execute(this::runPoll);
            } catch (RejectedExecutionException e) {
                polling.set(false);
                finish(List.of());
            }
        }

        private void runPoll() {
            List<T> found;
            if (done.get()) {
                polling.set(false);
                return;
            }
            try {
                again.set(false);
                found = scope.call(poll);
            } catch (Throwable t) {
                polling.set(false);
                fail(t);
                return;
            }
            polling.set(false);
            if (found != null && !found.isEmpty()) {
                finish(found);
                return;
            }
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) {
                finish(List.of());
            } else if (again.getAndSet(false)) {
                submit();
            } else {
                // the old loop's schedule: 100, 200, 400, 800 ms, then every 1 s (R1), never past the deadline
                long delay;
                synchronized (this) {
                    interval = settings.nextIntervalMillis(interval);
                    delay = Math.min(interval, left);
                }
                schedule(delay);
            }
        }

        void finish(List<T> items) {
            if (!done.compareAndSet(false, true)) {
                if (!items.isEmpty()) {
                    handBack(items);
                }
                return;
            }
            remove();
            boolean delivered;
            try {
                delivered = outcome.result(items);
            } catch (RuntimeException e) {
                LOG.warn("long-poll result could not be delivered", e);
                delivered = false;
            }
            if (!delivered && !items.isEmpty()) {
                handBack(items);
            }
        }

        private void fail(Throwable t) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            remove();
            try {
                outcome.error(t);
            } catch (RuntimeException e) {
                LOG.warn("long-poll error could not be delivered", e);
            }
        }

        private void handBack(List<T> items) {
            if (undelivered == null) {
                LOG.warn("{} item(s) found after the long-poll request ended; their leases expire on their own", items.size());
                return;
            }
            try {
                scope.call(() -> {
                    undelivered.accept(items);
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warn("releasing {} undelivered item(s) failed; their leases expire on their own", items.size(), e);
            }
        }

        private void remove() {
            if (waiters.remove(this)) {
                size.decrementAndGet();
            }
            synchronized (this) {
                if (next != null) {
                    next.cancel(false);
                    next = null;
                }
            }
        }

        @Override
        public void cancel() {
            if (done.compareAndSet(false, true)) {
                remove();
            }
        }
    }
}
