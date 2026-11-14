import sys
p = sys.argv[1] + "/sangam-connectors-health/src/main/java/org/sangam/connectors/health/x12/X12Decoders.java"
s = open(p, encoding="utf-8").read()

block = """        for (Map<String, Object> c : claims) {
            // [U] claim balance CLP03 - sum(CAS) = CLP04: from knowledge of the 835 TR3, no public text could be read
            Map<String, String> totals = new LinkedHashMap<>();
            BigDecimal adjSum = BigDecimal.ZERO;
            if (c.get("adjustments") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> a) {
                        BigDecimal v = dec(String.valueOf(a.get("amount")));
                        adjSum = adjSum.add(v);
                        totals.merge(String.valueOf(a.get("group")), v.toPlainString(),
                                (x, y) -> new BigDecimal(x).add(new BigDecimal(y)).toPlainString());
                    }
                }
            }
            c.put("adjustmentTotals", totals);
            c.put("claimBalanced", dec(String.valueOf(c.get("chargeAmount"))).subtract(adjSum)
                    .compareTo(dec(String.valueOf(c.get("paidAmount")))) == 0);
        }
"""
assert s.count(block) == 2, s.count(block)
s = s.replace(block, "        balanceClaims(claims, currency(m), invalid);\n", 2)

# 835 totals
a = """        BigDecimal paidSum = BigDecimal.ZERO;
        BigDecimal plbSum = BigDecimal.ZERO;
"""
assert s.count(a) == 1
s = s.replace(a, """        List<String> invalid = new ArrayList<>();
        List<String> paid = new ArrayList<>();
        List<String> plb = new ArrayList<>();
""")
s = s.replace("                    paidSum = paidSum.add(dec(s.el(4)));\n", "                    paid.add(s.el(4));\n")
s = s.replace("                        plbSum = plbSum.add(dec(s.el(i + 1)));\n", "                        plb.add(s.el(i + 1));\n")
a = """                case "TRN" -> m.put("traceNumber", s.el(2));"""
assert s.count(a) == 1
s = s.replace(a, a + """
                case "CUR" -> m.put("currency", s.el(2)); // CUR02: ISO 4217 code of the remittance (default USD)""")
a = """        BigDecimal bpr = dec(String.valueOf(m.getOrDefault("paymentAmount", "0")));
        m.put("balanced", bpr.compareTo(paidSum.subtract(plbSum)) == 0);"""
assert s.count(a) == 1
s = s.replace(a, """        String ccy = currency(m);
        Money bpr = amount(String.valueOf(m.getOrDefault("paymentAmount", "0")), ccy, "BPR02", invalid);
        Money paidSum = sum(paid, ccy, "CLP04", invalid);
        Money plbSum = sum(plb, ccy, "PLB", invalid);
        // BPR02 = sum(CLP04) - sum(PLB), exactly in minor units; any malformed amount makes the file unbalanced
        m.put("balanced", invalid.isEmpty() && bpr != null && paidSum != null && plbSum != null
                && bpr.equals(paidSum.minus(plbSum)));
        m.put("invalidAmounts", invalid);""")

# 277 decoder: needs an invalid list; find its balanceClaims call (first occurrence) and declare before
first = s.index("        balanceClaims(claims, currency(m), invalid);\n")
s = s[:first] + "        List<String> invalid = new ArrayList<>();\n" + s[first:]
# also expose invalid in the 277 map right after its call
s = s.replace("        balanceClaims(claims, currency(m), invalid);\n        m.put(\"claims\", claims);\n        if (!claims.isEmpty()) {\n            m.putAll(",
              "        balanceClaims(claims, currency(m), invalid);\n        m.put(\"invalidAmounts\", invalid);\n        m.put(\"claims\", claims);\n        if (!claims.isEmpty()) {\n            m.putAll(", 1)

old_dec = """    private static BigDecimal dec(String v) {
        try {
            return v == null || v.isBlank() ? BigDecimal.ZERO : new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }"""
assert s.count(old_dec) == 1
s = s.replace(old_dec, """    /** Currency of the transaction: CUR02 when present, else USD (the X12 HIPAA default). */
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
    }""")
s = s.replace("import java.math.BigDecimal;\n", "import org.sangam.core.money.Money;\n\n", 1)
assert "BigDecimal" not in s, [l for l in s.splitlines() if "BigDecimal" in l]
open(p, "w", encoding="utf-8", newline="").write(s)
print("ok")
