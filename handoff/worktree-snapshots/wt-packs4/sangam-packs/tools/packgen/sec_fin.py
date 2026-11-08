# SPDX-License-Identifier: Apache-2.0
"""Sector packs for financial services: banking (sec.bank.*), NBFC / fintech (sec.nbfc.*), capital markets
(sec.cap.*), insurance (sec.ins.*) and the cross-regulator packs (sec.fin.*: complaints, cyber incidents).
Regulatory references: spike/results/SECTOR-WORKFLOWS.md (SEC-BANK-*, SEC-NBFC-*, SEC-CAP-*, SEC-INS-*, SEC-FIN-*).
All thresholds, limits and timelines marked SYNTHETIC are pilot values; regulated entities replace them with their
board-approved policy (tenant DMN override, GP-37)."""
from lib import C, q
from money import M  # noqa: F401  (amounts inside FEEL expressions, minor units)
from common import core, outcome, end_ok, rejection, reject_with, revise, batch_loop
from gov2 import simple_seeds
from sector import SIDE, spack, start_form, clock, at_date, powers, authority, maybe


# ============================================================================================ banking
def bank_account_opening():
    pk = spack('bank', 'account-opening', 'Account opening with KYC, V-CIP and CKYCR',
               'केवाईसी, वी-सीआईपी एवं सीकेवाईसीआर सहित खाता खोलना', 'Banking',
               'Customer due diligence under the RBI KYC Master Direction: consent, CKYCR search and download, PAN '
               'verification, Aadhaar OTP e-KYC or V-CIP by an official with liveness and geo-tag, screening against '
               'the UNSC / UAPA lists and PEP, risk categorisation (re-KYC cycle 2 / 8 / 10 years), EDD for high '
               'risk, maker-checker account opening in the CBS with operationId, CKYCR upload, welcome kit.',
               ['SEC-BANK-001', 'SEC-BANK-002', 'SEC-BANK-003', 'SEC-BANK-004', 'SEC-BANK-005', 'SEC-BANK-006',
                'UP-05', 'UP-22', 'GP-01', 'GP-16', 'GP-34'] + SIDE,
               [('ckycr.search', 'a', '', 'CKYCR search (CERSAI)'), ('ckycr.download', 'a', '', 'CKYCR download'),
                ('pan.verify', 'a', '', 'PAN verification (ITD / Protean)'),
                ('uidai.ekyc', 'b', '', 'Aadhaar OTP e-KYC (AUA / KUA)'),
                ('sanctions.screen', 'a', '', 'UNSC / UAPA / PEP screening'),
                ('cbs.accountOpen', 'a', '', 'Core banking: open account (core execution)'),
                ('ckycr.upload', 'a', '', 'CKYCR upload of the KYC record')])
    p = pk.proc('sangam-sec-bank-account-opening', 'Account opening with KYC', '',
                start={'materialFields': 'customerName,pan,accountType,kycMode'})
    s = p.root
    st = start_form(p, s, 'ao_start', 'Application received', 'sec-bank-account-opening/application',
                    'Account opening application | खाता खोलने का आवेदन',
                    ['customerName*', 'pan*', 'mobile*', 'accountType*', 'kycMode*', 'occupation', 'consentGiven*',
                     'applicantRef', 'documents'])
    d = s.setv('ao_defaults', 'Configuration',
               defaults='customerName=@customerId;accountType=SAVINGS;kycMode=AADHAAR_OTP;occupation=SALARIED;'
                        'consentGiven=true;ckycFound=false;panValid=true;screenHit=false;pepFlag=false;amount=0;'
                        'caseType=ACCOUNT_OPENING;routingDecisionKey=sec-bank-ao-checker;'
                        'powersDecisionKey=sec-bank-ao-powers;executionConnector=cbs.accountOpen;'
                        'executionStep=open-account;reconcileRequired=false;outputTemplate=welcome-kit;'
                        'appealAllowed=false;vcipSeat=VCIP-OFFICIAL;complianceSeat=AML-COMPLIANCE;'
                        'eddSeat=BRANCH-HEAD')
    cg = s.xgw('ao_consentGw', 'Consent for KYC?')
    s.seq(st, d, cg)
    s.flow(cg, s.end('ao_endNoConsent', 'Stopped: no consent'), '!consentGiven', 'no')
    ck = s.conn('ao_ckycSearch', 'Search CKYCR', 'ckycr.search', 'search', 'query', 'pan,customerName', 'ckyc')
    s.flow(cg, ck, None, 'yes', default=True)
    dl = s.conn('ao_ckycDownload', 'Download CKYC record', 'ckycr.download', 'download', 'query', 'pan', 'ckycRec')
    g1, m1 = maybe(s, 'ao_ckyc', 'ckycFound', dl, dl, 'Found in CKYCR?')
    s.flow(ck, g1)
    pv = s.conn('ao_pan', 'Verify PAN', 'pan.verify', 'verify', 'query', 'pan,customerName', 'pan')
    s.flow(m1, pv)
    pg = s.xgw('ao_panGw', 'PAN valid?')
    s.flow(pv, pg)
    rv = revise(p, s, 'ao_correct', 'Correct application details | आवेदन विवरण सुधारें',
                ['customerName*', 'pan*', 'mobile*'], pv)
    s.flow(pg, rv, '!panValid', 'mismatch: correct')
    mg = s.xgw('ao_modeGw', 'KYC mode?')
    s.flow(pg, mg, None, 'valid', default=True)
    ve, vd = s.sla_task('ao_vcip', 'V-CIP: live video identification | वी-सीआईपी: सजीव वीडियो पहचान', 'vcipSeat',
                        form='sec-bank-account-opening/vcip', decision=('vcipDecision', ['pass', 'reschedule', 'fail']),
                        fields=['livenessChecked*', 'geoTag*', 'panCaptured*', 'vcipRecordingRef*', 'remarks'],
                        spec='2WD', on_breach='ESCALATE', sod=True,
                        doc='Official of the RE (not an agent) conducts the session in India; liveness, geo-tag, '
                            'PAN image, random questions; the recording is stored with date and time.')
    s.flow(mg, ve, "kycMode == 'VCIP'", 'V-CIP')
    ek = s.conn('ao_ekyc', 'Aadhaar OTP e-KYC', 'uidai.ekyc', 'otp-ekyc', 'query', 'customerName,mobile', 'ekyc')
    s.flow(mg, ek, None, 'e-KYC / digital', default=True)
    vg = s.xgw('ao_vcipGw', 'V-CIP result?')
    s.flow(vd, vg)
    s.flow(vg, ve, "vcipDecision == 'reschedule'", 'dropped / poor video: reschedule')
    kj = s.xgw('ao_kycJoin')
    s.flow(vg, kj, "vcipDecision == 'pass'", 'pass')
    s.flow(ek, kj)
    s.flow(vg, reject_with(p, s, 'ao_rjVcip', 'VCIP_FAILED'), None, 'fail', default=True)
    sc = s.conn('ao_screen', 'Screen UNSC / UAPA / PEP lists', 'sanctions.screen', 'screen', 'query',
                'customerName,pan', 'screen')
    s.flow(kj, sc)
    sg = s.xgw('ao_screenGw', 'Screening hit?')
    s.flow(sc, sg)
    cr = s.user('ao_compliance', 'Review screening hit | स्क्रीनिंग मिलान की समीक्षा', '${complianceSeat}',
                form='sec-bank-account-opening/screening', decision=('screenDecision', ['falsePositive', 'reject']),
                fields=['screenMatchDetails', 'remarks*'], sod=True,
                doc='A true match with a designated list is frozen and reported (UAPA s.51A order), never opened.')
    s.flow(sg, cr, 'screenHit', 'possible match')
    rk = s.rule('ao_risk', 'Customer risk category (DMN)', 'sec-bank-kyc-risk')
    s.flow(sg, rk, None, 'clear', default=True)
    crg = s.xgw('ao_compGw')
    s.flow(cr, crg)
    s.flow(crg, rk, "screenDecision == 'falsePositive'", 'false positive')
    s.flow(crg, reject_with(p, s, 'ao_rjScreen', 'SANCTIONS_MATCH'), None, 'true match', default=True)
    edd = s.user('ao_edd', 'Enhanced due diligence | संवर्धित सम्यक तत्परता', '${eddSeat}',
                 form='sec-bank-account-opening/edd', decision=('eddDecision', ['approve', 'reject']),
                 fields=['sourceOfFunds*', 'remarks*'], sod=True)
    san = core(s, 'ao_check', 'Checker approves account opening (core sanction, SoD)', 'sanction')
    g2, m2 = maybe(s, 'ao_eddOpt', 'eddRequired', edd, edd, 'High risk: EDD?')
    s.flow(rk, g2)
    s.flow(m2, san)
    rej = rejection(p, s, 'ao_rj')
    ex = core(s, 'ao_open', 'Open account in CBS (core execution)', 'execution')
    outcome(s, 'ao_sanGw', 'sanctionOutcome', {'SANCTIONED': ex, 'RETURNED': rv}, rej)
    s.flow(san, 'ao_sanGw')
    up = s.conn('ao_ckycUpload', 'Upload KYC record to CKYCR', 'ckycr.upload', 'upload', 'submit',
                'customerName,pan,riskCategory', 'ckycUp', step='ckyc-upload')
    cl = core(s, 'ao_close', 'Welcome kit and closure (core)', 'closure')
    fail = s.notify('ao_openFailed', 'CBS refused the account', 'cbs-open-failed', 'inapp', 'initiatorSeat')
    outcome(s, 'ao_exGw', 'executionOutcome', {'DONE': up}, fail)
    s.flow(ex, 'ao_exGw')
    s.flow(fail, san)
    s.seq(up, cl, end_ok(p, s, 'ao_end', 'Account opened'))
    s.side_exits('ao')
    pk.dmn('sec-bank-kyc-risk', 'Customer risk category and re-KYC cycle', 'FIRST',
           [('PEP', 'pepFlag', 'boolean'), ('Occupation', 'occupation', 'string'), ('KYC mode', 'kycMode', 'string')],
           [('riskCategory', 'Risk', 'string'), ('rekycYears', 'Re-KYC every (years)', 'integer'),
            ('eddRequired', 'EDD', 'boolean')],
           [(['true', '-', '-'], [q('HIGH'), '2', 'true'], 'PEP: high risk, EDD, senior approval'),
            (['-', q('CASH_INTENSIVE'), '-'], [q('HIGH'), '2', 'true'], 'cash-intensive business'),
            (['-', q('SELF_EMPLOYED'), '-'], [q('MEDIUM'), '8', 'false'], ''),
            (['-', '-', '-'], [q('LOW'), '10', 'false'], 'periodic updation 2 / 8 / 10 years (KYC MD para 38)')])
    authority(pk, 'sec-bank-ao-checker', 'Account opening checker', [
        (q('HIGH'), 'BRANCHHEAD', 'high risk: branch head'), ('-', 'OPSCHECKER', 'operations checker')],
        var='riskCategory', label='Risk category', typ='string')
    powers(pk, 'sec-bank-ao-powers', [('OPSCHECKER', 1000000000, 'operations checker'),
                                      ('BRANCHHEAD', 1000000000, 'branch head')])
    simple_seeds(pk, 'BRANCH-001', [
        ('OPS-MAKER', 'OPSMAKER', 'OPS-CHECKER', 'Operations maker', 'संचालन निर्माता'),
        ('OPS-CHECKER', 'OPSCHECKER', 'BRANCH-HEAD', 'Operations checker', 'संचालन जाँचकर्ता'),
        ('VCIP-OFFICIAL', 'VCIP', 'BRANCH-HEAD', 'V-CIP official', 'वी-सीआईपी अधिकारी'),
        ('AML-COMPLIANCE', 'COMPLIANCE', None, 'AML compliance officer', 'धन-शोधन निवारण अनुपालन अधिकारी'),
        ('BRANCH-HEAD', 'BRANCHHEAD', None, 'Branch head', 'शाखा प्रमुख')], 'bao')
    pk.accept('SEC-BANK-AO-1', 'V-CIP dropped call is rescheduled, not failed', ['SEC-BANK-002'],
              'kycMode = VCIP', 'the official records reschedule, then pass', 'a second V-CIP task; account opened')
    pk.accept('SEC-BANK-AO-2', 'Screening true match never opens the account', ['SEC-BANK-005'],
              'sim_sanctions_screen = hit=true', 'compliance records reject', 'rejection with SANCTIONS_MATCH',
              vars={'sim_sanctions_screen': 'hit=true'})


def bank_rekyc():
    pk = spack('bank', 'rekyc', 'Periodic updation of KYC (re-KYC)', 'केवाईसी का आवधिक अद्यतन (पुनः केवाईसी)',
               'Banking',
               'Monthly campaign: every customer due by risk category (2 / 8 / 10 years) gets its own case (GP-09); '
               'advance intimation and reminders, self-declaration (no change) through any channel or fresh KYC '
               '(V-CIP allowed); the decision to restrict an account after notice is never automated (KYC MD, '
               'RBI FAQ June 2025); CKYCR is updated.',
               ['SEC-BANK-007', 'SEC-BANK-008', 'UP-15', 'GP-30', 'GP-08', 'GP-09'],
               [('cbs.kycDue', 'a', '', 'Customers due for periodic updation'),
                ('ckycr.update', 'a', '', 'CKYCR update'), ('cbs.restrict', 'a', '', 'Partial freeze after notice')])
    p = pk.proc('sangam-sec-bank-rekyc-campaign', 'Re-KYC campaign', '', start={'materialFields': 'period'})
    s = p.root
    t = s.start('rk_timer', 'Monthly', timer_cycle='0 0 7 2 * ?', initiator=None)
    ld = s.svc('rk_load', 'Customers due', C['BATCH'], {'op': 'LOAD', 'connector': 'cbs.kycDue', 'sampleSize': '3'},
               async_=True)
    s.flow(t, ld)
    op, cg = batch_loop(p, s, 'rk', 'sangam-sec-bank-rekyc-customer', chunk=100, poll='PT1H')
    s.flow(ld, op)
    s.flow(cg, s.end('rk_end', 'Campaign closed'), None, 'complete', default=True)
    c = pk.proc('sangam-sec-bank-rekyc-customer', 'Re-KYC of one customer', '', start={'materialFields': 'key'})
    s = c.root
    a1 = s.start('rkc_start', 'Customer due', initiator=None)
    d = s.setv('rkc_defaults', 'Configuration', defaults='reminderGap=P30D;reviewSeat=OPS-CHECKER;changeInDetails=false;'
               'applicantRef=@key')
    n1 = s.notify('rkc_intimate', 'Advance intimation', 'rekyc-due', 'sms,email,inapp', 'applicantRef')
    w1 = s.egw('rkc_wait1', 'Response or reminder')
    s.seq(a1, d, n1, w1)
    r1 = s.catch_msg('rkc_resp1', 'Self-declaration / documents received', 'sangam.rekyc.response')
    t1 = s.catch_timer('rkc_rem1', 'Reminder due', duration='${reminderGap}')
    s.flow(w1, r1)
    s.flow(w1, t1)
    n2 = s.notify('rkc_remind', 'Reminder after the due date', 'rekyc-reminder', 'sms,email,letter', 'applicantRef')
    w2 = s.egw('rkc_wait2', 'Response or notice period ends')
    s.seq(t1, n2, w2)
    r2 = s.catch_msg('rkc_resp2', 'Response received', 'sangam.rekyc.response')
    t2 = s.catch_timer('rkc_noResp', 'Notice period over', duration='${reminderGap}')
    s.flow(w2, r2)
    s.flow(w2, t2)
    vj = s.xgw('rkc_respJoin')
    s.flow(r1, vj)
    s.flow(r2, vj)
    cg2 = s.xgw('rkc_changeGw', 'Change in KYC details?')
    s.flow(vj, cg2)
    ver = s.user('rkc_verify', 'Verify fresh KYC documents | नए केवाईसी दस्तावेज़ों का सत्यापन', '${reviewSeat}',
                 form='sec-bank-rekyc/verify', decision=('rekycDecision', ['accept', 'clarify']),
                 fields=['documents', 'remarks'], sod=True)
    s.flow(cg2, ver, 'changeInDetails', 'changed: fresh KYC')
    vg = s.xgw('rkc_verGw')
    s.flow(ver, vg)
    s.flow(vg, w2, "rekycDecision == 'clarify'", 'ask again')
    upd = s.conn('rkc_update', 'Update CKYCR and risk profile', 'ckycr.update', 'update', 'submit', 'key', 'ckycUpd',
                 step='ckyc-update')
    s.flow(vg, upd, None, 'accepted', default=True)
    s.flow(cg2, upd, None, 'self-declaration: no change', default=True)
    s.flow(upd, c.root.end('rkc_end', 'KYC updated', listeners=[c.item_listener('SUCCESS')]))
    dec = s.user('rkc_decide', 'Decide account action (not automated) | खाते पर कार्रवाई का निर्णय (स्वचालित नहीं)',
                 '${reviewSeat}', form='sec-bank-rekyc/decide', decision=('restrictDecision', ['restrict', 'extend']),
                 fields=['remarks*'], sod=True)
    s.flow(t2, dec)
    dg = s.xgw('rkc_decGw')
    s.flow(dec, dg)
    s.flow(dg, w2, "restrictDecision == 'extend'", 'extend (customer contacted)')
    rs = s.conn('rkc_restrict', 'Partial freeze after due notice', 'cbs.restrict', 'partial-freeze', 'submit', 'key',
                'restrict', step='restrict')
    s.flow(dg, rs, None, 'restrict', default=True)
    s.flow(rs, s.end('rkc_endRestricted', 'Restricted pending KYC', listeners=[c.item_listener('RESTRICTED')]))
    simple_seeds(pk, 'BRANCH-RK', [('OPS-CHECKER', 'OPSCHECKER', None, 'Operations checker', 'संचालन जाँचकर्ता')], 'brk')


