# SPDX-License-Identifier: Apache-2.0
"""How each pack acceptance scenario is executed by PackAcceptanceTest (SL-32), keyed by (pack id, scenario id).

Written into pack.json as acceptance[].run:
  process   process key to start (default: the pack's first main process)
  answers   {task key: {'vars': {...}, 'person': id, 'times': n}}: the scenario's own answers, over FormAnswers
  steps     [step, ...]: the scenario's "when" that configuration answers cannot express, applied in order. A step
            fires when activity `at` of the case is waiting (an open user task, an asynchronous job, a timer or a
            message subscription; for a user task, the n-th time it is open, n default 1). Exactly one of:
              {'at': id, 'advance': ISO-8601 duration}  offset the scenario clock (engine and pack clock) by the
                                                         duration, then run the timers that are due
              {'at': id, 'action': 'complete', 'n': k, 'vars': {...}, 'person': id, 'seat': id}
                                                         complete the k-th occurrence of user task `at` with these
                                                         values over FormAnswers (return, decline, reschedule ...)
              {'at': id, 'action': 'correlate', 'message': name, 'vars': {...}}
                                                         correlate the message `at` waits for (default: the name of
                                                         its subscription) to the process instance holding `at`
              {'at': id, 'action': 'set', 'vars': {...}}  set variables on the process instance holding `at` (a
                                                         change by another actor between two steps)
              {'at': id, 'action': 'start', 'pack': id | 'process': key, 'key': k, 'vars': {...}}
                                                         start one more case of the scenario (business key
                                                         <scenario>/<k>; a value '@case' is the scenario's key)
            any step may carry 'case': k, so that `at` is looked for only in the case <scenario>/<k> (a case the
            scenario started)
            'correlate' and 'set' may carry 'refused': code: the action must be refused with that message code, and
            the case goes on (an operator retry that the platform refuses)
  stopAt    stop when a task with this key opens (the expectation is about that point)
  expect    {'tasks': [...] in order, 'audit': [...], 'notAudit': [...], 'ended': bool, 'openTask': key,
             'refusedAt': {'task': key, 'code': text}, 'vars': {name: value of the case variable at the end},
             'batchRounds': {round: [item keys of the child cases started in that round]},
             'merit': {'selected': [...], 'waitlist': [...], 'scores': {key: n}, 'allotted': {key: category},
                       'promoted': key}} (merit keys are cases the scenario started, without its prefix)
  manual    why the scenario cannot be driven here yet (it needs data, a host process or a driver capability that
            is not decided); the test lists these
Every scenario needs expectState, expect or manual, or the build fails; a malformed step fails the build."""
import re

import money

OPEN = 'open question to the integrator (B3b, SL-32): '


def _recruitment_steps(apps, challenge):
    """GP-47 / GP-28: the applications are cases of their own that join the campaign (BatchJoinListener), started
    while the application window is open; each has its own exam.score answer (a simulator directive, by score round
    where the key changes). Then the campaign's statutory periods pass: applications close (P30D), scrutiny (P15D),
    examination (P30D), the challenge window (P7D, with one paid challenge when `challenge`), the score poll (PT10M)."""
    steps = [{'at': 'rc2_closeApps', 'action': 'start', 'process': 'sangam-recruitment-application', 'key': key,
              'vars': {'campaignId': '@case', 'applicantName': 'Candidate ' + key, 'applicantRef': 'mobile:9XXXX' + key,
                       'dob': '1996-01-0' + key[-1], 'socialCategory': cat, 'qualification': 'GRADUATE',
                       'pwd': False, 'sim_exam_score': score}}
             for key, (cat, score) in apps.items()]
    steps += [{'at': 'rc2_closeApps', 'advance': 'P31D'}, {'at': 'rc2_scrutinyPeriod', 'advance': 'P16D'},
              {'at': 'rc2_exam', 'advance': 'P31D'}]
    if challenge:
        steps.append({'at': 'rc2_challenge', 'action': 'correlate'})
    steps += [{'at': 'rc2_challengeClose', 'advance': 'P8D'}, {'at': 'rc2_poll', 'advance': 'PT11M'}]
    return steps


