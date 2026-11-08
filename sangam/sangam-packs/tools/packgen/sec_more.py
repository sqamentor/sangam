# SPDX-License-Identifier: Apache-2.0
"""Sector packs that close the gaps listed in spike/results/SECTOR-WORKFLOWS.md section 15 (banking DEA Fund, NBFC
DLG cap, capital-market surveillance / corporate actions / IPO, life death claims, reinsurance, intermediary
licensing, OT scheduling, blood bank, GST TCS, TCCCPR, SIM swap, e-way bill, MRP, examinations, scholarships,
meter-to-cash, disconnection, payroll with PF / ESI, appraisal, private litigation). Values marked SYNTHETIC or
"verify" are DMN entries listed in the verify-before-go-live register of SECTOR-WORKFLOWS.md."""
from lib import C, q
from common import core, outcome, end_ok, rejection, reject_with, batch_loop
from gov2 import simple_seeds
from sector import SIDE, spack, start_form, clock, at_date, powers, authority, maybe


def campaign(pk, p, prefix, cron, connector, child, name='Load items', chunk=100, poll='PT1H', sample='3', defaults=None):
    """Timer-started campaign with one child instance per item (GP-09)."""
    s = p.root
    t = s.start(prefix + '_timer', 'Cycle', timer_cycle=cron, initiator=None)
    ld = s.svc(prefix + '_load', name, C['BATCH'], {'op': 'LOAD', 'connector': connector, 'sampleSize': sample},
               async_=True)
    if defaults:
        dv = s.setv(prefix + '_defaults', 'Configuration', defaults=defaults)
        s.seq(t, dv, ld)
    else:
        s.flow(t, ld)
    op, cg = batch_loop(p, s, prefix, child, chunk=chunk, poll=poll)
    s.flow(ld, op)
    return s, cg


# ============================================================================================ banking: DEA Fund
def bank_dea():
    pk = spack('bank', 'inoperative-deposits', 'Inoperative accounts and unclaimed deposits (DEA Fund)',
               'निष्क्रिय खाते एवं अदावी जमा (डीईए निधि)', 'Banking',
               'Monthly review, one case per account: no customer-induced transaction for 2 years makes the account '
               'inoperative (customer told in advance, no charge for activation); balances unclaimed for 10 years '
               'are transferred to the RBI DEA Fund; reactivation or claim with KYC updated, no fee, claim refunded '
               'from the DEA Fund with interest where payable.',
               ['SEC-BANK-009', 'UP-15', 'GP-08', 'GP-09', 'GP-30'],
               [('cbs.dormancyDue', 'a', '', 'Accounts by inactivity'), ('cbs.markInoperative', 'a', '', 'Flag'),
                ('rbi.deaTransfer', 'c', '', 'Transfer to DEA Fund'), ('rbi.deaClaim', 'a', '', 'Claim from DEA Fund'),
                ('cbs.credit', 'a', '', 'Credit the claimant')])
    p = pk.proc('sangam-sec-bank-dormancy-review', 'Dormancy review', '', start={'materialFields': 'period'})
    s, cg = campaign(pk, p, 'dv', '0 0 5 3 * ?', 'cbs.dormancyDue', 'sangam-sec-bank-dormancy-account')
    s.flow(cg, s.end('dv_end', 'Review closed'), None, 'complete', default=True)
    c = pk.proc('sangam-sec-bank-dormancy-account', 'Dormancy of one account', '', start={'materialFields': 'key'})
    s = c.root
    a = s.start('da_start', 'Account inactive', initiator=None)
    d = s.setv('da_defaults', 'Configuration', defaults='yearsInactive=2;applicantRef=@key;opsSeat=OPS-CHECKER')
    r = s.rule('da_rule', 'Inoperative / unclaimed (DMN)', 'sec-bank-dormancy')
    g = s.xgw('da_gw', 'Action?')
    s.seq(a, d, r, g)
    n = s.notify('da_notice', 'Advance intimation to the customer and nominee', 'account-inoperative-notice',
                 'sms,email,letter', 'applicantRef')
    w = s.egw('da_wait', 'Customer acts or notice period ends')
    s.flow(g, n, "dormancyAction == 'INOPERATIVE' || dormancyAction == 'DEA'", 'inoperative / unclaimed')
    s.flow(n, w)
    s.flow(g, s.end('da_endActive', 'Still within limits', listeners=[c.item_listener('SUCCESS')]), None, 'none',
           default=True)
    rc = s.catch_msg('da_active', 'Customer transacts / updates KYC', 'sangam.account.reactivated')
    tt = s.catch_timer('da_period', 'Notice period over', duration='P30D')
    s.flow(w, rc)
    s.flow(w, tt)
    s.flow(rc, s.end('da_endReactivated', 'Reactivated', listeners=[c.item_listener('SUCCESS')]))
    g2 = s.xgw('da_g2', 'Unclaimed 10 years?')
    s.flow(tt, g2)
    mk = s.conn('da_mark', 'Mark inoperative (no charge)', 'cbs.markInoperative', 'mark', 'submit', 'key', 'mark',
                step='mark')
    s.flow(g2, mk, None, 'inoperative', default=True)
    s.flow(mk, s.end('da_endInoperative', 'Inoperative', listeners=[c.item_listener('INOPERATIVE')]))
    ap = s.user('da_approve', 'Approve DEA Fund transfer (checker) | डीईए निधि अंतरण का अनुमोदन', '${opsSeat}',
                form='sec-bank-inoperative-deposits/approve', decision=('deaDecision', ['approve', 'retain']),
                fields=['remarks'], sod=True)
    s.flow(g2, ap, "dormancyAction == 'DEA'", '10 years')
    ag = s.xgw('da_apGw')
    s.flow(ap, ag)
    tr = s.conn('da_transfer', 'Transfer to the DEA Fund', 'rbi.deaTransfer', 'transfer', 'submit', 'key', 'dea',
                step='dea-transfer')
    s.flow(ag, tr, "deaDecision == 'approve'", 'approve')
    s.flow(ag, mk, None, 'retain (dispute / legal hold)', default=True)
    s.flow(tr, s.end('da_endTransferred', 'Transferred to DEA Fund', listeners=[c.item_listener('TRANSFERRED')]))
    cl = pk.proc('sangam-sec-bank-dea-claim', 'Reactivation / claim', '', start={'materialFields': 'customerId,accountNo'})
    s = cl.root
    st = start_form(cl, s, 'dc_start', 'Claim or reactivation request', 'sec-bank-inoperative-deposits/claim',
                    'Reactivation / claim | पुनः सक्रियण / दावा', ['customerId*', 'accountNo*', 'claimantType*', 'documents'])
    d = s.setv('dc_defaults', 'Configuration', defaults='accountNo=@customerId;claimantType=HOLDER;transferredToDea=false;'
               'opsSeat=OPS-CHECKER;applicantRef=@customerId')
    kv = s.user('dc_kyc', 'Verify KYC and entitlement (holder, nominee, legal heir) | केवाईसी एवं पात्रता सत्यापन',
                '${opsSeat}', form='sec-bank-inoperative-deposits/verify', decision=('claimDecision', ['approve', 'reject']),
                fields=['transferredToDea', 'documents', 'remarks'], sod=True)
    s.seq(st, d, kv)
    g = s.xgw('dc_gw')
    s.flow(kv, g)
    s.flow(g, reject_with(cl, s, 'dc_rj', 'ENTITLEMENT_NOT_ESTABLISHED'), "claimDecision == 'reject'", 'reject')
    dg = s.xgw('dc_deaGw', 'Already with DEA Fund?')
    s.flow(g, dg, None, 'approve', default=True)
    cm = s.conn('dc_claim', 'Claim refund from the DEA Fund', 'rbi.deaClaim', 'claim', 'submit', 'accountNo', 'deaClaim',
                step='dea-claim')
    cr = s.conn('dc_credit', 'Reactivate / pay with interest, no fee', 'cbs.credit', 'credit', 'submit', 'accountNo,customerId',
                'credit', step='credit')
    s.flow(dg, cm, 'transferredToDea', 'yes')
    s.flow(dg, cr, None, 'no', default=True)
    s.flow(cm, cr)
    s.flow(cr, end_ok(cl, s, 'dc_end', 'Paid / reactivated'))
    pk.dmn('sec-bank-dormancy', 'Inoperative and unclaimed thresholds', 'FIRST',
           [('Years without customer-induced transaction', 'yearsInactive', 'integer')],
           [('dormancyAction', 'Action', 'string')],
           [(['>= 10'], [q('DEA')], 'DEA Fund Scheme 2014: unclaimed for 10 years'),
            (['>= 2'], [q('INOPERATIVE')], 'inoperative after 2 years'), (['-'], [q('NONE')], '')])
    simple_seeds(pk, 'BRANCH-DEA', [('OPS-CHECKER', 'OPSCHECKER', None, 'Operations checker', 'संचालन जाँचकर्ता')], 'bdv')


# ============================================================================================ NBFC: DLG cap
def nbfc_dlg():
    pk = spack('nbfc', 'dlg-arrangement', 'Default loss guarantee arrangement with the 5% cap',
               'पाँच प्रतिशत सीमा सहित डिफ़ॉल्ट हानि गारंटी व्यवस्था', 'NBFC / fintech',
               'DLG arrangement with an LSP or another RE: cover computed against the portfolio and refused above 5 %, '
               'DLG only as cash, fixed deposit or bank guarantee, board-approved policy and sanction with SoD, '
               'disclosure; invocation for a defaulted loan only within 120 days of overdue; DLG never used to '
               'set off below the portfolio NPA recognition.',
               ['SEC-NBFC-006', 'SEC-NBFC-007', 'GP-41', 'GP-42', 'UP-05'] + SIDE,
               [('dlg.register', 'a', '', 'DLG register'), ('dlg.invoke', 'c', '', 'Invoke guarantee')])
    p = pk.proc('sangam-sec-nbfc-dlg', 'DLG arrangement', '',
                start={'materialFields': 'dlgProvider,portfolioAmount,dlgAmount,dlgForm'})
    s = p.root
    st = start_form(p, s, 'dg_start', 'DLG proposal', 'sec-nbfc-dlg-arrangement/proposal', 'DLG proposal | डीएलजी प्रस्ताव',
                    ['dlgProvider*', 'portfolioAmount*', 'dlgAmount*', 'dlgForm*', 'documents'])
    d = s.setv('dg_defaults', 'Configuration',
               defaults='dlgProvider=LSP-1;portfolioAmount=100000000;dlgAmount=4000000;dlgForm=FIXED_DEPOSIT;'
                        'daysOverdue=90;amount=@dlgAmount;caseType=DLG;routingDecisionKey=sec-nbfc-dlg-authority;'
                        'powersDecisionKey=sec-nbfc-dlg-powers;appealAllowed=false;dlgTenure=P365D')
    pc = s.expr('dg_pct', 'Cover as % of the portfolio',
                "${execution.setVariable('dlgPct', dlgAmount * 100 / portfolioAmount)}")
    r = s.rule('dg_cap', 'Cap and form (DMN)', 'sec-nbfc-dlg-cap')
    g = s.xgw('dg_capGw', 'Within the cap?')
    s.seq(st, d, pc, r, g)
    s.flow(g, reject_with(p, s, 'dg_rjCap', 'DLG_ABOVE_CAP_OR_FORM'), '!dlgAllowed', 'no')
    san = core(s, 'dg_sanction', 'Approve under the board policy (core sanction)', 'sanction')
    s.flow(g, san, None, 'yes', default=True)
    reg = s.conn('dg_register', 'Record DLG and disclose', 'dlg.register', 'register', 'submit',
                 'dlgProvider,portfolioAmount,dlgAmount,dlgForm', 'dlgReg', step='register')
    rej = rejection(p, s, 'dg_rj')
    outcome(s, 'dg_sanGw', 'sanctionOutcome', {'SANCTIONED': reg}, rej)
    s.flow(san, 'dg_sanGw')
    w = s.egw('dg_wait', 'Invocation or tenure ends')
    s.flow(reg, w)
    iv = s.catch_msg('dg_invoke', 'Loan in default: invocation requested', 'sangam.dlg.invoke')
    te = s.catch_timer('dg_tenure', 'Tenure over', duration='${dlgTenure}')
    s.flow(w, iv)
    s.flow(w, te)
    ir = s.rule('dg_invRule', 'Invocation window (DMN)', 'sec-nbfc-dlg-invocation')
    ig = s.xgw('dg_invGw', 'Within 120 days overdue?')
    s.seq(iv, ir, ig)
    ix = s.conn('dg_doInvoke', 'Invoke DLG', 'dlg.invoke', 'invoke', 'submit', 'dlgProvider,daysOverdue', 'inv', step='invoke')
    s.flow(ig, ix, 'invocationAllowed', 'yes')
    s.flow(ig, w, None, 'outside the window: not invoked', default=True)
    s.flow(ix, w)
    s.flow(te, end_ok(p, s, 'dg_end', 'Arrangement ended'))
    s.side_exits('dg')
    pk.dmn('sec-nbfc-dlg-cap', 'DLG cap (RBI DLG guidelines, 8 Jun 2023)', 'FIRST',
           [('Cover %', 'dlgPct', 'double'), ('Form', 'dlgForm', 'string')], [('dlgAllowed', 'Allowed', 'boolean')],
           [(['> 5', '-'], ['false'], 'above 5 % of the portfolio'),
            (['-', q('CASH,FIXED_DEPOSIT,BANK_GUARANTEE').replace(',', '","')], ['true'], ''),
            (['-', '-'], ['false'], 'only cash, FD lien or bank guarantee')])
    pk.dmn('sec-nbfc-dlg-invocation', 'DLG invocation window', 'FIRST', [('Days overdue', 'daysOverdue', 'integer')],
           [('invocationAllowed', 'Allowed', 'boolean')],
           [(['<= 120'], ['true'], 'within 120 days of overdue'), (['-'], ['false'], '')])
    authority(pk, 'sec-nbfc-dlg-authority', 'DLG approver', [('-', 'CREDITHEAD', 'board-approved policy')])
    powers(pk, 'sec-nbfc-dlg-powers', [('CREDITHEAD', 10000000000, '')])
    simple_seeds(pk, 'NBFC-DLG', [('CREDIT-HEAD', 'CREDITHEAD', None, 'Head of credit', 'ऋण प्रमुख')], 'ndg')


