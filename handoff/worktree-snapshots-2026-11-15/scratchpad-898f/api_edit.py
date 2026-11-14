import sys
root = sys.argv[1] + "/"
s = ""


def load(p):
    global s, path
    path = root + p
    s = open(path, encoding="utf-8").read()


def save():
    open(path, "w", encoding="utf-8", newline="").write(s)


def rep(a, b, n=1):
    global s
    assert s.count(a) == n, (path, a[:120], s.count(a))
    s = s.replace(a, b)


# ------------------------------------------------------------------ LedgerRequest: typed money factory
load("sangam-ledger/src/main/java/org/sangam/ledger/LedgerRequest.java")
rep("""    public static LedgerRequest of(String op, AccountKey account, BigDecimal amount, String reference) {""",
    """    /**
     * A money operation (SD-398): the amount is exact {@link org.sangam.core.money.Money} and its currency is the
     * transaction currency. Use this for LIMIT / RECEIVABLE ledgers; {@link #of} remains for QUANTITY ledgers.
     */
    public static LedgerRequest money(String op, AccountKey account, org.sangam.core.money.Money amount, String reference) {
        return new LedgerRequest(op, account, null, amount.toMajor(), amount.currencyCode(), null, reference, null, null, null,
                false, false, false, null);
    }

    /** Replaces the amount (and currency) with an exact money amount. */
    public LedgerRequest withMoney(org.sangam.core.money.Money m) {
        return new LedgerRequest(operation, account, target, m.toMajor(), m.currencyCode(), rateType, reference, auditRef, actor,
                asOf, fromCommitment, fromEncumbrance, holdIfShort, attrs);
    }

    public static LedgerRequest of(String op, AccountKey account, BigDecimal amount, String reference) {""")
save()

# ------------------------------------------------------------------ Balance / LedgerService typed reads
load("sangam-ledger/src/main/java/org/sangam/ledger/Balance.java")
rep("""    public BigDecimal bucket(Bucket b) {""", """    /** {@link #available()} as exact money in the account currency (money ledgers only; SD-398). */
    public org.sangam.core.money.Money availableMoney() {
        if (currency == null) {
            throw new IllegalStateException("account " + key + " has no currency (a quantity account)");
        }
        return org.sangam.core.money.Money.ofMajor(available(), currency);
    }

    public BigDecimal bucket(Bucket b) {""")
save()

load("sangam-ledger/src/main/java/org/sangam/ledger/LedgerService.java")
rep("""    public BigDecimal available(AccountKey key) {""", """    /** Available amount of a money account as exact {@link org.sangam.core.money.Money} (account or ledger currency). */
    public org.sangam.core.money.Money availableMoney(AccountKey key) {
        String ccy = store.get(key).map(Balance::currency).orElse(definition(key.tenant(), key.ledger()).currency());
        if (ccy == null) {
            throw new IllegalStateException("ledger " + key.ledger() + " has no currency (a quantity ledger)");
        }
        return org.sangam.core.money.Money.ofMajor(available(key), ccy);
    }

    public BigDecimal available(AccountKey key) {""")
save()

# ------------------------------------------------------------------ BillingService: Money everywhere money flows
load("sangam-billing/src/main/java/org/sangam/billing/BillingService.java")
rep("""        /** Verifies a payment reported by a channel; returns the confirmed amount (or null if not confirmed). */
        BigDecimal confirm(String tenant, String channel, String paymentRef, BigDecimal amount);

        /** Initiates a refund; returns the rail reference. */
        String refund(String tenant, String channel, String paymentRef, BigDecimal amount, String operationId);""",
    """        /** Verifies a payment reported by a channel; returns the confirmed amount (or null if not confirmed). */
        Money confirm(String tenant, String channel, String paymentRef, Money amount);

        /** Initiates a refund; returns the rail reference. */
        String refund(String tenant, String channel, String paymentRef, Money amount, String operationId);""")
rep("""    public record Receipt(String number, String consumer, BigDecimal amount, Map<String, BigDecimal> allocation,""",
    """    public record Receipt(String number, String consumer, Money amount, Map<String, Money> allocation,""")
rep("""                           BigDecimal amount, String actor) {
        BigDecimal confirmed = payments == null ? amount : payments.confirm(tenant, channel, paymentRef, amount);""",
    """                           Money amount, String actor) {
        Money confirmed = payments == null ? amount : payments.confirm(tenant, channel, paymentRef, amount);
        if (confirmed != null && !confirmed.currencyCode().equals(amount.currencyCode())) {
            throw new IllegalStateException("payment " + paymentRef + " confirmed in " + confirmed.currencyCode());
        }""")
rep("""        BigDecimal left = confirmed;
        Map<String, BigDecimal> alloc = new LinkedHashMap<>();""", """        Money left = confirmed;
        Map<String, Money> alloc = new LinkedHashMap<>();""")
rep("""            BigDecimal take = b.available().min(left);
            posts.add(ledger.apply(new LedgerRequest("CONSUME", b.key(), null, take, null, null,""",
    """            Money take = b.availableMoney().min(left);
            if (take.signum() <= 0) {
                continue;
            }
            posts.add(ledger.apply(new LedgerRequest("CONSUME", b.key(), null, take.toMajor(), take.currencyCode(), null,""")
rep("""            left = left.subtract(take);""", """            left = left.minus(take);""")
rep("""    public String refund(AccountKey demandAccount, String channel, String paymentRef, BigDecimal amount, String operationId,
                         String actor) {
        ledger.apply(new LedgerRequest("REVERSE", demandAccount, null, amount, null, null,""",
    """    public String refund(AccountKey demandAccount, String channel, String paymentRef, Money amount, String operationId,
                         String actor) {
        ledger.apply(new LedgerRequest("REVERSE", demandAccount, null, amount.toMajor(), amount.currencyCode(), null,""")
