# IFMS research: Group C (Karnataka, Tamil Nadu, Kerala, Andhra Pradesh, Telangana)

Accessed date for all rows: 2026-11-04. Status: **V** = verified on an official page/document (gov.in / nic.in / cag.gov.in incl. its CDN `d1jmc0u14z2tmp.cloudfront.net`, which cag.gov.in 302-redirects to); **[U]** = unverified (secondary lead only, fetch blocked, scanned/image PDF, or not found).

Method notes
- CAG/SAI PDFs were downloaded via WebFetch (cag.gov.in redirects to its CloudFront CDN) and text-extracted locally with `pdftotext`; saiindia.gov.in refused connections (ECONNREFUSED) so the cag.gov.in mirror was used.
- treasury.telangana.gov.in PDFs failed with a TLS certificate-chain error, so they are [U].
- The Telangana IFMIS deck (CAG conference 2025) is mostly images, but its text layer was readable.
- No logins and no CAPTCHAs were encountered.

Key official sources used (short names used in tables)
- **KA-ART24**: AG (A&E) Karnataka, *Annual Review on the working of Treasuries 2023-24*: https://cag.gov.in/uploads/media/ART-2023-24-067ed1c123482e6-09555781.pdf
- **KA-SFS25**: Karnataka FD deck, *2nd Conference of State Finance Secretaries, 19.09.2025*: https://cag.gov.in/uploads/media/Karnataka-Second-Conference-of-State-Finance-Secretaries-19092025-068ceb1f0363c91-64620654.pdf
- **KA-PA21**: CAG Performance Audit Report No. 4 of 2021, *IFMS Khajane-II*: https://cag.gov.in/en/audit-report/details/117308
- **TN-SFS25**: TN FD deck, *Major IT Initiatives (IFHRMS / Kalanjiyam)*, 19.09.2025: https://cag.gov.in/uploads/media/Tamil-Nadu-Major-IT-Initiatives-068ceb202706392-89128310.pdf
- **TN-ABOUT**: https://karuvoolam.tn.gov.in/web/tnta/about-us
- **KL-AR24**: AG (A&E) Kerala, *Annual Review of the Working of Treasuries in Kerala 2023-24*: https://cag.gov.in/uploads/media/Annual-Review-2023-24-approved-by-AG-for-circulation-066b5f50082cb10-26407049.pdf
- **KL-IFMS**: https://ifms.kerala.gov.in/about
- **KL-BILLS**: Treasury Kerala, *Bill system in Kerala* (2020): https://portal.treasury.kerala.gov.in/treasury_portal/truploads/services/bills.pdf
- **AP-ARR21**: AG (A&E) AP, *Annual Review Report 2020-21*: https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-Report-2020-21-061b9d01d7e96c2-03475112.pdf (saiindia mirror is the same file)
- **AP-CFMS**: https://cfms.ap.gov.in/about.html
- **AP-TR22**: *Draft Andhra Pradesh Treasury Rules 2022*: https://cfms.ap.gov.in/gos/AP_Treasury_Rules_draft.pdf
- **AP-BE27**: AP FD G.O.Ms.No.1 dt 02-01-2026 (BE 2026-27 guidelines): https://apfinance.gov.in/images/BE_2026-27.pdf
- **TG-ARR22**: AG (A&E) Telangana, *Annual Review Report 2021-22*: https://cag.gov.in/uploads/ae_state_accounts/ARR2021-22-063f4a0a821ea40-14863316.pdf
- **TG-SFS25**: Telangana FD deck, *IFMIS* (2025): https://cag.gov.in/uploads/media/Telangana-PPT-onIFMIS-068ceb20ab627e1-31127828.pdf

---

