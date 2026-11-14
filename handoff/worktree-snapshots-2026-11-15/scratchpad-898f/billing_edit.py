import sys
root = sys.argv[1]
s = ""


def rep(a, b):
    global s
    assert s.count(a) == 1, a
    s = s.replace(a, b)


p = root + "/sangam-billing/src/main/java/org/sangam/billing/TariffEngine.java"
s = open(p, encoding="utf-8").read()
rep("import org.sangam.config.common.Tree;\n", "import org.sangam.config.common.Tree;\nimport org.sangam.core.money.Money;\n")
rep(""" *     rounding: {mode: HALF_UP, scale: 0}
 *     penalty: {graceDays: 15, kind: PERCENT_PER_MONTH, rate: 1.5, decisionKey: null, head: "0215-01-800"}
 * </pre>
 */""", """ *     rounding: {mode: HALF_UP, scale: 0}
 *     penalty: {graceDays: 15, kind: PERCENT_PER_MONTH, rate: 1.5, decisionKey: null, head: "0215-01-800"}
 * </pre>
 *
 * <p><b>Rounding rule (SD-398, SG-20).</b> Every line (slab, percentage, decision, minimum) is computed at full
 * precision. The document is rounded ONCE: the exact sum of the lines is rounded to {@code rounding.scale} decimals of
 * the tariff {@code currency} (default: the currency's minor unit) with {@code rounding.mode} (tenant configuration,
 * default HALF_UP), and that total is allocated back onto the lines by the largest-remainder method
 * ({@link Money#roundDocument}; ties go to the earlier line). So {@code sum of line amounts == invoice total} exactly,
 * and the total never drifts by per-line rounding. Penalty / interest is likewise computed exactly and rounded once.
 */""")
rep("""    /** One computed line. */
    public record Line(String component, String head, BigDecimal quantity, BigDecimal amount, String basis) {
    }""", """    /**
     * One computed line.
     *
     * @param exact  the full-precision amount (major units), before the document rounding
     * @param money  the line's share of the rounded document total (integer minor units)
     */
    public record Line(String component, String head, BigDecimal quantity, BigDecimal exact, Money money, String basis) {

        /** The rounded amount in major units (exact representation of {@link #money}). */
        public BigDecimal amount() {
            return money.toMajor();
        }
    }

    /** Currency of a tariff (required: a money amount without a currency is not money). */
    public static String currency(Map<String, Object> tariff) {
        String c = Tree.str(tariff.get("currency"), null);
        if (c == null) {
            throw new IllegalArgumentException("tariff '" + tariff.get("id") + "' has no currency (ISO 4217)");
        }
        return c;
    }

    /** {@code rounding.mode} of the tariff (tenant configuration), default HALF_UP. */
    public static RoundingMode roundingMode(Map<String, Object> tariff) {
        return RoundingMode.valueOf(Tree.str(Tree.map(tariff.get("rounding")).get("mode"), "HALF_UP").toUpperCase());
    }""")
rep("""                    amt = of.multiply(Tree.decimal(c.get("percent"), BigDecimal.ZERO)).divide(BigDecimal.valueOf(100), 6,
                            RoundingMode.HALF_UP);""", """                    amt = of.multiply(Tree.decimal(c.get("percent"), BigDecimal.ZERO)).movePointLeft(2); // exact""")
rep("""                              Map<String, Object> attrs) {
        List<Line> lines = new ArrayList<>();""", """                              Map<String, Object> attrs) {
        String currency = currency(tariff);
        List<Raw> lines = new ArrayList<>();""")
rep("""            lines.add(new Line(id, head, q, amt, basis));
        }
        BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal min = Tree.decimal(tariff.get("minimum"), null);
        if (min != null && total.compareTo(min) < 0) {
            lines.add(new Line("MINIMUM", Tree.str(tariff.get("minimumHead"), null), BigDecimal.ONE, min.subtract(total),
                    "minimum charge " + min));
        }
        Map<String, Object> rnd = Tree.map(tariff.get("rounding"));
        int scale = Tree.integer(rnd.get("scale"), 2);
        RoundingMode mode = RoundingMode.valueOf(Tree.str(rnd.get("mode"), "HALF_UP"));
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            out.add(new Line(l.component(), l.head(), l.quantity(), l.amount().setScale(scale, mode), l.basis()));
        }
        return out;
    }""", """            lines.add(new Raw(id, head, q, amt, basis));
        }
        BigDecimal total = lines.stream().map(Raw::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal min = Tree.decimal(tariff.get("minimum"), null);
        if (min != null && total.compareTo(min) < 0) {
            lines.add(new Raw("MINIMUM", Tree.str(tariff.get("minimumHead"), null), BigDecimal.ONE, min.subtract(total),
                    "minimum charge " + min));
        }
        // the ONE rounding step of the document: total rounded once, then allocated back (largest remainder)
        Map<String, Object> rnd = Tree.map(tariff.get("rounding"));
        int scale = Tree.integer(rnd.get("scale"), -1);
        Money.Rounded doc = Money.roundDocument(lines.stream().map(Raw::amount).toList(), currency, roundingMode(tariff), scale);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Raw l = lines.get(i);
            out.add(new Line(l.component(), l.head(), l.quantity(), l.amount(), doc.lines().get(i), l.basis()));
        }
        return out;
    }

    private record Raw(String component, String head, BigDecimal quantity, BigDecimal amount, String basis) {
    }""")
