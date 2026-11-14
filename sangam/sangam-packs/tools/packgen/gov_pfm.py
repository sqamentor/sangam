# SPDX-License-Identifier: Apache-2.0
"""M-134, owner decision 175 (W4Q-30): the worldwide PFM packs in the owner's order, first part
(spike/results/INTERNATIONAL-PFM-REQUIREMENTS.md section 3 and 4.5; the P rows of the sangam-ledger README map):

1. budget preparation and the budget-document bundle (PFM-020, PFM-022);
2. standard reports (PFM-040);
3. cash plan (PFM-047);
4. transfer-deadline monitor (PFM-046);
5. budget-to-accounting reconciliation (PFM-055);
6. replenishment of designated accounts (PFM-062, PFM-CH-6);
7. Do-Not-Pay payee screening (PFM-049).

The G rows PFM-056, PFM-065, PFM-069 and PFM-080 follow in gov_pfm_g.py. Owner decision 108: country values come from
the deploying government through its own channel; until then every country value is a SYNTHETIC placeholder
(pk.placeholder: readiness fails until a confirmed tenant override is deployed), and no consumer data is in these files.
No pack here rounds money (decision 139): a difference is an exact SUM of whole minor units (Scope.money_sum). Every
external answer is a fail-closed check (SL-115): an unconfirmed answer holds the case for a person, never an automatic
adverse action.
"""
from lib import C, q
from packs import Pack
from common import core, end_ok, checked_query, check_conditions, unconfirmed_hold, revise
from gov2 import simple_seeds
from sector import clock

import forms

DOMAIN = 'Public finance (international)'

forms.L.update({k: v for k, v in {
    'budgetYear': ('Budget year', 'बजट वर्ष', 'string'),
    'circularRef': ('Budget circular reference', 'बजट परिपत्र संदर्भ', 'string'),
    'requestAmount': ('Amount requested', 'अनुरोधित राशि', 'money'),
    'submissionNote': ('Submission: programmes, outputs and justification', 'प्रस्तुति: कार्यक्रम, आउटपुट एवं औचित्य', 'text'),
    'reviewNote': ('Review note', 'समीक्षा टिप्पणी', 'text'),
    'appropriationActRef': ('Appropriation act / resolution reference', 'विनियोग अधिनियम / संकल्प संदर्भ', 'string'),
    'enactedAmount': ('Amount enacted', 'अधिनियमित राशि', 'money'),
    'ledgerId': ('Ledger', 'लेजर', 'string'),
    'reportPeriod': ('Reporting period', 'रिपोर्ट अवधि', 'string'),
    'reportSet': ('Report set (monthly, quarterly, annual)', 'रिपोर्ट समूह', 'select'),
    'extractDatasetRef': ('Report data set (ledger extract)', 'रिपोर्ट डेटा सेट', 'readonly'),
    'varianceNote': ('Variances: original, revised and outturn explained', 'अंतर की व्याख्या', 'text'),
    'planPeriod': ('Plan period', 'योजना अवधि', 'string'),
    'forecastRef': ('Cash forecast (ledger)', 'नकद पूर्वानुमान', 'readonly'),
    'releasePlanNote': ('Release plan: monthly / weekly releases and borrowing need', 'निर्मुक्ति योजना', 'text'),
    'collectionChannel': ('Collection channel (agent bank, portal)', 'संग्रह माध्यम', 'string'),
    'collectionRef': ('Collection reference', 'संग्रह संदर्भ', 'string'),
    'collectionAmount': ('Amount collected', 'संग्रहित राशि', 'money'),
    'collectedOn': ('Date of collection', 'संग्रह तिथि', 'date'),
    'mismatchNote': ('Difference between collection and TSA credit: cause and action', 'अंतर का कारण एवं कार्रवाई', 'text'),
    'reconPeriod': ('Reconciliation period', 'मिलान अवधि', 'string'),
    'basisDifferenceNote': ('Basis differences (cash and accrual)', 'आधार अंतर', 'text'),
    'timingDifferenceNote': ('Timing differences', 'समय अंतर', 'text'),
    'entityDifferenceNote': ('Entity differences', 'इकाई अंतर', 'text'),
    'projectId': ('Project / financing agreement', 'परियोजना / वित्तपोषण समझौता', 'string'),
    'designatedAccount': ('Designated account', 'नामित खाता', 'string'),
    'claimAmount': ('Amount of the withdrawal application', 'आहरण आवेदन की राशि', 'money'),
    'soeRef': ('Statement of expenditure reference', 'व्यय विवरण संदर्भ', 'string'),
    'differenceNote': ('Difference: ineligible or partly paid amounts', 'अंतर: अपात्र या आंशिक भुगतान', 'text'),
    'payeeIdentifier': ('Payee identifier (tax / registration number)', 'प्राप्तकर्ता पहचानकर्ता', 'string'),
    'paymentRef': ('Payment reference', 'भुगतान संदर्भ', 'string'),
    'matchEvidence': ('Evidence: why the list entry is or is not this payee', 'साक्ष्य: सूची प्रविष्टि का मिलान', 'text'),
    'ceilingAmount': ('Ceiling (ledger)', 'सीमा (लेजर)', 'readonly'),
    'creditAmount': ('Amount credited (bank / TSA)', 'जमा राशि', 'readonly'),
    'outturnAmount': ('Budget outturn (budget basis)', 'बजट परिणाम (बजट आधार)', 'readonly'),
    'accountingAmount': ('Expense (accounting basis)', 'व्यय (लेखा आधार)', 'readonly'),
    'differenceAmount': ('Difference (exact)', 'अंतर', 'readonly'),
}.items() if k not in forms.L})
forms.OPT.update({k: v for k, v in {
    'trueMatch': ('Same person: block the payment', 'वही व्यक्ति: भुगतान रोकें'),
    'falsePositive': ('Different person: clear the payee', 'भिन्न व्यक्ति: प्राप्तकर्ता स्वीकृत'),
}.items() if k not in forms.OPT})