## Karnataka (IN-KA)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system(s) & modules | **Khajane-II (K2)**, the IFMS that replaced Khajane-I (treasury computerisation since 2001); the upgrade was decided in 2009. Modules: e-Sanction (online sanction orders, DSC-signed, validated against General/Special Delegation of Financial Powers); e-Budget (Budget Release, **AIE = Authorisation to Incur Expenditure**, GDFP fund release/redistribution, re-appropriation, supplementary estimates, additionality, Contingency Fund, surrender/errata); e-Receipt (tax, non-tax, TTR via netbanking/cards/UPI/NEFT-RTGS; 1.9 crore challans in 2024-25); e-Payment through EPS (DBT, account-based, e-RUPI, in-kind); e-Voucher to AG (from 2022; printed salary vouchers stopped 1 June 2025). **HRMS is a separate standalone application** that generates salary bills for about 5.03 lakh government and aided employees; K2 fetches the claims from it. Works: integrated with e-Procurement (AA, tendering, contract management, e-MB). Goods: GeM integration and a Vendor Management System module (<Rs 5 lakh) are being built. AG modules still at SRS stage on 31.03.2024 (Deposit, Accounts Compilation, Pension/e-PPO, HBA/vehicle advance, Loans, Treasury). Integrations with Commercial Tax, High Court, Transport, DMA, Excise, Kaveri (IGR), scholarships, Bhoomi. Portal k2.karnataka.gov.in [U]. | KA-SFS25; KA-ART24; KA-PA21 | 2026-11-04 | V (portal URL [U]) |
| 2. Treasury structure | **Commissioner of Treasuries (CoT), Bengaluru**, under the Finance Department. On 31.03.2024: **31 District Treasuries, 207 Sub-Treasuries**, plus State Cyber Treasury, State Pension Payment Monitoring Treasury, State Huzur Treasury, Additional State Huzur Treasury, Stamp Depot, Hubballi Treasury and a **Central Accounting Unit (CAU)**, a treasury used only for CSS under SNA-SPARSH. 36 account-rendering treasuries. All are banking treasuries. **DDOs: 31,556 (2024-25)**, with 36.75 lakh bills worth Rs 4.43 lakh crore. Staff in Taluk Panchayat treasuries. | KA-ART24; KA-SFS25 | 2026-11-04 | V |
| 3. Financial rules | Karnataka Treasury Code (Arts 13, 155, 206, 218 cited), Karnataka Financial Code (Arts 75, 176, 286/286-A on PD accounts cited). **KTPP Act 1999** (Karnataka Act 29 of 2000; text on indiacode.nic.in), amended 2001, 2003, 2007, 2015, 2023 (sec 6 SC/ST works reservation raised to Rs 1 crore; ordinance of 29.03.2023). Budget Manual edition, KPWD Code edition and KTPP Rules 2000 amendments not checked. | KA-ART24; https://www.indiacode.nic.in/bitstream/123456789/7000/1/29_of_2000%28e%29.pdf | 2026-11-04 | V for codes cited and KTPP Act; amendment list [U] (PRS/secondary) |
| 4. Bills, token, e-payment | Bills created against an online sanction order. AG dashboard analyses vouchers by treasury token, bill type and claim type. Failed e-payments go to suspense; refunds older than one year need AG "Alteration Memoranda". NDC (non-drawal/detailed contingent) bill delays can stop DDO salary. Under development: real-time bank-account validation, so a payee becomes immutable after the DDO verifies it. Bill-form numbers (KTC forms) not captured. | KA-ART24; KA-SFS25 | 2026-11-04 | V (form numbers [U]) |
| 5. Integrations | RBI e-Kuber; agency banks; AG (SFTP CSV voucher feed). **SNA implemented in treasuries vide GO FD 39 TAR 2022 dt 28.04.2022** (SNA accounts from July 2022). **SNA-SPARSH** run through a dedicated CAU treasury (TSA with RBI); CSS central share received under MH 1601 via CAS Nagpur. Separate detailed heads for state and central share. PFMS and e-Kuber integrated. | KA-ART24 | 2026-11-04 | V |
| 6. Budget classification | **15-digit classification** used on vouchers, and AG flags misclassification of the 15-digit code and of revenue/capital. A **12-digit classification** is cited for a Civil Deposit. Separate detailed heads for central and state share of CSS. Exact segment split not checked. | KA-ART24 | 2026-11-04 | V (segment split [U]) |
| 7. Languages | Kannada (official language) and English. Not checked on finance documents. | — | 2026-11-04 | [U] |
| 8. Distinctive features | (a) CAU treasury dedicated to SNA-SPARSH; (b) delegation of financial powers (GDFP/SDFP) checked by the system when a sanction is approved; (c) PD accounts under MH 8443 with large inoperative or adverse balances (Rs 577.83 crore debit balances across 19 administrators); (d) treasury staff posted at **Taluk Panchayats**; (e) online FD-concurrence module and quarterly certification of boards' and corporations' bank accounts, FDs and ODs (planned). | KA-ART24; KA-SFS25 | 2026-11-04 | V |

