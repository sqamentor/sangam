// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.time.Clock;
import java.util.Objects;

import org.sangam.packs.sim.InMemoryBatchLedger;
import org.sangam.packs.sim.InMemoryLedgerStore;
import org.sangam.packs.sim.InMemorySeatDirectory;
import org.sangam.packs.sim.LoggingAuditSink;
import org.sangam.packs.sim.SimulatorConnectorGateway;
import org.sangam.packs.sim.WorkingDayCalendar;

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

    /** The full set of ports. */
    public record Ports(ConnectorGateway gateway, SeatDirectory seats, BusinessCalendar calendar, PackAuditSink audit,
                        BatchLedger batches, LedgerStore ledgers, Clock clock) {
        public Ports {
            Objects.requireNonNull(gateway, "gateway");
            Objects.requireNonNull(seats, "seats");
            Objects.requireNonNull(calendar, "calendar");
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(batches, "batches");
            Objects.requireNonNull(ledgers, "ledgers");
            Objects.requireNonNull(clock, "clock");
        }

        public Ports withGateway(ConnectorGateway g) {
            return new Ports(g, seats, calendar, audit, batches, ledgers, clock);
        }

        public Ports withSeats(SeatDirectory s) {
            return new Ports(gateway, s, calendar, audit, batches, ledgers, clock);
        }

        public Ports withCalendar(BusinessCalendar c) {
            return new Ports(gateway, seats, c, audit, batches, ledgers, clock);
        }

        public Ports withAudit(PackAuditSink a) {
            return new Ports(gateway, seats, calendar, a, batches, ledgers, clock);
        }

        public Ports withBatches(BatchLedger b) {
            return new Ports(gateway, seats, calendar, audit, b, ledgers, clock);
        }

        public Ports withLedgers(LedgerStore l) {
            return new Ports(gateway, seats, calendar, audit, batches, l, clock);
        }

        public Ports withClock(Clock c) {
            return new Ports(gateway, seats, calendar, audit, batches, ledgers, c);
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

    public static ConnectorGateway gateway() {
        return ports.gateway();
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
}
