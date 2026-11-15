// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.operaton.bpm.engine.impl.cfg.CompositeProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.Capability;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.assembly.host.CapabilitySet;
import org.sangam.assembly.host.ModuleSwitch;
import org.sangam.assembly.host.SangamProperties;
import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.EngineEventSink;
import org.sangam.core.engine.IncidentSink;
import org.sangam.core.engine.WorkCalendar;
import org.sangam.core.security.EnvironmentPolicy;
import org.sangam.core.security.ProductionMode;
import org.sangam.core.time.EngineTimeZone;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.SangamProcessEnginePlugin;
import org.sangam.engine.db.DatabaseGuard;
import org.sangam.feel.JacksonCompatibilityGuard;

/**
 * Engine construction as host-neutral code (M-141b, extraction phase 1b of the design note): the engine settings read
 * from configuration, the module ports handed to the engine, the engine plugin order and the engine's capability check.
 * A host calls it from its engine hook (Spring: {@code EngineWiring} and {@code EngineConfigurationCustomizer}); the
 * one-transaction binding of engine and product rows (P-1) stays in the host.
 */
public final class EngineAssembly {

    /** The tenant and seat engine plugin of sangam-security (required while the security module is on). */
    public static final String SECURITY_PLUGIN = "org.sangam.security.assignment.SangamSecurityEnginePlugin";

    /**
     * Plugin class names in their required order after {@link SangamProcessEnginePlugin} (sangam-engine README note
     * 11): security, integration and ports first, then the generic modules (case files after ports, geo FEEL
     * functions), the compliance hooks after the module plugins, the profile binding of a case after compliance
     * accepted the start, and the global task listeners last, seeing every task the earlier plugins shaped.
     */
    public static final List<String> PLUGIN_ORDER = List.of(
            SECURITY_PLUGIN,
            "org.sangam.integration.bridge.operaton.SangamIntegrationEnginePlugin",
            "org.sangam.ports.SangamPortsPlugin",
            "org.sangam.casefile.cmmn.CaseFileEnginePlugin",
            "org.sangam.geo.feel.GeoEnginePlugin",
            "org.sangam.server.bridge.CompliancePurposePlugin",
            "org.sangam.server.bridge.ConfigCaseBindingPlugin",
            "org.sangam.server.bridge.OpsTaskListenerPlugin");

    /** M-021: the one test-mode switch (default off). */
    public static final String TEST_MODE_PROPERTY = "sangam.test-mode.enabled";

    private EngineAssembly() {
    }

    // ------------------------------------------------------------------ settings

    /**
     * The host-neutral engine settings read from configuration, applied to {@code settings} before the host binds its
     * {@code sangam.engine.*} prefix (which may still override them, as before):
     * <ul>
     *   <li>the job executor follows {@code operaton.bpm.job-execution.enabled} (default on);</li>
     *   <li>the SQL Server RCSI check follows {@code sangam.db.require-rcsi} (default on);</li>
     *   <li>the CMMN event listeners of the ports module follow {@code sangam.ports.cmmn-event-listeners} (default on);
     *   </li>
     *   <li>test mode (M-021) follows {@value #TEST_MODE_PROPERTY} (default off) and is refused in production;</li>
     *   <li>decision 144: the system audit scope follows {@value #SYSTEM_AUDIT_SCOPE_PROPERTY} (default
     *       {@value #DEFAULT_SYSTEM_AUDIT_SCOPE}, the server's default); the engine validates it at start.</li>
     * </ul>
     *
     * @param profiles the host's active profiles (they decide production and are handed to the test-mode check)
     */
    public static void applyConfiguration(SangamEngineSettings settings, SangamProperties properties,
                                          List<String> profiles) {
        settings.setJobExecutorActivate(properties.getBoolean("operaton.bpm.job-execution.enabled", true));
        settings.setRequireSqlServerRcsi(properties.getBoolean("sangam.db.require-rcsi", true));
        settings.setCmmnEventListenersEnabled(properties.getBoolean("sangam.ports.cmmn-event-listeners", true));
        settings.enableTestMode(testMode(properties, profiles), List.copyOf(profiles));
        settings.setSystemAuditScope(systemAuditScope(properties));
    }

