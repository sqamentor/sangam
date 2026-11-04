// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/**
 * Cumulative-threshold re-sanction (GP-42): adds {@code add} to the running total {@code totalVar} across loop
 * iterations (running bills, variations, extra items) and sets {@code thresholdCrossed} when the total exceeds
 * {@code limit} by more than {@code tolerancePct} percent (CPWD practice: a revised administrative approval is needed
 * when expenditure exceeds the AA amount by more than 10 %). Also sets {@code <totalVar>Pct} (total as % of limit).
 */
public class CumulativeThresholdDelegate implements JavaDelegate {

    private Expression totalVar;
    private Expression add;
    private Expression limit;
    private Expression tolerancePct;

    @Override
    public void execute(DelegateExecution ex) {
        String tv = Vars.field(totalVar, ex, "cumulativeAmount");
        BigDecimal total = Vars.decimal(ex, tv);
        if (total == null) {
            total = BigDecimal.ZERO;
        }
        BigDecimal a = Vars.decimal(Vars.field(add, ex, "0"));
        total = total.add(a);
        BigDecimal lim = Vars.decimal(Vars.field(limit, ex, "0"));
        BigDecimal tol = Vars.decimal(Vars.field(tolerancePct, ex, "10"));
        boolean crossed = lim.signum() > 0
                && total.compareTo(lim.multiply(BigDecimal.ONE.add(tol.movePointLeft(2)))) > 0;
        ex.setVariable(tv, total.doubleValue());
        ex.setVariable(tv + "Pct", lim.signum() == 0 ? 0d
                : total.multiply(BigDecimal.valueOf(100)).divide(lim, 2, RoundingMode.HALF_UP).doubleValue());
        ex.setVariable("thresholdCrossed", crossed);
        if (crossed) {
            Vars.audit(ex, "THRESHOLD_CROSSED", Vars.map("total", total.toPlainString(), "limit", lim.toPlainString(),
                    "tolerancePct", tol.toPlainString()));
        }
    }
}
