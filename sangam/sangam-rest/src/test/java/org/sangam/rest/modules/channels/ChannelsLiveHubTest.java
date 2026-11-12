// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sangam.channels.conversation.ConversationService;

/**
 * M-040 server half: two hubs stand for two nodes over one message store. Fan-out by a payload-free hint plus catch-up,
 * Last-Event-ID replay, repeats dropped by id, late commits found by the re-read window, heartbeat. Written, not run (Wave 3).
 */
class ChannelsLiveHubTest {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Duration WINDOW = Duration.ofMinutes(2);

    /** The shared database: committed messages with their (pre-commit) times; the reads as ConversationService does them. */
    static final class Store implements ChannelsLiveHub.Replay {
        final List<ConversationService.Message> committed = new CopyOnWriteArrayList<>();
        final Set<String> participants = new CopyOnWriteArraySet<>(Set.of("alice", "bob"));
        final AtomicInteger reads = new AtomicInteger();

        ConversationService.Message add(String id, Instant at) {
            ConversationService.Message m = new ConversationService.Message(id, "c1", "alice", "text " + id, List.of(), at);
            committed.add(m);
            return m;
        }

        private List<ConversationService.Message> ordered(String actor) {
            if (!participants.contains(actor)) {
                throw new ConversationService.AccessDenied();
            }
            reads.incrementAndGet();
            List<ConversationService.Message> all = new ArrayList<>(committed);
            all.sort(Comparator.comparing(ConversationService.Message::at).thenComparing(ConversationService.Message::id));
            return all;
        }

        @Override
        public List<ConversationService.Message> since(String tenantId, String conversationId, String actor, String after, int limit) {
            List<ConversationService.Message> all = ordered(actor);
            Instant at = all.stream().filter(m -> m.id().equals(after)).map(ConversationService.Message::at).findFirst().orElse(null);
            if (at == null) {
                return all.subList(Math.max(0, all.size() - limit), all.size());
            }
            return all.stream().filter(m -> !m.at().isBefore(at) && !m.id().equals(after)).limit(limit).toList();
        }

        @Override
        public List<ConversationService.Message> from(String tenantId, String conversationId, String actor, Instant from, int limit) {
            return ordered(actor).stream().filter(m -> from == null || !m.at().isBefore(from)).limit(limit).toList();
        }
    }

    static class Recorder implements ChannelsLiveHub.Sink {
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile int pings;
        boolean closed;
        Runnable onEnd = () -> { };

        @Override
        public void message(ConversationService.Message m) {
            events.add(m.id());
        }

        @Override
        public void ping() {
            pings++;
        }

        @Override
        public void fail(Exception e) {
            closed = true;
            onEnd.run();
        }

        @Override
        public void close() {
            closed = true;
            onEnd.run();
        }
    }

    final Store store = new Store();

    Recorder open(ChannelsLiveHub hub, String principal, String lastEventId, Instant newestAt) {
        Recorder r = new Recorder();
        r.onEnd = hub.register("t", principal, "c1", lastEventId, newestAt, r);
        return r;
    }

    @Test
    void aMessageSentOnOneNodeReachesAStreamOnAnother() {
        AtomicInteger hints = new AtomicInteger();
        ChannelsLiveHub nodeA = new ChannelsLiveHub(store, hints::incrementAndGet, WINDOW);
        ChannelsLiveHub nodeB = new ChannelsLiveHub(store, () -> { }, WINDOW);
        store.add("old", T0.minus(Duration.ofHours(2)));
        store.add("m0", T0.minus(Duration.ofHours(1)));
        Recorder onA = open(nodeA, "alice", null, T0.minus(Duration.ofHours(1)));
        Recorder onB = open(nodeB, "bob", null, T0.minus(Duration.ofHours(1)));

        ConversationService.Message m1 = store.add("m1", T0);
        nodeA.deliver("t", Set.of("alice", "bob"), m1);
        assertEquals(List.of("m1"), onA.events, "pushed on the sending node");
        assertEquals(1, hints.get(), "one payload-free hint per delivery");
        assertTrue(onB.events.isEmpty(), "the other node waits for its wake-up");

        nodeB.catchUp();
        assertEquals(List.of("m0", "m1"), onB.events, "caught up in time order; without a Last-Event-ID the messages inside the window"
                + " before subscription come too (at least once, covering a send between the client's history read and the stream), older ones not");
        nodeB.catchUp();
        nodeA.catchUp();
        assertEquals(List.of("m0", "m1"), onB.events, "a repeated catch-up sends nothing twice");
        assertEquals(List.of("m1"), onA.events, "the sending node reads from its newest time, and drops the id it pushed");
    }

