// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.x12;

import org.sangam.connectors.health.x12.X12.Delimiters;
import org.sangam.connectors.health.x12.X12.Segment;
import org.sangam.connectors.health.x12.X12.Transaction;

import org.sangam.core.money.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoders for the inbound HIPAA transactions into plain maps that a process can route on (US-HLT-085…090, 104, 106).
 * Every decoder returns {@code type} and {@code controlNumber}. Amounts (SD-398, the pack money contract) are integer
 * MINOR units of {@code currency} (cents for USD) as {@code Long}, with {@code amountUnit = MINOR}; an amount that is not
 * exact in the currency stays the raw text and is listed in {@code invalidAmounts}, never rounded or read as zero.
 */
public final class X12Decoders {

    private X12Decoders() {
    }

    /** Decodes every transaction of the interchange by its ST01. */
    public static List<Map<String, Object>> decodeAll(X12.Interchange ic) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Transaction t : ic.transactions()) {
            out.add(decode(t, ic.delimiters()));
        }
        return out;
    }

    public static Map<String, Object> decode(Transaction t, Delimiters d) {
        return switch (t.type()) {
            case "271" -> eligibility271(t, d);
            case "277" -> claimStatus277(t, d);
            case "278" -> serviceReview278(t, d);
            case "835" -> remittance835(t, d);
            case "999" -> ack999(t);
            case "TA1" -> ta1(t);
            default -> base(t);
        };
    }

    private static Map<String, Object> base(Transaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", t.type());
        m.put("controlNumber", t.controlNumber());
        m.put("implementation", t.implementation());
        return m;
    }

    /** 271: active coverage (EB01 = 1…5), benefits, AAA rejections (e.g. 72 invalid member id, 75 subscriber not found). */
    public static Map<String, Object> eligibility271(Transaction t, Delimiters d) {
        Map<String, Object> m = base(t);
        List<Map<String, Object>> benefits = new ArrayList<>();
        List<Map<String, Object>> rejects = new ArrayList<>();
        boolean active = false;
        boolean inactive = false;
        for (Segment s : t.segments()) {
            switch (s.id()) {
                case "TRN" -> m.putIfAbsent("trace", s.el(2));
                case "NM1" -> {
                    if ("IL".equals(s.el(1))) {
                        m.put("subscriberId", s.el(9));
                        m.put("subscriberName", (s.el(3) + " " + s.el(4)).trim());
                    } else if ("PR".equals(s.el(1))) {
                        m.put("payerName", s.el(3));
                    }
                }
                case "AAA" -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("valid", s.el(1));
                    r.put("reasonCode", s.el(3));
                    r.put("followUp", s.el(4));
                    rejects.add(r);
                }
                case "EB" -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("info", s.el(1));
                    e.put("coverageLevel", s.el(2));
                    e.put("serviceType", s.el(3));
                    e.put("insuranceType", s.el(4));
                    e.put("planDescription", s.el(5));
                    e.put("timePeriod", s.el(6));
                    if (!s.el(7).isEmpty()) {
                        e.put("amount", s.el(7));
                    }
                    if (!s.el(8).isEmpty()) {
                        e.put("percent", s.el(8));
                    }
                    e.put("inNetwork", s.el(12));
                    benefits.add(e);
                    String i = s.el(1);
                    if (i.matches("[1-5]")) {
                        active = true;
                    } else if (i.matches("[6-8]")) {
                        inactive = true;
                    }
                }
                default -> {
                }
            }
        }
        m.put("benefits", benefits);
        m.put("rejections", rejects);
        m.put("eligibility", !rejects.isEmpty() ? "REJECTED" : active ? "ACTIVE" : inactive ? "INACTIVE" : "UNKNOWN");
        if (!rejects.isEmpty()) {
            m.put("reasonCode", rejects.get(0).get("reasonCode"));
        }
        return minorUnits(m);
    }

    /** 277: STC per claim; category A* acknowledgement, P* pending, F* finalized, R* request for information, E* error. */
    public static Map<String, Object> claimStatus277(Transaction t, Delimiters d) {
        Map<String, Object> m = base(t);
        List<Map<String, Object>> claims = new ArrayList<>();
        Map<String, Object> cur = null;
        for (Segment s : t.segments()) {
            switch (s.id()) {
                case "TRN" -> {
                    if ("2".equals(s.el(1)) || cur == null) {
                        cur = new LinkedHashMap<>();
                        cur.put("trace", s.el(2));
                        claims.add(cur);
                    }
                }
                case "STC" -> {
                    if (cur == null) {
                        cur = new LinkedHashMap<>();
                        claims.add(cur);
                    }
                    String cat = s.comp(1, 1, d);
                    cur.putIfAbsent("statusCategory", cat);
                    cur.putIfAbsent("statusCode", s.comp(1, 2, d));
                    cur.putIfAbsent("statusDate", s.el(2));
                    if (!s.el(4).isEmpty()) {
                        cur.putIfAbsent("chargeAmount", s.el(4));
                    }
                    if (!s.el(5).isEmpty()) {
                        cur.putIfAbsent("paidAmount", s.el(5));
                    }
                    cur.putIfAbsent("status", category(cat));
                }
                case "REF" -> {
                    if (cur != null && "1K".equals(s.el(1))) {
                        cur.put("payerClaimNumber", s.el(2));
                    } else if (cur != null && "EJ".equals(s.el(1))) {
                        cur.put("patientControlNumber", s.el(2));
                    }
                }
                default -> {
                }
            }
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Map<String, Object> c : claims) {
            // occurrence of this patient control number in the file: the claim-scoped posting key with the trace number
            c.put("occurrence", seen.merge(String.valueOf(c.get("patientControlNumber")), 1, Integer::sum) - 1);
        }
        List<String> invalid = new ArrayList<>();
        balanceClaims(claims, currency(m), invalid);
        m.put("invalidAmounts", invalid);
        m.put("claims", claims);
        if (!claims.isEmpty()) {
            m.putAll(firstNonList(claims.get(0)));
        }
        return minorUnits(m);
    }

    static String category(String cat) {
        if (cat == null || cat.isEmpty()) {
            return "UNKNOWN";
        }
        return switch (cat.charAt(0)) {
            case 'A' -> cat.equals("A3") || cat.equals("A6") || cat.equals("A7") || cat.equals("A8") ? "REJECTED" : "ACKNOWLEDGED";
            case 'P' -> "PENDING";
            case 'F' -> cat.equals("F2") ? "DENIED" : cat.equals("F4") ? "ADJUDICATED_NO_PAYMENT" : "FINALIZED";
            case 'R' -> "INFO_REQUESTED";
            case 'E' -> "ERROR";
            default -> "UNKNOWN";
        };
    }

    /** 278 response: HCR01 A1 certified, A2 certified partial, A3 not certified, A4 pended, A6 modified, CT contact payer. */
    public static Map<String, Object> serviceReview278(Transaction t, Delimiters d) {
        Map<String, Object> m = base(t);
        for (Segment s : t.segments()) {
            switch (s.id()) {
                case "TRN" -> m.putIfAbsent("trace", s.el(2));
                case "HCR" -> {
                    m.putIfAbsent("action", s.el(1));
                    if (!s.el(2).isEmpty()) {
                        m.putIfAbsent("certificationNumber", s.el(2));
                    }
                    if (!s.el(3).isEmpty()) {
                        m.putIfAbsent("reasonCode", s.el(3));
                    }
                }
                case "AAA" -> m.putIfAbsent("rejectReasonCode", s.el(3));
                case "DTP" -> {
                    if ("ABC".equals(s.el(1)) || "AAH".equals(s.el(1))) {
                        m.putIfAbsent("certificationPeriod", s.el(3));
                    }
                }
                default -> {
                }
            }
        }
        String a = String.valueOf(m.getOrDefault("action", ""));
        m.put("decision", switch (a) {
            case "A1" -> "APPROVED";
            case "A2", "A6" -> "MODIFIED";
            case "A3" -> "DENIED";
            case "A4" -> "PENDED";
            case "C" -> "CANCELLED";
            case "CT", "NA" -> "CONTACT_PAYER";
            default -> m.containsKey("rejectReasonCode") ? "REJECTED" : "UNKNOWN";
        });
        return m;
    }

    /**
     * 835: BPR payment, TRN check/EFT trace, CLP claims with CAS adjustments (group CO/PR/OA/PI/CR + CARC + amount),
     * SVC lines, LQ remark codes, PLB provider adjustments. {@code balanced} checks BPR02 = Σ CLP04 − Σ PLB amounts.
     */
    public static Map<String, Object> remittance835(Transaction t, Delimiters d) {
        Map<String, Object> m = base(t);
        List<Map<String, Object>> claims = new ArrayList<>();
        List<Map<String, Object>> plbs = new ArrayList<>();
        Map<String, Object> claim = null;
        List<Map<String, Object>> adj = null;
        List<String> remarks = null;
        List<String> invalid = new ArrayList<>();
        List<String> paid = new ArrayList<>();
        List<String> plb = new ArrayList<>();
        for (Segment s : t.segments()) {
            switch (s.id()) {
                case "BPR" -> {
                    m.put("handling", s.el(1));
                    m.put("paymentAmount", s.el(2));
                    m.put("creditDebit", s.el(3));
                    m.put("paymentMethod", s.el(4));
                    m.put("paymentDate", s.el(16));
                }
                case "TRN" -> m.put("traceNumber", s.el(2));
                case "CUR" -> m.put("currency", s.el(2)); // CUR02: ISO 4217 code of the remittance (default USD)
                case "N1" -> {
                    if ("PR".equals(s.el(1))) {
                        m.put("payerName", s.el(2));
                    } else if ("PE".equals(s.el(1))) {
                        m.put("payeeName", s.el(2));
                        m.put("payeeId", s.el(4));
                    }
                }
                case "CLP" -> {
                    claim = new LinkedHashMap<>();
                    adj = new ArrayList<>();
                    remarks = new ArrayList<>();
                    claim.put("patientControlNumber", s.el(1));
                    claim.put("claimStatus", s.el(2));
                    claim.put("status", clpStatus(s.el(2)));
                    claim.put("chargeAmount", s.el(3));
                    claim.put("paidAmount", s.el(4));
                    if (!s.el(5).isEmpty()) {
                        claim.put("patientResponsibility", s.el(5));
                    }
                    claim.put("payerClaimNumber", s.el(7));
                    claim.put("adjustments", adj);
                    claim.put("remarkCodes", remarks);
                    claims.add(claim);
                    paid.add(s.el(4));
                }
                case "CAS" -> {
                    if (adj != null) {
                        // CAS01 group, then up to 6 triplets (reason, amount, quantity)
                        for (int i = 2; i <= 17; i += 3) {
                            if (s.el(i).isEmpty()) {
                                break;
                            }
                            Map<String, Object> a = new LinkedHashMap<>();
                            a.put("group", s.el(1));
                            a.put("reasonCode", s.el(i));
                            a.put("amount", s.el(i + 1));
                            adj.add(a);
                        }
                    }
                }
                case "LQ" -> {
                    if (remarks != null) {
                        remarks.add(s.el(2));
                    }
                }
                case "MOA", "MIA" -> {
                    if (remarks != null) {
                        for (int i = 3; i <= 7; i++) {
                            if (!s.el(i).isEmpty() && s.el(i).matches("[A-Z]{1,2}\\d+")) {
                                remarks.add(s.el(i));
                            }
                        }
                    }
                }
                case "PLB" -> {
                    for (int i = 3; i <= 13; i += 2) {
                        if (s.el(i).isEmpty()) {
                            break;
                        }
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("providerId", s.el(1));
                        p.put("reason", s.comp(i, 1, d));
                        p.put("reference", s.comp(i, 2, d));
                        p.put("amount", s.el(i + 1));
                        plbs.add(p);
                        plb.add(s.el(i + 1));
                    }
                }
                default -> {
                }
            }
        }
        balanceClaims(claims, currency(m), invalid);
        m.put("claims", claims);
        m.put("providerAdjustments", plbs);
        String ccy = currency(m);
        Money bpr = amount(String.valueOf(m.getOrDefault("paymentAmount", "0")), ccy, "BPR02", invalid);
        Money paidSum = sum(paid, ccy, "CLP04", invalid);
        Money plbSum = sum(plb, ccy, "PLB", invalid);
        // BPR02 = sum(CLP04) - sum(PLB), exactly in minor units; any malformed amount makes the file unbalanced
        m.put("balanced", invalid.isEmpty() && bpr != null && paidSum != null && plbSum != null
                && bpr.equals(paidSum.minus(plbSum)));
        m.put("invalidAmounts", invalid);
        m.put("claimCount", claims.size());
        if (!claims.isEmpty()) {
            // first claim at the top level, for single-claim routing in a process (claim pack: era* variables)
            Map<String, Object> c0 = claims.get(0);
            m.put("claimStatus", c0.get("status"));
            m.put("paidAmount", c0.get("paidAmount"));
            m.put("chargeAmount", c0.get("chargeAmount"));
            m.put("patientResponsibility", c0.getOrDefault("patientResponsibility", "0"));
            m.put("denied", "DENIED".equals(c0.get("status")));
            Object adjs = c0.get("adjustments");
            if (adjs instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> a0) {
                m.put("firstReasonCode", a0.get("reasonCode"));
                m.put("firstAdjustmentGroup", a0.get("group"));
            }
        }
        return minorUnits(m);
    }

    /**
     * Scopes a decoded 835 to one claim: {@code claims} keeps only that patient control number and the top-level claim
     * fields (status, amounts, first adjustment) describe it; {@code claimStatus = NOT_IN_FILE} when it is absent.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> focusClaim(Map<String, Object> remit, String patientControlNumber) {
        Map<String, Object> m = new LinkedHashMap<>(remit);
        List<Map<String, Object>> all = m.get("claims") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        List<Map<String, Object>> mine = new ArrayList<>();
        for (Map<String, Object> c : all) {
            if (patientControlNumber.equals(String.valueOf(c.get("patientControlNumber")))) {
                mine.add(c);
            }
        }
        m.put("claims", mine);
        m.put("claimFound", !mine.isEmpty());
        for (String k : List.of("paidAmount", "chargeAmount", "patientResponsibility", "firstReasonCode", "firstAdjustmentGroup")) {
            m.remove(k);
        }
        if (mine.isEmpty()) {
            m.put("claimStatus", "NOT_IN_FILE");
            m.put("denied", false);
            return m;
        }
        Map<String, Object> c0 = mine.get(0);
        m.put("claimStatus", c0.get("status"));
        m.put("paidAmount", c0.get("paidAmount"));
        m.put("chargeAmount", c0.get("chargeAmount"));
        m.put("patientResponsibility", c0.getOrDefault("patientResponsibility", "0"));
        m.put("denied", "DENIED".equals(c0.get("status")));
        if (c0.get("adjustments") instanceof List<?> adjs && !adjs.isEmpty() && adjs.get(0) instanceof Map<?, ?> a0) {
            m.put("firstReasonCode", a0.get("reasonCode"));
            m.put("firstAdjustmentGroup", a0.get("group"));
        }
        return m;
    }

    static String clpStatus(String code) {
        return switch (code) {
            case "1", "19" -> "PAID_PRIMARY";
            case "2", "20" -> "PAID_SECONDARY";
            case "3", "21" -> "PAID_TERTIARY";
            case "4" -> "DENIED";
            case "22" -> "REVERSAL";
            case "23" -> "NOT_OUR_CLAIM";
            case "25" -> "PREDETERMINATION";
            default -> "OTHER";
        };
    }

    /** 999: AK9 (A accepted, E accepted with errors, P partially accepted, R rejected) and IK5 per transaction. */
    public static Map<String, Object> ack999(Transaction t) {
        Map<String, Object> m = base(t);
        List<Map<String, Object>> sets = new ArrayList<>();
        Map<String, Object> cur = null;
        for (Segment s : t.segments()) {
            switch (s.id()) {
                case "AK1" -> m.put("groupControlNumber", s.el(2));
                case "AK2" -> {
                    cur = new LinkedHashMap<>();
                    cur.put("transactionType", s.el(1));
                    cur.put("controlNumber", s.el(2));
                    sets.add(cur);
                }
                case "IK3" -> {
                    if (cur != null) {
                        cur.put("errorSegment", s.el(1));
                        cur.put("errorCode", s.el(4));
                    }
                }
                case "IK5" -> {
                    if (cur != null) {
                        cur.put("ack", s.el(1));
                    }
                }
                case "AK9" -> {
                    m.put("ack", s.el(1));
                    m.put("included", s.el(2));
                    m.put("received", s.el(3));
                    m.put("acceptedCount", s.el(4));
                }
                default -> {
                }
            }
        }
        m.put("sets", sets);
        m.put("accepted", m.get("ack") != null && !"R".equals(m.get("ack")));
        return m;
    }

    /** TA1: TA104 A accepted, E accepted with errors, R rejected; TA105 note code. */
    public static Map<String, Object> ta1(Transaction t) {
        Map<String, Object> m = base(t);
        for (Segment s : t.segments()) {
            if ("TA1".equals(s.id())) {
                m.put("interchangeControlNumber", s.el(1));
                m.put("ack", s.el(4));
                m.put("noteCode", s.el(5));
                m.put("accepted", !"R".equals(s.el(4)));
            }
        }
        return m;
    }

    /** Currency of the transaction: CUR02 when present, else USD (the X12 HIPAA default). */
    static String currency(Map<String, Object> m) {
        Object c = m.get("currency");
        return c == null || c.toString().isBlank() ? "USD" : c.toString().trim();
    }

    /**
     * Exact amount (SD-398): blank is zero; a malformed value or one with more decimals than the currency allows is
     * recorded in {@code invalid} and returns null. It is never silently read as zero (the old decoder did), which
     * made a corrupt file look balanced or posted a wrong amount.
     */
    static Money amount(String v, String ccy, String field, List<String> invalid) {
        if (v == null || v.isBlank() || "null".equals(v)) {
            return Money.zero(ccy);
        }
        try {
            return Money.parse(v, ccy);
        } catch (RuntimeException e) {
            invalid.add(field + "=" + v);
            return null;
        }
    }

    static Money sum(List<String> values, String ccy, String field, List<String> invalid) {
        Money s = Money.zero(ccy);
        for (String v : values) {
            Money x = amount(v, ccy, field, invalid);
            if (x == null) {
                return null;
            }
            s = s.plus(x);
        }
        return s;
    }

    /** [U] claim balance CLP03 - sum(CAS) = CLP04 (from knowledge of the 835 TR3), exactly in minor units. */
    static void balanceClaims(List<Map<String, Object>> claims, String ccy, List<String> invalid) {
        for (Map<String, Object> c : claims) {
            Map<String, Money> byGroup = new LinkedHashMap<>();
            List<String> adjs = new ArrayList<>();
            boolean ok = true;
            if (c.get("adjustments") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> a) {
                        Money v = amount(String.valueOf(a.get("amount")), ccy, "CAS", invalid);
                        if (v == null) {
                            ok = false;
                            continue;
                        }
                        adjs.add(v.toPlainString());
                        byGroup.merge(String.valueOf(a.get("group")), v, Money::plus);
                    }
                }
            }
            Map<String, String> totals = new LinkedHashMap<>();
            byGroup.forEach((g, v) -> totals.put(g, v.toPlainString()));
            c.put("adjustmentTotals", totals);
            Money charge = amount(String.valueOf(c.get("chargeAmount")), ccy, "chargeAmount", invalid);
            Money paidAmt = amount(String.valueOf(c.get("paidAmount")), ccy, "paidAmount", invalid);
            Money adjSum = ok ? sum(adjs, ccy, "CAS", invalid) : null;
            c.put("claimBalanced", charge != null && paidAmt != null && adjSum != null
                    && charge.minus(adjSum).equals(paidAmt));
        }
    }

    private static Map<String, Object> firstNonList(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (!(v instanceof List<?>)) {
                out.put(k, v);
            }
        });
        return out;
    }

    static final java.util.Set<String> MONEY_KEYS = java.util.Set.of("amount", "paymentAmount", "chargeAmount", "paidAmount",
            "patientResponsibility");

    /** Rewrites every decoded amount to integer minor units of the transaction currency (one exact conversion). */
    static Map<String, Object> minorUnits(Map<String, Object> m) {
        String ccy = currency(m);
        m.put("currency", ccy);
        m.put("amountUnit", "MINOR");
        toMinor(m, ccy);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void toMinor(Object node, String ccy) {
        if (node instanceof Map<?, ?> raw) {
            Map<String, Object> mm = (Map<String, Object>) raw;
            for (Map.Entry<String, Object> e : mm.entrySet()) {
                Object v = e.getValue();
                if ((MONEY_KEYS.contains(e.getKey()) && (v instanceof String || v instanceof Number))) {
                    e.setValue(minorOrRaw(v, ccy));
                } else if ("adjustmentTotals".equals(e.getKey()) && v instanceof Map<?, ?> totals) {
                    Map<String, Object> conv = new LinkedHashMap<>();
                    totals.forEach((g, x) -> conv.put(String.valueOf(g), minorOrRaw(x, ccy)));
                    e.setValue(conv);
                } else {
                    toMinor(v, ccy);
                }
            }
        } else if (node instanceof List<?> l) {
            l.forEach(x -> toMinor(x, ccy));
        }
    }

    private static Object minorOrRaw(Object v, String ccy) {
        String s = String.valueOf(v);
        if (s.isBlank()) {
            return 0L;
        }
        try {
            return Money.parse(s, ccy).minor();
        } catch (RuntimeException notExact) {
            return s; // already reported in invalidAmounts by the balance checks
        }
    }}
