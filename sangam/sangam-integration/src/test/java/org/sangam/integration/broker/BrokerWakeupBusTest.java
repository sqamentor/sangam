// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.broker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.integration.relay.WakeupListener;

/**
 * M-058 wake-up bus over a scripted transport: a relay cycle publishes one payload-free hint and never fails the relay;
 * every notification wakes this node's subscribers; a (re)subscribe wakes them once (hints sent meanwhile are gone);
 * a lost subscription is retried with back-off; close stops the thread. Settings refuse a missing broker address.
 */
class BrokerWakeupBusTest {

    /** A broker in memory: listen() waits on a queue; "fail" ends the subscription with an error. */
    static final class ScriptedTransport implements BrokerWakeupBus.Transport {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final AtomicInteger published = new AtomicInteger();
        final AtomicInteger listens = new AtomicInteger();
        volatile boolean failPublish;

        @Override
        public void publish() {
            if (failPublish) {
                throw new IllegalStateException("broker down");
            }
            published.incrementAndGet();
            events.add("msg");
        }

        @Override
        public void listen(Runnable subscribed, Runnable message) throws Exception {
            listens.incrementAndGet();
            subscribed.run();
            while (true) {
                String e = events.take();
                switch (e) {
                    case "msg" -> message.run();
                    case "fail" -> throw new IllegalStateException("connection reset");
                    default -> {
                        return;
                    }
                }
            }
        }

        @Override
        public void close() {
            events.add("close");
        }
    }

    static WakeupListener listener() {
        return WakeupListener.forDialect(Dialect.MYSQL, () -> {
            throw new java.sql.SQLException("no LISTEN on this dialect");
        }, "sangam_outbox");
    }

    static void await(AtomicInteger n, int atLeast) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (n.get() < atLeast && System.nanoTime() < end) {
            Thread.sleep(10);
        }
    }

    @Test
    void notificationsAndResubscribesWakeTheSubscribers() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        WakeupListener l = listener();
        AtomicInteger woken = new AtomicInteger();
        l.subscribe(woken::incrementAndGet);
        BrokerWakeupBus bus = new BrokerWakeupBus("test", t, l, Duration.ofMillis(10), Duration.ofMillis(40));
        bus.start();
        await(woken, 1);
        assertEquals(1, woken.get(), "a (re)subscribe wakes once");

        bus.cycleCompleted(3);
        await(woken, 2);
        assertEquals(1, t.published.get());
        assertEquals(2, woken.get());

        t.events.add("fail");
        await(t.listens, 2);
        await(woken, 3);
        assertTrue(t.listens.get() >= 2, "a lost subscription is retried");
        assertTrue(woken.get() >= 3, "the resubscribe wakes again");

        bus.close();
        assertTrue(!bus.running());
    }

    @Test
    void aBrokerOutageNeverFailsTheRelay() {
        ScriptedTransport t = new ScriptedTransport();
        t.failPublish = true;
        BrokerWakeupBus bus = new BrokerWakeupBus("test", t, listener(), null, null);
        assertDoesNotThrow(() -> bus.cycleCompleted(1));
    }

    @Test
    void settingsNeedTheBrokerAddressAndResolveCredentialsByReference() {
        assertThrows(IllegalArgumentException.class, () -> new RedisWakeupTransport(Map.of(), r -> Optional.empty(), "n1"));
        assertThrows(IllegalStateException.class, () -> new RedisWakeupTransport(Map.of("host", "redis", "passwordRef", "secret://r/pw"),
                r -> Optional.empty(), "n1"));
        assertDoesNotThrow(() -> new RedisWakeupTransport(Map.of("host", "redis", "passwordRef", "secret://r/pw"),
                r -> Optional.of("pw"), "n1"));
        assertDoesNotThrow(() -> BrokerWakeupBus.nats(Map.of(), r -> Optional.empty(), "n1", listener()).cycleCompleted(1),
                "a NATS bus without serverUri logs the failed hint; it never fails the relay");
    }
}
