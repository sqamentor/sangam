# SPDX-License-Identifier: Apache-2.0
"""Works / WAMS project lifecycle and GeM purchase (split from gov2 to keep each generator file under 800 lines; main.py
builds this module right after gov2, so the pack order and every generated file are unchanged)."""
from lib import C, q
import rounding
from packs import Pack
from common import core, outcome, end_ok, rejection, reject_with, withdraw, checked_query, check_conditions
from procurement import PROFILE_CONNECTOR, procurement_mode  # decision 225 (IFC-189)
from gov2 import SIDE, simple_seeds


# ------------------------------------------------------------------------------------------------ works / WAMS
def works():
    pk = Pack('gov.works', 'works', 'Works / WAMS project lifecycle', 'निर्माण कार्य / डब्ल्यूएएमएस परियोजना जीवनचक्र',
              'Works / WAMS',
              'Estimate scrutiny, technical sanction then administrative approval with budget encumbrance (chained '
              'sanctions; the order is per tenant / department configuration, works-config), tender, work order, monthly measurement-book cycle with check measurement and running '
              'bills paid through P1, cumulative-threshold revised AA, amendments (variation, EoT, LD, price '
              'escalation) applied to the running case, final bill, completion, defect liability period and '
              'release of the performance security (D-WORKS).',
              ['D-WORKS', 'GP-05', 'GP-10', 'GP-11', 'GP-37', 'GP-41', 'GP-42', 'GP-43', 'GP-53', 'GP-09'] + SIDE,
              connectors=[('ifms.encumber', 'a', 'G-PFMS', 'Encumbrance against the AA'),
                          ('ifms.release', 'a', 'G-PFMS', 'Release on cancel')],
              depends=['sangam.core', 'gov.tender', 'gov.work-order', 'gov.p1.bill-payment', 'gov.contract-management',
                       'gov.security-instrument'])
    p = pk.proc('sangam-works-project', 'Works project', 'Order of sanctions from the pack configuration '
                'works-config (per tenant by override deployment, per office by rule row): TS_FIRST (default: estimate, '
                'technical sanction, administrative approval) or AA_FIRST.',
                start={'materialFields': 'workName,estimateAmount,budgetHead,amount,payeeAccount,currency,purpose'})
    s = p.root
    st = s.start('wk_start', 'Estimate submitted', form='works/estimate')
    p.forms.append(dict(key='works/estimate', name='Estimate | प्राक्कलन', decision=None,
                        fields=['workName*', 'estimateAmount*', 'budgetHead*', 'district', 'documents'], doc=None))
    d = s.setv('wk_defaults', 'Configuration', defaults=(
        'caseType=WORKS_TS;amount=@estimateAmount;scrutinySeat=DA-WK;cumulativeAmount=0;mbCycle=P30D;'
        'dlpPeriod=P365D;aaTolerancePct=10;raBillNo=0;ldRecoverable=0;finalBill=false;thresholdCrossed=false;'
        'aeSeat=AE-WK;eeSeat=EE-WK;releaseConnector=ifms.release;appealAllowed=false;contractorAccount=;'
        'contractorIfsc=;completionDueAt=null;delayReviewDays=30;officeId=DIV-WK'))
    scr = core(s, 'wk_estimate', 'Estimate scrutiny (core)', 'scrutiny')
    s.seq(st, d, scr)
    rej = rejection(p, s, 'wk_rj')
    cfg = s.rule('wk_config', 'Order of sanctions (pack configuration, DMN)', 'works-config', binding='latest')
    outcome(s, 'wk_scrGw', 'scrutinyOutcome', {'ACCEPTED': cfg}, rej)
    s.flow(scr, 'wk_scrGw')
    ts_cfg = s.setv('wk_tsCfg', 'Technical sanction', values='caseType=WORKS_TS;routingDecisionKey=works-ts-authority')
    aa1_cfg = s.setv('wk_aaFirstCfg', 'Administrative approval (first)', values='caseType=WORKS_AA;'
                     'routingDecisionKey=works-aa-authority;encumbranceLedger=')
    outcome(s, 'wk_orderGw', 'worksSanctionOrder', {'AA_FIRST': aa1_cfg}, ts_cfg, label='Order of sanctions?')
    s.flow(cfg, 'wk_orderGw')
    enc = s.conn('wk_encumber', 'Encumber against AA', 'ifms.encumber', 'encumber', 'submit', 'budgetHead,estimateAmount',
                 'encumber', step='encumber')
    # TS_FIRST (default): estimate > technical sanction > administrative approval
    ts = core(s, 'wk_ts', 'Technical sanction (core)', 'sanction')
    s.flow(ts_cfg, ts)
    aa_cfg = s.setv('wk_aaCfg', 'Administrative approval', values='tsAmount=@estimateAmount;caseType=WORKS_AA;'
                    'routingDecisionKey=works-aa-authority;encumbranceLedger=')
    outcome(s, 'wk_tsGw', 'sanctionOutcome', {'SANCTIONED': aa_cfg, 'RETURNED': scr}, rej)
    s.flow(ts, 'wk_tsGw')
    aa = core(s, 'wk_aa', 'Administrative approval and expenditure sanction (core)', 'sanction')
    s.flow(aa_cfg, aa)
    ref1 = s.setv('wk_aaRef', 'AA reference', values='aaRef=@approvedPayloadHash')
    outcome(s, 'wk_aaGw', 'sanctionOutcome', {'SANCTIONED': ref1, 'RETURNED': scr}, rej)
    s.flow(aa, 'wk_aaGw')
    s.flow(ref1, enc)
    # AA_FIRST: estimate > administrative approval > technical sanction
    aa1 = core(s, 'wk_aaFirst', 'Administrative approval and expenditure sanction (core)', 'sanction')
    s.flow(aa1_cfg, aa1)
    ref2 = s.setv('wk_aaFirstRef', 'AA reference', values='aaRef=@approvedPayloadHash')
    outcome(s, 'wk_aaFirstGw', 'sanctionOutcome', {'SANCTIONED': ref2, 'RETURNED': scr}, rej)
    s.flow(aa1, 'wk_aaFirstGw')
    ts2_cfg = s.setv('wk_tsSecondCfg', 'Technical sanction (after AA)', values='caseType=WORKS_TS;'
                     'routingDecisionKey=works-ts-authority')
    s.flow(ref2, ts2_cfg)
    ts2 = core(s, 'wk_tsSecond', 'Technical sanction (core)', 'sanction')
    s.flow(ts2_cfg, ts2)
    ts2v = s.setv('wk_tsSecondAmount', 'TS amount', values='tsAmount=@estimateAmount')
    outcome(s, 'wk_tsSecondGw', 'sanctionOutcome', {'SANCTIONED': ts2v, 'RETURNED': scr}, rej)
    s.flow(ts2, 'wk_tsSecondGw')
    s.flow(ts2v, enc)
    aav = s.setv('wk_aaAmount', 'AA amount', values='aaAmount=@estimateAmount')
    nit = s.user('wk_nit', 'Prepare NIT | निविदा सूचना तैयार करें', '${eeSeat}', form='works/nit', decision=None,
                 fields=['tenderId*', 'nitText*', 'bidDueAt*', 'openingAt*'])
    tender = s.call('wk_tender', 'Tender (gov.tender)', 'sangam-tender', bk='#{execution.processBusinessKey}/TENDER',
                    all_in=False, outs_all=False,
                    ins={'workName': 'itemDescription', 'estimateAmount': 'estimateAmount', 'initiatorSeat': 'actingSeat',
                         'initiatorPerson': 'initiator', 'officeId': 'officeId', 'tenderId': 'tenderId',
                         'nitText': 'nitText', 'bidDueAt': 'bidDueAt', 'openingAt': 'openingAt'},
                    outs={'tenderOutcome': 'tenderOutcome', 'contractorId': 'contractorId',
                          'contractValue': 'contractValue'})
    s.seq(enc, aav, nit, tender)
    wo = s.call('wk_workOrder', 'Work order (gov.work-order)', 'sangam-work-order', bk='#{execution.processBusinessKey}/WO',
                all_in=False, outs_all=False,
                ins={'workName': 'workName', 'contractorId': 'contractorId', 'contractValue': 'contractValue',
                     'completionDueAt': 'completionDueAt', 'initiatorSeat': 'actingSeat', 'initiatorPerson': 'initiator'},
                outs={'workOrderOutcome': 'workOrderOutcome'})
    tf = s.user('wk_tenderFailed', 'Tender failed: re-tender or drop | निविदा विफल: पुनः निविदा या छोड़ें', '${eeSeat}',
                form='works/tender-failed', decision=('tfDecision', ['retender', 'drop']), fields=['remarks*'])
    dates = s.user('wk_contractDates', 'Contract dates and payee details | अनुबंध तिथियाँ एवं आदाता विवरण', '${eeSeat}',
                   form='works/contract-dates', decision=None,
                   fields=['completionDueAt*', 'contractorAccount*', 'contractorIfsc*'])
    s.flow(dates, wo)
    outcome(s, 'wk_tenderGw', 'tenderOutcome', {'AWARDED': dates}, tf)
    s.flow(tender, 'wk_tenderGw')
    tfg = s.xgw('wk_tfGw')
    s.flow(tf, tfg)
    s.flow(tfg, nit, "tfDecision == 'retender'", 'retender')
    s.flow(tfg, withdraw(p, s, 'wk_drop'), None, 'drop', default=True)
    cyc = s.egw('wk_cycle', 'Monthly cycle, completion date')
    outcome(s, 'wk_woGw', 'workOrderOutcome', {'ISSUED': cyc}, tf)
    s.flow(wo, 'wk_woGw')
    tick = s.catch_timer('wk_month', 'Measurement period', duration='${mbCycle}')
    due = s.catch_timer('wk_completionDue', 'Stipulated completion date', date='${completionDueAt}')
    s.flow(cyc, tick)
    s.flow(cyc, due)
    ld = s.user('wk_delay', 'Completion overdue: assess delay (monthly) | पूर्णता विलंबित: विलंब का आकलन (मासिक)', '${eeSeat}',
                form='works/delay', decision=('delayDecision', ['proceed']), fields=['delayDays*', 'remarks'])
    ldr = s.svc('wk_requestLd', 'LD / EoT amendment case', C['LINK'],
                {'processKey': 'sangam-contract-amendment', 'linkType': 'amendment',
                 'inherit': 'contractValue,contractorId,delayDays,initiatorSeat,initiatorPerson,actingSeat,initiator'})
    rearm = s.svc('wk_rearm', 'Next delay review in 30 days', C['SLA'],
                  {'op': 'SHIFT', 'dateVar': 'completionDueAt', 'daysVar': 'delayReviewDays'})
    s.seq(due, ld, ldr, rearm, cyc)
    mb = s.user('wk_mb', 'Record measurements in MB | माप पुस्तिका में माप दर्ज करें', '${aeSeat}', form='works/measurement',
                decision=None, fields=['mbNo*', 'measuredValue*', 'finalBill', 'physicalReceived'])
    cm_e, cm_d = s.sla_task('wk_checkMeasure', 'Check-measure | जाँच-माप', 'eeSeat', form='works/check-measure',
                            decision=('cmDecision', ['accept', 'return']), fields=['checkMeasured*', 'remarks'],
                            default_spec='5WD', sod=True)
    s.seq(tick, mb, cm_e)
    cg = s.xgw('wk_cmGw', 'Accepted?')
    s.flow(cm_d, cg)
    s.flow(cg, mb, "cmDecision == 'return'", 'return to AE')
    cum = s.svc('wk_cumulative', 'Cumulative against AA (GP-42)', C['CUM'],
                {'totalVar': 'cumulativeAmount', 'add': '${measuredValue}', 'limit': '${aaAmount}',
                 'tolerancePct': '${aaTolerancePct}'})
    s.flow(cg, cum, None, 'accepted', default=True)
    tg = s.xgw('wk_thrGw', 'Over AA + tolerance?')
    s.flow(cum, tg)
    raa_cfg = s.setv('wk_raaCfg', 'Revised AA', values='caseType=WORKS_RAA;routingDecisionKey=works-aa-authority;'
                     'amount=@cumulativeAmount')
    raa = core(s, 'wk_raa', 'Revised administrative approval (core)', 'sanction')
    raa_ok = s.setv('wk_raaOk', 'New AA amount', values='aaAmount=@cumulativeAmount;thresholdCrossed=false')
    s.flow(tg, raa_cfg, 'thresholdCrossed', 'GP-42')
    s.seq(raa_cfg, raa, raa_ok)
    nb = s.expr('wk_billNo', 'Next RA bill number', "${execution.setVariable('raBillNo', raBillNo + 1)}")
    s.flow(tg, nb, None, 'within', default=True)
    s.flow(raa_ok, nb)
    net = s.expr('wk_net', 'Net of LD recoverable', "${execution.setVariable('raBillAmount', measuredValue - ldRecoverable)}")
    bill = s.call('wk_raBill', 'Running account bill (P1)', 'sangam-p1-bill-payment',
                  bk='#{execution.processBusinessKey}/RA-#{raBillNo}', all_in=False, outs_all=False,
                  ins={'contractorId': 'payee', 'contractorAccount': 'account', 'contractorIfsc': 'ifsc',
                       'raBillAmount': 'amount', 'budgetHead': 'budgetHead', 'aaRef': 'sanctionRef', 'raBillNo': 'invoiceNo',
                       'officeId': 'officeId', 'initiatorSeat': 'actingSeat', 'initiatorPerson': 'initiator',
                       "${'RA_BILL'}": 'caseType'},
                  outs={'caseState': 'raBillState'})
    ldz = s.setv('wk_ldCleared', 'LD recovered', values='ldRecoverable=0')
    s.seq(nb, net, bill, ldz)
    fg = s.xgw('wk_finalGw', 'Final bill?')
    s.flow(ldz, fg)
    s.flow(fg, cyc, '!finalBill', 'next month')
    cc = s.user('wk_completion', 'Completion certificate | पूर्णता प्रमाण पत्र', '${eeSeat}', form='works/completion',
                decision=None, fields=['completionDate*', 'remarks'], sod=True)
    s.flow(fg, cc, None, 'final', default=True)
    dlp = s.catch_timer('wk_dlp', 'Defect liability period', duration='${dlpPeriod}')
    dc = s.user('wk_dlpCheck', 'Defects during DLP? | दोष दायित्व अवधि में दोष?', '${eeSeat}', form='works/dlp',
                decision=('dlpDecision', ['satisfactory', 'unsatisfactory']), fields=['defectsFound', 'remarks'])
    s.seq(cc, dlp, dc)
    dg = s.xgw('wk_dlpGw')
    s.flow(dc, dg)
    rel = s.svc('wk_releaseSecurity', 'Release performance security', C['CORR'],
                {'messageName': 'sangam.contractCompleted', 'optional': 'true',
                 'targetBusinessKey': '${execution.processBusinessKey}/WO/performance-security-1'})
    inv = s.svc('wk_invokeSecurity', 'Invoke security for defects', C['CORR'],
                {'messageName': 'sangam.contractDefault', 'optional': 'true',
                 'targetBusinessKey': '${execution.processBusinessKey}/WO/performance-security-1'})
    s.flow(dg, rel, "dlpDecision == 'satisfactory'", 'no defects')
    s.flow(dg, inv, None, 'defects not rectified', default=True)
    cl = core(s, 'wk_close', 'Completion and closure (core)', 'closure')
    s.flow(rel, cl)
    s.flow(inv, cl)
    s.flow(cl, end_ok(p, s, 'wk_end', 'Work completed'))
    s.side_exits('wk')
    am = s.evsub('wk_amendmentReq', 'Request an amendment')
    a1 = am.start('wk_amReqStart', 'Amendment requested', msg='sangam.requestAmendment', interrupting=False)
    a2 = am.svc('wk_amLink', 'Amendment case (linked)', C['LINK'],
                {'processKey': 'sangam-contract-amendment', 'linkType': 'amendment',
                 'inherit': 'contractValue,contractorId,amendmentType,amendmentAmount,extensionDays,delayDays,'
                            'escalationIndex,justification,initiatorSeat,initiatorPerson,actingSeat,initiator'})
    am.seq(a1, a2, am.end('wk_amReqEnd', 'Requested'))
    ap = s.evsub('wk_amendmentApplied', 'Amendment approved: apply to the running contract')
    b1 = ap.start('wk_amApStart', 'Amendment approved', msg='sangam.amendmentApproved', interrupting=False)
    bg = ap.xgw('wk_amTypeGw', 'Type?')
    ap.flow(b1, bg)
    eot = ap.svc('wk_eot', 'Extend completion date (re-arms the timer)', C['SLA'],
                 {'op': 'SHIFT', 'dateVar': 'completionDueAt', 'daysVar': 'extensionDays'})
    ldv = ap.setv('wk_ldApply', 'LD recoverable from next bill', values='ldRecoverable=@ldAmount')
    var = ap.svc('wk_variation', 'Add to cumulative (variation / escalation)', C['CUM'],
                 {'totalVar': 'cumulativeAmount', 'add': '${amendmentAmount + escalationAmount}', 'limit': '${aaAmount}',
                  'tolerancePct': '${aaTolerancePct}'})
    ap.flow(bg, eot, "amendmentType == 'EOT'", 'EoT')
    ap.flow(bg, ldv, "amendmentType == 'LD'", 'LD')
    ap.flow(bg, var, None, 'variation / extra item / escalation', default=True)
    ae = ap.end('wk_amApEnd', 'Applied')
    for x in (eot, ldv, var):
        ap.flow(x, ae)
    pk.dmn('works-config', 'Works pack configuration: order of sanctions', 'FIRST',
           [('Office / department', 'officeId', 'string')],
           [('worksSanctionOrder', 'Order of sanctions', 'string')],
           [([q('DIV-WK-AAFIRST')], [q('AA_FIRST')], 'example: one department / division that grants AA before TS'),
            (['-'], [q('TS_FIRST')], 'default: estimate > technical sanction > administrative approval')],
           doc='Per department: add a row for its office. Per tenant: deploy overrides/<tenant>/works-config.dmn '
               '(PackDeployer with that tenant id); the tenant table wins (GP-37).')
    pk.override('state-b', 'works-config', 'Works pack configuration (State B: AA before TS)', 'FIRST',
                [('Office / department', 'officeId', 'string')],
                [('worksSanctionOrder', 'Order of sanctions', 'string')],
                [(['-'], [q('AA_FIRST')], 'State B grants administrative approval before technical sanction')])
    pk.dmn('works-ts-authority', 'Technical sanction powers (synthetic)', 'FIRST', [('Amount', 'amount', 'money')],
           [('competentRole', 'Competent role', 'string')],
           [(['<= 2500000'], [q('EE')], ''), (['<= 20000000'], [q('SE')], ''), (['-'], [q('CE')], '')])
    pk.dmn('works-aa-authority', 'Administrative approval powers (synthetic)', 'FIRST',
           [('Case type', 'caseType', 'string'), ('Amount', 'amount', 'money')],
           [('competentRole', 'Competent role', 'string')],
           [(['-', '<= 10000000'], [q('HOD')], ''), (['-', '<= 500000000'], [q('SECRETARY')], ''),
            (['-', '-'], [q('FIN-SECY')], 'with Finance')])
    simple_seeds(pk, 'DIV-WK', [('DA-WK', 'DA', 'AE-WK', 'Divisional accountant', 'मंडलीय लेखाकार'),
                                ('AE-WK', 'AE', 'EE-WK', 'Assistant Engineer', 'सहायक अभियंता'),
                                ('EE-WK', 'EE', 'SE-WK', 'Executive Engineer', 'अधिशासी अभियंता'),
                                ('SE-WK', 'SE', 'CE-WK', 'Superintending Engineer', 'अधीक्षण अभियंता'),
                                ('CE-WK', 'CE', 'HOD-WK', 'Chief Engineer', 'मुख्य अभियंता'),
                                ('HOD-WK', 'HOD', 'SECY-WK', 'Engineer-in-Chief (HoD)', 'प्रमुख अभियंता (विभागाध्यक्ष)'),
                                ('SECY-WK', 'SECRETARY', None, 'Secretary, PWD', 'सचिव, लोक निर्माण विभाग')], 'wk')


