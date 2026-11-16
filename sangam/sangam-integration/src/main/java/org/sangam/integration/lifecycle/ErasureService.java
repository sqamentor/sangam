// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.lifecycle;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorCallJournal;
import org.sangam.integration.relay.DeadLetterStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Erasure (privacy) per SANGAM.md VI.5.1 and R23, X.2.2.4 step 5, X.2.3.8; DPDP Act 2023 (GOV-4).
 *
 * <p>Scrubs the payload of an instance tree everywhere it was copied, and keeps what the law or the evidence needs:
 * <table>
 *   <caption>What erasure touches</caption>
 *   <tr><th>Store</th><th>Scrubbed</th><th>Kept</th></tr>
 *   <tr><td>Engine runtime + history</td><td>variables, comments (port {@link LifecyclePorts.EngineDataEraser})</td><td>ids, timestamps, activity trail</td></tr>
 *   <tr><td>{@code OUTBOX}</td><td>{@code payload} → {@code {"erased":true}}</td><td>event id, type, root_seq, deliveries</td></tr>
 *   <tr><td>{@code INBOX}</td><td>{@code payload} of messages correlated to the erased business keys</td><td>dedup key, state</td></tr>
 *   <tr><td>{@code IDEMPOTENCY_RECORD}</td><td>{@code response_body} where {@code resource_ref} is an erased instance</td><td>fingerprint, status, resource_ref</td></tr>
 *   <tr><td>{@code CONNECTOR_CALL}, dead letters</td><td>request input / payload copy</td><td>operation identity, provider reference</td></tr>
 *   <tr><td>Audit chain</td><td>event payload + salt ({@code AuditChain.erasePayload}) of each instance's chain and of its
 *       related chains (port {@link LifecyclePorts.RelatedAuditChains}: the {@code sec:d:} denial chain, SL-132); the
 *       chains still verify. Compliance per-principal chains ({@code cmp:p:}, {@code cmp:d:}) are not instance chains
 *       and hold references and purpose codes, not personal data, so they are kept</td><td>envelope, hashes</td></tr>
 *   <tr><td>Document store</td><td>objects (port {@link LifecyclePorts.DocumentEraser})</td><td>—</td></tr>
 *   <tr><td>History archive (RM-52)</td><td>a new archive without the tree's {@code ACT_HI_VARINST},
 *       {@code ACT_HI_DETAIL} and {@code ACT_HI_COMMENT} rows and their byte arrays replaces every earlier one (port
 *       {@link LifecyclePorts.ErasureFollower}); the earlier ones leave the store after the commit</td><td>the other
 *       history rows, both digests on the audit chain</td></tr>
 *   <tr><td>{@code RECONCILIATION_ITEM}, {@code OPERATION_LEDGER}</td><td><b>nothing</b>: an open reconciliation item is never deleted</td><td>all</td></tr>
 * </table>
 * Refused while a {@link LegalHoldStore legal hold} is active on the root (R61), and requires {@code ERASE_DATA}.
 * Writes {@code DATA_ERASED} (counts only, no personal data) on the root's chain.
 */
public final class ErasureService {

    public static final String ERASE_DATA = "ERASE_DATA";
    private static final String ERASED = "{\"erased\":true}";

    public static final class LegalHoldException extends RuntimeException {
        public LegalHoldException(String rootId, List<LegalHoldStore.Hold> holds) {
            super("409 LEGAL_HOLD: root " + rootId + " is under " + holds.size() + " legal hold(s); erasure refused");
        }
    }

    public record Result(String rootId, Map<String, Integer> scrubbed) {
    }

    private final Dialect dialect;
    private final Transactions engineTx;
    private final AuditChain audit;
    private final LegalHoldStore holds;
    private final ConnectorCallJournal journal;
    private final DeadLetterStore deadLetters;
    private final LifecyclePorts.EngineInstancePort engine;
    private final LifecyclePorts.EngineDataEraser engineData;
    private final LifecyclePorts.DocumentEraser documents;
    private final LifecyclePorts.RelatedAuditChains relatedChains;
    private final LifecyclePorts.Authorizer authorizer;
    private final List<LifecyclePorts.ErasureFollower> followers;