# ============================================================================================ capital markets
def cap_surveillance():
    pk = spack('cap', 'surveillance-alert', 'Trade surveillance alert', 'व्यापार निगरानी चेतावनी', 'Capital markets',
               'Exchange or internal surveillance alert (unusual volume, pump and dump, synchronised trades): '
               'analyst review with client clarification and due diligence, compliance officer decision, adverse '
               'observations reported to the exchange, STR where suspicious, closed within the processing '
               'timeline with reasons.',
               ['SEC-CAP-006', 'UP-03', 'UP-06', 'GP-06'],
               [('exchange.alerts', 'a', '', 'Exchange alert feed'), ('exchange.report', 'b', '', 'Report to the exchange'),
                ('fiu.finnet', 'b', '', 'STR')])
    p = pk.proc('sangam-sec-cap-surveillance', 'Surveillance alert', '', start={'materialFields': 'clientCode,alertType'})
    s = p.root
    st = start_form(p, s, 'sv_start', 'Alert received', 'sec-cap-surveillance-alert/alert', 'Surveillance alert | निगरानी चेतावनी',
                    ['clientCode*', 'alertType*', 'description'])
    d = s.setv('sv_defaults', 'Configuration', defaults='clientCode=@customerId;alertType=VOLUME_SPIKE;analystSeat=SURV-ANALYST;'
               'coSeat=COMPLIANCE-OFFICER;applicantRef=@clientCode')
    ck = clock(s, 'sv_clock', 'alert', '${alertProcessSpec}')
    r = s.rule('sv_tl', 'Processing timeline (DMN)', 'sec-cap-surveillance-tl')
    s.seq(st, d, r, ck)
    ae, ad = s.sla_task('sv_review', 'Review alert and client trading | चेतावनी एवं ग्राहक व्यापार की समीक्षा', 'analystSeat',
                        form='sec-cap-surveillance-alert/review', decision=('svDecision', ['close', 'clarify', 'escalate']),
                        fields=['findings*'], spec='${alertProcessSpec}', on_breach='ESCALATE')
    s.flow(ck, ae)
    g = s.xgw('sv_gw')
    s.flow(ad, g)
    ask = s.notify('sv_ask', 'Seek client clarification', 'surveillance-clarification', 'email', 'applicantRef')
    w = s.egw('sv_wait', 'Reply or lapse')
    s.flow(g, ask, "svDecision == 'clarify'", 'clarify')
    s.flow(ask, w)
    rp = s.catch_msg('sv_reply', 'Client replies', 'sangam.clarificationReceived')
    lp = s.catch_timer('sv_lapse', 'No reply in 7 days', duration='P7D')
    s.flow(w, rp)
    s.flow(w, lp)
    s.flow(rp, ae)
    co = s.user('sv_co', 'Compliance officer decision | अनुपालन अधिकारी का निर्णय', '${coSeat}',
                form='sec-cap-surveillance-alert/decide', decision=('coDecision', ['close', 'report', 'fileStr']),
                fields=['strGrounds', 'remarks*'], sod=True)
    s.flow(g, co, "svDecision == 'escalate'", 'escalate')
    s.flow(lp, co)
    s.flow(g, s.end('sv_endClosed', 'Closed with reasons'), None, 'close', default=True)
    cg = s.xgw('sv_coGw')
    s.flow(co, cg)
    rx = s.conn('sv_report', 'Report adverse observation to the exchange', 'exchange.report', 'report', 'submit',
                'clientCode,alertType', 'exRep', step='exchange-report')
    s.flow(cg, rx, "coDecision == 'report'", 'report')
    sr = s.conn('sv_str', 'File STR', 'fiu.finnet', 'str', 'submit', 'clientCode,strGrounds', 'str', step='str')
    s.flow(cg, sr, "coDecision == 'fileStr'", 'STR')
    s.flow(sr, rx)
    s.flow(rx, end_ok(p, s, 'sv_end', 'Reported'))
    s.flow(cg, s.end('sv_endCo', 'Closed by compliance'), None, 'close', default=True)
    pk.dmn('sec-cap-surveillance-tl', 'Alert processing timeline (exchange circulars; verify)', 'FIRST',
           [('Alert type', 'alertType', 'string')], [('alertProcessSpec', 'Process within', 'string')],
           [(['-'], [q('45D')], 'alerts processed within 45 days of generation (verify)')])
    simple_seeds(pk, 'BROKER-SURV', [('SURV-ANALYST', 'ANALYST', 'COMPLIANCE-OFFICER', 'Surveillance analyst', 'निगरानी विश्लेषक'),
                                     ('COMPLIANCE-OFFICER', 'COMPLIANCE', None, 'Compliance officer', 'अनुपालन अधिकारी')], 'csv')


def cap_corporate_action():
    pk = spack('cap', 'corporate-action', 'Corporate action: dividend to unpaid dividend account',
               'कॉर्पोरेट कार्रवाई: लाभांश से अदत्त लाभांश खाते तक', 'Capital markets',
               'Board decision, intimation to the exchanges (LODR), record date notice, beneficiary positions from '
               'the depositories at the record date, one payment case per holder (GP-09) with TDS, dividend paid '
               'within 30 days of declaration (Companies Act s.127), unpaid amounts moved to the unpaid dividend '
               'account within 7 days after that (s.124), transfer to IEPF after 7 years.',
               ['SEC-CAP-007', 'GP-08', 'GP-09', 'GP-12', 'GP-30'],
               [('exchange.intimate', 'b', '', 'Exchange intimation'), ('depository.benpos', 'a', '', 'Beneficiary positions'),
                ('bank.transfer', 'c', '', 'Dividend payment'), ('bank.unpaidDividend', 'a', '', 'Unpaid dividend account')])
    p = pk.proc('sangam-sec-cap-dividend', 'Dividend corporate action', '',
                start={'materialFields': 'companyName,dividendPerShare,recordDate'})
    s = p.root
    st = start_form(p, s, 'cd_start', 'Board declares dividend', 'sec-cap-corporate-action/declare',
                    'Dividend declaration | लाभांश घोषणा', ['companyName*', 'dividendPerShare*', 'recordDate*', 'documents'])
    d = s.setv('cd_defaults', 'Configuration', defaults='companyName=@customerId;dividendPerShare=1;csSeat=COMPANY-SECRETARY')
    ex = s.conn('cd_intimate', 'Intimate the exchanges (outcome of the board meeting, record date)', 'exchange.intimate',
                'intimate', 'submit', 'companyName,dividendPerShare,recordDate', 'exInt', step='intimation')
    pay30 = clock(s, 'cd_clock', 'payment', '30D')
    rd = at_date(s, 'cd_rd', 'record', 'recordDate')
    s.seq(st, d, ex, pay30, rd)
    rt = s.catch_timer('cd_recordDate', 'Record date', date='${recordDueAt}')
    s.flow(rd, rt)
    op, cg = batch_loop(p, s, 'cd', 'sangam-sec-cap-dividend-holder', chunk=200, poll='PT1H',
                        inherit='companyName,dividendPerShare')
    bp = s.svc('cd_benpos', 'Beneficiary positions at the record date', C['BATCH'],
               {'op': 'LOAD', 'connector': 'depository.benpos', 'sampleSize': '3'}, async_=True)
    s.seq(rt, bp, op)
    rv = s.user('cd_review', 'Review payment outcome before the 30-day limit | 30 दिन की सीमा से पूर्व भुगतान की समीक्षा', '${csSeat}',
                form='sec-cap-corporate-action/review', decision=None, fields=['remarks'], due='${paymentDueAt}')
    s.flow(cg, rv, None, 'complete', default=True)
    un = s.conn('cd_unpaid', 'Move unpaid amounts to the unpaid dividend account (7 days)', 'bank.unpaidDividend', 'transfer',
                'submit', 'companyName', 'unpaid', step='unpaid-dividend')
    s.seq(rv, un, end_ok(p, s, 'cd_end', 'Dividend closed'))
    c = pk.proc('sangam-sec-cap-dividend-holder', 'Dividend to one holder', '', start={'materialFields': 'key'})
    s = c.root
    a = s.start('ch_start', 'Holder', initiator=None)
    d = s.setv('ch_defaults', 'Configuration', defaults='executionConnector=bank.transfer;executionStep=dividend;'
               'verifyApproval=false;reconcileRequired=false;executionPayloadFields=key,dividendPerShare')
    ex = core(s, 'ch_pay', 'Pay dividend net of TDS (core execution)', 'execution')
    s.seq(a, d, ex)
    outcome(s, 'ch_gw', 'executionOutcome', {'DONE': end_ok(c, s, 'ch_end', 'Paid')},
            s.end('ch_endUnpaid', 'Unpaid (to unpaid dividend account)', listeners=[c.item_listener('FAILED', reason_var='opReasonCode')]))
    s.flow(ex, 'ch_gw')
    simple_seeds(pk, 'ISSUER', [('COMPANY-SECRETARY', 'CS', None, 'Company secretary', 'कंपनी सचिव')], 'cca')


def cap_ipo():
    pk = spack('cap', 'ipo-allotment', 'IPO from bid close to listing (T+3)', 'आईपीओ: बोली समापन से सूचीबद्धता (टी+3)',
               'Capital markets',
               'Public issue timeline after the issue closes: UPI mandates and ASBA blocks reconciled, basis of '
               'allotment approved with the designated stock exchange on T+1, credit of shares and unblocking of '
               'funds on T+2, listing and trading on T+3 (SEBI, mandatory from 1 Dec 2023); investor compensation '
               'for late unblocking.',
               ['SEC-CAP-008', 'GP-06', 'GP-12', 'GP-24'],
               [('rta.reconcile', 'a', '', 'Bid reconciliation'), ('exchange.basis', 'b', '', 'Basis of allotment'),
                ('depository.credit', 'a', '', 'Credit of shares'), ('sponsorbank.unblock', 'c', '', 'Unblock ASBA / UPI'),
                ('exchange.listing', 'b', '', 'Listing application')])
    p = pk.proc('sangam-sec-cap-ipo', 'IPO post-issue', '', start={'materialFields': 'issueName,issueCloseDate'})
    s = p.root
    st = start_form(p, s, 'ip_start', 'Issue closed (T)', 'sec-cap-ipo-allotment/close', 'Issue closure | निर्गम समापन',
                    ['issueName*', 'issueCloseDate', 'documents'])
    d = s.setv('ip_defaults', 'Configuration', defaults='issueName=@customerId;rtaSeat=RTA;mbSeat=LEAD-MANAGER;lateUnblock=false')
    rc = s.conn('ip_recon', 'Reconcile bids, UPI mandates and ASBA blocks', 'rta.reconcile', 'reconcile', 'query', 'issueName', 'recon')
    s.seq(st, d, rc)
    be, bd = s.sla_task('ip_basis', 'Basis of allotment with the designated exchange (T+1) | आबंटन का आधार (टी+1)', 'mbSeat',
                        form='sec-cap-ipo-allotment/basis', decision=None, fields=['documents*', 'remarks'], spec='1WD',
                        on_breach='ESCALATE', sod=True)
    s.flow(rc, be)
    bx = s.conn('ip_basisSend', 'Basis approved by the exchange', 'exchange.basis', 'approve', 'submit', 'issueName', 'basis',
                step='basis')
    s.flow(bd, bx)
    fk = s.pgw('ip_fork', 'T+2 in parallel')
    jn = s.pgw('ip_join')
    s.flow(bx, fk)
    cr = s.conn('ip_credit', 'Credit shares to demat accounts', 'depository.credit', 'credit', 'submit', 'issueName', 'credit',
                step='credit')
    ub = s.conn('ip_unblock', 'Unblock funds of non-allottees', 'sponsorbank.unblock', 'unblock', 'submit', 'issueName', 'unblock',
                step='unblock')
    for x in (cr, ub):
        s.flow(fk, x)
        s.flow(x, jn)
    ck = s.xgw('ip_ubGw', 'Unblock failed?')
    s.flow(jn, ck)
    cp = s.user('ip_comp', 'Investor compensation for late unblocking | विलंबित अनब्लॉकिंग पर निवेशक क्षतिपूर्ति', '${mbSeat}',
                form='sec-cap-ipo-allotment/compensation', decision=None, fields=['compensationAmount*', 'remarks'])
    lst = s.conn('ip_list', 'Listing and trading approval (T+3)', 'exchange.listing', 'list', 'submit', 'issueName', 'listing',
                 step='listing')
    s.flow(ck, cp, "unblockStatus == 'FAILED'", 'yes')
    s.flow(ck, lst, None, 'no', default=True)
    s.flow(cp, lst)
    s.flow(lst, end_ok(p, s, 'ip_end', 'Listed'))
    simple_seeds(pk, 'ISSUE', [('RTA', 'RTA', 'LEAD-MANAGER', 'Registrar to the issue', 'निर्गम रजिस्ट्रार'),
                               ('LEAD-MANAGER', 'BRLM', None, 'Book running lead manager', 'प्रमुख प्रबंधक')], 'cip')


