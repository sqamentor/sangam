// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.sangam.core.time.BusinessClock;
import org.sangam.engine.clock.OperatonClock;
import org.sangam.engine.clock.TestModeClock;
import org.sangam.rest.port.TestClockPort;
import org.sangam.server.config.EngineWiring;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;

/**
 * M-021: the test-mode clock replaces the process-wide {@link BusinessClock} when it is built
 * ({@link TestModeClock}). The server's bean over it gives that clock back when the context closes. Before, a closed
 * server left its engine-following clock installed for whatever ran next in the same JVM.
 */
class TestModeClockBridgeCloseTest {

    @Test
    void closingTheBridgeRestoresTheProcessClock() {
        BusinessClock before = BusinessClock.system();
        TestModeClockBridge bridge = new TestModeClockBridge(new TestModeClock(OperatonClock.INSTANCE, c -> { }));
        try {
            assertNotSame(before, BusinessClock.system(), "the control: test mode installs its own clock");
        } finally {
            bridge.close();
        }
        assertSame(before, BusinessClock.system());
        bridge.close();
        assertSame(before, BusinessClock.system(), "closing twice changes nothing");
    }

    @Test
    void theContextClosesTheBeanAsTheServerDeclaresIt() throws Exception {
        Bean declared = EngineWiring.class.getMethod("sangamTestClockPort", org.sangam.engine.SangamEngineSettings.class,
                org.sangam.engine.OperatonEngineAdapter.class, org.operaton.bpm.engine.ProcessEngine.class,
                org.springframework.core.env.Environment.class).getAnnotation(Bean.class);
        assertEquals("close", declared.destroyMethod(), "EngineWiring names the bean's destroy method");

        BusinessClock before = BusinessClock.system();
        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            // registered as the server does: by the port type, with the declared destroy method
            ctx.registerBean("sangamTestClockPort", TestClockPort.class,
                    () -> new TestModeClockBridge(new TestModeClock(OperatonClock.INSTANCE, c -> { })),
                    definition -> definition.setDestroyMethodName(declared.destroyMethod()));
            ctx.refresh();
            ctx.getBean(TestClockPort.class);
            assertNotSame(before, BusinessClock.system());
        }
        assertSame(before, BusinessClock.system(), "context close gives the process clock back");
    }
}