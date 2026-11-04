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
 *       reference may be posted again.</li>
 * </ul>
 * Updates of one account are serialized by a row lock on the account ({@code FOR UPDATE}, dialect-specific), taken in
 * the caller's engine transaction when there is one. Amounts are {@code NUMERIC(19,4)} (R33: never a binary float).
 */
public final class JdbcLedgerStore implements LedgerStore {

    private static final String[] ACCOUNT_COLS = {"tenant_id", "ledger", "account", "balance", "version", "updated_at"};

    private final ProductJdbc jdbc;
    private final SangamClock clock;
    private final Duration lockWait;

    public JdbcLedgerStore(DataSource dataSource, SangamClock clock, Duration lockWait) {
        this.jdbc = new ProductJdbc(dataSource);
        this.clock = clock;
        this.lockWait = lockWait;
    }

    /** Sets an opening balance (seed data: budget allotment, leave credit). */
    public void open(String tenantId, String ledger, String account, BigDecimal balance) {
        jdbc.run(c -> {
            lockAccount(c, tenantId, ledger, account);
            Jdbc.update(c, "UPDATE PACK_LEDGER_ACCOUNT SET balance = ?, version = version + 1, updated_at = ? "
                    + "WHERE tenant_id = ? AND ledger = ? AND account = ?", balance, clock.now(), tenantId, ledger, account);
            return null;
        });
    }

    @Override
    public BigDecimal balance(String tenantId, String ledger, String account) {
        return jdbc.run(c -> Jdbc.one(c, "SELECT balance FROM PACK_LEDGER_ACCOUNT WHERE tenant_id = ? AND ledger = ? "
                + "AND account = ?", rs -> rs.getBigDecimal(1), tenantId, ledger, account).orElse(BigDecimal.ZERO));
    }

    @Override
    public Posting post(String tenantId, String ledger, String account, BigDecimal delta, String reference,
                        boolean allowNegative) {
        return jdbc.run(c -> {
            BigDecimal current = lockAccount(c, tenantId, ledger, account);
            Optional<String> state = postingState(c, tenantId, ledger, account, reference);
            if (state.isPresent() && "POSTED".equals(state.get())) {
                return new Posting(true, "DUPLICATE_REFERENCE", current);
            }
            BigDecimal after = current.add(delta);
            if (!allowNegative && after.signum() < 0) {
                return new Posting(false, "INSUFFICIENT_BALANCE", current);
            }
            setBalance(c, tenantId, ledger, account, after);
            if (state.isPresent()) { // reversed earlier: posted again
                Jdbc.update(c, "UPDATE PACK_LEDGER_POSTING SET delta = ?, state = 'POSTED', balance_after = ?, "
                                + "created_at = ?, reversed_at = NULL WHERE tenant_id = ? AND ledger = ? AND account = ? "
                                + "AND reference = ?", delta, after, clock.now(), tenantId, ledger, account, reference);
            } else {
                Jdbc.update(c, "INSERT INTO PACK_LEDGER_POSTING (tenant_id, ledger, account, reference, delta, state, "
                                + "balance_after, created_at) VALUES (?,?,?,?,?,'POSTED',?,?)",
                        tenantId, ledger, account, reference, delta, after, clock.now());
            }
            return new Posting(true, null, after);
        });
    }

    @Override
    public Posting reverse(String tenantId, String ledger, String account, String reference) {
        return jdbc.run(c -> {
            BigDecimal current = lockAccount(c, tenantId, ledger, account);
            Optional<BigDecimal> delta = Jdbc.one(c, "SELECT delta FROM PACK_LEDGER_POSTING WHERE tenant_id = ? AND "
                            + "ledger = ? AND account = ? AND reference = ? AND state = 'POSTED'",
                    rs -> rs.getBigDecimal(1), tenantId, ledger, account, reference);
            if (delta.isEmpty()) {
                return new Posting(true, "NOTHING_TO_REVERSE", current);
            }
            BigDecimal after = current.subtract(delta.get());
            setBalance(c, tenantId, ledger, account, after);
            Jdbc.update(c, "UPDATE PACK_LEDGER_POSTING SET state = 'REVERSED', reversed_at = ? WHERE tenant_id = ? "
                    + "AND ledger = ? AND account = ? AND reference = ?", clock.now(), tenantId, ledger, account, reference);
            return new Posting(true, null, after);
        });
    }

    /** Row-locks the account (created with balance 0 on first use) and returns its balance. */
    private BigDecimal lockAccount(Connection c, String tenantId, String ledger, String account) {
        EngineDialect d = jdbc.dialect(c);
        String select = d.lockingSelect("balance", "PACK_LEDGER_ACCOUNT", "tenant_id = ? AND ledger = ? AND account = ?",
                lockWait);
        Optional<BigDecimal> b = Jdbc.one(c, select, rs -> rs.getBigDecimal(1), tenantId, ledger, account);
        if (b.isPresent()) {
            return b.get();
        }
        d.insertIfAbsent(c, "PACK_LEDGER_ACCOUNT", ACCOUNT_COLS, "version", tenantId, ledger, account, BigDecimal.ZERO, 0L,
                clock.now());
        return Jdbc.one(c, select, rs -> rs.getBigDecimal(1), tenantId, ledger, account).orElseThrow();
    }

    private Optional<String> postingState(Connection c, String tenantId, String ledger, String account, String reference) {
        return Jdbc.one(c, "SELECT state FROM PACK_LEDGER_POSTING WHERE tenant_id = ? AND ledger = ? AND account = ? "
                + "AND reference = ?", rs -> rs.getString(1), tenantId, ledger, account, reference);
    }

    private void setBalance(Connection c, String tenantId, String ledger, String account, BigDecimal balance) {
        Jdbc.update(c, "UPDATE PACK_LEDGER_ACCOUNT SET balance = ?, version = version + 1, updated_at = ? "
                + "WHERE tenant_id = ? AND ledger = ? AND account = ?", balance, clock.now(), tenantId, ledger, account);
    }
}