# ============================================================================================ insurance
def ins_death_claim():
    pk = spack('ins', 'death-claim', 'Life insurance death claim', 'जीवन बीमा मृत्यु दावा', 'Insurance',
               'Death claim intimation, documents, early-claim rule (policy younger than 3 years may be '
               'investigated; after 3 years no repudiation on any ground under Insurance Act s.45), settlement '
               'within the time limit (investigation completed first where warranted), payment to the nominee or '
               'legal heir once by operationId, interest for delay, rejection with reasons and the Ombudsman route.',
               ['SEC-INS-011', 'GP-06', 'GP-12', 'GP-22'] + SIDE,
               [('policy.coverCheck', 'a', '', 'Policy status'), ('claims.pay', 'c', '', 'Claim payment')])
    p = pk.proc('sangam-sec-ins-death-claim', 'Death claim', '', start={'materialFields': 'policyNo,claimantName,sumAssured'})
    s = p.root
    st = start_form(p, s, 'dc_start', 'Death intimated', 'sec-ins-death-claim/intimation', 'Death claim intimation | मृत्यु दावा सूचना',
                    ['policyNo*', 'claimantName*', 'claimantType*', 'sumAssured', 'dateOfDeath', 'documents'])
    d = s.setv('dc_defaults', 'Configuration',
               defaults='claimantName=@policyNo;claimantType=NOMINEE;sumAssured=1000000;policyAgeYears=5;coverActive=true;'
                        'amount=@sumAssured;caseType=DEATH_CLAIM;routingDecisionKey=sec-ins-death-authority;'
                        'powersDecisionKey=sec-ins-death-powers;executionConnector=claims.pay;executionStep=death-claim;'
                        'reconcileRequired=true;executionPayloadFields=policyNo,claimantName,amount;'
                        'appellateSeat=CLAIMS-REVIEW-COMMITTEE;handlerSeat=LIFE-CLAIMS;investigatorSeat=INVESTIGATOR')
    cv = s.conn('dc_cover', 'Policy status', 'policy.coverCheck', 'check', 'query', 'policyNo', 'cover')
    r = s.rule('dc_rule', 'Early claim and timeline (DMN)', 'sec-ins-death-rules')
    s.seq(st, d, cv, r)
    cg = s.xgw('dc_coverGw', 'In force / within grace?')
    s.flow(r, cg)
    s.flow(cg, reject_with(p, s, 'dc_rjLapse', 'POLICY_LAPSED'), '!coverActive', 'lapsed')
    ig = s.xgw('dc_invGw', 'Investigation warranted?')
    s.flow(cg, ig, None, 'yes', default=True)
    ie, idn = s.sla_task('dc_investigate', 'Claim investigation | दावा अन्वेषण', 'investigatorSeat',
                         form='sec-ins-death-claim/investigate', decision=('invDecision', ['genuine', 'fraud']),
                         fields=['findings*'], spec='${investigationSpec}')
    s.flow(ig, ie, 'investigationAllowed', 'early claim')
    ae, ad = s.sla_task('dc_assess', 'Assess and recommend | आकलन एवं अनुशंसा', 'handlerSeat', form='sec-ins-death-claim/assess',
                        decision=('assessDecision', ['approve', 'reject']), fields=['amount*', 'rejectionReasonCodes', 'remarks'],
                        spec='${settlementSpec}', on_breach='ESCALATE', sod=True)
    s.flow(ig, ae, None, 'no', default=True)
    s.flow(idn, s.xgw('dc_invRes'))
    rej = rejection(p, s, 'dc_rj')
    s.flow('dc_invRes', ae, "invDecision == 'genuine'", 'genuine')
    s.flow('dc_invRes', rej, None, 'fraud / non-disclosure', default=True)
    ag = s.xgw('dc_assGw')
    s.flow(ad, ag)
    san = core(s, 'dc_sanction', 'Settlement sanction (core sanction)', 'sanction')
    s.flow(ag, san, "assessDecision == 'approve'", 'approve')
    s.flow(ag, rej, None, 'reject', default=True)
    pay = core(s, 'dc_pay', 'Pay the nominee / legal heir (core execution)', 'execution')
    outcome(s, 'dc_sanGw', 'sanctionOutcome', {'SANCTIONED': pay, 'RETURNED': ae}, rej)
    s.flow(san, 'dc_sanGw')
    cl = core(s, 'dc_close', 'Settlement letter and closure (core)', 'closure')
    s.seq(pay, cl, end_ok(p, s, 'dc_end', 'Settled'))
    s.side_exits('dc')
    pk.dmn('sec-ins-death-rules', 'Death claim rules (verify timelines against the 2024 master circular)', 'FIRST',
           [('Policy age (years)', 'policyAgeYears', 'integer')],
           [('investigationAllowed', 'Investigation', 'boolean'), ('investigationSpec', 'Investigate within', 'string'),
            ('settlementSpec', 'Settle within', 'string')],
           [(['< 3'], ['true', q('45D'), q('15D')], 'early claim: investigation (verify 45 days)'),
            (['-'], ['false', q('45D'), q('15D')], 's.45: no repudiation after 3 years; settle in 15 days (verify)')])
    authority(pk, 'sec-ins-death-authority', 'Death claim authority', [('<= 2500000', 'CLAIMSMGR', ''), ('-', 'CLAIMSHEAD', '')])
    powers(pk, 'sec-ins-death-powers', [('CLAIMSMGR', 2500000, ''), ('CLAIMSHEAD', 10000000000, '')])
    simple_seeds(pk, 'LIFE-CLAIMS', [('LIFE-CLAIMS', 'HANDLER', 'CLAIMS-MGR', 'Life claims handler', 'जीवन दावा संचालक'),
                                     ('INVESTIGATOR', 'INVESTIGATOR', 'CLAIMS-MGR', 'Claim investigator', 'दावा अन्वेषक'),
                                     ('CLAIMS-MGR', 'CLAIMSMGR', 'CLAIMS-HEAD', 'Claims manager', 'दावा प्रबंधक'),
                                     ('CLAIMS-HEAD', 'CLAIMSHEAD', None, 'Head of claims', 'दावा प्रमुख'),
                                     ('CLAIMS-REVIEW-COMMITTEE', 'CRC', None, 'Claims review committee', 'दावा समीक्षा समिति')], 'idc')


def ins_reinsurance():
    pk = spack('ins', 'reinsurance', 'Reinsurance cession and recovery', 'पुनर्बीमा अभ्यर्पण एवं वसूली', 'Insurance',
               'Per risk: obligatory cession, treaty cession above the retention by rule, facultative placement '
               'offered first to Indian reinsurers in the order of preference (IRDAI Reinsurance Regulations), '
               'cession recorded; a claim on a ceded risk raises the recovery from the reinsurer, followed up '
               'until paid.',
               ['SEC-INS-012', 'GP-12', 'GP-15'],
               [('re.cede', 'a', '', 'Cession register'), ('re.placeFac', 'b', '', 'Facultative placement'),
                ('re.recovery', 'b', '', 'Recovery claim')])
    p = pk.proc('sangam-sec-ins-reinsurance', 'Reinsurance of one risk', '', start={'materialFields': 'policyNo,sumInsured'})
    s = p.root
    st = start_form(p, s, 'ri_start', 'Risk accepted', 'sec-ins-reinsurance/risk', 'Risk for reinsurance | पुनर्बीमा हेतु जोखिम',
                    ['policyNo*', 'sumInsured*', 'lineOfBusiness*'])
    d = s.setv('ri_defaults', 'Configuration', defaults='sumInsured=@amount;lineOfBusiness=FIRE;riSeat=RI-DESK')
    r = s.rule('ri_rule', 'Retention and cession (DMN)', 'sec-ins-ri-cession')
    ce = s.conn('ri_cede', 'Record obligatory and treaty cession', 're.cede', 'cede', 'submit', 'policyNo,sumInsured,lineOfBusiness',
                'cede', step='cede')
    s.seq(st, d, r, ce)
    fg = s.xgw('ri_facGw', 'Facultative needed?')
    s.flow(ce, fg)
    fp = s.user('ri_fac', 'Place facultative (Indian reinsurers first) | ऐच्छिक पुनर्बीमा प्लेसमेंट', '${riSeat}',
                form='sec-ins-reinsurance/fac', decision=None, fields=['reinsurerPanel*', 'remarks'])
    fs = s.conn('ri_facSend', 'Placement slip', 're.placeFac', 'place', 'submit', 'policyNo,reinsurerPanel', 'fac', step='fac')
    s.flow(fg, fp, 'facultativeRequired', 'yes')
    s.flow(fp, fs)
    w = s.egw('ri_wait', 'Claim or expiry')
    s.flow(fg, w, None, 'no', default=True)
    s.flow(fs, w)
    cm = s.catch_msg('ri_claim', 'Claim on the risk', 'sangam.ins.claimPaid')
    ex = s.catch_timer('ri_expiry', 'Policy expiry', duration='P365D')
    s.flow(w, cm)
    s.flow(w, ex)
    s.flow(ex, end_ok(p, s, 'ri_endExpired', 'Expired without claim'))
    rc = s.conn('ri_recover', 'Raise recovery on reinsurers', 're.recovery', 'claim', 'submit', 'policyNo', 'rec', step='recovery')
    rw = s.egw('ri_recWait', 'Paid or follow up')
    s.seq(cm, rc, rw)
    pd = s.catch_msg('ri_paid', 'Recovery received', 'sangam.ins.recoveryPaid')
    fu = s.catch_timer('ri_follow', 'Follow up', duration='P30D')
    s.flow(rw, pd)
    s.flow(rw, fu)
    fo = s.user('ri_followTask', 'Follow up recovery | वसूली का अनुवर्तन', '${riSeat}', form='sec-ins-reinsurance/follow',
                decision=None, fields=['remarks*'])
    s.seq(fu, fo, rw)
    s.flow(pd, end_ok(p, s, 'ri_end', 'Recovered'))
    pk.dmn('sec-ins-ri-cession', 'Retention and cession (SYNTHETIC; obligatory cession % per IRDAI notification - verify)',
           'FIRST', [('Sum insured', 'sumInsured', 'double'), ('Line', 'lineOfBusiness', 'string')],
           [('obligatoryPct', 'Obligatory cession %', 'integer'), ('facultativeRequired', 'Facultative', 'boolean')],
           [(['> 500000000', '-'], ['4', 'true'], 'above treaty capacity'), (['-', '-'], ['4', 'false'], '')])
    simple_seeds(pk, 'REINSURANCE', [('RI-DESK', 'RI', None, 'Reinsurance desk', 'पुनर्बीमा डेस्क')], 'iri')


def ins_intermediary():
    pk = spack('ins', 'intermediary-licensing', 'Agent and broker registration', 'अभिकर्ता एवं ब्रोकर पंजीकरण', 'Insurance',
               'Insurance agent: eligibility, pre-licensing training, examination, KYC and background check, '
               'appointment uploaded to the IRDAI agency portal, code issued; renewal and termination with '
               'portfolio reassignment. Broker: application to IRDAI with net worth and principal officer checks '
               'by rule.',
               ['SEC-INS-013', 'GP-16', 'GP-30', 'UP-22'] + SIDE,
               [('irdai.agencyPortal', 'b', '', 'IRDAI agency portal'), ('exam.book', 'a', '', 'Examination booking'),
                ('irdai.brokerApply', 'b', '', 'Broker application')])
    p = pk.proc('sangam-sec-ins-intermediary', 'Intermediary registration', '',
                start={'materialFields': 'applicantName,intermediaryType'})
    s = p.root
    st = start_form(p, s, 'ag_start', 'Application', 'sec-ins-intermediary-licensing/apply', 'Intermediary application | मध्यस्थ आवेदन',
                    ['applicantName*', 'intermediaryType*', 'pan', 'qualification', 'netWorth', 'documents'])
    d = s.setv('ag_defaults', 'Configuration', defaults='intermediaryType=AGENT;qualification=12TH;netWorth=0;examPassed=false;'
               'agencySeat=AGENCY-ADMIN')
    r = s.rule('ag_rule', 'Eligibility (DMN)', 'sec-ins-intermediary')
    g = s.xgw('ag_gw', 'Eligible?')
    s.seq(st, d, r, g)
    s.flow(g, reject_with(p, s, 'ag_rj', 'NOT_ELIGIBLE'), '!eligible', 'no')
    tg = s.xgw('ag_typeGw', 'Agent or broker?')
    s.flow(g, tg, None, 'yes', default=True)
    tr = s.user('ag_training', 'Pre-licensing training completed | पूर्व-अनुज्ञप्ति प्रशिक्षण पूर्ण', '${agencySeat}',
                form='sec-ins-intermediary-licensing/training', decision=None, fields=['trainingHours*'])
    s.flow(tg, tr, "intermediaryType == 'AGENT'", 'agent')
    ex = s.conn('ag_exam', 'Book examination', 'exam.book', 'book', 'submit', 'applicantName', 'exam', step='exam')
    er = s.catch_msg('ag_result', 'Examination result', 'sangam.exam.result')
    s.seq(tr, ex, er)
    eg = s.xgw('ag_examGw', 'Passed?')
    s.flow(er, eg)
    s.flow(eg, ex, '!examPassed', 'retake')
    ap = s.user('ag_appoint', 'Background check and appointment | पृष्ठभूमि जाँच एवं नियुक्ति', 'PRINCIPAL-OFFICER',
                form='sec-ins-intermediary-licensing/appoint', decision=('appointDecision', ['approve', 'reject']),
                fields=['remarks*'], sod=True)
    s.flow(eg, ap, None, 'passed', default=True)
    ag = s.xgw('ag_apGw')
    s.flow(ap, ag)
    up = s.conn('ag_upload', 'Upload appointment to the IRDAI agency portal', 'irdai.agencyPortal', 'appoint', 'submit',
                'applicantName,pan', 'portal', step='appoint')
    s.flow(ag, up, "appointDecision == 'approve'", 'approve')
    s.flow(ag, s.end('ag_endRejected', 'Not appointed'), None, 'reject', default=True)
    s.flow(up, end_ok(p, s, 'ag_end', 'Code issued'))
    br = s.conn('ag_broker', 'Apply to IRDAI for a broker licence', 'irdai.brokerApply', 'apply', 'submit',
                'applicantName,netWorth', 'broker', step='broker')
    s.flow(tg, br, None, 'broker', default=True)
    bw = s.catch_msg('ag_brokerResult', 'IRDAI decision', 'sangam.irdai.brokerDecision')
    s.seq(br, bw, end_ok(p, s, 'ag_endBroker', 'Broker decision recorded'))
    s.side_exits('ag')
    pk.dmn('sec-ins-intermediary', 'Intermediary eligibility (SYNTHETIC; verify current IRDAI regulations)', 'FIRST',
           [('Type', 'intermediaryType', 'string'), ('Qualification', 'qualification', 'string'), ('Net worth', 'netWorth', 'double')],
           [('eligible', 'Eligible', 'boolean')],
           [([q('AGENT'), q('10TH,12TH,GRADUATE').replace(',', '","'), '-'], ['true'], 'minimum 10th / 12th by location'),
            ([q('BROKER'), '-', '>= 7500000'], ['true'], 'direct broker net worth (verify)'), (['-', '-', '-'], ['false'], '')])
    simple_seeds(pk, 'AGENCY', [('AGENCY-ADMIN', 'AGENCY', 'PRINCIPAL-OFFICER', 'Agency administration', 'एजेंसी प्रशासन'),
                                ('PRINCIPAL-OFFICER', 'PO', None, 'Designated person (agency)', 'नामित व्यक्ति (एजेंसी)')], 'iag')