    /** Decision 144: the engine's system audit scope setting, the same key the Spring server binds. */
    public static final String SYSTEM_AUDIT_SCOPE_PROPERTY = "sangam.engine.system-audit-scope";
    /** Decision 144 (integrator ruling): the default every host uses unless configured otherwise. */
    public static final String DEFAULT_SYSTEM_AUDIT_SCOPE = "system:platform";

    /** The configured system audit scope, trimmed; blank or missing gives {@value #DEFAULT_SYSTEM_AUDIT_SCOPE}. */
    public static String systemAuditScope(SangamProperties properties) {
        String v = properties.get(SYSTEM_AUDIT_SCOPE_PROPERTY);
        return v == null || v.isBlank() ? DEFAULT_SYSTEM_AUDIT_SCOPE : v.trim();
    }

    /**
     * M-021: {@value #TEST_MODE_PROPERTY}; on together with production the start is refused. Production is the
     * platform's one resolution ({@link EnvironmentPolicy}, decisions 143 and 198): the switch true (it wins over a
     * development profile), a production profile, or, fail closed, a missing, invalid or contradictory switch.
     */
    public static boolean testMode(SangamProperties properties, Collection<String> profiles) {
        boolean on = properties.getBoolean(TEST_MODE_PROPERTY, false);
        if (on && EnvironmentPolicy.productionOrFailClosed(properties.get(ProductionMode.PROPERTY),
                "host configuration", EnvironmentPolicy.signals(profiles))) {
            throw new IllegalStateException("M-021: " + TEST_MODE_PROPERTY + "=true is refused in production"
                    + " (" + ProductionMode.PROPERTY + "=true, a prod profile, or the switch missing or invalid;"
                    + " decision 143)");
        }
        return on;
    }

    /** True while the security module is on ({@code sangam.modules.security.enabled}, default on). */
    public static boolean securityModuleOn(SangamProperties properties) {
        return ModuleSwitch.enabled(properties, "security", true);
    }

    // ------------------------------------------------------------------ module ports

    /**
     * The ports other modules supply to the engine (sangam-engine README note 4): event sink, expiry policy, audit and
     * incident sinks when present, and every work calendar under its name unless the settings already hold one.
     */
    public static void applyModulePorts(SangamEngineSettings settings, Optional<EngineEventSink> eventSink,
                                        Optional<ExpiryPolicy> expiryPolicy, Optional<AuditSink> auditSink,
                                        Optional<IncidentSink> incidentSink, Map<String, WorkCalendar> calendars) {
        eventSink.ifPresent(settings::setEventSink);
        expiryPolicy.ifPresent(settings::setExpiryPolicy);
        auditSink.ifPresent(settings::setAuditSink);
        incidentSink.ifPresent(settings::setIncidentSink);
        calendars.forEach((name, calendar) -> settings.getWorkCalendars().putIfAbsent(name, calendar));
    }

    // ------------------------------------------------------------------ plugin order

    /**
     * The engine's plugin list: the host's own default plugins first (grouped in one composite, so the Sangam plugins
     * can rely on and override their defaults), then {@code sangam}, then the {@link #PLUGIN_ORDER} plugins in that
     * order, then every other plugin in its incoming order, and last {@link EngineSettingsReapplyPlugin}.
     *
     * @param current           the plugins the host's engine configuration holds now (composites are flattened)
     * @param sangam            the {@link SangamProcessEnginePlugin} of this engine
     * @param hostDefault       which plugin classes found inside a composite are the host's default plugins
     * @param registeredOfType  the host's registered plugins of one {@link #PLUGIN_ORDER} class, for plugins the
     *                          host's composite did not collect
     * @param databaseType      the database type the database guard detected (re-pinned last)
     */
    public static List<ProcessEnginePlugin> orderPlugins(List<ProcessEnginePlugin> current, ProcessEnginePlugin sangam,
                                                         Predicate<String> hostDefault,
                                                         Function<String, List<ProcessEnginePlugin>> registeredOfType,
                                                         String databaseType) {
        return orderPlugins(PLUGIN_ORDER, current, sangam, hostDefault, registeredOfType, databaseType);
    }

