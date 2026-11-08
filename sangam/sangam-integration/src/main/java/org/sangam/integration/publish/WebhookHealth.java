// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Health of outbound webhook destinations (SL-39), in {@code INTEGRATION_WEBHOOK_STATE} so every relay node sees the
 * same state.
 *
 * <ul>
 *   <li>Every failed attempt (transport failure, 429, 5xx) increments {@code consecutive_failures}; a 2xx resets it.</li>
 *   <li>At {@code disableAfter} consecutive failures, or at once on {@code 410 Gone}, the destination is
 *       {@code DISABLED} and {@value #DISABLED_EVENT} is appended to the audit chain {@code webhook:<destination>}.
 *       The check-and-set runs on the row lock, so exactly one node disables and audits.</li>
 *   <li>Re-enable path: {@link #enable} (operator, audited {@value #ENABLED_EVENT}) resets the counter; deliveries held
 *       during the disable resume by themselves, parked ones are requeued through the outbox repair API.</li>
 * </ul>
 * Comparable services: Stripe disables an endpoint that keeps failing and notifies the owner; GitHub marks failed
 * deliveries and leaves redelivery to the owner. Sangam disables, audits and holds (see {@link HttpWebhookPublisher}).
 */
public final class WebhookHealth {

    public static final String DISABLED_EVENT = "WEBHOOK_DISABLED";
    public static final String ENABLED_EVENT = "WEBHOOK_ENABLED";

    public enum State { ENABLED, DISABLED }

    public record Status(String destination, State state, int consecutiveFailures, String lastError, Instant disabledAt,
                         String changedBy) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final int disableAfter;

    /**
     * @param disableAfter consecutive failed attempts that disable a destination (e.g. 50: with the relay's backoff cap
     *                     of 5 minutes that is hours of failure, not one bad minute)
     */
    public WebhookHealth(Dialect dialect, Transactions tx, AuditChain auditOrNull, int disableAfter) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = auditOrNull;
        this.disableAfter = Math.max(1, disableAfter);
    }

    public int disableAfter() {
        return disableAfter;
    }

    public Optional<Status> status(String destination) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT destination, state, consecutive_failures, last_error, disabled_at,"
                        + " changed_by FROM INTEGRATION_WEBHOOK_STATE WHERE destination=?",
                rs -> new Status(rs.getString(1), State.valueOf(rs.getString(2)), rs.getInt(3), rs.getString(4),
                        Jdbc.instant(rs, "disabled_at"), rs.getString(6)), destination));
    }

    public boolean isDisabled(String destination) {
        return status(destination).map(s -> s.state() == State.DISABLED).orElse(false);
    }

    /** A delivery succeeded: the failure streak ends. */
    public void success(String destination) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE INTEGRATION_WEBHOOK_STATE SET consecutive_failures=0, last_error=NULL,"
                + " updated_at=" + dialect.now() + " WHERE destination=? AND consecutive_failures<>0", destination));
    }

    /**
     * A delivery attempt failed. Returns true when this failure disabled the destination (audited under
     * {@code tenantId}, the tenant of the event that hit the threshold).
     */
    public boolean failure(String tenantId, String destination, String error) {
        return tx.inTransaction(c -> {
            ensureRow(c, destination);
            Jdbc.update(c, "UPDATE INTEGRATION_WEBHOOK_STATE SET consecutive_failures=consecutive_failures+1, last_error=?,"
                    + " updated_at=" + dialect.now() + " WHERE destination=?", truncate(error), destination);
            int n = Jdbc.update(c, "UPDATE INTEGRATION_WEBHOOK_STATE SET state='DISABLED', disabled_at=" + dialect.now()
                    + ", changed_by='auto' WHERE destination=? AND state='ENABLED' AND consecutive_failures>=?",
                    destination, disableAfter);
            if (n == 1) {
                audit(c, tenantId, destination, DISABLED_EVENT, "auto",
                        "disabled after " + disableAfter + " consecutive failed deliveries; last error: " + truncate(error));
            }
            return n == 1;
        });
    }

    /** Disables a destination now (operator action, or the receiver answered 410 Gone). Audited; false if already disabled. */
    public boolean disable(String tenantId, String destination, String actor, String reason) {
        return tx.inTransaction(c -> {
            ensureRow(c, destination);
            int n = Jdbc.update(c, "UPDATE INTEGRATION_WEBHOOK_STATE SET state='DISABLED', disabled_at=" + dialect.now()
                    + ", changed_by=?, last_error=?, updated_at=" + dialect.now() + " WHERE destination=? AND state='ENABLED'",
                    actor, truncate(reason), destination);
            if (n == 1) {
                audit(c, tenantId, destination, DISABLED_EVENT, actor, reason);
            }
            return n == 1;
        });
    }

    /** Re-enables a disabled destination and resets its failure streak. Audited; false if it was not disabled. */
    public boolean enable(String tenantId, String destination, String actor, String reason) {
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("re-enabling a webhook needs an actor and a reason");
        }
        return tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE INTEGRATION_WEBHOOK_STATE SET state='ENABLED', consecutive_failures=0,"
                    + " disabled_at=NULL, changed_by=?, updated_at=" + dialect.now() + " WHERE destination=? AND state='DISABLED'",
                    actor, destination);
            if (n == 1) {
                audit(c, tenantId, destination, ENABLED_EVENT, actor, reason);
            }
            return n == 1;
        });
    }

    private void ensureRow(Connection c, String destination) {
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO INTEGRATION_WEBHOOK_STATE(destination, state, consecutive_failures,"
                + " last_error, disabled_at, changed_by, updated_at) VALUES (?,'ENABLED',0,NULL,NULL,NULL," + dialect.now() + ")",
                destination);
    }

    private void audit(Connection c, String tenantId, String destination, String type, String actor, String reason) {
        if (audit == null) {
            return;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("destination", destination);
        p.put("actor", actor);
        p.put("reason", reason == null ? "" : reason);
        audit.append(c, tenantId == null || tenantId.isBlank() ? "_platform" : tenantId, "webhook:" + destination, type, p);
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
