# SPDX-License-Identifier: Apache-2.0
"""Worldwide public financial management (SL-33): the generic commitment, verification and payment chain of
spike/results/INTERNATIONAL-PFM-REQUIREMENTS.md (PFM-CH-2 commitment and PFM-CH-3 verification and payment; PFM-034
commitment control, PFM-039 breach, PFM-041 segregation of duties, PFM-048 certifying officer, PFM-049 payee checks,
PFM-050 rail with UNKNOWN_OUTCOME, PFM-051 reconciliation). Country rules are data: the delegation and the payment
mode are SYNTHETIC placeholders (readiness fails until a country deploys confirmed overrides), the currency is the
pack's (money in integer minor units, money.py); a tenant with another currency overrides the DMN in its units.
"""
from lib import C, q
from money import M  # noqa: F401
from packs import Pack
from common import core, outcome, end_ok, rejection
from gov2 import simple_seeds
from sector import powers, authority

import forms

forms.L.update({k: v for k, v in {
    'spendingUnit': ('Spending unit', 'व्यय इकाई', 'string'),
    'budgetLine': ('Budget line (classification)', 'बजट पंक्ति (वर्गीकरण)', 'string'),
    'commitmentType': ('Commitment type (ordinary, estimative, global)', 'प्रतिबद्धता प्रकार', 'select'),
    'verificationNote': ('Goods / services received and verified', 'वस्तु / सेवा प्राप्त एवं सत्यापित', 'text'),
    'certificationNote': ('Certification (certifying officer, personal liability recorded)',
                          'प्रमाणन (प्रमाणन अधिकारी, व्यक्तिगत दायित्व दर्ज)', 'text'),
    'breachReport': ('Breach report to the configured authorities', 'उल्लंघन रिपोर्ट', 'text'),
}.items() if k not in forms.L})
forms.OPT.update({k: v for k, v in {'certify': ('Certify', 'प्रमाणित करें')}.items() if k not in forms.OPT})

COVERS = ['SL-33', 'PFM-030', 'PFM-034', 'PFM-039', 'PFM-041', 'PFM-048', 'PFM-049', 'PFM-050', 'PFM-051',
          'PFM-CH-2', 'PFM-CH-3', 'GP-01', 'GP-11', 'GP-34']