    /** {@link #orderPlugins(List, ProcessEnginePlugin, Predicate, Function, String)} over an explicit order. */
    static List<ProcessEnginePlugin> orderPlugins(List<String> order, List<ProcessEnginePlugin> current,
                                                  ProcessEnginePlugin sangam, Predicate<String> hostDefault,
                                                  Function<String, List<ProcessEnginePlugin>> registeredOfType,
                                                  String databaseType) {
        List<ProcessEnginePlugin> host = new ArrayList<>();
        Map<String, List<ProcessEnginePlugin>> ordered = new LinkedHashMap<>();
        order.forEach(n -> ordered.put(n, new ArrayList<>()));
        List<ProcessEnginePlugin> others = new ArrayList<>();
        Map<ProcessEnginePlugin, Boolean> seen = new IdentityHashMap<>();
        for (ProcessEnginePlugin p : current == null ? List.<ProcessEnginePlugin>of() : current) {
            if (p instanceof CompositeProcessEnginePlugin composite) {
                for (ProcessEnginePlugin inner : composite.getPlugins()) {
                    classify(inner, sangam, host, ordered, others, seen, hostDefault, true);
                }
            } else {
                classify(p, sangam, host, ordered, others, seen, hostDefault, false);
            }
        }
        for (String className : order) {
            for (ProcessEnginePlugin p : registeredOfType.apply(className)) {
                if (seen.put(p, Boolean.TRUE) == null) {
                    ordered.get(className).add(p);
                }
            }
        }
        List<ProcessEnginePlugin> result = new ArrayList<>();
        if (!host.isEmpty()) {
            result.add(new CompositeProcessEnginePlugin(host));
        }
        result.add(sangam);
        ordered.values().forEach(result::addAll);
        result.addAll(others);
        result.add(new EngineSettingsReapplyPlugin(databaseType));
        return result;
    }

    private static void classify(ProcessEnginePlugin p, ProcessEnginePlugin sangam, List<ProcessEnginePlugin> host,
                                 Map<String, List<ProcessEnginePlugin>> ordered, List<ProcessEnginePlugin> others,
                                 Map<ProcessEnginePlugin, Boolean> seen, Predicate<String> hostDefault,
                                 boolean fromComposite) {
        if (p == sangam || p instanceof SangamProcessEnginePlugin || seen.put(p, Boolean.TRUE) != null) {
            return;
        }
        String name = p.getClass().getName();
        if (ordered.containsKey(name)) {
            ordered.get(name).add(p);
        } else if (fromComposite && hostDefault.test(name)) {
            host.add(p);
        } else {
            others.add(p);
        }
    }

    // ------------------------------------------------------------------ capabilities

    /**
     * The capabilities the engine needs: the shared transaction (P-1) and the config source always; the tenant
     * security plugin while the security module is on.
     */
    public static Set<Capability> needed(boolean securityModuleOn) {
        Set<Capability> out = new LinkedHashSet<>(List.of(Capabilities.SHARED_TRANSACTION, Capabilities.CONFIG_SOURCE));
        if (securityModuleOn) {
            out.add(Capabilities.TENANT_SECURITY_PLUGIN);
        }
        return out;
    }

    /**
     * Refuses the engine start when a capability it needs is missing. The tenant security plugin counts as supplied
     * only when it is in the final plugin list, so a security module whose plugin did not reach the engine refuses the
     * start instead of running tenant-bearing processes without it.
     *
     * @param hostSupplied what the host's seams supply (for the Spring host: config source and shared transaction)
     */
    public static CapabilitySet requireCapabilities(boolean securityModuleOn, List<ProcessEnginePlugin> plugins,
                                                    Collection<Capability> hostSupplied) {
        Set<Capability> supplied = new LinkedHashSet<>(hostSupplied);
        if (plugins.stream().anyMatch(p -> SECURITY_PLUGIN.equals(p.getClass().getName()))) {
            supplied.add(Capabilities.TENANT_SECURITY_PLUGIN);
        }
        CapabilitySet set = CapabilitySet.resolve(needed(securityModuleOn), supplied);
        set.refuseStartIfMissing();
        return set;
    }