def _hold(p, s, id, name, seat, back_to):
    """SL-115: an unconfirmed check result holds the case for the accountable person, who confirms the answer at its
    source; the check then runs again."""
    return unconfirmed_hold(p, s, id, name, seat, back_to)


def _check(p, s, id, name, connector, operation, inputs, prefix, results, seat, hold_name, mode='query', step=None):
    """A fail-closed check: (entry, gateway). The gateway's unconfirmed branch holds the case for `seat`; the caller
    adds the confirmed branches (the default flow is 'confirmed')."""
    e, c = checked_query(s, id, name, connector, operation, inputs, prefix, results, mode=mode, step=step)
    g = s.xgw(id + 'Gw', name + '?')
    s.flow(c, g)
    unconfirmed, _ = check_conditions([(prefix, results)])
    s.flow(g, _hold(p, s, id + 'Hold', hold_name, seat, e), unconfirmed, 'not confirmed')
    return e, g


def _calendar(pk, key, title, inputs, outputs, row, rule, reason):
    """A SYNTHETIC per-country table and its placeholder (decision 108)."""
    pk.dmn(key, title, 'FIRST', inputs, outputs, [(['-'] * len(inputs), row, 'SYNTHETIC')],
           doc='SYNTHETIC: country law or policy. Deploy the confirmed table as a tenant override.')
    pk.placeholder(key, rule, reason)


