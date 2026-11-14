// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lifecycle seam's other side (design note section 10.1): the host calls {@link #start()} once its own container is
 * up and {@link #stop(Duration)} on shutdown; the order of the components is decided here, not in the host.
 *
 * <ul>
 *   <li>{@link #start()} first refuses when a required capability is missing (fail closed, nothing is started), then
 *       starts every {@link Lifecycle} by ascending phase (equal phases by name). When one fails, the ones already
 *       started are stopped in reverse order and the failure is rethrown.</li>
 *   <li>{@link #stop(Duration)} stops in exactly the reverse of the start order. Every component gets its stop call even
 *       when an earlier one throws; the first failure is rethrown with the others suppressed.</li>
 *   <li>{@link #health()} reports the capability state first, then every check; a check that throws is DOWN.</li>
 * </ul>
 */
public final class SangamRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(SangamRuntime.class);

    /** Name of the built-in health check over the {@link CapabilitySet}. */
    public static final String CAPABILITIES_CHECK = "capabilities";

    public enum State {
        NEW, RUNNING, STOPPED, FAILED
    }

    private final CapabilitySet capabilities;
    private final List<Lifecycle> order;
    private final List<HealthCheck> checks;
    private final List<Lifecycle> started = new ArrayList<>();
    private State state = State.NEW;

    private SangamRuntime(CapabilitySet capabilities, List<Lifecycle> order, List<HealthCheck> checks) {
        this.capabilities = capabilities;
        this.order = order;
        this.checks = checks;
    }

    /** A runtime over {@code lifecycles} (names must be unique) and {@code checks}. */
    public static SangamRuntime of(CapabilitySet capabilities, List<? extends Lifecycle> lifecycles,
                                   List<? extends HealthCheck> checks) {
        Objects.requireNonNull(capabilities, "capabilities");
        Set<String> names = new HashSet<>();
        for (Lifecycle l : lifecycles) {
            if (!names.add(Objects.requireNonNull(l.name(), "lifecycle name"))) {
                throw new IllegalArgumentException("two lifecycle components are named " + l.name());
            }
        }
        List<Lifecycle> sorted = new ArrayList<>(lifecycles);
        sorted.sort(Comparator.comparingInt(Lifecycle::phase).thenComparing(Lifecycle::name));
        Set<String> checkNames = new HashSet<>(Set.of(CAPABILITIES_CHECK));
        for (HealthCheck h : checks) {
            if (!checkNames.add(Objects.requireNonNull(h.name(), "health check name"))) {
                throw new IllegalArgumentException("two health checks are named " + h.name());
            }
        }
        return new SangamRuntime(capabilities, List.copyOf(sorted), List.copyOf(checks));
    }

    public CapabilitySet capabilities() {
        return capabilities;
    }

    public synchronized State state() {
        return state;
    }

    /** Component names in start order. */
    public List<String> startOrder() {
        return order.stream().map(Lifecycle::name).toList();
    }

    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException("the runtime was already started (state " + state + ")");
        }
        try {
            capabilities.refuseStartIfMissing();
        } catch (CapabilityMissingException e) {
            state = State.FAILED;
            throw e;
        }
        capabilities.missingConveniences().forEach(c -> LOG.info("Sangam runs without {}: {}", c.id(), c.whenMissing()));
        for (Lifecycle l : order) {
            try {
                l.start();
                started.add(l);
            } catch (RuntimeException | Error e) {
                state = State.FAILED;
                RuntimeException rollback = stopStarted(Duration.ZERO);
                if (rollback != null) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        }
        state = State.RUNNING;
    }

    /** Stops what was started, in reverse order; a no-op when nothing runs. */
    public synchronized void stop(Duration drain) {
        Objects.requireNonNull(drain, "drain");
        if (drain.isNegative()) {
            throw new IllegalArgumentException("drain must not be negative: " + drain);
        }
        RuntimeException failure = stopStarted(drain);
        if (state == State.RUNNING) {
            state = State.STOPPED;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private RuntimeException stopStarted(Duration drain) {
        RuntimeException first = null;
        for (int i = started.size() - 1; i >= 0; i--) {
            Lifecycle l = started.get(i);
            try {
                l.stop(drain);
            } catch (RuntimeException e) {
                if (first == null) {
                    first = new IllegalStateException("stopping " + l.name() + " failed", e);
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        started.clear();
        return first;
    }

    /** The capability state, then every check by name order of registration; a throwing check is DOWN. */
    public List<HealthCheck.Result> health() {
        List<HealthCheck.Result> out = new ArrayList<>();
        Map<String, String> caps = new LinkedHashMap<>();
        capabilities.missingRequired().forEach(c -> caps.put(c.id(), "MISSING"));
        capabilities.missingConveniences().forEach(c -> caps.put(c.id(), "absent"));
        out.add(capabilities.missingRequired().isEmpty() ? HealthCheck.Result.up(CAPABILITIES_CHECK, caps)
                : HealthCheck.Result.down(CAPABILITIES_CHECK, caps));
        for (HealthCheck h : checks) {
            try {
                HealthCheck.Result r = h.check();
                out.add(r == null ? HealthCheck.Result.down(h.name(), Map.of("error", "no result")) : r);
            } catch (RuntimeException e) {
                out.add(HealthCheck.Result.down(h.name(), Map.of("error", String.valueOf(e.getMessage()))));
            }
        }
        return List.copyOf(out);
    }
}
