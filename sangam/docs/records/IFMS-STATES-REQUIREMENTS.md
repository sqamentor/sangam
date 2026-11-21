# IFMS for every State and UT: requirements, variations, gaps and the state profile model

Workstream: IFMS-STATES (research only; no product code; nothing committed).
Date of research: **2026-11-04** (pass 1); follow-up pass 2 on **2026-11-05** (official sources only; see §9 and §10).
Inputs read first: `SANGAM.md` IX.3–IX.7e; `spike/GOV-PATTERNS.md` (GP-01…GP-55); `sangam/sangam-packs/README.md` (51 packs, tenant DMN overrides GP-37, works TS/AA switch); `spike/results/GP-coverage-validation.md`.

**Method.**
- Seven parallel research passes were run: six regional groups (A West/Central, B North, C South, D East, E North-East, F UTs) and one central pass (G: LMMH, SNA-SPARSH, e-Kuber, PFMS, GFR/DFPR, CSS sharing, Sixth Schedule).
- Sources were official only: *.gov.in, *.nic.in, cag.gov.in (and its CDN, d1jmc0u14z2tmp.cloudfront.net), doe.gov.in, cga.gov.in/cga.nic.in, rbi.org.in, indiabudget.gov.in and niti.gov.in.
- The richest per-state evidence came from three kinds of document: the **CAG / AG (A&E) "Annual Review on the Working of Treasuries"** reports, the **state Finance Department decks at the CAG Conference of State Finance Secretaries (19-09-2025)**, and the Odisha IFMS 3.0 RFP. These were downloaded and text-extracted.
- No login was used and no CAPTCHA was bypassed.
- Some sites refused or failed connections: megfinance.gov.in, nifims.nagaland.gov.in, cfms.bihar.gov.in, koshvani.up.nic.in, jkdat.nic.in, accounts.goa.gov.in and treasury.telangana.gov.in (TLS errors). Items that depend on those sites are [U].
- The shared web-search budget ran out part-way through groups A, B and F. As a result Goa, DNH&DD and parts of MP, Chhattisgarh and Bihar are thinner.

**Legend.**
- **V**: verified on an official page or document.
- **P**: partly verified, meaning an official search excerpt only, or only some of the sub-items were confirmed.
- **[U]**: unverified. This covers secondary sources, inference, blocked sites and scanned PDFs that could not be read.

Quotes are kept to a minimum. The detailed per-field evidence tables (with every URL) were produced by the passes. The essentials, with their sources, are carried into §1 and §7.

---

## 0. Summary

**Coverage.**

| Measure | States (28) | UTs (8) | Total (36) |
|---|---|---|---|
| Profiled (at least the IFMS/treasury identity found officially) | 28 | 8 | 36 |
| **Verified profile** (≥ 5 of the 8 fields V or P) | **25** | **7** | **32** |
| Thin profile (< 5 of 8): Goa 3, Arunachal 4, Mizoram 4 / DNH&DD 2 (pass 1). **After pass 2: none** (GA 7, AR 7, MZ 7, DH 6) | 3 → 0 | 1 → 0 | 4 → 0 |
| Fields V or P, of 8 per entity | 185 / 224 (83 %) → **194** (87 %) | 51 / 64 (80 %) → **55** (86 %) | 236 / 288 (82 %) → **249** (86 %) |

The weakest field everywhere was **languages of official finance documents**. In most states only the UI or site language could be confirmed, not a finance-document language policy. The next weakest fields were the **budget-code digit structure** and **procurement / works codes**. *Pass 2 (2026-11-05)* filled these three fields for most entities; see §9. The statutory language is now V or P for all 36, the code structure for 27, and the procurement rule for 35. The V/8 counts above were recomputed only for the four thin rows.

**What every state has in common.**
1. **The LMMH layer is central.** Major, Sub-Major and Minor heads are fixed by CAG/CGA under Art. 150, with correction slips still being issued; the latest, CS 1128–1132 of 16-10-2026, opened MH 2246 and 4245. Each state extends the classification below Minor head with its own widths, from 12 digits up to 17 digits.
2. **SNA-SPARSH is now universal.** All centrally sponsored schemes (CSS) run through it in every state and in the UTs with a legislature from **01-11-2025** (DoE OM of 10-06-2025). Its features are fixed: a Mother Sanction per SLS, which lapses at 23:59 on 31 March; a 15:00 PFMS cut-off; a reissue tag on re-sent payments; and the State Treasury as the sole reconciliation unit.
3. **RBI e-Kuber is the dominant payment rail, with real exceptions.** Sikkim has no RBI government business; the State Bank of Sikkim pays by cheque. HP's IFMS had no e-Kuber link at the 2024-25 review. Nagaland and parts of Maharashtra are migrating from agency-bank CMP to e-Kuber.
4. **Every IFMS treats the sanction and the budget authorisation as objects that come before the bill.** Examples: Karnataka e-Sanction, Odisha IFMS sanction orders, WB AAFS, Manipur and J&K BEAMS slips, Mizoram e-Sanction.
5. **Public-account sub-ledgers carry large sums, and CAG repeatedly flags weak controls on them.** These include PD/PL/PLA accounts, Kerala Plan TSB, the Maharashtra Virtual PLA and the AP Green Channel.

**The variations that matter** (each one is configuration, never code):
- Treasury topology. Bihar has no directorate. The topology includes cyber, GST, SNA/CAU and Huzur treasuries, pension treasuries, out-of-state and multi-government treasuries (Shillong South serves 4 governments), and PAO-only UTs (Delhi, A&N, Lakshadweep).
- Head-of-account length, and the level at which budget is controlled (object head, SOE, DDO).
- Bill catalogues keyed to state form numbers: MTR-xx, MPTC-xx, HPTR-5, BTC Form-n, Odisha's 20 bill types, J&K's 37.
- Rulebooks. Some are inherited: Haryana uses Punjab's FR/TR, and Meghalaya inherited the Assam Budget Manual. Some are central rules "as adopted": NE states use CTR and GFR.
- Sub-state fund entities: Sixth Schedule ADCs, LAHDC, GTA, Manipur hill councils, Kerala's 16,550 virtual DDOs.
- Languages: 15+ scripts, including RTL Urdu in J&K.

**Top gaps against the existing packs** (detail in §5):
1. There is no **state profile** artefact. `tenantOverrides` supports only `kind: DMN`.
2. The head of account is a free string (`budgetHead: "4059-01-051"`). There is no segmented chart of accounts and no LMMH reference set.
3. There is no **SNA-SPARSH** lifecycle, and no **payment-rail** model covering e-Kuber, agency bank, state-owned bank and cheque/LoC.
4. There is no **treasury-unit topology** and no treasury scrutiny / objection / token stage.
5. There is no **bill-type catalogue**, no **AC/DC or UC tracking**, and no **public-account sub-ledgers**.
6. There are no **sub-state fund entities**.
7. Forms are **English and Hindi only**.
8. The delegation DMN keys only on role, case type and amount. It has no office tier, object head or effective date beyond the deployment.

---

## 1. Per-state / UT profile table

Regime codes:
- **G**: general-category state.
- **NE/H**: North-East or Himalayan (90:10 CSS sharing).
- **UT-L**: UT with legislature.
- **UT-NL**: UT without legislature.

Rail codes:
- **eK**: RBI e-Kuber.
- **CMP**: agency-bank cash-management product.
- **SBS**: State Bank of Sikkim.
- **SS**: SNA-SPARSH.

The **V/8** column counts the eight fields asked for that are V or P. Source keys are listed in §7.

### 1.1 States

| Code | State | Regime | IFMS / main systems | Treasury structure (DDOs) | Core financial rules | Rail · SNA-SPARSH | HoA extension | Languages | V/8 | Src |
|---|---|---|---|---|---|---|---|---|---|---|
| RJ | Rajasthan | G | **IFMS 3.0** (NIC): Budget, Pay Manager, WAM works, e-GRAS, SNA-SPARSH, Aadhaar Vault | Directorate of Treasuries & Accounts. 49 treasuries + 247 sub-treasuries; **33,337 DDOs** (2024-25); 7 treasuries demoted to sub-treasuries on 15-01-2025; Cyber Treasury | GF&AR Parts I–III, Budget Manual Vol-I, **RTPP Act 2012 / Rules 2013** (7th amendment 17-11-2025); PWF&AR Parts I–III (current edition [U]: only a secondary index found in pass 2) | eK, APBS; **SS first state** (2023) | 7 segments, 17 digits incl. demand (sub-head and group head in brackets, object 2): **V** in pass 2 from Budget Manual Vol-I §3.11, e.g. `4202 01 202 (01) [90] 17` | Hindi, English | 8 | RJ1–RJ4 |
| GJ | Gujarat | G | **IFMS** (2009-10, 18 modules) + Cyber Treasury; IFMS-III under development | Directorate of Accounts & Treasuries, Gandhinagar. 34 DT + 111 ST, 2 PAOs, 5 Pension Payment Offices, e-PAO (GST); about **5,042 DDOs** | Gujarat Financial Rules 1971, Gujarat Treasury Rules 2000, Budget Manual 1983, Financial Powers (Delegation) Rules 1998, purchase manual 2002 | RBI interface; SS pilot listed 2023 (P) | 2-digit sub-heads (8449-00-120-11); object level [U] | Gujarati, English (P) | 7 | GJ1–GJ3 |
| MH | Maharashtra | G | **Mahakosh**: BEAMS, Bill Portal, Treasury Net, **Sevaarth** (HRMS), NVV pension, Koshwahini, GRAS, CMP | Directorate of Accounts & Treasuries, Mumbai; 6 regional JDs; 34 DT + 323 ST; PAO Mumbai; Virtual Treasury | Maharashtra Treasury Rules 1968, Bombay Financial Rules 1959, Contingent Expenditure Rules, DFP Rules 1978 (P), Budget Manual (P) | CMP → eK pilot (2022); SS onboarded (RBI account GR Oct 2024; nodal-agency GR 14-02-2025; all CSS from 01-11-2025) (P: seen only in a police-department circular citing the GRs) | Demand (letters, e.g. ZC-1) + MH4-SMH2-MinH3-(SH2)(DH2)-OH2, 15 digits; BEAMS 8-digit code = MH + 4-digit serial (V) | Marathi, English | 7 | MH1–MH3 |
| GA | Goa | G | **Accounts-Online** (NIC Goa): IBPS bill processing, e-Pagaar20 pay bills, E-DDO, e-Challan (SBI MOPS), e-Payments, Treasury Accounting, Compilation, GPF, NPS, Works Audit (V, pass 2) | Directorate of Accounts, Porvorim (also Director of Treasuries), **PAO-style pre-audit**; Treasury Offices Porvorim + Margao; **10 sub-treasuries**; about **550 DDOs** (2021) (V, pass 2) | **Goa DFPR 2008** (amended 2023) (P); Goa Budget Manual (P); GFRBM Act 2006 / Rules 2007 (V); central GFR / FRSR applied (P); Goa Treasury Rules [U] | SBI e-payment + 7 link banks (V); SNAs exist (V); e-Kuber integrated per RBI 2025 (V, see §9); SS date [U] | **MH4-SMH2-MinH3-SH2** (2225-03-277-09) (V); detailed/object widths [U] | **Konkani** (Devanagari), Marathi permitted, English continues (Goa, Daman & Diu OL Act 1987) (V); budget docs English (P) | 7 | GA1, GA2–GA5 (§9) |
| MP | Madhya Pradesh | G | **IFMIS** (accounts from May 2018), Cyber Treasury, SS bills | Commissioner Treasury & Accounts → 7 divisional JDs → **54 treasuries**; 10-digit DDO codes | **MP Treasury Code 2020**, Budget Manual Vol-II; Financial Code Vol I and Finance Powers Manual 2025 Pt-1 (from 01-07-2025) [U: no official URL]; **MP Bhandar Kray tatha Seva Upaarjan Niyam 2015** (P) | SS per GoI directions of 29-07-2024; eK integrated (RBI 2025, V) | **Object + detailed object NN-NNN** (22-008) | Hindi (legacy Kruti Dev PDFs), English | 7 | MP1 |
| CT | Chhattisgarh | G | **e-Kosh** (ekoshonline.cg.gov.in): e-Bill, e-Challan, e-Payroll, **"SNA-Sparsh & State DBT"**, BCO-AG reconciliation; IFMIS Next Gen budget | Directorate of Treasury & Accounts; **34 treasuries** render accounts (CAG Accounts at a Glance 2023-24, V); DDOs [U] | FRBM 2005, Contingency Fund Act 2001, Pension Fund Act 2025; own CG Treasury Code Vol-II exists (P); **CG Bhandar Kray Niyam 2002**, consolidated to 10-12-2025 with a GeM rule (V) | SS combined with state DBT; eK integrated, over 90 % of payments via RBI (V) | BCO ceilings; Demand2-MH4-[SMH2]-{MinH3}-(Scheme4)-tag4-#OH2-DOH3 (P; 4-digit tag not decoded) | Hindi, English | 5 | CT1–CT2 |
| UP | Uttar Pradesh | G | **IFMS UP** (8 applications, 22 modules, 16 live Dec 2024), Rajkosh receipts, **Koshvani** MIS, CTS, e-Kuber monitor | Directorate of Treasuries; **82 treasuries** (75 district + 7 HQ) | **Financial Handbook** Vols I–VII (Treasury Rules = App. II of Vol V Pt I), UP Budget Manual, **UP Procurement Manual 2016** | eK; SS per DoE mandate | **15-digit to object head**; XML budget to the Legislature | Hindi, English | 8 | UP1–UP3 |
| UK | Uttarakhand | NE/H | **IFMS UK** (ifms.uk.gov.in, live 01-04-2019): e-DDO, e-HRMS, UKosh e-receipt, SNA Sparsh, State DBT | Directorate of Treasuries, Pension & Entitlements; 13 DT + 70 ST + 5 upgraded ST + 2 PAOs; **4,200+ DDOs** | FHB Part-1 DFP 2018 (amended 2019, 2022), Budget Manual 2012, Treasury Rules Manual 2015, **Procurement Rules 2017/2021** | Banks / RBI; SS module | MH4-SMH2-MinH3-SH2-DH2 + object 2, 15 digits (V) | Hindi, English | 7 | UK1–UK2 |
| BR | Bihar | G | **CFMS 2.0** (SS bill type; audit trail module flagged) | **No Directorate**: 76 treasuries report straight to the Finance Department; banking-only | **Bihar Treasury Code 2011**, Bihar Financial Rules, Bihar Budget Manual | eK for JIT (2026 circular) | ULB deposits on 8448-00-102-0001/2/3; code DD2-MH4-SMH2-MinH3-SH4-DH2-SDH2 + V/C, e.g. `12-2054-00-097-0001-01-01 V` (V) | Hindi, English | 8 (5 P) | BR1–BR2 |
| JH | Jharkhand | G | **IFMS 2.0 "KUBER"** (12 modules; e-GRAS, SNA-SPARSH, Hastakshar) | 27 district treasuries (incl. Jharkhand Bhavan, Delhi) + 6 sub-treasuries; **no functioning Directorate**: the Director (T&A) post is not functioning and the Finance Department does the work (CAG A&E Jharkhand page, V) | Jharkhand Treasury Code (Para 11B change control), JFR, Budget Manual, PW Accounts Code 1935; **Jharkhand Financial Rules 2025** text (FD PDF 14-10-2025; r.165 GeM, r.174 e-procurement) (V for the document; notification [U]) | SBI-CMP + eK; **no PFMS link at CAG audit** | **TSP / SCSP / OSP** components on schemes; DD2-letter-MH4-SMH2-MinH3-SH2-DH2-ObjGroup2-OH2, e.g. `12-S-2052-00-090-08-00-01-01` (V; meaning of the letter [U]) | Hindi, English | 8 (5 P) | JH1 |
| PB | Punjab | G | **IFMS Punjab "Next Gen"** (NIC, from 01-04-2020): eReceipt, Cyber Treasury, Vehicle POL | Director (Treasuries & Accounts); 21 DT + 76 ST + PAO New Delhi | Punjab Financial Rules, Punjab Treasury Rules + Subsidiary Treasury Rules; Punjab Transparency in Public Procurement Act 2019 + Rules 2022 (P); PWD Code (P) | eK (not for pension); SS per DoE | VAT challan auto-split 11/89; MH4-SMH2-MinH3 + SH2.DH2.OH2, 15 digits (V) | Punjabi, English (P) | 8 (4 P) | PB1–PB3 |
| HR | Haryana | G | IFMS + e-Billing / e-Pension + **e-GRAS**; GSTN | DG Treasuries & Accounts; 24 + **2 cyber treasuries** (Chandigarh: e-receipts; Panchkula: **SS from 25-01-2025**) + 82 ST; **13,443 DDOs** | **Applies the Punjab FR and Punjab TR** | eK; SS via Panchkula | PRI deposits 8448-109-98/99; [P2-DD2-MH4-SMH2-MinH3-SH2-DH2] + OH2, 19 digits, e.g. `[P-01-01-2011-02-101-99-51]` (V) | English (Hindi [U]) | 7 | HR1–HR2 |
| HP | Himachal Pradesh | NE/H | **HIMKOSH** (eSalary, ePension, eVitran, eBudget, eChallan, HPOLTIS, SS) | Department of Treasuries, Accounts & Lotteries; 12 DT + Capital, Cyber, GST and SNA treasuries + **full treasuries at Pangi, Kaza and Delhi** + 90 ST; **5,600 DDOs** | **HP Financial Rules 2009**, HP Treasury Rules 2017 (Rule 110: bill only for an SOE with allocation), CCS (CPS) Rules 2006 | **No eK link** (2024-25); own SNA Treasury | Control at **DDO × SOE**; MH4-SMH2-MinH3-SH2-Scheme4 + SOE2, e.g. `2202-02-796-01-S00N` (P) | Hindi, English | 8 | HP1–HP2 |
| KA | Karnataka | G | **Khajane-II** (e-Sanction, e-Budget with AIE, EPS e-payment, e-Voucher); **HRMS separate** | Commissioner of Treasuries; 31 DT + 207 ST + Cyber, Huzur, Pension Monitoring treasuries + **CAU (SS-only)**; **31,556 DDOs** | Karnataka Treasury Code, Karnataka Financial Code, **KTPP Act 1999** (amended to 2023), KTPP Rules 2000 | eK; SNA from 28-04-2022; SS via CAU | **15-digit** = MH4-SMH2-MinH3-SH1-DH2-OH3, e.g. `2055-00-113-0-03-240` (V; corrects the 2-2-2 assumption); 12-digit for deposits; paired central/state detailed heads | Kannada (CLM 52nd report, V); budget circulars in Kannada (P) | 7 | KA1–KA3 |
| TN | Tamil Nadu | G | **IFHRMS "Kalanjiyam"** (Karuvoolam): **Global Beneficiary Master**, Work ID, auto-TDS, OPPAS | Commissioner of Treasuries & Accounts; 32 DT + 243 ST + 9 PAOs + Pension Pay Office; **22,037 DDOs** | **TN Transparency in Tenders Act 1998 / Rules 2000**; TN Treasury Code Vol I, 7th edn, corrected to 30-04-2013 (V); TN Financial Code Vols I–II under rewrite (G.O.Ms.371, Gazette 413 of 18-12-2024) (V) | eK; **receipts ≥ ₹1 crore via RBI NEFT/RTGS** (Aug 2024); SS 50 SLS | SC/ST components, gender tags; MH4-SMH2-MinH3-SH2 (letters)-Group1-DH2-SDH2, 16, e.g. `2011 02 101 AA 30101` (V) | Tamil; English as additional (CLM 52nd, V) | 7 | TN1–TN3 |
| KL | Kerala | G | **IFMS Kerala**: BiMS 2.0, **SPARK** (HR), BAMS, Core TSB, ViMS, iAMS, e-TR5; bills also from Sankhya (LSG) and EMLI (works) | Directorate of Treasuries; 23 DT + 201 ST; about **47,500 DDOs incl. 16,550 "virtual DDOs"** | **Kerala Treasury Code 2016**, Kerala Financial Code, **Stores Purchase Manual 2013** (amended to 2023) | eK; SS onboarded (date [U]) | MH4-SMH2-MinH3-SH2-SSH2-DH2-OH2, 17 digits, e.g. `2056-00-001-99-00-02-05` (V); `8782-102-96-01` is a short public-account form | Malayalam; budget speech in Malayalam + English (V) | 7 | KL1–KL4 |
| AP | Andhra Pradesh | G | **CFMS on SAP S/4HANA** (APCFSS); receipts from 03-2018, expenditure from 04-2018 | Directorate of Treasuries & Accounts; **26 DT + AP State Capital Region Treasury + 196 divisional / sub-treasuries** (CAG review 2023-24, V); about 40,000 PD accounts | AP Treasury Code, AP Financial Code, **draft AP Treasury Rules 2022**, e-procurement GO Ms 129 (2019) | eK; SBIMOPS / PayU receipts; SS: 19 schemes onboarded by Sept 2025 (P) | **7-tier HoA** (8443-106-02-97-001-001; not re-confirmed in pass 2); SCSP/STC/BC/MAP/gender tags | Telugu; Urdu as additional (CLM 52nd, V; statewide by the 2022 Act [U]) | 7 | AP1–AP4 |
| TG | Telangana | G | **IFMIS** (from 2018; Work ID; PD accounts; HRMS planned) | Directorate of Treasuries & Accounts; 33 DT + 27 divisional ST + 67 ST + PAO Hyderabad; **15,000+ DDOs, 20,000+ PD accounts** | Telangana Financial Code, Treasury Code, Account Code (inherited from AP) | eK; SS: G.O.Ms.76 and 77 of 22-05-2024 and memo of 25-05-2024 (V) | AP-lineage 7-tier [U] | Telugu + Urdu (CLM 52nd, undivided AP, V); Urdu statewide 2017 [U] | 7 | TG1–TG2 |
| WB | West Bengal | G | **WBIFMS** (2014): e-Bantan, e-Billing, e-Pradan, **GRIPS 2.0** (99 % of receipts), HRMS, e-Pension, AAFS, SBMS, WBJIT | Directorate of Treasuries & Accounts; **91 units** (85 treasuries, 3 + 1 PAOs, Cyber Treasury, Pension Disbursement Office) | **WB Treasury Rules 2005** (revision awaiting Gazette), WB Financial Rules (under revision); WB PWD Code + WBWD Manual (CAG 2025, P); GeM mandatory from Sept 2026 (V, newsonair.gov.in) | **100 % eK 2.0**; SS = virtual limits to executing agencies | 17-digit with NP/SP and charged/voted [U]; gender tags (V) | Bengali; Bengali + Nepali in the Darjeeling and Kurseong subdivisions (CLM 52nd, V); later added languages [U]; memos in English | 8 (3 P) | WB1–WB3 |
| OD | Odisha | G | **IFMS (ex-iOTMS)**, **IFMS 3.0** RFP 2024: BETA budget, CePC e-payment, SBMS, **state JiT-FS** + CSS-JiT, GeM fund block | Directorate of Treasuries & Inspection; 30 DT + 9 special (incl. New Delhi) + Cyber + State Pension Treasury + 128 ST | **OGFR 2023** (consolidated to 04-06-2025; further amended 16-03-2026, No. 6904, V); OPWD Code Vols I–II (current edition [U]), Odisha Treasury Code, Odisha Budget Manual, **DFPR 1978** (amended 16-03-2024) | eK via central e-payment cell; SS; JIT APIs | 6 segments + object; A/B expenditure type | **Odia, English, Hindi** (mandated per service in the RFP) | 8 | OD1–OD3 |
| AS | Assam | NE/H | **FinAssam** (18+ modules; e-GRAS; phase-wise from CTMIS, phase II 28-02-2024) | Directorate of Accounts & Treasuries; 29 T (incl. **Shillong South, in Meghalaya**) + 34 ST (dated); **13,090 DDOs** | **Assam Treasury Rules 2017** (amended 2023 (P)), Assam Financial Rules, **DFP Rules 1999** (2nd edn 2016); Assam Public Procurement Act 2017 + Rules 2020 (P) | e-GRAS; SS generic (CGA) | MH4-SMH2-MinH3-SH4-DH3-OH2-SDH2, 20, e.g. `8443-00-108-1994-000-00-00` (P: search excerpt; PDF now 404) | Assamese (CLM 52nd, V); Bengali in Barak Valley (P); Bodo as associate language 2020 [U] | 6 | AS1–AS3 |
| SK | Sikkim | NE/H | **SIFMS** (PAO/CPAO; CAG notes no budget-excess block and no post-closure lock) | Directorate of Treasuries; PAO HQ + 6 CPAOs (**non-banking**) + **SS Cyber Treasury** + Legislative Assembly PAO | **Sikkim Financial Rules 1979**, Handbook of Payment & Accounting; Amendment Rules 2024 (notif. 12/FIN/ADM of 18-03-2024; secondary source only [U]) | **No RBI business: State Bank of Sikkim, cheques** (RBI FAQ: no s.21A agreement with Sikkim; RBI 2025: the only state not integrated, V) | MH4-SMH2-MinH3-SH2-DH2-OH2, 15, e.g. `2401 00 001 01 44` (V) | English (CLM 52nd, V); Nepali, Bhutia, Lepcha and later additions under the Sikkim Official Languages Act 1977 (V) | 6 | SK1–SK2 |
| AR | Arunachal Pradesh | NE/H | No branded IFMS; NIC TreasuryNet in some treasuries; some sub-treasuries fully manual; accounts sent offline | Director of Accounts & Treasuries; 26 T (incl. Shillong South) + 8 ST (31-03-2023); offices at Guwahati and Delhi; run by DC / SDO; DDO count [U] | **Central Treasury Rules Vol-I + GFR 2017** (as cited by CAG); **no state Budget Manual** (CAG SFAR 2023-24, V); procurement adopts GFR 2017 r.149 (P); state DFPR [U] | SS deadline 01-04-2025 (DoE); SNAs funded (V); e-Kuber integrated per RBI 2025 (V, see §9) | MH4-SMH2-MinH3-SH2-object3 (4217-01-800-03-051) (P) | **English** (CLM 52nd report, V; no OL notification) | 7 | AR1–AR4 (§9) |
| MN | Manipur | NE/H | NIC suite: **TreasuryNet**, **BEAMS** (from 2014), eGRAS (2021), e-Budget (2022), Cyber Treasury | Directorate of Treasuries & Accounts; 12 T (incl. DRC Guwahati) + 7 ST + Cyber | **Manipur DFPR 1995** (amended 2012, 29-10-2024) in the Manual of Financial Procedure 1999; GFR + CTR | **RBI e-Kuber as sole agency bank** (from 11-02-2020; over 90 % of payments via RBI, RBI 2025, V); SS from 17-12-2024 | BEAMS authorisation slips; MH4-SMH2-MinH3-SH2-DH2-OH2, e.g. `2225 02 001 01 00 01` (V) | Manipuri, English (CLM 52nd, V) | 6 | MN1–MN3 |
| ML | Meghalaya | NE/H | **MeghIFMS**: IOBS, BEAMS, TreasuryNET, e-Billing, CPPS, GRAS, SS, eProposal | Directorate of Accounts & Treasuries; 13 DT + 3 ST + Cyber; **Shillong South serves 4 governments**; SBI / PNB per treasury | **Meghalaya Treasury Rules 1985**, Meghalaya Financial Rules 1981, DFPR 2006 (P); Assam Budget Manual inherited | eK (SFTP), NPCI APBS; SS from 17-12-2024 | [U] | English; Khasi and Garo added by the Meghalaya Official Languages Ordinance 2026 (27-04-2026, commencement pending) (P: search result only) | 5 | ML1–ML2 |
| MZ | Mizoram | NE/H | **COMPACT** (NIC, 10 treasuries since 2016) + eBillDDO + Mizo e-Lekha + eGPF + GST-GRAS + **MIWAS** works + e-Sanction; AG uses VLC | Accounts & Treasuries (Chief Controller of Accounts); 10 treasuries; ST / DDO counts [U] | Central DFPR 1978 via the Mizoram Adaptation of Laws Order; Heads of Office notified under DFPR r.14 schedules (P: Assembly gazette mirror, not gov.in); **Mizoram Transparency in Public Procurement Act 2008** (amended 2019) (P) | SBI; SS from 17-12-2024; e-Kuber integrated per RBI 2025 (V, see §9) | **MH4-SMH2-MinH3-(SH2)-DH2-(OH2)**, 15 digits (V) | **Mizo, English, Hindi** (CLM 52nd report, V); budget-doc language [U] | 7 | MZ1–MZ4 (§9) |
| NL | Nagaland | NE/H | **NIFiMS** [U modules]; new **e-Pay Bill** (Dec 2025), PIMS v2 | Principal Directorate of Treasuries & Accounts; about 14 DT + 22 ST (sources inconsistent); **960 DDOs** | **CTR, GFR, CPWD Account Code** (RTI manual) | SBI CMP → eK (training 2025); SS deadline 01-04-2025 | Treasury-held DDO sub-allotments; MH4-SMH2-MinH3-SH2-SSH2-DH2, 15, e.g. `2011 02 101 01` (V) | English (CLM 52nd, V) | 6 | NL1–NL2 |
| TR | Tripura | NE/H | **CTOS** (2010) + separate HRMS, not integrated (CAG: about ₹3.97 cr excess pay); IFMS 2.0 planned | Directorate of Treasuries; 9 T + 13 ST | **DFP Rules Tripura 2019**, GFR 2017 "as adopted" (P), CTR as adopted, Budget Manual 1998 (P); Manual for Procurement of Goods 2018 (V) | eK; SS from 17-12-2024 | MH4-SMH2-MinH3-SH2-DH2-OH2, e.g. `2406 01 001 98 32 02` (V) | Bengali, Kokborok, English (CLM 52nd, V); budget speech in Bengali + English (V) | 6 | TR1–TR2 |

