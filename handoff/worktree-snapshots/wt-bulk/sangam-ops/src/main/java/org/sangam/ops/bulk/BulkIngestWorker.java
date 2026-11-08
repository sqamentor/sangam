// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background worker of {@link BulkIngest} on every node (rows are claimed with SKIP LOCKED, so nodes share jobs).
 * {@link #start} first gives back the rows this node held when it last stopped ({@link BulkIngest#recoverNode}),
 * then polls: stale rows of vanished nodes are re-queued, pending rows are applied in batches, settled jobs finish.
 * Everything it needs is in the database, so a restart resumes where the previous run stopped.
 */
public final class BulkIngestWorker {

    private static final Logger LOG = LoggerFactory.getLogger(BulkIngestWorker.class);

    private final BulkIngest ingest;
    private final int batch;
    private final long pollMillis;
    private final long staleMillis;
    private ScheduledExecutorService executor;

    public BulkIngestWorker(BulkIngest ingest, int batch, long pollMillis, long staleMillis) {
        if (batch < 1 || pollMillis < 1 || staleMillis < 1) {
            throw new IllegalArgumentException("bulk worker settings must be positive");
        }
        this.ingest = ingest;
        this.batch = batch;
        this.pollMillis = pollMillis;
        this.staleMillis = staleMillis;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        int back = ingest.recoverNode();
        if (back > 0) {
            LOG.info("bulk ingest: resumed {} rows this node ({}) held before it stopped", back, ingest.node());
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sangam-bulk-ingest");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::safeTick, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** One polling round; returns the number of rows settled. Public for tests and manual draining. */
    public int tick() {
        ingest.recoverStale(staleMillis);
        int total = 0;
        int n;
        do {
            n = ingest.runOnce(batch);
            total += n;
        } while (n > 0 && !Thread.currentThread().isInterrupted());
        ingest.finishSettledJobs();
        return total;
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.warn("bulk ingest round failed: {}", e.toString());
        }
    }
}
