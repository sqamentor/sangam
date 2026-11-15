# Statutory evidence for decision 288 (lane W4-V, read only)

Written 2026-11-13 (UTC) by lane W4-V. Its only job was to read primary official texts and record what they say. It changed no code and no pack file. Every fetch is logged in `DOWNLOADS-WAVE4.md` (W4-V lines from 2026-11-13T06:39:28Z), with a PLANNED line before it and a RESULT line after it.

This record does not claim legal compliance or government approval. Where the evidence is below 97 percent confidence, it says so.

## 0. Sources read (all official hosts; PDFs saved in `evidence/statutory/`, never executed; text extracted with `python -I`)

| Saved file | Source URL | Identity | SHA-256 |
|---|---|---|---|
| egazette-273957.pdf | https://egazette.gov.in/WriteReadData/2026/273957.pdf | Gazette of India Extraordinary, Part II Sec 3(i), **No. 473**, masthead "Monday, June 29, 2026", ID CG-DL-E-01072026-273957. Contains **G.S.R. 525(E)**, MoLE notification "New Delhi, the 29th June, 2026": **Employees' Provident Funds Scheme, 2026** (pp. 66-129). Digital signature dated 2026.07.01 00:40:08 +05'30' | 4e062db5bf5d8b904ae1c0d4af10950dc01de7df8360398dfe197d6d06aef489 |
| egazette-275186.pdf | https://egazette.gov.in/WriteReadData/2026/275186.pdf | No. 639 of 4 Aug 2026: **G.S.R. 703(E)**, corrigenda to G.S.R. 525(E) | 053c7ce5bf50809737dea4820ce7ad0cfcdf4ebf500e06b4b2c06dea691bc428 |
| egazette-273951.pdf | https://egazette.gov.in/WriteReadData/2026/273951.pdf | No. 475: **G.S.R. 527(E)**, Employees' Pension Scheme, 2026 (context only) | 6bd9d6fb82a1e0e6efcc6dff3901485aaf101dd621a68b8592409585e18a6592 |
| egazette-272366.pdf | https://egazette.gov.in/WriteReadData/2026/272366.pdf | Gazette of India Extraordinary, Part II Sec 3(i), **No. 310**, "Friday, May 8, 2026", ID CG-DL-E-08052026-272366: **G.S.R. 344(E)**, **Social Security (Central) Rules, 2026**. Digital signature dated 2026.05.08 22:05:34 +05'30' | 3b8cd59a72eb79268ce32689fe04383938cbdfe38bb829ed567179110833bcf0 |
| labour-2026-05-49aa9b62.pdf | https://www.labour.gov.in/static/uploads/2026/05/49aa9b62c2125499c37399b90e969d67.pdf | MoLE copy of the same Gazette No. 310 (G.S.R. 344(E)); the rule 19 and rule 2(1)(g),(h) text is identical | 37c5a8cd8684e9362f86a1a72642174045a302234fa1918e8a73a30296b43878 |
| indiacode-ss-code-2020.pdf | https://www.indiacode.nic.in/bitstream/123456789/16823/1/aA2020-36.pdf | **Code on Social Security, 2020** (Act 36 of 2020), India Code text with commencement footnotes | 53ad67f75fbc874fd837274e9e887fac7a21cc55c7454398b2cdb96046fd7967 |
| egazette-273002.pdf | https://egazette.gov.in/WriteReadData/2026/273002.pdf | **S.O. 2702(E)** of 29 May 2026 (Chapter III wage ceiling; context only) | 62dbc6c22949eed3ccd0cde11488312c4db4480623078254a558770d14cb3893 |
| esic-central-rules-1950-asof-2024-04-26.pdf | https://esic.gov.in/attachments/actfile/35df0691b900c1d011c6ceb7913eb1d6.pdf | ESIC consolidated copy of the ESI (Central) Rules, 1950, "as on 26.04.2024". This is not a Gazette text and was superseded on 8 May 2026 | bf1c13836ab0d718340763e6f037efb70c74655158cd1363ceb984b06c96be5a |
| esic-gen-regs-1950-asof-2023-05-17.pdf | https://sronandnagri.esic.gov.in/attachments/publicationfile/b5492c0755e7b0f96197f413a363bc6d.pdf | ESIC consolidated copy of the ESI (General) Regulations, 1950, "as on 17.05.2023". This is not a Gazette text | 9db45feb11e4b3321b46b581d1c6f45068edc83d8e4a8b8af50a5be9bfcf3118 |