RUNS = {
    # the core blocks run in host processes (integrator decision 2026-11-12); the host data is in core.py
    # level 3 (DS) returns to level 1; levels 1..3 again, no new case version (a return is not a resubmission)
    ('sangam.core', 'CORE-1'): dict(
        process='sangam-p2-file-movement',
        steps=[{'at': 'rc_level', 'action': 'complete', 'n': 3,
                'vars': {'recDecision': 'return', 'returnToLevel': 1, 'returnReason': 'Re-examine the figures'}}],
        expect=dict(tasks=['rc_level'] * 6 + ['sn_sanction'], audit=['CASE_RETURNED'], vars={'caseVersion': 1})),
    # the approval stage SLA (TRADE: 5WD) lapses; nobody completes sn_sanction
    ('sangam.core', 'CORE-2'): dict(
        process='sangam-p4-licence',
        steps=[{'at': 'sn_sanction', 'advance': 'P15D'}],
        expect=dict(tasks=['sc_scrutiny'], audit=['SLA_BREACHED', 'DEEMED_DECISION', 'APPROVAL_RECORDED'],
                    vars={'approvedBy': 'system', 'deemedDecision': True})),
    # PilotScenarioTest p1UnknownPaymentOutcomeIsReconciledWithoutRepeat as data
    ('sangam.core', 'CORE-3'): dict(
        process='sangam-p1-bill-payment',
        steps=[{'at': 'ex_resolved', 'action': 'correlate', 'vars': {'resolution': 'RETRY_SAME_OPERATION'},
                'refused': 'RETRY_NOT_ALLOWED'},
               {'at': 'ex_resolved', 'action': 'correlate',
                'vars': {'resolution': 'CONFIRMED_SUCCESS', 'resolutionEvidence': 'UTR123456'}}],
        expect=dict(audit=['EXT_OP_UNKNOWN', 'RECON_ITEM_OPENED', 'RECON_ITEM_RESOLVED', 'CASE_CLOSED'], ended=True)),
    # five synthesised beneficiaries ITEM-00001..5; the given's directive fails ITEM-00002 and ITEM-00004 in round 1
    # (ACCOUNT_CLOSED); the bank desk corrects both, the cell resends, round 2 carries exactly those two and pays them
    ('gov.dbt.batch', 'GP-49'): dict(
        answers={'dp_fix': {'vars': {'fixDecision': 'correct', 'correctedAccount': '50100099990001',
                                     'correctedIfsc': 'SBIN0001234'}},
                 'db_review': {'vars': {'roundDecision': 'resubmit'}}},
        steps=[{'at': 'dp_fix', 'action': 'complete', 'n': 2},       # both corrections are in
               {'at': 'db_poll', 'advance': 'PT5M'},                 # round 1 aggregates: two failures
               {'at': 'db_poll', 'advance': 'PT5M'}],                # round 2 aggregates: all paid
        expect=dict(tasks=['dp_fix', 'dp_fix', 'db_review'], audit=['BATCH_ROUND_COMPLETE'], ended=True,
                    batchRounds={'1': ['ITEM-00001', 'ITEM-00002', 'ITEM-00003', 'ITEM-00004', 'ITEM-00005'],
                                 '2': ['ITEM-00002', 'ITEM-00004']})),
    ('gov.ifms.bill', 'BL-1'): dict(expect=dict(audit=['CASE_REJECTED'], notAudit=['CASE_APPROVED'], ended=True)),
    ('gov.ifms.pao-chain', 'PC-1'): dict(expect=dict(tasks=['pc_pao', 'pc_second'], ended=True)),
    ('gov.p1.bill-payment', 'AC-01'): dict(expect=dict(tasks=['sc_scrutiny', 'sn_sanction'],
                                                       audit=['RULE_EVALUATED', 'APPROVAL_RECORDED', 'CASE_RECONCILED'])),
    # the amount changes while the approved payload waits for its signature (sn_sign is asynchronous): the payment
    # step finds a different hash, invalidates the approval and goes back to recommendation and approval
    ('gov.p1.bill-payment', 'AC-02'): dict(
        steps=[{'at': 'sn_sign', 'action': 'set', 'vars': {'amount': 480000}}],
        expect=dict(tasks=['sn_sanction', 'sn_sanction'], audit=['APPROVAL_INVALIDATED', 'CASE_CLOSED'])),
    ('gov.p1.bill-payment', 'AC-03'): dict(expect=dict(audit=['EXT_OP_UNKNOWN', 'RECON_ITEM_OPENED'],
                                                       notAudit=['CASE_CLOSED', 'EXT_OP_ACKNOWLEDGED'], ended=False)),
    # returned to the initiator with the shortfall (budget 3,10,000, bill 4,75,000), who revises the bill to
    # 3,00,000 and resubmits (directive amounts are major units, converted like every amount: money.convert_directive)
    ('gov.p1.bill-payment', 'J-P1-E11'): dict(
        answers={'p1_revise': {'vars': {'p1_reviseDecision': 'resubmit', 'amount': 300000}, 'times': 1}},
        expect=dict(tasks=['p1_revise', 'sn_sanction'], audit=['CASE_CLOSED'])),
    ('gov.p1.bill-payment', 'J-P1-E09'): dict(manual='domain question (owner / IFMS, integrator 2026-11-12): above 25,00,000 the approver is CE-ZONE and CE-ZONE is '
                                              'also a joint approval seat, so segregation of duties refuses the '
                                              'second act of the same seat (SodGuardListener)'),
    ('gov.p2.file-movement', 'AC-04'): dict(expect=dict(tasks=['sn_sanction', 'p2_dispatch'],
                                                        audit=['APPROVAL_RECORDED', 'CASE_CLOSED'])),
    # p2.us noted as US-B (recommendation level 2) and now approves as DS-B (the completion is refused, so the
    # completed-task list of the refused run is not asserted)
    ('gov.p2.file-movement', 'J-P2-E04'): dict(
        answers={'sn_sanction': {'person': 'p2.us'}},
        expect=dict(audit=['SOD_VIOLATION_BLOCKED'],
                    refusedAt={'task': 'sn_sanction', 'code': 'SOD_VIOLATION'})),
    ('gov.p3.grant-sanction', 'AC-05'): dict(expect=dict(tasks=['sc_scrutiny', 'sn_sanction'], audit=['CASE_CLOSED'])),
    ('gov.p3.grant-sanction', 'J-P3-E01'): dict(expect=dict(audit=['CASE_REJECTED'], notAudit=['CASE_IN_SANCTION'])),
    # callback lost: the fee's UNKNOWN (class b) waits out the acknowledgement window (p4: PT30M) and the status
    # lookup confirms it (SL-118); then the approval stage (TRADE: 5WD, deemed approval) is overdue
    ('gov.p4.licence', 'AC-06'): dict(
        steps=[{'at': 'ex_window', 'advance': 'PT31M'},
               {'at': 'sn_sanction', 'advance': 'P15D'}],
        expect=dict(tasks=['sc_scrutiny'],
                    audit=['EXT_OP_UNKNOWN', 'SLA_BREACHED', 'DEEMED_DECISION', 'DOCUMENT_ISSUED', 'CASE_CLOSED'],
                    notAudit=['RECON_ITEM_OPENED'],
                    vars={'op_lookedUp': True, 'feeLastStatus': 'COMPLETED'})),
    ('gov.p4.licence', 'J-P4-E08'): dict(expect=dict(audit=['EXT_OP_FAILED', 'DOCUMENT_ISSUED', 'CASE_CLOSED'])),
    # vacancies UR 3, OBC 1, SC 1. Round 1 (provisional key): app-1 90, app-2 85, app-3 80, app-4 OBC 70, app-5 SC 60,
    # app-6 75 would select 1, 2, 3 (UR), 4 (OBC), 5 (SC) and waitlist 6. The expert panel accepts a challenge; in
    # round 2 app-6 has 95 and app-3 50, so the merit list selects 6, 1, 2 (UR), 4, 5 and waitlists 3.
    ('gov.recruitment', 'GP-47'): dict(
        steps=_recruitment_steps({'app-1': ('UR', 'score=90'), 'app-2': ('UR', 'score=85'),
                                  'app-3': ('UR', 'score=80 @ scoreRound=1 || score=50 @ scoreRound=2'),
                                  'app-4': ('OBC', 'score=70'), 'app-5': ('SC', 'score=60'),
                                  'app-6': ('UR', 'score=75 @ scoreRound=1 || score=95 @ scoreRound=2')},
                                 challenge=True),
        expect=dict(tasks=['rc2_provKey', 'rc2_expert'], audit=['MERIT_LIST_PREPARED'],
                    merit={'selected': ['app-6', 'app-1', 'app-2', 'app-4', 'app-5'], 'waitlist': ['app-3'],
                           'scores': {'app-6': 95, 'app-3': 50, 'app-1': 90}})),
    # merit list: 1, 2, 3 (UR), 4 (OBC), 5 (SC); waitlist 6 (UR, 72) before 7 (OBC, 65). The OBC candidate app-4
    # declines the offer: the OBC post goes to the next waitlisted OBC candidate, app-7 (not app-6), who is verified
    # and receives the offer
    ('gov.recruitment', 'GP-28'): dict(
        steps=_recruitment_steps({'app-1': ('UR', 'score=90'), 'app-2': ('UR', 'score=85'),
                                  'app-3': ('UR', 'score=80'), 'app-4': ('OBC', 'score=75'),
                                  'app-5': ('SC', 'score=60'), 'app-6': ('UR', 'score=72'),
                                  'app-7': ('OBC', 'score=65')}, challenge=False)
        + [{'at': 'ra_declined', 'case': 'app-4', 'action': 'correlate'}],
        expect=dict(tasks=['ra_dv'] * 6, audit=['MERIT_LIST_PREPARED', 'WAITLIST_PROMOTED'],
                    merit={'selected': ['app-1', 'app-2', 'app-3', 'app-5', 'app-7'], 'waitlist': ['app-6'],
                           'promoted': 'app-7', 'allotted': {'app-7': 'OBC'}})),
    # the PIO does not decide within the 30-day clock: deemed refusal, rejection order, appeal heard by the FAA
    ('gov.rti', 'GP-44'): dict(
        steps=[{'at': 'rt_pio', 'advance': 'P31D'},
               {'at': 'rj_appealFiled', 'action': 'correlate', 'message': 'sangam.appealFiled'}],
        stopAt='ap_hear',
        expect=dict(audit=['SLA_BREACHED', 'DEEMED_DECISION', 'CASE_REJECTED'], openTask='ap_hear')),
    ('sec.bank.account-opening', 'SEC-BANK-AO-1'): dict(
        answers={'ao_vcip': {'vars': {'vcipDecision': 'reschedule'}, 'times': 1}},
        expect=dict(tasks=['ao_vcip', 'ao_vcip', 'sn_sanction'], audit=['CASE_CLOSED'])),
    ('sec.bank.account-opening', 'SEC-BANK-AO-2'): dict(
        answers={'ao_compliance': {'vars': {'screenDecision': 'reject'}}},
        expect=dict(tasks=['ao_compliance'], audit=['CASE_REJECTED'], notAudit=['CASE_APPROVED'])),
    ('sec.eu.gdpr-dsr', 'SEC-EU-GDPR-1'): dict(expect=dict(tasks=['gd_verify', 'gd_decide', 'gd_erasure', 'gd_respond'],
                                                          ended=True)),
    ('gov.intl.pfm-execution', 'PFM-CH-3'): dict(expect=dict(audit=['CASE_CLOSED'], ended=True)),
    ('sec.bank.payment-dual-auth', 'SEC-BANK-PY-1'): dict(expect=dict(tasks=['sn_sanction', 'sn_sanction'],
                                                                      audit=['CASE_CLOSED'])),
}

