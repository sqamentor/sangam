import sys
root = sys.argv[1] + "/sangam-registry/src/main/java/org/sangam/registry/"
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


# ---------------------------------------------------------------- schema
load("RegistrySchema.java")
rep("              acquisition_value   NUMERIC(24,6),\n              residual_value      NUMERIC(24,6),",
    "              acquisition_value   {money},\n              residual_value      {money},")
rep("              cost           NUMERIC(24,6),\n", "              cost           {money},\n              currency       {str64},\n")
save()

# ---------------------------------------------------------------- Asset
load("asset/Asset.java")
rep("import org.sangam.registry.support.Db;\n", "import org.sangam.core.money.Money;\nimport org.sangam.registry.support.Db;\n")
rep("                    BigDecimal acquisitionValue, BigDecimal residualValue, String currency,",
    "                    Money acquisitionValue, Money residualValue, String currency,")
rep('Db.decimal(rs, "acquisition_value"), Db.decimal(rs, "residual_value"),\n                rs.getString("currency"),',
    'money(rs, "acquisition_value"), money(rs, "residual_value"),\n                rs.getString("currency"),')
rep("""    public Map<String, Object> toMap() {""", """    /** A {@code {money}} column (BIGINT minor units) in the row's currency (SD-398). */
    static Money money(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : Money.ofMinor(v, rs.getString("currency"));
    }

    /** Wire form of an amount: decimal string in major units (MoneyAmount), next to {@code currency}. */
    static String wire(Money m) {
        return m == null ? null : m.toPlainString();
    }

    public Map<String, Object> toMap() {""")
rep('"acquisitionValue", acquisitionValue,\n                "residualValue", residualValue,',
    '"acquisitionValue", wire(acquisitionValue),\n                "residualValue", wire(residualValue),')
save()

# ---------------------------------------------------------------- WorkOrder
load("asset/WorkOrder.java")
rep("                        BigDecimal cost, Map<String, Object> findings,", "                        Money cost, Map<String, Object> findings,")
rep(' meter_at_done, cost, findings_json,', ' meter_at_done, cost, currency, findings_json,')
rep('                Db.decimal(rs, "cost"), Json.map(', '                Asset.money(rs, "cost"), Json.map(')
rep('"meterAtDone", meterAtDone, "cost", cost, "findings"', '"meterAtDone", meterAtDone, "cost", Asset.wire(cost),\n                "currency", cost == null ? null : cost.currencyCode(), "findings"')
s = s.replace("import java.math.BigDecimal;\n", "import java.math.BigDecimal;\nimport org.sangam.core.money.Money;\n", 1)
save()

# ---------------------------------------------------------------- AssetService
load("asset/AssetService.java")
rep("""                    inService == null ? null : inService.toString(), Maps.decimal(req, "acquisitionValue"),
                    Maps.decimal(req, "residualValue"), Maps.str(req, "currency"), Maps.integer(req, "usefulLifeMonths"),""",
    """                    inService == null ? null : inService.toString(), minor(req, "acquisitionValue", Maps.str(req, "currency")),
                    minor(req, "residualValue", Maps.str(req, "currency")), Maps.str(req, "currency"), Maps.integer(req, "usefulLifeMonths"),""")
rep(""""acquisitionValue", Maps.decimal(req, "acquisitionValue"), "meters\"""",
    """"acquisitionValue", Maps.str(req, "acquisitionValue"), "currency", Maps.str(req, "currency"), "meters\"""")
rep("""        BigDecimal value = patch.containsKey("acquisitionValue") ? Maps.decimal(patch, "acquisitionValue") : a.acquisitionValue();
        BigDecimal residual = patch.containsKey("residualValue") ? Maps.decimal(patch, "residualValue") : a.residualValue();""",
    """        String ccy = Maps.str(patch, "currency", a.currency());
        if (!java.util.Objects.equals(ccy, a.currency()) && !patch.containsKey("acquisitionValue")
                && (a.acquisitionValue() != null || a.residualValue() != null)) {
            throw RegistryException.invalid("changing the currency requires restating acquisitionValue / residualValue");
        }
        Long value = patch.containsKey("acquisitionValue") ? minor(patch, "acquisitionValue", ccy)
                : a.acquisitionValue() == null ? null : a.acquisitionValue().minor();
        Long residual = patch.containsKey("residualValue") ? minor(patch, "residualValue", ccy)
                : a.residualValue() == null ? null : a.residualValue().minor();""")
