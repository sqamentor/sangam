// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.packs;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.util.Optional;

import javax.sql.DataSource;

import org.sangam.core.engine.SangamClock;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.db.EngineDialect;
import org.sangam.packs.port.LedgerStore;

/**
 * JDBC {@link LedgerStore} (GP-11, GP-48) over {@code PACK_LEDGER_ACCOUNT} and {@code PACK_LEDGER_POSTING}, with the
 * semantics of the WS-PACKS simulator ({@code InMemoryLedgerStore}):
 * <ul>
 *   <li>postings are idempotent by reference per account: a repeat returns {@code applied=true,
 *       reason=DUPLICATE_REFERENCE} and changes nothing;</li>
 *   <li>{@code allowNegative=false} rejects a posting that would go below zero ({@code INSUFFICIENT_BALANCE});</li>
 *   <li>{@code reverse} undoes a posting by reference, once ({@code NOTHING_TO_REVERSE} otherwise); a reversed
 *       reference may be posted again;</li>
 *   <li>each account has an explicit {@code unit} column (fixed on first use): ISO 4217 currency = integer minor
 *       units of that currency, otherwise a quantity unit at {@value LedgerStore#QUANTITY_EXPONENT} decimal places
 *       (SD-398). A posting in another unit is refused ({@code UNIT_MISMATCH}).</li>
 * </ul>
 * Updates of one account are serialized by a row lock on the account ({@code FOR UPDATE}, dialect-specific), taken in
 * the caller's engine transaction when there is one. Amounts are BIGINT ({@code {money}}); never a binary float.
 */
public final class JdbcLedgerStore implements LedgerStore {

    private static final String[] ACCOUNT_COLS = {"tenant_id", "ledger", "account", "unit", "balance", "version", "updated_at"};

    private final ProductJdbc jdbc;
    private final SangamClock clock;
    private final Duration lockWait;

    /** Locked account row: its unit and balance in integer units. */
    private record Acct(String unit, long balance) {
        BigDecimal value() {
            return LedgerStore.amount(balance, unit);
        }
    }

    public JdbcLedgerStore(DataSource dataSource, SangamClock clock, Duration lockWait) {
        this.jdbc = new ProductJdbc(dataSource);
        this.clock = clock;
        this.lockWait = lockWait;
    }

    /** Sets an opening balance (seed data: budget allotment, leave credit) in {@code unit}. */
    public void open(String tenantId, String ledger, String account, String unit, BigDecimal balance) {
        long b = LedgerStore.units(balance, unit);
        jdbc.run(c -> {
            Acct a = lockAccount(c, tenantId, ledger, account, unit);
            if (!a.unit().equals(unit)) {
                throw new IllegalArgumentException("account " + account + " is in " + a.unit() + ", not " + unit);
            }
            setBalance(c, tenantId, ledger, account, b);
            return null;
        });
    }

    @Override
    public BigDecimal balance(String tenantId, String ledger, String account) {
        return jdbc.run(c -> Jdbc.one(c, "SELECT unit, balance FROM PACK_LEDGER_ACCOUNT WHERE tenant_id = ? AND ledger = ? "
                + "AND account = ?", rs -> new Acct(rs.getString(1), rs.getLong(2)), tenantId, ledger, account)
                .map(Acct::value).orElse(BigDecimal.ZERO));
    }

