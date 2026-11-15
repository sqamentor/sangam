// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.JakartaTransactionProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.assembly.engine.EngineAssembly;
import org.sangam.assembly.host.Capabilities;
import org.sangam.assembly.host.Capability;
import org.sangam.assembly.host.CapabilityMissingException;
import org.sangam.engine.SangamProcessEnginePlugin;

/**
 * Refuses the engine build on the Quarkus host before the engine exists (the Operaton extension builds the engine at
 * runtime init, before Quarkus' {@code StartupEvent}), so no job runs on a host that would refuse to start:
 * <ol>
 *   <li>the engine's own check ({@link EngineAssembly#requireCapabilities}): config source, shared transaction (only
 *       when the engine really holds the host's JTA transaction manager and data source), the tenant security plugin
 *       while the security module is on, and the engine area of extraction phase 1b
 *       ({@link EngineAssembly.EngineChecks}: UTC, database checks, schema before the engine, Jackson line, job leases
 *       while partitioning is on), as {@link SangamEngineConfiguration} ran them; not run counts as missing;</li>
 *   <li>every other required capability of the host, as it stands before the build (decision 376: what only the build
 *       can supply is pending, not missing, {@link QuarkusHost#capabilitiesBeforeBuild()}; the runtime settles it at
 *       start-up).</li>
 * </ol>
 * One {@code CAPABILITY_MISSING} refusal names every capability either check misses (sorted by id), so the refusal of
 * a start without one capability always names that capability, and keeps each failed engine check's own exception as
 * a suppressed exception. {@link #refuseHostGaps(EngineAssembly.EngineChecks)} runs the host check with the engine
 * area's database-free checks before {@link SangamEngineConfiguration} touches the database; {@link #preInit} runs
 * everything again on the final plugin list and refuses an engine without {@link SangamProcessEnginePlugin} (Sangam's
 * engine rules), so the Quarkus engine never runs without them.
 */
public final class EngineCapabilityGuard extends AbstractProcessEnginePlugin {

    private final QuarkusHost host;

    static final String NO_SANGAM_PLUGIN = "the Quarkus engine configuration has no SangamProcessEnginePlugin: Sangam's"
            + " engine rules (exact decimals, strict FEEL, tenant resolution, UTC gate, job guards, history audit) would"
            + " not run; start-up refused";

    /** What the engine area's checks found so far ({@link #engineChecked}); none run yet: all missing. */
    private volatile EngineAssembly.EngineChecks engine;

    public EngineCapabilityGuard(QuarkusHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** Refuses when the host lacks a required capability outside the engine area; nothing else has run yet. */
    public void refuseHostGaps() {
        List<Capability> missing = host.capabilitiesBeforeBuild().missingRequired();
        if (!missing.isEmpty()) {
            throw new CapabilityMissingException("engine start-up", missing);
        }
    }

    /**
     * Records {@code checks} and refuses, in one refusal, the host's gaps together with what those engine checks
     * missed (each failed check's exception suppressed in it).
     */
    public void refuseHostGaps(EngineAssembly.EngineChecks checks) {
        engineChecked(checks);
        Set<Capability> missing = new TreeSet<>(Comparator.comparing(Capability::id));
        missing.addAll(host.capabilitiesBeforeBuild().missingRequired());
        missing.addAll(checks.missing());
        if (!missing.isEmpty()) {
            throw EngineAssembly.refusal("engine start-up", List.copyOf(missing), checks.failures());
        }
    }

    /** Adds {@code checks} to what the engine area's checks found (preInit re-checks all of it). */
    public void engineChecked(EngineAssembly.EngineChecks checks) {
        EngineAssembly.EngineChecks current = engine;
        engine = current == null ? checks : current.and(checks);
    }

    /** What the engine area's checks found, or "not run" for the host's settings. */
    EngineAssembly.EngineChecks engineChecks() {
        EngineAssembly.EngineChecks current = engine;
        return current != null ? current
                : EngineAssembly.EngineChecks.notRun(host.engineSettings().isExecutorPartitioning());
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        if (configuration.getProcessEnginePlugins().stream().noneMatch(SangamProcessEnginePlugin.class::isInstance)) {
            throw new IllegalStateException(NO_SANGAM_PLUGIN);
        }
        Set<Capability> missing = new TreeSet<>(Comparator.comparing(Capability::id));
        EngineAssembly.EngineChecks checks = engineChecks();
        try {
            EngineAssembly.requireCapabilities(EngineAssembly.securityModuleOn(host.properties()),
                    configuration.getProcessEnginePlugins(), engineSupplied(configuration), checks);
        } catch (CapabilityMissingException e) {
            missing.addAll(e.missing());
        }
        missing.addAll(host.capabilitiesBeforeBuild().missingRequired());
        if (!missing.isEmpty()) {
            throw EngineAssembly.refusal("engine start-up", List.copyOf(missing), checks.failures());
        }
    }

    /** The host-supplied capabilities the engine check accepts for this engine configuration. */
    Set<Capability> engineSupplied(ProcessEngineConfigurationImpl configuration) {
        Set<Capability> supplied = new LinkedHashSet<>();
        Set<Capability> seams = host.seams().capabilities();
        if (seams.contains(Capabilities.CONFIG_SOURCE)) {
            supplied.add(Capabilities.CONFIG_SOURCE);
        }
        if (seams.contains(Capabilities.SHARED_TRANSACTION) && sameTransaction(configuration)) {
            supplied.add(Capabilities.SHARED_TRANSACTION);
        }
        return supplied;
    }

    /** The engine uses exactly the host's JTA transaction manager and data source (identity, not equality). */
    boolean sameTransaction(ProcessEngineConfigurationImpl configuration) {
        JtaTransactions tx = host.transactions();
        return configuration instanceof JakartaTransactionProcessEngineConfiguration jta
                && jta.getTransactionManager() == tx.transactionManager()
                && configuration.getDataSource() == tx.dataSource();
    }
}
