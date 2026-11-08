// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Transactions;
import org.sangam.programme.dashboard.DashboardFeed;
import org.sangam.programme.indicator.IndicatorRegistry;
import org.sangam.programme.milestone.MilestoneService;
import org.sangam.programme.period.PeriodService;
import org.sangam.programme.ports.ProgrammePorts;
import org.sangam.programme.programme.ProgrammeService;
import org.sangam.programme.progress.ProgressService;
import org.sangam.programme.progress.VarianceService;
import org.sangam.programme.returns.ReturnService;
import org.sangam.programme.returns.ValidationEngine;
import org.sangam.programme.rollup.RollupService;
import org.sangam.programme.target.TargetService;

import java.util.Objects;

/**
 * Wiring of every programme service for one database, plus the static holder the BPMN delegates use (delegates are
 * instantiated by the engine and cannot take constructor arguments). The integrator builds one module in
 * {@code sangam-server} and calls {@link #install}.
 *
 * <pre>
 * ProgrammeModule pm = new ProgrammeModule(dialect, EngineTx.transactions(engine), new Outbox(dialect),
 *         new ProgrammeModule.Ports(orgHierarchy, campaignStarter, dmnReturnValidator, notifier, dashboardPort, ledgerSource));
 * ProgrammeModule.install(pm);
 * </pre>
 */
public final class ProgrammeModule {

    public record Ports(ProgrammePorts.HierarchyPort hierarchy, ProgrammePorts.CampaignStarter campaignStarter,
                        ProgrammePorts.ReturnValidator returnValidator, ProgrammePorts.Notifier notifier,
                        ProgrammePorts.DashboardPort dashboard, ProgrammePorts.FinancialSource financialSource) {

        public Ports {
            hierarchy = hierarchy == null ? ProgrammePorts.HierarchyPort.FLAT : hierarchy;
            campaignStarter = campaignStarter == null ? ProgrammePorts.CampaignStarter.NONE : campaignStarter;
            returnValidator = returnValidator == null ? ProgrammePorts.ReturnValidator.NONE : returnValidator;
            notifier = notifier == null ? ProgrammePorts.Notifier.NONE : notifier;
            dashboard = dashboard == null ? ProgrammePorts.DashboardPort.NONE : dashboard;
            financialSource = financialSource == null ? ProgrammePorts.FinancialSource.NONE : financialSource;
        }

        public static Ports none() {
            return new Ports(null, null, null, null, null, null);
        }
    }

    private static volatile ProgrammeModule installed;

    private final Dialect dialect;
    private final Transactions tx;
    private final Ports ports;
    private final IndicatorRegistry registry;
    private final PeriodService periods;
    private final ProgrammeService programmes;
    private final TargetService targets;
    private final MilestoneService milestones;
    private final RollupService rollup;
    private final ValidationEngine validation;
    private final ReturnService returns;
    private final ProgressService progress;
    private final VarianceService variance;
    private final DashboardFeed dashboard;

    public ProgrammeModule(Dialect dialect, Transactions tx, Outbox outbox, Ports ports) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.tx = Objects.requireNonNull(tx, "transactions");
        this.ports = ports == null ? Ports.none() : ports;
        this.registry = new IndicatorRegistry(dialect);
        this.periods = new PeriodService(dialect);
        this.programmes = new ProgrammeService(dialect, registry);
        this.targets = new TargetService(dialect, registry, programmes, periods, this.ports.hierarchy());
        this.milestones = new MilestoneService(dialect, programmes);
        this.rollup = new RollupService(dialect, registry, periods, programmes, this.ports.hierarchy());
        this.validation = new ValidationEngine(this.ports.returnValidator());
        this.returns = new ReturnService(dialect, registry, periods, targets, rollup, validation, this.ports.hierarchy(),
                this.ports.campaignStarter(), this.ports.notifier(), outbox);
        this.progress = new ProgressService(dialect, registry, programmes, periods, targets, milestones, rollup, this.ports.hierarchy(),
                this.ports.financialSource());
        this.variance = new VarianceService(dialect, registry, programmes, periods, targets, rollup, outbox);
        this.dashboard = new DashboardFeed(dialect, progress, rollup, variance, this.ports.hierarchy(), outbox, this.ports.dashboard());
    }

    public static void install(ProgrammeModule module) {
        installed = module;
    }

    public static ProgrammeModule get() {
        ProgrammeModule m = installed;
        if (m == null) {
            throw new IllegalStateException("ProgrammeModule is not installed (sangam-server wiring)");
        }
        return m;
    }

    public Dialect dialect() {
        return dialect;
    }

    public Transactions tx() {
        return tx;
    }

    public Ports ports() {
        return ports;
    }

    public IndicatorRegistry registry() {
        return registry;
    }

    public PeriodService periods() {
        return periods;
    }

    public ProgrammeService programmes() {
        return programmes;
    }

    public TargetService targets() {
        return targets;
    }

    public MilestoneService milestones() {
        return milestones;
    }

    public RollupService rollup() {
        return rollup;
    }

    public ValidationEngine validation() {
        return validation;
    }

    public ReturnService returns() {
        return returns;
    }

    public ProgressService progress() {
        return progress;
    }

    public VarianceService variance() {
        return variance;
    }

    public DashboardFeed dashboard() {
        return dashboard;
    }
}