Candidate state-specific requirements
- **IFS-KA-001**: Sanction-before-bill. A bill must reference an approved, DSC-signed sanction order. The system checks the sanction's classification, recipient category and financial limit against the bill, and checks General/Special Delegation of Financial Powers matrices that are configurable per state.
- **IFS-KA-002**: Budget-control chain configurable as Budget Release, then AIE, then fund release/redistribution (GDFP), with re-appropriation, supplementary, additionality and Contingency Fund workflows.
- **IFS-KA-003**: Support a 15-digit head-of-account code (configurable segment lengths) plus a revenue/capital nature flag, validated at bill entry.
- **IFS-KA-004**: Pluggable external HRMS. Salary claims arrive as bills generated in a separate HRMS and are not prepared inside the IFMS.
- **IFS-KA-005**: Special treasury types: Cyber Treasury, Huzur Treasury, Pension Monitoring Treasury, and a CAU treasury limited to SNA-SPARSH transactions.
- **IFS-KA-006**: Works bills carry e-Procurement artefacts (e-MB, contract certificate, sanction) as digitally signed attachments that go to AG. Separate procurement paths above and below Rs 5 lakh (GeM or VMS).
- **IFS-KA-007**: Payee master with real-time bank-account validation. Bank details are locked once the DDO verifies them.
- **IFS-KA-008**: Failed e-payment lifecycle: suspense credit, re-issue, and AG Alteration Memo after one year.

---

## Tamil Nadu (IN-TN)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system(s) & modules | **IFHRMS, branded "Kalanjiyam"**, on the Karuvoolam portal (karuvoolam.tn.gov.in). Three verticals: Finance & Budgeting; HR (employees and pensioners); Treasury & Accounting, plus e-Challan. Modules: budget preparation (Number Statement, Part I/II estimates, re-appropriation, surrender, Contingency Fund, quarterly control of appropriation, supplementary estimates, real-time BvA); online bill submission and passing; out-of-treasury disbursements; state receipt management (e-Challan); deposit management including department PD accounts; reserve funds; loans and advances; debt (OML, NSSF, EAP, NABARD); guarantees (pilot); schemes master with SC/ST components and gender tagging (pilot); e-SR, payroll and ESS mobile app (Feb 2024); e-Pension, OPPAS and mustering (Aadhaar face authentication, 12-month window); **Works: Work ID created at proposal stage and activated after AS, with a separate bill type for capital works** (3 infrastructure departments); automatic TDS (from 1 April 2025); Global Beneficiary Master (GBM); TNPDCL electricity-bill auto-fetch; AG-DAAS digital vouchers. Launch year not confirmed. | TN-SFS25 | 2026-11-04 | V (launch date [U]) |
| 2. Treasury structure | **Department of Treasuries and Accounts**, headed by the **Commissioner of Treasuries and Accounts**, Chennai, under the Finance Department (formed 1962). **32 District Treasuries, 243 Sub-Treasuries, 9 Pay & Accounts Offices, 1 Pension Pay Office** (Chennai). **22,037 DDOs**, 9.33 lakh employees, 7.38 lakh pensioners. | TN-ABOUT; TN-SFS25 | 2026-11-04 | V |
| 3. Financial rules | **Tamil Nadu Transparency in Tenders Act 1998** (Act 43 of 1998) and **TNTT Rules 2000** (G.O.Ms.No.446 dt 26.09.2000). Consolidated text has amendments to 26.06.2018 (amendments cited: 2001-2004, 2007, 2009, 2012, 2018). Separate TNTT (PPP Procurement) Rules 2012. TN Treasury Code, TN Financial Code, TN Budget Manual and TN PWD Code exist, but editions were not checked. | https://www.karuvoolam.tn.gov.in/documents/45087/62910/TNTIT+ACT.pdf/4e73a405-511b-4749-4624-64047d51a412 ; https://www.tnbudget.tn.gov.in/tnweb_files/Rules/TNTIT%20Rules.pdf ; indiacode.nic.in handle 19526 | 2026-11-04 | V for TNTT Act/Rules (seen in search on official hosts); codes [U] |
| 4. Bills, token, e-payment | Salary and pension: 16.83 lakh bills. Non-salary: 19.20 lakh bills a year. Centralised welfare bills linked to GBM, so one bill can cover about 10 lakh beneficiaries. Separate capital-works bill type. Paper salary vouchers to AG stopped; non-salary vouchers stop from Aug 2025. Bill-form list and token procedure not found. | TN-SFS25 | 2026-11-04 | V (forms/token [U]) |
| 5. Integrations | AG (A&E) for budget, accounts and pension authorisation; **RBI e-Kuber for payments**; receipts through RTGS/NEFT direct to RBI (**mandatory for remittances of Rs 1 crore or more, from Aug 2024**; T+0 realisation); CAS Nagpur; **PFMS for CSS under SNA and SNA-SPARSH** (SNA-SPARSH bills for 50 SLS; APBS with NPCI/RBI in UAT; PMGSY, NREGA and NRLM ministry systems UAT done); GSTN; Registration, Motor Vehicles, Excise; aggregator banks (SBI, IOB, BoI, IB); TNPDCL; ITD/NSDL/TRACES; **TN Public Fund Tracking System (TNPFTS)** for state-funded schemes; WAMIS for works (pilot). | TN-SFS25 | 2026-11-04 | V (SNA-SPARSH go-live date [U]) |
| 6. Budget classification | HoA-level and DDO-level balance budget. Scheme master captures General, SC and ST components and gender allocation. Digit structure not found. | TN-SFS25 | 2026-11-04 | V (digits [U]) |
| 7. Languages | Tamil and English (the brand name "Kalanjiyam" is Tamil). Language of finance documents not checked. | — | 2026-11-04 | [U] |
| 8. Distinctive features | (a) **Global Beneficiary Master**: one beneficiary, one ID, validated on Aadhaar, PAN, TAN, GSTIN and bank account across payroll, pension, welfare, vendor and DBT; (b) apex-level centralised welfare billing (OAP, differently-abled schemes); (c) G2G utility auto-billing for about 1.15 lakh electricity connections; (d) OPPAS digital pension/GPF authorisation with AG; (e) mandatory RBI route for large receipts; (f) Number Statement auto-computed from payroll for budgeting. | TN-SFS25 | 2026-11-04 | V |

