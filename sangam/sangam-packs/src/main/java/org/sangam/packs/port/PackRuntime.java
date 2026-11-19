// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.time.Clock;
import java.util.Objects;

import org.sangam.packs.calendar.WorkingDayCalendar;
import org.sangam.packs.sim.DemoSandbox;
import org.sangam.packs.sim.InMemoryBatchLedger;
import org.sangam.packs.sim.InMemoryLedgerStore;
import org.sangam.packs.sim.InMemorySeatDirectory;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;

/**
 * The ports pack delegates use. Operaton instantiates {@code operaton:class} delegates reflectively, so they look the
 * ports up here instead of receiving them by injection. {@code sangam-server} installs the production adapters once
 * at start-up ({@link #install}); until then every port has a simulator or in-memory default, so the packs run in the
 * h2 developer profile and in an air-gapped demo without any external system (ADR-D-13, R56).
 */
public final class PackRuntime {

    private static volatile Ports ports = defaults();

    private PackRuntime() {
    }

    /**
     * The full set of ports. {@code signatures} answers whether a verified DSC signature exists for a task (decisions
     * 140 and 165); its default {@link SignatureVerifications#DENY} keeps a step that needs one closed.
     * {@code production} (decision 292) says the installation runs in production mode: then every rounding policy row
     * and versioned rules row whose permitted use is not {@code PRODUCTION} is refused
     * ({@code org.sangam.packs.core.RowEvidence}). It is false (non-production) unless the server sets it from its
     * fail-closed production setting (decision 143). {@code slotBindings} (decision 385 (2)) answers whether the
     * server's document route bound a document into a DSC slot; its default {@link DocumentSlotBindings#NONE} binds
     * nothing, so a DSC step with a slot is refused (fail closed).
     */
    public record Ports(ConnectorGateway gateway, SeatDirectory seats, BusinessCalendar calendar, PackAuditSink audit,
                        BatchLedger batches, LedgerStore ledgers, Clock clock, SignatureVerifications signatures,
                        boolean production, DocumentSlotBindings slotBindings) {
        public Ports {
            Objects.requireNonNull(gateway, "gateway");
            Objects.requireNonNull(seats, "seats");
            Objects.requireNonNull(calendar, "calendar");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(batches, "batches");
            Objects.requireNonNull(ledgers, "ledgers");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(signatures, "signatures");
            Objects.requireNonNull(slotBindings, "slotBindings");
        }

        /** The ports without a slot binding adapter (decision 385 (2) default: nothing is bound). */
        public Ports(ConnectorGateway gateway, SeatDirectory seats, BusinessCalendar calendar, PackAuditSink audit,
                     BatchLedger batches, LedgerStore ledgers, Clock clock, SignatureVerifications signatures,
                     boolean production) {
            this(gateway, seats, calendar, audit, batches, ledgers, clock, signatures, production,
                    DocumentSlotBindings.NONE);
        }

        /** The ports with a DSC verification adapter, non-production (decision 292 default). */
        public Ports(ConnectorGateway gateway, SeatDirectory seats, BusinessCalendar calendar, PackAuditSink audit,
                     BatchLedger batches, LedgerStore ledgers, Clock clock, SignatureVerifications signatures) {
            this(gateway, seats, calendar, audit, batches, ledgers, clock, signatures, false, DocumentSlotBindings.NONE);
        }

        /** The ports without a DSC verification adapter: every DSC check is refused; non-production. */
        public Ports(ConnectorGateway gateway, SeatDirectory seats, BusinessCalendar calendar, PackAuditSink audit,
                     BatchLedger batches, LedgerStore ledgers, Clock clock) {
            this(gateway, seats, calendar, audit, batches, ledgers, clock, SignatureVerifications.DENY, false,
                    DocumentSlotBindings.NONE);
        }

        public Ports withGateway(ConnectorGateway g) {
            return new Ports(g, seats, calendar, audit, batches, ledgers, clock, signatures, production, slotBindings);
        }

        public Ports withSeats(SeatDirectory s) {
            return new Ports(gateway, s, calendar, audit, batches, ledgers, clock, signatures, production, slotBindings);
        }

        public Ports withCalendar(BusinessCalendar c) {
            return new Ports(gateway, seats, c, audit, batches, ledgers, clock, signatures, production, slotBindings);
        }

        public Ports withAudit(PackAuditSink a) {
            return new Ports(gateway, seats, calendar, a, batches, ledgers, clock, signatures, production, slotBindings);
        }

        public Ports withBatches(BatchLedger b) {
            return new Ports(gateway, seats, calendar, audit, b, ledgers, clock, signatures, production, slotBindings);
        }

        public Ports withLedgers(LedgerStore l) {
            return new Ports(gateway, seats, calendar, audit, batches, l, clock, signatures, production, slotBindings);
        }

        public Ports withClock(Clock c) {
            return new Ports(gateway, seats, calendar, audit, batches, ledgers, c, signatures, production, slotBindings);
        }

        public Ports withSignatures(SignatureVerifications v) {
            return new Ports(gateway, seats, calendar, audit, batches, ledgers, clock, v, production, slotBindings);
        }

        /** Decision 292: production mode on or off; every other port is kept. */
        public Ports withProduction(boolean p) {
            return new Ports(gateway, seats, calendar, audit, batches, ledgers, clock, signatures, p, slotBindings);
        }

        /** Decision 385 (2): the document route's slot bindings; every other port is kept. */
        public Ports withSlotBindings(DocumentSlotBindings b) {
            return new Ports(gateway, seats, calendar, audit, batches, ledgers, clock, signatures, production, b);
        }
    }

    /** Simulator and in-memory defaults. */
    public static Ports defaults() {
        return new Ports(new SimulatorConnectorGateway(), new InMemorySeatDirectory(), new WorkingDayCalendar(),
                new LoggingAuditSink(), new InMemoryBatchLedger(), new InMemoryLedgerStore(), Clock.systemUTC());
    }

    /** Installs the production adapters (called once by sangam-server wiring). */
    public static void install(Ports p) {
        ports = Objects.requireNonNull(p);
    }

    /** Restores the defaults (developer profile, tests). */
    public static void reset() {
        ports = defaults();
    }

    public static Ports ports() {
        return ports;
    }

    /** The installed gateway; with demo sandbox tenants set, their payment rails go to the sandbox simulator. */
    public static ConnectorGateway gateway() {
        return DemoSandbox.routing(ports.gateway());
    }

    public static SeatDirectory seats() {
        return ports.seats();
    }

    public static BusinessCalendar calendar() {
        return ports.calendar();
    }

    public static PackAuditSink audit() {
        return ports.audit();
    }

    public static BatchLedger batches() {
        return ports.batches();
    }

    public static LedgerStore ledgers() {
        return ports.ledgers();
    }

    public static Clock clock() {
        return ports.clock();
    }

    public static SignatureVerifications signatures() {
        return ports.signatures();
    }

    /** Decision 385 (2): whether the document route bound a slot's document (default: nothing is bound). */
    public static DocumentSlotBindings slotBindings() {
        return ports.slotBindings();
    }

    /** Decision 292: true when the installation runs in production mode (default false, non-production). */
    public static boolean production() {
        return ports.production();
    }
}