The following files were fetched while looking for Issue No. 473. None of them is G.S.R. 525(E), and they are not used as evidence:

- egazette-273939 (83f8e1cd...)
- egazette-273940 (aba39387...; No. 476, G.S.R. 528(E))
- egazette-273941 (4e1eaa23...)
- egazette-273942 (a4a61bcf...; No. 474, G.S.R. 526(E), EDLI Scheme 2026)
- egazette-273943 (decfca01...)
- egazette-273950 (733f1b27...)
- egazette-273952 (7ba16547...)
- egazette-274112 (9dfc4512...)

Third-party pages were used only to find the official documents. The Gazette ID 273957 was first seen on gazettetracker.com and then confirmed from the egazette.gov.in PDF itself. Nothing was blocked. There were no logins, CAPTCHAs, forms or terms to accept.

Code compared: `sangam/sangam-packs/tools/packgen/rounding.py` (rows `epf-2026-18-5`, `epf-1952-unverified`, `ss-2026-r19-1-a-unverified`, `ss-2026-r19-1-b-unverified`), `payroll.py` (ROWS, ESI_APPLICATION, AVERAGE_DAILY_WAGE) and `MoneyCalculationDelegate.Policy.round` (`v.divide(quantumMinor, 0, mode).multiply(quantumMinor)` on exact minor units, quantum 100, `REFUSE` for negatives).

---

## 1. Row `epf-2026-18-5` (PF contribution rounding)

- **Document**: Employees' Provident Funds Scheme, 2026. It was made by the Central Government (Ministry of Labour and Employment) under s.15(1)(a) of the Code on Social Security, 2020, "in supersession of the Employees' Provident Funds Scheme, 1952", as notification **G.S.R. 525(E)** dated 29 June 2026. It is signed by Tejaswi S. Naik, Joint Secretary (F. No. S-35025/04/2026-SS-II).
- **Publication**: Gazette of India Extraordinary, Part II Sec 3(i), No. 473, with the masthead dated **Monday, June 29, 2026**. The e-Gazette upload ID is dated 01072026, and the PDF's digital signature is dated **2026-07-01 00:40 IST**.
- **Commencement as stated**: para 1(2), "It shall come into force on the date of its publication in the Official Gazette."
- **Corrigendum**: G.S.R. 703(E) of 4 Aug 2026 corrects wording on page 76 (lines 1, 3, 4 and 22) and elsewhere. None of these corrections touches para 18(5). Para 18(5) is lines 13-14 of page 76 by layout; this line mapping is inferred, but no entry in the corrigendum mentions rounding.
- **Provision (para 18(5), p. 76)**: "Each contribution shall be calculated to the nearest rupee, with fifty paise or more to be counted as the next higher rupee and fraction of a rupee less than fifty paise to be ignored."
- **Components (para 18(1),(2))**:
  - The contribution is made up of the employer's contribution and the employee's contribution.
  - The employer's rate is 12 percent of wages payable, and the employee's contribution is equal to the employer's.
  - A proviso sets 10 percent for notified classes of establishments.
  - Para 18(3): contributions are limited to the contribution on the wage ceiling where the monthly wage exceeds the ceiling.
  - Para 18(4): contributions are "calculated on the basis of wages actually drawn or payable during the month whether paid on a daily, weekly, fortnightly or monthly basis".
