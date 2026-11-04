# Group F: Union Territories, finance and IFMS regimes

Researched 2026-11-04 for Sangam. Status key: **V** means confirmed on an official page (gov.in / nic.in / cag.gov.in / indiabudget.gov.in). **[U]** means not confirmed on an official page. That covers news-only facts, background knowledge, and pages that were blocked, scanned or unreachable.

Method notes:
- The WebSearch budget ran out partway through. DNH&DD, the Delhi IFMS project and parts of Puducherry and J&K are therefore thinner than planned, and more items there are marked [U].
- mha.gov.in returned 403 to the fetch tool. A plain curl of the alternate URL worked.
- jkdat.nic.in did not resolve (DNS failure), so the J&K revised Budget Manual (SO-334 of 28.10.2020) was not read: [U].
- The Union "Notes on Demands for Grants 2026-27" PDFs (sbe49-59) were downloaded and text-extracted. They are the strongest source for the demand structure.

Common source URLs used below:
- S-CAG18: https://cag.gov.in/uploads/download_audit_report/2018/Chapter_1_Introduction_of_Report_No_3_of_2018_-_Union_Territories_without_Legislatures_Compliance_Audit_Observations_Union_Government_0.pdf
- S-DFG(n): https://www.indiabudget.gov.in/doc/eb/sbe(n).pdf (Notes on Demands for Grants 2026-27, Demand No. n)
- S-PCCA: https://www.mha.gov.in/principal-chief-controller-accounts-org

---

## UT regimes: with vs without legislature

| Dimension | UTs without legislature (A&N, Chandigarh, DNH&DD, Ladakh, Lakshadweep) | UTs with legislature (Delhi, Puducherry, J&K) | Source | Status |
|---|---|---|---|---|
| Constitutional / fund status | No legislature, council of ministers or consolidated fund of their own. They "function under the authority of Parliament and the Government of India". Each is run by an Administrator under Art. 239. | Each has its own Consolidated Fund. Delhi's monthly and annual Appropriation and Finance Accounts are of the "Consolidated Fund of NCT of Delhi" and go to its Legislative Assembly. J&K's Appropriation Bill draws on the "consolidated fund of the UT of J&K". | S-CAG18; https://coa.delhi.gov.in/pao/about-us | V (J&K fund wording: news only [U]) |
| Who votes the budget | Parliament. MHA prepares the Demands for Grants and Detailed Demands for Grants (DDG) for Parliament's approval. Each UT has its own demand under MHA: No. 52 A&N, 53 Chandigarh, 54 DNH&DD, 55 Ladakh, 56 Lakshadweep (2026-27 numbering). | Own legislature votes its own budget. Parliament votes only the central transfers: Demand 57 "Transfers to Delhi", 58 "Transfers to Jammu and Kashmir", 59 "Transfers to Puducherry". J&K Assembly passed its 2025-26 Appropriation Bills (L.A. Bills 2 and 3 of 2025) and the LG assented. From 2019 to 2024, with no assembly, the J&K budget was passed by Parliament [U]. | S-CAG18; S-DFG(52..59); https://www.newsonair.gov.in/3-key-bills-passed-by-jk-assembly-get-lieutenant-governors-assent | V (J&K 2019-24 detail [U]) |
| Budget shape | The UT's whole budget sits inside a Union demand. It is split into "Centre's Expenditure": Establishment (Secretariat, Other Establishment) plus "Schemes of UT" by sector (3.01 ... 3.24). Gross, Recoveries and Net are shown. | Central transfers are lump-sum grants: Central Assistance to UTs, the UT Disaster Response Fund contribution, and externally aided projects. Each UT then frames its own state-style budget. | S-DFG(52-59) | V |
| Delegation | Administrators may sanction plan schemes up to ₹50 crore where a Governor or Lt Governor is the Administrator, and ₹25 crore in the other UTs. | Elected government plus LG / Finance Department. Delegation follows each UT's own book of financial powers (J&K: Book of Financial Powers, 3rd edn 2002, amended by GOs). | S-CAG18; https://www.jakfinance.nic.in/IMP_REFERENCE/BOOK%20OF%20FINANCIAL%20POWERS/Book%20of%20Financial%20Powers%20-%20Part1.pdf | V |
| Accounting organisation | **Not one model.** A&N, Lakshadweep and DNH&DD run their own departmentalised Pay & Accounts Offices that report to CGA through e-Lekha/PFMS. Chandigarh runs a "Central Treasury" under the UT Finance Department, audited and accounted by the Pr. AG (A&E) Punjab & UT Chandigarh. Ladakh runs PAO/Treasury Leh and Kargil, coordinated with the Principal Accounts Office (MHA). The MHA Pr.CCA's 48 PAOs do **not** include Port Blair, Kavaratti, Chandigarh or Silvassa, which disproves the assumption that the Pr.CCA MHA keeps all UT accounts. | Delhi: departmentalised PAO system since 01.04.1977 (25 PAOs, Principal Accounts Office). Puducherry: Directorate of Accounts & Treasuries (treasuries plus PAO functions). J&K: treasury system (TreasuryNet in 132 treasuries across J&K and Ladakh), with accounts compiled by the AG (A&E) [U]. | S-PCCA; A&N/LD/CH/LA sources below | V |
| Financial rules | Central rules: GFR 2017, Delegation of Financial Powers Rules (DFPR), Central Government Account (Receipts & Payments) Rules 2022, Civil Accounts Manual, LMMH. Chandigarh's Central Treasury follows the Central Treasury Rules. Chandigarh staff moved from Punjab service rules to central service conditions from 1 Apr 2022 [U]. CPWD Works Manual for works [U]: not seen on any page, though APWD/PWD divisions exist. | Delhi: R&P Rules, GFR 2017, Government Accounting Rules 1990, Civil Accounts Manual, DDO Manual. J&K: own J&K Financial Code Vol I/II, J&K Treasury Code, Book of Financial Powers and Budget Manual, with GFR 2017 binding for procurement "codal formalities" (GO 07-F of 2020). Puducherry: not confirmed [U]. | A&N CPAO PDF; LD PAO page; CAG Chandigarh review; coa.delhi; jakfinance compendium | V / [U] as marked |
| Audit | CAG audits the UTs. Findings appear in the Union Government report "UTs without Legislatures" (e.g. Report 3 of 2018, 307 units audited in 2016-17) and are laid before Parliament under Art. 151. | CAG audits them too. Accounts are laid before the UT legislature (Delhi accounts go to its Legislative Assembly after C&AG approval). | S-CAG18; coa.delhi about-us | V |
| PFMS position | PFMS is the core payment and accounting system (A&N from 1 Oct 2020 [U: date from news]; Lakshadweep is PFMS nodal; Ladakh is integrating LIFMS with PFMS). | Own IFMS (IFMIS Puducherry, Delhi e-Lekha/payroll and a planned new IFMS, J&K TreasuryNet/BEAMS/JKPaySys), interfaced with PFMS, SNA-SPARSH and RBI e-Kuber. | below | V |