def bank_loan_origination():
    pk = spack('bank', 'loan-origination', 'Loan origination to disbursal', 'ऋण आवेदन से संवितरण तक', 'Banking',
               'Retail / MSME loan: consent, KYC status, bureau pull, indicative FOIR, credit policy by rule (auto '
               'decline with reasons), appraisal with clarification, legal and valuation in parallel for secured '
               'loans, committee above the band, sanction by delegated power with SoD and payload binding, Key Facts '
               'Statement with APR and a validity window, e-signed agreement, disbursal to the borrower account '
               'with operationId, loan booked in the LMS.',
               ['SEC-BANK-010', 'SEC-BANK-011', 'SEC-BANK-012', 'SEC-BANK-013', 'SEC-BANK-014', 'GP-01', 'GP-03',
                'GP-05', 'GP-12', 'GP-34', 'UP-02', 'UP-05'] + SIDE,
               [('cbs.kycStatus', 'a', '', 'KYC status of the customer'), ('bureau.pull', 'a', '', 'Credit bureau (CIC)'),
                ('docs.kfs', 'a', '', 'Key Facts Statement with APR'), ('bank.transfer', 'c', '', 'Disbursal'),
                ('lms.book', 'a', '', 'Book the loan in the LMS')])
    p = pk.proc('sangam-sec-bank-loan-origination', 'Loan origination', '',
                start={'materialFields': 'customerId,loanProduct,amount,tenureMonths,roiPct'})
    s = p.root
    st = start_form(p, s, 'lo_start', 'Loan application', 'sec-bank-loan-origination/application',
                    'Loan application | ऋण आवेदन',
                    ['customerId*', 'loanProduct*', 'amount*', 'tenureMonths*', 'monthlyIncome*', 'existingEmi',
                     'collateralType', 'consentGiven*', 'applicantRef', 'documents'])
    d = s.setv('lo_defaults', 'Configuration',
               defaults='loanProduct=PERSONAL;tenureMonths=36;monthlyIncome=50000;existingEmi=0;collateralType=NONE;'
                        'consentGiven=true;kycCompliant=true;bureauScore=750;roiPct=12;caseType=LOAN;committeeThreshold=50000000;'
                        'routingDecisionKey=sec-bank-loan-authority;powersDecisionKey=sec-bank-loan-powers;'
                        'signatureRequired=true;committeeSeats=CREDIT-COMM-1,CREDIT-COMM-2,CREDIT-COMM-3;'
                        'quorumRule=MAJORITY;analystSeat=CREDIT-ANALYST;kfsValidity=3WD;'
                        'executionConnector=bank.transfer;executionStep=disburse;reconcileRequired=true;'
                        'executionPayloadFields=customerId,amount;outputTemplate=sanction-letter;appealAllowed=false')
    cg = s.xgw('lo_consentGw', 'Consent for bureau pull?')
    s.seq(st, d, cg)
    s.flow(cg, s.end('lo_endNoConsent', 'Stopped: no consent'), '!consentGiven', 'no')
    ky = s.conn('lo_kyc', 'KYC status', 'cbs.kycStatus', 'status', 'query', 'customerId', 'kyc')
    s.flow(cg, ky, None, 'yes', default=True)
    kg = s.xgw('lo_kycGw', 'KYC compliant?')
    s.flow(ky, kg)
    s.flow(kg, reject_with(p, s, 'lo_rjKyc', 'KYC_NOT_COMPLIANT'), '!kycCompliant', 'no')
    bu = s.conn('lo_bureau', 'Credit bureau report', 'bureau.pull', 'report', 'query', 'customerId', 'bureau')
    s.flow(kg, bu, None, 'yes', default=True)
    fo = s.expr('lo_foir', 'Indicative FOIR',
                "${execution.setVariable('foirPct', (existingEmi + amount / tenureMonths) * 100 / monthlyIncome)}")
    pol = s.rule('lo_policy', 'Credit policy (DMN)', 'sec-bank-credit-policy')
    pg = s.xgw('lo_policyGw', 'Policy result?')
    s.seq(bu, fo, pol, pg)
    s.flow(pg, reject_with(p, s, 'lo_rjPolicy', 'CREDIT_POLICY_DECLINE'), "creditDecision == 'DECLINE'",
           'decline (reasons communicated)')
    ae, ad = s.sla_task('lo_appraise', 'Credit appraisal | ऋण मूल्यांकन', 'analystSeat',
                        form='sec-bank-loan-origination/appraisal',
                        decision=('appraisalDecision', ['recommend', 'clarify', 'reject']),
                        fields=['bureauScore', 'foirPct', 'remarks*', 'rejectionReasonCodes'], spec='3WD', sod=True)
    s.flow(pg, ae, None, 'eligible / refer', default=True)
    agw = s.xgw('lo_apprGw')
    s.flow(ad, agw)
    ask = s.notify('lo_ask', 'Ask the applicant for documents', 'loan-clarification', 'sms,email', 'applicantRef')
    rcv = s.catch_msg('lo_clar', 'Documents received', 'sangam.clarificationReceived')
    s.flow(agw, ask, "appraisalDecision == 'clarify'", 'clarify')
    s.seq(ask, rcv, ae)
    rej = rejection(p, s, 'lo_rj')
    s.flow(agw, rej, "appraisalDecision == 'reject'", 'reject')
    secg = s.xgw('lo_secGw', 'Secured?')
    s.flow(agw, secg, None, 'recommend', default=True)
    fk = s.pgw('lo_fork', 'Legal and valuation')
    jn = s.pgw('lo_join')
    s.flow(secg, fk, "collateralType != 'NONE'", 'secured')
    for sid, en, hi, fl in (('LEGAL-OFFICER', 'Legal scrutiny of title', 'स्वत्व की विधिक संवीक्षा', 'titleClear*'),
                            ('VALUER', 'Valuation of collateral', 'प्रतिभूति का मूल्यांकन', 'collateralValue*')):
        t = s.user('lo_' + sid.lower().replace('-', ''), f'{en} | {hi}', sid,
                   form='sec-bank-loan-origination/' + sid.lower(), decision=None, fields=[fl, 'remarks'])
        s.flow(fk, t)
        s.flow(t, jn)
    cmg = s.xgw('lo_commGw', 'Above the committee band?')
    s.flow(jn, cmg)
    s.flow(secg, cmg, None, 'unsecured', default=True)
    com = core(s, 'lo_committee', 'Credit committee (core committee)', 'committee')
    san = core(s, 'lo_sanction', 'Sanction by delegated power (core sanction, SoD, signature)', 'sanction')
    s.flow(cmg, com, 'amount > committeeThreshold', 'above 5 crore (committeeThreshold, minor units)')
    s.flow(cmg, san, None, 'within delegated power', default=True)
    ccg = s.xgw('lo_commRes')
    s.flow(com, ccg)
    s.flow(ccg, san, 'committeeApproved', 'approved')
    s.flow(ccg, reject_with(p, s, 'lo_rjComm', 'COMMITTEE_DECLINED'), None, 'declined', default=True)
    kfs = s.conn('lo_kfs', 'Issue Key Facts Statement (APR, fees, recovery, grievance officer)', 'docs.kfs', 'issue',
                 'submit', 'customerId,amount,tenureMonths,roiPct', 'kfs', step='kfs')
    outcome(s, 'lo_sanGw', 'sanctionOutcome', {'SANCTIONED': kfs, 'RETURNED': ae}, rej)
    s.flow(san, 'lo_sanGw')
    be, bd = s.sla_task('lo_accept', 'Borrower accepts KFS and sanction terms | उधारकर्ता द्वारा केएफएस एवं शर्तों की स्वीकृति',
                        'borrowerGroup', form='sec-bank-loan-origination/accept',
                        decision=('kfsDecision', ['accept', 'declined']), fields=['remarks'], spec='${kfsValidity}',
                        on_breach='DEEMED_REFUSAL', approve='accept', refuse='declined', max_esc=0)
    bgp = s.expr('lo_borrower', 'Borrower portal group',
                 "${execution.setVariable('borrowerGroup', 'external:'.concat(customerId))}")
    s.seq(kfs, bgp, be)
    bg = s.xgw('lo_acceptGw', 'Accepted within validity?')
    s.flow(bd, bg)
    s.flow(bg, s.end('lo_endDeclined', 'Offer lapsed / declined'), "kfsDecision != 'accept'", 'declined / lapsed')
    sign = s.svc('lo_agreement', 'E-sign loan agreement over the sanctioned terms', C['SIGN'],
                 {'op': 'REQUEST', 'purpose': 'loan-agreement'}, async_=True)
    s.flow(bg, sign, None, 'accepted', default=True)
    ex = core(s, 'lo_disburse', 'Disburse to the borrower account (core execution)', 'execution')
    s.flow(sign, ex)
    bk = s.conn('lo_book', 'Book loan in LMS (repayment schedule)', 'lms.book', 'book', 'submit',
                'customerId,amount,tenureMonths,roiPct', 'lms', step='book')
    fail = s.notify('lo_disbFailed', 'Disbursal failed', 'disbursal-failed', 'inapp', 'initiatorSeat')
    outcome(s, 'lo_exGw', 'executionOutcome', {'DONE': bk}, fail)
    s.flow(ex, 'lo_exGw')
    s.flow(fail, san)
    cl = core(s, 'lo_close', 'Sanction letter and closure (core)', 'closure')
    s.seq(bk, cl, end_ok(p, s, 'lo_end', 'Disbursed'))
    s.side_exits('lo')
    pk.dmn('sec-bank-credit-policy', 'Credit policy (SYNTHETIC)', 'FIRST',
           [('Bureau score', 'bureauScore', 'integer'), ('FOIR %', 'foirPct', 'number')],
           [('creditDecision', 'Decision', 'string'), ('kfsValidity', 'KFS validity', 'string')],
           [(['< 650', '-'], [q('DECLINE'), q('3WD')], 'score below cut-off'),
            (['-', '> 60'], [q('DECLINE'), q('3WD')], 'FOIR above 60%'),
            (['>= 750', '<= 50'], [q('ELIGIBLE'), q('3WD')], ''),
            (['-', '-'], [q('REFER'), q('3WD')], 'manual appraisal; KFS valid 3 working days (tenor 7 days or more)')])
    authority(pk, 'sec-bank-loan-authority', 'Loan sanctioning authority', [
        ('<= 1000000', 'BRANCHMGR', 'up to 10 lakh'), ('<= 50000000', 'ZONALCREDIT', 'up to 5 crore'),
        ('-', 'CREDITHEAD', 'above 5 crore, after committee')])
    powers(pk, 'sec-bank-loan-powers', [('BRANCHMGR', 1000000, ''), ('ZONALCREDIT', 50000000, ''),
                                        ('CREDITHEAD', 5000000000, '')])
    simple_seeds(pk, 'BRANCH-LO', [
        ('CREDIT-ANALYST', 'ANALYST', 'BRANCH-MGR', 'Credit analyst', 'ऋण विश्लेषक'),
        ('LEGAL-OFFICER', 'LEGAL', 'BRANCH-MGR', 'Legal officer', 'विधि अधिकारी'),
        ('VALUER', 'VALUER', 'BRANCH-MGR', 'Empanelled valuer', 'पैनलबद्ध मूल्यांकक'),
        ('BRANCH-MGR', 'BRANCHMGR', 'ZONAL-CREDIT', 'Branch manager', 'शाखा प्रबंधक'),
        ('ZONAL-CREDIT', 'ZONALCREDIT', 'CREDIT-HEAD', 'Zonal credit head', 'क्षेत्रीय ऋण प्रमुख'),
        ('CREDIT-HEAD', 'CREDITHEAD', None, 'Chief credit officer', 'मुख्य ऋण अधिकारी'),
        ('CREDIT-COMM-1', 'COMMITTEE', None, 'Credit committee member 1', 'ऋण समिति सदस्य 1'),
        ('CREDIT-COMM-2', 'COMMITTEE', None, 'Credit committee member 2', 'ऋण समिति सदस्य 2'),
        ('CREDIT-COMM-3', 'COMMITTEE', None, 'Credit committee member 3', 'ऋण समिति सदस्य 3')], 'blo')


def bank_collections():
    pk = spack('bank', 'collections', 'Collections, SMA / NPA, restructuring, OTS and SARFAESI',
               'वसूली, एसएमए / एनपीए, पुनर्संरचना, एकमुश्त निपटान एवं सरफेसी', 'Banking',
               'One case per overdue loan (from the LMS end-of-day): SMA-0/1/2 and NPA classification by days past '
               'due, CRILC reporting for large exposures, reminders on registered templates, contact by a recovery '
               'agent within permitted hours, promise to pay, restructuring or one-time settlement sanctioned with '
               'maker-checker, SARFAESI s.13(2) demand notice with the 60-day window and the reply to a '
               'representation within 15 days, s.13(4) measures.',
               ['SEC-BANK-015', 'SEC-BANK-016', 'SEC-BANK-017', 'SEC-BANK-018', 'GP-01', 'GP-06', 'GP-46', 'UP-04'],
               [('crilc.report', 'a', '', 'CRILC / CIC reporting'), ('lms.status', 'a', '', 'Loan status'),
                ('lms.settle', 'a', '', 'Post settlement / restructuring'),
                ('docs.notice', 'a', '', 'Demand / possession notice')])
    p = pk.proc('sangam-sec-bank-collections', 'Collections case', '',
                start={'materialFields': 'loanAccountNo,customerId,amount,dpd'})
    s = p.root
    st = s.start('co_start', 'Loan overdue (LMS end of day)', msg='sangam.loan.overdue', initiator=None)
    d = s.setv('co_defaults', 'Configuration',
               defaults='dpd=1;amount=0;crilcThreshold=50000000;loanAccountNo=@customerId;ptpDays=P7D;agentPool=COLL-AGENT-1,COLL-AGENT-2;'
                        'caseType=SETTLEMENT;routingDecisionKey=sec-bank-ots-authority;'
                        'powersDecisionKey=sec-bank-ots-powers;loanCured=false;appealAllowed=false;'
                        'applicantRef=@customerId')
    cls = s.rule('co_class', 'SMA / NPA classification (DMN)', 'sec-bank-sma')
    s.seq(st, d, cls)
    cr = s.conn('co_crilc', 'Report to CRILC / CICs', 'crilc.report', 'report', 'submit', 'loanAccountNo,smaClass,amount',
                'crilc', step='crilc')
    g1, m1 = maybe(s, 'co_crilcOpt', 'amount >= crilcThreshold', cr, cr, 'Exposure 5 crore or more?')
    s.flow(cls, g1)
    rem = s.notify('co_remind', 'Overdue reminder', 'loan-overdue', 'sms,email', 'applicantRef')
    asg = s.svc('co_assign', 'Allocate collection agent', C['ASSIGN'], {'strategy': 'LEAST_LOADED',
                                                                        'candidatesVar': 'agentPool',
                                                                        'resultVar': 'agentSeat'})
    s.seq(m1, rem, asg)
    ce, cd = s.sla_task('co_contact', 'Contact borrower (08:00-19:00, agent identity disclosed) | उधारकर्ता से संपर्क (08:00-19:00)',
                        'agentSeat', form='sec-bank-collections/contact',
                        decision=('collectDecision', ['promise', 'restructure', 'settle', 'legal', 'paid']),
                        fields=['contactOutcome*', 'promiseDate', 'remarks'], spec='3WD')
    s.flow(asg, ce)
    g = s.xgw('co_gw', 'Outcome?')
    s.flow(cd, g)
    s.flow(g, end_ok(p, s, 'co_endPaid', 'Regularised'), "collectDecision == 'paid'", 'paid')
    ptp = s.catch_timer('co_ptp', 'Promise-to-pay date', duration='${ptpDays}')
    chk = s.conn('co_status', 'Check repayment', 'lms.status', 'status', 'query', 'loanAccountNo', 'loan')
    pg = s.xgw('co_curedGw', 'Cured?')
    s.flow(g, ptp, "collectDecision == 'promise'", 'promise to pay')
    s.seq(ptp, chk, pg)
    s.flow(pg, end_ok(p, s, 'co_endCured', 'Cured'), 'loanCured', 'yes')
    s.flow(pg, cls, None, 'broken promise: reclassify', default=True)
    sanR = core(s, 'co_restructure', 'Restructuring sanction (core sanction, SoD)', 'sanction')
    s.flow(g, s.setv('co_kindR', 'Kind: restructuring', values='caseType=RESTRUCTURING'), "collectDecision == 'restructure'",
           'restructure')
    s.flow('co_kindR', sanR)
    sanO = core(s, 'co_ots', 'One-time settlement sanction (core sanction, SoD)', 'sanction')
    s.flow(g, s.setv('co_kindO', 'Kind: settlement', values='caseType=SETTLEMENT'), "collectDecision == 'settle'",
           'one-time settlement')
    s.flow('co_kindO', sanO)
    post = s.conn('co_post', 'Post restructuring / settlement terms in the LMS', 'lms.settle', 'settle', 'submit',
                  'loanAccountNo,amount,caseType', 'settle', step='settle')
    for sn in (sanR, sanO):
        outcome(s, sn + 'Gw', 'sanctionOutcome', {'SANCTIONED': post}, ce)
        s.flow(sn, sn + 'Gw')
    s.flow(post, end_ok(p, s, 'co_endSettled', 'Settled / restructured'))
    n132 = s.conn('co_notice132', 'SARFAESI s.13(2) demand notice', 'docs.notice', 's13-2', 'submit',
                  'loanAccountNo,amount', 'n132', step='s13-2')
    s.flow(g, n132, None, 'legal (NPA, secured)', default=True)
    w = s.egw('co_window', 'Representation or 60 days')
    s.flow(n132, w)
    rep = s.catch_msg('co_rep', 'Borrower representation', 'sangam.borrower.representation')
    t60 = s.catch_timer('co_60d', '60 days over', duration='P60D')
    s.flow(w, rep)
    s.flow(w, t60)
    re_, rd = s.sla_task('co_reply', 'Reply to representation with reasons (15 days) | अभ्यावेदन का सकारण उत्तर (15 दिन)',
                         'legalSeat', form='sec-bank-collections/reply', decision=None, fields=['replyText*'],
                         spec='15D')
    s.flow(rep, s.setv('co_legal', 'Authorised officer', defaults='legalSeat=AUTHORISED-OFFICER'))
    s.flow('co_legal', re_)
    nx = s.user('co_next', 'Measures under s.13(4)? | धारा 13(4) के अंतर्गत कार्रवाई?', 'AUTHORISED-OFFICER',
                form='sec-bank-collections/measures', decision=('measureDecision', ['proceed', 'settle', 'drop']),
                fields=['remarks*'], sod=True)
    s.flow(rd, nx)
    s.flow(t60, nx)
    ng = s.xgw('co_nextGw')
    s.flow(nx, ng)
    s.flow(ng, 'co_kindO', "measureDecision == 'settle'", 'settle')
    n134 = s.conn('co_notice134', 'Possession notice s.13(4)', 'docs.notice', 's13-4', 'submit', 'loanAccountNo',
                  'n134', step='s13-4')
    s.flow(ng, n134, "measureDecision == 'proceed'", 'proceed')
    s.flow(n134, s.end('co_endPossession', 'Recovery under SARFAESI'))
    s.flow(ng, s.end('co_endDropped', 'Legal action dropped'), None, 'drop', default=True)
    ev = s.evsub('co_closedSub', 'Loan closed during collections')
    e1 = ev.start('co_closedStart', 'Paid in full', msg='sangam.loan.closed', interrupting=True)
    ev.seq(e1, ev.end('co_closedEnd', 'Closed'))
    pk.dmn('sec-bank-sma', 'SMA / NPA classification by days past due', 'FIRST', [('Days past due', 'dpd', 'integer')],
           [('smaClass', 'Class', 'string')],
           [(['<= 30'], [q('SMA-0')], '1-30 days'), (['<= 60'], [q('SMA-1')], '31-60 days'),
            (['<= 90'], [q('SMA-2')], '61-90 days'), (['-'], [q('NPA')], 'more than 90 days (IRACP)')])
    authority(pk, 'sec-bank-ots-authority', 'Settlement / restructuring authority', [
        ('<= 1000000', 'BRANCHMGR', ''), ('<= 100000000', 'ZONALCREDIT', ''), ('-', 'CREDITHEAD', 'board committee')])
    powers(pk, 'sec-bank-ots-powers', [('BRANCHMGR', 1000000, ''), ('ZONALCREDIT', 100000000, ''),
                                       ('CREDITHEAD', 5000000000, '')])
    simple_seeds(pk, 'COLLECTIONS', [
        ('COLL-AGENT-1', 'AGENT', 'COLL-SUPERVISOR', 'Collection agent 1', 'वसूली एजेंट 1'),
        ('COLL-AGENT-2', 'AGENT', 'COLL-SUPERVISOR', 'Collection agent 2', 'वसूली एजेंट 2'),
        ('COLL-SUPERVISOR', 'SUPERVISOR', 'BRANCH-MGR', 'Collections supervisor', 'वसूली पर्यवेक्षक'),
        ('AUTHORISED-OFFICER', 'AO', 'BRANCH-MGR', 'Authorised officer (SARFAESI)', 'प्राधिकृत अधिकारी (सरफेसी)'),
        ('BRANCH-MGR', 'BRANCHMGR', 'ZONAL-CREDIT', 'Branch manager', 'शाखा प्रबंधक'),
        ('ZONAL-CREDIT', 'ZONALCREDIT', 'CREDIT-HEAD', 'Zonal credit head', 'क्षेत्रीय ऋण प्रमुख'),
        ('CREDIT-HEAD', 'CREDITHEAD', None, 'Chief credit officer', 'मुख्य ऋण अधिकारी')], 'bco')