### 1.2 Union Territories

| Code | UT | Regime | Systems | Accounts structure | Rules | Rail · SS | Budget | Languages | V/8 | Src |
|---|---|---|---|---|---|---|---|---|---|---|
| DL | Delhi (NCT) | UT-L | Delhi NIC Pay Roll, GPF, **Delhi e-Lekha** (delekha.delhi.gov.in), SGST reconciliation, SS; new IFMS of ₹261.66 cr [U] | **PAO-only**: Principal Accounts Office + 25 PAOs (departmentalised since 01-04-1977) | R&P Rules 1983, GFR 2017, GAR 1990, Civil Accounts Manual, DDO Manual | PFMS, SS, eK, GSTN | Own Consolidated Fund + Union Demand 57 ("Transfers to Delhi"); Union-style MH4-SMH2-MinH3-SH2-DH2-OH2, e.g. `2052 00 090 60 00 01` (V) | Hindi, English; Punjabi and Urdu as second official languages (Delhi Official Languages Act 2000, V); budget speech Hindi + English (V) | 7 | DL1–DL2 |
| PY | Puducherry | UT-L | **IFMIS Puducherry** (ifmis.py.gov.in): GRAS, e-Salary, BEAMS, SS, eK, NPCI | Directorate of Accounts & Treasuries; 3 T + 3 ST; branch offices at Karaikal, Mahe, Yanam | Central rules: GFR 2017, DFPR, R&P Rules 1983, CTR, Accounting Rules for Treasuries 1992 (DAT, V); GFR + CPWD Manual for procurement and works (P) | SS, eK, PFMS, GRAS | Own budget + Demand 59 | Tamil, Telugu, Malayalam, English (CLM 52nd, V); budget speeches English + Tamil (V) | 6 | PY1–PY2 |
| JK | Jammu & Kashmir | UT-L | **BEAMS** (beamsjk.gov.in), **TreasuryNet**, **JKPaySys** (37 bill types), JKGRAS + Cyber Treasury, GenProfit | **132 treasuries across J&K and Ladakh**; about 5,915 DDOs | **J&K Financial Code I/II, Treasury Code** (SRO-68/2017), **Book of Financial Powers** (3rd edn 2002), Budget Manual S.O. 334 of 28-10-2020 (P: index entry only; jkdat.nic.in did not resolve); GFR 2017 for procurement | eK, J&K Bank, GSTN; SS [U] | Own budget (Assembly from 2025-26) + Demand 58 | Kashmiri, Dogri, Urdu, Hindi, English (J&K Official Languages Act 2020, V) | 7 | JK1–JK3 |
| LA | Ladakh | UT-NL | **LIFMS** (BEAMS-derived, upgraded Jul 2024) → PFMS integration (Nov 2025) | Directorate of Accounts & Treasuries; PAO/TO at Leh and Kargil; LAHDC Fund Office | GFR, DFPR, CGA prescriptions | PFMS (migrating) | MHA **Demand 55** | English, Hindi, Urdu, Bhoti, Purgi (Ladakh Official Languages Regulation 2025, V; commencement notification [U]) | 7 | LA1–LA2 |
| CH | Chandigarh | UT-NL | PFMS (ECS); treasury software [U] | **Central Treasury Chandigarh**, accounted by Pr. AG (A&E) Punjab | Central Treasury Rules; Punjab legacy rules for deputationists | PFMS; eK [U] | **Demand 53** | English (no Official Language Act; CLM 52nd, V); Hindi in Union documents | 6 | CH1 |
| DH | DNH & Daman-Diu | UT-NL | **e-Lekha** monthly accounts to CGA; Daman PAO code 071207 (controller 066) (V, c.2014); DNH PAO 070789 (P); PFMS e-bill status [U] | **Two units still separate in 2025**: Directorate of Accounts (PAO) Silvassa and Director of Accounts Daman (Central PAO, pre-check, treasury admin, local fund audit); district treasuries Daman and Diu (V) | **DFPR, GFR, CTR, FR**; CCS (Pension) via CPAO (V); draft DNH&DD Panchayats (Funds & Accounts) Rules 2024 (V); GFR 2017 procurement (assumed, P) | SBI / PSU banks for challans and pension (V, 2020); SS not applicable (UT-NL works in PFMS); eK [U] | **Demand 54**, BE 2026-27 ₹2,835.20 cr gross (V); Union layout MH4-SMH2-MinH3-SH2-DH2-OH2 (P) | English, Hindi (Union); Gujarati for Daman & Diu under the 1987 Act; DNH used Hindi + Gujarati (CLM 52nd) (P) | 6 | DH1–DH4 (§9) |
| LD | Lakshadweep | UT-NL | **e-Lekha / PFMS** (EIS, DBT, EAT) | Principal PAO Kavaratti + Field Pay Unit | R&P Rules, Civil Accounts Manual, GFR, FR | PFMS, NSDL | **Demand 56** (shipping-heavy) | English (CLM 52nd, V); Hindi in Union documents; Malayalam on the website only (P) | 8 | LD1 |
| AN | Andaman & Nicobar | UT-NL | **PFMS** (LC approval, DSC management) | **Chief PAO + 3 PAOs + 5 Sub-PAOs** (treasuries converted in 1980) | **R&P Rules 2022**, GFR 2017, DFPR, Civil Accounts Manual | PFMS, NSDL | **Demand 52** | English, Hindi | 8 | AN1 |

**UT regimes.** Group F's finding is that there are **three regimes, not two**:
- **(a) PFMS-native departmentalised PAO UTs:** A&N, Lakshadweep and DNH&DD. *Pass 2:* DNH&DD still runs two Directorate / PAO units (Silvassa, Daman) with district treasuries, inherited from the Goa model, and sends accounts to the CGA through e-Lekha (V). They report to CGA. Their budget is a single MHA demand.
- **(b) Parliament-funded UTs that are treasury-based and run inherited state systems:** Chandigarh (Punjab legacy, accounts kept by the AG) and Ladakh (J&K TreasuryNet / BEAMS, moving to PFMS).
- **(c) UTs with a legislature and a state-like IFMS:** Delhi (PAO-based), Puducherry (treasury-based) and J&K (own codes). Each has its own Consolidated Fund. Parliament votes only the "Transfers to …" demands (57–59).

The earlier assumption that the MHA Pr.CCA keeps all UT accounts is **wrong**: its 48 PAOs exclude Port Blair, Kavaratti, Chandigarh and Silvassa.

On delegation, an Administrator may sanction plan schemes up to ₹50 crore where the Administrator is a Governor or Lt Governor, and up to ₹25 crore in the other UTs (CAG Report 3 of 2018).

---

## 2. Central frameworks every state profile builds on (verified, Group G)

| Framework | What is fixed centrally | Version / date | Status |
|---|---|---|---|
| Art. 150, GAR 1990, LMMH | Form of accounts; three parts (CF, Contingency Fund, Public Account); MH (4) / SMH (2) / MinH (3) fixed by CGA/CAG; Standard Minor Heads (001, 800, 911, new 503) | Correction slips: CS 1047 (13-01-2025) … **CS 1128–1132 (16-10-2026)**; CS 1068 (28-07-2025) revised the Standard Minor Heads | V. **Below the minor head, each government sets its own heads** (LMMH General Direction 1.1: sub-heads "to suit the local requirement of each Government"; para 8: the 2-2-2 widths are stated only "at the centre"; LMMH to CS 1127 of 09-10-2026, read in pass 2). No paragraph names detailed and object heads as state-owned in so many words |
| IGAS | IGAS 2 (grants-in-aid are revenue expenditure; OH 35), IGAS 3 (loans and advances); IGAS 1 (guarantees) | — | V (2, 3); IGAS 1: GFR 280(ii) and 282 require guarantee statements to comply with IGAS-1 (V, pass 2); the notification text was not read (gasab.gov.in unreachable) [U] |
| SNA (2021) | One SNA per CSS; SCB account; zero-balance subsidiary accounts for IAs; EAT module | DoE OM 23-03-2021, effective 01-07-2021 | V |
| **SNA-SPARSH** | SLS per Centre:State ratio; SLS-wise RBI drawing accounts; Mother Sanction; Cyber Treasury consolidated file; child sanction; **15:00 cut-off**; ratio/top-up master; **reissue tag**; no diversion to PD; UTs without legislature work directly in PFMS | DoE 13-07-2023; CGA OM 53 of 26-06-2025 (**one active Mother Sanction per SLS; lapses at 23:59 on 31 March; treasury is the sole reconciliation unit**); CGA FAQ OM 115 of 25-09-2025; **all CSS from 01-11-2025** (DoE OM 10-06-2025) | V |
| PFMS | Interfaces with all 28 states and 2 UTs with legislature; 4 integration models (1: external EAT/DBT; 2: external system with payment via PFMS; 3: PFMS UI; 4: State IFMS) | GIFMIS page | V |
| RBI | Agency-bank appointment on the recommendation of the State Finance Department (Master Direction 10-05-2021); direct settlement from 01-01-2018; e-Kuber holidays and year-end working | RBI MDs | V. *Pass 2:* RBI's presentation to the State Finance Secretaries' Conference (19-09-2025) says all States are integrated with RBI for e-payments **except Sikkim**, and names 13 States that route over 90 % of payments via RBI (incl. CT, MN, ML) (V). A named per-state e-Kuber list is still [U] |
| CNA / TSA | Central Nodal Agency models (TSA above ₹500 cr); TSA assignment accounts lapse at year-end | DoE 09-03-2022; CGA 25-05-2022 | V (P) |
| Model rules | **GFR 2017 updated to 31-07-2025**; **DFPR 2024** (from 01-04-2024; repeals 1978); Procurement Manual for Goods 2nd edn 2024; Works manual 2nd edn 2025 (DoE, listed 05-01-2026; amended 18-06-2026: V, pass 2); DFPR Annexure-I object heads replaced from FY 2027-28 by the DoE OM of 09-06-2026 (V); CPWD Works Manual 2022; PPP-MII revised 19-07-2024 | — | V / [U] as marked |
| CSS sharing | 60:40 general (core), 50:50 (optional); **90:10 / 80:20 NE + Himalayan** (8 NE + HP, UK, J&K); 100 % for UTs without legislature | NITI Sub-Group of CMs, 2016-17 | V |
| Sixth Schedule | Para 7 District / Regional Fund; form of accounts prescribed by CAG with the President's approval; CAG audit; Art. 275(1) grants pass through the state | — | V (structure, via CAG ADC reports) |
| Reference IFMS architecture | **None found** from DoE, NIC or MeitY. The de-facto standards are the SNA-SPARSH and PFMS treasury interfaces | — | [U] (not found) |

