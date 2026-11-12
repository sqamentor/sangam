// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.util.List;
import java.util.Map;

import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.RequestContext;

/**
 * One audited read of several chains for one user-visible request (SG-15; owner decision 2026-11-12: one
 * {@code AUDIT_VIEWED} per user-visible request, internal lookups write no extra event). {@link JdbcAuditAdapter}
 * implements it; adapters that enrich a response with audit events (task detail) use it instead of one
 * {@link AuditPort#search} per chain and page, each of which writes its own {@code AUDIT_VIEWED}.
 */
public interface ChainViews {

    /**
     * The newest {@code perChainLimit} class-B events of each chain (the rows {@link AuditPort#search} with that
     * {@code chainId} returns over its first pages), per chain oldest first, read in one transaction; then exactly one
     * {@code AUDIT_VIEWED} with {@code view} and the chain ids, on the tenant security chain in its own transaction.
     * Nothing is read or written when {@code chainIds} is empty.
     *
     * @return the events, keyed by chain id in the order given
     */
    Map<String, List<AuditPort.AuditEntry>> viewChains(RequestContext ctx, List<String> chainIds, int perChainLimit,
                                                       Map<String, ?> view);
}
