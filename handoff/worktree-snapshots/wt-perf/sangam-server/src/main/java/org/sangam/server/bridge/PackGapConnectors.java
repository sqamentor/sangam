// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.support.DocumentStore;

/** In-process connectors for pack connector ids that have no direct module operation. */
public final class PackGapConnectors {

    private PackGapConnectors() {
    }

    /**
     * {@code registry.beneficiary}, operation {@code check}: the first business key to claim (tenant, beneficiaryId,
     * schemeId) owns it; another business key gets {@code duplicate=true}. Persisted in REST_DOCUMENT under a hash, so
     * the identifier is not stored in clear. The claim commits in its own transaction: it must survive a rollback of
     * the calling step, and a unique violation must not abort that step's transaction.
     */
    public static final class BeneficiaryCheck implements SangamConnector {
        private static final String KIND = "pack:beneficiary-claim";
        private final DocumentStore store;

        public BeneficiaryCheck(DocumentStore store) {
            this.store = store;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("registry.beneficiary", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                    List.of("check"), ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest r) {
            String beneficiary = r.str("beneficiaryId");
            String scheme = r.str("schemeId");
            if (beneficiary == null || beneficiary.isBlank() || scheme == null || scheme.isBlank()) {
                return ConnectorResult.BusinessFailure.of("INPUT_REQUIRED", "beneficiaryId and schemeId are required");
            }
            String id = Hashes.hex(Hashes.sha256(beneficiary + "|" + scheme));
            String me = r.businessKey() != null ? r.businessKey() : r.rootId();
            String owner = store.get(r.tenantId(), KIND, id).map(DocumentStore.Doc::ownerId).orElse(null);
            if (owner == null) {
                try {
                    store.tx().inNewTx(() -> store.insert(r.tenantId(), KIND, id, null, null, me, null, Map.of(), "system"));
                    owner = me;
                } catch (ApiProblem duplicate) {
                    owner = store.get(r.tenantId(), KIND, id).map(DocumentStore.Doc::ownerId).orElse(me);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("duplicate", !owner.equals(me));
            return ConnectorResult.Success.of(out, r.operationId().toString());
        }
    }

    /**
     * {@code ledger.post}: posts an 835 remittance, claim by claim, on the claim receivable raised at submission
     * (account: ledger, line, period, holder = CLP01). Legs, each with its own stable reference:
     * paid = consume; CO / OA / PI = write-off; PR = transfer to holder {@code patient:<CLP01>}; status REVERSAL =
     * reverse the paid amount. A denied claim posts nothing (the denial is worked first). Anything that cannot be posted
     * cleanly (unmatched account, refused leg, unbalanced claim, CR group, reversal with adjustments) is returned in
     * {@code exceptions} / {@code exceptionCount} for the work queue (US-HLT-106). The claim balance rule is [U].
     */
    public static final class RemittancePosting implements SangamConnector {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
        private static final java.util.UUID REMIT_NS = java.util.UUID.fromString("3f0c6c2e-8a51-5a0e-9b3d-2f6b1c7d8e90");
        private final SangamConnector ledger;

        public RemittancePosting(SangamConnector ledger) {
            this.ledger = ledger;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("ledger.remittance", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                    List.of("post"), ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest r) {
            List<Map<String, Object>> claims = claims(r);
            if (claims == null) {
                return ConnectorResult.BusinessFailure.of("INPUT_REQUIRED", "eraClaims or patientControlNumber is required");
            }
            List<Map<String, Object>> exceptions = new java.util.ArrayList<>();
            Object scope = r.input().get("patientControlNumber");
            if (scope != null && !scope.toString().isBlank() && r.input().get("eraClaims") != null) {
                // claim-scoped: a mixed 835 reaches several processes; each posts only its own claim
                claims = claims.stream().filter(c -> scope.toString().equals(String.valueOf(c.get("patientControlNumber"))))
                        .toList();
                if (claims.isEmpty()) {
                    exceptions.add(exception(scope.toString(), "claim", "CLAIM_NOT_IN_FILE"));
                }
            }
            int posted = 0;
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            for (Map<String, Object> c : claims) {
                // claim key = trace + PCN + occurrence of that PCN in the file (decoder field; counted here when absent)
                int counted = seen.merge(String.valueOf(c.get("patientControlNumber")), 1, Integer::sum) - 1;
                int occurrence = c.get("occurrence") == null ? counted : Integer.parseInt(String.valueOf(c.get("occurrence")));
                posted += post(r, c, occurrence, exceptions);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("legsPosted", posted);
            out.put("exceptionCount", exceptions.size());
            out.put("exceptions", exceptions);
            return ConnectorResult.Success.of(out, r.operationId().toString());
        }

        private int post(ConnectorRequest r, Map<String, Object> c, int index, List<Map<String, Object>> exceptions) {
            String pcn = String.valueOf(c.get("patientControlNumber"));
            String status = String.valueOf(c.getOrDefault("status", "PAID_PRIMARY"));
            if ("DENIED".equals(status)) {
                return 0;
            }
            if (Boolean.FALSE.equals(c.get("claimBalanced"))) {
                exceptions.add(exception(pcn, "claim", "CLAIM_NOT_BALANCED"));
                return 0;
            }
            if ("NOT_OUR_CLAIM".equals(status) || "PREDETERMINATION".equals(status) || "OTHER".equals(status)) {
                exceptions.add(exception(pcn, "claim", "STATUS_" + status));
                return 0;
            }
            java.math.BigDecimal paid = dec(c.get("paidAmount"));
            Map<?, ?> totals = c.get("adjustmentTotals") instanceof Map<?, ?> m ? m : Map.of();
            java.math.BigDecimal pr = totals.containsKey("PR") ? dec(totals.get("PR")) : dec(c.get("patientResponsibility"));
            java.math.BigDecimal writeOff = dec(totals.get("CO")).add(dec(totals.get("OA"))).add(dec(totals.get("PI")));
            int n = 0;
            if ("REVERSAL".equals(status)) {
                n += leg(r, pcn, index, "reverse", paid.abs(), Map.of(), exceptions);
                if (pr.signum() != 0 || writeOff.signum() != 0) {
                    exceptions.add(exception(pcn, "adjustments", "REVERSAL_ADJUSTMENTS_MANUAL"));
                }
                return n;
            }
            if (dec(totals.get("CR")).signum() != 0) {
                exceptions.add(exception(pcn, "CR", "CORRECTION_GROUP_MANUAL"));
            }
            n += leg(r, pcn, index, "consume", paid, Map.of("fromEncumbrance", "false"), exceptions);
            n += leg(r, pcn, index, "write_off", writeOff, Map.of(), exceptions);
            n += leg(r, pcn, index, "transfer", pr, Map.of("toHolder", "patient:" + pcn), exceptions);
            return n;
        }

        private int leg(ConnectorRequest r, String pcn, int index, String op, java.math.BigDecimal amount, Map<String, Object> extra,
                        List<Map<String, Object>> exceptions) {
            if (amount.signum() == 0) {
                return 0;
            }
            Map<String, Object> in = new LinkedHashMap<>();
            for (String k : List.of("ledgerId", "line", "period", "actor")) {
                if (r.input().get(k) != null) {
                    in.put(k, r.input().get(k));
                }
            }
            in.put("holder", pcn);
            in.put("amount", amount.toPlainString());
            in.putAll(extra);
            // keyed on trace (TRN02) + patient control number + occurrence: a claim's legs post exactly once whichever
            // process handles it (claim case, later-remittance case, retry, replay); a second remittance posts separately
            Object trace = r.input().get("eraTraceNumber");
            java.util.UUID id = trace == null || trace.toString().isBlank() || "no-trace".equals(trace)
                    ? Hashes.uuidV5(r.operationId(), pcn + "|" + index + "|" + op)
                    : Hashes.uuidV5(REMIT_NS, r.tenantId() + "|" + trace + "|" + pcn + "|" + index + "|" + op);
            ConnectorResult res = ledger.invoke(new ConnectorRequest(r.tenantId(), id, r.attemptId(), r.attemptNumber(),
                    r.rootId(), r.businessKey(), op, in, r.deadline()));
            if (res instanceof ConnectorResult.Success) {
                return 1;
            }
            exceptions.add(exception(pcn, op, res instanceof ConnectorResult.BusinessFailure f ? f.code() : "LEG_NOT_POSTED"));
            return 0;
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> claims(ConnectorRequest r) {
            Object raw = r.input().get("eraClaims");
            try {
                if (raw instanceof String s && !s.isBlank()) {
                    return JSON.readValue(s, List.class);
                }
                if (raw instanceof List<?> l) {
                    return (List<Map<String, Object>>) l;
                }
            } catch (java.io.IOException e) {
                return null;
            }
            if (r.input().get("patientControlNumber") == null) {
                return null;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("patientControlNumber", r.input().get("patientControlNumber"));
            one.put("status", r.input().getOrDefault("eraClaimStatus", "PAID_PRIMARY"));
            one.put("paidAmount", r.input().get("eraPaidAmount"));
            one.put("patientResponsibility", r.input().get("eraPatientResponsibility"));
            return List.of(one);
        }

        private static Map<String, Object> exception(String pcn, String leg, String code) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("patientControlNumber", pcn);
            e.put("leg", leg);
            e.put("code", code);
            return e;
        }

        private static java.math.BigDecimal dec(Object v) {
            return v == null || v.toString().isBlank() ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(v.toString());
        }
    }

    /**
     * {@code billing.credit} and {@code billing.revise} on the billing connector's real operations.
     * <ul>
     *   <li>credit = waive {@code amount} on (consumer, head, cycle).</li>
     *   <li>revise = waive {@code supersededAmount} of the original bill, then invoice {@code units} under a cycle
     *       derived from the operation id, so the new lines do not collide with the original references. Retries
     *       are safe: both postings carry stable references (a retried revise may consume a bill number).</li>
     * </ul>
     * A missing input is a {@code INPUT_REQUIRED} business failure; amounts are never guessed.
     */
    public static final class BillingAdjustments implements SangamConnector {
        private final SangamConnector billing;

        public BillingAdjustments(SangamConnector billing) {
            this.billing = billing;
        }

        @Override
        public ConnectorDescriptor descriptor() {
            return new ConnectorDescriptor("billing.adjustments", "in-process", CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                    List.of("credit", "revise"), ConnectorProfile.LIVE);
        }

        @Override
        public ConnectorResult invoke(ConnectorRequest r) {
            Map<String, Object> in = new LinkedHashMap<>(r.input());
            if (in.get("consumer") == null && in.get("key") != null) {
                in.put("consumer", in.get("key"));
            }
            String op = r.operation().substring(r.operation().lastIndexOf('.') + 1);
            if ("credit".equals(op)) {
                String missing = missing(in, "consumer", "amount", "head", "cycle");
                if (missing != null) {
                    return required(missing);
                }
                in.putIfAbsent("orderRef", "credit:" + r.operationId());
                return billing.invoke(call(r, "waive", in));
            }
            if (!"revise".equals(op)) {
                return ConnectorResult.BusinessFailure.of("OPERATION_UNKNOWN", op);
            }
            String missing = missing(in, "consumer", "units", "tariffId", "cycle", "head", "supersededAmount");
            if (missing != null) {
                return required(missing);
            }
            Map<String, Object> waive = new LinkedHashMap<>(in);
            waive.put("amount", in.get("supersededAmount"));
            waive.put("orderRef", "revise:" + r.operationId());
            ConnectorResult first = billing.invoke(call(r, "waive", waive));
            if (!(first instanceof ConnectorResult.Success)) {
                return first;
            }
            Map<String, Object> invoice = new LinkedHashMap<>(in);
            invoice.put("cycle", in.get("cycle") + "-R" + r.operationId().toString().substring(0, 8));
            return billing.invoke(call(r, "invoice", invoice));
        }

        private static ConnectorRequest call(ConnectorRequest r, String op, Map<String, Object> input) {
            return new ConnectorRequest(r.tenantId(), r.operationId(), r.attemptId(), r.attemptNumber(), r.rootId(),
                    r.businessKey(), op, input, r.deadline());
        }

        private static String missing(Map<String, Object> in, String... keys) {
            for (String k : keys) {
                if (in.get(k) == null || String.valueOf(in.get(k)).isBlank()) {
                    return k;
                }
            }
            return null;
        }

        private static ConnectorResult required(String key) {
            return ConnectorResult.BusinessFailure.of("INPUT_REQUIRED", "connector input '" + key + "' is required");
        }
    }
}
