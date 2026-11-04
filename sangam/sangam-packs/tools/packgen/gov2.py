# SPDX-License-Identifier: Apache-2.0
"""IFMS (budget, challan, salary, pension), Works / WAMS, work order, tender, consultancy, GeM purchase,
security instruments and contract management."""
from lib import C, q
from packs import Pack
from common import core, outcome, end_ok, rejection, reject_with, withdraw, revise, batch_loop

SIDE = ['GP-14', 'VI.5']


def simple_seeds(pk, office, seats, prefix):
    pk.seeds(offices=[{'id': office, 'parent': ''}],
             seats=[{'id': sid, 'role': role, 'office': office, 'reportsTo': up, 'name': {'en': en, 'hi': hi}}
                    for sid, role, up, en, hi in seats],
             holdings=[{'seat': sid, 'person': prefix + '.' + sid.lower(), 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'}
                       for sid, _, _, _, _ in seats],
             roles=[{'code': role, 'name': {'en': en, 'hi': hi}} for _, role, _, en, hi in seats])


# ------------------------------------------------------------------------------------------------ budget re-appropriation
def budget():
    pk = Pack('gov.ifms.budget-reappropriation', 'budget-reappropriation', 'Budget re-appropriation',
              'बजट पुनर्विनियोजन', 'IFMS',
              'Savings in one head are re-appropriated to another: savings checked in IFMS, budget-section scrutiny, '
              'Finance concurrence through the referral inbox, sanction by the re-appropriation power, IFMS update '
              'with operationId and the re-appropriation order.',
              ['D-BUDGET-REAPPROPRIATION', 'GP-01', 'GP-11', 'GP-32', 'GP-37'] + SIDE,
              connectors=[('ledger.available', 'a', 'G-LEDGER', 'Savings available in the source line (sangam-ledger)'),
                          ('ledger.transfer', 'a', 'G-LEDGER', 'Post the re-appropriation as a ledger TRANSFER'),
                          ('referral.send', 'b', '', 'Finance concurrence')])
    p = pk.proc('sangam-budget-reappropriation', 'Budget re-appropriation', '',
                start={'materialFields': 'fromHead,toHead,amount'})
    s = p.root
    st = s.start('br_start', 'Proposal submitted', form='budget-reappropriation/proposal')
    p.forms.append(dict(key='budget-reappropriation/proposal', name='Re-appropriation proposal | पुनर्विनियोजन प्रस्ताव',
                        decision=None, fields=['fromHead*', 'toHead*', 'amount*', 'justification*', 'documents'], doc=None))
    d = s.setv('br_defaults', 'Configuration', defaults='caseType=REAPPROPRIATION;routingDecisionKey=reapp-authority;'
               'scrutinySeat=BUDGET-SO;referToTenant=finance;referralQuestion=Concurrence for re-appropriation;'
               'executionConnector=ledger.transfer;executionStep=reappropriate;ledgerId=BUDGET;reconcileRequired=false;'
               'outputTemplate=reappropriation-order;appealAllowed=false;budgetAvailable=0;budgetHead=@fromHead')
    bq = s.conn('br_savings', 'Savings in the source head', 'ledger.available', 'available', 'query', 'ledgerId,fromHead,amount', 'budget')
    g = s.xgw('br_savGw', 'Savings enough?')
    s.seq(st, d, bq, g)
    scr = core(s, 'br_scrutiny', 'Budget section scrutiny (core)', 'scrutiny')
    s.flow(g, scr, 'budgetAvailable >= amount', 'yes')
    s.flow(g, reject_with(p, s, 'br_rjSav', 'SAVINGS_NOT_AVAILABLE'), None, 'no', default=True)
    rej = rejection(p, s, 'br_rj')
    ref = core(s, 'br_finance', 'Finance concurrence (core referral)', 'referral')
    outcome(s, 'br_scrGw', 'scrutinyOutcome', {'ACCEPTED': ref}, rej)
    s.flow(scr, 'br_scrGw')
    san = core(s, 'br_sanction', 'Sanction by re-appropriation power (core)', 'sanction')
    cg = s.xgw('br_concurGw', 'Finance concurs?')
    s.flow(ref, cg)
    s.flow(cg, san, "referralDecision == 'concur'", 'concur')
    s.flow(cg, reject_with(p, s, 'br_rjFin', 'FINANCE_NOT_CONCURRED'), None, 'not concurred', default=True)
    ex = core(s, 'br_post', 'Post in IFMS (core execution)', 'execution')
    outcome(s, 'br_sanGw', 'sanctionOutcome', {'SANCTIONED': ex, 'RETURNED': scr}, rej)
    s.flow(san, 'br_sanGw')
    cl = core(s, 'br_close', 'Order and closure (core)', 'closure')
    fail = s.notify('br_postFailed', 'IFMS rejected the posting', 'ifms-posting-failed', 'inapp', 'initiatorSeat')
    outcome(s, 'br_exGw', 'executionOutcome', {'DONE': cl}, fail)
    s.flow(ex, 'br_exGw')
    s.flow(fail, scr)
    s.flow(cl, end_ok(p, s, 'br_end'))
    s.side_exits('br')
    pk.dmn('reapp-authority', 'Re-appropriation power', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')],
           [(['<= 1000000'], [q('HOD')], ''), (['<= 10000000'], [q('SECRETARY')], ''), (['-'], [q('FIN-SECY')], '')])
    simple_seeds(pk, 'DEPT-FIN', [('BUDGET-SO', 'BUDGET-SO', 'HOD-B', 'Budget section officer', 'बजट अनुभाग अधिकारी'),
                                  ('HOD-B', 'HOD', 'SECY-B', 'Head of department', 'विभागाध्यक्ष'),
                                  ('SECY-B', 'SECRETARY', 'FINSECY', 'Administrative secretary', 'प्रशासनिक सचिव'),
                                  ('FINSECY', 'FIN-SECY', None, 'Finance secretary', 'वित्त सचिव')], 'br')


# ------------------------------------------------------------------------------------------------ challan reconciliation
def challan():
    pk = Pack('gov.ifms.challan-reconciliation', 'challan-reconciliation', 'Receipts / challan reconciliation',
              'प्राप्ति / चालान मिलान', 'IFMS',
              'Daily (timer start) or on demand: the bank / treasury scroll is matched with the challans expected; '
              'each discrepancy becomes its own case (GP-09) resolved by the reconciliation desk.',
              ['GP-31', 'GP-30', 'GP-09', 'D-CHALLAN-RECON', 'GP-55'],
              connectors=[('treasury.challansExpected', 'a', 'G-PG', 'Challans booked (GRAS / e-Challan)'),
                          ('treasury.scroll', 'a', 'G-PFMS', 'Bank scroll for the date'),
                          ('treasury.adjust', 'a', 'G-PFMS', 'Adjustment entry')])
    p = pk.proc('sangam-challan-reconciliation', 'Challan reconciliation run', '',
                start={'materialFields': 'scrollDate'})
    s = p.root
    t = s.start('cr_timer', 'Daily 06:00', timer_cycle='0 0 6 * * ?', initiator=None)
    m = s.start('cr_manual', 'Run now (message)', msg='sangam.challanReconcileNow', initiator=None)
    j = s.xgw('cr_join')
    s.flow(t, j)
    s.flow(m, j)
    ld = s.svc('cr_expected', 'Challans expected', C['BATCH'], {'op': 'LOAD', 'connector': 'treasury.challansExpected',
                                                               'itemsVar': 'expectedJson', 'sampleSize': '4'}, async_=True)
    mt = s.svc('cr_match', 'Match with scroll', C['MATCH'], {'mode': 'SCROLL'}, async_=True)
    g = s.xgw('cr_discGw', 'Discrepancies?')
    s.seq(j, ld, mt, g)
    op, cg = batch_loop(p, s, 'cr', 'sangam-challan-discrepancy', inherit='scrollDate')
    s.flow(g, op, 'discrepancyCount > 0', 'yes')
    rep = s.svc('cr_report', 'Reconciliation statement', C['DOC'], {'template': 'challan-reconciliation-statement'})
    s.flow(g, rep, None, 'all matched', default=True)
    s.flow(cg, rep, None, 'resolved', default=True)
    s.flow(rep, s.end('cr_end', 'Reconciled'))
    c = pk.proc('sangam-challan-discrepancy', 'Challan discrepancy case', '', start={'materialFields': 'key,kind'})
    s = c.root
    st = s.start('cd_start', 'Discrepancy', initiator=None)
    d = s.setv('cd_defaults', 'Configuration', defaults='reconDeskSeat=TREASURY-RECON')
    e1, d1 = s.sla_task('cd_resolve', 'Resolve challan discrepancy | चालान विसंगति का निवारण', 'reconDeskSeat',
                        form='challan-reconciliation/resolve', decision=('discDecision', ['adjust', 'recover', 'writeOff']),
                        fields=['challanNo', 'discrepancyCause*', 'remarks'], default_spec='3WD')
    adj = s.conn('cd_adjust', 'Adjustment entry', 'treasury.adjust', 'adjust', 'submit', 'key,discDecision,discrepancyCause',
                 'adjust', step='adjust')
    s.seq(st, d, e1)
    s.seq(d1, adj, end_ok(c, s, 'cd_end', 'Resolved'))
    simple_seeds(pk, 'TREASURY', [('TREASURY-RECON', 'RECON', 'TO', 'Reconciliation desk', 'मिलान डेस्क'),
                                  ('TO', 'TO', None, 'Treasury officer', 'कोषागार अधिकारी')], 'cr')


# ------------------------------------------------------------------------------------------------ salary monthly
def salary():
    pk = Pack('gov.ifms.salary-monthly', 'salary-monthly', 'Monthly salary bills', 'मासिक वेतन बिल', 'IFMS',
              'On the 20th of each month (timer start cycle) one salary bill per DDO is generated from HRMS and each '
              'runs as a P1 bill with caseType SALARY (one instance per DDO, GP-09); the run closes when every bill '
              'is paid or rejected.',
              ['GP-30', 'GP-10', 'GP-09', 'GP-08', 'D-IFMS-BILL'],
              connectors=[('hrms.salaryBills', 'a', 'G-PFMS', 'Salary bill per DDO for the period')],
              depends=['sangam.core', 'gov.p1.bill-payment'])
    p = pk.proc('sangam-salary-monthly', 'Monthly salary run', '', start={'materialFields': 'period'})
    s = p.root
    t = s.start('sm_timer', 'Monthly on the 20th', timer_cycle='0 0 2 20 * ?', initiator=None)
    m = s.start('sm_manual', 'Run now (message)', msg='sangam.salaryRunNow', initiator=None)
    j = s.xgw('sm_join')
    s.flow(t, j)
    s.flow(m, j)
    d = s.setv('sm_defaults', 'Configuration', values='caseType=SALARY',
               defaults='period=current;actingSeat=CLERK-DDO;initiator=system;officeId=DPW-X')
    ld = s.svc('sm_load', 'Salary bills from HRMS', C['BATCH'], {'op': 'LOAD', 'connector': 'hrms.salaryBills',
                                                              'sampleSize': '3'}, async_=True)
    s.seq(j, d, ld)
    op, cg = batch_loop(p, s, 'sm', 'sangam-p1-bill-payment', poll='PT30M',
                        inherit='caseType,period,officeId,actingSeat,initiator')
    s.flow(ld, op)
    rep = s.svc('sm_report', 'Salary run report', C['DOC'], {'template': 'salary-run-report'})
    s.flow(cg, rep, None, 'complete', default=True)
    s.flow(rep, s.end('sm_end', 'Run closed'))


# ------------------------------------------------------------------------------------------------ pension
def pension():
    pk = Pack('gov.pension', 'pension', 'Pension: sanction, PPO and monthly disbursement', 'पेंशन: स्वीकृति, पीपीओ एवं मासिक भुगतान',
              'IFMS', 'Pension papers: computation by rule, service verification, Head of Office sanction, '
              'authorisation by the Accountant General / PAO with objections returned under the same case '
              '(GP-50), PPO issue and entry in the pension roll; monthly disbursement one instance per pensioner '
              'with life-certificate hold (GP-30, GP-09).',
              ['D-PENSION-MONTHLY', 'GP-30', 'GP-50', 'GP-09', 'GP-05', 'GP-34'] + SIDE,
              connectors=[('ag.pensionCase', 'b', 'G-PFMS', 'AG / PAO authorisation (e.g. Bhavishya / COMPACT)'),
                          ('pension.rollAdd', 'a', 'G-PFMS', 'Enter pensioner in the roll'),
                          ('pension.roll', 'a', 'G-PFMS', 'Monthly roll'),
                          ('pfms.payment', 'c', 'G-PFMS', 'Monthly pension payment')])
    p = pk.proc('sangam-pension-sanction', 'Pension sanction and PPO', '',
                start={'materialFields': 'employeeId,qualifyingService,lastPay,pensionAmount'})
    s = p.root
    st = s.start('ps_start', 'Pension papers initiated', form='pension/papers')
    p.forms.append(dict(key='pension/papers', name='Pension papers | पेंशन कागज़ात', decision=None,
                        fields=['employeeId*', 'employeeName*', 'retirementDate*', 'qualifyingService*', 'lastPay*',
                                'bankAccount*', 'ifsc*', 'documents'], doc=None))
    d = s.setv('ps_defaults', 'Configuration', defaults='caseType=PENSION;scrutinySeat=PENSION-CLERK;'
               'routingDecisionKey=pension-sanction-authority;executionConnector=ag.pensionCase;executionStep=ag;'
               'executionOperation=submitPensionCase;reconcileRequired=false;ackWindow=P7D;outputTemplate=ppo;'
               'appealAllowed=true;appellateSeat=HOD-P;amount=0;averageEmoluments=0;ageNextBirthday=61')
    comp = s.rule('ps_compute', 'Pension computation (DMN)', 'pension-computation')
    amt = s.setv('ps_amount', 'Amount for power check', values='amount=@pensionAmount')
    scr = core(s, 'ps_scrutiny', 'Pension section scrutiny (core)', 'scrutiny')
    s.seq(st, d, comp, amt, scr)
    rej = rejection(p, s, 'ps_rj', reopen=scr)
    ve, vd = s.sla_task('ps_verify', 'Verify service record | सेवा अभिलेख का सत्यापन', 'HOO-P',
                        form='pension/verify-service', decision=('serviceVerdict', ['verified', 'failed']),
                        fields=['qualifyingService*', 'remarks'], default_spec='10WD', sod=True)
    outcome(s, 'ps_scrGw', 'scrutinyOutcome', {'ACCEPTED': ve}, rej)
    s.flow(scr, 'ps_scrGw')
    san = core(s, 'ps_sanction', 'Head of Office sanction (core)', 'sanction')
    vg = s.xgw('ps_verGw')
    s.flow(vd, vg)
    s.flow(vg, rej, None, 'not verified', default=True)
    vf = s.setv('ps_verified', 'Service verified', values='serviceVerified=true')
    s.flow(vg, vf, "serviceVerdict == 'verified'", 'verified')
    s.flow(vf, san)
    ag = core(s, 'ps_ag', 'Authorisation by AG / PAO (core execution)', 'execution')
    outcome(s, 'ps_sanGw', 'sanctionOutcome', {'SANCTIONED': ag, 'RETURNED': scr}, rej)
    s.flow(san, 'ps_sanGw')
    obj = s.user('ps_objection', 'Reply to AG objection | महालेखाकार की आपत्ति का उत्तर', '${initiatorSeat}',
                 form='pension/objection', decision=('objDecision', ['resubmit', 'withdraw']),
                 fields=['objection', 'remarks*', 'documents'])
    ppo = s.svc('ps_ppo', 'Issue PPO', C['DOC'], {'template': 'ppo', 'resultVar': 'ppoNo', 'requireApproval': 'true'})
    outcome(s, 'ps_agGw', 'executionOutcome', {'DONE': ppo}, obj)
    s.flow(ag, 'ps_agGw')
    og = s.xgw('ps_objGw')
    s.flow(obj, og)
    ver = s.svc('ps_objVersion', 'Same case, new version (GP-50)', C['CASE'], {'op': 'RESUBMIT'})
    s.flow(og, ver, "objDecision == 'resubmit'", 'resubmit to AG')
    s.flow(ver, san)
    s.flow(og, withdraw(p, s, 'ps_wd'), None, 'withdraw', default=True)
    roll = s.conn('ps_roll', 'Enter in pension roll', 'pension.rollAdd', 'add', 'submit',
                  'employeeId,ppoNo,pensionAmount,bankAccount,ifsc', 'roll', step='roll')
    cl = core(s, 'ps_close', 'Notify and close (core)', 'closure')
    s.seq(ppo, roll, cl, end_ok(p, s, 'ps_end'))
    s.side_exits('ps')
    m = pk.proc('sangam-pension-monthly', 'Monthly pension disbursement', '', start={'materialFields': 'period'})
    s = m.root
    t = s.start('pm_timer', 'Monthly on the 25th', timer_cycle='0 0 1 25 * ?', initiator=None)
    ld = s.svc('pm_load', 'Pension roll', C['BATCH'], {'op': 'LOAD', 'connector': 'pension.roll', 'sampleSize': '3'},
               async_=True)
    s.flow(t, ld)
    op, cg = batch_loop(m, s, 'pm', 'sangam-pension-payment', inherit='period')
    s.flow(ld, op)
    rep = s.svc('pm_report', 'Disbursement report', C['DOC'], {'template': 'pension-disbursement-report'})
    s.flow(cg, rep, None, 'complete', default=True)
    s.flow(rep, s.end('pm_end', 'Month closed'))
    c = pk.proc('sangam-pension-payment', 'Pension payment for one pensioner', '',
                start={'materialFields': 'key,amount,bankAccount,ifsc'})
    s = c.root
    st = s.start('pp_start', 'Item', initiator=None)
    d = s.setv('pp_defaults', 'Configuration', defaults='lifeCertificateValid=true;executionConnector=pfms.payment;'
               'executionStep=pension;verifyApproval=false;reconcileRequired=true;bankAccount=;ifsc=')
    lg = s.xgw('pp_lifeGw', 'Life certificate valid?')
    s.seq(st, d, lg)
    hold = s.user('pp_life', 'Life certificate awaited | जीवन प्रमाण पत्र प्रतीक्षित', 'PAO-P', form='pension/life-certificate',
                  decision=('lifeDecision', ['proceed', 'drop']), fields=['lifeCertificate*', 'remarks'])
    ex = core(s, 'pp_pay', 'Pension payment (core execution)', 'execution')
    s.flow(lg, hold, '!lifeCertificateValid', 'hold')
    s.flow(lg, ex, None, 'valid', default=True)
    hg = s.xgw('pp_holdGw')
    s.flow(hold, hg)
    s.flow(hg, ex, "lifeDecision == 'proceed'", 'certificate received')
    s.flow(hg, s.end('pp_endHeld', 'Held', listeners=[c.item_listener('HELD')]), None, 'held', default=True)
    ok = end_ok(c, s, 'pp_endPaid', 'Paid')
    fl = s.end('pp_endFailed', 'Failed', listeners=[c.item_listener('FAILED', reason_var='opReasonCode')])
    outcome(s, 'pp_exGw', 'executionOutcome', {'DONE': ok}, fl)
    s.flow(ex, 'pp_exGw')
    pk.dmn('pension-computation', 'Pension computation (CCS (Pension) Rules 2021)', 'FIRST',
           [('Qualifying service (years)', 'qualifyingService', 'double'), ('Last emoluments', 'lastPay', 'double'),
            ('Average emoluments (10 months)', 'averageEmoluments', 'double')],
           [('pensionAmount', 'Monthly pension', 'double'), ('eligibleForPension', 'Eligible', 'boolean'),
            ('retirementGratuity', 'Retirement gratuity', 'double'), ('familyPension', 'Family pension (ordinary rate)', 'double'),
            ('ruleRef', 'Rule', 'string')],
           [(['>= 10', '-', '-'], ['min([max([max([lastPay, averageEmoluments]) * 0.5, 9000]), 125000])', 'true',
                                  'min([lastPay * 0.25 * min([floor(qualifyingService * 2), 66]), 2500000])',
                                  'max([lastPay * 0.3, 9000])',
                                  '"CCS (Pension) Rules 2021: 50% of last emoluments or average emoluments, whichever is more '
                                  'beneficial; minimum 9,000, maximum 50% of the highest pay (1,25,000); gratuity 1/4 per '
                                  'six-monthly period, max 16.5 x emoluments, ceiling 25 lakh [U ceiling date]"'],
             'pension after 10 years of qualifying service'),
            (['< 10', '-', '-'], ['0', 'false', 'lastPay * 0.5 * floor(qualifyingService * 2)', 'max([lastPay * 0.3, 9000])',
                                 '"CCS (Pension) Rules 2021: service gratuity below 10 years (half a month per six-monthly period) [U]"'],
             'service gratuity only')],
           doc='Replaces the earlier 50 % placeholder. Values from the CCS (Pension) Rules 2021 as summarised in IFMS-CENTRAL '
               'IFC-179; ceilings marked [U] are configuration placeholders. Commutation uses ccs-commutation-factor.')
    pk.dmn('ccs-commutation-factor', 'Commutation factor by age next birthday (CCS (Commutation of Pension) Rules)', 'FIRST',
           [('Age next birthday', 'ageNextBirthday', 'integer')],
           [('commutationFactor', 'Commutation factor', 'double'), ('ruleRef', 'Rule', 'string')],
           [(['61'], ['8.194', '"commutation table (age 61 next birthday)"'], 'retirement at 60'),
            (['-'], ['8.194', '"[U] load the full commutation table; default row is a placeholder"'], 'placeholder')])
    pk.dmn('pension-sanction-authority', 'Pension sanction authority', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')], [(['-'], [q('HOO')], 'Head of Office')])
    simple_seeds(pk, 'OFFICE-P', [('PENSION-CLERK', 'PENSION-CLERK', 'HOO-P', 'Pension clerk', 'पेंशन लिपिक'),
                                  ('HOO-P', 'HOO', 'HOD-P', 'Head of Office', 'कार्यालय प्रमुख'),
                                  ('HOD-P', 'HOD', None, 'Head of Department', 'विभागाध्यक्ष'),
                                  ('PAO-P', 'PAO', None, 'Pay and accounts officer', 'वेतन एवं लेखा अधिकारी')], 'pn')


# ------------------------------------------------------------------------------------------------ security instruments
def security():
    pk = Pack('gov.security-instrument', 'security-instrument', 'Bank guarantee / EMD / performance security',
              'बैंक गारंटी / बयाना / कार्य निष्पादन प्रतिभूति', 'Contracts',
              'Verify the guarantee with the issuing bank, review it a lead time before expiry and extend, encash or '
              'release; contract completion releases it and a contract default encashes it (GP-41).',
              ['GP-41', 'GP-13', 'GP-12', 'GP-53'],
              connectors=[('bank.bgVerify', 'a', '', 'SFMS confirmation from the issuing bank'),
                          ('bank.bgExtend', 'a', '', 'Extension request'),
                          ('bank.bgInvoke', 'c', '', 'Invocation (encashment)'),
                          ('bank.bgRelease', 'a', '', 'Release / return of the original')])
    p = pk.proc('sangam-security-instrument', 'Security instrument lifecycle', '',
                start={'materialFields': 'bgNo,bgBank,bgAmount,bgExpiryAt'})
    s = p.root
    st = s.start('si_start', 'Instrument received', form='security-instrument/register')
    p.forms.append(dict(key='security-instrument/register', name='Register guarantee | गारंटी पंजीकृत करें', decision=None,
                        fields=['bgNo*', 'bgBank*', 'bgAmount*', 'bgExpiryAt*', 'bgClaimAt', 'contractorId',
                                'physicalReceived', 'documents'], doc=None))
    d = s.setv('si_defaults', 'Configuration', defaults='custodianSeat=EE-SI;reviewLead=P30D;'
               'executionConnector=bank.bgInvoke;executionStep=invoke;verifyApproval=false;reconcileRequired=true')
    vf = s.conn('si_verify', 'Confirm with issuing bank (SFMS)', 'bank.bgVerify', 'confirm', 'query',
                'bgNo,bgBank,bgAmount,bgExpiryAt', 'bgv')
    g = s.xgw('si_vGw', 'Confirmed?')
    s.seq(st, d, vf, g)
    bad = s.user('si_notConfirmed', 'Guarantee not confirmed by the bank | बैंक द्वारा गारंटी की पुष्टि नहीं', '${custodianSeat}',
                 form='security-instrument/not-confirmed', decision=('bgDecision', ['resubmit', 'reject']),
                 fields=['remarks*'])
    s.flow(g, bad, "bgvStatus != 'COMPLETED'", 'no')
    rjs = s.end('si_endRejected', 'Instrument rejected')
    bg2 = s.xgw('si_badGw')
    s.flow(bad, bg2)
    s.flow(bg2, vf, "bgDecision == 'resubmit'", 'corrected')
    s.flow(bg2, rjs, None, 'reject', default=True)
    tell = s.svc('si_tellParent', 'Tell the contract', C['CORR'],
                 {'messageName': 'sangam.securityVerified', 'optional': 'true', 'variables': 'bgNo,bgAmount'})
    rv = s.svc('si_review', 'Review date before expiry', C['SLA'], {'op': 'BEFORE', 'clock': 'bgReview',
                                                                  'dateVar': 'bgExpiryAt', 'spec': '${reviewLead}'})
    w = s.egw('si_wait', 'Expiry review, completion or default')
    s.flow(g, tell, None, 'confirmed', default=True)
    s.seq(tell, rv, w)
    tm = s.catch_timer('si_due', 'Review date', date='${bgReviewDueAt}')
    done = s.catch_msg('si_completed', 'Contract completed', 'sangam.contractCompleted')
    dflt = s.catch_msg('si_default', 'Contract default', 'sangam.contractDefault')
    for x in (tm, done, dflt):
        s.flow(w, x)
    dec = s.user('si_decide', 'Guarantee expiring: extend, encash or release | गारंटी समाप्ति: विस्तार, भुनाना या मुक्त करना',
                 '${custodianSeat}', form='security-instrument/expiry', decision=('expiryDecision', ['extend', 'encash',
                                                                                                     'release']),
                 fields=['bgExpiryAt', 'remarks'])
    s.flow(tm, dec)
    ext = s.conn('si_extend', 'Request extension', 'bank.bgExtend', 'extend', 'submit', 'bgNo,bgExpiryAt', 'bgx',
                 step='extend')
    rel = s.conn('si_release', 'Release instrument', 'bank.bgRelease', 'release', 'submit', 'bgNo', 'bgr', step='release')
    inv = core(s, 'si_invoke', 'Invoke guarantee (core execution)', 'execution')
    outcome(s, 'si_decGw', 'expiryDecision', {'extend': ext, 'encash': inv}, rel)
    s.flow(dec, 'si_decGw')
    s.flow(ext, rv)
    s.flow(done, rel)
    s.flow(dflt, inv)
    cust = s.user('si_returnOriginal', 'Return the original instrument | मूल लिखत लौटाएँ', '${custodianSeat}',
                  form='security-instrument/return-original', decision=None, fields=['physicalReceived', 'custodian'])
    s.seq(rel, cust, s.end('si_endReleased', 'Released'))
    s.flow(inv, s.end('si_endEncashed', 'Encashed'))
    simple_seeds(pk, 'DIV-SI', [('EE-SI', 'EE', None, 'Executive Engineer (custodian)', 'अधिशासी अभियंता (अभिरक्षक)')], 'si')


# ------------------------------------------------------------------------------------------------ contract management
def contract():
    pk = Pack('gov.contract-management', 'contract-management', 'Contract amendment: variation, extra item, EoT, LD, price escalation',
              'अनुबंध संशोधन: परिवर्तन, अतिरिक्त मद, समय विस्तार, परिनिर्धारित नुकसानी, मूल्य वृद्धि', 'Contracts',
              'A running contract is amended: variation and extra items, extension of time, liquidated damages and '
              'price escalation are computed by rule, sanctioned by the competent authority and applied to the '
              'running works or supply case by message (GP-43, GP-42).',
              ['GP-43', 'GP-42', 'GP-05', 'GP-19'] + SIDE, connectors=[])
    p = pk.proc('sangam-contract-amendment', 'Contract amendment', '',
                start={'materialFields': 'amendmentType,amendmentAmount,extensionDays,ldAmount,escalationAmount'})
    s = p.root
    st = s.start('ca_start', 'Amendment proposed', form='contract-management/proposal')
    p.forms.append(dict(key='contract-management/proposal', name='Contract amendment | अनुबंध संशोधन', decision=None,
                        fields=['amendmentType*', 'amendmentAmount', 'extensionDays', 'delayDays', 'escalationIndex',
                                'justification*', 'documents'], doc=None))
    d = s.setv('ca_defaults', 'Configuration', defaults='amendmentAmount=0;extensionDays=0;delayDays=0;'
               'escalationIndex=0;contractValue=0;ldAmount=0;escalationAmount=0;caseType=VARIATION;'
               'routingDecisionKey=contract-amendment-authority;scrutinySeat=EE-CM;appealAllowed=false;'
               'outputTemplate=amendment-order')
    ld = s.rule('ca_ld', 'Liquidated damages (DMN)', 'contract-ld')
    pe = s.rule('ca_escalation', 'Price escalation (DMN)', 'contract-price-escalation')
    amt = s.expr('ca_amount', 'Amount for sanction',
                 "${execution.setVariable('amount', amendmentAmount + escalationAmount)}")
    scr = core(s, 'ca_scrutiny', 'Scrutiny (core)', 'scrutiny')
    s.seq(st, d, ld, pe, amt, scr)
    rej = rejection(p, s, 'ca_rj')
    san = core(s, 'ca_sanction', 'Sanction by amendment power (core)', 'sanction')
    outcome(s, 'ca_scrGw', 'scrutinyOutcome', {'ACCEPTED': san}, rej)
    s.flow(scr, 'ca_scrGw')
    tell = s.svc('ca_apply', 'Apply to the running contract', C['CORR'],
                 {'messageName': 'sangam.amendmentApproved', 'optional': 'true',
                  'variables': 'amendmentType,amendmentAmount,extensionDays,ldAmount,escalationAmount'})
    outcome(s, 'ca_sanGw', 'sanctionOutcome', {'SANCTIONED': tell, 'RETURNED': scr}, rej)
    s.flow(san, 'ca_sanGw')
    cl = core(s, 'ca_close', 'Amendment order and closure (core)', 'closure')
    s.seq(tell, cl, end_ok(p, s, 'ca_end'))
    s.side_exits('ca')
    pk.dmn('contract-ld', 'Liquidated damages (synthetic GCC clause)', 'FIRST',
           [('Delay (days)', 'delayDays', 'integer'), ('Contract value', 'contractValue', 'double')],
           [('ldAmount', 'LD amount', 'double')],
           [(['<= 0', '-'], ['0'], 'no delay'),
            (['-', '-'], ['min(delayDays * 0.0005, 0.1) * contractValue'], '0.05% per day, capped at 10%')])
    pk.dmn('contract-price-escalation', 'Price escalation (synthetic clause 10CC-style)', 'FIRST',
           [('Index change (%)', 'escalationIndex', 'double'), ('Base amount', 'amendmentAmount', 'double')],
           [('escalationAmount', 'Escalation', 'double')],
           [(['<= 5', '-'], ['0'], 'absorbed by the contractor up to 5%'),
            (['-', '-'], ['(escalationIndex - 5) / 100 * contractValue * 0.85'], 'beyond 5%, on 85% of value')])
    pk.dmn('contract-amendment-authority', 'Amendment authority', 'FIRST',
           [('Type', 'amendmentType', 'string'), ('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')],
           [([q('EOT'), '-'], [q('SE')], 'extension of time'), ([q('LD'), '-'], [q('EE')], 'LD levy'),
            (['-', '<= 500000'], [q('EE')], ''), (['-', '<= 2500000'], [q('SE')], ''), (['-', '-'], [q('CE')], '')])
    simple_seeds(pk, 'DIV-CM', [('EE-CM', 'EE', 'SE-CM', 'Executive Engineer', 'अधिशासी अभियंता'),
                                ('SE-CM', 'SE', 'CE-CM', 'Superintending Engineer', 'अधीक्षण अभियंता'),
                                ('CE-CM', 'CE', None, 'Chief Engineer', 'मुख्य अभियंता')], 'cm')


# ------------------------------------------------------------------------------------------------ tender
def tender():
    pk = Pack('gov.tender', 'tender', 'Tender / e-procurement', 'निविदा / ई-प्रापण', 'Procurement',
              'NIT approval and publication (CPPP / GePNIC), corrigendum extending the bid date on the running case, '
              'bid opening not before the opening time, two-envelope opening by committee, technical evaluation by '
              'quorum, financial opening, L1 by rule, negotiation and retender loops, award sanction, EMD refund by '
              'compensation (D-TENDER).',
              ['D-TENDER', 'GP-03', 'GP-04', 'GP-07', 'GP-13', 'GP-23', 'GP-24', 'GP-25', 'GP-41', 'GP-15'] + SIDE,
              connectors=[('cppp.publish', 'b', '', 'NIT / corrigendum on CPPP / GePNIC'),
                          ('eproc.bidsReceived', 'a', '', 'Bids received at closing'),
                          ('eproc.evaluate', 'a', '', 'Financial comparison, L1'),
                          ('emd.refund', 'a', 'G-PG', 'EMD refund to bidders')])
    p = pk.proc('sangam-tender', 'Tender', '', start={'materialFields': 'tenderId,nitText,estimateAmount,bidDueAt'})
    s = p.root
    st = s.start('td_start', 'Tender initiated', form='tender/nit')
    p.forms.append(dict(key='tender/nit', name='Notice inviting tender | निविदा आमंत्रण सूचना', decision=None,
                        fields=['tenderId*', 'itemDescription*', 'estimateAmount*', 'emdAmount', 'nitText*', 'bidDueAt*',
                                'openingAt*', 'documents'], doc=None))
    d = s.setv('td_defaults', 'Configuration', defaults=(
        'caseType=TENDER_NIT;amount=@estimateAmount;routingDecisionKey=tender-authority;bidOpeningSeats=BOC-1,BOC-2;'
        'tecSeats=TEC-1,TEC-2,TEC-3;financialOpeningGap=P1D;negotiationRounds=0;maxNegotiationRounds=2;'
        'retenderCount=0;maxRetenders=2;tpcSeat=TPC-CHAIR;eprocBidsCount=1;appealAllowed=false'))
    san = core(s, 'td_nitApproval', 'NIT approval (core sanction)', 'sanction')
    s.seq(st, d, san)
    pub = s.conn('td_publish', 'Publish NIT', 'cppp.publish', 'publishNit', 'submit', 'tenderId,nitText,bidDueAt,openingAt',
                 'publish', step='publish')
    rej = rejection(p, s, 'td_rj')
    outcome(s, 'td_nitGw', 'sanctionOutcome', {'SANCTIONED': pub}, rej)
    s.flow(san, 'td_nitGw')
    w = s.egw('td_window', 'Bid window (corrigendum allowed)')
    s.flow(pub, w)
    close = s.catch_timer('td_close', 'Bids close', date='${bidDueAt}')
    corr = s.catch_msg('td_corrigendum', 'Corrigendum', 'sangam.corrigendum')
    s.flow(w, close)
    s.flow(w, corr)
    apply = s.setv('td_applyCorr', 'New dates on the running case (GP-07)',
                   values='bidDueAt=@newBidDueAt;openingAt=@newOpeningAt')
    pubc = s.conn('td_publishCorr', 'Publish corrigendum', 'cppp.publish', 'publishCorrigendum', 'submit',
                  'tenderId,corrigendumText,bidDueAt', 'corrigendum', step='corrigendum')
    s.seq(corr, apply, pubc, w)
    bids = s.conn('td_bids', 'Bids received (EMD held)', 'eproc.bidsReceived', 'count', 'query', 'tenderId', 'eproc')
    s.flow(close, bids)
    refund = s.conn('td_refundEmd', 'Refund EMD (compensation)', 'emd.refund', 'refundAll', 'submit', 'tenderId', 'emdRefund',
                    step='emd-refund-all')
    s.node(refund)['attrs']['isForCompensation'] = 'true'
    s.node(refund)['comp_for'] = bids
    cb = s.boundary('td_bidsComp', bids, 'Refund EMD on failure', compensate=True)
    s.assoc(cb, refund)
    bg = s.xgw('td_bidsGw', 'Any bids?')
    s.flow(bids, bg)
    lock = s.catch_timer('td_openingLock', 'Not before opening time (GP-24)', date='${openingAt}')
    s.flow(bg, lock, 'eprocBidsCount > 0', 'bids received')
    retender = s.expr('td_retenderCount', 'Retender', "${execution.setVariable('retenderCount', retenderCount + 1)}")
    s.flow(bg, retender, None, 'no bids', default=True)
    s1 = s.setv('td_openCommittee', 'Bid opening committee', values='committeeSeats=@bidOpeningSeats;quorumRule=ALL')
    c1 = core(s, 'td_techOpening', 'Technical bid opening (core committee)', 'committee')
    s2 = s.setv('td_tecCommittee', 'Technical evaluation committee', values='committeeSeats=@tecSeats;quorumRule=MAJORITY')
    c2 = core(s, 'td_techEval', 'Technical evaluation (core committee, quorum)', 'committee')
    q1 = s.user('td_qualified', 'Record technically qualified bidders | तकनीकी रूप से योग्य बोलीदाता दर्ज करें', 'TEC-1',
                form='tender/qualified', decision=None, fields=['technicallyQualified*', 'documents'])
    fo = s.svc('td_finLockSet', 'Financial opening time', C['SLA'], {'op': 'DEADLINE', 'clock': 'financialOpening',
                                                                    'spec': '${financialOpeningGap}'})
    fl = s.catch_timer('td_finLock', 'Financial opening time lock', date='${financialOpeningDueAt}')
    s3 = s.setv('td_finCommittee', 'Financial opening committee', values='committeeSeats=@bidOpeningSeats;quorumRule=ALL')
    c3 = core(s, 'td_finOpening', 'Financial bid opening (core committee)', 'committee')
    ev = s.conn('td_evaluate', 'Comparative statement, L1', 'eproc.evaluate', 'compare', 'query', 'tenderId', 'evaluate')
    rl = s.rule('td_l1Rule', 'L1 acceptability (DMN)', 'tender-l1-acceptability')
    s.seq(lock, s1, c1, s2, c2, q1, fo, fl, s3, c3, ev, rl)
    neg = s.user('td_negotiate', 'Negotiate with L1 | एल1 से वार्ता', '${tpcSeat}', form='tender/negotiation',
                 decision=('negDecision', ['accepted', 'declined']), fields=['negotiatedAmount', 'remarks'])
    award_set = s.setv('td_awardVars', 'Award values', values='contractorId=@evaluateL1BidderId;'
                       'contractValue=@evaluateL1Amount;amount=@evaluateL1Amount;caseType=TENDER_AWARD')
    outcome(s, 'td_l1Gw', 'l1Action', {'ACCEPT': award_set, 'NEGOTIATE': neg}, retender)
    s.flow(rl, 'td_l1Gw')
    ng = s.xgw('td_negGw')
    s.flow(neg, ng)
    na = s.setv('td_negotiated', 'Negotiated price', values='evaluateL1Amount=@negotiatedAmount')
    s.flow(ng, na, "negDecision == 'accepted'", 'accepted')
    s.flow(na, award_set)
    s.flow(ng, retender, None, 'declined (GP-25)', default=True)
    rg = s.xgw('td_retenderGw', 'Retender allowed?')
    s.flow(retender, rg)
    redo = s.user('td_reissue', 'Re-issue NIT with new dates | नई तिथियों सहित पुनः निविदा', '${initiatorSeat}',
                  form='tender/reissue', decision=None, fields=['bidDueAt*', 'openingAt*', 'nitText'])
    s.flow(rg, redo, 'retenderCount <= maxRetenders', 'retender')
    s.flow(redo, pub)
    comp = s.throw('td_compensate', 'Refund all EMDs', compensate=True)
    failed = s.setv('td_failed', 'Tender failed', values='tenderOutcome=FAILED')
    s.flow(rg, comp, None, 'give up', default=True)
    s.seq(comp, failed, s.end('td_endFailed', 'Tender failed'))
    awd = core(s, 'td_award', 'Award sanction (core)', 'sanction')
    s.flow(award_set, awd)
    emd = s.conn('td_emdUnsuccessful', 'Refund EMD of unsuccessful bidders', 'emd.refund', 'refundUnsuccessful', 'submit',
                 'tenderId,contractorId', 'emdUnsuccessful', step='emd-refund-unsuccessful')
    outcome(s, 'td_awardGw', 'sanctionOutcome', {'SANCTIONED': emd}, retender)
    s.flow(awd, 'td_awardGw')
    ok = s.setv('td_awarded', 'Awarded', values='tenderOutcome=AWARDED')
    s.seq(emd, ok, end_ok(p, s, 'td_end', 'Awarded'))
    s.side_exits('td')
    pk.dmn('tender-authority', 'Tender NIT / award authority', 'FIRST',
           [('Case type', 'caseType', 'string'), ('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')],
           [(['-', '<= 2500000'], [q('EE')], ''), (['-', '<= 100000000'], [q('SE')], ''), (['-', '-'], [q('CE')], '')])
    pk.dmn('tender-l1-acceptability', 'L1 acceptability against the justified estimate', 'FIRST',
           [('L1 amount', 'evaluateL1Amount', 'double'), ('Estimate', 'estimateAmount', 'double'),
            ('Responsive bids', 'evaluateResponsiveBids', 'integer'), ('Negotiation rounds', 'negotiationRounds', 'integer')],
           [('l1Action', 'Action', 'string')],
           [(['-', '-', '< 1', '-'], [q('RETENDER')], 'no responsive bid'),
            (['-', '-', '-', '-'], ['if evaluateL1Amount <= estimateAmount * 1.05 then "ACCEPT" else '
                                    'if evaluateL1Amount <= estimateAmount * 1.2 then "NEGOTIATE" else "RETENDER"'],
             'within 5% accept, up to 20% negotiate, beyond retender')])
    simple_seeds(pk, 'DIV-T', [('BOC-1', 'BOC', 'EE-T', 'Bid opening member 1', 'बोली खोलने वाले सदस्य 1'),
                               ('BOC-2', 'BOC', 'EE-T', 'Bid opening member 2', 'बोली खोलने वाले सदस्य 2'),
                               ('TEC-1', 'TEC', 'EE-T', 'Technical committee chair', 'तकनीकी समिति अध्यक्ष'),
                               ('TEC-2', 'TEC', 'EE-T', 'Technical committee member', 'तकनीकी समिति सदस्य'),
                               ('TEC-3', 'TEC', 'EE-T', 'Technical committee member (finance)', 'तकनीकी समिति सदस्य (वित्त)'),
                               ('TPC-CHAIR', 'TPC', 'SE-T', 'Tender purchase committee chair', 'निविदा क्रय समिति अध्यक्ष'),
                               ('EE-T', 'EE', 'SE-T', 'Executive Engineer', 'अधिशासी अभियंता'),
                               ('SE-T', 'SE', 'CE-T', 'Superintending Engineer', 'अधीक्षण अभियंता'),
                               ('CE-T', 'CE', None, 'Chief Engineer', 'मुख्य अभियंता')], 'td')


# ------------------------------------------------------------------------------------------------ consultancy (QCBS)
def consultancy():
    pk = Pack('gov.consultancy', 'consultancy', 'Consultancy procurement (QCBS)', 'परामर्श सेवा प्रापण (क्यूसीबीएस)',
              'Procurement', 'Expression of interest, shortlist by committee, RFP, technical evaluation, financial '
              'opening of the qualified only, combined quality-and-cost score by rule, negotiation and award (GFR '
              'consultancy chapter, synthetic weights).',
              ['GP-03', 'GP-04', 'GP-23', 'GP-24', 'GP-25'] + SIDE,
              connectors=[('cppp.publish', 'b', '', 'EOI / RFP publication'),
                          ('eproc.qcbs', 'a', '', 'Combined score ranking')])
    p = pk.proc('sangam-consultancy', 'Consultancy (QCBS)', '', start={'materialFields': 'itemDescription,estimateAmount'})
    s = p.root
    st = s.start('cs_start', 'Requirement', form='consultancy/requirement')
    p.forms.append(dict(key='consultancy/requirement', name='Consultancy requirement | परामर्श आवश्यकता', decision=None,
                        fields=['itemDescription*', 'estimateAmount*', 'documents'], doc=None))
    d = s.setv('cs_defaults', 'Configuration', defaults='caseType=CONSULTANCY;amount=@estimateAmount;'
               'routingDecisionKey=tender-authority;committeeSeats=CEC-1,CEC-2,CEC-3;quorumRule=MAJORITY;'
               'eoiWindow=P21D;rfpWindow=P30D;appealAllowed=false')
    eoi = s.conn('cs_eoi', 'Publish EOI', 'cppp.publish', 'publishEoi', 'submit', 'itemDescription', 'eoi', step='eoi')
    w1s = s.svc('cs_eoiWindow', 'EOI window', C['SLA'], {'op': 'DEADLINE', 'clock': 'eoi', 'spec': '${eoiWindow}'})
    w1 = s.catch_timer('cs_eoiClose', 'EOI closes', date='${eoiDueAt}')
    c1 = core(s, 'cs_shortlist', 'Shortlist (core committee)', 'committee')
    rfp = s.conn('cs_rfp', 'Issue RFP to shortlisted', 'cppp.publish', 'issueRfp', 'submit', 'itemDescription', 'rfp', step='rfp')
    w2s = s.svc('cs_rfpWindow', 'Proposal window', C['SLA'], {'op': 'DEADLINE', 'clock': 'rfp', 'spec': '${rfpWindow}'})
    w2 = s.catch_timer('cs_rfpClose', 'Proposals close', date='${rfpDueAt}')
    c2 = core(s, 'cs_techEval', 'Technical evaluation (core committee)', 'committee')
    ts = s.user('cs_techScores', 'Record technical scores | तकनीकी अंक दर्ज करें', 'CEC-1', form='consultancy/technical-scores',
                decision=None, fields=['technicalScore*', 'documents'])
    fin = s.user('cs_finOpen', 'Open financial proposals of the qualified | योग्य के वित्तीय प्रस्ताव खोलें', 'CEC-1',
                 form='consultancy/financial', decision=None, fields=['financialQuote*'])
    rank = s.conn('cs_rank', 'Combined score (QCBS)', 'eproc.qcbs', 'rank', 'query', 'technicalScore,financialQuote',
                  'evaluate')
    neg = s.user('cs_negotiate', 'Negotiate with the top-ranked firm | शीर्ष फर्म से वार्ता', 'CEC-1',
                 form='consultancy/negotiation', decision=('negDecision', ['accepted', 'declined']),
                 fields=['negotiatedAmount', 'remarks'])
    s.seq(st, d, eoi, w1s, w1, c1, rfp, w2s, w2, c2, ts, fin, rank, neg)
    av = s.setv('cs_award', 'Award values', values='amount=@negotiatedAmount;caseType=CONSULTANCY_AWARD')
    san = core(s, 'cs_sanction', 'Award sanction (core)', 'sanction')
    ng = s.xgw('cs_negGw')
    s.flow(neg, ng)
    s.flow(ng, av, "negDecision == 'accepted'", 'accepted')
    s.flow(ng, rfp, None, 'declined: next ranked (re-issue)', default=True)
    s.flow(av, san)
    rej = rejection(p, s, 'cs_rj')
    doc = s.svc('cs_contract', 'Contract', C['DOC'], {'template': 'consultancy-contract', 'requireApproval': 'true'})
    outcome(s, 'cs_sanGw', 'sanctionOutcome', {'SANCTIONED': doc}, rej)
    s.flow(san, 'cs_sanGw')
    s.seq(doc, end_ok(p, s, 'cs_end', 'Contracted'))
    s.side_exits('cs')
    simple_seeds(pk, 'DEPT-C', [('CEC-1', 'CEC', 'JS-C', 'Consultancy committee chair', 'परामर्श समिति अध्यक्ष'),
                                ('CEC-2', 'CEC', 'JS-C', 'Consultancy committee member', 'परामर्श समिति सदस्य'),
                                ('CEC-3', 'CEC', 'JS-C', 'Consultancy committee member (finance)', 'परामर्श समिति सदस्य (वित्त)'),
                                ('JS-C', 'JS', None, 'Joint Secretary', 'संयुक्त सचिव')], 'cs')


# ------------------------------------------------------------------------------------------------ work order
def work_order():
    pk = Pack('gov.work-order', 'work-order', 'Work order / letter of acceptance', 'कार्यादेश / स्वीकृति पत्र', 'Works / WAMS',
              'Letter of acceptance, acceptance by the contractor within the window (else EMD forfeited), performance '
              'security as a linked instrument case, agreement signing, site handover and the work order.',
              ['D-WORKS', 'GP-41', 'GP-15', 'GP-34', 'GP-53'],
              connectors=[('emd.forfeit', 'c', 'G-PG', 'EMD forfeiture'), ('esign.sign', 'b', 'G-SIGN', 'Agreement')],
              depends=['sangam.core', 'gov.security-instrument'])
    p = pk.proc('sangam-work-order', 'Work order', '', start={'materialFields': 'contractorId,contractValue,workName'})
    s = p.root
    st = s.start('wo_start', 'Award received', form='work-order/award')
    p.forms.append(dict(key='work-order/award', name='Issue letter of acceptance | स्वीकृति पत्र जारी करें', decision=None,
                        fields=['workName*', 'contractorId*', 'contractValue*', 'completionDueAt*'], doc=None))
    d = s.setv('wo_defaults', 'Configuration', defaults='acceptanceWindow=P15D;securityWindow=P21D;eeSeat=EE-WO;aeSeat=AE-WO')
    loa = s.svc('wo_loa', 'Letter of acceptance', C['DOC'], {'template': 'letter-of-acceptance'})
    nt = s.notify('wo_tell', 'Send LoA to contractor', 'loa-issued', 'email', 'contractorId')
    dl = s.svc('wo_window', 'Acceptance window', C['SLA'], {'op': 'DEADLINE', 'clock': 'acceptance',
                                                           'spec': '${acceptanceWindow}'})
    w = s.egw('wo_wait', 'Accepted?')
    s.seq(st, d, loa, nt, dl, w)
    acc = s.catch_msg('wo_accepted', 'LoA accepted', 'sangam.loaAccepted')
    lap = s.catch_timer('wo_lapsed', 'Not accepted in time', date='${acceptanceDueAt}')
    s.flow(w, acc)
    s.flow(w, lap)
    ff = s.conn('wo_forfeit', 'Forfeit EMD', 'emd.forfeit', 'forfeit', 'submit', 'contractorId', 'forfeit', step='forfeit')
    nok = s.setv('wo_notAccepted', 'Not accepted', values='workOrderOutcome=NOT_ACCEPTED')
    s.seq(lap, ff, nok, s.end('wo_endNotAccepted', 'Not accepted'))
    ps = s.svc('wo_security', 'Performance security case (linked)', C['LINK'],
               {'processKey': 'sangam-security-instrument', 'linkType': 'performance-security',
                'inherit': 'contractorId,contractValue'})
    sw = s.svc('wo_secWindow', 'Security due', C['SLA'], {'op': 'DEADLINE', 'clock': 'security', 'spec': '${securityWindow}'})
    w2 = s.egw('wo_secWait', 'Security verified?')
    s.seq(acc, ps, sw, w2)
    sv = s.catch_msg('wo_secVerified', 'Security verified', 'sangam.securityVerified')
    sl = s.catch_timer('wo_secLapsed', 'Security not furnished', date='${securityDueAt}')
    s.flow(w2, sv)
    s.flow(w2, sl)
    s.seq(sl, s.conn('wo_forfeit2', 'Forfeit EMD (no security)', 'emd.forfeit', 'forfeit', 'submit', 'contractorId', 'forfeit2',
                     step='forfeit-security'), s.setv('wo_noSecurity', 'No security', values='workOrderOutcome=NO_SECURITY'),
          s.end('wo_endNoSecurity', 'Cancelled: no security'))
    ag = s.user('wo_agreement', 'Sign agreement | अनुबंध पर हस्ताक्षर', '${eeSeat}', form='work-order/agreement',
                decision=None, fields=['remarks'], bind=None)
    sg = s.svc('wo_sign', 'Sign agreement', C['SIGN'], {'op': 'REQUEST', 'purpose': 'agreement'}, async_=True)
    site = s.user('wo_site', 'Hand over site | स्थल सौंपें', '${aeSeat}', form='work-order/site-handover', decision=None,
                  fields=['remarks', 'physicalReceived'])
    wo = s.svc('wo_order', 'Work order', C['DOC'], {'template': 'work-order'})
    ok = s.setv('wo_issued', 'Issued', values='workOrderOutcome=ISSUED')
    s.seq(sv, ag, sg, site, wo, ok, end_ok(p, s, 'wo_end', 'Work order issued'))
    simple_seeds(pk, 'DIV-WO', [('AE-WO', 'AE', 'EE-WO', 'Assistant Engineer', 'सहायक अभियंता'),
                                ('EE-WO', 'EE', None, 'Executive Engineer', 'अधिशासी अभियंता')], 'wo')


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
                start={'materialFields': 'workName,estimateAmount,budgetHead'})
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
    pk.dmn('works-ts-authority', 'Technical sanction powers (synthetic)', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')],
           [(['<= 2500000'], [q('EE')], ''), (['<= 20000000'], [q('SE')], ''), (['-'], [q('CE')], '')])
    pk.dmn('works-aa-authority', 'Administrative approval powers (synthetic)', 'FIRST',
           [('Case type', 'caseType', 'string'), ('Amount', 'amount', 'double')],
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
                          ('ifms.release', 'a', 'G-PFMS', 'Release')],
              depends=['sangam.core', 'gov.tender', 'gov.p1.bill-payment'])
    p = pk.proc('sangam-gem-purchase', 'Purchase of goods', '',
                start={'materialFields': 'itemDescription,quantity,unitPrice,budgetHead,amount'})
    s = p.root
    st = s.start('gm_start', 'Indent raised', form='gem-purchase/indent')
    p.forms.append(dict(key='gem-purchase/indent', name='Indent | मांग पत्र', decision=None,
                        fields=['itemDescription*', 'quantity*', 'unitPrice*', 'gemAvailable*', 'budgetHead*',
                                'justification', 'documents'], doc=None))
    d = s.setv('gm_defaults', 'Configuration', defaults=(
        'caseType=PURCHASE;routingDecisionKey=gem-purchase-authority;deliveryPeriod=P30D;consigneeSeat=STORE-GM;'
        'executionConnector=gem.order;executionStep=gem-order;executionOperation=placeOrder;reconcileRequired=false;'
        'releaseConnector=ifms.release;budgetAvailable=0;appealAllowed=false;supplierAccount=;supplierIfsc=;'
        'supplierName=GeM seller'))
    amt = s.expr('gm_amount', 'Value', "${execution.setVariable('amount', quantity * unitPrice)}")
    mode = s.rule('gm_mode', 'Mode of procurement (DMN)', 'gem-procurement-mode')
    bq = s.conn('gm_budget', 'Budget available', 'ifms.budget', 'available', 'query', 'budgetHead,amount', 'budget')
    bg = s.xgw('gm_budgetGw', 'Budget?')
    s.seq(st, d, amt, mode, bq, bg)
    enc = s.conn('gm_encumber', 'Encumber', 'ifms.encumber', 'encumber', 'submit', 'budgetHead,amount', 'encumber',
                 step='encumber')
    s.flow(bg, enc, 'budgetAvailable >= amount', 'yes')
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
    s.flow(mg, bidDoc, "procurementMode == 'BID' || procurementMode == 'OUTSIDE_GEM'", 'bid')
    s.flow(bidDoc, tdr)
    s.flow(mg, order, None, 'direct / L1', default=True)
    tg = s.xgw('gm_tenderGw')
    s.flow(tdr, tg)
    s.flow(tg, order, "tenderOutcome == 'AWARDED'", 'awarded')
    s.flow(tg, rej, None, 'failed', default=True)
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
    pk.dmn('gem-procurement-mode', 'Mode of procurement (synthetic thresholds)', 'FIRST',
           [('Value', 'amount', 'double'), ('Available on GeM', 'gemAvailable', 'boolean')],
           [('procurementMode', 'Mode', 'string'), ('autoApprove', 'Auto-approve', 'boolean')],
           [(['-', 'false'], [q('OUTSIDE_GEM'), 'false'], 'not on GeM: tender with justification'),
            (['<= 25000', 'true'], [q('DIRECT'), 'true'], 'direct purchase, auto-approved (GP-29)'),
            (['<= 50000', 'true'], [q('DIRECT'), 'false'], 'direct purchase'),
            (['<= 1000000', 'true'], [q('L1'), 'false'], 'L1 among three sellers'),
            (['-', 'true'], [q('BID'), 'false'], 'bid / reverse auction')])
    pk.dmn('gem-purchase-authority', 'Purchase sanction powers', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')],
           [(['<= 100000'], [q('SO')], ''), (['<= 1000000'], [q('DS')], ''), (['-'], [q('JS')], '')])
    simple_seeds(pk, 'DEPT-GM', [('STORE-GM', 'STORE', 'SO-GM', 'Consignee / store keeper', 'परेषिती / भंडारपाल'),
                                 ('SO-GM', 'SO', 'DS-GM', 'Section officer (purchase)', 'अनुभाग अधिकारी (क्रय)'),
                                 ('DS-GM', 'DS', 'JS-GM', 'Deputy Secretary', 'उप सचिव'),
                                 ('JS-GM', 'JS', None, 'Joint Secretary', 'संयुक्त सचिव')], 'gm')


def build():
    budget()
    challan()
    salary()
    pension()
    security()
    contract()
    tender()
    consultancy()
    work_order()
    works()
    gem()
