# SPDX-License-Identifier: Apache-2.0
"""M-134, owner decision 175 (W4Q-30): the G (gap) rows of the worldwide PFM map, after the P-row packs of gov_pfm.py
(spike/results/INTERNATIONAL-PFM-REQUIREMENTS.md section 3):

- PFM-056 fiscal transparency outputs and open data (PEFA PI-9);
- PFM-065 procurement complaints (PEFA PI-24.4);
- PFM-069 debt operations approval chain; debt recording stays in the debt system, reached through a connector
  (PEFA PI-13);
- PFM-080 PEFA evidence pack (a report set from Sangam data).

Owner decision 108: country values are SYNTHETIC placeholders (readiness fails until a confirmed tenant override is
deployed) and no consumer data is in these files. No money is computed or rounded here (decision 139). Every external
answer is a fail-closed check (SL-115).
"""
from lib import C, q
from packs import Pack
from common import end_ok
from gov2 import simple_seeds
from gov_pfm import DOMAIN, _check, _calendar
from sector import clock

import forms

forms.L.update({k: v for k, v in {
    'publicationSet': ('Publication (in-year report, annual statements, citizens budget, open data)', 'प्रकाशन', 'select'),
    'publicationNote': ('Publication: content, machine-readable formats, no personal data', 'प्रकाशन टिप्पणी', 'text'),
    'portalRef': ('Publication reference (portal)', 'प्रकाशन संदर्भ', 'readonly'),
    'complainantName': ('Complainant (bidder)', 'शिकायतकर्ता (बोलीदाता)', 'string'),
    'complaintGrounds': ('Grounds of the complaint', 'शिकायत के आधार', 'text'),
    'admissibilityNote': ('Admissibility: time limit, standing, grounds', 'ग्राह्यता', 'text'),
    'entityResponse': ('Response of the procuring entity', 'प्रापण इकाई का उत्तर', 'text'),
    'decisionReasons': ('Decision and reasons', 'निर्णय एवं कारण', 'text'),
    'remedy': ('Remedy ordered', 'आदेशित उपचार', 'text'),
    'debtOperationType': ('Debt operation (borrowing, guarantee, on-lending)', 'ऋण संचालन प्रकार', 'select'),
    'lenderName': ('Lender / beneficiary of the guarantee', 'ऋणदाता / गारंटी लाभार्थी', 'string'),
    'debtAmount': ('Amount (pack currency)', 'राशि', 'money'),
    'debtCurrency': ('Currency of the instrument', 'लिखत की मुद्रा', 'string'),
    'authorityRemainingLimit': ('Remaining authority (debt system)', 'शेष प्राधिकार', 'readonly'),
    'termsNote': ('Terms: maturity, interest, fees, conditions', 'शर्तें', 'text'),
    'debtSustainabilityNote': ('Debt sustainability and cost-risk analysis', 'ऋण स्थिरता एवं लागत-जोखिम विश्लेषण', 'text'),
    'legalOpinion': ('Legal opinion', 'विधिक राय', 'text'),
    'assessmentRef': ('Assessment reference', 'मूल्यांकन संदर्भ', 'string'),
    'indicatorSet': ('Indicators covered', 'शामिल संकेतक', 'string'),
    'evidenceNote': ('Evidence by indicator; sources outside Sangam listed', 'संकेतकवार साक्ष्य', 'text'),
}.items() if k not in forms.L})
forms.OPT.update({k: v for k, v in {
    'admissible': ('Admissible', 'ग्राह्य'),
    'inadmissible': ('Not admissible', 'अग्राह्य'),
    'uphold': ('Uphold the complaint', 'शिकायत स्वीकार'),
    'dismiss': ('Dismiss the complaint', 'शिकायत अस्वीकार'),
}.items() if k not in forms.OPT})


