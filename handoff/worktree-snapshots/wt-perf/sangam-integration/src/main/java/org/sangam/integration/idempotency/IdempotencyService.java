// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.IdempotencyStore;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.BackpressureGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API idempotency lifecycle (SANGAM.md X.2.2, R41, T01, T55–T60) on top of the proven {@link IdempotencyStore}.
 * Framework-neutral; {@link IdempotencyServletFilter} and WS-REST's Spring interceptor adapt it to HTTP.
 *
 * <ol>
 *   <li>Missing key: 400 {@code IDEMPOTENCY_KEY_REQUIRED} when the profile requires one; otherwise the command runs
 *       without replay protection. Invalid key: 400 {@code IDEMPOTENCY_KEY_INVALID}.</li>
 *   <li>Claim (own short transaction): new → execute; same scope with a different fingerprint / principal → 422;
 *       live claim → 409 {@code IDEMPOTENCY_IN_PROGRESS} with {@code Retry-After: 1}, or wait up to
 *       {@code Prefer: wait=N} seconds polling the row; expired claim → takeover.</li>
 *   <li>Execute: the command calls {@link IdempotencyContext#complete} as the last write of its engine transaction
 *       (P-1).</li>
 *   <li>Classify: 2xx must have completed atomically; deterministic 4xx (400, 404, 409 precondition, 410, 422) are
 *       stored {@code FAILED_FINAL}; transient (409 retry-exhausted, 429, 5xx, 503 BACKPRESSURE) and 401/403 delete the
 *       claim so the next try executes.</li>
 *   <li>Replay: re-authorization and field masks via {@link ReplayAuthorizer}; {@code Idempotent-Replayed: true} or
 *       {@code compacted}; {@code Idempotency-Key-Expires}.</li>
 * </ol>
 */
public final class IdempotencyService {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyService.class);

    /** The command being protected. It runs its own engine transaction and calls {@code ctx.complete} inside it. */
    @FunctionalInterface
    public interface Command {
        IdempotentResponse execute(IdempotencyContext ctxOrNull);
    }

    /** A command result plus the error code needed for classification (e.g. {@code CONFLICT_RETRY_EXHAUSTED}). */
    public static final class CommandFailure extends RuntimeException {
        private final IdempotentResponse response;
        private final String code;

        public CommandFailure(IdempotentResponse response, String code) {
            super(code);
            this.response = response;
            this.code = code;
        }

        public IdempotentResponse response() {
            return response;
        }

        public String code() {
            return code;
        }
    }

    private final IdempotencyStore store;
    private final Dialect dialect;
    private final Transactions tx;
    private final IdempotencyProfile profile;
    private final ReplayAuthorizer authorizer;
    private final boolean strictAtomic;
    private final Map<String, AtomicLong> metrics = new ConcurrentHashMap<>();

    /**
     * @param strictAtomic when true, a 2xx command that did not call {@link IdempotencyContext#complete} inside its
     *                     transaction is treated as a programming error (P-1, X.2.2.7 rule 1): the claim is abandoned
     *                     and the response returned without protection, and an error is logged. When false, the result
     *                     is stored afterwards in its own transaction (not atomic; development only).
     */
    public IdempotencyService(IdempotencyStore store, Dialect dialect, Transactions tx, IdempotencyProfile profile,
                              ReplayAuthorizer authorizer, boolean strictAtomic) {
        this.store = store;
        this.dialect = dialect;
        this.tx = tx;
        this.profile = profile == null ? IdempotencyProfile.optional() : profile;
        this.authorizer = authorizer == null ? ReplayAuthorizer.ALLOW_ALL : authorizer;
        this.strictAtomic = strictAtomic;
    }

    public IdempotentResponse execute(IdempotentRequest req, Command command) {
        if (req.idempotencyKey() == null || req.idempotencyKey().isEmpty()) {
            if (profile.requires(req.commandType())) {
                count("api_idempotency_conflicts_total{reason=missing}");
                return IdempotentResponse.problem(400, "IDEMPOTENCY_KEY_REQUIRED",
                        "command " + req.commandType() + " requires an Idempotency-Key in profile " + profile.name(), null);
            }
            return command.execute(null);
        }
        byte[] fp;
        IdempotencyStore.BeginResult begin;
        try {
            fp = IdempotencyStore.fingerprint(req.commandType(), req.method(), req.pathTemplate(), req.pathParams(),
                    semanticParams(req), req.body());
            begin = beginWithWait(req, fp);
        } catch (IllegalArgumentException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("IDEMPOTENCY_KEY_INVALID")) {
                return IdempotentResponse.problem(400, "IDEMPOTENCY_KEY_INVALID", msg.replace("400 IDEMPOTENCY_KEY_INVALID: ", ""), null);
            }
            return IdempotentResponse.problem(400, "INVALID_REQUEST", msg, null);
        }
        return switch (begin) {
            case IdempotencyStore.Conflict c -> {
                count("api_idempotency_conflicts_total{reason=" + c.code() + "}");
                yield IdempotentResponse.problem(c.httpStatus(), c.code(), null, null);
            }
            case IdempotencyStore.InProgress p -> {
                count("api_idempotency_in_progress_total");
                yield IdempotentResponse.problem(p.httpStatus(), p.code(), "another attempt holds this key",
                        Map.of("Retry-After", Integer.toString(p.retryAfterSeconds())));
            }
            case IdempotencyStore.Replay r -> replay(req, r);
            case IdempotencyStore.New n -> run(req, n.claim(), command);
        };
    }

    private IdempotencyStore.BeginResult beginWithWait(IdempotentRequest req, byte[] fp) {
        long until = System.currentTimeMillis() + req.preferWaitSeconds() * 1000L;
        while (true) {
            IdempotencyStore.BeginResult r = store.begin(req.tenantId(), req.commandType(), req.idempotencyKey(), req.principalId(), fp);
            if (!(r instanceof IdempotencyStore.InProgress) || System.currentTimeMillis() >= until) {
                return r;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return r;
            }
        }
    }

    private IdempotentResponse run(IdempotentRequest req, IdempotencyStore.Claim claim, Command command) {
        IdempotencyContext ctx = new IdempotencyContext(store, claim);
        IdempotencyContext.bind(ctx);
        IdempotentResponse resp;
        try {
            resp = command.execute(ctx);
        } catch (IdempotencyStore.ClaimLostException e) {
            count("api_idempotency_in_progress_total");
            return IdempotentResponse.problem(409, "IDEMPOTENCY_IN_PROGRESS", "the claim was taken over by another attempt",
                    Map.of("Retry-After", "1"));
        } catch (BackpressureGate.BackpressureException e) {
            ctx.rolledBack();
            store.abandon(claim);
            return IdempotentResponse.problem(503, "BACKPRESSURE", e.getMessage(),
                    Map.of("Retry-After", Long.toString(e.retryAfter().toSeconds())));
        } catch (CommandFailure f) {
            ctx.rolledBack();
            classifyFailure(claim, f.response(), f.code());
            return f.response();
        } catch (RuntimeException e) {
            ctx.rolledBack();
            safeAbandon(claim);
            throw e;
        } finally {
            IdempotencyContext.unbind();
        }
        int s = resp.status();
        if (s >= 200 && s < 300) {
            if (!ctx.completed()) {
                if (strictAtomic) {
                    LOG.error("command {} returned {} without completing its idempotency claim in its transaction (P-1)",
                            req.commandType(), s);
                    safeAbandon(claim);
                } else {
                    tx.inTransaction(c -> {
                        store.complete(c, claim, s, resp.body(), resp.headers().get("Location"));
                        return null;
                    });
                }
            }
            return resp;
        }
        classifyFailure(claim, resp, null);
        return resp;
    }

    /** X.2.2.4 step 3. */
    private void classifyFailure(IdempotencyStore.Claim claim, IdempotentResponse resp, String code) {
        if (isFinal(resp.status(), code)) {
            try {
                store.failFinal(claim, resp.status(), resp.body());
            } catch (IdempotencyStore.ClaimLostException e) {
                LOG.debug("claim lost while storing FAILED_FINAL", e);
            }
        } else {
            safeAbandon(claim);
        }
    }

    static boolean isFinal(int status, String code) {
        return switch (status) {
            case 400, 404, 410, 422 -> true;
            case 409 -> code == null || !(code.equals("CONFLICT_RETRY_EXHAUSTED") || code.equals("IDEMPOTENCY_IN_PROGRESS")
                    || code.equals("STALE_LEASE"));
            default -> false;   // 401/403 (never stored), 408, 429, 5xx, 503 BACKPRESSURE: transient
        };
    }

    private void safeAbandon(IdempotencyStore.Claim claim) {
        try {
            store.abandon(claim);
        } catch (RuntimeException e) {
            LOG.warn("cannot abandon idempotency claim {}; it expires after the claim TTL", claim, e);
        }
    }

    private IdempotentResponse replay(IdempotentRequest req, IdempotencyStore.Replay r) {
        if (!authorizer.mayReplay(req.tenantId(), req.principalId(), req.commandType(), r.resourceRef())) {
            count("api_idempotent_replays_total{command=" + req.commandType() + ",kind=forbidden}");
            return IdempotentResponse.problem(403, "FORBIDDEN", null, null);
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Idempotent-Replayed", r.compacted() ? "compacted" : "true");
        expiry(req).ifPresent(t -> h.put("Idempotency-Key-Expires", t.toString()));
        if (r.resourceRef() != null) {
            h.put("Location", r.resourceRef());
        }
        count("api_idempotent_replays_total{command=" + req.commandType() + ",kind=" + (r.compacted() ? "compacted" : "full") + "}");
        byte[] body = r.compacted() || r.body() == null ? new byte[0]
                : authorizer.mask(req.tenantId(), req.principalId(), r.resourceRef(), r.body());
        String ct = r.httpStatus() >= 400 ? "application/problem+json" : "application/json";
        return new IdempotentResponse(r.httpStatus(), body, body.length == 0 ? null : ct, h);
    }

    private java.util.Optional<Instant> expiry(IdempotentRequest req) {
        try {
            return tx.inTransaction(c -> Jdbc.one(c, "SELECT response_expires_at FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND command_type=?"
                    + " AND idem_key=?", rs -> Jdbc.instant(rs, "response_expires_at"), req.tenantId(), req.commandType(), req.idempotencyKey()));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    private static Map<String, Object> semanticParams(IdempotentRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (req.semanticQueryParams() != null) {
            m.putAll(req.semanticQueryParams());
        }
        if (req.ifMatch() != null) {
            m.put("header:If-Match", req.ifMatch());
        }
        if (req.actingForSeat() != null) {
            m.put("header:X-Sangam-Acting-For-Seat", req.actingForSeat());
        }
        return m;
    }

    /** Step 5 cleanup (leader-elected, R29). */
    public int cleanup() {
        return store.cleanup();
    }

    /** X.2.2.7 rule 4 counters. */
    public Map<String, Long> metrics() {
        Map<String, Long> out = new java.util.TreeMap<>();
        metrics.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    private void count(String name) {
        metrics.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    Dialect dialect() {
        return dialect;
    }
}
