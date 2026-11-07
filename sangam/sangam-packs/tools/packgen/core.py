# SPDX-License-Identifier: Apache-2.0
"""The reusable government approval core (IX.3) as callable BPMN building blocks."""
from lib import C, q
from packs import Pack

core = Pack('sangam.core', 'core', 'Government approval core', 'सरकारी अनुमोदन मूल ढाँचा', 'Core',
            'Shared lifecycle DRAFT > SUBMITTED > SCRUTINY <> CLARIFICATION_SOUGHT > RECOMMENDATION (1..n) > '
            'SANCTION > EXECUTION > ACKNOWLEDGED > RECONCILED > CLOSED with side exits REJECTED, WITHDRAWN, '
            'CANCELLED and UNKNOWN_OUTCOME, as call activities every pack composes (IX.3).',
            ['IX.3', 'IX.5', 'R31', 'R41', 'R42', 'R43', 'R19', 'R57', 'R61', 'VI.3', 'VI.5', 'A-2', 'GP-01', 'GP-02',
             'GP-03', 'GP-04', 'GP-06', 'GP-14', 'GP-16', 'GP-18', 'GP-22', 'GP-32', 'GP-33', 'GP-34', 'GP-44',
             'GP-45', 'GP-20', 'GP-38', 'UP-05', 'CX-1', 'CX-2', 'CX-4', 'CX-5', 'CX-8', 'K4', 'K5', 'K6', 'K7', 'K8',
             'K10', 'K13', 'AC-07', 'AC-08', 'AC-09', 'AC-10', 'AC-11', 'R54'],
            connectors=[('esign.sign', 'b', 'G-SIGN', 'Signature over the approved payload hash'),
                        ('dsc.sign', 'a', 'G-SIGN', 'DSC signer agent'),
                        ('docs.issue', 'a', 'G-LANG', 'Bilingual output documents'),
                        ('notify.sms', 'b', 'G-DLT', 'SMS on DLT templates'),
                        ('notify.email', 'b', 'G-MAIL', 'Official e-mail'),
                        ('notify.inapp', 'b', '', 'In-app notification'),
                        ('referral.send', 'b', '', 'Referral to another department inbox (AC-09)'),
                        ('referral.reply', 'b', '', 'Reply to a referral'),
                        ('bank.statement', 'b', 'G-PFMS', 'Statement / status match for reconciliation')],
            depends=[], main=['sangam-core-scrutiny'])

DEC = ('scrutinyDecision', ['accept', 'clarify', 'reject'])


def scrutiny():
    p = core.proc('sangam-core-scrutiny', 'Core: scrutiny and clarification',
                  'SCRUTINY <> CLARIFICATION_SOUGHT (IX.3, GP-16). The statutory clock pauses while the applicant is '
                  'asked for clarification when the pack says so (GP-45, J-P4 step 6), and the case version moves '
                  'n to n+1 on the response (R43). Inputs: scrutinySeat, scrutinySla, clarificationWindow, '
                  'pauseOnClarification. Output: scrutinyOutcome ACCEPTED / REJECTED / LAPSED.')
    s = p.root
    st = s.start('sc_start', 'Scrutiny requested', initiator=None)
    d = s.setv('sc_defaults', 'Defaults', defaults='scrutinySeat=scrutiny-pool;scrutinySla=5WD;'
               'scrutinyOnBreach=ESCALATE;clarificationWindow=P15D;pauseOnClarification=true')
    lc = s.state('sc_state', 'SCRUTINY')
    entry, done = s.sla_task('sc_scrutiny', 'Scrutiny | संवीक्षा', 'scrutinySeat', form='core/scrutiny', decision=DEC,
                             fields=['remarks', 'clarificationQuery', 'rejectionReasonCodes', 'rejectionRemarks',
                                     'documents', 'presentedPayloadHash'],
                             spec='${scrutinySla}', on_breach='${scrutinyOnBreach}', sod=True)
    s.seq(st, d, lc, entry)
    gw = s.xgw('sc_gw', 'Scrutiny decision?')
    s.flow(done, gw)
    acc = s.setv('sc_accepted', 'Accepted', values='scrutinyOutcome=ACCEPTED')
    rej = s.setv('sc_rejected', 'Rejected', values='scrutinyOutcome=REJECTED')
    e1 = s.end('sc_endAccepted', 'Accepted')
    e2 = s.end('sc_endRejected', 'Rejected')
    s.flow(gw, acc, "scrutinyDecision == 'accept'", 'accept')
    s.flow(gw, rej, None, 'reject', default=True)
    s.seq(acc, e1)
    s.seq(rej, e2)
    cs = s.state('sc_clarState', 'CLARIFICATION_SOUGHT')
    s.flow(gw, cs, "scrutinyDecision == 'clarify'", 'clarify')
    pg = s.xgw('sc_pauseGw', 'Pause clock?')
    pause = s.svc('sc_pause', 'Pause statutory clock', C['SLA'], {'op': 'PAUSE', 'reason': 'clarification sought'})
    pj = s.xgw('sc_pauseJoin')
    s.flow(cs, pg)
    s.flow(pg, pause, 'pauseOnClarification', 'pause')
    s.flow(pg, pj, None, 'no pause', default=True)
    s.flow(pause, pj)
    dl = s.svc('sc_deadline', 'Clarification window', C['SLA'], {'op': 'DEADLINE', 'clock': 'clarification',
                                                                  'spec': '${clarificationWindow}'})
    nt = s.notify('sc_notify', 'Tell applicant', 'clarification-sought', 'sms,email,inapp', 'applicantRef')
    wait = s.egw('sc_wait', 'Response or lapse')
    s.seq(pj, dl, nt, wait)
    rcv = s.catch_msg('sc_received', 'Clarification received', 'sangam.clarificationReceived')
    lap = s.catch_timer('sc_lapsed', 'Window lapsed', date='${clarificationDueAt}')
    s.flow(wait, rcv)
    s.flow(wait, lap)
    res = s.svc('sc_resume', 'Resume statutory clock', C['SLA'], {'op': 'RESUME'})
    ver = s.svc('sc_version', 'New case version', C['CASE'], {'op': 'RESUBMIT'})
    back = s.state('sc_backToScrutiny', 'SCRUTINY')
    s.seq(rcv, res, ver, back, entry)
    res2 = s.svc('sc_resume2', 'Resume statutory clock', C['SLA'], {'op': 'RESUME'})
    lapsed = s.setv('sc_lapsedOut', 'Lapsed', values='scrutinyOutcome=LAPSED')
    e3 = s.end('sc_endLapsed', 'Lapsed')
    s.seq(lap, res2, lapsed, e3)


