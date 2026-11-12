// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sangam.server.bridge.MediaQueueFixture.await;
import static org.sangam.server.bridge.MediaQueueFixture.media;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.channels.messenger.MediaDownloads;

/**
 * The durable media path after the inbox commit (H2): download, M-082 scan, store, document key to the bot; retries for
 * passing failures only, through the row's due time; every final failure parks the row and is reported once. Written, not
 * run (Wave 3).
 */
class ChannelMediaWorkerTest {

    static final Duration LEASE = Duration.ofMinutes(10);

    MediaQueueFixture f;

    @BeforeEach
    void db() throws Exception {
        f = new MediaQueueFixture();
    }

    @Test
    void downloadsScansStoresAndHandsTheDocumentKeyToTheBot() {
        f.inbox("Ev1", "F1");
        assertEquals("PENDING", f.state("Ev1"));
        try (ChannelMediaWorker w = f.worker("n1", LEASE, (t, e, ref) -> media(ref), true, 1).start()) {
            await(() -> "DONE".equals(f.state("Ev1")));
            assertEquals("DONE", f.state("Ev1"));
            assertEquals(1, f.botVars.size());
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) f.botVars.get(0).get("botMedia");
            assertTrue(m.get("documentKey").toString().startsWith("t/media/slack/"));
            assertEquals("F1.pdf", m.get("fileName"));
            assertEquals("application/pdf", m.get("contentType"));
            assertEquals(1, f.docs.size());
            assertTrue(f.failures.isEmpty());
        }
    }

    @Test
    void aFileTheScannerRefusesIsParkedNotStoredOrRetried() {
        AtomicInteger calls = new AtomicInteger();
        f.inbox("Ev2", "F2");
        try (ChannelMediaWorker w = f.worker("n1", LEASE, (t, e, ref) -> {
            calls.incrementAndGet();
            return new MediaDownloads.Media(new byte[] {1}, "application/octet-stream", "x.exe");
        }, false, 1).start()) {
            await(() -> !f.failures.isEmpty());
            assertEquals(List.of("Ev2:MEDIA_REFUSED_BY_SCANNER"), f.failures);
            assertEquals("PARKED", f.state("Ev2"), "parked, never dropped");
            assertEquals("MEDIA_REFUSED_BY_SCANNER", f.column("media_error", "Ev2"));
            assertEquals(1, calls.get());
            assertTrue(f.docs.isEmpty());
            assertTrue(f.botVars.isEmpty());
        }
    }

    @Test
    void passingFailuresAreRetriedThroughTheRowAndPermanentOnesAreNot() {
        AtomicInteger calls = new AtomicInteger();
        f.inbox("Ev3", "F3");
        try (ChannelMediaWorker w = f.worker("n1", LEASE, (t, e, ref) -> {
            if (calls.incrementAndGet() < 3) {
                throw new MediaDownloads.MediaUnavailableException("HTTP 503", true);
            }
            return media(ref);
        }, true, 1).start()) {
            await(() -> "DONE".equals(f.state("Ev3")));
            assertEquals(3, calls.get(), "two retries, then success");
            assertEquals("2", f.column("media_attempt", "Ev3"));
            assertTrue(f.failures.isEmpty());
        }
        AtomicInteger permanent = new AtomicInteger();
        f.inbox("Ev4", "F4");
        try (ChannelMediaWorker w = f.worker("n1", LEASE, (t, e, ref) -> {
            permanent.incrementAndGet();
            throw new MediaDownloads.MediaUnavailableException("SLACK_file_not_found", false);
        }, true, 1).start()) {
            await(() -> !f.failures.isEmpty());
            assertEquals(1, permanent.get());
            assertEquals(List.of("Ev4:SLACK_file_not_found"), f.failures);
            assertEquals("PARKED", f.state("Ev4"));
        }
    }

    @Test
    void aPassingFailureAfterTheLastRetryIsParked() {
        AtomicInteger calls = new AtomicInteger();
        f.inbox("Ev5", "F5");
        try (ChannelMediaWorker w = f.worker("n1", LEASE, (t, e, ref) -> {
            calls.incrementAndGet();
            throw new MediaDownloads.MediaUnavailableException("HTTP 503", true);
        }, true, 1).start()) {
            await(() -> !f.failures.isEmpty());
            assertEquals(3, calls.get(), "the first attempt and the two retries");
            assertEquals(List.of("Ev5:HTTP 503"), f.failures);
            assertEquals("PARKED", f.state("Ev5"));
        }
    }

    @Test
    void aBotFailureKeepsTheStoredDocumentForTheRetry() {
        AtomicInteger downloads = new AtomicInteger();
        AtomicInteger botCalls = new AtomicInteger();
        f.inbox("Ev6", "F6");
        MediaQueueFixture g = f;
        org.sangam.channels.bot.BotBridge flaky = new org.sangam.channels.bot.BotBridge(new org.sangam.channels.bot.BotBridge.ProcessGateway() {
            @Override
            public boolean correlate(String tenantId, String messageName, String businessKey, Map<String, Object> variables) {
                if (botCalls.incrementAndGet() == 1) {
                    throw new IllegalStateException("engine busy");
                }
                g.botVars.add(variables);
                return true;
            }

            @Override
            public void start(String tenantId, String processKey, String businessKey, Map<String, Object> variables) {
            }
        }, Map.of("*", "bot"));
        try (ChannelMediaWorker w = new ChannelMediaWorker(f.queue("n1", LEASE), f.tx, t -> MediaQueueFixture.ENTRIES, (t, e, ref) -> {
            downloads.incrementAndGet();
            return media(ref);
        }, f.store, (n, b) -> true, flaky, 1, Duration.ofMillis(50), List.of(Duration.ofMillis(10)),
                (c, m, reason) -> f.failures.add(m.messageId() + ":" + reason)).start()) {
            await(() -> "DONE".equals(f.state("Ev6")));
            assertEquals("DONE", f.state("Ev6"));
            assertEquals(1, downloads.get(), "the retry used the stored document from the row");
            assertEquals(1, f.docs.size());
            assertEquals(2, botCalls.get());
            assertEquals(1, f.botVars.size());
        }
    }

    @Test
    void noChannelEntryIsParked() {
        f.tx.inTransaction(c -> {
            org.sangam.core.layer.Jdbc.update(c, "INSERT INTO CH_INBOUND_SEEN(tenant_id,channel,message_id,seen_at) VALUES ('other','SLACK','Ev7',CURRENT_TIMESTAMP)");
            return org.sangam.channels.messenger.InboundMediaQueue.enqueue(c, org.sangam.core.layer.Dialect.POSTGRESQL, "other", "SLACK", "Ev7", "U9",
                    "zed", "F7");
        });
        try (ChannelMediaWorker w = f.worker("n1", LEASE, (t, e, ref) -> media(ref), true, 1).start()) {
            await(() -> !f.failures.isEmpty());
            assertEquals(List.of("Ev7:NO_CHANNEL_ENTRY"), f.failures);
        }
    }
}