# ============================================================================================ healthcare
def health_ot():
    pk = spack('health', 'ot-scheduling', 'Operation theatre scheduling and surgical safety', 'शल्य कक्ष समय-निर्धारण एवं शल्य सुरक्षा',
               'Healthcare',
               'Surgery request, pre-anaesthetic check (fit / unfit / defer), informed consent for surgery and '
               'anaesthesia, OT slot by rule, WHO surgical safety checklist (sign in, time out, sign out) with the '
               'count and specimen, post-operative recovery; cancellation with reasons.',
               ['SEC-HEALTH-013', 'GP-06', 'UP-04'] + SIDE,
               [('ot.book', 'a', '', 'OT slot booking'), ('lis.specimen', 'a', '', 'Specimen to the laboratory')])
    p = pk.proc('sangam-sec-health-ot', 'Surgery', '', start={'materialFields': 'patientName,procedureName,urgency'})
    s = p.root
    st = start_form(p, s, 'ot_start', 'Surgery requested', 'sec-health-ot-scheduling/request', 'Surgery request | शल्य अनुरोध',
                    ['patientName*', 'procedureName*', 'urgency*', 'clinicalNotes'])
    d = s.setv('ot_defaults', 'Configuration', defaults='patientName=@customerId;procedureName=X;urgency=ELECTIVE;'
               'surgeonSeat=SURGEON;anaesSeat=ANAESTHETIST;otSeat=OT-NURSE')
    r = s.rule('ot_rule', 'Slot window by urgency (DMN)', 'sec-health-ot-urgency')
    pa = s.user('ot_pac', 'Pre-anaesthetic check | पूर्व-निश्चेतना जाँच', '${anaesSeat}', form='sec-health-ot-scheduling/pac',
                decision=('pacDecision', ['fit', 'defer']), fields=['clinicalNotes*'])
    s.seq(st, d, r, pa)
    pg = s.xgw('ot_pacGw')
    s.flow(pa, pg)
    s.flow(pg, s.end('ot_endDeferred', 'Deferred (optimise and re-request)'), "pacDecision == 'defer'", 'unfit / defer')
    cs = s.user('ot_consent', 'Informed consent for surgery and anaesthesia | शल्य एवं निश्चेतना हेतु सूचित सहमति', '${surgeonSeat}',
                form='sec-health-ot-scheduling/consent', decision=None, fields=['consentGiven*', 'documents*'])
    s.flow(pg, cs, None, 'fit', default=True)
    bk = s.conn('ot_book', 'Book OT slot', 'ot.book', 'book', 'submit', 'patientName,procedureName,otWindow', 'ot', step='book')
    s.flow(cs, bk)
    si = s.user('ot_signIn', 'Sign in (before induction) | साइन इन (निश्चेतना से पहले)', '${otSeat}', form='sec-health-ot-scheduling/sign-in',
                decision=None, fields=['checklistDone*'])
    to = s.user('ot_timeOut', 'Time out (before incision) | टाइम आउट (चीरे से पहले)', '${surgeonSeat}',
                form='sec-health-ot-scheduling/time-out', decision=('timeOutDecision', ['proceed', 'abort']),
                fields=['checklistDone*'])
    s.seq(bk, si, to)
    tg = s.xgw('ot_toGw')
    s.flow(to, tg)
    s.flow(tg, s.end('ot_endAborted', 'Aborted at time out'), "timeOutDecision == 'abort'", 'abort')
    so = s.user('ot_signOut', 'Sign out: counts, specimen, notes | साइन आउट: गणना, नमूना, टिप्पणी', '${otSeat}',
                form='sec-health-ot-scheduling/sign-out', decision=None, fields=['checklistDone*', 'clinicalNotes'])
    s.flow(tg, so, None, 'proceed', default=True)
    sp = s.conn('ot_specimen', 'Specimen to histopathology', 'lis.specimen', 'send', 'submit', 'patientName', 'spec', step='specimen')
    rc = s.user('ot_recovery', 'Post-operative recovery and handover | शल्योपरांत रिकवरी एवं हस्तांतरण', 'RECOVERY-NURSE',
                form='sec-health-ot-scheduling/recovery', decision=None, fields=['clinicalNotes*'])
    s.seq(so, sp, rc, end_ok(p, s, 'ot_end', 'Surgery complete'))
    s.side_exits('ot')
    pk.dmn('sec-health-ot-urgency', 'OT slot window (SYNTHETIC hospital policy)', 'FIRST', [('Urgency', 'urgency', 'string')],
           [('otWindow', 'Operate within', 'string')],
           [([q('EMERGENCY')], [q('1H')], ''), ([q('URGENT')], [q('24H')], ''), (['-'], [q('14D')], 'elective')])
    simple_seeds(pk, 'OT', [('SURGEON', 'SURGEON', 'OT-IN-CHARGE', 'Surgeon', 'शल्य चिकित्सक'),
                            ('ANAESTHETIST', 'ANAES', 'OT-IN-CHARGE', 'Anaesthetist', 'निश्चेतना विशेषज्ञ'),
                            ('OT-NURSE', 'NURSE', 'OT-IN-CHARGE', 'OT nurse', 'शल्य कक्ष परिचारिका'),
                            ('RECOVERY-NURSE', 'NURSE', 'OT-IN-CHARGE', 'Recovery nurse', 'रिकवरी परिचारिका'),
                            ('OT-IN-CHARGE', 'OTIC', None, 'OT in-charge', 'शल्य कक्ष प्रभारी')], 'hot')


def health_blood_bank():
    pk = spack('health', 'blood-bank', 'Blood centre: donation, testing and transfusion', 'रक्त केंद्र: दान, परीक्षण एवं आधान',
               'Healthcare',
               'Donation: donor screening and deferral, collection, mandatory TTI tests (HIV, HBV, HCV, syphilis, '
               'malaria), release to inventory or discard. Transfusion: request, grouping and cross-match, issue, '
               'bedside identity check, monitoring, reaction reported to haemovigilance (HvPI) (Drugs and '
               'Cosmetics Rules Part XII-B, NBTC guidelines).',
               ['SEC-HEALTH-014', 'GP-16', 'UP-18'],
               [('bbms.inventory', 'a', '', 'Blood inventory (e-RaktKosh)'), ('hvpi.report', 'a', '', 'Haemovigilance report')])
    p = pk.proc('sangam-sec-health-blood-donation', 'Blood donation', '', start={'materialFields': 'donorName,bloodGroup'})
    s = p.root
    st = start_form(p, s, 'bd_start', 'Donor registers', 'sec-health-blood-bank/donor', 'Blood donor registration | रक्तदाता पंजीकरण',
                    ['donorName*', 'bloodGroup', 'age*', 'haemoglobin*'])
    d = s.setv('bd_defaults', 'Configuration', defaults='donorName=@customerId;bloodGroup=O+;haemoglobin=13;ttiReactive=false')
    r = s.rule('bd_elig', 'Donor eligibility (DMN)', 'sec-health-donor')
    g = s.xgw('bd_gw', 'Eligible?')
    s.seq(st, d, r, g)
    s.flow(g, s.end('bd_endDeferred', 'Deferred (reason told to the donor)'), '!donorEligible', 'defer')
    cl = s.user('bd_collect', 'Collect blood and label | रक्त संग्रह एवं लेबल', 'BB-TECH', form='sec-health-blood-bank/collect',
                decision=None, fields=['sampleBarcode*'])
    tt = s.user('bd_tti', 'TTI testing | टीटीआई परीक्षण', 'BB-TECH', form='sec-health-blood-bank/tti', decision=None,
                fields=['ttiReactive*', 'results'])
    s.flow(g, cl, None, 'yes', default=True)
    s.flow(cl, tt)
    tg = s.xgw('bd_ttiGw', 'Reactive?')
    s.flow(tt, tg)
    dc = s.user('bd_discard', 'Discard and counsel the donor | त्यागें एवं दाता परामर्श', 'BB-MO', form='sec-health-blood-bank/discard',
                decision=None, fields=['remarks*'])
    s.flow(tg, dc, 'ttiReactive', 'reactive')
    s.flow(dc, s.end('bd_endDiscarded', 'Discarded'))
    inv = s.conn('bd_inventory', 'Release to inventory', 'bbms.inventory', 'add', 'submit', 'sampleBarcode,bloodGroup', 'inv',
                 step='inventory')
    s.flow(tg, inv, None, 'non-reactive', default=True)
    s.flow(inv, end_ok(p, s, 'bd_end', 'In inventory'))
    t = pk.proc('sangam-sec-health-transfusion', 'Transfusion', '', start={'materialFields': 'patientName,bloodGroup,component'})
    s = t.root
    st = start_form(t, s, 'tf_start', 'Blood requested', 'sec-health-blood-bank/request', 'Blood request | रक्त अनुरोध',
                    ['patientName*', 'bloodGroup', 'component*', 'urgency'])
    d = s.setv('tf_defaults', 'Configuration', defaults='patientName=@customerId;bloodGroup=O+;component=PRBC;urgency=ROUTINE;'
               'reaction=false')
    xm = s.user('tf_xmatch', 'Grouping and cross-match | समूहन एवं क्रॉस-मैच', 'BB-TECH', form='sec-health-blood-bank/xmatch',
                decision=('xmDecision', ['compatible', 'incompatible']), fields=['results*'])
    s.seq(st, d, xm)
    xg = s.xgw('tf_xmGw')
    s.flow(xm, xg)
    s.flow(xg, s.end('tf_endNoUnit', 'No compatible unit: clinician told'), "xmDecision == 'incompatible'", 'incompatible')
    iss = s.conn('tf_issue', 'Issue unit', 'bbms.inventory', 'issue', 'submit', 'patientName,bloodGroup,component', 'issue',
                 step='issue')
    s.flow(xg, iss, None, 'compatible', default=True)
    bs = s.user('tf_bedside', 'Bedside identity check and transfuse | शय्या पर पहचान जाँच एवं आधान', 'WARD-NURSE',
                form='sec-health-blood-bank/bedside', decision=None, fields=['checklistDone*', 'reaction'])
    s.flow(iss, bs)
    rg = s.xgw('tf_reactGw', 'Reaction?')
    s.flow(bs, rg)
    hv = s.conn('tf_hvpi', 'Report reaction to haemovigilance', 'hvpi.report', 'report', 'submit', 'patientName,component', 'hv',
                step='hvpi')
    s.flow(rg, hv, 'reaction', 'yes')
    e = end_ok(t, s, 'tf_end', 'Transfused')
    s.flow(rg, e, None, 'no', default=True)
    s.flow(hv, e)
    pk.dmn('sec-health-donor', 'Donor eligibility (verify against the current blood donor selection criteria)', 'FIRST',
           [('Age', 'age', 'integer'), ('Haemoglobin g/dL', 'haemoglobin', 'double')], [('donorEligible', 'Eligible', 'boolean')],
           [(['< 18', '-'], ['false'], ''), (['> 65', '-'], ['false'], ''), (['-', '< 12.5'], ['false'], 'Hb below 12.5'),
            (['-', '-'], ['true'], '')])
    simple_seeds(pk, 'BLOOD-CENTRE', [('BB-TECH', 'TECH', 'BB-MO', 'Blood centre technician', 'रक्त केंद्र तकनीशियन'),
                                      ('BB-MO', 'MO', None, 'Blood centre medical officer', 'रक्त केंद्र चिकित्सा अधिकारी'),
                                      ('WARD-NURSE', 'NURSE', 'BB-MO', 'Ward nurse', 'वार्ड परिचारिका')], 'hbb')


