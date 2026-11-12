// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sangam.sdk.SangamClient;
import org.sangam.sdk.testing.SangamTestEngine;
import org.sangam.sdk.worker.Saga;
import org.sangam.sdk.worker.SangamWorker;
import org.sangam.sdk.worker.TopicSubscription;

/** M-027: the Spring Boot adapter's client, worker and lifecycle, against the SDK test engine. */
class SangamWorkerAutoConfigurationTest {

    static SangamWorkerProperties props(String baseUrl) {
        SangamWorkerProperties p = new SangamWorkerProperties();
        p.setBaseUrl(baseUrl);
        p.setWorkerId("spring-w1");
        return p;
    }

    static Saga saga() {
        return Saga.step("reserve").action("stock.reserve", ctx -> Map.of())
                .compensation("stock.release", c -> Map.of()).build();
    }

    @Test
    void aBaseUrlIsRequired() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SangamWorkerAutoConfiguration.client(new SangamWorkerProperties(), null));
        assertTrue(e.getMessage().contains("sangam.worker.base-url"));
    }

    @Test
    void theWorkerTakesItsIdAndEveryTopicOnce() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient client = SangamWorkerAutoConfiguration.client(props(e.baseUrl()), null);
            SangamWorker w = SangamWorkerAutoConfiguration.worker(client, props(e.baseUrl()),
                    List.of(TopicSubscription.topic("pay", ctx -> Map.of()).build()), List.of(saga()));
            assertEquals("spring-w1", w.workerId());
            w.close();
        }
    }

    @Test
    void aTopicRegisteredTwiceIsAConfigurationError() {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            SangamClient client = SangamWorkerAutoConfiguration.client(props(e.baseUrl()), null);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> SangamWorkerAutoConfiguration.worker(client, props(e.baseUrl()),
                            List.of(TopicSubscription.topic("stock.reserve", ctx -> Map.of()).build()), List.of(saga())));
            assertTrue(ex.getMessage().contains("stock.reserve"));
        }
    }

    @Test
    void theLifecycleStartsHandlesWorkAndStopsGracefully() throws Exception {
        try (SangamTestEngine e = SangamTestEngine.start()) {
            String id = e.addWork("pay", Map.of());
            SangamClient client = SangamWorkerAutoConfiguration.client(props(e.baseUrl()), null);
            SangamWorker w = SangamWorkerAutoConfiguration.worker(client, props(e.baseUrl()),
                    List.of(TopicSubscription.topic("pay", ctx -> Map.of("done", true)).build()), List.of());
            SangamWorkerLifecycle life = new SangamWorkerLifecycle(w);
            assertFalse(life.isRunning());
            life.start();
            assertTrue(life.isRunning());
            e.awaitState(id, "COMPLETED", java.time.Duration.ofSeconds(10));
            life.stop();
            assertFalse(life.isRunning());
            life.stop();   // idempotent
            assertEquals(SangamWorkerLifecycle.PHASE, life.getPhase());
        }
    }
}