    public ErasureService(Dialect dialect, Transactions engineTx, AuditChain audit, LegalHoldStore holds,
                          ConnectorCallJournal journal, DeadLetterStore deadLetters, LifecyclePorts.EngineInstancePort engine,
                          LifecyclePorts.EngineDataEraser engineData, LifecyclePorts.DocumentEraser documents,
                          LifecyclePorts.Authorizer authorizer) {
        this.dialect = dialect;
        this.engineTx = engineTx;
        this.audit = audit;
        this.holds = holds;
        this.journal = journal;
        this.deadLetters = deadLetters;
        this.engine = engine;
        this.engineData = engineData;
        this.documents = documents == null ? LifecyclePorts.DocumentEraser.NONE : documents;
        this.authorizer = authorizer;
        this.relatedChains = LifecyclePorts.RelatedAuditChains.NONE;
        this.followers = List.of();
    }

    /**
     * As above, plus the audit chains that hold events of each erased chain (SL-132: the root's {@code sec:d:} denial
     * chain); their payloads are erased with the root's.
     */
    public ErasureService(Dialect dialect, Transactions engineTx, AuditChain audit, LegalHoldStore holds,
                          ConnectorCallJournal journal, DeadLetterStore deadLetters, LifecyclePorts.EngineInstancePort engine,
                          LifecyclePorts.EngineDataEraser engineData, LifecyclePorts.DocumentEraser documents,
                          LifecyclePorts.Authorizer authorizer, LifecyclePorts.RelatedAuditChains relatedChains) {
        this.dialect = dialect;
        this.engineTx = engineTx;
        this.audit = audit;
        this.holds = holds;
        this.journal = journal;
        this.deadLetters = deadLetters;
        this.engine = engine;
        this.engineData = engineData;
        this.documents = documents == null ? LifecyclePorts.DocumentEraser.NONE : documents;
        this.authorizer = authorizer;
        this.relatedChains = relatedChains == null ? LifecyclePorts.RelatedAuditChains.NONE : relatedChains;
        this.followers = List.of();
    }

    /**
     * As above, plus the copies the erasure follows (RM-52: the tree's history archive, which is replaced by an archive
     * without the erased data).
     */
    public ErasureService(Dialect dialect, Transactions engineTx, AuditChain audit, LegalHoldStore holds,
                          ConnectorCallJournal journal, DeadLetterStore deadLetters, LifecyclePorts.EngineInstancePort engine,
                          LifecyclePorts.EngineDataEraser engineData, LifecyclePorts.DocumentEraser documents,
                          LifecyclePorts.Authorizer authorizer, LifecyclePorts.RelatedAuditChains relatedChains,
                          List<LifecyclePorts.ErasureFollower> followers) {
        this.dialect = dialect;
        this.engineTx = engineTx;
        this.audit = audit;
        this.holds = holds;
        this.journal = journal;
        this.deadLetters = deadLetters;
        this.engine = engine;
        this.engineData = engineData;
        this.documents = documents == null ? LifecyclePorts.DocumentEraser.NONE : documents;
        this.authorizer = authorizer;
        this.relatedChains = relatedChains == null ? LifecyclePorts.RelatedAuditChains.NONE : relatedChains;
        this.followers = followers == null ? List.of() : List.copyOf(followers);
    }

    /** The audit chains erased for {@code tree}: each instance's chain, followed by its related chains (SL-132). */
    List<String> auditChains(String tenantId, List<String> tree) {
        List<String> chains = new ArrayList<>();
        for (String id : tree) {
            if (!chains.contains(id)) {
                chains.add(id);
            }
            for (String related : relatedChains.of(tenantId, id)) {
                if (!chains.contains(related)) {
                    chains.add(related);
                }
            }
        }
        return chains;
    }