# ============================================================================================ e-commerce: GST TCS
def ecom_gst_tcs():
    pk = spack('ecom', 'gst-tcs', 'GST tax collected at source by the marketplace', 'मार्केटप्लेस द्वारा स्रोत पर जीएसटी संग्रह',
               'E-commerce',
               'Monthly: TCS on the net taxable supplies made through the platform by each seller (rate by rule, '
               'CGST Act s.52), reviewed by the tax team, deposited by the 10th of the next month, GSTR-8 filed by '
               'the 10th, amendments in later returns; annual GSTR-9B.',
               ['SEC-ECOM-009', 'GP-30', 'GP-12'],
               [('erp.netSupplies', 'a', '', 'Net taxable supplies by seller'), ('gstn.payment', 'c', '', 'Tax deposit'),
                ('gstn.gstr8', 'b', '', 'GSTR-8 filing')])
    p = pk.proc('sangam-sec-ecom-gst-tcs', 'Monthly GST TCS', '', start={'materialFields': 'period'})
    s = p.root
    t = s.start('gt_timer', 'First of the month', timer_cycle='0 0 6 1 * ?', initiator=None)
    d = s.setv('gt_defaults', 'Configuration', defaults='period=CURRENT;taxSeat=TAX-TEAM;netSupplies=0;executionConnector=gstn.payment;'
               'executionStep=tcs-deposit;verifyApproval=false;reconcileRequired=true;executionPayloadFields=period,tcsAmount')
    ns = s.conn('gt_supplies', 'Net taxable supplies by seller GSTIN', 'erp.netSupplies', 'month', 'query', None, 'net')
    r = s.rule('gt_rate', 'TCS rate (DMN)', 'sec-ecom-tcs-rate')
    cp = s.expr('gt_compute', 'TCS amount', "${execution.setVariable('tcsAmount', netSupplies * tcsRatePct / 100)}")
    re_, rd = s.sla_task('gt_review', 'Review TCS by seller (deposit by the 10th) | विक्रेतावार टीसीएस की समीक्षा', 'taxSeat',
                         form='sec-ecom-gst-tcs/review', decision=None, fields=['remarks'], spec='7D', on_breach='ESCALATE')
    s.seq(t, d, ns, r, cp, re_)
    dp = core(s, 'gt_deposit', 'Deposit TCS (core execution)', 'execution')
    s.flow(rd, dp)
    fl = s.conn('gt_gstr8', 'File GSTR-8', 'gstn.gstr8', 'file', 'submit', 'period,tcsAmount', 'gstr8', step='gstr8')
    s.flow(dp, fl)
    fg = s.xgw('gt_fileGw', 'Filed?')
    s.flow(fl, fg)
    s.flow(fg, re_, "gstr8Status == 'FAILED'", 'error: correct')
    s.flow(fg, end_ok(p, s, 'gt_end', 'Filed'), None, 'filed', default=True)
    pk.dmn('sec-ecom-tcs-rate', 'GST TCS rate (CGST + SGST / IGST; verify notification)', 'FIRST', [('Supplies', 'netSupplies', 'double')],
           [('tcsRatePct', 'Rate %', 'double')], [(['-'], ['0.5'], '0.5 % from 10 Jul 2024 (was 1 %) (verify)')])
    simple_seeds(pk, 'TAX', [('TAX-TEAM', 'TAX', None, 'Indirect tax team', 'अप्रत्यक्ष कर दल')], 'egt')


# ============================================================================================ telecom
def tel_tcccpr():
    pk = spack('tel', 'tcccpr', 'Commercial communications: consent and spam complaints (TCCCPR)',
               'वाणिज्यिक संचार: सहमति एवं स्पैम शिकायतें (टीसीसीसीपीआर)', 'Telecom',
               'TCCCPR 2018 (amended 2025): consent recorded on the DLT consent register through a verified '
               'acquisition, revocable at any time; unsolicited commercial communication complaints verified '
               'against preferences and consent, the sender warned / blocked by the graded action rule, '
               'complainant informed within the regulatory timeline.',
               ['SEC-TEL-004', 'GP-06', 'GP-33', 'UP-03'],
               [('dlt.consent', 'a', '', 'DLT consent register'), ('dlt.preference', 'a', '', 'Preference register'),
                ('dlt.action', 'a', '', 'Action on sender')])
    p = pk.proc('sangam-sec-tel-ucc-complaint', 'UCC complaint', '', start={'materialFields': 'msisdn,senderHeader'})
    s = p.root
    st = start_form(p, s, 'uc_start', 'Spam complaint (1909 / app)', 'sec-tel-tcccpr/complaint', 'Spam complaint | स्पैम शिकायत',
                    ['msisdn*', 'senderHeader*', 'description'])
    d = s.setv('uc_defaults', 'Configuration', defaults='msisdn=@customerId;senderHeader=X;consentFound=false;'
               'priorViolations=0;opsSeat=UCC-DESK;applicantRef=@msisdn')
    ck = clock(s, 'uc_clock', 'ucc', '${uccSpec}')
    r = s.rule('uc_rule', 'Timeline and graded action (DMN)', 'sec-tel-ucc')
    cs = s.conn('uc_consent', 'Check preference and consent registers', 'dlt.consent', 'check', 'query', 'msisdn,senderHeader',
                'consent')
    s.seq(st, d, r, ck, cs)
    ve, vd = s.sla_task('uc_verify', 'Verify complaint | शिकायत का सत्यापन', 'opsSeat', form='sec-tel-tcccpr/verify',
                        decision=('uccDecision', ['valid', 'invalid']), fields=['remarks*'], spec='${uccSpec}', on_breach='ESCALATE')
    s.flow(cs, ve)
    g = s.xgw('uc_gw')
    s.flow(vd, g)
    ac = s.conn('uc_action', 'Graded action on the sender (warn / suspend / blacklist)', 'dlt.action', 'act', 'submit',
                'senderHeader,senderAction', 'act', step='action')
    s.flow(g, ac, "uccDecision == 'valid'", 'valid')
    n = s.notify('uc_notify', 'Tell the complainant', 'ucc-outcome', 'sms', 'applicantRef')
    s.flow(g, n, None, 'invalid (consent on record)', default=True)
    s.flow(ac, n)
    s.flow(n, end_ok(p, s, 'uc_end', 'Closed'))
    c = pk.proc('sangam-sec-tel-consent', 'Consent acquisition and revocation', '', start={'materialFields': 'msisdn,principalEntity'})
    s = c.root
    st = start_form(c, s, 'cn_start', 'Consent sought', 'sec-tel-tcccpr/consent', 'Consent request | सहमति अनुरोध',
                    ['msisdn*', 'principalEntity*', 'consentPurpose*'])
    d = s.setv('cn_defaults', 'Configuration', defaults='msisdn=@customerId;principalEntity=PE-1;consentPurpose=PROMOTIONAL')
    w = s.egw('cn_wait', 'Customer confirms or ignores')
    s.seq(st, d, w)
    ok = s.catch_msg('cn_confirm', 'Customer confirms (OTP / short code)', 'sangam.dlt.consentConfirmed')
    to = s.catch_timer('cn_expire', 'Not confirmed', duration='P1D')
    s.flow(w, ok)
    s.flow(w, to)
    s.flow(to, s.end('cn_endNone', 'No consent recorded'))
    rc = s.conn('cn_record', 'Record consent on DLT', 'dlt.consent', 'record', 'submit', 'msisdn,principalEntity,consentPurpose',
                'rec', step='record')
    s.flow(ok, rc)
    rv = s.catch_msg('cn_revoke', 'Customer revokes', 'sangam.dlt.consentRevoked')
    s.flow(rc, rv)
    rr = s.conn('cn_doRevoke', 'Revoke on DLT', 'dlt.consent', 'revoke', 'submit', 'msisdn,principalEntity', 'rev', step='revoke')
    s.seq(rv, rr, end_ok(c, s, 'cn_end', 'Revoked'))
    pk.dmn('sec-tel-ucc', 'UCC complaint timeline and graded action (TCCCPR 2018 as amended 2025; verify)', 'FIRST',
           [('Prior violations', 'priorViolations', 'integer')],
           [('uccSpec', 'Act within', 'string'), ('senderAction', 'Action', 'string')],
           [(['>= 2'], [q('5D'), q('BLACKLIST')], 'repeat offender'), (['>= 1'], [q('5D'), q('SUSPEND')], ''),
            (['-'], [q('5D'), q('WARN')], 'first violation')])
    simple_seeds(pk, 'TELCO-UCC', [('UCC-DESK', 'UCC', None, 'UCC complaint desk', 'यूसीसी शिकायत डेस्क')], 'ttc')


def tel_sim_swap():
    pk = spack('tel', 'sim-swap', 'SIM replacement with re-verification', 'पुनः सत्यापन सहित सिम प्रतिस्थापन', 'Telecom',
               'SIM swap / replacement: subscriber re-verified (e-KYC or biometric at the point of sale), fraud '
               'checks, new SIM activated, SMS barred for 24 hours after the swap (DoT), alert to the old SIM and '
               'registered e-mail, unbarred after the period.',
               ['SEC-TEL-005', 'GP-24', 'UP-21'],
               [('dot.kyc', 'a', '', 'Re-verification'), ('hlr.swap', 'a', '', 'Swap'), ('hlr.smsBar', 'a', '', 'SMS barring')])
    p = pk.proc('sangam-sec-tel-sim-swap', 'SIM swap', '', start={'materialFields': 'msisdn,customerName'})
    s = p.root
    st = start_form(p, s, 'ss_start', 'Replacement requested', 'sec-tel-sim-swap/request', 'SIM replacement | सिम प्रतिस्थापन',
                    ['msisdn*', 'customerName*', 'swapReason*'])
    d = s.setv('ss_defaults', 'Configuration', defaults='msisdn=@customerId;customerName=X;swapReason=LOST;kycOk=true;'
               'fraudHit=false;applicantRef=@msisdn')
    ky = s.conn('ss_kyc', 'Re-verify subscriber', 'dot.kyc', 'reverify', 'query', 'msisdn,customerName', 'kyc')
    g = s.xgw('ss_gw', 'Verified?')
    s.seq(st, d, ky, g)
    s.flow(g, reject_with(p, s, 'ss_rj', 'REVERIFICATION_FAILED'), '!kycOk || fraudHit', 'no')
    al = s.notify('ss_alert', 'Alert on the old SIM and e-mail', 'sim-swap-alert', 'sms,email', 'applicantRef')
    sw = s.conn('ss_swap', 'Activate the new SIM', 'hlr.swap', 'swap', 'submit', 'msisdn', 'swap', step='swap')
    br = s.conn('ss_bar', 'Bar SMS for 24 hours', 'hlr.smsBar', 'bar', 'submit', 'msisdn', 'bar', step='bar')
    s.flow(g, al, None, 'yes', default=True)
    s.seq(al, sw, br)
    t = s.catch_timer('ss_24h', '24 hours', duration='PT24H')
    ub = s.conn('ss_unbar', 'Lift SMS barring', 'hlr.smsBar', 'unbar', 'submit', 'msisdn', 'unbar', step='unbar')
    s.seq(br, t, ub, end_ok(p, s, 'ss_end', 'Replaced'))
    simple_seeds(pk, 'TELCO-POS', [('POS-AGENT', 'POS', None, 'Point of sale', 'बिक्री केंद्र')], 'tss')


# ============================================================================================ supply chain: e-way bill, MRP
def scm_eway():
    pk = spack('scm', 'eway-bill', 'E-way bill lifecycle', 'ई-वे बिल जीवनचक्र', 'Supply chain',
               'Movement of goods above the value threshold: e-way bill (Part A, Part B with vehicle) generated '
               'before movement, validity by distance, vehicle update on transhipment, extension within 8 hours of '
               'expiry, cancellation within 24 hours if goods do not move, recipient rejection within 72 hours '
               '(CGST Rules r.138).',
               ['SEC-SCM-004', 'GP-06', 'GP-24'],
               [('ewb.generate', 'b', '', 'Generate'), ('ewb.updateVehicle', 'b', '', 'Part B update'),
                ('ewb.extend', 'b', '', 'Extend'), ('ewb.cancel', 'b', '', 'Cancel')])
    p = pk.proc('sangam-sec-scm-eway-bill', 'E-way bill', '', start={'materialFields': 'invoiceNo,consignmentValue,distanceKm'})
    s = p.root
    st = start_form(p, s, 'ew_start', 'Dispatch planned', 'sec-scm-eway-bill/dispatch', 'Dispatch | प्रेषण',
                    ['invoiceNo*', 'consignmentValue*', 'distanceKm*', 'vehicleNo', 'recipientGstin'])
    d = s.setv('ew_defaults', 'Configuration', defaults='invoiceNo=@customerId;consignmentValue=100000;distanceKm=400;vehicleNo=X')
    r = s.rule('ew_rule', 'E-way bill needed and validity (DMN)', 'sec-scm-ewb')
    g = s.xgw('ew_gw', 'Required?')
    s.seq(st, d, r, g)
    s.flow(g, s.end('ew_endNotReq', 'Not required (below threshold)'), '!ewbRequired', 'no')
    gn = s.conn('ew_generate', 'Generate e-way bill', 'ewb.generate', 'generate', 'submit', 'invoiceNo,consignmentValue,vehicleNo',
                'ewb', step='generate')
    vc = clock(s, 'ew_valid', 'validity', '${validitySpec}')
    s.flow(g, gn, None, 'yes', default=True)
    s.flow(gn, vc)
    w = s.egw('ew_wait', 'Delivered, moved, rejected or expiring')
    s.flow(vc, w)
    dv = s.catch_msg('ew_delivered', 'Delivered', 'sangam.ewb.delivered')
    nm = s.catch_msg('ew_notMoved', 'Goods not moved', 'sangam.ewb.notMoved')
    tr = s.catch_msg('ew_tranship', 'Transhipment / vehicle change', 'sangam.ewb.vehicleChange')
    rj = s.catch_msg('ew_rejected', 'Recipient rejects (72 hours)', 'sangam.ewb.rejected')
    ex = s.catch_timer('ew_expiring', 'Validity ending', date='${validityDueAt}')
    for x in (dv, nm, tr, rj, ex):
        s.flow(w, x)
    s.flow(dv, end_ok(p, s, 'ew_end', 'Delivered'))
    cn = s.conn('ew_cancel', 'Cancel within 24 hours', 'ewb.cancel', 'cancel', 'submit', 'invoiceNo', 'cancel', step='cancel')
    s.seq(nm, cn, s.end('ew_endCancelled', 'Cancelled'))
    uv = s.conn('ew_update', 'Update Part B', 'ewb.updateVehicle', 'update', 'submit', 'invoiceNo,vehicleNo', 'upd', step='part-b')
    s.seq(tr, uv, w)
    s.flow(rj, s.end('ew_endRejected', 'Rejected by the recipient'))
    xt = s.conn('ew_extend', 'Extend validity (within 8 hours of expiry)', 'ewb.extend', 'extend', 'submit', 'invoiceNo', 'ext',
                step='extend')
    vc2 = clock(s, 'ew_valid2', 'validity', '1D')
    s.seq(ex, xt, vc2, w)
    pk.dmn('sec-scm-ewb', 'E-way bill threshold and validity (CGST Rules r.138; verify state thresholds)', 'FIRST',
           [('Value', 'consignmentValue', 'double'), ('Distance (km)', 'distanceKm', 'integer')],
           [('ewbRequired', 'Required', 'boolean'), ('validitySpec', 'Validity', 'string')],
           [(['<= 50000', '-'], ['false', q('1D')], 'threshold Rs 50,000'),
            (['-', '<= 200'], ['true', q('1D')], '1 day per 200 km'), (['-', '<= 400'], ['true', q('2D')], ''),
            (['-', '<= 800'], ['true', q('4D')], ''), (['-', '-'], ['true', q('8D')], 'long distance (SYNTHETIC cap)')])
    simple_seeds(pk, 'LOGISTICS', [('DISPATCH', 'DISPATCH', None, 'Dispatch desk', 'प्रेषण डेस्क')], 'sew')


