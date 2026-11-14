# SPDX-License-Identifier: Apache-2.0
"""Decision 223: PF and ESI computed per employee from the frozen wage register (single source).

HRIS (connector hris.inputs, operation freeze) returns the frozen wage register and the wage period: one record per
employee with employeeId, grossWages, pfWages and esiWages in integer minor units, esiFullPeriodWages (decision 295:
the wage that would have been payable for the complete wage period had the employee worked on all its working days,
integer minor units), esiRating (MONTHLY, FORTNIGHTLY, WEEKLY or DAILY: how the employee is rated), esiWageBasis
(decision 271: the employee's wage basis, for example TIME_RATED) and esiCoverageStart (the first day of the employee's current ESI coverage as HRIS records it, an ISO date,
or NONE when the employee is not covered) and optionally esiContinuationEndDate (decision 270: strictly the end of the
continuation after the employee's wages crossed the ESI limit, the last day of a contribution period, or empty; never
a general end of ESI coverage), plus wagePeriodFrom and wagePeriodTo (ISO dates). The register is stored as a bytes
JSON document (ConnectorCallDelegate field documents), because a string variable stops at 4000 characters.
esiContinuationEndDate replaces the round 6 field esiCoverageEnd (decision 264); no released data used that name
(nothing is released; payroll stays blocked), so the rename needs no data migration.

Gross wages are the exact sum of the employees' grossWages (MoneyCalculationDelegate op TOTAL), never one case
figure, and the payroll amount is that sum. Each contribution (PF employee share, PF employer share, ESI employee
share, ESI employer share) is a MoneyCalculationDelegate op EACH: the share of every employee is computed exactly and
rounded by its own decision 139 policy (rounding.py), written back on the register and summed exactly, so the total
always reconciles with the employees. The pack applies, from the versioned rules DMN below, the PF wage ceiling (the
PF wages of an employee are used up to the ceiling) and three separate ESI rules (decision 271):
- coverage eligibility: an employee whose ESI wages are above the limit pays and attracts no ESI, unless continued
  (decision 256: when the row's esiContinuation is CONTRIBUTION_PERIOD, an employee above the limit stays covered when
  the register's esiCoverageStart is on or before the first day of the contribution period that contains the wage
  period's first day; the contribution periods are the row's esiContributionPeriodStarts, never a caller flag;
  decision 264: under esiMidPeriodJoiner COVERED_TO_PERIOD_END a joiner covered on joining stays covered to the end of
  that contribution period; decision 270: esiContinuationEndDate is read only for an employee above the limit, a
  date before that contribution period ends the continuation, a date that would shorten the continuation derived from
  the contribution-period rules is refused). A wage period that crosses a contribution-period boundary is refused
  (WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED): its treatment is not verified from the official rules, so it
  is blocked from final calculation; the round 6 split by days (esiPeriodSplit) is removed;
- the employee low-wage exemption: no employee share when the average daily wage meets the row's daily limit under
  the row's esiLowWageComparison (AT_OR_BELOW in the demo rows); the average daily wage is the row's
  esiAverageDailyWage definition for the employee's esiWageBasis (decision 295: TIME_RATED=FULL_PERIOD_WAGE_BY_RATING,
  Social Security (Central) Rules 2026 rule 2(1)(h)(i): esiFullPeriodWages divided by 26 if monthly rated, 13
  fortnightly, 6 weekly, 1 daily, never wages per paid day), refused for a basis it does not define (piece-rated pay,
  rule 2(1)(h)(ii), not coded); esiAverageDailyWageStatus stays UNVERIFIED (see the decision 295 question in the round
  10 report); the exemption threshold itself was not found in current official text after 8 May 2026 (decision 298),
  so the exemption stays blocked; the exemption never removes the employer share or coverage;
- contribution-bearing wages: the ESI wages of the wage period, times the rate, rounded per employee and per share
  (decision 272: CEILING to the next rupee, employee and employer shares separately; the unrounded amount and the
  rounding difference are kept on the register and in the audit trace).

The rules and the rounding policies are selected by the wage period, not the business date (decision 255): the row in
force on the wage period's first day applies, unless the row in force on its last day says periodRuleBasis LAST_DAY
(only where a notification says the new rule applies to the whole wage period; every demo row keeps FIRST_DAY). The
process binds the DMN deployed with it, as for a rounding policy.

A rules row states its evidence and permitted use as a rounding policy row does (decision 288, rounding.py): evidence
VERIFIED only when the rule and its application were read in the primary text, use PRODUCTION only then. Every row
here is UNVERIFIED + SYNTHETIC_DEMO_ONLY (decision 298: the ESI ceiling, continuation, contribution periods and
low-wage exemption were not found in current official text after 8 May 2026; the PF ceiling texts were not read) and
the pack declares the decision a
placeholder, so the readiness check blocks go-live until the tenant deploys a confirmed table (a tenant's own table
wins, GP-37); a PRODUCTION row without VERIFIED evidence is refused at run time. The sources named in a row are what
was read (DOWNLOADS-WAVE4.md, 2026-11-13); they are not a claim of legal compliance or government approval
(decision 112). Gazette verification of every row is a listed gap (TESTING-GAP-REGISTER.md, decision 223).
"""
from lib import C, q
import rounding

