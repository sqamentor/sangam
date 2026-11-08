// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atomic, idempotent storage of ledger balances. {@link #post} applies every leg of a posting or none, idempotently by
 * {@code (tenant, reference)}: a repeated reference returns the first outcome (R41). Legs are applied in account-id
 * order to avoid deadlocks; guards are evaluated under the row lock, so concurrent requests can never oversell.
 */
public interface LedgerStore {

    /** Stored outcome of a posting. */
    record PostResult(boolean applied, boolean duplicate, String reason, List<Balance> after) {
    }

    /** One stored posting (the audit link: operation / audit reference and actor). */
    record Entry(String tenant, String reference, String operation, String auditRef, String actor, Instant at,
                 List<Movement> legs, Map<String, Object> info) {
    }

    PostResult post(Entry entry);

    Optional<Balance> get(AccountKey key);

    /** Accounts of a ledger in a tenant whose line / holder start with the given prefixes (null = any). */
    List<Balance> find(String tenant, String ledger, String period, String linePrefix, String holderPrefix, String stage);

    /** Sets state and attributes (freeze / expire / conditions); creates the account if missing. */
    Balance setState(AccountKey key, String currency, String state, Map<String, Object> attrs);

    Optional<Entry> entry(String tenant, String reference);

    /** Thread-safe in-memory store (development, tests, single-node demos). */
    final class InMemory implements LedgerStore {
        private final Map<String, Balance> balances = new ConcurrentHashMap<>();
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();
        private final Map<String, PostResult> results = new ConcurrentHashMap<>();

        @Override
        public synchronized PostResult post(Entry e) {
            String rk = e.tenant() + "|" + e.reference();
            PostResult prior = results.get(rk);
            if (prior != null) {
                return new PostResult(prior.applied(), true, prior.reason(), prior.after());
            }
            Map<String, Balance> next = new TreeMap<>();
            for (Movement m : e.legs()) {
                String id = m.key().id();
                Balance b = next.containsKey(id) ? next.get(id)
                        : balances.getOrDefault(id, Balance.empty(m.key(), m.currency()));
                if (m.requireOpen() && !Balance.OPEN.equals(b.state())) {
                    return remember(rk, e, new PostResult(false, false, "ACCOUNT_" + b.state(), List.of()), false);
                }
                Balance a = b.plus(m.deltas());
                if (m.guardAvailable() != null && a.available().compareTo(m.guardAvailable()) < 0
                        && m.availableDelta().signum() < 0) {
                    return remember(rk, e, new PostResult(false, false, "INSUFFICIENT_AVAILABLE", List.of(b)), false);
                }
                next.put(id, a);
            }
            balances.putAll(next);
            return remember(rk, e, new PostResult(true, false, null, new ArrayList<>(next.values())), true);
        }

        private PostResult remember(String rk, Entry e, PostResult r, boolean applied) {
            // failed postings are not remembered, so a retry after an allocation can succeed
            if (applied) {
                results.put(rk, r);
                entries.put(rk, e);
            }
            return r;
        }

        @Override
        public Optional<Balance> get(AccountKey key) {
            return Optional.ofNullable(balances.get(key.id()));
        }

        @Override
        public List<Balance> find(String tenant, String ledger, String period, String linePrefix, String holderPrefix,
                                  String stage) {
            List<Balance> out = new ArrayList<>();
            for (Balance b : balances.values()) {
                AccountKey k = b.key();
                if (k.tenant().equals(tenant) && k.ledger().equals(ledger) && (period == null || k.period().equals(period))
                        && (linePrefix == null || k.line().startsWith(linePrefix))
                        && (holderPrefix == null || k.holder().startsWith(holderPrefix))
                        && (stage == null || k.stage().equals(stage))) {
                    out.add(b);
                }
            }
            return out;
        }

        @Override
        public synchronized Balance setState(AccountKey key, String currency, String state, Map<String, Object> attrs) {
            Balance b = balances.getOrDefault(key.id(), Balance.empty(key, currency));
            Map<String, Object> a = new HashMap<>(b.attrs());
            if (attrs != null) {
                a.putAll(attrs);
            }
            Balance n = b.withState(state == null ? b.state() : state).withAttrs(a);
            balances.put(key.id(), n);
            return n;
        }

        @Override
        public Optional<Entry> entry(String tenant, String reference) {
            return Optional.ofNullable(entries.get(tenant + "|" + reference));
        }

        /** Sum of available over stripes / lots (helper for tests). */
        public BigDecimal totalAvailable(List<Balance> l) {
            return l.stream().map(Balance::available).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}
