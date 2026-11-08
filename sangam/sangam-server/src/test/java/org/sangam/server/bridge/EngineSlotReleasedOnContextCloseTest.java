// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.engine.clock.OperatonClock;
import org.sangam.server.SangamServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Regression (SL-37 follow-up): the D-10 one-engine-per-JVM guard ({@code OperatonClock.register}) was never released
 * when a Spring context closed, so a second server context in the same JVM failed to start even after the first was
 * closed, and tests needed {@code sangam.engine.allow-multiple-engines-per-jvm=true}. Two server contexts opened and
 * closed one after the other, with the flag explicitly off, must both start and must leave no engine slot behind.
 */
class EngineSlotReleasedOnContextCloseTest {

    private static ConfigurableApplicationContext start(String db) {
        return new SpringApplicationBuilder(SangamServerApplication.class).profiles("h2")
                .properties("server.port=0", "sangam.engine.allow-multiple-engines-per-jvm=false",
                        "spring.datasource.url=jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL")
                .run();
    }

    @Test
    void twoContextsOneAfterTheOtherWithoutTheFlag() {
        int before = OperatonClock.liveEngines();
        for (String db : new String[] {"slot-a", "slot-b"}) {
            try (ConfigurableApplicationContext ctx = start(db)) {
                ctx.getBean(ProcessEngine.class).getRepositoryService().createDeploymentQuery().count();
                assertEquals(before + 1, OperatonClock.liveEngines(), "one live engine while the context runs");
            }
            assertEquals(before, OperatonClock.liveEngines(), "closing the context releases its engine slot");
        }
    }
}