def mfg_mrp():
    pk = spack('mfg', 'mrp-planning', 'Material requirements planning run', 'सामग्री आवश्यकता नियोजन', 'Manufacturing',
               'Weekly MRP run: net requirements from demand, stock and open orders; the planner reviews exception '
               'messages; each planned order becomes its own case (GP-09) that is converted to a purchase '
               'requisition (procure-to-pay) or a production order after approval.',
               ['SEC-MFG-003', 'GP-08', 'GP-09', 'GP-30'],
               [('erp.mrpRun', 'a', '', 'MRP run'), ('erp.plannedOrders', 'a', '', 'Planned orders'),
                ('erp.productionOrder', 'a', '', 'Production order')],
               depends=('sangam.core', 'sec.scm.procure-to-pay'))
    p = pk.proc('sangam-sec-mfg-mrp', 'MRP run', '', start={'materialFields': 'period'})
    s = p.root
    t = s.start('mr_timer', 'Weekly', timer_cycle='0 0 2 ? * MON', initiator=None)
    d = s.setv('mr_defaults', 'Configuration', defaults='plannerSeat=PLANNER')
    rn = s.conn('mr_run', 'Run MRP', 'erp.mrpRun', 'run', 'submit', None, 'mrp', step='mrp-run')
    rv = s.user('mr_review', 'Review exception messages | अपवाद संदेशों की समीक्षा', '${plannerSeat}', form='sec-mfg-mrp-planning/review',
                decision=None, fields=['remarks'])
    ld = s.svc('mr_load', 'Planned orders', C['BATCH'], {'op': 'LOAD', 'connector': 'erp.plannedOrders', 'sampleSize': '3'},
               async_=True)
    s.seq(t, d, rn, rv, ld)
    op, cg = batch_loop(p, s, 'mr', 'sangam-sec-mfg-planned-order', chunk=100, poll='PT30M')
    s.flow(ld, op)
    s.flow(cg, s.end('mr_end', 'Run closed'), None, 'complete', default=True)
    c = pk.proc('sangam-sec-mfg-planned-order', 'Planned order', '', start={'materialFields': 'key'})
    s = c.root
    a = s.start('po_start', 'Planned order', initiator=None)
    d = s.setv('po_defaults', 'Configuration', defaults='procurementType=BUY;itemDescription=@key;quantity=10;unitPrice=100')
    ap = s.user('po_firm', 'Firm the planned order | नियोजित आदेश को अंतिम करें', 'PLANNER', form='sec-mfg-mrp-planning/firm',
                decision=('firmDecision', ['approve', 'reject']), fields=['quantity*', 'remarks'])
    s.seq(a, d, ap)
    g = s.xgw('po_gw')
    s.flow(ap, g)
    s.flow(g, s.end('po_endDropped', 'Dropped', listeners=[c.item_listener('DROPPED')]), "firmDecision == 'reject'", 'reject')
    tg = s.xgw('po_typeGw', 'Buy or make?')
    s.flow(g, tg, None, 'approve', default=True)
    pr = s.svc('po_pr', 'Raise purchase requisition (procure-to-pay)', C['LINK'],
               {'processKey': 'sangam-sec-scm-p2p', 'linkType': 'requisition', 'inherit': 'itemDescription,quantity,unitPrice'})
    mo = s.conn('po_make', 'Create production order', 'erp.productionOrder', 'create', 'submit', 'key,quantity', 'prod',
                step='production-order')
    s.flow(tg, pr, "procurementType == 'BUY'", 'buy')
    s.flow(tg, mo, None, 'make', default=True)
    e = end_ok(c, s, 'po_end', 'Converted')
    s.flow(pr, e)
    s.flow(mo, e)
    simple_seeds(pk, 'PLANNING', [('PLANNER', 'PLANNER', None, 'Material planner', 'सामग्री नियोजक')], 'mmr')


# ============================================================================================ education
def edu_exam():
    pk = spack('edu', 'examination', 'Examination, results and revaluation', 'परीक्षा, परिणाम एवं पुनर्मूल्यांकन', 'Education',
               'Examination cycle: registration window, hall tickets, conduct, evaluation, moderation by the '
               'result committee, publication and upload to DigiLocker / NAD (ABC credits); revaluation requests '
               'within the window, each its own case with a different evaluator and the revised result.',
               ['SEC-EDU-004', 'GP-03', 'GP-22', 'GP-24', 'GP-47'],
               [('exam.hallTickets', 'a', '', 'Hall tickets'), ('exam.publish', 'a', '', 'Publish results'),
                ('nad.upload', 'a', '', 'DigiLocker / NAD upload'), ('pg.collect', 'b', '', 'Revaluation fee')])
    p = pk.proc('sangam-sec-edu-exam-cycle', 'Examination cycle', '', start={'materialFields': 'examName,programme'})
    s = p.root
    st = start_form(p, s, 'ex_start', 'Examination notified', 'sec-edu-examination/notify', 'Examination notification | परीक्षा अधिसूचना',
                    ['examName*', 'programme*', 'registrationWindow'])
    d = s.setv('ex_defaults', 'Configuration', defaults='examName=@customerId;programme=BA;registrationWindow=P15D;'
               'committeeSeats=RESULT-COMM-1,RESULT-COMM-2,RESULT-COMM-3;quorumRule=MAJORITY;coeSeat=CONTROLLER')
    rw = s.catch_timer('ex_regClose', 'Registration closes', duration='${registrationWindow}')
    ht = s.conn('ex_hall', 'Issue hall tickets', 'exam.hallTickets', 'issue', 'submit', 'examName', 'hall', step='hall-tickets')
    cd = s.user('ex_conduct', 'Conduct examination | परीक्षा संचालन', '${coeSeat}', form='sec-edu-examination/conduct',
                decision=None, fields=['remarks'])
    ev = s.user('ex_evaluate', 'Evaluation and marks entry | मूल्यांकन एवं अंक प्रविष्टि', 'EVALUATOR',
                form='sec-edu-examination/evaluate', decision=None, fields=['results*'])
    s.seq(st, d, rw, ht, cd, ev)
    mc = core(s, 'ex_moderate', 'Result committee moderation (core committee)', 'committee')
    s.flow(ev, mc)
    mg = s.xgw('ex_modGw')
    s.flow(mc, mg)
    s.flow(mg, ev, '!committeeApproved', 're-evaluate')
    pb = s.conn('ex_publish', 'Publish results', 'exam.publish', 'publish', 'submit', 'examName', 'pub', step='publish')
    nd = s.conn('ex_nad', 'Upload to DigiLocker / NAD and ABC', 'nad.upload', 'upload', 'submit', 'examName', 'nad', step='nad')
    s.flow(mg, pb, None, 'approved', default=True)
    s.seq(pb, nd, end_ok(p, s, 'ex_end', 'Results published'))
    c = pk.proc('sangam-sec-edu-revaluation', 'Revaluation request', '', start={'materialFields': 'applicantName,examName,subject'})
    s = c.root
    st = start_form(c, s, 'rv_start', 'Revaluation requested', 'sec-edu-examination/revaluation',
                    'Revaluation request | पुनर्मूल्यांकन अनुरोध', ['applicantName*', 'examName*', 'subject*'])
    d = s.setv('rv_defaults', 'Configuration', defaults='examName=X;executionConnector=pg.collect;executionStep=reval-fee;'
               'verifyApproval=false;reconcileRequired=false;executionPayloadFields=applicantName,subject')
    fe = core(s, 'rv_fee', 'Collect fee (core execution)', 'execution')
    ee, ed = s.sla_task('rv_eval', 'Re-evaluate by a different evaluator | भिन्न मूल्यांकक द्वारा पुनर्मूल्यांकन', 'revaluatorSeat',
                        form='sec-edu-examination/re-evaluate', decision=None, fields=['results*', 'remarks'], spec='15D',
                        sod=True, sod_steps='ex_evaluate')
    rs = s.setv('rv_seat', 'Evaluator', defaults='revaluatorSeat=EVALUATOR-2')
    s.seq(st, d, fe, rs, ee)
    pb = s.conn('rv_publish', 'Publish the revised result', 'exam.publish', 'revise', 'submit', 'applicantName,subject', 'rpub',
                step='revised')
    s.seq(ed, pb, end_ok(c, s, 'rv_end', 'Revaluation done'))
    simple_seeds(pk, 'EXAMS', [('CONTROLLER', 'COE', None, 'Controller of examinations', 'परीक्षा नियंत्रक'),
                               ('EVALUATOR', 'EVALUATOR', 'CONTROLLER', 'Evaluator', 'मूल्यांकक'),
                               ('EVALUATOR-2', 'EVALUATOR', 'CONTROLLER', 'Second evaluator', 'द्वितीय मूल्यांकक'),
                               ('RESULT-COMM-1', 'COMMITTEE', None, 'Result committee member 1', 'परिणाम समिति सदस्य 1'),
                               ('RESULT-COMM-2', 'COMMITTEE', None, 'Result committee member 2', 'परिणाम समिति सदस्य 2'),
                               ('RESULT-COMM-3', 'COMMITTEE', None, 'Result committee member 3', 'परिणाम समिति सदस्य 3')], 'eex')


def edu_scholarship():
    pk = spack('edu', 'scholarship', 'Scholarship through the National Scholarship Portal', 'राष्ट्रीय छात्रवृत्ति पोर्टल द्वारा छात्रवृत्ति',
               'Education',
               'Student application with income and category, institute verification (L1), district / state '
               'verification (L2), sanction, payment by Aadhaar-based DBT through PFMS once by operationId, payment '
               'failure re-verified with corrected bank details, renewal next year.',
               ['SEC-EDU-005', 'GP-16', 'GP-12', 'GP-49'] + SIDE,
               [('nsp.application', 'a', '', 'NSP application'), ('pfms.dbt', 'c', '', 'DBT payment')])
    p = pk.proc('sangam-sec-edu-scholarship', 'Scholarship', '', start={'materialFields': 'applicantName,schemeId,income'})
    s = p.root
    st = start_form(p, s, 'sc_start', 'Application on NSP', 'sec-edu-scholarship/apply', 'Scholarship application | छात्रवृत्ति आवेदन',
                    ['applicantName*', 'schemeId*', 'income*', 'socialCategory*', 'bankAccount', 'documents'])
    d = s.setv('sc_defaults', 'Configuration',
               defaults='income=100000;amount=12000;caseType=SCHOLARSHIP;routingDecisionKey=sec-edu-sch-authority;'
                        'powersDecisionKey=sec-edu-sch-powers;executionConnector=pfms.dbt;executionStep=dbt;'
                        'reconcileRequired=true;executionPayloadFields=applicantName,amount;appealAllowed=true')
    r = s.rule('sc_rule', 'Income ceiling (DMN)', 'sec-edu-sch-eligibility')
    g = s.xgw('sc_gw', 'Eligible?')
    s.seq(st, d, r, g)
    s.flow(g, reject_with(p, s, 'sc_rjInc', 'INCOME_ABOVE_CEILING'), '!eligible', 'no')
    l1 = s.user('sc_l1', 'Institute verification (L1) | संस्थान सत्यापन (एल1)', 'INSTITUTE-NODAL', form='sec-edu-scholarship/l1',
                decision=('l1Decision', ['verified', 'failed']), fields=['remarks'])
    s.flow(g, l1, None, 'yes', default=True)
    l1g = s.xgw('sc_l1Gw')
    s.flow(l1, l1g)
    rej = rejection(p, s, 'sc_rj')
    s.flow(l1g, rej, "l1Decision == 'failed'", 'not verified')
    san = core(s, 'sc_l2', 'District / state verification and sanction (L2, core sanction)', 'sanction')
    s.flow(l1g, san, None, 'verified', default=True)
    pay = core(s, 'sc_pay', 'Pay by DBT (core execution)', 'execution')
    outcome(s, 'sc_sanGw', 'sanctionOutcome', {'SANCTIONED': pay, 'RETURNED': l1}, rej)
    s.flow(san, 'sc_sanGw')
    fx = s.user('sc_fix', 'Correct bank / Aadhaar seeding after failure | विफलता के बाद बैंक / आधार सुधार', 'INSTITUTE-NODAL',
                form='sec-edu-scholarship/fix', decision=None, fields=['bankAccount*', 'remarks'])
    outcome(s, 'sc_payGw', 'executionOutcome', {'DONE': end_ok(p, s, 'sc_end', 'Paid')}, fx)
    s.flow(pay, 'sc_payGw')
    s.flow(fx, san)
    s.side_exits('sc')
    pk.dmn('sec-edu-sch-eligibility', 'Income ceiling (SYNTHETIC; per scheme guidelines)', 'FIRST',
           [('Income', 'income', 'double')], [('eligible', 'Eligible', 'boolean')],
           [(['<= 250000'], ['true'], 'post-matric style ceiling Rs 2.5 lakh'), (['-'], ['false'], '')])
    authority(pk, 'sec-edu-sch-authority', 'Scholarship sanction', [('-', 'DNO', 'district / state nodal officer')])
    powers(pk, 'sec-edu-sch-powers', [('DNO', 1000000, '')])
    simple_seeds(pk, 'NSP', [('INSTITUTE-NODAL', 'INO', 'DISTRICT-NODAL', 'Institute nodal officer', 'संस्थान नोडल अधिकारी'),
                             ('DISTRICT-NODAL', 'DNO', None, 'District nodal officer', 'जिला नोडल अधिकारी')], 'esc')