RULES_KEY = 'sec-hr-wage-rules'
OPEN_END = rounding.OPEN_END
REGISTER = 'inputsRegister'
PERIOD = {'periodFrom': 'inputsWagePeriodFrom', 'periodTo': 'inputsWagePeriodTo'}

OUTPUTS = [('ruleId', 'Rules', 'string'), ('ruleVersion', 'Rules version', 'string')] + rounding.EVIDENCE_COLUMNS + [
           ('pfWageCeiling', 'PF wage ceiling (per month)', 'money'),
           ('esiWageLimit', 'ESI coverage wage limit (per month)', 'money'),
           ('esiContinuation', 'ESI continuation of coverage (CONTRIBUTION_PERIOD / NONE)', 'string'),
           ('esiLowWageDailyLimit', 'ESI low-wage exemption: average wages per day up to', 'money'),
           ('esiLowWageComparison', 'ESI low-wage exemption comparison (AT_OR_BELOW / BELOW)', 'string'),
           ('esiContributionPeriodStarts', 'ESI contribution periods: first days (MM-dd)', 'string'),
           ('periodRuleBasis', 'Rule for a wage period (FIRST_DAY / LAST_DAY)', 'string'),
           ('esiMidPeriodJoiner', 'ESI joiner in a contribution period (COVERED_TO_PERIOD_END / NONE)', 'string'),
           ('esiAverageDailyWage', 'ESI low-wage exemption: average daily wage per wage basis (BASIS=METHOD)',
            'string'),
           ('esiAverageDailyWageStatus', 'ESI average daily wage definition (VERIFIED / UNVERIFIED)', 'string'),
           ('pfCeilingApplication', 'How the PF ceiling is applied', 'string'),
           ('esiEligibility', 'How ESI eligibility is applied', 'string'),
           ('jurisdiction', 'Jurisdiction', 'string'), ('source', 'Source', 'string'),
           ('provision', 'Provision', 'string'), ('effectiveFrom', 'Effective from', 'string'),
           ('effectiveTo', 'Effective to', 'string')]

PF_APPLICATION = ('PF wages of each employee used up to the ceiling for both shares (a higher contribution by joint '
                  'option is not modelled)')
