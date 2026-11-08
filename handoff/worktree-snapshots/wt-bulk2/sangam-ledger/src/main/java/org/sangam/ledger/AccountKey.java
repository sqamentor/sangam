// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.core.layer.Hashes;

import java.util.Objects;

/**
 * Identity of one ledger account.
 *
 * @param tenant  configuration scope / engine tenant
 * @param ledger  ledger definition id
 * @param period  availability period label (fiscal year, multi-year window, {@code NOYEAR}, or an extra period such
 *                as {@code 2026-27/P13})
 * @param line    the classification line (a composite code, an item code for QUANTITY, a demand head for RECEIVABLE)
 * @param holder  the holder (office, controlling officer, location, customer)
 * @param stage   stage name from the definition
 * @param sub     optional sub-dimension: lot / batch, funding source (e.g. a financing agreement), currency, purpose
 */
public record AccountKey(String tenant, String ledger, String period, String line, String holder, String stage,
                         String sub) implements Comparable<AccountKey> {

    public AccountKey {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(ledger, "ledger");
        period = period == null ? "-" : period;
        line = line == null ? "-" : line;
        holder = holder == null ? "-" : holder;
        stage = stage == null ? "-" : stage;
        sub = sub == null ? "-" : sub;
    }

    public AccountKey atStage(String s, String newHolder) {
        return new AccountKey(tenant, ledger, period, line, newHolder, s, sub);
    }

    public AccountKey withLine(String newLine) {
        return new AccountKey(tenant, ledger, period, newLine, holder, stage, sub);
    }

    public AccountKey withSub(String newSub) {
        return new AccountKey(tenant, ledger, period, line, holder, stage, newSub);
    }

    public AccountKey withPeriod(String p) {
        return new AccountKey(tenant, ledger, p, line, holder, stage, sub);
    }

    /** Stable 64-hex account id (primary key of the balance row). */
    public String id() {
        return Hashes.hex(Hashes.sha256(String.join("\u0001", tenant, ledger, period, line, holder, stage, sub)));
    }

    @Override
    public int compareTo(AccountKey o) {
        return id().compareTo(o.id());
    }

    @Override
    public String toString() {
        return tenant + "/" + ledger + "/" + period + "/" + line + "/" + holder + "/" + stage + (sub.equals("-") ? "" : "/" + sub);
    }
}