# ------------------------------------------------------------------------------------------------ GeM purchase
def gem():
    pk = Pack('gov.gem-purchase', 'gem-purchase', 'Procurement of goods (GeM-style)', 'वस्तुओं का प्रापण (जेम-शैली)',
              'Procurement', 'Indent, mode of procurement by rule (direct purchase / L1 / bid / outside GeM), budget '
              'encumbrance, auto-approval below threshold or sanction by band, GeM order with operationId, delivery '
              'window, inspection and CRAC, rejection and return of goods, bill through P1 within the payment SLA.',
              ['GP-29', 'GP-01', 'GP-11', 'GP-12', 'GP-06', 'UP-14', 'R62'] + SIDE,
              connectors=[('gem.order', 'c', '', 'GeM order / contract'),
                          ('gem.rejectGoods', 'a', '', 'Rejection of goods on GeM'),
                          ('ifms.budget', 'a', 'G-PFMS', 'Budget'), ('ifms.encumber', 'a', 'G-PFMS', 'Encumbrance'),
                          ('ifms.release', 'a', 'G-PFMS', 'Release'), PROFILE_CONNECTOR],
              depends=['sangam.core', 'gov.tender', 'gov.p1.bill-payment'])
    p = pk.proc('sangam-gem-purchase', 'Purchase of goods', '',
                start={'materialFields': 'itemDescription,quantity,unitPrice,budgetHead,amount,payeeAccount,currency,purpose'})
    s = p.root
    st = s.start('gm_start', 'Indent raised', form='gem-purchase/indent')
    p.forms.append(dict(key='gem-purchase/indent', name='Indent | मांग पत्र', decision=None,
                        fields=['itemDescription*', 'quantity*', 'unitPrice*', 'gemAvailable*', 'budgetHead*',
                                'justification', 'documents'], doc=None))
    d = s.setv('gm_defaults', 'Configuration', defaults=(
        'caseType=PURCHASE;routingDecisionKey=gem-purchase-authority;deliveryPeriod=P30D;consigneeSeat=STORE-GM;'
        'executionConnector=gem.order;executionStep=gem-order;executionOperation=placeOrder;reconcileRequired=false;'
        'releaseConnector=ifms.release;appealAllowed=false;supplierAccount=;supplierIfsc=;'
        'supplierName=GeM seller'))
    amt = s.money_calc('gm_amount', 'Value (decision 139 policy)', 'amount', 'quantity * unitPrice',
                       rounding.declare(pk, 'gem-amount-rounding'))
    # decision 225: the value band comes from the profile's ifms-procurement-mode (department class from the bound
    # profile, fail closed); gem-procurement-mode keeps only the GeM facts: availability on GeM and auto-approval (GP-29)
    dept, pmode = procurement_mode(p, s, 'gm')
    mode = s.rule('gm_mode', 'GeM route and auto-approval (DMN)', 'gem-procurement-mode')
    # SL-115: the budget check fails closed (cleared before the query; no default stands in)
    be, bq = checked_query(s, 'gm_budget', 'Budget available', 'ifms.budget', 'available', 'budgetHead,amount',
                           'budget', ['budgetAvailable'])
    bg = s.xgw('gm_budgetGw', 'Budget?')
    s.seq(st, d, amt, dept)
    s.seq(pmode, mode, be)
    s.flow(bq, bg)
    enc = s.conn('gm_encumber', 'Encumber', 'ifms.encumber', 'encumber', 'submit', 'budgetHead,amount', 'encumber',
                 step='encumber')
    unconfirmed, enough = check_conditions([('budget', ['budgetAvailable'])], 'budgetAvailable >= amount')
    s.flow(bg, reject_with(p, s, 'gm_rjFunds', 'FUNDS_NOT_CONFIRMED'), unconfirmed, 'budget not confirmed')
    s.flow(bg, enc, enough, 'yes')
    s.flow(bg, reject_with(p, s, 'gm_rjBudget', 'BUDGET_NOT_AVAILABLE'), None, 'no', default=True)
    ag = s.xgw('gm_autoGw', 'Auto-approve (GP-29)?')
    s.flow(enc, ag)
    auto = s.svc('gm_autoApprove', 'Auto-approval below threshold', C['CASE'], {'op': 'BIND_DEEMED'})
    san = core(s, 'gm_sanction', 'Purchase sanction (core)', 'sanction')
    s.flow(ag, auto, 'autoApprove', 'yes')
    s.flow(ag, san, None, 'no', default=True)
    rej = rejection(p, s, 'gm_rj')
    mg = s.xgw('gm_modeGw', 'Mode?')
    s.flow(auto, mg)
    outcome(s, 'gm_sanGw', 'sanctionOutcome', {'SANCTIONED': mg}, rej)
    s.flow(san, 'gm_sanGw')
    tdr = s.call('gm_bid', 'Bid / outside-GeM tender (gov.tender)', 'sangam-tender', bk='#{execution.processBusinessKey}/BID',
                 all_in=False, outs_all=False,
                 ins={'itemDescription': 'itemDescription', 'amount': 'estimateAmount', 'initiatorSeat': 'actingSeat',
                      'initiatorPerson': 'initiator', 'officeId': 'officeId', 'tenderId': 'tenderId', 'nitText': 'nitText',
                      'bidDueAt': 'bidDueAt', 'openingAt': 'openingAt'},
                 outs={'tenderOutcome': 'tenderOutcome', 'contractorId': 'supplierName'})
    order = core(s, 'gm_order', 'GeM order (core execution)', 'execution')
    bidDoc = s.user('gm_bidDoc', 'Prepare bid document | बोली दस्तावेज़ तैयार करें', 'SO-GM', form='gem-purchase/bid-document',
                    decision=None, fields=['tenderId*', 'nitText*', 'bidDueAt*', 'openingAt*'])
    s.flow(mg, bidDoc, "gemRoute == 'BID' || gemRoute == 'OUTSIDE_GEM'", 'bid')
    s.flow(bidDoc, tdr)
    s.flow(mg, order, None, 'direct / L1', default=True)
    tg = s.xgw('gm_tenderGw')
    s.flow(tdr, tg)
    s.flow(tg, order, "tenderOutcome == 'AWARDED'", 'awarded')
    s.flow(tg, reject_with(p, s, 'gm_rjTender', 'TENDER_NOT_AWARDED'), None, 'failed', default=True)   # SL-55
    dlw = s.svc('gm_deliveryDue', 'Delivery period', C['SLA'], {'op': 'DEADLINE', 'clock': 'delivery',
                                                               'spec': '${deliveryPeriod}'})
    outcome(s, 'gm_orderGw', 'executionOutcome', {'DONE': dlw}, rej)
    s.flow(order, 'gm_orderGw')
    w = s.egw('gm_wait', 'Delivered?')
    s.flow(dlw, w)
    dv = s.catch_msg('gm_delivered', 'Goods delivered', 'sangam.goodsDelivered')
    late = s.catch_timer('gm_late', 'Delivery overdue', date='${deliveryDueAt}')
    s.flow(w, dv)
    s.flow(w, late)
    lt = s.user('gm_overdue', 'Delivery overdue: extend or cancel | आपूर्ति विलंबित: विस्तार या रद्द', '${consigneeSeat}',
                form='gem-purchase/overdue', decision=('lateDecision', ['extend', 'terminate']), fields=['remarks*'])
    s.flow(late, lt)
    lg = s.xgw('gm_lateGw')
    s.flow(lt, lg)
    s.flow(lg, dlw, "lateDecision == 'extend'", 'extend')
    s.flow(lg, withdraw(p, s, 'gm_terminate'), None, 'terminate', default=True)
    ie, idn = s.sla_task('gm_inspect', 'Inspect and accept (CRAC) | निरीक्षण एवं स्वीकृति (सीआरएसी)', 'consigneeSeat',
                         form='gem-purchase/crac', decision=('cracDecision', ['accept', 'reject']),
                         fields=['receivedQty*', 'acceptedQty*', 'remarks'], default_spec='5WD')
    s.flow(dv, ie)
    rg = s.xgw('gm_cracGw')
    s.flow(idn, rg)
    rgd = s.conn('gm_rejectGoods', 'Reject goods on GeM', 'gem.rejectGoods', 'reject', 'submit', 'acceptedQty,receivedQty',
                 'gemReject', step='reject-goods')
    s.flow(rg, rgd, "cracDecision == 'reject'", 'reject')
    s.flow(rgd, dlw)
    crac = s.svc('gm_cracDoc', 'CRAC', C['DOC'], {'template': 'crac'})
    s.flow(rg, crac, None, 'accept', default=True)
    bill = s.call('gm_bill', 'Bill within the payment SLA (P1)', 'sangam-p1-bill-payment',
                  bk='#{execution.processBusinessKey}/BILL', all_in=False, outs_all=False,
                  ins={'supplierName': 'payee', 'supplierAccount': 'account', 'supplierIfsc': 'ifsc', 'amount': 'amount',
                       'budgetHead': 'budgetHead', 'approvedPayloadHash': 'sanctionRef', 'initiatorSeat': 'actingSeat',
                       'initiatorPerson': 'initiator', 'officeId': 'officeId', "${'3WD'}|1": 'scrutinySla',
                       "${'3WD'}|2": 'sanctionSla'},
                  outs={'caseState': 'billState'})
    cl = core(s, 'gm_close', 'Closure (core)', 'closure')
    s.seq(crac, bill, cl, end_ok(p, s, 'gm_end', 'Purchased and paid'))
    s.side_exits('gm')
    # decision 225: the value bands are the profile's (ifms-procurement-mode -> procurementMode); a mode this table does
    # not know matches no row and stops the case (required rule), it is never routed by a catch-all row
    pk.dmn('gem-procurement-mode', 'GeM route and auto-approval (synthetic auto-approval threshold)', 'FIRST',
           [('Value', 'amount', 'money'), ('Available on GeM', 'gemAvailable', 'boolean'),
            ('Procurement mode (profile table)', 'procurementMode', 'string')],
           [('gemRoute', 'GeM route', 'string'), ('autoApprove', 'Auto-approve', 'boolean')],
           [(['-', 'false', '-'], [q('OUTSIDE_GEM'), 'false'], 'not on GeM: tender with justification'),
            (['<= 25000', 'true', q('DIRECT')], [q('DIRECT'), 'true'], 'direct purchase, auto-approved (GP-29)'),
            (['-', 'true', q('DIRECT')], [q('DIRECT'), 'false'], 'direct purchase'),
            (['-', 'true', q('GEM_L1')], [q('L1'), 'false'], 'L1 among three sellers'),
            (['-', 'true', q('LIMITED_TENDER')], [q('BID'), 'false'], 'bid / reverse auction (limited tender band)'),
            (['-', 'true', q('OPEN_TENDER')], [q('BID'), 'false'], 'bid / reverse auction (open tender band)')])
    pk.dmn('gem-purchase-authority', 'Purchase sanction powers', 'FIRST', [('Amount', 'amount', 'money')],
           [('competentRole', 'Competent role', 'string')],
           [(['<= 100000'], [q('SO')], ''), (['<= 1000000'], [q('DS')], ''), (['-'], [q('JS')], '')])
    simple_seeds(pk, 'DEPT-GM', [('STORE-GM', 'STORE', 'SO-GM', 'Consignee / store keeper', 'परेषिती / भंडारपाल'),
                                 ('SO-GM', 'SO', 'DS-GM', 'Section officer (purchase)', 'अनुभाग अधिकारी (क्रय)'),
                                 ('DS-GM', 'DS', 'JS-GM', 'Deputy Secretary', 'उप सचिव'),
                                 ('JS-GM', 'JS', None, 'Joint Secretary', 'संयुक्त सचिव')], 'gm')


def build():
    works()
    gem()