def bank_loan_closure():
    pk = spack('bank', 'loan-closure', 'Loan closure and release of property documents',
               'ऋण समापन एवं संपत्ति दस्तावेज़ों की वापसी', 'Banking',
               'On full repayment or settlement: closure check, NOC, original documents released and charges '
               'removed (CERSAI / ROC) within 30 days; compensation of Rs 5,000 per day of delay attributable to the '
               'lender, paid once by operationId; lost documents: certified copies at the lender\'s cost within a '
               'further 30 days (RBI, 13 September 2023).',
               ['SEC-BANK-019', 'GP-06', 'GP-12', 'GP-53'],
               [('lms.closureCheck', 'a', '', 'Dues outstanding'), ('cersai.satisfy', 'a', '', 'Satisfaction of charge'),
                ('docs.noc', 'a', '', 'No-dues certificate'), ('bank.credit', 'c', '', 'Compensation credit')])
    p = pk.proc('sangam-sec-bank-loan-closure', 'Loan closure', '', start={'materialFields': 'loanAccountNo,customerId'})
    s = p.root
    st = start_form(p, s, 'lc_start', 'Loan repaid / settled', 'sec-bank-loan-closure/request',
                    'Loan closure | ऋण समापन', ['loanAccountNo*', 'customerId*', 'applicantRef'])
    d = s.setv('lc_defaults', 'Configuration',
               defaults='loanAccountNo=@customerId;closureDuesNil=true;compensationDue=false;docsSeat=DOCS-CUSTODIAN;'
                        'compensationAmount=0;delayDays=0;applicantRef=@customerId;'
                        'executionConnector=bank.credit;executionStep=delay-compensation;verifyApproval=false;'
                        'reconcileRequired=false;executionPayloadFields=customerId,compensationAmount')
    ck = s.conn('lc_check', 'Closure check', 'lms.closureCheck', 'check', 'query', 'loanAccountNo', 'closure')
    g = s.xgw('lc_gw', 'Dues nil?')
    s.seq(st, d, ck, g)
    s.flow(g, s.end('lc_endDues', 'Dues outstanding: not closed'), '!closureDuesNil', 'no')
    clk = clock(s, 'lc_clock', 'release', '30D')
    s.flow(g, clk, None, 'yes', default=True)
    re_ = s.user('lc_release', 'Release original documents and NOC | मूल दस्तावेज़ एवं अनापत्ति प्रमाणपत्र लौटाएँ',
                 '${docsSeat}', form='sec-bank-loan-closure/release', decision=('releaseDecision', ['released', 'docsLost']),
                 fields=['documents', 'physicalReceived', 'remarks'], due='${releaseDueAt}')
    s.flow(clk, re_)
    late = s.boundary('lc_late', re_, '30 days passed', timer_date='${releaseDueAt}', cancel=False)
    lf = s.setv('lc_lateFlag', 'Delay recorded', values='compensationDue=true')
    s.flow(late, lf)
    s.flow(lf, s.end('lc_lateEnd', 'Delay recorded'))
    rg = s.xgw('lc_relGw')
    s.flow(re_, rg)
    cp = s.user('lc_duplicate', 'Obtain certified copies at the bank\'s cost | बैंक के व्यय पर प्रमाणित प्रतियाँ प्राप्त करें',
                '${docsSeat}', form='sec-bank-loan-closure/duplicate', decision=None, fields=['documents*', 'remarks'])
    s.flow(rg, cp, "releaseDecision == 'docsLost'", 'lost / damaged')
    sat = s.conn('lc_satisfy', 'Remove charge (CERSAI / ROC)', 'cersai.satisfy', 'satisfy', 'submit', 'loanAccountNo',
                 'cersai', step='satisfy')
    s.flow(rg, sat, None, 'released', default=True)
    s.flow(cp, sat)
    noc = s.conn('lc_noc', 'Issue NOC', 'docs.noc', 'issue', 'submit', 'loanAccountNo,customerId', 'noc', step='noc')
    s.flow(sat, noc)
    comp = s.user('lc_compute', 'Compute compensation (Rs 5,000 / day, delay attributable) | क्षतिपूर्ति की गणना',
                  'BRANCH-MGR', form='sec-bank-loan-closure/compensation', decision=None,
                  fields=['delayDays*', 'compensationAmount*', 'remarks'], sod=True)
    g2 = s.xgw('lc_compGw', 'Released late?')
    m2 = s.xgw('lc_compMerge')
    s.flow(noc, g2)
    s.flow(g2, comp, 'compensationDue', 'yes')
    s.flow(g2, m2, None, 'no', default=True)
    pay = core(s, 'lc_pay', 'Credit compensation (core execution)', 'execution')
    pg = s.xgw('lc_payGw', 'Attributable to the bank?')
    s.flow(comp, pg)
    s.flow(pg, pay, 'compensationAmount > 0', 'amount due')
    s.flow(pg, m2, None, 'not attributable', default=True)
    s.flow(pay, m2)
    s.flow(m2, end_ok(p, s, 'lc_end', 'Closed'))
    simple_seeds(pk, 'BRANCH-LC', [('DOCS-CUSTODIAN', 'CUSTODIAN', 'BRANCH-MGR', 'Documents custodian', 'दस्तावेज़ अभिरक्षक'),
                                   ('BRANCH-MGR', 'BRANCHMGR', None, 'Branch manager', 'शाखा प्रबंधक')], 'blc')


def bank_payment():
    pk = spack('bank', 'payment-dual-auth', 'Payment initiation with maker, checker and authoriser',
               'निर्माता, जाँचकर्ता एवं प्राधिकारी सहित भुगतान प्रारंभ', 'Banking',
               'Corporate / treasury payment (NEFT, RTGS, IMPS, SWIFT): beneficiary name validation, sanctions '
               'screening, rail limits by rule (RTGS minimum, IMPS cap), checker and a second authoriser above the '
               'band with SoD by person and seat and approval bound to the payload hash; the transfer is class (c): '
               'lost response goes to UNKNOWN_OUTCOME and reconciliation with the statement; returns credited back.',
               ['SEC-BANK-020', 'SEC-BANK-021', 'SEC-BANK-022', 'UP-05', 'GP-01', 'GP-12', 'GP-34', 'AC-02'] + SIDE,
               [('bene.validate', 'a', '', 'Beneficiary name lookup'), ('sanctions.screen', 'a', '', 'Screening'),
                ('bank.transfer', 'c', '', 'Payment rail'), ('cbs.credit', 'a', '', 'Credit back a returned payment')])
    p = pk.proc('sangam-sec-bank-payment', 'Payment with dual authorisation', '',
                start={'materialFields': 'payee,account,ifsc,amount,paymentRail'})
    s = p.root
    st = start_form(p, s, 'py_start', 'Payment initiated (maker)', 'sec-bank-payment-dual-auth/initiate',
                    'Payment initiation | भुगतान प्रारंभ',
                    ['payee*', 'account*', 'ifsc*', 'amount*', 'paymentRail*', 'purposeCode', 'remarks'])
    d = s.setv('py_defaults', 'Configuration',
               defaults='paymentRail=NEFT;purposeCode=P0101;beneNameMatch=true;screenHit=false;caseType=PAYMENT;'
                        'routingDecisionKey=sec-bank-pay-checker;powersDecisionKey=sec-bank-pay-powers;'
                        'executionConnector=bank.transfer;executionStep=pay;reconcileRequired=true;'
                        'executionPayloadFields=payee,account,ifsc,amount,paymentRail;outputTemplate=payment-advice;'
                        'appealAllowed=false;complianceSeat=AML-COMPLIANCE')
    bv = s.conn('py_bene', 'Validate beneficiary name', 'bene.validate', 'lookup', 'query', 'account,ifsc,payee', 'bene')
    s.seq(st, d, bv)
    bg = s.xgw('py_beneGw', 'Name matches?')
    s.flow(bv, bg)
    rv = revise(p, s, 'py_correct', 'Correct beneficiary details | लाभार्थी विवरण सुधारें', ['payee*', 'account*', 'ifsc*'],
                bv)
    s.flow(bg, rv, '!beneNameMatch', 'mismatch')
    sc = s.conn('py_screen', 'Screen payee', 'sanctions.screen', 'screen', 'query', 'payee', 'screen')
    s.flow(bg, sc, None, 'match', default=True)
    sg = s.xgw('py_screenGw', 'Hit?')
    s.flow(sc, sg)
    cr = s.user('py_compliance', 'Review screening hit | स्क्रीनिंग मिलान की समीक्षा', '${complianceSeat}',
                form='sec-bank-payment-dual-auth/screening', decision=('screenDecision', ['falsePositive', 'reject']),
                fields=['screenMatchDetails', 'remarks*'], sod=True)
    s.flow(sg, cr, 'screenHit', 'hit')
    lim = s.rule('py_limits', 'Rail limits and authorisation levels (DMN)', 'sec-bank-pay-limits')
    s.flow(sg, lim, None, 'clear', default=True)
    crg = s.xgw('py_compGw')
    s.flow(cr, crg)
    s.flow(crg, lim, "screenDecision == 'falsePositive'", 'false positive')
    s.flow(crg, reject_with(p, s, 'py_rjScreen', 'SANCTIONS_MATCH'), None, 'reject', default=True)
    lg = s.xgw('py_limGw', 'Rail allowed?')
    s.flow(lim, lg)
    s.flow(lg, rv, '!railAllowed', 'rail not permitted for this amount: correct')
    a1 = s.setv('py_auth1', 'Checker routing', values='routingDecisionKey=sec-bank-pay-checker')
    chk = core(s, 'py_checker', 'Checker (core sanction)', 'sanction')
    s.flow(lg, a1, None, 'allowed', default=True)
    s.flow(a1, chk)
    rej = rejection(p, s, 'py_rj')
    lvl = s.xgw('py_levelGw', 'Second authoriser?')
    outcome(s, 'py_chkGw', 'sanctionOutcome', {'SANCTIONED': lvl, 'RETURNED': rv}, rej)
    s.flow(chk, 'py_chkGw')
    a2 = s.setv('py_auth2', 'Authoriser routing', values='routingDecisionKey=sec-bank-pay-authoriser')
    auth = core(s, 'py_authoriser', 'Authoriser (core sanction, different person and seat)', 'sanction')
    s.flow(lvl, a2, 'authLevels > 1', 'above the band')
    s.flow(a2, auth)
    ex = core(s, 'py_pay', 'Transfer (core execution, class c)', 'execution')
    s.flow(lvl, ex, None, 'one level', default=True)
    outcome(s, 'py_authGw', 'sanctionOutcome', {'SANCTIONED': ex, 'RETURNED': rv}, rej)
    s.flow(auth, 'py_authGw')
    inv = s.boundary('py_inv', ex, 'Changed after approval', error='APPROVAL_INVALIDATED')
    s.flow(inv, a1)  # a changed payload is checked again from the checker level
    cl = core(s, 'py_close', 'Advice and closure (core)', 'closure')
    fail = s.notify('py_failed', 'Payment rejected by the rail', 'payment-failed', 'inapp', 'initiatorSeat')
    outcome(s, 'py_exGw', 'executionOutcome', {'DONE': cl}, fail)
    s.flow(ex, 'py_exGw')
    s.flow(fail, rv)
    s.flow(cl, end_ok(p, s, 'py_end', 'Paid'))
    ret = s.evsub('py_return', 'Payment returned by the beneficiary bank')
    r1 = ret.start('py_retStart', 'Return received', msg='sangam.payment.returned', interrupting=False)
    r2 = ret.conn('py_retCredit', 'Credit back the remitter', 'cbs.credit', 'credit', 'submit', 'account,amount',
                  'retCredit', step='return-credit')
    r3 = ret.notify('py_retNotify', 'Tell the maker', 'payment-returned', 'inapp,email', 'initiatorSeat')
    ret.seq(r1, r2, r3, ret.end('py_retEnd', 'Credited back'))
    s.side_exits('py')
    # SL-50, owner decision 2026-11-08 ("हाँ, रखो"): above the checker's own band the checker only concurs, and the
    # concurrence is capped at the band of the authoriser that covers this payment. One list feeds the limits table
    # (authBandMax), the authoriser routing and the powers table, so the three cannot drift apart.
    checker_alone = 2500000
    auth_bands = [('PAYAUTH', 100000000), ('TREASURYHEAD', 10000000000)]  # (role, band ceiling), ascending
    lows = [checker_alone] + [hi for _, hi in auth_bands[:-1]]
    pk.dmn('sec-bank-pay-limits', 'Rail limits and authorisation levels (SYNTHETIC bands)', 'FIRST',
           [('Rail', 'paymentRail', 'string'), ('Amount', 'amount', 'money')],
           [('railAllowed', 'Rail allowed', 'boolean'), ('authLevels', 'Authorisation levels', 'integer'),
            ('authBandMax', 'Ceiling of the authoriser band covering the amount (INR)', 'money')],
           [([q('RTGS'), '< 200000'], ['false', '1', '0'], 'RTGS minimum 2 lakh'),
            ([q('IMPS'), '> 500000'], ['false', '1', '0'], 'IMPS per-transaction cap 5 lakh'),
            (['-', '> %d' % auth_bands[-1][1]], ['true', '2', '0'],
             'above every authoriser band: no concurrence possible (SL-50)')]
           + [(['-', '> %d' % lo], ['true', '2', str(hi)], 'checker and authoriser; band of %s' % role)
              for (role, hi), lo in reversed(list(zip(auth_bands, lows)))]
           + [(['-', '-'], ['true', '1', '0'], 'checker alone')])
    authority(pk, 'sec-bank-pay-checker', 'Payment checker', [('-', 'PAYCHECKER', '')])
    authority(pk, 'sec-bank-pay-authoriser', 'Payment authoriser',
              [('<= %d' % hi, role, '') for role, hi in auth_bands[:-1]] + [('-', auth_bands[-1][0], '')])
    powers(pk, 'sec-bank-pay-powers', [
        ('PAYCHECKER', 'authBandMax', 'checker as first of two levels: concurrence capped at the band of the authoriser '
                                      'covering the amount (authBandMax, sec-bank-pay-limits; owner decision SL-50, '
                                      '2026-11-08); the own power of that authoriser is checked again at the second level',
         '> 1'),
        ('PAYCHECKER', checker_alone, 'checker up to 25 lakh alone')] + [(r, hi, '') for r, hi in auth_bands],
           doc='K6: checker power covers single-level payments; above it the checker concurs up to the band of the '
               'authoriser covering the amount and that authoriser approves (SEC-BANK-PY-1, SL-50).')
    for d in ('sec-bank-pay-limits', 'sec-bank-pay-powers'):
        pk.placeholder(d, 'SYNTHETIC band amounts in rupees (checker alone 2500000; PAYAUTH 100000000; TREASURYHEAD '
                          '10000000000), shipped in paise (x100, money.py)',
                       'tenant-specific band amounts: the concurrence rule itself is decided (owner, SL-50, 2026-11-08: '
                       'checker concurrence capped at the band of the authoriser covering the amount); the band amounts '
                       'are SYNTHETIC; confirm them from the board-approved delegation of financial powers and deploy '
                       'sec-bank-pay-limits and sec-bank-pay-powers together as tenant DMN overrides')
    simple_seeds(pk, 'TREASURY', [
        ('PAY-MAKER', 'PAYMAKER', 'PAY-CHECKER', 'Payment maker', 'भुगतान निर्माता'),
        ('PAY-CHECKER', 'PAYCHECKER', 'PAY-AUTH', 'Payment checker', 'भुगतान जाँचकर्ता'),
        ('PAY-AUTH', 'PAYAUTH', 'TREASURY-HEAD', 'Payment authoriser', 'भुगतान प्राधिकारी'),
        ('TREASURY-HEAD', 'TREASURYHEAD', None, 'Head of treasury operations', 'कोष संचालन प्रमुख'),
        ('AML-COMPLIANCE', 'COMPLIANCE', None, 'AML compliance officer', 'धन-शोधन निवारण अनुपालन अधिकारी')], 'bpy')
    pk.accept('SEC-BANK-PY-1', 'Above-band payment needs two different approvers', ['SEC-BANK-020', 'UP-05'],
              'amount = 3,000,000 by NEFT', 'the checker approves', 'an authoriser task opens; the checker cannot take it',
              vars={'amount': 3000000})