rep("""    /** Penalty / interest on an overdue amount, from the tariff's {@code penalty} block (or its decision). */
    public BigDecimal penalty(String tenant, Map<String, Object> tariff, BigDecimal overdue, LocalDate dueDate, LocalDate asOf) {
        Map<String, Object> p""", """    /**
     * Penalty / interest on an overdue amount, from the tariff's {@code penalty} block (or its decision): computed at
     * full precision and rounded ONCE to the currency minor unit with the tariff rounding mode.
     */
    public Money penalty(String tenant, Map<String, Object> tariff, BigDecimal overdue, LocalDate dueDate, LocalDate asOf) {
        return Money.round(penaltyExact(tenant, tariff, overdue, dueDate, asOf), currency(tariff), roundingMode(tariff));
    }

    /** Full-precision penalty (major units), before rounding. */
    BigDecimal penaltyExact(String tenant, Map<String, Object> tariff, BigDecimal overdue, LocalDate dueDate, LocalDate asOf) {
        Map<String, Object> p""")
rep("""            case "PERCENT_PER_MONTH" -> overdue.multiply(rate).multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(3000), 2, RoundingMode.HALF_UP);
            case "PERCENT_PER_ANNUM" -> overdue.multiply(rate).multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP);
            case "PERCENT_ONCE" -> overdue.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);""", """            // /3000 and /36500 need not terminate: 34 significant digits (DECIMAL128) is "full precision" here; the
            // single rounding to minor units happens in penalty()
            case "PERCENT_PER_MONTH" -> overdue.multiply(rate).multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(3000), java.math.MathContext.DECIMAL128);
            case "PERCENT_PER_ANNUM" -> overdue.multiply(rate).multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(36500), java.math.MathContext.DECIMAL128);
            case "PERCENT_ONCE" -> overdue.multiply(rate).movePointLeft(2);""")
open(p, "w", encoding="utf-8", newline="").write(s)

p = root + "/sangam-billing/src/main/java/org/sangam/billing/BillingService.java"
s = open(p, encoding="utf-8").read()
rep("""    public record Invoice(String number, String consumer, String cycle, LocalDate dueDate, List<TariffEngine.Line> lines,
                          BigDecimal total, List<LedgerService.Result> postings) {
    }""", """    /** {@code total} is the document total rounded once; the line moneys sum to it exactly (SG-20). */
    public record Invoice(String number, String consumer, String cycle, LocalDate dueDate, List<TariffEngine.Line> lines,
                          Money total, List<LedgerService.Result> postings) {
    }""")
rep("import org.sangam.core.time.BusinessClock;\n", "import org.sangam.core.money.Money;\nimport org.sangam.core.time.BusinessClock;\n")
rep("""        BigDecimal total = BigDecimal.ZERO;
        for (TariffEngine.Line l : lines) {
            if (l.amount().signum() == 0) {""", """        Money total = Money.zero(TariffEngine.currency(tariff));
        for (TariffEngine.Line l : lines) {
            if (l.money().isZero()) {""")
rep("""            total = total.add(l.amount());""", """            total = total.plus(l.money());""")
rep("""            BigDecimal p = tariffs.penalty(tenant, tariff, b.available(), due, asOf);""", """            Money p = tariffs.penalty(tenant, tariff, b.available(), due, asOf);""")
rep("""                out.add(ledger.apply(new LedgerRequest("ALLOCATE", k, null, p, null, null,""", """                out.add(ledger.apply(new LedgerRequest("ALLOCATE", k, null, p.toMajor(), null, null,""")
open(p, "w", encoding="utf-8", newline="").write(s)
print("ok")