Key takeaway: **the working split is three regimes, not two.** (a) PFMS-native departmentalised PAO UTs: A&N, Lakshadweep, DNH&DD. (b) Treasury-based UTs without a legislature that inherited a state system: Chandigarh (Punjab legacy, Central Treasury Rules) and Ladakh (J&K legacy: TreasuryNet, BEAMS clone LIFMS, moving to PFMS). (c) Legislature UTs with a state-like IFMS: Delhi, Puducherry, J&K.

---

## Delhi (NCT) (IN-DL)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS / systems | The Principal Accounts Office runs systems built with Delhi NIC: Pay Roll System (DDO bill generation, online LPC, e-payment file, GPF capture), GPF System, e-Lekha (Delhi's own, https://delekha.delhi.gov.in, consolidating PAO accounts and sharing CSS data with PFMS), SGST Reconciliation (since Jul 2017), and SNA-SPARSH. An "IFMS Delhi mobile app" circular and a DELEKHA user manual are published. A new Delhi IFMS of ₹261.66 cr was approved in Jan 2026 (news only). | https://coa.delhi.gov.in/hi/node/42718 ; https://coa.delhi.gov.in/pao/our-services | yes | V (new IFMS [U]) |
| 2. Treasury / PAO structure | Departmentalised accounts since 01.04.1977. The Principal Accounts Office heads 25 PAOs plus a GPF Cell and a Pension Cell. The Chief Secretary is Chief Accounting Authority, acting through the Finance Secretary. No treasuries are mentioned. The PAOs are the payment nodes. | https://coa.delhi.gov.in/pao/about-us ; https://coa.delhi.gov.in/hi/node/42683 | yes | V |
| 3. Financial rules | R&P Rules 1983, GFR 2017, Government Accounting Rules 1990, Civil Accounts Manual, Suspense Accounts Manual, DDO Manual, Scheme for Payment of Pensions, LMMH. These are central rules adopted for GNCTD. A Delhi DFPR, Delhi works manual (CPWD manual expected) and GeM mandate were not confirmed. | https://coa.delhi.gov.in/pao/our-services | yes | V (works/procurement [U]) |
| 4. Bill types / e-payment | DDOs generate "most types of bills" in the Pay Roll System, with an electronic payment file. The Delhi bill-form list was not found. DBT runs through PFMS. | https://coa.delhi.gov.in/hi/node/42718 | yes | V (forms [U]) |
| 5. Integrations | PFMS (CSS data from e-Lekha, DBT payments), SNA-SPARSH (with State Cyber Treasury, State IFMS, RBI e-Kuber), RBI/GSTN for SGST reconciliation. | https://coa.delhi.gov.in/hi/node/42718 ; https://coa.delhi.gov.in/pao/about-us | yes | V |
| 6. Budget classification | Own budget voted by the Delhi Legislative Assembly; LMMH heads. Union support comes through MHA Demand 57 "Transfers to Delhi": BE 2026-27 ₹1,348.01 cr (Central Assistance ₹951 cr, UTDRF ₹15 cr, EAP Chandrawal WTP ₹380 cr, 1984 riots compensation ₹2 cr). | S-DFG(57) | yes | V |
| 7. Languages | Hindi and English (Delhi PAO site has a Hindi /hi/ version). Urdu and Punjabi are additional official languages of Delhi [U]. | coa.delhi.gov.in/hi/ | yes | V (Hi/En); [U] others |
| 8. Distinctive | A PAO-only system with no treasury layer. Two "e-Lekha" systems coexist: Delhi's own delekha and CGA's national e-Lekha. Some PAOs (05, 22) were still feeding accounts manually into e-Lekha. A new IFMS procurement is in progress [U]. | https://coa.delhi.gov.in/pao/welcome-principal-accounts-office (snippet) | yes | V |

