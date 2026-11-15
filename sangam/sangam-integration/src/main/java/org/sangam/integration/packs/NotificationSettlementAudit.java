// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.packs;

import java.sql.Connection;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.sangam.integration.connector.Json;
import org.sangam.packs.core.NotifyDelegate;
import org.sangam.packs.port.PackAuditSink;
import org.sangam.packs.port.PackRuntime;

/**
 * Owner decision 313: {@code NOTIFICATION_SENT} is written once, by the integration result path, when the connector
 * call of a notification completes successfully. A notification submitted to a bound connector is only queued
 * ({@code PENDING}, {@code NOTIFICATION_QUEUED} from the pack); its outcome arrives later, when the dispatch, a lookup
 * or a reconciliation settles the operation. This listener, registered for the connector family
 * {@value NotifyDelegate#CONNECTOR_PREFIX} with {@link #register}, writes the event then.
 *
 * <p>Exactly once: the store calls a settlement listener only when the row first reaches a final state, inside the
 * settling transaction, and a final row is never settled again (decision 289), so a second settle, a retry, a
 * re-dispatch or a late reconciliation cannot write a second event. A {@code FAILED} or {@code CANCELLED} settlement
 * writes nothing here, and {@code UNKNOWN} / {@code RETRY_REQUESTED} are not settlements. The event is recorded
 * through {@link PackAuditSink#record(Connection, org.sangam.packs.port.PackAuditEvent)} on the settling connection,
 * so it commits or rolls back with the settlement; an exception rolls the settlement back.
 *
 * <p>The event is built by {@link NotifyDelegate#sentLater} from the stored operation (channel from the connector
 * name, template and recipient from the submitted payload), on the case's business key, so it lands on the same case
 * timeline as the pack's own notification events.
 */
public final class NotificationSettlementAudit implements PackSettlementListener {

    private final PackOperationStore store;
    private final Supplier<PackAuditSink> sink;
    private final Supplier<Clock> clock;

    /** Writes to the installed pack audit sink ({@link PackRuntime#audit()}, looked up at each settlement). */
    public NotificationSettlementAudit(PackOperationStore store) {
        this(store, PackRuntime::audit, PackRuntime::clock);
    }

    NotificationSettlementAudit(PackOperationStore store, Supplier<PackAuditSink> sink, Supplier<Clock> clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers the listener for the notification connectors on {@code store}; once per store. */
    public static NotificationSettlementAudit register(PackOperationStore store) {
        NotificationSettlementAudit audit = new NotificationSettlementAudit(store);
        store.onConnectorSettled(NotifyDelegate.CONNECTOR_PREFIX, audit);
        return audit;
    }

    @Override
    public void settled(Connection c, Settlement s) {
        if (s.status() != PackOperationStore.Status.COMPLETED
                || !NotifyDelegate.isNotification(s.connector(), s.operation())) {
            return;
        }
        PackOperationStore.Row row = store.find(c, s.tenantId(), s.operationId()).orElseThrow(
                () -> new IllegalStateException("settled notification operation " + s.operationId() + " has no row"));
        Map<String, Object> submitted = row.payloadJson() == null ? Map.of() : Json.readMap(row.payloadJson());
        sink.get().record(c, NotifyDelegate.sentLater(s.tenantId(), s.businessKey(), s.processInstanceId(),
                s.connector(), submitted, s.operationId(), s.externalRef(), s.settledBy(), clock.get().instant()));
    }
}
