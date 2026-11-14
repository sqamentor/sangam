// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.EngineMetrics;
import org.sangam.core.engine.IncidentSink;
import org.sangam.core.time.EngineTimeZone;
import org.sangam.engine.clock.OperatonClock;
import org.sangam.engine.db.EngineDialect;
import org.sangam.engine.history.CoreAuditChainSink;
import org.sangam.engine.incident.JdbcIncidentSink;
import org.sangam.engine.lease.CapabilityClassExpiryPolicy;
import org.sangam.engine.lease.ExclusiveLeaseTable;
import org.sangam.engine.lease.SangamLeaseTable;
import org.sangam.engine.lock.RootLock;
import org.sangam.engine.timer.TimerGuard;
import org.sangam.engine.timer.TimerPolicies;
import org.sangam.engine.tx.CommandRetrier;

/**
 * The Sangam engine parts shared by the plugin (engine-side hooks) and the adapter (product-side calls), built once
 * per engine for one database dialect. Ports left null in the settings get their defaults here.
 */
public final class SangamEngineComponents {

    private final SangamEngineSettings settings;
    private final EngineDialect dialect;
    private final OperatonClock clock = OperatonClock.INSTANCE;
    private final EngineMetrics metrics;
    private final RootLock rootLock;
    private final SangamLeaseTable leases;
    private final ExclusiveLeaseTable exclusiveLeases;
    private final TimerPolicies timerPolicies;
    private final TimerGuard timerGuard;
    private final CommandRetrier retrier;
    private final AuditSink auditSink;
    private final IncidentSink incidentSink;
    private final ExpiryPolicy expiryPolicy;
    private final org.sangam.engine.replay.CapturePoints capture;
    private final org.sangam.engine.tenant.WorkTenants workTenants;
    private volatile org.sangam.engine.job.PartitionResize partitionResize;

    public SangamEngineComponents(SangamEngineSettings settings, EngineDialect dialect) {
        // SL-53: every way of wiring a Sangam engine passes here; it never runs outside a UTC JVM
        EngineTimeZone.requireUtcJvm();
        // decision 144 (owner 197): no engine starts without a valid system audit scope (refused here, before anything)
        this.workTenants = new org.sangam.engine.tenant.WorkTenants(settings.getSystemAuditScope());
        this.settings = settings;
        this.dialect = dialect;
        this.metrics = settings.getMetrics();
        this.rootLock = new RootLock(dialect, settings.getLockTimeout(), metrics);
        this.leases = new SangamLeaseTable(dialect, settings.getLockTimeout());
        this.exclusiveLeases = new ExclusiveLeaseTable(dialect, settings.getLockTimeout());
        this.timerPolicies = new TimerPolicies(settings);
        // SL-53: offset-less date-times are engine-zone (UTC) wall time, as Operaton writes them in the UTC JVM
        // (FLOWABLE-INTO-OPERATON D-02); calendar arithmetic and the guard's wall clock use the configured timer zone.
        this.timerGuard = new TimerGuard(dialect, settings.getLockTimeout(), settings.getTimerZone());
        this.retrier = new CommandRetrier(settings.getRetryMaxAttempts(), settings.getRetryBase(), settings.getRetryCap(),
                metrics);
        this.auditSink = settings.getAuditSink() != null ? settings.getAuditSink()
                : new CoreAuditChainSink(dialect.coreDialect());
        this.incidentSink = settings.getIncidentSink() != null ? settings.getIncidentSink() : new JdbcIncidentSink(clock);
        this.expiryPolicy = settings.getExpiryPolicy() != null ? settings.getExpiryPolicy()
                : new CapabilityClassExpiryPolicy(dialect.coreDialect(), metrics);
        this.capture = org.sangam.engine.replay.CapturePoints.of(settings.getCaptureListener(), clock::now);
    }

    /** Decision 151: the replay capture points; {@code CapturePoints.NONE} without a capture listener. */
    public org.sangam.engine.replay.CapturePoints capture() { return capture; }

    /** Decision 144: tenant resolution of untenanted engine work and the validated system audit scope. */
    public org.sangam.engine.tenant.WorkTenants workTenants() { return workTenants; }

    /**
     * Decision 146: the engine's one partition resize service, built after the engine build on every node (partitioning
     * on or off; with no executor map its calls answer {@code NOT_INITIALISED}); {@code null} before the build.
     */
    public org.sangam.engine.job.PartitionResize partitionResize() { return partitionResize; }

    /** Set once by the plugin after the engine build. */
    public void partitionResize(org.sangam.engine.job.PartitionResize resize) {
        if (this.partitionResize != null) {
            throw new IllegalStateException("decision 146: the partition resize service is set once per engine");
        }
        this.partitionResize = resize;
    }

    public SangamEngineSettings settings() { return settings; }
    public EngineDialect dialect() { return dialect; }
    public OperatonClock clock() { return clock; }
    public EngineMetrics metrics() { return metrics; }
    public RootLock rootLock() { return rootLock; }
    public SangamLeaseTable leases() { return leases; }
    public ExclusiveLeaseTable exclusiveLeases() { return exclusiveLeases; }
    public TimerPolicies timerPolicies() { return timerPolicies; }
    public TimerGuard timerGuard() { return timerGuard; }
    public CommandRetrier retrier() { return retrier; }
    public AuditSink auditSink() { return auditSink; }
    public IncidentSink incidentSink() { return incidentSink; }
    public ExpiryPolicy expiryPolicy() { return expiryPolicy; }
}
