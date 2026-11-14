// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.util.Map;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.rest.port.RequestContext;

/**
 * Denial audits of the REST adapters (SL-132, the SL-137 design): a refusal that the caller follows with a throw, so
 * the caller's transaction rolls back and an in-line event would be lost ({@code SOD_VIOLATION_BLOCKED} of a task
 * completion).
 *
 * <p>The event goes to the {@linkplain #chainOf denial chain} of the chain it belongs to and commits on its own through
 * {@code leaf}. Two waits are excluded:
 * <ul>
 *   <li><i>Pool wait (SL-43).</i> The caller holds a main-pool connection (its REST/engine transaction); a second one
 *       from the same pool while it is held is the SL-43 pool deadlock once every slot is held by a caller waiting for
 *       its second. {@code leaf} must therefore be a separate leaf pool, whose connections are never held while another
 *       connection is requested. Without a wired leaf pool, {@link #mainPool} falls back to a new transaction of the
 *       main pool: the denial still survives and the row-lock wait below is still excluded, but the pool wait is not.</li>
 *   <li><i>Row-lock wait ({@code ROOT_SEQ}).</i> The caller may already have appended to the root chain in its own
 *       transaction and then holds that chain's {@code ROOT_SEQ} row. The denial chain is written only by denial
 *       writers, so its row is only ever held by other short denial transactions.</li>
 * </ul>
 * The chain id is {@link AuditChain#denialChain}, the one formula (sangam-security's
 * {@code SecurityAuditService.denialChain} delegates to it too), so a root's denials from both
 * modules share one denial chain; {@link JdbcAuditAdapter} reads it with the root's chain (timeline, export, verify).
 */
public final class DenialAudit {

    /** Prefix of every denial chain (starts with {@code sec:}: the envelope carries no {@code root_id}). */
    public static final String PREFIX = AuditChain.DENIAL_PREFIX;

    private final AuditChain chain;
    private final Transactions leaf;

    /** @param leaf transactions of the leaf pool for denial audits (see the class comment) */
    public DenialAudit(Dialect dialect, Transactions leaf) {
        if (leaf == null) {
            throw new IllegalArgumentException("a denial-audit transaction source is required");
        }
        this.chain = new AuditChain(dialect);
        this.leaf = leaf;
    }

    /** Fallback without a leaf pool: each denial in a new transaction of the main pool (pool wait not excluded). */
    public static DenialAudit mainPool(org.sangam.rest.support.TxSupport tx) {
        return new DenialAudit(tx.dialect(), tx.newTransactions());
    }

    /**
     * The denial-only chain of {@code chainId} (a root id or {@code sec:<tenant>}): {@code sec:d:} plus the first 32 hex
     * digits of SHA-256(tenant NUL chainId). Any root id fits {@code chain_id} (64 characters).
     */
    public static String chainOf(String tenantId, String chainId) {
        return AuditChain.denialChain(tenantId, chainId);   // the one formula, in sangam-core
    }

    /**
     * Appends {@code eventType} to the denial chain of {@code chainId} and commits it on its own. The payload is the
     * one {@link JdbcAuditAdapter#record} would store (sanitised values plus the actor).
     *
     * @return the event id
     */
    public String record(RequestContext ctx, String chainId, String eventType, Map<String, ?> payload) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("audit chain id is required");
        }
        String tenant = ctx.tenantId();
        String denialChain = chainOf(tenant, chainId);
        Map<String, Object> p = JdbcAuditAdapter.eventPayload(ctx, payload);
        return leaf.inTransaction(c -> chain.append(c, tenant, denialChain, eventType, p).eventId());
    }
}