def recommendation():
    p = core.proc('sangam-core-recommendation', 'Core: n-level recommendation by seat',
                  'RECOMMENDATION 1..n by seat without multi-instance (GP-02): the chain is recommendationSeats; '
                  'any level may recommend, return to any earlier level or to the initiator, reject, seek parallel '
                  'comments (J-P2-E05) or mark out of hierarchy with a reason (J-P2-E01). SoD by person and seat per '
                  'level (K5, J-P2-E04). Output: recOutcome RECOMMENDED / RETURNED / REJECTED.')
    s = p.root
    st = s.start('rc_start', 'Recommendation requested', initiator=None)
    d = s.setv('rc_defaults', 'Defaults', defaults='recommendationSla=3WD;recOnBreach=ESCALATE;resubmitTo=START;'
               'outOfHierarchyAllowed=true;commentsWindow=P7D;recommendationSeats=;signEachLevel=false;signMethod=esign;espId=ESP-1')
    lc = s.state('rc_state', 'RECOMMENDATION')
    init = s.svc('rc_init', 'First level', C['REC'], {'op': 'INIT'})
    dg = s.xgw('rc_doneGw', 'All levels done?')
    s.seq(st, d, lc, init, dg)
    ok = s.setv('rc_recommended', 'Recommended', values='recOutcome=RECOMMENDED')
    e1 = s.end('rc_endRecommended', 'Recommended')
    s.flow(dg, ok, 'recDone', 'done')
    s.seq(ok, e1)
    entry, done = s.sla_task('rc_level', 'Recommendation | अनुशंसा', 'currentSeat', form='core/recommendation',
                             decision=('recDecision', ['recommend', 'return', 'reject', 'seekComments', 'markTo']),
                             fields=['noting', 'remarks', 'returnToLevel', 'returnReason', 'commentSeats',
                                     'markToSeat', 'markReason', 'presentedPayloadHash'],
                             spec='${recommendationSla}', on_breach='${recOnBreach}', sod=True, step_suffix='recLevel')
    s.flow(dg, entry, None, 'next level', default=True)
    g = s.xgw('rc_decGw', 'Decision?')
    s.flow(done, g)
    adv = s.svc('rc_advance', 'Next level', C['REC'], {'op': 'ADVANCE'})
    sgw = s.xgw('rc_signGw', 'Sign noting?')
    s.flow(g, sgw, "recDecision == 'recommend'", 'recommend')
    sign = s.svc('rc_sign', 'Sign noting (DSC / eSign)', C['SIGN'], {'op': 'REQUEST', 'purpose': 'noting'}, async_=True)
    s.flow(sgw, sign, 'signEachLevel', 'CSMOP: sign before forwarding')
    s.flow(sgw, adv, None, 'no signature', default=True)
    sres = s.xgw('rc_signRes', 'Signed?')
    s.flow(sign, sres)
    s.flow(sres, adv, "signatureStatus == 'COMPLETED'", 'signed')
    sfail = s.notify('rc_signFailed', 'Signature not applied: the file has not moved', 'signature-failed', 'inapp',
                     'currentSeat')
    s.flow(sres, sfail, None, 'not signed', default=True)
    s.flow(sfail, entry)
    s.flow(adv, dg)
    ret = s.svc('rc_return', 'Return', C['REC'], {'op': 'RETURN'})
    s.flow(g, ret, "recDecision == 'return'", 'return')
    rg = s.xgw('rc_retGw', 'To initiator?')
    s.flow(ret, rg)
    ro = s.setv('rc_returned', 'Returned', values='recOutcome=RETURNED')
    e2 = s.end('rc_endReturned', 'Returned to initiator')
    s.flow(rg, ro, 'recReturnedToInitiator', 'initiator')
    s.flow(rg, dg, None, 'earlier level', default=True)
    s.seq(ro, e2)
    mk = s.svc('rc_mark', 'Out-of-hierarchy marking', C['REC'], {'op': 'MARK'})
    s.flow(g, mk, "recDecision == 'markTo'", 'mark')
    s.flow(mk, dg)
    cp = s.svc('rc_commentsPrep', 'Seek comments', C['REC'], {'op': 'COMMENTS'})
    s.flow(g, cp, "recDecision == 'seekComments'", 'comments')
    cm = s.user('rc_comments', 'Comments | टिप्पणियाँ', "${commentSeats.split(',')[loopCounter]}",
                form='core/comments', decision=None, fields=['comment*', 'documents'],
                mi={'count': '${commentCount}', 'max': 10})
    tmo = s.boundary('rc_commentsDeadline', cm, 'Comment deadline', timer_duration='${commentsWindow}')
    s.flow(cp, cm)
    s.flow(cm, entry)
    s.flow(tmo, entry)
    rj = s.setv('rc_rejected', 'Rejected', values='recOutcome=REJECTED')
    e3 = s.end('rc_endRejected', 'Rejected')
    s.flow(g, rj, None, 'reject', default=True)
    s.seq(rj, e3)