# ------------------------------------------------------------------------------------------------ 1 budget preparation
def budget_preparation():
    pk = Pack('gov.intl.pfm-budget-preparation', 'gov-intl-pfm-budget-preparation',
              'Budget preparation and budget-document bundle (worldwide PFM)',
              'बजट तैयारी एवं बजट दस्तावेज़ (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              'Budget calendar (PFM-020, PEFA PI-17): the circular opens the round, the spending unit submits within the '
              'ceiling read from the ledger and within the submission deadline, the budget office reviews (not the '
              'submitter), the executive approves, the budget-document bundle (proposal, annexes, fiscal-risk '
              "statement, citizens' budget; PFM-022, PI-5) is produced from the data and checked, and the budget goes "
              'to the legislature with a confirmed receipt; the enacted appropriation is recorded. Dates are SYNTHETIC '
              'until the country confirms them.',
              ['PFM-020', 'PFM-022', 'GP-06', 'GP-14'],
              connectors=[('ledger.ceilings', 'a', '', 'Ceiling of the spending unit for the budget year'),
                          ('legislature.submit', 'b', '', 'Submission of the budget to the legislature')],
              currency='USD')
    p = pk.proc('sangam-intl-pfm-budget-preparation', 'Budget preparation', '',
                start={'materialFields': 'budgetYear,spendingUnit,circularRef'})
    s = p.root
    st = s.start('bp_start', 'Budget circular issued', form='gov-intl-pfm-budget-preparation/circular')
    p.forms.append(dict(key='gov-intl-pfm-budget-preparation/circular', name='Budget circular | बजट परिपत्र',
                        decision=None, fields=['budgetYear*', 'spendingUnit*', 'circularRef*', 'description'], doc=None))
    d = s.setv('bp_defaults', 'Configuration',
               defaults='spendingUnit=SU-1;ledgerId=BUDGET;submitterSeat=SPENDING-UNIT;budgetOfficeSeat=BUDGET-OFFICE;'
                        'executiveSeat=FINANCE-MINISTER')
    r = s.rule('bp_cfg', 'Budget calendar (DMN)', 'intl-pfm-budget-calendar')
    ce, cg = _check(p, s, 'bp_ceiling', 'Ceiling of the spending unit', 'ledger.ceilings', 'ceilings',
                    'ledgerId,budgetYear,spendingUnit', 'ceiling', ['ceilingAmount'], '${budgetOfficeSeat}',
                    'Ceiling not confirmed: confirm it in the ledger | सीमा की पुष्टि नहीं')
    s.seq(st, d, r, ce)
    se, sx = s.sla_task('bp_submit', 'Prepare the budget submission within the ceiling | बजट प्रस्तुति',
                        'submitterSeat', form='gov-intl-pfm-budget-preparation/submission', decision=None,
                        fields=['ceilingAmount', 'requestAmount*', 'submissionNote*', 'documents'],
                        spec='${budgetSubmissionSpec}')
    s.flow(cg, se, None, 'confirmed', default=True)
    og = s.xgw('bp_overGw', 'Within the ceiling?')
    s.flow(sx, og)
    s.flow(og, se, 'requestAmount > ceilingAmount', 'above the ceiling: revise')
    re_, rx = s.sla_task('bp_review', 'Review the submission (budget office) | प्रस्तुति की समीक्षा', 'budgetOfficeSeat',
                         form='gov-intl-pfm-budget-preparation/review', decision=('reviewDecision', ['approve', 'return']),
                         fields=['requestAmount', 'submissionNote', 'reviewNote*'], spec='${budgetReviewSpec}',
                         sod=True, sod_steps='bp_submit')
    s.flow(og, re_, None, 'within the ceiling', default=True)
    rg = s.xgw('bp_reviewGw')
    s.flow(rx, rg)
    s.flow(rg, se, "reviewDecision == 'return'", 'returned')
    ex = s.user('bp_executive', 'Executive approval of the budget proposal | कार्यपालिका अनुमोदन', '${executiveSeat}',
                form='gov-intl-pfm-budget-preparation/executive', decision=('executiveDecision', ['approve', 'return']),
                fields=['requestAmount', 'reviewNote', 'remarks'], sod=True, sod_steps='bp_submit,bp_review')
    s.flow(rg, ex, None, 'approved', default=True)
    eg = s.xgw('bp_executiveGw')
    s.flow(ex, eg)
    s.flow(eg, re_, "executiveDecision == 'return'", 'returned to the budget office')
    bundle = s.call('bp_bundle', 'Budget-document bundle (PFM-022)', 'sangam-intl-pfm-budget-document-bundle')
    s.flow(eg, bundle, None, 'approved', default=True)
    bg = s.xgw('bp_bundleGw', 'Bundle complete?')
    s.flow(bundle, bg)
    s.flow(bg, re_, "bundleDecision == 'return'", 'bundle returned: correct the data')
    le, lg = _check(p, s, 'bp_legislature', 'Submit the budget to the legislature', 'legislature.submit', 'submit',
                    'budgetYear,spendingUnit,requestAmount', 'leg', ['legReceiptRef'], '${budgetOfficeSeat}',
                    'Legislature receipt not confirmed: confirm and resend | विधायिका प्राप्ति की पुष्टि नहीं',
                    mode='submit', step='legislature')
    s.flow(bg, le, None, 'complete', default=True)
    lc = clock(s, 'bp_legClock', 'legislature', '${legislatureSpec}')
    s.flow(lg, lc, None, 'received', default=True)
    w = s.egw('bp_wait', 'Enacted?')
    s.flow(lc, w)
    en = s.catch_msg('bp_enactedMsg', 'Budget enacted', 'sangam.pfm.budgetEnacted')
    late = s.catch_timer('bp_legLate', 'Legislature deadline passed', date='${legislatureDueAt}')
    s.flow(w, en)
    s.flow(w, late)
    fu = s.user('bp_legFollowUp', 'Budget not yet enacted: follow up (interim authority per the profile) | '
                'अनुवर्ती कार्रवाई', '${budgetOfficeSeat}', form='gov-intl-pfm-budget-preparation/follow-up',
                decision=None, fields=['remarks*'])
    s.flow(late, fu)
    s.flow(fu, lc)
    rec = s.user('bp_record', 'Record the enacted appropriation | अधिनियमित विनियोग दर्ज करें', '${budgetOfficeSeat}',
                 form='gov-intl-pfm-budget-preparation/enacted', decision=None,
                 fields=['appropriationActRef*', 'enactedAmount*', 'remarks'])
    s.flow(en, rec)
    s.flow(rec, end_ok(p, s, 'bp_end', 'Budget enacted', outcome='ENACTED',
                       data=['appropriationActRef', 'enactedAmount', 'legReceiptRef']))
    s.side_exits('bp')

    b = pk.proc('sangam-intl-pfm-budget-document-bundle', 'Budget-document bundle', '',
                start={'materialFields': 'budgetYear,spendingUnit,requestAmount'})
    t = b.root
    bs = t.start('bb_start', 'Bundle requested')
    bd = t.setv('bb_defaults', 'Configuration', defaults='budgetOfficeSeat=BUDGET-OFFICE')
    docs =[t.svc('bb_' + k, n, C['DOC'], {'template': tpl}) for k, n, tpl in (
        ('proposal', 'Budget proposal', 'budget-proposal'),
        ('annexes', 'Annexes by classification', 'budget-annexes'),
        ('fiscalRisk', 'Fiscal-risk statement', 'fiscal-risk-statement'),
        ('citizens', "Citizens' budget", 'citizens-budget'))]
    chk = t.user('bb_check', 'Check the bundle: complete and consistent with the data | बजट दस्तावेज़ जाँच',
                 '${budgetOfficeSeat}', form='gov-intl-pfm-budget-preparation/bundle-check',
                 decision=('bundleDecision', ['approve', 'return']), fields=['remarks'])
    t.seq(bs, bd, *docs, chk)
    cg2 = t.xgw('bb_checkGw')
    t.flow(chk, cg2)
    t.flow(cg2, end_ok(b, t, 'bb_endReturned', 'Bundle returned', outcome='RETURNED'), "bundleDecision == 'return'",
           'returned')
    t.flow(cg2, end_ok(b, t, 'bb_end', 'Bundle ready', outcome='BUNDLED'), None, 'complete', default=True)

    _calendar(pk, 'intl-pfm-budget-calendar', 'Budget calendar (SYNTHETIC; per country)',
              [('Spending unit', 'spendingUnit', 'string')],
              [('budgetSubmissionSpec', 'Submission within', 'string'), ('budgetReviewSpec', 'Review within', 'string'),
               ('legislatureSpec', 'Enactment expected within', 'string')],
              [q('30D'), q('15D'), q('90D')], 'SYNTHETIC 30 / 15 / 90-day budget calendar',
              'country budget calendar not verified (INTERNATIONAL-PFM-REQUIREMENTS PFM-020, section 5)')
    simple_seeds(pk, 'MINISTRY', [
        ('SPENDING-UNIT', 'SPENDINGUNIT', 'BUDGET-OFFICE', 'Spending unit budget officer', 'व्यय इकाई बजट अधिकारी'),
        ('BUDGET-OFFICE', 'BUDGETOFFICE', 'FINANCE-MINISTER', 'Budget office', 'बजट कार्यालय'),
        ('FINANCE-MINISTER', 'MINISTER', None, 'Minister of finance (executive approval)', 'वित्त मंत्री')], 'ibp')


