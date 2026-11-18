# SPDX-License-Identifier: Apache-2.0
"""Decision 139: calculation-specific, versioned rounding policies of the packs (single source).

Every money amount a pack computes is computed by MoneyCalculationDelegate in exact decimal arithmetic and rounded
once by the policy DMN of that calculation (lib.Scope.money_calc / instalment_calc). declare(pk, key) writes the
policy as a DMN of the pack: input asOfDate (the tenant's business date) selects the row in force, so a policy has
effective dates; the calling process binds the DMN deployed with it, so a later policy change never recalculates a
case that already ran; a tenant's own table wins (GP-37), which is how an installation deploys its verified policy.

Decision 288: every row states its evidence (VERIFIED only when the primary text was read and checked against the rule
as implemented; otherwise UNVERIFIED) and its permitted use (PRODUCTION only with VERIFIED evidence, citation and an
explicit effective date; otherwise SYNTHETIC_DEMO_ONLY), with the reason in evidenceNote. A real but unproven rule is
UNVERIFIED + SYNTHETIC_DEMO_ONLY with its real (unread) source; a demonstration rule names no source. Every policy
with a row not for PRODUCTION is a pack placeholder, so the readiness check blocks go-live of every tenant that
enables the pack until the tenant deploys a confirmed override. A restriction (a verified rule that the pack applies
at a different stage than the rule does) is a placeholder too. No shipped row is PRODUCTION today; the ESI rounding rows
are VERIFIED from 8 May 2026 (decision 296, primary text read) but stay SYNTHETIC_DEMO_ONLY. A VERIFIED row names the
source and provision that were read and its explicit effective date. Nothing here is a claim of legal compliance or
government approval (decision 112).

Amounts are integer minor units (money.py); quantumMinor is the rounding unit in minor units (100 = one rupee).
"""
from lib import q

OPEN_END = '9999-12-31'
# the first day of a row with no recorded effective date (decision 280: a VERIFIED row never has it; the delegate
# refuses such a row, RowEvidence.NO_EFFECTIVE_DATE)
NO_EFFECTIVE_DATE = '1900-01-01'

# decision 288: evidence status and permitted use are separate. Evidence is VERIFIED only when the primary text was
# read and checked against the rule as implemented (a citation, a build or an implementation-based test is not
# verification); use is PRODUCTION only with VERIFIED evidence, a citation and an explicit effective date. A real but
# unproven rule is UNVERIFIED + SYNTHETIC_DEMO_ONLY (its notification and date unverified too), never called fictitious.
VERIFIED, UNVERIFIED = 'VERIFIED', 'UNVERIFIED'
PRODUCTION, DEMO_ONLY = 'PRODUCTION', 'SYNTHETIC_DEMO_ONLY'
EVIDENCE_COLUMNS = [('evidenceStatus', 'Evidence (VERIFIED / UNVERIFIED)', 'string'),
                    ('permittedUse', 'Permitted use (PRODUCTION / SYNTHETIC_DEMO_ONLY)', 'string'),
                    ('evidenceNote', 'Why this evidence status and use', 'string')]

OUTPUTS = [('policyId', 'Policy', 'string'), ('policyVersion', 'Policy version', 'string')] + EVIDENCE_COLUMNS + [
           ('mode', 'Rounding mode', 'string'),
           ('quantumMinor', 'Rounding unit (minor units)', 'number'), ('negatives', 'Negative amounts', 'string'),
           ('residual', 'Instalment that takes the remainder', 'string'), ('application', 'Applied to', 'string'),
           ('stage', 'Stage', 'string'), ('jurisdiction', 'Jurisdiction', 'string'), ('scheme', 'Scheme', 'string'),
           ('purpose', 'Purpose', 'string'), ('source', 'Source', 'string'), ('provision', 'Provision', 'string'),
           ('effectiveFrom', 'Effective from', 'string'), ('effectiveTo', 'Effective to', 'string')]

DEMO_SOURCE = 'none found (SYNTHETIC_DEMO: not a statutory or contractual rule; deploy the confirmed policy as a tenant override)'