def sanction():
    p = core.proc('sangam-core-sanction', 'Core: sanction by the competent authority',
                  'SANCTION / APPROVAL (IX.3): the competent role comes from the routing DMN (routingDecisionKey, '
                  'the rule in force today), the seat from the hierarchy (IX.5 rule 2); at completion the seat\'s '
                  'delegated power is checked against the powers DMN (K6), SoD by person and seat (K5), and the '
                  'approval is bound to the payload hash (K4). Deemed approval by the system principal on behalf of '
                  'the seat where the law provides it (J-P4-E07). Optional signature over the approved hash (GP-34, '
                  'J-P2-E06/E07). Output: sanctionOutcome SANCTIONED / REJECTED / RETURNED.')
    s = p.root
    st = s.start('sn_start', 'Sanction requested', initiator=None)
    d = s.setv('sn_defaults', 'Defaults', values='deemedDecision=false',
               defaults='sanctionSla=5WD;sanctionOnBreach=ESCALATE;signatureRequired=false;'
                        'powersDecisionKey=sangam-core-powers;routingDecisionKey=sangam-core-competent-authority;'
                        'caseType=GENERAL;signMethod=esign;espId=ESP-1')
    lc = s.state('sn_state', 'SANCTION')
    rt = s.rule('sn_route', 'Competent authority (DMN)', '${routingDecisionKey}', binding='latest')
    seat = s.svc('sn_seat', 'Resolve competent seat', C['SEAT'], {'op': 'ROLE', 'roleVar': 'competentRole',
                                                                   'targetVar': 'sanctionSeat'})
    entry, done = s.sla_task('sn_sanction', 'Sanction / approval | स्वीकृति / अनुमोदन', 'sanctionSeat',
                             form='core/sanction',
                             decision=('sanctionDecision', ['approve', 'reject', 'return', 'forward']),
                             fields=['remarks', 'rejectionReasonCodes', 'rejectionRemarks', 'returnReason',
                                     'signMethod', 'presentedPayloadHash', 'sodExceptionId'],
                             spec='${sanctionSla}', on_breach='${sanctionOnBreach}', sod=True,
                             power={'decisionKey': '${powersDecisionKey}', 'approveValues': 'approve'},
                             bind='approve')
    s.seq(st, d, lc, rt, seat, entry)
    dg = s.xgw('sn_deemedGw', 'Deemed?')
    s.flow(done, dg)
    bd = s.svc('sn_bindDeemed', 'Bind deemed approval', C['CASE'], {'op': 'BIND_DEEMED'})
    g = s.xgw('sn_decGw', 'Decision?')
    s.flow(dg, bd, "deemedDecision && sanctionDecision == 'approve'", 'deemed approval')
    s.flow(dg, g, None, default=True)
    s.flow(bd, g)
    sg = s.xgw('sn_signGw', 'Signature required?')
    s.flow(g, sg, "sanctionDecision == 'approve'", 'approve')
    sign = s.svc('sn_sign', 'Sign approved payload', C['SIGN'], {'op': 'REQUEST', 'purpose': 'sanction'}, async_=True)
    s.flow(sg, sign, 'signatureRequired && !deemedDecision', 'sign')
    approved = s.state('sn_approved', 'APPROVED')
    s.flow(sg, approved, None, 'no signature', default=True)
    sr = s.xgw('sn_signRes', 'Signed?')
    s.flow(sign, sr)
    s.flow(sr, approved, "signatureStatus == 'COMPLETED'", 'signed')
    sw = s.egw('sn_signWait', 'Awaiting ESP')
    s.flow(sr, sw, "signatureStatus == 'PENDING'", 'pending')
    smsg = s.catch_msg('sn_signMsg', 'Signature result', 'sangam.signatureResult')
    stmo = s.catch_timer('sn_signTimeout', 'ESP timeout', duration='PT15M')
    s.flow(sw, smsg)
    s.flow(sw, stmo)
    sap = s.svc('sn_signApply', 'Apply signature result', C['SIGN'], {'op': 'APPLY_RESULT'})
    s.seq(smsg, sap, sr)
    sun = s.setv('sn_signUnknown', 'Signature status unknown', values='signatureStatus=UNKNOWN')
    s.seq(stmo, sun)
    sfail = s.notify('sn_signFailed', 'Signature not applied: the case has not moved', 'signature-failed', 'inapp',
                     'sanctionSeat')
    s.flow(sr, sfail, None, 'failed', default=True)
    s.flow(sun, sfail)
    s.flow(sfail, entry)
    so = s.setv('sn_sanctioned', 'Sanctioned', values='sanctionOutcome=SANCTIONED')
    e1 = s.end('sn_endSanctioned', 'Sanctioned')
    s.seq(approved, so, e1)
    rj = s.setv('sn_rejected', 'Rejected', values='sanctionOutcome=REJECTED')
    e2 = s.end('sn_endRejected', 'Rejected')
    s.flow(g, rj, "sanctionDecision == 'reject'", 'reject')
    s.seq(rj, e2)
    rt2 = s.setv('sn_returned', 'Returned', values='sanctionOutcome=RETURNED')
    e3 = s.end('sn_endReturned', 'Returned')
    s.flow(g, rt2, "sanctionDecision == 'return'", 'return')
    s.seq(rt2, e3)
    fw = s.svc('sn_forward', 'Forward to higher seat', C['SEAT'], {'op': 'ESCALATE', 'targetVar': 'sanctionSeat'})
    s.flow(g, fw, None, 'forward', default=True)
    s.flow(fw, entry)