Candidate state-specific requirements
- **IFS-TN-001**: Beneficiary golden record (GBM). Every payee has a unique financial ID, de-duplicated horizontally (across schemes) and vertically (across geographies), validated against Aadhaar, PAN, GSTIN and bank.
- **IFS-TN-002**: Bulk or apex welfare bill type covering up to about 10^6 beneficiary lines, with exception alerts.
- **IFS-TN-003**: Works lifecycle: Work ID at proposal, activated on Administrative Sanction, yearly fund requirement linked to the budget head, and a dedicated capital-works bill type.
- **IFS-TN-004**: Receipt-channel rule engine, for example remittances of Rs 1 crore or more must go by RBI NEFT/RTGS. Thresholds are configurable per state.
- **IFS-TN-005**: Quarterly expenditure-control rules (percentage caps per quarter, with relaxation workflow).
- **IFS-TN-006**: Budget "Number Statement" auto-derived from the HR/payroll establishment.
- **IFS-TN-007**: Pension proposal e-workflow with AG authorisation (OPPAS), and biometric or face-authentication mustering with a configurable window.
- **IFS-TN-008**: Utility-biller integration that fetches and validates bills automatically and debits a centralised budget.
- **IFS-TN-009**: Scheme master tags (SC/ST component, gender-budget category) on every scheme head.

---

