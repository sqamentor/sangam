// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.sangam.channels.conversation.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events behind {@link ConversationService.LiveDelivery} (M-040). Streams are held per tenant and principal, so an
 * event can reach only a recipient of the same tenant, and every stream belongs to a caller already checked as a participant.
 *
 * <ul>
 *   <li><b>This node.</b> A message sent here is pushed to this node's streams of its conversation after commit.</li>
 *   <li><b>Other nodes.</b> After a local delivery the hub calls {@code notifyNodes}, a payload-free hint on the M-005
 *       wake-up mechanism (no tenant data travels); every node's {@link #wake()} then catches up each open conversation
 *       with {@link ConversationService#messagesFrom}. The same catch-up runs on every heartbeat tick, so a lost hint costs
 *       latency, never a message (R1).</li>
 *   <li><b>Late commits.</b> A message's time is taken before its transaction commits, so a message can commit after a
 *       newer one. A catch-up therefore reads from {@code window} (longer than any send transaction) before the newest
 *       message time this node has seen, in database time, and drops what it already sent.</li>
 *   <li><b>Reconnect.</b> {@link #subscribe} registers the stream first and then replays the messages after the client's
 *       {@code Last-Event-ID} ({@link ConversationService#since}), so nothing falls between replay and live delivery.</li>
 *   <li><b>At least once.</b> A replay, the re-read window or a catch-up after a direct push meets messages again; each
 *       stream drops the ids it has already sent (the last {@value #SENT_IDS}) and the client's {@code Last-Event-ID}, and the
 *       client drops any other repeat by id. Without a {@code Last-Event-ID} the first catch-up also sends the messages of the
 *       last {@code window} before subscription, which covers a message stored between the client's history read and its
 *       stream request.</li>
 *   <li><b>Heartbeat.</b> An SSE comment line every {@code heartbeat}, so an idle proxied stream is not closed.</li>
 * </ul>
 * A failed send drops the stream and never affects the stored message.
 */
public final class ChannelsLiveHub implements ConversationService.LiveDelivery, AutoCloseable {

    /** Messages replayed or caught up per conversation and query. */
    public static final int BATCH = 500;
    /** Message ids a stream remembers to drop repeats. */
    public static final int SENT_IDS = 2048;
    private static final int MAX_ROUNDS = 10;
    private static final Logger LOG = LoggerFactory.getLogger(ChannelsLiveHub.class);

    /** The reads the hub needs; both are participant-checked and throw {@link ConversationService.AccessDenied}. */
    public interface Replay {
        /** {@link ConversationService#since}: the messages after a {@code Last-Event-ID}. */
        List<ConversationService.Message> since(String tenantId, String conversationId, String actor, String afterMessageId, int limit);

        /** {@link ConversationService#messagesFrom}: the messages at or after a database time (null = all), oldest first. */
        List<ConversationService.Message> from(String tenantId, String conversationId, String actor, Instant fromInclusiveOrNull, int limit);

        /** The service, looked up on first use (it is built with this hub as its live delivery). */
        static Replay of(Supplier<ConversationService> service) {
            return new Replay() {
                @Override
                public List<ConversationService.Message> since(String t, String c, String a, String after, int limit) {
                    return service.get().since(t, c, a, after, limit);
                }

                @Override
                public List<ConversationService.Message> from(String t, String c, String a, Instant from, int limit) {
                    return service.get().messagesFrom(t, c, a, from, limit);
                }
            };
        }
    }

    /** Where a stream's events go: an {@link SseEmitter} in the server, a recorder in tests. */
    interface Sink {
        void message(ConversationService.Message m) throws IOException;

        void ping() throws IOException;

        void fail(Exception e);

        void close();
    }

    private static final class Stream {
        final String tenantId;
        final String principal;
        final String conversationId;
        final Sink sink;
        private final Set<String> sent = new LinkedHashSet<>();

        Stream(String tenantId, String principal, String conversationId, Sink sink) {
            this.tenantId = tenantId;
            this.principal = principal;
            this.conversationId = conversationId;
            this.sink = sink;
        }

        /** The client already has this id (its Last-Event-ID). */
        synchronized void markSent(String id) {
            remember(id);
        }

        private boolean remember(String id) {
            if (!sent.add(id)) {
                return false;
            }
            if (sent.size() > SENT_IDS) {
                Iterator<String> oldest = sent.iterator();
                oldest.next();
                oldest.remove();
            }
            return true;
        }

        synchronized void send(ConversationService.Message m) {
            if (!remember(m.id())) {
                return;
            }
            try {
                sink.message(m);
            } catch (IOException | IllegalStateException e) {
                sink.fail(e);
            }
        }

        synchronized void ping() {
            try {
                sink.ping();
            } catch (IOException | IllegalStateException e) {
                sink.fail(e);
            }
        }
    }

    private final Map<String, List<Stream>> streams = new ConcurrentHashMap<>();
    /** tenant + conversation to the newest message time this node has seen for it (database time). */
    private final Map<String, Instant> newest = new ConcurrentHashMap<>();
    private final Replay replay;
    private final Runnable notifyNodes;
    private final Duration window;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean catchUpQueued = new AtomicBoolean();

    /** Single node, no replay and no heartbeat (embedded use). */
    public ChannelsLiveHub() {
        this.replay = null;
        this.notifyNodes = () -> { };
        this.window = Duration.ZERO;
        this.timer = null;
    }

    /**
     * @param replay      the reads ({@link Replay#of})
     * @param notifyNodes payload-free hint to every node that a message was delivered; must not block for long
     * @param heartbeat   comment and catch-up interval (at most 60 s keeps a proxy with a 120 s idle limit open)
     * @param window      how far a catch-up re-reads behind the newest message time it has seen (longer than a send transaction)
     */
    public ChannelsLiveHub(Replay replay, Runnable notifyNodes, Duration heartbeat, Duration window) {
        this(replay, notifyNodes, window, true, heartbeat);
    }

    /** Without a timer: the caller drives {@link #catchUp()} (tests). */
    ChannelsLiveHub(Replay replay, Runnable notifyNodes, Duration window) {
        this(replay, notifyNodes, window, false, null);
    }

    private ChannelsLiveHub(Replay replay, Runnable notifyNodes, Duration window, boolean timed, Duration heartbeat) {
        if (window.isNegative() || (timed && (heartbeat.isNegative() || heartbeat.isZero()))) {
            throw new IllegalArgumentException("heartbeat must be positive and window not negative");
        }
        this.replay = replay;
        this.notifyNodes = notifyNodes;
        this.window = window;
        if (timed) {
            this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sangam-channels-live");
                t.setDaemon(true);
                return t;
            });
            long ms = heartbeat.toMillis();
            timer.scheduleWithFixedDelay(this::tick, ms, ms, TimeUnit.MILLISECONDS);
        } else {
            this.timer = null;
        }
    }

    /** Single node, no replay. */
    public SseEmitter subscribe(String tenantId, String principal, String conversationId) {
        return subscribe(tenantId, principal, conversationId, null, null);
    }

    /**
     * The caller has already been checked as a participant of the conversation.
     *
     * @param lastEventId    the client's {@code Last-Event-ID} (a message id) or null: the messages after it are replayed
     * @param newestAtOrNull the time of the conversation's newest message at subscription (null when it has none), where
     *                       this node's catch-up starts if it has no newer time yet
     */
    public SseEmitter subscribe(String tenantId, String principal, String conversationId, String lastEventId, Instant newestAtOrNull) {
        SseEmitter emitter = new SseEmitter(0L);
        Runnable remove = register(tenantId, principal, conversationId, lastEventId, newestAtOrNull, new Sink() {
            @Override
            public void message(ConversationService.Message m) throws IOException {
                emitter.send(SseEmitter.event().name("message").id(m.id()).data(m));
            }

            @Override
            public void ping() throws IOException {
                emitter.send(SseEmitter.event().comment("ping"));
            }

            @Override
            public void fail(Exception e) {
                emitter.completeWithError(e);
            }

            @Override
            public void close() {
                emitter.complete();
            }
        });
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /** Registers the stream, then replays after {@code lastEventId}; returns the removal, which the sink's end must call. */
    Runnable register(String tenantId, String principal, String conversationId, String lastEventId, Instant newestAtOrNull, Sink sink) {
        Stream stream = new Stream(tenantId, principal, conversationId, sink);
        String key = key(tenantId, principal);
        streams.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(stream);
        seen(key(tenantId, conversationId), newestAtOrNull);
        Runnable remove = () -> streams.computeIfPresent(key, (k, list) -> {
            list.remove(stream);
            return list.isEmpty() ? null : list;
        });
        if (lastEventId != null && replay != null) {
            stream.markSent(lastEventId);
            try {
                for (ConversationService.Message m : replay.since(tenantId, conversationId, principal, lastEventId, BATCH)) {
                    stream.send(m);
                }
            } catch (RuntimeException e) {
                remove.run();
                throw e;
            }
        }
        return remove;
    }

    @Override
    public void deliver(String tenantId, Set<String> recipients, ConversationService.Message message) {
        for (String recipient : recipients) {
            List<Stream> list = streams.get(key(tenantId, recipient));
            if (list == null) {
                continue;
            }
            for (Stream s : list) {
                if (s.conversationId.equals(message.conversationId())) {
                    s.send(message);
                }
            }
        }
        String conversationKey = key(tenantId, message.conversationId());
        if (newest.containsKey(conversationKey)) {
            seen(conversationKey, message.at());
        }
        try {
            notifyNodes.run();
        } catch (RuntimeException e) {
            LOG.debug("live fan-out hint failed (the heartbeat catch-up covers it): {}", e.toString());
        }
    }

    /** A hint that a message may have been delivered on some node; the catch-up runs on the hub's own thread. Never throws. */
    public void wake() {
        if (timer != null && catchUpQueued.compareAndSet(false, true)) {
            try {
                timer.execute(this::catchUp);
            } catch (RuntimeException e) {
                catchUpQueued.set(false);
            }
        }
    }

    private void tick() {
        for (List<Stream> list : streams.values()) {
            for (Stream s : list) {
                s.ping();
            }
        }
        catchUp();
    }

    /** Groups the open streams by conversation and sends what they have not had yet. */
    void catchUp() {
        catchUpQueued.set(false);
        if (replay == null) {
            return;
        }
        Map<String, List<Stream>> byConversation = new LinkedHashMap<>();
        for (List<Stream> list : streams.values()) {
            for (Stream s : list) {
                byConversation.computeIfAbsent(key(s.tenantId, s.conversationId), k -> new ArrayList<>()).add(s);
            }
        }
        newest.keySet().retainAll(byConversation.keySet());
        byConversation.forEach((conversationKey, group) -> {
            try {
                catchUp(conversationKey, group);
            } catch (RuntimeException e) {
                LOG.warn("live catch-up of a conversation failed (retried on the next tick): {}", e.toString());
            }
        });
    }

    /** Reads as the first stream's principal that is still a participant; a stream whose principal is not is closed. */
    private void catchUp(String conversationKey, List<Stream> group) {
        for (Stream reader : group) {
            try {
                read(conversationKey, reader, group);
                return;
            } catch (ConversationService.AccessDenied e) {
                reader.sink.close();
            }
        }
    }

    private void read(String conversationKey, Stream reader, List<Stream> group) {
        Instant seen = newest.get(conversationKey);
        // with no time yet (the conversation was empty at subscription) every message is new
        Instant from = seen == null ? null : seen.minus(window);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<ConversationService.Message> fresh = replay.from(reader.tenantId, reader.conversationId, reader.principal, from, BATCH);
            for (ConversationService.Message m : fresh) {
                for (Stream s : group) {
                    s.send(m);
                }
                seen(conversationKey, m.at());
            }
            if (fresh.size() < BATCH) {
                return;
            }
            from = fresh.get(fresh.size() - 1).at();
        }
    }

    private void seen(String conversationKey, Instant at) {
        if (at != null) {
            newest.merge(conversationKey, at, (a, b) -> a.isAfter(b) ? a : b);
        }
    }

    /** Open streams on this node. */
    public int streamCount() {
        return streams.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    private static String key(String tenantId, String other) {
        return tenantId + '\n' + other;
    }
}