# ------------------------------------------------------------------------------------------------ PFM-056
def fiscal_transparency():
    pk = Pack('gov.intl.pfm-fiscal-transparency', 'gov-intl-pfm-fiscal-transparency', 'Fiscal transparency publication '
              '(worldwide PFM)', 'राजकोषीय पारदर्शिता प्रकाशन (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              "Fiscal transparency outputs (PFM-056, PEFA PI-9): in-year reports, annual statements, the citizens' "
              'budget and open data in machine-readable form are prepared from the ledger extract, approved by a '
              'different officer and published through the open-data connector with a confirmed reference, by the '
              'publication deadline from DMN. Deadlines are SYNTHETIC until the country confirms them.',
              ['PFM-056', 'GP-06'],
              connectors=[('ledger.report', 'a', '', 'Ledger extract for the publication'),
                          ('opendata.publish', 'b', '', 'Publication on the open-data portal')], currency='USD')
    p = pk.proc('sangam-intl-pfm-fiscal-transparency', 'Fiscal transparency publication', '',
                start={'materialFields': 'publicationSet,reportPeriod'})
    s = p.root
    st = s.start('ft_start', 'Publication due', form='gov-intl-pfm-fiscal-transparency/publication')
    p.forms.append(dict(key='gov-intl-pfm-fiscal-transparency/publication', name='Publication | प्रकाशन', decision=None,
                        fields=['publicationSet*', 'reportPeriod*'], doc=None))
    d = s.setv('ft_defaults', 'Configuration', defaults='ledgerId=BUDGET;publicationSet=IN_YEAR_REPORT;'
                                                       'publisherSeat=TRANSPARENCY-OFFICER;'
                                                       'approverSeat=ACCOUNTING-OFFICER')
    r = s.rule('ft_cfg', 'Publication deadline (DMN)', 'intl-pfm-publication-calendar')
    dl = clock(s, 'ft_due', 'publication', '${publicationSpec}')
    xe, xg = _check(p, s, 'ft_extract', 'Ledger extract', 'ledger.report', 'report',
                    'ledgerId,publicationSet,reportPeriod', 'extract', ['extractDatasetRef'], '${publisherSeat}',
                    'Ledger extract not confirmed: check the ledger | लेजर उद्धरण की पुष्टि नहीं')
    s.seq(st, d, r, dl, xe)
    pr = s.user('ft_prepare', 'Prepare the publication | प्रकाशन तैयार करें', '${publisherSeat}',
                form='gov-intl-pfm-fiscal-transparency/prepare', decision=None,
                fields=['extractDatasetRef', 'publicationNote*', 'documents'], due='${publicationDueAt}')
    s.flow(xg, pr, None, 'confirmed', default=True)
    ap = s.user('ft_approve', 'Approve the publication (not the preparer) | प्रकाशन का अनुमोदन', '${approverSeat}',
                form='gov-intl-pfm-fiscal-transparency/approve', decision=('approveDecision', ['approve', 'return']),
                fields=['extractDatasetRef', 'publicationNote', 'remarks'], sod=True, sod_steps='ft_prepare',
                due='${publicationDueAt}')
    s.flow(pr, ap)
    ag = s.xgw('ft_approveGw')
    s.flow(ap, ag)
    s.flow(ag, pr, "approveDecision == 'return'", 'returned')
    doc = s.svc('ft_doc', 'Publication document', C['DOC'], {'template': 'fiscal-transparency-publication'})
    s.flow(ag, doc, None, 'approved', default=True)
    pe, pg = _check(p, s, 'ft_publish', 'Publish on the open-data portal', 'opendata.publish', 'publish',
                    'publicationSet,reportPeriod,extractDatasetRef', 'portal', ['portalRef'], '${publisherSeat}',
                    'Publication not confirmed by the portal: confirm and resend | प्रकाशन की पुष्टि नहीं',
                    mode='submit', step='publish')
    s.flow(doc, pe)
    s.flow(pg, end_ok(p, s, 'ft_end', 'Published', outcome='PUBLISHED', data=['portalRef']), None, 'published',
           default=True)
    s.side_exits('ft')
    _calendar(pk, 'intl-pfm-publication-calendar', 'Publication deadlines (SYNTHETIC; per country)',
              [('Publication', 'publicationSet', 'string')], [('publicationSpec', 'Publish within', 'string')],
              [q('30D')], 'SYNTHETIC 30-day publication deadline',
              'country publication deadlines not verified (PFM-056, PEFA PI-9)')
    simple_seeds(pk, 'TREASURY', [
        ('TRANSPARENCY-OFFICER', 'TRANSPARENCYOFFICER', 'ACCOUNTING-OFFICER', 'Fiscal transparency officer',
         'राजकोषीय पारदर्शिता अधिकारी'),
        ('ACCOUNTING-OFFICER', 'ACCOUNTING-OFFICER', None, 'Accounting officer', 'लेखा अधिकारी')], 'ift')


