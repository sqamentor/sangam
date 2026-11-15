// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * When a capability is claimed relative to the engine build (owner decision 376, G7 extraction phase 3 blocker 5), for
 * a host whose container builds the engine before the host's start event (Quarkus):
 *
 * <ul>
 *   <li>a service that does not need the engine (audit chain and outbox writer, idempotency store) is claimed before the
 *       build, through {@link HostSeams.Builder#assembled}; {@link #requireClaimableBeforeBuild} refuses a capability
 *       of {@link #AFTER_ENGINE_BUILD} there;</li>
 *   <li>the integration services that need the engine (inbox and relay, reconciliation, the lifecycle and maintenance
 *       services; assembled whole, {@code IntegrationLifecycles}) claim {@link Capabilities#OUTBOX_RELAY_INBOX} only
 *       after the build succeeded: the host {@link #expect}s it before the build, {@link #engineBuilt} assembles it;</li>
 *   <li>the capability set is settled at start-up ({@link #atStart}): an expected capability whose engine was never
 *       built, or whose assembly failed, is missing, and the start is refused with {@code CAPABILITY_MISSING}.</li>
 * </ul>
 *
 * Before the build an expected capability is pending, not missing ({@link #beforeBuild}), so the engine-build guard
 * does not refuse a host for what only the build can supply; a capability of {@link #AFTER_ENGINE_BUILD} that nothing
 * expects is missing already then.
 *
 * @param <E> the engine type the after-build services are assembled from
 */
public final class EngineBuildClaims<E> {

    private static final Logger LOG = LoggerFactory.getLogger(EngineBuildClaims.class);

    /** Decision 376: the capabilities whose services need the built engine. */
    public static final Set<Capability> AFTER_ENGINE_BUILD = Set.of(Capabilities.OUTBOX_RELAY_INBOX);

    /** Assembles the services of one capability from the built engine; their components join the runtime. */
    @FunctionalInterface
    public interface Assembler<E> {
        List<? extends Lifecycle> assemble(E engine) throws Exception;
    }

    private final Map<Capability, Assembler<E>> expected = new LinkedHashMap<>();
    private final Set<Capability> claimed = new LinkedHashSet<>();
    private final List<Lifecycle> lifecycles = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private boolean built;

    /**
     * Refuses a claim before the engine build of a capability decision 376 claims only after it.
     *
     * @throws IllegalArgumentException naming the capability
     */
    public static void requireClaimableBeforeBuild(Collection<Capability> claims) {
        for (Capability c : claims) {
            if (AFTER_ENGINE_BUILD.contains(c)) {
                throw new IllegalArgumentException(c.id() + " needs the built engine: it is claimed only after the"
                        + " engine build succeeded (owner decision 376), not before");
            }
        }
    }

    /**
     * {@code capability} is assembled by {@code assembler} once the engine is built.
     *
     * @throws IllegalArgumentException when {@code capability} does not need the engine (claimed before the build)
     * @throws IllegalStateException    when it is expected already or the engine was built already
     */
    public synchronized EngineBuildClaims<E> expect(Capability capability, Assembler<E> assembler) {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(assembler, "assembler");
        if (!AFTER_ENGINE_BUILD.contains(capability)) {
            throw new IllegalArgumentException(capability.id() + " does not need the engine: it is claimed before the"
                    + " engine build (owner decision 376)");
        }
        if (built) {
            throw new IllegalStateException("the engine was built already; " + capability.id()
                    + " can no longer be expected");
        }
        if (expected.putIfAbsent(capability, assembler) != null) {
            throw new IllegalStateException(capability.id() + " is expected already");
        }
        return this;
    }

    /** Expected and the engine not built yet. */
    public synchronized Set<Capability> pending() {
        return built ? Set.of() : Set.copyOf(expected.keySet());
    }

    /**
     * The engine was built: assembles every expected capability, in the order expected. A failed assembly leaves its
     * capability missing and keeps the failure for the start refusal; it does not stop the others.
     *
     * @throws IllegalStateException when called twice
     */
    public synchronized void engineBuilt(E engine) {
        Objects.requireNonNull(engine, "engine");
        if (built) {
            throw new IllegalStateException("the engine build was reported already");
        }
        built = true;
        for (Map.Entry<Capability, Assembler<E>> e : expected.entrySet()) {
            Capability c = e.getKey();
            try {
                List<Lifecycle> assembled = List.copyOf(Objects.requireNonNull(e.getValue().assemble(engine),
                        "assembled components"));
                lifecycles.addAll(assembled);
                claimed.add(c);
            } catch (Exception ex) {
                IllegalStateException failure = new IllegalStateException(c.id()
                        + ": assembling its services after the engine build failed", ex);
                failures.add(failure);
                LOG.warn("{} is missing: its services could not be assembled after the engine build", c.id(), ex);
            }
        }
    }

    public synchronized boolean built() {
        return built;
    }

    /** The capabilities assembled after the build. */
    public synchronized Set<Capability> claimed() {
        return Set.copyOf(claimed);
    }

    /** The components of the services assembled after the build. */
    public synchronized List<Lifecycle> lifecycles() {
        return List.copyOf(lifecycles);
    }

    /** Why an after-build assembly failed. */
    public synchronized List<Throwable> failures() {
        return List.copyOf(failures);
    }

    /** Before the build: {@code supplied} plus the pending capabilities (only the build can supply them). */
    public synchronized CapabilitySet beforeBuild(Collection<Capability> needed, Collection<Capability> supplied) {
        Set<Capability> all = new LinkedHashSet<>(supplied);
        all.addAll(pending());
        return CapabilitySet.resolve(needed, all);
    }

    /** At start-up: {@code supplied} plus what the build really claimed; a pending capability is missing. */
    public synchronized CapabilitySet atStart(Collection<Capability> needed, Collection<Capability> supplied) {
        Set<Capability> all = new LinkedHashSet<>(supplied);
        all.addAll(claimed);
        return CapabilitySet.resolve(needed, all);
    }

    /** The runtime's view of these claims over {@code seams}: settled when the runtime starts. */
    public SangamRuntime.Settlement settlement(HostSeams seams, Collection<Capability> needed) {
        Objects.requireNonNull(seams, "seams");
        List<Capability> neededCopy = List.copyOf(needed);
        return new SangamRuntime.Settlement() {
            @Override
            public CapabilitySet capabilities() {
                return atStart(neededCopy, seams.capabilities());
            }

            @Override
            public List<Lifecycle> lifecycles() {
                return EngineBuildClaims.this.lifecycles();
            }

            @Override
            public List<Throwable> failures() {
                return EngineBuildClaims.this.failures();
            }
        };
    }
}
