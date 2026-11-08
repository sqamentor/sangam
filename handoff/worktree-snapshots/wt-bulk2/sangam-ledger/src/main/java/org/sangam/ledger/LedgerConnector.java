// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.sangam.config.common.Tree;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Thin in-process connector that lets packs reach {@link LedgerService} through their connector ids
 * {@code ledger.available}, {@code ledger.allocate}, {@code ledger.distribute}, {@code ledger.withdraw},
 * {@code ledger.commit}, {@code ledger.encumber}, {@code ledger.release}, {@code ledger.consume}, {@code ledger.transfer},
 * {@code ledger.adjust}, {@code ledger.releaseHolds}, {@code ledger.closePeriod} (bind each id to this connector; the
 * operation is taken from the connector id suffix or {@link ConnectorRequest#operation()}).
 *
 * <p>The posting reference is the request's {@code operationId} (T35 / R36), so a retried pack step never posts twice;
 * {@link #lookup} answers from the stored entry, which makes the connector class (a).
 *
 * <p>Account fields are read from the step inputs, first key found wins (pack variables differ per domain):
 * <ul>
 *   <li>ledger: {@code ledgerId}, {@code ledger};</li>
 *   <li>line: {@code line}, {@code headOfAccount}, {@code fromHead}, {@code slsCode}, {@code receiptHead}, {@code itemCode};</li>
 *   <li>holder: {@code holder}, {@code ddoCode}, {@code motherSanctionNo}, {@code consumer}, {@code location};</li>
 *   <li>period: {@code period}, {@code fiscalYear} (missing or {@code current}: the period resolver);</li>
 *   <li>stage: {@code stage} (missing: the definition's consume stage; for {@code allocate} the first stage);</li>
 *   <li>amount: {@code amount}, {@code surrenderAmount}, {@code beAmount};</li>
 *   <li>target for transfer: line {@code toHead} and / or holder {@code toHolder}; for distribute / withdraw: the parent stage with holder
 *       {@code fromHolder} (default {@code -}).</li>
 * </ul>
 */
public final class LedgerConnector implements SangamConnector {

    private static final List<String> LINE = List.of("line", "headOfAccount", "fromHead", "slsCode", "receiptHead", "itemCode");
    private static final List<String> HOLDER = List.of("holder", "ddoCode", "motherSanctionNo", "consumer", "location");
    private static final List<String> AMOUNT = List.of("amount", "surrenderAmount", "beAmount");

    private final LedgerService ledger;
    private final BiFunction<String, LocalDate, String> periodResolver;
    private final Clock clock;

    /**
     * @param periodResolver tenant and date to the current period label (wire it from the tenant profile's fiscal
     *                       calendar: {@code resolver.resolve(t, d).locale().fiscal().yearOf(d).label()})
     */
    public LedgerConnector(LedgerService ledger, BiFunction<String, LocalDate, String> periodResolver, Clock clock) {
        this.ledger = ledger;
        this.periodResolver = periodResolver;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor("ledger", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                List.of("available", "allocate", "distribute", "withdraw", "commit", "encumber", "release", "consume", "transfer",
                        "adjust", "reverse", "write_off", "releaseHolds", "closePeriod"), ConnectorProfile.LIVE);
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        try {
            String op = r.operation().contains(".") ? r.operation().substring(r.operation().lastIndexOf('.') + 1) : r.operation();
            String tenant = r.tenantId();
            LocalDate today = clock.instant().atZone(ledger.zone(tenant)).toLocalDate(); // tenant business date
            String ledgerId = first(r, List.of("ledgerId", "ledger"), "BUDGET");
            LedgerDefinition def = ledger.definition(tenant, ledgerId);
            String period = first(r, List.of("period", "fiscalYear"), null);
            if (period == null || "current".equalsIgnoreCase(period)) {
                period = periodResolver.apply(tenant, today);
            }
            String stage = first(r, List.of("stage"), "allocate".equals(op) ? def.stages().get(0) : def.consumeAt());
            AccountKey acct = new AccountKey(tenant, ledgerId, period, first(r, LINE, "-"), first(r, HOLDER, "-"), stage,
                    first(r, List.of("sub"), null));
            BigDecimal amount = new BigDecimal(first(r, AMOUNT, "0"));
            String ref = r.operationId().toString();
            Map<String, Object> out = new LinkedHashMap<>();
            switch (op) {
                case "available" -> {
                    out.put("available", ledger.available(acct).toPlainString());
                    return ConnectorResult.Success.of(out, null);
                }
                case "releaseHolds" -> {
                    List<LedgerService.Result> res = ledger.releaseHolds(acct);
                    out.put("released", res.size());
                    return ConnectorResult.Success.of(out, ref);
                }
                case "closePeriod" -> {
                    String next = first(r, List.of("nextPeriod"), null);
                    List<LedgerService.Result> res = ledger.closePeriod(tenant, ledgerId, period, next, today, r.str("actor"));
                    out.put("postings", res.size());
                    return ConnectorResult.Success.of(out, ref);
                }
                default -> {
                    LedgerRequest req = request(r, op, acct, def, amount, ref, today);
                    LedgerService.Result res = ledger.apply(req);
                    out.put("applied", res.applied());
                    out.put("held", res.held());
                    out.put("duplicate", res.duplicate());
                    out.put("available", ledger.available(acct).toPlainString());
                    if (res.applied() || res.held()) {
                        return new ConnectorResult.Success(out, ref, res.duplicate());
                    }
                    return new ConnectorResult.BusinessFailure(res.reason(), "ledger refused " + op, out);
                }
            }
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("LEDGER_REQUEST_INVALID", e.getMessage());
        }
    }

    private LedgerRequest request(ConnectorRequest r, String op, AccountKey acct, LedgerDefinition def, BigDecimal amount,
                                  String ref, LocalDate today) {
        AccountKey target = null;
        String operation = switch (op) {
            case "release" -> "DISENCUMBER";
            default -> op.toUpperCase();
        };
        if ("transfer".equals(op)) {
            String toLine = first(r, List.of("toHead", "toLine"), null);
            String toHolder = first(r, List.of("toHolder"), null);
            if (toLine == null && toHolder == null) {
                toLine = "-";
            }
            target = toLine == null ? acct : acct.withLine(toLine);
            if (toHolder != null) {
                target = target.atStage(target.stage(), toHolder);
            }
        } else if ("distribute".equals(op) || "withdraw".equals(op)) {
            String parent = def.parentStage(acct.stage());
            target = acct.atStage(parent == null ? acct.stage() : parent, first(r, List.of("fromHolder"), "-"));
            if ("distribute".equals(op)) {
                // DISTRIBUTE moves from the parent (account) to the child (target)
                AccountKey child = acct;
                acct = target;
                target = child;
            }
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (String k : List.of("caseType", "billType", "sanctionRef", "satisfiedConditions", "fromAttrs", "toAttrs", "purpose")) {
            if (r.input().get(k) != null) {
                attrs.put(k, r.input().get(k));
            }
        }
        boolean fromEnc = !"false".equals(first(r, List.of("fromEncumbrance"), "consume".equals(op) ? "true" : "false"));
        boolean fromCom = "true".equals(first(r, List.of("fromCommitment"), "false"));
        boolean hold = "true".equals(first(r, List.of("holdIfShort"), "false"));
        return new LedgerRequest(operation, acct, target, amount, r.str("currency"), r.str("rateType"), ref, r.businessKey(),
                r.str("actor"), today, fromCom, fromEnc && "consume".equals(op), hold, attrs);
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        return ledger.store().entry(r.tenantId(), r.operationId().toString())
                .<LookupResult>map(e -> new LookupResult.Found(true, Map.of("operation", e.operation()), e.reference(), null))
                .orElseGet(LookupResult.NotFound::new);
    }

    private static String first(ConnectorRequest r, List<String> keys, String def) {
        for (String k : keys) {
            Object v = r.input().get(k);
            if (v != null && !v.toString().isBlank()) {
                return Tree.str(v, def);
            }
        }
        return def;
    }
}