## Kerala (IN-KL)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system(s) & modules | **IFMS Kerala** (implemented 2017; full-fledged Feb 2017). Modules: e-Treasury (receipts), **Core TSB**, **BAMS** (budget allocation and monitoring), **BiMS** (bills; **BiMS 2.0 from 11.04.2024**, now also carrying claims from Sankhya, EMLI and DBT-AGRI), **PIMS** (pensioners), **CoreTIS** (Dec 2016), CRA e-Stamping, **ViMS** (vendors), **iAMS** (accounts), UMAS. Integrated applications: **SPARK** (HR/payroll), EMLI (works), Sankhya/LSG billing, PlanSpace, GAINPF, Jeevan Pramaan, LIC/SLI/PLI, PFMS. TSB Online since 07.02.2019. **e-TR5** electronic receipt replaced paper TR-5 (from about June 2022). | KL-IFMS; KL-AR24; KL-BILLS | 2026-11-04 | V (e-TR5 date [U], from secondary/news) |
| 2. Treasury structure | **Directorate of Treasuries**, headed by the Director of Treasuries, Thiruvananthapuram, under the Finance Department. 4 regions (HQ, Southern/Kottayam, Central/Thrissur, Northern/Kozhikode). **23 District Treasuries, 201 (banking) Sub-Treasuries, 12 Stamp Depots, 1 e-Treasury**. **About 47,500 DDOs: about 31,000 regular and 16,550 "virtual DDOs"** (LSGI decentralised-planning implementing officers). DDO code is 10 digits; DSC mandatory. | KL-AR24; https://cag.gov.in/ae/kerala/en/page-ae-kerala-treasury-inspection-updated ; KL-BILLS | 2026-11-04 | V (DDO count is from a 2020 doc) |
| 3. Financial rules | **Kerala Treasury Code (2016 edition)**, under review after IFMS. **Kerala Financial Code Vol I** (Art 239(4)(e), Rule 282(5)(d) PD accounts; Arts 120-162 stores purchase). **Stores Purchase Manual, Revised Edition 2013**, with 7 amendments by Dec 2022, Chapter 19 on GeM, and Para 8.27 MSME exemptions amended 2021 and 2023. Kerala Budget Manual and Kerala PWD Manual editions not checked. | KL-AR24; https://www.spd.kerala.gov.in/docs/pdf2019/Stores%20Purchase%20Manual%20FINAL.pdf ; https://document.kerala.gov.in/documents/circulars/circular0909202212:24:57.pdf ; https://www.spd.kerala.gov.in/docs/pdf2023/10_2023.pdf | 2026-11-04 | V (SPM via search result on official host); KBM/PWD [U] |
| 4. Bills, token, e-payment | Bill form prescribed per claim type under KTC. Generation channels: **BiMS** (general/contingent), **SPARK** (HR claims), **Sankhya** (all LSGI claims), **EMLI** (works). Standardised bill forms; FIFO processing; mandatory pre-registration of project, work, procurement or scheme before billing; third-party direct credit; **treasury token and bill book abolished**; no cash payment from the Consolidated Fund. Salary and pension paperless since May 2021; other bills being moved. Advances: 2,136 pending (Rs 39 crore, 1,166 DDOs). | KL-BILLS; KL-AR24 | 2026-11-04 | V |
| 5. Integrations | RBI **e-Kuber** (vendor and salary payments; automatic reconciliation of e-Treasury receipts); agency banks; AG VLC (electronic voucher-level data); PFMS (listed as integrated). Salary and pension credited to **TSB or bank accounts**. SNA/SNA-SPARSH adoption dates not found. | KL-AR24; KL-IFMS | 2026-11-04 | V (SNA dates [U]) |
| 6. Budget classification | HoA examples like 8782-102-96-01 and 8443-00-106. DT codes are 4-digit (e.g. 0101 Thiruvananthapuram, 0301 Kollam). Full digit structure not found. | KL-AR24 | 2026-11-04 | V partial; structure [U] |
| 7. Languages | Malayalam and English (CAG Kerala pages are bilingual). The government now styles itself "Government of Keralam" on the BiMS 2.0 portal title (from a search snippet). | https://cag.gov.in/ae/kerala/en/page-ae-kerala-treasury-inspection-updated ; https://bims.treasury.kerala.gov.in/ | 2026-11-04 | [U] for finance-document language policy |
| 8. Distinctive features | (a) **Treasury as a bank**: Treasury Savings Bank with SB, pensioner SB, fixed deposit, security and public accounts, open to officials, pensioners, institutions and the public; Core TSB with any-branch banking; TSB Online; e-Wallet planned; e-KYC with UIDAI planned. (b) **No PD-account model for schemes**: schemes run through **Plan (Scheme) Treasury Savings Bank accounts under 8031-102-99** (PSTSB, GO(P) 87/2018/Fin dt 10.06.2018 [U]). Only 77 legacy PD accounts remained (31.03.2024). (c) **Virtual DDOs** for LSGI decentralised planning; LSGI claims flow through Sankhya into BiMS. (d) Treasury also runs GIS/medisep-type insurance and NPS/CPS work. | KL-AR24; KL-BILLS; KL-IFMS | 2026-11-04 | V (PSTSB GO number [U]) |

Candidate state-specific requirements
- **IFS-KL-001**: Treasury-bank ledger. Support customer deposit accounts (SB, FD, pensioner SB, security deposits) held in the treasury, with interest, TDS on interest, KYC/e-KYC and online transfers, as a public-account sub-ledger.
- **IFS-KL-002**: Salary and pension credit target may be a treasury SB account or an external bank account.
- **IFS-KL-003**: Scheme funds parked in Plan TSB accounts (8031-102-99) instead of PD accounts. No withdrawal is allowed without a scheme-wise ledger balance.
- **IFS-KL-004**: "Virtual DDO" role type with limited scope (LSGI implementing officers) and claims routed from an external LSG accounting system (Sankhya).
- **IFS-KL-005**: Multi-source bill intake: general bills, HR (SPARK), LSGI (Sankhya) and works (EMLI) all feed one treasury bill queue processed FIFO.
- **IFS-KL-006**: Mandatory pre-registration of project, work, procurement or scheme before any related bill can be presented.
- **IFS-KL-007**: Tokenless e-bill flow. Bill identity is the electronic bill ID, with no physical token.
- **IFS-KL-008**: Electronic departmental receipt (e-TR5) issued against online remittances, mapped to the DDO's e-Treasury code.

---

