// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.sangam.core.time.BusinessClock;

/**
 * The server's hold on the process-wide business clock ({@link BusinessClock#system()}). A server context installs its
 * tenant-profile clock when it starts and gives the process-wide clock back when it closes. Without the second half
 * the process kept the clock of a closed context, and the next caller of {@code BusinessClock.system()} in the same
 * JVM (a second context after a restart in an embedding host, another test class) read tenant zones through a
 * connection pool that was already closed.
 *
 * <p>Several contexts can be live in one JVM ({@code sangam.engine.allow-multiple-engines-per-jvm}); they may close in
 * any order. The process-wide clock is then the clock of the context that started last among those still live. When
 * the last one closes, the clock that was there before the first one started comes back; this also drops a clock that
 * was layered over the server's while it ran (the test-mode clock takes its zones from the clock it replaces).
 */
final class BusinessClockInstallation {

    private static final Object LOCK = new Object();
    /** Clocks of the live server contexts, oldest first. */
    private static final List<BusinessClock> LIVE = new ArrayList<>();
    /** The process-wide clock before the first live context installed its own. */
    private static BusinessClock baseline;

    private BusinessClockInstallation() {
    }

    /** Makes {@code clock} the process-wide business clock for as long as its context lives. */
    static void install(BusinessClock clock) {
        Objects.requireNonNull(clock, "clock");
        synchronized (LOCK) {
            BusinessClock previous = BusinessClock.install(clock);
            if (LIVE.isEmpty()) {
                baseline = previous;
            }
            LIVE.add(clock);
        }
    }

    /** The context of {@code clock} closes. A clock that was not installed here (or was released already) is ignored. */
    static void release(BusinessClock clock) {
        synchronized (LOCK) {
            if (!LIVE.removeIf(c -> c == clock)) {
                return;
            }
            if (LIVE.isEmpty()) {
                BusinessClock.install(baseline);
                baseline = null;
            } else if (BusinessClock.system() == clock) {
                BusinessClock.install(LIVE.get(LIVE.size() - 1));
            }
        }
    }
}