def execution():
    p = core.proc('sangam-core-execution', 'Core: execution with operationId, acknowledgement and reconciliation',
                  'EXECUTION > ACKNOWLEDGED > RECONCILED with UNKNOWN_OUTCOME (IX.3, VI.3, A-2, AC-03). The approval '
                  'hash is re-verified first (K4: a change after approval throws APPROVAL_INVALIDATED, J-P1-E01); the '
                  'effect goes through the ConnectorGateway with operationId = UUIDv5(businessKey, step, attempt); an '
                  'unknown outcome opens a reconciliation item and nothing is repeated automatically for class (c). '
                  'Inputs: executionConnector, executionOperation, executionStep, reconcileRequired, ackWindow. '
                  'Output: executionOutcome DONE / FAILED (opReasonCode).')
    s = p.root
    st = s.start('ex_start', 'Execution requested', initiator=None)
    d = s.setv('ex_defaults', 'Defaults', defaults='ackWindow=PT4H;reconcileRequired=false;verifyApproval=true;'
               'executionStep=execute;executionOperation=execute;executionPayloadFields=;reconRetry=PT1H;'
               'reconciliationSeat=reconciliation-desk')
    lc = s.state('ex_state', 'EXECUTION')
    prep = s.svc('ex_prepare', 'Verify approval, derive operationId', C['OP'],
                 {'op': 'PREPARE', 'step': '${executionStep}', 'connector': '${executionConnector}',
                  'verifyApproval': '${verifyApproval}'})
    sub = s.svc('ex_submit', 'Submit external operation', C['OP'],
                {'op': 'SUBMIT', 'connector': '${executionConnector}', 'operation': '${executionOperation}',
                 'payloadFields': '${executionPayloadFields}'}, async_=True)
    g = s.xgw('ex_status', 'Outcome?')
    s.seq(st, d, lc, prep, sub, g)
    ack = s.state('ex_ack', 'ACKNOWLEDGED')
    s.flow(g, ack, "opStatus == 'COMPLETED'", 'completed')
    fo = s.setv('ex_failed', 'Failed', values='executionOutcome=FAILED')
    e1 = s.end('ex_endFailed', 'Confirmed failure')
    s.flow(g, fo, "opStatus == 'FAILED'", 'failed')
    s.seq(fo, e1)
    w = s.egw('ex_wait', 'Result or window')
    s.flow(g, w, "opStatus == 'PENDING'", 'pending')
    rm = s.catch_msg('ex_result', 'Operation result (inbox)', 'sangam.operationResult')
    to = s.catch_timer('ex_window', 'Acknowledgement window', duration='${ackWindow}')
    s.flow(w, rm)
    s.flow(w, to)
    ap = s.svc('ex_applyResult', 'Apply result', C['OP'], {'op': 'APPLY_RESULT'})
    lk = s.svc('ex_lookup', 'Look up outcome', C['OP'], {'op': 'LOOKUP', 'maxLookups': '${maxLookups}'}, async_=True)
    s.seq(rm, ap, g)
    s.seq(to, lk, g)
    s.flow(g, sub, "opStatus == 'RETRY'", 'retry same operationId')
    rec = s.svc('ex_unknown', 'UNKNOWN_OUTCOME: open reconciliation item', C['OP'],
                {'op': 'OPEN_RECON', 'kind': 'UNKNOWN_OUTCOME'})
    s.flow(g, rec, None, 'unknown', default=True)
    nr = s.notify('ex_notifyOps', 'Alert reconciliation desk', 'recon-item-opened', 'inapp,email',
                  'reconciliationSeat')
    rr = s.catch_msg('ex_resolved', 'Resolved by operator', 'sangam.reconciliationResolved')
    res = s.svc('ex_applyResolution', 'Apply resolution', C['OP'], {'op': 'APPLY_RESOLUTION'})
    s.seq(rec, nr, rr, res, g)
    rg = s.xgw('ex_reconGw', 'Reconcile with statement?')
    s.flow(ack, rg)
    done = s.setv('ex_done', 'Done', values='executionOutcome=DONE')
    e2 = s.end('ex_endDone', 'Done')
    s.flow(rg, done, None, 'no', default=True)
    s.seq(done, e2)
    rc = s.svc('ex_reconcile', 'Match with bank / treasury statement', C['OP'], {'op': 'RECONCILE'}, async_=True)
    s.flow(rg, rc, 'reconcileRequired', 'yes')
    mg = s.xgw('ex_matchGw', 'Matched?')
    s.flow(rc, mg)
    rcd = s.state('ex_reconciled', 'RECONCILED')
    s.flow(mg, rcd, "reconStatus == 'MATCHED'", 'matched')
    s.flow(rcd, done)
    mm = s.svc('ex_mismatch', 'Open mismatch item', C['OP'], {'op': 'OPEN_RECON', 'kind': 'MISMATCH'})
    s.flow(mg, mm, "reconStatus == 'MISMATCH'", 'mismatch')
    mr = s.catch_msg('ex_mmResolved', 'Mismatch resolved', 'sangam.reconciliationResolved')
    ma = s.svc('ex_mmApply', 'Apply resolution', C['OP'], {'op': 'APPLY_RESOLUTION'})
    mg2 = s.xgw('ex_mmGw', 'Resolution?')
    s.seq(mm, mr, ma, mg2)
    s.flow(mg2, rcd, "opStatus == 'COMPLETED'", 'confirmed')
    s.flow(mg2, fo, "opStatus == 'FAILED'", 'failed')
    s.flow(mg2, rc, None, 'check again', default=True)
    pw = s.catch_timer('ex_reconWait', 'Statement not yet in', duration='${reconRetry}')
    s.flow(mg, pw, None, 'pending', default=True)
    s.flow(pw, rc)


def closure():
    p = core.proc('sangam-core-closure', 'Core: output, notification, retention and closure',
                  'CLOSED (IX.3): output document from the approved data only, notification on registered '
                  'templates (R60), retention classification at closure (R61, CSMOP A / C) and the final state.')
    s = p.root
    st = s.start('cl_start', 'Close case', initiator=None)
    d = s.setv('cl_defaults', 'Defaults', defaults='outputTemplate=generic-order;retentionCategory=C;'
               'closureTemplate=case-closed;issueOutput=true;requireApprovalForOutput=false')
    g = s.xgw('cl_outGw', 'Issue output?')
    doc = s.svc('cl_issue', 'Issue output document', C['DOC'],
                {'template': '${outputTemplate}', 'requireApproval': '${requireApprovalForOutput}'})
    j = s.xgw('cl_join')
    s.seq(st, d, g)
    s.flow(g, doc, 'issueOutput', 'yes')
    s.flow(g, j, None, 'no', default=True)
    s.flow(doc, j)
    n = s.notify('cl_notify', 'Notify', '${closureTemplate}', 'sms,email,inapp', 'applicantRef')
    r = s.svc('cl_retention', 'Classify for retention', C['CASE'], {'op': 'RETENTION'})
    c = s.state('cl_closed', 'CLOSED')
    e = s.end('cl_end', 'Closed')
    s.seq(j, n, r, c, e)