## Andhra Pradesh (IN-AP)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system(s) & modules | **CFMS (Comprehensive Financial Management System)**, a **SAP-based ERP (SAP S/4HANA)** designed, built and run by **APCFSS** (AP Centre for Financial Systems & Services) for the Finance Department. Initiated by GO Ms 192 dt 10.07.2016 and GO Ms 203 dt 21.10.2016. **Receipts module from 01.03.2018, expenditure from 02.04.2018** (Phase I). Phase II: full HR and payroll, works management, expenditure sanctions, debt. Key process areas: Budget, Revenue, Expenditure, Debt & Investment, HR, Ways & Means, Accounts. ServiceNow helpdesk. | AP-CFMS; AP-ARR21 | 2026-11-04 | V |
| 2. Treasury structure | **Directorate of Treasuries and Accounts (DTA), Ibrahimpatnam, Vijayawada** (department carved out of Revenue on 1.4.1958). Two wings: Treasuries and Accounts (24 accounts branches serving 183 HoDs). As of 2020-21: **13 District Treasuries, 196 Sub-Treasuries** (incl. AP State Capital Region Treasury), PAO Ibrahimpatnam, PAO AP Bhavan New Delhi. All STs are banking. About 40,000 PD accounts. Counts likely changed after the 2022 district reorganisation (26 districts), but this was not checked. DDO count not found. | AP-ARR21 | 2026-11-04 | V (2020-21 figures); current counts [U] |
| 3. Financial rules | **AP Treasury Code** (TR 16 cited), **AP Financial Code** (Art 99 cited). **Draft AP Treasury Rules 2022** (published as a draft on the CFMS site; final notification not checked). Procurement: **G.O.Ms.No.129 Fin (PMU-PPP) dt 24.09.2019**, mandatory e-procurement of goods, works and services (cites G.O.Ms.No.20 IT&C dt 06.07.2004). AP Budget Manual and APSS/PWD codes not checked. | AP-ARR21; AP-TR22; https://assets-bg.gem.gov.in/resources/pdf/G.O.MS.No-129.pdf | 2026-11-04 | V (GO 129 seen as hosted on gem.gov.in, content from search summary); Budget Manual [U] |
| 4. Bills, token, e-payment | Paperless bills: DDOs submit with DSC, treasuries process them, and all payments go through e-Kuber. Draft TR 2022: the treasury returns a bill online with objections instead of disallowing it; seven-tier HoA required on each bill; separate bills per HoA; AG objection statements returned within a fortnight (TR 181); vouchers digital (TR 188). Citizen bill-status lookup on cfms.ap.gov.in. Bill-form list not captured. | AP-ARR21; AP-TR22 | 2026-11-04 | V |
| 5. Integrations | RBI e-Kuber (payments; receipt scrolls); **SBIMOPS and PayU** receipt gateways; GSTN; AG (SFTP data and 9 PDF reports). PFMS/SNA/SNA-SPARSH dates not found. | AP-CFMS; AP-ARR21; AP-TR22 | 2026-11-04 | V (SNA [U]) |
| 6. Budget classification | **Seven-tier head of account** (TR draft), e.g. PD HoA **8443-106-02-97-001-001** (Major-Minor-Sub-Major/Group-Sub-head-Detailed-Sub-detailed). BE 2026-27 GO captures Manifesto Schemes, CASP/CSS, EAP, NABARD/RIDF and State Development Schemes, plus SCSP, TSP (now "ST Component"), BC sub-plan, Minority Action Plan, and two-category gender budget. Charged/voted not captured. | AP-TR22; AP-ARR21; AP-BE27 | 2026-11-04 | V (exact segment digit widths [U]) |
| 7. Languages | Telugu and English. Urdu as an additional official language in certain districts. Not checked. | — | 2026-11-04 | [U] |
| 8. Distinctive features | (a) **Green Channel PD accounts** (G.O.Ms.No.99 Fin dt 14.08.2019): no treasury scrutiny and no Finance Department fund clearance regardless of amount; a bill authorised by the PD Administrator goes straight to e-Kuber. (b) PD accounts defined as paper or virtual bank accounts (same GO). (c) DTA is fund manager for Class IV GPF. (d) ITDA/Fifth-Schedule-specific treasury flows not found; ITDA portals exist (e.g. itdaseethampeta.ap.gov.in). (e) Comprehensive Budget Release Order (CBRO) for BE 2026-27 (from search snippet). | AP-TR22; AP-ARR21 | 2026-11-04 | V for (a)-(c); (d)-(e) [U] |

Candidate state-specific requirements
- **IFS-AP-001**: "Green Channel" bypass flag on a PD or deposit account. Bills authorised by the account administrator skip treasury audit and Finance Department ways-and-means clearance and are pushed directly to the payment rail. The flag must be audited and controlled by policy.
- **IFS-AP-002**: Ways-and-means or Finance Department clearance gate (a configurable queue of bills held for fund availability) applied before payment for non-green-channel bills.
- **IFS-AP-003**: Seven-tier HoA with configurable segment widths. Bills must not span major heads.
- **IFS-AP-004**: Objection-return loop. The treasury returns a bill online with structured objections, and the DDO resubmits. The system must not support outright "disallow" without return.
- **IFS-AP-005**: Adapter boundary for an SAP-based ERP core. Sangam must interoperate with (not replace) SAP S/4HANA CFMS via APIs and events.
- **IFS-AP-006**: Multiple receipt aggregators (SBIMOPS, PayU, RBI), each with its own scroll and reconciliation.
- **IFS-AP-007**: Budget tags for SCSP, STC, BC sub-plan, MAP, gender (100% / 30-99%), and scheme-source types (Manifesto, CASP, EAP, NABARD, SDS).

