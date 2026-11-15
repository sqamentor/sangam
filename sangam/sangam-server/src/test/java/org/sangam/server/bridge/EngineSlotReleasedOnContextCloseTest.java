// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.engine.clock.OperatonClock;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Regression (SL-37 follow-up): the D-10 one-engine-per-JVM guard ({@code OperatonClock.register}) was never released
 * when a Spring context closed, so a second server context in the same JVM failed to start even after the first was
 * closed, and tests needed {@code sangam.engine.allow-multiple-engines-per-jvm=true}. Two server contexts opened and
 * closed one after the other, with the flag explicitly off, must both start and must leave no engine slot behind.
 *
 * <p>SL-67: this class runs in the suite's shared JVM, after any other test class. Its own test context
 * ({@link NoEngine}) has no engine and goes through the test context cache, which holds one context at most
 * ({@code src/test/resources/spring.properties}, FLOWABLE-INTO-OPERATON D-10, one engine per JVM). Loading it closes a
 * server context that an earlier class left in the cache. The scenario therefore starts with no live engine in the
 * JVM, which the test checks first: without the flag, the second context can start only because the first released
 * its slot.
 */
@SpringJUnitConfig(EngineSlotReleasedOnContextCloseTest.NoEngine.class)
class EngineSlotReleasedOnContextCloseTest {

    /** This class's test context: empty, so it holds no engine slot. */
    @Configuration(proxyBeanMethods = false)
    static class NoEngine {
    }

    private static ConfigurableApplicationContext start(String db) {
        // as command-line arguments: builder properties are defaults, which application.yaml overrides (the server and
        // management ports 8080/8081, the database URL), so the scenario would depend on free fixed ports
        return new SpringApplicationBuilder(SangamServerApplication.class).profiles("h2")
                .run("--server.port=0", "--management.server.port=0", "--sangam.engine.allow-multiple-engines-per-jvm=false",
                        "--spring.datasource.url=jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL");
    }

    @Test
    void twoContextsOneAfterTheOtherWithoutTheFlag() {
        assertEquals(0, OperatonClock.liveEngines(), "no engine is live in this JVM before the scenario");
        for (String db : new String[] {"slot-a", "slot-b"}) {
            try (ConfigurableApplicationContext ctx = start(db)) {
                ctx.getBean(ProcessEngine.class).getRepositoryService().createDeploymentQuery().count();
                assertEquals(1, OperatonClock.liveEngines(), "one live engine while the context runs");
            }
            assertEquals(0, OperatonClock.liveEngines(), "closing the context releases its engine slot");
        }
    }
}