RUN_KEYS = {'process', 'answers', 'steps', 'stopAt', 'expect', 'manual'}
ACTIONS = {'complete': {'n', 'vars', 'person', 'seat'}, 'correlate': {'message', 'vars', 'refused'},
           'set': {'vars', 'refused'}, 'start': {'pack', 'process', 'key', 'vars'}}
# ISO-8601 duration as java.time.Duration.parse reads it after the driver's P<n>D / PT... split
DURATION = re.compile(r'P(?:\d+D)?(?:T(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d+)?S)?)?')


def check_run(where, run):
    """Fails the generation on a malformed run: unknown keys, a step without `at`, both or neither of
    advance / action, an unknown action or field, a duration Java cannot read."""
    unknown = set(run) - RUN_KEYS
    if unknown:
        raise ValueError(f'{where}: unknown run keys {sorted(unknown)}')
    for i, st in enumerate(run.get('steps', [])):
        at = f'{where} step {i + 1}'
        if not isinstance(st.get('at'), str) or not st['at']:
            raise ValueError(f'{at}: needs an activity id in "at"')
        if ('advance' in st) == ('action' in st):
            raise ValueError(f'{at}: give exactly one of "advance" and "action"')
        if 'advance' in st:
            d = st['advance']
            if set(st) - {'case'} != {'at', 'advance'} or not isinstance(d, str) or d in ('P', 'PT') \
                    or not DURATION.fullmatch(d) or d.endswith('T'):
                raise ValueError(f'{at}: "advance" is an ISO-8601 duration (days, hours, minutes, seconds), got {d!r}')
            continue
        allowed = ACTIONS.get(st['action'])
        if allowed is None:
            raise ValueError(f'{at}: unknown action {st["action"]!r}; one of {sorted(ACTIONS)}')
        extra = set(st) - {'at', 'action', 'case'} - allowed
        if extra:
            raise ValueError(f'{at}: {st["action"]} does not take {sorted(extra)}')
        if 'n' in st and (not isinstance(st['n'], int) or isinstance(st['n'], bool) or st['n'] < 1):
            raise ValueError(f'{at}: "n" is a positive occurrence number')
        if st['action'] == 'start' and (not st.get('key') or ('pack' in st) == ('process' in st)):
            raise ValueError(f'{at}: start needs "key" and exactly one of "pack" and "process"')
        if 'vars' in st and not isinstance(st['vars'], dict):
            raise ValueError(f'{at}: "vars" is an object')
        for k, v in (st.get('vars') or {}).items():
            if k.startswith(money.SIM_PREFIX) and isinstance(v, str):
                money.parse_directive(v, f'{at} {k}')  # the simulator directive grammar


for _key, _run in RUNS.items():
    check_run(f'{_key[0]} {_key[1]}', _run)
