// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.catalogue;

import org.sangam.config.common.ConfigException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A document with two (or more) owning parties in different tenants, each of which must approve before it takes
 * effect: intragovernmental agreements and orders (US G-Invoicing, BR intra-SIAFI; PFM-053), inter-entity charges,
 * buyer / seller orders between departments. Settlement happens without external cash: the caller posts a ledger
 * TRANSFER (or a pair of postings) between the parties' accounts once {@link #isEffective()}.
 *
 * <p>Within one cell the parties are tenants of the same database; across cells the counterpart approves through the
 * referral / event path (ADR-D-21: no cross-cell transaction) and this object is mirrored in each cell.
 *
 * <p>The document type in the catalogue declares {@code parties: [{role: BUYER}, {role: SELLER}]}; any amendment after
 * one party approved resets the other approvals (both sides always approved the same content hash).
 */
public final class TwoSidedDocument {

    public enum State { DRAFT, PARTLY_APPROVED, EFFECTIVE, REJECTED, CANCELLED }

    public record Approval(String role, String tenant, String actor, String contentHash, Instant at, boolean approved,
                           String reason) {
    }

    private final String id;
    private final String typeId;
    private final Map<String, String> partyTenants;
    private final Map<String, Approval> approvals = new LinkedHashMap<>();
    private String contentHash;
    private State state = State.DRAFT;

    /** @param partyTenants role → tenant, e.g. {@code {BUYER: GOI.MHA, SELLER: GOI.CPWD}} */
    public TwoSidedDocument(String id, String typeId, Map<String, String> partyTenants, String contentHash) {
        if (partyTenants == null || partyTenants.size() < 2) {
            throw new ConfigException("PARTIES_REQUIRED", "a two-sided document needs at least two parties");
        }
        this.id = id;
        this.typeId = typeId;
        this.partyTenants = Map.copyOf(partyTenants);
        this.contentHash = contentHash;
    }

    public static List<String> roles(DocumentType type) {
        return type.raw().get("parties") instanceof List<?> l
                ? l.stream().map(o -> o instanceof Map<?, ?> m ? String.valueOf(m.get("role")) : String.valueOf(o)).toList()
                : List.of();
    }

    /** Records one party's decision; the acting tenant must be the tenant of that role. */
    public synchronized State decide(String role, String actingTenant, String actor, String presentedHash, boolean approve,
                                     String reason, Instant at) {
        if (state == State.EFFECTIVE || state == State.REJECTED || state == State.CANCELLED) {
            throw new ConfigException("STATE_FINAL", id + " is " + state);
        }
        String t = partyTenants.get(role);
        if (t == null || !t.equals(actingTenant)) {
            throw new ConfigException("TENANT_MISMATCH", "role " + role + " belongs to " + t);
        }
        if (!contentHash.equals(presentedHash)) {
            throw new ConfigException("APPROVAL_INVALIDATED", "content changed after review");
        }
        approvals.put(role, new Approval(role, actingTenant, actor, presentedHash, at, approve, reason));
        if (!approve) {
            state = State.REJECTED;
        } else if (approvals.size() == partyTenants.size() && approvals.values().stream().allMatch(Approval::approved)) {
            state = State.EFFECTIVE;
        } else {
            state = State.PARTLY_APPROVED;
        }
        return state;
    }

    /** An amendment by one party: new content, every approval reset. */
    public synchronized void amend(String newHash) {
        if (state == State.EFFECTIVE) {
            throw new ConfigException("STATE_FINAL", "an effective document is amended by a new document");
        }
        contentHash = newHash;
        approvals.clear();
        state = State.DRAFT;
    }

    public synchronized void cancel() {
        state = State.CANCELLED;
    }

    public boolean isEffective() {
        return state == State.EFFECTIVE;
    }

    public State state() {
        return state;
    }

    public String id() {
        return id;
    }

    public String typeId() {
        return typeId;
    }

    public Map<String, String> partyTenants() {
        return partyTenants;
    }

    public synchronized Map<String, Approval> approvals() {
        return Map.copyOf(approvals);
    }
}
