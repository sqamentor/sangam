// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.host;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What one host hands to the assembly (design note section 10.1): its seams, collected once. A host is a thin adapter
 * that builds one of these and nothing that decides product behaviour.
 *
 * <p>{@link #capabilities()} derives the seam capabilities from what was actually handed over, so a host cannot claim
 * a seam it did not supply: no {@link ConnectionBinding} that shares the engine connection means no
 * {@link Capabilities#SHARED_TRANSACTION}; no host scheduler means the assembly's {@link HostScheduler#standalone}
 * one (scheduling is then still supplied), unless the host declined the seam ({@link Builder#withoutScheduler}). The capabilities the assembly areas build from the host's collaborators
 * (security plugin, audit chain, idempotency store, ...) are added by those areas through {@link Builder#assembled}
 * as each area moves into the assembly; no host adds them by hand.
 */
public final class HostSeams {

    private final String hostName;
    private final SangamProperties properties;
    private final boolean defaultsSource;
    private final boolean lifecycleHook;
    private final ConnectionBinding connection;
    private final SecurityContextBinding<?> securityContext;
    private final HostScheduler scheduler;
    private final boolean hostScheduler;
    private final boolean health;
    private final HttpBinding http;
    private final Set<Capability> assembled;

    private HostSeams(Builder b) {
        this.hostName = b.hostName;
        this.properties = b.properties;
        this.defaultsSource = b.defaultsSource;
        this.lifecycleHook = b.lifecycleHook;
        this.connection = b.connection;
        this.securityContext = b.securityContext;
        this.hostScheduler = b.scheduler != null;
        this.scheduler = b.scheduler != null ? b.scheduler
                : b.withoutScheduler ? null : HostScheduler.standalone("sangam-scheduler-" + b.hostName);
        this.health = b.health;
        this.http = b.http;
        this.assembled = Set.copyOf(b.assembled);
    }

    public static Builder builder(String hostName) {
        return new Builder(hostName);
    }

    public String hostName() {
        return hostName;
    }

    /** The host's configuration (with {@code sangam-defaults.yaml} below it when the host supplied that source). */
    public SangamProperties properties() {
        return properties;
    }

    public Optional<ConnectionBinding> connection() {
        return Optional.ofNullable(connection);
    }

    public Optional<SecurityContextBinding<?>> securityContext() {
        return Optional.ofNullable(securityContext);
    }

    /**
     * The host's scheduler, or the assembly's standalone one.
     *
     * @throws CapabilityMissingException when the host declined the scheduling seam ({@link Builder#withoutScheduler})
     */
    public HostScheduler scheduler() {
        if (scheduler == null) {
            throw new CapabilityMissingException("scheduling", List.of(Capabilities.SCHEDULING));
        }
        return scheduler;
    }

    /** True when the scheduler is the host's own (false: the assembly's standalone scheduler). */
    public boolean hostScheduler() {
        return hostScheduler;
    }

    public Optional<HttpBinding> http() {
        return Optional.ofNullable(http);
    }

    /** The capabilities these seams supply, derived from what was handed over. */
    public Set<Capability> capabilities() {
        Set<Capability> out = new LinkedHashSet<>();
        if (properties != null) {
            out.add(Capabilities.CONFIG_SOURCE);
        }
        if (defaultsSource) {
            out.add(Capabilities.DEFAULTS_SOURCE);
        }
        if (lifecycleHook) {
            out.add(Capabilities.LIFECYCLE);
        }
        if (connection != null && connection.sharesEngineConnection()) {
            out.add(Capabilities.SHARED_TRANSACTION);
        }
        if (securityContext != null) {
            out.add(Capabilities.SECURITY_CONTEXT);
        }
        if (scheduler != null) {
            out.add(Capabilities.SCHEDULING);
        }
        if (health) {
            out.add(Capabilities.HEALTH);
        }
        if (http != null) {
            out.add(Capabilities.HTTP);
        }
        out.addAll(assembled);
        return Set.copyOf(out);
    }

    /** The capability set for {@code needed} against what these seams supply. */
    public CapabilitySet resolve(Collection<Capability> needed) {
        return CapabilitySet.resolve(needed, capabilities());
    }

    /**
     * A runtime over these seams: the standalone scheduler (when used) is one of its components, so it stops after
     * everything that schedules on it.
     */
    public SangamRuntime runtime(Collection<Capability> needed, List<? extends Lifecycle> lifecycles,
                                 List<? extends HealthCheck> checks) {
        return SangamRuntime.of(resolve(needed), withScheduler(lifecycles), checks);
    }

    /**
     * A runtime over these seams whose capabilities and after-build components are settled at start-up from
     * {@code claims} (owner decision 376); the standalone scheduler (when used) is one of its fixed components.
     */
    public SangamRuntime runtime(EngineBuildClaims<?> claims, Collection<Capability> needed,
                                 List<? extends Lifecycle> lifecycles, List<? extends HealthCheck> checks) {
        Objects.requireNonNull(claims, "claims");
        return SangamRuntime.settledAtStart(claims.settlement(this, needed), withScheduler(lifecycles), checks);
    }

    private List<Lifecycle> withScheduler(List<? extends Lifecycle> lifecycles) {
        List<Lifecycle> all = new ArrayList<>(lifecycles);
        if (!hostScheduler && scheduler instanceof HostScheduler.Standalone s) { // none when the host declined it
            all.add(s);
        }
        return all;
    }

    /** Collects the seams a host supplies. */
    public static final class Builder {

        private final String hostName;
        private SangamProperties properties;
        private boolean defaultsSource;
        private boolean lifecycleHook;
        private ConnectionBinding connection;
        private SecurityContextBinding<?> securityContext;
        private HostScheduler scheduler;
        private boolean withoutScheduler;
        private boolean health;
        private HttpBinding http;
        private final Set<Capability> assembled = new LinkedHashSet<>();

        private Builder(String hostName) {
            if (hostName == null || !hostName.matches("[a-z][a-z0-9-]*")) {
                throw new IllegalArgumentException("host name must be lower-case letters, digits and '-': " + hostName);
            }
            this.hostName = hostName;
        }

        /** Config-source seam; {@code withDefaults}: {@code sangam-defaults.yaml} is its lowest source. */
        public Builder properties(SangamProperties properties, boolean withDefaults) {
            this.properties = Objects.requireNonNull(properties, "properties");
            this.defaultsSource = withDefaults;
            return this;
        }

        /** Lifecycle seam: the host calls {@link SangamRuntime#start()} and {@link SangamRuntime#stop}. */
        public Builder lifecycleHook() {
            this.lifecycleHook = true;
            return this;
        }

        public Builder connection(ConnectionBinding connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
            return this;
        }

        public Builder securityContext(SecurityContextBinding<?> securityContext) {
            this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
            return this;
        }

        public Builder scheduler(HostScheduler scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            this.withoutScheduler = false;
            return this;
        }

        /**
         * The host declines the scheduling seam: no standalone fallback, {@link Capabilities#SCHEDULING} is missing and
         * the start is refused (a host whose scheduling component was removed by its operator, decision 279).
         */
        public Builder withoutScheduler() {
            this.scheduler = null;
            this.withoutScheduler = true;
            return this;
        }

        /** Health seam: the host maps {@link SangamRuntime#health()} to an endpoint. */
        public Builder health() {
            this.health = true;
            return this;
        }

        public Builder http(HttpBinding http) {
            this.http = Objects.requireNonNull(http, "http");
            return this;
        }

        /**
         * Capabilities an assembly area built from the host's collaborators. Only the seam capabilities are refused
         * here: those are derived from the seams themselves and can never be claimed.
         */
        public Builder assembled(Collection<Capability> capabilities) {
            for (Capability c : capabilities) {
                if (SEAM_DERIVED.contains(c)) {
                    throw new IllegalArgumentException(c.id() + " is derived from the host's seams, not claimed");
                }
                assembled.add(c);
            }
            return this;
        }

        public HostSeams build() {
            return new HostSeams(this);
        }
    }

    /** Capabilities only a seam can supply. */
    static final Set<Capability> SEAM_DERIVED = Set.of(Capabilities.CONFIG_SOURCE, Capabilities.DEFAULTS_SOURCE,
            Capabilities.LIFECYCLE, Capabilities.SHARED_TRANSACTION, Capabilities.SECURITY_CONTEXT,
            Capabilities.SCHEDULING, Capabilities.HEALTH, Capabilities.HTTP);
}