# ------------------------------------------------------------------------------------------------ 2 standard reports
def standard_reports():
    pk = Pack('gov.intl.pfm-standard-reports', 'gov-intl-pfm-standard-reports', 'Standard budget execution reports '
              '(worldwide PFM)', 'मानक बजट निष्पादन रिपोर्ट (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              'In-year and annual budget execution reports (PFM-040, PEFA PI-1, PI-2, PI-28): the ledger extract by '
              'every segment and stage (original, revised, released, committed, verified, paid, available) is taken '
              'from the ledger, the preparer explains the variances, a different officer approves, and the report is '
              'issued by the due date from DMN. The due dates are SYNTHETIC until the country confirms them.',
              ['PFM-040', 'GP-06'],
              connectors=[('ledger.report', 'a', '', 'Ledger extract by segment and stage')], currency='USD')
    p = pk.proc('sangam-intl-pfm-standard-reports', 'Standard budget execution report', '',
                start={'materialFields': 'reportSet,reportPeriod,ledgerId'})
    s = p.root
    st = s.start('sr_start', 'Report period closed', form='gov-intl-pfm-standard-reports/period')
    p.forms.append(dict(key='gov-intl-pfm-standard-reports/period', name='Report period | रिपोर्ट अवधि', decision=None,
                        fields=['reportSet*', 'reportPeriod*', 'ledgerId'], doc=None))
    d = s.setv('sr_defaults', 'Configuration', defaults='ledgerId=BUDGET;reportSet=MONTHLY;'
                                                       'preparerSeat=ACCOUNTS-OFFICER;reviewerSeat=ACCOUNTING-OFFICER')
    r = s.rule('sr_cfg', 'Report due date (DMN)', 'intl-pfm-report-calendar')
    dl = clock(s, 'sr_due', 'report', '${reportDueSpec}')
    xe, xg = _check(p, s, 'sr_extract', 'Ledger extract', 'ledger.report', 'report', 'ledgerId,reportSet,reportPeriod',
                    'extract', ['extractDatasetRef'], '${preparerSeat}',
                    'Ledger extract not confirmed: check the ledger | लेजर उद्धरण की पुष्टि नहीं')
    s.seq(st, d, r, dl, xe)
    pr = s.user('sr_prepare', 'Prepare the report and explain variances | रिपोर्ट तैयार करें', '${preparerSeat}',
                form='gov-intl-pfm-standard-reports/prepare', decision=None,
                fields=['extractDatasetRef', 'varianceNote*', 'documents'], due='${reportDueAt}')
    s.flow(xg, pr, None, 'confirmed', default=True)
    rv = s.user('sr_review', 'Approve the report (not the preparer) | रिपोर्ट का अनुमोदन', '${reviewerSeat}',
                form='gov-intl-pfm-standard-reports/review', decision=('reviewDecision', ['approve', 'return']),
                fields=['extractDatasetRef', 'varianceNote', 'remarks'], sod=True, sod_steps='sr_prepare',
                due='${reportDueAt}')
    s.flow(pr, rv)
    rg = s.xgw('sr_reviewGw')
    s.flow(rv, rg)
    s.flow(rg, pr, "reviewDecision == 'return'", 'returned')
    doc = s.svc('sr_doc', 'Issue the report', C['DOC'], {'template': 'budget-execution-report'})
    s.flow(rg, doc, None, 'approved', default=True)
    s.flow(doc, end_ok(p, s, 'sr_end', 'Report issued', outcome='ISSUED', data=['extractDatasetRef']))
    s.side_exits('sr')
    _calendar(pk, 'intl-pfm-report-calendar', 'Report due dates (SYNTHETIC; per country)',
              [('Report set', 'reportSet', 'string')], [('reportDueSpec', 'Report due within', 'string')],
              [q('15D')], 'SYNTHETIC 15-day report deadline',
              'country in-year and annual report deadlines not verified (PFM-040, PEFA PI-28)')
    simple_seeds(pk, 'TREASURY', [
        ('ACCOUNTS-OFFICER', 'ACCOUNTSOFFICER', 'ACCOUNTING-OFFICER', 'Accounts officer', 'लेखा अधिकारी (तैयारी)'),
        ('ACCOUNTING-OFFICER', 'ACCOUNTING-OFFICER', None, 'Accounting officer', 'लेखा अधिकारी')], 'isr')