rep("""    public LedgerService.Result waive(AccountKey demandAccount, BigDecimal amount, String orderRef, String actor) {
        return ledger.apply(new LedgerRequest("WRITE_OFF", demandAccount, null, amount, null, null,""",
    """    public LedgerService.Result waive(AccountKey demandAccount, Money amount, String orderRef, String actor) {
        return ledger.apply(new LedgerRequest("WRITE_OFF", demandAccount, null, amount.toMajor(), amount.currencyCode(), null,""")
save()

# ------------------------------------------------------------------ BillingConnector: strict parse at the boundary
load("sangam-billing/src/main/java/org/sangam/billing/BillingConnector.java")
rep("""                            new BigDecimal(r.str("amount")), r.str("actor"));""",
    """                            money(tenant, ledgerId, r.str("amount")), r.str("actor"));""")
rep("""                    LedgerService.Result res = billing.waive(k, new BigDecimal(r.str("amount")), r.str("orderRef"), r.str("actor"));""",
    """                    LedgerService.Result res = billing.waive(k, money(tenant, ledgerId, r.str("amount")), r.str("orderRef"), r.str("actor"));""")
rep("""    private BigDecimal outstanding(String tenant, String ledgerId, String consumer) {""",
    """    /** API boundary (SD-398): a decimal string in the ledger currency, rejected if finer than its minor unit. */
    private org.sangam.core.money.Money money(String tenant, String ledgerId, String amount) {
        String ccy = ledger.definition(tenant, ledgerId).currency();
        if (ccy == null) {
            throw new IllegalArgumentException("ledger " + ledgerId + " has no currency");
        }
        return org.sangam.core.money.Money.parse(amount, ccy);
    }

    private BigDecimal outstanding(String tenant, String ledgerId, String consumer) {""")
save()

# ------------------------------------------------------------------ server payment port
load("sangam-server/src/main/java/org/sangam/server/bridge/BillingPaymentPort.java")
rep("""    public BigDecimal confirm(String tenant, String channel, String paymentRef, BigDecimal amount) {""",
    """    public org.sangam.core.money.Money confirm(String tenant, String channel, String paymentRef, org.sangam.core.money.Money amount) {""")
rep("""                return new BigDecimal(a.toString());""",
    """                // the rail's confirmed amount, exact in the collection currency (a sub-paisa figure is an error)
                return org.sangam.core.money.Money.parse(a.toString(), amount.currencyCode());""")
rep("""    public String refund(String tenant, String channel, String paymentRef, BigDecimal amount, String operationId) {""",
    """    public String refund(String tenant, String channel, String paymentRef, org.sangam.core.money.Money amount, String operationId) {""")
save()

# ------------------------------------------------------------------ REST billing refund: decimal string + ledger currency -> Money
load("sangam-rest/src/main/java/org/sangam/rest/modules/billing/BillingController.java")
rep("""        String rail = billing.refund(demand, body.channel(), body.paymentRef(), decimal(body.amount()), operationId,""",
    """        String rail = billing.refund(demand, body.channel(), body.paymentRef(),
                money(body.amount(), definitions.ledger().definition(ctx.tenantId(), ledger).currency()), operationId,""")
rep("""    private static BigDecimal decimal(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (RuntimeException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "amount must be a decimal string (R33)");
        }
    }""", """    /** Controller boundary (MoneyAmount, SD-398): strict decimal string in the ledger currency. */
    private static org.sangam.core.money.Money money(String s, String currency) {
        try {
            return org.sangam.core.money.Money.parse(s, currency);
        } catch (RuntimeException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "amount must be a decimal string in " + currency
                    + " with at most its minor-unit digits (MoneyAmount): " + e.getMessage());
        }
    }""")
save()

# ------------------------------------------------------------------ REST ledger operation
load("sangam-rest/src/main/java/org/sangam/rest/modules/ledger/LedgerController.java")
rep("""                body.target() == null ? null : body.target().key(ctx.tenantId(), ledger), decimal(body.amount()),""",
    """                body.target() == null ? null : body.target().key(ctx.tenantId(), ledger),
                amount(body.amount(), body.currency() != null ? body.currency()
                        : service().definition(ctx.tenantId(), ledger).currency()),""")
rep("""        LedgerService.Result res = service().apply(r);
        return Lists.okRef(reference, ResultView.of(res));""", """        LedgerService.Result res;
        try {
            res = service().apply(r);
        } catch (IllegalArgumentException e) { // SG-22 / SG-23: sub-minor-unit or non-positive amount
            throw ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage());
        }
        return Lists.okRef(reference, ResultView.of(res));""")
rep("""    private static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "amount must be a decimal string (R33)");
        }
    }""", """    /**
     * Controller boundary (MoneyAmount, SD-398): a money ledger takes a strict decimal string in its currency (more
     * fraction digits than the minor unit are rejected); a quantity ledger a strict plain decimal. A missing amount is
     * an error, never zero.
     */
    static BigDecimal amount(String s, String currency) {
        if (s == null || s.isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "amount is required (MoneyAmount decimal string)");
        }
        boolean money;
        try {
            money = currency != null && java.util.Currency.getInstance(currency).getDefaultFractionDigits() >= 0;
        } catch (IllegalArgumentException notIso) {
            money = false; // a quantity unit code
        }
        if (money) {
            try {
                return org.sangam.core.money.Money.parse(s, currency).toMajor();
            } catch (RuntimeException e) {
                throw ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage());
            }
        }
        if (!s.strip().matches("[+-]?\\\\d+(\\\\.\\\\d+)?")) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "amount must be a plain decimal string (R33)");
        }
        return new BigDecimal(s.strip());
    }""")
save()
print("ok")
