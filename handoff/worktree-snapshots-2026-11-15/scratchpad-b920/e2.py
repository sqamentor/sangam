import sys
p=sys.argv[1]
s=open(p,encoding='utf-8').read().replace('\r\n','\n')
def rep(a,b):
    global s
    assert s.count(a)==1, a[:90]
    s=s.replace(a,b)
rep('''        if (!plan.amount().equals(confirmed)) {
            throw new IllegalStateException("payment " + paymentRef + " is already receipted as " + no + " for "
                    + plan.amount().toPlainString() + " " + plan.amount().currencyCode() + ", not "
                    + confirmed.toPlainString() + " " + confirmed.currencyCode());
        }''','''        if (!plan.amount().equals(confirmed) || !consumer.equals(plan.consumer()) || !ledgerId.equals(plan.ledger())) {
            // the payment is receipted once: a call for another amount, consumer or ledger is not a retry of it
            throw new IllegalStateException("payment " + paymentRef + " is already receipted as " + no + " ("
                    + plan.amount().toPlainString() + " " + plan.amount().currencyCode() + " to " + plan.consumer()
                    + " on " + plan.ledger() + ")");
        }''')
rep('''    /** A receipt's allocation plan: the confirmed amount, the legs oldest due first, and the advance left over. */
    record Plan(Money amount, List<Leg> legs, Money advance) {''','''    /** A receipt's allocation plan: ledger, consumer, confirmed amount, legs oldest due first, advance left over. */
    record Plan(String ledger, String consumer, Money amount, List<Leg> legs, Money advance) {''')
rep('''            Map<String, Object> out = new LinkedHashMap<>();
            out.put("currency", amount.currencyCode());''','''            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ledger", ledger);
            out.put("consumer", consumer);
            out.put("currency", amount.currencyCode());''')
rep('''            return new Plan(Money.parse(amountValue.toString(), ccy), List.copyOf(legs),''','''            return new Plan(str(m, "ledger"), str(m, "consumer"), Money.parse(amountValue.toString(), ccy), List.copyOf(legs),''')
rep('''        return new Plan(confirmed, legs, left.signum() > 0''','''        return new Plan(ledgerId, consumer, confirmed, legs, left.signum() > 0''')
open(p,'w',encoding='utf-8',newline='\r\n').write(s)
print('ok')