    /**
     * As {@link #requireCapabilities(boolean, List, Collection)}, together with the engine area's own checks
     * ({@code engine}): one refusal names every missing capability of both, in catalogue order, and carries each
     * failed engine check's own exception as a suppressed exception (its message, for example the database guard's,
     * is kept).
     */
    public static CapabilitySet requireCapabilities(boolean securityModuleOn, List<ProcessEnginePlugin> plugins,
                                                    Collection<Capability> hostSupplied, EngineChecks engine) {
        Set<Capability> supplied = new LinkedHashSet<>(hostSupplied);
        if (plugins.stream().anyMatch(p -> SECURITY_PLUGIN.equals(p.getClass().getName()))) {
            supplied.add(Capabilities.TENANT_SECURITY_PLUGIN);
        }
        supplied.addAll(engine.supplied());
        Set<Capability> needed = new LinkedHashSet<>(needed(securityModuleOn));
        needed.addAll(engine.needed());
        CapabilitySet set = CapabilitySet.resolve(needed, supplied);
        List<Capability> missing = set.missingRequired();
        if (!missing.isEmpty()) {
            throw refusal("engine start-up", missing, engine.failures());
        }
        return set;
    }

    // ------------------------------------------------------------------ engine checks (phase 1b)

    /**
     * Every capability the engine area checks itself on every host (owner decisions 276, 286 and 287): the UTC JVM,
     * the database checks, the product schema before the engine, the FEEL engine's Jackson line and, while executor
     * partitioning is on, the job leases. A host leaves these to {@link #checkBeforeDatabase} and
     * {@link #checkDatabase}; it never claims them itself.
     */
    public static final Set<Capability> ENGINE_AREA = Set.of(Capabilities.UTC_JVM, Capabilities.DATABASE_CHECKS,
            Capabilities.SCHEMA_BEFORE_ENGINE, Capabilities.JACKSON_FEEL, Capabilities.JOB_LEASES);

    /**
     * The engine area's needs for these settings: always the UTC JVM, the database checks, the schema before the engine
     * and the FEEL engine's Jackson line; the job leases only while {@code sangam.engine.executor-partitioning} is on
     * (owner decision 286: without partitioning there are no leases to need, on every host).
     */
    public static Set<Capability> engineNeeded(boolean executorPartitioning) {
        Set<Capability> out = new LinkedHashSet<>(List.of(Capabilities.UTC_JVM, Capabilities.DATABASE_CHECKS,
                Capabilities.SCHEMA_BEFORE_ENGINE, Capabilities.JACKSON_FEEL));
        if (executorPartitioning) {
            out.add(Capabilities.JOB_LEASES);
        }
        return out;
    }

    /**
     * The engine area's checks before any database access:
     * <ul>
     *   <li>{@code utc-jvm}: the core UTC gate ({@link EngineTimeZone#requireUtcJvm()}, SL-53);</li>
     *   <li>{@code jackson-feel-compatibility}: {@link JacksonCompatibilityGuard#check()} (SG-33), the check the FEEL
     *       engine runs when it is built, now before the engine exists;</li>
     *   <li>{@code schema-before-engine}: the engine applies the product schema in {@code prepare}
     *       ({@code applyProductSchema}, default on), or the host runs its own schema step first
     *       ({@code hostRunsSchema}, the Spring host's Liquibase beans); neither means the engine would start on a
     *       schema nobody created;</li>
     *   <li>{@code job-leases} (owner decision 286), only while executor partitioning is on: supplied by the engine
     *       itself, whose {@code JobExecutorSetup} installs the partitioned acquisition with its leases and refuses at
     *       {@code postInit} an acquisition option the partitioned query does not reproduce (M-056).</li>
     * </ul>
     *
     * @param hostRunsSchema true only when the host ran its own product schema step before this call
     */
    public static EngineChecks checkBeforeDatabase(SangamEngineSettings settings, boolean hostRunsSchema) {
        Set<Capability> supplied = new LinkedHashSet<>();
        List<RuntimeException> failures = new ArrayList<>();
        check(Capabilities.UTC_JVM, EngineTimeZone::requireUtcJvm, supplied, failures);
        check(Capabilities.JACKSON_FEEL, JacksonCompatibilityGuard::check, supplied, failures);
        if (settings.isApplyProductSchema() || hostRunsSchema) {
            supplied.add(Capabilities.SCHEMA_BEFORE_ENGINE);
        } else {
            failures.add(new IllegalStateException("sangam.engine.apply-product-schema=false and the host runs no"
                    + " product schema step of its own: the engine would start before the product schema exists"));
        }
        Set<Capability> needed = engineNeeded(settings.isExecutorPartitioning());
        needed.remove(Capabilities.DATABASE_CHECKS);
        if (needed.contains(Capabilities.JOB_LEASES)) {
            supplied.add(Capabilities.JOB_LEASES);
        }
        return new EngineChecks(needed, supplied, failures);
    }

