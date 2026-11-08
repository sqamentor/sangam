# SPDX-License-Identifier: Apache-2.0
"""Generic billing / demand / collection pack (CAPABILITY-COVERAGE N10), neutral to government or sector: water, power,
property tax, fees, rents, licence fees, premiums. Built on sangam-billing (tariffs from the tenant profile, RECEIVABLE
ledger) through the `billing.*` connectors."""
from lib import C
from packs import Pack
from common import batch_loop, end_ok
import forms

LBL = {
    'consumer': ('Account / consumer', 'खाता / उपभोक्ता', 'string'),
    'cycle': ('Billing cycle', 'बिलिंग चक्र', 'string'),
    'tariffId': ('Tariff', 'शुल्क दर', 'select'),
    'billedUnits': ('Units / quantity', 'इकाइयाँ / मात्रा', 'number'),
    'invoiceNo': ('Invoice number', 'बीजक संख्या', 'readonly'),
    'arrears': ('Arrears', 'बकाया', 'money'),
    'waiverAmount': ('Amount to waive / write off', 'माफ़ / बट्टे खाते की राशि', 'money'),
    'orderRef': ('Order reference', 'आदेश संदर्भ', 'string'),
}
OPT = {'waive': ('Waive / write off', 'माफ़ / बट्टे खाते में डालें'), 'recover': ('Continue recovery', 'वसूली जारी रखें'),
       'remindAgain': ('Remind again', 'पुनः स्मरण कराएँ')}
for k, v in LBL.items():
    forms.L.setdefault(k, v)
for k, v in OPT.items():
    forms.OPT.setdefault(k, v)