def bank_aml():
    pk = spack('bank', 'aml-str', 'AML alert investigation and STR / CTR to FIU-IND',
               'धन-शोधन चेतावनी जाँच एवं एफआईयू-आईएनडी को एसटीआर / सीटीआर', 'Banking',
               'Transaction-monitoring alert: L1 triage, L2 investigation, Principal Officer decision recorded with '
               'reasons; STR filed on FINnet within 7 days of the conclusion of suspicion; no tipping-off (no '
               'customer notification); risk re-categorised; FIU requests answered; monthly CTR by the 15th '
               '(PML Rules r.7-8).',
               ['SEC-BANK-023', 'SEC-BANK-024', 'SEC-BANK-025', 'SEC-BANK-026', 'GP-06', 'GP-30', 'UP-03', 'UP-06'],
               [('cbs.txnHistory', 'a', '', 'Transaction history'), ('fiu.finnet', 'b', '', 'FINnet 2.0 filing'),
                ('cbs.riskUpdate', 'a', '', 'Customer risk update'), ('cbs.cashTxns', 'a', '', 'Cash transactions for CTR')])
    p = pk.proc('sangam-sec-bank-aml-alert', 'AML alert to STR', '',
                start={'materialFields': 'customerId,alertType,alertScore'})
    s = p.root
    st = start_form(p, s, 'am_start', 'Alert raised (TMS or staff referral)', 'sec-bank-aml-str/alert',
                    'AML alert | धन-शोधन चेतावनी', ['customerId*', 'alertType*', 'alertScore', 'description'])
    d = s.setv('am_defaults', 'Configuration', defaults='alertType=STRUCTURING;alertScore=50;l1Seat=AML-L1;'
               'l2Seat=AML-L2;poSeat=PRINCIPAL-OFFICER;strSla=7D')
    pr = s.rule('am_prio', 'Alert priority (DMN)', 'sec-bank-aml-priority')
    s.seq(st, d, pr)
    l1e, l1d = s.sla_task('am_l1', 'L1 alert triage | एल1 चेतावनी प्राथमिक जाँच', 'l1Seat', form='sec-bank-aml-str/l1',
                          decision=('l1Decision', ['close', 'escalate']), fields=['remarks*'], spec='${l1Spec}')
    s.flow(pr, l1e)
    g1 = s.xgw('am_l1Gw')
    s.flow(l1d, g1)
    s.flow(g1, s.end('am_endFalse', 'Closed as false positive (reasons recorded)'), "l1Decision == 'close'", 'close')
    th = s.conn('am_history', 'Pull transaction history', 'cbs.txnHistory', 'history', 'query', 'customerId', 'txn')
    s.flow(g1, th, None, 'escalate', default=True)
    l2e, l2d = s.sla_task('am_l2', 'L2 investigation | एल2 अन्वेषण', 'l2Seat', form='sec-bank-aml-str/l2',
                          decision=('l2Decision', ['recommendStr', 'close']), fields=['findings*', 'documents'],
                          spec='10WD', sod=True)
    s.flow(th, l2e)
    g2 = s.xgw('am_l2Gw')
    s.flow(l2d, g2)
    s.flow(g2, s.end('am_endClosedL2', 'Closed after investigation'), "l2Decision == 'close'", 'close')
    po = s.user('am_po', 'Principal Officer: suspicious? (record reasons) | प्रधान अधिकारी: संदिग्ध? (कारण दर्ज करें)',
                '${poSeat}', form='sec-bank-aml-str/po', decision=('poDecision', ['fileStr', 'noStr']),
                fields=['strGrounds*'], sod=True)
    s.flow(g2, po, None, 'recommend STR', default=True)
    g3 = s.xgw('am_poGw')
    s.flow(po, g3)
    s.flow(g3, s.end('am_endNoStr', 'No STR (reasons recorded)'), "poDecision == 'noStr'", 'no STR')
    fe, fd = s.sla_task('am_prepare', 'Prepare STR in FINnet format (7 days) | फिननेट प्रारूप में एसटीआर (7 दिन)', 'poSeat',
                        form='sec-bank-aml-str/prepare', decision=None, fields=['strGrounds*', 'documents'],
                        spec='${strSla}', on_breach='ESCALATE')
    s.flow(g3, fe, None, 'file STR', default=True)
    fl = s.conn('am_file', 'File STR on FINnet', 'fiu.finnet', 'str', 'submit', 'customerId,strGrounds', 'str',
                step='str')
    s.flow(fd, fl)
    fg = s.xgw('am_fileGw', 'Accepted?')
    s.flow(fl, fg)
    s.flow(fg, fe, "strStatus == 'FAILED'", 'validation error: correct')
    ru = s.conn('am_risk', 'Raise customer risk and enhanced monitoring (no tipping-off)', 'cbs.riskUpdate', 'raise',
                'submit', 'customerId', 'risk', step='risk')
    s.flow(fg, ru, None, 'acknowledged', default=True)
    s.flow(ru, s.end('am_endFiled', 'STR filed'))
    fr = s.evsub('am_fiuReq', 'Request from FIU-IND')
    f1 = fr.start('am_fiuStart', 'FIU request', msg='sangam.fiu.request', interrupting=False)
    fre, frd = fr.sla_task('am_fiuReply', 'Reply to FIU-IND | एफआईयू-आईएनडी को उत्तर', 'poSeat', form='sec-bank-aml-str/fiu',
                           decision=None, fields=['replyText*', 'documents'], spec='7D')
    fr.seq(f1, fre)
    fr.flow(frd, fr.end('am_fiuEnd', 'Replied'))
    pk.dmn('sec-bank-aml-priority', 'Alert priority', 'FIRST', [('Score', 'alertScore', 'integer')],
           [('l1Spec', 'L1 SLA', 'string'), ('strSla', 'STR within (of the conclusion)', 'string')],
           [(['>= 80'], [q('1WD'), q('7D')], 'high'), (['-'], [q('3WD'), q('7D')], 'STR within 7 days (PML Rules r.8)')])
    c = pk.proc('sangam-sec-bank-ctr', 'Monthly cash transaction report', '', start={'materialFields': 'period'})
    s = c.root
    t = s.start('ctr_timer', 'First of the month', timer_cycle='0 0 6 1 * ?', initiator=None)
    d = s.setv('ctr_defaults', 'Configuration', defaults='poSeat=PRINCIPAL-OFFICER')
    q_ = s.conn('ctr_pull', 'Cash transactions of the month', 'cbs.cashTxns', 'month', 'query', None, 'cash')
    re_, rd = s.sla_task('ctr_review', 'Review CTR (file by the 15th) | सीटीआर की समीक्षा (15 तारीख तक)', 'poSeat',
                         form='sec-bank-aml-str/ctr', decision=None, fields=['remarks'], spec='10D')
    s.seq(t, d, q_, re_)
    sub = s.conn('ctr_file', 'File CTR on FINnet', 'fiu.finnet', 'ctr', 'submit', None, 'ctr', step='ctr')
    s.flow(rd, sub)
    s.flow(sub, s.end('ctr_end', 'CTR filed'))
    simple_seeds(pk, 'AML-CELL', [('AML-L1', 'L1', 'AML-L2', 'AML analyst L1', 'धन-शोधन विश्लेषक एल1'),
                                  ('AML-L2', 'L2', 'PRINCIPAL-OFFICER', 'AML investigator L2', 'धन-शोधन अन्वेषक एल2'),
                                  ('PRINCIPAL-OFFICER', 'PO', None, 'Principal Officer (PMLA)', 'प्रधान अधिकारी (पीएमएलए)')],
                 'bam')


def bank_emandate():
    pk = spack('bank', 'emandate', 'NACH / e-mandate registration and recurring debits',
               'एनएसीएच / ई-अधिदेश पंजीकरण एवं आवर्ती नामे', 'Banking',
               'Mandate registered with AFA (or a signed NACH form verified by maker and checker); every debit is '
               'its own linked case: pre-debit notice at least 24 hours before, customer opt-out, AFA above the '
               'no-AFA limit (Rs 15,000; Rs 1 lakh for mutual funds, insurance premium, card bills), debit by '
               'operationId, bounce handling; modification and revocation with AFA.',
               ['SEC-BANK-027', 'SEC-BANK-028', 'SEC-BANK-029', 'UP-08', 'GP-10', 'GP-12'],
               [('npci.mandate', 'b', '', 'Mandate register / modify / revoke'), ('npci.debit', 'c', '', 'Debit')])
    p = pk.proc('sangam-sec-bank-emandate', 'Mandate lifecycle', '',
                start={'materialFields': 'customerId,account,amount,frequency,mandateType'})
    s = p.root
    st = start_form(p, s, 'em_start', 'Mandate requested', 'sec-bank-emandate/register',
                    'Mandate registration | अधिदेश पंजीकरण',
                    ['customerId*', 'account*', 'ifsc', 'amount*', 'frequency*', 'mandateType*', 'merchantCategory'])
    d = s.setv('em_defaults', 'Configuration', defaults='mandateType=API_EMANDATE;frequency=MONTHLY;'
               'merchantCategory=GENERAL;debitCycle=P30D;opsSeat=MANDATE-MAKER')
    tg = s.xgw('em_typeGw', 'Physical NACH form?')
    s.seq(st, d, tg)
    mk = s.user('em_verify', 'Verify signed NACH form (maker) | हस्ताक्षरित एनएसीएच प्रपत्र का सत्यापन', '${opsSeat}',
                form='sec-bank-emandate/verify', decision=None, fields=['documents*', 'remarks'])
    ck = s.user('em_check', 'Approve mandate (checker) | अधिदेश अनुमोदन (जाँचकर्ता)', 'MANDATE-CHECKER',
                form='sec-bank-emandate/check', decision=('mandateDecision', ['approve', 'reject']), fields=['remarks'],
                sod=True)
    s.flow(tg, mk, "mandateType == 'NACH_PHYSICAL'", 'physical')
    s.flow(mk, ck)
    reg = s.conn('em_register', 'Register mandate (AFA by the customer)', 'npci.mandate', 'register', 'submit',
                 'customerId,account,amount,frequency', 'mandate', step='register')
    s.flow(tg, reg, None, 'e-mandate / UPI AutoPay / card', default=True)
    cg = s.xgw('em_checkGw')
    s.flow(ck, cg)
    s.flow(cg, reg, "mandateDecision == 'approve'", 'approve')
    s.flow(cg, s.end('em_endRejected', 'Mandate rejected'), None, 'reject', default=True)
    rg = s.xgw('em_regGw', 'Registered?')
    s.flow(reg, rg)
    s.flow(rg, s.end('em_endFailed', 'Registration failed'), "mandateStatus == 'FAILED'", 'failed')
    w = s.egw('em_wait', 'Next debit, change or revocation')
    s.flow(rg, w, None, 'active', default=True)
    nx = s.catch_timer('em_next', 'Next debit cycle', duration='${debitCycle}')
    md = s.catch_msg('em_modify', 'Modification (with AFA)', 'sangam.mandate.modify')
    rv = s.catch_msg('em_revoke', 'Revocation (with AFA)', 'sangam.mandate.revoke')
    for x in (nx, md, rv):
        s.flow(w, x)
    lk = s.svc('em_spawn', 'Start the debit as a linked case', C['LINK'],
               {'processKey': 'sangam-sec-bank-emandate-debit', 'linkType': 'debit',
                'inherit': 'customerId,account,amount,merchantCategory'})
    s.seq(nx, lk, w)
    mo = s.conn('em_doModify', 'Modify mandate', 'npci.mandate', 'modify', 'submit', 'customerId,amount', 'mandateMod',
                step='modify')
    s.seq(md, mo, w)
    rk = s.conn('em_doRevoke', 'Revoke mandate', 'npci.mandate', 'revoke', 'submit', 'customerId', 'mandateRev',
                step='revoke')
    s.seq(rv, rk, s.end('em_endRevoked', 'Revoked'))
    c = pk.proc('sangam-sec-bank-emandate-debit', 'Recurring debit (one cycle)', '',
                start={'materialFields': 'customerId,amount'})
    s = c.root
    a1 = s.start('ed_start', 'Debit due', initiator=None)
    d = s.setv('ed_defaults', 'Configuration', defaults='merchantCategory=GENERAL;afaDone=false;'
               'executionConnector=npci.debit;executionStep=debit;verifyApproval=false;reconcileRequired=false;'
               'applicantRef=@customerId')
    r = s.rule('ed_afa', 'AFA needed? (DMN)', 'sec-bank-afa-limit')
    pre = at_date(s, 'ed_pre', 'preDebit', 'debitDate', 'P1D')
    n = s.notify('ed_notify', 'Pre-debit notice (at least 24 hours before)', 'pre-debit-notice', 'sms,email',
                 'applicantRef')
    deb = at_date(s, 'ed_dueCalc', 'debit', 'debitDate')
    s.seq(a1, d, r, pre)
    tw = s.catch_timer('ed_preWait', 'Notice time', date='${preDebitDueAt}')
    s.seq(pre, tw, n, deb)
    w = s.egw('ed_wait', 'Opt-out or debit time')
    s.flow(deb, w)
    oo = s.catch_msg('ed_optOut', 'Customer opts out of this debit', 'sangam.mandate.optOut')
    dt = s.catch_timer('ed_debitTime', 'Debit time', date='${debitDueAt}')
    s.flow(w, oo)
    s.flow(w, dt)
    s.flow(oo, s.end('ed_endSkipped', 'Skipped by the customer'))
    ag = s.xgw('ed_afaGw', 'AFA required?')
    s.flow(dt, ag)
    aw = s.egw('ed_afaWait', 'AFA or timeout')
    s.flow(ag, aw, 'afaRequired', 'yes')
    am = s.catch_msg('ed_afaMsg', 'AFA completed', 'sangam.mandate.afaDone')
    at = s.catch_timer('ed_afaTimeout', 'AFA not completed', duration='PT24H')
    s.flow(aw, am)
    s.flow(aw, at)
    s.flow(at, s.end('ed_endNoAfa', 'Not debited: AFA not completed'))
    ex = core(s, 'ed_debit', 'Debit (core execution)', 'execution')
    s.flow(ag, ex, None, 'within the no-AFA limit', default=True)
    s.flow(am, ex)
    ok = end_ok(c, s, 'ed_end', 'Debited')
    bn = s.notify('ed_bounce', 'Debit returned: tell the customer', 'debit-bounce', 'sms,email', 'applicantRef')
    outcome(s, 'ed_exGw', 'executionOutcome', {'DONE': ok}, bn)
    s.flow(ex, 'ed_exGw')
    s.flow(bn, s.end('ed_endBounced', 'Bounced'))
    pk.dmn('sec-bank-afa-limit', 'Additional factor of authentication for recurring debits', 'FIRST',
           [('Merchant category', 'merchantCategory', 'string'), ('Amount', 'amount', 'money')],
           [('afaRequired', 'AFA required', 'boolean')],
           [([q('MUTUAL_FUND,INSURANCE,CREDIT_CARD').replace(',', '","'), '> 100000'], ['true'],
             'Rs 1 lakh for mutual funds, insurance premium, credit card bills'),
            ([q('MUTUAL_FUND,INSURANCE,CREDIT_CARD').replace(',', '","'), '-'], ['false'], ''),
            (['-', '> 15000'], ['true'], 'Rs 15,000 otherwise'), (['-', '-'], ['false'], '')])
    simple_seeds(pk, 'MANDATES', [('MANDATE-MAKER', 'MAKER', 'MANDATE-CHECKER', 'Mandate maker', 'अधिदेश निर्माता'),
                                  ('MANDATE-CHECKER', 'CHECKER', None, 'Mandate checker', 'अधिदेश जाँचकर्ता')], 'bem')


