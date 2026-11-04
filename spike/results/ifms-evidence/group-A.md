# IFMS research — Group A (Rajasthan, Gujarat, Maharashtra, Goa, Madhya Pradesh, Chhattisgarh)

Accessed: 2026-11-04 (all rows). Status: **V** = verified on an official page/PDF fetched in this session; **V(x)** = seen only in an official page's search-result excerpt (not fully fetched); **[U]** = unverified / prior knowledge / secondary source only.

Method notes:
- CAG "Annual Review on the Working of Treasuries" PDFs (cag.gov.in, served via CAG's CloudFront CDN d1jmc0u14z2tmp.cloudfront.net) were the richest official source. Rajasthan and MP reviews are **Hindi-only**; the MP PDF uses a legacy Kruti Dev font (text extracts as Latin gibberish and had to be decoded by hand, so treat MP figures with mild caution).
- The session-wide WebSearch budget ran out partway through (after Rajasthan, Gujarat and Maharashtra). Goa, MP and Chhattisgarh were researched only by fetching known official URLs directly, so they have many more [U] rows.
- Blocked/failed: saiindia.gov.in (connection refused; the cag.gov.in mirror worked); ekoshonline.cg.nic.in (DNS failure; the new domain ekoshonline.cg.gov.in worked); accounts.goa.gov.in (redirect loop); the CAG Chhattisgarh and Goa A&E sub-pages (404).
- CAG URL key used below: `CAG-RJ-TR2425` = https://cag.gov.in/uploads/ae_state_accounts/Final-Annual-Review-PDF-06870d0bed06534-66656426.pdf ; `CAG-GJ-TR2526` = https://cag.gov.in/uploads/ae_state_accounts/Treasury-Review-2025-26-06a4de71d99de95-22145555.pdf ; `CAG-MH-AR2223` = https://cag.gov.in/uploads/media/Final-Annual-review-2022-23-066bb056ed454e3-48312028.pdf ; `CAG-MP-TR2425` = https://cag.gov.in/uploads/ae_state_accounts/Treasury-Review-for-the-year-2024-25-069537c4e860fe1-54402331.pdf ; `RJ-FD-CAG2025` = https://cag.gov.in/uploads/media/Rajasthan-SNA-SPARSH-CAG-19-09-1-068ceb1aa563593-38246907.pdf (Rajasthan Finance Dept presentation "Best Practices under IFMS in Rajasthan", CAG Finance Secretaries Conference, 19 Sep 2025).

---

## Rajasthan (IN-RJ)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name/URLs/modules | **IFMS 3.0** (NIC-built; Finance Dept owner); mobile app. Core module groups: **Budget Mgmt** (masters, BFC estimation, re-appropriation, pool budget, RE enforcement, additional authorisation, surrender, supplementary, SNA grant release, contingency); **Disbursement Engine** (employee & salary mgmt = Pay Manager, pension mgmt, treasury & ceiling, **Work Account Mgmt (WAM)**, SNA-SPARSH, vendor/beneficiary mgmt, FVC/GIA bills, inventory, ICDMS, ESS/PSS self-service); **Revenue Mgmt** (e-GRAS: quick challan, departmental e-challan, track/deface challan, refund, challan reconciliation, remitter registration, payment gateway); **Accounting** (compilation, reconciliation, AG reports, AG IM-server integration); stamps inventory; civil pension; online reconciliation. Portals: ifms.raj.nic.in (legacy), ifms.rajasthan.gov.in, paymanager.rajasthan.gov.in | RJ-FD-CAG2025; https://informatics.nic.in/files/websites/july-2025/ifms-rajasthan.php | yes | V (module list); portal URLs V(x) |
| 1a. Scale | 67 administrative depts, 145 HoDs, 204 budget controlling officers, 29,000+ offices, 10 lakh+ employees, 5.67 lakh civil pensioners, 99K+ vendors, 1.5 Cr+ beneficiaries, 44,000+ DDOs/divisions/PD accounts, 57+ banks | RJ-FD-CAG2025 | yes | V |
| 2. Treasury structure | **Directorate of Treasuries & Accounts** (Hindi: Nideshalaya Kosh evam Lekha; head = Director & ex-officio Joint Secretary). **49 treasuries, 247 sub-treasuries, 33,337 DDOs** (2024-25 review). 56 treasuries/240 sub-treasuries were working until 15-01-2025, after which 7 treasuries were turned back into sub-treasuries. 2,850 sanctioned treasury posts, 1,779 filled. A **Cyber Treasury** was set up for SNA-SPARSH | CAG-RJ-TR2425 (Hindi), RJ-FD-CAG2025 | yes | V |
| 3. Financial rules | **GF&AR** (General Financial & Accounts Rules) Part I (rules & procedures), Part II (store purchase), Part III (delegation of financial powers); third-party copies are amended to 31-08-2023 [U]. **Budget Manual Vol-I** (finance.rajasthan.gov.in). **RTPP Act 2012** (in force 26-01-2013) + **RTPP Rules 2013**, frequently amended: Seventh Amendment Rules 2025 (17-11-2025) changed rule 42(6) so bid security may be paid via eGRAS or an FDR; further SPPP notifications on 26-11-2025 and 01-12-2025. PWF&AR (works) not checked | https://finance.rajasthan.gov.in/docs/rules/gfar/GFR-I.pdf ; https://finance.rajasthan.gov.in/docs/budget/budgetmanual/budgetmanual_vol1_270912.pdf ; https://finance.rajasthan.gov.in/docs/rules/Bill19-2012.pdf ; https://finance.rajasthan.gov.in/PDFDOCS/FR/14715.pdf ; https://sppp.rajasthan.gov.in/actrulesprocedures.php | yes | V(x) (titles/dates from official-page excerpts); PWF&AR [U] |
| 4. Bill types/forms | IFMS bill types are mapped to budget heads: **GIA bill, GST-TDS bill, SD-refund bill, rejection advices, FVC bills**, salary bills via Pay Manager (also a PRI module for Panchayati Raj staff), work bills via WAM. Workflow: Maker / Checker / Approver at implementing-agency level; e-bills with sub-vouchers and sanctions; bills held for want of budget are **auto-forwarded** to the sub-treasury when budget becomes available. GA-55 = salary bill form (secondary sources only) | RJ-FD-CAG2025; CAG-RJ-TR2425 | yes | V (bill types/workflow); GA-55 [U] |
| 5. Integrations | RBI (**e-Kuber**; NIC cites about Rs 100 cr/yr savings), PFMS, NPCI (**APBS/Aadhaar-based payments**), AG IM server / VLC, GSTIN, NSDL, UIDAI (**Aadhaar Vault**), agency banks, ministry portals (NREGA-Soft, OMMAS, AwaasSoft, PMMVY). **SNA-SPARSH: first state** — GoI OM 13-07-2023; FD circular 07-08-2023; SBM-G and RUSA rolled out Aug 2023; 4 CSS in FY23-24; 25 CSS + 53 SLS onboarded in FY24-25 (Rs 2,469.55 cr); RBI drawing accounts for 133 SLS / 78 CSS (as of 11-09-2025); MGNREGA/PMGSY integrated Mar 2025; Rs 500 cr incentive. Single-DDO + single-treasury model; e-account submission to AG since March 2020. Raj SSO login; OTP MFA | RJ-FD-CAG2025; informatics.nic.in (above) | yes | V |
| 6. Budget classification | Demand no. (2) + Major (4) + Sub-major (2) + Minor (3) + **Sub-head (2, in round brackets)** + **Group head (2, in square brackets; rare)** + **Object head (2)**, i.e. 17 digits including the demand number. Published object-head list (01 Salaries, 03 Travel, 16 Minor Works …). Object heads are also used for PD-account receipts. IFMS keeps a CSS sharing pattern (CA/SF) and maps state heads to GoI heads | Budget Manual Vol-I (above); https://finance.rajasthan.gov.in/aspxfiles/budgetobjectcodes.aspx ; CAG-RJ-TR2425 Annex 9 | yes | V(x) for code lengths; object heads V |
| 7. Languages | Hindi (CAG treasury review published only in Hindi; FD circulars bilingual) + English (IFMS UI, presentations) | CAG-RJ-TR2425 | yes | V |
| 8. Distinctive features | **PD accounts at scale**: 829 PD accounts received Rs 27,157.66 cr from the Consolidated Fund in 2024-25 (revenue Rs 21,693.97 cr; capital Rs 5,253 cr; loan heads Rs 210.69 cr). Treasury network was restructured mid-year (15-01-2025). CAG found duplicate payments through Pay Manager (Rs 11.79 [lakh/cr unclear]) and IFMS 3.0 control weaknesses. TSP / Fifth Schedule areas (Banswara, Dungarpur etc.) not verified | CAG-RJ-TR2425; RJ-FD-CAG2025 | yes | V (PD); TSP [U] |

**Candidate state-specific requirements**
- IFS-RJ-001: Budget head model must support 7 segments (demand-2, MH-4, SMH-2, MinH-3, sub-head-2, group-head-2, object-2), with an optional group head and code-display conventions (round/square brackets).
- IFS-RJ-002: Configurable bill-type catalogue (salary, GIA, FVC, GST-TDS, SD-refund, works/WAM, pension, PRI salary), each mapped to permitted budget heads and mandatory deduction heads, validated against GF&AR rules.
- IFS-RJ-003: SNA-SPARSH JIT lifecycle: mother sanction intake from PFMS, auto budget release for CA/SF by sharing pattern, IA hierarchy and limit allocation, Maker-Checker-Approver bills at IA level, debit-mandate/DN-CN-RN reconciliation through a Cyber Treasury.
- IFS-RJ-004: "Pending for budget" bill queue that auto-forwards when allotment arrives, with an audit trail (CAG flagged this behaviour).
- IFS-RJ-005: PD-account sub-ledger with object-head-wise receipts, and alerts on large transfers from the Consolidated Fund to PD accounts.
- IFS-RJ-006: Treasury/sub-treasury hierarchy must be effective-dated, so a sub-treasury can be promoted or demoted mid-year with accounts rolled up correctly.
- IFS-RJ-007: Aadhaar Vault integration (encrypted storage, UI masking) and APBS payment rail alongside NEFT/e-Kuber.
- IFS-RJ-008: e-GRAS-style receipt portal with challan defacement, refund and remitter registration, and use of eGRAS challans as bid security (RTPP rule 42(6)).
- IFS-RJ-009: Hindi as a primary language for reports and audit outputs.

---

## Gujarat (IN-GJ)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name/URLs/modules | **IFMS** (in use since 2009-10; pilot at Ahmedabad treasury in 2008; all treasuries 2008-09; all sub-treasuries by Dec 2010). **18 modules**: PR/CR tracking, cheque inventory, pension, external interface, bill processing, SSNL, expenditure accounting, grant, e-scroll, RBI interface, PD/PLA, EMD, stamp processing, letter of credit, PAO, pension application, employee census, information. **Cyber Treasury Portal** (e-receipts): on VATiS server Dec 2008, on IFMS server May 2011; DDO digital signatures since Apr 2012. **New IFMS (IFMS-III) is under development**; e-sub-voucher functionality is still pending. CAG notes: no audit trails, password-only access, unsupported hardware | CAG-GJ-TR2526 §1.4.3, §4.3; https://gil.gujarat.gov.in/tendercms/TenderDocs/201731414435508.pdf | yes | V |
| 2. Treasury structure | **Directorate of Accounts and Treasuries (DAT), Gandhinagar** (est. May 1960). **34 district treasuries, 111 sub-treasuries, 2 PAOs (Ahmedabad, Gandhinagar), 5 Pension Payment Offices, 1 Divisional Treasury (Ahmedabad), 1 e-PAO (GST) Gandhinagar**. 5,352 controlling officers, 850 HoDs, 250 administrative depts; about **5,042 DDOs** (digital-signature paragraph). A 2017 count was 33 treasuries / 138 sub-treasuries | CAG-GJ-TR2526 §1.2, §4.1, §4.2; GIL 2017 PDF | yes | V |
| 3. Financial rules | **Gujarat Financial Rules 1971**; **Gujarat Treasury Rules 2000** (e.g. r.109 digital signature, r.211 AC/DC, r.368-369, r.398 PD withdrawals); **Gujarat Budget Manual 1983**; **Financial Powers (Delegation) Rules 1998** (FD GR SSP-1098-316-Z, 06-06-1998; English and Gujarati); Manual of Office Procedure for Purchase of Stores (2002 ed.; Departmental Purchase Committees). Current procurement policy/GR not verified | CAG-GJ-TR2526; https://financedepartment.gujarat.gov.in/Documents/Rule-Eng_13_2014-2-19_481.pdf ; https://financedepartment.gujarat.gov.in/rules.html ; https://sje.gujarat.gov.in/dsd/downloads/pur_manual200204.pdf | yes | V (rules cited by CAG); DFP & purchase manual V(x); current procurement [U] |
| 4. Bill types/forms | AC bills, with **DC bill in Form-46** (2,655 AC bills worth Rs 104.60 cr pending as of 31-03-2026); GIA with utilisation certificates (Rs 7,977.23 cr of UCs awaited); GPF (MH 8009); pension (PPOs); **Letter of Credit / cheque system for R&B, Irrigation and Forest divisions**; online bill submission with **DDO digital signature (PKI; DSC from (n)Code Solutions/GNFC)** | CAG-GJ-TR2526 | yes | V |
| 5. Integrations | RBI interface, banks, AG; Cyber Treasury e-receipts; **e-PAO (GST)** for GST receipts. SNA-SPARSH: listed as an "advanced-stage pilot" in the 2023 DoE year-ender; MoHFW 2024-25 showed nil PM-ABHIM SNA-SPARSH spend for Gujarat. e-Kuber, PFMS, DBT portal not verified | CAG-GJ-TR2526; https://www.pib.gov.in/PressReleasePage.aspx?PRID=1990746 | yes | V (RBI/GST); SNA V(x); others [U] |
| 6. Budget classification | Sub-heads under minor heads with 2-digit codes (e.g. 8449-00-120-11). CAG found IFMS sub-head mapping not matching the budget document, and minor heads not opened per LMMHA. Plan/non-plan budget appears in the 2017 IFMS scope. Object/detailed-head structure not verified | CAG-GJ-TR2526 §3.4.3, §3.4.5; GIL 2017 PDF | yes | V (sub-head); rest [U] |
| 7. Languages | Gujarati + English (FD rules published in both languages; CAG review in English) | financedepartment.gujarat.gov.in/rules.html | yes | V(x) |
| 8. Distinctive features | DAT also runs pay fixation/verification for state and Panchayat staff, stores verification for panchayats, CGEGIS/SGGIS accounts, and HBA through HDFC. Pensions are paid for state, central, Panchayat, GIA-institution staff and freedom fighters. PD/PL accounts (36 inoperative for 3+ years; a 3-year closure rule under FD circular of 03-06-2009; IFMS allowed minus balances). Tribal treasuries include Ahwa-Dang and Narmada; TSP budgeting not verified | CAG-GJ-TR2526 | yes | V; TSP [U] |

**Candidate state-specific requirements**
- IFS-GJ-001: AC/DC bill pairing (DC in Form-46) with ageing and escalation dashboards; block new AC drawals while DCs are overdue (configurable).
- IFS-GJ-002: Letter-of-Credit / cheque-drawing mode for works divisions (R&B, Irrigation, Forest) alongside treasury bill mode.
- IFS-GJ-003: PD/PLA ledger with hard no-minus-balance control and auto-flagging/closure workflow for accounts inactive for 3 years.
- IFS-GJ-004: Sub-head master versioning reconciled against the budget document and the AG database (prevent the mapping drift CAG found).
- IFS-GJ-005: Separate accounting unit types: District Treasury, Sub-Treasury, PAO, Pension Payment Office, Divisional Treasury, e-PAO (GST).
- IFS-GJ-006: Mandatory PKI/DSC signing for DDO bill submission; e-sub-voucher support.
- IFS-GJ-007: Pension module covering multiple payer classes (state, Panchayat, GIA institutions, freedom fighters, central agency pensions).
- IFS-GJ-008: Gujarati + English UI and print formats.

---

## Maharashtra (IN-MH)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name/URLs/modules | **IFMS / Mahakosh** (NIC Pune; MAHA-IT supports Sevaarth and NVV). Modules: **BEAMS** (budget; vendor/payee registration; "Virtual PLA"), **Bill Portal** (billportal.mahakosh.gov.in, all bills except pay bills; "bill-cum-BDS" barcode), **Treasury Net** (bill receipt, audit, cheque, compilation), **Sevaarth** (payroll/HRMS; pension data from AG), **Nivrutti Vetan Vahini (NVV) / Nivrutivetanika** (pension bills), **Arthwahini** (daily data upload; bank-account confirmation), **Koshwahini** (public MIS, koshwahini.mahakosh.gov.in), **GRAS** e-challan (gras.mahakosh.gov.in/echallan), **CMP e-payment**, HRMS (mahakosh.maharashtra.gov.in/HR/HRMS) | CAG-MH-AR2223 §1.2-1.3, ch.5-6; https://mahakosh.maharashtra.gov.in/ ; https://billportal.mahakosh.gov.in/ | yes | V |
| 2. Treasury structure | **Directorate of Accounts & Treasuries (DAT), Mumbai** (est. 01-01-1962), with **6 regional Joint Directors** (Pune, Nashik, Aurangabad, Nagpur, Amravati, Navi Mumbai/Konkan). **34 district treasuries, 323 sub-treasuries** (all banking treasuries), **1 PAO Mumbai** (64,394 pensioners), **1 Virtual Treasury** (GRAS receipts, under JDAT Konkan). Accounts are also rendered by 342 PWD & Irrigation divisions, 174 Forest divisions and 69 other divisions. About 1.57 lakh vouchers/month; 2,988 staff in position. Two AG offices (Mumbai: 15 treasuries; Nagpur: 19) | CAG-MH-AR2223 | yes | V |
| 3. Financial rules | **Maharashtra Treasury Rules 1968** (Mahakosh copy amended to 31-07-1981; Marathi and English); **Bombay Financial Rules 1959**; **Maharashtra Contingent Expenditure Rules** (1965 per filename); **Accounting Rules for Treasuries 1992**; Maharashtra Koshagar Niyampustika Vol I & II (Marathi); **Manual of Office Procedure for Purchase of Stores** + GR CSPO-1088/(2512)/Industry-6 of 02-01-1992; MCS Revised Pay Rules 2009. **Delegation of Financial Powers Rules 1978** and **Maharashtra Budget Manual** are listed in the CAG Mumbai manuals list. Current procurement GR (2016 Industries Dept) not verified | https://mahakosh.maharashtra.gov.in/rulesManuals.php ; https://cag.gov.in/ae/mumbai/en/page-ae-mumbai-list-of-menual-ae-mumbai | yes | V (Mahakosh list); DFPR/Budget Manual V(x); procurement GR [U] |
| 4. Bill types/forms | Bill Portal MTR forms: **MTR-18** (TA tour/transfer/retirement, LTC), **18B** (festival/medical/TA advances), **24A**, **28**, **29**, **31** (contingent: telephone, electricity, POL, honorarium …), **44** (grant-in-aid), **45/45A**, **52/52A** (GPF), **GIS-8**; medical reimbursement. Each bill has 5 parts (accounting info, core bill, payee + DDO certificate, treasury space, AG space). Flow: DDO Bill Portal, then authorisation slip, barcode token at Treasury Net, audit, cheque section (voucher no.), EFT/NEFT/**CMP** advice, TO approval on the CMP portal. e-Kuber pilot (Mulshi Mar 2022; Maval and Ambegaon Nov 2022): passed bills go back to the DDO for beneficiary confirmation (**10 days**) before voucher generation. Vendor bank details are registered in BEAMS and confirmed by the TO in Arthwahini | billportal.mahakosh.gov.in; CAG-MH-AR2223 §1.3, §5.12 | yes | V |
| 5. Integrations | RBI **e-Kuber** (pilot 2022), agency bank CMP (SBI), NEFT/EFT, AG (pension data via Sevaarth), UID/**Jeevan Pramaan** for life certificates, GRAS + mobile app. MahaDBT, PFMS/SNA-SPARSH, GSTN not verified (MoHFW report lists Maharashtra SNA-SPARSH spend [U]) | CAG-MH-AR2223 | yes | V (listed); others [U] |
| 6. Budget classification | BEAMS budget codes / object heads not verified. Capital MHs 4701/4702/4711 drawn into PLAs of Irrigation Development Corporations | CAG-MH-AR2223 §2.28 | yes | [U] (code structure) |
| 7. Languages | Marathi + English (MTR in both; Koshagar Niyampustika and guidebooks in Marathi) | mahakosh rulesManuals.php | yes | V |
| 8. Distinctive features | **Virtual PLA system in BEAMS** (FD GR 02-03-2021): the six Irrigation Development Corporations draw capital funds into PLAs controlled virtually (pilot KBIDC Pune from 01-04-2021). Heavy PD/PLA use, with CAG findings: GST diverted into a PLA, revenue receipts parked in PLAs, Rs 168.56 cr unreconciled. Digitally signed vouchers not yet introduced. Zilla Parishad staff GIS handled via the Bill Portal. MahaDBT and TSP (Adivasi Vikas) not verified | CAG-MH-AR2223 | yes | V; MahaDBT/TSP [U] |

**Candidate state-specific requirements**
- IFS-MH-001: Form catalogue keyed by MTR form number (18, 18B, 24A, 28, 29, 31, 44, 45, 45A, 52, 52A, GIS-8) with per-form claim validation, a 5-part bill layout and barcode/"bill-cum-BDS" output.
- IFS-MH-002: Separate pay-bill (Sevaarth) and non-pay-bill (Bill Portal) channels sharing one budget-watch service (BEAMS).
- IFS-MH-003: Configurable post-pass "beneficiary confirmation by DDO" step with an SLA timer (10 days) before voucher generation, for e-Kuber payments.
- IFS-MH-004: Virtual PLA: budget-controlled virtual sub-ledgers for PSU/corporation administrators instead of cash transfer to bank accounts.
- IFS-MH-005: Regional tier (Joint Director) between the Directorate and district treasuries, and a Virtual Treasury unit for e-receipts.
- IFS-MH-006: Works and forest divisions as accounting units rendering accounts directly to the AG (non-treasury compilation path).
- IFS-MH-007: Pension pipeline: AG e-authority, then payroll system, then treasury first-payment bill, with biometric life certificates via Jeevan Pramaan.
- IFS-MH-008: Marathi + English UI and print formats.

---

## Goa (IN-GA)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name/URLs/modules | Not identified. The Directorate page links accounts.goa.gov.in (redirect loop on fetch), goaonline.gov.in (e-services; NIC payment gateway) and e-stamping. No IFMS module or e-challan system is named | https://www.goa.gov.in/department/accounts/ ; https://www.goaonline.gov.in/ | yes | [U] |
| 2. Treasury structure | **Directorate of Accounts**, HQ Porvorim, branch Margao. The Director is also **Director of Treasuries** and statutory auditor of municipalities, village panchayats and planning authorities. It **works as a Pay & Accounts Office with pre-audit of claims** (10 pay-audit sections at Porvorim, 4 at Margao). **2 district treasuries** (Porvorim–North, Margao–South); treasuries/sub-treasuries at taluka HQs (Dharbandora pending; new Tiswadi sub-treasury). Exact sub-treasury and DDO counts not found | https://www.goa.gov.in/department/accounts/ | yes | V (structure); counts [U] |
| 3. Financial rules | Not verified (Goa is believed to follow Union GFR/CTR/DFPR adapted from the former UT: [U]) | — | — | [U] |
| 4. Bill types/forms | Pre-audit model (PAO style) is verified; specific forms not found | goa.gov.in (above) | yes | V (pre-audit); forms [U] |
| 5. Integrations | NIC payment gateway on Goa Online; e-stamping. RBI/PFMS/SNA-SPARSH not verified (Goa is not named in the 2023 DoE SNA-SPARSH list) | goaonline.gov.in; PIB PRID=1990746 | yes | [U] |
| 6. Budget classification | Not verified | — | — | [U] |
| 7. Languages | English + Konkani (portal has a Konkani version); Marathi also used officially [U] | goa.gov.in | yes | V (En/Konkani) |
| 8. Distinctive features | Directorate combines treasury, PAO pre-audit and local-body audit. Pays central, railway and defence pensions as an agency. Small state with district-level treasuries (not a 3-tier network) | goa.gov.in | yes | V |

**Candidate state-specific requirements**
- IFS-GA-001: Support a **pre-audit (PAO) model**: claims audited centrally by pay-audit sections before payment, not only treasury post-check of DDO-drawn bills.
- IFS-GA-002: Small topology: 2 district treasuries plus taluka sub-treasuries, with pay-audit sections as work queues.
- IFS-GA-003: Local-body statutory audit workflow (municipalities, village panchayats, planning authorities) under the same directorate.
- IFS-GA-004: Agency pension disbursement for central, railway and defence pensioners, with inter-state pension transfer.
- IFS-GA-005: Konkani + English localisation.

---

## Madhya Pradesh (IN-MP)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name/URLs/modules | **IFMIS** (Integrated Financial Management Information System); treasury accounts prepared through IFMIS **since May 2018**. Cyber Treasury e-receipts; SNA-SPARSH bills prepared in IFMIS and integrated with PFMS. Portal URL and module list not fetched (mptreasury.gov.in [U]) | CAG-MP-TR2425 (Hindi, Kruti Dev) | yes | V (IFMIS, 2018, cyber treasury); URL [U] |
| 2. Treasury structure | Finance Dept, then **Commissioner, Treasury & Accounts** (Aayukt Kosh evam Lekha), then **7 divisional Joint Directors (Treasury, Accounts & Pension)**, then **54 treasuries** (2024-25), all banking treasuries. A Central Treasury is also referenced. Sub-treasury and DDO counts not extracted. DDO codes are 10 digits (e.g. 0101704001) | CAG-MP-TR2425 §1.2 | yes | V |
| 3. Financial rules | **Madhya Pradesh Treasury Code 2020** (Koshalay Sanhita; Part II subsidiary rules cited, e.g. r.85, 150-151, 250, 314, 319, 324-325); **MP Budget Manual Vol-II** cited. MP Financial Code, DFP book, MP Bhandar Kray evam Seva Upaarjan Niyam not verified | CAG-MP-TR2425 | yes | V (Treasury Code 2020, Budget Manual); rest [U] |
| 4. Bill types/forms | **MPTC forms** (e.g. MPTC-56 for PWD monthly account, MPTC-71 list), **Form 80**; TA bills needing sanction; telephone/electricity contingent bills; GPF/DPF vouchers; specimen-signature scanning in IFMIS is missing | CAG-MP-TR2425 | yes | V (form numbers); purposes partly [U] |
| 5. Integrations | **SNA-SPARSH / JIT** per GoI directions dated 29-07-2024: bill prepared in IFMIS, integrated with PFMS, credit notification of central share to the state SNA. Cyber Treasury. e-Kuber, GSTN, DBT not verified | CAG-MP-TR2425 §4.5.2 | yes | V |
| 6. Budget classification | **Object head + detailed object head** written as "22-008", "31-009", "34-001", "51-000" (2-digit object + 3-digit detail); CAG found many object-head misclassifications. Charged/voted and scheme flags not verified | CAG-MP-TR2425 (annex tables) | yes | V |
| 7. Languages | Hindi (CAG review in Hindi, legacy font) + English (annex tables) | CAG-MP-TR2425 | yes | V |
| 8. Distinctive features | Forest-revenue depositors must pay through the Cyber Treasury, with a PD-account fallback; PD accounts under 8443-106. Tribal Welfare Dept DDOs appear in findings; Fifth Schedule / TSP budgeting not verified | CAG-MP-TR2425 | yes | V (PD/cyber); TSP [U] |

**Candidate state-specific requirements**
- IFS-MP-001: Object head with a 3-digit detailed sub-object (NN-NNN) as a first-class classification segment, validated against the bill type to stop misclassification.
- IFS-MP-002: Hierarchy Commissioner, then divisional Joint Director, then treasury (no sub-treasury tier assumed; make tiers configurable).
- IFS-MP-003: Form library keyed to MP Treasury Code 2020 forms (MPTC-xx, Form 80) with rule references stored on validations.
- IFS-MP-004: JIT/SNA-SPARSH bill type that creates a PFMS-linked payment and tracks the central-share credit notification.
- IFS-MP-005: Mandatory cyber-treasury receipt channel per revenue head/department, with a configurable PD fallback.
- IFS-MP-006: Specimen-signature/DSC registry for DDOs within the IFMIS (gap flagged by CAG).
- IFS-MP-007: Ability to ingest and emit legacy-font Hindi documents (Kruti Dev to Unicode conversion) for historic records.

---

## Chhattisgarh (IN-CT)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name/URLs/modules | **e-Kosh** (ekoshonline.cg.gov.in; new portal at /newekoshonline; old cg.nic.in domain is dead). Modules: e-Budget (Allocation/Proposal), budget reports, **e-Bill**, **e-Challan** (echallan.cg.gov.in), receipt dashboard, **e-Payroll** (incl. ministers' payroll and self-DDO), **Karmik Sampada/e-Karamchari** (HR), Employee Corner, **Online GPF Final**, e-Refund, **Fund Drawal System**, **Vendor Payment System** (OTP), Treasury Login, **BCO reconciliation with AG**, AG interface (AG slip), PFMS reports, **"SNA Sparsh & State DBT"** (/cgsnasparsh), Shram Samman, e-Loksewa; pension.cg.gov.in; cps.cg.gov.in. The FD site mentions an **"IFMIS Next Gen" Budget Planning module** | https://ekoshonline.cg.gov.in/ ; https://finance.cg.gov.in/ | yes | V |
| 2. Treasury structure | **Directorate of Treasury and Accounts, Chhattisgarh** (Sanchalanalay Kosh evam Lekha; recruitment rules say "Directorate Treasury, Accounts and Pension"). Treasury, sub-treasury and DDO counts not found | https://dta.cg.gov.in/ ; https://finance.cg.gov.in/Rules_acts.aspx | yes | V (name); counts [U] |
| 3. Financial rules | FD list includes CG FRBM Act 2005 (amended to 2020) and Rules 2006; Contingency Fund Act 2001 (amended 2015, 2025); GPF Rules (amended 2020); Pension Fund Act 2025 / Rules 2026; State Guarantee Rules 2020; Growth & Stability Fund Act 2025 / Rules 2026; MP Reorganisation Act 2000. A CG Financial Code, Treasury Code, Budget Manual, DFP and Bhandar Kray Niyam are **not listed** (likely inherited MP codes adapted after 2000: [U]) | finance.cg.gov.in/Rules_acts.aspx | yes | V (list); codes [U] |
| 4. Bill types/forms | FD forms: BCO expenditure-limit relaxation, re-appropriation, new item, contingency-fund advance, supplementary, guarantee application, pension-case set (all Hindi). Treasury bill forms not found | https://finance.cg.gov.in/form_formats.aspx | yes | V (budget forms); bill forms [U] |
| 5. Integrations | PFMS (reports), **SNA-SPARSH combined with State DBT**; 2023 DoE list put CG among advanced-stage pilots; AG interface; Jeevan Pramaan; e-Challan. e-Kuber, GSTN not verified | ekoshonline.cg.gov.in; PIB PRID=1990746 | yes | V / V(x) for pilot status |
| 6. Budget classification | BCO-level expenditure limits (relaxation format) exist; code structure not verified (probably MP-like object/detail heads: [U]) | finance.cg.gov.in forms | yes | [U] |
| 7. Languages | Hindi + English (site toggle; forms in Hindi) | dta.cg.gov.in | yes | V |
| 8. Distinctive features | Budget Controlling Officer (BCO) expenditure ceilings with a relaxation request process; ministers' payroll in e-Payroll; labour (Shram Samman) payments via e-Kosh; state Pension Fund (2025). Fifth Schedule / TSP not verified | ekoshonline.cg.gov.in; finance.cg.gov.in | yes | V; TSP [U] |

**Candidate state-specific requirements**
- IFS-CT-001: BCO expenditure-limit ceilings with a formal relaxation request/approval workflow.
- IFS-CT-002: A single disbursement channel shared by SNA-SPARSH CSS payments and state DBT schemes.
- IFS-CT-003: Fund Drawal and Vendor Payment flows with OTP-confirmed vendor payments.
- IFS-CT-004: BCO-to-AG reconciliation workflow (monthly expenditure reconciliation sign-off).
- IFS-CT-005: Budget-proposal forms (new item, supplementary, re-appropriation, contingency-fund advance) as configurable workflow templates.
- IFS-CT-006: Hindi-first forms with English fallback.

---

## Cross-state observations

1. **Same treasury model everywhere, with different tiers**: Directorate (sometimes Commissioner) → optional regional/divisional Joint Directors (MH 6, MP 7) → district treasuries (RJ 49, GJ 34, MH 34, MP 54, GA 2) → sub-treasuries (RJ 247, GJ 111, MH 323). The platform needs an effective-dated, configurable org tree that also includes extra unit types: PAO, Pension Payment Office, Virtual/Cyber Treasury, e-PAO (GST), and works/forest divisions that render accounts directly to the AG.
2. **PD/PL accounts are a major fund-parking channel** in every state where data was found (RJ 829 accounts / Rs 27k cr; GJ and MH inoperative accounts and minus balances; MP forest-revenue fallback). Requirements: no-minus-balance controls, inactivity closure, and virtual-PLA style budget control (MH).
3. **Classification goes below LMMH in every state**: sub-head (2), optional group head (RJ), object head (2) and detailed object (MP NN-NNN). CAG repeatedly flags mapping drift, so masters must be versioned and reconcilable with the budget document and AG data.
4. **Bill catalogues are keyed by state form numbers** (MTR-xx in MH, MPTC-xx/Form 80 in MP, Form-46 DC in GJ, GA-xx in RJ [U]). Use a form-number-driven template registry with rule citations.
5. **AC/DC bill tracking and GIA utilisation certificates** are chronic audit issues (GJ Rs 104.6 cr pending DC; Rs 7,977 cr UCs). Generic ageing and escalation rules apply across states.
6. **SNA-SPARSH/JIT is now universal** (Expenditure Profile 2026-27: all notified states onboarded; 50 of 81 CSS by 31-12-2025, V(x)). RJ was first (2023); MP is on GoI 29-07-2024 directions; CG combines it with state DBT. Common flow: mother sanction → state share matching → IA allocation → bill → PFMS/RBI debit/credit notification reconciliation.
7. **Payment rails are moving from agency-bank CMP to RBI e-Kuber** (RJ fully, MH piloting) plus APBS/Aadhaar. Rails must be pluggable per state and per treasury.
8. **Online e-receipt portals** (e-GRAS RJ, Cyber Treasury GJ/MP, GRAS MH, e-Challan CG) all need challan lifecycle support, bank-scroll reconciliation and use of challans as bid security or deposits.
9. **Digital signing is uneven** (GJ PKI since 2012 with gaps; MH has no digitally signed vouchers yet; MP lacks a specimen-signature registry). Signing must be configurable, and a paper-token/barcode mode is still needed.
10. **Languages**: Hindi (RJ, MP, CG), Marathi (MH), Gujarati (GJ), Konkani (GA), each with English. Audit outputs are sometimes in a single regional language. Legacy Hindi fonts (Kruti Dev) persist in official PDFs.
11. **Tribal/Fifth Schedule (TSP) budgeting** was not verified for any state in this pass because the search budget ran out. It is a known gap to research next for RJ, GJ, MH, MP and CG.