# ============================================================================================ utilities
def util_billing():
    pk = spack('util', 'meter-to-cash', 'Meter to cash (billing cycle)', 'मीटर से नकद (बिलिंग चक्र)', 'Utilities',
               'Monthly cycle, one case per consumer (GP-09): meter read (AMI / manual / prepaid), validation '
               'exceptions (zero, abnormal, meter defective: provisional bill), bill by tariff rule, delivery, due '
               'date, payment by operationId, late payment surcharge, bill dispute with revision; non-payment '
               'hands over to disconnection.',
               ['SEC-UTIL-004', 'GP-08', 'GP-09', 'GP-30', 'GP-12'],
               [('mdm.consumersDue', 'a', '', 'Consumers in the cycle: items with key and, for a new connection, connectionCaseKey'), ('mdm.read', 'a', '', 'Meter read'),
                ('billing.generate', 'a', '', 'Bill'), ('billing.revise', 'a', '', 'Bill revision')],
               depends=('sangam.core', 'sec.util.disconnection'))
    p = pk.proc('sangam-sec-util-billing-cycle', 'Billing cycle', '', start={'materialFields': 'period'})
    s, cg = campaign(pk, p, 'bc', '0 0 1 1 * ?', 'mdm.consumersDue', 'sangam-sec-util-bill')
    s.flow(cg, s.end('bc_end', 'Cycle closed'), None, 'complete', default=True)
    c = pk.proc('sangam-sec-util-bill', 'Bill of one consumer', '', start={'materialFields': 'key'})
    s = c.root
    a = s.start('bl_start', 'Consumer in the cycle', initiator=None)
    d = s.setv('bl_defaults', 'Configuration', defaults='units=100;mdmReadStatus=NORMAL;tariffCategory=DOMESTIC;applicantRef=@key;'
               'billingSeat=BILLING-CLERK;dueSpec=15D;consumer=@key;tariffId=DEFAULT;cycle=@batchId;connectionCaseKey=')
    rd = s.conn('bl_read', 'Meter read', 'mdm.read', 'read', 'query', 'key', 'mdm')
    g = s.xgw('bl_readGw', 'Read valid?')
    s.seq(a, d, rd, g)
    vx = s.user('bl_exception', 'Resolve read exception (provisional bill if meter defective) | रीडिंग अपवाद का समाधान',
                '${billingSeat}', form='sec-util-meter-to-cash/exception', decision=None, fields=['units*', 'remarks'])
    tr = s.rule('bl_tariff', 'Tariff (DMN)', 'sec-util-tariff')
    s.flow(g, vx, "mdmReadStatus != 'NORMAL'", 'zero / abnormal / defective')
    s.flow(g, tr, None, 'normal', default=True)
    s.flow(vx, tr)
    cp = s.expr('bl_compute', 'Bill amount', "${execution.setVariable('billAmount', fixedCharge + units * energyRate)}")
    gb = s.conn('bl_bill', 'Generate and deliver bill', 'billing.generate', 'bill', 'submit',
                'key,consumer,units,billAmount,tariffId,cycle', 'bill',
                step='bill')
    du = clock(s, 'bl_due', 'payment', '${dueSpec}')
    w = s.egw('bl_wait', 'Paid, disputed or due date')
    tb = s.svc('bl_tellConnection', 'First bill: release a held compensation credit', C['CORR'],
               {'messageName': 'sangam.util.firstBill', 'targetBusinessKey': '${connectionCaseKey}', 'optional': 'true',
                'variables': 'consumer,cycle,head'})
    s.seq(tr, cp, gb, tb, du, w)
    pd = s.catch_msg('bl_paid', 'Payment received', 'sangam.util.paid')
    dp = s.catch_msg('bl_dispute', 'Bill disputed', 'sangam.util.billDispute')
    od = s.catch_timer('bl_overdue', 'Due date passed', date='${paymentDueAt}')
    for x in (pd, dp, od):
        s.flow(w, x)
    s.flow(pd, end_ok(c, s, 'bl_end', 'Paid'))
    rv = s.user('bl_revise', 'Examine dispute and revise bill | विवाद की जाँच एवं बिल संशोधन', '${billingSeat}',
                form='sec-util-meter-to-cash/dispute', decision=('disputeDecision', ['customer', 'seller']),
                fields=['units', 'remarks*'])
    s.flow(dp, rv)
    rg = s.xgw('bl_revGw')
    s.flow(rv, rg)
    sp = s.setv('bl_superseded', 'Amount of the bill being replaced', values='supersededAmount=@billAmount')
    rc = s.expr('bl_recompute', 'Revised bill amount', "${execution.setVariable('billAmount', fixedCharge + units * energyRate)}")
    rb = s.conn('bl_reviseBill', 'Issue revised bill', 'billing.revise', 'revise', 'submit',
                'key,consumer,units,tariffId,cycle,head,supersededAmount', 'rev', step='revise')
    s.flow(rg, sp, "disputeDecision == 'customer'", 'upheld')
    s.seq(sp, rc, rb)
    rf = s.xgw('bl_revRes', 'Revised?')
    s.flow(rf, rv, "revStatus == 'FAILED'", 'refused: re-examine')
    s.flow(rb, rf)
    s.flow(rf, du, None, 'revised', default=True)
    s.flow(rg, w, None, 'bill correct', default=True)
    dc = s.svc('bl_disconnect', 'Hand over to disconnection (notice case)', C['LINK'],
               {'processKey': 'sangam-sec-util-disconnection', 'linkType': 'non-payment', 'inherit': 'key,billAmount,applicantRef'})
    s.flow(od, dc)
    s.flow(dc, s.end('bl_endOverdue', 'Overdue: disconnection case opened', listeners=[c.item_listener('OVERDUE')]))
    pk.dmn('sec-util-tariff', 'Tariff (SYNTHETIC; SERC tariff order)', 'FIRST', [('Category', 'tariffCategory', 'string'),
                                                                                   ('Units', 'units', 'double')],
           [('fixedCharge', 'Fixed charge', 'double'), ('energyRate', 'Energy rate per unit', 'double'),
            ('head', 'Bill head (must equal the tariff line head)', 'string')],
           [([q('DOMESTIC'), '<= 100'], ['50', '3.5', q('ENERGY')], ''), ([q('DOMESTIC'), '-'], ['50', '5.5', q('ENERGY')], ''),
            (['-', '-'], ['200', '8', q('ENERGY')], 'commercial')])
    simple_seeds(pk, 'BILLING', [('BILLING-CLERK', 'BILLING', None, 'Billing clerk', 'बिलिंग लिपिक')], 'ubl')


def util_disconnection():
    pk = spack('util', 'disconnection', 'Disconnection for non-payment and reconnection', 'भुगतान न होने पर विच्छेदन एवं पुनर्संयोजन',
               'Utilities',
               'Electricity Act s.56: written notice of not less than 15 clear days before disconnecting; no '
               'disconnection while a disputed amount under appeal is deposited as provided; disconnection, then '
               'reconnection within the standard of performance after payment.',
               ['SEC-UTIL-005', 'GP-06', 'GP-46'],
               [('docs.notice', 'a', '', 'Disconnection notice'), ('field.disconnect', 'a', '', 'Disconnect'),
                ('field.reconnect', 'a', '', 'Reconnect')])
    p = pk.proc('sangam-sec-util-disconnection', 'Disconnection', '', start={'materialFields': 'key,billAmount'})
    s = p.root
    st = start_form(p, s, 'ds_start', 'Overdue bill', 'sec-util-disconnection/start', 'Overdue bill | अतिदेय बिल',
                    ['consumerNo', 'billAmount', 'applicantRef'])
    d = s.setv('ds_defaults', 'Configuration', defaults='consumerNo=@key;billAmount=0;areaType=MUNICIPAL;applicantRef=@consumerNo')
    nt = s.conn('ds_notice', 'Serve 15 clear days notice (s.56)', 'docs.notice', 's56', 'submit', 'consumerNo,billAmount', 'notice',
                step='notice')
    w = s.egw('ds_wait', 'Payment, appeal or notice period')
    s.seq(st, d, nt, w)
    pd = s.catch_msg('ds_paid', 'Paid', 'sangam.util.paid')
    ap = s.catch_msg('ds_appeal', 'Dispute / appeal with deposit', 'sangam.util.appeal')
    t15 = s.catch_timer('ds_15d', '15 clear days', duration='P16D')
    for x in (pd, ap, t15):
        s.flow(w, x)
    s.flow(pd, end_ok(p, s, 'ds_endPaid', 'Paid before disconnection'))
    s.flow(ap, s.end('ds_endAppeal', 'Held: dispute before the forum'))
    dc = s.user('ds_disconnect', 'Disconnect supply | आपूर्ति विच्छेद', 'LINEMAN', form='sec-util-disconnection/disconnect',
                decision=None, fields=['photos', 'remarks'])
    s.flow(t15, dc)
    w2 = s.catch_msg('ds_paidAfter', 'Paid with reconnection charges', 'sangam.util.paid')
    r = s.rule('ds_rule', 'Reconnection standard (DMN)', 'sec-util-reconnect')
    s.seq(dc, w2, r)
    re_, rd = s.sla_task('ds_reconnect', 'Reconnect supply | आपूर्ति पुनर्संयोजन', 'reconnectSeat', form='sec-util-disconnection/reconnect',
                         decision=None, fields=['remarks'], spec='${reconnectSpec}', on_breach='ESCALATE')
    rs = s.setv('ds_seat', 'Lineman', defaults='reconnectSeat=LINEMAN')
    s.seq(r, rs, re_)
    s.flow(rd, end_ok(p, s, 'ds_end', 'Reconnected'))
    pk.dmn('sec-util-reconnect', 'Reconnection standard (SERC SoP; verify)', 'FIRST', [('Area', 'areaType', 'string')],
           [('reconnectSpec', 'Reconnect within', 'string')],
           [([q('METRO')], [q('6H')], ''), ([q('MUNICIPAL')], [q('12H')], ''), (['-'], [q('24H')], 'rural')])
    simple_seeds(pk, 'O-AND-M', [('LINEMAN', 'LINEMAN', None, 'Lineman', 'लाइनमैन')], 'uds')


# ============================================================================================ HR
def hr_payroll():
    pk = spack('hr', 'payroll', 'Monthly payroll with PF and ESI returns', 'पीएफ एवं ईएसआई विवरणी सहित मासिक वेतन', 'HR',
               'Monthly: inputs frozen, payroll computed with PF (12 % on wages up to the ceiling) and ESI (0.75 % / '
               '3.25 % up to the wage limit) by rule, maker-checker approval, wages paid by the 7th (Code on Wages '
               's.17), PF ECR and ESI contributions filed and paid by the 15th, payslips issued.',
               ['SEC-HR-005', 'GP-01', 'GP-30', 'GP-12', 'UP-05'],
               [('hris.inputs', 'a', '', 'Attendance and inputs'), ('bank.transfer', 'c', '', 'Salary payment file'),
                ('epfo.ecr', 'b', '', 'PF ECR'), ('esic.contribution', 'b', '', 'ESI contribution'),
                ('docs.payslip', 'a', '', 'Payslips')])
    p = pk.proc('sangam-sec-hr-payroll', 'Monthly payroll', '', start={'materialFields': 'period'})
    s = p.root
    t = s.start('pr_timer', '25th of the month', timer_cycle='0 0 6 25 * ?', initiator=None)
    d = s.setv('pr_defaults', 'Configuration',
               defaults='period=CURRENT;grossWages=1000000;pfWages=600000;esiWages=200000;amount=@grossWages;caseType=PAYROLL;'
                        'routingDecisionKey=sec-hr-payroll-authority;powersDecisionKey=sec-hr-payroll-powers;'
                        'executionConnector=bank.transfer;executionStep=salary;reconcileRequired=true;'
                        'executionPayloadFields=period,grossWages;appealAllowed=false;payrollSeat=PAYROLL-MAKER')
    inp = s.conn('pr_inputs', 'Freeze attendance and inputs', 'hris.inputs', 'freeze', 'query', None, 'inputs')
    r = s.rule('pr_rates', 'PF / ESI rates (DMN)', 'sec-hr-statutory')
    cp = s.expr('pr_compute', 'Contributions',
                "${execution.setVariable('pfAmount', pfWages * (pfEmployeePct + pfEmployerPct) / 100)}")
    ce = s.expr('pr_esiCalc', 'ESI', "${execution.setVariable('esiAmount', esiWages * (esiEmployeePct + esiEmployerPct) / 100)}")
    mk = s.user('pr_make', 'Review payroll register (maker) | वेतन रजिस्टर की समीक्षा', '${payrollSeat}', form='sec-hr-payroll/make',
                decision=None, fields=['grossWages*', 'remarks'])
    s.seq(t, d, inp, r, cp, ce, mk)
    san = core(s, 'pr_approve', 'Approve payroll (core sanction)', 'sanction')
    s.flow(mk, san)
    pay = core(s, 'pr_pay', 'Pay salaries by the 7th (core execution)', 'execution')
    outcome(s, 'pr_sanGw', 'sanctionOutcome', {'SANCTIONED': pay}, mk)
    s.flow(san, 'pr_sanGw')
    fk = s.pgw('pr_fork', 'Returns by the 15th')
    jn = s.pgw('pr_join')
    s.flow(pay, fk)
    for key, conn_, en, hi in (('pf', 'epfo.ecr', 'File PF ECR and pay', 'पीएफ ईसीआर दाखिल एवं भुगतान'),
                               ('esi', 'esic.contribution', 'File ESI contribution and pay', 'ईएसआई अंशदान दाखिल एवं भुगतान')):
        te, td = s.sla_task('pr_' + key, f'{en} | {hi}', 'payrollSeat', form='sec-hr-payroll/' + key, decision=None,
                            fields=['remarks'], spec='15D', on_breach='ESCALATE')
        sx = s.conn('pr_' + key + 'File', en, conn_, 'file', 'submit', 'period,' + key + 'Amount', key + 'Ret', step=key)
        s.flow(fk, te)
        s.flow(td, sx)
        s.flow(sx, jn)
    ps = s.conn('pr_payslips', 'Issue payslips', 'docs.payslip', 'issue', 'submit', 'period', 'slips', step='payslips')
    s.seq(jn, ps, end_ok(p, s, 'pr_end', 'Payroll closed'))
    pk.dmn('sec-hr-statutory', 'PF and ESI rates (EPF Scheme / ESI Act; verify current notifications)', 'FIRST',
           [('Period', 'period', 'string')],
           [('pfEmployeePct', 'PF employee %', 'double'), ('pfEmployerPct', 'PF employer %', 'double'),
            ('esiEmployeePct', 'ESI employee %', 'double'), ('esiEmployerPct', 'ESI employer %', 'double'),
            ('pfWageCeiling', 'PF wage ceiling', 'double'), ('esiWageLimit', 'ESI wage limit', 'double')],
           [(['-'], ['12', '12', '0.75', '3.25', '15000', '21000'], 'PF ceiling Rs 15,000; ESI limit Rs 21,000 (verify)')])
    authority(pk, 'sec-hr-payroll-authority', 'Payroll approver', [('-', 'HRHEAD', '')])
    powers(pk, 'sec-hr-payroll-powers', [('HRHEAD', 10000000000, '')])
    simple_seeds(pk, 'PAYROLL', [('PAYROLL-MAKER', 'PAYROLL', 'HR-HEAD', 'Payroll executive', 'वेतन कार्यकारी'),
                                 ('HR-HEAD', 'HRHEAD', None, 'Head of HR', 'मानव संसाधन प्रमुख')], 'hpr')