# ------------------------------------------------------------------------------------------------ 3 cash plan
def cash_plan():
    pk = Pack('gov.intl.pfm-cash-plan', 'gov-intl-pfm-cash-plan', 'Cash plan (worldwide PFM)',
              'नकद योजना (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              'Cash forecasting and the cash plan (PFM-047, PEFA PI-21.2): the forecast comes from the ledger '
              '(releases and commitments), the cash manager sets the release plan, the budget office concurs (not the '
              'cash manager), the plan is issued, and it is updated in year on the review cycle from DMN until the '
              'period closes. The cycle is SYNTHETIC until the country confirms it.',
              ['PFM-047', 'GP-06'],
              connectors=[('ledger.cashForecast', 'a', '', 'Cash forecast from releases and commitments')],
              currency='USD')
    p = pk.proc('sangam-intl-pfm-cash-plan', 'Cash plan', '', start={'materialFields': 'planPeriod,ledgerId'})
    s = p.root
    st = s.start('cp_start', 'Plan period opened', form='gov-intl-pfm-cash-plan/period')
    p.forms.append(dict(key='gov-intl-pfm-cash-plan/period', name='Cash plan period | नकद योजना अवधि', decision=None,
                        fields=['planPeriod*', 'ledgerId'], doc=None))
    d = s.setv('cp_defaults', 'Configuration', defaults='ledgerId=BUDGET;cashManagerSeat=CASH-MANAGER;'
                                                       'budgetOfficeSeat=BUDGET-OFFICE')
    r = s.rule('cp_cfg', 'Cash plan cycle (DMN)', 'intl-pfm-cash-plan')
    fe, fg = _check(p, s, 'cp_forecast', 'Cash forecast', 'ledger.cashForecast', 'forecast',
                    'ledgerId,planPeriod,cashPlanHorizon', 'forecast', ['forecastRef'], '${cashManagerSeat}',
                    'Cash forecast not confirmed: check the ledger | नकद पूर्वानुमान की पुष्टि नहीं')
    s.seq(st, d, r, fe)
    pr = s.user('cp_prepare', 'Set the release plan from the forecast | निर्मुक्ति योजना', '${cashManagerSeat}',
                form='gov-intl-pfm-cash-plan/prepare', decision=None,
                fields=['forecastRef', 'releasePlanNote*', 'documents'])
    s.flow(fg, pr, None, 'confirmed', default=True)
    cc = s.user('cp_concur', 'Concur with the cash plan (budget office) | नकद योजना पर सहमति', '${budgetOfficeSeat}',
                form='gov-intl-pfm-cash-plan/concur', decision=('concurDecision', ['approve', 'return']),
                fields=['forecastRef', 'releasePlanNote', 'remarks'], sod=True, sod_steps='cp_prepare')
    s.flow(pr, cc)
    cg = s.xgw('cp_concurGw')
    s.flow(cc, cg)
    s.flow(cg, pr, "concurDecision == 'return'", 'returned')
    doc = s.svc('cp_doc', 'Issue the cash plan', C['DOC'], {'template': 'cash-plan'})
    s.flow(cg, doc, None, 'concurred', default=True)
    w = s.egw('cp_wait', 'Update or close?')
    s.flow(doc, w)
    upd = s.catch_timer('cp_review', 'In-year update due', duration='${cashPlanReviewSpec}')
    closed = s.catch_msg('cp_closed', 'Plan period closed', 'sangam.pfm.periodClosed')
    s.flow(w, upd)
    s.flow(w, closed)
    s.flow(upd, fe)
    s.flow(closed, end_ok(p, s, 'cp_end', 'Plan period closed', outcome='CLOSED', data=['forecastRef']))
    s.side_exits('cp')
    _calendar(pk, 'intl-pfm-cash-plan', 'Cash plan horizon and update cycle (SYNTHETIC; per country)',
              [('Plan period', 'planPeriod', 'string')],
              [('cashPlanHorizon', 'Forecast horizon', 'string'), ('cashPlanReviewSpec', 'Update every (ISO duration)',
                                                                     'string')],
              [q('MONTHLY'), q('P30D')], 'SYNTHETIC monthly horizon, 30-day update',
              'country cash-planning horizon and update cycle not verified (PFM-047, PEFA PI-21.2)')
    simple_seeds(pk, 'TREASURY', [
        ('CASH-MANAGER', 'CASHMANAGER', 'BUDGET-OFFICE', 'Cash manager (treasury)', 'नकद प्रबंधक'),
        ('BUDGET-OFFICE', 'BUDGETOFFICE', None, 'Budget office', 'बजट कार्यालय')], 'icp')


# ------------------------------------------------------------------------------------------------ 4 transfer deadline
def transfer_deadline():
    pk = Pack('gov.intl.pfm-transfer-deadline', 'gov-intl-pfm-transfer-deadline', 'Collection transfer-deadline monitor '
              '(worldwide PFM)', 'संग्रह अंतरण समय-सीमा निगरानी (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              'Revenue collected by an agent (bank, portal) must reach the treasury single account by the deadline '
              'of its channel from DMN (for example T+1; PFM-046, PEFA PI-20): the case waits for the TSA credit, '
              'compares the credited amount with the collection, sends a difference to the treasury officer, and '
              'records a late transfer for follow-up with the agent. Deadlines are SYNTHETIC until the country '
              'confirms them.',
              ['PFM-046', 'GP-06'],
              connectors=[('tsa.credit', 'a', '', 'Credit in the treasury single account')], currency='USD')
    p = pk.proc('sangam-intl-pfm-transfer-deadline', 'Collection transfer deadline', '',
                start={'materialFields': 'collectionChannel,collectionRef,collectionAmount'})
    s = p.root
    st = s.start('td_start', 'Collection reported', form='gov-intl-pfm-transfer-deadline/collection')
    p.forms.append(dict(key='gov-intl-pfm-transfer-deadline/collection', name='Collection | संग्रह', decision=None,
                        fields=['collectionChannel*', 'collectionRef*', 'collectionAmount*', 'collectedOn*'], doc=None))
    d = s.setv('td_defaults', 'Configuration', defaults='collectionChannel=AGENT-BANK;treasurySeat=TREASURY-OFFICER;'
                                                       'transferLate=false')
    r = s.rule('td_cfg', 'Transfer deadline of the channel (DMN)', 'intl-pfm-transfer-deadline')
    dl = clock(s, 'td_deadline', 'transfer', '${transferSpec}')
    w = s.egw('td_wait', 'Credited in time?')
    s.seq(st, d, r, dl, w)
    cr = s.catch_msg('td_credited', 'TSA credit reported', 'sangam.pfm.tsaCredited')
    late = s.catch_timer('td_late', 'Transfer deadline passed', date='${transferDueAt}')
    s.flow(w, cr)
    s.flow(w, late)
    lt = s.setv('td_lateMark', 'Late transfer', values='transferLate=true')
    ov = s.user('td_overdue', 'Transfer overdue: pursue the collecting agent | अंतरण विलंबित', '${treasurySeat}',
                form='gov-intl-pfm-transfer-deadline/overdue', decision=None, fields=['remarks*'])
    s.seq(late, lt, ov)
    cr2 = s.catch_msg('td_creditedLate', 'TSA credit reported (late)', 'sangam.pfm.tsaCredited')
    s.flow(ov, cr2)
    me, mg = _check(p, s, 'td_match', 'TSA credit of the collection', 'tsa.credit', 'lookup',
                    'collectionChannel,collectionRef', 'credit', ['creditAmount'], '${treasurySeat}',
                    'TSA credit not confirmed: check the bank statement | टीएसए जमा की पुष्टि नहीं')
    s.flow(cr, me)
    s.flow(cr2, me)
    mm = s.user('td_mismatch', 'Credit differs from the collection: investigate | अंतर की जाँच', '${treasurySeat}',
                form='gov-intl-pfm-transfer-deadline/mismatch', decision=None, fields=['creditAmount', 'mismatchNote*'])
    s.flow(mg, mm, 'creditAmount != collectionAmount', 'difference')
    s.flow(mm, end_ok(p, s, 'td_endDifference', 'Difference recorded', outcome='DIFFERENCE',
                      data=['creditAmount', 'transferLate']))
    s.flow(mg, end_ok(p, s, 'td_end', 'Transferred', outcome='TRANSFERRED', data=['creditAmount', 'transferLate']),
           None, 'credited in full', default=True)
    s.side_exits('td')
    _calendar(pk, 'intl-pfm-transfer-deadline', 'Transfer deadline by channel (SYNTHETIC; per country)',
              [('Collection channel', 'collectionChannel', 'string')], [('transferSpec', 'Transfer within', 'string')],
              [q('1WD')], 'SYNTHETIC T+1 working day', 'country transfer deadlines per collection channel not verified '
              '(PFM-046, PEFA PI-20)')
    simple_seeds(pk, 'TREASURY', [
        ('TREASURY-OFFICER', 'TREASURYOFFICER', None, 'Treasury officer (revenue accounts)', 'कोषागार अधिकारी')], 'itd')