- **Rounding unit and stage**: the unit is one rupee, half-up at 50 paise. It applies to **each contribution**, meaning the employer's and the employee's contribution separately, per member, on the **month's** wages (para 18(4)).
- **Related, not part of this row**: EPS 2026 (G.S.R. 527(E)) para 4(3) separately rounds each contribution to the Pension Fund (the 8.33 percent part of the employer's share and the 1.16 percent Central Government share) to the nearest rupee with the same 50-paise rule. EPF Scheme para 41(2) rounds account items, and para 42 rounds interest.

### Comparison with the pack

| Aspect | Pack | Primary text | Result |
|---|---|---|---|
| Mode | HALF_UP, quantum 100 minor units (Rs 1) | nearest rupee, 50 paise or more up, below 50 paise ignored | **match**: HALF_UP on exact paise reproduces this, including sub-paisa fractions |
| Application | each contribution (employee share, employer share) separately | "Each contribution" | **match** |
| Stage | per employee per wage period | per member, on wages of the month (18(4)) | **match only when the wage period is the calendar month**. For weekly or fortnightly wage periods, the text requires the monthly total, not a per-wage-period rounding. Not verified how the pack treats a non-monthly wage period |
| Negatives | REFUSE | text silent | no conflict |
| Citation | G.S.R. 525(E) of 29 June 2026, para 18(5) | same | **correct citation**: G.S.R. 525(E) is the EPF Scheme 2026, and para 18(5) is the rounding provision |
| Effective from | frm 2026-06-29 | in force "on the date of its publication"; Gazette dated 29 June 2026; e-copy signed 1 July 2026 | **29 June 2026 is the Gazette date** (see the confidence note) |
| Previous row `epf-1952-unverified` to 2026-06-28 | consistent with a 29 June start | 1952 Scheme provision not read in this lane | not checked |

### Worked boundary examples (12 percent, each share separately)

| PF wages (Rs) | Exact share (Rs) | Para 18(5) (Rs) | Pack HALF_UP/100 (Rs) |
|---|---|---|---|
| 15,000.00 (exact rupee) | 1,800.00 | 1,800 | 1,800 |
| 12,345.00 | 1,481.40 | 1,481 | 1,481 |
| 12,337.50 (exactly 50 paise) | 1,480.50 | 1,481 | 1,481 |
| 12,346.00 | 1,481.52 | 1,482 | 1,482 |
| 4.12 (sub-paisa fraction) | 0.4944 | 0 (below 50 paise) | 0 |
| 0 | 0 | 0 | 0 |

Each share is the same figure, rounded on its own. The pension split (8.33 percent of Rs 15,000 = 1,249.50, which rounds to 1,250 under EPS para 4(3)) is a separate rounding and is not modelled by this row.

### Verdict

**CONFIRMED-FROM-PRIMARY** for the citation, the provision, the rounding mode and unit, and the per-contribution application.

Two points are open:

- **(a) Stage.** The text sets the unit as the month (18(4)). The pack row says "per wage period", which is the same thing only for monthly payroll.
- **(b) Effective date. Confidence below 97 percent.** Should the date be 29 June 2026 (the Gazette date) or 1 July 2026 (the date the e-Gazette was signed and uploaded)? The text says only "date of its publication". The Gazette is dated 29 June, but the electronic copy was signed at 00:40 IST on 1 July. Treating the printed Gazette date as the publication date is the usual reading, but which date counts is a legal question this lane cannot settle. The owner should decide it, and the row should record both dates.

Restoring VERIFIED also needs the other decision 288 steps (impact check, 1952-period row), which are outside this lane.

---

## 2. Row `ss-2026-r19-1-a` (ESI employer rounding; in code `ss-2026-r19-1-a-unverified`) and its sibling `ss-2026-r19-1-b-unverified`

- **Document**: Social Security (Central) Rules, 2026. They were made by the Central Government (MoLE) under ss.154, 155, 158 and 159 of the Code on Social Security, 2020, as notification **G.S.R. 344(E)** dated 8 May 2026 (draft G.S.R. 935(E) of 30 Dec 2025). Signed by Ashutosh A.T. Pednekar, Jt. Secy. (F. No. R-11011/03/2020-SS-II).
- The preamble supersedes, among others, "(iii) Employees' State Insurance (Central) Rules, 1950".
- **Publication**: Gazette of India Extraordinary, Part II Sec 3(i), No. 310, **Friday, May 8, 2026**. The e-Gazette ID is dated 08052026, and the signature is dated 2026-05-08 22:05 IST. The three dates agree.
- **Commencement as stated**: rule 1(2), "They shall come into force on the date of their publication in the Official Gazette." This is **2026-05-08**.
- **Provision (rule 19(1), pp. 159-160)**: "The amount of contribution for a wage period shall be in respect of — (a) employer's contribution, a sum (rounded to the next higher rupee) equal to three and one-fourth per cent. of the wages payable to an employee; and (b) employee's contribution, a sum (rounded to the next higher rupee) equal to three-fourth per cent. of the wages payable to an employee."
- **Components**:
  - The employer's share is 3.25 percent and the employee's share is 0.75 percent of wages payable, each a separate "sum" rounded separately.
  - Rule 19(2),(3): the employer's share is waived for up to 3 years for persons with disability and reimbursed by the Central Government. This is not modelled.
- **Rounding unit and stage**: the next higher rupee, applied to each share, per employee, per **wage period**.
- The wage period is "the unit as specified in the regulations" (Code s.29(3)).

### Comparison with the pack

| Aspect | Pack | Primary text | Result |
|---|---|---|---|
| Mode | CEILING, quantum 100 | "rounded to the next higher rupee" | **match** for fractional amounts. For an exact-rupee amount, CEILING leaves it unchanged. The text does not expressly address an exact rupee; reading "rounded" as applying only to a fraction is the natural reading, but it is an interpretation |
| Application | employer share and employee share, each separately | (a) and (b) each "a sum (rounded ...)" | **match** |
| Stage | per employee per wage period | "for a wage period", "wages payable to an employee" | **match** |
| Rates (DMN) | 3.25 / 0.75 | 3.25 / 0.75 | **match** |
| Citation | G.S.R. 344(E) of 8 May 2026, rule 19(1)(a) / (b) | same | **correct citation**. G.S.R. 344(E) is the right instrument for ESI rounding from 8 May 2026. The provision text in the code ("could not be read reliably (font encoding)") is out of date, because the English text reads cleanly |
| Owner's "Rule 51" | row says "before it, ESI (Central) Rules 1950 r.51 (not read)" | r.51 of the 1950 Rules was **superseded on 8 May 2026** by G.S.R. 344(E) | Rule 51 does not apply to periods from 8 May 2026. For earlier periods, see the note below |
| Effective from | **no frm** (NO_EFFECTIVE_DATE) | 2026-05-08 | the row lacks the date that decision 280 requires; the primary text gives **2026-05-08** |
| Negatives | REFUSE | silent | no conflict |

**Pre-8-May-2026 note.** The ESIC consolidated copy of the 1950 Rules (as on 26.04.2024) shows rule 51 as 4.75 / 1.75 percent, "rounded off to the next higher rupee multiple of five paise". Those rates do not match the 3.25 / 0.75 rates commonly reported as in force since 2019. This copy is therefore not a reliable statement of rule 51 as it stood before 8 May 2026, and the amending notification was not read. Rounding for periods before 8 May 2026 is **NOT-FOUND (not verified)**.

### Worked boundary examples (each share separately)

| ESI wages (Rs) | Employer 3.25% exact → r.19(1)(a) | Employee 0.75% exact → r.19(1)(b) | Pack CEILING/100 |
|---|---|---|---|
| 10,000.00 (exact rupee result) | 325.00 → 325 | 75.00 → 75 | 325 / 75 |
| 10,001.00 | 325.0325 → 326 | 75.0075 → 76 | 326 / 76 |
| 15,500.00 | 503.75 → 504 | 116.25 → 117 | 504 / 117 |
| 0.01 | 0.000325 → 1 | 0.000075 → 1 | 1 / 1 |
| 0 | 0 → 0 | 0 → 0 | 0 / 0 |

The 10,001 example shows why separate rounding matters. Combined at 4 percent, the amount would be 400.04, rounded up to 401. Rounded separately, as rule 19 requires, the shares are 326 + 76 = 402.

### Verdict

**CONFIRMED-FROM-PRIMARY** for the citation (G.S.R. 344(E), rule 19(1)(a) and (b)), the rates, the CEILING to the rupee, the separate shares, the per-wage-period stage and the effective date of 2026-05-08. These remain open:

- **MISMATCH (data)**: the row has no effective date. It should be 2026-05-08, and decision 280 requires the date.
- **Stale provision text**: the row says the text could not be read; it now has been.
- **Exact-rupee reading** (above): this is interpretation, not explicit text.
- **Not covered**: periods before 8 May 2026 (NOT-FOUND) and the waiver for persons with disability.

---

## 3. ESI items of decisions 270, 271, 281, 282 and 283 (`payroll.py`)

Where the provisions sit after 8 May 2026:

- **Code s.29(3)**: the wage period is "the unit as specified in the regulations".
- **Code s.29(4)**: contributions "ordinarily fall due on the last day of the wage period". For part-period or multi-employer employment, the due days are "as may be specified in the regulations".
- **Rule 2(1)(q)**: the contribution period is "the period not exceeding six consecutive months, as specified in the regulations made under section 157".
- **ESI regulations**: no final ESI regulations under s.157 of the Code were located. Third parties report only a *draft* ESI (General) Regulations, 2026. The 1950 ESI (General) Regulations survive only through Code s.164(2)(a) ("to the extent they are not contrary").
- **Code s.164(2)(b)** (one-year survival of ESI rules and regulations): India Code's footnote lists s.164(2)(a) and (c), but not (b), among the provisions brought into force on 21 Nov 2025 (S.O. 5319(E)).
- Whether the 1950 Regulations still apply is a legal question this lane cannot settle. **Confidence is below 97 percent.**

### 3.1 Average daily wage for the employee low-wage exemption (decision 271(2))

- **Primary text (G.S.R. 344(E), rule 2(1)(h))**: "average daily wages during a wage period" means:
  - (i) for an employee on a time-rate basis: "the amount of wage which would have been payable to him for the complete wage period had he worked on all the working days in that wage period, divided by 26 if he is monthly rated, 13 if he is fortnightly rated, 6 if he is weekly rated and 1 if he is daily rated";
  - (ii) for any other basis: the wages earned in the complete wage period divided by the days (full or part) worked for wages. A proviso deems 26, 13, 6 or 1 days where wages are received without working.
- Rule 2(1)(g) separately defines the average daily wage for a *contribution period*: wages payable divided by the days for which wages were payable.
- **Pack**: `TIME_RATED=WAGES_PER_PAID_DAY`, meaning ESI wages divided by paid days (`esiDays`), with other bases refused.
- **Verdict: MISMATCH.** For time-rated pay, the rule uses the *full-period rate* (as if every working day had been worked) divided by a fixed 26, 13, 6 or 1. It does not use wages actually payable divided by paid days. Examples (monthly rated, rate Rs 4,680, 26 working days):
  - Full month, `esiDays` = 30 calendar paid days. Pack: 4,680 / 30 = 156.00. Rule: 4,680 / 26 = 180.00.
  - 4 days unpaid leave, wages 3,960, `esiDays` = 26. Pack: 152.31. Rule: still 180.00, because the rule ignores leave.
  - Against an assumed Rs 176 limit, the pack exempts the employee and the rule does not.
- Piece-rated pay (2(1)(h)(ii)) has a defined method. The pack's refusal of it is safe, but it is not the defined rule.
- **The exemption itself is NOT-FOUND for periods from 8 May 2026.**
  - Old rule 52 of the 1950 Rules read: "The average daily wages during a wage period for exemption from payment of employee's contribution under section 42 shall be upto and inclusive of rupees one hundred seventy-six only." That was the ESIC consolidated copy, superseded on 8 May 2026.
  - G.S.R. 344(E) contains no exemption threshold.
  - Code s.2(31) defines a Chapter IV "exempted employee" as one "whose wage is specified in the notification by the Central Government and who is not liable to pay employee's contribution". No such notification was located.
- The pack's AT_OR_BELOW comparison matches the old rule 52 wording "upto and inclusive", but no current source for Rs 176 was found.

### 3.2 Wage period spanning a contribution-period boundary (decision 271(4))

- **NOT-FOUND.** None of the following addresses a wage period that falls partly in two contribution periods: Code s.29, G.S.R. 344(E), or the 1950 Regulations copy read (reg. 4 contribution periods 1 Apr-30 Sep and 1 Oct-31 Mar; regs. 36, 38, 39).
- The pack's refusal (`WAGE_PERIOD_CROSSES_CONTRIBUTION_PERIOD_UNVERIFIED`) is consistent with the evidence.
- The pack's contribution-period starts `04-01,10-01` match reg. 4 of the 1950 Regulations, but whether that regulation still applies under the Code is not confirmed.

### 3.3 Partial-month wage period (decision 281)

- **NOT-FOUND (current).** Code s.29(4) leaves part-period due dates to the regulations. The old reg. 36 (due on the last day of employment in that wage period) and reg. 39 (for a part-period employee, the average daily wage counts only wages up to the due day) are 1950 texts whose current applicability is unconfirmed.
- Rule 19 levies on "wages payable" for the wage period and does not prorate.
- The pack's refusal is consistent with the evidence.

### 3.4 Joiner inside a wage or contribution period (decision 283; pack `esiMidPeriodJoiner=COVERED_TO_PERIOD_END`)

- **NOT-FOUND (current).** The only text found is the old reg. 4 proviso: for a first-time employee "the first contribution period shall commence from the date of such employment in the contribution period current on that day". That provision is about benefit periods, is a 1950 text, and does not state "covered to period end".
- The pack's joiner rule has no primary support. The pack's BLOCKED status is consistent with the evidence.

### 3.5 Coverage ceiling and continuation after crossing it (decisions 270 and 282)

- **Comparison direction, CONFIRMED-FROM-PRIMARY.** Code s.2(26) first proviso: for Chapter IV, "employee" means an employee "drawing wages less than or equal to the wage ceiling notified by the Central Government". The pack treats wages *above* the limit as not covered, which matches.
- **Ceiling amount (Rs 21,000), NOT-FOUND (current).** Old rule 50 of the 1950 Rules set Rs 21,000 and was superseded on 8 May 2026. S.O. 2702(E) (read: Rs 15,000) is expressly "for the purposes of Chapter III" only. No Chapter IV notification under s.2(89) was located.
- **Continuation, NOT-FOUND (current).**
  - The old rule 50 proviso read: an employee whose wages "exceed twenty-one thousand rupees a month at any time after and not before the beginning of the contribution period, shall continue to be an employee until the end of that period". It was superseded on 8 May 2026.
  - The Code and G.S.R. 344(E) contain no continuation provision.
  - As a historical comparison only: the old proviso keys continuation on wages being within the limit at the start of the contribution period. The pack keys it on `esiCoverageStart` being on or before the period's first day. These are not the same test.

---

## 4. Summary of verdicts

| Item | Verdict |
|---|---|
| epf-2026-18-5: G.S.R. 525(E) is the EPF Scheme 2026, and para 18(5) is the contribution-rounding provision | CONFIRMED-FROM-PRIMARY |
| epf-2026-18-5: HALF_UP to the rupee, each contribution separately | CONFIRMED-FROM-PRIMARY (matches the pack) |
| epf-2026-18-5: stage | MISMATCH for non-monthly wage periods only: para 18(4) works on wages of the month; the pack says per wage period |
| epf-2026-18-5: date 2026-06-29 | Gazette masthead 29 June 2026, but the e-Gazette was signed and uploaded on 1 July 2026; the legal publication date is below 97 percent, owner to decide |
| ss-2026-r19-1-a (and r19-1-b): G.S.R. 344(E) rule 19(1)(a)/(b), next higher rupee, shares separate, per wage period, rates 3.25/0.75 | CONFIRMED-FROM-PRIMARY |
| ss-2026-r19-1-a: effective date | MISMATCH (row has none); primary text gives 2026-05-08 |
| ESI Rule 51 (1950) | superseded 8 May 2026; for periods before that, NOT-FOUND (the ESIC copy read is inconsistent) |
| ESI average daily wage, time-rated (271) | MISMATCH: rule 2(1)(h)(i) uses the full-period rate divided by 26/13/6/1, not wages per paid day |
| ESI low-wage exemption threshold (Rs 176) after 8 May 2026 | NOT-FOUND (old r.52 superseded; no s.2(31) notification located) |
| ESI wage period across a contribution-period boundary (271(4)) | NOT-FOUND (pack's block consistent) |
| ESI partial-month wage period (281) | NOT-FOUND (pack's refusal consistent) |
| ESI mid-period joiner (283) | NOT-FOUND (pack's block consistent) |
| ESI ceiling comparison (≤ ceiling covered) | CONFIRMED-FROM-PRIMARY (Code s.2(26)) |
| ESI ceiling amount (Rs 21,000) and continuation (270/282) after 8 May 2026 | NOT-FOUND (old r.50 superseded; no successor located) |

Nothing was BLOCKED.

Out of scope for this lane:

- the decision 288 impact check (whether these rows fed any finalised calculation);
- the 1952 Scheme rounding row;
- whether the 1950 ESI Regulations still apply under Code s.164.
