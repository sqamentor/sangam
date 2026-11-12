// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.spring;

import java.util.Objects;

import org.sangam.sdk.worker.SangamWorker;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the worker when the application context has started and stops it gracefully before the context destroys
 * the beans its handlers use (M-027). The phase is late in start-up and early in shutdown, so handlers never run
 * against a half-built or half-closed context.
 */
public class SangamWorkerLifecycle implements SmartLifecycle {

    /** Started after, and stopped before, the default phase (web server, data sources). */
    public static final int PHASE = Integer.MAX_VALUE - 1024;

    private final SangamWorker worker;
    private volatile boolean running;

    public SangamWorkerLifecycle(SangamWorker worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    @Override
    public synchronized void start() {
        if (!running) {
            worker.start();
            running = true;
        }
    }

    /** Graceful: stops fetching, lets running handlers finish within the worker's shutdown timeout, releases the rest. */
    @Override
    public synchronized void stop() {
        if (running) {
            running = false;
            worker.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    public SangamWorker worker() {
        return worker;
    }
}