def bank_cheque():
    pk = spack('bank', 'cheque-return', 'Cheque return and Section 138 NI Act', 'चेक वापसी एवं परक्राम्य लिखत अधिनियम धारा 138',
               'Banking',
               'CTS return: memo with the reason the same day, charges by schedule, repeated dishonour control '
               '(cheque facility withdrawn after the board threshold); payee side: legal notice within 30 days of '
               'the return memo, 15 days for the drawer to pay, complaint within one month after that, or '
               're-presentation within validity.',
               ['SEC-BANK-030', 'SEC-BANK-031', 'GP-35', 'GP-46'],
               [('cts.returnMemo', 'a', '', 'Return memo'), ('cts.present', 'a', '', 'Re-presentation'),
                ('docs.notice', 'a', '', 'Legal notice')])
    p = pk.proc('sangam-sec-bank-cheque-return', 'Cheque return', '', start={'materialFields': 'chequeNo,amount,returnReason'})
    s = p.root
    st = start_form(p, s, 'cq_start', 'Cheque returned in clearing', 'sec-bank-cheque-return/return',
                    'Cheque return | चेक वापसी', ['chequeNo*', 'amount*', 'drawerName', 'returnReason*', 'applicantRef'])
    d = s.setv('cq_defaults', 'Configuration', defaults='chequeNo=@customerId;returnReason=FUNDS_INSUFFICIENT;'
               'drawerName=X;returnCount=1;payeeSeat=PAYEE-DESK;applicantRef=@customerId')
    m = s.conn('cq_memo', 'Return memo with reason', 'cts.returnMemo', 'memo', 'submit', 'chequeNo,returnReason', 'memo',
               step='memo')
    n = s.notify('cq_notify', 'Tell the customer the same day', 'cheque-returned', 'sms,email', 'applicantRef')
    r = s.rule('cq_rule', 'Charges and repeated dishonour (DMN)', 'sec-bank-cheque-rules')
    s.seq(st, d, m, n, r)
    wd = s.user('cq_withdraw', 'Withdraw cheque facility (repeated dishonour) | चेक सुविधा वापस लें', 'BRANCH-MGR',
                form='sec-bank-cheque-return/withdraw', decision=None, fields=['remarks*'])
    g, mg = maybe(s, 'cq_rep', 'withdrawChequeBook', wd, wd, 'Repeated dishonour?')
    s.flow(r, g)
    pe, pd = s.sla_task('cq_payee', 'Payee: legal notice or re-present (30 days) | आदाता: विधिक नोटिस या पुनः प्रस्तुति (30 दिन)',
                        'payeeSeat', form='sec-bank-cheque-return/payee',
                        decision=('payeeDecision', ['issueNotice', 'represent', 'drop']), fields=['remarks'],
                        spec='30D', on_breach='DEEMED_REFUSAL', approve='issueNotice', refuse='drop', max_esc=0)
    s.flow(mg, pe)
    pg = s.xgw('cq_payeeGw')
    s.flow(pd, pg)
    rp = s.conn('cq_represent', 'Re-present the cheque', 'cts.present', 'present', 'submit', 'chequeNo,amount', 'present',
                step='represent')
    s.flow(pg, rp, "payeeDecision == 'represent'", 're-present')
    s.flow(rp, s.end('cq_endRepresented', 'Re-presented'))
    nt = s.conn('cq_notice', 'Legal notice to the drawer', 'docs.notice', 's138', 'submit', 'chequeNo,amount,drawerName',
                'notice', step='s138')
    s.flow(pg, nt, "payeeDecision == 'issueNotice'", 'notice')
    s.flow(pg, s.end('cq_endDropped', 'No action'), None, 'drop', default=True)
    w = s.egw('cq_wait', 'Payment or 15 days')
    s.flow(nt, w)
    pm = s.catch_msg('cq_paid', 'Drawer paid', 'sangam.cheque.paid')
    t15 = s.catch_timer('cq_15d', '15 days after notice', duration='P15D')
    s.flow(w, pm)
    s.flow(w, t15)
    s.flow(pm, s.end('cq_endPaid', 'Paid after notice'))
    ce, cd = s.sla_task('cq_complaint', 'File complaint before the magistrate (one month) | मजिस्ट्रेट के समक्ष परिवाद (एक माह)',
                        'payeeSeat', form='sec-bank-cheque-return/complaint', decision=None, fields=['caseNo', 'court'],
                        spec='30D')
    s.flow(t15, ce)
    s.flow(cd, s.end('cq_endComplaint', 'Complaint filed'))
    pk.dmn('sec-bank-cheque-rules', 'Cheque return rules (SYNTHETIC board policy)', 'FIRST',
           [('Reason', 'returnReason', 'string'), ('Returns this year', 'returnCount', 'integer'),
            ('Amount', 'amount', 'money')],
           [('withdrawChequeBook', 'Withdraw facility', 'boolean'), ('returnCharge', 'Charge (INR)', 'money')],
           [([q('FUNDS_INSUFFICIENT'), '>= 4', '>= 10000000'], ['true', '500'],
             'dishonour four times in a financial year for 1 crore or more'),
            ([q('FUNDS_INSUFFICIENT'), '-', '-'], ['false', '500'], ''),
            (['-', '-', '-'], ['false', '0'], 'technical reasons: no charge to the customer')])
    simple_seeds(pk, 'CLEARING', [('PAYEE-DESK', 'PAYEE', 'BRANCH-MGR', 'Payee legal desk', 'आदाता विधि डेस्क'),
                                  ('BRANCH-MGR', 'BRANCHMGR', None, 'Branch manager', 'शाखा प्रबंधक')], 'bcq')


def bank_dispute():
    pk = spack('bank', 'txn-dispute', 'Failed transactions, unauthorised transactions and card chargebacks',
               'विफल लेनदेन, अनधिकृत लेनदेन एवं कार्ड चार्जबैक', 'Banking',
               'Failed transactions reversed within the RBI TAT (ATM T+5, IMPS / UPI T+1) with suo moto compensation '
               'of Rs 100 per day beyond it; unauthorised electronic transactions: instrument blocked, shadow credit '
               'within 10 working days, customer liability by reporting delay, resolution within 90 days; card '
               'chargeback with merchant representment and pre-arbitration; the RB-IOS route after 30 days.',
               ['SEC-BANK-032', 'SEC-BANK-033', 'SEC-BANK-034', 'SEC-BANK-035', 'GP-06', 'GP-12', 'GP-15', 'GP-35',
                'UP-09'],
               [('switch.status', 'a', '', 'Switch / NPCI transaction status'), ('cbs.reversal', 'a', '', 'Reversal'),
                ('cbs.compensation', 'c', '', 'Compensation credit'), ('cards.block', 'a', '', 'Block the instrument'),
                ('card.chargeback', 'b', '', 'Chargeback to the network')])
    p = pk.proc('sangam-sec-bank-txn-dispute', 'Transaction dispute', '',
                start={'materialFields': 'customerId,disputeType,txnRef,amount,channel'})
    s = p.root
    st = start_form(p, s, 'dp_start', 'Dispute reported', 'sec-bank-txn-dispute/report', 'Transaction dispute | लेनदेन विवाद',
                    ['customerId*', 'disputeType*', 'txnRef*', 'amount*', 'channel*', 'reportedWithinDays', 'description'])
    d = s.setv('dp_defaults', 'Configuration',
               defaults='disputeType=FAILED_TXN;channel=UPI;txnRef=@customerId;reportedWithinDays=1;txnFailed=true;'
                        'tatBreached=false;opsSeat=DISPUTE-DESK;executionConnector=cbs.reversal;executionStep=reversal;'
                        'verifyApproval=false;reconcileRequired=false;executionPayloadFields=customerId,txnRef,amount;'
                        'applicantRef=@customerId')
    r = s.rule('dp_tat', 'TAT, compensation and liability (DMN)', 'sec-bank-dispute-tat')
    tg = s.xgw('dp_typeGw', 'Dispute type?')
    s.seq(st, d, r, tg)
    tc = clock(s, 'dp_tatClock', 'tat', '${tatSpec}')
    s.flow(tg, tc, "disputeType == 'FAILED_TXN'", 'failed transaction')
    ss = s.conn('dp_status', 'Transaction status from the switch', 'switch.status', 'status', 'query', 'txnRef', 'txn')
    s.flow(tc, ss)
    fg = s.xgw('dp_failGw', 'Debited, not credited?')
    s.flow(ss, fg)
    s.flow(fg, s.end('dp_endSuccess', 'Transaction was successful (evidence sent)'), '!txnFailed', 'no')
    rvs = s.user('dp_reverse', 'Reverse the failed debit | विफल नामे की प्रतिवर्ती प्रविष्टि', '${opsSeat}',
                 form='sec-bank-txn-dispute/reverse', decision=None, fields=['remarks'], due='${tatDueAt}')
    s.flow(fg, rvs, None, 'yes', default=True)
    lb = s.boundary('dp_tatLate', rvs, 'TAT exceeded', timer_date='${tatDueAt}', cancel=False)
    s.flow(lb, s.setv('dp_lateFlag', 'Compensation due', values='tatBreached=true'))
    s.flow('dp_lateFlag', s.end('dp_lateEnd', 'Recorded'))
    ex = core(s, 'dp_reversal', 'Reversal (core execution)', 'execution')
    s.flow(rvs, ex)
    cpg = s.xgw('dp_compGw', 'Beyond TAT?')
    s.flow(ex, cpg)
    cp = s.conn('dp_compensate', 'Suo moto compensation credit', 'cbs.compensation', 'credit', 'submit',
                'customerId,txnRef,compensationPerDay', 'comp', step='compensation')
    s.flow(cpg, cp, 'tatBreached', 'yes')
    fin = end_ok(p, s, 'dp_endReversed', 'Reversed')
    s.flow(cpg, fin, None, 'within TAT', default=True)
    s.flow(cp, fin)
    blk = s.conn('dp_block', 'Block the instrument', 'cards.block', 'block', 'submit', 'customerId,txnRef', 'block',
                 step='block')
    s.flow(tg, blk, "disputeType == 'UNAUTHORISED'", 'unauthorised')
    she, shd = s.sla_task('dp_shadow', 'Shadow credit (10 working days) | छाया जमा (10 कार्य दिवस)', 'opsSeat',
                          form='sec-bank-txn-dispute/shadow', decision=None, fields=['remarks'], spec='10WD')
    s.flow(blk, she)
    ie, idn = s.sla_task('dp_investigate', 'Investigate and decide liability (90 days) | जाँच एवं दायित्व निर्णय (90 दिन)',
                         'opsSeat', form='sec-bank-txn-dispute/investigate',
                         decision=('liabilityDecision', ['bankLiable', 'customerLiable']),
                         fields=['customerLiability', 'findings*'], spec='90D', sod=True)
    s.flow(shd, ie)
    lg = s.xgw('dp_liabGw')
    s.flow(idn, lg)
    s.flow(lg, end_ok(p, s, 'dp_endBank', 'Credit made final'), "liabilityDecision == 'bankLiable'", 'bank liable')
    rvn = s.notify('dp_reverseShadow', 'Shadow credit reversed with reasons and the RB-IOS route',
                   'dispute-customer-liable', 'sms,email,letter', 'applicantRef')
    s.flow(lg, rvn, None, 'customer liable', default=True)
    s.flow(rvn, s.end('dp_endCustomer', 'Closed: customer liable'))
    cb = s.conn('dp_chargeback', 'Raise chargeback with the network', 'card.chargeback', 'raise', 'submit',
                'txnRef,amount', 'cb', step='chargeback')
    s.flow(tg, cb, None, 'card chargeback', default=True)
    w = s.egw('dp_cbWait', 'Representment or network window')
    s.flow(cb, w)
    rp = s.catch_msg('dp_repr', 'Merchant representment', 'sangam.chargeback.representment')
    tw = s.catch_timer('dp_cbWindow', 'No representment in the window', duration='P45D')
    s.flow(w, rp)
    s.flow(w, tw)
    s.flow(tw, end_ok(p, s, 'dp_endCbWon', 'Chargeback final'))
    rv = s.user('dp_review', 'Review representment | प्रति-प्रस्तुति की समीक्षा', '${opsSeat}',
                form='sec-bank-txn-dispute/representment', decision=('reprDecision', ['accept', 'preArbitration']),
                fields=['findings*', 'documents'], sod=True)
    s.flow(rp, rv)
    rg = s.xgw('dp_reprGw')
    s.flow(rv, rg)
    s.flow(rg, s.end('dp_endCbLost', 'Representment accepted: customer informed'), "reprDecision == 'accept'", 'accept')
    pa = s.conn('dp_preArb', 'Pre-arbitration / arbitration', 'card.chargeback', 'pre-arbitration', 'submit',
                'txnRef,amount', 'arb', step='pre-arbitration')
    s.flow(rg, pa, None, 'pre-arbitration', default=True)
    rl = s.catch_msg('dp_ruling', 'Network ruling', 'sangam.chargeback.ruling')
    s.seq(pa, rl, s.end('dp_endRuling', 'Ruling applied'))
    om = s.evsub('dp_ombudsman', 'Unresolved after 30 days')
    o1 = om.start('dp_omStart', '30 days', interrupting=False)
    om.nodes[-1]['body'] = ['<timerEventDefinition><timeDuration xsi:type="tFormalExpression">P30D'
                            '</timeDuration></timerEventDefinition>']
    o2 = om.notify('dp_omNotify', 'Tell the customer about the RB-IOS route', 'rbios-route', 'sms,email', 'applicantRef')
    om.seq(o1, o2, om.end('dp_omEnd', 'Informed'))
    pk.dmn('sec-bank-dispute-tat', 'Failed-transaction TAT and customer liability (RBI 2019 / 2017)', 'FIRST',
           [('Type', 'disputeType', 'string'), ('Channel', 'channel', 'string'),
            ('Reported within (days)', 'reportedWithinDays', 'integer')],
           [('tatSpec', 'TAT', 'string'), ('compensationPerDay', 'Compensation per day (INR)', 'integer'),
            ('customerLiability', 'Customer liability', 'string')],
           [([q('FAILED_TXN'), q('ATM'), '-'], [q('5D'), '100', q('NIL')], 'ATM: T+5, Rs 100 per day after'),
            ([q('FAILED_TXN'), '-', '-'], [q('1D'), '100', q('NIL')], 'IMPS / UPI: T+1'),
            ([q('UNAUTHORISED'), '-', '<= 3'], [q('10WD'), '0', q('ZERO')], 'reported within 3 working days'),
            ([q('UNAUTHORISED'), '-', '<= 7'], [q('10WD'), '0', q('LIMITED')], '4-7 working days: capped liability'),
            ([q('UNAUTHORISED'), '-', '-'], [q('10WD'), '0', q('BOARD_POLICY')], 'beyond 7 days: board policy'),
            (['-', '-', '-'], [q('45D'), '0', q('NETWORK_RULES')], 'chargeback: network rules')])
    simple_seeds(pk, 'DISPUTES', [('DISPUTE-DESK', 'DISPUTE', None, 'Disputes desk', 'विवाद डेस्क')], 'bdp')


