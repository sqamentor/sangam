// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.conversation;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Scheduled conversation retention (NG-12): one run applies, for one tenant, every retention class that has a period, through
 * {@link ConversationService#purgeBefore} (which removes the messages, their index rows and their attachments, and audits
 * {@code CONVERSATION_PURGED}). The cut-off is the database's current time minus the class period, computed in UTC, so the
 * nodes of a cluster agree and no JVM clock is read. A class with no period (or one the policy does not know) is kept.
 * The host runs it on its leader-elected channels schedule; a second run with nothing old enough removes nothing.
 */
public final class ConversationRetention {

    public static final String ACTOR = "system:retention";

    private final ConversationService conversations;
    private final Dialect dialect;
    private final Transactions tx;
    private final Function<String, Map<String, Period>> policy;

    /**
     * @param policy tenant to (retention class to period); the host reads it from the tenant's {@code retention-classes}
     *               registry entries ({@code retentionYears}) or its configuration
     */
    public ConversationRetention(ConversationService conversations, Dialect dialect, Transactions tx, Function<String, Map<String, Period>> policy) {
        this.conversations = conversations;
        this.dialect = dialect;
        this.tx = tx;
        this.policy = policy;
    }

    /** Applies the tenant's policy once; returns the number of messages removed per class (classes with a period only). */
    public Map<String, Integer> runOnce(String tenantId) {
        Map<String, Period> periods = policy.apply(tenantId);
        Map<String, Integer> removed = new LinkedHashMap<>();
        if (periods == null || periods.isEmpty()) {
            return removed;
        }
        record State(Instant now, List<String> classes) {
        }
        State s = tx.inTransaction(c -> new State(Jdbc.dbNow(c, dialect),
                Jdbc.query(c, "SELECT DISTINCT retention_class FROM CH_CONVERSATION WHERE tenant_id=? ORDER BY retention_class", rs -> rs.getString(1), tenantId)));
        for (String cls : s.classes()) {
            Period p = periods.get(cls);
            if (p == null || p.isZero() || p.isNegative()) {
                continue;
            }
            removed.put(cls, conversations.purgeBefore(tenantId, cls, cutoff(s.now(), p), ACTOR));
        }
        return removed;
    }

    /** {@code now} minus the period, on the UTC calendar. */
    static Instant cutoff(Instant now, Period p) {
        return now.atOffset(ZoneOffset.UTC).minus(p).toInstant();
    }

    /** A period from a {@code retention-classes} entry's {@code retentionYears}. */
    public static Period years(Object retentionYears) {
        if (retentionYears == null) {
            return null;
        }
        int y = Integer.parseInt(retentionYears.toString().trim());
        if (y <= 0) {
            throw new IllegalArgumentException("retentionYears must be positive");
        }
        return Period.ofYears(y);
    }
}