rep("""                value, residual, Maps.str(patch, "currency", a.currency()), life,""", """                value, residual, ccy, life,""")
rep("""        BigDecimal bv = bookValue(c, ctx, id, d);
        audit.record(c, ctx, id, "ASSET_DISPOSED", Maps.of("date", d, "method", method, "reason", reason,
                "bookValue", bv, "transition", tr));""", """        Money bv = bookValue(c, ctx, id, d);
        audit.record(c, ctx, id, "ASSET_DISPOSED", Maps.of("date", d, "method", method, "reason", reason,
                "bookValue", bv == null ? null : bv.toPlainString(), "currency", bv == null ? null : bv.currencyCode(),
                "transition", tr));""")
rep("""        BigDecimal cost = a.acquisitionValue();
        BigDecimal residual = a.residualValue();
        if (residual == null && cost != null && p.residualPercent() != null) {
            residual = cost.multiply(p.residualPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }""", """        Money cost = a.acquisitionValue();
        Money residual = a.residualValue();
        if (residual == null && cost != null && p.residualPercent() != null) {
            // one rounding of a derived amount (residual % of cost) to whole minor units
            residual = Money.round(cost.toMajor().multiply(p.residualPercent()).movePointLeft(2), cost.currencyCode(),
                    RoundingMode.HALF_UP);
        }""")
rep("""    public BigDecimal bookValue(Connection c, RegistryContext ctx, String id, LocalDate asOf) {""",
    """    public Money bookValue(Connection c, RegistryContext ctx, String id, LocalDate asOf) {""")
rep("""    static String classRoot(String tenant, String classKey) {""", """    /**
     * Money field of a request as minor units of {@code currency} (API boundary, SD-398): a decimal string or number in
     * major units, rejected if it has more fraction digits than the currency allows or has no currency.
     */
    static Long minor(Map<String, Object> req, String field, String currency) {
        Object v = req.get(field);
        if (v == null) {
            return null;
        }
        if (currency == null) {
            throw RegistryException.invalid(field + " needs a currency (ISO 4217)");
        }
        try {
            return Money.parse(v.toString(), currency).minor();
        } catch (RuntimeException e) {
            throw RegistryException.invalid(field + ": " + e.getMessage());
        }
    }

    static String classRoot(String tenant, String classKey) {""")
s = s.replace("import java.math.BigDecimal;\n", "import java.math.BigDecimal;\nimport org.sangam.core.money.Money;\n", 1)
save()

# ---------------------------------------------------------------- MaintenanceService
load("asset/MaintenanceService.java")
rep("""        BigDecimal cost = Maps.decimal(result, "cost");""", """        // cost in the asset's currency (or the request's), integer minor units (SD-398)
        String ccy = Maps.str(result, "currency", Jdbc.one(c, "SELECT currency FROM REG_ASSET WHERE tenant_id=? AND object_id=?",
                rs -> rs.getString(1), ctx.tenant(), wo.assetId()).orElse(null));
        Long cost = AssetService.minor(result, "cost", ccy);""")
rep("""        params.add(cost);
""", """        params.add(cost);
        params.add(cost == null ? null : ccy);
""")
rep(""" meter_at_done=?, cost=?, findings_json=?""", """ meter_at_done=?, cost=?, currency=?, findings_json=?""")
rep(""""cost", cost, "parts", parts.size()""", """"cost", Maps.str(result, "cost"), "currency", ccy, "parts", parts.size()""")
rep("""        BigDecimal cost = BigDecimal.ZERO;
        for (WorkOrder w : wos) {
            if (w.cost() != null) {
                cost = cost.add(w.cost());
            }""", """        Money cost = null;
        for (WorkOrder w : wos) {
            if (w.cost() != null) {
                cost = cost == null ? w.cost() : cost.plus(w.cost()); // exact; mixed currencies fail loudly
            }""")
rep(""""maintenanceCost", cost);""", """"maintenanceCost", cost == null ? null : cost.toPlainString(),
                "currency", cost == null ? null : cost.currencyCode());""")
s = s.replace("import java.math.BigDecimal;\n", "import java.math.BigDecimal;\nimport org.sangam.core.money.Money;\n", 1)
save()

# ---------------------------------------------------------------- AssetController
load("rest/AssetController.java")
rep("""            return Maps.of("assetId", id, "asOf", d, "bookValue", assets.bookValue(c, ctx, id, d));""",
    """            org.sangam.core.money.Money bv = assets.bookValue(c, ctx, id, d); // MoneyAmount string + currency
            return Maps.of("assetId", id, "asOf", d, "bookValue", bv == null ? null : bv.toPlainString(),
                    "currency", bv == null ? null : bv.currencyCode());""")
save()
print("ok")
