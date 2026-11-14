// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Explicit mappings from the two power-limit contracts that existed before owner decision 142 to the versioned
 * {@link PowerDecision} contract. Callers migrate through these; neither mapping ever broadens authority.
 *
 * <p><b>Contract L (packs task listener)</b>: one DMN row with {@code powerLimit} (decimal), optional {@code allowed}
 * (boolean) and optional {@code noMonetaryLimit} (boolean). <b>Contract S (security service)</b>: every matched DMN
 * row with {@code maxAmount} (number, decimal string or {@code "UNLIMITED"}), optional {@code allowed} and
 * {@code ruleRef}, plus the seat holder's posting cap. Neither contract carries a currency: the caller passes the
 * currency the table is denominated in (the tenant profile currency).
 *
 * <p><b>Behaviour-difference inventory</b> (old behaviour, then new; each has a test in
 * {@code LegacyPowerContractsTest}):
 * <ol>
 *   <li><b>L, row states no power.</b> Old: {@code allowed} absent, no {@code powerLimit}, no {@code noMonetaryLimit}
 *       and no amount: permitted. New: DENY ("row states no power").</li>
 *   <li><b>L and S, missing amount.</b> Old: a null amount was always within power (L passed 0 to the table). New: a
 *       monetary operation without an amount is denied by the evaluator.</li>
 *   <li><b>L and S, negative amount.</b> Old: any negative amount was within any ceiling. New: compared by magnitude.</li>
 *   <li><b>L, ceiling and noMonetaryLimit together.</b> Old: noMonetaryLimit won (permissive). New: conflicting row,
 *       DENY.</li>
 *   <li><b>L, monetary-ness and case type.</b> Old: implied by an amount variable; case type read from a process
 *       variable with default {@code GENERAL}. New: from trusted {@link OperationMetadata} of the stored case type.</li>
 *   <li><b>S, several rows.</b> Old: the highest ceiling of all rows, and any UNLIMITED row, won (permissive). New:
 *       every row is a delegated limit and all must pass: the lowest ceiling binds, an UNLIMITED row never lifts
 *       another row's ceiling, and a row without {@code maxAmount} denies instead of being ignored.</li>
 *   <li><b>S, malformed maxAmount.</b> Old: a NumberFormatException left the command unaudited. New: DENY with a
 *       reason, audited.</li>
 *   <li><b>S, non-boolean allowed.</b> Old: ignored (the row counted as allowed). New: DENY (malformed).</li>
 *   <li><b>L and S, currency.</b> Old: none, any amount compared with any ceiling. New: ceilings carry the table
 *       currency; an amount in another currency is denied.</li>
 *   <li><b>L and S, evaluation failure.</b> Old: an exception (fail closed, but not recorded as a decision). New: DENY,
 *       with the audit payload.</li>
 *   <li><b>S, posting cap.</b> Old: applied as the minimum with the ceiling, also under UNLIMITED; skipped for system
 *       actors (no seat holder). New: a {@link PowerRuleSource.MandatoryCap} that UNLIMITED never removes. The mapper
 *       does not invent a cap for system actors (unchanged, not broadened); whether system actors need one is for
 *       the security service owner.</li>
 * </ol>
 */
public final class LegacyPowerContracts {

    public static final String UNLIMITED = "UNLIMITED";

    private LegacyPowerContracts() {
    }

    /** Contract L: the single row of the packs delegation-of-powers decision. */
    public static PowerRuleSource.DelegatedLimit listenerRow(String decisionKey, String version, Map<String, Object> row,
                                                             String currency) {
        return new PowerRuleSource.DelegatedLimit(decisionKey, version, listenerDecision(row, currency));
    }

    static PowerDecision listenerDecision(Map<String, Object> row, String currency) {
        if (row == null || row.isEmpty()) {
            return new PowerDecision.Deny("no delegation-of-powers row matched");
        }
        Object allowed = row.get("allowed");
        if (allowed != null && !(allowed instanceof Boolean)) {
            return new PowerDecision.Deny("malformed allowed value: " + allowed);
        }
        if (Boolean.FALSE.equals(allowed)) {
            return new PowerDecision.Deny("the row states no power (allowed = false)");
        }
        Object nml = row.get("noMonetaryLimit");
        if (nml != null && !(nml instanceof Boolean)) {
            return new PowerDecision.Deny("malformed noMonetaryLimit value: " + nml);
        }
        boolean unlimited = Boolean.TRUE.equals(nml);
        Object raw = row.get("powerLimit");
        if (unlimited && raw != null) {
            return new PowerDecision.Deny("conflicting row: powerLimit " + raw + " and noMonetaryLimit = true");
        }
        if (unlimited) {
            return new PowerDecision.Unlimited();
        }
        if (raw == null) {
            return new PowerDecision.Deny("the row states no power (no powerLimit, no noMonetaryLimit)");
        }
        return limited(raw, currency);
    }

    /** Contract S: every matched row of the security service's table, one delegated limit per row. */
    public static List<PowerRuleSource.DelegatedLimit> serviceRows(String tableKey, int version,
                                                                   List<Map<String, Object>> rows, String currency) {
        String v = String.valueOf(version);
        if (rows == null || rows.isEmpty()) {
            return List.of(new PowerRuleSource.DelegatedLimit(tableKey, v,
                    new PowerDecision.Deny("no delegation-of-powers row matched")));
        }
        List<PowerRuleSource.DelegatedLimit> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Object ref = row == null ? null : row.get("ruleRef");
            String id = tableKey + "#" + (ref == null ? "row" + (i + 1) : ref.toString());
            out.add(new PowerRuleSource.DelegatedLimit(id, v, serviceDecision(row, currency)));
        }
        return out;
    }

    static PowerDecision serviceDecision(Map<String, Object> row, String currency) {
        if (row == null) {
            return new PowerDecision.Deny("malformed row");
        }
        Object allowed = row.get("allowed");
        if (allowed != null && !(allowed instanceof Boolean)) {
            return new PowerDecision.Deny("malformed allowed value: " + allowed);
        }
        if (Boolean.FALSE.equals(allowed)) {
            return new PowerDecision.Deny("the row states no power (allowed = false)");
        }
        Object m = row.get("maxAmount");
        if (m == null) {
            return new PowerDecision.Deny("the row states no power (no maxAmount)");
        }
        if (UNLIMITED.equals(m.toString().trim().toUpperCase(Locale.ROOT))) {
            return new PowerDecision.Unlimited();
        }
        return limited(m, currency);
    }

    /** Contract S posting cap of the seat holder; empty when the holder has none. */
    public static Optional<PowerRuleSource.MandatoryCap> postingCap(String seatId, String version, BigDecimal cap,
                                                                    String currency) {
        return cap == null ? Optional.empty()
                : Optional.of(new PowerRuleSource.MandatoryCap("posting-cap:" + seatId, version, cap, currency));
    }

    private static PowerDecision limited(Object raw, String currency) {
        BigDecimal v;
        try {
            v = raw instanceof BigDecimal b ? b : new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException e) {
            return new PowerDecision.Deny("malformed ceiling: " + raw);
        }
        if (v.signum() < 0) {
            return new PowerDecision.Deny("malformed ceiling (negative): " + v.toPlainString());
        }
        if (currency == null || !PowerDecision.CURRENCY.matcher(currency).matches()) {
            return new PowerDecision.Deny("the table currency is unknown or not ISO 4217: " + currency);
        }
        return new PowerDecision.Limited(v, currency);
    }
}
