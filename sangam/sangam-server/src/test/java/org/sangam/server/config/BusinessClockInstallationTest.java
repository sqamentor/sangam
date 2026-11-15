// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.time.BusinessClock;

/**
 * A closed server context does not leave its business clock as the process-wide one. Regression: the clock of a closed
 * context stayed installed, so a later engine in the same JVM failed in {@code BusinessClock.system().zone(tenant)}
 * with "HikariDataSource has been closed".
 */
class BusinessClockInstallationTest {

    private BusinessClock before;

    @BeforeEach
    void remember() {
        before = BusinessClock.system();
    }

    @AfterEach
    void restore() {
        BusinessClock.install(before);
    }

    private static BusinessClock clock(String zone) {
        return BusinessClock.fixedZone(Clock.systemUTC(), ZoneId.of(zone));
    }

    @Test
    void aClosedContextGivesTheProcessWideClockBack() {
        BusinessClock a = clock("Asia/Kolkata");
        BusinessClockInstallation.install(a);
        assertSame(a, BusinessClock.system());
        BusinessClockInstallation.release(a);
        assertSame(before, BusinessClock.system(), "the clock from before the context started");
        BusinessClockInstallation.release(a);
        assertSame(before, BusinessClock.system(), "a second release changes nothing");
    }

    @Test
    void ofSeveralLiveContextsTheLastStartedOneStillLiveHoldsTheClockInAnyCloseOrder() {
        BusinessClock a = clock("Asia/Kolkata");
        BusinessClock b = clock("Europe/Berlin");
        BusinessClock c = clock("America/Chicago");
        BusinessClockInstallation.install(a);
        BusinessClockInstallation.install(b);
        BusinessClockInstallation.install(c);
        BusinessClockInstallation.release(a);
        assertSame(c, BusinessClock.system(), "closing an older context does not take the clock from the newest");
        BusinessClockInstallation.release(c);
        assertSame(b, BusinessClock.system(), "never the clock of the closed context a");
        BusinessClockInstallation.release(b);
        assertSame(before, BusinessClock.system());
    }

    @Test
    void aClockLayeredOverTheServersGoesWithTheLastContext() {
        BusinessClock a = clock("Asia/Kolkata");
        BusinessClockInstallation.install(a);
        BusinessClock layered = BusinessClock.of(Clock.systemUTC(), a::zone);   // as the test-mode clock does
        BusinessClock.install(layered);
        BusinessClockInstallation.release(a);
        assertSame(before, BusinessClock.system(), "the layered clock read its zones from the closed context");
    }

    @Test
    void aClockThatWasNeverInstalledHereIsIgnored() {
        BusinessClockInstallation.release(clock("UTC"));
        assertSame(before, BusinessClock.system());
    }
}
