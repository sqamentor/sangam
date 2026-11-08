// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ports of the ledger that other workstreams implement (all optional; defaults are no-ops or config-backed). */
public final class LedgerPorts {

    private LedgerPorts() {
    }

    /** Exchange rates by rate type (e.g. OFFICIAL, MARKET, BUDGET); backed by config or a rate connector. */
    @FunctionalInterface
    public interface RatePort {
        Optional<BigDecimal> rate(String from, String to, String rateType, LocalDate asOf);
    }

    /**
     * SHADOW mode: the external system of record (an existing FMIS, core banking, ERP). The ledger mirrors postings
     * and asks the external system for availability; differences are found by {@link #balances} reconciliation.
     */
    public interface ExternalLedgerPort {
        /** External availability of an account, or empty when unknown (then the mirror decides). */
        Optional<BigDecimal> available(AccountKey key);

        /** External balances for reconciliation of a ledger / period. */
        Map<AccountKey, BigDecimal> balances(String tenant, String ledger, String period);
    }

    /** A DMN / rule evaluation hook: input variables in, output variables out (e.g. transfer rules, conditions). */
    @FunctionalInterface
    public interface DecisionPort {
        List<Map<String, Object>> evaluate(String tenant, String decisionKey, Map<String, Object> variables);
    }

    /** One accounting entry produced by the event mapping (cash or accrual basis). */
    public record AccountingEntry(String tenant, String reference, String event, String basis, String debit, String credit,
                                  BigDecimal amount, String currency, BigDecimal functionalAmount) {
    }

    /** Receives accounting entries (GL connector export, or a table read by it). */
    @FunctionalInterface
    public interface AccountingSink {
        void accept(List<AccountingEntry> entries);
    }

    /** Limit breach events (PFM-039). */
    @FunctionalInterface
    public interface BreachSink {
        void breach(Balance account, LedgerRequest cause);
    }

    /** Audit link: every applied posting is reported with its reference and audit reference (R42 chain). */
    @FunctionalInterface
    public interface AuditLink {
        void posted(String tenant, String reference, String operation, String auditRef, String actor,
                    Map<String, Object> summary);
    }
}