# ============================================================================================ cross-regulator
def fin_complaint():
    pk = spack('fin', 'complaint', 'Customer complaint with regulator timelines', 'नियामक समय-सीमा सहित ग्राहक शिकायत',
               'Financial services (cross-sector)',
               'One complaint process for every regulated entity: the regulator scheme sets the acknowledgement, '
               'resolution and escalation clocks by rule (RBI internal ombudsman before any rejection and RB-IOS '
               'after 30 days; IRDAI Bima Bharosa 14 days; SEBI SCORES 21 days; e-commerce grievance officer 48 '
               'hours / one month; TRAI; DPDP 90 days; electricity CGRF); final reply carries the escalation route; '
               'reopen within the window.',
               ['SEC-FIN-001', 'SEC-FIN-002', 'SEC-FIN-003', 'SEC-ECOM-007', 'SEC-INS-010', 'SEC-CAP-005',
                'SEC-TEL-003', 'SEC-UTIL-003', 'GP-06', 'GP-22', 'UP-04', 'UP-10'] + SIDE,
               [('crm.case', 'a', '', 'CRM / CMS case reference')])
    p = pk.proc('sangam-sec-fin-complaint', 'Customer complaint', '',
                start={'materialFields': 'customerId,regulatorScheme,grievanceText'})
    s = p.root
    st = start_form(p, s, 'fc_start', 'Complaint received', 'sec-fin-complaint/complaint', 'Complaint | शिकायत',
                    ['customerId*', 'regulatorScheme*', 'category', 'grievanceText*', 'applicantRef', 'documents'])
    d = s.setv('fc_defaults', 'Configuration', defaults='regulatorScheme=RBI;groSeat=GRO;ioSeat=INTERNAL-OMBUDSMAN;'
               'reopened=false;applicantRef=@customerId')
    r = s.rule('fc_tat', 'Clocks by regulator scheme (DMN)', 'sec-fin-complaint-tat')
    crm = s.conn('fc_crm', 'Register complaint reference', 'crm.case', 'register', 'submit', 'customerId,regulatorScheme',
                 'crm', step='register')
    ack = s.notify('fc_ack', 'Acknowledge with reference and timeline', 'complaint-ack', 'sms,email,inapp', 'applicantRef')
    s.seq(st, d, r, crm, ack)
    re_, rd = s.sla_task('fc_resolve', 'Resolve complaint | शिकायत का समाधान', 'groSeat', form='sec-fin-complaint/resolve',
                         decision=('complaintDecision', ['resolve', 'reject']), fields=['actionTaken*', 'replyText*'],
                         spec='${resolveSpec}', on_breach='ESCALATE')
    s.flow(ack, re_)
    g = s.xgw('fc_gw', 'Rejected or partly?')
    s.flow(rd, g)
    io = s.user('fc_io', 'Internal ombudsman review before rejection | अस्वीकृति से पूर्व आंतरिक लोकपाल समीक्षा',
                '${ioSeat}', form='sec-fin-complaint/io', decision=('ioDecision', ['uphold', 'overturn']),
                fields=['remarks*'], sod=True)
    s.flow(g, io, "complaintDecision == 'reject' && ioRequired", 'reject (IO first)')
    ig = s.xgw('fc_ioGw')
    s.flow(io, ig)
    s.flow(ig, re_, "ioDecision == 'overturn'", 'overturned: resolve')
    fr = s.notify('fc_reply', 'Final reply with the escalation route', 'complaint-final-reply', 'sms,email,letter',
                  'applicantRef')
    s.flow(ig, fr, None, 'upheld', default=True)
    s.flow(g, fr, None, 'resolved', default=True)
    w = s.egw('fc_wait', 'Reopen or close')
    s.flow(fr, w)
    ro = s.catch_msg('fc_reopen', 'Customer not satisfied', 'sangam.complaint.reopen')
    tc = s.catch_timer('fc_closeAfter', 'Window over', duration='P30D')
    s.flow(w, ro)
    s.flow(w, tc)
    rg = s.xgw('fc_reopenGw', 'First reopen?')
    s.flow(ro, rg)
    s.flow(rg, s.setv('fc_mark', 'Reopened', values='reopened=true'), '!reopened', 'yes')
    s.flow('fc_mark', re_)
    e = end_ok(p, s, 'fc_end', 'Closed')
    s.flow(rg, e, None, 'already reopened: regulator route', default=True)
    s.flow(tc, e)
    pk.dmn('sec-fin-complaint-tat', 'Complaint clocks by regulator scheme', 'FIRST',
           [('Scheme', 'regulatorScheme', 'string')],
           [('resolveSpec', 'Resolve within', 'string'), ('ioRequired', 'Internal ombudsman before rejection', 'boolean'),
            ('escalationRoute', 'Escalation route', 'string')],
           [([q('RBI')], [q('30D'), 'true', q('RB-IOS (CMS portal) after 30 days')], 'RBI: IO review, RB-IOS after 30 days'),
            ([q('IRDAI')], [q('14D'), 'false', q('Bima Bharosa / Insurance Ombudsman')], 'IRDAI: 14 days'),
            ([q('SEBI')], [q('21D'), 'false', q('SCORES second level / ODR')], 'SEBI SCORES: 21 days'),
            ([q('ECOMMERCE')], [q('30D'), 'false', q('National Consumer Helpline / Commission')],
             'acknowledge in 48 hours, redress in one month (E-Commerce Rules r.4(5))'),
            ([q('TRAI')], [q('30D'), 'false', q('Appellate authority of the service provider')], ''),
            ([q('DPDP')], [q('90D'), 'false', q('Data Protection Board')], 'DPDP Rules r.14(3)'),
            ([q('ELECTRICITY')], [q('30D'), 'false', q('CGRF / Electricity Ombudsman')], ''),
            (['-'], [q('30D'), 'false', q('Regulator')], '')])
    simple_seeds(pk, 'GRIEVANCE', [('GRO', 'GRO', 'NODAL-OFFICER', 'Grievance redressal officer', 'शिकायत निवारण अधिकारी'),
                                   ('NODAL-OFFICER', 'NODAL', None, 'Principal nodal officer', 'प्रधान नोडल अधिकारी'),
                                   ('INTERNAL-OMBUDSMAN', 'IO', None, 'Internal ombudsman', 'आंतरिक लोकपाल')], 'fco')


def fin_cyber():
    pk = spack('fin', 'cyber-incident', 'Cyber incident response and regulatory reporting',
               'साइबर घटना प्रतिक्रिया एवं नियामक रिपोर्टिंग', 'Financial services (cross-sector)',
               'Containment and, in parallel, the reports each law requires: CERT-In within 6 hours (2022 '
               'directions), the sector regulator within 6 hours (RBI IT Governance Directions 2023, SEBI CSCRF, '
               'IRDAI), the Data Protection Board and the affected data principals (DPDP Rules r.7, detailed report '
               'within 72 hours); root cause analysis by the deadline, CISO approval with SoD, RCA submitted.',
               ['SEC-FIN-004', 'SEC-FIN-005', 'SEC-FIN-006', 'SEC-BANK-036', 'SEC-CAP-003', 'SEC-LEGAL-003',
                'UP-10', 'GP-06'],
               [('certin.report', 'b', '', 'CERT-In incident report'), ('regulator.report', 'b', '', 'Regulator portal'),
                ('dpb.report', 'b', '', 'Data Protection Board intimation'), ('notify.email', 'b', '', 'Data principals')])
    p = pk.proc('sangam-sec-fin-cyber-incident', 'Cyber incident', '',
                start={'materialFields': 'incidentSummary,incidentCategory,priority'})
    s = p.root
    st = start_form(p, s, 'ci_start', 'Incident detected', 'sec-fin-cyber-incident/raise', 'Cyber incident | साइबर घटना',
                    ['incidentSummary*', 'incidentCategory*', 'priority*', 'personalDataAffected', 'regulatedBy*'])
    d = s.setv('ci_defaults', 'Configuration', defaults='incidentCategory=MALWARE;personalDataAffected=false;'
               'regulatedBy=RBI;socSeat=SOC-LEAD;cisoSeat=CISO;dpoSeat=DPO')
    r = s.rule('ci_rules', 'Reporting obligations (DMN)', 'sec-fin-incident-reporting')
    fk = s.pgw('ci_fork', 'Contain and report in parallel')
    jn = s.pgw('ci_join', 'All done')
    s.seq(st, d, r, fk)
    ce, cd = s.sla_task('ci_contain', 'Contain and preserve evidence (logs 180 days) | रोकथाम एवं साक्ष्य संरक्षण',
                        'socSeat', form='sec-fin-cyber-incident/contain', decision=None,
                        fields=['actionTaken*', 'findings'], spec='4H')
    s.flow(fk, ce)
    s.flow(cd, jn)
    for key, cond, seat, spec, conn_, en, hi in (
            ('certin', 'certInRequired', 'cisoSeat', '${certInSpec}', 'certin.report', 'Report to CERT-In (6 hours)', 'सर्ट-इन को रिपोर्ट (6 घंटे)'),
            ('regulator', 'regulatorReport', 'cisoSeat', '${regulatorSpec}', 'regulator.report',
             'Initial report to the regulator', 'नियामक को प्रारंभिक रिपोर्ट'),
            ('dpb', 'personalDataAffected', 'dpoSeat', '${dpbSpec}', 'dpb.report',
             'Intimate Data Protection Board and data principals (72 hours)', 'डेटा संरक्षण बोर्ड एवं डेटा प्रधानों को सूचना (72 घंटे)')):
        g = s.xgw('ci_' + key + 'Gw', en + '?')
        mg = s.xgw('ci_' + key + 'Merge')
        s.flow(fk, g)
        te, td = s.sla_task('ci_' + key, f'{en} | {hi}', seat, form='sec-fin-cyber-incident/' + key, decision=None,
                            fields=['incidentSummary', 'remarks'], spec=spec, on_breach='ESCALATE')
        rp = s.conn('ci_' + key + 'Send', 'Send: ' + en, conn_, 'report', 'submit', 'incidentSummary,incidentCategory,priority',
                    key + 'Rep', step=key)
        s.flow(g, te, cond, 'required')
        s.flow(g, mg, None, 'not required', default=True)
        s.flow(td, rp)
        s.flow(rp, mg)
        s.flow(mg, jn)
    rce, rcd = s.sla_task('ci_rca', 'Root cause analysis | मूल कारण विश्लेषण', 'socSeat', form='sec-fin-cyber-incident/rca',
                          decision=None, fields=['rootCause*', 'capaActions*'], spec='${rcaSpec}')
    s.flow(jn, rce)
    ap = s.user('ci_approve', 'CISO approves RCA | सीआईएसओ द्वारा विश्लेषण का अनुमोदन', '${cisoSeat}',
                form='sec-fin-cyber-incident/approve', decision=('rcaDecision', ['approve', 'return']),
                fields=['remarks'], sod=True)
    s.flow(rcd, ap)
    ag = s.xgw('ci_apGw')
    s.flow(ap, ag)
    s.flow(ag, rce, "rcaDecision == 'return'", 'rework')
    fr = s.conn('ci_final', 'Submit RCA to the regulator', 'regulator.report', 'rca', 'submit', 'rootCause,capaActions',
                'rcaRep', step='rca')
    g2, m2 = maybe(s, 'ci_finalOpt', 'regulatorReport', fr, fr, 'Regulator RCA?')
    s.flow(ag, g2, None, 'approved', default=True)
    s.flow(m2, end_ok(p, s, 'ci_end', 'Closed'))
    pk.dmn('sec-fin-incident-reporting', 'Incident reporting obligations', 'FIRST',
           [('Regulator', 'regulatedBy', 'string'), ('Priority', 'priority', 'string')],
           [('certInRequired', 'CERT-In', 'boolean'), ('regulatorReport', 'Regulator report', 'boolean'),
            ('regulatorSpec', 'Regulator clock', 'string'), ('rcaSpec', 'RCA due', 'string'),
            ('certInSpec', 'CERT-In clock', 'string'), ('dpbSpec', 'DPDP Board detailed report', 'string')],
           [([q('NONE'), '-'], ['true', 'false', q('6H'), q('30D'), q('6H'), q('72H')], 'CERT-In applies to every body corporate'),
            (['-', q('P4')], ['true', 'false', q('6H'), q('30D'), q('6H'), q('72H')], 'low severity: internal only'),
            ([q('SEBI'), '-'], ['true', 'true', q('6H'), q('30D'), q('6H'), q('72H')], 'CSCRF: SEBI and CERT-In within 6 hours'),
            (['-', '-'], ['true', 'true', q('6H'), q('21D'), q('6H'), q('72H')],
             'RBI / IRDAI: 6 hours; RCA (SYNTHETIC 21 days); DPDP Rules r.7: 72 hours')])
    simple_seeds(pk, 'SECOPS', [('SOC-LEAD', 'SOC', 'CISO', 'SOC lead', 'एसओसी प्रमुख'),
                                ('CISO', 'CISO', None, 'Chief information security officer', 'मुख्य सूचना सुरक्षा अधिकारी'),
                                ('DPO', 'DPO', None, 'Data protection officer', 'डेटा संरक्षण अधिकारी')], 'fci')


# ============================================================================================ NBFC / fintech
def nbfc_digital_lending():
    pk = spack('nbfc', 'digital-lending', 'Digital lending through an app or LSP', 'ऐप या ऋण सेवा प्रदाता द्वारा डिजिटल ऋण',
               'NBFC / fintech',
               'RBI Digital Lending Directions: explicit consent for each data use, Account Aggregator consent, '
               'bureau, credit decision by the regulated entity (not the LSP), KFS with APR before the contract, '
               'acceptance within the KFS validity, disbursal straight to the borrower account (no pass-through or '
               'pool account), cooling-off exit with principal and proportionate APR only, reporting to CICs, nodal '
               'grievance officer of the RE and the LSP.',
               ['SEC-NBFC-001', 'SEC-NBFC-002', 'SEC-NBFC-003', 'SEC-NBFC-004', 'SEC-NBFC-005', 'SEC-NBFC-006',
                'GP-12', 'GP-44', 'UP-01', 'UP-22'] + SIDE,
               [('aa.fetch', 'a', '', 'Account Aggregator data (consent artefact)'), ('bureau.pull', 'a', '', 'CIC'),
                ('docs.kfs', 'a', '', 'KFS'), ('bank.transfer', 'c', '', 'Disbursal to the borrower account'),
                ('lms.book', 'a', '', 'Book loan'), ('lms.foreclose', 'a', '', 'Cooling-off exit'),
                ('cic.report', 'a', '', 'Credit information reporting')])
    p = pk.proc('sangam-sec-nbfc-digital-loan', 'Digital loan', '',
                start={'materialFields': 'customerId,amount,tenureDays,aprPct'})
    s = p.root
    st = start_form(p, s, 'dl_start', 'Application in the app', 'sec-nbfc-digital-lending/application',
                    'Digital loan application | डिजिटल ऋण आवेदन',
                    ['customerId*', 'amount*', 'tenureDays*', 'lspName', 'consentGiven*', 'dataConsentScope'])
    d = s.setv('dl_defaults', 'Configuration',
               defaults='tenureDays=90;lspName=DIRECT;consentGiven=true;bureauScore=720;aprPct=24;caseType=LOAN;'
                        'routingDecisionKey=sec-nbfc-dl-authority;powersDecisionKey=sec-nbfc-dl-powers;'
                        'executionConnector=bank.transfer;executionStep=disburse;reconcileRequired=true;'
                        'executionPayloadFields=customerId,amount;appealAllowed=false;outputTemplate=loan-documents')
    cg = s.xgw('dl_consentGw', 'Explicit consent?')
    s.seq(st, d, cg)
    s.flow(cg, s.end('dl_endNoConsent', 'Stopped: no consent'), '!consentGiven', 'no')
    aa = s.conn('dl_aa', 'Fetch bank statements via Account Aggregator', 'aa.fetch', 'fetch', 'query', 'customerId', 'aa')
    s.flow(cg, aa, None, 'yes', default=True)
    bu = s.conn('dl_bureau', 'Bureau report', 'bureau.pull', 'report', 'query', 'customerId', 'bureau')
    r = s.rule('dl_policy', 'Credit decision of the RE (DMN)', 'sec-nbfc-dl-policy')
    g = s.xgw('dl_policyGw', 'Decision?')
    s.seq(aa, bu, r, g)
    s.flow(g, reject_with(p, s, 'dl_rj', 'CREDIT_POLICY_DECLINE'), "creditDecision == 'DECLINE'", 'decline')
    san = core(s, 'dl_sanction', 'Credit officer of the RE sanctions (core sanction)', 'sanction')
    s.flow(g, san, None, 'approve / refer', default=True)
    kfs = s.conn('dl_kfs', 'KFS with APR, cooling-off, recovery agents, grievance officers', 'docs.kfs', 'issue', 'submit',
                 'customerId,amount,tenureDays,aprPct,lspName', 'kfs', step='kfs')
    rej = rejection(p, s, 'dl_rjS')
    outcome(s, 'dl_sanGw', 'sanctionOutcome', {'SANCTIONED': kfs}, rej)
    s.flow(san, 'dl_sanGw')
    grp = s.expr('dl_group', 'Borrower group', "${execution.setVariable('borrowerGroup', 'external:'.concat(customerId))}")
    be, bd = s.sla_task('dl_accept', 'Accept KFS and loan agreement | केएफएस एवं ऋण अनुबंध स्वीकार करें', 'borrowerGroup',
                        form='sec-nbfc-digital-lending/accept', decision=('kfsDecision', ['accept', 'declined']),
                        fields=['remarks'], spec='${kfsValidity}', on_breach='DEEMED_REFUSAL', approve='accept',
                        refuse='declined', max_esc=0)
    s.seq(kfs, grp, be)
    ag = s.xgw('dl_acceptGw')
    s.flow(bd, ag)
    s.flow(ag, s.end('dl_endLapsed', 'Offer lapsed / declined'), "kfsDecision != 'accept'", 'no')
    ex = core(s, 'dl_disburse', 'Disburse to the borrower account only (core execution)', 'execution')
    s.flow(ag, ex, None, 'accepted', default=True)
    co = clock(s, 'dl_cooling', 'coolingOff', '${coolingOffSpec}')
    fail = s.notify('dl_failed', 'Disbursal failed', 'disbursal-failed', 'inapp', 'initiatorSeat')
    outcome(s, 'dl_exGw', 'executionOutcome', {'DONE': co}, fail)
    s.flow(ex, 'dl_exGw')
    s.flow(fail, s.end('dl_endFailed', 'Disbursal failed'))
    w = s.egw('dl_coolWait', 'Exit or cooling-off ends')
    s.flow(co, w)
    xm = s.catch_msg('dl_exit', 'Borrower exits in cooling-off', 'sangam.loan.exitCoolingOff')
    xt = s.catch_timer('dl_coolEnd', 'Cooling-off over', date='${coolingOffDueAt}')
    s.flow(w, xm)
    s.flow(w, xt)
    fc = s.conn('dl_foreclose', 'Foreclose: principal + proportionate APR, no penalty', 'lms.foreclose', 'foreclose',
                'submit', 'customerId,amount', 'exit', step='cooling-off-exit')
    s.seq(xm, fc, s.end('dl_endExited', 'Exited in cooling-off'))
    bk = s.conn('dl_book', 'Book loan', 'lms.book', 'book', 'submit', 'customerId,amount,tenureDays,aprPct', 'lms',
                step='book')
    cic = s.conn('dl_cic', 'Report to CICs', 'cic.report', 'report', 'submit', 'customerId,amount', 'cic', step='cic')
    s.seq(xt, bk, cic, end_ok(p, s, 'dl_end', 'Loan active'))
    s.side_exits('dl')
    pk.dmn('sec-nbfc-dl-policy', 'Digital credit policy (SYNTHETIC)', 'FIRST',
           [('Bureau score', 'bureauScore', 'integer'), ('Tenure (days)', 'tenureDays', 'integer')],
           [('creditDecision', 'Decision', 'string'), ('coolingOffSpec', 'Cooling-off', 'string'),
            ('kfsValidity', 'KFS validity', 'string')],
           [(['< 650', '-'], [q('DECLINE'), q('1D'), q('1WD')], ''),
            (['-', '>= 7'], [q('APPROVE'), q('3D'), q('3WD')], 'tenor 7 days or more: at least 3 days'),
            (['-', '-'], [q('APPROVE'), q('1D'), q('1WD')], 'tenor below 7 days: at least 1 day')])
    authority(pk, 'sec-nbfc-dl-authority', 'Digital loan authority', [('<= 200000', 'CREDITOFFICER', ''),
                                                                       ('-', 'CREDITHEAD', '')])
    powers(pk, 'sec-nbfc-dl-powers', [('CREDITOFFICER', 200000, ''), ('CREDITHEAD', 50000000, '')])
    simple_seeds(pk, 'NBFC-CREDIT', [('CREDIT-OFFICER', 'CREDITOFFICER', 'CREDIT-HEAD', 'Credit officer (RE)', 'ऋण अधिकारी'),
                                     ('CREDIT-HEAD', 'CREDITHEAD', None, 'Head of credit (RE)', 'ऋण प्रमुख')], 'ndl')


