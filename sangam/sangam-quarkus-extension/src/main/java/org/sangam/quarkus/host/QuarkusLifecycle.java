// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.time.Duration;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import org.sangam.assembly.host.SangamRuntime;

/**
 * Lifecycle seam of the Quarkus host (design note section 10.1, decision 279): Quarkus' {@link StartupEvent} calls
 * {@link SangamRuntime#start()}, its {@link ShutdownEvent} calls {@link SangamRuntime#stop(Duration)} with
 * {@value QuarkusHost#DRAIN_PROPERTY} (default 30 s). An optional bean: without it (an operator's
 * {@code quarkus.arc.exclude-types}) nothing would start or drain Sangam in order, so {@code lifecycle} is missing and
 * the start is refused.
 */
@Singleton
public class QuarkusLifecycle {

    /** Default drain on shutdown. */
    static final Duration DEFAULT_DRAIN = Duration.ofSeconds(30);

    void onStart(@Observes StartupEvent event, QuarkusHost host) {
        host.runtime().start();
    }

    void onStop(@Observes ShutdownEvent event, QuarkusHost host) {
        host.runtime().stop(host.properties().getDuration(QuarkusHost.DRAIN_PROPERTY, DEFAULT_DRAIN));
    }
}
