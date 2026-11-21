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
from common import core, outcome, end_ok, rejection, reject_with, checked_query, check_conditions, unconfirmed_hold
from gov2 import simple_seeds
from sector import powers, authority, clock

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
                start={'materialFields': 'spendingUnit,budgetLine,amount,payee,payeeAccount,currency,purpose'})
    s = p.root
    st = s.start('pe_start', 'Commitment requested', form='gov-intl-pfm-execution/request')
    p.forms.append(dict(key='gov-intl-pfm-execution/request', name='Commitment request | प्रतिबद्धता अनुरोध',
                        decision=None, fields=['spendingUnit*', 'budgetLine*', 'commitmentType', 'amount*', 'payee*',
                                               'description'], doc=None))
    d = s.setv('pe_defaults', 'Configuration',
               defaults='spendingUnit=SU-1;budgetLine=GEN-01;commitmentType=ORDINARY;amount=0;payee=;ledgerId=BUDGET;'
                        'caseType=COMMITMENT;'
                        'routingDecisionKey=intl-pfm-commitment-authority;powersDecisionKey=intl-pfm-powers;'
                        'scrutinySeat=BUDGET-HOLDER;executionConnector=treasury.pay;executionStep=pay;'
                        'reconcileRequired=true;reconcileConnector=bank.statement;'
                        'executionPayloadFields=payee,amount,budgetLine;outputTemplate=payment-advice;'
                        'appealAllowed=false;verifierSeat=VERIFIER;certifierSeat=CERTIFYING-OFFICER;'
                        'budgetOfficeSeat=BUDGET-OFFICE')
    # SL-115: the authority and payee checks fail closed (cleared before every query; no default stands in)
    ae, av = checked_query(s, 'pe_available', 'Available released authority (PFM-034)', 'ledger.available',
                           'available', 'ledgerId,budgetLine,spendingUnit', 'funds', ['fundsAvailable'])
    s.seq(st, d, ae)
    ag = s.xgw('pe_fundsGw', 'Authority available?')
    s.flow(av, ag)
    br = s.user('pe_breach', 'Breach: report to the configured authorities (PFM-039) | उल्लंघन रिपोर्ट',
                '${budgetOfficeSeat}', form='gov-intl-pfm-execution/breach', decision=None, fields=['breachReport*'])
    funds_unconfirmed, funds_short = check_conditions([('funds', ['fundsAvailable'])], 'fundsAvailable < amount')
    s.flow(ag, reject_with(p, s, 'pe_rjFunds', 'FUNDS_NOT_CONFIRMED'), funds_unconfirmed, 'authority not confirmed')
    s.flow(ag, br, funds_short, 'short of authority')
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
                fields=['verificationNote*', 'rejectionReasonCodes'], sod=True, sod_steps='sn_sanction')
    s.flow(enc, vf)
    vg = s.xgw('pe_verifyGw')
    s.flow(vf, vg)
    s.flow(vg, rej, "verifyDecision == 'reject'", 'not received')
    pce, pc = checked_query(s, 'pe_payee', 'Payee integrity (PFM-049)', 'payee.integrity', 'check', 'payee', 'payee',
                            ['payeeClear'])
    s.flow(vg, pce, None, 'verified', default=True)
    pg = s.xgw('pe_payeeGw', 'Payee clear?')
    s.flow(pc, pg)
    payee_unconfirmed, payee_excluded = check_conditions([('payee', ['payeeClear'])], '!payeeClear')
    s.flow(pg, reject_with(p, s, 'pe_rjPayee', 'PAYEE_CHECK_NOT_CONFIRMED'), payee_unconfirmed,
           'payee check not confirmed')
    # SL-55: an automatic rejection carries its coded reason (nobody is there to give one)
    px = s.setv('pe_payeeExcluded', 'Reason: PAYEE_EXCLUDED', values='rejectionReasonCodes=PAYEE_EXCLUDED')
    s.flow(pg, px, payee_excluded, 'payee excluded')
    s.flow(px, rej)
    cf = s.user('pe_certify', 'Certify the payment order (PFM-048) | भुगतान आदेश प्रमाणित करें', '${certifierSeat}',
                form='gov-intl-pfm-execution/certify', decision=('certifyDecision', ['certify', 'reject']),
                fields=['certificationNote*', 'rejectionReasonCodes'], sod=True, sod_steps='pe_verify,sn_sanction')
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
              # SL-115: the simulated ledger and payee registry answer (a directive amount is major units, converted
              # like every amount: money.convert_directive); a start value of fundsAvailable is never trusted
              vars={'amount': 50000, 'sim_ledger_available': 'available=1000000', 'sim_payee_integrity': 'clear=true'})


