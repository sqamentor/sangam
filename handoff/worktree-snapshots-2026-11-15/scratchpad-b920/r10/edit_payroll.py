import sys

path = sys.argv[1]
raw = open(path, 'rb').read().decode('utf-8')
crlf = '\r\n' in raw
t = raw.replace('\r\n', '\n')

R = []

R.append(('''employee with employeeId, grossWages, pfWages and esiWages in integer minor units, esiDays (the days of the wage
period for which wages are paid, a whole number), esiWageBasis (decision 271: the employee's wage basis, for example
TIME_RATED) and esiCoverageStart''', '''employee with employeeId, grossWages, pfWages and esiWages in integer minor units, esiFullPeriodWages (decision 295:
the wage that would have been payable for the complete wage period had the employee worked on all its working days,
integer minor units), esiRating (MONTHLY, FORTNIGHTLY, WEEKLY or DAILY: how the employee is rated), esiWageBasis
(decision 271: the employee's wage basis, for example TIME_RATED) and esiCoverageStart'''))

R.append(('''- the employee low-wage exemption: no employee share when the average daily wage meets the row's daily limit under
  the row's esiLowWageComparison (AT_OR_BELOW in the demo rows); the average daily wage is the row's
  esiAverageDailyWage definition for the employee's esiWageBasis (TIME_RATED=WAGES_PER_PAID_DAY: ESI wages divided by
  the paid days, never a calendar-day divisor or a prorated fragment), marked UNVERIFIED in esiAverageDailyWageStatus
  because no citable source was read, and refused for a basis it does not define (piece-rated pay, for example); the
  exemption never removes the employer share or coverage;''', '''- the employee low-wage exemption: no employee share when the average daily wage meets the row's daily limit under
  the row's esiLowWageComparison (AT_OR_BELOW in the demo rows); the average daily wage is the row's
  esiAverageDailyWage definition for the employee's esiWageBasis (decision 295: TIME_RATED=FULL_PERIOD_WAGE_BY_RATING,
  Social Security (Central) Rules 2026 rule 2(1)(h)(i): esiFullPeriodWages divided by 26 if monthly rated, 13
  fortnightly, 6 weekly, 1 daily, never wages per paid day), refused for a basis it does not define (piece-rated pay,
  rule 2(1)(h)(ii), not coded); esiAverageDailyWageStatus stays UNVERIFIED (see the decision 295 question in the round
  10 report); the exemption threshold itself was not found in current official text after 8 May 2026 (decision 298),
  so the exemption stays blocked; the exemption never removes the employer share or coverage;'''))

R.append(('''A rules row states its evidence and permitted use as a rounding policy row does (decision 288, rounding.py): evidence
VERIFIED only when the rule and its application were read in the primary text, use PRODUCTION only then. Every row
here is UNVERIFIED + SYNTHETIC_DEMO_ONLY (real rules, primary texts not read) and the pack declares the decision a''',
'''A rules row states its evidence and permitted use as a rounding policy row does (decision 288, rounding.py): evidence
VERIFIED only when the rule and its application were read in the primary text, use PRODUCTION only then. Every row
here is UNVERIFIED + SYNTHETIC_DEMO_ONLY (decision 298: the ESI ceiling, continuation, contribution periods and
low-wage exemption were not found in current official text after 8 May 2026; the PF ceiling texts were not read) and
the pack declares the decision a'''))

R.append(('''ESI_REPORTED = ('ESI coverage wage limit Rs 21,000 per month as reported by commentary; whether a Chapter IV wage '
                'ceiling was notified after the ESI (Central) Rules 1950 were superseded on 8 May 2026 was not '
                'established (sources conflict)')
ESI_CONTINUATION = ('ESI continuation of coverage to the end of the contribution period (April to September, '
                    'October to March) and the employee-share exemption up to an average of Rs 176 a day, as known '
                    'under the ESI Act 1948 and the ESI (Central) Rules 1950 and reported by commentary; whether and '
                    'how the Code on Social Security 2020 and its rules keep them was not read')''',
'''EVIDENCE = rounding.EVIDENCE
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
                    'notification located); ' + EVIDENCE + ' sections 3.1 to 3.5')'''))

R.append(('''WHY = ('not read: the Gazette texts, the EPF Scheme 2026 paragraph that applies the ceiling to contributions and '
       'the provisions on ESI continuation and the low-wage exemption were not read, so neither the values nor their '
       'application reach 97 percent confidence')
# decision 288: real rules (ceiling, limit, continuation, exemption) whose primary texts were not read: UNVERIFIED and
# SYNTHETIC_DEMO_ONLY, with their notifications and dates unverified too; never labelled fictitious
NOTE = ('UNVERIFIED, SYNTHETIC_DEMO_ONLY (decision 288): real PF and ESI rules whose primary texts (notifications, '
        'dates, commencement and application) were not read or checked against the rules as implemented')''',
'''WHY = ('PF wage ceiling: the Gazette texts and the EPF Scheme 2026 paragraph that applies the ceiling to '
       'contributions were not read; ESI wage limit Rs 21,000, continuation after crossing it, the contribution periods '
       'and the Rs 176 low-wage exemption: ' + NOT_FOUND + ' (decision 298); so neither the values nor their '
       'application reach 97 percent confidence')
# decisions 288 and 298: real rules whose primary texts were not read (PF ceiling) or whose current source was not found
# (ESI): UNVERIFIED and SYNTHETIC_DEMO_ONLY; never labelled fictitious
NOTE = ('UNVERIFIED, SYNTHETIC_DEMO_ONLY (decisions 288, 298): the ESI Rs 21,000 ceiling, continuation after crossing '
        'the ceiling, the April to September and October to March contribution periods and the Rs 176 low-wage '
        'exemption are each ' + NOT_FOUND + ' (' + EVIDENCE + ' section 3); ESI cannot run in production until sources '
        'are found (a known 0.1.0 gap); the PF wage ceiling notifications were not read')'''))

