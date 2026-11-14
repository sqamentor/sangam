// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.audit;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Transactions;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.dialect.SecurityDialect;
import org.sangam.security.policy.AuthEffectiveMaintainer;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Class-B security and authority audit (R42 audit part, X.2.3.2, X.4.0.3). Events are appended through the core
 * {@link AuditChain} in the caller's transaction (L5 insert, never through the outbox):
 * <ul>
 *   <li>events with a root (task assignment, acted-for, approvals, SoD, power checks) go to the root chain;</li>
 *   <li>events without one (logins, grants, memberships, seat holders, admin) go to the tenant security chain
 *       {@code sec:<tenant>} (X.2.3.3 rule 2);</li>
 *   <li>denials ({@link #denial}) go to the {@linkplain #denialChain denial chain} of the chain they belong to and
 *       commit on their own (SL-132).</li>
 * </ul>
 * The payload carries the envelope actor fields: {@code principal_id}, {@code seat_id}, {@code acted_for_seat_id}
 * (IX.5 rule 4, "acted for"), {@code on_behalf_of} (VI.4 rule 7), {@code auth_method}, {@code client_ip} and the
 * {@code policy_version} (VI.4) current in the transaction.
 */
public final class SecurityAuditService implements SecurityPorts.SecurityAudit {

    private final AuditChain chain;
    private final Transactions denialTx;

    public SecurityAuditService(SecurityDialect dialect) {
        this(dialect, null);
    }

    /**
     * @param denialTx <b>leaf</b> transactions for denial events ({@code AUTH_DENIED}, {@code SOD_VIOLATION_BLOCKED},
     *                 {@code POWER_LIMIT_EXCEEDED}; see {@link #denial}): the denial rolls the command transaction back,
     *                 which would drop an in-line event. It must come from a pool other than the one callers hold
     *                 (SL-132, the SL-43 pool-wait rule; the server wires a dedicated leaf pool). Null writes in-line on
     *                 the denial chain (tests, or callers that commit the denial themselves).
     */
    public SecurityAuditService(SecurityDialect dialect, Transactions denialTx) {
        this.chain = new AuditChain(dialect.core());
        this.denialTx = denialTx;
    }

    /**
     * Records a denial so that it survives the rollback of the command transaction (SL-132, the SL-137 design).
     *
     * <p>The event goes to the {@linkplain #denialChain denial chain} of the chain it belongs to (the root's chain, or
     * {@code sec:<tenant>} without a root), never to that chain itself, and commits on its own through
     * {@code denialTx}. The caller holds a connection and may hold row locks, so two waits are excluded:
     * <ul>
     *   <li><i>Pool wait (SL-43).</i> {@code denialTx} is a leaf pool whose connections are never held while another
     *       connection is requested; a wait for one can never be part of a wait cycle.</li>
     *   <li><i>Row-lock wait ({@code ROOT_SEQ}).</i> Callers append to the root chain and to {@code sec:<tenant>} in
     *       their own transaction (engine listeners, admin services), and then hold that chain's {@code ROOT_SEQ} row
     *       until they commit. The denial chain is written only here, so its row is only ever held by other short
     *       denial transactions, which never wait for a caller.</li>
     * </ul>
     * The envelope (the {@code policy_version} the decision saw) is read on the caller's connection {@code c}; the
     * separate transaction only appends.
     */
    public void denial(Connection c, SecurityIdentity actor, SecurityEventType type, String rootId, Map<String, ?> payload) {
        String tenant = actor.tenantId();
        String chainId = denialChain(tenant, rootId == null ? securityChain(tenant) : rootId);
        Map<String, Object> envelope = envelope(c, actor, payload);
        if (denialTx == null) {
            chain.append(c, tenant, chainId, type.name(), envelope);
            return;
        }
        denialTx.inTransaction(leaf -> chain.append(leaf, tenant, chainId, type.name(), envelope));
    }

    public static String securityChain(String tenant) {
        return "sec:" + tenant;
    }

    /**
     * The denial-only chain of {@code chainId} (a root id or {@code sec:<tenant>}): {@code sec:d:} plus the first 32 hex
     * digits of SHA-256(tenant NUL chainId), so any root id fits {@code chain_id} (64 characters) and the tenant is part
     * of the key. Only {@link #denial} appends to it. Readers that show a root's audit read this chain with it (the
     * REST adapter computes the same id: {@code org.sangam.rest.adapter.jdbc.DenialAudit#chainOf}).
     */
    public static String denialChain(String tenant, String chainId) {
        return DENIAL_PREFIX + Hashes.hex(Hashes.sha256(tenant + "\u0000" + chainId)).substring(0, 32);
    }

    /** Prefix of every denial chain; it starts with {@code sec:}, so the envelope carries no {@code root_id}. */
    public static final String DENIAL_PREFIX = "sec:d:";

    @Override
    public void record(Connection c, SecurityIdentity actor, String eventType, String rootId, Map<String, ?> payload) {
        String chainId = rootId == null ? securityChain(actor.tenantId()) : rootId;
        chain.append(c, actor.tenantId(), chainId, eventType, envelope(c, actor, payload));
    }

    public void record(Connection c, SecurityIdentity actor, SecurityEventType type, String rootId, Map<String, ?> payload) {
        record(c, actor, type.name(), rootId, payload);
    }

    /** Security-chain event (no root). */
    public void security(Connection c, SecurityIdentity actor, SecurityEventType type, Map<String, ?> payload) {
        record(c, actor, type.name(), null, payload);
    }

    /** Admin event recorded in a tenant other than the actor's (platform admin creating a tenant). */
    public void securityIn(Connection c, String tenant, SecurityIdentity actor, SecurityEventType type, Map<String, ?> payload) {
        chain.append(c, tenant, securityChain(tenant), type.name(), envelope(c, actor, payload));
    }

    private static Map<String, Object> envelope(Connection c, SecurityIdentity actor, Map<String, ?> payload) {
        Map<String, Object> actorMap = new LinkedHashMap<>();
        actorMap.put("principal_id", actor.personId());
        actorMap.put("subject", actor.subject());
        actorMap.put("auth_method", actor.authMethod());
        if (actor.clientIp() != null) {
            actorMap.put("client_ip", actor.clientIp());
        }
        if (actor.onBehalfOf() != null) {
            actorMap.put("on_behalf_of", actor.onBehalfOf());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actor", actorMap);
        m.put("policy_version", AuthEffectiveMaintainer.policyVersion(c, actor.tenantId()));
        if (payload != null) {
            for (Map.Entry<String, ?> e : payload.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                if (e.getKey().equals("seat_id") || e.getKey().equals("acted_for_seat_id")) {
                    actorMap.put(e.getKey(), e.getValue());
                } else {
                    m.put(e.getKey(), normalise(e.getValue()));
                }
            }
        }
        return m;
    }

    /** CanonicalJson accepts integral numbers only; decimals and other values are carried as strings (R33). */
    private static Object normalise(Object v) {
        if (v instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Double || v instanceof Float) {
            return v.toString();
        }
        if (v instanceof java.time.Instant || v instanceof Enum<?>) {
            return v.toString();
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, x) -> {
                if (x != null) {
                    out.put(String.valueOf(k), normalise(x));
                }
            });
            return out;
        }
        if (v instanceof java.util.Collection<?> coll) {
            return coll.stream().map(SecurityAuditService::normalise).toList();
        }
        return v;
    }
}