# ------------------------------------------------------------------------------------------------ M-134 PFM packs
# Owner decision 108: country data comes from the deploying government through its own channel; until then every
# country value is a SYNTHETIC placeholder (pk.placeholder: readiness fails until a confirmed override is deployed),
# and no consumer data is in these files (ADR-D-20).

forms.L.update({k: v for k, v in {
    'breachKind': ('Kind of breach (over-obligation, over-expenditure, other)', 'उल्लंघन का प्रकार', 'select'),
    'breachFacts': ('Facts of the breach (limit, amount, date, how it was detected)', 'उल्लंघन के तथ्य', 'text'),
    'investigationFindings': ('Investigation findings and responsibility', 'जाँच के निष्कर्ष एवं उत्तरदायित्व', 'text'),
    'correctiveAction': ('Corrective action taken', 'सुधारात्मक कार्रवाई', 'text'),
    'suspenseAccount': ('Suspense account', 'उचंत खाता', 'string'),
    'suspenseItemRef': ('Suspense item reference', 'उचंत मद संदर्भ', 'string'),
    'postedOn': ('Date the item was posted to suspense', 'उचंत में दर्ज करने की तिथि', 'date'),
    'clearanceNote': ('Clearance: the correct head and evidence', 'निपटान: सही शीर्ष एवं साक्ष्य', 'text'),
    'targetHead': ('Correct budget / accounting head', 'सही बजट / लेखा शीर्ष', 'string'),
}.items() if k not in forms.L})
forms.OPT.update({k: v for k, v in {
    'cleared': ('Cleared to the correct head', 'सही शीर्ष में निपटाया'),
    'proposeWriteOff': ('Propose write-off', 'बट्टे खाते का प्रस्ताव'),
}.items() if k not in forms.OPT})


