// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources;

import org.sangam.core.layer.Transactions;
import org.sangam.resources.booking.AllotmentService;
import org.sangam.resources.booking.BookingRules;
import org.sangam.resources.booking.BookingService;
import org.sangam.resources.booking.ClaimLedger;
import org.sangam.resources.booking.WaitlistService;
import org.sangam.resources.bpmn.ResourcesRuntime;
import org.sangam.resources.calendar.CalendarService;
import org.sangam.resources.catalogue.ResourceCatalogue;
import org.sangam.resources.queue.QueueService;
import org.sangam.resources.rest.QueueRestAdapter;
import org.sangam.resources.rest.QueueRestPort;
import org.sangam.resources.rest.ResourcesRestAdapter;
import org.sangam.resources.rest.ResourcesRestPort;
import org.sangam.resources.rest.RosterRestAdapter;
import org.sangam.resources.rest.RosterRestPort;
import org.sangam.resources.roster.RosterService;

/**
 * Entry point: wires every service of the module for one database.
 *
 * <pre>{@code
 * ResourcesContext ctx = ResourcesContext.builder(ResourcesDialect.POSTGRESQL)
 *         .decisions(decisionSource)                       // DMN tables (entitlement, priority, tariff, refund, queue, roster)
 *         .guard(ResourcesContext.Guard.of(security.authorization()))
 *         .billing(billingAdapter).notifications(notifier).displayFeed(feed)
 *         .approvals(new OperatonApprovalPort(runtimeService))
 *         .leave(new SecurityLeaveCalendar()).seatLink(new SecurityDutySeatLink(postingService))
 *         .businessCalendars((tenant, id) -> profiles.businessCalendar(tenant, id))
 *         .build();
 * SangamResources res = new SangamResources(ctx);
 * res.registerForBpmn();                                   // delegates find the services
 * ResourcesRestPort rest = res.rest(Transactions.of(engineAdapter));
 * }</pre>
 */
public final class SangamResources {

    private final ResourcesContext ctx;
    private final ResourceCatalogue catalogue;
    private final CalendarService calendars;
    private final ClaimLedger claims;
    private final BookingRules rules;
    private final BookingService bookings;
    private final AllotmentService allotments;
    private final WaitlistService waitlist;
    private final QueueService queue;
    private final RosterService roster;

    public SangamResources(ResourcesContext ctx) {
        this.ctx = ctx;
        this.catalogue = new ResourceCatalogue(ctx);
        this.calendars = new CalendarService(ctx, catalogue);
        this.claims = new ClaimLedger(ctx);
        this.rules = new BookingRules(ctx);
        this.bookings = new BookingService(ctx, catalogue, calendars, claims, rules);
        this.allotments = new AllotmentService(ctx, catalogue, claims, rules);
        this.waitlist = new WaitlistService(ctx, catalogue, bookings, allotments, rules);
        this.waitlist.wire();
        this.queue = new QueueService(ctx);
        this.roster = new RosterService(ctx);
    }

    /** Registers the services for the BPMN delegates of {@code org.sangam.resources.bpmn}. */
    public SangamResources registerForBpmn() {
        ResourcesRuntime.register(ResourcesContext.class, ctx);
        ResourcesRuntime.register(ResourceCatalogue.class, catalogue);
        ResourcesRuntime.register(CalendarService.class, calendars);
        ResourcesRuntime.register(BookingService.class, bookings);
        ResourcesRuntime.register(AllotmentService.class, allotments);
        ResourcesRuntime.register(WaitlistService.class, waitlist);
        ResourcesRuntime.register(QueueService.class, queue);
        ResourcesRuntime.register(RosterService.class, roster);
        return this;
    }

    public ResourcesRestPort rest(Transactions tx) {
        return new ResourcesRestAdapter(tx, ctx, catalogue, calendars, bookings, waitlist, allotments);
    }

    public QueueRestPort queueRest(Transactions tx) {
        return new QueueRestAdapter(tx, queue);
    }

    public RosterRestPort rosterRest(Transactions tx) {
        return new RosterRestAdapter(tx, roster);
    }

    /** Scheduler-ready sweepers under per-(tenant, sweeper) leases; {@code node} must be unique per process. */
    public ResourcesSweepers sweepers(Transactions tx, String node, java.time.Duration leaseTtl, int batch) {
        return new ResourcesSweepers(this, tx, node, leaseTtl, batch);
    }

    public ResourcesContext context() {
        return ctx;
    }

    public ResourceCatalogue catalogue() {
        return catalogue;
    }

    public CalendarService calendars() {
        return calendars;
    }

    public ClaimLedger claims() {
        return claims;
    }

    public BookingService bookings() {
        return bookings;
    }

    public AllotmentService allotments() {
        return allotments;
    }

    public WaitlistService waitlist() {
        return waitlist;
    }

    public QueueService queue() {
        return queue;
    }

    public RosterService roster() {
        return roster;
    }
}