def rejection():
    p = core.proc('sangam-core-rejection', 'Core: rejection with reasons and appeal window',
                  'REJECTED (IX.3) with coded reasons, a bilingual rejection order with the appeal route and '
                  'deadline (J-P3-E01), and the appeal window (GP-22). Output: rejectionOutcome FINAL / '
                  'APPEAL_ALLOWED (the pack then re-verifies with a different officer).')
    s = p.root
    st = s.start('rj_start', 'Reject', initiator=None)
    d = s.setv('rj_defaults', 'Defaults', defaults='appealAllowed=true;appealWindow=P30D;'
               'appellateSeat=appellate-authority;rejectionTemplate=rejection-order')
    rec = s.svc('rj_record', 'Record rejection with reasons', C['CASE'], {'op': 'REJECT'})
    doc = s.svc('rj_order', 'Issue rejection order', C['DOC'], {'template': '${rejectionTemplate}'})
    n = s.notify('rj_notify', 'Tell applicant', 'case-rejected', 'sms,email,inapp', 'applicantRef')
    g = s.xgw('rj_appealGw', 'Appeal provided?')
    s.seq(st, d, rec, doc, n, g)
    ret = s.svc('rj_retention', 'Classify for retention', C['CASE'], {'op': 'RETENTION'})
    fin = s.setv('rj_final', 'Final', values='rejectionOutcome=FINAL')
    e1 = s.end('rj_endFinal', 'Rejection final')
    s.flow(g, ret, None, 'no', default=True)
    s.seq(ret, fin, e1)
    dl = s.svc('rj_window', 'Open appeal window', C['SLA'], {'op': 'DEADLINE', 'clock': 'appeal',
                                                           'spec': '${appealWindow}'})
    s.flow(g, dl, 'appealAllowed', 'yes')
    w = s.egw('rj_wait', 'Appeal or lapse')
    s.flow(dl, w)
    am = s.catch_msg('rj_appealFiled', 'Appeal filed', 'sangam.appealFiled')
    at = s.catch_timer('rj_lapse', 'Appeal window lapsed', date='${appealDueAt}')
    s.flow(w, am)
    s.flow(w, at)
    s.flow(at, ret)
    ca = s.call('rj_appeal', 'Appeal (core)', 'sangam-core-appeal')
    s.flow(am, ca)
    ag = s.xgw('rj_appealRes', 'Allowed?')
    s.flow(ca, ag)
    al = s.setv('rj_allowed', 'Appeal allowed', values='rejectionOutcome=APPEAL_ALLOWED')
    e2 = s.end('rj_endAllowed', 'Appeal allowed')
    s.flow(ag, al, "appealOutcome == 'ALLOWED'", 'allowed')
    s.flow(ag, ret, None, 'dismissed', default=True)
    s.seq(al, e2)


def appeal():
    p = core.proc('sangam-core-appeal', 'Core: appeal or reopen',
                  'Appeal after rejection or deemed refusal (GP-22, J-P3-E04, J-P4-E06): the appellate seat decides; '
                  'everyone who acted on the original case is excluded (K5 extended). Output: appealOutcome '
                  'ALLOWED / DISMISSED.')
    s = p.root
    st = s.start('ap_start', 'Appeal filed', initiator=None)
    d = s.setv('ap_defaults', 'Defaults', defaults='appealSla=30D;appealOnBreach=ESCALATE;'
               'appellateSeat=appellate-authority')
    rec = s.svc('ap_record', 'Record appeal', C['CASE'], {'op': 'APPEAL'})
    lc = s.state('ap_state', 'APPEAL')
    entry, done = s.sla_task('ap_hear', 'Decide appeal | अपील पर निर्णय', 'appellateSeat', form='core/appeal',
                             decision=('appealDecision', ['allow', 'remand', 'dismiss']),
                             fields=['appealGrounds', 'appealDecisionReasons*', 'documents'],
                             spec='${appealSla}', on_breach='${appealOnBreach}', sod=True,
                             sod_exclude='appealExcludedPersons')
    s.seq(st, d, rec, lc, entry)
    doc = s.svc('ap_order', 'Issue appellate order', C['DOC'], {'template': 'appellate-order'})
    g = s.xgw('ap_gw', 'Allowed?')
    s.seq(done, doc, g)
    al = s.setv('ap_allowed', 'Allowed', values='appealOutcome=ALLOWED')
    di = s.setv('ap_dismissed', 'Dismissed', values='appealOutcome=DISMISSED')
    e1 = s.end('ap_endAllowed', 'Allowed')
    e2 = s.end('ap_endDismissed', 'Dismissed')
    s.flow(g, al, "appealDecision == 'allow' || appealDecision == 'remand'", 'allow / remand')
    s.flow(g, di, None, 'dismiss', default=True)
    s.seq(al, e1)
    s.seq(di, e2)


