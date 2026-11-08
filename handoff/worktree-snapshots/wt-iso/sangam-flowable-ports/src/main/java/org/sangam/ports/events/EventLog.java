// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.support.Sql;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;

/**
 * Database event log (FLOWABLE-INTO-OPERATON FC-43, Flowable {@code ACT_EVT_LOG}; table {@code EVT_LOG}). Every inbound receipt,
 * dispatch outcome and outbound hand-off is written in the same transaction as its effect, so the log never claims an
 * effect that rolled back. Payloads are stored only when {@code storePayloads} is on (R61/DPDP: default off).
 */
public class EventLog {

    public enum Direction { INBOUND, DISPATCH, OUTBOUND, ENGINE }

    public record Entry(long id, String tenantId, String direction, String channelKey, String eventKey, String messageId,
                        String correlationKey, String outcome, String detail, Instant createdAt) {
    }

    private final Dialect dialect;
    private final boolean storePayloads;

    public EventLog(Dialect dialect, boolean storePayloads) {
        this.dialect = dialect;
        this.storePayloads = storePayloads;
    }

    public void log(Connection c, String tenant, Direction direction, String channelKey, String eventKey, String messageId,
                    String correlationKey, String outcome, String detail, String payload) {
        Jdbc.update(c, "INSERT INTO EVT_LOG(tenant_id, direction, channel_key, event_key, message_id, correlation_key, outcome,"
                        + " detail, payload, created_at) VALUES (?,?,?,?,?,?,?,?,?," + dialect.now() + ")",
                tenant, direction.name(), channelKey, eventKey, messageId, correlationKey, outcome,
                detail == null ? null : truncate(detail, 3900), storePayloads ? payload : null);
    }

    public List<Entry> recent(Connection c, String tenant, String eventKey, int limit) {
        String where = eventKey == null ? "tenant_id = ?" : "tenant_id = ? AND event_key = ?";
        Object[] params = eventKey == null ? new Object[]{tenant} : new Object[]{tenant, eventKey};
        return Jdbc.query(c, "SELECT id, tenant_id, direction, channel_key, event_key, message_id, correlation_key, outcome,"
                        + " detail, created_at FROM EVT_LOG WHERE " + where + " ORDER BY id DESC" + Sql.limit(dialect, limit),
                rs -> new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), Jdbc.instant(rs, "created_at")), params);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