def check_evidence(where, evidence, use, source, provision, frm, note):
    """Decision 288 (and 280): the evidence and use of a shipped rules or policy row; raises on anything else."""
    if evidence not in (VERIFIED, UNVERIFIED):
        raise ValueError(f'{where}: evidenceStatus is VERIFIED or UNVERIFIED, not {evidence}')
    if use not in (PRODUCTION, DEMO_ONLY):
        raise ValueError(f'{where}: permittedUse is PRODUCTION or SYNTHETIC_DEMO_ONLY, not {use}')
    if not note:
        raise ValueError(f'{where}: a row records why it has its evidence status and use (evidenceNote)')
    if use == PRODUCTION and evidence != VERIFIED:
        raise ValueError(f'{where}: PRODUCTION needs VERIFIED evidence (decision 288)')
    # decisions 280 and 296: VERIFIED evidence (whatever the use) means the cited text was read, so the row names its
    # source and provision and records the explicit effective date the text gives
    if evidence == VERIFIED:
        if not source or source == DEMO_SOURCE or source.startswith('cited, not read') or not provision:
            raise ValueError(f'{where}: a VERIFIED row names the source and provision that were read')
        if frm == NO_EFFECTIVE_DATE:
            raise ValueError(f'{where}: a VERIFIED row records its explicit effective date (decisions 280, 288)')


def row(version, evidence, use, note, mode, quantum, negatives, application, stage, jurisdiction, scheme, purpose,
        source, provision, frm=NO_EFFECTIVE_DATE, to=OPEN_END, residual=''):
    return dict(version=version, evidence=evidence, use=use, note=note, mode=mode, quantum=quantum,
                negatives=negatives, application=application, stage=stage, jurisdiction=jurisdiction, scheme=scheme,
                purpose=purpose, source=source, provision=provision, frm=frm, to=to, residual=residual)


DEMO_NOTE = 'UNVERIFIED, SYNTHETIC_DEMO_ONLY: a demonstration rule, no statutory or contractual rule was identified'


def demo(purpose, mode, quantum, application, stage, residual='', why=''):
    return [row('demo-1', UNVERIFIED, DEMO_ONLY, DEMO_NOTE, mode, quantum, 'REFUSE', application, stage,
                'any (demonstration)', 'demonstration', purpose, DEMO_SOURCE, why or 'none', residual=residual)]


# the primary texts read by lane W4-V, with SHA-256 of each PDF (sangam-handoff evidence/STATUTORY-EVIDENCE-288.md)
EVIDENCE = 'sangam-handoff evidence/STATUTORY-EVIDENCE-288.md'
# decision 296: read in the Gazette of India Extraordinary No. 310 of 8 May 2026 (in force on publication, rule 1(2))
ESI_2026 = ('Social Security (Central) Rules, 2026, G.S.R. 344(E) of 8 May 2026, Gazette of India Extraordinary Part II '
            'Sec 3(i) No. 310 of 8 May 2026, in force on publication (rule 1(2)); read in the Gazette PDF and the MoLE '
            'copy, ' + EVIDENCE + ' section 2')
EPF_2026 = ('Employees\' Provident Funds Scheme, 2026, G.S.R. 525(E) of 29 June 2026, Gazette of India Extraordinary '
            'Part II Sec 3(i) No. 473 (masthead 29 June 2026; e-Gazette signed and uploaded 1 July 2026 00:40 IST), in '
            'force on publication (para 1(2)); read, ' + EVIDENCE + ' section 1')
PER_EMPLOYEE = ('applied per employee per wage period to each share (decision 223, payroll.py op EACH)')
NOT_READ = ('UNVERIFIED, SYNTHETIC_DEMO_ONLY (decision 288): a real rule whose cited primary text was not read or checked '
            'against the rounding as implemented; not a production rule until it is')
# decision 294: the text was read (para 18(5) matches the pack's HALF_UP to the rupee, each contribution separately),
# but the date of publication is open, so the row stays UNVERIFIED until the owner or counsel confirms it
PF_NOTE = ('UNVERIFIED, SYNTHETIC_DEMO_ONLY (decisions 288, 294): EPF Scheme 2026 para 18(5) was read and matches the '
           'rounding as implemented (' + EVIDENCE + ' section 1), but the scheme is in force on the date of its '
           'publication and that date is open: the Gazette issue (No. 473) is dated 29 June 2026, the e-Gazette copy was '
           'signed and uploaded on 1 July 2026 00:40 IST; the row stays UNVERIFIED until the owner or counsel confirms '
           'which date applies. Para 18(4) works on the wages of the month: a wage period that is not a calendar month '
           'is refused (decision 293)')
