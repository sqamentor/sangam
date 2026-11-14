import sys
root = sys.argv[1] + "/sangam-programme/src/main/java/org/sangam/programme/"
s = ""


def load(p):
    global s, path
    path = root + p
    s = open(path, encoding="utf-8").read()


def save():
    open(path, "w", encoding="utf-8", newline="").write(s)


def rep(a, b, n=1):
    global s
    assert s.count(a) == n, (path, a, s.count(a))
    s = s.replace(a, b)


def imp():
    global s
    s = s.replace("import java.math.BigDecimal;\n", "import java.math.BigDecimal;\nimport org.sangam.core.money.Money;\n", 1)


load("ProgrammeSchema.java")
rep("budget {dec}, currency {str64}", "budget {money}, currency {str64}")
rep("allocated {dec}, released {dec}, committed_amt {dec}, expended {dec}, currency {str64}",
    "allocated {money}, released {money}, committed_amt {money}, expended {money}, currency {str64}")
save()

# ------------------------------------------------------------------ ProgrammeService
load("programme/ProgrammeService.java")
rep("LocalDate endsOn, Status status, BigDecimal budget, String currency, List<String> indicators,",
    "LocalDate endsOn, Status status, Money budget, String currency, List<String> indicators,")
rep("Status.DRAFT.name(), in.budget(), in.currency(), Db.json(",
    "Status.DRAFT.name(), minor(in.budget(), in.currency()), in.currency(), Db.json(")
rep("in.budget() == null ? p.budget() : in.budget(), in.currency() == null ? p.currency() : in.currency(),",
    "budgetMinor(in, p), in.currency() == null ? p.currency() : in.currency(),")
rep('Status.valueOf(rs.getString("status")), rs.getBigDecimal("budget"), rs.getString("currency"), inds,',
    'Status.valueOf(rs.getString("status")), Db.money(rs, "budget"), rs.getString("currency"), inds,')
rep("""    @SuppressWarnings({"unchecked", "rawtypes"})
    static Programme map(""", """    /** API boundary (SD-398): a budget is exact in its currency's minor units; more fraction digits are rejected. */
    static Long minor(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        if (currency == null) {
            throw ProgrammeException.invalid("budget needs a currency (ISO 4217)");
        }
        try {
            return Money.ofMajor(amount, currency).minor();
        } catch (RuntimeException e) {
            throw ProgrammeException.invalid("budget: " + e.getMessage());
        }
    }

    private static Long budgetMinor(ProgrammeInput in, Programme p) {
        String ccy = in.currency() == null ? p.currency() : in.currency();
        if (in.budget() != null) {
            return minor(in.budget(), ccy);
        }
        if (p.budget() != null && !p.budget().currencyCode().equals(ccy)) {
            throw ProgrammeException.invalid("changing the currency requires restating the budget");
        }
        return p.budget() == null ? null : p.budget().minor();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Programme map(""")
imp()
save()

# ------------------------------------------------------------------ Db.money helper
load("support/Db.java")
i = s.rindex("}")
s = s[:i] + """
    /** A {@code {money}} column (BIGINT minor units) in the row's {@code currency} column (SD-398). */
    public static org.sangam.core.money.Money money(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : org.sangam.core.money.Money.ofMinor(v, rs.getString("currency"));
    }
}
"""
save()

# ------------------------------------------------------------------ ports
load("ports/ProgrammePorts.java")
rep("        Map<String, BigDecimal[]> actuals(Connection c, String tenantId, String programmeId, String periodId, String orgUnit);",
    "        /** Per head: {allocated, released, committed, expended} in the programme currency (exact minor units). */\n"
    "        Map<String, Money[]> actuals(Connection c, String tenantId, String programmeId, String periodId, String orgUnit);")
imp()
if "import org.sangam.core.money.Money;" not in s:
    s = s.replace("package org.sangam.programme.ports;\n", "package org.sangam.programme.ports;\n\nimport org.sangam.core.money.Money;\n", 1)
save()

# ------------------------------------------------------------------ ProgressService
load("progress/ProgressService.java")
rep("""    public record Financial(String programmeId, String periodId, String orgUnit, String head, BigDecimal allocated, BigDecimal released,
                            BigDecimal committed, BigDecimal expended, String currency, String source, String updatedBy, Instant updatedAt) {""",
    """    /** Amounts are exact {@link Money} in {@code currency} (JSON: MoneyAmount decimal strings). */
    public record Financial(String programmeId, String periodId, String orgUnit, String head, Money allocated, Money released,
                            Money committed, Money expended, String currency, String source, String updatedBy, Instant updatedAt) {""")