def breach_case():
    pk = Pack('gov.intl.pfm-breach-case', 'gov-intl-pfm-breach-case', 'Budget limit breach case (worldwide PFM)',
              'बजट सीमा उल्लंघन मामला (अंतरराष्ट्रीय सार्वजनिक वित्त)', 'Public finance (international)',
              'A breach of a budget limit is a legal event in some countries (PFM-039, PFM-CH-7): the case records the '
              'facts, a person other than the reporter investigates and records responsibility and corrective action, '
              'the accounting officer reviews, and the report goes to the authorities the country configures, within '
              'the report deadline from DMN. Authorities and deadline are SYNTHETIC until the country confirms them.',
              ['PFM-039', 'PFM-CH-7', 'GP-14'],
              connectors=[('authority.report', 'b', '', 'Report to the configured authorities')], currency='USD')
    p = pk.proc('sangam-intl-pfm-breach-case', 'Budget limit breach case', '',
                start={'materialFields': 'spendingUnit,budgetLine,amount,breachKind'})
    s = p.root
    st = s.start('bc_start', 'Breach detected', form='gov-intl-pfm-breach-case/report')
    p.forms.append(dict(key='gov-intl-pfm-breach-case/report', name='Breach detected | उल्लंघन पाया गया', decision=None,
                        fields=['spendingUnit*', 'budgetLine*', 'amount*', 'breachKind*', 'breachFacts*'], doc=None))
    d = s.setv('bc_defaults', 'Configuration',
               defaults='spendingUnit=SU-1;budgetLine=GEN-01;amount=0;breachKind=OTHER;'
                        'investigatorSeat=BREACH-INVESTIGATOR;accountingSeat=ACCOUNTING-OFFICER;'
                        'budgetOfficeSeat=BUDGET-OFFICE')
    r = s.rule('bc_cfg', 'Breach reporting rule (DMN)', 'intl-pfm-breach-reporting')
    dl = clock(s, 'bc_deadline', 'breachReport', '${breachReportSpec}')
    inv = s.user('bc_investigate', 'Investigate: findings, responsibility and corrective action | जाँच',
                 '${investigatorSeat}', form='gov-intl-pfm-breach-case/investigate', decision=None,
                 fields=['breachFacts', 'investigationFindings*', 'correctiveAction*'], due='${breachReportDueAt}')
    s.seq(st, d, r, dl, inv)
    rv = s.user('bc_review', 'Review the breach report (accounting officer) | उल्लंघन रिपोर्ट की समीक्षा',
                '${accountingSeat}', form='gov-intl-pfm-breach-case/review', decision=('reviewDecision', ['approve', 'reject']),
                fields=['investigationFindings', 'correctiveAction', 'remarks'], sod=True, sod_steps='bc_investigate',
                due='${breachReportDueAt}')
    s.flow(inv, rv)
    rg = s.xgw('bc_reviewGw')
    s.flow(rv, rg)
    s.flow(rg, inv, "reviewDecision == 'reject'", 'investigate again')
    re_, rp = checked_query(s, 'bc_send', 'Report to the configured authorities', 'authority.report', 'report',
                            'breachAuthorities,breachKind,breachFacts,investigationFindings,correctiveAction', 'report',
                            ['reportReceiptRef'], mode='submit', step='breach-report')
    s.flow(rg, re_, None, 'approved', default=True)
    sg = s.xgw('bc_sentGw', 'Report received?')
    s.flow(rp, sg)
    sent_unconfirmed, _ = check_conditions([('report', ['reportReceiptRef'])])
    s.flow(sg, unconfirmed_hold(p, s, 'bc_sendHold', 'Report not confirmed by the authority: confirm and resend | '
                                'रिपोर्ट की पुष्टि नहीं', '${budgetOfficeSeat}', re_), sent_unconfirmed, 'not confirmed')
    s.flow(sg, end_ok(p, s, 'bc_end', 'Breach reported', outcome='REPORTED', data=['reportReceiptRef']), None,
           'reported', default=True)
    s.side_exits('bc')
    pk.dmn('intl-pfm-breach-reporting', 'Breach reporting rule (SYNTHETIC: authorities and deadline per country)', 'FIRST',
           [('Kind of breach', 'breachKind', 'string')],
           [('breachAuthorities', 'Authorities to report to', 'string'), ('breachReportSpec', 'Report due within', 'string')],
           [(['-'], [q('CONFIGURE-PER-COUNTRY'), q('30D')], 'SYNTHETIC')],
           doc='PFM-039, PFM-CH-7. The authorities and the deadline are country law: deploy the confirmed table as a '
               'tenant override.')
    pk.placeholder('intl-pfm-breach-reporting', 'SYNTHETIC authorities and 30-day deadline',
                   'country breach-reporting authorities and deadline not verified (INTERNATIONAL-PFM-REQUIREMENTS '
                   'PFM-039, section 5)')
    simple_seeds(pk, 'MINISTRY', [
        ('BREACH-INVESTIGATOR', 'INVESTIGATOR', 'ACCOUNTING-OFFICER', 'Breach investigator', 'उल्लंघन जाँच अधिकारी'),
        ('ACCOUNTING-OFFICER', 'ACCOUNTING-OFFICER', None, 'Accounting officer', 'लेखा अधिकारी'),
        ('BUDGET-OFFICE', 'BUDGETOFFICE', None, 'Budget office', 'बजट कार्यालय')], 'ibc')


