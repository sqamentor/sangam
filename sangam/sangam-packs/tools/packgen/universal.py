# SPDX-License-Identifier: Apache-2.0
"""Universal (non-government) packs for UP-01..UP-22 (PREMIUM-AND-UNIVERSAL-SCOPE.md section 3.1)."""
from lib import C, q
from packs import Pack
from common import core, outcome, end_ok, rejection, batch_loop, broadcast
from gov2 import simple_seeds


def upack(n, slug, en, hi, desc, covers, connectors=(), depends=('sangam.core',)):
    return Pack(f'universal.up{n:02d}-{slug}', f'up{n:02d}-{slug}', f'UP-{n:02d} {en}', f'यूपी-{n:02d} {hi}', 'Universal',
                desc, [f'UP-{n:02d}'] + list(covers), connectors=list(connectors), depends=list(depends))


def up01():
    pk = upack(1, 'stp-order', 'Straight-through order processing', 'सीधा आदेश प्रसंस्करण',
               'No human step: validate, route by rule, reserve, charge, ship. Short history TTL (30 days) for '
               'high-throughput definitions (R22, R29); every effect is idempotent by operationId; no hot root.',
               ['R22', 'R29', 'R40', 'GP-08'],
               [('order.validate', 'a', '', 'Validation'), ('inventory.reserve', 'a', '', 'Reservation'),
                ('saga.charge', 'c', '', 'Payment capture'), ('shipping.create', 'a', '', 'Shipment')], depends=[])
    p = pk.proc('sangam-up01-stp-order', 'UP-01 Straight-through order', '', ttl=30,
                start={'materialFields': 'orderId,customerId,amount'})
    s = p.root
    st = s.start('u1_start', 'Order received', msg='sangam.order.received', initiator=None)
    v = s.conn('u1_validate', 'Validate', 'order.validate', 'validate', 'query', 'orderId,customerId,amount', 'validate',
               async_=False)
    r = s.rule('u1_route', 'Fulfilment route (DMN)', 'up01-route')
    res = s.conn('u1_reserve', 'Reserve stock', 'inventory.reserve', 'reserve', 'submit', 'orderId', 'reserve',
                 step='reserve', error='STP_FAILED', async_=False)
    ch = s.conn('u1_charge', 'Capture payment', 'saga.charge', 'capture', 'submit', 'orderId,amount', 'charge',
                step='charge', error='STP_FAILED', async_=False)
    sh = s.conn('u1_ship', 'Create shipment', 'shipping.create', 'create', 'submit', 'orderId,fulfilmentChannel', 'ship',
                step='ship', error='STP_FAILED', async_=False)
    e = s.end('u1_end', 'Fulfilled')
    s.seq(st, v, r, res, ch, sh, e)
    for host in (res, ch, sh):
        b = s.boundary(host + '_err', host, 'Failed', error='STP_FAILED')
        s.flow(b, s.end(host + '_failEnd', 'Failed (exception queue)'))
    pk.dmn('up01-route', 'Fulfilment route', 'FIRST', [('Amount', 'amount', 'double')],
           [('fulfilmentChannel', 'Channel', 'string')],
           [(['> 50000'], [q('INSURED_COURIER')], ''), (['-'], [q('STANDARD')], '')])


def up02():
    pk = upack(2, 'saga', 'Saga across services', 'सेवाओं के पार सागा',
               'Forward steps with compensations, per-step timeouts and unknown outcomes: reserve stock, charge (core '
               'execution: UNKNOWN_OUTCOME and reconciliation for class c), book shipment; any failure or timeout '
               'compensates the completed steps in reverse.', ['R36', 'R37', 'R23', 'GP-11', 'GP-12', 'GP-13'],
               [('inventory.reserve', 'a', '', 'Reserve'), ('inventory.release', 'a', '', 'Release (compensation)'),
                ('saga.charge', 'c', '', 'Charge'), ('payment.refund', 'a', '', 'Refund (compensation)'),
                ('shipping.create', 'a', '', 'Book'), ('shipping.cancel', 'a', '', 'Cancel (compensation)')])
    p = pk.proc('sangam-up02-saga', 'UP-02 Order saga', '', start={'materialFields': 'orderId,amount'})
    s = p.root
    st = s.start('u2_start', 'Order', form='up02-saga/order')
    p.forms.append(dict(key='up02-saga/order', name='Order | आदेश', decision=None, fields=['orderId*', 'customerId*', 'amount*'],
                        doc=None))
    d = s.setv('u2_defaults', 'Configuration', defaults='executionConnector=saga.charge;executionStep=charge;'
               'verifyApproval=false;reconcileRequired=false;stepTimeout=PT10M')
    r = s.conn('u2_reserve', 'Reserve stock', 'inventory.reserve', 'reserve', 'submit', 'orderId', 'reserve',
               step='reserve', error='SAGA_STEP_FAILED')
    ch = core(s, 'u2_charge', 'Charge (core execution)', 'execution')
    cg = s.xgw('u2_chargeGw', 'Charged?')
    b = s.conn('u2_book', 'Book shipment', 'shipping.create', 'book', 'submit', 'orderId', 'ship', step='ship',
               error='SAGA_STEP_FAILED')
    e = end_ok(p, s, 'u2_end', 'Completed')
    s.seq(st, d, r, ch, cg)
    comp = s.throw('u2_compensate', 'Compensate completed steps', compensate=True)
    fe = s.end('u2_endCompensated', 'Compensated')
    s.flow(cg, b, "executionOutcome == 'DONE'", 'yes')
    s.flow(cg, comp, None, 'failed', default=True)
    s.seq(b, e)
    s.flow(comp, fe)
    for host, conn_, op_ in ((r, 'inventory.release', 'release'), (ch, 'payment.refund', 'refund'),
                             (b, 'shipping.cancel', 'cancel')):
        cb = s.boundary(host + '_comp', host, 'Compensate', compensate=True)
        h = s.conn(host + '_undo', 'Undo: ' + op_, conn_, op_, 'submit', 'orderId,amount', host + 'Undo',
                   step=op_ + '-compensation')
        s.node(h)['attrs']['isForCompensation'] = 'true'
        s.node(h)['comp_for'] = host
        s.assoc(cb, h)
    for host in (r, b):
        eb = s.boundary(host + '_fail', host, 'Step failed', error='SAGA_STEP_FAILED')
        s.flow(eb, comp)
    tb = s.boundary('u2_chargeTimeout', ch, 'Step timeout', timer_duration='${stepTimeout}')
    s.flow(tb, comp)


def up03():
    pk = upack(3, 'event-correlation', 'Windowed event correlation', 'समय-खिड़की घटना सहसंबंध',
               'Many events per key within a time window: de-duplicated, late events dropped or accepted, bounded '
               'state, threshold evaluation, out-of-order sorted; the window closes on a timer (R83, R38).',
               ['R38', 'R83', 'R40', 'GP-15'])
    p = pk.proc('sangam-up03-event-correlation', 'UP-03 Event correlation window', '', ttl=90,
                start={'materialFields': 'eventType'})
    s = p.root
    st = s.start('u3_start', 'First event', msg='sangam.event.first', initiator=None)
    d = s.setv('u3_defaults', 'Configuration', defaults='windowSize=PT15M;threshold=3;lateEventPolicy=DROP')
    win = s.svc('u3_window', 'Open window', C['SLA'], {'op': 'DEADLINE', 'clock': 'window', 'spec': '${windowSize}'})
    add = s.svc('u3_add', 'Add event (dedupe, bounded)', C['EVWIN'], {'op': 'ADD'})
    w = s.egw('u3_wait', 'Next event or window end')
    s.seq(st, d, win, add, w)
    nx = s.catch_msg('u3_next', 'Next event (same key)', 'sangam.event.next')
    add2 = s.svc('u3_add2', 'Add event', C['EVWIN'], {'op': 'ADD'})
    ev = s.svc('u3_eval', 'Evaluate threshold', C['EVWIN'], {'op': 'EVALUATE'})
    g = s.xgw('u3_gw', 'Threshold?')
    s.flow(w, nx)
    s.seq(nx, add2, ev, g)
    s.flow(g, w, '!thresholdReached', 'not yet')
    rv = s.user('u3_review', 'Review correlated signal | सहसंबद्ध संकेत की समीक्षा', 'ANALYST', form='up03-event-correlation/review',
                decision=('signalDecision', ['proceed', 'drop']), fields=['remarks'])
    s.flow(g, rv, None, 'reached', default=True)
    s.flow(rv, s.end('u3_endReviewed', 'Reviewed'))
    t = s.catch_timer('u3_close', 'Window ends', date='${windowDueAt}')
    s.flow(w, t)
    ev2 = s.svc('u3_eval2', 'Final evaluation', C['EVWIN'], {'op': 'EVALUATE'})
    g2 = s.xgw('u3_gw2', 'Threshold?')
    s.seq(t, ev2, g2)
    s.flow(g2, rv, 'thresholdReached', 'reached')
    s.flow(g2, s.end('u3_endQuiet', 'Window closed below threshold'), None, 'no', default=True)
    simple_seeds(pk, 'OPS-U3', [('ANALYST', 'ANALYST', None, 'Fraud / signal analyst', 'धोखाधड़ी / संकेत विश्लेषक')], 'u3')


