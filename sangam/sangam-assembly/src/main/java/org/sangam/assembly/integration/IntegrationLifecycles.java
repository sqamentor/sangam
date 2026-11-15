// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.assembly.host.HostSeams;
import org.sangam.assembly.host.Lifecycle;
import org.sangam.assembly.host.SangamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The start and stop order of the integration background services, decided here and not in a host (design note
 * section 2.1; extraction phase 3). The order is the one the Spring host's {@code IntegrationServices} keeps: inbox,
 * outbox relay, reconciliation, maintenance, connector worker; stopped in exactly the reverse order by
 * {@code SangamRuntime}.
 *
 * <p>Owner decision 315: the services keep their own executors, so a host hands over only how each one starts and
 * stops; but none of them is assembled for a host without the scheduling seam ({@link #builder} refuses with
 * {@code CAPABILITY_MISSING}), the same refusal the runtime gives at start.
 *
 * <p>{@value #BACKGROUND_ENABLED} (default {@code true}, the Spring host's key): {@code false} assembles the services
 * without starting them (they still serve direct calls and are still closed on shutdown).
 */
public final class IntegrationLifecycles {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationLifecycles.class);

    /** The phase of the first service; the same value as the Spring host's {@code IntegrationServices.PHASE}. */
    public static final int PHASE = Integer.MAX_VALUE - 100;

    /** The Spring host's switch of the background services. */
    public static final String BACKGROUND_ENABLED = "sangam.integration.background.enabled";

    /** The integration background services, in start order. */
    public enum Service {
        INBOX(true), RELAY(true), RECONCILIATION(true), MAINTENANCE(true),
        /** The connector external-task worker: only where topics are bound. */
        WORKER(false);

        private final boolean always;

        Service(boolean always) {
            this.always = always;
        }

        /** The runtime component name. */
        public String componentName() {
            return "sangam-integration-" + name().toLowerCase(java.util.Locale.ROOT);
        }

        /** {@link #PHASE} plus the position: ascending phases start in this order and stop in reverse. */
        public int phase() {
            return PHASE + ordinal();
        }
    }

    private IntegrationLifecycles() {
    }

    /**
     * Starts collecting the services of a host.
     *
     * @throws CapabilityMissingException when the host declined the scheduling seam (decision 315)
     */
    public static Builder builder(HostSeams seams, SangamProperties properties) {
        Objects.requireNonNull(seams, "seams");
        Objects.requireNonNull(properties, "properties");
        seams.scheduler(); // refuses with CAPABILITY_MISSING naming scheduling
        return new Builder(properties.getBoolean(BACKGROUND_ENABLED, true));
    }

    /** Collects how each service starts and stops; the order never depends on the order of the calls. */
    public static final class Builder {

        private final boolean background;
        private final Map<Service, Lifecycle> components = new EnumMap<>(Service.class);

        private Builder(boolean background) {
            this.background = background;
        }

        /** {@code service} starts with {@code start} and stops by closing {@code stop}. */
        public Builder add(Service service, Runnable start, AutoCloseable stop) {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(stop, "stop");
            if (components.containsKey(service)) {
                throw new IllegalStateException(service.componentName() + " was already added");
            }
            String name = service.componentName();
            components.put(service, Lifecycle.of(name, service.phase(), () -> {
                if (background) {
                    start.run();
                }
            }, drain -> close(name, stop)));
            return this;
        }

        /**
         * The components in start order.
         *
         * @throws IllegalStateException when the inbox, the relay, the reconciliation engine or the maintenance is
         *                               missing: the integration area is assembled whole or not at all
         */
        public List<Lifecycle> build() {
            List<String> missing = new ArrayList<>();
            for (Service s : Service.values()) {
                if (s.always && !components.containsKey(s)) {
                    missing.add(s.componentName());
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("the integration services are assembled whole; missing " + missing);
            }
            if (!background) {
                LOG.info("sangam-integration background services are assembled but not started ({}=false)",
                        BACKGROUND_ENABLED);
            }
            return List.copyOf(components.values());
        }
    }

    private static void close(String name, AutoCloseable stop) {
        try {
            stop.close();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("stopping " + name + " failed", e);
        }
    }
}