# ------------------------------------------------------------------------------------------------ 5 budget-to-accounting
def budget_accounting_reconciliation():
    pk = Pack('gov.intl.pfm-budget-accounting-reconciliation', 'gov-intl-pfm-budget-accounting-reconciliation',
              'Budget-to-accounting reconciliation (worldwide PFM)', 'बजट-लेखा मिलान (अंतरराष्ट्रीय सार्वजनिक वित्त)',
              DOMAIN,
              'The budget outturn of the period (ledger, budget basis) is reconciled with the expense of the '
              'financial statements (accounting basis) (PFM-055; IPSAS 24 comparison of budget and actual): the '
              'difference is computed exactly, a non-zero difference is explained by basis, timing and entity '
              'differences, a different officer approves, and the reconciliation statement is issued.',
              ['PFM-055'],
              connectors=[('ledger.outturn', 'a', '', 'Budget outturn of the period (budget basis)'),
                          ('accounts.statement', 'a', '', 'Expense of the period (accounting basis)')], currency='USD')
    p = pk.proc('sangam-intl-pfm-budget-accounting-reconciliation', 'Budget-to-accounting reconciliation', '',
                start={'materialFields': 'reconPeriod,ledgerId'})
    s = p.root
    st = s.start('br_start', 'Period to reconcile', form='gov-intl-pfm-budget-accounting-reconciliation/period')
    p.forms.append(dict(key='gov-intl-pfm-budget-accounting-reconciliation/period', name='Reconciliation period | '
                        'मिलान अवधि', decision=None, fields=['reconPeriod*', 'ledgerId'], doc=None))
    d = s.setv('br_defaults', 'Configuration', defaults='ledgerId=BUDGET;accountantSeat=ACCOUNTANT;'
                                                       'reviewerSeat=ACCOUNTING-OFFICER')
    oe, oq = checked_query(s, 'br_outturn', 'Budget outturn (ledger)', 'ledger.outturn', 'outturn',
                           'ledgerId,reconPeriod', 'outturn', ['outturnAmount'])
    ae, aq = checked_query(s, 'br_accounts', 'Expense (financial statements)', 'accounts.statement', 'expense',
                           'reconPeriod', 'accounting', ['accountingAmount'])
    s.seq(st, d, oe)
    s.flow(oq, ae)
    g = s.xgw('br_bothGw', 'Both confirmed?')
    s.flow(aq, g)
    unconfirmed, _ = check_conditions([('outturn', ['outturnAmount']), ('accounting', ['accountingAmount'])])
    s.flow(g, _hold(p, s, 'br_hold', 'Amounts not confirmed: check the ledger and the accounts | राशियों की पुष्टि नहीं',
                    '${accountantSeat}', oe), unconfirmed, 'not confirmed')
    diff = s.money_sum('br_difference', 'Difference (exact)', 'differenceAmount', 'outturnAmount - accountingAmount')
    s.flow(g, diff, None, 'confirmed', default=True)
    dg = s.xgw('br_diffGw', 'Difference?')
    s.flow(diff, dg)
    ex = s.user('br_explain', 'Explain the difference (basis, timing, entity) | अंतर की व्याख्या', '${accountantSeat}',
                form='gov-intl-pfm-budget-accounting-reconciliation/explain', decision=None,
                fields=['outturnAmount', 'accountingAmount', 'differenceAmount', 'basisDifferenceNote*',
                        'timingDifferenceNote', 'entityDifferenceNote'])
    rv = s.user('br_review', 'Approve the reconciliation (not the preparer) | मिलान का अनुमोदन', '${reviewerSeat}',
                form='gov-intl-pfm-budget-accounting-reconciliation/review',
                decision=('reviewDecision', ['approve', 'return']),
                fields=['differenceAmount', 'basisDifferenceNote', 'timingDifferenceNote', 'entityDifferenceNote',
                        'remarks'], sod=True, sod_steps='br_explain')
    s.flow(dg, ex, 'differenceAmount != 0', 'difference to explain')
    s.flow(dg, rv, None, 'no difference', default=True)
    s.flow(ex, rv)
    rg = s.xgw('br_reviewGw')
    s.flow(rv, rg)
    s.flow(rg, ex, "reviewDecision == 'return'", 'returned')
    doc = s.svc('br_doc', 'Reconciliation statement', C['DOC'], {'template': 'budget-accounting-reconciliation'})
    s.flow(rg, doc, None, 'approved', default=True)
    s.flow(doc, end_ok(p, s, 'br_end', 'Reconciled', outcome='RECONCILED',
                       data=['outturnAmount', 'accountingAmount', 'differenceAmount']))
    s.side_exits('br')
    simple_seeds(pk, 'TREASURY', [
        ('ACCOUNTANT', 'ACCOUNTANT', 'ACCOUNTING-OFFICER', 'Accountant (financial statements)', 'लेखाकार'),
        ('ACCOUNTING-OFFICER', 'ACCOUNTING-OFFICER', None, 'Accounting officer', 'लेखा अधिकारी')], 'ibr')