# ------------------------------------------------------------------------------------------------ PFM-065
def procurement_complaint():
    pk = Pack('gov.intl.pfm-procurement-complaint', 'gov-intl-pfm-procurement-complaint', 'Procurement complaint '
              '(worldwide PFM)', 'प्रापण शिकायत (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              'A bidder complaint (PFM-065, PEFA PI-24.4): the complaint review officer decides admissibility, an '
              'admissible complaint suspends the award through the e-procurement connector (confirmed), the procuring '
              'entity answers within its period, and the review officer (not the procuring entity) upholds or '
              'dismisses it with reasons and a remedy within the decision deadline from DMN; the decision is issued '
              'and the procuring entity notified. Periods and the suspension rule are SYNTHETIC until the country '
              'confirms them.',
              ['PFM-065', 'GP-06', 'GP-14'],
              connectors=[('procurement.suspend', 'b', '', 'Suspension of the award (e-procurement)')], currency='USD')
    p = pk.proc('sangam-intl-pfm-procurement-complaint', 'Procurement complaint', '',
                start={'materialFields': 'tenderId,complainantName,complaintGrounds'})
    s = p.root
    st = s.start('pc_start', 'Complaint lodged', form='gov-intl-pfm-procurement-complaint/complaint')
    p.forms.append(dict(key='gov-intl-pfm-procurement-complaint/complaint', name='Procurement complaint | प्रापण शिकायत',
                        decision=None, fields=['tenderId*', 'complainantName*', 'complaintGrounds*', 'documents'],
                        doc=None))
    d = s.setv('pc_defaults', 'Configuration', defaults='reviewSeat=COMPLAINT-REVIEW;procuringSeat=PROCURING-ENTITY')
    r = s.rule('pc_cfg', 'Complaint periods (DMN)', 'intl-pfm-complaint-rules')
    dl = clock(s, 'pc_clock', 'complaintDecision', '${complaintDecisionSpec}')
    ad = s.user('pc_admissibility', 'Admissibility of the complaint | शिकायत की ग्राह्यता', '${reviewSeat}',
                form='gov-intl-pfm-procurement-complaint/admissibility',
                decision=('admissibleDecision', ['admissible', 'inadmissible']),
                fields=['complaintGrounds', 'admissibilityNote*'], due='${complaintDecisionDueAt}')
    s.seq(st, d, r, dl, ad)
    agw = s.xgw('pc_admissibleGw')
    s.flow(ad, agw)
    ina = s.svc('pc_inadmissibleDoc', 'Inadmissibility decision', C['DOC'], {'template': 'complaint-decision'})
    s.flow(agw, ina, "admissibleDecision == 'inadmissible'", 'not admissible')
    s.flow(ina, end_ok(p, s, 'pc_endInadmissible', 'Not admissible', outcome='INADMISSIBLE'))
    sg = s.xgw('pc_suspendRuleGw', 'Suspend the award?')
    s.flow(agw, sg, None, 'admissible', default=True)
    se, sgc = _check(p, s, 'pc_suspend', 'Suspend the award', 'procurement.suspend', 'suspend', 'tenderId',
                     'suspension', ['suspensionRef'], '${reviewSeat}',
                     'Suspension not confirmed: confirm with the e-procurement system | निलंबन की पुष्टि नहीं',
                     mode='submit', step='suspend')
    resp_e, resp_x = s.sla_task('pc_response', 'Answer the complaint (procuring entity) | शिकायत का उत्तर',
                                'procuringSeat', form='gov-intl-pfm-procurement-complaint/response', decision=None,
                                fields=['complaintGrounds', 'entityResponse*', 'documents'], spec='${entityResponseSpec}')
    s.flow(sg, se, 'suspendAward', 'suspend')
    s.flow(sg, resp_e, None, 'no suspension', default=True)
    s.flow(sgc, resp_e, None, 'suspended', default=True)
    dc = s.user('pc_decide', 'Decide the complaint with reasons | शिकायत पर निर्णय', '${reviewSeat}',
                form='gov-intl-pfm-procurement-complaint/decision', decision=('complaintDecision', ['uphold', 'dismiss']),
                fields=['complaintGrounds', 'entityResponse', 'decisionReasons*', 'remedy'], sod=True,
                sod_steps='pc_response', due='${complaintDecisionDueAt}')
    s.flow(resp_x, dc)
    doc = s.svc('pc_decisionDoc', 'Complaint decision', C['DOC'], {'template': 'complaint-decision'})
    s.flow(dc, doc)
    nt = s.notify('pc_notify', 'Decision to the procuring entity', 'complaint-decided', 'inapp', 'procuringSeat')
    s.flow(doc, nt)
    og = s.xgw('pc_outcomeGw')
    s.flow(nt, og)
    s.flow(og, end_ok(p, s, 'pc_endUpheld', 'Complaint upheld', outcome='UPHELD', data=['remedy']),
           "complaintDecision == 'uphold'", 'upheld')
    s.flow(og, end_ok(p, s, 'pc_endDismissed', 'Complaint dismissed', outcome='DISMISSED'), None, 'dismissed',
           default=True)
    s.side_exits('pc')
    _calendar(pk, 'intl-pfm-complaint-rules', 'Complaint periods and suspension (SYNTHETIC; per country)',
              [('Tender', 'tenderId', 'string')],
              [('complaintDecisionSpec', 'Decide within', 'string'), ('entityResponseSpec', 'Entity answers within',
                                                                       'string'),
               ('suspendAward', 'Suspend the award while pending', 'boolean')],
              [q('20D'), q('7D'), 'true'], 'SYNTHETIC 20-day decision, 7-day answer, suspension on',
              'country complaint-review periods and suspension rule not verified (PFM-065, PEFA PI-24.4)')
    simple_seeds(pk, 'REVIEW-BODY', [
        ('COMPLAINT-REVIEW', 'COMPLAINTREVIEW', None, 'Complaint review officer', 'शिकायत समीक्षा अधिकारी'),
        ('PROCURING-ENTITY', 'PROCURINGENTITY', 'COMPLAINT-REVIEW', 'Procuring entity officer', 'प्रापण इकाई अधिकारी')],
        'ipc')


