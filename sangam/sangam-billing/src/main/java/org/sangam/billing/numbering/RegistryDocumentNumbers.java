// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing.numbering;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.billing.DocumentNumbers;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.numbering.NumberingService;

import java.io.IOException;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@link DocumentNumbers} over the registry {@link NumberingService} (N09; owner decision M-002 for SL-130 / SL-131).
 *
 * <p>One row per document in {@code BIL_DOCUMENT}, primary key (tenant, kind, document identity), holding the number
 * and the plan. The first {@link #issue} of a document runs one transaction: it claims the row (insert-if-absent), then
 * allocates the number from the GAP_FREE series with the document recorded as {@code ref_type} ({@code billing.invoice}
 * / {@code billing.receipt}) and {@code ref_id} (the identity), then writes the number to the row. The counter
 * increment, the allocation row and the document row commit together or not at all, so a failure burns no number. A
 * concurrent first issue of the same document waits on the claimed key and, when the winner commits, reads the
 * winner's number and plan; it allocates nothing. Every later {@link #issue} reads the stored row.
 *
 * <p>Series: {@code INVOICE} (scope = cycle) and {@code RECEIPT} (scope = channel, period = business date). A tenant may
 * define them in the registry; a series the tenant has not defined is defined on first use from the definition given
 * to the constructor ({@link #DEFAULT_INVOICE_SERIES}, {@link #DEFAULT_RECEIPT_SERIES}: GAP_FREE, the same text as the
 * config-series numbers, the receipt also naming its channel and date, which the registry's unique number per series
 * requires).
 */
public final class RegistryDocumentNumbers implements DocumentNumbers {

    /** {@code INV/<cycle>/<n>}: the invoice number of the config series, one counter per cycle, gap-free. */
    public static final Map<String, Object> DEFAULT_INVOICE_SERIES = Map.of("seriesKey", "INVOICE",
            "pattern", "INV/{scope}/{seq}", "gapPolicy", "GAP_FREE", "reset", "NEVER");

    /** {@code RCP/<channel>/<date>/<n>}: one counter per channel and business date, gap-free. */
    public static final Map<String, Object> DEFAULT_RECEIPT_SERIES = Map.of("seriesKey", "RECEIPT",
            "pattern", "RCP/{scope}/{period}/{seq}", "gapPolicy", "GAP_FREE", "reset", "DAILY");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM_ACTOR = "system:billing";

    private final Transactions tx;
    private final Dialect dialect;
    private final NumberingService numbering;
    private final Map<Kind, Map<String, Object>> definitions;
    private final Set<String> knownSeries = ConcurrentHashMap.newKeySet();

    public RegistryDocumentNumbers(Transactions tx, Dialect dialect, NumberingService numbering) {
        this(tx, dialect, numbering, DEFAULT_INVOICE_SERIES, DEFAULT_RECEIPT_SERIES);
    }

    /**
     * @param tx                transactions on the product database (the registry and {@code BIL_DOCUMENT} tables)
     * @param invoiceDefinition registry series definition used when the tenant has none ({@code seriesKey} required)
     * @param receiptDefinition likewise for receipts
     */
    public RegistryDocumentNumbers(Transactions tx, Dialect dialect, NumberingService numbering,
                                   Map<String, Object> invoiceDefinition, Map<String, Object> receiptDefinition) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.numbering = Objects.requireNonNull(numbering, "numbering");
        Map<Kind, Map<String, Object>> d = new LinkedHashMap<>();
        d.put(Kind.INVOICE, Map.copyOf(Objects.requireNonNull(invoiceDefinition, "invoiceDefinition")));
        d.put(Kind.RECEIPT, Map.copyOf(Objects.requireNonNull(receiptDefinition, "receiptDefinition")));
        for (Map<String, Object> def : d.values()) {
            if (!(def.get("seriesKey") instanceof String k) || k.isBlank()) {
                throw new IllegalArgumentException("a billing series definition needs a seriesKey: " + def);
            }
        }
        this.definitions = Map.copyOf(d);
    }

    /** The registry series key of a document kind. */
    public String seriesKey(Kind kind) {
        return (String) definitions.get(kind).get("seriesKey");
    }

    @Override
    public Issued issue(Request r, Supplier<Map<String, Object>> plan) {
        Optional<Issued> prior = tx.inTransaction(c -> find(c, r));
        if (prior.isPresent()) {
            return prior.get();
        }
        String seriesKey = ensureSeries(r);
        Map<String, Object> p = plan.get();
        String planJson = CanonicalJson.write(p == null ? Map.of() : p);
        for (int round = 0; round < 3; round++) {
            Issued out = tx.inTransaction(c -> {
                boolean claimed = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO BIL_DOCUMENT(tenant_id, doc_type, doc_key,"
                                + " series_key, plan_json, created_at, created_by) VALUES (?,?,?,?,?," + dialect.now() + ",?)",
                        r.tenant(), r.kind().name(), r.key(), seriesKey, planJson, actor(r));
                if (!claimed) {
                    return find(c, r).orElse(null);   // the winner's number and plan; null: it rolled back, claim again
                }
                NumberingService.Allocation a = numbering.allocate(c, context(r), seriesKey, r.scope(), r.businessDate(),
                        Map.of(), r.kind().refType(), r.key());
                Jdbc.update(c, "UPDATE BIL_DOCUMENT SET doc_number=? WHERE tenant_id=? AND doc_type=? AND doc_key=?",
                        a.formatted(), r.tenant(), r.kind().name(), r.key());
                return new Issued(a.formatted(), p, true);
            });
            if (out != null) {
                return out;
            }
        }
        throw new IllegalStateException("billing document " + r.kind() + " " + r.key() + " of " + r.tenant()
                + " could not be claimed; retry");
    }

    /** The stored number and plan of a document; empty when it has none (or only an uncommitted claim). */
    private Optional<Issued> find(Connection c, Request r) {
        return Jdbc.one(c, "SELECT doc_number, plan_json FROM BIL_DOCUMENT WHERE tenant_id=? AND doc_type=? AND doc_key=?",
                        rs -> new String[]{rs.getString(1), rs.getString(2)}, r.tenant(), r.kind().name(), r.key())
                .filter(row -> row[0] != null)
                .map(row -> new Issued(row[0], parse(row[1]), false));
    }

    /** The series key of the request's kind; defines the series from the configured definition if the tenant has none. */
    private String ensureSeries(Request r) {
        Map<String, Object> def = definitions.get(r.kind());
        String seriesKey = (String) def.get("seriesKey");
        String known = r.tenant() + "\u0001" + seriesKey;
        if (knownSeries.contains(known)) {
            return seriesKey;
        }
        RegistryException last = null;
        for (int round = 0; round < 2; round++) {
            try {
                tx.inTransaction(c -> {
                    try {
                        numbering.series(c, r.tenant(), seriesKey);
                    } catch (RegistryException e) {
                        if (e.code() != RegistryException.Code.NOT_FOUND) {
                            throw e;
                        }
                        numbering.define(c, context(r), def);
                    }
                    return null;
                });
                knownSeries.add(known);
                return seriesKey;
            } catch (RegistryException e) {
                if (e.code() != RegistryException.Code.CONFLICT) {
                    throw e;
                }
                last = e;   // defined concurrently by another node: read it on the next round
            }
        }
        throw last;
    }

    private static RegistryContext context(Request r) {
        return RegistryContext.of(r.tenant(), actor(r)).withPurpose("billing");
    }

    private static String actor(Request r) {
        return r.actor() == null || r.actor().isBlank() ? SYSTEM_ACTOR : r.actor();
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("stored billing document plan is not valid JSON", e);
        }
    }
}