# ------------------------------------------------------------------------------------------------ 6 replenishment
def replenishment():
    pk = Pack('gov.intl.pfm-replenishment', 'gov-intl-pfm-replenishment', 'Designated account replenishment '
              '(worldwide PFM)', 'नामित खाता पुनःपूर्ति (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              'Replenishment of a designated / project account (PFM-062, PFM-CH-6): the project unit prepares the '
              'withdrawal application with its statement of expenditure, the treasury reviews it (not the preparer), '
              "the application goes to the financing partner with a confirmed reference (the partner's no-objection "
              'is external), and the receipt in the designated account is confirmed against the claim within the '
              'period from DMN; a difference is recorded. The period is SYNTHETIC until the country confirms it.',
              ['PFM-062', 'PFM-CH-6', 'GP-06'],
              connectors=[('donor.withdrawal', 'b', '', 'Withdrawal application to the financing partner'),
                          ('tsa.credit', 'a', '', 'Credit in the designated account')], currency='USD')
    p = pk.proc('sangam-intl-pfm-replenishment', 'Designated account replenishment', '',
                start={'materialFields': 'projectId,designatedAccount,claimAmount,soeRef'})
    s = p.root
    st = s.start('rp_start', 'Withdrawal application prepared', form='gov-intl-pfm-replenishment/application')
    p.forms.append(dict(key='gov-intl-pfm-replenishment/application', name='Withdrawal application | आहरण आवेदन',
                        decision=None, fields=['projectId*', 'designatedAccount*', 'claimAmount*', 'soeRef*',
                                               'documents'], doc=None))
    d = s.setv('rp_defaults', 'Configuration', defaults='treasurySeat=TREASURY-OFFICER')
    r = s.rule('rp_cfg', 'Expected receipt period (DMN)', 'intl-pfm-replenishment')
    rv = s.user('rp_review', 'Review the application and statement of expenditure (treasury) | आवेदन की समीक्षा',
                '${treasurySeat}', form='gov-intl-pfm-replenishment/review',
                decision=('reviewDecision', ['approve', 'return']), fields=['claimAmount', 'soeRef', 'remarks'],
                sod=True)
    s.seq(st, d, r, rv)
    rg = s.xgw('rp_reviewGw')
    s.flow(rv, rg)
    rev = revise(p, s, 'rp_revise', 'Correct the withdrawal application | आवेदन सुधारें',
                 ['claimAmount*', 'soeRef*', 'documents'], rv, withdraw_prefix='rp_wd')
    s.flow(rg, rev, "reviewDecision == 'return'", 'returned')
    ae, ag = _check(p, s, 'rp_apply', 'Send the withdrawal application', 'donor.withdrawal', 'apply',
                    'projectId,designatedAccount,claimAmount,soeRef', 'application', ['applicationRef'], '${treasurySeat}',
                    'Application not confirmed by the partner: confirm and resend | आवेदन की पुष्टि नहीं',
                    mode='submit', step='withdrawal-application')
    s.flow(rg, ae, None, 'approved', default=True)
    rc = clock(s, 'rp_receiptClock', 'receipt', '${receiptSpec}')
    s.flow(ag, rc, None, 'sent', default=True)
    w = s.egw('rp_wait', 'Received?')
    s.flow(rc, w)
    rec = s.catch_msg('rp_received', 'Replenishment received', 'sangam.pfm.replenishmentReceived')
    late = s.catch_timer('rp_late', 'Receipt overdue', date='${receiptDueAt}')
    s.flow(w, rec)
    s.flow(w, late)
    fu = s.user('rp_followUp', 'Replenishment overdue: follow up with the partner | अनुवर्ती कार्रवाई', '${treasurySeat}',
                form='gov-intl-pfm-replenishment/follow-up', decision=None, fields=['remarks*'])
    s.flow(late, fu)
    s.flow(fu, rc)
    ce, cg = _check(p, s, 'rp_credit', 'Credit in the designated account', 'tsa.credit', 'lookup',
                    'designatedAccount,applicationRef', 'credit', ['creditAmount'], '${treasurySeat}',
                    'Credit not confirmed: check the account statement | जमा की पुष्टि नहीं')
    s.flow(rec, ce)
    df = s.user('rp_difference', 'Credit differs from the claim: record ineligible or unpaid amounts | अंतर दर्ज करें',
                '${treasurySeat}', form='gov-intl-pfm-replenishment/difference', decision=None,
                fields=['claimAmount', 'creditAmount', 'differenceNote*'])
    s.flow(cg, df, 'creditAmount != claimAmount', 'difference')
    s.flow(df, end_ok(p, s, 'rp_endDifference', 'Replenished with a difference', outcome='DIFFERENCE',
                      data=['applicationRef', 'creditAmount']))
    s.flow(cg, end_ok(p, s, 'rp_end', 'Replenished', outcome='REPLENISHED', data=['applicationRef', 'creditAmount']),
           None, 'received in full', default=True)
    s.side_exits('rp')
    _calendar(pk, 'intl-pfm-replenishment', 'Expected replenishment period (SYNTHETIC; per agreement)',
              [('Project', 'projectId', 'string')], [('receiptSpec', 'Receipt expected within', 'string')],
              [q('30D')], 'SYNTHETIC 30-day receipt period', 'replenishment period of the financing agreement not '
              'verified (PFM-062, PFM-CH-6)')
    simple_seeds(pk, 'TREASURY', [
        ('PROJECT-UNIT', 'PROJECTUNIT', 'TREASURY-OFFICER', 'Project unit accountant', 'परियोजना इकाई लेखाकार'),
        ('TREASURY-OFFICER', 'TREASURYOFFICER', None, 'Treasury officer', 'कोषागार अधिकारी')], 'irp')