rep("""                           BigDecimal gapPct, BigDecimal allocated, BigDecimal released, BigDecimal expended) {""",
    """                           BigDecimal gapPct, Money allocated, Money released, Money expended) {""")
rep("""                                     BigDecimal allocated, BigDecimal released, BigDecimal committed, BigDecimal expended, String currency,
                                     String actor) {""", """                                     Money allocated, Money released, Money committed, Money expended, String currency,
                                     String actor) {""")
rep("""        for (BigDecimal v : new BigDecimal[]{allocated, released, committed, expended}) {
            if (v != null && v.signum() < 0) {
                throw ProgrammeException.invalid("financial amounts must not be negative");
            }
        }""", """        for (Money v : new Money[]{allocated, released, committed, expended}) {
            if (v != null && v.signum() < 0) {
                throw ProgrammeException.invalid("financial amounts must not be negative");
            }
            if (v != null && !v.currencyCode().equals(currency)) {
                throw ProgrammeException.invalid("financial amount in " + v.currencyCode() + " but the row currency is " + currency);
            }
        }""")
rep("""        for (Map.Entry<String, BigDecimal[]> e : financialSource.actuals(c, t, programmeId, periodId, orgUnit).entrySet()) {
            BigDecimal[] v = e.getValue();
            write(c, t, programmeId, periodId, orgUnit, e.getKey(), at(v, 0), at(v, 1), at(v, 2), at(v, 3),
                    programmes.get(c, t, programmeId).currency(), "IMPORT", actor);""", """        String ccy = programmes.get(c, t, programmeId).currency();
        for (Map.Entry<String, Money[]> e : financialSource.actuals(c, t, programmeId, periodId, orgUnit).entrySet()) {
            Money[] v = e.getValue();
            for (Money x : v) {
                if (x != null && !x.currencyCode().equals(ccy)) {
                    throw ProgrammeException.invalid("imported " + x.currencyCode() + " amount for a " + ccy + " programme");
                }
            }
            write(c, t, programmeId, periodId, orgUnit, e.getKey(), at(v, 0), at(v, 1), at(v, 2), at(v, 3), ccy, "IMPORT", actor);""")
rep("""        List<Object[]> sums = Jdbc.query(c, "SELECT head, SUM(allocated) AS a, SUM(released) AS r, SUM(committed_amt) AS cm, SUM(expended) AS e,"
                        + " MAX(currency) AS cur FROM PG_FINANCIAL WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit IN "
                        + Db.in(kids.size()) + " GROUP BY head",
                rs -> new Object[]{rs.getString("head"), rs.getBigDecimal("a"), rs.getBigDecimal("r"), rs.getBigDecimal("cm"),
                        rs.getBigDecimal("e"), rs.getString("cur")}, params.toArray());
        for (Object[] s : sums) {
            write(c, t, programmeId, periodId, node, (String) s[0], (BigDecimal) s[1], (BigDecimal) s[2], (BigDecimal) s[3], (BigDecimal) s[4],
                    (String) s[5], "ROLLUP", null);
            n[0]++;
        }""", """        // exact integer sums per head AND currency: amounts in different currencies are never added (the old MAX(currency)
        // labelled a mixed sum with one of them)
        List<Object[]> sums = Jdbc.query(c, "SELECT head, currency, SUM(allocated) AS a, SUM(released) AS r, SUM(committed_amt) AS cm,"
                        + " SUM(expended) AS e, COUNT(allocated) AS na, COUNT(released) AS nr, COUNT(committed_amt) AS ncm,"
                        + " COUNT(expended) AS ne FROM PG_FINANCIAL WHERE tenant_id=? AND programme_id=? AND period_id=? AND org_unit IN "
                        + Db.in(kids.size()) + " GROUP BY head, currency ORDER BY head",
                rs -> {
                    String cur = rs.getString("currency");
                    return new Object[]{rs.getString("head"), cur, sum(rs, "a", "na", cur), sum(rs, "r", "nr", cur),
                            sum(rs, "cm", "ncm", cur), sum(rs, "e", "ne", cur)};
                }, params.toArray());
        Set<String> heads = new HashSet<>();
        for (Object[] s : sums) {
            if (!heads.add((String) s[0])) {
                throw ProgrammeException.invalid("cannot roll up head " + s[0] + " of " + node + ": children report different currencies");
            }
        }
        for (Object[] s : sums) {
            write(c, t, programmeId, periodId, node, (String) s[0], (Money) s[2], (Money) s[3], (Money) s[4], (Money) s[5],
                    (String) s[1], "ROLLUP", null);
            n[0]++;
        }""")
