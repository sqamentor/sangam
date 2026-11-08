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
 *   <tr><td>Audit chain</td><td>event payload + salt ({@code AuditChain.erasePayload}); the chain still verifies</td><td>envelope, hashes</td></tr>
 *   <tr><td>Document store</td><td>objects (port {@link LifecyclePorts.DocumentEraser})</td><td>—</td></tr>
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
    private final LifecyclePorts.Authorizer authorizer;

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
    }

    /**
     * @param correlationKeys business keys / correlation keys whose inbox messages belong to this tree
     */
    public Result erase(String tenantId, String rootId, Collection<String> correlationKeys, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required (R23)");
        }
        Result r = engineTx.inTransaction(c -> {
            authorizer.require(c, tenantId, actor, ERASE_DATA, rootId);
            List<LegalHoldStore.Hold> active = holds.active(c, tenantId, rootId);
            if (!active.isEmpty()) {
                throw new LegalHoldException(rootId, active);
            }
            List<String> tree = new ArrayList<>(engine.instanceTree(c, rootId));
            if (!tree.contains(rootId)) {
                tree.add(0, rootId);
            }
            Map<String, Integer> n = new LinkedHashMap<>();
            // audit chain events of the tree (payload + salt), before the new DATA_ERASED event is appended
            int auditErased = 0;
            for (String chain : tree) {
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
            audit.append(c, tenantId, rootId, "DATA_ERASED", p);
            return new Result(rootId, n);
        });
        // object store last: not transactional; a failure leaves the DB erased and is retried by the operator
        List<String> tree = engineTx.inTransaction(c -> engine.instanceTree(c, rootId));
        int docs = documents.erase(tenantId, tree.isEmpty() ? List.of(rootId) : tree, reason);
        Map<String, Integer> all = new LinkedHashMap<>(r.scrubbed());
        all.put("documents", docs);
        return new Result(rootId, all);
    }

    Dialect dialect() {
        return dialect;
    }
}
