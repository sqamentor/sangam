// SPDX-License-Identifier: Apache-2.0
package org.sangam.field;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.field.dispatch.DispatchService;
import org.sangam.field.evidence.EvidenceBlobStore;
import org.sangam.field.evidence.EvidenceService;
import org.sangam.field.ports.FieldPorts;
import org.sangam.field.ports.FieldPorts.DecisionPort;
import org.sangam.field.ports.FieldPorts.FieldEvents;
import org.sangam.field.ports.FieldPorts.GeoPort;
import org.sangam.field.rest.FieldRestService;
import org.sangam.field.schedule.AssignmentService;
import org.sangam.field.schedule.RandomSelector;
import org.sangam.field.schedule.RotationRule;
import org.sangam.field.schedule.ScheduleService;
import org.sangam.field.scoring.ScoringService;
import org.sangam.field.sync.ConflictResolver;
import org.sangam.field.sync.DeviceRegistry;
import org.sangam.field.sync.QueuePolicy;
import org.sangam.field.sync.SyncService;
import org.sangam.field.template.TemplateRegistry;
import org.sangam.field.visit.VisitService;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Wiring of the whole field module from its ports (one object graph; the integrator exposes the services as beans).
 * Every port is optional: unset ports fall back to the air-gapped defaults ({@link FieldPorts}).
 */
public final class FieldModule {

    public record Ports(GeoPort geo, DecisionPort decisions, FieldEvents events, EvidenceBlobStore blobs, FieldAudit audit,
                        QueuePolicy defaultQueuePolicy, FieldConfig config, Clock clock) {
    }

    public final TemplateRegistry templates;
    public final AssignmentService assignments;
    public final RandomSelector selector;
    public final RotationRule rotation;
    public final ScheduleService schedules;
    public final ScoringService scoring;
    public final EvidenceService evidence;
    public final ConflictResolver conflicts;
    public final DeviceRegistry devices;
    public final VisitService visits;
    public final DispatchService dispatch;
    public final SyncService sync;
    public final FieldRestService rest;

    public FieldModule(Transactions tx, Dialect dialect, Ports ports) {
        GeoPort geo = ports.geo() == null ? new FieldPorts.PureGeoPort() : ports.geo();
        DecisionPort decisions = ports.decisions() == null ? FieldPorts.NO_DECISIONS : ports.decisions();
        FieldEvents events = ports.events() == null ? FieldPorts.NO_EVENTS : ports.events();
        EvidenceBlobStore blobs = ports.blobs() == null
                ? new EvidenceBlobStore.FileSystem(Path.of(System.getProperty("java.io.tmpdir"), "sangam-field-evidence")) : ports.blobs();
        FieldAudit audit = ports.audit() == null ? FieldAudit.chain(dialect) : ports.audit();
        FieldConfig config = ports.config() == null ? FieldConfig.defaults() : ports.config();
        Clock clock = ports.clock() == null ? Clock.systemUTC() : ports.clock();

        templates = new TemplateRegistry(tx, dialect, audit, clock);
        assignments = new AssignmentService(tx, dialect, templates, audit, events, clock);
        selector = new RandomSelector(tx, decisions, audit, clock);
        rotation = new RotationRule(assignments, decisions);
        schedules = new ScheduleService(tx, assignments, selector, rotation, clock);
        scoring = new ScoringService(decisions);
        evidence = new EvidenceService(tx, blobs, assignments, templates, audit, config, clock);
        conflicts = new ConflictResolver(tx, audit, clock);
        devices = new DeviceRegistry(tx, dialect, audit, ports.defaultQueuePolicy(), clock);
        visits = new VisitService(tx, assignments, templates, evidence, scoring, conflicts, geo, audit, events, config, clock);
        dispatch = new DispatchService(tx, dialect, config, audit, events, clock);
        sync = new SyncService(tx, dialect, devices, visits, assignments, templates, evidence, dispatch, audit, config, clock);
        rest = new FieldRestService(this, tx, dialect, config, clock);
    }
}
