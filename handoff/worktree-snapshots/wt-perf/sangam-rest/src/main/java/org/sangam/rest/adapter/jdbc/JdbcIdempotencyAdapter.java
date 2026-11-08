// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.layer.IdempotencyStore;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.IdempotencyPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link IdempotencyPort} over the core {@link IdempotencyStore} (R41 / X.2.2).
 * <ul>
 *   <li>Step 1: the claim runs in its own short transaction ({@link TxSupport#newTransactions()}).</li>
 *   <li>Step 2: the command runs in a Spring transaction that the engine joins; for a 2xx result the
 *       {@code COMPLETED} update is the <b>last write of that same transaction</b> (L6, property P-1). A lost claim
 *       rolls the whole command back.</li>
 *   <li>Step 3: a non-2xx result rolls the command transaction back; deterministic 4xx (400, 404, 409 precondition,
 *       410, 422) are then stored as {@code FAILED_FINAL}; transient results (409 CONFLICT_RETRY_EXHAUSTED or
 *       IDEMPOTENCY_IN_PROGRESS, 429, 5xx) and 401/403 are not stored.</li>
 * </ul>
 */
public class JdbcIdempotencyAdapter implements IdempotencyPort {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcIdempotencyAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TxSupport tx;
    private final IdempotencyStore store;

    public JdbcIdempotencyAdapter(TxSupport tx, RestProperties props) {
        this.tx = tx;
        this.store = new IdempotencyStore(tx.dialect(), tx.newTransactions(), props.getNode());
    }

    @Override
    public Begin begin(RequestContext ctx, String commandType, String key, byte[] fingerprint) {
        String tenant = ctx.tenantId() == null ? "-" : ctx.tenantId();
        String principal = ctx.principalId() == null ? "-" : ctx.principalId();
        IdempotencyStore.BeginResult r;
        try {
            r = store.begin(tenant, commandType, key, principal, fingerprint);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("400 IDEMPOTENCY_KEY_INVALID")) {
                return new Rejected(400, "IDEMPOTENCY_KEY_INVALID", 0);
            }
            throw e;
        }
        return switch (r) {
            case IdempotencyStore.New n -> new Claimed(n.claim());
            case IdempotencyStore.Replay rp -> new Replay(rp.httpStatus(), rp.body(), rp.resourceRef(), rp.compacted(), null);
            case IdempotencyStore.Conflict cf -> new Rejected(cf.httpStatus(), cf.code(), 0);
            case IdempotencyStore.InProgress ip -> new Rejected(ip.httpStatus(), ip.code(), ip.retryAfterSeconds());
        };
    }

    /** Carries a checked exception out of the transaction callback. */
    private static final class Wrapped extends RuntimeException {
        Wrapped(Exception cause) {
            super(cause);
        }
    }

    @Override
    public Outcome execute(Claimed claimed, Command command) throws Exception {
        IdempotencyStore.Claim claim = (IdempotencyStore.Claim) claimed.handle();
        Outcome outcome;
        try {
            outcome = tx.inTxWithStatus(status -> {
                Outcome o;
                try {
                    o = command.run();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new Wrapped(e);
                }
                if (o.status() >= 200 && o.status() < 300) {
                    // L6, always the last write of the command transaction (P-1)
                    store.complete(tx.connection(), claim, o.status(), o.body(), o.resourceRef());
                } else {
                    status.setRollbackOnly();
                }
                return o;
            });
        } catch (IdempotencyStore.ClaimLostException e) {
            // another attempt took the claim over; this command was rolled back and the claim is not ours to delete
            throw ApiProblem.conflict("IDEMPOTENCY_IN_PROGRESS",
                    "Another attempt with the same Idempotency-Key took over; retry to receive its result");
        } catch (Wrapped w) {
            abandonQuietly(claim);
            throw (Exception) w.getCause();
        } catch (RuntimeException | Error e) {
            abandonQuietly(claim);
            throw e;
        }
        int s = outcome.status();
        if (s >= 200 && s < 300) {
            return outcome;
        }
        if (deterministic(s, outcome.body())) {
            try {
                store.failFinal(claim, s, outcome.body());
            } catch (IdempotencyStore.ClaimLostException e) {
                LOG.warn("idempotency claim lost while storing FAILED_FINAL for {}", claim);
            }
        } else {
            abandonQuietly(claim);
        }
        return outcome;
    }

    /** X.2.2.4 step 3 classification. */
    static boolean deterministic(int status, byte[] body) {
        return switch (status) {
            case 400, 404, 410, 422 -> true;
            case 409 -> {
                String code = problemCode(body);
                yield !"CONFLICT_RETRY_EXHAUSTED".equals(code) && !"IDEMPOTENCY_IN_PROGRESS".equals(code);
            }
            default -> false;
        };
    }

    static String problemCode(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(body);
            JsonNode code = n == null ? null : n.get("code");
            return code == null || code.isNull() ? null : code.asText();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private void abandonQuietly(IdempotencyStore.Claim claim) {
        try {
            store.abandon(claim);
        } catch (RuntimeException e) {
            LOG.warn("could not release idempotency claim {}: {}", claim, e.toString());
        }
    }

    /** Step 5: compacts and deletes expired rows; called by a leader-elected scheduler (R29). */
    public int cleanup() {
        return store.cleanup();
    }
}