---

## 3. Cross-state common requirements (IFS-001 …)

These apply to every state and UT. In a state profile each one becomes a *switch* or a *parameter*, never a fork of the code.

### 3.1 Chart of accounts and budget classification
- **IFS-001** Chart of accounts in six tiers below sector and sub-sector: MH(4) · SMH(2) · MinH(3) · Sub-Head · Detailed Head · Object Head. The width of each state-owned tier is set in the state profile, which also sets the render format (separators, brackets as in Rajasthan, NN-NNN as in MP).
- **IFS-002** MH, SMH and MinH form a **centrally governed reference dataset** (CGA LMMH). It is read-only for states. Each code carries a validity period and the correction-slip number that made it.
- **IFS-003** Correction-slip ingestion workflow: draft, review, effective-date activation, then a remap/merge of budget lines. The CS number is kept in the audit trail.
- **IFS-004** Enforce what the first digit of a major head means: 0/1 receipts, 2/3 revenue expenditure, 4/5 capital (4000 = capital receipts), 6/7 loans, 8 Contingency Fund and Public Account (**8000 = Contingency Fund; 8001–8999 = Public Account**: LMMH para 8 "Coding Pattern" and list, V, pass 2).
- **IFS-005** Three-part fund model (CF, Contingency Fund, Public Account) with sector rollups.
- **IFS-006** Charged/voted flag on every expenditure line and transaction, carried into the Appropriation Accounts.
- **IFS-007** Standard Minor Heads as a reusable template under any major head.
- **IFS-008** State-defined object-head master mapped to IGAS-2 categories, including a flag for grants-in-aid that create capital assets.
- **IFS-009** Mapping from Demand / unit of appropriation to the account head, kept separate from the head structure itself. This covers WB's "one department, one demand" rule and the demand number inside Rajasthan's code.
- **IFS-010** IGAS-1 guarantee register, IGAS-3 loans register and IGAS-2 grant classification.
- **IFS-038** **Configurable classification tags** on scheme and budget lines. Tags include the sub-plan component (TSP/STC, SCSP, OSP, BC, Minority), gender and child budget, plan status (NP/SP), the expenditure type (Odisha A/B), the scheme source (Manifesto, CSS, EAP, NABARD/RIDF) and the revenue/capital nature. The tag set is defined per state (seen in JH, TN, AP, WB, OD).
- **IFS-039** **Control-dimension selector.** Each state chooses the level at which the budget is checked at bill time: object head, SOE (HP Rule 110), detailed head, DDO × head, or a BEAMS authorisation slip.

### 3.2 Budget control chain
- **IFS-040** A configurable chain from budget to bill. Its stages are allocation → release / Budget Release Order → authorisation (AIE in KA; BEAMS slip in MN, J&K, PY, LA; e-Vitran in HP) → DDO sub-allotment (Nagaland: held by the treasury) → bill. Each stage can be switched on or off, and each has its own approver chain.
- **IFS-041** A "pending for budget" queue that forwards a held bill automatically when the allotment arrives (RJ). Every transition is audited.
- **IFS-042** Ceilings with relaxation workflows: Budget Controlling Officer ceilings (CT), quarterly expenditure caps (TN), and Contingency Fund advance, supplementary and re-appropriation forms (CT, KA).
- **IFS-043** Hard budget block, plus an account-period lock after closure. CAG has flagged both as missing (SK, HP, BR audit trail).
- **IFS-032** A just-in-time release control (GFR 230(7)) that blocks drawal ahead of need (also OTC SR 242, OGFR rule 6, OBM 141(3)).

### 3.3 Sanction, commitment and bill
- **IFS-044** **Sanction-before-bill.** A bill must reference an approved, digitally signed sanction or commitment object, such as e-Sanction (KA, MZ), AAFS (WB), an IFMS sanction order (OD, mandatory since 2018), eProposal (ML), or a Work ID or pre-registered scheme / project / procurement (TN, TG, KL). The bill is checked against the sanction's classification, payee category and limit.
- **IFS-045** A **bill-type catalogue held as data**, with no catalogue in code. Each bill type has an id and a state form number (MTR-18, MPTC-56, HPTR-5, BTC Form-8, GA-55 [U], Form-46 DC). It also lists the permitted head-of-account patterns, mandatory deductions (TDS, GST-TDS, GPF, NPS), required attachments, signatories and the approval-chain reference. Odisha has 20 types and about 70 sub-types; J&K has 37.
- **IFS-046** **Multi-source bill intake.** Bills can come from the IFMS's own DDO portal or from external sources: HRMS / payroll (KA HRMS, KL SPARK, MH Sevaarth), local-body systems (KL Sankhya), works systems (KL EMLI, MZ MIWAS, JH WAMIS), welfare portals (HP e-Kalyan, TN centralised welfare bills) and ministry portals feeding SNA-SPARSH.
- **IFS-047** **Treasury scrutiny chain** of configurable depth. Odisha's chain is front desk → dealing assistant → accountant → ATO/TO. The outcome is either pass or a **structured objection that returns the bill**; the draft AP TR 2022 bars outright disallowance. The treasury may approve or object but **cannot edit DDO data** (ML).
- **IFS-048** Bill identity. An electronic bill id is always present. A treasury **token number** is optional, as a sequence per treasury per day that must be unique (TG flagged duplicates). Kerala abolished tokens. Odisha uses a "Book of Drawal", which is optional.
- **IFS-049** **AC/DC bill pairing.** Track ageing and escalate, and block or warn on further AC drawal while DC bills are overdue. The threshold comes from the state's treasury code (GJ, BR, JH, SK).
- **IFS-050** **Grant-in-aid sanction and UC tracking.** A GIA bill links to its sanction. Utilisation certificates are tracked against due dates, with blocks and an export to AG VLC (PB, HP, GJ ₹7,977 cr of UCs awaited).

### 3.4 SNA-SPARSH and CSS
- **IFS-011** Scheme master in which each CSS is broken into SLSs. Each SLS has **exactly one Centre:State ratio** and an optional top-up. All of these are versioned and effective-dated.
- **IFS-012** Registry of SNAs and implementing agencies to any depth, mapped to SLS and to PFMS codes.
- **IFS-013** Mother Sanction fetch: **one active per SLS**, a limit check, and **auto-lapse at 23:59 on 31 March**. Payment is blocked when no Mother Sanction exists.
- **IFS-014** IA payment files → treasury scrutiny → Cyber Treasury consolidated file, with SNA/IA and SLS flags and the Centre, State and top-up shares split.
- **IFS-015** A configurable PFMS cut-off, default 15:00, with next-working-day logic based on the e-Kuber holiday calendar.
- **IFS-016** Receive the child sanction, pre-fund the state's RBI drawing account, auto-push to e-Kuber, and take in DN/CN notifications.
- **IFS-017** Re-push failed transactions with a mandatory **reissue tag**.
- **IFS-018** SLS-wise reconciliation across IFMS, PFMS and e-Kuber, with the treasury as the sole reconciliation unit, and submission to the AG.
- **IFS-019** No transfer of CSS funds to PD or bank accounts.
- **IFS-020** Support for legacy SNA (2021) and migration from it.
- **IFS-021** Deductions inside CSS payment files.
- **IFS-022** DBT files carry the DBT Mission scheme code and an APBS / account-based flag.
- **IFS-023** PFMS integration model chosen per scheme or agency (models 1–4).
- **IFS-024** Daily data share from the treasury to PFMS.
- **IFS-051** **SNA-SPARSH hosting variant** chosen per state. The variants are:
  - a dedicated treasury unit: KA CAU, HP SNA Treasury, HR Panchkula cyber treasury, SK SS Cyber Treasury;
  - an IFMS module: UK, JH, CT;
  - a bill type: BR CFMS 2.0;
  - a "virtual limit to executing agencies" model: WB.

  The **onboarding date** is also set per state. MZ, TR, MN and ML went live on 17-12-2024; AR and NL had a deadline of 01-04-2025; RJ started in 2023.
- **IFS-052** **State JIT for state schemes**, separate from CSS-JIT. Odisha's JiT-FS is the example: an IA requisition goes through the DDO to the treasury, and the service is exposed as an authenticated API (it is used by MUKTASoft).
- **IFS-035** CSS sharing presets by state category, overridable per scheme.

### 3.5 Payment and receipt rails
- **IFS-025** RBI e-Kuber connector for payments and receipts, with e-scroll and DN/CN, and year-end special working days.
- **IFS-026** Agency-bank master, per treasury: e.g. SBI or PNB in Meghalaya.
- **IFS-053** **Payment rail chosen per state, treasury and bill category.** The rails are:
  - e-Kuber;
  - agency-bank CMP (SBI-CMP);
  - a **state-owned government bank with cheques** (Sikkim; no RBI);
  - treasury cheques or **Letter of Credit / CCL-DCL for cheque-drawing divisions** (GJ, UK, A&N, HP);
  - APBS / NPCI.

  Pension may use a different rail from other bills (Punjab). Parallel running during migration must be supported (NL, MH, LA).
- **IFS-054** **Two-stage payment failure.** One stage is an e-Kuber return; the other is a failure at the beneficiary's bank after e-Kuber has succeeded (PB). The lifecycle is suspense → reissue → AG Alteration Memo after one year (KA).
- **IFS-027** GRAS-style e-receipt: GRN → bank or gateway → CIN → e-scroll → receipt voucher.
- **IFS-055** **Receipt rule engine** with these rules:
  - apportion one challan across several heads (PB VAT 11/89);
  - set channel thresholds (TN: ₹1 crore and above via RBI NEFT/RTGS);
  - reject a deposit below the challan amount (JH);
  - support challan refund and defacement;
  - accept a challan as bid security (RJ RTPP rule 42(6));
  - support several aggregators (AP SBIMOPS/PayU).
- **IFS-028** GST receipt reconciliation (GSTN against RBI).
- **IFS-056** **Payee / beneficiary golden record.** A unique payee code (PB, HR UCP), bank-account validation and a lock after the DDO verifies it (KA), detection of several beneficiaries on one account (PB, TR, CH), and a single financial id across payroll, pension, vendor and DBT (TN GBM).

### 3.6 Public-account sub-ledgers
- **IFS-057** **A generic public-account sub-ledger.** It covers PD, PL and PLA accounts; Virtual PLA (MH); Plan TSB under 8031-102-99 (KL); Green Channel PD (AP, with no treasury scrutiny or ways-and-means gate, audited); SBMS scheme bank accounts (OD, WB); and TSA assignment accounts (IFS-029). Its configurable controls are: no minus balance, closure after three years of inactivity (GJ, TG), lapse rules, interest and KYC (KL treasury bank), and alerts on large transfers from the Consolidated Fund (RJ ₹27,158 cr).
- **IFS-058** **Treasury savings bank** (Kerala). Treasury deposit accounts for officials, pensioners and the public, with interest, TDS and e-KYC. Salary and pension may be credited to the TSB.
- **IFS-059** **Local-body deposit templates.** These are sub-heads keyed to the type of local body: BR 8448-00-102-0001/2/3 for ULBs; HR 8448-109-98/99 for PRIs.

### 3.7 Organisation, topology and sub-state entities
- **IFS-060** **Typed, effective-dated topology of accounting units.** It covers:
  - the unit types: district treasury, sub-treasury, upgraded sub-treasury, divisional sub-treasury, HQ / Capital / Huzur treasury, Cyber treasury (receipts, or SNA), GST treasury / e-PAO, CAU (SS only), State Pension Treasury or Pension Payment / Disbursement Office, Special treasury, PAO, Sub-PAO, Principal or Chief PAO, CPAO (non-banking), PAO for a legislature, Field Pay Unit, and works or forest divisions that render accounts to the AG;
  - **configurable depth**: Bihar has no directorate; MH has regional and MP divisional JD tiers;
  - promotion or demotion during the year (RJ, 15-01-2025);
  - a separate "parent for account rendition" (AR: Boleng ST renders through Aalo);
  - banking vs non-banking mode per unit.
- **IFS-061** **Extra-territorial and multi-government treasuries.** A treasury has `owning_government`, `served_governments[]` and `physical_state`, with a bank and AG mapping for each government. Examples: Shillong South (ML, AS, MZ, AR); offices at Guwahati and Delhi (AR, MN); New Delhi PAOs and treasuries (BR, JH, PB, TG, AP, WB, OD, HP).
- **IFS-033** **Sub-state fund entities** as separate accounting entities, each with its own fund rules, budget, PLA at the treasury, CAG audit and grant-in-aid funding. They include:
  - Sixth Schedule ADCs: AS BTC/KAAC/DHAC, ML KHADC/JHADC/GHADC, TR TTAADC, MZ Lai/Mara/Chakma;
  - councils under a state Act: MN hill ADCs;
  - LAHDC Leh and Kargil;
  - WB GTA, through the Home & Hill Affairs department;
  - ULBs and PRIs.
- **IFS-062** **Statutory transfers to sub-state entities**: revenue shares (ML forest revenue and profession tax), formula-computed shares (AS BTC) and pass-through of central releases with UC loops (DoNER, Art. 275(1)). Each is tracked as due against released.
- **IFS-063** **Mandatory finance-officer vetting** in the approval chains of sub-state entities (MN: U.O. number and date recorded).
- **IFS-064** **Special DDO classes**: virtual DDOs for local-body implementing officers (KL, about 16,550); the treasury acting as DDO for pensioners and Grade-IV GPF (NL); cheque-drawing DDOs (HP); and one DDO mapped to exactly one treasury (HP found 439 DDOs mapped to more than one).
- **IFS-065** **Org change over time.** Entity merger (DNH&DD, 2020), DDO-code succession (TG, gram panchayat to ULB), district reorganisation (AP, 26 districts) and changes of regime (J&K: Parliament-voted 2019–24, Assembly-voted from 2025).
- **IFS-036** **UT budget modes**: a UT as a Union demand, with the Administrator's delegation (₹50 cr / ₹25 cr) and accounts rendered to CGA; or a UT that works like a state, with its own Consolidated Fund. The **account-rendering target (AG A&E or CGA e-Lekha)** and the **payment-node type (treasury, PAO, sub-PAO, CDDO)** are chosen per tenant.
- **IFS-066** **Location dimension**: regions (PY: Puducherry, Karaikal, Mahe, Yanam), non-contiguous districts (DNH&DD), islands (A&N sub-PAOs down to Campbell Bay; Lakshadweep). Each location can have its own calendar and SLA.

### 3.8 Rules, delegation and procurement
- **IFS-030** **Delegation-of-powers engine.** Authority by rank and office tier, with monetary limits by object or transaction type, re-appropriation rules and general or special delegations (KA GDFP/SDFP). It is effective-dated and keyed to amendments: UK FHB-1 2018/2019/2022; MN DFPR 1995/2012/2024; TR DFPR 2019 with 7 amendments; OD DFPR 1978 amended 2024; central DFPR 2024.
- **IFS-067** **Layered rule library.** A central base (GFR 2017, CTR, R&P Rules 1983 or 2022, DFPR 2024, CPWD) is overlaid by "adopted from" another rulebook (HR ← PB FR/TR; UK ← UP FHB pattern; JH ← BR; ML ← Assam Budget Manual; NE states ← CTR + GFR "as adopted"), then by the state's own amendments, each with `effective_from` and a citation. Every validation message and objection memo **cites the rule**, e.g. "FHB Vol V Pt I App II r.n" or "HPTR 2017 r.110".
- **IFS-031** **Procurement rule-pack hooks** per state: RTPP Act 2012 / Rules 2013 (RJ), KTPP Act 1999 (KA), TNTT Act 1998 / Rules 2000 (TN), UP Procurement Manual 2016, UK Procurement Rules 2017/2021, KL Stores Purchase Manual 2013, AP GO Ms 129/2019, and central GFR / Manuals / PPP-MII. Each is versioned by edition.
- **IFS-068** **GeM fund-blocking hook.** Funds are blocked before the order and the bill is created after it (OD). GeM can be made the mandatory route (UP, TR forward auction).

### 3.9 Works
- **IFS-069** **Works finance variants**: CCL/DCL (UK, since 1974, with a 2019 accounting GO); Letter of Credit for R&B, Irrigation and Forest (GJ) and for APWD and Forest (A&N); a Work ID activated on administrative sanction, with a capital-works bill type (TN, TG); e-MB with e-Procurement artefacts attached to bills (KA); works accounts rendered by the division (MH, MZ MIWAS). This extends the existing works TS/AA order switch.

### 3.10 Pension, HR and payroll
- **IFS-070** Pension authorisation from the AG (e-PPO, OPPAS), with **format checks on authorisation numbers** (JH: 9-digit GPO) and blocks on duplicate payment. Payer classes include the state, central government, panchayats, GIA institutions, freedom fighters, ex-French pensioners (PY), defence and other states. Life proof can come from Jeevan Pramaan DLC, face authentication (TN, 12-month window) or a six-monthly photo (MN).
- **IFS-071** **Hard HRMS–bill integration.** Payroll bills must not allow free-text net pay (TR CAG fraud finding). Pay bills are split by pension scheme (HP: OPS / NPS for joiners after 15-05-2003). LPC transfer and GPF capture (DL).

