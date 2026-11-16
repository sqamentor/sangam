// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.sangam.channels.bot.BotBridge;
import org.sangam.channels.messenger.InboundMediaQueue;
import org.sangam.channels.messenger.MediaDownloads;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.DocumentStore;
import org.sangam.rest.modules.channels.InboundMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The asynchronous media path of the channel webhooks (integrator decision, contract W3-M 14:01:57Z), durable: the webhook
 * has already verified the delivery and committed the inbox row together with its pending media ({@link InboundMediaQueue}).
 * This worker claims due rows from the database (skip-locked, epoch and lease), downloads each with the tenant's
 * {@code conversation-channels} entry for that channel, scans it (the M-082 scanner, or the {@code allow-unscanned} rule),
 * stores it as a document and, in the transaction that marks the row {@code DONE}, hands its key to the bot as the
 * {@code botMedia} variable (message id {@code <id>#media}).
 *
 * <p>A failure the provider marks as passing (rate limit, 5xx, network) goes back to {@code PENDING} after each of
 * {@code retries} in turn (the stored document, once there, is kept for the next attempt); a refusal by the scanner, a
 * missing entry, a permanent provider error, or a passing one after the last retry parks the row and calls {@code failed}
 * in the same transaction (the server audits {@code INBOUND_MEDIA_FAILED}). Nothing is dropped: a row whose node stops
 * stays claimed until its lease runs out and is then taken by any node, and at {@link #start} this node takes back its own
 * claims at once. {@link #received} only wakes the poll.
 */
public final class ChannelMediaWorker implements InboundMedia, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelMediaWorker.class);
    /** How long {@link #close()} waits for a claim in flight; a claim is one short transaction. */
    static final Duration POLLER_STOP_WAIT = Duration.ofSeconds(30);

    /** Downloads one media reference with a channel entry. */
    @FunctionalInterface
    public interface Download {
        MediaDownloads.Media fetch(String tenantId, Map<String, Object> channelEntry, String mediaRef);
    }

    /** Called once per parked row, inside the transaction that parks it. */
    @FunctionalInterface
    public interface Failed {
        void failed(Connection c, InboundMediaQueue.Pending media, String reason);
    }

    private final InboundMediaQueue queue;
    private final Transactions tx;
    private final Function<String, List<Map<String, Object>>> entries;
    private final Download download;
    private final DocumentStore documents;
    private final BiPredicate<String, byte[]> scanner;
    private final BotBridge bot;
    private final int threads;
    private final Duration poll;
    private final List<Duration> retries;
    private final Failed failed;
    private final ScheduledExecutorService poller;
    private final ExecutorService workers;
    private final AtomicInteger busy = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * @param queue    the durable media rows
     * @param tx       transactions on the same database
     * @param entries  the tenant's {@code conversation-channels} entries
     * @param scanner  the M-082 scan (true = clean)
     * @param threads  rows worked at once on this node
     * @param poll     how often due rows are looked for without a wake-up
     * @param retries  the waits before each repeat of a passing failure
     */
    public ChannelMediaWorker(InboundMediaQueue queue, Transactions tx, Function<String, List<Map<String, Object>>> entries, Download download,
                              DocumentStore documents, BiPredicate<String, byte[]> scanner, BotBridge bot, int threads, Duration poll,
                              List<Duration> retries, Failed failed) {
        if (threads < 1 || poll.isNegative() || poll.isZero()) {
            throw new IllegalArgumentException("threads and poll must be positive");
        }
        this.queue = queue;
        this.tx = tx;
        this.entries = entries;
        this.download = download;
        this.documents = documents;
        this.scanner = scanner;
        this.bot = bot;
        this.threads = threads;
        this.poll = poll;
        this.retries = List.copyOf(retries);
        this.failed = failed;
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "sangam-channels-media-poll"));
        this.workers = Executors.newFixedThreadPool(threads, r -> daemon(r, "sangam-channels-media"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Takes back this node's earlier claims and starts polling. */
    public ChannelMediaWorker start() {
        if (started.compareAndSet(false, true)) {
            int resumed = queue.resumeOwn();
            if (resumed > 0) {
                LOG.info("resuming {} inbound media rows node {} had claimed before it stopped", resumed, queue.node());
            }
            poller.scheduleWithFixedDelay(this::drain, 0, poll.toMillis(), TimeUnit.MILLISECONDS);
        }
        return this;
    }

    /** Wake-up after a webhook commit; the row itself is already durable. */
    @Override
    public void received(String tenantId, String channel, String externalId, String principalId, String messageId, String mediaRef) {
        try {
            poller.execute(this::drain);
        } catch (RuntimeException e) {
            LOG.debug("media worker stopped; the row waits for the next node: {}", e.toString());
        }
    }

    /** Rows this node is working on now. */
    public int busy() {
        return busy.get();
    }

    /** Claims as many due rows as there are free threads and hands them to the workers, until none are due. */
    void drain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            while (!poller.isShutdown()) {
                int free = threads - busy.get();
                if (free <= 0) {
                    return;
                }
                List<InboundMediaQueue.Pending> due = queue.claim(free);
                if (due.isEmpty()) {
                    return;
                }
                for (InboundMediaQueue.Pending p : due) {
                    busy.incrementAndGet();
                    try {
                        workers.execute(() -> {
                            try {
                                run(p);
                            } finally {
                                busy.decrementAndGet();
                                received(null, null, null, null, null, null);   // a thread is free: look again
                            }
                        });
                    } catch (RuntimeException e) {
                        busy.decrementAndGet();   // stopped: the claim's lease brings the row back
                        return;
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("claiming inbound media failed; next poll in {}: {}", poll, e.toString());
        } finally {
            draining.set(false);
        }
    }

    void run(InboundMediaQueue.Pending p) {
        MediaDownloads.StoredMedia stored = p.stored();
        try {
            if (stored == null) {
                Optional<Map<String, Object>> entry = entries.apply(p.tenantId()).stream()
                        .filter(e -> p.channel().equalsIgnoreCase(String.valueOf(e.get("channel")))).findFirst();
                if (entry.isEmpty()) {
                    park(p, "NO_CHANNEL_ENTRY");
                    return;
                }
                MediaDownloads.Media media = download.fetch(p.tenantId(), entry.get(), p.mediaRef());
                stored = MediaDownloads.store(documents, scanner, p.tenantId(), p.channel(), media);
                // SL-176: the copy goes on the row before anything else, so a takeover reuses it instead of storing again
                if (!queue.remember(p, stored)) {
                    discard(p, stored);
                    return;
                }
            }
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("documentKey", stored.documentKey());
            attrs.put("fileName", stored.fileName());
            attrs.put("contentType", stored.contentType() == null ? "application/octet-stream" : stored.contentType());
            attrs.put("sizeBytes", stored.sizeBytes());
            attrs.put("sha256", stored.sha256());
            boolean delivered = tx.inTransaction(c -> {
                if (!queue.complete(c, p)) {
                    return false;
                }
                bot.onInbound(new BotBridge.InboundMessage(p.tenantId(), p.channel(), p.externalId(), p.principalId(), p.messageId() + "#media",
                        null, attrs));
                return true;
            });
            if (!delivered) {
                LOG.info("inbound media of {} message {} (tenant {}): claim taken over by another worker, not delivered here",
                        p.channel(), p.messageId(), p.tenantId());
            }
        } catch (MediaDownloads.MediaUnavailableException e) {
            retryOrPark(p, stored, e.retryable(), e.getMessage());
        } catch (IllegalArgumentException e) {
            // MEDIA_REFUSED_BY_SCANNER, or an entry without the fields the download needs: repeating changes nothing
            park(p, e.getMessage());
        } catch (Exception e) {
            retryOrPark(p, stored, true, e.toString());
        }
    }

    /** The claim was lost before the copy reached the row: no row references it, so it is removed (SL-176). */
    private void discard(InboundMediaQueue.Pending p, MediaDownloads.StoredMedia stored) {
        LOG.info("inbound media of {} message {} (tenant {}): claim taken over before the stored copy was recorded; removing copy {}",
                p.channel(), p.messageId(), p.tenantId(), stored.documentKey());
        try {
            documents.delete(stored.documentKey());
        } catch (Exception e) {
            LOG.warn("removing unreferenced inbound media copy {} failed: {}", stored.documentKey(), e.toString());
        }
    }

    private void retryOrPark(InboundMediaQueue.Pending p, MediaDownloads.StoredMedia stored, boolean retryable, String reason) {
        try {
            if (retryable && p.attempt() < retries.size()) {
                queue.retry(p, retries.get(p.attempt()), reason, stored);
                return;
            }
            park(p, reason);
        } catch (RuntimeException e) {
            // the row stays claimed; its lease brings it back
            LOG.warn("recording the media failure of {} message {} failed: {}", p.channel(), p.messageId(), e.toString());
        }
    }

    private void park(InboundMediaQueue.Pending p, String reason) {
        LOG.warn("inbound media of {} message {} (tenant {}) parked: {}", p.channel(), p.messageId(), p.tenantId(), reason);
        tx.inTransaction(c -> {
            if (queue.park(c, p, reason)) {
                failed.failed(c, p, reason);
            }
            return null;
        });
    }

    /**
     * Stops claiming; rows in work keep their claim and come back by lease (or at this node's next start). Returns only
     * after the poller has stopped, so no claim of this worker commits after it: a later start on the same node (its
     * {@code resumeOwn()}) then sees every claim this worker made. Results of rows still in work are fenced by their
     * claim epoch, so the worker threads are not waited for.
     */
    @Override
    public void close() {
        poller.shutdownNow();
        workers.shutdownNow();
        try {
            if (!poller.awaitTermination(POLLER_STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("inbound media poller of node {} did not stop within {}; a claim still in flight comes back by its lease",
                        queue.node(), POLLER_STOP_WAIT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for the worker threads after {@link #close}. */
    boolean awaitStopped(Duration wait) throws InterruptedException {
        return workers.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS);
    }
}