def hr_appraisal():
    pk = spack('hr', 'appraisal', 'Annual performance appraisal', 'वार्षिक कार्य-निष्पादन मूल्यांकन', 'HR',
               'Annual cycle with one case per employee (GP-09): goals, self-appraisal, manager rating, reviewer '
               'with SoD, calibration committee, communication, employee appeal to HR within the window.',
               ['SEC-HR-006', 'GP-02', 'GP-03', 'GP-09', 'GP-22'],
               [('hris.employees', 'a', '', 'Employees in scope'), ('hris.rating', 'a', '', 'Record rating')])
    p = pk.proc('sangam-sec-hr-appraisal-cycle', 'Appraisal cycle', '', start={'materialFields': 'period'})
    s, cg = campaign(pk, p, 'ac', '0 0 6 1 4 ?', 'hris.employees', 'sangam-sec-hr-appraisal', chunk=200,
                     defaults='committeeSeats=CALIB-1,CALIB-2,CALIB-3;quorumRule=MAJORITY')
    cal = core(s, 'ac_calibrate', 'Calibration committee (core committee)', 'committee')
    s.flow(cg, cal, None, 'all rated', default=True)
    s.flow(cal, s.end('ac_end', 'Cycle closed'))
    c = pk.proc('sangam-sec-hr-appraisal', 'Appraisal of one employee', '', start={'materialFields': 'key'})
    s = c.root
    a = s.start('ap_start', 'Employee in scope', initiator=None)
    d = s.setv('ap_defaults', 'Configuration', defaults='employeeGroup=EMPLOYEE;managerSeat=LINE-MANAGER;reviewerSeat=REVIEWER')
    se, sd = s.sla_task('ap_self', 'Self-appraisal | स्व-मूल्यांकन', 'employeeGroup', form='sec-hr-appraisal/self', decision=None,
                        fields=['achievements*'], spec='10WD')
    me, md = s.sla_task('ap_manager', 'Manager rating | प्रबंधक रेटिंग', 'managerSeat', form='sec-hr-appraisal/manager',
                        decision=None, fields=['rating*', 'remarks'], spec='10WD')
    re_, rd = s.sla_task('ap_review', 'Reviewer | समीक्षक', 'reviewerSeat', form='sec-hr-appraisal/review',
                         decision=('reviewDecision', ['accept', 'return']), fields=['rating', 'remarks'], spec='5WD', sod=True)
    s.seq(a, d, se)
    s.seq(sd, me)
    s.flow(md, re_)
    g = s.xgw('ap_gw')
    s.flow(rd, g)
    s.flow(g, me, "reviewDecision == 'return'", 'return')
    rc = s.conn('ap_record', 'Record rating and communicate', 'hris.rating', 'record', 'submit', 'key,rating', 'rate', step='rating')
    s.flow(g, rc, None, 'accept', default=True)
    w = s.egw('ap_appealWait', 'Appeal window')
    s.flow(rc, w)
    am = s.catch_msg('ap_appeal', 'Employee appeals', 'sangam.appealFiled')
    at = s.catch_timer('ap_lapse', 'Window over', duration='P15D')
    s.flow(w, am)
    s.flow(w, at)
    ah = s.user('ap_hr', 'HR decides the appeal | मानव संसाधन द्वारा अपील निर्णय', 'HR-BP', form='sec-hr-appraisal/appeal',
                decision=('appealDecision', ['allow', 'dismiss']), fields=['rating', 'remarks*'], sod=True)
    s.flow(am, ah)
    e = end_ok(c, s, 'ap_end', 'Appraised')
    s.flow(ah, e)
    s.flow(at, e)
    simple_seeds(pk, 'PMS', [('LINE-MANAGER', 'MANAGER', 'REVIEWER', 'Line manager', 'प्रबंधक'),
                             ('REVIEWER', 'REVIEWER', 'HR-BP', 'Reviewer', 'समीक्षक'),
                             ('HR-BP', 'HRBP', None, 'HR business partner', 'मानव संसाधन व्यवसाय साझेदार'),
                             ('CALIB-1', 'COMMITTEE', None, 'Calibration member 1', 'अंशांकन सदस्य 1'),
                             ('CALIB-2', 'COMMITTEE', None, 'Calibration member 2', 'अंशांकन सदस्य 2'),
                             ('CALIB-3', 'COMMITTEE', None, 'Calibration member 3', 'अंशांकन सदस्य 3')], 'hap')


# ============================================================================================ legal
def legal_litigation():
    pk = spack('legal', 'litigation', 'Private litigation and legal notices', 'निजी मुकदमे एवं विधिक नोटिस', 'Legal / privacy',
               'Legal notice or suit received: limitation and reply period by rule, assessment, counsel engaged '
               'with fee approval, reply / written statement within the period (CPC: 30 days, extendable to 90), '
               'hearings tracked from the court portal, judgment, appeal decision within the appeal period, '
               'compliance; or outward suit before limitation expires.',
               ['SEC-LEGAL-004', 'GP-21', 'GP-22', 'GP-35', 'GP-51'] + SIDE,
               [('court.ecourts', 'b', '', 'e-Courts case status'), ('docs.reply', 'a', '', 'Reply / pleading')])
    p = pk.proc('sangam-sec-legal-litigation', 'Litigation matter', '', start={'materialFields': 'matterType,court,amount'})
    s = p.root
    st = start_form(p, s, 'lt_start', 'Notice / summons received or claim to file', 'sec-legal-litigation/intake',
                    'Legal matter intake | विधिक मामला प्रविष्टि', ['matterType*', 'counterpartyName*', 'court', 'amount', 'documents'])
    d = s.setv('lt_defaults', 'Configuration', defaults='matterType=SUMMONS;counterpartyName=X;court=CIVIL;counselSeat=LEGAL-COUNSEL;'
               'appealRecommended=false')
    r = s.rule('lt_rule', 'Response and appeal periods (DMN)', 'sec-legal-periods')
    ck = clock(s, 'lt_clock', 'response', '${responseSpec}')
    ae, ad = s.sla_task('lt_assess', 'Assess, engage counsel and approve fee | आकलन, अधिवक्ता नियुक्ति एवं शुल्क अनुमोदन', 'counselSeat',
                        form='sec-legal-litigation/assess', decision=('legalDecision', ['contest', 'settle', 'file']),
                        fields=['counselName*', 'remarks*'], spec='5WD')
    s.seq(st, d, r, ck, ae)
    g = s.xgw('lt_gw')
    s.flow(ad, g)
    se = s.user('lt_settle', 'Negotiate settlement | समझौता वार्ता', '${counselSeat}', form='sec-legal-litigation/settle',
                decision=('settleDecision', ['settled', 'failed']), fields=['remarks*'])
    s.flow(g, se, "legalDecision == 'settle'", 'settle')
    sg = s.xgw('lt_setGw')
    s.flow(se, sg)
    s.flow(sg, end_ok(p, s, 'lt_endSettled', 'Settled'), "settleDecision == 'settled'", 'settled')
    rp = s.user('lt_reply', 'Reply / written statement / plaint before the deadline | समय-सीमा से पूर्व उत्तर / लिखित कथन / वाद-पत्र',
                '${counselSeat}', form='sec-legal-litigation/reply', decision=None, fields=['replyText*', 'documents'],
                due='${responseDueAt}')
    s.flow(g, rp, None, 'contest / file', default=True)
    s.flow(sg, rp, None, 'failed', default=True)
    lb = s.boundary('lt_lateWarn', rp, 'Deadline reached', timer_date='${responseDueAt}', cancel=False)
    s.flow(lb, s.notify('lt_lateNotify', 'Deadline reached: escalate', 'legal-deadline', 'email,inapp', 'counselSeat'))
    s.flow('lt_lateNotify', s.end('lt_lateEnd', 'Escalated'))
    fl = s.conn('lt_file', 'File / serve', 'docs.reply', 'file', 'submit', 'matterType,court', 'filing', step='filing')
    s.flow(rp, fl)
    w = s.egw('lt_wait', 'Next hearing or judgment')
    s.flow(fl, w)
    hr = s.catch_msg('lt_hearing', 'Hearing date from the court', 'sangam.hearingDateSet')
    jd = s.catch_msg('lt_judgment', 'Judgment / final order', 'sangam.court.judgment')
    s.flow(w, hr)
    s.flow(w, jd)
    at = s.user('lt_attend', 'Attend hearing and record the order | सुनवाई में उपस्थिति एवं आदेश दर्ज करें', '${counselSeat}',
                form='sec-legal-litigation/hearing', decision=None, fields=['nextHearingDate', 'remarks'])
    s.seq(hr, at, w)
    ac = clock(s, 'lt_appealClock', 'appeal', '${appealSpec}')
    dj = s.user('lt_decide', 'Comply or appeal (within the appeal period) | अनुपालन या अपील (अपील अवधि में)', 'LEGAL-HEAD',
                form='sec-legal-litigation/decide', decision=('judgmentDecision', ['comply', 'appeal']),
                fields=['judgment*', 'appealRecommended'], due='${appealDueAt}', sod=True)
    s.seq(jd, ac, dj)
    jg = s.xgw('lt_jGw')
    s.flow(dj, jg)
    lk = s.svc('lt_appealCase', 'Open the appeal as a linked matter', C['LINK'],
               {'processKey': 'sangam-sec-legal-litigation', 'linkType': 'appeal', 'inherit': 'counterpartyName,amount'})
    s.flow(jg, lk, "judgmentDecision == 'appeal'", 'appeal')
    s.flow(lk, s.end('lt_endAppealed', 'Appeal filed'))
    cp = s.user('lt_comply', 'Comply with the order | आदेश का अनुपालन', '${counselSeat}', form='sec-legal-litigation/comply',
                decision=None, fields=['actionTaken*'])
    s.flow(jg, cp, None, 'comply', default=True)
    s.flow(cp, end_ok(p, s, 'lt_end', 'Closed'))
    s.side_exits('lt')
    pk.dmn('sec-legal-periods', 'Response and appeal periods (CPC, Limitation Act; verify per forum)', 'FIRST',
           [('Matter', 'matterType', 'string')], [('responseSpec', 'Respond within', 'string'), ('appealSpec', 'Appeal within', 'string')],
           [([q('LEGAL_NOTICE')], [q('15D'), q('90D')], 'notice period as stated (SYNTHETIC 15 days)'),
            ([q('SUMMONS')], [q('30D'), q('90D')], 'written statement 30 days (CPC O.VIII r.1); first appeal 90 days to the HC'),
            ([q('COMMERCIAL')], [q('30D'), q('60D')], 'Commercial Courts Act s.13: 60 days'),
            ([q('CONSUMER')], [q('30D'), q('45D')], 'CPA 2019: version 30 (+15) days; appeal 45 days'),
            (['-'], [q('30D'), q('90D')], 'outward suit: file before limitation (3 years for contracts)')])
    simple_seeds(pk, 'LEGAL', [('LEGAL-COUNSEL', 'COUNSEL', 'LEGAL-HEAD', 'In-house counsel', 'आंतरिक अधिवक्ता'),
                               ('LEGAL-HEAD', 'GC', None, 'General counsel', 'प्रधान विधि अधिकारी')], 'llt')


def build():
    for f in (bank_dea, nbfc_dlg, cap_surveillance, cap_corporate_action, cap_ipo, ins_death_claim, ins_reinsurance,
              ins_intermediary, health_ot, health_blood_bank, ecom_gst_tcs, tel_tcccpr, tel_sim_swap, scm_eway, mfg_mrp,
              edu_exam, edu_scholarship, util_disconnection, util_billing, hr_payroll, hr_appraisal, legal_litigation):
        f()