def up04():
    pk = upack(4, 'skills-routing', 'SLA-based and skills-based routing', 'एसएलए एवं कौशल आधारित आवंटन',
               'Priority sets the SLA and the pool by rule; the case goes to the first pool member whose skills cover '
               'the request (R82); breach escalates (R79).', ['R79', 'R82', 'GP-06', 'GP-17'])
    p = pk.proc('sangam-up04-skills-routing', 'UP-04 Skills-based request', '', start={'materialFields': 'subject,priority'})
    s = p.root
    st = s.start('u4_start', 'Request', form='up04-skills-routing/request')
    p.forms.append(dict(key='up04-skills-routing/request', name='Service request | सेवा अनुरोध', decision=None,
                        fields=['subject*', 'description', 'priority*', 'skillsRequired'], doc=None))
    d = s.setv('u4_defaults', 'Configuration', defaults='requiredSkills=@skillsRequired;skills_TEAM-A=billing,hindi;'
               'skills_TEAM-B=network;skills_TEAM-C=billing,network,english')
    r = s.rule('u4_sla', 'SLA and pool by priority (DMN)', 'up04-sla')
    a = s.svc('u4_assign', 'Skills match (R82)', C['ASSIGN'], {'strategy': 'SKILLS', 'candidatesVar': 'pool',
                                                               'resultVar': 'assignedGroup'})
    te, td = s.sla_task('u4_resolve', 'Resolve request | अनुरोध का समाधान', 'assignedGroup', form='up04-skills-routing/resolve',
                        decision=('resolveDecision', ['resolve', 'return']), fields=['resolutionNotes*'],
                        spec='${slaSpec}', on_breach='ESCALATE')
    s.seq(st, d, r, a, te)
    s.flow(td, end_ok(p, s, 'u4_end', 'Resolved'))
    pk.dmn('up04-sla', 'SLA and pool by priority', 'FIRST', [('Priority', 'priority', 'string')],
           [('slaSpec', 'SLA', 'string'), ('pool', 'Pool', 'string')],
           [([q('P1')], [q('4WH'), q('TEAM-C,TEAM-A')], ''), ([q('P2')], [q('2WD'), q('TEAM-A,TEAM-B,TEAM-C')], ''),
            (['-'], [q('5WD'), q('TEAM-A,TEAM-B,TEAM-C')], '')])
    simple_seeds(pk, 'SUPPORT', [('TEAM-A', 'AGENT', 'TEAM-LEAD', 'Team A', 'टीम ए'), ('TEAM-B', 'AGENT', 'TEAM-LEAD', 'Team B', 'टीम बी'),
                                 ('TEAM-C', 'AGENT', 'TEAM-LEAD', 'Team C (senior)', 'टीम सी (वरिष्ठ)'),
                                 ('TEAM-LEAD', 'LEAD', None, 'Team lead', 'टीम प्रमुख')], 'u4')


def up05():
    pk = upack(5, 'four-eyes', 'Four-eyes / dual control', 'चार-आँख / दोहरा नियंत्रण',
               'Maker submits a sensitive change (limit, master data, payment); a checker of sufficient authority approves; '
               'maker and checker differ by person and seat (no self-approval through delegation), the approval is '
               'bound to the payload hash, and the change is applied with an operationId.',
               ['R19', 'R43', 'VI.4', 'GP-01', 'GP-34', 'K4', 'K5'],
               [('masterdata.apply', 'a', '', 'Apply the change')])
    p = pk.proc('sangam-up05-four-eyes', 'UP-05 Dual-control change', '',
                start={'materialFields': 'subject,amount,description'})
    s = p.root
    st = s.start('u5_start', 'Change proposed (maker)', form='up05-four-eyes/change')
    p.forms.append(dict(key='up05-four-eyes/change', name='Sensitive change | संवेदनशील परिवर्तन', decision=None,
                        fields=['subject*', 'amount*', 'description*'], doc=None))
    d = s.setv('u5_defaults', 'Configuration', defaults='caseType=DUAL_CONTROL;routingDecisionKey=up05-checker;'
               'executionConnector=masterdata.apply;executionStep=apply;reconcileRequired=false;issueOutput=false;'
               'appealAllowed=false')
    san = core(s, 'u5_check', 'Checker approval (core sanction, SoD, binding)', 'sanction')
    ex = core(s, 'u5_apply', 'Apply (core execution)', 'execution')
    s.seq(st, d, san)
    rej = rejection(p, s, 'u5_rj')
    outcome(s, 'u5_gw', 'sanctionOutcome', {'SANCTIONED': ex}, rej)
    s.flow(san, 'u5_gw')
    inv = s.boundary('u5_inv', ex, 'Changed after approval', error='APPROVAL_INVALIDATED')
    s.flow(inv, san)
    cl = core(s, 'u5_close', 'Close (core)', 'closure')
    s.seq(ex, cl, end_ok(p, s, 'u5_end', 'Applied'))
    pk.dmn('up05-checker', 'Checker by amount', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')],
           [(['<= 1000000'], [q('DS')], 'checker'), (['-'], [q('JS')], 'senior checker')])


def up06():
    pk = upack(6, 'hitl-ai-review', 'Human-in-the-loop AI review', 'मानव-समीक्षित एआई',
               'An IDP / agent worker proposes extracted fields with confidence; below the threshold, or for any write '
               'action, a human approves, edits or rejects; the reviewed result is fed back as evaluation data; the '
               'applied payload is bound to the reviewed version (R72, R75).', ['R14', 'R72', 'R75', 'GP-34'],
               [('ai.extract', 'a', '', 'Extraction (external task sangam.ai.extract)'),
                ('ai.feedback', 'a', '', 'Evaluation feedback'), ('records.apply', 'a', '', 'Apply reviewed data')])
    p = pk.proc('sangam-up06-hitl-ai', 'UP-06 AI proposal with human review', '',
                start={'materialFields': 'extractedFields,correctedFields'})
    s = p.root
    st = s.start('u6_start', 'Document received', form='up06-hitl-ai-review/intake')
    p.forms.append(dict(key='up06-hitl-ai-review/intake', name='Document intake | दस्तावेज़ प्राप्ति', decision=None,
                        fields=['documents*', 'description'], doc=None))
    d = s.setv('u6_defaults', 'Configuration', defaults='confidenceThreshold=0.9;writeAction=true;w_confidence=0;'
               'extractedFields=;correctedFields=;reviewerSeat=REVIEWER-AI')
    x = s.ext('u6_extract', 'Extract with confidence (IDP / agent)', 'sangam.ai.extract')
    g = s.xgw('u6_gw', 'Human review needed?')
    s.seq(st, d, x, g)
    auto = s.svc('u6_auto', 'Bind the agent output', C['CASE'], {'op': 'BIND_DEEMED'})
    s.flow(g, auto, '!writeAction && w_confidence >= confidenceThreshold', 'high confidence, read-only')
    rv = s.user('u6_review', 'Review extracted data | निकाले गए डेटा की समीक्षा', '${reviewerSeat}',
                form='up06-hitl-ai-review/review', decision=('reviewDecision', ['approve', 'edit', 'reject']),
                fields=['confidence', 'extractedFields', 'correctedFields', 'remarks'], bind='approve,edit')
    s.flow(g, rv, None, 'review (R72 write actions always)', default=True)
    fb = s.conn('u6_feedback', 'Feed back as evaluation data', 'ai.feedback', 'feedback', 'submit',
                'extractedFields,correctedFields,reviewDecision', 'feedback', step='feedback')
    s.flow(rv, fb)
    rg = s.xgw('u6_rg', 'Approved?')
    s.flow(fb, rg)
    ap = s.conn('u6_apply', 'Apply reviewed data', 'records.apply', 'apply', 'submit', 'extractedFields,correctedFields',
                'apply', step='apply')
    s.flow(rg, ap, "reviewDecision != 'reject'", 'approve / edit')
    s.flow(auto, ap)
    s.flow(rg, s.end('u6_endRejected', 'Rejected'), None, 'reject', default=True)
    s.flow(ap, end_ok(p, s, 'u6_end', 'Applied'))
    simple_seeds(pk, 'OPS-U6', [('REVIEWER-AI', 'REVIEWER', None, 'Human reviewer', 'मानव समीक्षक')], 'u6')


