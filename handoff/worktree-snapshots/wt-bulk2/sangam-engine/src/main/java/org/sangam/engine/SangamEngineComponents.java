// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import java.time.ZoneId;

import org.sangam.core.api.ExpiryPolicy;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.EngineMetrics;
import org.sangam.core.engine.IncidentSink;
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

    public SangamEngineComponents(SangamEngineSettings settings, EngineDialect dialect) {
        this.settings = settings;
        this.dialect = dialect;
        this.metrics = settings.getMetrics();
        this.rootLock = new RootLock(dialect, settings.getLockTimeout(), metrics);
        this.leases = new SangamLeaseTable(dialect, settings.getLockTimeout());
        this.exclusiveLeases = new ExclusiveLeaseTable(dialect, settings.getLockTimeout());
        this.timerPolicies = new TimerPolicies(settings);
        // Offset-less starts are local time in the JVM zone Operaton cached at class load (FLOWABLE-INTO-OPERATON D-02).
        this.timerGuard = new TimerGuard(dialect, settings.getLockTimeout(), ZoneId.systemDefault(), settings.getTimerZone());
        this.retrier = new CommandRetrier(settings.getRetryMaxAttempts(), settings.getRetryBase(), settings.getRetryCap(),
                metrics);
        this.auditSink = settings.getAuditSink() != null ? settings.getAuditSink()
                : new CoreAuditChainSink(dialect.coreDialect());
        this.incidentSink = settings.getIncidentSink() != null ? settings.getIncidentSink() : new JdbcIncidentSink(clock);
        this.expiryPolicy = settings.getExpiryPolicy() != null ? settings.getExpiryPolicy()
                : new CapabilityClassExpiryPolicy(dialect.coreDialect(), metrics);
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