# decision 296: rule 19(1)(a) and (b) read; worked examples from the evidence file (each share separately, minor units
# in the pack: Rs 10,001 gives 326 + 76 = 402, combined at 4 percent it would be 401; Rs 10,000 gives exactly 325 and 75;
# Rs 0 gives 0)
ESI_NOTE = ('VERIFIED, SYNTHETIC_DEMO_ONLY (decision 296): rule 19(1)(a) and (b) of the Social Security (Central) Rules '
            '2026 were read (' + EVIDENCE + ' section 2): employer 3.25 percent and employee 0.75 percent of the wages '
            'payable for a wage period, each a sum rounded to the next higher rupee, separately; worked examples: wages '
            'Rs 10,001 give 326 + 76 = 402 (combined at 4 percent it would be 401), Rs 10,000 give exactly 325 and 75 '
            '(an exact rupee is not raised: the natural reading, not explicit text), Rs 0 gives 0. In force from 8 May 2026; periods before it are refused (old '
            'rule 51 not found). Use stays SYNTHETIC_DEMO_ONLY: payroll stays blocked by the unverified ESI rules '
            '(decision 298)')
ESI_PROVISION = ('rule 19(1)(%s): the %s\'s contribution, a sum (rounded to the next higher rupee) equal to %s of the '
                 'wages payable to an employee, for a wage period; CEILING to the next whole rupee per employee and '
                 'wage period, each share separately (decision 272)')