    /**
     * @param correlationKeys business keys / correlation keys whose inbox messages belong to this tree
     */
    public Result erase(String tenantId, String rootId, Collection<String> correlationKeys, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required (R23)");
        }
        List<LifecyclePorts.ErasureFollower.FollowUp> followUps = new ArrayList<>();
        Result r = engineTx.inTransaction(c -> {
            authorizer.require(c, tenantId, actor, ERASE_DATA, rootId);
            List<LegalHoldStore.Hold> active = holds.active(c, tenantId, rootId);
            if (!active.isEmpty()) {
                throw new LegalHoldException(rootId, active);
            }
            // RM-52: the followed copies (the history archive) are read while the audit payloads still are
            followUps.clear();
            for (LifecyclePorts.ErasureFollower f : followers) {
                LifecyclePorts.ErasureFollower.FollowUp u = f.before(c, tenantId, rootId);
                if (u != null) {
                    followUps.add(u);
                }
            }
            List<String> tree = new ArrayList<>(engine.instanceTree(c, rootId));
            if (!tree.contains(rootId)) {
                tree.add(0, rootId);
            }
            Map<String, Integer> n = new LinkedHashMap<>();
            // audit chain events of the tree (payload + salt), before the new DATA_ERASED event is appended
            int auditErased = 0;
            for (String chain : auditChains(tenantId, tree)) {
                List<String> events = Jdbc.query(c, "SELECT e.event_id FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                        + " WHERE e.tenant_id=? AND e.chain_id=? AND p.erased_at IS NULL", rs -> rs.getString(1), tenantId, chain);
                for (String ev : events) {
                    audit.erasePayload(c, ev, reason);
                    auditErased++;
                }
            }
            n.put("auditPayloads", auditErased);
            int outbox = 0, idem = 0;
            for (String id : tree) {
                outbox += Jdbc.update(c, "UPDATE OUTBOX SET payload=? WHERE tenant_id=? AND root_id=?", ERASED, tenantId, id);
                idem += Jdbc.update(c, "UPDATE IDEMPOTENCY_RECORD SET response_body=NULL WHERE tenant_id=? AND resource_ref=?", tenantId, id);
            }
            n.put("outbox", outbox);
            n.put("idempotency", idem);
            int inbox = 0;
            if (correlationKeys != null) {
                for (String k : correlationKeys) {
                    inbox += Jdbc.update(c, "UPDATE INBOX SET payload=? WHERE tenant_id=? AND correlation_key=?", ERASED, tenantId, k);
                }
            }
            n.put("inbox", inbox);
            n.put("connectorCalls", journal.erase(c, tenantId, tree));
            n.put("deadLetters", deadLetters == null ? 0 : deadLetters.erase(c, tenantId, tree));
            n.put("engine", engineData == null ? 0 : engineData.erase(c, tenantId, tree, reason));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("actor", actor);
            p.put("reason", reason);
            p.put("instances", tree.size());
            p.put("scrubbed", new LinkedHashMap<>(n));
            String erasedEvent = audit.append(c, tenantId, rootId, "DATA_ERASED", p).eventId();
            if (!followers.isEmpty()) {
                int replaced = 0;
                for (LifecyclePorts.ErasureFollower.FollowUp u : followUps) {
                    replaced += u.erased(c, erasedEvent, reason);
                }
                n.put("historyArchives", replaced);
            }
            return new Result(rootId, n);
        });
        // object store last: not transactional; a failure leaves the DB erased and is retried by the operator
        List<String> tree = engineTx.inTransaction(c -> engine.instanceTree(c, rootId));
        int docs = documents.erase(tenantId, tree.isEmpty() ? List.of(rootId) : tree, reason);
        Map<String, Integer> all = new LinkedHashMap<>(r.scrubbed());
        all.put("documents", docs);
        if (!followers.isEmpty()) {
            int removed = 0;
            for (LifecyclePorts.ErasureFollower.FollowUp u : followUps) {
                removed += u.committed();
            }
            all.put("historyArchivesRemoved", removed);
        }
        return new Result(rootId, all);
    }

    Dialect dialect() {
        return dialect;
    }
}
