import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read().replace('\r\n', '\n')


def rep(a, b):
    global s
    assert s.count(a) == 1, a[:80]
    s = s.replace(a, b)


rep(''' * Generic billing on a RECEIVABLE ledger: demand raised per invoice line (one account per consumer × head × cycle),
 * collection through a payment connector port, receipts numbered by a series, refunds, waivers / write-offs,
 * penalties and interest. The per-cycle batch (one case per consumer) is a pack (GP-09) calling {@link #invoice}.
 */''', ''' * Generic billing on a RECEIVABLE ledger: demand raised per invoice line (one account per consumer × head × cycle),
 * collection through a payment connector port, receipts numbered by a series, refunds, waivers / write-offs,
 * penalties and interest. The per-cycle batch (one case per consumer) is a pack (GP-09) calling {@link #invoice}.
 *
 * <p><b>Numbers and retries (SL-130, SL-131).</b> Invoice and receipt numbers come from {@link DocumentNumbers},
 * keyed by the document identity (invoice: tenant + ledger + consumer + cycle; receipt: tenant + paymentRef): a
 * retried step or a re-invoice gets the number the document already has and draws none. A receipt stores its
 * allocation plan with its number, and a retried collection replays that plan (each leg's posting reference is
 * {@code rcp:<paymentRef>:<account id>}, so an applied leg is a ledger duplicate) instead of re-planning over the
 * arrears the first attempt already reduced.
 */''')
rep('''    private final NumberSeries series;
    private final PaymentPort payments;''', '''    private final DocumentNumbers numbers;
    private final PaymentPort payments;''')
rep('''    public BillingService(LedgerService ledger, TariffEngine tariffs, NumberSeries series, PaymentPort payments,
                          BusinessClock businessClock) {
        this.businessClock = java.util.Objects.requireNonNull(businessClock, "businessClock");
        this.ledger = ledger;
        this.tariffs = tariffs;
        this.series = series;
        this.payments = payments;
    }''', '''    /**
     * Over a config number series with a node-local document map ({@link DocumentNumbers#overSeries}): single node,
     * tests and tools. Production passes a durable {@link DocumentNumbers} ({@code numbering.RegistryDocumentNumbers}).
     */
    public BillingService(LedgerService ledger, TariffEngine tariffs, NumberSeries series, PaymentPort payments,
                          BusinessClock businessClock) {
        this(ledger, tariffs, DocumentNumbers.overSeries(series), payments, businessClock);
    }

    public BillingService(LedgerService ledger, TariffEngine tariffs, DocumentNumbers numbers, PaymentPort payments,
                          BusinessClock businessClock) {
        this.businessClock = java.util.Objects.requireNonNull(businessClock, "businessClock");
        this.ledger = ledger;
        this.tariffs = tariffs;
        this.numbers = java.util.Objects.requireNonNull(numbers, "numbers");
        this.payments = payments;
    }''')
rep('''    /** Raises the invoice of one consumer for one cycle; idempotent per (consumer, cycle). */''',
    '''    /**
     * Raises the invoice of one consumer for one cycle; idempotent per (ledger, consumer, cycle): a repeat returns the
     * invoice's existing number (SL-130) and posts nothing new (each line's reference is per consumer, cycle, component).
     */''')
rep('''        String no = "INV/" + cycle + "/" + series.next(tenant, "INVOICE", "-", cycle);''',
    '''        String no = numbers.issue(new DocumentNumbers.Request(tenant, DocumentNumbers.Kind.INVOICE,
                DocumentNumbers.invoiceKey(ledgerId, consumer, cycle), cycle, businessClock.today(tenant), actor),
                Map::of).number();''')
rep('''    /**
     * Records a collection: confirms it through the payment port, then allocates it to the consumer's outstanding
     * demand oldest due date first (arrears before current), each allocation guarded by the ledger.
     */''', '''    /**
     * Records a collection: confirms it through the payment port, then allocates it to the consumer's outstanding
     * demand oldest due date first (arrears before current), each allocation guarded by the ledger. The receipt of a
     * paymentRef is planned once: a retry replays the stored plan under the same receipt number (SL-131).
     */''')
start = s.index('        List<Balance> open = new ArrayList<>();\n        for (Balance b : ledger.arrears(tenant, ledgerId, LocalDate.MAX)) {')
end = s.index('    /** Refund of a collection')
new_body = open(sys.argv[2], encoding='utf-8').read().replace('\r\n', '\n')
s = s[:start] + new_body + s[end:]
open(p, 'w', encoding='utf-8', newline='\r\n').write(s)
print('ok')
