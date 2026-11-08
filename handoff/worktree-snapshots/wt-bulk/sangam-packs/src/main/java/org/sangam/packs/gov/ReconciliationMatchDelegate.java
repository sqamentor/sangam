// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.gov;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;

import org.sangam.packs.core.Vars;
import org.sangam.packs.port.ConnectorRequest;
import org.sangam.packs.port.ConnectorResult;
import org.sangam.packs.port.PackRuntime;

/**
 * File-based reconciliation (GP-31 bank / treasury scroll against challans and receipts; GP-55 book balance against
 * physical count). Field {@code mode}:
 * <ul>
 *   <li>{@code SCROLL}: the expected records {@code expectedJson} (JSON array with {@code key}, {@code amount}) are
 *       matched with the records the {@code connector} returns for the scroll date (default {@code treasury.scroll});
 *       a record matches on key and amount; the simulator, which has no scroll, echoes the expected records;</li>
 *   <li>{@code STOCK}: {@code bookBalances} and {@code physicalCounts} (JSON objects item to quantity) are compared;
 *       {@code unitRates} (JSON) values the shortages.</li>
 * </ul>
 * Outputs {@code matchedCount}, {@code discrepancyCount} and {@code items} (JSON array of discrepancies with a
 * {@code key}, ready for the per-item discrepancy cases of the GP-09 pattern).
 */
public class ReconciliationMatchDelegate implements JavaDelegate {

    private Expression mode;
    private Expression connector;

    @Override
    public void execute(DelegateExecution ex) {
        List<Map<String, Object>> disc = new ArrayList<>();
        int matched = 0;
        if ("STOCK".equals(Vars.field(mode, ex, "SCROLL"))) {
            Map<String, Object> book = Vars.jsonMap(ex, "bookBalances");
            Map<String, Object> phys = Vars.jsonMap(ex, "physicalCounts");
            Map<String, Object> rates = Vars.jsonMap(ex, "unitRates");
            for (Map.Entry<String, Object> e : book.entrySet()) {
                BigDecimal b = Vars.decimal(e.getValue());
                BigDecimal p = Vars.decimal(phys.getOrDefault(e.getKey(), 0));
                if (b.compareTo(p) == 0) {
                    matched++;
                    continue;
                }
                BigDecimal diff = p.subtract(b);
                BigDecimal rate = Vars.decimal(rates.getOrDefault(e.getKey(), 0));
                disc.add(Vars.map("key", e.getKey(), "book", b.toPlainString(), "physical", p.toPlainString(),
                        "difference", diff.toPlainString(), "value", diff.multiply(rate).abs().toPlainString(),
                        "kind", diff.signum() < 0 ? "SHORTAGE" : "EXCESS"));
            }
        } else {
            List<Map<String, Object>> expected = Vars.jsonList(ex, "expectedJson");
            String c = Vars.field(connector, ex, "treasury.scroll");
            Map<String, Object> p = Vars.map("scrollDate", Vars.str(ex, "scrollDate"));
            ConnectorResult r = PackRuntime.gateway().query(new ConnectorRequest(Vars.tenant(ex), c, "fetchScroll",
                    null, ex.getProcessBusinessKey(), ex.getProcessInstanceId(), Vars.withDirective(ex, c, p),
                    Vars.hash(p)));
            Object recs = r.data().get("records");
            Map<String, BigDecimal> scroll = new HashMap<>();
            if (recs instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) {
                        scroll.put(String.valueOf(m.get("key")), Vars.decimal(m.get("amount")));
                    }
                }
            } else {
                for (Map<String, Object> e : expected) {
                    scroll.put(String.valueOf(e.get("key")), Vars.decimal(e.get("amount")));
                }
            }
            for (Map<String, Object> e : expected) {
                String k = String.valueOf(e.get("key"));
                BigDecimal amt = Vars.decimal(e.get("amount"));
                BigDecimal got = scroll.remove(k);
                if (got != null && amt != null && got.compareTo(amt) == 0) {
                    matched++;
                } else {
                    disc.add(Vars.map("key", k, "expected", amt == null ? null : amt.toPlainString(),
                            "found", got == null ? null : got.toPlainString(),
                            "kind", got == null ? "NOT_IN_SCROLL" : "AMOUNT_MISMATCH"));
                }
            }
            for (Map.Entry<String, BigDecimal> e : scroll.entrySet()) {
                disc.add(Vars.map("key", e.getKey(), "found", e.getValue().toPlainString(), "kind",
                        "NOT_IN_BOOKS"));
            }
        }
        ex.setVariable("matchedCount", matched);
        ex.setVariable("discrepancyCount", disc.size());
        ex.setVariable("items", Vars.toJson(disc));
        Vars.audit(ex, "RECONCILIATION_RUN", Vars.map("matched", matched, "discrepancies", disc.size()));
    }
}
