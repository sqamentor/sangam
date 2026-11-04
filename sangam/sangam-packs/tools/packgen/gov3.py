# SPDX-License-Identifier: Apache-2.0
"""e-Recruitment, RTI, grievance, leave / HR, inspection, stores, assets, court cases, audit paras, land
acquisition, right to public services and records retention."""
from lib import C, q
from packs import Pack
from common import core, outcome, end_ok, rejection, reject_with, withdraw, batch_loop, broadcast
from gov2 import simple_seeds

SIDE = ['GP-14', 'VI.5']


# ------------------------------------------------------------------------------------------------ e-recruitment
def recruitment():
    pk = Pack('gov.recruitment', 'recruitment', 'e-Recruitment', 'ई-भर्ती', 'e-Recruitment',
              'Notification approved and published; every application is its own case that joins the campaign '
              '(GP-08/09) with fee through the gateway (GP-15); eligibility; admit cards in bulk (GP-27); '
              'provisional scores, paid challenges to the answer key, expert review and bulk recompute (GP-47); '
              'merit list with reservation (GP-26) and roster carried forward (GP-48); document verification; '
              'waitlist promotion when a selected candidate declines or fails DV (GP-28) (D-RECRUIT).',
              ['D-RECRUIT', 'GP-08', 'GP-09', 'GP-15', 'GP-24', 'GP-26', 'GP-27', 'GP-28', 'GP-47', 'GP-48', 'GP-33',
               'GP-39'] + SIDE,
              connectors=[('recruit.publish', 'b', '', 'Notification on the recruitment portal'),
                          ('paygov.fee', 'b', 'G-PG', 'Application fee'),
                          ('exam.score', 'a', '', 'Score of a candidate for a key version'),
                          ('docs.render', 'a', 'G-LANG', 'Admit cards and offer letters (external task)')])
    c = pk.proc('sangam-recruitment-campaign', 'Recruitment campaign', '', start={'materialFields': 'postName,vacancies'})
    s = c.root
    st = s.start('rc2_start', 'Requisition', form='recruitment/requisition')
    c.forms.append(dict(key='recruitment/requisition', name='Recruitment requisition | भर्ती मांग', decision=None,
                        fields=['postName*', 'vacancies*', 'qualification', 'documents'], doc=None))
    d = s.setv('rc2_defaults', 'Configuration', values='batchRound=1;scoreRound=1',
               defaults='caseType=RECRUITMENT;amount=0;routingDecisionKey=recruit-authority;applicationWindow=P30D;'
                        'scrutinyPeriod=P15D;examGap=P30D;challengeWindow=P7D;recruitmentValidity=P365D;'
                        'challengeCount=0;examCtrlSeat=EXAM-CTRL;expertSeat=EXPERT-PANEL;appealAllowed=false')
    bid = s.expr('rc2_batchId', 'Campaign id', "${execution.setVariable('batchId', execution.processBusinessKey)}")
    san = core(s, 'rc2_approve', 'Notification approval (core sanction)', 'sanction')
    pub = s.conn('rc2_publish', 'Publish notification', 'recruit.publish', 'publish', 'submit', 'postName,vacancies',
                 'publish', step='publish')
    s.seq(st, d, bid, san)
    rej = rejection(c, s, 'rc2_rj')
    outcome(s, 'rc2_sanGw', 'sanctionOutcome', {'SANCTIONED': pub}, rej)
    s.flow(san, 'rc2_sanGw')
    win = s.svc('rc2_window', 'Application window (GP-24)', C['SLA'], {'op': 'DEADLINE', 'clock': 'applications',
                                                                       'spec': '${applicationWindow}'})
    close = s.catch_timer('rc2_closeApps', 'Applications close', date='${applicationsDueAt}')
    s.seq(pub, win, close)
    n1, g1 = broadcast(s, 'rc2_closed', 'sangam.recruit.applicationsClosed')
    s.flow(close, n1)
    scr = s.catch_timer('rc2_scrutinyPeriod', 'Scrutiny period', duration='${scrutinyPeriod}')
    s.flow(g1, scr, None, 'all told', default=True)
    n2, g2 = broadcast(s, 'rc2_admit', 'sangam.recruit.admitCards')
    s.flow(scr, n2)
    ex = s.svc('rc2_examDate', 'Examination date', C['SLA'], {'op': 'DEADLINE', 'clock': 'exam', 'spec': '${examGap}'})
    s.flow(g2, ex, None, 'admit cards out', default=True)
    exam = s.catch_timer('rc2_exam', 'Examination held', date='${examDueAt}')
    s.flow(ex, exam)
    n3, g3 = broadcast(s, 'rc2_prov', 'sangam.recruit.scores', round_to_open='1')
    s.flow(exam, n3)
    key = s.user('rc2_provKey', 'Publish provisional answer key and results | अनंतिम उत्तर कुंजी एवं परिणाम प्रकाशित करें',
                 '${examCtrlSeat}', form='recruitment/provisional-key', decision=None, fields=['documents*'])
    s.flow(g3, key, None, 'provisional scores requested', default=True)
    cw = s.svc('rc2_challengeWindow', 'Challenge window (GP-47)', C['SLA'],
               {'op': 'DEADLINE', 'clock': 'challenge', 'spec': '${challengeWindow}'})
    cg = s.egw('rc2_challenges', 'Challenges')
    s.seq(key, cw, cg)
    ch = s.catch_msg('rc2_challenge', 'Paid challenge received', 'sangam.recruit.challenge')
    cc = s.expr('rc2_countChallenge', 'Count', "${execution.setVariable('challengeCount', challengeCount + 1)}")
    chc = s.catch_timer('rc2_challengeClose', 'Challenge window closes', date='${challengeDueAt}')
    s.flow(cg, ch)
    s.flow(cg, chc)
    s.seq(ch, cc, cg)
    exp = s.user('rc2_expert', 'Expert review of challenges | चुनौतियों की विशेषज्ञ समीक्षा', '${expertSeat}',
                 form='recruitment/expert-review', decision=('expertVerdict', ['accept', 'reject']),
                 fields=['remarks*', 'documents'])
    s.flow(chc, exp)
    rs = s.setv('rc2_finalRound', 'Final key: score round 2', values='scoreRound=2')
    n4, g4 = broadcast(s, 'rc2_final', 'sangam.recruit.scores', round_to_open='2')
    s.seq(exp, rs, n4)
    w = s.catch_timer('rc2_poll', 'Poll scores', duration='PT10M')
    agg = s.svc('rc2_aggregate', 'Scores recorded?', C['BATCH'], {'op': 'AGGREGATE', 'round': '2'}, async_=True)
    ag = s.xgw('rc2_aggGw', 'All scored?')
    s.flow(g4, w, None, 'final scores requested', default=True)
    s.seq(w, agg, ag)
    s.flow(ag, w, '!batchComplete', 'not yet')
    merit = s.svc('rc2_merit', 'Merit list with reservation (GP-26)', C['MERIT'], {'minimumScore': '0'})
    s.flow(ag, merit, None, 'complete', default=True)
    pubr = s.svc('rc2_resultDoc', 'Publish result', C['DOC'], {'template': 'recruitment-result'})
    n5, g5 = broadcast(s, 'rc2_result', 'sangam.recruit.result', selected_var='selectedKeys')
    s.seq(merit, pubr, n5)
    vw = s.svc('rc2_validity', 'Panel validity', C['SLA'], {'op': 'DEADLINE', 'clock': 'panel',
                                                           'spec': '${recruitmentValidity}'})
    s.flow(g5, vw, None, 'results out', default=True)
    wl = s.egw('rc2_waitlist', 'Vacated posts')
    s.flow(vw, wl)
    vac = s.catch_msg('rc2_vacated', 'Selected candidate vacated', 'sangam.recruit.vacated')
    exp2 = s.catch_timer('rc2_panelExpiry', 'Panel expires', date='${panelDueAt}')
    s.flow(wl, vac)
    s.flow(wl, exp2)
    prom = s.svc('rc2_promote', 'Waitlist promotion (GP-28)', C['WAIT'])
    pg = s.xgw('rc2_promGw', 'Someone to promote?')
    s.seq(vac, prom, pg)
    sel = s.setv('rc2_selectFlag', 'Offer to the promoted candidate', values='promotedSelected=true')
    tell = s.svc('rc2_tellPromoted', 'Tell the promoted candidate', C['CORR'],
                 {'messageName': 'sangam.recruit.result', 'targetBusinessKey': '${promotedKey}',
                  'variables': 'promotedSelected:selected', 'optional': 'true'})
    s.flow(pg, sel, '!waitlistExhausted', 'promote')
    s.seq(sel, tell, wl)
    s.flow(pg, wl, None, 'waitlist exhausted', default=True)
    carry = s.svc('rc2_carry', 'Close the panel (carry-forward recorded in roster)', C['DOC'],
                  {'template': 'recruitment-closure'})
    s.seq(exp2, carry, end_ok(c, s, 'rc2_end', 'Campaign closed'))
    a = pk.proc('sangam-recruitment-application', 'Recruitment application', '',
                start={'materialFields': 'applicantName,dob,socialCategory,qualification'})
    a.listeners.append(f'<operaton:executionListener event="start" class="{C["JOIN"]}">'
                       f'<operaton:field name="batchVar"><operaton:string>campaignId</operaton:string></operaton:field>'
                       f'</operaton:executionListener>')
    s = a.root
    st = s.start('ra_start', 'Application', form='recruitment/application')
    a.forms.append(dict(key='recruitment/application', name='Application for the post | पद हेतु आवेदन', decision=None,
                        fields=['applicantName*', 'applicantRef*', 'dob*', 'socialCategory*', 'qualification*', 'pwd',
                                'documents'], doc=None))
    d = s.setv('ra_defaults', 'Configuration', defaults='pwd=false;fee=0;category=@socialCategory;score=0;'
               'dvSeat=DV-BOARD;joiningWindow=P30D;waitlistValidity=P365D')
    fee = s.rule('ra_fee', 'Fee and exemptions (DMN)', 'recruit-fee')
    fg = s.xgw('ra_feeGw', 'Fee payable?')
    s.seq(st, d, fee, fg)
    pc = s.setv('ra_payCfg', 'Fee step', values='executionConnector=paygov.fee;executionStep=fee;verifyApproval=false;'
                'reconcileRequired=false;executionPayloadFields=fee,applicantRef,campaignId')
    pay = core(s, 'ra_pay', 'Fee (core execution)', 'execution')
    s.flow(fg, pc, 'fee > 0', 'pay')
    s.flow(pc, pay)
    wait1 = s.catch_msg('ra_closed', 'Applications closed', 'sangam.recruit.applicationsClosed')
    s.flow(fg, wait1, None, 'exempt', default=True)
    notpaid = s.end('ra_endUnpaid', 'Fee not paid', listeners=[a.item_listener('FEE_NOT_PAID')])
    outcome(s, 'ra_payGw', 'executionOutcome', {'DONE': wait1}, notpaid)
    s.flow(pay, 'ra_payGw')
    el = s.rule('ra_eligibility', 'Eligibility (DMN)', 'recruit-eligibility')
    eg = s.xgw('ra_eligGw', 'Eligible?')
    s.seq(wait1, el, eg)
    inel_n = s.notify('ra_ineligible', 'Tell candidate: not eligible', 'recruit-ineligible', 'sms,email', 'applicantRef')
    s.flow(eg, inel_n, '!eligible', 'no')
    s.flow(inel_n, s.end('ra_endIneligible', 'Ineligible', listeners=[a.item_listener('INELIGIBLE', round_var='scoreRound')]))
    admit = s.catch_msg('ra_admitMsg', 'Admit cards', 'sangam.recruit.admitCards')
    s.flow(eg, admit, None, 'yes', default=True)
    card = s.ext('ra_admitCard', 'Admit card (bulk, GP-27)', 'sangam.docs.bulk')
    scoreMsg = s.catch_msg('ra_scoreMsg', 'Scores available', 'sangam.recruit.scores')
    s.seq(admit, card, scoreMsg)
    fetch = s.conn('ra_score', 'Fetch score for this key version', 'exam.score', 'score', 'query',
                   'campaignId,scoreRound', 'exam')
    sc = s.setv('ra_scoreSet', 'Score', values='score=@examScore')
    rec = s.throw('ra_scored', 'Score recorded', listeners=[a.item_listener('SCORED', data_vars='score,category,dob,pwd',
                                                                          round_var='scoreRound')])
    s.seq(scoreMsg, fetch, sc, rec)
    w2 = s.egw('ra_awaitResult', 'Recompute or result')
    s.flow(rec, w2)
    again = s.catch_msg('ra_rescore', 'Scores recomputed', 'sangam.recruit.scores')
    res = s.catch_msg('ra_result', 'Result', 'sangam.recruit.result')
    s.flow(w2, again)
    s.flow(w2, res)
    s.flow(again, fetch)
    sg = s.xgw('ra_selGw', 'Selected?')
    s.flow(res, sg)
    dve, dvd = s.sla_task('ra_dv', 'Document verification | दस्तावेज़ सत्यापन', 'dvSeat', form='recruitment/dv',
                          decision=('dvVerdict', ['verified', 'failed']), fields=['remarks', 'documents'],
                          default_spec='10WD', sod=True)
    s.flow(sg, dve, 'selected', 'selected')
    w3 = s.egw('ra_waitlisted', 'Waitlisted')
    s.flow(sg, w3, None, 'waitlisted / not selected', default=True)
    prom = s.catch_msg('ra_promoted', 'Promoted from waitlist', 'sangam.recruit.result')
    wexp = s.catch_timer('ra_waitExpiry', 'Panel expires', duration='${waitlistValidity}')
    s.flow(w3, prom)
    s.flow(w3, wexp)
    s.flow(prom, dve)
    s.flow(wexp, s.end('ra_endNotSelected', 'Not selected'))
    dg = s.xgw('ra_dvGw')
    s.flow(dvd, dg)
    offer = s.ext('ra_offer', 'Offer letter (bulk)', 'sangam.docs.bulk')
    s.flow(dg, offer, "dvVerdict == 'verified'", 'verified')
    vac = s.svc('ra_vacate', 'Tell campaign: post vacated', C['CORR'],
                {'messageName': 'sangam.recruit.vacated', 'targetBusinessKey': '${campaignId}',
                 'variables': 'itemKey:vacatedKey', 'optional': 'true'})
    s.flow(dg, vac, None, 'DV failed', default=True)
    s.flow(vac, s.end('ra_endVacated', 'Vacated'))
    jw = s.egw('ra_joinWait', 'Accept offer?')
    s.flow(offer, jw)
    acc = s.catch_msg('ra_accepted', 'Offer accepted', 'sangam.recruit.offerAccepted')
    dec = s.catch_msg('ra_declined', 'Offer declined', 'sangam.recruit.offerDeclined')
    lap = s.catch_timer('ra_joinLapse', 'Did not join', duration='${joiningWindow}')
    for x in (acc, dec, lap):
        s.flow(jw, x)
    s.flow(dec, vac)
    s.flow(lap, vac)
    appt = s.svc('ra_appointment', 'Appointment order', C['DOC'], {'template': 'appointment-order'})
    s.seq(acc, appt, s.end('ra_endAppointed', 'Appointed'))
    s.side_exits('ra')
    pk.dmn('recruit-fee', 'Application fee and exemptions (synthetic)', 'FIRST',
           [('Category', 'socialCategory', 'string'), ('PwD', 'pwd', 'boolean')], [('fee', 'Fee', 'double')],
           [(['-', 'true'], ['0'], 'PwBD exempt'), ([q('SC'), '-'], ['0'], 'SC exempt'),
            ([q('ST'), '-'], ['0'], 'ST exempt'), (['-', '-'], ['100'], 'others')])
    pk.dmn('recruit-eligibility', 'Eligibility (age, qualification; synthetic)', 'FIRST',
           [('Qualification', 'qualification', 'string')],
           [('eligible', 'Eligible', 'boolean')],
           [([q('BELOW_10TH')], ['false'], ''), (['-'], ['true'], '')])
    pk.dmn('recruit-authority', 'Recruitment notification approval', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')], [(['-'], [q('JS')], '')])
    simple_seeds(pk, 'COMMISSION', [('EXAM-CTRL', 'EXAMCTRL', 'JS-R', 'Controller of examinations', 'परीक्षा नियंत्रक'),
                                    ('EXPERT-PANEL', 'EXPERT', 'EXAM-CTRL', 'Subject expert panel', 'विषय विशेषज्ञ पैनल'),
                                    ('DV-BOARD', 'DV', 'EXAM-CTRL', 'Document verification board', 'दस्तावेज़ सत्यापन बोर्ड'),
                                    ('JS-R', 'JS', None, 'Joint Secretary (recruitment)', 'संयुक्त सचिव (भर्ती)')], 'rc')
    pk.accept('GP-47', 'Answer-key challenge and bulk recompute', ['GP-47', 'GP-09'],
              'provisional scores in round 1', 'challenges accepted by the expert panel',
              'every application re-fetches its score into round 2; the merit list uses round 2',
              {'postName': 'Junior Assistant', 'vacancies': '{"UR":3,"OBC":1,"SC":1}'}, None)
    pk.accept('GP-28', 'Waitlist promotion on decline', ['GP-28', 'GP-26'], 'merit list published',
              'a selected OBC candidate declines the offer', 'the next waitlisted OBC candidate receives the offer',
              None, None)


# ------------------------------------------------------------------------------------------------ RTI
def rti():
    pk = Pack('gov.rti', 'rti', 'Right to Information request', 'सूचना का अधिकार अनुरोध', 'RTI',
              'RTI Act 2005: s.7(1) 30 days or 48 hours for life or liberty, +5 days via APIO, fee exemption for BPL, '
              's.7(3) additional fee pauses the clock (GP-45), s.6(3) transfer, s.11 third-party notice and objection '
              'window (GP-46), deemed refusal on lapse (s.7(2), GP-44), first appeal to the FAA (s.19, GP-22) with the '
              'PIO excluded, closure with retention.',
              ['D-RTI', 'GP-06', 'GP-22', 'GP-35', 'GP-44', 'GP-45', 'GP-46', 'R61', 'GOV-11', 'J-P2-E10'] + SIDE,
              connectors=[('paygov.fee', 'b', 'G-PG', 'RTI fee'), ('referral.send', 'b', '', 's.6(3) transfer'),
                          ('notify.sms', 'b', 'G-DLT', 'Applicant and third-party notices')])
    p = pk.proc('sangam-rti-request', 'RTI request', '', start={'materialFields': 'information,applicantName'})
    s = p.root
    st = s.start('rt_start', 'Request received', form='rti/request')
    p.forms.append(dict(key='rti/request', name='RTI application | सूचना का अधिकार आवेदन', decision=None,
                        fields=['applicantName*', 'applicantRef*', 'information*', 'lifeLiberty', 'bplApplicant',
                                'documents'], doc=None))
    d = s.setv('rt_defaults', 'Configuration', defaults=(
        'lifeLiberty=false;bplApplicant=false;viaApio=false;pioSeat=PIO-1;appellateSeat=FAA-1;appealSla=30D;'
        'appealWindow=P30D;appealAllowed=true;fee=10;thirdPartyWindow=P10D;thirdPartyTimeline=P40D;'
        'additionalFeeWindow=P30D;retentionCategory=C;retentionYears=3;rejectionTemplate=rti-rejection;'
        'outputTemplate=rti-reply;issueOutput=false;thirdPartyName=null'))
    tl = s.rule('rt_limit', 'Time limit (DMN: 30 days / 48 hours / APIO)', 'rti-time-limit')
    clock = s.svc('rt_clock', 'Statutory clock from receipt', C['SLA'], {'op': 'DEADLINE', 'clock': 'case',
                                                                       'spec': '${caseSla}'})
    fg = s.xgw('rt_feeGw', 'Fee payable?')
    s.seq(st, d, tl, clock, fg)
    pc = s.setv('rt_payCfg', 'Fee step', values='executionConnector=paygov.fee;executionStep=fee;verifyApproval=false;'
                'reconcileRequired=false;executionPayloadFields=fee,applicantRef')
    pay = core(s, 'rt_pay', 'RTI fee (core execution)', 'execution')
    pe, pd = s.sla_task('rt_pio', 'PIO: decide request | लोक सूचना अधिकारी: अनुरोध पर निर्णय', 'pioSeat',
                        form='rti/pio-decision',
                        decision=('pioDecision', ['supply', 'reject', 'transfer', 'additionalFee', 'thirdParty']),
                        fields=['replyText', 'exemptionSection', 'additionalFee', 'transferToAuthority',
                                'thirdPartyName', 'documents'],
                        spec='${caseSla}', on_breach='DEEMED_REFUSAL', approve='supply', refuse='reject')
    s.flow(fg, pc, '!bplApplicant', 'yes')
    s.flow(fg, pe, None, 'BPL exempt', default=True)
    s.flow(pc, pay)
    s.flow(pay, pe)
    g = s.xgw('rt_decGw', 'PIO decision?')
    s.flow(pd, g)
    reply = s.svc('rt_reply', 'Issue reply', C['DOC'], {'template': 'rti-reply'})
    s.flow(g, reply, "pioDecision == 'supply'", 'supply')
    nr = s.notify('rt_notifyReply', 'Send reply', 'rti-reply-sent', 'sms,email', 'applicantRef')
    aw = s.svc('rt_appealWindow', 'First appeal window', C['SLA'], {'op': 'DEADLINE', 'clock': 'appeal',
                                                                   'spec': '${appealWindow}'})
    ae = s.egw('rt_appealWait', 'First appeal?')
    s.seq(reply, nr, aw, ae)
    af = s.catch_msg('rt_appealFiled', 'First appeal filed', 'sangam.appealFiled')
    al = s.catch_timer('rt_appealLapse', 'Appeal window lapsed', date='${appealDueAt}')
    s.flow(ae, af)
    s.flow(ae, al)
    ap = core(s, 'rt_firstAppeal', 'First appeal before the FAA (core)', 'appeal')
    s.flow(af, ap)
    apg = s.xgw('rt_appealGw', 'Appeal allowed?')
    s.flow(ap, apg)
    s.flow(apg, pe, "appealOutcome == 'ALLOWED'", 'FAA directs the PIO')
    cl = core(s, 'rt_close', 'Close and classify (core)', 'closure')
    sa = s.notify('rt_secondAppeal', 'Tell applicant about the second appeal (Information Commission)',
                  'rti-second-appeal-route', 'sms,email', 'applicantRef')
    s.flow(apg, sa, None, 'dismissed', default=True)
    s.flow(sa, cl)
    s.flow(al, cl)
    rj = s.setv('rt_rejectReason', 'Exemption as reason', values='rejectionReasonCodes=@exemptionSection')
    s.flow(g, rj, "pioDecision == 'reject'", 'reject / deemed refusal')
    rjc = core(s, 'rt_reject', 'Rejection with appeal route (core)', 'rejection')
    s.flow(rj, rjc)
    rg = s.xgw('rt_rejGw', 'Appeal allowed?')
    s.flow(rjc, rg)
    s.flow(rg, pe, "rejectionOutcome == 'APPEAL_ALLOWED'", 'FAA directs supply')
    s.flow(rg, s.end('rt_endRejected', 'Rejected'), None, 'final', default=True)
    tr = s.conn('rt_transfer', 'Transfer to the public authority (s.6(3))', 'referral.send', 'transferRti', 'submit',
                'information,transferToAuthority,applicantRef', 'transfer', step='transfer')
    s.flow(g, tr, "pioDecision == 'transfer'", 'transfer')
    tn = s.notify('rt_tellTransfer', 'Tell applicant of the transfer', 'rti-transferred', 'sms,email', 'applicantRef')
    s.seq(tr, tn, s.end('rt_endTransferred', 'Transferred'))
    pause = s.svc('rt_pause', 'Pause clock (s.7(3))', C['SLA'], {'op': 'PAUSE', 'reason': 'additional fee awaited'})
    s.flow(g, pause, "pioDecision == 'additionalFee'", 'additional fee')
    an = s.notify('rt_askFee', 'Ask for additional fee', 'rti-additional-fee', 'sms,email', 'applicantRef')
    afw = s.svc('rt_feeWindow', 'Fee window', C['SLA'], {'op': 'DEADLINE', 'clock': 'additionalFee',
                                                         'spec': '${additionalFeeWindow}'})
    fw = s.egw('rt_feeWait', 'Fee paid?')
    s.seq(pause, an, afw, fw)
    fpaid = s.catch_msg('rt_feePaid', 'Additional fee paid', 'sangam.feePaid')
    flap = s.catch_timer('rt_feeLapse', 'Not paid', date='${additionalFeeDueAt}')
    s.flow(fw, fpaid)
    s.flow(fw, flap)
    res = s.svc('rt_resume', 'Resume clock', C['SLA'], {'op': 'RESUME'})
    s.seq(fpaid, res, pe)
    s.flow(flap, cl)
    tp = s.notify('rt_thirdParty', 'Notice to third party (s.11)', 'rti-third-party-notice', 'sms,email', 'thirdPartyName')
    s.flow(g, tp, "pioDecision == 'thirdParty'", 'third party')
    tw = s.svc('rt_tpWindow', 'Objection window', C['SLA'], {'op': 'DEADLINE', 'clock': 'thirdParty',
                                                             'spec': '${thirdPartyWindow}'})
    tclock = s.svc('rt_tpClock', 'Decision within 40 days of receipt', C['SLA'],
                   {'op': 'DEADLINE', 'clock': 'case', 'spec': '${thirdPartyTimeline}'})
    tpw = s.egw('rt_tpWait', 'Submission or lapse')
    s.seq(tp, tw, tclock, tpw)
    sub = s.catch_msg('rt_tpSubmission', 'Third-party submission', 'sangam.thirdPartySubmission')
    tlap = s.catch_timer('rt_tpLapse', 'Window closes', date='${thirdPartyDueAt}')
    s.flow(tpw, sub)
    s.flow(tpw, tlap)
    s.flow(sub, pe)
    s.flow(tlap, pe)
    s.flow(cl, end_ok(p, s, 'rt_end', 'Disposed'))
    s.side_exits('rt')
    pk.dmn('rti-time-limit', 'RTI time limit (RTI Act 2005 s.7)', 'FIRST',
           [('Life or liberty', 'lifeLiberty', 'boolean'), ('Received via APIO', 'viaApio', 'boolean')],
           [('caseSla', 'Time limit', 'string')],
           [(['true', '-'], [q('48H')], 's.7(1) proviso'), (['false', 'true'], [q('35D')], 's.5(2): +5 days via APIO'),
            (['-', '-'], [q('30D')], 's.7(1)')])
    simple_seeds(pk, 'PA', [('PIO-1', 'PIO', 'FAA-1', 'Public information officer', 'लोक सूचना अधिकारी'),
                            ('FAA-1', 'FAA', None, 'First appellate authority', 'प्रथम अपीलीय प्राधिकारी')], 'rti')
    pk.accept('GP-44', 'Deemed refusal on lapse opens the first appeal', ['GP-44', 'GP-35', 'T22'],
              'a request with the 30-day clock', 'the PIO does not decide in time',
              'DEEMED_DECISION (refusal), rejection order, appeal window; FAA hears without the PIO',
              {'applicantName': 'A. Kumar', 'applicantRef': 'mobile:9XXXXXXX01', 'information': 'copy of file notings'},
              None)


# ------------------------------------------------------------------------------------------------ grievance
def grievance():
    pk = Pack('gov.grievance', 'grievance', 'Public grievance (CPGRAMS-style)', 'लोक शिकायत (सीपीग्राम्स-शैली)',
              'Grievance', 'Lodged grievance routed by category to the grievance officer, forwarded to subordinate '
              'offices, interim reply extending the clock, tiered escalation on SLA breach, disposal with action '
              'taken report, citizen rating, appeal and reopen on a poor rating (GP-22); CMMN case variant with '
              'ad-hoc field report, forwarding and hearing (GP-21).',
              ['D-GRIEVANCE', 'GP-06', 'GP-21', 'GP-22', 'GP-33', 'GP-45', 'UP-10'] + SIDE,
              connectors=[('notify.sms', 'b', 'G-DLT', 'Citizen updates')])
    p = pk.proc('sangam-grievance', 'Public grievance', '', start={'materialFields': 'grievanceText,category'})
    s = p.root
    st = s.start('gv_start', 'Grievance lodged', form='grievance/lodge')
    p.forms.append(dict(key='grievance/lodge', name='Lodge grievance | शिकायत दर्ज करें', decision=None,
                        fields=['applicantName*', 'applicantRef*', 'category*', 'grievanceText*', 'district',
                                'documents'], doc=None))
    d = s.setv('gv_defaults', 'Configuration', defaults='appellateSeat=APPELLATE-GRO;appealSla=30D;interimExtension=P30D;'
               'feedbackWindow=P15D;appealAllowed=true;rating=null;retentionCategory=C;retentionYears=3;issueOutput=false;'
               'subordinateOffice=null')
    rt = s.rule('gv_route', 'Route by category (DMN)', 'grievance-routing', binding='latest')
    seat = s.svc('gv_seat', 'Grievance officer seat', C['SEAT'], {'op': 'ROLE', 'roleVar': 'groRole', 'targetVar': 'groSeat'})
    clk = s.svc('gv_clock', 'Resolution clock', C['SLA'], {'op': 'DEADLINE', 'clock': 'case', 'spec': '${resolutionSla}'})
    ack = s.notify('gv_ack', 'Acknowledge with registration number', 'grievance-registered', 'sms,email', 'applicantRef')
    ee, ed = s.sla_task('gv_examine', 'Examine grievance | शिकायत की जाँच', 'groSeat', form='grievance/examine',
                        decision=('groDecision', ['resolve', 'forward', 'interim', 'reject']),
                        fields=['actionTaken', 'interimReply', 'subordinateOffice', 'rejectionReasonCodes', 'documents'],
                        spec='${resolutionSla}', on_breach='ESCALATE', max_esc=3)
    s.seq(st, d, rt, seat, clk, ack, ee)
    g = s.xgw('gv_decGw', 'Decision?')
    s.flow(ed, g)
    fo = s.setv('gv_forwardOffice', 'Subordinate office', values='officeId=@subordinateOffice')
    fs = s.svc('gv_forwardSeat', 'Grievance officer there', C['SEAT'], {'op': 'ROLE', 'roleVar': 'groRole',
                                                                      'targetVar': 'groSeat'})
    s.flow(g, fo, "groDecision == 'forward'", 'forward')
    s.seq(fo, fs, ee)
    it = s.notify('gv_interim', 'Interim reply to citizen', 'grievance-interim', 'sms,email', 'applicantRef')
    ext = s.svc('gv_extend', 'Extended clock', C['SLA'], {'op': 'DEADLINE', 'clock': 'case', 'spec': '${interimExtension}'})
    s.flow(g, it, "groDecision == 'interim'", 'interim reply')
    s.seq(it, ext, ee)
    atr = s.svc('gv_atr', 'Action taken report', C['DOC'], {'template': 'grievance-atr'})
    s.flow(g, atr, "groDecision == 'resolve'", 'resolve')
    nt = s.notify('gv_disposed', 'Disposed: rate the reply', 'grievance-disposed', 'sms,email', 'applicantRef')
    fwd = s.svc('gv_feedbackWindow', 'Feedback window', C['SLA'], {'op': 'DEADLINE', 'clock': 'feedback',
                                                                   'spec': '${feedbackWindow}'})
    fw = s.egw('gv_feedbackWait', 'Rating?')
    s.seq(atr, nt, fwd, fw)
    fb = s.catch_msg('gv_feedback', 'Citizen rating', 'sangam.grievanceFeedback')
    fl = s.catch_timer('gv_feedbackLapse', 'No rating', date='${feedbackDueAt}')
    s.flow(fw, fb)
    s.flow(fw, fl)
    rg = s.xgw('gv_ratingGw', 'Poor rating?')
    s.flow(fb, rg)
    ap = core(s, 'gv_appeal', 'Appeal / reopen (core)', 'appeal')
    s.flow(rg, ap, "rating == 'poor'", 'appeal')
    apg = s.xgw('gv_appealGw', 'Reopen?')
    s.flow(ap, apg)
    reopen = s.setv('gv_reopen', 'Reopen with the appellate direction', values='groSeat=@appellateSeat')
    s.flow(apg, reopen, "appealOutcome == 'ALLOWED'", 'reopen')
    s.flow(reopen, ee)
    cl = core(s, 'gv_close', 'Close (core)', 'closure')
    s.flow(apg, cl, None, 'upheld', default=True)
    s.flow(rg, cl, None, 'satisfied', default=True)
    s.flow(fl, cl)
    s.flow(cl, end_ok(p, s, 'gv_end'))
    rej = rejection(p, s, 'gv_rj', reopen=ee)
    s.flow(g, rej, None, 'reject (not a grievance)', default=True)
    s.side_exits('gv')
    sla = pk.proc('sangam-grievance-sla', 'Grievance case variant: SLA watch',
                  'Blocking process task of the CMMN case; exited when the grievance is disposed.')
    s = sla.root
    a1 = s.start('gs_start', 'Watch', initiator=None)
    a2 = s.catch_timer('gs_due', '21 days', duration='P21D')
    a3 = s.user('gs_review', 'Senior officer review of a delayed grievance | विलंबित शिकायत की वरिष्ठ अधिकारी द्वारा समीक्षा',
                'NODAL-GRV', form='grievance/delayed-review', decision=None, fields=['remarks*'])
    s.seq(a1, a2, a3, s.end('gs_end', 'Reviewed'))
    pk.cmmn('sangam-grievance-case', GRV_CASE)
    pk.dmn('grievance-routing', 'Grievance routing and resolution time', 'FIRST',
           [('Category', 'category', 'string')],
           [('groRole', 'Grievance officer role', 'string'), ('resolutionSla', 'Resolution time', 'string')],
           [([q('PENSION')], [q('GRO-PENSION'), q('21D')], ''), ([q('SERVICE')], [q('GRO'), q('21D')], ''),
            ([q('CORRUPTION')], [q('CVO'), q('30D')], 'vigilance'), (['-'], [q('GRO'), q('21D')], '')],
           doc='CPGRAMS reform (2024) targets 21 days; synthetic roles.')
    pk.seeds(offices=[{'id': 'MIN-G', 'parent': ''}, {'id': 'FIELD-G', 'parent': 'MIN-G'}],
             seats=[{'id': 'GRO-1', 'role': 'GRO', 'office': 'MIN-G', 'reportsTo': 'NODAL-GRV',
                     'name': {'en': 'Grievance officer', 'hi': 'शिकायत अधिकारी'}},
                    {'id': 'GRO-F', 'role': 'GRO', 'office': 'FIELD-G', 'reportsTo': 'GRO-1',
                     'name': {'en': 'Grievance officer (field)', 'hi': 'शिकायत अधिकारी (क्षेत्रीय)'}},
                    {'id': 'GRO-PEN', 'role': 'GRO-PENSION', 'office': 'MIN-G', 'reportsTo': 'NODAL-GRV',
                     'name': {'en': 'Pension grievance officer', 'hi': 'पेंशन शिकायत अधिकारी'}},
                    {'id': 'CVO-1', 'role': 'CVO', 'office': 'MIN-G', 'reportsTo': 'SECY-G',
                     'name': {'en': 'Chief vigilance officer', 'hi': 'मुख्य सतर्कता अधिकारी'}},
                    {'id': 'NODAL-GRV', 'role': 'NODAL', 'office': 'MIN-G', 'reportsTo': 'SECY-G',
                     'name': {'en': 'Nodal grievance officer', 'hi': 'नोडल शिकायत अधिकारी'}},
                    {'id': 'APPELLATE-GRO', 'role': 'APPELLATE', 'office': 'MIN-G', 'reportsTo': 'SECY-G',
                     'name': {'en': 'Appellate authority (grievances)', 'hi': 'अपीलीय प्राधिकारी (शिकायत)'}},
                    {'id': 'SECY-G', 'role': 'SECRETARY', 'office': 'MIN-G', 'name': {'en': 'Secretary', 'hi': 'सचिव'}}],
             holdings=[{'seat': x, 'person': 'gv.' + x.lower(), 'basis': 'SUBSTANTIVE', 'from': '2026-01-01'}
                       for x in ['GRO-1', 'GRO-F', 'GRO-PEN', 'CVO-1', 'NODAL-GRV', 'APPELLATE-GRO', 'SECY-G']],
             roles=[])


GRV_CASE = '''<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
             xmlns:operaton="http://operaton.org/schema/1.0/cmmn"
             id="defs_grievance_case" targetNamespace="http://sangam.org/packs">
  <!-- SPDX-License-Identifier: Apache-2.0 -->
  <!-- Grievance, case variant (hardened from spike D-GRIEVANCE, GP-21): examine at once; seek field report
       (repeatable), forward to a subordinate office and personal hearing are discretionary; dispose needs examine;
       the SLA watch is a blocking process task exited on disposal (CMMN timer listeners unsupported, E16-b). -->
  <case id="sangam-grievance-case" name="Public grievance (case)" operaton:historyTimeToLive="3650">
    <casePlanModel id="grvc_plan" name="Grievance" autoComplete="true">
      <planItem id="grvc_pi_examine" definitionRef="grvc_examine"/>
      <planItem id="grvc_pi_report" definitionRef="grvc_report">
        <itemControl id="grvc_ic1">
          <repetitionRule id="grvc_rep1"><condition id="grvc_c1">${true}</condition></repetitionRule>
          <manualActivationRule id="grvc_mar1"><condition id="grvc_c2">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="grvc_pi_forward" definitionRef="grvc_forward">
        <itemControl id="grvc_ic2">
          <manualActivationRule id="grvc_mar2"><condition id="grvc_c3">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="grvc_pi_hearing" definitionRef="grvc_hearing">
        <itemControl id="grvc_ic3">
          <manualActivationRule id="grvc_mar3"><condition id="grvc_c4">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="grvc_pi_dispose" definitionRef="grvc_dispose">
        <itemControl id="grvc_ic4">
          <requiredRule id="grvc_req"><condition id="grvc_c5">${true}</condition></requiredRule>
        </itemControl>
        <entryCriterion id="grvc_ec_dispose" sentryRef="grvc_s_examined"/>
      </planItem>
      <planItem id="grvc_pi_sla" definitionRef="grvc_sla">
        <exitCriterion id="grvc_xc_sla" sentryRef="grvc_s_disposed"/>
      </planItem>
      <planItem id="grvc_pi_disposed" definitionRef="grvc_disposedMs">
        <entryCriterion id="grvc_ec_ms" sentryRef="grvc_s_disposed"/>
      </planItem>
      <sentry id="grvc_s_examined" name="Examined">
        <planItemOnPart id="grvc_op1" sourceRef="grvc_pi_examine"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <sentry id="grvc_s_disposed" name="Disposed">
        <planItemOnPart id="grvc_op2" sourceRef="grvc_pi_dispose"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <humanTask id="grvc_examine" name="Examine grievance" operaton:candidateGroups="GRO-1"/>
      <humanTask id="grvc_report" name="Seek report from field office" operaton:candidateGroups="GRO-F"/>
      <humanTask id="grvc_forward" name="Forward to subordinate office" operaton:candidateGroups="GRO-1"/>
      <humanTask id="grvc_hearing" name="Personal hearing of complainant" operaton:candidateGroups="GRO-1"/>
      <humanTask id="grvc_dispose" name="Dispose grievance (final reply)" operaton:candidateGroups="GRO-1"/>
      <processTask id="grvc_sla" name="SLA watch" processRef="sangam-grievance-sla" isBlocking="true"/>
      <milestone id="grvc_disposedMs" name="Disposed"/>
    </casePlanModel>
  </case>
</definitions>
'''


# ------------------------------------------------------------------------------------------------ leave / HR
def leave():
    pk = Pack('gov.leave', 'leave', 'Leave and charge arrangement', 'अवकाश एवं प्रभार व्यवस्था', 'HR / leave',
              'Leave application checked against the balance ledger carried across years (GP-48), auto-approved '
              'below the threshold or routed by rule (GP-29), with a state-specific routing override (GP-37); charge '
              'arrangement creates the additional-charge holding for the leave period (GP-18); joining report; '
              'cancellation re-credits; annual credit and lapse one instance per employee (GP-30, GP-09).',
              ['D-LEAVE', 'GP-18', 'GP-29', 'GP-30', 'GP-37', 'GP-48', 'GP-09'] + SIDE,
              connectors=[('hr.chargeArrangement', 'a', '', 'Additional charge for the leave period (WS-SECURITY)'),
                          ('hr.chargeRevert', 'a', '', 'End of the additional charge'),
                          ('hrms.employees', 'a', '', 'Employees for the annual credit')])
    p = pk.proc('sangam-leave', 'Leave application', '',
                start={'materialFields': 'employeeId,leaveType,leaveFrom,leaveTo,leaveDays'})
    s = p.root
    st = s.start('lv_start', 'Leave applied', form='leave/application')
    p.forms.append(dict(key='leave/application', name='Leave application | अवकाश आवेदन', decision=None,
                        fields=['employeeId*', 'leaveType*', 'leaveFrom*', 'leaveTo*', 'leaveDays*', 'stationLeave',
                                'chargeSeat', 'documents'], doc=None))
    d = s.setv('lv_defaults', 'Configuration', defaults='caseType=LEAVE;amount=0;routingDecisionKey=leave-authority;'
               'chargeSeat=null;encumbranceLedger=leave;appealAllowed=false;issueOutput=true;outputTemplate=leave-order')
    acct = s.expr('lv_account', 'Ledger account', "${execution.setVariable('encumbranceAccount', "
                                                  "employeeId.concat(':').concat(leaveType))}")
    bal = s.svc('lv_balance', 'Leave balance (GP-48)', C['LEDGER'], {'op': 'BALANCE', 'ledger': 'leave',
                                                                    'account': '${encumbranceAccount}',
                                                                    'resultVar': 'leaveBalance'})
    rt = s.rule('lv_route', 'Routing (DMN, tenant override GP-37)', 'leave-routing', binding='latest')
    s.seq(st, d, acct, bal, rt)
    rej = rejection(p, s, 'lv_rj')
    auto = s.svc('lv_auto', 'Auto-approved below threshold (GP-29)', C['CASE'], {'op': 'BIND_DEEMED'})
    san = core(s, 'lv_sanction', 'Leave sanction (core)', 'sanction')
    debit = s.svc('lv_debit', 'Debit balance', C['LEDGER'], {'op': 'ENCUMBER', 'ledger': 'leave',
                                                             'account': '${encumbranceAccount}',
                                                             'amount': '${leaveDays}'})
    short = reject_with(p, s, 'lv_rjShort', 'INSUFFICIENT_LEAVE_BALANCE')
    outcome(s, 'lv_routeGw', 'leaveRoute', {'AUTO': auto, 'INSUFFICIENT': short}, san)
    s.flow(rt, 'lv_routeGw')
    s.flow(auto, debit)
    outcome(s, 'lv_sanGw', 'sanctionOutcome', {'SANCTIONED': debit}, rej)
    s.flow(san, 'lv_sanGw')
    nob = s.boundary('lv_noBalance', debit, 'Insufficient balance', error='INSUFFICIENT_BALANCE')
    s.flow(nob, short)
    order = s.svc('lv_order', 'Leave order', C['DOC'], {'template': 'leave-order'})
    cg = s.xgw('lv_chargeGw', 'Charge arrangement?')
    s.seq(debit, order, cg)
    ch = s.conn('lv_charge', 'Create additional charge for the period (GP-18)', 'hr.chargeArrangement', 'grant', 'submit',
                'employeeId,chargeSeat,leaveFrom,leaveTo', 'charge', step='charge')
    j = s.xgw('lv_chargeJoin')
    s.flow(cg, ch, 'chargeSeat != null', 'yes')
    s.flow(cg, j, None, 'no', default=True)
    s.flow(ch, j)
    back = s.catch_timer('lv_leaveEnds', 'Leave ends', date='${leaveTo}')
    jr = s.user('lv_joining', 'Submit joining report | कार्यग्रहण रिपोर्ट प्रस्तुत करें', '${initiatorSeat}',
                form='leave/joining', decision=None, fields=['joiningReport*', 'remarks'])
    rv = s.conn('lv_revert', 'End the additional charge', 'hr.chargeRevert', 'revert', 'submit', 'employeeId,chargeSeat',
                'chargeRevert', step='charge-revert')
    cl = core(s, 'lv_close', 'Close (core)', 'closure')
    s.seq(j, back, jr, rv, s.setv('lv_noOutput', 'No second order', values='issueOutput=false'), cl,
          end_ok(p, s, 'lv_end', 'Rejoined'))
    s.side_exits('lv')
    rules = [([q('CL'), '<= 3', '-'], [q('AUTO'), q('')], 'casual leave up to 3 days: auto (GP-29)'),
             (['-', '-', '< leaveDays'], [q('INSUFFICIENT'), q('')], 'balance short'),
             ([q('EL'), '<= 30', '-'], [q('CONTROLLING'), q('')], ''),
             (['-', '-', '-'], [q('HOD'), q('')], 'longer leave to the head of department')]
    pk.dmn('leave-routing', 'Leave routing (central rules, synthetic)', 'FIRST',
           [('Leave type', 'leaveType', 'string'), ('Days', 'leaveDays', 'integer'), ('Balance', 'leaveBalance', 'double')],
           [('leaveRoute', 'Route', 'string'), ('note', 'Note', 'string')], rules,
           doc='GP-37: the state-b tenant deploys its own version from overrides/state-b.')
    pk.override('state-b', 'leave-routing', 'Leave routing (State B override)', 'FIRST',
                [('Leave type', 'leaveType', 'string'), ('Days', 'leaveDays', 'integer'),
                 ('Balance', 'leaveBalance', 'double')],
                [('leaveRoute', 'Route', 'string'), ('note', 'Note', 'string')],
                [([q('CL'), '<= 2', '-'], [q('AUTO'), q('State B: 2 days')], ''),
                 (['-', '-', '< leaveDays'], [q('INSUFFICIENT'), q('')], ''),
                 (['-', '-', '-'], [q('CONTROLLING'), q('')], '')])
    pk.dmn('leave-authority', 'Leave sanctioning authority', 'FIRST', [('Route', 'leaveRoute', 'string')],
           [('competentRole', 'Competent role', 'string')],
           [([q('HOD')], [q('HOD')], ''), (['-'], [q('CONTROLLING')], '')])
    y = pk.proc('sangam-leave-yearend', 'Leave: annual credit and lapse', '', start={'materialFields': 'period'})
    s = y.root
    t = s.start('ly_timer', '1 January', timer_cycle='0 0 1 1 1 ?', initiator=None)
    ld = s.svc('ly_load', 'Employees', C['BATCH'], {'op': 'LOAD', 'connector': 'hrms.employees', 'sampleSize': '3'},
               async_=True)
    s.flow(t, ld)
    op, cg = batch_loop(y, s, 'ly', 'sangam-leave-credit')
    s.flow(ld, op)
    s.flow(cg, s.end('ly_end', 'Credited'), None, 'complete', default=True)
    c = pk.proc('sangam-leave-credit', 'Annual leave credit for one employee', '', start={'materialFields': 'key'})
    s = c.root
    a1 = s.start('lc_start', 'Employee', initiator=None)
    a2 = s.expr('lc_account', 'Account', "${execution.setVariable('elAccount', key.concat(':EL'))}")
    a3 = s.svc('lc_balance', 'EL balance', C['LEDGER'], {'op': 'BALANCE', 'ledger': 'leave', 'account': '${elAccount}',
                                                         'resultVar': 'leaveBalance'})
    a4 = s.rule('lc_rule', 'Credit and carry-forward cap (DMN)', 'leave-yearend')
    a5 = s.svc('lc_credit', 'Post net credit', C['LEDGER'], {'op': 'CREDIT', 'ledger': 'leave', 'account': '${elAccount}',
                                                            'amount': '${netCredit}'})
    s.seq(a1, a2, a3, a4, a5, end_ok(c, s, 'lc_end', 'Credited'))
    pk.dmn('leave-yearend', 'EL credit with 300-day cap (synthetic)', 'FIRST', [('Balance', 'leaveBalance', 'double')],
           [('netCredit', 'Net credit', 'double')],
           [(['>= 300'], ['0'], 'at cap: nothing more accrues'), (['> 270'], ['300 - leaveBalance'], 'up to the cap'),
            (['-'], ['30'], '30 days a year')])
    simple_seeds(pk, 'OFFICE-L', [('CONTROLLING-1', 'CONTROLLING', 'HOD-L', 'Controlling officer', 'नियंत्रक अधिकारी'),
                                  ('HOD-L', 'HOD', None, 'Head of department', 'विभागाध्यक्ष')], 'lv')


# ------------------------------------------------------------------------------------------------ inspection
def inspection():
    pk = Pack('gov.inspection', 'inspection', 'Inspection and enforcement', 'निरीक्षण एवं प्रवर्तन', 'Inspection',
              'Risk-based monthly inspection programme (timer start, one case per establishment, GP-09), least-loaded '
              'assignment and reassignment on reschedule (GP-17, R82), inspection with geo-tagged evidence, '
              'compliance notice with window, re-inspection, penalty order by the competent authority.',
              ['GP-17', 'GP-06', 'GP-30', 'GP-09', 'GP-33', 'UP-04'] + SIDE,
              connectors=[('inspection.establishments', 'a', '', 'Establishments due (risk-ranked)')])
    pr = pk.proc('sangam-inspection-programme', 'Inspection programme', '', start={'materialFields': 'period'})
    s = pr.root
    t = s.start('ip_timer', 'Monthly on the 1st', timer_cycle='0 0 3 1 * ?', initiator=None)
    m = s.start('ip_manual', 'Special drive (message)', msg='sangam.inspectionDrive', initiator=None)
    j = s.xgw('ip_join')
    s.flow(t, j)
    s.flow(m, j)
    ld = s.svc('ip_load', 'Establishments due (risk-ranked)', C['BATCH'],
               {'op': 'LOAD', 'connector': 'inspection.establishments', 'sampleSize': '3'}, async_=True)
    s.flow(j, ld)
    op, cg = batch_loop(pr, s, 'ip', 'sangam-inspection', poll='PT1H')
    s.flow(ld, op)
    rep = s.svc('ip_report', 'Programme report', C['DOC'], {'template': 'inspection-programme-report'})
    s.flow(cg, rep, None, 'complete', default=True)
    s.flow(rep, s.end('ip_end', 'Programme closed'))
    p = pk.proc('sangam-inspection', 'Inspection of an establishment', '', start={'materialFields': 'key,premisesId'})
    s = p.root
    st = s.start('in_start', 'Inspection due', form='inspection/case')
    p.forms.append(dict(key='inspection/case', name='Inspection case | निरीक्षण प्रकरण', decision=None,
                        fields=['premisesId*', 'riskScore', 'district'], doc=None))
    d = s.setv('in_defaults', 'Configuration', defaults='inspectorPool=INSP-1,INSP-2,INSP-3;complianceWindow=P30D;'
               'caseType=PENALTY;routingDecisionKey=inspection-penalty-authority;amount=0;appealAllowed=true;'
               'appellateSeat=APPELLATE-INSP;reinspection=false')
    asg = s.svc('in_assign', 'Least-loaded inspector (R82)', C['ASSIGN'],
                {'strategy': 'LEAST_LOADED', 'candidatesVar': 'inspectorPool', 'resultVar': 'inspectorSeat'})
    ie, idn = s.sla_task('in_inspect', 'Schedule and inspect | निरीक्षण निर्धारित एवं संपन्न करें', 'inspectorSeat',
                         form='inspection/inspect', decision=('inspectionDecision', ['satisfactory', 'unsatisfactory',
                                                                                     'reschedule']),
                         fields=['visitDate', 'findings', 'photos', 'geoTag', 'rescheduleReason'], default_spec='10WD')
    s.seq(st, d, asg, ie)
    g = s.xgw('in_gw', 'Result?')
    s.flow(idn, g)
    rr = s.svc('in_reassign', 'Reassign (round robin)', C['ASSIGN'],
               {'strategy': 'ROUND_ROBIN', 'candidatesVar': 'inspectorPool', 'resultVar': 'inspectorSeat'})
    s.flow(g, rr, "inspectionDecision == 'reschedule'", 'reschedule (GP-17)')
    s.flow(rr, ie)
    ok = s.svc('in_report', 'Inspection report', C['DOC'], {'template': 'inspection-report'})
    s.flow(g, ok, "inspectionDecision == 'satisfactory'", 'satisfactory')
    s.flow(ok, end_ok(p, s, 'in_end', 'Inspected'))
    cn = s.svc('in_notice', 'Compliance notice', C['DOC'], {'template': 'compliance-notice'})
    s.flow(g, cn, None, 'unsatisfactory', default=True)
    ntf = s.notify('in_tell', 'Serve notice', 'compliance-notice', 'sms,email', 'premisesId')
    cw = s.svc('in_window', 'Compliance window', C['SLA'], {'op': 'DEADLINE', 'clock': 'compliance',
                                                           'spec': '${complianceWindow}'})
    w = s.egw('in_wait', 'Compliance reported?')
    s.seq(cn, ntf, cw, w)
    rep2 = s.catch_msg('in_reported', 'Compliance reported', 'sangam.complianceReported')
    lap = s.catch_timer('in_lapse', 'Window lapsed', date='${complianceDueAt}')
    s.flow(w, rep2)
    s.flow(w, lap)
    ri = s.setv('in_reinspect', 'Re-inspection', values='reinspection=true')
    s.seq(rep2, ri, ie)
    san = core(s, 'in_penalty', 'Penalty order (core sanction)', 'sanction')
    s.flow(lap, san)
    pen = s.svc('in_penaltyOrder', 'Penalty order', C['DOC'], {'template': 'penalty-order', 'requireApproval': 'true'})
    rej = rejection(p, s, 'in_rj')
    outcome(s, 'in_sanGw', 'sanctionOutcome', {'SANCTIONED': pen}, rej)
    s.flow(san, 'in_sanGw')
    s.flow(pen, end_ok(p, s, 'in_endPenalty', 'Penalty imposed', outcome='PENALTY'))
    pk.dmn('inspection-penalty-authority', 'Penalty authority', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')], [(['-'], [q('DC')], '')])
    simple_seeds(pk, 'DIST-I', [('INSP-1', 'INSPECTOR', 'DC-I', 'Inspector 1', 'निरीक्षक 1'),
                                ('INSP-2', 'INSPECTOR', 'DC-I', 'Inspector 2', 'निरीक्षक 2'),
                                ('INSP-3', 'INSPECTOR', 'DC-I', 'Inspector 3', 'निरीक्षक 3'),
                                ('DC-I', 'DC', 'APPELLATE-INSP', 'Deputy Commissioner', 'उपायुक्त'),
                                ('APPELLATE-INSP', 'APPELLATE', None, 'Appellate authority', 'अपीलीय प्राधिकारी')], 'in')


# ------------------------------------------------------------------------------------------------ stores
def stores():
    pk = Pack('gov.stores', 'stores', 'Stores: physical verification and discrepancies', 'भंडार: भौतिक सत्यापन एवं विसंगतियाँ',
              'Stores / inventory', 'Annual physical verification by a board (timer start, GFR 213/217), book against '
              'physical comparison, one discrepancy case per item (GP-55, GP-09) investigated and resolved by '
              'recovery, adjustment or write-off sanctioned under the write-off powers.',
              ['GP-55', 'GP-30', 'GP-09', 'GP-01'],
              connectors=[('stores.ledger', 'a', '', 'Book balances'), ('stores.adjust', 'a', '', 'Stock adjustment'),
                          ('hr.recovery', 'a', '', 'Recovery from the responsible person')])
    p = pk.proc('sangam-stock-verification', 'Annual stock verification', '', start={'materialFields': 'period'})
    s = p.root
    t = s.start('sv_timer', '1 April', timer_cycle='0 0 4 1 4 ?', initiator=None)
    m = s.start('sv_manual', 'Surprise check (message)', msg='sangam.stockCheck', initiator=None)
    j = s.xgw('sv_join')
    s.flow(t, j)
    s.flow(m, j)
    book = s.conn('sv_book', 'Book balances', 'stores.ledger', 'balances', 'query', 'period', 'ledger')
    cnt = s.user('sv_count', 'Physical verification count | भौतिक सत्यापन गणना', 'VERIFY-BOARD',
                 form='stores/count', decision=None, fields=['physicalCounts*', 'bookBalances*', 'documents'])
    mt = s.svc('sv_match', 'Book against physical', C['MATCH'], {'mode': 'STOCK'})
    g = s.xgw('sv_gw', 'Discrepancies?')
    s.seq(j, book, cnt, mt, g)
    op, cg = batch_loop(p, s, 'sv', 'sangam-stock-discrepancy', inherit='period')
    s.flow(g, op, 'discrepancyCount > 0', 'yes')
    rep = s.svc('sv_report', 'Verification report', C['DOC'], {'template': 'stock-verification-report'})
    s.flow(g, rep, None, 'none', default=True)
    s.flow(cg, rep, None, 'resolved', default=True)
    s.flow(rep, s.end('sv_end', 'Verified'))
    c = pk.proc('sangam-stock-discrepancy', 'Stock discrepancy', '', start={'materialFields': 'key,difference,value'})
    s = c.root
    a1 = s.start('sd_start', 'Discrepancy', initiator=None)
    d = s.setv('sd_defaults', 'Configuration', defaults='storeOfficerSeat=STORE-OFFICER;caseType=WRITE_OFF;amount=0;'
               'routingDecisionKey=stores-writeoff-authority;appealAllowed=false')
    ie, idn = s.sla_task('sd_investigate', 'Investigate discrepancy | विसंगति की जाँच', 'storeOfficerSeat',
                         form='stores/investigate', decision=('discDecision', ['recover', 'writeOff', 'adjust']),
                         fields=['discrepancyCause*', 'recoveryFrom', 'writeOffAmount'], default_spec='10WD')
    s.seq(a1, d, ie)
    g = s.xgw('sd_gw', 'Resolution?')
    s.flow(idn, g)
    wo = s.setv('sd_woAmount', 'Write-off amount', values='amount=@writeOffAmount')
    san = core(s, 'sd_sanction', 'Write-off sanction (core)', 'sanction')
    s.flow(g, wo, "discDecision == 'writeOff'", 'write off')
    s.flow(wo, san)
    adj = s.conn('sd_adjust', 'Adjust stock', 'stores.adjust', 'adjust', 'submit', 'key,difference,discDecision', 'adjust',
                 step='adjust')
    rej = rejection(c, s, 'sd_rj')
    outcome(s, 'sd_sanGw', 'sanctionOutcome', {'SANCTIONED': adj}, rej)
    s.flow(san, 'sd_sanGw')
    rec = s.conn('sd_recover', 'Recovery order', 'hr.recovery', 'recover', 'submit', 'recoveryFrom,value', 'recovery',
                 step='recovery')
    s.flow(g, rec, "discDecision == 'recover'", 'recover')
    s.flow(rec, adj)
    s.flow(g, adj, None, 'adjust', default=True)
    s.flow(adj, end_ok(c, s, 'sd_end', 'Resolved'))
    pk.dmn('stores-writeoff-authority', 'Write-off of losses (synthetic GFR-style powers)', 'FIRST',
           [('Amount', 'amount', 'double')], [('competentRole', 'Competent role', 'string')],
           [(['<= 50000'], [q('HOO')], ''), (['<= 1000000'], [q('HOD')], ''), (['-'], [q('SECRETARY')], '')])
    simple_seeds(pk, 'STORES', [('VERIFY-BOARD', 'BOARD', 'HOO-S', 'Verification board', 'सत्यापन बोर्ड'),
                                ('STORE-OFFICER', 'STOREOFFICER', 'HOO-S', 'Store officer', 'भंडार अधिकारी'),
                                ('HOO-S', 'HOO', 'HOD-S', 'Head of office', 'कार्यालय प्रमुख'),
                                ('HOD-S', 'HOD', None, 'Head of department', 'विभागाध्यक्ष')], 'st')


# ------------------------------------------------------------------------------------------------ assets / vehicles
def assets():
    pk = Pack('gov.assets', 'assets', 'Assets and vehicles: register to disposal', 'परिसंपत्ति एवं वाहन: पंजीकरण से निपटान',
              'Assets / vehicles', 'Registration, periodic maintenance / fitness cycle, condemnation proposal, survey / '
              'condemnation board (committee), condemnation sanction by book value, disposal by e-auction with '
              'relisting, deposit of proceeds and removal from the register; loss reports.',
              ['GP-03', 'GP-10', 'GP-01', 'GP-53'],
              connectors=[('assets.register', 'a', '', 'Asset register'), ('eauction.list', 'b', '', 'e-Auction listing'),
                          ('assets.remove', 'a', '', 'Removal from register')])
    p = pk.proc('sangam-asset-lifecycle', 'Asset / vehicle lifecycle', '', start={'materialFields': 'assetId,assetValue'})
    s = p.root
    st = s.start('as_start', 'Asset acquired', form='assets/register')
    p.forms.append(dict(key='assets/register', name='Register asset | परिसंपत्ति पंजीकृत करें', decision=None,
                        fields=['assetId*', 'assetValue*', 'category', 'documents'], doc=None))
    d = s.setv('as_defaults', 'Configuration', defaults='maintenanceCycle=P180D;assetOfficerSeat=ASSET-OFFICER;'
               'surveyBoard=BOARD-1,BOARD-2,BOARD-3;caseType=CONDEMNATION;amount=@assetValue;'
               'routingDecisionKey=assets-condemnation-authority;auctionWindow=P60D;appealAllowed=false')
    reg = s.conn('as_register', 'Enter in register', 'assets.register', 'register', 'submit', 'assetId,assetValue,category',
                 'register', step='register')
    cyc = s.catch_timer('as_cycle', 'Maintenance due', duration='${maintenanceCycle}')
    mt = s.user('as_maintain', 'Periodic maintenance / fitness | आवधिक रखरखाव / फ़िटनेस', '${assetOfficerSeat}',
                form='assets/maintenance', decision=('conditionDecision', ['fit', 'condemn']), fields=['condition', 'remarks'])
    s.seq(st, d, reg, cyc, mt)
    g = s.xgw('as_gw', 'Fit?')
    s.flow(mt, g)
    s.flow(g, cyc, "conditionDecision == 'fit'", 'fit')
    sb = s.setv('as_board', 'Survey board', values='committeeSeats=@surveyBoard;quorumRule=ALL')
    cm = core(s, 'as_survey', 'Survey / condemnation board (core committee)', 'committee')
    s.flow(g, sb, None, 'condemn', default=True)
    s.seq(sb, cm)
    cg = s.xgw('as_boardGw', 'Board agrees?')
    s.flow(cm, cg)
    s.flow(cg, cyc, '!committeeApproved', 'keep in use')
    san = core(s, 'as_sanction', 'Condemnation sanction (core)', 'sanction')
    s.flow(cg, san, None, 'condemn', default=True)
    lst = s.conn('as_list', 'List on e-auction', 'eauction.list', 'list', 'submit', 'assetId,reservePrice', 'auction',
                 step='auction')
    rej = rejection(p, s, 'as_rj')
    outcome(s, 'as_sanGw', 'sanctionOutcome', {'SANCTIONED': lst}, rej)
    s.flow(san, 'as_sanGw')
    aw = s.svc('as_auctionWindow', 'Auction window', C['SLA'], {'op': 'DEADLINE', 'clock': 'auction',
                                                               'spec': '${auctionWindow}'})
    w = s.egw('as_wait', 'Sold?')
    s.seq(lst, aw, w)
    sold = s.catch_msg('as_sold', 'Auction result', 'sangam.auctionResult')
    unsold = s.catch_timer('as_unsold', 'No bid', date='${auctionDueAt}')
    s.flow(w, sold)
    s.flow(w, unsold)
    rl = s.user('as_relist', 'No bid: revise reserve price and relist | बोली नहीं: आरक्षित मूल्य संशोधित कर पुनः सूचीबद्ध करें',
                '${assetOfficerSeat}', form='assets/relist', decision=None, fields=['reservePrice*'])
    s.seq(unsold, rl, lst)
    rm = s.conn('as_remove', 'Remove from register', 'assets.remove', 'remove', 'submit', 'assetId', 'remove', step='remove')
    s.seq(sold, rm, end_ok(p, s, 'as_end', 'Disposed'))
    loss = s.evsub('as_loss', 'Loss / accident report')
    l1 = loss.start('as_lossStart', 'Loss reported', msg='sangam.assetLost', interrupting=False)
    l2 = loss.user('as_lossReport', 'Report loss or accident | हानि या दुर्घटना की रिपोर्ट', '${assetOfficerSeat}',
                   form='assets/loss', decision=None, fields=['description*', 'documents'])
    loss.seq(l1, l2, loss.end('as_lossEnd', 'Reported'))
    pk.dmn('assets-condemnation-authority', 'Condemnation powers by book value', 'FIRST',
           [('Amount', 'amount', 'double')], [('competentRole', 'Competent role', 'string')],
           [(['<= 500000'], [q('HOO')], ''), (['-'], [q('HOD')], '')])
    simple_seeds(pk, 'OFFICE-A', [('ASSET-OFFICER', 'ASSETOFFICER', 'HOO-A', 'Asset / transport officer', 'परिसंपत्ति / परिवहन अधिकारी'),
                                  ('BOARD-1', 'BOARD', 'HOO-A', 'Survey board member 1', 'सर्वेक्षण बोर्ड सदस्य 1'),
                                  ('BOARD-2', 'BOARD', 'HOO-A', 'Survey board member 2', 'सर्वेक्षण बोर्ड सदस्य 2'),
                                  ('BOARD-3', 'BOARD', 'HOO-A', 'Survey board member 3', 'सर्वेक्षण बोर्ड सदस्य 3'),
                                  ('HOO-A', 'HOO', 'HOD-A', 'Head of office', 'कार्यालय प्रमुख'),
                                  ('HOD-A', 'HOD', None, 'Head of department', 'विभागाध्यक्ष')], 'as')


# ------------------------------------------------------------------------------------------------ court cases
def court():
    pk = Pack('gov.court-case', 'court-case', 'Court case (LIMBS-style)', 'न्यायालय वाद (लिम्ब्स-शैली)', 'Court cases',
              'Notice received, nodal officer engages counsel, para-wise comments and counter affidavit, hearing series '
              'with dates set by the court (GP-51), stay order suspending the impugned case (GP-40), judgment, '
              'compliance or appeal within limitation (linked case).',
              ['GP-51', 'GP-40', 'GP-21', 'GP-22', 'GP-54'],
              connectors=[('court.limbs', 'b', '', 'LIMBS case status / next date')])
    p = pk.proc('sangam-court-case', 'Court case', '', start={'materialFields': 'caseNo,court'})
    s = p.root
    st = s.start('cc_start', 'Notice received', form='court-case/notice')
    p.forms.append(dict(key='court-case/notice', name='Court case notice | न्यायालय वाद सूचना', decision=None,
                        fields=['caseNo*', 'court*', 'subject*', 'impugnedCaseKey', 'nextHearingDate', 'documents'], doc=None))
    d = s.setv('cc_defaults', 'Configuration', defaults='nodalSeat=LEGAL-NODAL;deptSeat=DEPT-OFFICER;'
               'limitationPeriod=P90D;stayInForce=false;impugnedCaseKey=null')
    en = s.user('cc_counsel', 'Engage counsel and assign | अधिवक्ता नियुक्त करें', '${nodalSeat}', form='court-case/counsel',
                decision=None, fields=['counselName*', 'remarks'])
    pe, pd = s.sla_task('cc_comments', 'Para-wise comments | पैरावार टिप्पणियाँ', 'deptSeat', form='court-case/comments',
                        decision=None, fields=['paraWiseComments*', 'documents'], default_spec='10WD')
    ca = s.user('cc_affidavit', 'File counter affidavit | प्रति-शपथपत्र दाखिल करें', '${nodalSeat}', form='court-case/affidavit',
                decision=None, fields=['documents*', 'remarks'])
    hs = s.svc('cc_schedule', 'Next hearing (GP-51)', C['HEAR'])
    s.seq(st, d, en, pe)
    s.seq(pd, ca, hs)
    wa = s.egw('cc_waitA', 'Reminder or new date')
    s.flow(hs, wa)
    rem = s.catch_timer('cc_reminder', 'Three days before', date='${hearingReminderAt}')
    nd1 = s.catch_msg('cc_newDate1', 'Date changed (LIMBS)', 'sangam.hearingDateSet')
    s.flow(wa, rem)
    s.flow(wa, nd1)
    s.flow(nd1, hs)
    br = s.notify('cc_brief', 'Brief counsel', 'hearing-brief', 'email,inapp', 'nodalSeat')
    wb = s.egw('cc_waitB', 'Hearing or new date')
    s.seq(rem, br, wb)
    hr = s.catch_timer('cc_hearing', 'Hearing', date='${hearingAt}')
    nd2 = s.catch_msg('cc_newDate2', 'Date changed (LIMBS)', 'sangam.hearingDateSet')
    s.flow(wb, hr)
    s.flow(wb, nd2)
    s.flow(nd2, hs)
    out = s.user('cc_outcome', 'Record hearing outcome | सुनवाई का परिणाम दर्ज करें', '${nodalSeat}', form='court-case/outcome',
                 decision=('hearingOutcome', ['adjourned', 'interimOrder', 'judgment']),
                 fields=['nextHearingDate', 'stayOrderRef', 'judgment', 'documents'])
    s.flow(hr, out)
    og = s.xgw('cc_outGw', 'Outcome?')
    s.flow(out, og)
    s.flow(og, hs, "hearingOutcome == 'adjourned'", 'adjourned')
    stay = s.svc('cc_stay', 'Stay: suspend the impugned case (GP-40)', C['SUSP'], {'op': 'SUSPEND'})
    s.flow(og, stay, "hearingOutcome == 'interimOrder'", 'stay')
    s.flow(stay, hs)
    rg = s.xgw('cc_stayGw', 'Stay in force?')
    s.flow(og, rg, None, 'judgment', default=True)
    res = s.svc('cc_vacate', 'Stay vacated: resume', C['SUSP'], {'op': 'RESUME'})
    dec = s.user('cc_decide', 'Comply or appeal | अनुपालन या अपील', '${nodalSeat}', form='court-case/decide',
                 decision=('judgmentDecision', ['comply', 'appeal']), fields=['appealRecommended', 'remarks'])
    s.flow(rg, res, 'stayInForce', 'yes')
    s.flow(rg, dec, None, 'no', default=True)
    s.flow(res, dec)
    dg = s.xgw('cc_decGw')
    s.flow(dec, dg)
    lim = s.svc('cc_limitation', 'Limitation period', C['SLA'], {'op': 'DEADLINE', 'clock': 'limitation',
                                                                'spec': '${limitationPeriod}'})
    fa = s.user('cc_fileAppeal', 'File appeal before limitation | परिसीमा से पूर्व अपील दायर करें', '${nodalSeat}',
                form='court-case/file-appeal', decision=None, fields=['caseNo*', 'court*', 'documents'],
                due='${limitationDueAt}')
    ln = s.svc('cc_appealCase', 'Appeal as a linked case', C['LINK'],
               {'processKey': 'sangam-court-case', 'linkType': 'appeal',
                'inherit': 'subject,caseNo,court,impugnedCaseKey,actingSeat,initiator'})
    s.flow(dg, lim, "judgmentDecision == 'appeal'", 'appeal')
    s.seq(lim, fa, ln)
    comp = s.user('cc_comply', 'Implement the order | आदेश का अनुपालन करें', '${deptSeat}', form='court-case/comply',
                  decision=None, fields=['remarks*', 'documents'])
    s.flow(dg, comp, None, 'comply', default=True)
    cl = core(s, 'cc_close', 'Close (core)', 'closure')
    s.flow(ln, cl)
    s.flow(comp, cl)
    s.flow(cl, end_ok(p, s, 'cc_end'))
    simple_seeds(pk, 'LEGAL', [('LEGAL-NODAL', 'LEGALNODAL', 'JS-L', 'Nodal officer (litigation)', 'नोडल अधिकारी (वाद)'),
                               ('DEPT-OFFICER', 'DEPTOFFICER', 'LEGAL-NODAL', 'Dealing officer', 'संबंधित अधिकारी'),
                               ('JS-L', 'JS', None, 'Joint Secretary', 'संयुक्त सचिव')], 'cc')


# ------------------------------------------------------------------------------------------------ audit paras
def audit_para():
    pk = Pack('gov.audit-para', 'audit-para', 'Audit inspection and paras', 'लेखापरीक्षा निरीक्षण एवं पैरा', 'Audit paras',
              'An audit inspection report is received and each para is its own case (GP-09) answered by the owning '
              'office, approved by the head of department, sent to the AG, and settled, dropped or answered again '
              'on the AG verdict; CMMN case variant for ad-hoc audit work (GP-21).',
              ['GP-21', 'GP-36', 'GP-09', 'GP-50'],
              connectors=[('ag.paraReply', 'b', '', 'Reply to the Accountant General')])
    p = pk.proc('sangam-audit-inspection', 'Audit inspection report', '', start={'materialFields': 'subject'})
    s = p.root
    st = s.start('ai_start', 'Inspection report received', form='audit-para/inspection-report')
    p.forms.append(dict(key='audit-para/inspection-report', name='Audit inspection report | लेखापरीक्षा निरीक्षण प्रतिवेदन',
                        decision=None, fields=['subject*', 'auditParas*', 'documents'], doc=None))
    d = s.setv('ai_defaults', 'Paras as items', values='items=@auditParas')
    s.seq(st, d)
    op, cg = batch_loop(p, s, 'ai', 'sangam-audit-para', poll='PT1H', inherit='subject')
    s.flow(d, op)
    rep = s.svc('ai_report', 'Status of paras', C['DOC'], {'template': 'audit-para-status'})
    s.flow(cg, rep, None, 'all settled', default=True)
    s.flow(rep, s.end('ai_end', 'Report closed'))
    c = pk.proc('sangam-audit-para', 'Audit para', '', start={'materialFields': 'paraNo,paraText,replyToAudit'})
    s = c.root
    a1 = s.start('ap2_start', 'Para', initiator=None)
    d = s.setv('ap2_defaults', 'Configuration', defaults='paraOwnerSeat=DDO-AUDIT;caseType=AUDIT_REPLY;amount=0;'
               'routingDecisionKey=audit-reply-authority;appealAllowed=false;verdictWait=P90D;paraNo=@key')
    re_, rd = s.sla_task('ap2_reply', 'Reply to audit para | लेखापरीक्षा पैरा का उत्तर', 'paraOwnerSeat',
                         form='audit-para/reply', decision=None, fields=['paraNo', 'paraText', 'replyToAudit*', 'documents'],
                         default_spec='20WD')
    san = core(s, 'ap2_approve', 'Reply approved by HoD (core sanction)', 'sanction')
    s.seq(a1, d, re_)
    s.seq(rd, san)
    send = s.conn('ap2_send', 'Send reply to AG', 'ag.paraReply', 'reply', 'submit', 'paraNo,replyToAudit', 'agReply',
                  step='ag-reply')
    outcome(s, 'ap2_sanGw', 'sanctionOutcome', {'SANCTIONED': send}, re_)
    s.flow(san, 'ap2_sanGw')
    w = s.egw('ap2_wait', 'AG verdict')
    s.flow(send, w)
    v = s.catch_msg('ap2_verdict', 'AG verdict', 'sangam.auditVerdict')
    rm = s.catch_timer('ap2_remind', 'No verdict yet', duration='${verdictWait}')
    s.flow(w, v)
    s.flow(w, rm)
    rmn = s.notify('ap2_reminder', 'Follow up with AG', 'audit-followup', 'email', 'paraOwnerSeat')
    s.seq(rm, rmn, w)
    vg = s.xgw('ap2_vGw', 'Verdict?')
    s.flow(v, vg)
    s.flow(vg, re_, "agVerdict == 'furtherReply'", 'further reply (GP-50)')
    s.flow(vg, end_ok(c, s, 'ap2_end', 'Settled / dropped'), None, 'settled / dropped', default=True)
    pk.cmmn('sangam-audit-para-case', AUDIT_CASE)
    pk.dmn('audit-reply-authority', 'Audit reply approval', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')], [(['-'], [q('HOD')], '')])
    simple_seeds(pk, 'DEPT-AU', [('DDO-AUDIT', 'DDO', 'HOD-AU', 'Para owner (DDO)', 'पैरा स्वामी (आहरण एवं संवितरण अधिकारी)'),
                                 ('AUDIT-CELL', 'AUDITCELL', 'HOD-AU', 'Internal audit cell', 'आंतरिक लेखापरीक्षा प्रकोष्ठ'),
                                 ('HOD-AU', 'HOD', None, 'Head of department', 'विभागाध्यक्ष')], 'au')


AUDIT_CASE = '''<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
             xmlns:operaton="http://operaton.org/schema/1.0/cmmn"
             id="defs_audit_para_case" targetNamespace="http://sangam.org/packs">
  <!-- SPDX-License-Identifier: Apache-2.0 -->
  <!-- Audit para, case variant (GP-21, hardened from spike gp21-audit-case): draft reply at once; collect records
       and seek comments of another section are discretionary and repeatable; approval needs the draft; milestone
       Sent to AG. -->
  <case id="sangam-audit-para-case" name="Audit para (case)" operaton:historyTimeToLive="3650">
    <casePlanModel id="apc_plan" name="Audit para" autoComplete="true">
      <planItem id="apc_pi_draft" definitionRef="apc_draft"/>
      <planItem id="apc_pi_records" definitionRef="apc_records">
        <itemControl id="apc_ic1">
          <repetitionRule id="apc_rep1"><condition id="apc_c1">${true}</condition></repetitionRule>
          <manualActivationRule id="apc_mar1"><condition id="apc_c2">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="apc_pi_comments" definitionRef="apc_comments">
        <itemControl id="apc_ic2">
          <repetitionRule id="apc_rep2"><condition id="apc_c3">${true}</condition></repetitionRule>
          <manualActivationRule id="apc_mar2"><condition id="apc_c4">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="apc_pi_approve" definitionRef="apc_approve">
        <entryCriterion id="apc_ec1" sentryRef="apc_s_drafted"/>
      </planItem>
      <planItem id="apc_pi_sent" definitionRef="apc_sentMs">
        <entryCriterion id="apc_ec2" sentryRef="apc_s_approved"/>
      </planItem>
      <sentry id="apc_s_drafted" name="Drafted">
        <planItemOnPart id="apc_op1" sourceRef="apc_pi_draft"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <sentry id="apc_s_approved" name="Approved">
        <planItemOnPart id="apc_op2" sourceRef="apc_pi_approve"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <humanTask id="apc_draft" name="Draft reply to the para" operaton:candidateGroups="DDO-AUDIT"/>
      <humanTask id="apc_records" name="Collect records" operaton:candidateGroups="DDO-AUDIT"/>
      <humanTask id="apc_comments" name="Seek comments of another section" operaton:candidateGroups="AUDIT-CELL"/>
      <humanTask id="apc_approve" name="Approve reply" operaton:candidateGroups="HOD-AU"/>
      <milestone id="apc_sentMs" name="Sent to AG"/>
    </casePlanModel>
  </case>
</definitions>
'''


# ------------------------------------------------------------------------------------------------ land acquisition
def land():
    pk = Pack('gov.land-acquisition', 'land-acquisition', 'Land acquisition (RFCTLARR Act 2013)',
              'भूमि अर्जन (आरएफसीटीएलएआरआर अधिनियम 2013)', 'Land acquisition',
              'Social impact assessment (s.4), expert group appraisal, preliminary notification (s.11), objections '
              'window and hearing (s.15, GP-46), declaration (s.19), award within twelve months or the proceedings '
              'lapse (s.25, a deemed outcome, GP-44), R&R, compensation one payment per awardee (GP-09), possession.',
              ['GP-46', 'GP-44', 'GP-03', 'GP-09', 'GP-12', 'GP-05'],
              connectors=[('gazette.publish', 'b', '', 'Gazette / newspaper notification'),
                          ('land.awardees', 'a', '', 'Awardees and amounts'), ('pfms.payment', 'c', 'G-PFMS', 'Compensation')])
    p = pk.proc('sangam-land-acquisition', 'Land acquisition', '', start={'materialFields': 'projectName,areaHectares'})
    s = p.root
    st = s.start('la_start', 'Requisition', form='land-acquisition/requisition')
    p.forms.append(dict(key='land-acquisition/requisition', name='Land acquisition requisition | भूमि अर्जन मांग', decision=None,
                        fields=['projectName*', 'areaHectares*', 'district*', 'documents'], doc=None))
    d = s.setv('la_defaults', 'Configuration', defaults='siaSeat=SIA-UNIT;collectorSeat=COLLECTOR;'
               'committeeSeats=EXPERT-1,EXPERT-2,EXPERT-3;quorumRule=MAJORITY;objectionWindow=P60D;awardWindow=P365D;'
               'objectionCount=0;caseType=LAND;amount=0;routingDecisionKey=land-authority;appealAllowed=false')
    sia_e, sia_d = s.sla_task('la_sia', 'Social impact assessment (s.4) | सामाजिक प्रभाव आकलन (धारा 4)', 'siaSeat',
                              form='land-acquisition/sia', decision=None, fields=['siaReport*', 'remarks'],
                              default_spec='P180D')
    ex = core(s, 'la_expert', 'Expert group appraisal (core committee)', 'committee')
    s.seq(st, d, sia_e)
    s.seq(sia_d, ex)
    eg = s.xgw('la_expertGw', 'Recommended?')
    s.flow(ex, eg)
    gov_ = core(s, 'la_decide', 'Appropriate government decides (core sanction)', 'sanction')
    s.flow(eg, gov_, 'committeeApproved', 'yes')
    drop = s.end('la_endDropped', 'Proposal dropped')
    s.flow(eg, drop, None, 'no', default=True)
    pn = s.svc('la_s11', 'Preliminary notification (s.11)', C['DOC'], {'template': 'preliminary-notification-s11'})
    gz = s.conn('la_gazette11', 'Publish (gazette, newspapers, website)', 'gazette.publish', 'publish', 'submit',
                'projectName,areaHectares', 'gazette11', step='s11')
    rej = rejection(p, s, 'la_rj')
    outcome(s, 'la_govGw', 'sanctionOutcome', {'SANCTIONED': pn}, rej)
    s.flow(gov_, 'la_govGw')
    ow = s.svc('la_objWindow', 'Objection window (s.15, 60 days)', C['SLA'],
               {'op': 'DEADLINE', 'clock': 'objection', 'spec': '${objectionWindow}'})
    w = s.egw('la_objWait', 'Objections')
    s.seq(pn, gz, ow, w)
    ob = s.catch_msg('la_objection', 'Objection received', 'sangam.objectionReceived')
    oc = s.expr('la_countObj', 'Count', "${execution.setVariable('objectionCount', objectionCount + 1)}")
    ol = s.catch_timer('la_objClose', 'Window closes', date='${objectionDueAt}')
    s.flow(w, ob)
    s.flow(w, ol)
    s.seq(ob, oc, w)
    hear = s.user('la_hearing', 'Hear objections (s.15) | आपत्तियों की सुनवाई (धारा 15)', '${collectorSeat}',
                  form='land-acquisition/hearing', decision=None, fields=['objectionsSummary*', 'documents'])
    dcl = core(s, 'la_declare', 'Declaration (s.19) (core sanction)', 'sanction')
    s.seq(ol, hear, dcl)
    dd = s.svc('la_s19', 'Declaration notified', C['DOC'], {'template': 'declaration-s19'})
    outcome(s, 'la_dclGw', 'sanctionOutcome', {'SANCTIONED': dd}, rej)
    s.flow(dcl, 'la_dclGw')
    awd = s.svc('la_awardClock', 'Award within 12 months (s.25)', C['SLA'],
                {'op': 'DEADLINE', 'clock': 'award', 'spec': '${awardWindow}'})
    at = s.user('la_award', 'Make award (s.23) | अधिनिर्णय (धारा 23)', '${collectorSeat}', form='land-acquisition/award',
                decision=None, fields=['awardAmount*', 'documents'], due='${awardDueAt}')
    s.seq(dd, awd, at)
    lapse = s.boundary('la_lapse', at, 'Proceedings lapse (s.25)', timer_date='${awardDueAt}')
    lr = s.setv('la_lapsed', 'Lapsed', values='rejectionReasonCodes=PROCEEDINGS_LAPSED_S25')
    s.seq(lapse, lr, s.end('la_endLapsed', 'Lapsed'))
    rr = s.user('la_rr', 'R&R scheme | पुनर्वास एवं पुनर्व्यवस्थापन योजना', '${collectorSeat}', form='land-acquisition/rr',
                decision=None, fields=['documents*', 'remarks'])
    ld = s.svc('la_awardees', 'Awardees', C['BATCH'], {'op': 'LOAD', 'connector': 'land.awardees', 'sampleSize': '3'},
               async_=True)
    s.seq(at, rr, ld)
    op, cg = batch_loop(p, s, 'la', 'sangam-land-compensation-payment', inherit='projectName')
    s.flow(ld, op)
    pos = s.user('la_possession', 'Take possession | कब्ज़ा लें', '${collectorSeat}', form='land-acquisition/possession',
                 decision=None, fields=['remarks*', 'documents'])
    s.flow(cg, pos, None, 'compensation paid', default=True)
    cl = core(s, 'la_close', 'Close (core)', 'closure')
    s.seq(pos, cl, end_ok(p, s, 'la_end'))
    c = pk.proc('sangam-land-compensation-payment', 'Compensation to one awardee', '',
                start={'materialFields': 'key,amount'})
    s = c.root
    a1 = s.start('lp_start', 'Awardee', initiator=None)
    d = s.setv('lp_defaults', 'Configuration', defaults='executionConnector=pfms.payment;executionStep=compensation;'
               'verifyApproval=false;reconcileRequired=true')
    ex = core(s, 'lp_pay', 'Compensation (core execution)', 'execution')
    s.seq(a1, d, ex)
    ok = end_ok(c, s, 'lp_endPaid', 'Paid')
    fl = s.end('lp_endFailed', 'Failed (deposit with authority)', listeners=[c.item_listener('FAILED',
                                                                                             reason_var='opReasonCode')])
    outcome(s, 'lp_gw', 'executionOutcome', {'DONE': ok}, fl)
    s.flow(ex, 'lp_gw')
    pk.dmn('land-authority', 'Land acquisition decisions', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')], [(['-'], [q('SECRETARY')], 'appropriate government')])
    simple_seeds(pk, 'DIST-LA', [('SIA-UNIT', 'SIA', 'COLLECTOR', 'Social impact assessment unit', 'सामाजिक प्रभाव आकलन इकाई'),
                                 ('EXPERT-1', 'EXPERT', 'COLLECTOR', 'Expert group member 1', 'विशेषज्ञ समूह सदस्य 1'),
                                 ('EXPERT-2', 'EXPERT', 'COLLECTOR', 'Expert group member 2', 'विशेषज्ञ समूह सदस्य 2'),
                                 ('EXPERT-3', 'EXPERT', 'COLLECTOR', 'Expert group member 3', 'विशेषज्ञ समूह सदस्य 3'),
                                 ('COLLECTOR', 'COLLECTOR', 'SECY-LA', 'Collector', 'कलेक्टर'),
                                 ('SECY-LA', 'SECRETARY', None, 'Secretary, Revenue', 'सचिव, राजस्व')], 'la')


# ------------------------------------------------------------------------------------------------ right to public services
def rtps():
    pk = Pack('gov.rtps', 'rtps', 'Right to public services (time-bound delivery)', 'लोक सेवा अधिकार (समयबद्ध प्रदाय)',
              'Right to public services', 'A notified service is decided by the designated officer within the '
              'notified time; on delay or refusal the first appellate authority decides; on unjustified delay the '
              'second appellate authority imposes a penalty on the designated officer and compensation to the '
              'applicant (state RTPS Acts, synthetic timelines).',
              ['GP-06', 'GP-22', 'GP-35', 'GP-44', 'GP-12'] + SIDE,
              connectors=[('hr.penaltyRecovery', 'a', '', 'Recovery of the penalty from salary'),
                          ('pfms.payment', 'c', 'G-PFMS', 'Compensation to the applicant')])
    p = pk.proc('sangam-rtps-service', 'Notified public service', '', start={'materialFields': 'serviceCode,applicantName'})
    s = p.root
    st = s.start('rs_start', 'Application', form='rtps/application')
    p.forms.append(dict(key='rtps/application', name='Public service application | लोक सेवा आवेदन', decision=None,
                        fields=['serviceCode*', 'applicantName*', 'applicantRef*', 'documents'], doc=None))
    d = s.setv('rs_defaults', 'Configuration', defaults='escalated=false;appealAllowed=true;appealSla=30D;'
               'secondAppellateSeat=SECOND-APPELLATE;outputTemplate=service-certificate')
    tl = s.rule('rs_timeline', 'Notified timeline (DMN)', 'rtps-timeline', binding='latest')
    ds = s.svc('rs_doSeat', 'Designated officer', C['SEAT'], {'op': 'ROLE', 'roleVar': 'designatedRole', 'targetVar': 'doSeat'})
    aps = s.svc('rs_appSeat', 'First appellate authority', C['SEAT'], {'op': 'ROLE', 'roleVar': 'appellateRole',
                                                                      'targetVar': 'appellateSeat'})
    clk = s.svc('rs_clock', 'Notified time', C['SLA'], {'op': 'DEADLINE', 'clock': 'case', 'spec': '${caseSla}'})
    de, dd = s.sla_task('rs_decide', 'Designated officer: decide | पदाभिहित अधिकारी: निर्णय', 'doSeat', form='rtps/decide',
                        decision=('doDecision', ['approve', 'reject']),
                        fields=['remarks', 'rejectionReasonCodes', 'documents'], spec='${caseSla}', on_breach='ESCALATE',
                        max_esc=1)
    s.seq(st, d, tl, ds, aps, clk, de)
    g = s.xgw('rs_gw', 'Decision?')
    s.flow(dd, g)
    cert = s.svc('rs_cert', 'Service certificate', C['DOC'], {'template': 'service-certificate'})
    s.flow(g, cert, "doDecision == 'approve'", 'approve')
    pg = s.xgw('rs_delayGw', 'Delayed?')
    s.flow(cert, pg)
    pen = s.user('rs_penalty', 'Impose penalty on designated officer | पदाभिहित अधिकारी पर शास्ति', '${secondAppellateSeat}',
                 form='rtps/penalty', decision=('penaltyDecision', ['approve', 'reject']),
                 fields=['officerPenalty', 'remarks*'])
    s.flow(pg, pen, 'escalated', 'delay (escalated)')
    cl = core(s, 'rs_close', 'Close (core)', 'closure')
    s.flow(pg, cl, None, 'in time', default=True)
    rec = s.conn('rs_recover', 'Recover penalty from salary', 'hr.penaltyRecovery', 'recover', 'submit',
                 'doSeat,officerPenalty', 'penalty', step='penalty')
    pgw = s.xgw('rs_penGw')
    s.flow(pen, pgw)
    s.flow(pgw, rec, "penaltyDecision == 'approve'", 'impose')
    s.flow(pgw, cl, None, 'no penalty', default=True)
    s.flow(rec, cl)
    s.flow(cl, end_ok(p, s, 'rs_end'))
    rej = rejection(p, s, 'rs_rj', reopen=de)
    s.flow(g, rej, None, 'reject', default=True)
    s.side_exits('rs')
    pk.dmn('rtps-timeline', 'Notified services and timelines (synthetic)', 'FIRST', [('Service', 'serviceCode', 'string')],
           [('caseSla', 'Time limit', 'string'), ('designatedRole', 'Designated officer', 'string'),
            ('appellateRole', 'First appellate authority', 'string')],
           [([q('INCOME-CERT')], [q('7WD'), q('TEHSILDAR'), q('SDM')], ''),
            ([q('CASTE-CERT')], [q('15WD'), q('TEHSILDAR'), q('SDM')], ''),
            ([q('BIRTH-CERT')], [q('7WD'), q('REGISTRAR'), q('SDM')], ''),
            ([q('WATER-CONNECTION')], [q('15WD'), q('JE'), q('EE')], ''),
            (['-'], [q('30D'), q('TEHSILDAR'), q('SDM')], '')])
    simple_seeds(pk, 'TEHSIL', [('TEHSILDAR-1', 'TEHSILDAR', 'SDM-1', 'Tehsildar', 'तहसीलदार'),
                                ('REGISTRAR-1', 'REGISTRAR', 'SDM-1', 'Registrar of births and deaths', 'जन्म-मृत्यु रजिस्ट्रार'),
                                ('JE-1', 'JE', 'EE-1', 'Junior Engineer', 'कनिष्ठ अभियंता'),
                                ('EE-1', 'EE', 'SECOND-APPELLATE', 'Executive Engineer', 'अधिशासी अभियंता'),
                                ('SDM-1', 'SDM', 'SECOND-APPELLATE', 'Sub-divisional magistrate', 'उपखंड मजिस्ट्रेट'),
                                ('SECOND-APPELLATE', 'COLLECTOR', None, 'Second appellate authority (Collector)',
                                 'द्वितीय अपीलीय प्राधिकारी (कलेक्टर)')], 'rs')


# ------------------------------------------------------------------------------------------------ records retention
def records():
    pk = Pack('gov.records', 'records', 'Records retention, review and transfer', 'अभिलेख प्रतिधारण, समीक्षा एवं अंतरण',
              'Records', 'Closed records are reviewed on the retention schedule (Public Records Rules 1997 r.9, CSMOP '
              '2022 A / C): retain, transfer to the National Archives (PDF/A) or destroy with a permanent Form 6 list '
              'after appraisal; a legal hold blocks destruction (GP-52, R61).',
              ['GP-52', 'GP-53', 'R61', 'GOV-11'],
              connectors=[('archive.nai', 'a', 'G-REC', 'Transfer to the National Archives'),
                          ('archive.destroyCopies', 'a', 'G-REC', 'Destruction of digital copies')])
    p = pk.proc('sangam-record-retention', 'Record retention', '', start={'materialFields': 'fileNo,retentionCategory'})
    s = p.root
    st = s.start('rr_start', 'Record closed', form='records/register')
    p.forms.append(dict(key='records/register', name='Register closed record | बंद अभिलेख पंजीकृत करें', decision=None,
                        fields=['fileNo*', 'subject*', 'retentionCategory*', 'retentionYears*', 'legalHold'], doc=None))
    d = s.setv('rr_defaults', 'Configuration', defaults='legalHold=false;recordOfficerSeat=RECORD-OFFICER;'
               'reReviewPeriod=P5Y;recordAgeYears=0')
    rv = s.svc('rr_schedule', 'Review date from the schedule', C['SLA'],
               {'op': 'DEADLINE', 'clock': 'review', 'spec': "${'P'.concat(retentionYears).concat('Y')}"})
    w = s.catch_timer('rr_review', 'Review due', date='${reviewDueAt}')
    t = s.user('rr_decide', 'Record review (Public Records Rules r.9) | अभिलेख समीक्षा (लोक अभिलेख नियम 9)',
               '${recordOfficerSeat}', form='records/review', decision=('reviewDecision', ['retain', 'transferNai', 'destroy']),
               fields=['remarks*'])
    s.seq(st, d, rv, w, t)
    g = s.xgw('rr_gw', 'Decision?')
    s.flow(t, g)
    rr2 = s.svc('rr_again', 'Next review', C['SLA'], {'op': 'DEADLINE', 'clock': 'review', 'spec': '${reReviewPeriod}'})
    s.flow(g, rr2, "reviewDecision == 'retain'", 'retain')
    s.flow(rr2, w)
    nai = s.conn('rr_nai', 'Transfer to National Archives (PDF/A)', 'archive.nai', 'transfer', 'submit',
                 'fileNo,subject,retentionCategory', 'nai', step='nai')
    s.flow(g, nai, "reviewDecision == 'transferNai'", 'transfer')
    s.flow(nai, s.end('rr_endTransferred', 'Transferred'))
    hg = s.xgw('rr_holdGw', 'Legal hold?')
    s.flow(g, hg, None, 'destroy', default=True)
    blocked = s.notify('rr_blocked', 'Destruction blocked by legal hold', 'destruction-blocked', 'inapp', 'recordOfficerSeat')
    s.flow(hg, blocked, 'legalHold', 'held')
    s.flow(blocked, rr2)
    chk = s.rule('rr_appraisal', 'Appraisal needed? (DMN)', 'records-destruction-check')
    s.flow(hg, chk, None, 'no hold', default=True)
    ag = s.xgw('rr_apprGw', 'Appraisal?')
    s.flow(chk, ag)
    ap = s.user('rr_appraise', 'Appraisal before destruction | नष्ट करने से पूर्व मूल्यांकन', 'NAI-APPRAISER',
                form='records/appraisal', decision=('appraisalDecision', ['destroy', 'transferNai']), fields=['remarks*'])
    f6 = s.svc('rr_form6', 'Form 6 destruction list (kept permanently)', C['DOC'], {'template': 'form-6-destruction-list'})
    s.flow(ag, ap, 'appraisalRequired', 'older than 25 years / category A')
    s.flow(ag, f6, None, 'no', default=True)
    apg = s.xgw('rr_apprDecGw')
    s.flow(ap, apg)
    s.flow(apg, nai, "appraisalDecision == 'transferNai'", 'archival value')
    s.flow(apg, f6, None, 'destroy', default=True)
    dc = s.conn('rr_destroy', 'Destroy digital copies', 'archive.destroyCopies', 'destroy', 'submit', 'fileNo', 'destroy',
                step='destroy')
    s.seq(f6, dc, s.end('rr_endDestroyed', 'Destroyed'))
    h = s.evsub('rr_hold', 'Legal hold set')
    h1 = h.start('rr_holdStart', 'Hold', msg='sangam.legalHoldSet', interrupting=False)
    h.seq(h1, h.svc('rr_holdSet', 'Set hold', C['CASE'], {'op': 'LEGAL_HOLD'}), h.end('rr_holdEnd', 'Held'))
    r = s.evsub('rr_release', 'Legal hold released')
    r1 = r.start('rr_relStart', 'Release', msg='sangam.legalHoldReleased', interrupting=False)
    r.seq(r1, r.svc('rr_relSet', 'Release hold', C['CASE'], {'op': 'RELEASE_HOLD'}), r.end('rr_relEnd', 'Released'))
    pk.dmn('records-destruction-check', 'Appraisal before destruction (Public Records Rules r.9)', 'FIRST',
           [('Category', 'retentionCategory', 'string'), ('Age (years)', 'recordAgeYears', 'integer')],
           [('appraisalRequired', 'Appraisal', 'boolean')],
           [([q('A'), '-'], ['true'], 'category A'), (['-', '>= 25'], ['true'], 'older than 25 years'),
            (['-', '-'], ['false'], '')])
    simple_seeds(pk, 'RECORDS', [('RECORD-OFFICER', 'RECORDOFFICER', None, 'Departmental record officer', 'विभागीय अभिलेख अधिकारी'),
                                 ('NAI-APPRAISER', 'APPRAISER', None, 'Appraisal (National Archives)', 'मूल्यांकन (राष्ट्रीय अभिलेखागार)')],
                 'rec')


def build():
    recruitment()
    rti()
    grievance()
    leave()
    inspection()
    stores()
    assets()
    court()
    audit_para()
    land()
    rtps()
    records()