    @Override
    public Posting post(String tenantId, String ledger, String account, String unit, BigDecimal delta, String reference,
                        boolean allowNegative) {
        long d = LedgerStore.units(delta, unit); // reject a non-representable amount before touching the account
        return jdbc.run(c -> {
            Acct current = lockAccount(c, tenantId, ledger, account, unit);
            if (!current.unit().equals(unit)) {
                return new Posting(false, "UNIT_MISMATCH", current.value());
            }
            Optional<String> state = postingState(c, tenantId, ledger, account, reference);
            if (state.isPresent() && "POSTED".equals(state.get())) {
                return new Posting(true, "DUPLICATE_REFERENCE", current.value());
            }
            long after = Math.addExact(current.balance(), d);
            if (!allowNegative && after < 0) {
                return new Posting(false, "INSUFFICIENT_BALANCE", current.value());
            }
            setBalance(c, tenantId, ledger, account, after);
            if (state.isPresent()) { // reversed earlier: posted again
                Jdbc.update(c, "UPDATE PACK_LEDGER_POSTING SET delta = ?, state = 'POSTED', balance_after = ?, "
                                + "created_at = ?, reversed_at = NULL WHERE tenant_id = ? AND ledger = ? AND account = ? "
                                + "AND reference = ?", d, after, clock.now(), tenantId, ledger, account, reference);
            } else {
                Jdbc.update(c, "INSERT INTO PACK_LEDGER_POSTING (tenant_id, ledger, account, reference, delta, state, "
                                + "balance_after, created_at) VALUES (?,?,?,?,?,'POSTED',?,?)",
                        tenantId, ledger, account, reference, d, after, clock.now());
            }
            return new Posting(true, null, LedgerStore.amount(after, unit));
        });
    }

    @Override
    public Posting reverse(String tenantId, String ledger, String account, String reference) {
        return jdbc.run(c -> {
            Optional<Acct> found = lockExisting(c, tenantId, ledger, account);
            if (found.isEmpty()) {
                return new Posting(true, "NOTHING_TO_REVERSE", BigDecimal.ZERO);
            }
            Acct current = found.get();
            Optional<Long> delta = Jdbc.one(c, "SELECT delta FROM PACK_LEDGER_POSTING WHERE tenant_id = ? AND "
                            + "ledger = ? AND account = ? AND reference = ? AND state = 'POSTED'",
                    rs -> rs.getLong(1), tenantId, ledger, account, reference);
            if (delta.isEmpty()) {
                return new Posting(true, "NOTHING_TO_REVERSE", current.value());
            }
            long after = Math.subtractExact(current.balance(), delta.get());
            setBalance(c, tenantId, ledger, account, after);
            Jdbc.update(c, "UPDATE PACK_LEDGER_POSTING SET state = 'REVERSED', reversed_at = ? WHERE tenant_id = ? "
                    + "AND ledger = ? AND account = ? AND reference = ?", clock.now(), tenantId, ledger, account, reference);
            return new Posting(true, null, LedgerStore.amount(after, current.unit()));
        });
    }

    private Optional<Acct> lockExisting(Connection c, String tenantId, String ledger, String account) {
        EngineDialect d = jdbc.dialect(c);
        String select = d.lockingSelect("unit, balance", "PACK_LEDGER_ACCOUNT", "tenant_id = ? AND ledger = ? AND account = ?",
                lockWait);
        return Jdbc.one(c, select, rs -> new Acct(rs.getString(1), rs.getLong(2)), tenantId, ledger, account);
    }

    /** Row-locks the account (created in {@code unit} with balance 0 on first use) and returns it. */
    private Acct lockAccount(Connection c, String tenantId, String ledger, String account, String unit) {
        Optional<Acct> b = lockExisting(c, tenantId, ledger, account);
        if (b.isPresent()) {
            return b.get();
        }
        jdbc.dialect(c).insertIfAbsent(c, "PACK_LEDGER_ACCOUNT", ACCOUNT_COLS, "version", tenantId, ledger, account, unit, 0L, 0L,
                clock.now());
        return lockExisting(c, tenantId, ledger, account).orElseThrow();
    }

    private Optional<String> postingState(Connection c, String tenantId, String ledger, String account, String reference) {
        return Jdbc.one(c, "SELECT state FROM PACK_LEDGER_POSTING WHERE tenant_id = ? AND ledger = ? AND account = ? "
                + "AND reference = ?", rs -> rs.getString(1), tenantId, ledger, account, reference);
    }

    private void setBalance(Connection c, String tenantId, String ledger, String account, long balance) {
        Jdbc.update(c, "UPDATE PACK_LEDGER_ACCOUNT SET balance = ?, version = version + 1, updated_at = ? "
                + "WHERE tenant_id = ? AND ledger = ? AND account = ?", balance, clock.now(), tenantId, ledger, account);
    }
}
