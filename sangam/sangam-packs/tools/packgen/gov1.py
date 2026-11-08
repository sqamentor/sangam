# SPDX-License-Identifier: Apache-2.0
"""Pilot packs P1-P4 (IX.4) and the DBT batch (GP-49)."""
from lib import C, q
from packs import Pack
from common import core, outcome, end_ok, rejection, reject_with, withdraw, revise, batch_loop

SIDE = ['GP-14', 'VI.5']


# ---------------------------------------------------------------------------------------------------------------- P1
def p1():
    pk = Pack('gov.p1.bill-payment', 'p1-bill-payment', 'P1 Bill / payment approval', 'पी1 बिल / भुगतान अनुमोदन',
              'IFMS', 'IFMIS-style bill: draft, duplicate and budget checks with encumbrance, checker selected from '
              'the eligible list, n-level recommendation, approver by amount band (delegation of financial powers), '
              'optional joint approval, signed approval bound to the payload hash, payment file to PFMS/IFMIS with '
              'operationId, acknowledgement, bank reconciliation, payment advice and voucher (IX.4 P1, X.4.3).',
              ['AC-01', 'AC-02', 'AC-03', 'GP-01', 'GP-11', 'GP-12', 'GP-18', 'GP-19', 'GP-29', 'GP-33', 'GP-34',
               'GP-50', 'J-P1-H', 'J-P1-E01', 'J-P1-E02', 'J-P1-E03', 'J-P1-E04', 'J-P1-E05', 'J-P1-E06',
               'J-P1-E07', 'J-P1-E08', 'J-P1-E09', 'J-P1-E10', 'J-P1-E11', 'J-P1-E12', 'J-P1-E13', 'J-P1-E14',
               'R58', 'D-IFMS-BILL'] + SIDE,
              connectors=[('ifms.duplicateBill', 'a', 'G-PFMS', 'Duplicate-bill check (read-only)'),
                          ('ifms.budget', 'a', 'G-PFMS', 'Budget-head availability (read-only)'),
                          ('ifms.encumber', 'a', 'G-PFMS', 'Commitment control: encumbrance (GP-11)'),
                          ('ifms.release', 'a', 'G-PFMS', 'Release of the encumbrance on cancel'),
                          ('pfms.payment', 'c', 'G-PFMS', 'Signed payment file with SSID (SFTP / API)'),
                          ('bank.statement', 'b', 'G-PFMS', 'Bank debit confirmation by operationId'),
                          ('esign.sign', 'b', 'G-SIGN', 'Approver signature'),
                          ('notify.sms', 'b', 'G-DLT', 'Payee SMS on a -G header')],
              main=['sangam-p1-bill-payment'])
    p = pk.proc('sangam-p1-bill-payment', 'P1 Bill / payment approval',
                'Pack P1. Material fields: payee, account, ifsc, amount, budgetHead, sanctionRef (K4).',
                start={'materialFields': 'payee,account,ifsc,amount,budgetHead,sanctionRef'})
    s = p.root
    st = s.start('p1_start', 'Bill submitted', form='p1-bill-payment/bill-draft')
    p.forms.append(dict(key='p1-bill-payment/bill-draft', name='Bill for payment | भुगतान हेतु बिल', decision=None,
                        fields=['payee*', 'account*', 'ifsc*', 'amount*', 'invoiceNo*', 'invoiceDate', 'budgetHead*',
                                'sanctionRef', 'billType', 'selectedSeat', 'documents'], doc=None))
    d = s.setv('p1_defaults', 'Pack configuration', defaults=(
        'caseType=BILL;checkerRole=AO;fallbackSeat=ACCTS-HEAD;scrutinySla=3WD;sanctionSla=3WD;'
        'routingDecisionKey=p1-competent-authority;powersDecisionKey=sangam-core-powers;signatureRequired=true;'
        'executionConnector=pfms.payment;executionOperation=submitPaymentFile;executionStep=payment;'
        'reconcileRequired=true;reconcileConnector=bank.statement;ackWindow=PT4H;outputTemplate=payment-advice;'
        'closureTemplate=bill-paid;retentionCategory=C;retentionYears=10;appealAllowed=false;'
        'releaseConnector=ifms.release;jointApprovalThreshold=10000000;jointApprovalSeats=SE-CIRCLE,CE-ZONE;'
        'recommendationSeats=;dupDuplicate=false;budgetAvailable=0;applicantRef=@payee'))
    s.seq(st, d)
    dup = s.conn('p1_dupCheck', 'Duplicate-bill check', 'ifms.duplicateBill', 'check', 'query',
                 'payee,invoiceNo,amount', 'dup')
    s.flow(d, dup)
    dg = s.xgw('p1_dupGw', 'Similar bill exists?')
    s.flow(dup, dg)
    dr = s.user('p1_dupReview', 'Similar bill exists: pass with reason or reject | समान बिल विद्यमान: कारण सहित पारित करें या अस्वीकार करें',
                '${fallbackSeat}', form='p1-bill-payment/duplicate-review', decision=('dupDecision', ['proceed', 'reject']),
                fields=['duplicateOverrideReason*', 'remarks'], sod=True)
    s.flow(dg, dr, 'dupDuplicate', 'J-P1-E03')
    bq = s.conn('p1_budget', 'Budget-head availability', 'ifms.budget', 'available', 'query', 'budgetHead,amount',
                'budget')
    s.flow(dg, bq, None, 'no duplicate', default=True)
    dgr = s.xgw('p1_dupDecGw', 'Proceed?')
    s.flow(dr, dgr)
    s.flow(dgr, bq, "dupDecision == 'proceed'", 'proceed')
    rej_dup = reject_with(p, s, 'p1_rjDup', 'DUPLICATE_BILL')
    s.flow(dgr, rej_dup, None, 'reject', default=True)
    bg = s.xgw('p1_budgetGw', 'Budget sufficient?')
    s.flow(bq, bg)
    enc = s.conn('p1_encumber', 'Encumber budget (commitment control)', 'ifms.encumber', 'encumber', 'submit',
                 'budgetHead,amount,sanctionRef', 'encumber', step='encumber')
    s.flow(bg, enc, 'budgetAvailable >= amount', 'yes')
    short = s.notify('p1_budgetShort', 'Tell initiator: budget insufficient', 'budget-insufficient', 'inapp',
                     'initiatorSeat')
    s.flow(bg, short, None, 'J-P1-E11', default=True)
    cand = s.svc('p1_checker', 'Eligible checker (S07)', C['SEAT'],
                 {'op': 'CANDIDATES', 'roleVar': 'checkerRole', 'targetVar': 'checkerSeat',
                  'fallbackSeat': '${fallbackSeat}'})
    cs = s.setv('p1_checkerSeat', 'Checker seat', values='scrutinySeat=@checkerSeat')
    scr = core(s, 'p1_scrutiny', 'Checker scrutiny (core)', 'scrutiny')
    s.seq(enc, cand, cs, scr)
    rev = revise(p, s, 'p1_revise', 'Revise bill | बिल संशोधित करें',
                 ['payee', 'account', 'ifsc', 'amount', 'invoiceNo', 'budgetHead', 'sanctionRef', 'documents'], dup)
    s.flow(short, rev)
    rejected = rejection(p, s, 'p1_rj')
    lapsed = reject_with(p, s, 'p1_rjLapsed', 'CLARIFICATION_LAPSED')
    rec = core(s, 'p1_recommend', 'Recommendation levels (core)', 'recommendation')
    outcome(s, 'p1_scrGw', 'scrutinyOutcome', {'ACCEPTED': rec, 'LAPSED': lapsed}, rejected)
    s.flow(scr, 'p1_scrGw')
    san = core(s, 'p1_sanction', 'Approver by amount band (core)', 'sanction')
    outcome(s, 'p1_recGw', 'recOutcome',
            {'RECOMMENDED': san, 'RETURNED': rev}, rejected)
    s.flow(rec, 'p1_recGw')
    jg = s.xgw('p1_jointGw', 'Joint approval needed?')
    outcome(s, 'p1_sanGw', 'sanctionOutcome', {'SANCTIONED': jg, 'RETURNED': rev}, rejected)
    s.flow(san, 'p1_sanGw')
    joint = s.user('p1_joint', 'Joint approval | संयुक्त अनुमोदन', "${jointApprovalSeats.split(',')[loopCounter]}",
                   form='p1-bill-payment/joint-approval', decision=('jointDecision', ['approve', 'reject']),
                   fields=['remarks', 'presentedPayloadHash'], mi={'count': '2', 'max': 5}, sod=True, bind='approve',
                   joint=True)
    ex = core(s, 'p1_execute', 'Payment instruction (core execution)', 'execution')
    s.flow(jg, joint, 'amount > jointApprovalThreshold', 'J-P1-E09')
    s.flow(jg, ex, None, 'no', default=True)
    jr = s.xgw('p1_jointRes', 'Both approved?')
    s.flow(joint, jr)
    s.flow(jr, ex, "jointDecision == 'approve'", 'approved')
    s.flow(jr, rejected, None, 'rejected', default=True)
    inv = s.boundary('p1_invalidated', ex, 'Approval invalidated', error='APPROVAL_INVALIDATED')
    ni = s.notify('p1_invNotify', 'Approval no longer valid: re-approval', 'approval-invalidated', 'inapp',
                  'initiatorSeat')
    s.flow(inv, ni)
    s.flow(ni, rec)
    corr = s.user('p1_correct', 'Rejected by treasury: correct the bill | कोषागार द्वारा अस्वीकृत: बिल सुधारें',
                  '${initiatorSeat}', form='p1-bill-payment/treasury-rejection',
                  decision=('correctDecision', ['correct', 'withdraw']),
                  fields=['correctedAccount', 'correctedIfsc', 'remarks'])
    cl = core(s, 'p1_close', 'Payment advice, voucher, closure (core)', 'closure')
    outcome(s, 'p1_exGw', 'executionOutcome', {'DONE': cl}, corr)
    s.flow(ex, 'p1_exGw')
    cg = s.xgw('p1_correctGw', 'Correct?')
    s.flow(corr, cg)
    fix = s.setv('p1_applyFix', 'Apply corrected bank details', values='account=@correctedAccount;ifsc=@correctedIfsc')
    ver = s.svc('p1_fixVersion', 'New case version (approval invalidated)', C['CASE'], {'op': 'RESUBMIT'})
    s.flow(cg, fix, "correctDecision == 'correct'", 'J-P1-E12 correct')
    s.seq(fix, ver, rec)
    w = withdraw(p, s, 'p1_wdTreasury')
    s.flow(cg, w, None, 'withdraw', default=True)
    e = end_ok(p, s, 'p1_end', 'Paid and reconciled')
    s.flow(cl, e)
    s.side_exits('p1')
    pk.dmn('p1-competent-authority', 'P1 competent authority by amount band', 'FIRST',
           [('Case type', 'caseType', 'string'), ('Amount (INR)', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')],
           [([q('SALARY'), '-'], [q('DDO')], 'salary bills: drawing and disbursing officer'),
            (['-', '<= 500000'], [q('EE')], 'Executive Engineer up to 5,00,000'),
            (['-', '<= 2500000'], [q('SE')], 'Superintending Engineer up to 25,00,000'),
            (['-', '> 2500000'], [q('CE')], 'Chief Engineer above 25,00,000')],
           doc='X.4.3 synthetic bands for the pilot tenant (Directorate of Public Works, District X).')
    pk.seeds(offices=[{'id': 'DPW-ZONE', 'parent': ''}, {'id': 'DPW-CIRCLE', 'parent': 'DPW-ZONE'},
                      {'id': 'DPW-X', 'parent': 'DPW-CIRCLE'}],
             seats=[{'id': 'CLERK-DDO', 'role': 'CLERK', 'office': 'DPW-X', 'reportsTo': 'ACCTS-HEAD',
                     'name': {'en': 'Accounts clerk, DDO office', 'hi': 'लेखा लिपिक, आहरण एवं संवितरण कार्यालय'}},
                    {'id': 'AO-1', 'role': 'AO', 'office': 'DPW-X', 'reportsTo': 'ACCTS-HEAD',
                     'name': {'en': 'Accounts officer 1', 'hi': 'लेखा अधिकारी 1'}},
                    {'id': 'AO-2', 'role': 'AO', 'office': 'DPW-X', 'reportsTo': 'ACCTS-HEAD',
                     'name': {'en': 'Accounts officer 2', 'hi': 'लेखा अधिकारी 2'}},
                    {'id': 'AO-3', 'role': 'AO', 'office': 'DPW-X', 'reportsTo': 'ACCTS-HEAD',
                     'name': {'en': 'Accounts officer 3', 'hi': 'लेखा अधिकारी 3'}},
                    {'id': 'ACCTS-HEAD', 'role': 'AO-HEAD', 'office': 'DPW-X', 'reportsTo': 'EE-DIV1',
                     'name': {'en': 'Head of accounts section', 'hi': 'लेखा अनुभाग प्रमुख'}},
                    {'id': 'DDO-X', 'role': 'DDO', 'office': 'DPW-X', 'reportsTo': 'EE-DIV1',
                     'name': {'en': 'Drawing and disbursing officer', 'hi': 'आहरण एवं संवितरण अधिकारी'}},
                    {'id': 'EE-DIV1', 'role': 'EE', 'office': 'DPW-X', 'reportsTo': 'SE-CIRCLE',
                     'name': {'en': 'Executive Engineer', 'hi': 'अधिशासी अभियंता'}},
                    {'id': 'SE-CIRCLE', 'role': 'SE', 'office': 'DPW-CIRCLE', 'reportsTo': 'CE-ZONE',
                     'name': {'en': 'Superintending Engineer', 'hi': 'अधीक्षण अभियंता'}},
                    {'id': 'CE-ZONE', 'role': 'CE', 'office': 'DPW-ZONE',
                     'name': {'en': 'Chief Engineer', 'hi': 'मुख्य अभियंता'}}],
             holdings=[{'seat': 'CLERK-DDO', 'person': 'p1.clerk', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'AO-1', 'person': 'p1.ao1', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'AO-2', 'person': 'p1.ao2', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'AO-3', 'person': 'p1.ao3', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01',
                        'to': '2026-11-12'},
                       {'seat': 'ACCTS-HEAD', 'person': 'p1.head', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'DDO-X', 'person': 'p1.ddo', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'EE-DIV1', 'person': 'p1.ee', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01',
                        'to': '2026-11-11'},
                       {'seat': 'EE-DIV1', 'person': 'p1.ee2', 'basis': 'SUBSTANTIVE', 'from': '2026-11-12'},
                       {'seat': 'EE-DIV1', 'person': 'p1.se', 'basis': 'ADDITIONAL_CHARGE', 'ref': 'AC-ORDER-17',
                        'from': '2026-11-10', 'to': '2026-11-24'},
                       {'seat': 'SE-CIRCLE', 'person': 'p1.se', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'CE-ZONE', 'person': 'p1.ce', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'}],
             roles=[{'code': 'CLERK', 'name': {'en': 'Accounts clerk', 'hi': 'लेखा लिपिक'}},
                    {'code': 'AO', 'name': {'en': 'Accounts officer (checker)', 'hi': 'लेखा अधिकारी (जाँचकर्ता)'}},
                    {'code': 'DDO', 'name': {'en': 'Drawing and disbursing officer', 'hi': 'आहरण एवं संवितरण अधिकारी'}},
                    {'code': 'EE', 'name': {'en': 'Executive Engineer', 'hi': 'अधिशासी अभियंता'}},
                    {'code': 'SE', 'name': {'en': 'Superintending Engineer', 'hi': 'अधीक्षण अभियंता'}},
                    {'code': 'CE', 'name': {'en': 'Chief Engineer', 'hi': 'मुख्य अभियंता'}}])
    bill = {'payee': 'M/s Shree Constructions', 'account': '50100012345678', 'ifsc': 'SBIN0001234',
            'amount': 475000.0, 'invoiceNo': 'INV-2026-0412', 'budgetHead': '4059-01-051', 'sanctionRef': 'AA/2026/77',
            'actingSeat': 'CLERK-DDO', 'initiator': 'p1.clerk', 'officeId': 'DPW-X', 'selectedSeat': 'AO-2'}
    pk.accept('AC-01', 'Initiator > selected checker > approver by band; payment reconciles', ['T16', 'T17', 'T19', 'T27'],
              'bill of 4,75,000 by CLERK-DDO, checker AO-2 selected', 'checker forwards; EE approves and signs',
              'payment COMPLETED, bank statement MATCHED, caseState CLOSED; audit shows seat, person, rule version',
              bill, 'CLOSED')
    pk.accept('AC-02', 'Change after approval invalidates the approval', ['T16', 'J-P1-E01', 'J-P1-E12'],
              'an approved bill', 'the amount is changed before the payment step',
              'APPROVAL_INVALIDATED; back to recommendation; DMN re-routes; no payment with the old hash',
              dict(bill), None)
    pk.accept('AC-03', 'Payment success with lost response reconciles without a duplicate', ['T02', 'T27', 'J-P1-E04'],
              'sim_pfms_payment = UNKNOWN_THEN_COMPLETED', 'the payment response is lost',
              'UNKNOWN_OUTCOME item; no automatic retry (class c); operator confirms success with the UTR',
              dict(bill, sim_pfms_payment='UNKNOWN'))  # the UNKNOWN_OUTCOME is the item's, not caseState (SL-32)
    pk.accept('J-P1-E11', 'Budget insufficient', ['T19'], 'sim_ifms_budget = available=310000', 'submit',
              'returned to the initiator with the shortfall', dict(bill, sim_ifms_budget='available=310000'), None)
    pk.accept('J-P1-E09', 'Joint approval for high-value bills', ['T16', 'T17'], 'amount above jointApprovalThreshold',
              'two joint approvers approve', 'both approvals bound to the same payload hash',
              dict(bill, amount=12500000.0), None)


# ---------------------------------------------------------------------------------------------------------------- P2
def p2():
    pk = Pack('gov.p2.file-movement', 'p2-file-movement', 'P2 File movement (e-Office)', 'पी2 फ़ाइल संचलन (ई-ऑफ़िस)',
              'e-Office', 'Note / draft for approval moves up the "put up to" chain by seat, with return to any level, '
              'parallel comments, out-of-hierarchy marking with reason, signed noting at every level (CSMOP), '
              'disposal by the competent authority, dispatch, closure with retention category, part files, '
              'physical-file custody, inter-department referral and RTI linkage (IX.4 P2, X.4.4).',
              ['AC-04', 'GP-02', 'GP-18', 'GP-32', 'GP-34', 'GP-52', 'GP-53', 'GP-54', 'J-P2-H', 'J-P2-E01',
               'J-P2-E02', 'J-P2-E03', 'J-P2-E04', 'J-P2-E05', 'J-P2-E06', 'J-P2-E07', 'J-P2-E08', 'J-P2-E09',
               'J-P2-E10', 'R57', 'R61'] + SIDE,
              connectors=[('dsc.sign', 'a', 'G-SIGN', 'DSC signer agent for noting'),
                          ('esign.sign', 'b', 'G-SIGN', 'eSign fallback'),
                          ('dak.dispatch', 'c', 'G-MAIL', 'Dispatch: gov.in e-mail, dak register'),
                          ('referral.send', 'b', '', 'Referral to another department')],
              main=['sangam-p2-file-movement'])
    p = pk.proc('sangam-p2-file-movement', 'P2 File movement',
                'Pack P2. Material fields: subject, noting, draftLetter (the approval binds note + draft letter).',
                start={'materialFields': 'subject,noting,draftLetter'})
    s = p.root
    st = s.start('p2_start', 'File opened', form='p2-file-movement/file-open')
    p.forms.append(dict(key='p2-file-movement/file-open', name='Open e-file | ई-फ़ाइल खोलें', decision=None,
                        fields=['fileNo*', 'subject*', 'subjectHead*', 'receiptNo', 'noting*', 'draftLetter',
                                'documents'], doc=None))
    d = s.setv('p2_defaults', 'Pack configuration', defaults=(
        'caseType=FILE;signEachLevel=true;signMethod=dsc;signatureRequired=true;resubmitTo=START;'
        'routingDecisionKey=p2-disposal-authority;powersDecisionKey=p2-disposal-powers;recommendationSla=3WD;'
        'sanctionSla=3WD;executionConnector=dak.dispatch;executionOperation=dispatch;executionStep=dispatch;'
        'reconcileRequired=false;issueOutput=false;retentionCategory=C;retentionYears=10;appealAllowed=false;'
        'custodianSeat=@initiatorSeat;closureTemplate=file-closed'))
    rt = s.rule('p2_putUpTo', '"Put up to" chain (DMN)', 'p2-put-up-to', binding='latest')
    rs = s.svc('p2_chain', 'Chain of seats', C['SEAT'], {'op': 'ROLES', 'rolesVar': 'recommendationRoles',
                                                         'targetVar': 'recommendationSeats'})
    sign = s.svc('p2_signNote', 'Initiator signs the note', C['SIGN'], {'op': 'REQUEST', 'purpose': 'noting'},
                 async_=True)
    sg = s.xgw('p2_signGw', 'Signed?')
    s.seq(st, d, rt, rs, sign, sg)
    resign = s.user('p2_resign', 'Signature not applied: sign again | हस्ताक्षर नहीं हुआ: पुनः हस्ताक्षर करें',
                    '${initiatorSeat}', form='p2-file-movement/resign', decision=('resignDecision', ['resubmit', 'withdraw']),
                    fields=['signMethod', 'espId'])
    s.flow(sg, resign, "signatureStatus != 'COMPLETED'", 'J-P2-E06')
    rg = s.xgw('p2_resignGw')
    s.flow(resign, rg)
    s.flow(rg, sign, "resignDecision == 'resubmit'", 'sign again')
    s.flow(rg, withdraw(p, s, 'p2_wdSign'), None, 'withdraw', default=True)
    rec = core(s, 'p2_movement', 'Movement up the hierarchy (core)', 'recommendation')
    s.flow(sg, rec, None, 'signed', default=True)
    san = core(s, 'p2_disposal', 'Disposal by competent authority (core)', 'sanction')
    rev = revise(p, s, 'p2_revise', 'Revise note and draft | नोट और प्रारूप संशोधित करें',
                 ['noting', 'draftLetter', 'documents'], rec)
    dropped = rejection(p, s, 'p2_rj')
    outcome(s, 'p2_recGw', 'recOutcome', {'RECOMMENDED': san, 'RETURNED': rev}, dropped)
    s.flow(rec, 'p2_recGw')
    disp = s.user('p2_dispatch', 'Issue the approved letter | अनुमोदित पत्र जारी करें', '${initiatorSeat}',
                  form='p2-file-movement/dispatch', decision=None, fields=['letterNo*', 'dispatchRecipients*'])
    outcome(s, 'p2_sanGw', 'sanctionOutcome', {'SANCTIONED': disp, 'RETURNED': rec}, dropped)
    s.flow(san, 'p2_sanGw')
    letter = s.svc('p2_letter', 'Render letter from the approved version', C['DOC'],
                   {'template': 'dispatch-letter', 'requireApproval': 'true'})
    ex = core(s, 'p2_send', 'Dispatch (core execution)', 'execution')
    s.seq(disp, letter, ex)
    inv = s.boundary('p2_invalidated', ex, 'Draft changed after approval', error='APPROVAL_INVALIDATED')
    s.flow(inv, rec)
    part = s.notify('p2_partial', 'Partial success: some recipients not reached', 'dispatch-partial', 'inapp',
                    'initiatorSeat')
    close = s.user('p2_close', 'Close file: retention category | फ़ाइल बंद करें: अभिलेख श्रेणी', '${initiatorSeat}',
                   form='p2-file-movement/close', decision=None, fields=['retentionCategory*', 'retentionYears*',
                                                                        'legalHold'])
    outcome(s, 'p2_exGw', 'executionOutcome', {'DONE': close}, part)
    s.flow(ex, 'p2_exGw')
    s.flow(part, close)
    cl = core(s, 'p2_closure', 'Closure (core)', 'closure')
    e = end_ok(p, s, 'p2_end', 'File closed')
    s.seq(close, cl, e)
    s.side_exits('p2')
    # part file (GP-54), physical custody (GP-53), referral (GP-32), RTI link (J-P2-E10)
    pf = s.evsub('p2_partFile', 'Open a part file')
    a = pf.start('p2_partStart', 'Part file requested', msg='sangam.openPartFile', interrupting=False)
    b = pf.svc('p2_partLink', 'Start linked part file', C['LINK'],
               {'processKey': 'sangam-p2-file-movement', 'linkType': 'part',
                'inherit': 'subject,subjectHead,fileNo,partFileReason,initiatorSeat,actingSeat,initiator'})
    c = pf.end('p2_partEnd', 'Part file opened')
    pf.seq(a, b, c)
    ph = s.evsub('p2_physical', 'Physical file custody')
    a = ph.start('p2_physStart', 'Physical file moved', msg='sangam.physicalFileMoved', interrupting=False)
    b = ph.user('p2_physAck', 'Acknowledge receipt of the physical file | भौतिक फ़ाइल की प्राप्ति स्वीकार करें',
                '${custodianSeat}', form='p2-file-movement/physical-receipt', decision=None,
                fields=['physicalReceived*', 'custodian', 'remarks'])
    c = ph.end('p2_physEnd', 'Custody recorded')
    ph.seq(a, b, c)
    rf = s.evsub('p2_refer', 'Refer to another department')
    a = rf.start('p2_referStart', 'Referral requested', msg='sangam.referFile', interrupting=False)
    b = core(rf, 'p2_referCall', 'Referral (core)', 'referral')
    c = rf.end('p2_referEnd', 'Reply on file')
    rf.seq(a, b, c)
    rt2 = s.evsub('p2_rti', 'RTI request linked')
    a = rt2.start('p2_rtiStart', 'RTI linked', msg='sangam.rtiLinked', interrupting=False)
    b = rt2.svc('p2_rtiHold', 'Legal hold while the RTI is open', C['CASE'], {'op': 'LEGAL_HOLD'})
    c = rt2.end('p2_rtiEnd', 'Hold set')
    rt2.seq(a, b, c)
    pk.dmn('p2-put-up-to', 'P2 "put up to" chain by subject head', 'FIRST',
           [('Subject head', 'subjectHead', 'string')],
           [('recommendationRoles', 'Chain of roles', 'string'), ('outOfHierarchyAllowed', 'Marking allowed', 'boolean')],
           [([q('ESTABLISHMENT')], [q('SO,US'), 'true'], ''),
            ([q('BUDGET')], [q('SO,US,DS'), 'true'], ''),
            ([q('VIGILANCE')], [q('SO,US,DS'), 'false'], 'no out-of-hierarchy marking'),
            ([q('POLICY')], [q('SO,US,DS'), 'true'], ''),
            (['-'], [q('SO,US,DS'), 'true'], 'default')])
    pk.dmn('p2-disposal-authority', 'P2 who can dispose, by subject head', 'FIRST',
           [('Subject head', 'subjectHead', 'string')], [('competentRole', 'Competent role', 'string')],
           [([q('ESTABLISHMENT')], [q('DS')], ''), ([q('BUDGET')], [q('JS')], ''),
            ([q('VIGILANCE')], [q('SECRETARY')], ''), ([q('POLICY')], [q('JS')], ''), (['-'], [q('DS')], '')])
    pk.dmn('p2-disposal-powers', 'P2 disposal powers by seat role', 'FIRST',
           [('Seat role', 'seatRole', 'string'), ('Subject head', 'subjectHead', 'string')],
           [('powerLimit', 'Power limit', 'double'), ('allowed', 'May dispose', 'boolean')],
           [([q('SECRETARY'), '-'], ['1000000000', 'true'], ''),
            ([q('JS'), 'not("VIGILANCE")'], ['1000000000', 'true'], ''),
            ([q('DS'), q('ESTABLISHMENT')], ['1000000000', 'true'], ''),
            (['-', '-'], ['0', 'false'], 'no disposal power')])
    pk.seeds(offices=[{'id': 'MIN', 'parent': ''}, {'id': 'DIV-B', 'parent': 'MIN'}, {'id': 'SEC-B2', 'parent': 'DIV-B'},
                      {'id': 'SEC-C2', 'parent': 'DIV-B'}],
             seats=[{'id': 'DA-B2', 'role': 'DA', 'office': 'SEC-B2', 'reportsTo': 'SO-B2',
                     'name': {'en': 'Dealing assistant, Section B-2', 'hi': 'व्यवहार सहायक, अनुभाग बी-2'}},
                    {'id': 'SO-B2', 'role': 'SO', 'office': 'SEC-B2', 'reportsTo': 'US-B',
                     'name': {'en': 'Section officer B-2', 'hi': 'अनुभाग अधिकारी बी-2'}},
                    {'id': 'SO-C2', 'role': 'SO', 'office': 'SEC-C2', 'reportsTo': 'US-B',
                     'name': {'en': 'Section officer C-2', 'hi': 'अनुभाग अधिकारी सी-2'}},
                    {'id': 'US-B', 'role': 'US', 'office': 'DIV-B', 'reportsTo': 'DS-B',
                     'name': {'en': 'Under Secretary', 'hi': 'अवर सचिव'}},
                    {'id': 'DS-B', 'role': 'DS', 'office': 'DIV-B', 'reportsTo': 'JS-1',
                     'name': {'en': 'Deputy Secretary', 'hi': 'उप सचिव'}},
                    {'id': 'JS-1', 'role': 'JS', 'office': 'MIN', 'reportsTo': 'SECY',
                     'name': {'en': 'Joint Secretary', 'hi': 'संयुक्त सचिव'}},
                    {'id': 'SECY', 'role': 'SECRETARY', 'office': 'MIN', 'name': {'en': 'Secretary', 'hi': 'सचिव'}}],
             holdings=[{'seat': 'DA-B2', 'person': 'p2.da', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'SO-B2', 'person': 'p2.so', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'SO-C2', 'person': 'p2.soc', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'US-B', 'person': 'p2.us', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'DS-B', 'person': 'p2.ds', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'DS-B', 'person': 'p2.js', 'basis': 'ADDITIONAL_CHARGE', 'ref': 'OM-AC-2026-41',
                        'from': '2026-11-10', 'to': '2026-11-24'},
                       {'seat': 'DS-B', 'person': 'p2.us', 'basis': 'ADDITIONAL_CHARGE', 'ref': 'OM-AC-2026-42',
                        'from': '2026-12-01', 'to': '2026-12-15'},
                       {'seat': 'JS-1', 'person': 'p2.js', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'},
                       {'seat': 'SECY', 'person': 'p2.secy', 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'}],
             roles=[{'code': 'DA', 'name': {'en': 'Dealing assistant', 'hi': 'व्यवहार सहायक'}},
                    {'code': 'SO', 'name': {'en': 'Section officer', 'hi': 'अनुभाग अधिकारी'}},
                    {'code': 'US', 'name': {'en': 'Under Secretary', 'hi': 'अवर सचिव'}},
                    {'code': 'DS', 'name': {'en': 'Deputy Secretary', 'hi': 'उप सचिव'}},
                    {'code': 'JS', 'name': {'en': 'Joint Secretary', 'hi': 'संयुक्त सचिव'}},
                    {'code': 'SECRETARY', 'name': {'en': 'Secretary', 'hi': 'सचिव'}}])
    f = {'fileNo': 'B-2/14/2026-Estt', 'subject': 'Sanction of honorarium', 'subjectHead': 'ESTABLISHMENT',
         'noting': 'Proposal for approval.', 'draftLetter': 'Draft order.', 'actingSeat': 'DA-B2', 'initiator': 'p2.da',
         'officeId': 'SEC-B2'}
    pk.accept('AC-04', 'Up three levels, back two, resubmitted, approved; acted-for shown', ['T15', 'T17'],
              'chain SO-B2 > US-B > DS-B, DS on leave with JS-1 on additional charge',
              'DS-B returns to level 1; file goes up again; JS-1 approves as DS-B',
              'CASE_RETURNED, CASE_RESUBMITTED, ACTED_FOR with the additional-charge order; dispatch; closed',
              f, 'CLOSED')
    pk.accept('J-P2-E04', 'Additional-charge holder who noted lower down cannot decide', ['T17'],
              'p2.us noted as US-B and holds DS-B on additional charge', 'p2.us tries to approve as DS-B',
              '422 SOD_VIOLATION; SOD_VIOLATION_BLOCKED', f, None)


# ---------------------------------------------------------------------------------------------------------------- P3
def p3():
    pk = Pack('gov.p3.grant-sanction', 'p3-grant-sanction', 'P3 Grant / scheme sanction (DBT)',
              'पी3 अनुदान / योजना स्वीकृति (डीबीटी)', 'Scheme / DBT',
              'Application with DPDP notice and DigiLocker pull, eligibility and duplicate-beneficiary checks, '
              'document verification with clarification, optional field verification, entitlement by rule, '
              'sanction by limit, DBT disbursement in instalments with per-record operationId, account-failure '
              'correction with re-verification and re-sanction, rejection with reasons and appeal with a '
              'different verifier (IX.4 P3, X.4.5).',
              ['AC-05', 'GP-16', 'GP-17', 'GP-19', 'GP-22', 'GP-37', 'J-P3-H', 'J-P3-E01', 'J-P3-E02', 'J-P3-E03',
               'J-P3-E04', 'J-P3-E05', 'J-P3-E06', 'J-P3-E07', 'J-P3-E08', 'J-P3-E09', 'R53', 'R59',
               'D-DBT-SCHEME'] + SIDE,
              connectors=[('digilocker.pull', 'a', 'G-DL', 'Requester: applicant certificates'),
                          ('registry.beneficiary', 'a', '', 'Beneficiary registry / duplicate check'),
                          ('pfms.dbt', 'c', 'G-PFMS', 'DBT record per instalment'),
                          ('bank.statement', 'b', 'G-PFMS', 'Credit confirmation'),
                          ('esign.sign', 'b', 'G-SIGN', 'Sanction order signature')],
              main=['sangam-p3-grant-application'])
    p = pk.proc('sangam-p3-grant-application', 'P3 Grant / scheme application',
                'Pack P3. Material fields: beneficiaryId, bankAccount, ifsc, amount (entitlement), schemeId.',
                start={'materialFields': 'beneficiaryId,bankAccount,ifsc,amount,schemeId'})
    s = p.root
    st = s.start('p3_start', 'Application submitted', form='p3-grant-sanction/application')
    p.forms.append(dict(key='p3-grant-sanction/application', name='Scheme application | योजना आवेदन', decision=None,
                        fields=['applicantName*', 'applicantRef*', 'schemeId*', 'beneficiaryId*', 'bankAccount*',
                                'ifsc*', 'income*', 'age*', 'socialCategory', 'landHolding', 'district*',
                                'guardianName', 'consentNoticeVersion', 'documents'], doc=None))
    d = s.setv('p3_defaults', 'Pack configuration', defaults=(
        'caseType=GRANT;routingDecisionKey=p3-sanction-authority;powersDecisionKey=sangam-core-powers;'
        'signatureRequired=true;executionConnector=pfms.dbt;executionOperation=submitDbtRecord;executionStep=dbt;'
        'reconcileRequired=true;reconcileConnector=bank.statement;outputTemplate=disbursement-record;'
        'issueOutput=true;appealAllowed=true;appealWindow=P30D;appellateSeat=APPELLATE-DIR;instalmentNo=0;'
        'instalmentInterval=P90D;retentionCategory=C;retentionYears=10;amount=0;'
        'executionPayloadFields=beneficiaryId,bankAccount,ifsc,instalmentAmount,instalmentNo,schemeId;'
        'entitlementOverride=null;childApplicant=false;regDuplicate=false;scrutinySla=7WD;sanctionSla=5WD;'
        'correctedAccount=null;correctedIfsc=null'))
    cg = s.xgw('p3_childGw', 'Child applicant?')
    s.seq(st, d, cg)
    child = s.setv('p3_child', 'Child record: guardian, analytics blocked (GOV-4)',
                   values='childApplicant=true;analyticsBlocked=true')
    pull = s.conn('p3_digilocker', 'Pull certificates from DigiLocker', 'digilocker.pull', 'pull', 'query',
                  'beneficiaryId,schemeId', 'dl')
    s.flow(cg, child, 'age < 18', 'J-P3-E09')
    s.flow(cg, pull, None, 'adult', default=True)
    s.flow(child, pull)
    dlg = s.xgw('p3_dlGw', 'Fetched?')
    s.flow(pull, dlg)
    selfup = s.setv('p3_selfUploaded', 'Self-uploaded documents, to verify', values='documentsSelfUploaded=true')
    elig = s.rule('p3_eligibility', 'Eligibility pre-check (DMN, bound version)', 'p3-eligibility')
    s.flow(dlg, selfup, "dlStatus != 'COMPLETED'", 'J-P3-E07')
    s.flow(dlg, elig, None, 'fetched', default=True)
    s.flow(selfup, elig)
    reg = s.conn('p3_registry', 'Duplicate-beneficiary check', 'registry.beneficiary', 'check', 'query',
                 'beneficiaryId,schemeId', 'reg')
    s.flow(elig, reg)
    rg = s.xgw('p3_dupGw', 'Possible duplicate?')
    s.flow(reg, rg)
    vseat = s.svc('p3_verifierSeat', 'Verifier seat for the block', C['SEAT'],
                  {'op': 'ROLE', 'role': 'DOCVERIFIER', 'targetVar': 'scrutinySeat'})
    dupt = s.user('p3_duplicate', 'Possible duplicate beneficiary | संभावित दोहरा लाभार्थी', 'DOCVERIFIER',
                  form='p3-grant-sanction/duplicate', decision=('dupDecision', ['proceed', 'reject']),
                  fields=['remarks*'], sod=True)
    s.flow(rg, dupt, 'regDuplicate', 'J-P3-E06')
    s.flow(rg, vseat, None, 'unique', default=True)
    dg2 = s.xgw('p3_dupDecGw')
    s.flow(dupt, dg2)
    s.flow(dg2, vseat, "dupDecision == 'proceed'", 'proceed')
    s.flow(dg2, reject_with(p, s, 'p3_rjDup', 'DUPLICATE_BENEFICIARY'), None, 'reject', default=True)
    scr = core(s, 'p3_docVerify', 'Document verification (core scrutiny)', 'scrutiny')
    s.flow(vseat, scr)
    rejected = rejection(p, s, 'p3_rj', reopen=vseat)
    fv = s.rule('p3_fieldRule', 'Field verification required? (DMN)', 'p3-field-verification-required')
    outcome(s, 'p3_scrGw', 'scrutinyOutcome', {'ACCEPTED': fv}, rejected)
    s.flow(scr, 'p3_scrGw')
    fg = s.xgw('p3_fieldGw', 'Field visit?')
    s.flow(fv, fg)
    fseat = s.svc('p3_fieldSeat', 'Field verifier for the block', C['SEAT'],
                  {'op': 'ROLE', 'role': 'FIELDVERIFIER', 'targetVar': 'fieldVerifierSeat'})
    s.flow(fg, fseat, 'fieldVerification', 'yes')
    fe, fd = s.sla_task('p3_field', 'Field verification | क्षेत्र सत्यापन', 'fieldVerifierSeat',
                        form='p3-grant-sanction/field-verification',
                        decision=('fieldVerdict', ['verified', 'doubtful', 'failed']),
                        fields=['visitDate*', 'geoTag*', 'photos*', 'findings*'], default_spec='10WD', sod=True)
    s.flow(fseat, fe)
    eg = s.xgw('p3_eligibleGw', 'Eligible after verification?')
    s.flow(fg, eg, None, 'no visit', default=True)
    fgr = s.xgw('p3_fieldRes', 'Verified?')
    s.flow(fd, fgr)
    s.flow(fgr, reject_with(p, s, 'p3_rjField', 'FIELD_VERIFICATION_FAILED', reopen=vseat),
           "fieldVerdict == 'failed'", 'failed')
    s.flow(fgr, eg, None, 'verified / doubtful', default=True)
    ent = s.rule('p3_entitlement', 'Entitlement (DMN)', 'p3-entitlement')
    s.flow(eg, ent, 'eligible', 'eligible')
    inel = s.setv('p3_ineligible', 'Ineligible: reasons from the rule', values='rejectionReasonCodes=@eligibilityReasonCode')
    s.flow(eg, inel, None, 'AC-05 ineligible', default=True)
    s.flow(inel, rejected)
    amt = s.setv('p3_amount', 'Entitlement is the sanction amount', values='amount=@entitlement')
    re_, rd = s.sla_task('p3_recommend', 'Recommend with entitlement | पात्रता सहित अनुशंसा', 'scrutinySeat',
                         form='p3-grant-sanction/recommend', decision=('p3RecDecision', ['recommend', 'reject']),
                         fields=['entitlementOverride', 'overrideReason', 'remarks'], default_spec='3WD')
    s.seq(ent, amt, re_)
    og = s.xgw('p3_overrideGw', 'Override?')
    rg2 = s.xgw('p3_recGw', 'Recommended?')
    s.flow(rd, rg2)
    s.flow(rg2, og, "p3RecDecision == 'recommend'", 'recommend')
    s.flow(rg2, rejected, None, 'reject', default=True)
    ov = s.setv('p3_override', 'Apply override (reason recorded)', values='amount=@entitlementOverride')
    san = core(s, 'p3_sanction', 'Sanction by limit (core)', 'sanction')
    s.flow(og, ov, 'entitlementOverride != null', 'override')
    s.flow(og, san, None, 'as computed', default=True)
    s.flow(ov, san)
    inc = s.expr('p3_nextInstalment', 'Next instalment',
                 "${execution.setVariable('instalmentNo', instalmentNo + 1)}")
    ia = s.expr('p3_instalmentAmount', 'Instalment amount',
                "${execution.setVariable('instalmentAmount', amount / instalments)}")
    sfx = s.setv('p3_stepSuffix', 'One operation per instalment', values='executionStepSuffix=@instalmentNo')
    ex = core(s, 'p3_disburse', 'DBT disbursement (core execution)', 'execution')
    outcome(s, 'p3_sanGw', 'sanctionOutcome', {'SANCTIONED': inc, 'RETURNED': vseat}, rejected)
    s.flow(san, 'p3_sanGw')
    s.seq(inc, ia, sfx, ex)
    inv = s.boundary('p3_invalidated', ex, 'Approval invalidated', error='APPROVAL_INVALIDATED')
    s.flow(inv, san)
    bank = s.user('p3_bankUpdate', 'Payment returned: update bank details | भुगतान वापस: बैंक विवरण अद्यतन करें',
                  '${initiatorSeat}', form='p3-grant-sanction/bank-update',
                  decision=('bankDecision', ['resubmit', 'withdraw']), fields=['correctedAccount*', 'correctedIfsc*'])
    nb = s.notify('p3_bankNotify', 'Tell applicant: account failure', 'dbt-account-failed', 'sms,inapp', 'applicantRef')
    more = s.xgw('p3_moreGw', 'More instalments?')
    outcome(s, 'p3_exGw', 'executionOutcome', {'DONE': more}, nb)
    s.flow(ex, 'p3_exGw')
    s.flow(nb, bank)
    bg = s.xgw('p3_bankGw')
    s.flow(bank, bg)
    fix = s.setv('p3_applyBank', 'Apply new account', values='bankAccount=@correctedAccount;ifsc=@correctedIfsc')
    ver = s.svc('p3_bankVersion', 'New case version (approval invalidated)', C['CASE'], {'op': 'RESUBMIT'})
    av_e, av_d = s.sla_task('p3_accountVerify', 'Verify new bank account | नए बैंक खाते का सत्यापन', 'scrutinySeat',
                            form='p3-grant-sanction/account-verify', decision=('accountVerdict', ['verified', 'failed']),
                            fields=['remarks'], default_spec='3WD', sod=True)
    s.flow(bg, fix, "bankDecision == 'resubmit'", 'J-P3-E03')
    s.seq(fix, ver, av_e)
    s.flow(bg, withdraw(p, s, 'p3_wdBank'), None, 'withdraw', default=True)
    avg = s.xgw('p3_avGw')
    s.flow(av_d, avg)
    resan = s.expr('p3_reSanction', 'Same instalment again', "${execution.setVariable('instalmentNo', instalmentNo - 1)}")
    s.flow(avg, resan, "accountVerdict == 'verified'", 're-sanction')
    s.flow(resan, san)
    s.flow(avg, bank, None, 'not verified', default=True)
    wait = s.catch_timer('p3_instalmentWait', 'Next instalment date', duration='${instalmentInterval}')
    s.flow(more, wait, 'instalmentNo < instalments', 'J-P3-E02 partial disbursement')
    s.flow(wait, inc)
    cl = core(s, 'p3_close', 'Disbursement record and closure (core)', 'closure')
    s.flow(more, cl, None, 'all paid', default=True)
    s.flow(cl, end_ok(p, s, 'p3_end', 'Disbursed and closed'))
    s.side_exits('p3')
    pk.dmn('p3-eligibility', 'P3 eligibility (scheme rule)', 'FIRST',
           [('Scheme', 'schemeId', 'string'), ('Income', 'income', 'double'), ('Age', 'age', 'integer'),
            ('Child with guardian', 'childApplicant', 'boolean')],
           [('eligible', 'Eligible', 'boolean'), ('eligibilityReasonCode', 'Reason code', 'string')],
           [(['-', '> 250000', '-', '-'], ['false', q('INCOME_ABOVE_LIMIT')], 'income ceiling'),
            ([q('OLD-AGE-PENSION'), '-', '< 60', '-'], ['false', q('AGE_BELOW_60')], ''),
            (['-', '-', '< 18', 'false'], ['false', q('MINOR_WITHOUT_GUARDIAN')], ''),
            (['-', '-', '-', '-'], ['true', q('ELIGIBLE')], '')],
           doc='SYNTHETIC scheme rule. Bound to the deployment (GP-19, J-P3-E08): in-flight cases keep it.')
    pk.dmn('p3-field-verification-required', 'P3 field verification required?', 'FIRST',
           [('Land holding (ha)', 'landHolding', 'double'), ('Self-uploaded documents', 'documentsSelfUploaded',
                                                             'boolean')],
           [('fieldVerification', 'Field verification', 'boolean')],
           [(['-', 'true'], ['true'], 'documents not from DigiLocker'),
            (['> 2', '-'], ['true'], 'larger holdings are visited'),
            (['-', '-'], ['false'], '')])
    pk.dmn('p3-entitlement', 'P3 entitlement amount and instalments', 'FIRST',
           [('Scheme', 'schemeId', 'string'), ('Social category', 'socialCategory', 'string')],
           [('entitlement', 'Entitlement (INR)', 'double'), ('instalments', 'Instalments', 'integer')],
           [([q('PM-KISAN-LIKE'), '-'], ['6000', '3'], 'three instalments of 2,000'),
            ([q('HOUSING-GRANT'), q('SC')], ['150000', '3'], ''),
            ([q('HOUSING-GRANT'), q('ST')], ['150000', '3'], ''),
            ([q('HOUSING-GRANT'), '-'], ['120000', '3'], ''),
            ([q('OLD-AGE-PENSION'), '-'], ['12000', '12'], 'monthly'),
            (['-', '-'], ['25000', '1'], 'default one-time grant')])
    pk.dmn('p3-sanction-authority', 'P3 sanctioning authority by amount', 'FIRST',
           [('Amount', 'amount', 'double')], [('competentRole', 'Competent role', 'string')],
           [(['<= 200000'], [q('DWO')], 'District Welfare Officer'), (['<= 1000000'], [q('JD')], 'Joint Director'),
            (['> 1000000'], [q('DIRECTOR')], 'Director')])
    pk.seeds(offices=[{'id': 'DIR-WEL', 'parent': ''}, {'id': 'DIST-A', 'parent': 'DIR-WEL'},
                      {'id': 'BLOCK-A1', 'parent': 'DIST-A'}],
             seats=[{'id': 'DEO-A1', 'role': 'DEO', 'office': 'BLOCK-A1', 'reportsTo': 'DOCVERIFIER-A1',
                     'name': {'en': 'Data-entry operator, Block A1', 'hi': 'डेटा प्रविष्टि संचालक, ब्लॉक ए1'}},
                    {'id': 'DOCVERIFIER-A1', 'role': 'DOCVERIFIER', 'office': 'BLOCK-A1', 'reportsTo': 'DWO-A',
                     'name': {'en': 'Document verifier, Block A1', 'hi': 'दस्तावेज़ सत्यापनकर्ता, ब्लॉक ए1'}},
                    {'id': 'DOCVERIFIER-A2', 'role': 'DOCVERIFIER', 'office': 'DIST-A', 'reportsTo': 'DWO-A',
                     'name': {'en': 'Document verifier (district pool)', 'hi': 'दस्तावेज़ सत्यापनकर्ता (ज़िला)'}},
                    {'id': 'FIELD-A1', 'role': 'FIELDVERIFIER', 'office': 'BLOCK-A1', 'reportsTo': 'DWO-A',
                     'name': {'en': 'Field verifier, Block A1', 'hi': 'क्षेत्र सत्यापनकर्ता, ब्लॉक ए1'}},
                    {'id': 'DWO-A', 'role': 'DWO', 'office': 'DIST-A', 'reportsTo': 'JD-WEL',
                     'name': {'en': 'District Welfare Officer', 'hi': 'ज़िला कल्याण अधिकारी'}},
                    {'id': 'JD-WEL', 'role': 'JD', 'office': 'DIR-WEL', 'reportsTo': 'DIR-W',
                     'name': {'en': 'Joint Director', 'hi': 'संयुक्त निदेशक'}},
                    {'id': 'DIR-W', 'role': 'DIRECTOR', 'office': 'DIR-WEL', 'name': {'en': 'Director', 'hi': 'निदेशक'}},
                    {'id': 'APPELLATE-DIR', 'role': 'APPELLATE', 'office': 'DIR-WEL',
                     'name': {'en': 'Appellate authority (Additional Director)', 'hi': 'अपीलीय प्राधिकारी (अपर निदेशक)'}}],
             holdings=[{'seat': s_, 'person': 'p3.' + s_.lower(), 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'}
                       for s_ in ['DEO-A1', 'DOCVERIFIER-A1', 'DOCVERIFIER-A2', 'FIELD-A1', 'DWO-A', 'JD-WEL', 'DIR-W',
                                  'APPELLATE-DIR']],
             roles=[{'code': 'DEO', 'name': {'en': 'Data-entry operator', 'hi': 'डेटा प्रविष्टि संचालक'}},
                    {'code': 'DOCVERIFIER', 'name': {'en': 'Document verifier', 'hi': 'दस्तावेज़ सत्यापनकर्ता'}},
                    {'code': 'FIELDVERIFIER', 'name': {'en': 'Field verifier', 'hi': 'क्षेत्र सत्यापनकर्ता'}},
                    {'code': 'DWO', 'name': {'en': 'District Welfare Officer', 'hi': 'ज़िला कल्याण अधिकारी'}},
                    {'code': 'JD', 'name': {'en': 'Joint Director', 'hi': 'संयुक्त निदेशक'}},
                    {'code': 'DIRECTOR', 'name': {'en': 'Director', 'hi': 'निदेशक'}}])
    app = {'applicantName': 'Sita Devi', 'applicantRef': 'mobile:98XXXXXX10', 'schemeId': 'HOUSING-GRANT',
           'beneficiaryId': 'VAULT-7f3a', 'bankAccount': '3210987654', 'ifsc': 'PUNB0123400', 'income': 120000.0,
           'age': 41, 'socialCategory': 'SC', 'landHolding': 0.5, 'district': 'A', 'officeId': 'BLOCK-A1',
           'actingSeat': 'DEO-A1', 'initiator': 'p3.deo-a1', 'instalmentInterval': 'PT1S'}
    pk.accept('AC-05', 'Ineligible after verification; partial disbursement; account failure', ['T11', 'T27'],
              'housing grant of 1,50,000 in three instalments', 'income above limit (variant); instalment 2 fails '
              'with ACCOUNT_CLOSED (variant)', 'rejection with coded reasons and appeal route; 2 of 3 credited; new '
              'account re-verified, re-sanctioned, new operationId', app, 'CLOSED')
    pk.accept('J-P3-E01', 'Ineligible after verification', ['T11'], 'income 3,00,000', 'verifier accepts documents',
              'REJECTED with INCOME_ABOVE_LIMIT; rejection order; appeal window', dict(app, income=300000.0),
              'REJECTED')


def dbt_batch():
    pk = Pack('gov.dbt.batch', 'dbt-batch', 'DBT batch with partial failure', 'आंशिक विफलता सहित डीबीटी बैच',
              'Scheme / DBT', 'A sanctioned beneficiary list is paid as one instance per beneficiary (GP-09), the '
              'parent aggregates the outcomes, each failure is routed by reason code to its desk and only the '
              'failures are resent in the next round (GP-49).',
              ['GP-08', 'GP-09', 'GP-12', 'GP-49', 'D-DBT-SCHEME', 'R58'],
              connectors=[('dbt.beneficiaryList', 'a', 'G-PFMS', 'Sanctioned list for the period'),
                          ('pfms.dbt', 'c', 'G-PFMS', 'DBT record'),
                          ('bank.statement', 'b', 'G-PFMS', 'Credit confirmation')],
              main=['sangam-dbt-batch'])
    p = pk.proc('sangam-dbt-batch', 'DBT batch round', 'Parent of the GP-09 pattern: no multi-instance.',
                start={'materialFields': 'schemeId,period,batchTotal'})
    s = p.root
    st = s.start('db_start', 'Batch created', form='dbt-batch/batch')
    p.forms.append(dict(key='dbt-batch/batch', name='DBT batch | डीबीटी बैच', decision=None,
                        fields=['schemeId*', 'documents'], doc=None))
    d = s.setv('db_defaults', 'Configuration', defaults='caseType=GRANT;batchTotal=0;period=current;'
               'routingDecisionKey=p3-sanction-authority;amount=0;retryReasons=ACCOUNT_INVALID,ACCOUNT_CLOSED,'
               'AADHAAR_NOT_SEEDED,NAME_MISMATCH;dbtCellSeat=DBT-CELL')
    ld = s.svc('db_load', 'Load sanctioned beneficiary list', C['BATCH'],
               {'op': 'LOAD', 'connector': 'dbt.beneficiaryList', 'sampleSize': '5'}, async_=True)
    san = core(s, 'db_sanction', 'Batch sanction (core)', 'sanction')
    s.seq(st, d, ld, san)
    op, cg = batch_loop(p, s, 'db', 'sangam-dbt-beneficiary-payment', inherit='schemeId,period,approvedPayloadHash')
    sg = s.xgw('db_sanGw', 'Sanctioned?')
    s.flow(san, sg)
    s.flow(sg, op, "sanctionOutcome == 'SANCTIONED'", 'sanctioned')
    e0 = s.end('db_endNotSanctioned', 'Not sanctioned')
    s.flow(sg, e0, None, 'not sanctioned', default=True)
    fg = s.xgw('db_failGw', 'Failures?')
    s.flow(cg, fg, None, 'complete', default=True)
    rv = s.user('db_review', 'Review failed records by reason | कारणवार विफल अभिलेखों की समीक्षा', '${dbtCellSeat}',
                form='dbt-batch/review-failures', decision=('roundDecision', ['resubmit', 'close']),
                fields=['remarks'], doc='Shows batchFailures (item to reason) and batchOutcomes.')
    s.flow(fg, rv, 'batchFailed > 0', 'GP-49')
    nr = s.svc('db_nextRound', 'Next round: only the failures', C['BATCH'],
               {'op': 'NEXT_ROUND', 'retryReasons': '${retryReasons}'})
    rg = s.xgw('db_roundGw')
    s.flow(rv, rg)
    s.flow(rg, nr, "roundDecision == 'resubmit'", 'resend failures')
    nrg = s.xgw('db_retryGw', 'Anything to resend?')
    s.flow(nr, nrg)
    s.flow(nrg, 'db_fanOut', 'retryCount > 0', 'resend')
    rep = s.svc('db_report', 'Batch report', C['DOC'], {'template': 'dbt-batch-report'})
    s.flow(nrg, rep, None, 'nothing retryable', default=True)
    s.flow(rg, rep, None, 'close', default=True)
    s.flow(fg, rep, None, 'all paid', default=True)
    e = s.end('db_end', 'Batch closed')
    s.flow(rep, e)
    c = pk.proc('sangam-dbt-beneficiary-payment', 'DBT payment for one beneficiary',
                'Child of the GP-09 pattern: one record, its own operationId and state; failures are corrected at the '
                'desk chosen by the reason code (GP-49) and recorded for the next round.',
                start={'materialFields': 'key,bankAccount,ifsc,amount'})
    s = c.root
    st = s.start('dp_start', 'Item started', initiator=None)
    d = s.setv('dp_defaults', 'Configuration', defaults='executionConnector=pfms.dbt;executionOperation=submitDbtRecord;'
               'executionStep=dbt;verifyApproval=false;reconcileRequired=true;bankAccount=;ifsc=;amount=0;'
               'correctedAccount=null;correctedIfsc=null')
    cg = s.xgw('dp_corrGw', 'Corrected in a previous round?')
    s.seq(st, d, cg)
    fx = s.setv('dp_applyFix', 'Use corrected account', values='bankAccount=@correctedAccount;ifsc=@correctedIfsc')
    ex = core(s, 'dp_execute', 'DBT record (core execution)', 'execution')
    s.flow(cg, fx, 'correctedAccount != null', 'corrected')
    s.flow(cg, ex, None, default=True)
    s.flow(fx, ex)
    ok = s.end('dp_endPaid', 'Credited', listeners=[c.item_listener('SUCCESS')])
    rt = s.rule('dp_route', 'Route failure by reason (DMN)', 'dbt-failure-routing')
    outcome(s, 'dp_exGw', 'executionOutcome', {'DONE': ok}, rt)
    s.flow(ex, 'dp_exGw')
    fix = s.user('dp_fix', 'Correct failed record | विफल अभिलेख सुधारें', '${failureDesk}',
                 form='dbt-batch/fix-record', decision=('fixDecision', ['correct', 'drop']),
                 fields=['correctedAccount', 'correctedIfsc', 'remarks'])
    s.flow(rt, fix)
    fg = s.xgw('dp_fixGw')
    s.flow(fix, fg)
    fe = s.end('dp_endFailed', 'Failed, corrected for resend',
               listeners=[c.item_listener('FAILED', reason_var='opReasonCode',
                                          data_vars='correctedAccount,correctedIfsc')])
    de = s.end('dp_endDropped', 'Dropped', listeners=[c.item_listener('DROPPED', reason_var='opReasonCode')])
    s.flow(fg, fe, "fixDecision == 'correct'", 'correct')
    s.flow(fg, de, None, 'drop', default=True)
    pk.dmn('dbt-failure-routing', 'DBT failure desk by reason code (GP-49)', 'FIRST',
           [('Reason code', 'opReasonCode', 'string')], [('failureDesk', 'Desk', 'string')],
           [([q('ACCOUNT_INVALID')], [q('BANK-DESK')], ''), ([q('ACCOUNT_CLOSED')], [q('BANK-DESK')], ''),
            ([q('NAME_MISMATCH')], [q('BANK-DESK')], ''), ([q('AADHAAR_NOT_SEEDED')], [q('DBT-CELL')], ''),
            (['-'], [q('DDO-DBT')], 'other rejections to the DDO')])
    pk.seeds(offices=[{'id': 'DIR-WEL', 'parent': ''}],
             seats=[{'id': 'DBT-CELL', 'role': 'DBTCELL', 'office': 'DIR-WEL',
                     'name': {'en': 'DBT cell', 'hi': 'डीबीटी प्रकोष्ठ'}},
                    {'id': 'BANK-DESK', 'role': 'BANKDESK', 'office': 'DIR-WEL',
                     'name': {'en': 'Bank coordination desk', 'hi': 'बैंक समन्वय डेस्क'}},
                    {'id': 'DDO-DBT', 'role': 'DDO', 'office': 'DIR-WEL',
                     'name': {'en': 'DDO (DBT)', 'hi': 'आहरण एवं संवितरण अधिकारी (डीबीटी)'}}],
             holdings=[], roles=[])
    pk.accept('GP-49', 'Partial batch failure resends only the failures', ['GP-09', 'GP-49'],
              'five beneficiaries, two fail with ACCOUNT_CLOSED', 'the bank desk corrects; the cell resends',
              'round 2 has exactly the two corrected items', {'schemeId': 'PM-KISAN-LIKE'}, None)


# ---------------------------------------------------------------------------------------------------------------- P4
def p4():
    pk = Pack('gov.p4.licence', 'p4-licence', 'P4 Licence / permit (time-bound service)',
              'पी4 अनुज्ञप्ति / परमिट (समयबद्ध सेवा)', 'Licence / permit',
              'Application and fee through the payment gateway (callback via the inbox, lookup when lost), '
              'scrutiny with clarification that pauses the statutory clock, inspection with rescheduling, approval '
              'with SLA escalation or deemed approval where the law provides it, signed certificate from the '
              'approved data, DigiLocker push, rejection with appeal (IX.4 P4, X.4.6). CMMN case variant included.',
              ['AC-06', 'GP-06', 'GP-15', 'GP-16', 'GP-17', 'GP-21', 'GP-22', 'GP-24', 'GP-34', 'GP-44', 'GP-45',
               'J-P4-H', 'J-P4-E01', 'J-P4-E02', 'J-P4-E03', 'J-P4-E04', 'J-P4-E05', 'J-P4-E06', 'J-P4-E07',
               'J-P4-E08', 'J-P4-E09', 'J-P4-E10', 'R31', 'R57', 'R59', 'D-LICENCE'] + SIDE,
              connectors=[('paygov.fee', 'b', 'G-PG', 'Fee with merchant reference = operationId, status query'),
                          ('paygov.refund', 'a', 'G-PG', 'Refund of an excess or withdrawn fee'),
                          ('esign.sign', 'b', 'G-SIGN', 'Certificate signature'),
                          ('digilocker.push', 'a', 'G-DL', 'Issuer push of the certificate'),
                          ('notify.sms', 'b', 'G-DLT', 'Applicant SMS with whitelisted URL')],
              main=['sangam-p4-licence'])
    p = pk.proc('sangam-p4-licence', 'P4 Licence / permit',
                'Pack P4. Material fields: applicantName, licenceType, tradeName, premisesAddress, durationYears, fee.',
                start={'materialFields': 'applicantName,licenceType,tradeName,premisesAddress,durationYears,fee'})
    s = p.root
    st = s.start('p4_start', 'Application received', form='p4-licence/application')
    p.forms.append(dict(key='p4-licence/application', name='Licence application | अनुज्ञप्ति आवेदन', decision=None,
                        fields=['applicantName*', 'applicantRef*', 'licenceType*', 'tradeName*', 'premisesAddress*',
                                'premisesArea*', 'durationYears*', 'consentNoticeVersion', 'documents'], doc=None))
    d = s.setv('p4_defaults', 'Pack configuration', defaults=(
        'caseType=LICENCE;routingDecisionKey=p4-approving-authority;powersDecisionKey=sangam-core-powers;'
        'signatureRequired=true;outputTemplate=licence-certificate;requireApprovalForOutput=true;'
        'appealAllowed=true;appealWindow=P30D;appellateSeat=APPELLATE-LIC;pauseOnClarification=true;'
        'retentionCategory=C;retentionYears=10;releaseConnector=paygov.refund;issueOutput=false;amount=0'))
    fee = s.rule('p4_fee', 'Fee (DMN)', 'p4-fee')
    tl = s.rule('p4_timeline', 'Service timeline and deemed approval (DMN)', 'p4-service-timeline', binding='deployment')
    pay = s.setv('p4_payConfig', 'Fee payment step',
                 values='executionConnector=paygov.fee;executionOperation=collectFee;executionStep=fee;'
                        'verifyApproval=false;reconcileRequired=false;ackWindow=PT30M;'
                        'executionPayloadFields=fee,applicantRef,licenceType')
    ex = core(s, 'p4_payFee', 'Fee payment (core execution)', 'execution')
    s.seq(st, d, fee, tl, pay, ex)
    clock = s.svc('p4_clock', 'Statutory clock starts on fee confirmation', C['SLA'],
                  {'op': 'DEADLINE', 'clock': 'case', 'spec': '${caseSla}'})
    again = s.user('p4_payAgain', 'Payment failed: pay again | भुगतान विफल: पुनः भुगतान करें', '${initiatorSeat}',
                   form='p4-licence/pay-again', decision=('payDecision', ['resubmit', 'withdraw']), fields=[])
    outcome(s, 'p4_feeGw', 'executionOutcome', {'DONE': clock}, again)
    s.flow(ex, 'p4_feeGw')
    pg = s.xgw('p4_payAgainGw')
    s.flow(again, pg)
    s.flow(pg, ex, "payDecision == 'resubmit'", 'pay again')
    s.flow(pg, withdraw(p, s, 'p4_wdFee'), None, 'withdraw', default=True)
    sseat = s.svc('p4_scrutinySeat', 'Scrutiny clerk for the ward', C['SEAT'],
                  {'op': 'ROLE', 'role': 'SCRUTINY-CLERK', 'targetVar': 'scrutinySeat'})
    scr = core(s, 'p4_scrutiny', 'Scrutiny with clarification (core)', 'scrutiny')
    s.seq(clock, sseat, scr)
    rejected = rejection(p, s, 'p4_rj', reopen='p4_breachRule')
    insp_rule = s.rule('p4_inspRule', 'Inspection required? (DMN)', 'p4-inspection-required')
    lapsed = reject_with(p, s, 'p4_rjLapsed', 'CLARIFICATION_LAPSED')
    outcome(s, 'p4_scrGw', 'scrutinyOutcome', {'ACCEPTED': insp_rule, 'LAPSED': lapsed}, rejected)
    s.flow(scr, 'p4_scrGw')
    ig = s.xgw('p4_inspGw', 'Inspection?')
    s.flow(insp_rule, ig)
    iseat = s.svc('p4_inspSeat', 'Inspector for the ward', C['SEAT'],
                  {'op': 'ROLE', 'role': 'INSPECTOR', 'targetVar': 'inspectorSeat'})
    s.flow(ig, iseat, 'inspectionRequired', 'yes')
    ie, idn = s.sla_task('p4_inspect', 'Schedule and conduct inspection | निरीक्षण निर्धारित एवं संपन्न करें',
                         'inspectorSeat', form='p4-licence/inspection',
                         decision=('inspectionDecision', ['satisfactory', 'unsatisfactory', 'reschedule']),
                         fields=['inspectionSlot', 'findings', 'photos', 'geoTag', 'rescheduleReason',
                                 'rescheduleRequestedBy'], spec='${inspectionSla}', sod=True)
    s.flow(iseat, ie)
    rg = s.xgw('p4_inspRes', 'Inspection result?')
    s.flow(idn, rg)
    who = s.xgw('p4_whoGw', 'Requested by applicant?')
    s.flow(rg, who, "inspectionDecision == 'reschedule'", 'J-P4-E05')
    ps = s.svc('p4_pause', 'Pause clock (applicant asked)', C['SLA'], {'op': 'PAUSE', 'reason': 'applicant reschedule'})
    nn = s.notify('p4_newSlot', 'Tell applicant the new slot', 'inspection-rescheduled', 'sms,inapp', 'applicantRef')
    s.flow(who, ps, "rescheduleRequestedBy == 'applicant'", 'applicant')
    s.flow(who, nn, None, 'department', default=True)
    s.flow(ps, nn)
    s.flow(nn, ie)
    rs = s.svc('p4_resume', 'Resume clock', C['SLA'], {'op': 'RESUME'})
    s.flow(rg, rs, "inspectionDecision == 'satisfactory'", 'satisfactory')
    s.flow(rg, reject_with(p, s, 'p4_rjInsp', 'INSPECTION_UNSATISFACTORY'), None, 'unsatisfactory', default=True)
    bre = s.setv('p4_breachRule', 'Approval breach rule from the timeline', values='sanctionOnBreach=@deemedAction;'
                 'sanctionSla=@approvalSla')
    s.flow(ig, bre, None, 'no inspection', default=True)
    s.flow(rs, bre)
    san = core(s, 'p4_approve', 'Approval (core sanction, deemed approval if the law provides)', 'sanction')
    s.flow(bre, san)
    cert = s.svc('p4_certificate', 'Signed certificate from the approved data', C['DOC'],
                 {'template': 'licence-certificate', 'requireApproval': 'true', 'resultVar': 'licenceNo'})
    outcome(s, 'p4_sanGw', 'sanctionOutcome', {'SANCTIONED': cert, 'RETURNED': sseat}, rejected)
    s.flow(san, 'p4_sanGw')
    push = s.setv('p4_pushConfig', 'DigiLocker push step',
                  values='executionConnector=digilocker.push;executionOperation=pushCertificate;'
                         'executionStep=digilocker;verifyApproval=true;reconcileRequired=false;ackWindow=PT1H;'
                         'executionPayloadFields=licenceNo,applicantName,licenceType,approvedPayloadHash')
    px = core(s, 'p4_push', 'Push to DigiLocker (core execution)', 'execution')
    s.seq(cert, push, px)
    cl = core(s, 'p4_close', 'Notify and close (core)', 'closure')
    partial = s.notify('p4_pushPending', 'Partial success: DigiLocker copy pending', 'digilocker-pending', 'sms,inapp',
                       'applicantRef')
    outcome(s, 'p4_pushGw', 'executionOutcome', {'DONE': cl}, partial)
    s.flow(px, 'p4_pushGw')
    s.flow(partial, cl)
    s.flow(cl, end_ok(p, s, 'p4_end', 'Licence issued'))
    # appeal after rejection: allowed appeal goes back to approval with the original officer excluded (K5)
    s.side_exits('p4')
    pk.dmn('p4-fee', 'P4 licence fee', 'FIRST',
           [('Licence type', 'licenceType', 'string'), ('Area (sq m)', 'premisesArea', 'double'),
            ('Years', 'durationYears', 'integer')],
           [('fee', 'Fee (INR)', 'double')],
           [([q('TRADE'), '<= 50', '-'], ['2000'], 'small shop'), ([q('TRADE'), '<= 200', '-'], ['5000'], ''),
            ([q('TRADE'), '> 200', '-'], ['10000'], ''), ([q('FOOD'), '-', '-'], ['7500'], ''),
            ([q('FACTORY'), '-', '-'], ['25000'], ''), (['-', '-', '-'], ['3000'], 'default')],
           doc='SYNTHETIC fee table; fee x years is applied by the gateway mandate in the pilot.')
    pk.dmn('p4-service-timeline', 'P4 notified timeline and breach rule (time-bound service law)', 'FIRST',
           [('Licence type', 'licenceType', 'string')],
           [('caseSla', 'Statutory timeline', 'string'), ('approvalSla', 'Approval stage SLA', 'string'),
            ('inspectionSla', 'Inspection SLA', 'string'), ('deemedAction', 'On breach', 'string'),
            ('deemedRule', 'Rule cited', 'string')],
           [([q('TRADE')], [q('30D'), q('5WD'), q('7WD'), q('DEEMED_APPROVAL'), q('Time-bound Services Act s.5 (synthetic)')], ''),
            ([q('FOOD')], [q('60D'), q('7WD'), q('10WD'), q('ESCALATE'), q('')], ''),
            ([q('FACTORY')], [q('90D'), q('10WD'), q('15WD'), q('ESCALATE'), q('')], ''),
            (['-'], [q('30D'), q('5WD'), q('7WD'), q('ESCALATE'), q('')], '')],
           doc='Deemed approval only where the state law provides it (to confirm with the G1 buyer).')
    pk.dmn('p4-inspection-required', 'P4 inspection required?', 'FIRST',
           [('Licence type', 'licenceType', 'string'), ('Area (sq m)', 'premisesArea', 'double')],
           [('inspectionRequired', 'Inspection', 'boolean')],
           [([q('FOOD'), '-'], ['true'], ''), ([q('FACTORY'), '-'], ['true'], ''), (['-', '> 200'], ['true'], ''),
            (['-', '-'], ['false'], '')])
    pk.dmn('p4-approving-authority', 'P4 approving authority', 'FIRST',
           [('Licence type', 'licenceType', 'string'), ('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')],
           [([q('FACTORY'), '-'], [q('DC')], 'Deputy Commissioner'), (['-', '-'], [q('LO')], 'Licensing officer')])
    pk.cmmn('sangam-p4-licence-case', P4_CASE)
    pk.seeds(offices=[{'id': 'MC', 'parent': ''}, {'id': 'WARD-7', 'parent': 'MC'}],
             seats=[{'id': 'CLERK-W7', 'role': 'SCRUTINY-CLERK', 'office': 'WARD-7', 'reportsTo': 'LO-MC',
                     'name': {'en': 'Scrutiny clerk, Ward 7', 'hi': 'संवीक्षा लिपिक, वार्ड 7'}},
                    {'id': 'INSP-W7', 'role': 'INSPECTOR', 'office': 'WARD-7', 'reportsTo': 'LO-MC',
                     'name': {'en': 'Inspector, Ward 7', 'hi': 'निरीक्षक, वार्ड 7'}},
                    {'id': 'LO-MC', 'role': 'LO', 'office': 'MC', 'reportsTo': 'DC-MC',
                     'name': {'en': 'Licensing officer', 'hi': 'अनुज्ञापन अधिकारी'}},
                    {'id': 'DC-MC', 'role': 'DC', 'office': 'MC',
                     'name': {'en': 'Deputy Commissioner (escalation)', 'hi': 'उपायुक्त (वृद्धि)'}},
                    {'id': 'APPELLATE-LIC', 'role': 'APPELLATE', 'office': 'MC',
                     'name': {'en': 'Appellate authority (Commissioner)', 'hi': 'अपीलीय प्राधिकारी (आयुक्त)'}}],
             holdings=[{'seat': s_, 'person': 'p4.' + s_.lower(), 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'}
                       for s_ in ['CLERK-W7', 'INSP-W7', 'LO-MC', 'DC-MC', 'APPELLATE-LIC']],
             roles=[{'code': 'SCRUTINY-CLERK', 'name': {'en': 'Scrutiny clerk', 'hi': 'संवीक्षा लिपिक'}},
                    {'code': 'INSPECTOR', 'name': {'en': 'Inspector', 'hi': 'निरीक्षक'}},
                    {'code': 'LO', 'name': {'en': 'Licensing officer', 'hi': 'अनुज्ञापन अधिकारी'}},
                    {'code': 'DC', 'name': {'en': 'Deputy Commissioner', 'hi': 'उपायुक्त'}}])
    app = {'applicantName': 'Ramesh Traders', 'applicantRef': 'mobile:97XXXXXX21', 'licenceType': 'TRADE',
           'tradeName': 'Ramesh General Store', 'premisesAddress': 'Shop 4, Ward 7', 'premisesArea': 35.0,
           'durationYears': 1, 'officeId': 'WARD-7', 'initiator': 'citizen.ramesh'}
    pk.accept('AC-06', 'SLA breach escalates; lost fee callback reconciled; signed certificate issued', ['T22', 'T47'],
              'trade licence, sim_paygov_fee = UNKNOWN_THEN_COMPLETED', 'callback lost; approval stage overdue',
              'fee confirmed by status lookup, SLA_BREACHED then ESCALATED or DEEMED_DECISION, signed certificate, '
              'DigiLocker push', dict(app, sim_paygov_fee='UNKNOWN_THEN_COMPLETED'), 'CLOSED')
    pk.accept('J-P4-E08', 'DigiLocker push fails: partial success', ['TP-07'], 'sim_digilocker_push = FAILED:ISSUER_DOWN',
              'push fails', 'certificate issued; applicant told the DigiLocker copy is pending',
              dict(app, sim_digilocker_push='FAILED:ISSUER_DOWN'), 'CLOSED')


P4_CASE = '''<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
             xmlns:operaton="http://operaton.org/schema/1.0/cmmn"
             id="defs_p4_licence_case" targetNamespace="http://sangam.org/packs">
  <!-- SPDX-License-Identifier: Apache-2.0 -->
  <!-- P4 licence, case variant (hardened from spike WF-3 case, GP-21): scrutiny runs at once; clarification is a
       repeatable discretionary task; inspection becomes available after scrutiny and is manually activated (not
       every licence needs it); approval needs scrutiny; the milestone Approved completes the case. Candidate groups
       are seats. The SLA runs in the BPMN process task (CMMN timer listeners are not supported, spike E16-b). -->
  <case id="sangam-p4-licence-case" name="P4 Licence case" operaton:historyTimeToLive="3650">
    <casePlanModel id="p4c_plan" name="Licence case" autoComplete="true">
      <planItem id="p4c_pi_scrutiny" definitionRef="p4c_scrutiny"/>
      <planItem id="p4c_pi_clarify" definitionRef="p4c_clarify">
        <itemControl id="p4c_ic_clarify">
          <repetitionRule id="p4c_rep"><condition id="p4c_c1">${true}</condition></repetitionRule>
          <manualActivationRule id="p4c_mar1"><condition id="p4c_c2">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="p4c_pi_inspection" definitionRef="p4c_inspection">
        <itemControl id="p4c_ic_insp">
          <manualActivationRule id="p4c_mar2"><condition id="p4c_c3">${true}</condition></manualActivationRule>
        </itemControl>
        <entryCriterion id="p4c_ec_insp" sentryRef="p4c_s_scrutinised"/>
      </planItem>
      <planItem id="p4c_pi_approval" definitionRef="p4c_approval">
        <itemControl id="p4c_ic_appr">
          <requiredRule id="p4c_req"><condition id="p4c_c4">${true}</condition></requiredRule>
        </itemControl>
        <entryCriterion id="p4c_ec_appr" sentryRef="p4c_s_scrutinised"/>
      </planItem>
      <planItem id="p4c_pi_sla" definitionRef="p4c_sla">
        <exitCriterion id="p4c_xc_sla" sentryRef="p4c_s_approved"/>
      </planItem>
      <planItem id="p4c_pi_approved" definitionRef="p4c_approvedMs">
        <entryCriterion id="p4c_ec_ms" sentryRef="p4c_s_approved"/>
      </planItem>
      <sentry id="p4c_s_scrutinised" name="Scrutinised">
        <planItemOnPart id="p4c_op1" sourceRef="p4c_pi_scrutiny"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <sentry id="p4c_s_approved" name="Approved">
        <planItemOnPart id="p4c_op2" sourceRef="p4c_pi_approval"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <humanTask id="p4c_scrutiny" name="Scrutiny" operaton:candidateGroups="CLERK-W7"/>
      <humanTask id="p4c_clarify" name="Seek clarification from applicant" operaton:candidateGroups="CLERK-W7"/>
      <humanTask id="p4c_inspection" name="Inspection" operaton:candidateGroups="INSP-W7"/>
      <humanTask id="p4c_approval" name="Approval" operaton:candidateGroups="LO-MC"/>
      <processTask id="p4c_sla" name="Statutory clock" processRef="sangam-p4-case-sla" isBlocking="true"/>
      <milestone id="p4c_approvedMs" name="Approved"/>
    </casePlanModel>
  </case>
</definitions>
'''


def p4_case_sla(pk):
    p = pk.proc('sangam-p4-case-sla', 'P4 case variant: statutory clock',
                'Runs as the blocking process task of the CMMN case; exits when the case is approved.')
    s = p.root
    st = s.start('pcs_start', 'Clock started', initiator=None)
    d = s.svc('pcs_deadline', 'Deadline', C['SLA'], {'op': 'DEADLINE', 'clock': 'case', 'spec': '30D'})
    w = s.catch_timer('pcs_due', 'Timeline lapsed', date='${caseDueAt}')
    t = s.user('pcs_escalate', 'Licence overdue: direct action | अनुज्ञप्ति विलंबित: कार्रवाई निर्देशित करें', 'DC-MC',
               form='p4-licence/case-overdue', decision=None, fields=['remarks*'])
    e = s.end('pcs_end', 'Escalation handled')
    s.seq(st, d, w, t, e)


def build():
    p1()
    p2()
    p3()
    dbt_batch()
    p4()
    from packs import ALL
    p4_case_sla([x for x in ALL if x.id == 'gov.p4.licence'][0])