rep("""    private void write(Connection c, String t, String programmeId, String periodId, String orgUnit, String head, BigDecimal allocated,
                       BigDecimal released, BigDecimal committed, BigDecimal expended, String currency, String source, String actor) {""",
    """    private static Money sum(ResultSet rs, String col, String count, String currency) throws SQLException {
        return rs.getLong(count) == 0 ? null : Money.ofMinor(rs.getLong(col), currency);
    }

    private static Long minor(Money m) {
        return m == null ? null : m.minor();
    }

    private void write(Connection c, String t, String programmeId, String periodId, String orgUnit, String head, Money allocated,
                       Money released, Money committed, Money expended, String currency, String source, String actor) {""")
rep("""                t, programmeId, periodId, orgUnit, head, allocated, released, committed, expended, currency, source, actor);""",
    """                t, programmeId, periodId, orgUnit, head, minor(allocated), minor(released), minor(committed), minor(expended), currency,
                source, actor);""")
rep("""        BigDecimal alloc = BigDecimal.ZERO;
        BigDecimal rel = BigDecimal.ZERO;
        BigDecimal exp = BigDecimal.ZERO;
        List<Financial> rows = financial(c, t, programmeId, periodId, orgUnit);""", """        List<Financial> rows = financial(c, t, programmeId, periodId, orgUnit);
        String ccy = rows.isEmpty() ? prog.currency() : rows.get(0).currency();
        Money alloc = ccy == null ? null : Money.zero(ccy);
        Money rel = alloc;
        Money exp = alloc;""")
rep("""                alloc = alloc.add(nz(f.allocated()));
                rel = rel.add(nz(f.released()));
                exp = exp.add(nz(f.expended()));""", """                alloc = alloc.plus(nz(f.allocated(), f.currency())); // exact; a mixed currency fails here
                rel = rel.plus(nz(f.released(), f.currency()));
                exp = exp.plus(nz(f.expended(), f.currency()));""")
rep("""    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null : part.multiply(HUNDRED).divide(whole, MC).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal at(BigDecimal[] v, int i) {""", """    /** A percentage of two money amounts (a ratio, not money): computed from exact minor units, shown to 2 places. */
    private static BigDecimal pct(Money part, Money whole) {
        return whole == null || whole.signum() == 0 ? null
                : BigDecimal.valueOf(part.minor()).multiply(HUNDRED).divide(BigDecimal.valueOf(whole.minor()), MC)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static Money nz(Money v, String currency) {
        return v == null ? Money.zero(currency) : v;
    }

    private static Money at(Money[] v, int i) {""")
rep("""                rs.getBigDecimal("allocated"), rs.getBigDecimal("released"), rs.getBigDecimal("committed_amt"), rs.getBigDecimal("expended"),""",
    """                Db.money(rs, "allocated"), Db.money(rs, "released"), Db.money(rs, "committed_amt"), Db.money(rs, "expended"),""")
imp()
save()

# ------------------------------------------------------------------ REST adapter: wire BigDecimal + currency -> Money
load("rest/ProgrammeRestAdapter.java")
rep("""            return m.progress().recordFinancial(c, ctx.tenant(), r.programmeId(), r.periodId(), r.orgUnit(), r.head(), r.allocated(), r.released(),
                    r.committed(), r.expended(), r.currency(), ctx.actor());""", """            // controller boundary: decimal + currency on the wire, exact Money inside (SD-398)
            return m.progress().recordFinancial(c, ctx.tenant(), r.programmeId(), r.periodId(), r.orgUnit(), r.head(),
                    money(r.allocated(), r.currency()), money(r.released(), r.currency()), money(r.committed(), r.currency()),
                    money(r.expended(), r.currency()), r.currency(), ctx.actor());""")
i = s.rindex("}")
s = s[:i] + """
    private static org.sangam.core.money.Money money(java.math.BigDecimal v, String currency) {
        if (v == null) {
            return null;
        }
        if (currency == null) {
            throw org.sangam.programme.ProgrammeException.invalid("financial amounts need a currency (ISO 4217)");
        }
        try {
            return org.sangam.core.money.Money.ofMajor(v, currency);
        } catch (RuntimeException e) {
            throw org.sangam.programme.ProgrammeException.invalid(e.getMessage());
        }
    }
}
"""
save()
print("ok")
load("dashboard/DashboardFeed.java")
rep("    private static String plain(BigDecimal v) {", """    private static String plain(org.sangam.core.money.Money v) {
        return v == null ? null : v.toPlainString();
    }

    private static String plain(BigDecimal v) {""")
save()
print("ok2")