def execution():
    pk = Pack('gov.intl.pfm-execution', 'gov-intl-pfm-execution', 'Commitment, verification and payment (worldwide PFM)',
              'प्रतिबद्धता, सत्यापन एवं भुगतान (अंतरराष्ट्रीय सार्वजनिक वित्त)', 'Public finance (international)',
              'Generic expenditure chain: commitment only against available released authority (encumbered), '
              'commitment approver by delegation (DMN); verification of receipt by a person other than the committer; '
              'certifying officer with liability recorded; payee integrity check; payment on the profile rail with '
              'operationId, UNKNOWN_OUTCOME and reconciliation; a shortfall of authority opens the breach report.',
              COVERS, connectors=[('ledger.available', 'a', '', 'Available released authority'),
                                  ('ledger.encumber', 'a', '', 'Record the commitment'),
                                  ('payee.integrity', 'a', '', 'Payee checks (exclusion / deceased / duplicate)'),
                                  ('treasury.pay', 'c', '', 'Payment rail'),
                                  ('bank.statement', 'a', '', 'Bank / TSA statement')],
              currency='USD')
    p = pk.proc('sangam-intl-pfm-execution', 'Commitment to payment', '',
                start={'materialFields': 'spendingUnit,budgetLine,amount,payee'})
    s = p.root
    st = s.start('pe_start', 'Commitment requested', form='gov-intl-pfm-execution/request')
    p.forms.append(dict(key='gov-intl-pfm-execution/request', name='Commitment request | प्रतिबद्धता अनुरोध',
                        decision=None, fields=['spendingUnit*', 'budgetLine*', 'commitmentType', 'amount*', 'payee*',
                                               'description'], doc=None))
    d = s.setv('pe_defaults', 'Configuration',
               defaults='spendingUnit=SU-1;budgetLine=GEN-01;commitmentType=ORDINARY;amount=0;payee=;ledgerId=BUDGET;'
                        'fundsAvailable=0;payeeClear=true;caseType=COMMITMENT;'
                        'routingDecisionKey=intl-pfm-commitment-authority;powersDecisionKey=intl-pfm-powers;'
                        'scrutinySeat=BUDGET-HOLDER;executionConnector=treasury.pay;executionStep=pay;'
                        'reconcileRequired=true;reconcileConnector=bank.statement;'
                        'executionPayloadFields=payee,amount,budgetLine;outputTemplate=payment-advice;'
                        'appealAllowed=false;verifierSeat=VERIFIER;certifierSeat=CERTIFYING-OFFICER;'
                        'budgetOfficeSeat=BUDGET-OFFICE')
    av = s.conn('pe_available', 'Available released authority (PFM-034)', 'ledger.available', 'available', 'query',
                'ledgerId,budgetLine,spendingUnit', 'funds')
    s.seq(st, d, av)
    ag = s.xgw('pe_fundsGw', 'Authority available?')
    s.flow(av, ag)
    br = s.user('pe_breach', 'Breach: report to the configured authorities (PFM-039) | उल्लंघन रिपोर्ट',
                '${budgetOfficeSeat}', form='gov-intl-pfm-execution/breach', decision=None, fields=['breachReport*'])
    s.flow(ag, br, 'fundsAvailable < amount', 'short of authority')
    s.flow(br, s.end('pe_endBreach', 'Breach reported, no commitment'))
    sc = core(s, 'pe_scrutiny', 'Budget holder scrutiny (core)', 'scrutiny')
    s.flow(ag, sc, None, 'available', default=True)
    sn = core(s, 'pe_commit', 'Commitment approver (core sanction, delegation DMN)', 'sanction')
    rej = rejection(p, s, 'pe_rj')
    outcome(s, 'pe_scGw', 'scrutinyOutcome', {'ACCEPTED': sn}, rej)
    s.flow(sc, 'pe_scGw')
    enc = s.conn('pe_encumber', 'Record the commitment (encumbrance)', 'ledger.encumber', 'encumber', 'submit',
                 'ledgerId,budgetLine,spendingUnit,amount', 'enc', step='commit')
    outcome(s, 'pe_snGw', 'sanctionOutcome', {'SANCTIONED': enc}, rej)
    s.flow(sn, 'pe_snGw')
    vf = s.user('pe_verify', 'Verify receipt (not the committer, PFM-041) | प्राप्ति का सत्यापन', '${verifierSeat}',
                form='gov-intl-pfm-execution/verify', decision=('verifyDecision', ['approve', 'reject']),
                fields=['verificationNote*'], sod=True, sod_steps='sn_sanction')
    s.flow(enc, vf)
    vg = s.xgw('pe_verifyGw')
    s.flow(vf, vg)
    s.flow(vg, rej, "verifyDecision == 'reject'", 'not received')
    pc = s.conn('pe_payee', 'Payee integrity (PFM-049)', 'payee.integrity', 'check', 'query', 'payee', 'payee')
    s.flow(vg, pc, None, 'verified', default=True)
    pg = s.xgw('pe_payeeGw', 'Payee clear?')
    s.flow(pc, pg)
    s.flow(pg, rej, '!payeeClear', 'payee excluded')
    cf = s.user('pe_certify', 'Certify the payment order (PFM-048) | भुगतान आदेश प्रमाणित करें', '${certifierSeat}',
                form='gov-intl-pfm-execution/certify', decision=('certifyDecision', ['certify', 'reject']),
                fields=['certificationNote*'], sod=True, sod_steps='pe_verify,sn_sanction')
    s.flow(pg, cf, None, 'clear', default=True)
    cg = s.xgw('pe_certGw')
    s.flow(cf, cg)
    s.flow(cg, rej, "certifyDecision == 'reject'", 'not certified')
    ex = core(s, 'pe_pay', 'Payment (core execution, class c)', 'execution')
    s.flow(cg, ex, None, 'certified', default=True)
    cl = core(s, 'pe_close', 'Advice and closure (core)', 'closure')
    fail = s.notify('pe_failed', 'Payment failed on the rail', 'payment-failed', 'inapp', 'initiatorSeat')
    outcome(s, 'pe_exGw', 'executionOutcome', {'DONE': cl}, fail)
    s.flow(ex, 'pe_exGw')
    s.flow(fail, s.end('pe_endFailed', 'Payment failed'))
    s.flow(cl, end_ok(p, s, 'pe_end', 'Paid'))
    s.side_exits('pe')
    authority(pk, 'intl-pfm-commitment-authority', 'Commitment approver (SYNTHETIC delegation)',
              [('<= 100000', 'COMMITMENT-OFFICER', ''), ('-', 'ACCOUNTING-OFFICER', '')])
    powers(pk, 'intl-pfm-powers', [('COMMITMENT-OFFICER', 100000, 'SYNTHETIC'),
                                   ('ACCOUNTING-OFFICER', 10000000, 'SYNTHETIC')],
           doc='SYNTHETIC delegation in major units of the pack currency (shipped in minor units); each country '
               'deploys its own (INTERNATIONAL-PFM-REQUIREMENTS PFM-CH-2, unverified per country).')
    for dk in ('intl-pfm-commitment-authority', 'intl-pfm-powers'):
        pk.placeholder(dk, 'SYNTHETIC delegation (100000 / 10000000, major units)',
                       'country delegation of commitment powers not verified: deploy the confirmed table as a tenant '
                       'override (INTERNATIONAL-PFM-REQUIREMENTS section 5)')
    simple_seeds(pk, 'MINISTRY', [
        ('BUDGET-HOLDER', 'BUDGETHOLDER', 'ACCOUNTING-OFFICER', 'Budget holder', 'बजट धारक'),
        ('COMMITMENT-OFFICER', 'COMMITMENT-OFFICER', 'ACCOUNTING-OFFICER', 'Commitment officer', 'प्रतिबद्धता अधिकारी'),
        ('ACCOUNTING-OFFICER', 'ACCOUNTING-OFFICER', None, 'Accounting officer', 'लेखा अधिकारी'),
        ('VERIFIER', 'VERIFIER', 'ACCOUNTING-OFFICER', 'Receiving and verifying officer', 'सत्यापन अधिकारी'),
        ('CERTIFYING-OFFICER', 'CERTIFIER', 'ACCOUNTING-OFFICER', 'Certifying officer', 'प्रमाणन अधिकारी'),
        ('BUDGET-OFFICE', 'BUDGETOFFICE', None, 'Budget office', 'बजट कार्यालय')], 'ipf')
    pk.accept('PFM-CH-3', 'Commitment within authority is verified, certified and paid by three persons',
              ['PFM-CH-2', 'PFM-CH-3', 'PFM-041'], 'amount = 50,000 with authority available',
              'commitment approved, receipt verified, order certified', 'paid and reconciled; three different persons',
              vars={'amount': 50000, 'fundsAvailable': 1000000})


def build():
    execution()
