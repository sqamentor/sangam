// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sangam.casefile.ports.CaseFilePorts;
import org.sangam.core.engine.WorkCalendar;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.ports.history.HistoryQueryService;
import org.sangam.ports.support.Paging;
import org.sangam.registry.RegistryModule;
import org.sangam.registry.RegistryContext;
import org.sangam.resources.SangamResources;
import org.sangam.resources.booking.BookingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Port bindings of the case module (sangam-case README "Integration notes" §3) onto the other modules: business
 * calendars (FC-23 {@code WorkCalendar} beans by name), escalation seats (sangam-security {@code SeatDirectory}),
 * engine history for the timeline (ports {@code HistoryQueryService}, read model), venue booking (sangam-resources
 * {@code BookingService}, N01) and the party registry (sangam-registry {@code PartyService}, N14).
 */
public final class CaseModuleDirectories {

    private static final Logger LOG = LoggerFactory.getLogger(CaseModuleDirectories.class);

    private CaseModuleDirectories() {
    }

    /** Calendars by bean name; unknown or null names fall back to {@link WorkCalendar#PLAIN}. */
    public static CaseFilePorts.Calendars calendars(Map<String, WorkCalendar> beans) {
        Map<String, WorkCalendar> copy = Map.copyOf(beans);
        return name -> name == null ? WorkCalendar.PLAIN : copy.getOrDefault(name, WorkCalendar.PLAIN);
    }

    /** The superior / escalation seat of a seat at an instant (AJ-04 step 6). */
    public static CaseFilePorts.EscalationSeats escalation(SecurityPorts.SeatDirectory seats) {
        return seats::escalationSeat;
    }

    /**
     * Engine history for the case timeline from the ports read model. The case module has already authorised the read
     * of the case file (VI.4 on {@code CASE_FILE}); the engine rows are limited to the case's own instances and the
     * tenant, so the predicate here is tenant-only.
     */
    public static CaseFilePorts.ProcessHistorySource history(HistoryQueryService queries) {
        return (tenant, instanceIds, limit) -> {
            List<CaseFilePorts.HistoryEntry> out = new ArrayList<>();
            int max = Math.max(1, Math.min(limit, Paging.MAX_PAGE));
            for (String instanceId : instanceIds) {
                if (out.size() >= max) {
                    break;
                }
                var filter = new HistoryQueryService.Filter(tenant, null, instanceId, null, null, null, null, null, null,
                        null, null, null);
                var page = queries.activityInstances(filter, HistoryQueryService.AuthPredicate.NONE,
                        new Paging.PageRequest(0, max - out.size()));
                for (HistoryQueryService.ActivityRow r : page.items()) {
                    out.add(new CaseFilePorts.HistoryEntry(r.id(), r.processInstanceId(), r.activityId(), r.activityId(),
                            r.activityType(), r.startTime(), r.endTime(), null));
                }
            }
            return out;
        };
    }

    /**
     * Venue booking on the resources module: a hold confirmed at once for the meeting slot, as the tenant's system
     * principal on behalf of the case module; {@code venue} is the resource id. Unavailable → empty (the meeting module
     * then reports the clash).
     */
    public static CaseFilePorts.VenueBooking venues(SangamResources resources) {
        return new CaseFilePorts.VenueBooking() {
            @Override
            public Optional<String> book(Connection c, String tenantId, String venue, Instant from, Instant to,
                                         String purposeRef) {
                SecurityIdentity system = SecurityIdentity.system(tenantId, "sangam-case");
                var request = new BookingModel.HoldRequest(venue, from, to, 1, system.personId(), null, null, purposeRef,
                        null, null, Map.of("source", "sangam-case"), purposeRef, false, null, null, false, null);
                try {
                    BookingModel.HoldResult held = resources.bookings().hold(c, system, request);
                    if (!held.held()) {
                        return Optional.empty();
                    }
                    var booking = resources.bookings().confirm(c, system, held.booking().bookingId());
                    return Optional.of(booking.bookingId());
                } catch (RuntimeException e) {
                    LOG.info("venue {} not booked for {}: {}", venue, purposeRef, e.getMessage());
                    return Optional.empty();
                }
            }

            @Override
            public void release(Connection c, String tenantId, String bookingRef) {
                if (bookingRef == null || bookingRef.startsWith("unmanaged:")) {
                    return;
                }
                SecurityIdentity system = SecurityIdentity.system(tenantId, "sangam-case");
                resources.bookings().cancel(c, system, bookingRef, "meeting cancelled or moved", system.personId());
            }
        };
    }

    /** External party references resolved through the party registry (N14); unknown references → empty. */
    public static CaseFilePorts.PartyDirectory parties(RegistryModule registry) {
        return (c, tenantId, externalRef) -> {
            try {
                return Optional.of(registry.parties().get(c, RegistryContext.of(tenantId, "system:sangam-case"), externalRef)
                        .toMap());
            } catch (RuntimeException e) {
                LOG.debug("party {} not resolved in the registry: {}", externalRef, e.toString());
                return Optional.empty();
            }
        };
    }

    /**
     * Object-level authorization when sangam-security is switched off (development): every caller that passed the REST
     * endpoint permission ({@code case.*} / {@code programme.*}) is allowed, within its tenant. Production binds
     * sangam-security's {@code AuthorizationService} (VI.4 deny-wins).
     */
    public static SecurityPorts.Authorization tenantOnly() {
        return new SecurityPorts.Authorization() {
            @Override
            public SqlPredicate visible(Connection c, SecurityIdentity identity, String resourceType, String permission,
                                        String alias) {
                return new SqlPredicate("1=1", List.of());
            }

            @Override
            public boolean allow(Connection c, SecurityIdentity identity, String resourceType, String permission,
                                 String resourceId) {
                return true;
            }

            @Override
            public void require(Connection c, SecurityIdentity identity, String resourceType, String permission,
                                String resourceId) {
            }

            @Override
            public long policyVersion(Connection c, String tenantId) {
                return 0;
            }
        };
    }
}