# ------------------------------------------------------------------------------------------------ PFM-069
def debt_operation():
    pk = Pack('gov.intl.pfm-debt-operation', 'gov-intl-pfm-debt-operation', 'Debt operation approval (worldwide PFM)',
              'ऋण संचालन अनुमोदन (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              'Borrowing, guarantee or on-lending approval chain (PFM-069, PEFA PI-13): the remaining borrowing '
              'authority is read from the debt system; an operation above it goes back to the debt office; within it, '
              'the debt office records the sustainability and cost-risk analysis, legal gives its opinion (not the '
              'analyst), the approver from DMN approves (not the analyst or legal), and the operation is recorded in '
              'the debt system with a confirmed reference. Debt recording stays in the debt system. The approver is '
              'SYNTHETIC until the country confirms it.',
              ['PFM-069', 'GP-14'],
              connectors=[('debt.limit', 'a', '', 'Remaining borrowing / guarantee authority (debt system)'),
                          ('debt.record', 'b', '', 'Record the operation in the debt system')], currency='USD')
    p = pk.proc('sangam-intl-pfm-debt-operation', 'Debt operation approval', '',
                start={'materialFields': 'debtOperationType,lenderName,debtAmount,debtCurrency'})
    s = p.root
    st = s.start('do_start', 'Debt operation proposed', form='gov-intl-pfm-debt-operation/proposal')
    p.forms.append(dict(key='gov-intl-pfm-debt-operation/proposal', name='Debt operation | ऋण संचालन', decision=None,
                        fields=['debtOperationType*', 'lenderName*', 'debtAmount*', 'debtCurrency*', 'termsNote*',
                                'documents'], doc=None))
    d = s.setv('do_defaults', 'Configuration', defaults='debtOperationType=BORROWING;debtOfficeSeat=DEBT-OFFICE;'
                                                       'legalSeat=LEGAL-ADVISER')
    r = s.rule('do_cfg', 'Approver of the operation (DMN)', 'intl-pfm-debt-approval')
    le, lg = _check(p, s, 'do_limit', 'Remaining authority (debt system)', 'debt.limit', 'remaining',
                    'debtOperationType', 'authority', ['authorityRemainingLimit'], '${debtOfficeSeat}',
                    'Remaining authority not confirmed: check the debt system | शेष प्राधिकार की पुष्टि नहीं')
    s.seq(st, d, r, le)
    ab = s.user('do_aboveLimit', 'Above the remaining authority: record the outcome | प्राधिकार से अधिक',
                '${debtOfficeSeat}', form='gov-intl-pfm-debt-operation/above-limit', decision=None,
                fields=['debtAmount', 'authorityRemainingLimit', 'remarks*'])
    s.flow(lg, ab, 'authorityRemainingLimit < debtAmount', 'above the authority')
    s.flow(ab, end_ok(p, s, 'do_endAbove', 'Above the authority', outcome='ABOVE_AUTHORITY'))
    an = s.user('do_analysis', 'Sustainability and cost-risk analysis (debt office) | ऋण विश्लेषण', '${debtOfficeSeat}',
                form='gov-intl-pfm-debt-operation/analysis', decision=None,
                fields=['termsNote', 'authorityRemainingLimit', 'debtSustainabilityNote*', 'documents'])
    s.flow(lg, an, None, 'within the authority', default=True)
    lo = s.user('do_legal', 'Legal opinion | विधिक राय', '${legalSeat}', form='gov-intl-pfm-debt-operation/legal',
                decision=('legalDecision', ['approve', 'return']), fields=['termsNote', 'legalOpinion*'], sod=True,
                sod_steps='do_analysis')
    s.flow(an, lo)
    log = s.xgw('do_legalGw')
    s.flow(lo, log)
    s.flow(log, an, "legalDecision == 'return'", 'returned')
    apv = s.user('do_approve', 'Approve the debt operation | ऋण संचालन का अनुमोदन', '${approverSeat}',
                 form='gov-intl-pfm-debt-operation/approve', decision=('approveDecision', ['approve', 'return']),
                 fields=['debtAmount', 'debtSustainabilityNote', 'legalOpinion', 'remarks'], sod=True,
                 sod_steps='do_analysis,do_legal')
    s.flow(log, apv, None, 'cleared', default=True)
    apg = s.xgw('do_approveGw')
    s.flow(apv, apg)
    s.flow(apg, an, "approveDecision == 'return'", 'returned')
    re_, rg = _check(p, s, 'do_record', 'Record in the debt system', 'debt.record', 'record',
                     'debtOperationType,lenderName,debtAmount,debtCurrency', 'record', ['recordRef'], '${debtOfficeSeat}',
                     'Recording not confirmed by the debt system: confirm and resend | अभिलेखन की पुष्टि नहीं',
                     mode='submit', step='record')
    s.flow(apg, re_, None, 'approved', default=True)
    s.flow(rg, end_ok(p, s, 'do_end', 'Approved and recorded', outcome='APPROVED', data=['recordRef']), None,
           'recorded', default=True)
    s.side_exits('do')
    _calendar(pk, 'intl-pfm-debt-approval', 'Approver of debt operations (SYNTHETIC; per country)',
              [('Debt operation', 'debtOperationType', 'string')], [('approverSeat', 'Approver seat', 'string')],
              [q('FINANCE-MINISTER')], 'SYNTHETIC: minister of finance approves every operation',
              'country debt-approval authority not verified (PFM-069, PEFA PI-13.2)')
    simple_seeds(pk, 'MINISTRY', [
        ('DEBT-OFFICE', 'DEBTOFFICE', 'FINANCE-MINISTER', 'Debt management office', 'ऋण प्रबंधन कार्यालय'),
        ('LEGAL-ADVISER', 'LEGALADVISER', 'FINANCE-MINISTER', 'Legal adviser', 'विधिक सलाहकार'),
        ('FINANCE-MINISTER', 'MINISTER', None, 'Minister of finance', 'वित्त मंत्री')], 'ido')