---

## Telangana (IN-TG)

(ISO 3166-2:IN code is **IN-TG**; older lists use IN-TS.)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system(s) & modules | **IFMIS (Integrated Financial Management and Information System)** replaced 13 siloed legacy applications. Modules: Expenditure (payroll, pensions, **PD accounts**, contingent bills, supplementary bills, e-Kuber); Revenue (cyber receipts, manual challans); Budget (annual preparation, **Budget Release Orders**, budget authorisation); Debt; AG accounts; **Work-ID based tracking of work bills**; mobile apps. Described as cloud-based. **HRMS integration is planned (future)**. Timeline: pilot at PAO Jun 2018; all Hyderabad PAOs Feb 2019; DWA Feb 2020; DTA full state Apr 2020; pensions Sep 2020; budget Feb 2021; accounts Apr 2021. AG: switch-over w.e.f. Apr 2019, receipts/expenditure/accounts fully live by Mar 2022. Portals: ifmis.telangana.gov.in, treasury.telangana.gov.in. | TG-SFS25; TG-ARR22 | 2026-11-04 | V |
| 2. Treasury structure | **Directorate of Treasuries and Accounts (DTA)**, Hyderabad, under the Finance Department. **33 District Treasuries, 27 Divisional Sub-Treasuries, 67 Sub-Treasuries**, PAO Hyderabad, Joint Director Pension Payment Office plus 10 Assistant PPOs, PAO Telangana Bhavan New Delhi; the State Legislature also renders accounts. All banking treasuries. **15,000+ DDOs, 200+ HoDs, 20,000+ PD accounts**, 3 lakh+ employees, 2.5 lakh+ pensioners, 110+ treasuries. Treasury site cites about 11,210 PD administrators and 124 treasuries. | TG-ARR22; TG-SFS25; https://treasury.telangana.gov.in/ | 2026-11-04 | V (treasury-site PD numbers from search snippet [U]) |
| 3. Financial rules | **Telangana Financial Code Vol I** (Art 3; Art 271(iii) PD accounts), Treasury Code Vol I (TR 16 / SR 86(a)), Account Code Vol II (Art 15). Codes inherited from AP and adapted. PD unspent-balance lapse per G.O. No.25 Fin (DCM) dt 30.03.2019. E-procurement GOs (GO Ms 2 dt 15.01.2003, GO Ms 48 dt 22.08.2003, of the undivided state) are hosted on eprocurement.telangana.gov.in. Current TG procurement mandate not checked. | TG-ARR22; https://www.eprocurement.telangana.gov.in/downloads/goPre/GO-MS-No-2-Dated-15-01-2003.pdf | 2026-11-04 | V for codes; e-proc GOs [U] (not opened) |
| 4. Bills, token, e-payment | Contingent, supplementary, salary, pension and work bills (Work-ID). 25 lakh+ bills and 29 lakh+ challans a year. All payments through RBI e-Kuber. AG flagged duplicate token numbers and asked for IFMIS validation. Contract and honorarium pay through IFMIS-DBT only, from Nov 2022 (from search snippet). | TG-SFS25; TG-ARR22 | 2026-11-04 | V (DBT-honorarium [U]) |
| 5. Integrations | RBI e-Kuber (real-time); PFMS; SGST via RBI scrolls accounted at DTO Ranga Reddy. SNA-SPARSH instructions dated 23.05.2024 and FIN MS 77 dt 22.05.2024 are on treasury.telangana.gov.in/pfms_docs, but the fetch failed with a TLS error. DBT: 2.6 crore+ beneficiary payments a year; **Rs 6,000+ crore paid in a single day under Runa Mafi (crop-loan waiver)**; about 60 lakh farmers paid a year. | TG-SFS25; TG-ARR22; https://treasury.telangana.gov.in/pfms_docs/Instructions_SPARSH_23.05.24.pdf | 2026-11-04 | V (SNA-SPARSH doc [U]) |
| 6. Budget classification | Detailed-head-wise receipt and payment data downloaded by AG from IFMIS into VLC. Digit structure assumed to follow the inherited AP 7-tier pattern (not checked). | TG-ARR22 | 2026-11-04 | V partial; structure [U] |
| 7. Languages | Telugu and English; Urdu as second official language. Not checked on finance documents. | — | 2026-11-04 | [U] |
| 8. Distinctive features | (a) Very large **PD-account estate** (20,000+) managed in IFMIS, with lapse rules; GP-to-municipality mergers needed PD account consolidation and blocked DDO codes. (b) Mass farmer DBT in a single day (Runa Mafi). (c) Separate PAO system for Hyderabad. (d) A green-channel equivalent was not found. (e) ITDA/Scheduled-Area flows not found. | TG-SFS25; TG-ARR22 | 2026-11-04 | V for (a)-(c); (d)-(e) [U] |