def cancellation():
    p = core.proc('sangam-core-cancellation', 'Core: withdrawal and authorised cancellation',
                  'WITHDRAWN / CANCELLED (IX.3, VI.5, GP-14): an in-flight external operation goes to '
                  'CANCEL_REQUESTED; an acknowledgement cancels, an effect that already happened becomes a '
                  'LATE_RESULT reconciliation item, no acknowledgement in the window becomes UNKNOWN_OUTCOME '
                  '(J-P1-E13). Encumbrances are released (GP-11) and the record is classified for retention.')
    s = p.root
    st = s.start('cn_start', 'Cancellation requested', initiator=None)
    d = s.setv('cn_defaults', 'Defaults', defaults='cancelKind=CANCELLED;cancelAckWindow=PT2H;opStatus=NONE;'
               'encumbranceLedger=;encumbranceAccount=;releaseConnector=;cancelReason=not stated')
    lc = s.state('cn_state', 'CANCEL_REQUESTED')
    g = s.xgw('cn_inflight', 'Operation in flight?')
    s.seq(st, d, lc, g)
    cc = s.svc('cn_cancelOp', 'Cancel at external system', C['OP'], {'op': 'CANCEL'}, async_=True)
    s.flow(g, cc, "opStatus == 'PENDING' || opStatus == 'UNKNOWN' || opStatus == 'NEW'", 'yes')
    rel_gw = s.xgw('cn_relGw', 'Encumbrance?')
    s.flow(g, rel_gw, None, 'no', default=True)
    cg = s.xgw('cn_cancelGw', 'Cancel result?')
    s.flow(cc, cg)
    s.flow(cg, rel_gw, "cancelStatus == 'CANCELLED'", 'cancelled')
    late = s.svc('cn_late', 'Effect already done: late-result item', C['OP'], {'op': 'OPEN_RECON',
                                                                             'kind': 'LATE_RESULT'})
    s.flow(cg, late, "cancelStatus == 'COMPLETED'", 'too late')
    s.flow(late, rel_gw)
    cw = s.egw('cn_wait', 'Ack or window')
    s.flow(cg, cw, "cancelStatus == 'PENDING'", 'pending')
    ack = s.catch_msg('cn_ack', 'Cancel acknowledged', 'sangam.cancelAck')
    tmo = s.catch_timer('cn_ackWindow', 'No acknowledgement', duration='${cancelAckWindow}')
    s.flow(cw, ack)
    s.flow(cw, tmo)
    apa = s.svc('cn_applyAck', 'Apply acknowledgement', C['OP'], {'op': 'APPLY_CANCEL_ACK'})
    s.seq(ack, apa, cg)
    un = s.svc('cn_unknown', 'UNKNOWN_OUTCOME: reconciliation', C['OP'], {'op': 'OPEN_RECON',
                                                                        'kind': 'UNKNOWN_OUTCOME'})
    s.flow(tmo, un)
    s.flow(cg, un, None, 'unknown', default=True)
    rr = s.catch_msg('cn_resolved', 'Resolved by operator', 'sangam.reconciliationResolved')
    ra = s.svc('cn_applyResolution', 'Apply resolution', C['OP'], {'op': 'APPLY_RESOLUTION'})
    s.seq(un, rr, ra, rel_gw)
    rel = s.svc('cn_release', 'Release ledger encumbrance', C['LEDGER'],
                {'op': 'RELEASE', 'ledger': '${encumbranceLedger}', 'account': '${encumbranceAccount}'})
    j0 = s.xgw('cn_relJoin0')
    s.flow(rel_gw, rel, "encumbranceLedger != ''", 'yes')
    s.flow(rel_gw, j0, None, 'no', default=True)
    s.flow(rel, j0)
    cg2 = s.xgw('cn_relConnGw', 'External encumbrance?')
    s.flow(j0, cg2)
    relc = s.conn('cn_releaseExternal', 'Release external encumbrance', '${releaseConnector}', 'release', 'submit',
                  None, 'release', step='release')
    j = s.xgw('cn_relJoin')
    s.flow(cg2, relc, "releaseConnector != ''", 'yes')
    s.flow(cg2, j, None, 'no', default=True)
    s.flow(relc, j)
    n = s.notify('cn_notify', 'Tell the parties', 'case-cancelled', 'sms,email,inapp', 'applicantRef')
    kg = s.xgw('cn_kindGw', 'Withdrawn or cancelled?')
    s.seq(j, n, kg)
    wd = s.state('cn_withdrawn', 'WITHDRAWN')
    cd = s.state('cn_cancelled', 'CANCELLED')
    s.flow(kg, wd, "cancelKind == 'WITHDRAWN'", 'withdrawn')
    s.flow(kg, cd, None, 'cancelled', default=True)
    ret = s.svc('cn_retention', 'Classify for retention', C['CASE'], {'op': 'RETENTION'})
    e = s.end('cn_end', 'Ended')
    s.flow(wd, ret)
    s.flow(cd, ret)
    s.flow(ret, e)


def reconciliation():
    p = core.proc('sangam-core-reconciliation', 'Core: standalone reconciliation item',
                  'An UNKNOWN_OUTCOME or mismatch item raised outside a running execution (restore from backup with '
                  'known downstream effects J-P1-E14, late result after delete, statement exceptions): the operator '
                  'confirms success with evidence, confirms failure, or retries the same operationId (class a/b '
                  'only), OJ-02.')
    s = p.root
    st = s.start('rn_start', 'Item raised', initiator=None)
    o = s.svc('rn_open', 'Open item', C['OP'], {'op': 'OPEN_RECON', 'kind': '${reconKind}'})
    d = s.setv('rn_defaults', 'Defaults', defaults='reconKind=UNKNOWN_OUTCOME')
    r = s.catch_msg('rn_resolved', 'Resolved', 'sangam.reconciliationResolved')
    a = s.svc('rn_apply', 'Apply resolution', C['OP'], {'op': 'APPLY_RESOLUTION'})
    g = s.xgw('rn_gw', 'Retry?')
    s.seq(st, d, o, r, a, g)
    sub = s.svc('rn_retry', 'Retry same operationId', C['OP'], {'op': 'SUBMIT', 'connector': '${opConnector}'},
                async_=True)
    s.flow(g, sub, "opStatus == 'RETRY'", 'retry')
    s.flow(sub, g)
    e = s.end('rn_end', 'Resolved')
    s.flow(g, e, None, 'resolved', default=True)


