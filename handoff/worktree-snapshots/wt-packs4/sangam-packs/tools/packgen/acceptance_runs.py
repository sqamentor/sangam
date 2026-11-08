# SPDX-License-Identifier: Apache-2.0
"""How each pack acceptance scenario is executed by PackAcceptanceTest (SL-32), keyed by (pack id, scenario id).

Written into pack.json as acceptance[].run:
  process   process key to start (default: the pack's first main process)
  answers   {task key: {'vars': {...}, 'person': id, 'times': n}}: the scenario's own answers, over FormAnswers
  stopAt    stop when a task with this key opens (the expectation is about that point)
  expect    {'tasks': [...] in order, 'audit': [...], 'notAudit': [...], 'ended': bool, 'openTask': key,
             'refusedAt': {'task': key, 'code': text}}
  manual    why the scenario cannot be driven by configuration answers here (it needs a clock, a message from
            another system, or steering the runner does not model); the test lists these
Every scenario needs expectState, expect or manual, or the build fails."""

CLOCK = 'needs a clock driver: '
STEER = 'needs scenario steering beyond configuration answers: '

RUNS = {
    ('sangam.core', 'CORE-1'): dict(manual=STEER + 'level 3 returns the case to level 1 inside the core blocks'),
    ('sangam.core', 'CORE-2'): dict(manual=CLOCK + 'the sanction SLA lapses (deemed decision)'),
    ('sangam.core', 'CORE-3'): dict(manual=STEER + 'an operator action on an unknown-outcome item'),
    ('gov.dbt.batch', 'GP-49'): dict(manual=STEER + 'the bank desk corrects items and the cell resends round 2'),
    ('gov.ifms.bill', 'BL-1'): dict(expect=dict(audit=['CASE_REJECTED'], notAudit=['CASE_APPROVED'], ended=True)),
    ('gov.ifms.pao-chain', 'PC-1'): dict(expect=dict(tasks=['pc_pao', 'pc_second'], ended=True)),
    ('gov.p1.bill-payment', 'AC-01'): dict(expect=dict(tasks=['sc_scrutiny', 'sn_sanction'],
                                                       audit=['RULE_EVALUATED', 'APPROVAL_RECORDED', 'CASE_RECONCILED'])),
    ('gov.p1.bill-payment', 'AC-02'): dict(manual=STEER + 'the amount is changed between approval and payment'),
    ('gov.p1.bill-payment', 'AC-03'): dict(expect=dict(audit=['EXT_OP_UNKNOWN', 'RECON_ITEM_OPENED'],
                                                       notAudit=['CASE_CLOSED', 'EXT_OP_ACKNOWLEDGED'], ended=False)),
    ('gov.p1.bill-payment', 'J-P1-E11'): dict(stopAt='p1_revise',
                                              expect=dict(openTask='p1_revise', notAudit=['CASE_IN_SANCTION'])),
    ('gov.p1.bill-payment', 'J-P1-E09'): dict(manual=STEER + 'two joint approvers act on the same payload'),
    ('gov.p2.file-movement', 'AC-04'): dict(expect=dict(tasks=['sn_sanction', 'p2_dispatch'],
                                                        audit=['APPROVAL_RECORDED', 'CASE_CLOSED'])),
    ('gov.p2.file-movement', 'J-P2-E04'): dict(manual=STEER + 'one person acts on two levels of the same file'),
    ('gov.p3.grant-sanction', 'AC-05'): dict(expect=dict(tasks=['sc_scrutiny', 'sn_sanction'], audit=['CASE_CLOSED'])),
    ('gov.p3.grant-sanction', 'J-P3-E01'): dict(expect=dict(audit=['CASE_REJECTED'], notAudit=['CASE_IN_SANCTION'])),
    ('gov.p4.licence', 'AC-06'): dict(manual=CLOCK + 'the payment status lookup and the overdue approval stage'),
    ('gov.p4.licence', 'J-P4-E08'): dict(expect=dict(audit=['EXT_OP_FAILED', 'DOCUMENT_ISSUED', 'CASE_CLOSED'])),
    ('gov.recruitment', 'GP-47'): dict(manual=STEER + 'challenges accepted by the expert panel (round 2 scores)'),
    ('gov.recruitment', 'GP-28'): dict(manual=STEER + 'a selected candidate declines the offer'),
    ('gov.rti', 'GP-44'): dict(manual=CLOCK + 'the PIO decision deadline lapses (deemed refusal)'),
    ('sec.bank.account-opening', 'SEC-BANK-AO-1'): dict(manual=STEER + 'the V-CIP official records reschedule'),
    ('sec.bank.account-opening', 'SEC-BANK-AO-2'): dict(
        answers={'ao_compliance': {'vars': {'screenDecision': 'reject'}}},
        expect=dict(tasks=['ao_compliance'], audit=['CASE_REJECTED'], notAudit=['CASE_APPROVED'])),
    ('sec.eu.gdpr-dsr', 'SEC-EU-GDPR-1'): dict(expect=dict(tasks=['gd_verify', 'gd_decide', 'gd_erasure', 'gd_respond'],
                                                          ended=True)),
    ('gov.intl.pfm-execution', 'PFM-CH-3'): dict(expect=dict(audit=['CASE_CLOSED'], ended=True)),
    ('sec.bank.payment-dual-auth', 'SEC-BANK-PY-1'): dict(expect=dict(tasks=['sn_sanction', 'sn_sanction'],
                                                                      audit=['CASE_CLOSED'])),
}
