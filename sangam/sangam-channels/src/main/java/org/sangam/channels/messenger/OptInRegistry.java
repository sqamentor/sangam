// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.util.Map;
import java.util.Optional;

/** Maps an opted-in external messenger identity to a Sangam principal. An unknown or opted-out identity maps to nothing. */
public final class OptInRegistry {

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;

    public OptInRegistry(Dialect dialect, Transactions tx, AuditChain audit) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
    }

    public void optIn(String tenantId, String channel, String externalId, String principalId, String evidence) {
        set(tenantId, channel, externalId, principalId, "ACTIVE", evidence);
    }

    public void optOut(String tenantId, String channel, String externalId, String principalId, String evidence) {
        set(tenantId, channel, externalId, principalId, "OPTED_OUT", evidence);
    }

    private void set(String tenantId, String channel, String externalId, String principalId, String state, String evidence) {
        tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE CH_OPT_IN SET principal_id=?, state=?, evidence=?, updated_at=? WHERE tenant_id=? AND channel=? AND external_id=?",
                    principalId, state, evidence, Jdbc.dbNow(c, dialect), tenantId, channel, externalId);
            if (n == 0) {
                Jdbc.update(c, "INSERT INTO CH_OPT_IN(tenant_id,channel,external_id,principal_id,state,evidence,updated_at) VALUES (?,?,?,?,?,?,?)",
                        tenantId, channel, externalId, principalId, state, evidence, Jdbc.dbNow(c, dialect));
            }
            audit.append(c, tenantId, "optin:" + channel, "CHANNEL_OPT_IN_" + state, Map.of("channel", channel, "principal", principalId));
            return null;
        });
    }

    public Optional<String> principalOf(String tenantId, String channel, String externalId) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT principal_id FROM CH_OPT_IN WHERE tenant_id=? AND channel=? AND external_id=? AND state='ACTIVE'",
                rs -> rs.getString(1), tenantId, channel, externalId));
    }

    /** A registered mapping. The evidence stays server-side. */
    public record OptIn(String channel, String externalId, String principalId, String state, java.time.Instant updatedAt) {
    }

    public java.util.List<OptIn> list(String tenantId, String channelOrNull) {
        String sql = "SELECT channel,external_id,principal_id,state,updated_at FROM CH_OPT_IN WHERE tenant_id=?" + (channelOrNull == null ? "" : " AND channel=?") + " ORDER BY channel, external_id";
        Object[] params = channelOrNull == null ? new Object[]{tenantId} : new Object[]{tenantId, channelOrNull};
        return tx.inTransaction(c -> Jdbc.query(c, sql, rs -> new OptIn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), params));
    }
}
