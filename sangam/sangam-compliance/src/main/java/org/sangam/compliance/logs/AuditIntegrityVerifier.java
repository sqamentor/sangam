// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.logs;

import org.sangam.compliance.ComplianceAudit;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Log integrity (CERT-In para (iv) "maintain securely"; DPDP Rule 6(1)(c); SPDI r.8; CMP-012, CMP-056, CMP-091)
 * through the existing core {@link AuditChain} hash chain: every chain of the tenant is recomputed with
 * {@link AuditChain#verify} (gapless sequence, prev-hash links, envelopes, hashes, payload digests and the chain head)
 * and the result is itself appended to the compliance chain as evidence. No second hashing scheme exists.
 */
public final class AuditIntegrityVerifier {

    public record ChainResult(String chainId, boolean ok, long events, Long firstBadSeq, String reason) {
    }

    public record Result(boolean ok, int chains, long events, List<ChainResult> failures) {
    }

    private final AuditChain chain;
    private final ComplianceAudit audit;

    public AuditIntegrityVerifier(AuditChain chain, ComplianceAudit audit) {
        this.chain = chain;
        this.audit = audit;
    }

    /** Verifies up to {@code maxChains} chains of the tenant (all when {@code maxChains <= 0}). */
    public Result verifyTenant(Connection c, String tenantId, int maxChains) {
        List<String> chains = Jdbc.query(c, "SELECT DISTINCT chain_id FROM AUDIT_EVENT WHERE tenant_id=? ORDER BY chain_id",
                rs -> rs.getString(1), tenantId);
        if (maxChains > 0 && chains.size() > maxChains) {
            chains = chains.subList(0, maxChains);
        }
        List<ChainResult> failures = new ArrayList<>();
        long events = 0;
        for (String id : chains) {
            AuditChain.VerifyResult r = chain.verify(c, tenantId, id);
            events += r.eventsChecked();
            if (!r.ok()) {
                failures.add(new ChainResult(id, false, r.eventsChecked(), r.firstBadSeq(), r.reason()));
            }
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("chains", chains.size());
        ev.put("events", events);
        ev.put("failures", failures.size());
        ev.put("failedChains", failures.stream().map(ChainResult::chainId).toList());
        audit.organisation(c, tenantId, ComplianceAudit.AUDIT_VERIFIED, ev);
        return new Result(failures.isEmpty(), chains.size(), events, failures);
    }

    public ChainResult verifyChain(Connection c, String tenantId, String chainId) {
        AuditChain.VerifyResult r = chain.verify(c, tenantId, chainId);
        return new ChainResult(chainId, r.ok(), r.eventsChecked(), r.firstBadSeq(), r.reason());
    }
}