def committee():
    p = core.proc('sangam-core-committee', 'Core: committee decision (all / quorum)',
                  'GP-03 (all members must sign) and GP-04 (quorum n of m) for purchase, tender opening and evaluation, '
                  'survey / condemnation and selection boards: members are seats in committeeSeats, at most 50 '
                  '(GP-09 limit, enforced at deploy); quorumRule ALL / MAJORITY / n. Output: committeeApproved.')
    s = p.root
    st = s.start('cm_start', 'Committee convened', initiator=None)
    d = s.setv('cm_defaults', 'Defaults', defaults='quorumRule=ALL;committeeWindow=P7D')
    pr = s.svc('cm_prepare', 'Constitute committee', C['COMM'], {'op': 'PREPARE'})
    t = s.user('cm_member', 'Committee member decision | समिति सदस्य का निर्णय',
               "${committeeSeats.split(',')[loopCounter]}", form='core/committee-member',
               decision=('vote', ['approve', 'reject', 'abstain']), fields=['voteRemarks', 'documents'],
               mi={'count': '${committeeSize}', 'max': 50,
                   'complete': "${quorumRule != 'ALL' && committeeApprovals >= quorum}"},
               listeners=[f'<operaton:taskListener event="complete" class="{C["COMM"]}"/>'])
    b = s.boundary('cm_deadline', t, 'Committee deadline', timer_duration='${committeeWindow}')
    ta = s.svc('cm_tally', 'Tally', C['COMM'], {'op': 'TALLY'})
    e = s.end('cm_end', 'Decided')
    s.seq(st, d, pr, t, ta, e)
    s.flow(b, ta)


def referral():
    p = core.proc('sangam-core-referral', 'Core: inter-department referral / concurrence',
                  'GP-32: the question goes to the other department\'s inbox through the referral connector (no shared '
                  'rows, AC-09, J-P2-E09); the reply comes back as a message; reminders at the SLA.')
    s = p.root
    st = s.start('rf_start', 'Refer', initiator=None)
    d = s.setv('rf_defaults', 'Defaults', defaults='referralSla=P15D')
    snd = s.conn('rf_send', 'Send referral', 'referral.send', 'send', 'submit',
                 'referToTenant,referralQuestion,fileNo,subject', 'referral')
    dl = s.svc('rf_deadline', 'Reply due', C['SLA'], {'op': 'DEADLINE', 'clock': 'referral', 'spec': '${referralSla}'})
    w = s.egw('rf_wait', 'Reply or reminder')
    s.seq(st, d, snd, dl, w)
    rp = s.catch_msg('rf_reply', 'Reply received', 'sangam.referralReply')
    tm = s.catch_timer('rf_due', 'Reply overdue', date='${referralDueAt}')
    s.flow(w, rp)
    s.flow(w, tm)
    rm = s.notify('rf_remind', 'Reminder', 'referral-reminder', 'email,inapp', 'referToTenant')
    s.flow(tm, rm)
    s.flow(rm, dl)
    ok = s.setv('rf_done', 'Replied', values='referralOutcome=REPLIED')
    e = s.end('rf_end', 'Replied')
    s.seq(rp, ok, e)
    p2 = core.proc('sangam-core-referral-inbound', 'Core: referral received from another department',
                   'Receiving side of GP-32: a referral arrives in this tenant\'s inbox, is examined by the referral desk '
                   'and answered through the connector.')
    s = p2.root
    st = s.start('ri_start', 'Referral received', msg='sangam.referralReceived', initiator=None)
    d = s.setv('ri_defaults', 'Defaults', defaults='referralDeskSeat=referral-desk;referralReplySla=10WD')
    entry, done = s.sla_task('ri_examine', 'Examine referral and advise | संदर्भ की जाँच एवं परामर्श',
                             'referralDeskSeat', form='core/referral-inbound',
                             decision=('referralDecision', ['concur', 'notConcur', 'advise']),
                             fields=['referralQuestion', 'referralReply*', 'documents'],
                             spec='${referralReplySla}')
    rp = s.conn('ri_reply', 'Send reply', 'referral.reply', 'reply', 'submit',
                'referralDecision,referralReply,parentBusinessKey', 'reply')
    e = s.end('ri_end', 'Replied')
    s.seq(st, d, entry)
    s.seq(done, rp, e)


def decisions():
    roles = [('SO', 100000), ('US', 500000), ('DS', 2500000), ('JS', 10000000), ('SECRETARY', 100000000)]
    core.dmn('sangam-core-competent-authority', 'Competent authority (generic amount bands)', 'FIRST',
             [('Case type', 'caseType', 'string'), ('Amount (INR)', 'amount', 'double')],
             [('competentRole', 'Competent role', 'string')],
             [(['-', f'<= {lim}'], [q(r)], f'up to {lim}') for r, lim in roles] +
             [(['-', '-'], [q('SECRETARY')], 'above all bands')],
             doc='SYNTHETIC bands for the pilot tenant, not a real delegation-of-powers order. Packs set '
                 'routingDecisionKey to their own table.')
    rules = [([q(r), '-'], [str(lim), 'true'], f'{r} up to {lim}') for r, lim in roles]
    rules += [([q('EE'), '-'], ['500000', 'true'], 'Executive Engineer up to 5 lakh'),
              ([q('SE'), '-'], ['2500000', 'true'], 'Superintending Engineer up to 25 lakh'),
              ([q('CE'), '-'], ['1000000000', 'true'], 'Chief Engineer'),
              ([q('DWO'), '-'], ['200000', 'true'], 'District Welfare Officer up to 2 lakh'),
              ([q('JD'), '-'], ['1000000', 'true'], 'Joint Director up to 10 lakh'),
              ([q('DIRECTOR'), '-'], ['5000000', 'true'], 'Director up to 50 lakh'),
              ([q('LO'), '-'], ['1000000000', 'true'], 'Licensing officer (no money limit)'),
              ([q('DC'), '-'], ['1000000000', 'true'], 'Deputy Commissioner (no money limit)'),
              ([q('HOD'), '-'], ['10000000', 'true'], 'Head of department up to 1 crore'),
              ([q('DDO'), '-'], ['1000000', 'true'], 'Drawing and disbursing officer up to 10 lakh'),
              (['-', '-'], ['0', 'false'], 'no delegated power')]
    core.dmn('sangam-core-powers', 'Delegation of powers by seat role', 'FIRST',
             [('Seat role', 'seatRole', 'string'), ('Case type', 'caseType', 'string')],
             [('powerLimit', 'Power limit (INR)', 'double'), ('allowed', 'Allowed', 'boolean')], rules,
             doc='K6 / IX.5 rule 3: checked at the moment of action against the version in force. SYNTHETIC limits.')
    core.dmn('sangam-core-retention', 'Retention category by case type (R61)', 'FIRST',
             [('Case type', 'caseType', 'string')],
             [('retentionCategory', 'Category', 'string'), ('retentionYears', 'Years', 'integer')],
             [([q('POLICY')], [q('A'), '25'], 'policy files: permanent, review at 25 years'),
              ([q('FILE')], [q('C'), '10'], 'routine e-office files'),
              (['-'], [q('C'), '10'], 'default (CSMOP 2022 category C, up to 10 years)')])