R.append(('''# decision 271: the average daily wage of the low-wage exemption per wage basis; no citable source defines it, so the
# column is marked UNVERIFIED and a basis it does not define (piece-rated, for example) is refused, never assumed
AVERAGE_DAILY_WAGE = 'TIME_RATED=WAGES_PER_PAID_DAY\'''',
'''# decisions 271 and 295: the average daily wage of the low-wage exemption per wage basis: rule 2(1)(h)(i) for
# time-rated pay (AverageDailyWage.FULL_PERIOD_WAGE_BY_RATING); a basis it does not define (piece-rated) is refused
AVERAGE_DAILY_WAGE = 'TIME_RATED=FULL_PERIOD_WAGE_BY_RATING\''''))

R.append(('''                   'average daily wage (esiAverageDailyWage for the employee\\'s esiWageBasis: for TIME_RATED the ESI '
                   'wages divided by the paid days esiDays) is at or below the daily limit pays no employee share, the '
                   'employer share and coverage stay;''',
'''                   'average daily wage (esiAverageDailyWage for the employee\\'s esiWageBasis: for TIME_RATED rule '
                   '2(1)(h)(i), esiFullPeriodWages divided by 26, 13, 6 or 1 by esiRating) is at or below the daily '
                   'limit pays no employee share, the employer share and coverage stay;'''))

R.append(('''    pk.placeholder(RULES_KEY, 'UNVERIFIED (use SYNTHETIC_DEMO_ONLY) PF wage ceiling (Rs 15,000, Rs 25,000 from 17 September 2026), ESI coverage '
                              'limit (Rs 21,000), ESI continuation to the end of the contribution period and the ESI '
                              'low-wage exemption (Rs 176 a day, average daily wage per wage basis UNVERIFIED), '
                              'applied per employee by wage period',
                   'the Gazette texts and the provisions that apply the ceiling, the limit, continuation and the '
                   'exemption to contributions were not verified at 97 percent; the average daily wage definition '
                   'and the treatment of a wage period crossing a contribution-period boundary have no source read '
                   '(decision 271); deploy the confirmed rules as a tenant override (decision 223)')''',
'''    pk.placeholder(RULES_KEY, 'UNVERIFIED (use SYNTHETIC_DEMO_ONLY) PF wage ceiling (Rs 15,000, Rs 25,000 from 17 '
                              'September 2026), ESI coverage limit (Rs 21,000), ESI continuation to the end of the '
                              'contribution period and the ESI low-wage exemption (Rs 176 a day), applied per '
                              'employee by wage period',
                   'the PF ceiling texts were not read; the ESI limit, continuation, contribution periods and '
                   'low-wage exemption are ' + NOT_FOUND + ' (decision 298); the treatment of a wage period crossing '
                   'a contribution-period boundary has no source (decision 271); deploy the confirmed rules as a '
                   'tenant override (decision 223)')'''))

R.append(('''# (id, task name, itemResult, result, formula, rounding policy, bounds)
SHARES = [
    ('pr_pfEmployee', 'PF employee share per employee', 'pfEmployee', 'pfEmployeeAmount',
     'pfWages * pfEmployeePct / 100', 'sec-hr-pf-rounding', {'cap': 'pfWages<=pfWageCeiling'}),
    ('pr_pfEmployer', 'PF employer share per employee', 'pfEmployer', 'pfEmployerAmount',
     'pfWages * pfEmployerPct / 100', 'sec-hr-pf-rounding', {'cap': 'pfWages<=pfWageCeiling'}),''',
'''# decision 293: EPF Scheme 2026 para 18(4) works on the wages of the month; a PF share refuses any other wage period
PF_UNIT = {'periodUnit': 'CALENDAR_MONTH'}

# (id, task name, itemResult, result, formula, rounding policy, bounds)
SHARES = [
    ('pr_pfEmployee', 'PF employee share per employee', 'pfEmployee', 'pfEmployeeAmount',
     'pfWages * pfEmployeePct / 100', 'sec-hr-pf-rounding', {'cap': 'pfWages<=pfWageCeiling', **PF_UNIT}),
    ('pr_pfEmployer', 'PF employer share per employee', 'pfEmployer', 'pfEmployerAmount',
     'pfWages * pfEmployerPct / 100', 'sec-hr-pf-rounding', {'cap': 'pfWages<=pfWageCeiling', **PF_UNIT}),'''))

R.append(('''      'exemptWhen': 'esiWages/esiDays<=esiLowWageDailyLimit', ''',
          '''      'exemptWhen': 'esiFullPeriodWages/esiRating<=esiLowWageDailyLimit', '''))

for old, new in R:
    n = t.count(old)
    if n != 1:
        raise SystemExit(f'marker found {n} times: {old[:80]!r}')
    t = t.replace(old, new)

if crlf:
    t = t.replace('\n', '\r\n')
open(path, 'wb').write(t.encode('utf-8'))
print('ok', path, 'crlf' if crlf else 'lf')
