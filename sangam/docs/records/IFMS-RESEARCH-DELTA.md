# IFMS research delta (pass 2, 2026-11-05)

This lists the values that changed in `IFMS-STATES-REQUIREMENTS.md` and `IFMS-CENTRAL-REQUIREMENTS.md` after the official-source follow-up. It is meant for the coder updating the profiles. Each line gives the requirement id, the old value → the new value, and the status: V = verified, P = partial, [U] = still a placeholder. Sources and dates seen are in the two documents (states §9, central §1 S45–S58). Values still marked [U] stay configuration placeholders. Nothing is committed.

## A. Central (IFC-*)

| Id | Was | Now | St |
|---|---|---|---|
| §2.1 registry seed | AoBR to Series 351 (2019); count [U]; "54 / 93" quoted | AoBR to **Series 386 (22-07-2026)**: **53 ministries** (34 with no departments, 19 with), **53 departments**, **7 standalone units** (DAE, DoS, Cabinet Sectt, President's Sectt, PMO, NITI, NSCS), **94 business units**. Do not use "54 / 93". Add MHA Internal Security, States, and J&K & Ladakh Affairs (not in IGOD). Vice-President's Sectt comes from DFPR 3(1)(e) only | V |
| §2.1 new departments | Capital Development / Urban Development / Border Management [U] | All three exist. Capital Dev. and Urban Dev. under MoHUA from **22-07-2026** (effective-dated registry entries). Capital Dev. owns CPWD, DDA, Union properties, civil works | V |
| IFC-018 / IFC-033 / IFC-034 | DFPR amendment 09-06-2026 not read | Annexure-I object heads replaced by a **61-head / 8-class** harmonised list **from 01-04-2027** (FY 2027-28). Version the object-head master by FY and supply an old → new map. Check whether the seeded IFC-033 list is pre- or post-2027 | V |
| IFC-027 | Works Manual 2nd Ed. 2025 [U] | Exists (DoE, listed 05-01-2026; amended 18-06-2026). CPWD after 2022 still [U] | V / [U] |
| IFC-044 | FA appendices by 03-10-2025 [U] | BE 2026-27 circular 28-08-2025: appendices I–VII in UBIS by **03-10-2025**. BE 2027-28 deadline [U] | V |
| IFC-052 | vote on account [U] | GFR 55 (no New Service) and 67(2) (Contingency Fund advance beyond the provision) | V |
| IFC-053 | 16th FC from 2026-27 [U] | **2026-27 to 2030-31**. No carry-over of 15th-FC liabilities unless permitted | V |
| IFC-071 | ceilings [U] | Rule: GFR 62(3)–(4) + DEA OM 25-05-2022, MEP/QEP in UBIS. **Percentages still [U]**: keep configurable, default unset | P |
| IFC-113 / S12 | e-Bill effective 05-08-2024 [U] | 05-08-2024 | V |
| IFC-123 | TDS / GST-TDS [U] | GST-TDS head **8658-00-139**. Rates [U] | P |
| IFC-140 | DDO reconciliation [U] | Monthly certificate **CAM-90**, by the last day of the following month (CAM 2.19.12, GFR 57(5)); checks PAO-01 and CMP-03 | V |
| IFC-161 | DoE OM 01-2024 [U] | No such OM. Use the OMs of 20-05-2022, 07-07-2022, 14-09-2022 and 30-11-2022; for central sector schemes use the CNA master circular 21-05-2024 paras 47, 52–53 | S/P |
| IFC-163 | GFR form [U] | **No GFR form** for the grantee asset register (GFR 230(9) bars disposal without approval). GFR 21 = grants register; GFR 22 = government fixed-asset register | V |
| IFC-164 | NGO-Darpan [U] | Not in GFR; it is a NITI Aayog requirement (Darpan UID captured in PFMS) | P |
| IFC-173 UPS | 10 / 25 years [U] | Switch to NPS at the latest **12 months** before superannuation (3 months before deemed VR); not while proceedings are pending. Full payout from **25 years**; pro-rata from 10 to 25; minimum ₹10,000 after 10 years (FAQ, S); 50 % of the 12-month average basic | V |
| IFC-174 pension timers | approx. secondary values | Start T−12 months; verification T−8m; Forms 4/6/6-A by **T−6m**; Form 7 by T−4m; to the AO by **T−4m**; PPO **T−2m**; AO → CPAO within 2 months; CPAO → bank within **21 days** (CCS (Pension) Rules 2021 r.56–63) | V |
| IFC-178 | Jeevan Pramaan [U] | Accepted, including face authentication (CPAO booklet 2026) | V |
| IFC-179 pension DMN | 50 % placeholder | r.44: 10 years' qualifying service; **50 % of emoluments or of the 10-month average, whichever is more beneficial**; **min ₹9,000, max ₹1,25,000**; gratuity below 10 years. Commutation 40 % per the 1981 Rules (rule number [U]) | V |
| IFC-184 | 8th CPC [U] | Constituted 03-11-2025; report due ~May 2027 | V |
| IFC-188 LTE | ₹1 crore [U] | **₹50 lakh general**; **₹1 crore+ only for scientific departments** (DST, DBT, DSIR, DAE, DoS, MoES, DRDO, DHR/ICMR) and research institutions. Key the threshold on department class | V (corrected) |
| IFC-202 | 15-04 / 30-04 / 03-09-2026 [U] | Sy-I **30-04-2026**, late adjustments 06-05-2026. Prelim and Sy-II [U] | P |
| IFC-217 | sampling [U] | PAO passes bills in **3 working days**; concurrent post-check in IAOS. Sampling % [U] | P |
| IFC-227 | PLI [U] | MH **8014** (SMH 01 PLI / 02 RPLI; minor 101–108), investments in 8015; only DoP may book 8014–8016 | V |
| IFC-237 | UT accounts via MHA PAOs [U] | **Corrected**: each UT reports to the CGA directly: DNH&DD Director of Accounts, CPAO Port Blair, AG (A&E) Chandigarh, Ladakh FD & Treasuries, Lakshadweep Pay & Accounts | P (corrected) |
| IFC-250 | own calendars [U] | Non-civil departments follow the same CGA March calendar | S |
| IFC-255 | retention [U] | GFR 320 + App. 9 (sanctions 2 years / 1 year after audit; DDO cash book 10 years; contingent 3 years / 1 year after audit; hold anything under audit objection) | V |
| §6.9 charged | [U] | Art. 112(3)(a)–(g) list | V |
| Unchanged [U] | — | IFC-214 FRBM statements, IFC-219 ATN months, IFC-254 GPF days, S23, S41 (replaced by S45), Railways / Defence / DAE / DoT books | [U] |

## B. States: field values (§1 rows; ids IFS-<CODE>-nnn)

| State | Field / id | Was | Now | St |
|---|---|---|---|---|
| GA | systems | [U] | **Accounts-Online** (IBPS, e-Pagaar20, E-DDO, e-Challan SBI MOPS, Treasury Accounting, Works Audit) (IFS-GA-006 new) | V |
| GA | topology | 2 DT + taluka ST | Treasury Offices Porvorim + Margao; **10 sub-treasuries** (Panaji, Mapusa, Bicholim, Pernem, Valpoi, Vasco, Ponda, Sanguem, Quepem, Canacona); ~550 DDOs | V |
| GA | rules | [U] | Goa DFPR 2008 (amended 2023) (P); Budget Manual (P); GFRBM 2006 / 2007 (V); central GFR (P). Treasury Rules [U] (IFS-GA-007 new) | P |
| GA | rail | [U] | SBI e-payment + link banks; e-Kuber integrated | V |
| GA | HoA | [U] | MH4-SMH2-MinH3-SH2 (`2225-03-277-09`); lower widths [U] (IFS-GA-008 new) | V (partial) |
| GA | language (IFS-GA-005) | Konkani, English | Konkani (Devanagari) + Marathi permitted + English | V |
| AR | rules | CTR + GFR | + **no state Budget Manual**; procurement adopts GFR r.149 | V / P |
| AR | HoA | [U] | MH4-SMH2-MinH3-SH2-OH3 (`4217-01-800-03-051`) | P |
| AR | rail | eK [U] | e-Kuber integrated | V |
| AR | language | English [U] | English | V |
| MZ | systems | COMPACT… | + eGPF, GST-GRAS; AG on VLC | V |
| MZ | rules (IFS-MZ-004) | DFPR adaptation [U] | DFPR 1978 by adaptation; HoO via r.14 schedules I–III | P |
| MZ | IFS-MZ-005 | MADC 1996 [U] | MADC 1996 → **Mizoram ADC Fund Rules 2010** | V |
| MZ | procurement | — | Mizoram Transparency in Public Procurement Act 2008 (am. 2019) | P |
| MZ | HoA | [U] | MH4-SMH2-MinH3-SH2-DH2-OH2 (15) | V |
| MZ | rail / language | eK [U]; Mizo, English [U] | e-Kuber integrated; Mizo, English, Hindi | V |
| DH | systems (IFS-DH-005 new) | [U] | e-Lekha; **two PAO units still separate** (Silvassa, Daman); district treasuries Daman, Diu | V |
| DH | rules (IFS-DH-006 new) | [U] | DFPR + GFR + CTR + FR; CCS (Pension) via CPAO; draft Panchayat Accounts Rules 2024 | V |
| DH | budget | Demand 54 | BE 2026-27 ₹2,835.20 cr gross | V |
| DH | language (IFS-DH-004) | [U] | Gujarati (D&D 1987 Act) + Hindi / English | P |
| all | rail (§2 RBI) | per-state list [U] | **All states on RBI e-payments except Sikkim**. Set `rail.eKuber=true` for every state except SK | V |
| MH | SS | [U] | onboarded; all CSS from 01-11-2025 | P |
| MH | HoA | [U] | demand letters + MH4-SMH2-MinH3-SH2-DH2-OH2; BEAMS 8-digit = MH + serial | V |
| MP | rules | [U] | + Bhandar Kray tatha Seva Upaarjan Niyam 2015 (P); Finance Powers Manual 2025 [U] | P |
| CT | topology | counts [U] | 34 treasuries | V |
| CT | procurement | — | Bhandar Kray Niyam 2002, consolidated to 10-12-2025 | V |
| CT | HoA | [U] | Demand2-MH4-SMH2-MinH3-Scheme4-tag4-OH2-DOH3 | P |
| UK | HoA | [U] | 2-2-2 (15) | V |
| BR | HoA | — | **DD2-MH4-SMH2-MinH3-SH4-DH2-SDH2 + V/C** (19 + flag) | V |
| JH | topology | Directorate [U] | **no directorate**: Finance Department (set topology depth like BR) | V |
| JH | rules / procurement | JFR | + Jharkhand Financial Rules 2025 (GeM r.165; notification [U]) | V (doc) |
| JH | HoA | — | DD2-letter-MH4-SMH2-MinH3-SH2-DH2-OG2-OH2 | V |
| PB | HoA / procurement | — | 15 (SH.DH.OH); PTPP Act 2019 + Rules 2022 | V / P |
| HR | HoA | — | [Part2-DD2-MH4-SMH2-MinH3-SH2-DH2] + OH2 (19) | V |
| HP | HoA | DDO × SOE | MH4-SMH2-MinH3-SH2-Scheme4 + SOE2 | P |
| KA | HoA (IFS-KA-003) | 15, 2-2-2 implied | **SH1-DH2-OH3** | V (corrected) |
| TN | HoA | [U] | SH2 letters-Group1-DH2-SDH2 (16); SDH 51–99 = charged | V |
| TN | rules | [U] | TN Treasury Code Vol I 7th edn; Financial Code under rewrite (2024) | V |
| KL | HoA | [U] | **17**: MH4-SMH2-MinH3-SH2-SSH2-DH2-OH2 | V |
| KL | language | [U] | Malayalam + English | V |
| AP | topology | 13 DT | **26 DT + Capital Region Treasury + 196** | V |
| AP | IFS-AP-007 CBRO | [U] | four quarters released at once → HoD CBDO → DDO | P |
| TG | SS | [U] | G.O.Ms.76 / 77 of 22-05-2024 | V |
| WB | IFS-WB-001 | 2nd cyber treasury [U] | **Cyber Treasury-II, code CAH** (11-09-2025) | V |
| WB | procurement | — | GeM mandatory from Sept 2026; WB PWD Code + WBWD Manual | V / P |
| OD | rules | OGFR to 04-06-2025 | + amendment 16-03-2026 (No. 6904) | V |
| AS | HoA / procurement | [U] | MH4-SMH2-MinH3-SH4-DH3-OH2-SDH2 (20) (P); Assam Public Procurement Act 2017 + Rules 2020 (P) | P |
| SK | HoA | [U] | 2-2-2 (15) | V |
| SK | IFS-SK-008 | 371F [U] | Art. 371F(k) | V |
| MN, NL, TR | HoA | [U] | 15 digits (NL: SH2-SSH2-DH2, last tier = object) | V |
| TR | IFS-TR-004 | 1982 rules [U] | TTAADC (Budget & Account) Rules 1982 | V |
| TR | procurement | — | Manual for Procurement of Goods 2018 | V |
| DL | HoA / language | — | Union 15; + Punjabi, Urdu second official (OL Act 2000) | V |
| PY | rules | [U] | GFR 2017, DFPR, R&P Rules 1983, CTR, Accounting Rules for Treasuries 1992 | V |
| JK | language (IFS-JK-006) | [U] | Kashmiri, Dogri, Urdu, Hindi, English (OL Act 2020) | V |
| LA | language | [U] | English, Hindi, Urdu, Bhoti, Purgi (Regulation 2025; commencement [U]) | V |
| CH | IFS-CH-002 | 01-04-2022 [U] | Chandigarh Employees (Conditions of Service) Rules 2022 | V |
| CH, LD | language | [U] | English (CH: no OL Act) | V |
| AS, BTC | IFS-AS-001 | BTC Fund Rules 2012 | confirmed (notified Sept 2012) | V |
| IFS-004 | 8xxx [U] | **8000 = Contingency Fund; 8001–8999 = Public Account** | V (corrected) |
| IFS-001 | sub-head and below state-owned [U] | each government sets them (LMMH GD 1.1); **the segment definition must be per state** (widths 1–4; extra prefixes: demand, part, letter; suffix V/C) | V |

**Language model change (IFS-072):** a language entry is now `{lang, script, scope: statewide | districts[], effectiveFrom, docClass: statutory | budget | accounts}`. Full per-state values are in states §9.1.

**Procurement pack choice (IFS-031):** one of three families: a state Act, a financial-rules chapter, or central GFR + CPWD. Per-state values are in states §9.3.

## C. Still placeholders (do not hard-code)

- **Code structures:** GJ, WB, TG, OD, ML, JK and LA have none; GA below the sub-head; AP's 7-tier example not re-confirmed.
- **Budget-document languages:** [U] for 27 entities.
- **Procurement:** TG procurement rules.
- **Works manuals:** GJ, HP, CT, ML, MN, MZ, TR, AR and LD.
- **SNA-SPARSH dates:** KL and JK.
- **Chandigarh:** treasury software.
- **Rush-of-expenditure percentages.**
- **Accounts calendar:** March Prelim and Sy-II dates.
- **e-Bill post-check:** sampling percentage.
- **Timers:** ATN months; GPF days.
- **Pension:** commutation rule number.
- **Manuals:** CPWD after 2022.