POLICIES = {
    # ---- P3 grant: DBT instalments (gov.p3.grant-sanction) ------------------------------------------------------
    'p3-instalment-rounding': dict(
        title='Instalment rounding (P3 grant)',
        rows=demo('DBT instalment of a sanctioned grant', 'DOWN', 100, 'INSTALMENT', 'each instalment', 'LAST'),
        placeholder=('SYNTHETIC instalment rule: whole rupees down, remainder on the last instalment',
                     'no scheme guideline on instalment rounding was found in the repository or in a public source; '
                     'deploy the scheme\'s confirmed instalment policy as a tenant override (decision 139)')),
    # ---- e-commerce TCS (sec.ecom.gst-tcs) ------------------------------------------------------------------------
    'sec-ecom-tcs-rounding': dict(
        title='TCS amount rounding (GST, CGST Act s.52)',
        rows=demo('tax collected at source by the operator on net taxable supplies', 'HALF_UP', 100, 'TOTAL',
                  'per seller per month, single total (no CGST / SGST / IGST split)',
                  why='CGST Act 2017 s.170 rounds a sum payable to the nearest rupee (50 paise or more up); the stage '
                      'at which it applies to TCS (per seller and per tax head) was not verified'),
        placeholder=('SYNTHETIC TCS rounding: nearest rupee on one total per seller',
                     'CGST Act s.170 (nearest rupee) is known, but its stage for TCS (per seller, per CGST / SGST / '
                     'IGST head) was not verified and the pack has no tax-head split; deploy the confirmed policy as '
                     'a tenant override (decision 139)')),
    # ---- payroll: PF (sec.hr.payroll) -----------------------------------------------------------------------------
    'sec-hr-pf-rounding': dict(
        title='PF contribution rounding (EPF Scheme)',
        # decisions 288 and 294: epf-2026-18-5 stays UNVERIFIED (reason and both dates in PF_NOTE); its row dates stay
        # as the selection of the rule from the Gazette issue date, not as a confirmed commencement
        rows=[row('epf-2026-18-5', UNVERIFIED, DEMO_ONLY, PF_NOTE, 'HALF_UP', 100, 'REFUSE',
                  'each contribution (employee share, employer share) separately', 'per employee per calendar month '
                  '(para 18(4); decision 293)', 'IN', 'EPF Scheme 2026', 'provident fund contribution', EPF_2026,
                  'para 18(5) (read): each contribution calculated to the nearest rupee, fifty paise or more counted '
                  'as the next higher rupee and a fraction below fifty paise ignored', frm='2026-06-29'),
              row('epf-1952-unverified', UNVERIFIED, DEMO_ONLY, NOT_READ, 'HALF_UP', 100, 'REFUSE',
                  'each contribution separately', 'per employee per wage period', 'IN',
                  'EPF Scheme 1952 (period before 29 June 2026)', 'provident fund contribution',
                  'EPF Scheme 1952: the rounding provision was not identified or read',
                  'not identified (nearest rupee assumed as for the 2026 row)', to='2026-06-28')],
        placeholder=('PF rounding is UNVERIFIED (use SYNTHETIC_DEMO_ONLY)',
                     'decisions 288 and 294: EPF Scheme 2026 para 18(5) was read and matches, but its date of '
                     'publication (Gazette 29 June 2026 or e-Gazette 1 July 2026) is not confirmed, and the 1952 Scheme '
                     'provision was not read; deploy the confirmed policy as a tenant override (decision 139); '
                     + PER_EMPLOYEE)),
    # ---- payroll: ESI, employee and employer shares separately ----------------------------------------------------
    'sec-hr-esi-employee-rounding': dict(
        title='ESI employee contribution rounding',
        # decision 296: rule 19(1)(b) read; in force from 8 May 2026, no row before it (old rule 51 not found), so an
        # earlier wage period matches no row and is refused (POLICY_MISSING)
        rows=[row('ss-2026-r19-1-b', VERIFIED, DEMO_ONLY, ESI_NOTE, 'CEILING', 100, 'REFUSE',
                  'employee share', 'per employee per wage period', 'IN', 'ESI under the Code on Social Security, 2020',
                  'ESI employee contribution', ESI_2026,
                  ESI_PROVISION % ('b', 'employee', 'three-fourth per cent.'), frm='2026-05-08')],
        placeholder=('ESI employee rounding is VERIFIED but SYNTHETIC_DEMO_ONLY: next higher rupee from 8 May 2026',
                     'decision 296: rule 19(1)(b) was read and matches; its use stays SYNTHETIC_DEMO_ONLY because the '
                     'ESI ceiling, continuation, contribution periods and low-wage exemption were not found in current '
                     'official text (decision 298); wage periods before 8 May 2026 are refused; deploy the confirmed '
                     'policy as a tenant override (decision 139); ' + PER_EMPLOYEE)),
    'sec-hr-esi-employer-rounding': dict(
        title='ESI employer contribution rounding',
        # decision 296: rule 19(1)(a) read (W4-V, 2026-11-13); in force from 8 May 2026, no row before it
        rows=[row('ss-2026-r19-1-a', VERIFIED, DEMO_ONLY, ESI_NOTE, 'CEILING', 100, 'REFUSE',
                  'employer share', 'per employee per wage period', 'IN', 'ESI under the Code on Social Security, 2020',
                  'ESI employer contribution', ESI_2026,
                  ESI_PROVISION % ('a', 'employer', 'three and one-fourth per cent.'), frm='2026-05-08')],
        placeholder=('ESI employer rounding is VERIFIED but SYNTHETIC_DEMO_ONLY: next higher rupee from 8 May 2026',
                     'decision 296: rule 19(1)(a) was read and matches; its use stays SYNTHETIC_DEMO_ONLY because the '
                     'ESI ceiling, continuation, contribution periods and low-wage exemption were not found in current '
                     'official text (decision 298); wage periods before 8 May 2026 are refused; deploy the confirmed '
                     'policy as a tenant override (decision 139); ' + PER_EMPLOYEE)),
    # ---- utilities: bill amount (sec.util.meter-to-cash) ----------------------------------------------------------
    'sec-util-bill-rounding': dict(
        title='Electricity bill amount rounding',
        rows=demo('consumer bill: fixed charge plus energy charge', 'HALF_UP', 1, 'TOTAL', 'bill total',
                  why='the tariff is a SYNTHETIC SERC tariff order; no rounding rule of a tariff order or supply code '
                      'was verified'),
        placeholder=('SYNTHETIC bill rounding: nearest paisa on the bill total',
                     'the bill rounding of the applicable SERC tariff order / supply code was not verified; deploy it '
                     'as a tenant override (decision 139)')),
    # ---- universal returns and refunds (universal.up13-returns-refunds) -------------------------------------------
    'up13-refund-rounding': dict(
        title='Refund due rounding',
        rows=demo('refund on the accepted quantity net of the restocking fee', 'HALF_UP', 1, 'TOTAL', 'refund total'),
        placeholder=('SYNTHETIC refund rounding: nearest paisa',
                     'refund rounding is the tenant\'s business policy (or its payment provider\'s); deploy it as a '
                     'tenant override (decision 139)')),
    # ---- procure to pay (sec.scm.procure-to-pay); GeM purchase (gov.gem-purchase) ---------------------------------
    'sec-scm-p2p-amount-rounding': dict(
        title='Requisition value rounding (quantity x unit price)',
        rows=demo('requisition / order value', 'HALF_UP', 1, 'line', 'line value',
                  why='SEC-SCM-001: value rules are company policy (SYNTHETIC); quantity is a decimal field'),
        placeholder=('SYNTHETIC line value rounding: nearest paisa',
                     'the company\'s order value rounding (line versus order total) was not verified; deploy it as a '
                     'tenant override (decision 139)')),
    'up14-payable-rounding': dict(
        title='Payable rounding (invoiced quantity x invoice price)',
        rows=demo('payable after the three-way match', 'HALF_UP', 1, 'line', 'line value',
                  why='SEC-SCM-001: value rules are company policy (SYNTHETIC); quantity is a decimal field'),
        placeholder=('SYNTHETIC payable rounding: nearest paisa',
                     'the company's payable rounding (line versus invoice total) was not verified; deploy it as a '
                     'tenant override (decision 139)')),
    'sec-edu-refund-rounding': dict(
        title='Withdrawal refund rounding (fee x refund %)',
        rows=demo('withdrawal refund', 'HALF_UP', 1, 'line', 'line value',
                  why='SEC-SCM-001: value rules are company policy (SYNTHETIC); quantity is a decimal field'),
        placeholder=('SYNTHETIC refund rounding: nearest paisa',
                     'the institution's refund rounding was not verified; deploy it as a tenant override '
                     '(decision 139)')),
    'gem-amount-rounding': dict(
        title='GeM purchase value rounding (quantity x unit price)',
        rows=demo('purchase value', 'HALF_UP', 1, 'line', 'line value'),
        placeholder=('SYNTHETIC purchase value rounding: nearest paisa',
                     'the rounding of a GeM order value (line versus order) was not verified; deploy it as a tenant '
                     'override (decision 139)')),
}