Candidate state-specific requirements
- **IFS-TG-001**: PD-account lifecycle at scale: creation, administrator logins, balance confirmation to AG, inoperative alerts (3 years) and auto-lapse per GO, and a no self-cheque rule.
- **IFS-TG-002**: DDO-code succession: transfer balances and history from a blocked DDO to its successor (for example on GP to ULB merger).
- **IFS-TG-003**: High-volume DBT burst capacity (millions of credits a day) with e-Kuber batching and failed-credit reprocessing.
- **IFS-TG-004**: Dual accounting units (District Treasuries vs Pay & Accounts Offices) with distinct workflows for the capital-city departments.
- **IFS-TG-005**: Unique token numbers per treasury per day enforced as a constraint.
- **IFS-TG-006**: Budget Release Order and budget authorisation stages separate from allocation (shared AP-lineage pattern).
- **IFS-TG-007**: Work-ID-based linkage of works bills to sanctioned works.

---

## Cross-state observations

1. **Two architectural lineages.** AP runs a COTS ERP (SAP S/4HANA, APCFSS). KA (TCS-built K2), TN (IFHRMS/Kalanjiyam), KL (NIC-built suite) and TG (in-house IFMIS) run custom or bespoke stacks. Sangam must integrate as a workflow/orchestration layer beside existing IFMS cores, not assume it replaces them.
2. **HRMS coupling varies.** HRMS is external in KA (separate HRMS) and KL (SPARK), integrated in TN (IFHRMS is HR plus finance) and AP (CFMS Phase II), and planned in TG. The payroll bill source must be a pluggable adapter.
3. **RBI e-Kuber is universal** for payments in all five states. TN additionally forces large receipts (Rs 1 crore or more) onto RBI NEFT/RTGS. Receipt aggregators differ: KA EPS, AP SBIMOPS/PayU, TN SBI/IOB/BoI/IB, KL e-Treasury gateway.
4. **SNA-SPARSH handling differs.** KA set up a dedicated CAU treasury. TN runs it inside IFHRMS for 50 SLS. TG has 2024 instructions (not verified). AP and KL dates were not found. CSS budgets need paired detailed heads for central and state share (KA).
5. **Deposit/PD models diverge sharply.** KA and TG have large PD-account estates with lapse rules. AP has PD accounts plus a "Green Channel" bypass. KL largely abolished PD for schemes in favour of Plan TSB accounts inside its treasury bank. The data model needs a generic "public-account sub-ledger" abstraction with configurable controls (bypass, lapse, interest, KYC).
6. **Head-of-account length is not uniform.** KA cites 15-digit and 12-digit codes; AP uses 7 tiers. Segment counts and widths must be configuration, not schema.
7. **Sanction and works linkage is converging.** KA (sanction-before-bill, e-MB), TN (Work ID activated on AS, capital-works bill type), TG (Work-ID tracking) and KL (mandatory pre-registration of works and schemes) all require a "pre-registered commitment" entity that bills reference.
8. **Tokenless and paperless trend.** KL abolished treasury tokens. KA, TN and AP have moved to digital vouchers for AG. Token numbers remain in KA and TG. Workflow IDs should be electronic bill IDs, with tokens as an optional state feature.
9. **Beneficiary masters.** TN's GBM (unique financial ID) and KA's bank-validated immutable payee point to a shared requirement: a validated, de-duplicated payee registry across payroll, pension, vendor and DBT.
10. **Local bodies.** KL is unique in having about 16,550 virtual DDOs and an LSG accounting system (Sankhya) feeding treasury bills. KA has treasury staff at Taluk Panchayats. TG merged GPs into ULBs. PRI/ULB fund flows need a separate DDO class and external-system bill intake.
11. **Gaps to follow up.** Language policy for finance documents (all five states) [U]. Bill-form numbers and token rules [U]. Current AP treasury counts after the 2022 reorganisation [U]. TG SNA-SPARSH GO (TLS-blocked) [U]. Scheduled-Area (ITDA) treasury exceptions in AP/TG [U]. Budget Manual, PWD Code and DFP rule editions for all five states [U].