ESI_APPLICATION = ('an employee whose ESI wages for the wage period are above the limit is not covered (no employee '
                   'or employer share) unless the coverage start in the register (esiCoverageStart) is on or before '
                   'the first day of the contribution period containing the first day of the wage period: coverage '
                   'then continues to the end of that contribution period on the actual ESI wages; an employee whose '
                   'average daily wage (esiAverageDailyWage for the employee\'s esiWageBasis: for TIME_RATED rule '
                   '2(1)(h)(i), esiFullPeriodWages divided by 26, 13, 6 or 1 by esiRating) is at or below the daily '
                   'limit pays no employee share, the employer share and coverage stay; the rule in force on the first day of the wage period applies; '
                   'decision 264: a joiner covered on joining stays covered to the end of that contribution period; '
                   'decision 270: esiContinuationEndDate ends only the continuation of an employee above the limit, '
                   'never shortens the continuation derived from the contribution periods; decision 271: a wage '
                   'period crossing a contribution-period boundary is blocked from final calculation')
SO_2702 = ('MoLE S.O. 2702(E) of 29 May 2026 under Code on Social Security 2020 s.2(89) (Chapter III wage ceiling '
           'Rs 15,000, as reported by commentary; text not read)')
SO_5109 = ('MoLE S.O. 5109(E) of 17 September 2026 under Code on Social Security 2020 s.2(89): Rs 25,000 per month '
           'as the wage ceiling for Chapter III, from publication in the Official Gazette, superseding S.O. 2702(E) '
           '(read in a published transcription, staffnews.in, 2026-11-13; Gazette copy not read)')
EVIDENCE = rounding.EVIDENCE
# decision 298: what W4-V found (and did not find) in the official texts after 8 May 2026
NOT_FOUND = 'not found in current official text after 2026-05-08'
ESI_REPORTED = ('ESI coverage wage limit Rs 21,000 per month: ' + NOT_FOUND + ' (old rule 50 of the ESI (Central) '
                'Rules 1950 was superseded by G.S.R. 344(E) on 8 May 2026; no Chapter IV notification under Code s.2(89) '
                'was located); the comparison (covered at or below the ceiling) is Code on Social Security 2020 s.2(26), '
                'read; ' + EVIDENCE + ' section 3.5')
ESI_CONTINUATION = ('ESI continuation of coverage after crossing the ceiling: ' + NOT_FOUND + '; contribution '
                    'periods April to September and October to March: ' + NOT_FOUND + ' (old regulation 4 of the ESI '
                    '(General) Regulations 1950, applicability under Code s.164 unconfirmed); employee-share exemption '
                    'up to an average of Rs 176 a day: ' + NOT_FOUND + ' (old rule 52 superseded; no s.2(31) '
                    'notification located); ' + EVIDENCE + ' sections 3.1 to 3.5')
WHY = ('PF wage ceiling: the Gazette texts and the EPF Scheme 2026 paragraph that applies the ceiling to '
       'contributions were not read; ESI wage limit Rs 21,000, continuation after crossing it, the contribution periods '
       'and the Rs 176 low-wage exemption: ' + NOT_FOUND + ' (decision 298); so neither the values nor their '
       'application reach 97 percent confidence')
# decisions 288 and 298: real rules whose primary texts were not read (PF ceiling) or whose current source was not found
# (ESI): UNVERIFIED and SYNTHETIC_DEMO_ONLY; never labelled fictitious
NOTE = ('UNVERIFIED, SYNTHETIC_DEMO_ONLY (decisions 288, 298): the ESI Rs 21,000 ceiling, continuation after crossing '
        'the ceiling, the April to September and October to March contribution periods and the Rs 176 low-wage '
        'exemption are each ' + NOT_FOUND + ' (' + EVIDENCE + ' section 3); ESI cannot run in production until sources '
        'are found (a known 0.1.0 gap); the PF wage ceiling notifications were not read')

# decisions 271 and 295: the average daily wage of the low-wage exemption per wage basis: rule 2(1)(h)(i) for
# time-rated pay (AverageDailyWage.FULL_PERIOD_WAGE_BY_RATING); a basis it does not define (piece-rated) is refused
AVERAGE_DAILY_WAGE = 'TIME_RATED=FULL_PERIOD_WAGE_BY_RATING'