    @Test
    void aLateCommitBehindANewerMessageIsStillDelivered() {
        ChannelsLiveHub nodeB = new ChannelsLiveHub(store, () -> { }, WINDOW);
        Recorder onB = open(nodeB, "bob", null, null);
        store.add("newer", T0.plusSeconds(2));
        nodeB.catchUp();
        assertEquals(List.of("newer"), onB.events, "an empty conversation at subscription: everything is new");
        store.add("late", T0.plusSeconds(1));
        nodeB.catchUp();
        assertEquals(List.of("newer", "late"), onB.events, "its time is before the newest seen, but inside the window");
        store.add("next", T0.plus(Duration.ofMinutes(10)));
        nodeB.catchUp();
        assertEquals(List.of("newer", "late", "next"), onB.events);
        store.add("ancient", T0);
        nodeB.catchUp();
        assertEquals(List.of("newer", "late", "next"), onB.events, "outside the window: left to the history read");
    }

    @Test
    void lastEventIdReplaysOnlyWhatFollowsAndTheLiveRepeatIsDropped() {
        ChannelsLiveHub hub = new ChannelsLiveHub(store, () -> { }, WINDOW);
        store.add("a", T0);
        store.add("b", T0.plusSeconds(1));
        ConversationService.Message c = store.add("c", T0.plusSeconds(2));
        Recorder r = open(hub, "bob", "a", c.at());
        assertEquals(List.of("b", "c"), r.events, "replayed after the Last-Event-ID, oldest first");
        hub.deliver("t", Set.of("bob"), c);
        hub.catchUp();
        assertEquals(List.of("b", "c"), r.events, "the replayed ids are not sent again, although the window re-reads a");
    }

    @Test
    void aStreamWhosePrincipalIsRefusedIsClosed() {
        ChannelsLiveHub hub = new ChannelsLiveHub(store, () -> { }, WINDOW);
        Recorder alice = open(hub, "alice", null, null);
        assertEquals(1, hub.streamCount());
        store.participants.remove("alice");
        store.add("x", T0);
        hub.catchUp();
        assertTrue(alice.closed);
        assertTrue(alice.events.isEmpty());
        assertEquals(0, hub.streamCount());
    }

    @Test
    void aFailingReplayLeavesNoStreamBehindAndOtherTenantsAreNotReached() {
        ChannelsLiveHub hub = new ChannelsLiveHub(store, () -> { }, WINDOW);
        assertThrows(ConversationService.AccessDenied.class, () -> open(hub, "eve", "a", null));
        assertEquals(0, hub.streamCount());
        Recorder bob = open(hub, "bob", null, null);
        hub.deliver("other-tenant", Set.of("bob"), new ConversationService.Message("y", "c1", "x", "y", List.of(), T0));
        assertTrue(bob.events.isEmpty(), "streams are keyed by tenant");
    }

    @Test
    void aBrokenSinkIsDropped() {
        ChannelsLiveHub hub = new ChannelsLiveHub(store, () -> { }, WINDOW);
        Recorder broken = new Recorder() {
            @Override
            public void message(ConversationService.Message m) {
                throw new IllegalStateException("client gone");
            }
        };
        broken.onEnd = hub.register("t", "bob", "c1", null, null, broken);
        hub.deliver("t", Set.of("bob"), store.add("z", T0));
        assertTrue(broken.closed);
        assertEquals(0, hub.streamCount());
    }

    @Test
    void theTimedHubSendsHeartbeatsAndRefusesABadInterval() {
        assertThrows(IllegalArgumentException.class, () -> new ChannelsLiveHub(store, () -> { }, Duration.ZERO, WINDOW));
        try (ChannelsLiveHub hub = new ChannelsLiveHub(store, () -> { }, Duration.ofMillis(50), WINDOW)) {
            Recorder r = open(hub, "bob", null, null);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (r.pings == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(r.pings > 0, "an SSE comment keeps an idle stream open");
        }
    }
}