def build():
    pk = Pack('universal.billing-cycle', 'billing-cycle', 'Billing cycle: invoice, collection, arrears, penalty, waiver',
              'बिलिंग चक्र: बीजक, संग्रह, बकाया, दंड, माफ़ी', 'Billing',
              'Per cycle (timer or message) the account register is loaded and one case per account runs (GP-09): invoice '
              'from the tenant tariff (slabs, fixed, percentage, DMN), demand raised in the receivable ledger, wait for the '
              'payment or the due date; payment is collected and receipted; after the due date arrears are checked, the '
              'penalty / interest of the tariff is levied, a reminder goes out, and an officer decides waiver / write-off '
              '(maker-checker) or continued recovery. Neutral to government or sector.',
              ['N10', 'GP-09', 'GP-30', 'GP-08', 'GP-48', 'IFC-136', 'IFS-055'],
              connectors=[('billing.accounts', 'a', '', 'Accounts billed in the cycle (register)'),
                          ('billing.invoice', 'a', '', 'Raise the invoice (receivable demand)'),
                          ('billing.collect', 'a', '', 'Record a confirmed payment and receipt'),
                          ('billing.arrears', 'a', '', 'Outstanding and arrears of the account'),
                          ('billing.penalty', 'a', '', 'Levy penalty / interest per tariff'),
                          ('billing.waive', 'a', '', 'Waiver / write-off')])
    p = pk.proc('sangam-billing-cycle', 'Billing cycle run', '', start={'materialFields': 'cycle'})
    s = p.root
    t = s.start('bc_timer', 'Monthly on the 1st', timer_cycle='0 0 3 1 * ?', initiator=None)
    m = s.start('bc_manual', 'Run now (message)', msg='sangam.billing.runCycle', initiator=None)
    j = s.xgw('bc_join')
    s.flow(t, j)
    s.flow(m, j)
    d = s.setv('bc_defaults', 'Configuration', defaults='cycle=current;tariffId=DEFAULT;ledgerId=DEMAND')
    ld = s.svc('bc_load', 'Accounts of the cycle', C['BATCH'], {'op': 'LOAD', 'connector': 'billing.accounts', 'sampleSize': '3'},
               async_=True)
    s.seq(j, d, ld)
    op, cg = batch_loop(p, s, 'bc', 'sangam-billing-account', inherit='cycle,tariffId,ledgerId')
    s.flow(ld, op)
    rep = s.svc('bc_report', 'Cycle report (billed, collected, arrears)', C['DOC'], {'template': 'billing-cycle-report'})
    s.flow(cg, rep, None, 'complete', default=True)
    s.flow(rep, s.end('bc_end', 'Cycle closed'))

    a = pk.proc('sangam-billing-account', 'Billing of one account', '', start={'materialFields': 'key,consumer,cycle'})
    s = a.root
    st = s.start('ba_start', 'Account in cycle', initiator=None)
    d = s.setv('ba_defaults', 'Configuration', defaults='consumer=@key;tariffId=DEFAULT;ledgerId=DEMAND;dueWait=P15D;'
               'arrInArrears=false;officerSeat=BILL-OFFICER;checkerSeat=BILL-CHECKER;waiverAmount=0;billedUnits=0')
    inv = s.conn('ba_invoice', 'Invoice from the tariff', 'billing.invoice', 'invoice', 'submit',
                 'ledgerId,consumer,cycle,tariffId,billedUnits', 'inv', step='invoice')
    s.seq(st, d, inv)
    eg = s.egw('ba_wait', 'Paid or due?')
    s.flow(inv, eg)
    paid = s.catch_msg('ba_paid', 'Payment confirmed by the channel', 'sangam.billing.paymentReceived')
    due = s.catch_timer('ba_due', 'Due date passed', duration='P15D')
    s.flow(eg, paid)
    s.flow(eg, due)
    col = s.conn('ba_collect', 'Collect and receipt', 'billing.collect', 'collect', 'submit',
                 'ledgerId,consumer,channel,paymentRef,amount', 'col', step='collect')
    s.flow(paid, col)
    arr = s.conn('ba_arrears', 'Outstanding / arrears', 'billing.arrears', 'arrears', 'query', 'ledgerId,consumer', 'arr')
    s.flow(col, arr)
    s.flow(due, arr)
    g = s.xgw('ba_arrGw', 'In arrears?')
    s.flow(arr, g)
    s.flow(g, end_ok(a, s, 'ba_end', 'Settled'), None, 'nothing outstanding', default=True)
    pen = s.conn('ba_penalty', 'Penalty / interest (tariff)', 'billing.penalty', 'penalty', 'submit',
                 'ledgerId,consumer,tariffId', 'pen', step='penalty')
    s.flow(g, pen, 'arrInArrears', 'arrears')
    rem = s.notify('ba_remind', 'Reminder / demand notice', 'billing-demand-notice', 'sms', 'consumer')
    s.flow(pen, rem)
    e1, x1 = s.sla_task('ba_decide', 'Arrears: waive or recover | बकाया: माफ़ करें या वसूली', 'officerSeat',
                        form='billing-cycle/decide', decision=('arrearsDecision', ['recover', 'waive', 'remindAgain']),
                        fields=['consumer', 'arrears', 'waiverAmount', 'orderRef', 'remarks*'], default_spec='10WD')
    s.flow(rem, e1)
    dg = s.xgw('ba_decGw')
    s.flow(x1, dg)
    e2, x2 = s.sla_task('ba_check', 'Approve waiver (checker) | माफ़ी का अनुमोदन', 'checkerSeat', form='billing-cycle/approve',
                        decision=('waiverDecision', ['approve', 'reject']), fields=['waiverAmount', 'orderRef', 'remarks*'],
                        default_spec='5WD', sod=True)
    s.flow(dg, e2, "arrearsDecision == 'waive'", 'waive')
    s.flow(dg, eg, "arrearsDecision == 'remindAgain'", 'wait again')
    rec = s.end('ba_recovery', 'Recovery proceedings', listeners=[a.item_listener('FAILED')])
    s.flow(dg, rec, None, 'recover', default=True)
    wv = s.conn('ba_waive', 'Waiver / write-off', 'billing.waive', 'waive', 'submit',
                'ledgerId,consumer,cycle,waiverAmount,orderRef', 'wv', step='waive')
    wg = s.xgw('ba_wvGw')
    s.flow(x2, wg)
    s.flow(wg, wv, "waiverDecision == 'approve'", 'approved')
    s.flow(wg, e1, None, 'rejected', default=True)
    s.flow(wv, end_ok(a, s, 'ba_endWaived', 'Waived'))
    pk.seeds(offices=[{'id': 'BILLING', 'parent': ''}],
             seats=[{'id': 'BILL-OFFICER', 'role': 'BILLING_OFFICER', 'office': 'BILLING', 'reportsTo': 'BILL-CHECKER',
                     'name': {'en': 'Billing officer', 'hi': 'बिलिंग अधिकारी'}},
                    {'id': 'BILL-CHECKER', 'role': 'BILLING_CHECKER', 'office': 'BILLING', 'reportsTo': None,
                     'name': {'en': 'Billing checker', 'hi': 'बिलिंग जाँचकर्ता'}}],
             holdings=[{'seat': 'BILL-OFFICER', 'person': 'bl.officer', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'BILL-CHECKER', 'person': 'bl.checker', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'}],
             roles=[{'code': 'BILLING_OFFICER', 'name': {'en': 'Billing officer', 'hi': 'बिलिंग अधिकारी'}},
                    {'code': 'BILLING_CHECKER', 'name': {'en': 'Billing checker', 'hi': 'बिलिंग जाँचकर्ता'}}])
