// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing;

import org.sangam.config.catalogue.NumberSeries;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Numbers of billing documents, keyed by the document's identity (SL-130, SL-131; owner decision M-002): the first
 * {@link #issue} of a document draws its number and stores it with the document's plan; every later {@link #issue} of
 * the same document (a retried step, a re-invoice) returns that number and plan, draws nothing and does not call the
 * plan supplier again. A number is therefore never burned by a retry, and a retried collection replays the allocation
 * it planned the first time instead of re-planning over the reduced arrears.
 *
 * <p>Document identity: invoice = tenant + ledger + consumer + cycle; receipt = tenant + paymentRef. The production
 * implementation is {@code numbering.RegistryDocumentNumbers} (the registry {@code NumberingService}, GAP_FREE, with
 * the number recorded against the document as {@code ref_type} / {@code ref_id}, and the plan in {@code BIL_DOCUMENT}).
 */
public interface DocumentNumbers {

    /** Billing document kinds, each with its own series. */
    enum Kind {
        INVOICE("billing.invoice"), RECEIPT("billing.receipt");

        private final String refType;

        Kind(String refType) {
            this.refType = refType;
        }

        /** The {@code ref_type} recorded with the allocated number. */
        public String refType() {
            return refType;
        }
    }

    /**
     * One document to number.
     *
     * @param key          the document identity within the tenant and kind (see {@link #invoiceKey}, {@link #receiptKey})
     * @param scope        the series scope: the cycle of an invoice, the collection channel of a receipt
     * @param businessDate the tenant business date (selects the counter period)
     */
    record Request(String tenant, Kind kind, String key, String scope, LocalDate businessDate, String actor) {
        public Request {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(businessDate, "businessDate");
        }
    }

    /** A document's number and its stored plan; {@code first} is true only for the call that drew the number. */
    record Issued(String number, Map<String, Object> plan, boolean first) {
        public Issued {
            Objects.requireNonNull(number, "number");
            plan = plan == null ? Map.of() : plan;
        }
    }

    /**
     * Returns the number and plan of the document, drawing and storing them on the first call only.
     *
     * @param plan computes the document's plan (e.g. the receipt's allocation); called only when the document has no
     *             number yet, and the plan stored by the call that wins is the one every call returns
     */
    Issued issue(Request request, Supplier<Map<String, Object>> plan);

    /**
     * Invoice identity: ledger + consumer + cycle (the tenant is the request's own), as the canonical JSON array
     * {@code ["<ledger>","<consumer>","<cycle>"]}, so no part can run into the next.
     */
    static String invoiceKey(String ledgerId, String consumer, String cycle) {
        return org.sangam.core.layer.CanonicalJson.write(java.util.List.of(part(ledgerId), part(consumer), part(cycle)));
    }

    /** Receipt identity: the payment reference (the tenant is the request's own). */
    static String receiptKey(String paymentRef) {
        if (paymentRef == null || paymentRef.isBlank()) {
            throw new IllegalArgumentException("a receipt needs a paymentRef (its document identity)");
        }
        return paymentRef;
    }

    private static String part(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("an invoice needs ledger, consumer and cycle (its document identity)");
        }
        return s;
    }

    /**
     * Over a config {@link NumberSeries}, keyed in memory: single node, for tests and tools only (a restart or a second
     * node forgets the issued numbers). Numbers keep the config-series format: {@code INV/<cycle>/<n>} from series
     * {@code INVOICE} (unit {@code -}, period = cycle) and {@code RCP/<n>} from series {@code RECEIPT} (unit = channel,
     * period = business date).
     */
    static DocumentNumbers overSeries(NumberSeries series) {
        Objects.requireNonNull(series, "series");
        Map<String, Issued> issued = new ConcurrentHashMap<>();
        return (r, plan) -> {
            String id = r.tenant() + "\u0001" + r.kind() + "\u0001" + r.key();
            Issued prior = issued.get(id);
            if (prior != null) {
                return new Issued(prior.number(), prior.plan(), false);
            }
            synchronized (issued) {
                prior = issued.get(id);
                if (prior != null) {
                    return new Issued(prior.number(), prior.plan(), false);
                }
                Map<String, Object> p = plan.get();
                String number = switch (r.kind()) {
                    case INVOICE -> "INV/" + r.scope() + "/" + series.next(r.tenant(), "INVOICE", "-", r.scope());
                    case RECEIPT -> "RCP/" + series.next(r.tenant(), "RECEIPT", r.scope(), r.businessDate().toString());
                };
                Issued first = new Issued(number, p, true);
                issued.put(id, first);
                return first;
            }
        };
    }
}