# ============================================================================================ capital markets
def cap_onboarding():
    pk = spack('cap', 'investor-onboarding', 'Investor onboarding (KRA KYC, demat and trading account)',
               'निवेशक पंजीकरण (केआरए केवाईसी, डीमैट एवं ट्रेडिंग खाता)', 'Capital markets',
               'Stock broker / depository participant onboarding: KRA fetch or fresh KYC with Aadhaar e-KYC or '
               'video IPV, PAN-Aadhaar link status, nomination or a recorded opt-out, segment activation by '
               'financial proof, maker-checker by compliance, KRA upload, UCC to the exchange, BO account at the '
               'depository, account opening kit.',
               ['SEC-CAP-001', 'SEC-CAP-002', 'SEC-CAP-004', 'UP-05', 'UP-22', 'GP-16'] + SIDE,
               [('kra.fetch', 'a', '', 'KRA download'), ('kra.upload', 'a', '', 'KRA upload'),
                ('pan.verify', 'a', '', 'PAN status and Aadhaar link'), ('uidai.ekyc', 'b', '', 'e-KYC'),
                ('exchange.ucc', 'a', '', 'UCC registration'), ('depository.openBo', 'a', '', 'BO account')])
    p = pk.proc('sangam-sec-cap-investor-onboarding', 'Investor onboarding', '',
                start={'materialFields': 'customerName,pan,segments'})
    s = p.root
    st = start_form(p, s, 'io_start', 'Application', 'sec-cap-investor-onboarding/application',
                    'Investor account opening | निवेशक खाता खोलना',
                    ['customerName*', 'pan*', 'mobile*', 'segments*', 'nominationOpted*', 'incomeRange', 'documents'])
    d = s.setv('io_defaults', 'Configuration',
               defaults='customerName=@customerId;segments=EQUITY;nominationOpted=true;incomeRange=BELOW_10L;'
                        'kraValidated=false;panOperative=true;caseType=ONBOARDING;amount=0;'
                        'routingDecisionKey=sec-cap-checker;powersDecisionKey=sec-cap-powers;issueOutput=true;'
                        'outputTemplate=account-opening-kit;appealAllowed=false;executionConnector=exchange.ucc;'
                        'executionStep=ucc;reconcileRequired=false')
    kr = s.conn('io_kra', 'Fetch KYC from KRA', 'kra.fetch', 'fetch', 'query', 'pan', 'kra')
    pv = s.conn('io_pan', 'PAN status and Aadhaar seeding', 'pan.verify', 'verify', 'query', 'pan,customerName', 'pan')
    s.seq(st, d, kr, pv)
    pg = s.xgw('io_panGw', 'PAN operative?')
    s.flow(pv, pg)
    s.flow(pg, reject_with(p, s, 'io_rjPan', 'PAN_INOPERATIVE'), '!panOperative', 'no')
    kg = s.xgw('io_kraGw', 'KRA KYC validated?')
    s.flow(pg, kg, None, 'yes', default=True)
    ek = s.conn('io_ekyc', 'Aadhaar e-KYC', 'uidai.ekyc', 'otp-ekyc', 'query', 'customerName,mobile', 'ekyc')
    ipv = s.user('io_ipv', 'In-person / video verification (IPV / VIPV) | व्यक्तिगत / वीडियो सत्यापन', 'KYC-OFFICER',
                 form='sec-cap-investor-onboarding/ipv', decision=None,
                 fields=['livenessChecked*', 'geoTag', 'vcipRecordingRef', 'remarks'])
    s.flow(kg, ek, '!kraValidated', 'fresh KYC')
    s.flow(ek, ipv)
    kj = s.xgw('io_kycJoin')
    s.flow(kg, kj, None, 'validated', default=True)
    s.flow(ipv, kj)
    nom = s.user('io_optout', 'Record declaration to opt out of nomination | नामांकन से बाहर रहने की घोषणा', 'KYC-OFFICER',
                 form='sec-cap-investor-onboarding/optout', decision=None, fields=['documents*', 'remarks'])
    g2, m2 = maybe(s, 'io_nom', '!nominationOpted', nom, nom, 'Opted out of nomination?')
    s.flow(kj, g2)
    seg = s.rule('io_segments', 'Segments allowed (DMN)', 'sec-cap-segments')
    s.flow(m2, seg)
    san = core(s, 'io_check', 'Compliance checker approves (core sanction)', 'sanction')
    s.flow(seg, san)
    up = s.conn('io_kraUpload', 'Upload / modify KYC at KRA', 'kra.upload', 'upload', 'submit', 'pan,customerName', 'kraUp',
                step='kra-upload')
    rej = rejection(p, s, 'io_rj')
    outcome(s, 'io_sanGw', 'sanctionOutcome', {'SANCTIONED': up}, rej)
    s.flow(san, 'io_sanGw')
    ucc = core(s, 'io_ucc', 'Register UCC with the exchange (core execution)', 'execution')
    bo = s.conn('io_bo', 'Open BO account at the depository', 'depository.openBo', 'open', 'submit', 'pan,customerName',
                'bo', step='bo')
    cl = core(s, 'io_close', 'Account opening kit and closure (core)', 'closure')
    s.seq(up, ucc, bo, cl, end_ok(p, s, 'io_end', 'Account active'))
    s.side_exits('io')
    pk.dmn('sec-cap-segments', 'Segment activation', 'FIRST', [('Income range', 'incomeRange', 'string')],
           [('segmentsAllowed', 'Segments', 'string')],
           [([q('BELOW_10L')], [q('EQUITY,MF')], 'derivatives need proof of financial standing'),
            (['-'], [q('EQUITY,MF,FNO,COMMODITY')], '')])
    authority(pk, 'sec-cap-checker', 'Onboarding checker', [('-', 'COMPLIANCE', '')])
    powers(pk, 'sec-cap-powers', [('COMPLIANCE', 1000000000, '')])
    simple_seeds(pk, 'BROKER-DP', [('KYC-OFFICER', 'KYC', 'COMPLIANCE-OFFICER', 'KYC officer', 'केवाईसी अधिकारी'),
                                   ('COMPLIANCE-OFFICER', 'COMPLIANCE', None, 'Compliance officer', 'अनुपालन अधिकारी')], 'cio')


# ============================================================================================ insurance
def ins_proposal():
    pk = spack('ins', 'proposal-underwriting', 'Proposal, underwriting and policy issuance',
               'प्रस्ताव, हामीदारी एवं पॉलिसी जारी करना', 'Insurance',
               'Proposal decided within the IRDAI time limit: KYC, underwriting rule (straight-through or manual), '
               'medicals at the insurer\'s cost, decision with reasons (accept, accept with loading after the '
               'proposer\'s consent, postpone, decline), premium collected once, policy and e-insurance account, '
               'free-look cancellation with refund less proportionate risk premium and expenses.',
               ['SEC-INS-001', 'SEC-INS-002', 'SEC-INS-003', 'SEC-INS-004', 'GP-06', 'GP-12', 'GP-29', 'UP-06',
                'UP-08'] + SIDE,
               [('ckycr.search', 'a', '', 'CKYC'), ('tpa.medicals', 'a', '', 'Medical examination schedule'),
                ('pg.collect', 'b', '', 'Premium collection'), ('policy.issue', 'a', '', 'Policy and e-IA'),
                ('pg.refund', 'a', '', 'Free-look refund')])
    p = pk.proc('sangam-sec-ins-proposal', 'Proposal to policy', '',
                start={'materialFields': 'customerName,productCode,sumAssured,age'})
    s = p.root
    st = start_form(p, s, 'ip_start', 'Proposal received', 'sec-ins-proposal-underwriting/proposal',
                    'Insurance proposal | बीमा प्रस्ताव',
                    ['customerName*', 'productCode*', 'sumAssured*', 'age*', 'smoker', 'medicalDisclosures', 'applicantRef',
                     'documents'])
    d = s.setv('ip_defaults', 'Configuration',
               defaults='customerName=@customerId;productCode=TERM;sumAssured=1000000;age=35;smoker=false;'
                        'medicalDisclosures=NONE;uwSeat=UNDERWRITER;executionConnector=pg.collect;'
                        'executionStep=premium;verifyApproval=false;reconcileRequired=false;'
                        'executionPayloadFields=customerName,premiumAmount;premiumAmount=0;applicantRef=@customerId;'
                        'appealAllowed=false')
    ky = s.conn('ip_kyc', 'KYC (CKYC)', 'ckycr.search', 'search', 'query', 'customerName', 'ckyc')
    uw = s.rule('ip_rule', 'Underwriting route (DMN)', 'sec-ins-uw')
    g = s.xgw('ip_routeGw', 'Straight-through?')
    s.seq(st, d, ky, uw, g)
    mg = s.xgw('ip_medGw', 'Medicals needed?')
    s.flow(g, mg, "uwRoute == 'MANUAL'", 'manual')
    md = s.conn('ip_medicals', 'Schedule medicals (insurer\'s cost)', 'tpa.medicals', 'schedule', 'submit',
                'customerName,age,sumAssured', 'med', step='medicals')
    s.flow(mg, md, 'medicalRequired', 'yes')
    mw = s.egw('ip_medWait', 'Report or timeout')
    s.flow(md, mw)
    mr = s.catch_msg('ip_medReport', 'Medical report', 'sangam.ins.medicalReport')
    mt = s.catch_timer('ip_medTimeout', 'No medicals in 30 days', duration='P30D')
    s.flow(mw, mr)
    s.flow(mw, mt)
    s.flow(mt, s.end('ip_endClosed', 'Closed: medicals not done (proposer informed)'))
    ue, ud = s.sla_task('ip_uw', 'Underwriting decision | हामीदारी निर्णय', 'uwSeat',
                        form='sec-ins-proposal-underwriting/decide',
                        decision=('uwDecision', ['accept', 'acceptLoaded', 'postpone', 'reject']),
                        fields=['loadingPct', 'premiumAmount*', 'remarks*', 'rejectionReasonCodes'], spec='${decisionSpec}',
                        sod=True)
    s.flow(mg, ue, None, 'no', default=True)
    s.flow(mr, ue)
    stp = s.setv('ip_stp', 'Standard terms', values='uwDecision=accept')
    s.flow(g, stp, None, 'straight-through', default=True)
    ug = s.xgw('ip_uwGw')
    s.flow(ud, ug)
    s.flow(stp, ug)
    grp = s.expr('ip_group', 'Proposer group', "${execution.setVariable('proposerGroup', 'external:'.concat(customerName))}")
    s.flow(ug, grp, "uwDecision == 'acceptLoaded'", 'counter-offer')
    ce, cd = s.sla_task('ip_consent', 'Proposer accepts revised terms | प्रस्तावक द्वारा संशोधित शर्तों की स्वीकृति',
                        'proposerGroup', form='sec-ins-proposal-underwriting/consent',
                        decision=('counterDecision', ['accept', 'declined']), fields=['remarks'], spec='15D',
                        on_breach='DEEMED_REFUSAL', approve='accept', refuse='declined', max_esc=0)
    s.flow(grp, ce)
    cg = s.xgw('ip_consGw')
    s.flow(cd, cg)
    pay = core(s, 'ip_premium', 'Collect premium once (core execution)', 'execution')
    s.flow(ug, pay, "uwDecision == 'accept'", 'accept')
    s.flow(cg, pay, "counterDecision == 'accept'", 'accepted')
    s.flow(cg, s.end('ip_endDeclined', 'Counter-offer declined'), None, 'declined', default=True)
    s.flow(ug, s.end('ip_endPostponed', 'Postponed (reasons communicated)'), "uwDecision == 'postpone'", 'postpone')
    rej = rejection(p, s, 'ip_rj')
    s.flow(ug, rej, None, 'decline', default=True)
    iss = s.conn('ip_issue', 'Issue policy and credit the e-insurance account', 'policy.issue', 'issue', 'submit',
                 'customerName,productCode,sumAssured', 'policy', step='issue')
    pf = s.notify('ip_payFailed', 'Premium not received', 'premium-failed', 'sms,email', 'applicantRef')
    outcome(s, 'ip_payGw', 'executionOutcome', {'DONE': iss}, pf)
    s.flow(pay, 'ip_payGw')
    s.flow(pf, s.end('ip_endUnpaid', 'Not issued: premium not received'))
    fl = clock(s, 'ip_freeLook', 'freeLook', '${freeLookSpec}')
    s.flow(iss, fl)
    w = s.egw('ip_flWait', 'Free-look cancellation or period ends')
    s.flow(fl, w)
    fc = s.catch_msg('ip_flCancel', 'Free-look cancellation', 'sangam.ins.freeLookCancel')
    ft = s.catch_timer('ip_flEnd', 'Free-look over', date='${freeLookDueAt}')
    s.flow(w, fc)
    s.flow(w, ft)
    rf = s.conn('ip_refund', 'Refund premium less risk premium, medicals and stamp duty', 'pg.refund', 'refund', 'submit',
                'customerName,premiumAmount', 'flRefund', step='free-look-refund')
    s.seq(fc, rf, s.end('ip_endFreeLook', 'Cancelled in free-look'))
    s.flow(ft, end_ok(p, s, 'ip_end', 'Policy in force'))
    s.side_exits('ip')
    pk.dmn('sec-ins-uw', 'Underwriting route (SYNTHETIC grid)', 'FIRST',
           [('Age', 'age', 'integer'), ('Sum assured', 'sumAssured', 'money'), ('Smoker', 'smoker', 'boolean')],
           [('uwRoute', 'Route', 'string'), ('medicalRequired', 'Medicals', 'boolean'),
            ('decisionSpec', 'Decide within', 'string'), ('freeLookSpec', 'Free-look period', 'string')],
           [(['> 50', '-', '-'], [q('MANUAL'), 'true', q('7D'), q('30D')], 'above 50: medicals'),
            (['-', '> 10000000', '-'], [q('MANUAL'), 'true', q('7D'), q('30D')], 'above 1 crore'),
            (['-', '-', 'true'], [q('MANUAL'), 'false', q('7D'), q('30D')], 'smoker: manual'),
            (['-', '-', '-'], [q('STP'), 'false', q('7D'), q('30D')], 'decide within 7 days of the complete proposal; free-look 30 days (verify)')])
    simple_seeds(pk, 'UW', [('UNDERWRITER', 'UNDERWRITER', 'CHIEF-UW', 'Underwriter', 'हामीदार'),
                            ('CHIEF-UW', 'CHIEFUW', None, 'Chief underwriter', 'मुख्य हामीदार')], 'iip')


