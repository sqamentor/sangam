// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.ports;

import org.sangam.core.engine.WorkCalendar;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Outbound ports of the case module. The module never duplicates a capability another module owns: entity links and
 * documents come from sangam-flowable-ports (bound directly), seats from WS-SECURITY ({@code SecurityPorts}), messages
 * from WS-INTEGRATION's connectors through {@link Notifier}, venue booking from the resource module (N01), external
 * party registries (N14) through {@link PartyDirectory}. Each port has a default so the module runs air-gapped
 * (ARCHITECTURE §1.9).
 */
public final class CaseFilePorts {

    private CaseFilePorts() {
    }

    /** A message to a person or seat (meeting notice, deadline warning, escalation, reply acknowledgement). */
    public record Message(String tenantId, String recipientRef, String kind, String subject, String body,
                          Map<String, Object> attributes) {
    }

    /**
     * Sends inside the caller's transaction (outbox write, exactly once through the relay). Returns a delivery reference.
     * The default writes nothing and returns {@code null}; the event is still on the module's own outbox destination.
     */
    @FunctionalInterface
    public interface Notifier {
        String send(Connection c, Message message);

        Notifier NONE = (c, m) -> null;
    }

    /** Business calendars by name (FC-23); {@code null} or unknown names fall back to {@link WorkCalendar#PLAIN}. */
    @FunctionalInterface
    public interface Calendars {
        WorkCalendar calendar(String name);

        Calendars PLAIN = n -> WorkCalendar.PLAIN;
    }

    /** Venue / room booking (N01). */
    public interface VenueBooking {
        /** @return a booking reference, or empty when the venue is not available. */
        Optional<String> book(Connection c, String tenantId, String venue, Instant from, Instant to, String purposeRef);

        void release(Connection c, String tenantId, String bookingRef);

        VenueBooking NONE = new VenueBooking() {
            @Override
            public Optional<String> book(Connection c, String tenantId, String venue, Instant from, Instant to, String purposeRef) {
                return Optional.of("unmanaged:" + venue);
            }

            @Override
            public void release(Connection c, String tenantId, String bookingRef) {
            }
        };
    }

    /** An external party registry (N14) used to resolve {@code externalRef} and to search candidates. */
    public interface PartyDirectory {
        Optional<Map<String, Object>> resolve(Connection c, String tenantId, String externalRef);

        PartyDirectory NONE = (c, t, r) -> Optional.empty();
    }

    /** One entry of the process / case history merged into a case timeline. */
    public record HistoryEntry(String id, String instanceId, String activityId, String activityName, String activityType,
                               Instant startedAt, Instant endedAt, String assignee) {
    }

    /**
     * Process and case history for the timeline. The integrator binds it to sangam-flowable-ports
     * {@code HistoryQueryService.activityInstances} (read model, FC-25) with the VI.4 predicate.
     */
    @FunctionalInterface
    public interface ProcessHistorySource {
        List<HistoryEntry> history(String tenantId, List<String> instanceIds, int limit);

        ProcessHistorySource NONE = (t, ids, l) -> List.of();
    }

    /** Starts a process for an action item, reference or meeting (one instance per item, GP-09). */
    @FunctionalInterface
    public interface ProcessStarter {
        /** @return the process instance id, or {@code null} when no process is configured. */
        String start(Connection c, String tenantId, String processKey, String businessKey, Map<String, Object> variables);

        ProcessStarter NONE = (c, t, k, b, v) -> null;
    }

    /** Escalation target for a vacant or overdue seat (AJ-04 step 6). Bound to {@code SecurityPorts.SeatDirectory}. */
    @FunctionalInterface
    public interface EscalationSeats {
        Optional<String> superior(Connection c, String tenantId, String seatId, Instant at);

        EscalationSeats NONE = (c, t, s, a) -> Optional.empty();
    }
}