### 3.11 Language, signature, audit and operations
- **IFS-072** **Language packs per state, with several per state.** The scripts include Devanagari, Gujarati, Gurmukhi, Bengali, Odia, Assamese, Kannada, Tamil, Telugu, Malayalam, Meitei, **Urdu (RTL)** and Roman (Mizo, Khasi, Kokborok). Output templates can use a single language (several CAG treasury reviews are Hindi-only). Legacy-font conversion (Kruti Dev to Unicode, MP and BR) is needed for historic records. This extends R54.
- **IFS-073** **Signature policy per state.** Whether DSC/eSign is mandatory (UK GO 15-02-2023; KL; GJ since 2012), the **signing sequence** (HR: Treasury Officer signs, then the DDO), dual DDO + treasury signing of vouchers (AS), and OTP release of payment files through a configurable channel (ML: SANDES and e-mail).
- **IFS-074** **Rendering of accounts to the AG.** Monthly accounts by the 5th (WB: 8 April for March; ML), VLC / e-voucher exports, soft vouchers (WB TR forms), and SFTP CSV feeds (KA, AP).
- **IFS-075** **No-bypass and change control.** An immutable audit trail that cannot be switched off (BR), no back-end bypass of validations, overrides only through an approved exception workflow (JH), and a configuration-change register with approval before production (JTC Para 11B).
- **IFS-076** **Coexistence with existing IFMS cores.** Adapter boundaries for SAP S/4HANA (AP CFMS) and NIC suites (TreasuryNet, BEAMS, COMPACT, CTOS, Khajane-II, IFHRMS); phased cut-over per treasury with legacy and new systems running in parallel (AS CTMIS → FinAssam; TR CTOS → IFMS 2.0; DL; LA).
- **IFS-077** **Offline and low-connectivity operation.** Store-and-forward and batch rendition of accounts for manual or offline treasuries (AR), island sub-PAOs (A&N, LD) and high-altitude units (LA, HP Pangi/Kaza), with SLA timers that can be paused per location.
- **IFS-078** **High-volume DBT bursts.** TG paid more than ₹6,000 cr in one day (Runa Mafi); TN bulk welfare bills reach about 10⁶ lines; WB schedules monthly DBT windows. Batching into e-Kuber and reprocessing of failed credits is required (extends GP-49).
- **IFS-029, 034, 037** TSA assignment accounts; tagging of Art. 275(1) / TSP flows; transfers to ULBs and RLBs through IFMS (the CAG's recommendation to the 16th Finance Commission).

**Count:** 78 cross-state requirements (IFS-001 … IFS-078).

---

## 4. State-specific variations (IFS-<CODE>-nnn)

Each item is something the platform must let that state configure. Where a variation is already covered by an IFS-0xx item, it is listed as **an instance of** that item, so it becomes a parameter value in the profile and not new code. The full rationale and source for each item are in the matching row of §1.

### Rajasthan (RJ)
- IFS-RJ-001: 7-segment, 17-digit code: demand(2), MH(4), SMH(2), MinH(3), sub-head(2, in round brackets), group head(2, in square brackets, optional), object(2). Instance of IFS-001.
- IFS-RJ-002: Bill catalogue: salary (Pay Manager, with a PRI salary module), GIA, FVC, GST-TDS, SD-refund, works (WAM), pension. GA-55 is [U].
- IFS-RJ-003: SNA-SPARSH through a Cyber Treasury. Budget for the Central Assistance (CA) and State Fund (SF) shares is released automatically according to the sharing pattern. Maker / Checker / Approver at IA level.
- IFS-RJ-004: Pending-for-budget queue with auto-forward to the sub-treasury (IFS-041).
- IFS-RJ-005: PD sub-ledger with object-head-wise receipts. Alerts on large transfers from the Consolidated Fund.
- IFS-RJ-006: Effective-dated promotion and demotion of treasuries (15-01-2025).
- IFS-RJ-007: Aadhaar Vault (encrypted, masked in the UI) and the APBS rail.
- IFS-RJ-008: e-GRAS challan defacement and refund. A challan is accepted as RTPP bid security (rule 42(6), 7th amendment 2025).
- IFS-RJ-009: Hindi as the primary language for reports and audit outputs. Raj SSO with OTP MFA.

### Gujarat (GJ)
- IFS-GJ-001: AC bill / DC bill in Form-46 pairing, with a block on new AC drawal (IFS-049).
- IFS-GJ-002: Letter of Credit / cheque mode for R&B, Irrigation and Forest divisions (IFS-069).
- IFS-GJ-003: PD/PLA with no minus balance and closure after 3 years inactive (FD circular 03-06-2009).
- IFS-GJ-004: Sub-head master reconciled with the budget document and the AG (CAG found drift).
- IFS-GJ-005: Unit types: District Treasury, Sub-Treasury, PAO, Pension Payment Office, Divisional Treasury, e-PAO (GST).
- IFS-GJ-006: DDO DSC mandatory; e-sub-vouchers.
- IFS-GJ-007: Multi-payer pension: state, Panchayat, GIA institutions, freedom fighters.
- IFS-GJ-008: Gujarati and English.

### Maharashtra (MH)
- IFS-MH-001: Form catalogue keyed by MTR number (18, 18B, 24A, 28, 29, 31, 44, 45/45A, 52/52A, GIS-8). 5-part bill layout with a barcode "bill-cum-BDS".
- IFS-MH-002: Separate pay bill (Sevaarth) and non-pay bill (Bill Portal) channels sharing one BEAMS budget watch.
- IFS-MH-003: After a bill is passed, the DDO confirms the beneficiary within **10 days** before the voucher is generated (e-Kuber pilot).
- IFS-MH-004: **Virtual PLA** for Irrigation Development Corporations (GR 02-03-2021).
- IFS-MH-005: Regional Joint Director tier; Virtual Treasury for GRAS.
- IFS-MH-006: Works and forest divisions render accounts to the AG directly (342 + 174 + 69 divisions).
- IFS-MH-007: Pension pipeline: AG e-authority → Sevaarth → first payment; Jeevan Pramaan.
- IFS-MH-008: Marathi and English.

### Goa (GA)
- IFS-GA-001: **Pre-audit (PAO) model**: pay-audit sections audit claims centrally before payment.
- IFS-GA-002: Two-district topology, with pay-audit sections as work queues.
- IFS-GA-003: Statutory audit of municipalities, village panchayats and planning authorities by the same directorate.
- IFS-GA-004: Agency payment of central, railway and defence pensions.
- IFS-GA-005: Konkani (Devanagari) official, Marathi permitted, English continued (Goa, Daman & Diu Official Language Act 1987, V).
- IFS-GA-006: Adapters to **Accounts-Online** (IBPS bills, e-Pagaar20 pay, E-DDO, e-Challan via SBI MOPS, Works Audit) (V).
- IFS-GA-007: Goa DFPR 2008 rule pack, with central GFR / FRSR applied (P). Goa Treasury Rules [U].
- IFS-GA-008: HoA MH4-SMH2-MinH3-SH2 (e.g. 2225-03-277-09); widths below the sub-head [U].

### Madhya Pradesh (MP)
- IFS-MP-001: Object + 3-digit detailed object (NN-NNN) validated against the bill type (CAG found misclassification).
- IFS-MP-002: Commissioner → divisional JD → treasury, with no assumed sub-treasury tier.
- IFS-MP-003: Form library keyed to MP Treasury Code 2020 (MPTC-56, MPTC-71, Form 80).
- IFS-MP-004: SNA-SPARSH bill with tracking of the central-share credit notification.
- IFS-MP-005: Cyber Treasury mandatory for specified receipts (forest revenue), with a PD fallback (8443-106).
- IFS-MP-006: DDO specimen-signature / DSC registry (a CAG gap).
- IFS-MP-007: Conversion of Kruti Dev legacy-font records to Unicode.

### Chhattisgarh (CT)
- IFS-CT-001: BCO expenditure ceilings with a relaxation request workflow.
- IFS-CT-002: One disbursement channel for SNA-SPARSH and state DBT.
- IFS-CT-003: Fund Drawal and OTP-confirmed Vendor Payment flows.
- IFS-CT-004: BCO–AG monthly reconciliation sign-off.
- IFS-CT-005: Budget-proposal templates: new item, supplementary, re-appropriation, contingency advance, guarantee.
- IFS-CT-006: Hindi-first forms. Ministers' payroll and self-DDO in e-Payroll.

### Uttar Pradesh (UP)
- IFS-UP-001: Two kinds of treasury: 75 at division/district level and 7 at HQ level.
- IFS-UP-002: 15-digit classification to object head; XML budget export to the Legislature.
- IFS-UP-003: Treasury token issued on transfer of a digitally signed bill from the DDO portal to CTS (IFS-048).
- IFS-UP-004: External receipt system of record (Rajkosh), with a challan-to-head feed.
- IFS-UP-005: Rule citations by FHB volume / part / paragraph (IFS-067).
- IFS-UP-006: UP Procurement Manual 2016 rule pack, with GeM optionally mandatory.
- IFS-UP-007: e-Kuber returns and reissue (IFS-054).

### Uttarakhand (UK)
- IFS-UK-001: **CCL/DCL** credit-limit system for works divisions (IFS-069).
- IFS-UK-002: Unit types include the upgraded sub-treasury and the out-of-state PAO.
- IFS-UK-003: DSC/eSign mandatory on every transaction (GO 15-02-2023).
- IFS-UK-004: DFP matrix from FHB-1 2018, versioned by the 2019 and 2022 amendments.
- IFS-UK-005: Procurement Rules 2017/2021 plus the procurement grievance portal.
- IFS-UK-006: DLC input to pension. e-Society registration sits inside IFMS. e-Challan replaced manual challans (GO 31-12-2020).

### Bihar (BR)
- IFS-BR-001: Treasuries report directly to the Finance Department, with no directorate (IFS-060 depth).
- IFS-BR-002: AC/DC pairing per BTC 2011 (IFS-049).
- IFS-BR-003: ULB-type deposit sub-heads 8448-00-102-0001/2/3 (IFS-059).
- IFS-BR-004: SNA-SPARSH bill type with e-Kuber JIT (IFS-051, bill-type variant).
- IFS-BR-005: Banking-only treasuries, with cash workflows switched off per treasury.
- IFS-BR-006: Citations to BTC 2011, BFR and BBM rules and forms (BTC Form-8 LOC register).
- IFS-BR-007: Audit trail on by default and impossible to switch off (IFS-075).

### Jharkhand (JH)
- IFS-JH-001: TSP / SCSP / OSP component attribute and reports (IFS-038).
- IFS-JH-002: AG GPO/PPO format checks (9-digit GPO) and duplicate-payment blocks (IFS-070).
- IFS-JH-003: Reject a challan deposit below the generated amount (IFS-055).
- IFS-JH-004: No back-end bypass; approved exception workflow (IFS-075).
- IFS-JH-005: JTC Para 11B change register.
- IFS-JH-006: Map CSS schemes to PFMS / SNA-SPARSH codes (IFMS was not linked to PFMS at the time of the CAG audit).
- IFS-JH-007: WAMIS and SBI-CMP rails alongside e-Kuber. "Hamar Apan Budget" citizen-budget output.

### Punjab (PB)
- IFS-PB-001: Revenue apportionment of one challan by percentage (VAT 11/89).
- IFS-PB-002: GIA sanction + UC tracking, with CSV to AG VLC (IFS-050).
- IFS-PB-003: Two-stage payment failure (IFS-054). Account pre-validation is optional.
- IFS-PB-004: Rail per bill category, so pension can stay off e-Kuber (IFS-053).
- IFS-PB-005: Unique Payee Code, and detection of several beneficiaries on one account (IFS-056).
- IFS-PB-006: Punjab FR/TR rule pack, published for re-use by Haryana and Chandigarh.

### Haryana (HR)
- IFS-HR-001: Single-purpose cyber treasuries: e-receipts (Chandigarh) and SNA-SPARSH (Panchkula, from 25-01-2025).
- IFS-HR-002: Signing sequence: TO signs, then the DDO countersigns (IFS-073).
- IFS-HR-003: Change of payee code (UCP) as a maker-checker flow tied to e-Kuber.
- IFS-HR-004: PRI deposit sub-heads 8448-109-98/99.
- IFS-HR-005: **Adopted rulebook**: Punjab FR/TR with Haryana overrides (IFS-067).

### Himachal Pradesh (HP)
- IFS-HP-001: Functional treasuries (Capital, Cyber, GST, SNA) and full treasuries in remote tribal blocks (Pangi, Kaza).
- IFS-HP-002: Budget check at DDO × SOE (TR 2017 r.110) (IFS-039).
- IFS-HP-003: Cheque-drawing DDOs and non-banking sub-treasuries. The rail must work **without e-Kuber**.
- IFS-HP-004: Pay bills split by OPS / NPS (joined on or after 15-05-2003).
- IFS-HP-005: DBT bills from e-Kalyan (HPTR-5) enter the treasury queue (IFS-046).
- IFS-HP-006: One DDO maps to one treasury (CAG found 439 DDOs mapped to more than one).
- IFS-HP-007: State-prefixed form identifiers held as data. Lotteries, stamps and opium custody as treasury functions.

### Karnataka (KA)
- IFS-KA-001: Sanction-before-bill, with checks against GDFP/SDFP on DSC-signed sanctions (IFS-044).
- IFS-KA-002: Budget Release → AIE → GDFP release / redistribution, plus re-appropriation, additionality and Contingency Fund (IFS-040).
- IFS-KA-003: 15-digit HoA = MH4-SMH2-MinH3-**SH1-DH2-OH3** (12-digit for deposits) with a revenue/capital flag.
- IFS-KA-004: External HRMS as the salary-bill source (IFS-046).
- IFS-KA-005: Cyber, Huzur, Pension Monitoring and **CAU (SS-only)** treasuries.
- IFS-KA-006: Works bills carry e-MB, contract and sanction artefacts. Procurement splits at ₹5 lakh (VMS below, GeM above).
- IFS-KA-007: Payee bank details locked after real-time validation.
- IFS-KA-008: Failed payment → suspense → reissue → AG Alteration Memo after one year.

### Tamil Nadu (TN)
- IFS-TN-001: **Global Beneficiary Master** (IFS-056).
- IFS-TN-002: Apex welfare bill of up to about 10⁶ lines (IFS-078).
- IFS-TN-003: Work ID at proposal stage, activated on AS; capital-works bill type.
- IFS-TN-004: Receipts of ₹1 crore and above go by RBI NEFT/RTGS (IFS-055).
- IFS-TN-005: Quarterly expenditure caps with relaxation.
- IFS-TN-006: Budget "Number Statement" derived from payroll.
- IFS-TN-007: OPPAS pension authorisation; face-authentication mustering within a 12-month window.
- IFS-TN-008: Automatic fetch of utility bills (TNPDCL, about 1.15 lakh connections).
- IFS-TN-009: SC/ST component and gender tags. Automatic TDS from 01-04-2025. TNTT Act/Rules rule pack.

### Kerala (KL)
- IFS-KL-001: **Treasury savings bank ledger**: SB, FD, pensioner SB, interest, TDS, e-KYC (IFS-058).
- IFS-KL-002: Salary and pension credited to TSB or to a bank.
- IFS-KL-003: Scheme funds in Plan TSB 8031-102-99 instead of PD.
- IFS-KL-004: **Virtual DDO** role (about 16,550 LSGI officers), with claims coming from Sankhya.
- IFS-KL-005: FIFO intake from BiMS, SPARK, Sankhya and EMLI.
- IFS-KL-006: Project / work / procurement / scheme must be pre-registered before a bill.
- IFS-KL-007: **Tokenless** e-bill (IFS-048).
- IFS-KL-008: e-TR5 electronic departmental receipt. Kerala Treasury Code 2016 and Stores Purchase Manual 2013 rule packs.

### Andhra Pradesh (AP)
- IFS-AP-001: **Green Channel** flag on PD accounts, audited and controlled by policy (G.O.Ms.99/2019).
- IFS-AP-002: Ways-and-means / Finance Department clearance queue for other bills.
- IFS-AP-003: 7-tier HoA. A bill must not span major heads.
- IFS-AP-004: Objection-return loop only, with no outright disallowance (draft TR 2022).
- IFS-AP-005: Adapter boundary to **SAP S/4HANA CFMS** (IFS-076).
- IFS-AP-006: Several receipt aggregators (SBIMOPS, PayU, RBI).
- IFS-AP-007: SCSP / STC / BC / MAP / gender tags and scheme-source types. **Comprehensive Budget Release Order**: all four quarters released at once, then an HoD distribution order (CBDO) to DDOs (P: the 2026-27 CBRO guideline on apfinance.gov.in was identified, not opened).

### Telangana (TG)
- IFS-TG-001: PD lifecycle at scale: 20,000+ accounts, lapse under GO 25/2019, inoperative alerts.
- IFS-TG-002: DDO-code succession on GP → ULB merger (IFS-065).
- IFS-TG-003: DBT burst capacity (IFS-078).
- IFS-TG-004: Treasury and PAO dual units for the capital.
- IFS-TG-005: Token unique per treasury per day.
- IFS-TG-006: BRO / budget authorisation as separate stages.
- IFS-TG-007: Work-ID linkage. Contract and honorarium pay only through IFMIS-DBT [U: pass 2 found only a non-official copy of a 2025 health-department G.O.].

### West Bengal (WB)
- IFS-WB-001: Unit types: treasury, PAO, Cyber Treasury (CAG; and **Cyber Treasury-II, code CAH**, notification DT/O/02/C-2025/893 of 11-09-2025 for state GIA via e-Kuber, V), Pension Disbursement Office, Delhi PAO.
- IFS-WB-002: 17-digit HoA with NP/SP and charged/voted segments [U]; demand = department.
- IFS-WB-003: GRIPS 2.0 receipts (multi-credit, PoS) and refund of online deposits.
- IFS-WB-004: SNA-SPARSH as **virtual limits to executing agencies**, with FTO → bill conversion.
- IFS-WB-005: Monthly account due by the 5th (IFS-074).
- IFS-WB-006: AAFS sanction object linked to UPMS project ids.
- IFS-WB-007: Scheduled monthly DBT windows. A scheme can be replaced without code changes.
- IFS-WB-008: GTA grants flow through Home & Hill Affairs (IFS-062).
- IFS-WB-009: Soft vouchers (TR forms) for the AG.
- IFS-WB-010: Gender and child budget tags.

### Odisha (OD)
- IFS-OD-001: District, Sub, Special (incl. New Delhi), Cyber and State Pension treasuries. Works and forest divisions and PL operators originate payments.
- IFS-OD-002: DDO "Book of Drawal" and a token series.
- IFS-OD-003: Four-stage treasury scrutiny with objection and resubmission (IFS-047).
- IFS-OD-004: **Centralised e-Payment Cell** batching advices into e-Kuber (half-hourly NEFT).
- IFS-OD-005: 20 bill types and about 70 sub-types, validated against any HoA segment.
- IFS-OD-006: 6 segments + object; A/B expenditure type.
- IFS-OD-007: **State JiT-FS** API for external systems (IFS-052).
- IFS-OD-008: CSS-JIT with code mapping and a voucher-level PFMS push.
- IFS-OD-009: GeM fund blocking (IFS-068).
- IFS-OD-010: System-generated sanction order mandatory (since 2018).
- IFS-OD-011: Odia, English and Hindi for every service flow.
- IFS-OD-012: SBMS for ULBs, GPs, NHM and OLM, with passbook reconciliation and UCs.

### Assam (AS)
- IFS-AS-001: **Council Fund ledgers** (BTC under the BTC Fund Rules 2012; KAAC; DHAC), with the share computed by formula (IFS-033, IFS-062).
- IFS-AS-002: Pass-through of central releases GoI → State → Council, with UC tracking.
- IFS-AS-003: An extra-territorial treasury (Shillong South) (IFS-061).
- IFS-AS-004: Vendor and Agency Bank user classes.
- IFS-AS-005: e-GRAS adapter.
- IFS-AS-006: Phased cut-over from CTMIS to FinAssam per treasury.
- IFS-AS-007: Vouchers signed by both the DDO and the treasury.
- IFS-AS-008: RIDF loan and reimbursement claims.
- IFS-AS-009: Assamese, with Bodo and Bengali as configurable additions.
- IFS-AS-010: DFPR 1999 schedules versioned.

### Sikkim (SK)
- IFS-SK-001: **Banking-model switch**: State Bank of Sikkim with cheque / bank-draft settlement and no dependence on e-Kuber (IFS-053).
- IFS-SK-002: Non-banking CPAOs; cheque stock, cancellation and revalidation registers (Handbook forms 25 and 28).
- IFS-SK-003: SNA-SPARSH Cyber Treasury as its own accounting unit.
- IFS-SK-004: Hard budget block and period lock (IFS-043).
- IFS-SK-005: Separate Pension / GPF / GIS directorate that also pays central pensions.
- IFS-SK-006: PAO for the Legislative Assembly.
- IFS-SK-007: AC/DC backlog alerts.
- IFS-SK-008: SFR 1979 rule pack plus the 2024 amendment, with continuity of old laws under **Art. 371F(k)** (V).

### Arunachal Pradesh (AR)
- IFS-AR-001: Treasuries outside the state (Shillong South, DRC Guwahati, Delhi).
- IFS-AR-002: Parent for account rendition kept separate from administrative control.
- IFS-AR-003: Offline / manual treasury mode with batch rendition (IFS-077).
- IFS-AR-004: Rule pack = central CTR Vol-I + GFR 2017.
- IFS-AR-005: DC / SDO (Civil) as treasury supervisor in the chain.
- IFS-AR-006: Late SNA-SPARSH onboarding date.

### Manipur (MN)
- IFS-MN-001: **Finance Officer vetting** (with U.O. number and date) for every ADC / DRDA proposal (IFS-063).
- IFS-MN-002: BEAMS authorisation slip between release and bill.
- IFS-MN-003: RBI e-Kuber as the sole agency bank.
- IFS-MN-004: Cyber Treasury as an accounts-rendering unit for GRAS.
- IFS-MN-005: GFR + CTR + DFPR 1995 rule pack, with department Schedule-III overrides (High Court 2024).
- IFS-MN-006: Six-monthly photo life proof.
- IFS-MN-007: Hill-area (ADC jurisdiction) flag on DDOs and offices.

### Meghalaya (ML)
- IFS-ML-001: **Multi-government treasury** (Shillong South for ML, AS, MZ, AR) (IFS-061).
- IFS-ML-002: SBI or PNB mapped per treasury.
- IFS-ML-003: The treasury approves or objects only and **never edits** (IFS-047).
- IFS-ML-004: TO DSC plus a SANDES / e-mail OTP before the e-Kuber file is released.
- IFS-ML-005: eProposal → AA → payment advice linkage.
- IFS-ML-006: Treasury Rules 1985 + Financial Rules 1981 + DFPR 2006, with GFR as fallback and the Assam Budget Manual inherited.
- IFS-ML-007: ADC revenue-share transfers (forest revenue, profession tax).
- IFS-ML-008: Monthly account due by the 5th.

### Mizoram (MZ)
- IFS-MZ-001: MIWAS works accounts for PWD, PHE, Power, I&WR, Forest and LRSWC.
- IFS-MZ-002: e-Sanction DDO → HoD → Admin Department → Finance, with a hard budget stop.
- IFS-MZ-003: 100 % DBT for state schemes, with a status API.
- IFS-MZ-004: Central DFPR applied through an adaptation order; Heads of Office notified by the Finance Department into the DFPR Rule 14 schedules I–III (P: Assembly gazette mirror, not a gov.in host).
- IFS-MZ-005: ADC District Funds (Lai, Mara, Chakma) under the MADC Fund Rules 1996 (from 26-11-1996), replaced by the Mizoram ADC Fund Rules 2010 (gazette 27-07-2010) (V, CAG MADC report 2015-16).
- IFS-MZ-006: Department label "Accounts & Treasuries / Chief Controller of Accounts". No "DTA" label may be assumed.

### Nagaland (NL)
- IFS-NL-001: Treasury-held DDO sub-allotment ledger, which blocks a bill that exceeds it.
- IFS-NL-002: Treasury acting as DDO for pensioners and Grade-IV GPF.
- IFS-NL-003: CTR + GFR + CPWD Account Code rule pack.
- IFS-NL-004: CMP → e-Kuber migration with parallel running.
- IFS-NL-005: Late SNA-SPARSH onboarding.
- IFS-NL-006: Village Council / Village Development Board as payee or implementing entity (VDBs under the Nagaland Village and Area Council Act 1978 receive grants-in-aid and implement; P, rd.nagaland.gov.in).

### Tripura (TR)
- IFS-TR-001: Hard HRMS-to-salary-bill binding, with no free-text net pay (IFS-071).
- IFS-TR-002: Maker-checker on new beneficiaries; one id per account.
- IFS-TR-003: GFR and CTR "as adopted", plus DFPR 2019 with 7 amendments.
- IFS-TR-004: **TTAADC** as a separate entity (TTAADC (Budget & Account) Rules 1982, V) with PLA passbook reconciliation.
- IFS-TR-005: Anomaly-analytics hook (the state uses IDEA).
- IFS-TR-006: Bengali, Kokborok and English (V).
- IFS-TR-007: Migration from CTOS to IFMS 2.0.

### Delhi (DL)
- IFS-DL-001: **PAO-only deployment** with a Principal Accounts Office tier.
- IFS-DL-002: Two consolidation feeds (Delhi e-Lekha and CGA PFMS) with reconciliation.
- IFS-DL-003: SGST reconciliation between RBI and GSTN.
- IFS-DL-004: LPC and GPF capture in payroll bills.
- IFS-DL-005: Own-budget heads kept separate from "Transfers to Delhi" receipts (Demand 57).
- IFS-DL-006: Coexistence path with the planned new IFMS [U].

### Puducherry (PY)
- IFS-PY-001: Region dimension (Puducherry, Karaikal, Mahe, Yanam) with branch routing.
- IFS-PY-002: Pension payer classes including the ex-French government.
- IFS-PY-003: Treasury pays on behalf of municipalities and commune panchayats, with ledgers per local body.
- IFS-PY-004: Language per region. Tamil, Telugu, Malayalam and English are all official (V); mapping one language to each region (Yanam Telugu, Mahe Malayalam) is inference.
- IFS-PY-005: Connectors for BEAMS, GRAS, e-Salary, SNA-SPARSH, e-Kuber and NPCI.

### Jammu & Kashmir (JK)
- IFS-JK-001: **BEAMS allocation gate** consumed by the bill (IFS-040).
- IFS-JK-002: 37 JKPaySys bill types mapped to J&K Treasury Code forms.
- IFS-JK-003: Own codes (Financial Code, Treasury Code, Book of Financial Powers) with GFR 2017 layered on for procurement.
- IFS-JK-004: JKGRAS / Cyber Treasury, e-Kuber and GSTN-TDS connectors. J&K Bank e-banking.
- IFS-JK-005: Geo-tagged evidence of work progress tied to releases.
- IFS-JK-006: Urdu (RTL), Hindi, Dogri, Kashmiri and English output (J&K Official Languages Act 2020, V).
- IFS-JK-007: Approval authorities by period (Parliament-voted 2019–24, then the Assembly) (IFS-065).

### Ladakh (LA)
- IFS-LA-001: Two rails in parallel (LIFMS / TreasuryNet and PFMS), with a switch-over date per DDO.
- IFS-LA-002: **LAHDC** Council Funds, Fund Office and Chief Controller of Finance, with possible non-lapsable carry-forward.
- IFS-LA-003: Council powers temporarily vested in the DC.
- IFS-LA-004: Demand 55, with flow UT → Council → DDO.
- IFS-LA-005: Seasonal working calendar for high-altitude works [U: only a third-party copy of a 2024 PW(R&B) order was found].

### Chandigarh (CH)
- IFS-CH-001: Treasury-based, Parliament-funded UT that renders accounts to the AG (A&E) and not to CGA (IFS-036).
- IFS-CH-002: Rule pack chosen by employee origin and date (Punjab or Haryana deputationist vs UT cadre on central service conditions from 01-04-2022: UT of Chandigarh Employees (Conditions of Service) Rules 2022, MHA notification 29-03-2022, V).
- IFS-CH-003: PFMS beneficiary de-duplication.
- IFS-CH-004: GIA to the Municipal Corporation and aided institutions, with UCs.

### Dadra & Nagar Haveli and Daman & Diu (DH)
- IFS-DH-001: Entity merger of the two former PAOs, with history and code mapping (IFS-065).
- IFS-DH-002: Dimension for the three non-contiguous districts.
- IFS-DH-003: Separate GIA streams for panchayats and municipal councils.
- IFS-DH-004: Gujarati (Daman & Diu, 1987 Act) and Hindi / English (Union) (P).
- IFS-DH-005: e-Lekha accounts with two PAO units still separate (Silvassa, Daman) (V).
- IFS-DH-006: DFPR + GFR + CTR + FR rule pack; draft Panchayats (Application of Funds and Maintenance of Accounts) Rules 2024 (V).

### Lakshadweep (LD)
- IFS-LD-001: Offline capture on the outer islands, forwarded later to the Kavaratti PAO (IFS-077).
- IFS-LD-002: Field Pay Unit queue.
- IFS-LD-003: Ship operations expenditure and ship-ticket receipts as workflow types.
- IFS-LD-004: PAO as a mandatory member of procurement and condemnation committees (core committee GP-03).
- IFS-LD-005: English as the official language (V); Malayalam is used on the website only (not established as an official language).

### Andaman & Nicobar (AN)
- IFS-AN-001: Chief PAO → PAO → Sub-PAO → CDDO/DDO hierarchy, with PFMS PAO codes as master data.
- IFS-AN-002: LC workflow for APWD and Forest, with post-check of divisional accounts.
- IFS-AN-003: Remote sub-PAOs with SLAs that can be paused (IFS-077).
- IFS-AN-004: GIA to the three PRI tiers and the Municipal Council, linked to the internal audit plan.
- IFS-AN-005: Rule pack cites the **R&P Rules 2022**, not 1983 (rule versions per tenant).

**Count:** 248 state- and UT-specific items across all 36 entities. About 60 % of them are *instances* of a cross-state IFS-0xx item, so they become parameter values. The rest are genuinely state-unique features, which the profile switches on as optional capabilities. Examples: the Kerala treasury bank, the AP Green Channel, the Sikkim state bank, multi-government treasuries, Odisha's state JiT-FS, the MH Virtual PLA and the TN GBM.

---

## 5. Gap analysis against the existing packs

Baseline (from `sangam-packs/README.md` and the P1 sources):
- Bill payment is P1. Its material fields are payee, account, ifsc, amount, `budgetHead` (a free string, sample `4059-01-051`) and sanctionRef.
- P1's connectors are ifms.duplicateBill, ifms.budget, ifms.encumber, ifms.release, pfms.payment, bank.statement, esign.sign and notify.sms.
- P1's DMN `p1-competent-authority` maps (caseType, amount) to a role: DDO, EE, SE, CE.
- The core DMN `sangam-core-powers` maps (seatRole, caseType) to a limit (SO 1 lakh … SECRETARY).
- `tenantOverrides` supports only `kind: DMN`; the sample is state-b `works-config.dmn`, where AA comes first.
- Forms are en/hi only, and seeds are synthetic.
- The ports are ConnectorGateway, SeatDirectory, BusinessCalendar, LedgerStore and BatchLedger.

Status legend:
- **C**: covered.
- **P**: partly covered, so it needs extension.
- **G**: gap.

| # | Requirement area (IFS) | Existing pack / mechanism | Status | What is missing |
|---|---|---|---|---|
| GA-01 | State profile as one deployable artefact (§6) | `PackDeployer.deploy(packIds, tenantId)` + `tenantOverrides` (DMN only) | **G** | Override kinds for forms, seeds, calendars, connector bindings, language packs, catalogues and rulebooks. A profile manifest; precedence and inheritance; validation and activation workflow; effective dating |
| GA-02 | Segmented chart of accounts, LMMH reference, correction slips (IFS-001…010, 038, 039) | `budgetHead` free string | **G** | CoA service and reference data; segment schema per state; tag sets; validation at bill entry; CS ingestion process |
| GA-03 | Budget chain allocation → release → authorisation → sub-allotment (IFS-040…043) | `ifms.budget` query + `ifms.encumber`/`release` (GP-11); `gov.ifms.budget-reappropriation` | **P** | Multi-stage authorisation objects (BRO, AIE, BEAMS slip, DDO sub-allotment); hold-for-budget queue; ceilings and relaxation; control-dimension choice |
| GA-04 | Sanction-before-bill (IFS-044) | `sanctionRef` is a material field; core sanction block | **P** | Sanction as a linked case whose classification, limit and payee category are checked by the bill; Work ID / AAFS / pre-registration entities |
| GA-05 | Bill-type catalogue keyed to state forms (IFS-045) | One generic P1 bill + salary + pension packs | **G** | Catalogue data (form no., HoA patterns, deductions, attachments, chain ref) and a catalogue-driven form/DMN selection |
| GA-06 | Multi-source bill intake (IFS-046) | ConnectorGateway inbound messages | **P** | Inbound "bill received from external system" start contract per source (HRMS, LSG, works, welfare, ministry portals) |
| GA-07 | Treasury scrutiny chain, objections, token, no-edit (IFS-047, 048) | P1 has treasury rejection (bank details only) and core scrutiny / clarification | **P** | Treasury-side stage with configurable depth; structured objection codes with rule citations; token series; a no-edit policy for treasury users |
| GA-08 | AC/DC pairing, GIA/UC tracking (IFS-049, 050) | — | **G** | Linked-case pattern exists (`LinkedInstanceDelegate`, GP-22/54) but no AC/DC or UC pack |
| GA-09 | SNA-SPARSH lifecycle (IFS-011…024, 051, 052) | `gov.dbt.batch` (GP-49 partial failure), `pfms.payment` connector, `sangam.pfms.paymentFile` topic | **P/G** | SLS / ratio master; Mother Sanction ledger with 31-March lapse; IA hierarchy; Cyber Treasury consolidation; 15:00 cut-off timer; child-sanction message; reissue tag on re-push; SLS reconciliation; hosting variants; state JiT-FS |
| GA-10 | Payment rails (IFS-025, 026, 053, 054) | One `pfms.payment` + `bank.statement`; VI.3 reconciliation | **P** | Rail abstraction (e-Kuber, CMP, state bank, cheque/LoC, APBS) selected by state, treasury and bill type; e-Kuber DN/CN and return-note messages; two-stage failure; suspense → reissue → alteration memo; parallel-run migration |
| GA-11 | Receipts / GRAS (IFS-027, 028, 055) | `gov.ifms.challan-reconciliation` (scroll matching, GP-31) | **P** | GRN/CIN lifecycle, apportionment, channel thresholds, short-deposit reject, refund / defacement, challan as security |
| GA-12 | Payee golden record (IFS-056) | `ifms.duplicateBill` only | **G** | Payee registry with validation, lock and de-duplication across payroll, pension, vendor and DBT |
| GA-13 | Public-account sub-ledgers (IFS-057…059) | `LedgerStore` (generic postings), `gov.security-instrument` | **P** | PD/PL/PLA/Virtual PLA/Plan TSB/Green Channel/SBMS account types with controls (minus balance, lapse, inactivity, bypass flag); Kerala treasury bank |
| GA-14 | Accounting-unit topology (IFS-060, 061, 064, 066) | `SeatDirectory` office hierarchy + `seatForRole` | **P** | Typed accounting units (treasury, PAO, cyber, CAU …) with banking mode, rendition parent, `served_governments[]`; DDO-to-treasury mapping; special DDO classes; location dimension |
| GA-15 | Sub-state fund entities and statutory transfers (IFS-033, 062, 063) | Referral across tenants (GP-32) | **G** | Entity type for ADC / LAHDC / GTA / ULB / PRI, with own fund rules and PLA; share-formula transfers; finance-officer vetting step |
| GA-16 | Delegation of powers (IFS-030) | `sangam-core-powers.dmn` (seatRole, caseType → limit); `p1-competent-authority.dmn` (caseType, amount → role); version binding per deployment (GP-19) | **P** | DMN inputs for office tier / level, object head / transaction type, general vs special delegation, acting-for; amendment-dated tables per state; values are synthetic |
| GA-17 | Layered rulebooks with citations (IFS-067) | DMN overrides per tenant (GP-37) | **P** | "adopted-from" layering (central → donor state → state amendments); citation fields on validation outcomes and objection memos |
| GA-18 | Works variants (IFS-069) | `gov.works` (TS/AA order switch via `works-config.dmn` + state-b override), `gov.work-order`, `gov.contract-management` | **P** | CCL/DCL and LoC cheque-drawing mode; Work ID activation on AS; e-MB attachments on bills; division accounts rendered to the AG |
| GA-19 | Procurement rule packs and GeM hooks (IFS-031, 068) | `gov.tender`, `gov.gem-purchase` (mode DMN), `gov.consultancy` | **P** | State procurement-act DMNs (RTPP, KTPP, TNTT …); GeM fund block before order |
| GA-20 | Pension variants (IFS-070) | `gov.pension` (sanction, PPO, monthly) | **P** | Payer classes and recoupment; AG authorisation format validation; life-proof modes |
| GA-21 | Payroll binding (IFS-071) | `gov.ifms.salary-monthly` | **P** | External HRMS as system of record; no free-text net pay; OPS/NPS bill split; LPC |
| GA-22 | Languages beyond Hindi (IFS-072, R54) | Forms en + hi; Unicode round trip (GP-38) | **P** | Language-pack mechanism per tenant (n languages, scripts, RTL); per-region language; legacy-font import |
| GA-23 | Signature policy and sequence (IFS-073) | `SignatureDelegate`, `signEachLevel`, `signatureRequired` | **P** | Signing order (TO then DDO), dual signature on vouchers, OTP release channel, mandatory-DSC policy per tenant |
| GA-24 | Account rendition to AG / CGA (IFS-074, 036) | — | **G** | Monthly account process (5th-day SLA), VLC / e-voucher / SFTP exports, rendering target AG vs CGA |
| GA-25 | No-bypass, change register, period lock (IFS-075, 043) | Audit sink (R42); SoD and power guards; ModelGuard | **P** | Configuration change-approval workflow for profiles; accounting-period lock |
| GA-26 | Coexistence and phased cut-over (IFS-076) | Ports + simulators (ADR-D-13) | **P** | Adapter contracts for SAP CFMS and NIC suites; per-treasury cut-over switch with dual run |
| GA-27 | Offline / remote units (IFS-077) | Air-gapped install (AC-11) | **P** | Store-and-forward at unit level; SLA pause per location (core SLA pause exists for statutory clocks) |
| GA-28 | High-volume DBT (IFS-078) | `gov.dbt.batch`, GP-08/09 | **P** | Throughput target (millions of credits a day) and e-Kuber batching; DBT Mission code and APBS flag |
| GA-29 | UT regimes (IFS-036, 065) | — | **G** | Budget as a Union demand vs own Consolidated Fund; Administrator delegation; regime change by period |
| GA-30 | Tenant granularity | AC-09: tenant = department | **P (design decision)** | The brief sets tenant = state, but packs isolate tenants per department. A **two-level tenant (state → department)** with inherited profile is needed (§6.4) |

**Top 10 gaps by impact on adoption** (to schedule first):
1. GA-01 (state profile artefact)
2. GA-02 (chart of accounts)
3. GA-09 (SNA-SPARSH; mandatory everywhere since 01-11-2025)
4. GA-10 (payment rails)
5. GA-14 (accounting-unit topology)
6. GA-05 (bill catalogue)
7. GA-03 (budget chain)
8. GA-13 (public-account sub-ledgers)
9. GA-15 (sub-state entities)
10. GA-22 (language packs)

---

## 6. Proposed state profile configuration model

**Principle.** A state (or UT) switches Sangam on by **publishing a profile**, a versioned bundle of data and DMN, and deploying the same packs against it. No state-specific Java, BPMN fork or schema change is allowed. Every value has `effectiveFrom` / `effectiveTo` and a `source` (citation or GO number), because rules change during the year: LMMH slips, DFPR amendments, SNA-SPARSH onboarding dates, treasury restructuring.

### 6.1 Layers and precedence

```
L0  central reference      LMMH (MH/SMH/MinH, standard minor heads), GFR 2017, DFPR 2024, R&P Rules,
                           SNA-SPARSH protocol constants, e-Kuber calendar, IGAS      (read-only, shipped)
L1  category preset        GENERAL | NE_HIMALAYAN | UT_WITH_LEGISLATURE | UT_WITHOUT_LEGISLATURE
                           (CSS ratios, PFMS mode, budget mode, accounting target)
L2  donor rulebook         optional "adoptedFrom": e.g. HR <- PB FR/TR; NE states <- CTR+GFR "as adopted"
L3  state profile          the state's own values (this section)
L4  department overlay     per-department DMN rows / seats (today's GP-37 mechanism)
L5  office overlay         office-specific rows (e.g. works-config row per office)
Resolution: most specific wins; L0 central-reference fields are not overridable (validation error).
```

### 6.2 Profile structure (illustrative YAML; a data contract, not code)

```yaml
profile: { id: IN-RJ, name: {en: Rajasthan, hi: राजस्थान}, version: 2026.11.0,
           effectiveFrom: 2026-04-01, approvedBy: <seat>, source: "FD order …" }
regime:
  category: GENERAL                 # L1 preset → CSS 60:40/50:50
  budgetMode: OWN_CONSOLIDATED_FUND # | UNION_DEMAND (UT-NL: demandNo, adminDelegationLimit)
  accountsRenderedTo: AG_AE         # | CGA_ELEKHA
  rbiBanker: true                   # SK: false → banker: STATE_BANK_OF_SIKKIM
languages:
  ui: [hi, en]; outputs: {default: [hi, en], auditReports: [hi]}
  regions: {}                       # PY: {KARAIKAL: [ta,en], MAHE: [ml,en], YANAM: [te,en]}
calendar: { holidays: ref:calendars/IN-RJ-2026.ics, ekuberCalendar: L0, locations: {} }
orgTopology:
  directorate: { name: "Directorate of Treasuries & Accounts", tiers: [DIRECTORATE, TREASURY, SUB_TREASURY] }
  unitTypes: [DISTRICT_TREASURY, SUB_TREASURY, CYBER_TREASURY_SNA]     # from the L0 type catalogue
  units: ref:seed/units.json        # code, type, banking: true|false, renditionParent, agencyBank,
                                    # owningGovernment, servedGovernments[], physicalState, from/to
  ddoClasses: [REGULAR, PD_ADMINISTRATOR]   # KL adds VIRTUAL_DDO; NL adds TREASURY_AS_DDO
chartOfAccounts:
  central: L0@CS-1132
  segments:                         # below MinH; widths and render are state data
    - {id: demand,    width: 2, position: prefix}
    - {id: subHead,   width: 2, render: "(%s)"}
    - {id: groupHead, width: 2, render: "[%s]", optional: true}
    - {id: objectHead,width: 2, master: ref:coa/object-heads.csv}
  tags: [CSS_SHARE_CA_SF, CHARGED_VOTED]      # JH: TSP/SCSP/OSP; AP: SCSP/STC/BC/MAP/GENDER; OD: EXP_TYPE_A_B
budgetControl:
  chain: [ALLOCATION, RELEASE, DDO_ALLOTMENT] # KA: [..., AIE]; JK/MN: [..., BEAMS_SLIP]; NL: treasury-held sub-allotment
  controlDimension: DDO_X_OBJECT_HEAD         # HP: DDO_X_SOE
  holdForBudgetQueue: true; hardBlock: true; ceilings: [] ; quarterlyCaps: null
rulebooks:
  base: [L0:GFR2017@2025-07-31]
  adoptedFrom: null                           # HR: {state: IN-PB, books: [PFR, PTR]}
  own: [{id: GFAR, parts: [I,II,III]}, {id: RTPP, act: 2012, rules: 2013, amendment: 7, date: 2025-11-17}]
  citationsOnValidation: true
delegation:                                   # DMN tables (versioned), deployed as tenant overrides
  tables: [ref:dmn/powers.dmn, ref:dmn/competent-authority.dmn, ref:dmn/reappropriation.dmn]
  inputs: [seatRole, officeTier, caseType, objectHead, amount, delegationKind]   # GDFP/SDFP
billCatalogue: ref:catalogue/bills.yaml        # id, formNo, hoaPatterns[], deductions[], attachments[],
                                               # sanctionRequired, chainRef, signatureSequence, rail
approvalChains:                                # named chains referenced by bill types and sanctions
  DDO_BILL:  [MAKER, CHECKER, DDO_APPROVER]
  TREASURY:  [FRONT_DESK, DEALING_ASST, ACCOUNTANT, TO]   # depth per unit type; outcome PASS|OBJECT
  policies: {treasuryMayEdit: false, objectionReturnsOnly: true, sod: PERSON_AND_SEAT}
signatures: {dscMandatory: true, voucherSigners: [DDO], sequence: [DDO], releaseOtp: null}
                                               # HR: sequence [TO, DDO]; ML: releaseOtp: SANDES|EMAIL
paymentRails:
  default: EKUBER
  byBillType: {}                               # PB: {PENSION: AGENCY_BANK}
  byUnit: {}                                   # HP: {CHEQUE_DRAWING_DDO: TREASURY_CHEQUE}
  failure: {twoStage: true, suspenseAfterDays: 0, alterationMemoAfter: P1Y}
receipts: {portal: EGRAS, apportionment: [], channelThresholds: [], rejectShortDeposit: true}
publicAccounts:
  types: [PD]                                  # MH: VIRTUAL_PLA; KL: PLAN_TSB, TREASURY_SB; AP: GREEN_CHANNEL_PD
  controls: {noMinusBalance: true, inactivityClose: P3Y, cfTransferAlert: 10000000}
integrations:
  pfms: {model: 4, ssid: <cfg>}
  snaSparsh: {enabled: true, onboarded: 2023-08-07, hosting: CYBER_TREASURY, cutoff: "15:00", reissueTag: true}
  stateJit: null                               # OD: {enabled: true, api: true}
  ekuber: {payments: true, receipts: true}; gstn: true; npci: {apbs: true}
  hrms: {source: PAY_MANAGER, mode: INTERNAL}  # KA/KL: EXTERNAL (HRMS / SPARK)
  external: [AADHAAR_VAULT, AG_VLC]            # AP: SAP_CFMS core adapter
subStateEntities: []                           # AS: [{type: SIXTH_SCHEDULE_ADC, id: BTC, fundRules: "BTC Fund Rules 2012",
                                               #       share: ref:dmn/btc-share.dmn, vetting: null}]
works: {sanctionOrder: TS_FIRST, creditLimitMode: null, workId: true}   # UK: CCL_DCL; GJ: LOC
pension: {payerClasses: [STATE], lifeProof: [DLC], agAuthorisationFormat: null}
procurement: {act: RTPP, gemMandatory: false, gemFundBlock: false}
accounts: {monthlyDueDay: 10, exports: [VLC], periodLock: true}
operations: {offlineUnits: [], slaPauseLocations: []}
retention: {categories: ref:core-retention.dmn}   # R61
```

**Contrast examples.** These are the switches only; everything else is inherited.
- **IN-SK:** `rbiBanker: false`, `banker: STATE_BANK_OF_SIKKIM`, `paymentRails.default: CHEQUE`, unit types `PAO, CPAO(banking:false), CYBER_TREASURY_SNA, LEGISLATURE_PAO`, rulebook `SFR1979 + 2024 amendment`.
- **IN-ML:** a `servedGovernments` entry for Shillong South `[IN-ML, IN-AS, IN-MZ, IN-AR]`, `signatures.releaseOtp: SANDES`, `approvalChains.policies.treasuryMayEdit: false`, a Sixth Schedule ADC entity, `adoptedFrom: {state: IN-AS, books: [BUDGET_MANUAL]}`.
- **IN-AN:** `category: UT_WITHOUT_LEGISLATURE`, `budgetMode: UNION_DEMAND (52)`, `accountsRenderedTo: CGA_ELEKHA`, unit types `CHIEF_PAO, PAO, SUB_PAO`, rulebook `R&P Rules 2022`, PFMS model 3, `operations.offlineUnits: [Campbell Bay …]`.

### 6.3 How the profile maps onto Sangam mechanisms

| Profile section | Realised as | Existing hook / needed extension |
|---|---|---|
| delegation, budgetControl rules, procurement, works switches, sanction authorities | DMN tables deployed as tenant overrides | **Exists** (`tenantOverrides kind: DMN`, `Decisions` prefers tenant). Extend the DMN inputs (GA-16) |
| orgTopology, seats, DDO classes | Seeds loaded into `SeatDirectory` | **Exists** for seats; extend to typed accounting units (GA-14). New override kind `SEED` |
| billCatalogue, approvalChains | Catalogue data read by P1/salary/pension packs to choose form, DMN and chain | **New** override kinds `CATALOGUE`, `CHAIN`; packs read the chain from the catalogue, not literals |
| chartOfAccounts | CoA reference service + per-tenant segment schema; validated by a core listener | **New** (GA-02); L0 shipped as a reference pack `sangam.ref.lmmh@CS-n` |
| languages | Language packs (labels for forms, outputs, notifications) | Extend form JSON from `{en, hi}` to `{<bcp47>: …}`; new override kind `LANG` |
| calendar | `BusinessCalendar` tenant holidays | **Exists** (port); add an e-Kuber calendar and per-location calendars |
| paymentRails, integrations, receipts | Connector bindings: which adapter backs `pfms.payment`, `ekuber.payment`, `gras.challan` and so on for this tenant, plus the capability class | **New** override kind `CONNECTOR_BINDING` over `ConnectorGateway` (adapters stay simulator-first, ADR-D-13) |
| publicAccounts, subStateEntities | Ledger account types over `LedgerStore`; entity tenants linked by referral | Extend `LedgerStore` with account-type policies; sub-state entity as a child tenant |
| rulebooks (citations) | Rule ids attached to DMN rules and objection codes; surfaced in the audit (R42) | Add `ruleRef` output column convention to DMN tables |
| everything | Versioned, effective-dated; a case binds to the profile version at start (like `packVersion`, R43 / K10) | Extend `PackStartListener` to bind `profileVersion` too (GP-19 semantics) |

### 6.4 Tenant model

- **Tenant = state.** This is the brief's requirement and the unit of the profile.
- **Sub-tenant = department** (and sub-state entity), which inherits the state profile. Today's AC-09 isolation per department is kept by deploying with `tenantId = "<STATE>.<DEPT>"`. Profile resolution walks `IN-RJ.PWD → IN-RJ → L1 → L0`.
- Operaton resolves call activities only inside one tenant, so a deployment must include the core and its dependencies for each department tenant. That is already how `deploy(packIds, tenantId)` works.
- **Cross-government work** goes through `referral` to another tenant. Examples are a multi-government treasury, ADC pass-through and inter-state pension. **This is a design decision for the owner:** confirm two-level tenancy versus a flat tenant per department with a `stateProfile` pointer.

### 6.5 Switching a state on (no code change)

1. **Pick the category preset** (L1) and, optionally, a donor rulebook (L2).
2. **Fill the profile** from the state's own orders: units and DDOs, segments, bill catalogue, DFP tables, rails and integrations.
3. **Validate** with a profile linter. It checks that the L0 tiers are untouched, that every bill type has HoA patterns that resolve in the CoA, that every chain role resolves to seats, that the DMN inputs are complete, that every language key is present, and that every enabled capability has a connector binding (or a simulator).
4. **Approve** through a Sangam process: maker (state IFMS cell) → checker (Finance Department) → approver, with the payload hash bound to the approval (VI.4). This also satisfies the change register in IFS-075.
5. **Deploy**: `PackDeployer.deploy(packs, "<STATE>[.<DEPT>]")` with the profile overrides. Run the pack acceptance scenarios against the profile on the simulator. Existing cases stay on their bound profile version (GP-19). Migrate them only through `PackMigrator` (GP-20).
6. **Phased cut-over per treasury or DDO** (`effectiveFrom` per unit; dual running per IFS-076).

---

## 7. Source register (accessed 2026-11-04; pass-2 sources are cited inline in §9, seen 2026-11-04/05)

The CAG treasury reviews are served from `cag.gov.in/uploads/...`, which redirects to CAG's CDN. URLs are given as published.

| Key | Source |
|---|---|
| C1 | CAG, Structure of Accounts: https://www.cag.gov.in/uploads/media/Structure-of-Accounts-20200604131646.pdf |
| C2 | CGA LMMH correction slips: https://cga.gov.in/Book/CorrectionSlip/Published/List-of-Major-and-Minor-Heads-of-Account-of-Union-and-States-LMMH.aspx |
| C3 | DoE SNA OM 23-03-2021: https://doe.gov.in/files/circulars_document/23_03_2021.pdf |
| C4 | DoE SNA-SPARSH letter 13-07-2023: https://doe.gov.in/files/public-finance-state-cna-sna-document/Letter_Dated_13_07_202_To_ALL_Chief_Secretaries_Principal_Secretaries.pdf |
| C5 | DoE OM 10-06-2025 (all CSS from 01-11-2025): https://doe.gov.in/files/circulars_document/OM_dated_10062025_to_all_secys.pdf |
| C6 | CGA OM 53 of 26-06-2025: https://cga.nic.in//writereaddata/file/GIFMISSPARSH53dt26062025.pdf ; FAQ OM 115: https://cga.nic.in//writereaddata/file/OMNo115dt25092025.pdf |
| C7 | DoE OM 17-12-2024 (NE onboarding): https://doe.gov.in/files/circulars_document/OM_5.pdf |
| C8 | GIFMIS PFMS models: https://gifmis.cga.gov.in/SitePages/about-Verticals-RollOut.aspx |
| C9 | RBI agency-bank MD: https://www.rbi.org.in/Scripts/BS_ViewMasDirections.aspx?id=12090 ; e-Kuber: https://www.rbi.org.in/Scripts/BS_ViewMasDirections.aspx?id=12541 |
| C10 | GFR 2017 (to 31-07-2025): https://doe.gov.in/files/circulars_document/UpdatedGFR31July2025_0.pdf ; DFPR 2024: https://doe.gov.in/files/circulars_document/Delegation_of_Financial_Powers_Rules_2024_Booklet.pdf ; Goods Manual 2024: https://doe.gov.in/files/circulars_document/Manual_Goods_2024.pdf |
| C11 | NITI CSS sharing: https://www.niti.gov.in/sites/default/files/2019-08/Final%20Report%20of%20the%20Sub-Group%20submitter%20to%20PM.pdf |
| RJ1–4 | RJ FD deck (CAG conf. 19-09-2025): https://cag.gov.in/uploads/media/Rajasthan-SNA-SPARSH-CAG-19-09-1-068ceb1aa563593-38246907.pdf ; CAG RJ treasury review 2024-25: https://cag.gov.in/uploads/ae_state_accounts/Final-Annual-Review-PDF-06870d0bed06534-66656426.pdf ; NIC: https://informatics.nic.in/files/websites/july-2025/ifms-rajasthan.php ; FD rules: https://finance.rajasthan.gov.in/docs/rules/gfar/GFR-I.pdf , https://sppp.rajasthan.gov.in/actrulesprocedures.php , https://finance.rajasthan.gov.in/aspxfiles/budgetobjectcodes.aspx |
| GJ1–3 | CAG GJ review 2025-26: https://cag.gov.in/uploads/ae_state_accounts/Treasury-Review-2025-26-06a4de71d99de95-22145555.pdf ; https://financedepartment.gujarat.gov.in/rules.html ; https://gil.gujarat.gov.in/tendercms/TenderDocs/201731414435508.pdf |
| MH1–3 | CAG MH review 2022-23: https://cag.gov.in/uploads/media/Final-Annual-review-2022-23-066bb056ed454e3-48312028.pdf ; https://mahakosh.maharashtra.gov.in/rulesManuals.php ; https://billportal.mahakosh.gov.in/ |
| GA1 | https://www.goa.gov.in/department/accounts/ |
| MP1 | CAG MP review 2024-25 (Hindi): https://cag.gov.in/uploads/ae_state_accounts/Treasury-Review-for-the-year-2024-25-069537c4e860fe1-54402331.pdf |
| CT1–2 | https://ekoshonline.cg.gov.in/ ; https://finance.cg.gov.in/Rules_acts.aspx |
| UP1–3 | CAG UP report 2025 Ch-II: https://cag.gov.in/uploads/download_audit_report/2025/(E)-Chapter-II-06a7587406d06c6.32378909.pdf ; https://budget.up.nic.in/Fin_H_Book/volume5/part1/index.html ; https://msme.up.gov.in/doc/Policies/ProcurementMannualGOUP1.pdf |
| UK1–2 | https://ifms.uk.gov.in/IFMSaboutUs.aspx ; https://ekosh.uk.gov.in/document-category/rules-regulations |
| BR1–2 | CAG BR review 2019-20: https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-2019-20-064b50a3e661067-66724113.pdf ; BR SNA circular (Jan 2026) on betastate.bihar.gov.in |
| JH1 | CAG Report 1 of 2025 (IFMS JH): https://cag.gov.in/uploads/download_audit_report/2022/Report-No.-1-of-2025-IFMS-in-JHR_English-(08-03-2025)-068ac55c7bf0127.94924518.pdf ; https://jkuber.jharkhand.gov.in/ |
| PB1–3 | https://ifms.punjab.gov.in/ ; CAG PB reviews 2021-22, 2022-23: https://cag.gov.in/uploads/ae_state_accounts/Annual-review-2021-22-065b74be9940c23-43727362.pdf , https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-final-pk-065b201e016d802-53150340.pdf ; NIC Informatics Apr 2021 |
| HR1–2 | CAG HR reviews 2024-25, 2023-24: https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-2024-25-06979d0a1f1d3a6-12990500.pdf , https://cag.gov.in/uploads/ae_state_accounts/Final-File-Annual-Review-2023-24-067c5557fe09938-44840245.pdf |
| HP1–2 | https://himkosh.nic.in/ ; CAG HP review 2024-25: https://cag.gov.in/uploads/ae_state_accounts/Treasury-Inspection-Annual-Review-2024-25-Final-068cbde5fdde158-92801357.pdf |
| KA1–3 | AG KA review 2023-24: https://cag.gov.in/uploads/media/ART-2023-24-067ed1c123482e6-09555781.pdf ; KA FD deck: https://cag.gov.in/uploads/media/Karnataka-Second-Conference-of-State-Finance-Secretaries-19092025-068ceb1f0363c91-64620654.pdf ; KTPP Act: https://www.indiacode.nic.in/bitstream/123456789/7000/1/29_of_2000%28e%29.pdf |
| TN1–3 | TN FD deck: https://cag.gov.in/uploads/media/Tamil-Nadu-Major-IT-Initiatives-068ceb202706392-89128310.pdf ; https://karuvoolam.tn.gov.in/web/tnta/about-us ; TNTT Rules: https://www.tnbudget.tn.gov.in/tnweb_files/Rules/TNTIT%20Rules.pdf |
| KL1–4 | AG KL review 2023-24: https://cag.gov.in/uploads/media/Annual-Review-2023-24-approved-by-AG-for-circulation-066b5f50082cb10-26407049.pdf ; https://ifms.kerala.gov.in/about ; https://portal.treasury.kerala.gov.in/treasury_portal/truploads/services/bills.pdf ; SPM: https://www.spd.kerala.gov.in/docs/pdf2019/Stores%20Purchase%20Manual%20FINAL.pdf |
| AP1–4 | https://cfms.ap.gov.in/about.html ; AG AP review 2020-21: https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-Report-2020-21-061b9d01d7e96c2-03475112.pdf ; draft TR 2022: https://cfms.ap.gov.in/gos/AP_Treasury_Rules_draft.pdf ; BE 2026-27 GO: https://apfinance.gov.in/images/BE_2026-27.pdf |
| TG1–2 | AG TG review 2021-22: https://cag.gov.in/uploads/ae_state_accounts/ARR2021-22-063f4a0a821ea40-14863316.pdf ; TG FD deck: https://cag.gov.in/uploads/media/Telangana-PPT-onIFMIS-068ceb20ab627e1-31127828.pdf |
| WB1–3 | https://finance.wb.gov.in/Fin_New/Pages/Dir_TA.aspx ; WB FD deck: https://cag.gov.in/uploads/media/West-Bengal-Pioneering-Excellence-in-Public-Finance-Management-068ceb1b7526356-45804343.pdf ; https://home.wb.gov.in/gorkhaland |
| OD1–3 | IFMS 3.0 RFP: https://ct.odisha.gov.in/sites/default/files/2024-03/RFP-3395_15.03.2024_IFMS-3.0.pdf ; https://finance.odisha.gov.in/en/publication/act-and-rules/rules ; https://finance.odisha.gov.in/notification/dfpr |
| AS1–3 | https://fin.assam.gov.in/assamfinance/welcome ; DFPR 1999 2nd edn: https://finance.assam.gov.in/sites/default/files/The%20Delegation%20of%20Financial%20Power%20Rules%2C%201999%20With%20up-to%20date%20Amendments%20Second%20Edition%2C%202016.pdf ; https://dimahasao.assam.gov.in/departments/treasury |
| SK1–2 | CAG SK review 2019-21: https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-of-Treasuries-Approved-06189faf4b5ec31-37635011.pdf ; https://cag.gov.in/ae/sikkim/en/page-ae-sikkim-about-treasury-function-hi |
| AR1–2 | CAG AR review 2022-23: https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-on-the-working-of-Treasuries-of-Arunahcal-Pradesh-for-the-year-2022-23-066c4439f7e5cf8-48883523.pdf ; C7 |
| MN1–3 | https://informatics.nic.in/files/flipbooks/april23/files/basic-html/page6.html ; https://manipur.nic.in/?p=4940 ; https://www.manipurgovtpress.nic.in/media/gazzete/EOG_156_25-07-2012.pdf |
| ML1–2 | CAG ML review 2024-25: https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-on-working-of-Treasuries-2024-25-pdf-06a1fbbe24b9673-57242665.pdf ; https://southwestkhasihills.gov.in/?p=24002 |
| MZ1–2 | https://informatics.nic.in/uploads/pdfs/b0dbf947_mizoram.pdf ; https://cag.gov.in/ae/mizoram/en/page-ae-mizoram-account-vlc |
| NL1–2 | https://treasuries.nagaland.gov.in/ (RTI manual 2024-25) ; https://cag.gov.in/ae/nagaland/en/page-ae-nagaland-treasury-inspection |
| TR1–2 | CAG TR AR 2023-24 Ch.VI: https://d1jmc0u14z2tmp.cloudfront.net/uploads/download_audit_report/2025/8--Chapter-6-069ccc56ee40cd2.11725036.pdf ; https://finance.tripura.gov.in/about-us |
| DL1–2 | https://coa.delhi.gov.in/pao/about-us ; https://coa.delhi.gov.in/hi/node/42718 |
| PY1–2 | https://ifmis.py.gov.in/ ; https://py.gov.in/sites/default/files/citizencharter-accountstreasuries.pdf |
| JK1–3 | NIC J&K project profile: https://cdnbbsr.s3waas.gov.in/s3819e3d6c1381eac87c17617e5165f38c/uploads/2021/09/2021092297.pdf ; https://www.jakfinance.nic.in/IMP_REFERENCE/BOOK%20OF%20FINANCIAL%20POWERS/Book%20of%20Financial%20Powers%20-%20Part1.pdf ; https://rgp.jk.gov.in/pdf/SRO/SRO-2017/68.pdf |
| LA1–2 | https://ladakh.gov.in/finance-department-2/ ; https://ladakh.gov.in/?p=51180 |
| CH1 | CAG PB/UT review 2021-22 (as PB2) ; CAG Report 3 of 2018 (UTs): https://cag.gov.in/uploads/download_audit_report/2018/Chapter_1_Introduction_of_Report_No_3_of_2018_-_Union_Territories_without_Legislatures_Compliance_Audit_Observations_Union_Government_0.pdf |
| DH1 | Union Notes on Demands 2026-27, Demand 54: https://www.indiabudget.gov.in/doc/eb/sbe54.pdf |
| LD1 | https://lakshadweep.gov.in/departments-new/pay-and-accounts-office/ ; sbe56 |
| AN1 | https://andamannicobar.gov.in/pdf/CPAO_Suo%20Motu.pdf ; sbe52 |
| UT demands | https://www.indiabudget.gov.in/doc/eb/sbe52.pdf … sbe59.pdf ; MHA Pr.CCA: https://www.mha.gov.in/principal-chief-controller-accounts-org |

---

## 8. Open items and [U] follow-ups (none block the design)

Status after pass 2 (2026-11-05). Closed items are recorded in §1, §4 and §9.

1. **Thin profiles:** closed as profiles. GA, AR, MZ and DH now have at least 6 of 8 fields. These sub-items are still [U]:
   - Goa: Treasury Rules; widths below the sub-head; works code;
   - Arunachal: state DFPR; DDO count;
   - Mizoram: budget-document language; sub-treasury and DDO counts; works code;
   - DNH&DD: PFMS e-bill status; e-Kuber; the merged UT's language notification.
2. **Budget-code digit structures:** 27 of 36 now have a segment definition (§9.2). Still [U]:
   - no structure: GJ (only `8449-00-120-11`), WB (the 17-digit claim), TG, OD, ML, JK, LA;
   - partial: GA (to sub-head), AR (P);
   - not re-confirmed: AP's 7-tier example.
3. **Language of finance documents:** the statutory language is V or P for all 36 (§9.1). Still [U]:
   - budget-document language for 27 entities;
   - any rule on the language of treasury forms (none found anywhere);
   - the 53rd CLM report (2025) PDF;
   - Act names marked "title only" in §9.1.
4. **Procurement and works codes:** a procurement rule is named for 35 of 36 (§9.3). Still [U]:
   - Telangana procurement rules;
   - works manuals for GJ, HP, CT, ML, MN, MZ, TR, AR, LD;
   - Bihar's 09-12-2024 notification text;
   - whether the Jharkhand Financial Rules 2025 are notified;
   - the WB GeM order number;
   - the current RJ PWF&AR and OPWD editions.
5. **Tribal / Fifth Schedule (TSP) budgeting:** minor head 796 "Tribal Area Sub-Plan" is confirmed as a standard minor head (P). Per-state TSP demand and ITDA lines for RJ, GJ, MH, MP, CT and OD are still [U]. Sixth Schedule fund rule texts are now confirmed (§9.4): BTC Fund Rules 2012 (V), MADC 1996 → Mizoram ADC Fund Rules 2010 (V), TTAADC (Budget & Account) Rules 1982 (V).
6. **Central items:**
   - closed: LMMH tier ownership (§2); the CNA master circular of 21-05-2024 (see the central doc); the DFPR 2024 amendment of 09-06-2026 (object heads); Works Manual 2nd edn 2025; e-Kuber (all states except Sikkim);
   - still [U]: CPWD Works Manual after 2022; a named per-state e-Kuber list; IGAS 1 notification text.
7. **Sites retried:**
   - treasury.telangana.gov.in: works when the certificate check is skipped; the TG SNA-SPARSH G.O.s are now V;
   - nifims.nagaland.gov.in and jkdat.nic.in: failed again;
   - also refused or unreachable in pass 2: goabudget.gov.in, accountsgoa.gov.in, finance.mp.gov.in, wbfin.wb.gov.in, saiindia.gov.in, gasab.gov.in; indiacode.nic.in intermittently (403 / timeout).
8. **Owner decision:** two-level tenancy (state → department) versus a flat department tenant with a state-profile pointer (§6.4). Unchanged.

Interface specifications for individual states remain ON-REQUEST, as RQ-3 (IX.7c). Odisha's JIT API is the one public reference. Under ADR-D-13 nothing here depends on them: every integration in §6 is a connector binding with a simulator.

---

## 9. Pass 2 findings (official-source follow-up, seen 2026-11-04/05)

Same legend: V = read on an official page or document; P = official search excerpt, partial, or an official document identified but not opened; [U] = not established. The changed values are also listed for the coding workstream in `spike/results/IFMS-RESEARCH-DELTA.md`.

### 9.1 Language of official finance documents (all 36)

Base source **CLM52**: Commissioner for Linguistic Minorities, 52nd Report (29-03-2016, covering July 2014 to June 2015), section "Official Language of the State/UT": https://minorityaffairs.gov.in/WriteReadData/RTF1984/9607554317.pdf. It names the language but rarely the Act. An Act name marked "(title only)" was not checked on indiacode. "Budget docs" means the budget speech, AFS or Demands seen on the official finance site for 2025-26 or 2026-27.

| Code | Statutory official language(s) | Budget docs seen in | St | Extra source |
|---|---|---|---|---|
| RJ | Hindi; English additional (CLM52 §8.2); OL Act 1956 (title only) | [U] | P | — |
| GJ | Gujarati (CLM52 §28.2); OL Act 1960 (title only) | [U] (official page empty) | P | — |
| MH | Marathi (CLM52 §30.2); OL Act 1964 (title only) | Marathi budget speech 2026-27 (English [U]) | P | finance.maharashtra.gov.in |
| GA | Konkani (Devanagari); Marathi permitted; English continues (OL Act 1987 s.3–4) | English (P) | V | https://dol.goa.gov.in/wp-content/uploads/2025/03/Official-Language-Act-1987.pdf |
| MP | Hindi (CLM52 §12.2) | [U] (site refused) | P | — |
| CT | Hindi (CLM52 §10.2); Chhattisgarhi 2007 amendment [U] | budget circulars in Hindi | P | finance.cg.gov.in |
| UP | Hindi; Urdu additional (CLM52 §14.3; 1989 amendment) | Hindi speech and Hindi demand volumes 2026-27; English speech label | V | https://budget.up.nic.in/ |
| UK | Hindi (CLM52 §13.2); Sanskrit second language [U] | [U] | P | — |
| BR | Hindi; Urdu additional (CLM52 §9.2) | [U] (site refused) | P | — |
| JH | Hindi (CLM52 §11.2); second state languages by notification [U] | 2026-27 speech PDF exists; language [U] | P | finance.jharkhand.gov.in |
| PB | Punjabi in Gurmukhi (CLM52 §7.2) | Demands Vol-IV 2026-27 bilingual Gurmukhi / English | P | https://www.punjabassembly.gov.in/images/docs/AssemblyFiles/Budget/2_7.pdf |
| HR | Hindi; English additional (CLM52 §4.2); Punjabi second language [U] | [U] | P | — |
| HP | Hindi (CLM52 §5.2); HP OL Act 1975 (official copy); Sanskrit 2019 [U] | [U] | P | himachal.nic.in (OL Act 1975 PDF) |
| KA | Kannada (CLM52 §29.2) | budget circulars in Kannada | P | finance.karnataka.gov.in |
| TN | Tamil; English additional (CLM52 §36.2) | [U] | P | — |
| KL | Malayalam (CLM52 §33.2) | Malayalam + English speeches 2023–2026 | V | https://www.budget.kerala.gov.in/portal/budget_speech |
| AP | Telugu; Urdu additional (CLM52 §32.2); statewide Urdu 2022 [U] | [U] | P | — |
| TG | Telugu + Urdu (CLM52 §32.2, undivided AP); 2017 amendment [U] | [U] (official copy not reachable) | P | — |
| WB | Bengali; Bengali + Nepali in the Darjeeling and Kurseong subdivisions (CLM52 §24.2); 2012 and 2018 additions [U] | budget publications 2026 exist; language [U] | P | finance.wb.gov.in |
| OD | Odia (CLM52 §21.2) | [U] | P | — |
| AS | Assamese (CLM52 §16.2); Bengali in Barak Valley; Bodo 2020 [U] | [U] | P | indiacode (1985 amendment) |
| SK | English (CLM52 §22.2); Nepali, Bhutia, Lepcha and later additions (Sikkim OL Act 1977) | [U] | V | https://sikkim.gov.in/uploads/Gazette/1977_THE_SIKKIM_OFFICIAL_LANGUAGES_ACT_20221029.pdf |
| AR | English (CLM52 §15.3; no notification issued) | [U] | P | — |
| MN | Manipuri; English (CLM52 §17.3) | [U] | P | — |
| ML | English (CLM52 §18.2); Meghalaya Official Languages Ordinance 2026 (27-04-2026) adds Khasi and Garo; commencement pending | 2026-27 speech exists; language [U] | P | https://meglaw.gov.in/pdf/LL_(B)_36_2005_139_Dated_Shillong_the_27th_April_2026.pdf (search result only) |
| MZ | Mizo, English, Hindi (CLM52 §19.2); Mizoram OL Act 1974 | [U] | P | — |
| NL | English (CLM52 §20.2) | [U] | P | — |
| TR | Bengali, English, Kokborok (CLM52 §23.2) | Bengali + English speech 2026-27; AFS and expenditure volumes in English | V | https://finance.tripura.gov.in/ |
| DL | Hindi + English; Punjabi and Urdu as second official languages (Delhi OL Act 2000, CLM52 §3.3) | Hindi + English speech 2026-27 | V | https://finance.delhi.gov.in/finance/budget-speech-2026-27-hindi |
| PY | Tamil, Telugu, Malayalam, English (CLM52 §35.2) | English + Tamil speeches 2025 | V | https://finance.py.gov.in/sites/default/files/CMSpeech_Tamil_2025.pdf |
| JK | Kashmiri, Dogri, Urdu, Hindi, English (J&K Official Languages Act 2020, Act 23 of 2020, from 29-09-2020) | [U] | V (statute) | https://law.jk.gov.in/jklawData/StaActs/Official%20Languages%20Act,%202020.pdf |
| LA | English, Hindi, Urdu, Bhoti, Purgi (Ladakh Official Languages Regulation 2025, Reg. 3 of 2025); commencement notification [U] | Union budget (English / Hindi) | V (statute) | https://egazette.gov.in/WriteReadData/2025/263538.pdf |
| CH | English; no OL Act (CLM52 §2.3) | Union budget (English / Hindi) | P | — |
| DH | Pre-merger: DNH Hindi + Gujarati (CLM52 §25.2); D&D Gujarati under the 1987 Act; merged-UT notification [U] | Union budget (English / Hindi) | P | — |
| LD | English (CLM52 §34.2) | Union budget (English / Hindi) | P | — |
| AN | Hindi + English (CLM52 §31.3) | Union budget (English / Hindi) | P | — |

**For the profile model (IFS-072):**
- Model each language as an entry with a scope (statewide, or listed districts / regions), a script, and an `effectiveFrom` date. Recent changes (ML 2026, LA 2025, J&K 2020) are not all in force yet.
- Keep the *document language* separate from the *statutory language*. Accounts and audit outputs (CAG accounts, AFS volumes) are usually English even where the statute names only a regional language. Speeches and citizen documents are usually the regional language + English.
- No state rule on the language of treasury forms was found. Leave it as a profile parameter, defaulting to the statutory language + English.

### 9.2 Budget-code (head of account) structure below the minor head

LMMH General Directions (https://cga.gov.in/DownloadPDF.aspx?filenameid=1979, to CS 1127):
- Para 1.1: sub-heads are set "to suit the local requirement of each Government".
- Para 3.1 Note 2: sub-head = scheme; detailed head = sub-scheme or activity; object head = object of expenditure.
- Para 8: the 2-2-2 widths are stated only for the Centre. States and UTs with a legislature may open standard minor heads without a correction slip.

**So the segment definition must be per state. It is not a uniform 2-2-2.**

| Code | Segments (widths) | Digits | Example (as printed) | St | Source |
|---|---|---|---|---|---|
| RJ | Demand2-MH4-SMH2-MinH3-(SH2)-[GH2]-OH2 | 15 / 17 | `4202 01 202 (01) [90] 17` | V | https://finance.rajasthan.gov.in/docs/budget/budgetmanual/budgetmanual_vol1_270912.pdf (§3.11) |
| GJ | only `8449-00-120-11` known | — | — | [U] | budget page has no document links |
| MH | Demand letters + MH4-SMH2-MinH3-(SH2)(DH2)-OH2; separate 8-digit BEAMS code | 15 | `2011 02 101 (00)(01)`, BEAMS `20110011`, object `01` | V | https://mls.org.in/PDF2026/budjet/ZC-Maharashtra-Legislature-Secretariat.pdf |
| GA | MH4-SMH2-MinH3-SH2; lower widths [U] | 11+ | `2225-03-277-09` | V (partial) | CAG Report 2 of 2018 (Goa State Finances) |
| MP | probably Demand2-MH4-SMH2-MinH3-Scheme4-OH2-DOH3 (by analogy with CT; fits `22-008`) | ~20 | — | P | MP hosts refused |
| CT | Demand2-MH4-[SMH2]-{MinH3}-(Scheme4)-tag4-#OH2-DOH3 | ~20 | `2012 [03] {090} (4330) 0112 #01 001` | P | https://finance.cg.gov.in/budget_doc/2026-2027/Book/01/B-01.pdf |
| UP | MH4-SMH2-MinH3-SH2-DH2-OH2 | 15 | `2058-103-03` (to sub-head) | P | CAG UP Appropriation Accounts 2024-25 |
| UK | MH4-SMH2-MinH3-SH2-DH2 + object 2 | 15 | `2052-00-090-19-00`, object `42` | V | https://cdnbbsr.s3waas.gov.in/s3c65d7bd70fe3e5e3a2f3de681edc193d/uploads/2025/01/20250130782274141.pdf |
| BR | DD2-MH4-SMH2-MinH3-SH4-DH2-SDH2 + V/C | 19 + flag | `12-2054-00-097-0001-01-01 V` | V | https://bica.bihar.gov.in/Doc/Study%20Matrials/Budget.pdf |
| JH | DD2-letter-MH4-SMH2-MinH3-SH2-DH2-ObjGroup2-OH2 | 20 + letter | `12-S-2052-00-090-08-00-01-01` | V | https://finance.jharkhand.gov.in/pdf/Budget_2026_27/Finance_Department.pdf |
| PB | MH4-SMH2-MinH3 + SH2.DH2.OH2 (DH 99 = none) | 15 | `4401 … 103`, `03.99.53` | V | https://www.punjabassembly.gov.in/images/docs/AssemblyFiles/Budget/2_7.pdf |
| HR | [P2-DD2-MH4-SMH2-MinH3-SH2-DH2] + OH2 + flag | 19 | `[P-01-01-2011-02-101-99-51]`, `01` | V | https://cdnbbsr.s3waas.gov.in/s386e78499eeb33fb9cac16b7555b50767/uploads/2024/02/20240223431839687.pdf |
| HP | MH4-SMH2-MinH3-SH2-Scheme4 + SOE2 | 11 + 4 + 2 | `2202-02-796-01-S00N`, SOE `01` | P | https://tribal.hp.gov.in/pdf/TADP(ND)2022-23.pdf |
| KA | MH4-SMH2-MinH3-SH1-DH2-OH3 | 15 | `2055-00-113-0-03-240` | V | https://cag.gov.in/uploads/state_accounts_report/KAR_Appropriation_Accounts_2013_14.pdf |
| TN | MH4-SMH2-MinH3-SH2 (letters)-Group1-DH2-SDH2 (group 3 rev / 4 cap / 8 PA; SDH 01–49 voted, 51–99 charged) | 16 | `2011 02 101 AA 30101` | V | https://financedept.tn.gov.in/en/my-documents/2020/07/d01.pdf |
| KL | MH4-SMH2-MinH3-SH2-SSH2-DH2-OH2 (+ P/NP, V/C) | 17 | `2056-00-001-99-00-02-05` | V | https://niyamasabha.nic.in/images/15-4-session/Appropriation%20Accts%202020-21%20(Eng).pdf |
| AP | MH4-SMH2-MinH3-GSH2-SH2-DH3-SDH3 (reported) | 19? | to sub-head only | P | CAG AP Appropriation Accounts 2023-24 |
| TG | [U] | — | — | [U] | no budget PDFs reachable |
| WB | [U] (17-digit claim unconfirmed) | — | — | [U] | wbfin.wb.gov.in did not resolve |
| OD | [U] (6 segments + object from the RFP, pass 1) | — | — | [U] | budget e-book download timed out |
| AS | MH4-SMH2-MinH3-SH4-DH3-OH2-SDH2 | 20 | `8443-00-108-1994-000-00-00` | P | finance.assam.gov.in (search excerpt; PDF now 404) |
| SK | MH4-SMH2-MinH3-SH2-DH2-OH2 (DH often = district) | 15 | `2401 00 001 01 44`, `01` | V | https://cag.gov.in/uploads/subsite_whats_new/ANNUAL-APP-2023-2024-0669a44a0757767-36164319.pdf |
| AR | MH4-SMH2-MinH3-SH2-object3 | 14 | `4217-01-800-03-051` | P | https://cag.gov.in/webroot/uploads/download_audit_report/2024/State-Finance-Report-2023-24-Arunachal-069c280ae5f3392.54463922.pdf |
| MN | MH4-SMH2-MinH3-SH2-DH2-OH2 | 15 | `2225 02 001 01 00 01` | V | https://manipur.gov.in/wp-content/uploads/2026/03/DMD-14_2026-27.pdf |
| ML | [U] | — | — | [U] | only the financial statement reachable |
| MZ | MH4-SMH2-MinH3-(SH2)-DH2-(OH2) | 15 | `2011-02-101 (01) 01 (01)` | V | https://finance.mizoram.gov.in/uploads/attachments/2026/02/6d0a0368f7a584144478668658c6b405/demand-for-grants-2026-27.pdf |
| NL | MH4-SMH2-MinH3-SH2-SSH2-DH2 (last tier = object) | 15 | `2011 02 101 01`, `01` | V | https://finance.nagaland.gov.in/Content/Files/DE13AEAD-5ABF-44DE-B3CB-AE7B02DBF84F.pdf |
| TR | MH4-SMH2-MinH3-SH2-DH2-OH2 | 15 | `2406 01 001 98 32 02` | V | https://finance.tripura.gov.in/sites/default/files/Expenditure-Budget-Volume-2-Part-II.pdf |
| DL | Union style MH4-SMH2-MinH3-SH2-DH2-OH2 | 15 | `2052 00 090 60 00 01` | V | https://finance.delhi.gov.in/sites/default/files/Finance/generic_multiple_files/d-11_1.pdf |
| PY | Union-style object 2 (BEAMS); full code [U] | ? | object `28` | P | https://finance.py.gov.in/sites/default/files/FD%20Compendium%2030.09.2022.pdf |
| JK, LA | [U] | — | — | [U] | not reached |
| CH, DH, LD, AN | Union layout MH4-SMH2-MinH3-SH2-DH2-OH2 | 15 | none UT-specific | P | LMMH para 8; https://www.indiabudget.gov.in/doc/eb/dg53.pdf |

**Object-head lists change by year.** The Union list is replaced from FY 2027-28 (DoE OM of 09-06-2026: 61 heads in 8 classes). UP's budget site says 63 object heads from 2027-28 (https://budget.up.nic.in/act/object_chnage.pdf, P). Press reports (not official) say MH and MP change too. Object-head masters must therefore be versioned by financial year.

### 9.3 Procurement and works codes (all 36)

| Code | Procurement rule | Works code / manual | e-proc / GeM | St | Source |
|---|---|---|---|---|---|
| RJ | RTPP Act 2012 / Rules 2013 | PWF&AR Parts I–III (edition [U]) | eproc.rajasthan.gov.in; GeM MoU 28-09-2020 | P | gem.gov.in/mou |
| GJ | Gujarat Purchase Policy 2024 (from 01-04-2024; replaces 2016 policy and 2002 manual) | [U] | nprocure; e-tender above ₹1 lakh | [U] (secondary sources only) | — |
| MH | Manual of Office Procedures for Procurement (Industries GR, from 01-12-2016) | Maharashtra PW Manual and PW Account Code (edition [U]) | e-tender above ₹3 lakh; GeM GR 24-08-2017 | P | https://cdnbbsr.s3waas.gov.in/ (Industries GR copy) |
| GA | central GFR 2017 (no state code found) | CPWD Works Manual adopted by Goa PWD (CAG 2010) | eprocure.goa.gov.in | P | cag.gov.in Goa Civil 2010 Ch. 3 |
| MP | MP Bhandar Kray tatha Seva Upaarjan Niyam 2015 | MP Works Department Manual (edition [U]) | mptenders.gov.in; GeM circular 13-04-2018 | P | mpforest.gov.in (Cord_39-65S.pdf) |
| CT | CG Bhandar Kray Niyam 2002, consolidated to 10-12-2025 (GeM rule 3) | [U] | eproc.cgstate.gov.in [U] | V (proc) | https://csidc.in/wp-content/uploads/2025/12/SPR11122025.pdf |
| UP | UP Procurement Manual (Goods) 2016; GO 26-11-2024 aligning with GFR 2017 + GeM GTC, GeM at least 25 % | FHB Vol VI (PWD) | e-tender since GO 12-05-2007 | P | CAG UP Report 4 of 2017; gem.gov.in/mou |
| UK | UK Procurement Rules 2017 (14-07-2017), amended 06-01-2021 | UK PWD GOs; FHB Vol VI inherited [U] | uktenders.gov.in | P | ekosh.uk.gov.in; pwd.uk.gov.in |
| BR | Bihar Financial Rules procurement chapter; amendments 20-08-2020, 16-07-2024, 09-12-2024 (Rules 124–131ZZB) [U: secondary for 2024] | Bihar PWD Code | eproc2.bihar.gov.in; GeM circulars 2017–18 | P | betastate.bihar.gov.in |
| JH | **Jharkhand Financial Rules 2025** (FD PDF 14-10-2025): Ch. 7 goods/services, r.165 GeM mandatory, r.174 e-procurement (notification [U]) | JFR 2025 Ch. 10; PW Account Code 1935 | jharkhandtenders.gov.in | V (document) | https://finance.jharkhand.gov.in/download//oth_updates/14102025_498.pdf |
| PB | Punjab Transparency in Public Procurement Act 2019 + Rules 2022 (services exempted under s.63, 2023) | Punjab PWD Code | sppp.punjab.gov.in; eproc.punjab.gov.in | P | https://www.indiacode.nic.in/handle/123456789/15114 |
| HR | Store purchase rules (HPPC); GeM notification 11-12-2018 | Haryana PWD Code (edition from 01-11-2009) | etenders.hry.nic.in (≥ ₹1 lakh) | P | S&D charter (s3waas) |
| HP | HP Financial Rules 2009 procurement chapter (amended 2020, Dec 2024) | [U] (CPWD likely) | hptenders.gov.in; GeM via HPFR r.94 | P | himachal.nic.in (HPFR amendment) |
| KA | KTPP Act 1999 / Rules 2000 (Act amended 29/2024; Second Amendment Rules 2024) | Karnataka PWD Code 2014 | kppp.karnataka.gov.in | P | indiacode 29_of_2000 |
| TN | TNTT Act 1998 / Rules 2000 (Act amended 5/2024, 29/2024) | TN PWD Code | tntenders.gov.in | P | financedept.tn.gov.in (Ex_No_86_2024) |
| KL | Stores Purchase Manual 2013 (Ch. 19 GeM 26-04-2019; amended 09-03-2021) | Kerala PWD Manual (2012 revised) | etenders.kerala.gov.in | P | gem.gov.in/mou |
| AP | GO Ms 129 of 24-09-2019 (e-procurement mandatory); GeM GOs 21/2017, 151/2019 | AP PWD Code / APDSS (edition [U]) | apeprocurement.gov.in | P | assets-bg.gem.gov.in (G.O.MS.No-151) |
| TG | [U] (AP rules inherited, presumed) | [U] | tender.telangana.gov.in | [U] | none found |
| WB | WB Financial Rules Vol I (purchase rules) | WB PWD Code + WBWD Manual (CAG 2025 audit criteria) | wbtenders.gov.in; **GeM mandatory from Sept 2026** | P / V (GeM) | newsonair.gov.in (24-09-2026); cag.gov.in 2025 Ch. 2 |
| OD | OGFR 2023, amended 04-06-2025 and 16-03-2026 (No. 6904) | OPWD Code Vols I–II (edition [U]) | tendersodisha.gov.in; e-procurement mandatory | V | https://finance.odisha.gov.in/en/notification/ogfr |
| AS | Assam Public Procurement Act 2017 + Rules 2020 | Assam PWD Manual + AFR Ch. 7–8 | assamtenders.gov.in [U] | P | finance.assam.gov.in |
| SK | Sikkim Financial Rules 1979; DFP Rules 1977 (updated 2020) | Sikkim Public Works Code | e-procurement in use; GeM MoU Oct 2024 | P | hcs.gov.in (HC order) |
| AR | GFR 2017 r.149 adopted (11-10-2017) | [U] (CPWD likely) | arunachaltenders.gov.in | P | gem.gov.in/mou |
| MN | Manipur Public Procurement Policy 2022 | [U] | manipurtenders.gov.in [U] | P | eodbmanipur.mn.gov.in |
| ML | Procurement Rules for departments, societies and PSUs (20-07-2018) | [U] | [U] | P | gem.gov.in/mou |
| MZ | Mizoram Transparency in Public Procurement Act 2008 (amended 2019); Preferential Store Purchase Rules 1994 | [U] | [U] | P (Act text via PRS only) | — |
| NL | e-Procurement Policy (DIT&C); GeM letter 27-03-2019 | CPWD Account Code; Contractors Registration Rules 2025 | nagalandtenders.gov.in [U] | P | ditc.nagaland.gov.in |
| TR | Manual for Procurement of Goods 2018 (Finance Dept) | [U] | tripuratenders.gov.in [U] | V | https://finance.tripura.gov.in/sites/default/files/Manual-for-Procurement-of-Goods.pdf |
| DL | GFR 2017 + central goods manual | CPWD Works Manual | govtprocurement.delhi.gov.in [U] | P | vigilance.delhi.gov.in |
| PY | GFR 2017 + CPWD Manual (FD circular 24-10-2017) | CPWD Works Manual | GeM under GFR r.149 | P | finance.py.gov.in; pwd.py.gov.in |
| JK | GFR 2017 + central manuals (2020 LG order); e-tendering GO 117-F of 2021 | J&K PWD manuals + central Works Manual 2019 | jktenders.gov.in | P | jakfinance.nic.in (Compendium 2020) |
| LA | GFR 2017 + Works Manual 2019 | central works manual | GeM nodal officer | P | ladakh.gov.in |
| CH | GFR 2017 (assumed); GeM cell 14-08-2020 | [U] | etenders.chd.nic.in [U] | P | gem.gov.in/mou |
| DH | GFR 2017 (assumed) | CPWD DSR with UT cost index [U] | NIC e-procurement | P | negd.gov.in |
| LD | GFR 2017 (assumed) | [U] | [U] | [U] | nothing official found |
| AN | GFR 2017 | CPWD Manual (CAG Report 8 of 2017) | eprocure.andamannicobar.gov.in | P | cag.gov.in |

**Pattern.** Three families of procurement rule pack:
- (a) a state **transparency / procurement Act**: RJ, KA, TN, PB, AS, MZ;
- (b) a **financial-rules chapter or a store-purchase rulebook**: BR, JH, OD, HP, SK, WB, MP, CT, KL, GJ, MH, UP, UK, TR, ML, MN;
- (c) **central GFR 2017 + CPWD**: the UTs, GA, AR, NL.

GeM is now mandatory or near-mandatory in most states. IFS-031 and IFS-068 stand; the pack choice is profile data.

### 9.4 Other state items closed in pass 2

| Item | Value | St | Source |
|---|---|---|---|
| e-Kuber, all states | All States integrated with RBI for e-payments except Sikkim; 13 States route more than 90 % of payments via RBI (incl. CT, MN, ML) | V | https://cag.gov.in/uploads/media/RBI-PPT-on-State-Finance-Secretaries-Conference-19-9-25-068ceb1d5e44388-11348057.pdf |
| TG SNA-SPARSH | G.O.Ms.76 (ARU in DTA) and 77 (SOP), both 22-05-2024; memo 25-05-2024 (4 schemes 2023-24, 22 more from 01-06-2024) | V | https://treasury.telangana.gov.in/pfms_docs/2024FIN_MS76.PDF ; …/22052024FIN_MS77.PDF ; …/Instructions_SPARSH_23.05.24.pdf |
| WB Cyber Treasury-II | code CAH, notification DT/O/02/C-2025/893 of 11-09-2025 | V | https://finance.wb.gov.in/writereaddata/DTA-893.pdf |
| AP treasuries | 26 DT + AP State Capital Region Treasury + 196 divisional / sub-treasuries | V | https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-Report-2023-24-0673f0c5a975553-62062027.pdf |
| AP CSS / CBRO | 19 schemes on SNA-SPARSH (Sept 2025); CBRO guideline 2026-27 | P | https://cag.gov.in/uploads/media/Andhra-Pradesh-CAG-Con-CSS-19Sep-068ceb18728c3f7-05122622.pdf ; https://apfinance.gov.in/images/BE_2026-27_CBRO_Guidelines.pdf |
| CT treasuries | 34 treasuries + 157 PW divisions render accounts | V | https://cag.gov.in/uploads/state_accounts_report/account-report-Accounts-at-a-Glance-English-067651e83112079-16450540.pdf |
| JH directorate | Director (T&A) post not functioning; Finance Department does the work | V | https://cag.gov.in/ae/jharkhand/en/page-ae-jharkhand-functions-try |
| TN codes | Treasury Code Vol I 7th edn (to 30-04-2013); Financial Code rewrite G.O.Ms.371 / Gazette 413 of 18-12-2024 | V | https://financedept.tn.gov.in/ta/my-documents/2020/07/Tamilnadu-Treasury-code-Vol.I.pdf ; https://www.stationeryprinting.tn.gov.in/extraordinary/2024/413_Ex_II_2_2024.pdf |
| SK Art. 371F(k) | pre-merger laws continue until amended or repealed | V | Constitution as on 01-05-2024 (Legislative Dept, s3waas) |
| BTC Fund Rules 2012 | notified Sept 2012 under Sixth Schedule para 7(2) | V | https://cag.gov.in/uploads/download_audit_report/2020/BTC-Report-2019-20-(09-01-2024)-068b044c0a230c8.68789562.pdf |
| MADC Fund Rules | 1996 (from 26-11-1996), replaced by Mizoram ADC Fund Rules 2010 | V | https://cag.gov.in/webroot/uploads/download_audit_report/2016/MADC-2015-16-0697b46ce741f85.54191326.pdf ; https://cag.gov.in/webroot/uploads/download_audit_report/2015/CADC-2009-15-(signed-copy)-069830c92d12674.08335812.pdf |
| TTAADC rules | TTAADC (Budget & Account) Rules 1982, under ss.41 and 52 of the TTAADC Act 1979 | V | https://www.thc.nic.in/Tripura%20State%20Lagislation%20Rules/ |
| PY rules | GFR 2017, DFPR, R&P Rules 1983, CTR, Accounting Rules for Treasuries 1992 | V | https://dat.py.gov.in/sites/default/files/page1.pdf ; https://dpar.py.gov.in/RTI/5.pdf |
| CH service rules | UT of Chandigarh Employees (Conditions of Service) Rules 2022 (MHA 29-03-2022; central rules from 01-04-2022) | V | https://www.gmch.gov.in/sites/default/files/documents/a5_0.pdf |
| Goa profile | Accounts-Online, 2 TOs + 10 STs, ~550 DDOs; DFPR 2008; OL Act 1987 | V / P | https://cdnbbsr.s3waas.gov.in/s3aebf7782a3d445f43cf30ee2c0d84dee/uploads/2021/09/2021100189.pdf ; https://cag.gov.in/uploads/media/GOA-004242-06a4740ce0910f7-64952530.pdf ; https://d1jmc0u14z2tmp.cloudfront.net/uploads/download_audit_report/2025/Chapter-I-06895e6a187b0d1.96146279.pdf |
| DNH&DD profile | two directorates (2025), e-Lekha, PAO codes; DFPR / GFR / CTR / FR; Demand 54 BE 2026-27 ₹2,835.20 cr gross | V | https://cdnbbsr.s3waas.gov.in/s371e09b16e21f7b6919bbfc43f6a5b2f0/uploads/2025/09/202509092087795369.pdf ; …/2020/10/2020100484.pdf ; https://www.indiabudget.gov.in/doc/eb/dg54.pdf |
| Mizoram profile | COMPACT / VLC; SBI | V | https://informatics.nic.in/uploads/pdfs/b0dbf947_mizoram.pdf ; https://cag.gov.in/ae/mizoram/en/page-ae-mizoram-account-vlc |

**Still [U] after pass 2** (only non-official copies, or nothing found):
- MH SNA-SPARSH GRs (seen only in a police circular);
- KL SNA-SPARSH date;
- J&K SNA-SPARSH (JIT circular of 22-01-2025: news only);
- the Chandigarh treasury software and e-Kuber;
- the Ladakh works season;
- RJ GA-55;
- the Delhi new IFMS (₹261.66 cr, press only);
- MP Finance Powers Manual 2025 (no official URL);
- SK Amendment Rules 2024 (secondary only);
- NIFiMS modules;
- IFS-TG-007.

---

## 10. Changelog

| Date | Pass | Change |
|---|---|---|
| 2026-11-04 | 1 | First version: 36 profiles, 78 cross-state and 248 state-specific requirements; 4 thin profiles; 59 lines with [U]. |
| 2026-11-05 | 2 (official-source follow-up) | **Thin profiles filled:** GA 3 → 7 (Accounts-Online, 10 sub-treasuries, DFPR 2008, HoA 4-2-3-2, OL Act 1987), AR 4 → 7 (no Budget Manual, SNA, HoA example, English), MZ 4 → 7 (VLC / GST-GRAS, DFPR r.14 schedules, MADC 1996 → 2010, 15-digit HoA, OL), DH 2 → 6 (two directorates, e-Lekha, central rule pack, Demand 54 BE 2026-27). §0 counts updated. |
| 2026-11-05 | 2 | **Weak fields:** new §9.1 (language, 36 rows), §9.2 (HoA segments: 27 entities with a structure; KA, TN, BR, JH, HR, HP, CT, AS and KL are not 2-2-2), §9.3 (procurement / works, 36 rows: JFR 2025, OGFR amendment 2026, WB GeM mandate, MZ procurement Act, TR goods manual, CG store-purchase rules to 2025). §1 cells updated to match. |
| 2026-11-05 | 2 | **[U] items closed in §1, §2, §3, §4:** LMMH tier ownership; the 8000 / 8001–8999 split (IFS-004); e-Kuber for all states except Sikkim; Works Manual 2nd edn; MH and AP SS (P); TG SS (V); MP and CT e-Kuber; CT counts; JH directorate; TN codes; AP treasury counts; IFS-WB-001, -SK-008, -MZ-004/005, -TR-004/006, -NL-006, -AP-007 (P), -PY-004, -JK-006, -CH-002, -DH-004, -LD-005, -GA-005 (+ new GA-006…008, DH-005…006); UT regime (a) note on DNH&DD. |
| 2026-11-05 | 2 | **Corrections:** KA head of account is SH1-DH2-OH3, not 2-2-2. "8 = Public Account" corrected (8000 is the Contingency Fund). AP has 26 district treasuries after reorganisation. Gujarat's 2002 purchase manual is reported replaced by the Purchase Policy 2024 (left [U]: secondary sources only). |
| 2026-11-05 | 2 | §8 rewritten with the status of each open item. The coder delta is in `spike/results/IFMS-RESEARCH-DELTA.md`. Nothing committed. |