Candidate UT-specific requirements:
- IFS-DL-001: Model the payment node as a **PAO** (not a treasury) with a Principal Accounts Office consolidation tier. The org hierarchy must allow "PAO-only" deployments.
- IFS-DL-002: Support two parallel account-consolidation feeds: the UT's own consolidation (Delhi e-Lekha) and the CGA e-Lekha/PFMS CSS data share, with reconciliation.
- IFS-DL-003: Treat SGST receipt reconciliation (RBI vs GSTN) as a configurable receipt workflow.
- IFS-DL-004: Payroll-originated bills must carry an LPC transfer between DDOs and auto-capture GPF deductions.
- IFS-DL-005: Distinguish UT own-budget heads from "Transfers to Delhi" grant receipts (Central Assistance, UTDRF, EAP) in receipt classification.
- IFS-DL-006: Plan a migration/coexistence path for a new IFMS (₹261.66 cr project [U]) replacing the NIC payroll and e-Lekha stack.

## Puducherry (IN-PY)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS / systems | **IFMIS Puducherry** at https://ifmis.py.gov.in (DAT). Modules listed: PACT, PeBS, PyPension, TRAS, PYGST, PERMS; e-Pudhuvai (DAT Dashboard, PYUC, LFA Pension, PY FinAccts, PPSBMS); Budgetary Controls (GRAS, e-Salary, **BEAMS**, BEAT, IPR); SNA-SPARSH, RBI e-Kuber, External Integration Portal, NPCI. Module meanings are not described on the page. | https://ifmis.py.gov.in/ ; https://dat.py.gov.in/key-objectives | yes | V (names only) |
| 2. Treasury / PAO structure | Directorate of Accounts & Treasuries (DAT), headed by the Director, with 8 Deputy Directors and branch Deputy Directors at Karaikal, Mahe and Yanam. **3 treasuries** (Puducherry, Villianur, Karaikal) and **3 sub-treasuries** (Bahour, Mahe, Yanam). DAT pays for government departments, municipalities and commune panchayats. A DDO list exists, but no count is given. | https://py.gov.in/sites/default/files/citizencharter-accountstreasuries.pdf ; https://dat.py.gov.in/key-objectives | yes | V |
| 3. Financial rules | Not confirmed (Acts & Rules page not read). Central rules (GFR, CTR/R&P, DFPR) probably apply with Puducherry orders. | dat.py.gov.in | yes | [U] |
| 4. Bill types / e-payment | Bills honoured: Pay, Arrear, TA, Medical Reimbursement, Contingent, Grants-in-aid. The charter says payments are "by way of issuing cheques" (dated). IFMIS now lists RBI e-Kuber and NPCI. | citizen charter; ifmis.py.gov.in | yes | V |
| 5. Integrations | SNA-SPARSH, RBI e-Kuber, NPCI, PFMS (DAT page has a "GoI Scheme Code" sub-page), GRAS (e-receipts). | https://ifmis.py.gov.in/ ; https://dat.py.gov.in/key-objectives | yes | V |
| 6. Budget classification | Own legislature and budget (state-style LMMH). Union support via MHA Demand 59 "Transfers to Puducherry": BE 2026-27 ₹3,517.88 cr. | S-DFG(59) | yes | V |
| 7. Languages | Tamil (Puducherry, Karaikal), Malayalam (Mahe), Telugu (Yanam), English, French heritage [U]. Charter is in English. | citizen charter | yes | [U] |
| 8. Distinctive | Four non-contiguous regions inside three states (TN, Kerala, AP) with branch DAT offices. Pensions paid to **ex-French pensioners**, central pensioners, freedom fighters, defence and other states' pensioners through UT treasuries. DAT also pays local bodies (municipalities, commune panchayats). | citizen charter | yes | V |

