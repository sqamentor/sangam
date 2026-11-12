// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one pull loop of the event gateway's pulling consumers (M-064: NATS JetStream pull consumers, SQS, Service Bus,
 * Pub/Sub): a daemon thread calls {@link #pollOnce} until closed. A poll that found nothing waits
 * {@code properties.pollIntervalMs} (default 1 s; the long-polling APIs already wait on the server); a failing poll
 * backs off exponentially from 1 s to {@code properties.maxBackoffMs} (default 60 s) and never stops the loop.
 *
 * <p>Each received message goes through {@link #deliver}: the processor's broker entry (inbox de-duplication, R38;
 * tenant admission, SL-142). The subclass acknowledges (deletes, completes) only when it returns true, and otherwise
 * leaves the message to the broker's redelivery (visibility timeout, lock expiry, ack deadline), after the
 * {@code redeliverBackoffMs} delay where the API can set one. A queue's own redrive limit (SQS maxReceiveCount,
 * Service Bus MaxDeliveryCount, a Pub/Sub dead-letter policy) still applies: size it for the longest tenant freeze.
 */
public abstract class PollingInbound implements ChannelAdapters.InboundChannelAdapter, Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(PollingInbound.class);

    protected final ChannelDefinition ch;
    protected final String tenant;
    private final InboundEventProcessor processor;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    protected PollingInbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor) {
        this.ch = ch;
        this.tenant = tenant;
        this.processor = processor;
    }

    /** Receives one batch and settles each message; returns how many messages it received. */
    protected abstract int pollOnce() throws Exception;

    /** Releases the transport (connections, subscriptions). Called once when the loop ends. */
    protected void release() {
    }

    /** Hands one message to the processor; true means: acknowledge it now. */
    protected final boolean deliver(String raw, Map<String, String> headers, String messageId) {
        return ChannelAdapters.deliver(processor, tenant, ch, raw, headers, messageId);
    }

    /** Seconds a not-yet-admitted message stays invisible before the broker offers it again. */
    protected final int redeliverDelaySeconds() {
        return (int) Math.max(1, Math.min(43_200, ChannelAdapters.redeliverBackoffMs(ch) / 1000));
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this, "sangam-" + ch.adapterType() + "-" + ch.key());
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public void run() {
        long idle = Math.max(10L, Long.parseLong(ch.property("pollIntervalMs", "1000").trim()));
        long maxBackoff = Math.max(1000L, Long.parseLong(ch.property("maxBackoffMs", "60000").trim()));
        long backoff = 1000L;
        try {
            while (running.get()) {
                try {
                    int n = pollOnce();
                    backoff = 1000L;
                    if (n == 0 && running.get()) {
                        ChannelAdapters.sleep(idle);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    if (!running.get()) {
                        return;
                    }
                    LOG.warn("{} channel {}: poll failed, retrying in {} ms: {}", ch.adapterType(), ch.key(), backoff, e.toString());
                    ChannelAdapters.sleep(backoff);
                    backoff = Math.min(maxBackoff, backoff * 2);
                }
            }
        } finally {
            release();
        }
    }

    public boolean running() {
        return running.get();
    }

    @Override
    public void close() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
