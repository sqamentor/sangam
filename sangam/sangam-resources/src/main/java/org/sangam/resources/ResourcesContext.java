// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources;

import org.sangam.config.locale.BusinessCalendar;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.resources.dialect.ResourcesDialect;
import org.sangam.resources.port.ResourcesOutboxPublisher;
import org.sangam.resources.port.ResourcesPorts;
import org.sangam.resources.port.Visibility;
import org.sangam.resources.rules.DecisionSource;
import org.sangam.resources.rules.RuleEvaluator;
import org.sangam.resources.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Everything the resources services share: the dialect, the DMN evaluator, the outbound ports, the authorization guard
 * and the business-calendar lookup. Immutable; build it with {@link #builder(ResourcesDialect)}.
 */
public final class ResourcesContext {

    /** Secured resource types registered with the VI.4 policy layer (integration note in the README). */
    public static final String RT_RESOURCE = "RES_RESOURCE";
    public static final String RT_BOOKING = "RES_BOOKING";
    public static final String RT_ALLOTMENT = "RES_ALLOTMENT";
    public static final String RT_QUEUE = "RES_SERVICE_POINT";
    public static final String RT_ROSTER = "RES_ROSTER";

    /*
     * Visibility types: one per table read through VisibleQuery (the security ResourceDescriptor of each names the table,
     * its id column and its attribute columns; README integration note 3). Object checks keep the type of the owning
     * aggregate above.
     */
    /** RES_WAITLIST (entry_id; scope_key, requester_id, type_id, resource_id). */
    public static final String RT_WAITLIST = "RES_WAITLIST";
    /** RES_DRAW (draw_id; scope_key). */
    public static final String RT_DRAW = "RES_DRAW";
    /** RES_EVENT (entity_id; entity_type, actor_id). */
    public static final String RT_EVENT = "RES_EVENT";
    /** RES_COUNTER (counter_id; service_point_id). */
    public static final String RT_COUNTER = "RES_COUNTER";
    /** RES_TOKEN (token_id; service_point_id, counter_id, party_id). */
    public static final String RT_TOKEN = "RES_TOKEN";
    /** RES_CALL_EVENT (token_id; service_point_id, counter_id). */
    public static final String RT_CALL_EVENT = "RES_CALL_EVENT";
    /** RES_SHIFT_TEMPLATE (shift_id). */
    public static final String RT_SHIFT = "RES_SHIFT_TEMPLATE";
    /** RES_ROTATION (rotation_id). */
    public static final String RT_ROTATION = "RES_ROTATION";
    /** RES_ROSTER_MEMBER (person_id; period_id, seat_id). */
    public static final String RT_ROSTER_MEMBER = "RES_ROSTER_MEMBER";
    /** RES_COVERAGE_DEMAND (demand_id; period_id, duty_seat_id). */
    public static final String RT_COVERAGE = "RES_COVERAGE_DEMAND";
    /** RES_ROSTER_ASSIGNMENT (assignment_id; period_id, person_id, seat_id, duty_seat_id). */
    public static final String RT_ASSIGNMENT = "RES_ROSTER_ASSIGNMENT";
    /** RES_SWAP_REQUEST (swap_id; assignment_id, requester_id, counterpart_id). */
    public static final String RT_SWAP = "RES_SWAP_REQUEST";

    /** Permission names of {@code org.sangam.security.policy.Permission}. */
    public static final String P_READ = "READ";
    public static final String P_MODIFY = "MODIFY";
    public static final String P_ADMIN = "ADMIN";
    public static final String P_APPROVE = "APPROVE";
    public static final String P_CANCEL = "CANCEL";
    public static final String P_WORK = "WORK";

    /** Object-level authorization check (wired to {@link SecurityPorts.Authorization#require}). */
    @FunctionalInterface
    public interface Guard {
        void require(Connection c, SecurityIdentity actor, String resourceType, String permission, String id);

        Guard ALLOW_ALL = (c, a, t, p, id) -> { };

        static Guard of(SecurityPorts.Authorization authz) {
            return authz::require;
        }
    }

    private final ResourcesDialect dialect;
    private final RuleEvaluator rules;
    private final Guard guard;
    private final Visibility visibility;
    private final boolean deliverDirectly;
    private final Outbox outbox;
    private final BiFunction<String, String, Optional<BusinessCalendar>> businessCalendars;
    private final ResourcesPorts.BillingPort billing;
    private final ResourcesPorts.NotificationPort notifications;
    private final ResourcesPorts.DisplayFeedPort displayFeed;
    private final ResourcesPorts.ApprovalPort approvals;
    private final ResourcesPorts.LeaveCalendar leave;
    private final ResourcesPorts.DutySeatLink seatLink;
    private final ResourcesPorts.AttendancePort attendance;
    private final ResourcesPorts.MetricsPort metrics;
    private final ResourcesPorts.AuditPort audit;
    private final SecurityPorts.SeatDirectory seats;
    private final Map<String, Object> settings;

    private ResourcesContext(Builder b) {
        this.dialect = Objects.requireNonNull(b.dialect, "dialect");
        this.rules = b.rules != null ? b.rules : new RuleEvaluator(new DecisionSource.InMemory());
        this.guard = b.guard;
        this.visibility = b.visibility;
        this.deliverDirectly = b.deliverDirectly;
        this.outbox = new Outbox(dialect.core());
        this.businessCalendars = b.businessCalendars;
        this.billing = b.billing;
        this.notifications = b.notifications;
        this.displayFeed = b.displayFeed;
        this.approvals = b.approvals;
        this.leave = b.leave;
        this.seatLink = b.seatLink;
        this.attendance = b.attendance;
        this.metrics = b.metrics;
        this.audit = b.audit;
        this.seats = b.seats;
        this.settings = Map.copyOf(b.settings);
    }

    public static Builder builder(ResourcesDialect dialect) {
        return new Builder(dialect);
    }

    public ResourcesDialect dialect() {
        return dialect;
    }

    public RuleEvaluator rules() {
        return rules;
    }

    public Guard guard() {
        return guard;
    }

    /** Row-level visibility predicate for every list, count and get-by-id ({@link org.sangam.resources.support.VisibleQuery}). */
    public Visibility visibility() {
        return visibility;
    }

    /**
     * Sends a notification <b>after commit</b>: appends it to the core outbox in the caller's transaction; the relay
     * delivers it through {@link ResourcesOutboxPublisher}. Root = the recipient, so a person's notices stay in order.
     */
    public void notify(Connection c, String tenantId, String recipientId, String templateKey, Map<String, Object> data) {
        if (recipientId == null) {
            return;
        }
        if (deliverDirectly) {
            notifications.notify(c, tenantId, recipientId, templateKey, data);
            return;
        }
        outbox.append(c, tenantId, "res-notify:" + recipientId, ResourcesOutboxPublisher.EVT_NOTIFICATION,
                Sql.toJson(ResourcesOutboxPublisher.notificationPayload(recipientId, templateKey, data)),
                List.of(Outbox.Destination.unordered(ResourcesOutboxPublisher.DEST_NOTIFICATION)));
    }

    /**
     * Publishes a display-feed event <b>after commit</b> through the outbox. Root = the service point and the destination
     * is ordered, so a board sees calls in commit order.
     */
    public void feed(Connection c, String tenantId, ResourcesPorts.FeedEvent event) {
        if (deliverDirectly) {
            displayFeed.publish(tenantId, event);
            return;
        }
        outbox.append(c, tenantId, "res-feed:" + event.servicePointId(), ResourcesOutboxPublisher.EVT_FEED,
                Sql.toJson(ResourcesOutboxPublisher.feedPayload(event)),
                List.of(Outbox.Destination.ordered(ResourcesOutboxPublisher.DEST_DISPLAY_FEED)));
    }

    /** The publisher the relay uses for this module's destinations (delivers to the configured ports). */
    public ResourcesOutboxPublisher outboxPublisher(org.sangam.core.layer.Transactions tx) {
        return new ResourcesOutboxPublisher(notifications, displayFeed, tx);
    }

    public Optional<BusinessCalendar> businessCalendar(String tenantId, String calendarId) {
        return calendarId == null ? Optional.empty() : businessCalendars.apply(tenantId, calendarId);
    }

    public ResourcesPorts.BillingPort billing() {
        return billing;
    }

    public ResourcesPorts.NotificationPort notifications() {
        return notifications;
    }

    public ResourcesPorts.DisplayFeedPort displayFeed() {
        return displayFeed;
    }

    public ResourcesPorts.ApprovalPort approvals() {
        return approvals;
    }

    public ResourcesPorts.LeaveCalendar leave() {
        return leave;
    }

    public ResourcesPorts.DutySeatLink seatLink() {
        return seatLink;
    }

    public ResourcesPorts.AttendancePort attendance() {
        return attendance;
    }

    public ResourcesPorts.MetricsPort metrics() {
        return metrics;
    }

    public ResourcesPorts.AuditPort audit() {
        return audit;
    }

    /** Optional seat directory (sangam-security {@code PostingService}); null when not wired. */
    public SecurityPorts.SeatDirectory seats() {
        return seats;
    }

    /** Module-wide defaults (profile data, e.g. {@code holdMinutes}, {@code maxClaimRows}). */
    public Map<String, Object> settings() {
        return settings;
    }

    public int setting(String name, int def) {
        return Sql.intOr(settings.get(name), def);
    }

    public Instant now(Connection c) {
        return Sql.dbNow(c, dialect);
    }

    /**
     * Appends one row to {@code RES_EVENT} (the module's own history: who, what, why, under which authority and which
     * decision table) and forwards it to the external audit port.
     */
    public void event(Connection c, SecurityIdentity actor, String entityType, String entityId, String eventType,
                      String reason, String authorityRef, Map<String, ?> payload) {
        Map<String, Object> p = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        Jdbc.update(c, "INSERT INTO RES_EVENT(tenant_id, entity_type, entity_id, event_type, actor_id, reason,"
                        + " authority_ref, payload_json, at_time) VALUES (?,?,?,?,?,?,?,?," + dialect.now() + ")",
                actor.tenantId(), entityType, entityId, eventType, actor.personId(), reason, authorityRef, Sql.toJson(p));
        p.put("entityType", entityType);
        if (reason != null) {
            p.put("reason", reason);
        }
        if (authorityRef != null) {
            p.put("authorityRef", authorityRef);
        }
        audit.record(c, actor, "RES_" + eventType, entityId, p);
    }

    public static final class Builder {
        private final ResourcesDialect dialect;
        private RuleEvaluator rules;
        private Guard guard = Guard.ALLOW_ALL;
        private Visibility visibility = Visibility.TENANT_ONLY;
        private boolean deliverDirectly;
        private BiFunction<String, String, Optional<BusinessCalendar>> businessCalendars = (t, id) -> Optional.empty();
        private ResourcesPorts.BillingPort billing = ResourcesPorts.Defaults.FREE;
        private ResourcesPorts.NotificationPort notifications = ResourcesPorts.Defaults.SILENT;
        private ResourcesPorts.DisplayFeedPort displayFeed = ResourcesPorts.Defaults.NO_FEED;
        private ResourcesPorts.ApprovalPort approvals = ResourcesPorts.Defaults.NO_APPROVAL;
        private ResourcesPorts.LeaveCalendar leave = ResourcesPorts.Defaults.NO_LEAVE;
        private ResourcesPorts.DutySeatLink seatLink = ResourcesPorts.Defaults.NO_SEAT_LINK;
        private ResourcesPorts.AttendancePort attendance = ResourcesPorts.Defaults.NO_ATTENDANCE;
        private ResourcesPorts.MetricsPort metrics = ResourcesPorts.Defaults.NO_METRICS;
        private ResourcesPorts.AuditPort audit = ResourcesPorts.Defaults.NO_AUDIT;
        private SecurityPorts.SeatDirectory seats;
        private final Map<String, Object> settings = new LinkedHashMap<>();

        private Builder(ResourcesDialect dialect) {
            this.dialect = dialect;
        }

        public Builder rules(RuleEvaluator v) { this.rules = v; return this; }
        public Builder decisions(DecisionSource v) { this.rules = new RuleEvaluator(v); return this; }
        public Builder guard(Guard v) { this.guard = v; return this; }
        public Builder visibility(Visibility v) { this.visibility = v; return this; }
        /** Wires both the object guard and the list predicate to the same VI.4 authorization (recommended). */
        public Builder authorization(SecurityPorts.Authorization authz) {
            this.guard = Guard.of(authz);
            this.visibility = Visibility.of(authz);
            return this;
        }
        /** Development only (no OUTBOX tables): call the notification and feed ports inside the transaction. */
        public Builder deliverDirectly(boolean v) { this.deliverDirectly = v; return this; }
        public Builder businessCalendars(BiFunction<String, String, Optional<BusinessCalendar>> v) { this.businessCalendars = v; return this; }
        public Builder billing(ResourcesPorts.BillingPort v) { this.billing = v; return this; }
        public Builder notifications(ResourcesPorts.NotificationPort v) { this.notifications = v; return this; }
        public Builder displayFeed(ResourcesPorts.DisplayFeedPort v) { this.displayFeed = v; return this; }
        public Builder approvals(ResourcesPorts.ApprovalPort v) { this.approvals = v; return this; }
        public Builder leave(ResourcesPorts.LeaveCalendar v) { this.leave = v; return this; }
        public Builder seatLink(ResourcesPorts.DutySeatLink v) { this.seatLink = v; return this; }
        public Builder attendance(ResourcesPorts.AttendancePort v) { this.attendance = v; return this; }
        public Builder metrics(ResourcesPorts.MetricsPort v) { this.metrics = v; return this; }
        public Builder audit(ResourcesPorts.AuditPort v) { this.audit = v; return this; }
        public Builder seats(SecurityPorts.SeatDirectory v) { this.seats = v; return this; }
        public Builder settings(Map<String, ?> v) { if (v != null) { this.settings.putAll(v); } return this; }

        public ResourcesContext build() {
            return new ResourcesContext(this);
        }
    }
}