Candidate UT-specific requirements:
- IFS-PY-001: Region dimension (Puducherry / Karaikal / Mahe / Yanam) on every bill, treasury and report, with branch-office approval routing.
- IFS-PY-002: Pension workflow must support multiple payer categories (UT, Central, ex-French government, freedom fighters, defence, other states) with distinct recoupment accounting.
- IFS-PY-003: Treasury can pay **on behalf of local bodies** (municipalities, commune panchayats). Needs a separate fund/ledger per local body.
- IFS-PY-004: Configurable UI and notice language per region (Tamil / Malayalam / Telugu / English) [U].
- IFS-PY-005: Connectors for IFMIS sub-modules (GRAS e-receipts, BEAMS budget control, e-Salary, SNA-SPARSH, e-Kuber, NPCI).

## Jammu & Kashmir (IN-JK)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS / systems | **BEAMS** (Budget Estimation, Allocation & Monitoring System, https://beamsjk.gov.in; login page also at beams.jk.gov.in): estimation, budget documents, allocation, expenditure authorisation, now including capex and CSS. **TreasuryNet**: workflow-based e-bill/challan processing "as per Treasury business code" (http://jakfinance.nic.in). **JKPaySys**: DDO bill preparation, 37 bill types, used by about 5,894-5,915 DDOs of 36-38 departments. **JKGRAS** e-receipts plus a Cyber Treasury. **GenProfit** (GPF). **CPIS**. Empowerment/Janbhagidari works-tracking portal [U: news]. | https://cdnbbsr.s3waas.gov.in/s3819e3d6c1381eac87c17617e5165f38c/uploads/2021/09/2021092297.pdf (NIC J&K project profile) ; https://beams.jk.gov.in/Pages/login.aspx | yes | V |
| 2. Treasury / PAO structure | TreasuryNet runs in **132 treasuries across J&K and Ladakh**. DDOs: about 5,915. The AG (A&E) role was not read [U]. | NIC profile | yes | V |
| 3. Financial rules | J&K Financial Code Vol I and II, J&K Treasury Code Vol I (amended by SRO-68 of 2017), Book of Financial Powers (3rd edn 2002, amended by many GOs), revised Budget Manual (SO-334 of 28.10.2020, not readable). GFR 2017 is binding for "codal formalities" in procurement (GO 07-F of 2020). | https://rgp.jk.gov.in/pdf/SRO/SRO-2017/68.pdf ; https://www.jakfinance.nic.in/IMP_REFERENCE/BOOK%20OF%20FINANCIAL%20POWERS/Book%20of%20Financial%20Powers%20-%20Part1.pdf ; https://www.jakfinance.nic.in/IMP_REFERENCE/COMPENDIUMS/2020/COMPENDIUM-2020.pdf | yes (via search snippets) | V (manual [U]) |
| 4. Bill types / e-payment | 37 bill types in JKPaySys. The DDO may only draw within the BEAMS allocation. Payments go through RBI e-Kuber. e-Challan through JKGRAS. | NIC profile | yes | V |
| 5. Integrations | JKPaySys is integrated with BEAMS, CPIS, GPF and TreasuryNet. Interfaces to RBI e-Kuber and GSTN API. MoU with J&K Bank for e-banking. e-procurement on GePNIC (https://jktenders.gov.in) for tenders of ₹1 lakh and above, with a separate GePNIC instance for Ladakh. PFMS integration "needs to be integrated" per the profile. SNA-SPARSH not confirmed [U]. | NIC profile | yes | V |
| 6. Budget classification | Own budget, Appropriation Bills in the J&K Legislative Assembly from 2025 (sections 41-44 of the J&K Reorganisation Act 2019 [U]). Union support via Demand 58: BE 2026-27 ₹43,290.29 cr, mostly "Central Assistance ... to meet its resource gap" ₹42,650.27 cr. | S-DFG(58) ; newsonair.gov.in | yes | V |
| 7. Languages | Official languages: Kashmiri, Dogri, Urdu, Hindi, English (J&K Official Languages Act 2020) [U]. Finance codes are in English; legacy records are in Urdu [U]. | none | n/a | [U] |
| 8. Distinctive | Own budget-control-first architecture (BEAMS allocation gates bills). Treasury system shared with Ladakh. Large DDO base. Grassroots works tracking with geo-tagged photos. Both PRIs and ULBs are funded through BEAMS [U]. | NIC profile | yes | V / [U] |

Candidate UT-specific requirements:
- IFS-JK-001: **Budget-allocation gate**: a bill workflow must check and consume an external BEAMS allocation (head, DDO, amount) before submission to the treasury.
- IFS-JK-002: Configurable bill catalogue (37 JKPaySys bill types) mapped to J&K Treasury Code forms, not central TR forms.
- IFS-JK-003: Rule-pack support for **own codes** (J&K Financial Code, Treasury Code, Book of Financial Powers) with GFR 2017 layered on for procurement. Effective-dated rule versions are needed (GO amendments).
- IFS-JK-004: Receipts via a JKGRAS / Cyber Treasury e-challan connector. Payments via an RBI e-Kuber connector, with GSTN TDS API.
- IFS-JK-005: Work-level geo-tagged progress and photo evidence linked to fund releases ("release funds through BEAMS before issuing work order").
- IFS-JK-006: Multilingual output (Urdu RTL, Hindi, Dogri, Kashmiri) [U].
- IFS-JK-007: Handle the regime switch over time (Parliament-voted budgets 2019-24, then Assembly-voted). Approval authorities must be configurable by period.

## Ladakh (IN-LA)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS / systems | **LIFMS**, Ladakh Integrated Financial Management System (https://lifms.ladakh.gov.in; About page titled "BeamsLadakh", so it is a BEAMS-derived budgeting tool for budget preparation, authorisation and real-time expenditure monitoring). Upgraded July 2024. LIFMS-PFMS integration reviewed on 25 Nov 2025 with Principal Accounts Office (MHA) and the PFMS team. Also PFMS, plus legacy J&K TreasuryNet. | https://ladakh.gov.in/?p=51180 ; https://ladakh.gov.in/department-of-finance-ut-ladakh-through-its-lifms-section-conducted-a-comprehensive-hands-on-training-session-on-the-newly-upgraded-ladakh-integrated-financial-management-system-lifms-on-july-18 ; https://lifms.ladakh.gov.in/ (TLS cert error on fetch) | yes | V |
| 2. Treasury / PAO structure | Directorate of Accounts & Treasuries, UT Ladakh. Pay & Accounts Officers at **Leh** and **Kargil**, also called "Treasury Officers/PAO". Fund Office Kargil (LAHDC). Treasuries are within the 132 J&K/Ladakh TreasuryNet treasuries. DDOs are mapped to PAO Leh/Kargil and CDDOs (PFMS). | https://ladakh.gov.in/finance-department-2/ ; https://ladakh.gov.in/?p=51180 ; NIC J&K profile | yes | V |
| 3. Financial rules | The Finance Department interprets **GFR and DFPRs** for Ladakh and consolidates accounts "as prescribed by the CGA". Pensions go to CPAO. Whether J&K codes still apply residually is unclear [U]. | https://ladakh.gov.in/finance-department-2/ | yes | V |
| 4. Bill types / e-payment | PFMS e-payment for DDOs mapped to PAOs (training covered pensions, EIS and banking modules) [U: news]. TDS under CGST s.51 circular (14/10/2026). | ladakh.gov.in finance page | yes | V / [U] |
| 5. Integrations | PFMS (in progress), Principal Accounts Office MHA, CPAO, separate GePNIC e-procurement instance. | ladakh.gov.in/?p=51180 ; NIC J&K profile | yes | V |
| 6. Budget classification | Voted by Parliament under MHA **Demand 55 (Ladakh)**: BE 2026-27 net ₹4,869.31 cr (Rev ₹2,542 cr, Cap ₹2,327 cr; capital share unusually high). Public Works covers UT Administration **and LAHDC** works. LAHDC Fund under Head 8448 (councillors seek non-lapsable status) [U: news]. | S-DFG(55) | yes | V (8448 [U]) |
| 7. Languages | English and Hindi official. Ladakhi (Bhoti) and Purgi/Balti, plus Urdu in Kargil records [U]. | none | n/a | [U] |
| 8. Distinctive | Two **LAHDCs** (Leh, Kargil) under the LAHDC Act 1997 with their own Council Fund, a Chief Controller of Finance LAHDC Kargil, and a Fund Office. Reported capital budget ₹344 cr per council. Leh council's term ended Oct 2025, with powers vested in the DC [U: news]. Five new districts placed under existing councils [U]. Hybrid regime: Parliament-voted, but inherited J&K treasury/BEAMS tools, migrating to PFMS. | ladakh.gov.in/finance-department-2/ ; news | yes | V / [U] |

Candidate UT-specific requirements:
- IFS-LA-001: Support **dual-rail operation** during migration (LIFMS/TreasuryNet and PFMS), with a per-DDO switch-over date and reconciliation.
- IFS-LA-002: Model **Hill Council funds** (LAHDC Leh/Kargil) as autonomous sub-entities with their own Council Fund, Fund Office, Chief Controller of Finance, and possible non-lapsable carry-forward.
- IFS-LA-003: Configurable delegation where council powers may be temporarily vested in the Deputy Commissioner (term expiry).
- IFS-LA-004: Parliament-voted demand structure (Demand 55) with LAHDC works inside the UT's Public Works scheme head. Allocation must flow UT, then Council, then DDO.
- IFS-LA-005: Seasonal / high-altitude working calendar, with work-season-aware expenditure phasing for capital works [U].

## Chandigarh (IN-CH)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS / systems | PFMS is used (CAG found multiple beneficiaries per account number in PFMS ECS transactions at the UT). A Chandigarh-specific treasury software name was not found. The CAG review refers to "IFMS" for Punjab (NIC Punjab), and it is unclear whether CTO Chandigarh uses it [U]. | https://cag.gov.in/uploads/ae_state_accounts/Annual-review-2021-22-065b74be9940c23-43727362.pdf | yes | V (PFMS); [U] other |
| 2. Treasury / PAO structure | **Central Treasury Chandigarh** under the administrative control of the UT Finance Department. Accounts are rendered to and inspected by the Pr. AG (A&E) Punjab & UT Chandigarh. No PAOs found. | same | yes | V |
| 3. Financial rules | Central Treasury Rules (the inspection criterion for the Central Treasury). Punjab Financial / Civil Service Rules were historically relevant, and the CAG cited non-implementation of the Punjab Pay Commission. UT Chandigarh Employees (Conditions of Service) Rules 2022 apply central service conditions from 1 Apr 2022 [U: news]. GFR 2017 probably applies [U]. | CAG review; S-CAG18 | yes | V / [U] |
| 4. Bill types / e-payment | CTR forms are expected [U]. ECS payments via PFMS (V). | CAG review | yes | V / [U] |
| 5. Integrations | PFMS (V). RBI e-Kuber noted for Punjab treasuries. Whether it covers CTO Chandigarh is [U]. | CAG review | yes | V / [U] |
| 6. Budget classification | Voted by Parliament under MHA **Demand 53**: BE 2026-27 net ₹5,720.17 cr. Includes grant-in-aid to the Municipal Corporation, aided schools/colleges and Punjab Engineering College. Own tax revenue is significant (₹2,192 cr tax in 2016-17, mainly sales tax). | S-DFG(53) ; S-CAG18 | yes | V |
| 7. Languages | English and Hindi; Punjabi in use [U]. | none | n/a | [U] |
| 8. Distinctive | Governor of Punjab is the Administrator. Joint capital of Punjab and Haryana, with the Punjab rule legacy for staff (deputationists from both states). Parliament-voted, yet treasury-based, unlike the PAO-based island UTs. | S-CAG18 | yes | V |

Candidate UT-specific requirements:
- IFS-CH-001: **Treasury-based** model for a Parliament-funded UT. Accounts are rendered to an AG (A&E), not to CGA PAO consolidation. The account-rendering target must be configurable (AG vs CGA).
- IFS-CH-002: Rule-pack switching by employee origin and date: Punjab/Haryana deputationists vs UT cadre on central conditions from 1 Apr 2022 [U].
- IFS-CH-003: PFMS beneficiary de-duplication (account-number to multiple-name checks) as a pre-payment validation.
- IFS-CH-004: Grant-in-aid workflow to a Municipal Corporation and aided institutions, with utilisation certificates.

## Dadra & Nagar Haveli and Daman & Diu (IN-DH)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS / systems | Not confirmed. Probably PFMS / e-Lekha like other PAO-based UTs. | n/a (search budget exhausted) | n/a | [U] |
| 2. Treasury / PAO structure | Directorate of Accounts / Pay & Accounts Office, "Lekha Bhawan", Amli, **Silvassa**. Directorate of Accounts, Moti **Daman** (seen in address lists of central circulars on cag.gov.in). Counts not found. | cag.gov.in media circulars (search snippets) | snippet only | [U] |
| 3. Financial rules | Central GFR / R&P / DFPR expected | none | n/a | [U] |
| 4. Bill types / e-payment | Not found | none | n/a | [U] |
| 5. Integrations | Not found | none | n/a | [U] |
| 6. Budget classification | Voted by Parliament under MHA **Demand 54 (DNH&DD)**: BE 2026-27 net ₹2,832.70 cr. Explicit lines for grants to District and Village Panchayats (₹316.30 cr BE 26-27) and Municipal Councils (₹222 cr). | S-DFG(54) | yes | V |
| 7. Languages | Gujarati, Hindi, English; Marathi in DNH; Portuguese heritage in Daman & Diu [U]. | none | n/a | [U] |
| 8. Distinctive | Merged UT from 26 Jan 2020 (DNH and DD Merger Act 2019) [U]. Two pre-merger accounts establishments (Silvassa, Daman) still visible. Three non-contiguous districts (Dadra & Nagar Haveli, Daman, Diu). In 2016-17 power was the dominant non-tax revenue for both former UTs (V, S-CAG18). | S-CAG18 | yes | V / [U] |

Candidate UT-specific requirements:
- IFS-DH-001: Support **entity merger**: combine two accounting units (former DNH and DD PAOs) with historical data and code mapping preserved, and effective-dated org restructuring.
- IFS-DH-002: Non-contiguous district dimension (DNH / Daman / Diu) for approvals and reporting.
- IFS-DH-003: Grant release workflows to District/Village Panchayats and Municipal Councils as separate grant-in-aid streams.
- IFS-DH-004: Gujarati plus Hindi/English language packs [U].

## Lakshadweep (IN-LD)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS / systems | **e-Lekha / PFMS**: Daily Accounts Abstract and monthly accounts go to CGA. PFMS modules EIS, DBT, EAT. PAO roles include State Scheme Manager, Agency Approver L2 and Finance Department user. NSDL portal for NPS. | https://lakshadweep.gov.in/departments-new/pay-and-accounts-office/ | yes | V |
| 2. Treasury / PAO structure | **Principal Pay & Accounts Office, Kavaratti** under UTLA, "guided by" CGA. Units: JAO Compilation, JAO Field Pay Unit, PAO, Pension & GPF section. No sub-PAOs on other islands are listed. | same | yes | V |
| 3. Financial rules | Receipt & Payment Rules, Civil Accounts Manual, GFR, FR, TA Rules, GPF Rules, CCS (Pension) Rules, Medical Attendance Rules. | same | yes | V |
| 4. Bill types / e-payment | Pre-check / post-check of bills, personal claims and contingent bills via PFMS. Field Pay Unit. Exact forms not listed. | same | yes | V (forms [U]) |
| 5. Integrations | PFMS (nodal agency in the UT), e-Lekha, NSDL CRA. | same | yes | V |
| 6. Budget classification | Voted by Parliament under MHA **Demand 56**: BE 2026-27 net ₹1,682.35 cr. A large head for ports, ship-to-shore, ship acquisition and maintenance, airports/helipads and helicopter service. Panchayati Raj line about ₹8 cr. | S-DFG(56) | yes | V |
| 7. Languages | English and Hindi (site). Malayalam and Jeseri (Mahl on Minicoy) are spoken locally [U]. | lakshadweep.gov.in | yes | V (En/Hi); [U] |
| 8. Distinctive | Island logistics dominate spending. In 2016-17 savings came from "running and maintenance of ships" and delayed ship acquisition, and shipping is about 35% of non-tax revenue. The PAO also advises on procurement and sits on tender and condemnation committees. | S-CAG18 ; S-DFG(56) ; PAO page | yes | V |

Candidate UT-specific requirements:
- IFS-LD-001: **Offline / low-connectivity** bill capture on outer islands, with store-and-forward to the Kavaratti PAO [U: need inferred from island geography].
- IFS-LD-002: Field Pay Unit workflow (pay processing for remote units) separate from the main pre-check queue.
- IFS-LD-003: Shipping / vessel operating expenditure and ship-ticket receipts as first-class workflow types (contracts, maintenance, ticket revenue).
- IFS-LD-004: PAO participation as a mandatory member in procurement, tender evaluation and condemnation committees.
- IFS-LD-005: Malayalam language pack [U].

## Andaman & Nicobar Islands (IN-AN)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS / systems | **PFMS**. The PAO approves Budget and Letters of Credit (LC) at PAO level in PFMS, manages DSC and user profiles for CDDO / DDO Checker / PD Checker, and processes GPF and advance bills. PAO Code **071361** covers Forest and APWD divisions. PFMS second-level approver for agencies. PFMS go-live 1 Oct 2020 and e-Bill/e-Claim training [U: news]. | https://andamannicobar.gov.in/pdf/CPAO_Suo%20Motu.pdf | yes | V |
| 2. Treasury / PAO structure | Accounts were taken over from AG (Central) Calcutta on **01-06-1980**. District treasury Port Blair and sub-treasuries Rangat and Car Nicobar were converted to PAOs. Now there is a **Chief Pay & Accounts Office**, **3 PAOs** (Sri Vijaya Puram, Rangat, Car Nicobar) and **5 Sub-PAOs** (Diglipur, Mayabunder, Hut Bay, Nancowry/Kamorta, Campbell Bay), plus an Internal Audit wing. The Director of Accounts & Budget also functions as Principal Accounts Officer. | same | yes | V |
| 3. Financial rules | LMMH, **Central Government Account (Receipts & Payments) Rules 2022**, GFR 2017, DFPR, DDO Manual, Civil Accounts Manual. CPWD manual for APWD [U]. | same | yes | V |
| 4. Bill types / e-payment | Pay & allowances, contingencies, GPF (incl. final), pensions. Post-check of PWD and Forest divisional accounts (cheque-drawing DDOs). Remittances by cheque or draft to other accounts officers. | same | yes | V |
| 5. Integrations | PFMS, NSDL (NPS). Audits grantee bodies (Gram Panchayats, Municipal Council, Zilla Parishad). Bank / e-Kuber not stated. | same | yes | V (bank [U]) |
| 6. Budget classification | Voted by Parliament under MHA **Demand 52**: BE 2026-27 net ₹6,680.94 cr (gross ₹6,735.94 cr, recoveries ₹55 cr). Scheme heads include grants to ZP/PS/GP and the Port Blair Municipal Council, Ports & Lighthouses, and Smart City. | S-DFG(52) | yes | V |
| 7. Languages | English and Hindi (CPAO doc uses Hindi fonts Mangal/Krishna). Bengali, Tamil, Telugu and Nicobarese in local use [U]. | CPAO PDF (font metadata) | yes | V (Hi/En); [U] |
| 8. Distinctive | Largest island PAO network: sub-PAOs on remote islands down to Campbell Bay. Shipping and ship-ticketing (STARS upgrade) and HF radio-telephony are budget items. Port Blair renamed Sri Vijaya Puram. Lt Governor is Administrator (₹50 cr delegation). | CPAO PDF ; S-CAG18 | yes | V |

Candidate UT-specific requirements:
- IFS-AN-001: Multi-tier accounts hierarchy (Chief PAO, then PAO, then Sub-PAO, then CDDO/DDO) with PFMS PAO codes as master data.
- IFS-AN-002: Letter of Credit (LC) workflow for cheque-drawing divisions (APWD, Forest) plus post-check of divisional accounts and vouchers.
- IFS-AN-003: Remote sub-PAO operation with intermittent connectivity and ship-borne physical document movement. Timers and SLAs must be pausable or configurable per location [U: inferred].
- IFS-AN-004: Grant-in-aid to three-tier PRIs and a Municipal Council, with an internal audit plan linked to the grantees.
- IFS-AN-005: Rule-pack must reference **R&P Rules 2022** (not 1983) for PFMS-native UTs, while Delhi's site still cites 1983. Rule versions must be per-tenant.

---

## Cross-UT observations

1. **Three operating regimes, not two.** Funding source (Parliament vs own legislature) does not predict the accounting machinery:
   - PFMS-native departmentalised PAO UTs: A&N, Lakshadweep, DNH&DD [U].
   - Parliament-funded but treasury-based UTs inheriting state systems: Chandigarh (Punjab legacy, Central Treasury Rules, AG (A&E) Punjab) and Ladakh (J&K TreasuryNet/BEAMS legacy, LIFMS, migrating to PFMS).
   - Legislature UTs with their own IFMS: Delhi (PAO-based, own e-Lekha/payroll, new IFMS planned), Puducherry (treasury-based IFMIS), J&K (TreasuryNet, BEAMS, JKPaySys, own codes).

   Sangam needs a tenant-level choice of payment node type (Treasury / PAO / Sub-PAO / CDDO) and of the account-rendering target (CGA e-Lekha vs AG (A&E)).
2. **The MHA Pr.CCA does not keep the accounts of UTs without legislature.** Its 48 PAOs serve MHA/CAPF. UT PAOs report directly to CGA (A&N, Lakshadweep), and Ladakh coordinates with the "Principal Accounts Office (MHA)". The initial hypothesis is wrong for A&N, Lakshadweep and Chandigarh.
3. **Budget structure:** UTs without a legislature are *a single Union demand each* (52-56), with "Establishment" plus "Schemes of UT" sub-heads, and gross/recoveries/net, on the Union LMMH. UTs with a legislature appear in the Union budget only as lump-sum "Transfers to ..." (57-59). Sangam's budget model must support a UT-as-sub-demand (sanction from MHA, re-appropriation limits, ₹50 cr / ₹25 cr Administrator delegation) and a UT-as-state budget.
4. **The BEAMS pattern recurs** in J&K (BEAMS), Ladakh (LIFMS, "BeamsLadakh") and Puducherry (a BEAMS module in IFMIS). An allocation-before-expenditure gate is a cross-UT pattern worth a generic connector.
5. **Common rails:** PFMS (all), SNA-SPARSH (Delhi, Puducherry; others [U]), RBI e-Kuber (J&K, Puducherry, Delhi via SNA-SPARSH), NSDL NPS (A&N, Lakshadweep), GePNIC e-procurement (J&K, Ladakh), GRAS-type e-receipts (J&K, Puducherry).
6. **Rule versions differ:** A&N cites the Central Government Account (R&P) Rules 2022, while Delhi's PAO site lists the R&P Rules 1983. J&K keeps its own Financial and Treasury Codes with GFR 2017 layered on. Rule packs must be effective-dated and per tenant.
7. **Local-body flows are explicit in Union demands:** grants to PRIs/ULBs in DNH&DD (₹316 cr panchayats, ₹222 cr municipal councils), A&N (ZP/PS/GP, Port Blair MC), Chandigarh (Municipal Corporation), Ladakh (LAHDC works), and Puducherry (DAT pays municipalities and commune panchayats). Grant-in-aid with utilisation certificates is core, not optional.
8. **Geography:** island UTs (A&N with 5 remote sub-PAOs; Lakshadweep) and multi-enclave UTs (Puducherry 4 regions; DNH&DD 3 districts) need a location dimension, offline tolerance and location-specific SLAs.
9. **Gaps to close in a follow-up** (search budget exhausted):
   - DNH&DD systems and rules.
   - The official Delhi IFMS project notice.
   - Puducherry's financial rules and DDO count.
   - J&K Budget Manual SO-334 (jkdat.nic.in was unreachable).
   - Whether Chandigarh's Central Treasury uses NIC Punjab IFMS.
   - Confirmation of CPWD manual applicability in A&N, Lakshadweep, Chandigarh and DNH&DD.
   - Official-language notifications for each UT.