# ------------------------------------------------------------------------------------------------ PFM-080
def pefa_evidence():
    pk = Pack('gov.intl.pfm-pefa-evidence', 'gov-intl-pfm-pefa-evidence', 'PEFA evidence pack (worldwide PFM)',
              'पीईएफए साक्ष्य संग्रह (अंतरराष्ट्रीय सार्वजनिक वित्त)', DOMAIN,
              'PEFA evidence pack (PFM-080): the indicator evidence that Sangam holds is extracted from the ledger as '
              'one report set, the coordinator compiles it indicator by indicator and lists the evidence that comes '
              'from outside Sangam, a different officer reviews, and the evidence pack is issued. It is evidence for '
              'assessors, not an assessment or a score.',
              ['PFM-080'],
              connectors=[('ledger.report', 'a', '', 'Ledger report set for the indicators')], currency='USD')
    p = pk.proc('sangam-intl-pfm-pefa-evidence', 'PEFA evidence pack', '',
                start={'materialFields': 'assessmentRef,reportPeriod,indicatorSet'})
    s = p.root
    st = s.start('pf_start', 'Evidence requested', form='gov-intl-pfm-pefa-evidence/request')
    p.forms.append(dict(key='gov-intl-pfm-pefa-evidence/request', name='PEFA evidence request | पीईएफए साक्ष्य अनुरोध',
                        decision=None, fields=['assessmentRef*', 'reportPeriod*', 'indicatorSet'], doc=None))
    d = s.setv('pf_defaults', 'Configuration', defaults='ledgerId=BUDGET;indicatorSet=PI-1..PI-31;'
                                                       'coordinatorSeat=PEFA-COORDINATOR;reviewerSeat=ACCOUNTING-OFFICER')
    xe, xg = _check(p, s, 'pf_extract', 'Ledger report set', 'ledger.report', 'pefaEvidence',
                    'ledgerId,indicatorSet,reportPeriod', 'extract', ['extractDatasetRef'], '${coordinatorSeat}',
                    'Ledger report set not confirmed: check the ledger | लेजर रिपोर्ट की पुष्टि नहीं')
    s.seq(st, d, xe)
    cm = s.user('pf_compile', 'Compile the evidence by indicator | साक्ष्य संकलन', '${coordinatorSeat}',
                form='gov-intl-pfm-pefa-evidence/compile', decision=None,
                fields=['extractDatasetRef', 'evidenceNote*', 'documents'])
    s.flow(xg, cm, None, 'confirmed', default=True)
    rv = s.user('pf_review', 'Review the evidence pack (not the compiler) | साक्ष्य की समीक्षा', '${reviewerSeat}',
                form='gov-intl-pfm-pefa-evidence/review', decision=('reviewDecision', ['approve', 'return']),
                fields=['extractDatasetRef', 'evidenceNote', 'remarks'], sod=True, sod_steps='pf_compile')
    s.flow(cm, rv)
    rg = s.xgw('pf_reviewGw')
    s.flow(rv, rg)
    s.flow(rg, cm, "reviewDecision == 'return'", 'returned')
    doc = s.svc('pf_doc', 'PEFA evidence pack', C['DOC'], {'template': 'pefa-evidence-pack'})
    s.flow(rg, doc, None, 'approved', default=True)
    s.flow(doc, end_ok(p, s, 'pf_end', 'Evidence pack issued', outcome='ISSUED', data=['extractDatasetRef']))
    s.side_exits('pf')
    simple_seeds(pk, 'TREASURY', [
        ('PEFA-COORDINATOR', 'PEFACOORDINATOR', 'ACCOUNTING-OFFICER', 'PEFA coordinator', 'पीईएफए समन्वयक'),
        ('ACCOUNTING-OFFICER', 'ACCOUNTING-OFFICER', None, 'Accounting officer', 'लेखा अधिकारी')], 'ipf2')


def build():
    fiscal_transparency()
    procurement_complaint()
    debt_operation()
    pefa_evidence()
