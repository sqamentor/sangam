// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A long-running component (relay, drainer, scheduler, long-poll pool, ...) as the assembly describes it (design note
 * section 2.1). {@link SangamRuntime} starts them by ascending {@link #phase()} and stops them in exactly the reverse
 * order; the host only maps its own start and shutdown events to {@link SangamRuntime#start()} and
 * {@link SangamRuntime#stop(Duration)}, never the individual components.
 */
public interface Lifecycle {

    /** Unique name within one runtime (used in the start order, errors and health). */
    String name();

    /** Lower phases start first and stop last. */
    int phase();

    void start();

    /** Stops, letting in-flight work finish for at most {@code drain}. */
    void stop(Duration drain);

    /** A descriptor over two callbacks. */
    static Lifecycle of(String name, int phase, Runnable start, Consumer<Duration> stop) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(stop, "stop");
        return new Lifecycle() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int phase() {
                return phase;
            }

            @Override
            public void start() {
                start.run();
            }

            @Override
            public void stop(Duration drain) {
                stop.accept(drain);
            }

            @Override
            public String toString() {
                return name + "@" + phase;
            }
        };
    }
}