ROWS = [
    dict(version='demo-pf15k', frm='1900-01-01', to='2026-09-16', pf='15000', esi='21000',
         cont='CONTRIBUTION_PERIOD', low='176', cmp='AT_OR_BELOW', starts='04-01,10-01', basis='FIRST_DAY',
         joiner='COVERED_TO_PERIOD_END', adw=AVERAGE_DAILY_WAGE, adw_status='UNVERIFIED',
         source=SO_2702 + '; ' + ESI_REPORTED + '; ' + ESI_CONTINUATION),
    dict(version='demo-pf25k', frm='2026-09-17', to=OPEN_END, pf='25000', esi='21000',
         cont='CONTRIBUTION_PERIOD', low='176', cmp='AT_OR_BELOW', starts='04-01,10-01', basis='FIRST_DAY',
         joiner='COVERED_TO_PERIOD_END', adw=AVERAGE_DAILY_WAGE, adw_status='UNVERIFIED',
         source=SO_5109 + '; ' + ESI_REPORTED + '; ' + ESI_CONTINUATION),
]


def declare_rules(pk):
    """Writes the versioned PF ceiling and ESI rules DMN into pack `pk` once (plus its placeholder)."""
    if getattr(pk, 'wage_rules_declared', False):
        return RULES_KEY
    pk.wage_rules_declared = True
    rules = []
    for r in ROWS:
        rounding.check_evidence(f'{RULES_KEY} row {r["version"]}', rounding.UNVERIFIED, rounding.DEMO_ONLY,
                                r['source'], WHY, r['frm'], NOTE)
        rules.append(([f'[date({q(r["frm"])})..date({q(r["to"])})]'],
                      [q(RULES_KEY), q(r['version']), q(rounding.UNVERIFIED), q(rounding.DEMO_ONLY), q(NOTE), r['pf'],
                       r['esi'], q(r['cont']), r['low'], q(r['cmp']), q(r['starts']), q(r['basis']), q(r['joiner']),
                       q(r['adw']), q(r['adw_status']), q(PF_APPLICATION), q(ESI_APPLICATION), q('IN'),
                       q(r['source']), q(WHY), q(r['frm']), q(r['to'])],
                      f'{rounding.UNVERIFIED} {rounding.DEMO_ONLY} {r["version"]}'))
    pk.dmn(RULES_KEY, 'PF wage ceiling and ESI coverage (decision 223 versioned rules)', 'UNIQUE',
           [('Wage period day', 'date(asOfDate)', 'date')], OUTPUTS, rules,
           doc='Decision 223 wage rules, selected by the wage period; generated from tools/packgen/payroll.py')
    pk.placeholder(RULES_KEY, 'UNVERIFIED (use SYNTHETIC_DEMO_ONLY) PF wage ceiling (Rs 15,000, Rs 25,000 from 17 '
                              'September 2026), ESI coverage limit (Rs 21,000), ESI continuation to the end of the '
                              'contribution period and the ESI low-wage exemption (Rs 176 a day), applied per '
                              'employee by wage period',
                   'the PF ceiling texts were not read; the ESI limit, continuation, contribution periods and '
                   'low-wage exemption are ' + NOT_FOUND + ' (decision 298); the treatment of a wage period crossing '
                   'a contribution-period boundary has no source (decision 271); deploy the confirmed rules as a '
                   'tenant override (decision 223)')
    return RULES_KEY


def register_call(s, id, name):
    """hris.inputs freeze: the frozen wage register stored as a bytes JSON document (inputsRegister) and the wage
    period (inputsWagePeriodFrom, inputsWagePeriodTo)."""
    connector, mode, prefix = 'hris.inputs', 'query', 'inputs'
    s.proc.connectors.add((connector, mode))
    s.proc.conn_prefixes.setdefault(connector, set()).add(prefix)
    return s.svc(id, name, C['CALL'], {'connector': connector, 'operation': 'freeze', 'mode': mode,
                                       'resultPrefix': prefix, 'documents': 'register'}, async_=True)