def up07():
    pk = upack(7, 'improvement-loop', 'Process-mining improvement loop', 'प्रक्रिया-खनन सुधार चक्र',
               'Quarterly: discover variants and bottlenecks from history, decide an improvement, approve and deploy a '
               'new package version, measure for a period, compare versions, keep or roll back (R65, R66, R43).',
               ['R43', 'R65', 'R66', 'GP-36'],
               [('analytics.discover', 'a', '', 'Discovery / conformance'), ('analytics.compare', 'a', '', 'Version comparison'),
                ('deploy.package', 'a', '', 'Publish new package version'), ('deploy.rollback', 'a', '', 'Roll back')])
    p = pk.proc('sangam-up07-improvement-loop', 'UP-07 Improvement loop', '', start={'materialFields': 'improvementProposal'})
    s = p.root
    st = s.start('u7_start', 'Quarterly', timer_cycle='0 0 5 1 1/3 ?', initiator=None)
    d = s.setv('u7_defaults', 'Configuration', defaults='caseType=CHANGE;amount=0;routingDecisionKey=up07-approver;'
               'measurePeriod=P30D;ownerSeat=PROCESS-OWNER;improvementProposal=;appealAllowed=false')
    disc = s.conn('u7_discover', 'Discover variants and bottlenecks', 'analytics.discover', 'discover', 'query', None,
                  'discovery')
    rv = s.user('u7_review', 'Review variants and bottlenecks | प्रकारों एवं अवरोधों की समीक्षा', '${ownerSeat}',
                form='up07-improvement-loop/review', decision=('improveDecision', ['proceed', 'drop']),
                fields=['improvementProposal*', 'documents'])
    s.seq(st, d, disc, rv)
    g = s.xgw('u7_gw', 'Improve?')
    s.flow(rv, g)
    san = core(s, 'u7_approve', 'Approve change (core sanction)', 'sanction')
    s.flow(g, san, "improveDecision == 'proceed'", 'proceed')
    s.flow(g, s.end('u7_endNoChange', 'No change'), None, 'drop', default=True)
    dep = s.conn('u7_deploy', 'Deploy new version', 'deploy.package', 'publish', 'submit', 'improvementProposal', 'deploy',
                 step='deploy')
    rej = rejection(p, s, 'u7_rj')
    outcome(s, 'u7_sanGw', 'sanctionOutcome', {'SANCTIONED': dep}, rej)
    s.flow(san, 'u7_sanGw')
    m = s.catch_timer('u7_measure', 'Measure', duration='${measurePeriod}')
    cmp_ = s.conn('u7_compare', 'Compare versions', 'analytics.compare', 'compare', 'query', None, 'compare')
    dec = s.user('u7_decide', 'Compare before and after | पहले और बाद की तुलना', '${ownerSeat}',
                 form='up07-improvement-loop/compare', decision=('keepDecision', ['accepted', 'rollback']),
                 fields=['experimentResult*'])
    s.seq(dep, m, cmp_, dec)
    kg = s.xgw('u7_keepGw')
    s.flow(dec, kg)
    rb = s.conn('u7_rollback', 'Roll back version', 'deploy.rollback', 'rollback', 'submit', None, 'rollback', step='rollback')
    s.flow(kg, rb, "keepDecision == 'rollback'", 'roll back')
    s.flow(rb, s.end('u7_endRolledBack', 'Rolled back'))
    s.flow(kg, end_ok(p, s, 'u7_end', 'Kept'), None, 'keep', default=True)
    pk.dmn('up07-approver', 'Change approver', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')], [(['-'], [q('PROCESSBOARD')], '')])
    simple_seeds(pk, 'PMO', [('PROCESS-OWNER', 'OWNER', 'PROCESS-BOARD', 'Process owner', 'प्रक्रिया स्वामी'),
                             ('PROCESS-BOARD', 'PROCESSBOARD', None, 'Process board', 'प्रक्रिया बोर्ड')], 'u7')


def up08():
    pk = upack(8, 'entity-lifecycle', 'Long-lived entity lifecycle', 'दीर्घकालिक इकाई जीवनचक्र',
               'A policy / subscription / contract lives for years: endorsements arrive as messages, an anniversary '
               'timer drives renewal, and after N cycles the case continues as a new linked instance (continue-as-new) '
               'so history stays bounded (R49, R38, GP-10).', ['R38', 'R49', 'GP-10'])
    p = pk.proc('sangam-up08-entity-lifecycle', 'UP-08 Entity lifecycle', '', start={'materialFields': 'policyNo,customerId'})
    s = p.root
    st = s.start('u8_start', 'Entity created', form='up08-entity-lifecycle/create')
    p.forms.append(dict(key='up08-entity-lifecycle/create', name='Create policy / subscription | पॉलिसी / सदस्यता बनाएँ',
                        decision=None, fields=['policyNo*', 'customerId*'], doc=None))
    d = s.setv('u8_defaults', 'Configuration', defaults='renewalCycle=P365D;cycles=0;continueAsNewAfter=5;'
               'serviceSeat=SERVICE-DESK')
    w = s.egw('u8_wait', 'Event or anniversary')
    s.seq(st, d, w)
    en = s.catch_msg('u8_endorse', 'Endorsement requested', 'sangam.entity.endorsement')
    an = s.catch_timer('u8_anniversary', 'Anniversary', duration='${renewalCycle}')
    cx = s.catch_msg('u8_cancel', 'Cancelled', 'sangam.entity.cancel')
    for x in (en, an, cx):
        s.flow(w, x)
    et = s.user('u8_endorsement', 'Process endorsement | पृष्ठांकन संसाधित करें', '${serviceSeat}',
                form='up08-entity-lifecycle/endorsement', decision=None, fields=['endorsement*'])
    s.seq(en, et, w)
    rn = s.user('u8_renew', 'Renew or terminate | नवीनीकरण या समाप्ति', '${serviceSeat}', form='up08-entity-lifecycle/renew',
                decision=('renewalDecision', ['renew', 'terminate']), fields=['remarks'])
    s.flow(an, rn)
    rg = s.xgw('u8_rg')
    s.flow(rn, rg)
    inc = s.expr('u8_cycle', 'Count cycle', "${execution.setVariable('cycles', cycles + 1)}")
    s.flow(rg, inc, "renewalDecision == 'renew'", 'renew')
    s.flow(rg, s.end('u8_endTerminated', 'Terminated'), None, 'terminate', default=True)
    cg = s.xgw('u8_can', 'Continue as new?')
    s.flow(inc, cg)
    s.flow(cg, w, 'cycles < continueAsNewAfter', 'same instance')
    cn = s.svc('u8_continue', 'Continue as a new linked instance', C['LINK'],
               {'processKey': 'sangam-up08-entity-lifecycle', 'linkType': 'continuation',
                'inherit': 'policyNo,customerId,renewalCycle,continueAsNewAfter'})
    s.flow(cg, cn, None, 'bounded history', default=True)
    s.flow(cn, s.end('u8_endContinued', 'Continued'))
    s.flow(cx, s.end('u8_endCancelled', 'Cancelled'))
    simple_seeds(pk, 'SVC', [('SERVICE-DESK', 'SERVICE', None, 'Service desk', 'सेवा डेस्क')], 'u8')


UP09_CASE = '''<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/CMMN/20151109/MODEL"
             xmlns:operaton="http://operaton.org/schema/1.0/cmmn"
             id="defs_up09_claim_case" targetNamespace="http://sangam.org/packs">
  <!-- SPDX-License-Identifier: Apache-2.0 -->
  <!-- UP-09 dynamic case with ad-hoc work and external experts (R45, R76): triage at once; investigation steps and an
       external expert report (group EXTERNAL-EXPERT, external principals with scoped access) are discretionary and
       repeatable; the decision needs triage; the milestone closes the case. -->
  <case id="sangam-up09-claim-investigation" name="UP-09 Claim investigation (case)" operaton:historyTimeToLive="3650">
    <casePlanModel id="u9_plan" name="Claim investigation" autoComplete="true">
      <planItem id="u9_pi_triage" definitionRef="u9_triage"/>
      <planItem id="u9_pi_investigate" definitionRef="u9_investigate">
        <itemControl id="u9_ic1">
          <repetitionRule id="u9_rep1"><condition id="u9_c1">${true}</condition></repetitionRule>
          <manualActivationRule id="u9_mar1"><condition id="u9_c2">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="u9_pi_expert" definitionRef="u9_expert">
        <itemControl id="u9_ic2">
          <repetitionRule id="u9_rep2"><condition id="u9_c3">${true}</condition></repetitionRule>
          <manualActivationRule id="u9_mar2"><condition id="u9_c4">${true}</condition></manualActivationRule>
        </itemControl>
      </planItem>
      <planItem id="u9_pi_decide" definitionRef="u9_decide">
        <itemControl id="u9_ic3"><requiredRule id="u9_req"><condition id="u9_c5">${true}</condition></requiredRule></itemControl>
        <entryCriterion id="u9_ec1" sentryRef="u9_s_triaged"/>
      </planItem>
      <planItem id="u9_pi_closed" definitionRef="u9_closedMs">
        <entryCriterion id="u9_ec2" sentryRef="u9_s_decided"/>
      </planItem>
      <sentry id="u9_s_triaged" name="Triaged">
        <planItemOnPart id="u9_op1" sourceRef="u9_pi_triage"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <sentry id="u9_s_decided" name="Decided">
        <planItemOnPart id="u9_op2" sourceRef="u9_pi_decide"><standardEvent>complete</standardEvent></planItemOnPart>
      </sentry>
      <humanTask id="u9_triage" name="Triage claim" operaton:candidateGroups="CLAIMS-HANDLER"/>
      <humanTask id="u9_investigate" name="Investigation step" operaton:candidateGroups="INVESTIGATOR"/>
      <humanTask id="u9_expert" name="External expert report" operaton:candidateGroups="EXTERNAL-EXPERT"/>
      <humanTask id="u9_decide" name="Decide claim" operaton:candidateGroups="CLAIMS-MANAGER"/>
      <milestone id="u9_closedMs" name="Claim decided"/>
    </casePlanModel>
  </case>
</definitions>
'''


def up09():
    pk = upack(9, 'dynamic-case', 'Dynamic case with ad-hoc work and external experts', 'तदर्थ कार्य एवं बाह्य विशेषज्ञों सहित गतिशील प्रकरण',
               'Claim investigation as a CMMN case: discretionary, repeatable investigation steps and an external expert '
               'report; decision after triage (R45, R76).', ['R45', 'R76', 'GP-21'], depends=[])
    pk.cmmn('sangam-up09-claim-investigation', UP09_CASE)
    pk.main = ['sangam-up09-claim-investigation']
    simple_seeds(pk, 'CLAIMS', [('CLAIMS-HANDLER', 'HANDLER', 'CLAIMS-MANAGER', 'Claims handler', 'दावा संचालक'),
                                ('INVESTIGATOR', 'INVESTIGATOR', 'CLAIMS-MANAGER', 'Investigator', 'अन्वेषक'),
                                ('EXTERNAL-EXPERT', 'EXTERNAL', None, 'External expert (portal)', 'बाह्य विशेषज्ञ (पोर्टल)'),
                                ('CLAIMS-MANAGER', 'MANAGER', None, 'Claims manager', 'दावा प्रबंधक')], 'u9')


def up10():
    pk = upack(10, 'incident-swarming', 'Tiered escalation and swarming', 'स्तरीय वृद्धि एवं सामूहिक समाधान',
               'Priority sets the resolver group and SLA; breaches escalate in tiers; a major incident opens a bridge with '
               'parallel resolver groups (swarming); priority can change in flight (R79, R82).', ['R79', 'R82', 'GP-06'])
    p = pk.proc('sangam-up10-incident', 'UP-10 Incident', '', start={'materialFields': 'incidentSummary,priority'})
    s = p.root
    st = s.start('u10_start', 'Incident raised', form='up10-incident-swarming/raise')
    p.forms.append(dict(key='up10-incident-swarming/raise', name='Raise incident | घटना दर्ज करें', decision=None,
                        fields=['incidentSummary*', 'priority*', 'description'], doc=None))
    r = s.rule('u10_prio', 'Resolver group and SLA by priority (DMN)', 'up10-priority', binding='latest')
    g = s.xgw('u10_majorGw', 'Major incident?')
    s.seq(st, r, g)
    re_, rd = s.sla_task('u10_resolve', 'Resolve incident | घटना का समाधान', 'resolverGroup', form='up10-incident-swarming/resolve',
                         decision=('incidentDecision', ['fixed', 'swarm']), fields=['resolutionNotes'], spec='${slaSpec}',
                         on_breach='ESCALATE', max_esc=3)
    s.flow(g, re_, None, 'no', default=True)
    fork = s.pgw('u10_swarm', 'Swarm')
    s.flow(g, fork, 'majorIncident', 'major')
    dg = s.xgw('u10_decGw')
    s.flow(rd, dg)
    s.flow(dg, fork, "incidentDecision == 'swarm'", 'swarm')
    join = s.pgw('u10_join', 'All groups done')
    for grp, en, hi in (('NET-OPS', 'Network investigation', 'नेटवर्क जाँच'), ('APP-OPS', 'Application investigation', 'अनुप्रयोग जाँच'),
                        ('DB-OPS', 'Database investigation', 'डेटाबेस जाँच'),
                        ('INCIDENT-MGR', 'Major incident bridge', 'प्रमुख घटना ब्रिज')):
        t = s.user('u10_' + grp.lower().replace('-', ''), f'{en} | {hi}', grp, form='up10-incident-swarming/swarm-' + grp.lower(),
                   decision=None, fields=['resolutionNotes'])
        s.flow(fork, t)
        s.flow(t, join)
    conf = s.user('u10_confirm', 'Confirm resolution | समाधान की पुष्टि', '${initiatorSeat}', form='up10-incident-swarming/confirm',
                  decision=('confirmDecision', ['close', 'reopen']), fields=['feedback'])
    s.flow(join, conf)
    s.flow(dg, conf, None, 'fixed', default=True)
    cg = s.xgw('u10_confGw')
    s.flow(conf, cg)
    s.flow(cg, re_, "confirmDecision == 'reopen'", 'reopen')
    s.flow(cg, end_ok(p, s, 'u10_end', 'Closed'), None, 'close', default=True)
    rp = s.evsub('u10_reprioritise', 'Re-prioritise in flight')
    a1 = rp.start('u10_rpStart', 'Priority changed', msg='sangam.incident.reprioritise', interrupting=False)
    a2 = rp.rule('u10_rpRule', 'Re-evaluate', 'up10-priority', binding='latest')
    rp.seq(a1, a2, rp.end('u10_rpEnd', 'Updated'))
    pk.dmn('up10-priority', 'Incident priority matrix', 'FIRST', [('Priority', 'priority', 'string')],
           [('resolverGroup', 'Resolver group', 'string'), ('slaSpec', 'SLA', 'string'), ('majorIncident', 'Major', 'boolean')],
           [([q('P1')], [q('L2-SUPPORT'), q('PT1H'), 'true'], ''), ([q('P2')], [q('L2-SUPPORT'), q('4WH'), 'false'], ''),
            (['-'], [q('L1-SUPPORT'), q('2WD'), 'false'], '')])
    simple_seeds(pk, 'ITSM', [('L1-SUPPORT', 'L1', 'L2-SUPPORT', 'Service desk L1', 'सेवा डेस्क एल1'),
                              ('L2-SUPPORT', 'L2', 'INCIDENT-MGR', 'Resolver L2', 'समाधानकर्ता एल2'),
                              ('NET-OPS', 'OPS', 'INCIDENT-MGR', 'Network operations', 'नेटवर्क संचालन'),
                              ('APP-OPS', 'OPS', 'INCIDENT-MGR', 'Application operations', 'अनुप्रयोग संचालन'),
                              ('DB-OPS', 'OPS', 'INCIDENT-MGR', 'Database operations', 'डेटाबेस संचालन'),
                              ('INCIDENT-MGR', 'MANAGER', None, 'Incident manager', 'घटना प्रबंधक')], 'u10')


def up11():
    pk = upack(11, 'change-window', 'Change approval with window and rollback', 'समय-खिड़की एवं वापसी सहित परिवर्तन अनुमोदन',
               'Risk by rule decides whether the CAB (committee) must approve; a freeze-calendar check; implementation '
               'only inside the approved window (time lock); verification; failure rolls back by compensation '
               '(R31, R23).', ['R23', 'R31', 'GP-03', 'GP-04', 'GP-24', 'GP-13'],
               [('change.freezeCheck', 'a', '', 'Freeze calendar'), ('change.rollback', 'a', '', 'Rollback')])
    p = pk.proc('sangam-up11-change', 'UP-11 Change', '', start={'materialFields': 'subject,description,rollbackPlan'})
    s = p.root
    st = s.start('u11_start', 'Change requested', form='up11-change-window/request')
    p.forms.append(dict(key='up11-change-window/request', name='Change request | परिवर्तन अनुरोध', decision=None,
                        fields=['subject*', 'description*', 'changeWindowStart*', 'rollbackPlan*', 'priority'], doc=None))
    d = s.setv('u11_defaults', 'Configuration', defaults='priority=P3;committeeSeats=CAB-1,CAB-2,CAB-3;quorumRule=MAJORITY;'
               'freezeFrozen=false;implementerSeat=IMPLEMENTER')
    r = s.rule('u11_risk', 'Risk and CAB need (DMN)', 'up11-risk')
    g = s.xgw('u11_cabGw', 'CAB?')
    s.seq(st, d, r, g)
    cab = core(s, 'u11_cab', 'Change advisory board (core committee)', 'committee')
    fz = s.conn('u11_freeze', 'Freeze calendar check', 'change.freezeCheck', 'check', 'query', 'changeWindowStart', 'freeze')
    s.flow(g, cab, 'cabRequired', 'yes')
    s.flow(g, fz, None, 'standard change', default=True)
    cg = s.xgw('u11_cabRes', 'Approved?')
    s.flow(cab, cg)
    s.flow(cg, fz, 'committeeApproved', 'approved')
    s.flow(cg, s.end('u11_endRejected', 'Rejected by CAB'), None, 'rejected', default=True)
    fg = s.xgw('u11_freezeGw', 'Frozen?')
    s.flow(fz, fg)
    rs = s.user('u11_reschedule', 'Window falls in a freeze: reschedule | परिवर्तन रोक अवधि में: पुनर्निर्धारित करें',
                '${initiatorSeat}', form='up11-change-window/reschedule', decision=None, fields=['changeWindowStart*'])
    s.flow(fg, rs, 'freezeFrozen', 'frozen')
    s.flow(rs, fz)
    lock = s.catch_timer('u11_window', 'Change window opens', date='${changeWindowStart}')
    s.flow(fg, lock, None, 'clear', default=True)
    impl = s.user('u11_implement', 'Implement change | परिवर्तन लागू करें', '${implementerSeat}',
                  form='up11-change-window/implement', decision=None, fields=['remarks'])
    ver = s.user('u11_verify', 'Verify change | परिवर्तन सत्यापित करें', '${implementerSeat}', form='up11-change-window/verify',
                 decision=('verificationResult', ['success', 'rollback']), fields=['remarks'])
    s.seq(lock, impl, ver)
    cb = s.boundary('u11_implComp', impl, 'Roll back', compensate=True)
    rb = s.conn('u11_rollback', 'Roll back change', 'change.rollback', 'rollback', 'submit', 'subject,rollbackPlan',
                'rollback', step='rollback')
    s.node(rb)['attrs']['isForCompensation'] = 'true'
    s.node(rb)['comp_for'] = impl
    s.assoc(cb, rb)
    vg = s.xgw('u11_verGw')
    s.flow(ver, vg)
    comp = s.throw('u11_compensate', 'Roll back by compensation', compensate=True)
    s.flow(vg, comp, "verificationResult == 'rollback'", 'failed')
    s.flow(comp, s.end('u11_endRolledBack', 'Rolled back'))
    s.flow(vg, end_ok(p, s, 'u11_end', 'Implemented'), None, 'success', default=True)
    pk.dmn('up11-risk', 'Change risk', 'FIRST', [('Priority', 'priority', 'string')], [('cabRequired', 'CAB', 'boolean')],
           [([q('P1')], ['true'], 'emergency changes still go to the e-CAB'), ([q('P2')], ['true'], ''), (['-'], ['false'], '')])
    simple_seeds(pk, 'CAB', [('CAB-1', 'CAB', None, 'CAB member 1', 'सीएबी सदस्य 1'), ('CAB-2', 'CAB', None, 'CAB member 2', 'सीएबी सदस्य 2'),
                             ('CAB-3', 'CAB', None, 'CAB member 3', 'सीएबी सदस्य 3'),
                             ('IMPLEMENTER', 'IMPL', None, 'Implementer', 'कार्यान्वयनकर्ता')], 'u11')


def up12():
    pk = upack(12, 'order-decomposition', 'Order decomposition and amendment in flight', 'आदेश विभाजन एवं प्रगति में संशोधन',
               'One order becomes one sub-order instance per line (GP-09 pattern instead of a dynamic multi-instance), '
               'aggregated by the parent; amendments are broadcast to the running sub-orders; partial fulfilment is '
               'reported (R36, R49).', ['R36', 'R49', 'GP-09', 'GP-14'],
               [('order.lines', 'a', '', 'Lines of the order'), ('telecom.activate', 'c', '', 'Activation'),
                ('telecom.modify', 'a', '', 'Modification')])
    p = pk.proc('sangam-up12-order', 'UP-12 Decomposed order', '', start={'materialFields': 'orderId,customerId'})
    s = p.root
    st = s.start('u12_start', 'Order captured', form='up12-order-decomposition/order')
    p.forms.append(dict(key='up12-order-decomposition/order', name='Order capture | आदेश ग्रहण', decision=None,
                        fields=['orderId*', 'customerId*'], doc=None))
    ld = s.svc('u12_lines', 'Decompose into lines', C['BATCH'], {'op': 'LOAD', 'connector': 'order.lines', 'sampleSize': '3'},
               async_=True)
    s.seq(st, ld)
    op, cg = batch_loop(p, s, 'u12', 'sangam-up12-suborder', poll='PT1M', inherit='orderId,customerId')
    s.flow(ld, op)
    fg = s.xgw('u12_partialGw', 'Partial?')
    s.flow(cg, fg, None, 'complete', default=True)
    pn = s.notify('u12_partial', 'Partial fulfilment notice', 'order-partial', 'email', 'customerId')
    s.flow(fg, pn, 'batchFailed > 0', 'some lines failed')
    e = end_ok(p, s, 'u12_end', 'Fulfilled')
    s.flow(fg, e, None, 'all lines', default=True)
    s.flow(pn, e)
    am = s.evsub('u12_amend', 'Amend in flight')
    a1 = am.start('u12_amStart', 'Amendment', msg='sangam.order.amend', interrupting=False)
    n, g = broadcast(am, 'u12_am', 'sangam.suborder.amend', own_keys=False)
    am.flow(a1, n)
    am.flow(g, am.end('u12_amEnd', 'Amendment sent'), None, 'all told', default=True)
    c = pk.proc('sangam-up12-suborder', 'UP-12 Sub-order (one line)', '', start={'materialFields': 'key,amount'})
    s = c.root
    a1 = s.start('u12s_start', 'Line', initiator=None)
    d = s.setv('u12s_defaults', 'Configuration', defaults='executionConnector=telecom.activate;executionStep=activate;'
               'verifyApproval=false;reconcileRequired=false')
    ex = core(s, 'u12s_activate', 'Activate (core execution)', 'execution')
    s.seq(a1, d, ex)
    ok = end_ok(c, s, 'u12s_endOk', 'Activated')
    fl = s.end('u12s_endFailed', 'Fallout', listeners=[c.item_listener('FAILED', reason_var='opReasonCode')])
    outcome(s, 'u12s_gw', 'executionOutcome', {'DONE': ok}, fl)
    s.flow(ex, 'u12s_gw')
    am = s.evsub('u12s_amend', 'Amendment')
    b1 = am.start('u12s_amStart', 'Amend', msg='sangam.suborder.amend', interrupting=False)
    b2 = am.conn('u12s_modify', 'Modify line', 'telecom.modify', 'modify', 'submit', 'key', 'modify', step='modify')
    am.seq(b1, b2, am.end('u12s_amEnd', 'Modified'))


def up13():
    pk = upack(13, 'returns-refunds', 'Returns, refunds and partial fulfilment', 'वापसी, धनवापसी एवं आंशिक पूर्ति',
               'Return eligibility by policy, pickup, inspection of the returned quantity, partial refund computed on '
               'the accepted quantity and paid once by operationId (R36).', ['R36', 'GP-13', 'GP-12'],
               [('shipping.pickup', 'a', '', 'Pickup'), ('payment.refund', 'a', '', 'Refund')])
    p = pk.proc('sangam-up13-returns', 'UP-13 Return and refund', '', start={'materialFields': 'orderId,returnQty,refundAmount'})
    s = p.root
    st = s.start('u13_start', 'Return requested', form='up13-returns-refunds/request')
    p.forms.append(dict(key='up13-returns-refunds/request', name='Return request | वापसी अनुरोध', decision=None,
                        fields=['orderId*', 'returnQty*', 'refundAmount*', 'category', 'description'], doc=None))
    d = s.setv('u13_defaults', 'Configuration', defaults='category=GENERAL;inspectorSeat=RETURNS-DESK;'
               'executionConnector=payment.refund;executionStep=refund;verifyApproval=false;reconcileRequired=false;'
               'executionPayloadFields=orderId,refundDue')
    r = s.rule('u13_policy', 'Return policy (DMN)', 'up13-policy')
    g = s.xgw('u13_gw', 'Eligible?')
    s.seq(st, d, r, g)
    s.flow(g, s.end('u13_endIneligible', 'Not eligible'), '!returnEligible', 'no')
    pu = s.conn('u13_pickup', 'Arrange pickup', 'shipping.pickup', 'pickup', 'submit', 'orderId', 'pickup', step='pickup')
    s.flow(g, pu, None, 'yes', default=True)
    rcv = s.catch_msg('u13_received', 'Goods received back', 'sangam.returnReceived')
    ins = s.user('u13_inspect', 'Inspect returned goods | लौटाई गई वस्तु का निरीक्षण', '${inspectorSeat}',
                 form='up13-returns-refunds/inspect', decision=('returnDecision', ['accept', 'reject']),
                 fields=['acceptedQty*', 'remarks'])
    s.seq(pu, rcv, ins)
    ig = s.xgw('u13_ig')
    s.flow(ins, ig)
    calc = s.expr('u13_calc', 'Refund on accepted quantity',
                  "${execution.setVariable('refundDue', refundAmount * acceptedQty / returnQty * (1 - restockingFeePct / 100))}")
    s.flow(ig, calc, "returnDecision == 'accept'", 'accept')
    s.flow(ig, s.end('u13_endRejected', 'Return rejected'), None, 'reject', default=True)
    ex = core(s, 'u13_refund', 'Refund once (core execution)', 'execution')
    s.seq(calc, ex, end_ok(p, s, 'u13_end', 'Refunded'))
    pk.dmn('up13-policy', 'Return policy', 'FIRST', [('Category', 'category', 'string')],
           [('returnEligible', 'Eligible', 'boolean'), ('restockingFeePct', 'Restocking fee %', 'double')],
           [([q('PERISHABLE')], ['false', '0'], ''), ([q('ELECTRONICS')], ['true', '10'], ''), (['-'], ['true', '0'], '')])
    simple_seeds(pk, 'RET', [('RETURNS-DESK', 'RETURNS', None, 'Returns desk', 'वापसी डेस्क')], 'u13')


def up14():
    pk = upack(14, 'three-way-match', 'Three-way match with tolerance exceptions', 'सहनशीलता अपवादों सहित त्रि-पक्षीय मिलान',
               'PO, goods receipt and invoice are compared by rule with quantity and price tolerances; within tolerance '
               'posts automatically, exceptions go to an AP clerk who may seek supplier clarification (R8, R71).',
               ['R8', 'R71', 'GP-29', 'GP-31', 'GP-16'], [('ap.post', 'a', '', 'Post invoice for payment')])
    p = pk.proc('sangam-up14-three-way-match', 'UP-14 Three-way match', '',
                start={'materialFields': 'poQty,grnQty,invoiceQty,poPrice,invoicePrice'})
    s = p.root
    st = s.start('u14_start', 'Invoice received', form='up14-three-way-match/invoice')
    p.forms.append(dict(key='up14-three-way-match/invoice', name='Supplier invoice | आपूर्तिकर्ता बीजक', decision=None,
                        fields=['supplierName*', 'poQty*', 'grnQty*', 'invoiceQty*', 'poPrice*', 'invoicePrice*'], doc=None))
    d = s.setv('u14_defaults', 'Configuration', defaults='apSeat=AP-CLERK;executionConnector=ap.post;executionStep=post;'
               'verifyApproval=false;reconcileRequired=false')
    m = s.rule('u14_match', 'Match with tolerances (DMN)', 'up14-tolerance')
    g = s.xgw('u14_gw', 'Result?')
    s.seq(st, d, m, g)
    post = core(s, 'u14_post', 'Post (core execution)', 'execution')
    s.flow(g, post, "matchResult != 'EXCEPTION'", 'match / within tolerance')
    ex = s.user('u14_exception', 'Resolve match exception | मिलान अपवाद का समाधान', '${apSeat}',
                form='up14-three-way-match/exception', decision=('exceptionDecision', ['approve', 'clarify', 'reject']),
                fields=['remarks*'], sod=True)
    s.flow(g, ex, None, 'exception', default=True)
    eg = s.xgw('u14_eg')
    s.flow(ex, eg)
    s.flow(eg, post, "exceptionDecision == 'approve'", 'approve')
    ask = s.notify('u14_ask', 'Ask supplier to clarify', 'supplier-clarification', 'email', 'supplierName')
    s.flow(eg, ask, "exceptionDecision == 'clarify'", 'clarify')
    resp = s.catch_msg('u14_response', 'Supplier response (credit note / corrected invoice)', 'sangam.supplierResponse')
    s.seq(ask, resp, m)
    s.flow(eg, s.end('u14_endRejected', 'Invoice rejected'), None, 'reject', default=True)
    s.flow(post, end_ok(p, s, 'u14_end', 'Posted'))
    pk.dmn('up14-tolerance', 'Three-way match tolerances (2% quantity, 1% price)', 'FIRST',
           [('Invoice qty', 'invoiceQty', 'double'), ('Received qty', 'grnQty', 'double'), ('PO qty', 'poQty', 'double'),
            ('Invoice price', 'invoicePrice', 'double'), ('PO price', 'poPrice', 'double')],
           [('matchResult', 'Result', 'string')],
           [(['-', '-', '-', '-', '-'],
             ['if invoiceQty = grnQty and grnQty <= poQty and invoicePrice = poPrice then "MATCH" '
              'else if abs(invoiceQty - grnQty) <= 0.02 * poQty and abs(invoicePrice - poPrice) <= 0.01 * poPrice '
              'then "WITHIN_TOLERANCE" else "EXCEPTION"'], '')])
    simple_seeds(pk, 'AP', [('AP-CLERK', 'AP', None, 'Accounts payable clerk', 'देय लेखा लिपिक')], 'u14')


def up15():
    pk = upack(15, 'recertification', 'Periodic recertification at scale', 'बड़े पैमाने पर आवधिक पुनः प्रमाणन',
               'Monthly: every entity due (access, KYC, licence renewal) gets its own case in staggered chunks (GP-09); '
               'the reviewer recertifies or revokes; no answer by the deadline revokes automatically (R31, R64).',
               ['R31', 'R64', 'GP-30', 'GP-08', 'GP-09', 'GP-44'],
               [('iam.entitiesDue', 'a', '', 'Entities due'), ('iam.revoke', 'a', '', 'Revocation')])
    p = pk.proc('sangam-up15-recertification', 'UP-15 Recertification campaign', '', start={'materialFields': 'period'})
    s = p.root
    t = s.start('u15_timer', 'Monthly', timer_cycle='0 0 6 1 * ?', initiator=None)
    ld = s.svc('u15_load', 'Entities due', C['BATCH'], {'op': 'LOAD', 'connector': 'iam.entitiesDue', 'sampleSize': '4'},
               async_=True)
    s.flow(t, ld)
    op, cg = batch_loop(p, s, 'u15', 'sangam-up15-recertify', chunk=100, poll='PT1H')
    s.flow(ld, op)
    s.flow(cg, s.end('u15_end', 'Campaign closed'), None, 'complete', default=True)
    c = pk.proc('sangam-up15-recertify', 'UP-15 Recertify one entity', '', start={'materialFields': 'key'})
    s = c.root
    a1 = s.start('u15c_start', 'Entity due', initiator=None)
    d = s.setv('u15c_defaults', 'Configuration', defaults='reviewerSeat=REVIEWER;recertSla=10WD')
    te, td = s.sla_task('u15c_review', 'Recertify | पुनः प्रमाणित करें', 'reviewerSeat', form='up15-recertification/review',
                        decision=('recertDecision', ['yes', 'no']), fields=['entityId', 'remarks'], spec='${recertSla}',
                        on_breach='DEEMED_REFUSAL', approve='yes', refuse='no')
    s.seq(a1, d, te)
    g = s.xgw('u15c_gw')
    s.flow(td, g)
    rv = s.conn('u15c_revoke', 'Revoke', 'iam.revoke', 'revoke', 'submit', 'key', 'revoke', step='revoke')
    s.flow(g, rv, "recertDecision == 'no'", 'revoke (or no answer)')
    s.flow(rv, end_ok(c, s, 'u15c_endRevoked', 'Revoked', outcome='REVOKED'))
    s.flow(g, end_ok(c, s, 'u15c_end', 'Recertified'), None, 'recertified', default=True)
    simple_seeds(pk, 'IAM', [('REVIEWER', 'REVIEWER', None, 'Access reviewer', 'पहुँच समीक्षक')], 'u15')


def up16():
    pk = upack(16, 'provisioning', 'Fan-out provisioning and de-provisioning with deadline and evidence',
               'समय-सीमा एवं साक्ष्य सहित प्रावधान एवं प्रावधान-निरसन',
               'Joiner: account, badge and laptop in parallel, compensated if any fails; leaver: revoke-all in parallel '
               'within the deadline, escalated when late; evidence document of completion (R71, R36, R42).',
               ['R36', 'R42', 'R71', 'GP-05', 'GP-33'],
               [('provision.account', 'a', '', 'Accounts'), ('provision.badge', 'a', '', 'Badge'),
                ('provision.laptop', 'a', '', 'Laptop'), ('iam.revokeAll', 'a', '', 'Revoke all access'),
                ('provision.recoverAssets', 'a', '', 'Recover assets'), ('provision.undo', 'a', '', 'Undo (compensation)')])
    p = pk.proc('sangam-up16-provisioning', 'UP-16 Joiner / leaver provisioning', '',
                start={'materialFields': 'employeeId,employeeName,mode'})
    s = p.root
    st = s.start('u16_start', 'Joiner or leaver', form='up16-provisioning/request')
    p.forms.append(dict(key='up16-provisioning/request', name='Joiner / leaver | नवागत / प्रस्थानकर्ता', decision=None,
                        fields=['employeeId*', 'employeeName*', 'joiningDate*'], doc=None))
    d = s.setv('u16_defaults', 'Configuration', defaults='mode=JOINER;securitySeat=SECURITY-OFFICER')
    g = s.xgw('u16_mode', 'Joiner or leaver?')
    s.seq(st, d, g)
    f1 = s.pgw('u16_jfork', 'Provision in parallel')
    j1 = s.pgw('u16_jjoin', 'All provisioned')
    s.flow(g, f1, "mode == 'JOINER'", 'joiner')
    for c_, nm in (('provision.account', 'Accounts'), ('provision.badge', 'Badge'), ('provision.laptop', 'Laptop')):
        cid = 'u16_' + c_.split('.')[1]
        t = s.conn(cid, 'Provision: ' + nm, c_, 'provision', 'submit', 'employeeId,employeeName', cid, step=cid,
                   error='PROVISION_FAILED')
        s.flow(f1, t)
        s.flow(t, j1)
        cb = s.boundary(cid + '_comp', t, 'Undo', compensate=True)
        h = s.conn(cid + '_undo', 'Undo: ' + nm, 'provision.undo', 'undo', 'submit', 'employeeId', cid + 'Undo',
                   step=cid + '-undo')
        s.node(h)['attrs']['isForCompensation'] = 'true'
        s.node(h)['comp_for'] = t
        s.assoc(cb, h)
        eb = s.boundary(cid + '_fail', t, 'Failed', error='PROVISION_FAILED')
        s.flow(eb, 'u16_compensate')
    comp = s.throw('u16_compensate', 'Undo what was provisioned', compensate=True)
    s.flow(comp, s.end('u16_endFailed', 'Provisioning failed (undone)'))
    f2 = s.pgw('u16_lfork', 'Revoke in parallel')
    j2 = s.pgw('u16_ljoin', 'All revoked')
    s.flow(g, f2, None, 'leaver', default=True)
    for c_, nm in (('iam.revokeAll', 'Revoke all access'), ('provision.recoverAssets', 'Recover assets')):
        cid = 'u16_' + c_.split('.')[1]
        t = s.conn(cid, nm, c_, 'revoke', 'submit', 'employeeId', cid, step=cid)
        s.flow(f2, t)
        s.flow(t, j2)
    ev = s.svc('u16_evidence', 'Evidence of completion', C['DOC'], {'template': 'provisioning-evidence'})
    s.flow(j1, ev)
    s.flow(j2, ev)
    s.flow(ev, end_ok(p, s, 'u16_end', 'Done'))
    late = s.evsub('u16_late', 'Leaver deadline')
    l1 = late.start('u16_lateStart', 'Deadline passed', timer_date=None, interrupting=False)
    # literal: an event-subprocess timer is armed when the scope starts, before any variable is set
    late.nodes[-1]['body'] = ['<timerEventDefinition><timeDuration xsi:type="tFormalExpression">PT4H'
                              '</timeDuration></timerEventDefinition>']
    l2 = late.notify('u16_lateNotify', 'Escalate to security', 'revocation-late', 'email,inapp', 'securitySeat')
    late.seq(l1, l2, late.end('u16_lateEnd', 'Escalated'))
    simple_seeds(pk, 'HR-IT', [('SECURITY-OFFICER', 'SECURITY', None, 'Security officer', 'सुरक्षा अधिकारी')], 'u16')


def up17():
    pk = upack(17, 'prior-authorisation', 'Regulated clinical / benefit pathway (prior authorisation)',
               'विनियमित नैदानिक / लाभ मार्ग (पूर्व प्राधिकरण)',
               'Consent gate, eligibility with the payer, clinical criteria by rule (auto-approve where met), statutory '
               'decision clock (72 hours urgent / 14 days standard), clinical and peer review, decision callback to the '
               'provider, appeal (R53-style minimum data, R79).', ['R53', 'R79', 'R38', 'GP-35', 'GP-22', 'GP-15'],
               [('payer.eligibility', 'a', '', 'Eligibility'), ('provider.callback', 'b', '', 'Decision to the provider')])
    p = pk.proc('sangam-up17-prior-auth', 'UP-17 Prior authorisation', '',
                start={'materialFields': 'customerId,description,urgency'})
    s = p.root
    st = s.start('u17_start', 'Request from provider', form='up17-prior-authorisation/request')
    p.forms.append(dict(key='up17-prior-authorisation/request', name='Prior authorisation request | पूर्व प्राधिकरण अनुरोध',
                        decision=None, fields=['customerId*', 'description*', 'priority', 'consentGiven*', 'documents'],
                        doc=None))
    d = s.setv('u17_defaults', 'Configuration', defaults='urgency=@priority;consentGiven=false;appealAllowed=true;'
               'appellateSeat=MEDICAL-DIRECTOR;reviewerSeat=CLINICAL-REVIEWER;caseType=PRIOR_AUTH')
    cg = s.xgw('u17_consent', 'Consent recorded?')
    s.seq(st, d, cg)
    s.flow(cg, s.end('u17_endNoConsent', 'Stopped: no consent'), '!consentGiven', 'no')
    el = s.conn('u17_eligibility', 'Eligibility with payer', 'payer.eligibility', 'check', 'query', 'customerId', 'payer')
    s.flow(cg, el, None, 'yes', default=True)
    cr = s.rule('u17_criteria', 'Clinical criteria and decision clock (DMN)', 'up17-criteria')
    clk = s.svc('u17_clock', 'Statutory decision clock', C['SLA'], {'op': 'DEADLINE', 'clock': 'case', 'spec': '${decisionClock}'})
    ag = s.xgw('u17_autoGw', 'Criteria met automatically?')
    s.seq(el, cr, clk, ag)
    auto = s.svc('u17_auto', 'Auto-approval bound to the request', C['CASE'], {'op': 'BIND_DEEMED'})
    s.flow(ag, auto, 'autoApprove', 'yes')
    re_, rd = s.sla_task('u17_review', 'Clinical review | नैदानिक समीक्षा', 'reviewerSeat', form='up17-prior-authorisation/review',
                         decision=('clinicalDecision', ['approve', 'reject', 'peer']),
                         fields=['clinicalCriteriaMet', 'remarks', 'rejectionReasonCodes'], spec='${decisionClock}',
                         on_breach='ESCALATE', bind='approve')
    s.flow(ag, re_, None, 'no', default=True)
    g = s.xgw('u17_gw')
    s.flow(rd, g)
    peer = s.user('u17_peer', 'Peer review | सहकर्मी समीक्षा', 'PEER-REVIEWER', form='up17-prior-authorisation/peer',
                  decision=None, fields=['remarks*'])
    s.flow(g, peer, "clinicalDecision == 'peer'", 'peer review')
    s.flow(peer, re_)
    cb = s.conn('u17_callback', 'Decision to the provider', 'provider.callback', 'decision', 'submit',
                'customerId,clinicalDecision', 'callback', step='callback')
    s.flow(g, cb, "clinicalDecision == 'approve'", 'approve')
    s.flow(auto, cb)
    s.flow(cb, end_ok(p, s, 'u17_end', 'Decided'))
    rej = rejection(p, s, 'u17_rj', reopen=re_)
    s.flow(g, rej, None, 'reject', default=True)
    pk.dmn('up17-criteria', 'Clinical criteria and decision clock', 'FIRST', [('Urgency', 'urgency', 'string')],
           [('autoApprove', 'Auto-approve', 'boolean'), ('decisionClock', 'Decision clock', 'string')],
           [([q('P1')], ['false', q('72H')], 'urgent: 72 hours'), (['-'], ['false', q('14D')], 'standard: 14 days')])
    simple_seeds(pk, 'PAYER', [('CLINICAL-REVIEWER', 'REVIEWER', 'MEDICAL-DIRECTOR', 'Clinical reviewer', 'नैदानिक समीक्षक'),
                               ('PEER-REVIEWER', 'PEER', 'MEDICAL-DIRECTOR', 'Peer reviewer', 'सहकर्मी समीक्षक'),
                               ('MEDICAL-DIRECTOR', 'DIRECTOR', None, 'Medical director (appeals)', 'चिकित्सा निदेशक (अपील)')], 'u17')


def up18():
    pk = upack(18, 'capa', 'Non-conformance to CAPA with effectiveness check', 'गैर-अनुरूपता से सीएपीए एवं प्रभावशीलता जाँच',
               'Non-conformance, root cause, CAPA plan approved, actions implemented, effectiveness checked after N days '
               '(loops back when not effective), closure e-signed (R45, R57-style, R31).', ['R31', 'R45', 'R57', 'GP-22', 'GP-34'])
    p = pk.proc('sangam-up18-capa', 'UP-18 CAPA', '', start={'materialFields': 'description,rootCause,capaActions'})
    s = p.root
    st = s.start('u18_start', 'Non-conformance', form='up18-capa/nc')
    p.forms.append(dict(key='up18-capa/nc', name='Non-conformance report | गैर-अनुरूपता रिपोर्ट', decision=None,
                        fields=['description*', 'category', 'documents'], doc=None))
    d = s.setv('u18_defaults', 'Configuration', defaults='effectivenessDelay=P30D;qaSeat=QA-LEAD;caseType=CAPA;amount=0;'
               'routingDecisionKey=up18-approver;rootCause=;capaActions=;appealAllowed=false')
    inv = s.user('u18_investigate', 'Investigate root cause | मूल कारण की जाँच', '${qaSeat}', form='up18-capa/investigate',
                 decision=None, fields=['rootCause*', 'capaActions*'])
    san = core(s, 'u18_approve', 'Approve CAPA (core sanction)', 'sanction')
    s.seq(st, d, inv, san)
    imp = s.user('u18_implement', 'Implement actions | कार्रवाई लागू करें', '${qaSeat}', form='up18-capa/implement',
                 decision=None, fields=['remarks*'])
    outcome(s, 'u18_sanGw', 'sanctionOutcome', {'SANCTIONED': imp}, inv)
    s.flow(san, 'u18_sanGw')
    wait = s.catch_timer('u18_wait', 'Effectiveness period', duration='${effectivenessDelay}')
    chk = s.user('u18_check', 'Effectiveness check | प्रभावशीलता जाँच', 'QA-HEAD', form='up18-capa/effectiveness',
                 decision=('effectiveDecision', ['yes', 'no']), fields=['effective', 'remarks'], sod=True)
    s.seq(imp, wait, chk)
    g = s.xgw('u18_gw')
    s.flow(chk, g)
    s.flow(g, inv, "effectiveDecision == 'no'", 'recurrence: investigate again')
    sign = s.svc('u18_sign', 'E-signature on closure', C['SIGN'], {'op': 'REQUEST', 'purpose': 'capa-closure'}, async_=True)
    s.flow(g, sign, None, 'effective', default=True)
    s.flow(sign, end_ok(p, s, 'u18_end', 'Closed'))
    pk.dmn('up18-approver', 'CAPA approver', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')], [(['-'], [q('QAHEAD')], '')])
    simple_seeds(pk, 'QA', [('QA-LEAD', 'QA', 'QA-HEAD', 'Quality engineer', 'गुणवत्ता अभियंता'),
                            ('QA-HEAD', 'QAHEAD', None, 'Head of quality', 'गुणवत्ता प्रमुख')], 'u18')


def up19():
    pk = upack(19, 'contract-lifecycle', 'Contract lifecycle (CLM)', 'अनुबंध जीवनचक्र (सीएलएम)',
               'Draft, redlining loop with the counterparty (external party), parallel legal and finance review, signing '
               'authority, signature over the approved version, obligation reminders and renewal decision before '
               'expiry with a linked renewal (R74, R57-style, R79).', ['R57', 'R74', 'R79', 'GP-02', 'GP-03', 'GP-25', 'GP-30'],
               [('esign.sign', 'b', 'G-SIGN', 'Signature')])
    p = pk.proc('sangam-up19-clm', 'UP-19 Contract lifecycle', '', start={'materialFields': 'counterpartyName,description,amount'})
    s = p.root
    st = s.start('u19_start', 'Contract requested', form='up19-contract-lifecycle/request')
    p.forms.append(dict(key='up19-contract-lifecycle/request', name='Contract request | अनुबंध अनुरोध', decision=None,
                        fields=['counterpartyName*', 'description*', 'amount'], doc=None))
    d = s.setv('u19_defaults', 'Configuration', defaults='amount=0;caseType=CONTRACT;routingDecisionKey=up19-signatory;'
               'signatureRequired=true;renewalLead=P60D;contractExpiryAt=null;appealAllowed=false')
    dr = s.user('u19_draft', 'Draft contract | अनुबंध का प्रारूप', 'LEGAL-COUNSEL', form='up19-contract-lifecycle/draft',
                decision=None, fields=['redline*', 'remarks'])
    rl = s.user('u19_redline', 'Counterparty redline | प्रतिपक्ष संशोधन', 'COUNTERPARTY', form='up19-contract-lifecycle/redline',
                decision=('redlineDecision', ['accept', 'return']), fields=['redline', 'remarks'])
    s.seq(st, d, dr, rl)
    g = s.xgw('u19_rg')
    s.flow(rl, g)
    s.flow(g, dr, "redlineDecision == 'return'", 'another round')
    f = s.pgw('u19_fork', 'Parallel review')
    s.flow(g, f, None, 'agreed text', default=True)
    j = s.pgw('u19_join')
    for seat, en, hi in (('LEGAL-REVIEW', 'Legal review', 'विधिक समीक्षा'), ('FINANCE-REVIEW', 'Finance review', 'वित्त समीक्षा')):
        t = s.user('u19_' + seat.lower().replace('-', ''), f'{en} | {hi}', seat,
                   form='up19-contract-lifecycle/' + seat.lower(), decision=None, fields=['remarks*'])
        s.flow(f, t)
        s.flow(t, j)
    san = core(s, 'u19_sign', 'Signing authority with signature (core sanction)', 'sanction')
    s.flow(j, san)
    dates = s.user('u19_dates', 'Record term and expiry | अवधि एवं समाप्ति दर्ज करें', 'LEGAL-COUNSEL',
                   form='up19-contract-lifecycle/term', decision=None, fields=['dueDate*'])
    outcome(s, 'u19_sanGw', 'sanctionOutcome', {'SANCTIONED': dates}, dr)
    s.flow(san, 'u19_sanGw')
    ex = s.setv('u19_expiry', 'Expiry', values='contractExpiryAt=@dueDate')
    rn = s.svc('u19_renewalDue', 'Renewal notice before expiry', C['SLA'],
               {'op': 'BEFORE', 'clock': 'renewal', 'dateVar': 'contractExpiryAt', 'spec': '${renewalLead}'})
    w = s.catch_timer('u19_renewalWait', 'Renewal notice', date='${renewalDueAt}')
    rd = s.user('u19_renewal', 'Renewal decision | नवीनीकरण निर्णय', 'LEGAL-COUNSEL', form='up19-contract-lifecycle/renewal',
                decision=('renewalDecision', ['renew', 'terminate']), fields=['remarks'])
    s.seq(dates, ex, rn, w, rd)
    rg = s.xgw('u19_renGw')
    s.flow(rd, rg)
    ln = s.svc('u19_renew', 'Renewal as a linked contract', C['LINK'],
               {'processKey': 'sangam-up19-clm', 'linkType': 'renewal', 'inherit': 'counterpartyName,description,amount'})
    s.flow(rg, ln, "renewalDecision == 'renew'", 'renew')
    s.flow(ln, end_ok(p, s, 'u19_endRenewed', 'Renewed'))
    s.flow(rg, end_ok(p, s, 'u19_end', 'Expired'), None, 'terminate', default=True)
    ob = s.evsub('u19_obligations', 'Obligation reminders')
    o1 = ob.start('u19_obStart', 'Monthly', interrupting=False)
    ob.nodes[-1]['body'] = ['<timerEventDefinition><timeCycle xsi:type="tFormalExpression">R/P30D'
                            '</timeCycle></timerEventDefinition>']
    o2 = ob.notify('u19_obNotify', 'Remind obligations', 'contract-obligations', 'email', 'counterpartyName')
    ob.seq(o1, o2, ob.end('u19_obEnd', 'Reminded'))
    pk.dmn('up19-signatory', 'Signing authority', 'FIRST', [('Amount', 'amount', 'double')],
           [('competentRole', 'Competent role', 'string')],
           [(['<= 1000000'], [q('DS')], ''), (['-'], [q('JS')], '')])
    simple_seeds(pk, 'LEGAL-U', [('LEGAL-COUNSEL', 'COUNSEL', None, 'Contract counsel', 'अनुबंध अधिवक्ता'),
                                 ('COUNTERPARTY', 'EXTERNAL', None, 'Counterparty (portal)', 'प्रतिपक्ष (पोर्टल)'),
                                 ('LEGAL-REVIEW', 'LEGAL', None, 'Legal reviewer', 'विधिक समीक्षक'),
                                 ('FINANCE-REVIEW', 'FINANCE', None, 'Finance reviewer', 'वित्त समीक्षक')], 'u19')


def up20():
    pk = upack(20, 'rpa', 'RPA step against a legacy UI', 'पुराने यूआई पर आरपीए चरण',
               'A versioned bot script runs as an external task under the worker lease (topic sangam.rpa, bot pool, '
               'credentials by reference); a screen failure becomes a BPMN error handled by a person who retries or '
               'does the step in attended mode (R73).', ['R73', 'GP-27', 'GP-31'], [('rpa.bot', 'c', '', 'Bot run')])
    p = pk.proc('sangam-up20-rpa', 'UP-20 RPA step', '', start={'materialFields': 'description,rpaScript'})
    s = p.root
    st = s.start('u20_start', 'Work item', form='up20-rpa/item')
    p.forms.append(dict(key='up20-rpa/item', name='Legacy data entry item | पुराने तंत्र में डेटा प्रविष्टि', decision=None,
                        fields=['description*', 'documents'], doc=None))
    d = s.setv('u20_defaults', 'Configuration', defaults='rpaScript=legacy-entry;rpaScriptVersion=1.0.0;botPool=unattended;'
               'credentialRef=vault:legacy-erp;operatorSeat=BOT-OPERATOR')
    bot = s.ext('u20_bot', 'Bot runs the legacy screen', 'sangam.rpa')
    s.seq(st, d, bot)
    eb = s.boundary('u20_screenFail', bot, 'Screen failure', error='RPA_SCREEN_FAILURE')
    h = s.user('u20_handle', 'Bot failed: retry or do manually | बॉट विफल: पुनः प्रयास या मैन्युअल', '${operatorSeat}',
               form='up20-rpa/handle', decision=('botDecision', ['retry', 'manual']), fields=['remarks'])
    s.flow(eb, h)
    g = s.xgw('u20_gw')
    s.flow(h, g)
    s.flow(g, bot, "botDecision == 'retry'", 'retry')
    m = s.user('u20_manual', 'Attended step | सहायक चरण', '${operatorSeat}', form='up20-rpa/manual', decision=None,
               fields=['remarks*'])
    s.flow(g, m, None, 'manual', default=True)
    e = end_ok(p, s, 'u20_end', 'Done')
    s.flow(bot, e)
    s.flow(m, e)
    simple_seeds(pk, 'RPA', [('BOT-OPERATOR', 'OPERATOR', None, 'Bot operator', 'बॉट संचालक')], 'u20')


def up21():
    pk = upack(21, 'iot-storm-control', 'IoT / event-triggered process with storm control', 'आईओटी / घटना-प्रेरित प्रक्रिया एवं तूफ़ान नियंत्रण',
               'An alarm starts one case per device; repeats correlate to it (de-duplicated, bounded), a debounce '
               'window waits, a threshold decides whether to dispatch, and a clear event closes it automatically '
               '(R83, R40).', ['R40', 'R83', 'GP-15', 'GP-17'], depends=[])
    p = pk.proc('sangam-up21-iot-alarm', 'UP-21 Device alarm', '', ttl=30, start={'materialFields': 'deviceId'})
    s = p.root
    st = s.start('u21_start', 'Alarm (per device business key)', msg='sangam.iot.alarm', initiator=None)
    d = s.setv('u21_defaults', 'Configuration', defaults='debounce=PT2M;threshold=3;fieldSeat=FIELD-CREW')
    add = s.svc('u21_add', 'Record alarm', C['EVWIN'], {'op': 'ADD'})
    w = s.egw('u21_wait', 'Repeat, clear or debounce end')
    s.seq(st, d, add, w)
    rep = s.catch_msg('u21_repeat', 'Repeat alarm', 'sangam.iot.alarm')
    clr = s.catch_msg('u21_clear', 'Clear', 'sangam.iot.clear')
    db = s.catch_timer('u21_debounce', 'Debounce window', duration='${debounce}')
    for x in (rep, clr, db):
        s.flow(w, x)
    add2 = s.svc('u21_add2', 'Record repeat', C['EVWIN'], {'op': 'ADD'})
    s.seq(rep, add2, w)
    s.flow(clr, s.end('u21_endCleared', 'Auto-closed on clear'))
    ev = s.svc('u21_eval', 'Threshold', C['EVWIN'], {'op': 'EVALUATE'})
    g = s.xgw('u21_gw', 'Dispatch?')
    s.seq(db, ev, g)
    s.flow(g, s.end('u21_endNoise', 'Below threshold'), '!thresholdReached', 'noise')
    wo = s.user('u21_dispatch', 'Field maintenance | क्षेत्र रखरखाव', '${fieldSeat}', form='up21-iot-storm-control/dispatch',
                decision=None, fields=['findings*', 'photos'])
    s.flow(g, wo, None, 'dispatch', default=True)
    cb = s.boundary('u21_clearedDuring', wo, 'Cleared during work', msg='sangam.iot.clear')
    s.flow(cb, s.end('u21_endClearedWork', 'Cleared'))
    s.flow(wo, s.end('u21_end', 'Repaired'))
    simple_seeds(pk, 'FIELD', [('FIELD-CREW', 'CREW', None, 'Field crew', 'क्षेत्रीय दल')], 'u21')


def up22():
    pk = upack(22, 'external-collaboration', 'External-party collaboration (supplier onboarding)', 'बाह्य पक्ष सहयोग (आपूर्तिकर्ता पंजीकरण)',
               'A supplier without an internal account is invited with a scoped one-time link (external principal), '
               'submits documents in a portal task, internal review with clarification rounds, vendor creation '
               '(R19, R67, R76; the citizen screens C01-C06 are the government analogue).', ['R19', 'R67', 'R76', 'GP-16'],
               [('portal.inviteExternal', 'a', '', 'Magic-link invitation'), ('vendor.create', 'a', '', 'Vendor master')])
    p = pk.proc('sangam-up22-supplier-onboarding', 'UP-22 Supplier onboarding', '',
                start={'materialFields': 'supplierName,applicantRef'})
    s = p.root
    st = s.start('u22_start', 'Supplier nominated', form='up22-external-collaboration/nominate')
    p.forms.append(dict(key='up22-external-collaboration/nominate', name='Nominate supplier | आपूर्तिकर्ता नामित करें',
                        decision=None, fields=['supplierName*', 'applicantRef*'], doc=None))
    d = s.setv('u22_defaults', 'Configuration', defaults='reviewerSeat=VENDOR-DESK;supplierSla=P15D')
    grp = s.expr('u22_group', 'External principal group', "${execution.setVariable('externalGroup', 'external:'.concat(applicantRef))}")
    inv = s.conn('u22_invite', 'Send scoped one-time link', 'portal.inviteExternal', 'invite', 'submit',
                 'supplierName,applicantRef,externalGroup', 'invite', step='invite')
    se, sd = s.sla_task('u22_submit', 'Supplier submits documents | आपूर्तिकर्ता दस्तावेज़ जमा करे', 'externalGroup',
                        form='up22-external-collaboration/supplier', decision=None, fields=['documents*', 'remarks'],
                        spec='${supplierSla}', on_breach='ESCALATE', max_esc=0)
    s.seq(st, d, grp, inv, se)
    rv = s.user('u22_review', 'Review supplier documents | आपूर्तिकर्ता दस्तावेज़ों की समीक्षा', '${reviewerSeat}',
                form='up22-external-collaboration/review', decision=('vendorDecision', ['accept', 'clarify', 'reject']),
                fields=['remarks*'])
    s.flow(sd, rv)
    g = s.xgw('u22_gw')
    s.flow(rv, g)
    s.flow(g, se, "vendorDecision == 'clarify'", 'clarify')
    vc = s.conn('u22_create', 'Create vendor', 'vendor.create', 'create', 'submit', 'supplierName', 'vendor', step='vendor')
    s.flow(g, vc, "vendorDecision == 'accept'", 'accept')
    s.flow(vc, end_ok(p, s, 'u22_end', 'Onboarded'))
    s.flow(g, s.end('u22_endRejected', 'Rejected'), None, 'reject', default=True)
    simple_seeds(pk, 'PROC', [('VENDOR-DESK', 'VENDOR', None, 'Vendor management desk', 'विक्रेता प्रबंधन डेस्क')], 'u22')


def build():
    for f in (up01, up02, up03, up04, up05, up06, up07, up08, up09, up10, up11, up12, up13, up14, up15, up16, up17, up18,
              up19, up20, up21, up22):
        f()