def ins_motor_claim():
    pk = spack('ins', 'motor-claim', 'Motor claim: own damage, theft and third party', 'मोटर दावा: स्वयं क्षति, चोरी एवं तृतीय पक्ष',
               'Insurance',
               'FNOL, cover check, fraud score, surveyor appointed within 24 hours for claims at or above the '
               'licensed-surveyor threshold, survey report within 15 days, assessment with SIU referral, '
               'settlement sanctioned and paid within the time limit after the report (cashless to the garage or '
               'reimbursement), interest for delay, salvage; theft after the untraced report with subrogation; '
               'third party to MACT; rejection with reasons and the Ombudsman route.',
               ['SEC-INS-005', 'SEC-INS-006', 'SEC-INS-007', 'GP-06', 'GP-12', 'GP-17', 'UP-04', 'UP-06', 'UP-09'] + SIDE,
               [('policy.coverCheck', 'a', '', 'Policy in force'), ('fraud.score', 'a', '', 'Fraud analytics'),
                ('claims.pay', 'c', '', 'Claim payment'), ('salvage.auction', 'a', '', 'Salvage disposal')])
    p = pk.proc('sangam-sec-ins-motor-claim', 'Motor claim', '',
                start={'materialFields': 'policyNo,vehicleRegNo,lossType,claimAmount'})
    s = p.root
    st = start_form(p, s, 'mc_start', 'Claim intimated (FNOL)', 'sec-ins-motor-claim/fnol', 'Motor claim intimation | मोटर दावा सूचना',
                    ['policyNo*', 'vehicleRegNo*', 'lossType*', 'claimAmount*', 'lossDate', 'garageId', 'firNo', 'photos'])
    d = s.setv('mc_defaults', 'Configuration',
               defaults='vehicleRegNo=@policyNo;lossType=OWN_DAMAGE;claimAmount=@amount;coverActive=true;fraudScore=10;'
                        'totalLoss=false;amount=@claimAmount;caseType=CLAIM;routingDecisionKey=sec-ins-claim-authority;'
                        'powersDecisionKey=sec-ins-claim-powers;sanctionSla=7D;executionConnector=claims.pay;'
                        'executionStep=pay-claim;reconcileRequired=true;executionPayloadFields=policyNo,amount,garageId;'
                        'garageId=REIMBURSEMENT;outputTemplate=settlement-letter;appellateSeat=GRIEVANCE-CELL;'
                        'handlerSeat=CLAIMS-HANDLER')
    cv = s.conn('mc_cover', 'Cover check', 'policy.coverCheck', 'check', 'query', 'policyNo,lossDate', 'cover')
    cg = s.xgw('mc_coverGw', 'Policy in force?')
    s.seq(st, d, cv, cg)
    s.flow(cg, reject_with(p, s, 'mc_rjCover', 'POLICY_NOT_IN_FORCE'), '!coverActive', 'no')
    fr = s.conn('mc_fraud', 'Fraud score', 'fraud.score', 'score', 'query', 'policyNo,vehicleRegNo,claimAmount', 'fraud')
    tr = s.rule('mc_triage', 'Claim triage (DMN)', 'sec-ins-motor-triage')
    s.flow(cg, fr, None, 'yes', default=True)
    lg = s.xgw('mc_typeGw', 'Loss type?')
    s.seq(fr, tr, lg)
    tp = s.user('mc_tp', 'Third-party claim: defend or settle before MACT | तृतीय पक्ष दावा: एमएसीटी में बचाव या समझौता',
                'TP-LEGAL', form='sec-ins-motor-claim/tp', decision=None, fields=['caseNo', 'court', 'remarks'])
    s.flow(lg, tp, "lossType == 'THIRD_PARTY'", 'third party')
    s.flow(tp, s.end('mc_endTp', 'Handled before MACT'))
    th = s.egw('mc_theftWait', 'Untraced report or recovery')
    s.flow(lg, th, "lossType == 'THEFT'", 'theft')
    ut = s.catch_msg('mc_untraced', 'Police untraced report', 'sangam.ins.untracedReport')
    rc = s.catch_msg('mc_recovered', 'Vehicle recovered', 'sangam.ins.vehicleRecovered')
    s.flow(th, ut)
    s.flow(th, rc)
    sub = s.user('mc_subrogation', 'Collect RC transfer, keys and subrogation letter | आरसी अंतरण, चाबियाँ एवं प्रत्यासन पत्र',
                 '${handlerSeat}', form='sec-ins-motor-claim/subrogation', decision=None, fields=['documents*'])
    s.flow(ut, sub)
    sv = s.xgw('mc_survGw', 'Surveyor required?')
    s.flow(lg, sv, None, 'own damage', default=True)
    s.flow(rc, sv)
    ae, ad = s.sla_task('mc_appoint', 'Appoint surveyor (24 hours) | सर्वेक्षक नियुक्त करें (24 घंटे)', 'handlerSeat',
                        form='sec-ins-motor-claim/appoint', decision=None, fields=['surveyorId*'], spec='${surveyorAppointSpec}')
    s.flow(sv, ae, 'surveyorRequired', 'yes')
    se, sd = s.sla_task('mc_survey', 'Survey report (15 days) | सर्वेक्षण रिपोर्ट (15 दिन)', 'surveyorGroup',
                        form='sec-ins-motor-claim/survey', decision=None,
                        fields=['assessedLoss*', 'totalLoss', 'photos', 'findings*'], spec='${surveyReportSpec}')
    sg = s.setv('mc_survSeat', 'Surveyor', values='surveyorGroup=@surveyorId')
    s.seq(ad, sg, se)
    sj = s.xgw('mc_siuGw', 'SIU referral?')
    s.flow(sd, sj)
    s.flow(sv, sj, None, 'below the surveyor threshold: desk assessment', default=True)
    s.flow(sub, sj)
    siu = s.user('mc_siu', 'SIU investigation | विशेष जाँच इकाई जाँच', 'SIU', form='sec-ins-motor-claim/siu',
                 decision=('siuDecision', ['genuine', 'fraud']), fields=['findings*'])
    s.flow(sj, siu, 'siuReferral', 'yes')
    asg = s.user('mc_assess', 'Assess claim | दावे का आकलन', '${handlerSeat}', form='sec-ins-motor-claim/assess',
                 decision=('assessDecision', ['approve', 'reject']),
                 fields=['assessedLoss*', 'amount*', 'rejectionReasonCodes', 'remarks'], sod=True)
    s.flow(sj, asg, None, 'no', default=True)
    siug = s.xgw('mc_siuRes')
    s.flow(siu, siug)
    s.flow(siug, asg, "siuDecision == 'genuine'", 'genuine')
    rej = rejection(p, s, 'mc_rj')
    s.flow(siug, rej, None, 'fraud', default=True)
    san = core(s, 'mc_sanction', 'Settlement sanction (core sanction, 7 days)', 'sanction')
    ag = s.xgw('mc_assGw')
    s.flow(asg, ag)
    s.flow(ag, san, "assessDecision == 'approve'", 'approve')
    s.flow(ag, rej, None, 'reject', default=True)
    pay = core(s, 'mc_pay', 'Pay garage / insured (core execution)', 'execution')
    outcome(s, 'mc_sanGw', 'sanctionOutcome', {'SANCTIONED': pay, 'RETURNED': asg}, rej)
    s.flow(san, 'mc_sanGw')
    salv = s.conn('mc_salvage', 'Dispose salvage', 'salvage.auction', 'auction', 'submit', 'vehicleRegNo', 'salvage',
                  step='salvage')
    cl = core(s, 'mc_close', 'Settlement letter and closure (core)', 'closure')
    g3, m3 = maybe(s, 'mc_salvOpt', 'totalLoss', salv, salv, 'Total loss?')
    pf = s.notify('mc_payFailed', 'Payment failed', 'claim-payment-failed', 'inapp', 'initiatorSeat')
    outcome(s, 'mc_payGw', 'executionOutcome', {'DONE': g3}, pf)
    s.flow(pay, 'mc_payGw')
    s.flow(pf, san)
    s.seq(m3, cl, end_ok(p, s, 'mc_end', 'Settled'))
    s.side_exits('mc')
    pk.dmn('sec-ins-motor-triage', 'Motor claim triage (SYNTHETIC)', 'FIRST',
           [('Claim amount', 'claimAmount', 'money'), ('Fraud score', 'fraudScore', 'integer')],
           [('surveyorRequired', 'Licensed surveyor', 'boolean'), ('siuReferral', 'SIU', 'boolean'),
            ('surveyorAppointSpec', 'Appoint surveyor within', 'string'), ('surveyReportSpec', 'Survey report within', 'string'),
            ('sanctionSla', 'Settle within (after the report)', 'string')],
           [(['-', '>= 70'], ['true', 'true', q('1D'), q('15D'), q('7D')], 'high fraud score'),
            (['>= 50000', '-'], ['true', 'false', q('1D'), q('15D'), q('7D')], 'licensed surveyor at or above the threshold (Insurance Act s.64UM)'),
            (['-', '-'], ['false', 'false', q('1D'), q('15D'), q('7D')],
             'desk assessment; survey report 15 days, settlement 7 days after it (PPHI 2024, verify)')])
    authority(pk, 'sec-ins-claim-authority', 'Claim settlement authority', [
        ('<= 200000', 'CLAIMSMGR', ''), ('<= 2500000', 'ZONALCLAIMS', ''), ('-', 'CLAIMSHEAD', '')])
    powers(pk, 'sec-ins-claim-powers', [('CLAIMSMGR', 200000, ''), ('ZONALCLAIMS', 2500000, ''),
                                        ('CLAIMSHEAD', 1000000000, '')])
    simple_seeds(pk, 'CLAIMS-MOTOR', [
        ('CLAIMS-HANDLER', 'HANDLER', 'CLAIMS-MGR', 'Claims handler', 'दावा संचालक'),
        ('SIU', 'SIU', 'CLAIMS-HEAD', 'Special investigation unit', 'विशेष जाँच इकाई'),
        ('TP-LEGAL', 'LEGAL', 'CLAIMS-HEAD', 'Third-party legal desk', 'तृतीय पक्ष विधि डेस्क'),
        ('CLAIMS-MGR', 'CLAIMSMGR', 'ZONAL-CLAIMS', 'Claims manager', 'दावा प्रबंधक'),
        ('ZONAL-CLAIMS', 'ZONALCLAIMS', 'CLAIMS-HEAD', 'Zonal claims head', 'क्षेत्रीय दावा प्रमुख'),
        ('CLAIMS-HEAD', 'CLAIMSHEAD', None, 'Head of claims', 'दावा प्रमुख'),
        ('GRIEVANCE-CELL', 'GRIEVANCE', None, 'Grievance cell (review)', 'शिकायत प्रकोष्ठ (पुनर्विचार)')], 'imc')


def ins_health_cashless():
    pk = spack('ins', 'health-claim', 'Health claim: cashless pre-authorisation, discharge and reimbursement',
               'स्वास्थ्य दावा: कैशलेस पूर्व-प्राधिकरण, डिस्चार्ज एवं प्रतिपूर्ति', 'Insurance',
               'IRDAI Master Circular on Health Insurance (29 May 2024): cashless authorisation decided within one '
               'hour of the request, enhancements likewise, final discharge authorisation within three hours (any '
               'overrun charge borne by the insurer), settlement through NHCX; reimbursement decided within the '
               'time limit after the last document; rejection with reasons reviewed by the claims review committee.',
               ['SEC-INS-008', 'SEC-INS-009', 'SEC-HEALTH-005', 'UP-17', 'GP-06', 'GP-15', 'GP-35'] + SIDE,
               [('policy.coverCheck', 'a', '', 'Cover'), ('hcx.eligibility', 'a', '', 'NHCX coverage eligibility'),
                ('hcx.preauthReply', 'b', '', 'Pre-authorisation reply to the hospital'),
                ('hcx.settle', 'c', '', 'Claim settlement')])
    p = pk.proc('sangam-sec-ins-health-claim', 'Health claim', '',
                start={'materialFields': 'policyNo,patientName,hospitalId,estimatedCost'})
    s = p.root
    st = start_form(p, s, 'hc_start', 'Pre-authorisation / claim request', 'sec-ins-health-claim/request',
                    'Health claim request | स्वास्थ्य दावा अनुरोध',
                    ['policyNo*', 'patientName*', 'hospitalId*', 'admissionType*', 'estimatedCost*', 'diagnosis',
                     'abhaNumber', 'documents'])
    d = s.setv('hc_defaults', 'Configuration',
               defaults='patientName=@policyNo;hospitalId=NETWORK-HOSP-1;admissionType=EMERGENCY;estimatedCost=@amount;'
                        'coverActive=true;networkHospital=true;tpaSeat=TPA-DOCTOR;executionConnector=hcx.settle;'
                        'executionStep=settle;verifyApproval=false;reconcileRequired=false;'
                        'executionPayloadFields=policyNo,hospitalId,approvedAmount;approvedAmount=@estimatedCost;'
                        'appellateSeat=CLAIMS-REVIEW-COMMITTEE')
    tat = s.rule('hc_tat', 'Health claim timelines (DMN)', 'sec-ins-health-tat')
    cv = s.conn('hc_cover', 'Cover and NHCX eligibility', 'hcx.eligibility', 'eligibility', 'query',
                'policyNo,hospitalId', 'cover')
    cg = s.xgw('hc_coverGw', 'Covered?')
    s.seq(st, d, tat, cv, cg)
    s.flow(cg, reject_with(p, s, 'hc_rjCover', 'NOT_COVERED'), '!coverActive', 'no')
    ng = s.xgw('hc_netGw', 'Cashless at a network hospital?')
    s.flow(cg, ng, None, 'yes', default=True)
    pe, pd = s.sla_task('hc_preauth', 'Cashless pre-authorisation (1 hour) | कैशलेस पूर्व-प्राधिकरण (1 घंटा)', 'tpaSeat',
                        form='sec-ins-health-claim/preauth', decision=('preauthDecision', ['approve', 'clarify', 'reject']),
                        fields=['approvedAmount*', 'remarks', 'rejectionReasonCodes'], spec='${preauthSpec}', on_breach='ESCALATE')
    s.flow(ng, pe, 'networkHospital', 'cashless')
    pg = s.xgw('hc_preGw')
    s.flow(pd, pg)
    q1 = s.conn('hc_query', 'Query to the hospital', 'hcx.preauthReply', 'query', 'submit', 'policyNo,hospitalId', 'hq',
                step='query')
    qr = s.catch_msg('hc_queryReply', 'Hospital replies', 'sangam.hcx.queryReply')
    s.flow(pg, q1, "preauthDecision == 'clarify'", 'query')
    s.seq(q1, qr, pe)
    rej = rejection(p, s, 'hc_rj')
    s.flow(pg, rej, "preauthDecision == 'reject'", 'reject')
    ar = s.conn('hc_reply', 'Authorisation to the hospital', 'hcx.preauthReply', 'approve', 'submit',
                'policyNo,hospitalId,approvedAmount', 'auth', step='authorise')
    s.flow(pg, ar, None, 'approve', default=True)
    w = s.egw('hc_stay', 'Enhancement or discharge')
    s.flow(ar, w)
    en = s.catch_msg('hc_enh', 'Enhancement request', 'sangam.hcx.enhancement')
    dr = s.catch_msg('hc_dis', 'Discharge request', 'sangam.hcx.dischargeRequest')
    s.flow(w, en)
    s.flow(w, dr)
    ee, ed = s.sla_task('hc_enhance', 'Enhancement (1 hour) | वृद्धि अनुरोध (1 घंटा)', 'tpaSeat',
                        form='sec-ins-health-claim/enhance', decision=None, fields=['approvedAmount*', 'remarks'], spec='${preauthSpec}',
                        on_breach='ESCALATE')
    s.flow(en, ee)
    s.flow(ed, w)
    de, dd = s.sla_task('hc_discharge', 'Final discharge authorisation (3 hours) | अंतिम डिस्चार्ज प्राधिकरण (3 घंटे)', 'tpaSeat',
                        form='sec-ins-health-claim/discharge', decision=None,
                        fields=['approvedAmount*', 'remarks'], spec='${dischargeSpec}', on_breach='ESCALATE',
                        doc='The patient is never made to wait; charges for delay beyond three hours are borne by the '
                            'insurer from the shareholders\' fund.')
    s.flow(dr, de)
    pay = core(s, 'hc_settle', 'Settle with the hospital (core execution)', 'execution')
    s.flow(dd, pay)
    sub = s.expr('hc_insGroup', 'Insured group', "${execution.setVariable('insuredGroup', 'external:'.concat(policyNo))}")
    s.flow(ng, sub, None, 'reimbursement', default=True)
    re_, rd = s.sla_task('hc_docs', 'Submit claim documents | दावा दस्तावेज़ प्रस्तुत करें', 'insuredGroup',
                         form='sec-ins-health-claim/documents', decision=None, fields=['documents*', 'estimatedCost'],
                         spec='30D', max_esc=0)
    s.flow(sub, re_)
    ae, ad = s.sla_task('hc_assess', 'Assess reimbursement claim | प्रतिपूर्ति दावे का आकलन', 'tpaSeat',
                        form='sec-ins-health-claim/assess', decision=('reimbDecision', ['approve', 'reject']),
                        fields=['approvedAmount*', 'rejectionReasonCodes', 'remarks'], spec='${reimbursementSpec}', sod=True)
    s.flow(rd, ae)
    rg = s.xgw('hc_reimbGw')
    s.flow(ad, rg)
    s.flow(rg, pay, "reimbDecision == 'approve'", 'approve')
    s.flow(rg, rej, None, 'reject', default=True)
    pf = s.notify('hc_payFailed', 'Settlement failed', 'claim-payment-failed', 'inapp', 'initiatorSeat')
    outcome(s, 'hc_payGw', 'executionOutcome', {'DONE': end_ok(p, s, 'hc_end', 'Settled')}, pf)
    s.flow(pay, 'hc_payGw')
    s.flow(pf, s.end('hc_endPayFailed', 'Settlement failed (reconciliation)'))
    s.side_exits('hc')
    pk.dmn('sec-ins-health-tat', 'Health claim timelines (IRDAI Master Circular on Health Insurance 2024)', 'FIRST',
           [('Admission type', 'admissionType', 'string')],
           [('preauthSpec', 'Cashless decision within', 'string'), ('dischargeSpec', 'Discharge authorisation within', 'string'),
            ('reimbursementSpec', 'Reimbursement decision within', 'string')],
           [(['-'], [q('1H'), q('3H'), q('15D')], '1 hour / 3 hours (primary); reimbursement 15 days (SYNTHETIC, within the 30-day limit)')])
    simple_seeds(pk, 'TPA', [('TPA-DOCTOR', 'TPADOCTOR', 'TPA-HEAD', 'TPA medical officer', 'टीपीए चिकित्सा अधिकारी'),
                             ('TPA-HEAD', 'TPAHEAD', None, 'Head of health claims', 'स्वास्थ्य दावा प्रमुख'),
                             ('CLAIMS-REVIEW-COMMITTEE', 'CRC', None, 'Claims review committee', 'दावा समीक्षा समिति')],
                 'ihc')


def build():
    for f in (bank_account_opening, bank_rekyc, bank_loan_origination, bank_collections, bank_loan_closure,
              bank_payment, bank_aml, bank_emandate, bank_cheque, bank_dispute, fin_complaint, fin_cyber,
              nbfc_digital_lending, cap_onboarding, ins_proposal, ins_motor_claim, ins_health_cashless):
        f()