# ------------------------------------------------------------------------------------------------ 7 Do-Not-Pay
def do_not_pay():
    pk = Pack('gov.intl.pfm-do-not-pay', 'gov-intl-pfm-do-not-pay', 'Do-Not-Pay payee screening (worldwide PFM)',
              'भुगतान निषेध सूची जाँच (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              'Payee integrity before payment (PFM-049): the payee is screened against the exclusion lists the '
              'country binds to the payee.exclusion connector (debarment, deceased persons, other do-not-pay '
              'sources); no match clears the payee; a match is adjudicated by the screening officer with evidence and '
              'confirmed by a second officer, and a confirmed match blocks the payment. An unconfirmed screening '
              'holds the case: the payment is never cleared without an answer.',
              ['PFM-049', 'GP-14'],
              connectors=[('payee.exclusion', 'a', '', 'Exclusion lists (do-not-pay sources)')], currency='USD')
    p = pk.proc('sangam-intl-pfm-do-not-pay', 'Do-Not-Pay payee screening', '',
                start={'materialFields': 'payee,payeeIdentifier,paymentRef,amount'})
    s = p.root
    st = s.start('dn_start', 'Payment to screen', form='gov-intl-pfm-do-not-pay/payment')
    p.forms.append(dict(key='gov-intl-pfm-do-not-pay/payment', name='Payment to screen | जाँच हेतु भुगतान',
                        decision=None, fields=['payee*', 'payeeIdentifier*', 'paymentRef*', 'amount'], doc=None))
    d = s.setv('dn_defaults', 'Configuration', defaults='screeningSeat=PAYEE-SCREENING;reviewerSeat=ACCOUNTING-OFFICER')
    se, sg = _check(p, s, 'dn_screen', 'Screen the payee', 'payee.exclusion', 'screen', 'payee,payeeIdentifier',
                    'exclusion', ['exclusionMatch'], '${screeningSeat}',
                    'Screening not confirmed: payment held, confirm the lists | जाँच की पुष्टि नहीं, भुगतान रोका')
    s.seq(st, d, se)
    ad = s.user('dn_adjudicate', 'Adjudicate the list match | सूची मिलान का निर्णय', '${screeningSeat}',
                form='gov-intl-pfm-do-not-pay/adjudicate', decision=('matchDecision', ['trueMatch', 'falsePositive']),
                fields=['payee', 'payeeIdentifier', 'matchEvidence*'])
    s.flow(sg, ad, 'exclusionMatch', 'match')
    s.flow(sg, end_ok(p, s, 'dn_endClear', 'Payee clear', outcome='CLEARED', data=['exclusionMatch']), None,
           'no match', default=True)
    rv = s.user('dn_review', 'Confirm the adjudication (second officer) | निर्णय की पुष्टि', '${reviewerSeat}',
                form='gov-intl-pfm-do-not-pay/review', decision=('reviewDecision', ['approve', 'return']),
                fields=['matchEvidence', 'remarks'], sod=True, sod_steps='dn_adjudicate')
    s.flow(ad, rv)
    rg = s.xgw('dn_reviewGw')
    s.flow(rv, rg)
    s.flow(rg, ad, "reviewDecision == 'return'", 'returned')
    mg = s.xgw('dn_matchGw', 'Same person?')
    s.flow(rg, mg, None, 'confirmed', default=True)
    blk = s.notify('dn_blocked', 'Payment blocked', 'payment-blocked', 'inapp', 'initiatorSeat')
    s.flow(mg, blk, "matchDecision == 'trueMatch'", 'true match')
    s.flow(blk, end_ok(p, s, 'dn_endBlocked', 'Payment blocked', outcome='BLOCKED', data=['matchDecision']))
    s.flow(mg, end_ok(p, s, 'dn_endOverride', 'Payee cleared (false positive)', outcome='CLEARED',
                      data=['matchDecision']), None, 'false positive', default=True)
    s.side_exits('dn')
    simple_seeds(pk, 'TREASURY', [
        ('PAYEE-SCREENING', 'SCREENINGOFFICER', 'ACCOUNTING-OFFICER', 'Payee screening officer', 'प्राप्तकर्ता जाँच अधिकारी'),
        ('ACCOUNTING-OFFICER', 'ACCOUNTING-OFFICER', None, 'Accounting officer', 'लेखा अधिकारी')], 'idn')


def build():
    budget_preparation()
    standard_reports()
    cash_plan()
    transfer_deadline()
    budget_accounting_reconciliation()
    replenishment()
    do_not_pay()