def gross(s, id='pr_gross', register=REGISTER):
    """Gross wages: the exact sum of every employee's grossWages in the frozen register (op TOTAL, no rounding)."""
    return s.svc(id, 'Gross wages (sum of the employees in the frozen register, decision 223)', C['MONEY'],
                 {'result': 'grossWages', 'op': 'TOTAL', 'items': register, 'itemKey': 'employeeId',
                  'formula': 'grossWages'})


# decision 256: the coverage start in the register, the continuation mode and the contribution periods of the rules
CONTINUATION = 'esiCoverageStart@esiContinuation@esiContributionPeriodStarts'
# decision 264: the joiner rules column; decision 270: the register's optional continuation end
COVERAGE = {'joiner': 'esiMidPeriodJoiner', 'continuationEnd': 'esiContinuationEndDate'}
# decision 271: the employee's wage basis in the register, the rules definition and its status
EXEMPT_BASIS = 'esiWageBasis@esiAverageDailyWage@esiAverageDailyWageStatus'

# decision 293: EPF Scheme 2026 para 18(4) works on the wages of the month; a PF share refuses any other wage period
PF_UNIT = {'periodUnit': 'CALENDAR_MONTH'}

# (id, task name, itemResult, result, formula, rounding policy, bounds)
SHARES = [
    ('pr_pfEmployee', 'PF employee share per employee', 'pfEmployee', 'pfEmployeeAmount',
     'pfWages * pfEmployeePct / 100', 'sec-hr-pf-rounding', {'cap': 'pfWages<=pfWageCeiling', **PF_UNIT}),
    ('pr_pfEmployer', 'PF employer share per employee', 'pfEmployer', 'pfEmployerAmount',
     'pfWages * pfEmployerPct / 100', 'sec-hr-pf-rounding', {'cap': 'pfWages<=pfWageCeiling', **PF_UNIT}),
    ('pr_esiEmployee', 'ESI employee share per employee', 'esiEmployee', 'esiEmployeeAmount',
     'esiWages * esiEmployeePct / 100', 'sec-hr-esi-employee-rounding',
     {'eligibleWhen': 'esiWages<=esiWageLimit', 'continuation': CONTINUATION, **COVERAGE,
      'exemptWhen': 'esiFullPeriodWages/esiRating<=esiLowWageDailyLimit', 'exemptComparison': 'esiLowWageComparison',
      'exemptBasis': EXEMPT_BASIS}),
    ('pr_esiEmployer', 'ESI employer share per employee', 'esiEmployer', 'esiEmployerAmount',
     'esiWages * esiEmployerPct / 100', 'sec-hr-esi-employer-rounding',
     {'eligibleWhen': 'esiWages<=esiWageLimit', 'continuation': CONTINUATION, **COVERAGE}),
]


def contributions(s, pk, register=REGISTER):
    """The four per-employee shares (op EACH) and the PF and ESI totals (op SUM); returns the task ids in order."""
    rules_key = declare_rules(pk)
    ids = []
    for id, name, item_result, result, formula, policy, bounds in SHARES:
        key = rounding.declare(pk, policy)
        s.proc.decisions_used.add(key)
        s.proc.decisions_used.add(rules_key)
        ids.append(s.svc(id, name + ' (decision 223, decision 139 policy)', C['MONEY'],
                         {'result': result, 'op': 'EACH', 'items': register, 'itemKey': 'employeeId',
                          'itemResult': item_result, 'formula': formula, 'policy': key, **bounds,
                          'rules': rules_key, **PERIOD}))
    ids.append(s.money_sum('pr_pfCalc', 'PF (sum of the per-employee shares)', 'pfAmount',
                           'pfEmployeeAmount + pfEmployerAmount'))
    ids.append(s.money_sum('pr_esiCalc', 'ESI (sum of the per-employee shares)', 'esiAmount',
                           'esiEmployeeAmount + esiEmployerAmount'))
    return ids