    /**
     * Owner decision 287 (c), on every host: the database guard ({@link DatabaseGuard#check}: dialect, READ COMMITTED,
     * SQL Server RCSI, MySQL server identity, CockroachDB opt-in) on the very data source the engine is handed, not
     * only on the isolation wrapper {@code SangamEngineConfigurer.prepare} checks. A pool that hands the engine another
     * isolation level fails here with the guard's own message.
     */
    public static EngineChecks checkDatabase(SangamEngineSettings settings, DataSource engineDataSource) {
        Set<Capability> supplied = new LinkedHashSet<>();
        List<RuntimeException> failures = new ArrayList<>();
        check(Capabilities.DATABASE_CHECKS, () -> DatabaseGuard.check(engineDataSource,
                settings.isRequireSqlServerRcsi(), settings.getMysqlDatabaseType(),
                settings.isExperimentalCockroachDb(), settings.isExperimentalYugabyteDb()), supplied, failures);
        return new EngineChecks(Set.of(Capabilities.DATABASE_CHECKS), supplied, failures);
    }

    private static void check(Capability capability, Runnable check, Set<Capability> supplied,
                              List<RuntimeException> failures) {
        try {
            check.run();
            supplied.add(capability);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    /**
     * The {@code CAPABILITY_MISSING} refusal naming {@code missing}, with each of {@code causes} as a suppressed
     * exception, so the reason of every failed check stays in the refusal.
     */
    public static CapabilityMissingException refusal(String what, Collection<Capability> missing,
                                                     Collection<? extends Throwable> causes) {
        CapabilityMissingException e = new CapabilityMissingException(what, List.copyOf(missing));
        causes.forEach(e::addSuppressed);
        return e;
    }

    /**
     * What the engine area's checks found.
     *
     * @param needed   what these settings need from the engine area
     * @param supplied what the checks established
     * @param failures why each failed check failed (their own exceptions)
     */
    public record EngineChecks(Set<Capability> needed, Set<Capability> supplied, List<RuntimeException> failures) {

        public EngineChecks {
            needed = Set.copyOf(needed);
            supplied = Set.copyOf(supplied);
            failures = List.copyOf(failures);
        }

        /** Nothing checked yet: everything these settings need from the engine area counts as missing. */
        public static EngineChecks notRun(boolean executorPartitioning) {
            return new EngineChecks(engineNeeded(executorPartitioning), Set.of(), List.of());
        }

        /** Both results together. */
        public EngineChecks and(EngineChecks other) {
            Set<Capability> n = new LinkedHashSet<>(needed);
            n.addAll(other.needed);
            Set<Capability> s = new LinkedHashSet<>(supplied);
            s.addAll(other.supplied);
            List<RuntimeException> f = new ArrayList<>(failures);
            f.addAll(other.failures);
            return new EngineChecks(n, s, f);
        }

        /** The needed capabilities the checks did not establish, in catalogue order. */
        public List<Capability> missing() {
            return Capabilities.required().stream().filter(c -> needed.contains(c) && !supplied.contains(c)).toList();
        }

        /** Refuses {@code what} when a needed capability is missing. */
        public void refuseIfMissing(String what) {
            List<Capability> m = missing();
            if (!m.isEmpty()) {
                throw refusal(what, m, failures);
            }
        }
    }
}