def build():
    scrutiny()
    recommendation()
    sanction()
    execution()
    closure()
    rejection()
    appeal()
    cancellation()
    reconciliation()
    committee()
    referral()
    decisions()
    core.seeds(offices=[{'id': 'HQ', 'parent': ''}],
               seats=[{'id': 'scrutiny-pool', 'role': 'SCRUTINY', 'office': 'HQ', 'reportsTo': 'appellate-authority',
                       'name': {'en': 'Scrutiny pool', 'hi': 'संवीक्षा समूह'}},
                      {'id': 'appellate-authority', 'role': 'APPELLATE', 'office': 'HQ',
                       'name': {'en': 'Appellate authority', 'hi': 'अपीलीय प्राधिकारी'}},
                      {'id': 'reconciliation-desk', 'role': 'RECON', 'office': 'HQ',
                       'name': {'en': 'Reconciliation desk', 'hi': 'मिलान डेस्क'}},
                      {'id': 'referral-desk', 'role': 'REFERRAL', 'office': 'HQ',
                       'name': {'en': 'Referral desk', 'hi': 'संदर्भ डेस्क'}}],
               holdings=[{'seat': 'appellate-authority', 'person': 'aa.demo', 'basis': 'SUBSTANTIVE',
                          'from': '2026-01-01'},
                         {'seat': 'reconciliation-desk', 'person': 'recon.demo', 'basis': 'SUBSTANTIVE',
                          'from': '2026-01-01'}],
               roles=[{'code': 'SO', 'name': {'en': 'Section Officer', 'hi': 'अनुभाग अधिकारी'}},
                      {'code': 'US', 'name': {'en': 'Under Secretary', 'hi': 'अवर सचिव'}},
                      {'code': 'DS', 'name': {'en': 'Deputy Secretary', 'hi': 'उप सचिव'}},
                      {'code': 'JS', 'name': {'en': 'Joint Secretary', 'hi': 'संयुक्त सचिव'}},
                      {'code': 'SECRETARY', 'name': {'en': 'Secretary', 'hi': 'सचिव'}},
                      {'code': 'APPELLATE', 'name': {'en': 'Appellate authority', 'hi': 'अपीलीय प्राधिकारी'}}])
    # read by RegistrySeedLoader (packs/*/registry/*.json); national identifiers apply only to that jurisdiction (ADR-D-20)
    core.identifier_types = [('IN', [
        {'typeKey': 'AADHAAR', 'label': 'Aadhaar number', 'pattern': '[2-9][0-9]{11}', 'normalise': ['DIGITS_ONLY'],
         'checksum': 'VERHOEFF', 'unique': True, 'sensitive': True, 'matchWeight': 12, 'appliesTo': 'PERSON'},
        {'typeKey': 'PAN', 'label': 'Permanent account number', 'pattern': '[A-Z]{5}[0-9]{4}[A-Z]',
         'normalise': ['UPPER', 'STRIP_SPACES'], 'unique': True, 'sensitive': True, 'matchWeight': 12},
        {'typeKey': 'GSTIN', 'label': 'GST identification number', 'pattern': '[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]',
         'normalise': ['UPPER', 'STRIP_SPACES'], 'unique': True, 'matchWeight': 12, 'appliesTo': 'ORGANISATION'},
        {'typeKey': 'BENEFICIARY_ID', 'label': 'Scheme beneficiary id (vault token)', 'normalise': ['TRIM'], 'unique': True,
         'matchWeight': 10}]), (None, [
        {'typeKey': 'BANK_ACCOUNT', 'label': 'Bank account', 'pattern': '[0-9A-Z]{6,34}',
         'normalise': ['UPPER', 'STRIP_SPACES', 'STRIP_PUNCT'], 'sensitive': True, 'matchWeight': 6},
        {'typeKey': 'MOBILE', 'label': 'Mobile number', 'normalise': ['DIGITS_ONLY'], 'pattern': '[0-9]{10,15}',
         'sensitive': True, 'matchWeight': 4, 'appliesTo': 'PERSON'}])]
    core.accept('CORE-1', 'Return to any earlier level and resubmission', ['GP-02', 'R43', 'T19'],
                'a chain of three recommendation seats', 'level 3 returns to level 1, then all recommend',
                'caseVersion is unchanged, the case passes levels 1..3 again, CASE_RETURNED is audited')
    core.accept('CORE-2', 'Deemed approval keeps the state machine', ['GP-44', 'J-P4-E07', 'IX.5 rule 6'],
                'sanctionOnBreach = DEEMED_APPROVAL', 'the sanction SLA lapses',
                'DEEMED_DECISION by system on behalf of the seat, approval bound to the payload hash')
    core.accept('CORE-3', 'Unknown outcome is never repeated for class (c)', ['AC-03', 'VI.3', 'T02'],
                'executionConnector = pfms.payment, sim_pfms_payment = UNKNOWN_THEN_COMPLETED',
                'the operator tries RETRY_SAME_OPERATION', 'the retry is refused; CONFIRMED_SUCCESS with evidence closes')