def declare(pk, key):
    """Writes policy `key` into pack `pk` once (DMN plus placeholder); returns the key for money_calc."""
    if key in getattr(pk, 'rounding_declared', set()):
        return key
    pk.rounding_declared = getattr(pk, 'rounding_declared', set()) | {key}
    pol = POLICIES[key]
    rules = []
    for r in pol['rows']:
        check_evidence(f'{key} row {r["version"]}', r['evidence'], r['use'], r['source'], r['provision'], r['frm'],
                       r['note'])
        entry = f'[date({q(r["frm"])})..date({q(r["to"])})]'
        rules.append(([entry], [q(key), q(r['version']), q(r['evidence']), q(r['use']), q(r['note']), q(r['mode']),
                                str(r['quantum']), q(r['negatives']), q(r['residual']), q(r['application']),
                                q(r['stage']), q(r['jurisdiction']), q(r['scheme']), q(r['purpose']), q(r['source']),
                                q(r['provision']), q(r['frm']), q(r['to'])],
                      f'{r["evidence"]} {r["use"]} {r["version"]}'))
    pk.dmn(key, pol['title'] + ' (decision 139 rounding policy)', 'UNIQUE', [('As of', 'date(asOfDate)', 'date')],
           OUTPUTS, rules, doc='Decision 139 rounding policy, generated from tools/packgen/rounding.py')
    if pol.get('placeholder') or any(r['use'] != PRODUCTION for r in pol['rows']):
        rule, reason = pol['placeholder']
        pk.placeholder(key, rule, reason)
    return key
