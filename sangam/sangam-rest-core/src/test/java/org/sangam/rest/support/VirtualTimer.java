// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Single-threaded virtual time for {@link WorkLongPoll} tests: {@link #schedule(Runnable, long, TimeUnit)} queues a
 * task at "now + delay", {@link #runUntil(long)} moves the clock and runs what is due, in order, on the calling thread;
 * {@link #execute(Runnable)} runs inline (the poll threads). {@code lagMs} makes every timer task run that much later
 * than it was due, which is what a starved timer thread on a saturated host does.
 */
final class VirtualTimer extends AbstractExecutorService implements ScheduledExecutorService {

    private final long lagMs;
    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private long nowNanos;
    private long seq;
    private boolean shutdown;

    VirtualTimer(long lagMs) {
        this.lagMs = lagMs;
    }

    long nanoTime() {
        return nowNanos;
    }

    long nowMillis() {
        return TimeUnit.NANOSECONDS.toMillis(nowNanos);
    }

    /** Runs every task due up to {@code untilMs} (virtual), advancing the clock to each task's run time. */
    void runUntil(long untilMs) {
        long until = TimeUnit.MILLISECONDS.toNanos(untilMs);
        while (!queue.isEmpty() && queue.peek().at <= until) {
            Task t = queue.poll();
            if (t.cancelled) {
                continue;
            }
            nowNanos = Math.max(nowNanos, t.at);
            t.done = true;
            t.task.run();
        }
        nowNanos = Math.max(nowNanos, until);
    }

    int pending() {
        int n = 0;
        for (Task t : queue) {
            n += t.cancelled ? 0 : 1;
        }
        return n;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Task t = new Task(nowNanos + unit.toNanos(delay) + TimeUnit.MILLISECONDS.toNanos(lagMs), seq++, command);
        queue.add(t);
        return t;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
        command.run();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        queue.clear();
        return new ArrayList<>();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    private static final class Task implements ScheduledFuture<Object> {
        final long at;
        final long order;
        final Runnable task;
        boolean cancelled;
        boolean done;

        Task(long at, long order, Runnable task) {
            this.at = at;
            this.order = order;
            this.task = task;
        }

        @Override
        public int compareTo(Delayed o) {
            Task t = (Task) o;
            return at != t.at ? Long.compare(at, t.at) : Long.compare(order, t.order);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(at, TimeUnit.NANOSECONDS);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