def suspense_ageing():
    pk = Pack('gov.intl.pfm-suspense-ageing', 'gov-intl-pfm-suspense-ageing', 'Suspense item clearance and ageing (worldwide PFM)',
              'उचंत मद निपटान एवं आयु निगरानी (अंतरराष्ट्रीय सार्वजनिक वित्त)', 'Public finance (international)',
              'A suspense item (PFM-051, PEFA PI-27) is cleared to the correct head within the clearance period from '
              'DMN; the period is guarded by an SLA that escalates; an item that cannot be cleared becomes a write-off '
              'proposal that another officer approves; the reclassification is posted through the ledger and is '
              'confirmed before the case closes. Periods are SYNTHETIC until the country confirms them.',
              ['PFM-051', 'GP-06', 'GP-14'],
              connectors=[('ledger.reclassify', 'a', '', 'Reclassify the item from suspense to the correct head')],
              currency='USD')
    p = pk.proc('sangam-intl-pfm-suspense-ageing', 'Suspense item clearance', '',
                start={'materialFields': 'suspenseAccount,suspenseItemRef,amount'})
    s = p.root
    st = s.start('su_start', 'Item posted to suspense', form='gov-intl-pfm-suspense-ageing/item')
    p.forms.append(dict(key='gov-intl-pfm-suspense-ageing/item', name='Suspense item | उचंत मद', decision=None,
                        fields=['suspenseAccount*', 'suspenseItemRef*', 'amount*', 'postedOn*', 'description'], doc=None))
    d = s.setv('su_defaults', 'Configuration',
               defaults='suspenseAccount=SUSPENSE;suspenseItemRef=@customerId;amount=0;ledgerId=ACCOUNTS;'
                        'clearingSeat=SUSPENSE-CLERK;accountingSeat=ACCOUNTING-OFFICER')
    r = s.rule('su_cfg', 'Suspense clearance period (DMN)', 'intl-pfm-suspense-ageing')
    ce, cx = s.sla_task('su_clear', 'Clear the suspense item to the correct head | उचंत मद का निपटान', 'clearingSeat',
                        form='gov-intl-pfm-suspense-ageing/clear', decision=('clearDecision', ['cleared', 'proposeWriteOff']),
                        fields=['targetHead*', 'clearanceNote*'], spec='${suspenseClearSpec}')
    s.seq(st, d, r, ce)
    cg = s.xgw('su_clearGw')
    s.flow(cx, cg)
    wo = s.user('su_writeOff', 'Approve the write-off proposal (not the proposer) | बट्टे खाते का अनुमोदन',
                '${accountingSeat}', form='gov-intl-pfm-suspense-ageing/write-off',
                decision=('writeOffDecision', ['approve', 'reject']), fields=['clearanceNote', 'remarks*'], sod=True,
                sod_steps='su_clear')
    s.flow(cg, wo, "clearDecision == 'proposeWriteOff'", 'write-off proposed')
    wg = s.xgw('su_writeOffGw')
    s.flow(wo, wg)
    s.flow(wg, ce, "writeOffDecision == 'reject'", 'clear it instead')
    pe, po = checked_query(s, 'su_post', 'Reclassify from suspense (ledger)', 'ledger.reclassify', 'reclassify',
                           'ledgerId,suspenseAccount,suspenseItemRef,targetHead,amount,clearDecision', 'reclass',
                           ['reclassEntryRef'], mode='submit', step='reclassify')
    s.flow(cg, pe, None, 'cleared', default=True)
    s.flow(wg, pe, None, 'write-off approved', default=True)
    pg = s.xgw('su_postGw', 'Posted?')
    s.flow(po, pg)
    post_unconfirmed, _ = check_conditions([('reclass', ['reclassEntryRef'])])
    s.flow(pg, unconfirmed_hold(p, s, 'su_postHold', 'Reclassification not confirmed: check the ledger | '
                                'पुनर्वर्गीकरण की पुष्टि नहीं', '${accountingSeat}', pe), post_unconfirmed, 'not confirmed')
    s.flow(pg, end_ok(p, s, 'su_end', 'Suspense item cleared', outcome='CLEARED', data=['reclassEntryRef']), None,
           'posted', default=True)
    s.side_exits('su')
    pk.dmn('intl-pfm-suspense-ageing', 'Suspense clearance period (SYNTHETIC; per country)', 'FIRST',
           [('Suspense account', 'suspenseAccount', 'string')],
           [('suspenseClearSpec', 'Clear within', 'string')],
           [(['-'], [q('30D')], 'SYNTHETIC')],
           doc='PFM-051 (PEFA PI-27.2 suspense clearance). Deploy the confirmed period as a tenant override.')
    pk.placeholder('intl-pfm-suspense-ageing', 'SYNTHETIC 30-day clearance period',
                   'country suspense clearance period not verified (INTERNATIONAL-PFM-REQUIREMENTS PFM-051, section 5)')
    simple_seeds(pk, 'TREASURY', [
        ('SUSPENSE-CLERK', 'SUSPENSECLERK', 'ACCOUNTING-OFFICER', 'Suspense clearing clerk', 'उचंत निपटान लिपिक'),
        ('ACCOUNTING-OFFICER', 'ACCOUNTING-OFFICER', None, 'Accounting officer', 'लेखा अधिकारी')], 'isu')


def build():
    execution()
    breach_case()
    suspense_ageing()
