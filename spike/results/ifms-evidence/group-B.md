# IFMS research - Group B (UP, Uttarakhand, Bihar, Jharkhand, Punjab, Haryana, Himachal Pradesh)

Accessed: 2026-11-04 for all rows. Status: V = verified on an official page or PDF (*.gov.in / *.nic.in / cag.gov.in / saiindia.gov.in / doe.gov.in); [U] = unverified (secondary lead, inference, or the official source could not be read).

Method notes:
- The CAG PDFs were downloaded from cag.gov.in (which redirects to the CAG CloudFront CDN) and converted to text locally. Quotes are kept short.
- Several official sites could not be read: cfms.bihar.gov.in (DNS failure), koshvani.up.nic.in (timeout), informaticsweb.nic.in (connection refused or timeout), saiindia.gov.in (timeout; the same file was then fetched from cag.gov.in), and budget.up.nic.in (TLS certificate rejected by WebFetch; read with a certificate-check bypass).
- The shared web-search budget ran out before the last Bihar query (CFMS go-live date and module list), so some Bihar rows stay [U].

Cross-cutting central source used for every state's SNA-SPARSH row:
- DoE OM F.No.1(27)/PFMS/2020 dated 10-06-2025 (https://doe.gov.in/files/circulars_document/OM_dated_10062025_to_all_secys.pdf). It says 29 CSSs were already notified for SNA-SPARSH in all States/UTs with legislature, 37 more CSSs are added from 01-07-2025, and **all CSSs move to SNA-SPARSH from 01-11-2025**. It refers back to the DoE JIT guidelines of 13-07-2023. (V)

---

## Uttar Pradesh (UP)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name / URL / modules | IFMS UP, developed with NIC and run by the Directorate of Treasuries. The FRS defines **8 applications** (DDO portal, Budget Management System, **Rajkosh** for online receipts, E-Pension system, E-Kuber File Monitoring System, GST Monitoring System, Kosh MPR, Treasury (CTS)) and **22 modules**, of which 16 were live in Dec 2024. Live: listing and uniform coding, Budget, Sanction/Allotment, e-Billing (DDO portal), Treasury Bill Passing (CTS), e-Payment (RBI e-Kuber), Online Receipt (Rajkosh), Treasury, GPF/Loan schedules, Works and Forest, Monthly Accounts, Reconciliation, Deposit Accounts, MIS, Data Storage, **Koshvani** (MIS portal). Inoperative: Debt Management, Long-Term Advance, Inter-State Transaction, GPF module, AG Authorisation, Audit. Treasury computerisation began in 1994-95. | https://cag.gov.in/uploads/download_audit_report/2025/(E)-Chapter-II-06a7587406d06c6.32378909.pdf | yes | V |
| | koshvani.up.nic.in as the public MIS URL (from a secondary lead; the site timed out) | http://koshvani.up.nic.in | yes | [U] |
| 2. Treasury structure | **Directorate of Treasuries, UP** (CAG report). **82 treasuries**: 75 at division/district level and 7 at government/headquarters level. No sub-treasury or DDO count was found. | same CAG UP Ch-II | yes | V (counts) / [U] (sub-treasuries, DDOs) |
| 3. Financial rules | **Financial Handbook (FHB)**. Vol V (Account Rules) Part I, 4th edn (preface dated 1979; correction slips to 1999; printed 2000). Vol V Part II covers treasury procedure. **Treasury Rules (UP) are Appendix II of FHB Vol V Pt I**. Vol VII covers Forest Account Rules (reprint 1960). Vol II (FR/SR) is hosted at the same site. The CAG criteria list UP Treasury Rules, the **UP Budget Manual** and the FHB. **UP Procurement Manual (Procurement of Goods), 1st edn 2016**, issued by the MSME and Export Promotion Dept and in force from 01-04-2016; it replaced the UP Store Purchase Rules. Delegation of financial powers is held to be FHB Vol I, and works accounts FHB Vol VI; neither was confirmed. | https://budget.up.nic.in/Fin_H_Book/volume5/part1/index.html ; https://budget.up.nic.in/Fin_H_Book/volume7/index.html ; https://msme.up.gov.in/doc/Policies/ProcurementMannualGOUP1.pdf | yes | V (Vol V, VII, Budget Manual, Procurement Manual 2016) / [U] (Vol I, VI subjects) |
| 4. Bill types / token / e-payment | DDOs prepare, validate and forward **digitally signed bills** in the e-Billing (DDO portal) module, which also **generates token numbers**. The CTS passes the bills. e-Payment goes through RBI e-Kuber, including failed-transaction handling. The GPF/Loan schedules and Works and Forest modules are live. No list of named bill forms was found. | CAG UP Ch-II | yes | V (flow) / [U] (form list) |
| 5. Integrations | RBI e-Kuber (e-Payment); GST monitoring; AG monthly accounts. SNA-SPARSH is mandatory for all CSS from 01-11-2025 (DoE OM). No UP-specific SNA-SPARSH go-live date or DBT portal URL was found. | CAG UP Ch-II ; DoE OM 10-06-2025 | yes | V / [U] (UP DBT portal) |
| 6. Budget classification | Budget control and accounts go **up to the standard Object Head level**. The CAG notes a **15-digit classification** of budget heads (used in the XML budget file sent to the Legislature). | CAG UP Ch-II | yes | V |
| 7. Languages | The Finance Department budget portal is bilingual (Hindi/English). The FHB is in English. | https://budget.up.nic.in/ | yes | V |
| 8. Distinctive features | Seven HQ/government-level treasuries alongside 75 district/division treasuries. Rajkosh is a separate receipt application. The CAG found no formal IT policy, BCP/DR plan or RPO/RTO, which matters for a hosting SLA. A December 2024 report (secondary lead) says UP fully adopted GeM for procurement. | CAG UP Ch-II | yes | V / [U] (GeM) |

Candidate state-specific requirements
- IFS-UP-001: Model a treasury hierarchy with **two tiers of treasuries**: division/district treasuries and government/HQ (Lucknow) treasuries. Do not assume one treasury per district.
- IFS-UP-002: Budget head master must support **15-digit classification** down to the standard Object Head, with XML export of the budget for the Legislature.
- IFS-UP-003: Bill submission must give a **treasury token number** when a digitally signed bill moves from the DDO portal to the treasury (CTS). Token number is a configurable sequence per treasury.
- IFS-UP-004: Receipts module must allow an external receipt application (Rajkosh) as the system of record, with a challan-to-head reconciliation feed.
- IFS-UP-005: Rule-reference library must cite FHB volume/part/paragraph (e.g. FHB Vol V Pt I para / Treasury Rule n of Appendix II) on validation messages and objection memos.
- IFS-UP-006: Procurement workflows must be configurable to the UP Procurement Manual 2016 (goods), with an optional mandatory-GeM routing rule.
- IFS-UP-007: e-Payment must handle e-Kuber return/failed transactions with re-issue workflow.

---

## Uttarakhand (UK)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name / URL / modules | **IFMS Uttarakhand** (ifms.uk.gov.in). Modules: e-DDO, e-Pension, **e-HRMS**, e-Society, **SNA Sparsh**, **e-Receipt / UKosh** (challans), stakeholder interfaces (AG, RBI, Bank, **State DBT**, account reconciliation), dashboards, citizen services (UKosh query, Digital Life Certificate), IFMS Care helpdesk. IFMS went live in all departments from **01-04-2019** (vendor contract 22-03-2019 with Indus Web Solution, per CAG). The DTPE site is ekosh.uk.gov.in. | https://ifms.uk.gov.in/IFMSaboutUs.aspx ; https://ekosh.uk.gov.in/about-department/introduction/ | yes | V (modules) / [U] (go-live date and vendor come from the CAG report seen only as a search snippet) |
| 2. Treasury structure | **Directorate of Treasuries, Pension and Entitlements, Uttarakhand** (DTPE; निदेशालय कोषागार, पेंशन एवं हकदारी), Dehradun. Set up 09-11-2000. **13 district treasuries, 70 sub-treasuries, 5 upgraded sub-treasuries, 2 PAOs**, plus a Finance Data Center. **4,200+ DDOs, 105+ HoDs, 67+ departments**. | https://ekosh.uk.gov.in/about-department/introduction/ ; https://ifms.uk.gov.in/IFMSaboutUs.aspx | yes | V |
| 3. Financial rules | **Uttarakhand Financial Handbook Part-1 (Delegation of Financial Power) 2018**, amended 23-12-2019 and 25-03-2022. **Budget Manual Uttarakhand 2012**. **Uttarakhand Treasury Rules Manual (Amended) 2015**. **Uttarakhand Procurement Rules 2017**, amended 2021 (06-01-2021), with a procurement grievance portal (2022). GPF Rules 2017 (amendment). Uttarakhand Retirement Benefits Act 2018 (amended 2018). Audit Manual (2025). | https://ekosh.uk.gov.in/document-category/rules-regulations ; https://ekosh.uk.gov.in/document-category/acts | yes | V |
| 4. Bill types / token / e-payment | DDOs prepare online bills, e-cheques and online pension papers. A GO of 15-02-2023 makes **e-sign/DSC mandatory** for all transactions under IFMS. All receipts moved to **e-challan instead of manual challan** (GO 31-12-2020). | https://ekosh.uk.gov.in/document-category/ifms-gos | yes | V |
| 5. Integrations | RBI and banks integrated. A State DBT interface and a **SNA Sparsh module** exist on the portal. HRMS was rolled out by GOs of 14-12-2020 and 05-06-2023, and e-Service Book increment entries are required by a GO of 17-07-2026. PFMS is not named on the about page. | https://ifms.uk.gov.in/IFMSaboutUs.aspx ; ekosh IFMS GOs | yes | V / [U] (PFMS link) |
| 6. Budget classification | No state-specific digit structure was found on official pages. | - | yes | [U] |
| 7. Languages | Portal and GOs are bilingual (Hindi/English); GO titles appear in both. | ekosh.uk.gov.in | yes | V |
| 8. Distinctive features | **CCL/DCL (cheque credit limit / deposit credit limit) system** for works departments. Applicable since 01-04-1974, it was re-specified for accounting after IFMS (GO No.129 of 29-03-2019 and GO of 05-09-2019). The directorate is the nodal agency for pension disbursement and e-Jeevan Pramaan. e-Society registration sits inside IFMS. Hill-area specific treasury rules were not found. | ekosh IFMS GOs | yes | V / [U] (hill rules) |

Candidate state-specific requirements
- IFS-UK-001: Support a **CCL/DCL (cheque/deposit credit limit) mechanism** for works divisions. Limits are allotted to divisions, cheque drawals are tracked against the limit, and accounting follows the 2019 post-IFMS GO.
- IFS-UK-002: Treasury-unit master must cover district treasury, sub-treasury, **upgraded sub-treasury** and PAO (including out-of-state PAO) as distinct types.
- IFS-UK-003: Make **DSC/e-sign mandatory** on every bill, sanction and order, as a configurable policy.
- IFS-UK-004: Delegation-of-powers matrix must be loadable from FHB Part-1 (2018) and **versioned by amendment date** (2019, 2022).
- IFS-UK-005: Procurement workflow must be configurable to the Uttarakhand Procurement Rules 2017/2021 and link to the procurement grievance mechanism.
- IFS-UK-006: Pension flow must take Digital Life Certificate (Jeevan Pramaan) as an input event.

---

## Bihar (BR)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name / URL / modules | **CFMS (Comprehensive Financial Management System)**, now **CFMS 2.0**. Treasuries send accounts online through CFMS to the PAG (A&E). The CAG noted an **Audit trail module** in CFMS that was not operating and no provision to start family pension on death (2019-20). A **"Bill Type - SNA SPARSH"** exists in CFMS 2.0. The module list (expenditure, system admin), the TCS vendor and the 2019 go-live come from secondary leads only. cfms.bihar.gov.in did not resolve. | https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-2019-20-064b50a3e661067-66724113.pdf ; https://betastate.bihar.gov.in/file_2/FileUpload/2026/Jan/14-Jan-2026/5/DyPage/4791413a-ecdb-4302-a7c7-044e52ca6fc6.pdf | yes | V (CFMS, CFMS 2.0, SNA bill type) / [U] (module list, vendor, go-live) |
| 2. Treasury structure | **76 treasuries** (including one PAO at Bihar Bhawan, New Delhi) as of 31-03-2020. **All are banking treasuries with no cash business.** **There is no Directorate of Treasuries in Bihar**; treasuries are under the direct administrative control of the Finance Department. Sub-treasury and DDO counts were not found. | CAG Bihar Annual Review 2019-20 | yes | V |
| 3. Financial rules | **Bihar Treasury Code 2011** (BTC; replaced the 1937 code from Oct 2011), **Bihar Financial Rules** (BFR; budget control in Ch.16), **Bihar Budget Manual** (BBM). BTC Form-8 is the LOC register (Rule 96). Procurement rules and the delegation-of-powers instrument were not found. | CAG Bihar Annual Review 2019-20 ; https://betastate.bihar.gov.in/file_2/FileUpload/2024/Aug/07-Aug-2024/5/DyPage/133674852688373679.pdf | yes | V (BTC 2011 and rules cited in the CAG review) / [U] (BFR edition, procurement) |
| 4. Bill types / token / e-payment | **AC (abstract contingent) / DC (detailed contingent) bills**; the CAG flags pending DC bills. Grants-in-aid bills to SNAs are noted. A SNA SPARSH bill type exists. The token process was not documented. | CAG Bihar Annual Review 2019-20 ; Bihar SNA circular | yes | V / [U] (token) |
| 5. Integrations | CFMS 2.0 SNA is integrated with **RBI e-Kuber** for JIT release (Finance Dept circular, Jan 2026; the Hindi text is font-garbled but these terms are legible). SNA-SPARSH is mandatory from 01-11-2025 (DoE). The state DBT portal (agriculture DBT) is only a secondary lead. | Bihar SNA circular ; DoE OM | yes | V (partial) / [U] (DBT portal) |
| 6. Budget classification | **ULB deposits**: Nagar Nigam, Nagar Parishad and Nagar Panchayat are booked under sub-heads 0001/0002/0003 of **MH 8448-00-102** (FD letter 8037 dt 14-09-2015). The digit structure was not found. | CAG Bihar Annual Review 2019-20 | yes | V / [U] (digits) |
| 7. Languages | Finance Department circulars are in Hindi (Devanagari) with English technical terms; the CAG reviews are in English. | Bihar SNA circular | yes | V |
| 8. Distinctive features | No treasury directorate, so Finance Dept to treasury is a direct reporting line. ULB fund flows go through PD/PL accounts under 8448 with ULB-type sub-heads. The CAG flags heavy AC-bill pendency. Press reports (secondary) on the 2023-24 State Finances report cite grants-in-aid of about Rs 22,470.94 cr to SNAs without detailed vouchers, and PD accounts not migrated to CFMS. | CAG Bihar Annual Review 2019-20 | yes | V / [U] (press items) |

Candidate state-specific requirements
- IFS-BR-001: Org model must allow treasuries to report **directly to the Finance Department**, with no directorate tier (the treasury-hierarchy depth is configurable).
- IFS-BR-002: **AC/DC bill pairing**. Track each AC drawal against its DC bill and block or warn on further AC bills while DC bills are pending (threshold configurable per BTC).
- IFS-BR-003: PD/PL account sub-head mapping for ULB types (Nagar Nigam / Parishad / Panchayat to 8448-00-102-0001/2/3), validated at challan and voucher time.
- IFS-BR-004: Bill-type catalogue must include **SNA-SPARSH bill type** with e-Kuber JIT release.
- IFS-BR-005: Treasuries flagged as banking-only (no cash business), so cash-chest workflows are disabled per treasury.
- IFS-BR-006: Rule references in validations must point to BTC 2011 / BFR / BBM rule numbers and BTC form numbers (e.g. BTC Form-8 for LOC).
- IFS-BR-007: Audit-trail module enabled by default; it cannot be switched off (a CAG finding).

---

## Jharkhand (JH)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name / URL / modules | **IFMS 2.0 "KUBER"** (jkuber.jharkhand.gov.in), built by NIC. It grew from TIS KUBER (June 2007). The state decided in Aug 2015 to build IFMS on its own after missing the MMP DPR deadline. **12 modules**: e-Budget (COBT), e-Allotment, e-Bill (DDO bill management), e-Treasury, Treasury MIS (e-Dashboard), **Jharkhand e-GRAS (e-Challan)**, e-GPF, Employee Portal, e-Pension, Kuber Pay Slip, **"Hamar Apan Budget"**, plus others. The portal also lists Debt Management System, Loan Portal, Audit Management System, Helpdesk, **Jharkhand SNA-SPARSH** and JKuber-Hastakshar (signing). Planning, Fund and Debt Management, and Audit modules from the DPR were not implemented at the time of the CAG audit. | https://jkuber.jharkhand.gov.in/ ; https://cag.gov.in/uploads/download_audit_report/2022/Report-No.-1-of-2025-IFMS-in-JHR_English-(08-03-2025)-068ac55c7bf0127.94924518.pdf | yes | V |
| 2. Treasury structure | **33 treasuries**: 27 at district level (including Jharkhand Bhavan, New Delhi) and **6 sub-treasuries** at sub-divisional level. Pension is handled by the Directorate of Pension and Accounts (portal link). The exact treasury-directorate name and the DDO count were not found. | CAG Report No.1 of 2025 (JH IFMS) ; jkuber portal | yes | V (counts) / [U] (directorate name, DDOs) |
| 3. Financial rules | **Jharkhand Treasury Code (JTC)** (Para 11B governs change management), **Jharkhand Financial Rules (JFR)**, **Jharkhand Budget Manual**, **Jharkhand Public Works Accounts Code**, Forest Act and Manual, IT/ITeS Policy 2016. Editions, years and procurement rules were not found. | CAG Report No.1 of 2025 | yes | V (names) / [U] (editions, procurement) |
| 4. Bill types / token / e-payment | e-Bill at DDO level with workflow as defined in JFR/JTC. **AC bills**: the CAG found 122 DDOs drew 405 further AC bills in breach of the JTC. Pension, DCRG and CVP payments are made on AG-issued GPO/PPO data. The **AG GPO number is 9 digits** ("12" + yy + 5-digit serial). e-Payment runs through SBI-CMP and RBI e-Kuber. | CAG Report No.1 of 2025 | yes | V |
| 5. Integrations | WAMIS (works), revenue-department systems, **HRMS**, GSTN, payment gateways, **SBI-CMP**, **RBI e-Kuber**. At the time of the CAG audit **IFMS was not integrated with PFMS** (CSS central share not captured; scheme mapping incomplete). The portal now carries a "PFMS Scheme Mapping Form" and a Jharkhand SNA-SPARSH link. The claim that Jharkhand was an original SNA-SPARSH pilot state is a secondary lead only. | CAG Report No.1 of 2025 ; jkuber portal | yes | V / [U] (pilot claim) |
| 6. Budget classification | Scheme provisions are split into **Other Sub-Plan (OSP), Tribal Sub-Plan (TSP) and Scheduled Castes Sub-Plan (SCSP)** components, decided by the Planning and Development Dept since 2017-18. The digit structure was not found. | CAG Report No.1 of 2025 | yes | V / [U] (digits) |
| 7. Languages | Portal is English/Hindi; the CAG report is in English. | jkuber portal | yes | V |
| 8. Distinctive features | **TSP/SCSP/OSP component tagging** on scheme budgets (Fifth Schedule state). The CAG found excess DCRG/CVP payments of about Rs 11 cr in 344 cases, short-realised revenue because challans accepted lower deposits than generated, back-end bypass of validations, and changes made directly in production. A citizen-budget module, "Hamar Apan Budget", is part of the suite. Secondary press (May 2026) reports a KUBER payroll fraud, unverified. | CAG Report No.1 of 2025 | yes | V / [U] (press) |

Candidate state-specific requirements
- IFS-JH-001: Budget lines must carry a **sub-plan component attribute (TSP / SCSP / OSP)**, with allocation and expenditure reports split by component.
- IFS-JH-002: Pension/DCRG/CVP payment must validate against AG authorisation (GPO/PPO) with **format checks on the 9-digit GPO** (prefix, year, serial), block a duplicate payment against the same authorisation, and enforce PPO uniqueness.
- IFS-JH-003: Receipt challans must **reject deposits below the challan amount** (e-GRAS reconciliation).
- IFS-JH-004: No back-end bypass of bill validations. Any override needs a recorded, approved exception workflow.
- IFS-JH-005: Change management must follow JTC Para 11B: a configuration and workflow change register with approvals before production.
- IFS-JH-006: Map CSS schemes to PFMS/SNA-SPARSH scheme codes so the central share is captured as a receipt event.
- IFS-JH-007: Integrate with the works system (WAMIS) and the agency bank's cash-management product (SBI-CMP) as configurable payment rails alongside e-Kuber.

---

## Punjab (PB)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name / URL / modules | **IFMS Punjab ("Next Gen")** at ifms.punjab.gov.in. TCS computerised treasuries from Dec 2010 to Mar 2020. **NIC built the new IFMS, live from 01-04-2020.** Modules include **eReceipt** (online and offline challans, GRN verification), Vehicle Management System (POL limits), Govt Property Management System, and a Cyber Treasury for receipt reconciliation. Debt Management, e-Vouchers and eBudget were "in process" in 2021. | https://ifms.punjab.gov.in/ ; https://informatics.nic.in/files/informatics-flipbook-April-2021/files/basic-html/page33.html ; https://cag.gov.in/uploads/ae_state_accounts/Annual-review-2021-22-065b74be9940c23-43727362.pdf | yes | V |
| 2. Treasury structure | **Director (Treasuries & Accounts)** under the Finance Department. **21 District Treasuries and 76 Sub-Treasuries** (2022-23 review), plus a PAO at Punjab Bhawan, New Delhi. Each is headed by a DTO/TO. The DDO count was not found. | https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-final-pk-065b201e016d802-53150340.pdf | yes | V |
| 3. Financial rules | **Punjab Financial Rules** (Vol I), **Punjab Treasury Rules & Subsidiary Treasury Rules**, Punjab Civil Service Rules (CAG inspection criteria). Editions, the delegation-of-powers instrument and procurement rules were not found. | CAG PB reviews 2021-22, 2022-23 | yes | V (names) / [U] (editions, procurement) |
| 4. Bill types / token / e-payment | **Grants-in-aid bills** are drawn in IFMS, but GIA sanctions and UCs are not captured, so UCs are tracked manually in AG VLC (2021-22). e-Kuber is used for all payments except pension. 27,179 e-Kuber failures in FY 2020-21. Payments that pass e-Kuber but fail at the beneficiary bank are not reported back. RBI account validation was not adopted. A Unique Payee Code cut return notes by 98% (NIC). Drafts are still issued by DTs. | CAG PB review 2021-22 ; NIC Informatics Apr 2021 | yes | V |
| 5. Integrations | RBI e-Kuber (payments; receipt integration was in process in 2021), GSTN (SGST), departmental portals, receipt banks (SBI, PNB, IDBI). The CAG flags multiple beneficiaries registered against a single account in PFMS. No Punjab-specific SNA-SPARSH evidence beyond the DoE mandate. | NIC Informatics ; CAG PB 2021-22 ; DoE OM | yes | V / [U] (SNA specifics) |
| 6. Budget classification | No digit structure found. eReceipt **auto-splits VAT: 11% to a department account and 89% to the Government Treasury**. | NIC Informatics Apr 2021 | yes | V (split) / [U] (digits) |
| 7. Languages | The DGG&IT site header is English/Punjabi. The CAG reviews are in English. The language of finance-department documents was not confirmed. | https://dit.punjab.gov.in/projects/ifms/ | yes | V (site) / [U] (finance docs) |
| 8. Distinctive features | Moved vendors mid-life (TCS to NIC in 2020). Receipt-level revenue split rules. Vehicle POL-limit control is tied to an HRMS code. Stamp-vendor discount (1-2%) is handled in eReceipt. Pension is outside e-Kuber. | sources above | yes | V |

Candidate state-specific requirements
- IFS-PB-001: Receipt engine must support **automatic revenue apportionment** of one challan into several heads or accounts by configured percentages (e.g. VAT 11/89).
- IFS-PB-002: GIA bills must link to a **GIA sanction record and UC tracking**, with UC-pending blocks and CSV export to AG VLC.
- IFS-PB-003: e-Payment must handle **two-stage failure** (e-Kuber return note vs beneficiary-bank failure after e-Kuber success), with a reconciliation queue and optional account pre-validation.
- IFS-PB-004: Payment rail selection must be configurable per bill category (pension may use a different rail than e-Kuber).
- IFS-PB-005: Payee master must enforce a **Unique Payee Code** and detect several beneficiaries on one bank account.
- IFS-PB-006: Rule library must cover the Punjab Financial Rules / Punjab Treasury Rules, which are shared with Haryana (see Cross-state observations).

---

## Haryana (HR)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name / URL / modules | Haryana IFMS components: IFMS proper, web software for DDOs, **Electronic Payment System**, **e-Billing and e-Pension**, monthly treasury accounts and validation, GPF data, **e-GRAS** (receipts; since Nov 2013 / 01-01-2014), GSTN and RBI integration, online PD/PLA accounts. **e-Salary** (2012) is a press lead. The Treasuries department site is hrtreasuries.gov.in (from memo headers). The portal URLs egrashry.nic.in and esalary were not confirmed. | https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-2024-25-06979d0a1f1d3a6-12990500.pdf | yes | V / [U] (portal URLs, e-salary) |
| 2. Treasury structure | **Director General, Treasuries and Accounts (DTA) cum Secretary, Finance Department**. As of 31-03-2025: **26 treasuries (24 + 2 cyber treasuries) and 82 sub-treasuries**, all banking. **13,443 DDOs** use e-Payments (10,087 in the 2023-24 review). Chandigarh is the **Cyber Treasury for e-Receipts**; Panchkula is the **State Cyber Treasury for SNA-SPARSH** (operational from **25-01-2025**). | CAG HR Annual Review 2024-25 ; https://cag.gov.in/uploads/ae_state_accounts/Final-File-Annual-Review-2023-24-067c5557fe09938-44840245.pdf | yes | V |
| 3. Financial rules | Haryana applies the **Punjab Financial Rules** (Vol I, e.g. Rule 3.17) and the **Punjab Treasury Rules & Subsidiary Treasury Rules** (Vol I, e.g. Rules 2.16, 4.49(4), 4.132). The Haryana procurement rules / HKCL link was not found (HKCL looks like an unrelated lead). | CAG HR reviews 2023-24, 2024-25 | yes | V / [U] (procurement) |
| 4. Bill types / token / e-payment | Bills move through e-Billing. Pay orders are **digitally signed by the Treasury Officer, then verified and digitally signed by the DDO** online. A UCP (unique code of payee) change request module in e-Billing/e-Pension was built for e-Kuber (DTA memo 2026). Detailed contingent bills are pending. | CAG HR 2024-25 ; https://cdnbbsr.s3waas.gov.in/s3e0a209539d1e74ab9fe46b9e01a19a97/uploads/2026/02/20260218167566485.pdf | yes | V (CAG) / [U] (memo read only via search snippet) |
| 5. Integrations | RBI (e-Kuber), GSTN (SGST data to the cyber treasury), agency banks (SBI, PNB and others), **SNA-SPARSH via the Panchkula cyber treasury**. A state DBT portal was not found. | CAG HR 2024-25 | yes | V / [U] (DBT) |
| 6. Budget classification | **PRI deposits**: 8448-109-98 (Zila Parishad), 8448-109-99 (Panchayat Samiti). e-GRAS cash bookings use 8782-102-95-… The digit structure was not found. | CAG HR 2024-25 | yes | V / [U] (digits) |
| 7. Languages | Departmental memos and CAG reviews are in English. Hindi use in finance documents was not confirmed. | - | yes | [U] |
| 8. Distinctive features | **Two cyber treasuries with distinct roles**: receipts (Chandigarh) and SNA-SPARSH JIT payments (Panchkula). The Cyber Treasury sends a single monthly receipt account. PRI-tier PD sub-heads. Pay orders carry a dual digital signature (TO then DDO). | CAG HR 2024-25 | yes | V |

Candidate state-specific requirements
- IFS-HR-001: Treasury master must support **virtual (cyber) treasuries** with single-purpose roles (e-receipts; SNA-SPARSH JIT) and their own monthly accounts.
- IFS-HR-002: The pay-order signature sequence must be configurable (**TO signs, then DDO countersigns**) rather than fixed DDO-first.
- IFS-HR-003: Payee code (UCP) change must be its own maker-checker workflow tied to e-Kuber payee registration.
- IFS-HR-004: PD accounts for PRIs must use tier-specific sub-heads (Zila Parishad / Panchayat Samiti) under 8448-109.
- IFS-HR-005: Rule library must allow **one state to adopt another state's rulebook** (Punjab FR/TR applied in Haryana) with local overrides.

---

## Himachal Pradesh (HP)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS name / URL / modules | **HIMKOSH - Integrated Financial Management Information System** (himkosh.nic.in), built by NIC HP. Projects: **eSalary, ePension, eVitran** (online budget disbursement), **eBudget, eChallan, eKosh** (financial reporting), HPNPS, AGHP-VLC (electronic VLC transfer to AG), **HPOLTIS** (treasury information system), **SNA-SPARSH** (bills for CSS), eService Book, myGPF, and HRMS-eSalary integration. FY 2026-27 dashboard: 177,169 employees, 192,251 pensioners, 107 HoDs, **5,600 DDOs**. | https://himkosh.nic.in/ ; https://tal.hp.gov.in/WebPages/wpAbout.aspx | yes | V |
| 2. Treasury structure | **Department of Treasuries, Accounts and Lotteries** (कोष, लेखा एवं लॉटरी), headed by the Special Secretary (Finance) cum Director, Treasuries. **12 district treasuries, 1 Capital Treasury, 1 Cyber Treasury and 1 GST Treasury (Shimla), 3 full-fledged treasuries at Pangi, Kaza and Delhi, 1 SNA Treasury, and 90 sub-treasuries** (2024-25). District treasury wings: Token, Bill Passing, LOC, GIS, Pension, Deposits, Accounts, Treasurer. The department is custodian of valuables and opium and the nodal agency for non-postal stamps. | https://cag.gov.in/uploads/ae_state_accounts/Treasury-Inspection-Annual-Review-2024-25-Final-068cbde5fdde158-92801357.pdf ; tal.hp.gov.in | yes | V |
| 3. Financial rules | **HP Financial Rules 2009** (replaced HPFR 1971). An amendment to Rule 157 (UCs) is only a press lead. **HP Treasury Rules & Detailed Treasury Procedure 2017** (e.g. Rule 42(f); Rule 110: bills only for SOEs with budget). **HP CCS (Contributory Pension) Rules 2006**. The HPFR 2009 procurement chapter and the delegation-of-powers instrument were not separately confirmed. | CAG HP review 2024-25 ; https://himachal.nic.in/WriteReadData/l892s/15_l892s/1499233683.pdf | yes | V (TR 2017, CPS Rules) / [U] (HPFR 2009 text read only via search snippet; amendments) |
| 4. Bill types / token / e-payment | Bills are processed **only for SOEs (standard object of expenditure) with an allocation to that DDO** (TR 2017 Rule 110). Separate pay bills are required for NPS employees who joined on or after 15-05-2003. **Cheque-drawing DDOs** exist (non-banking sub-treasuries; the treasury acts as banker). **HPTR-5 bills via the e-Kalyan portal** for social-security/DBT schemes. SNA-SPARSH e-claims/e-bills need a DSC. **Linkage of IFMS with RBI e-Kuber was yet to be established** (CAG 2024-25). | CAG HP review 2024-25 ; tal.hp.gov.in | yes | V |
| 5. Integrations | SNA-SPARSH (own SNA Treasury), PFMS (portal link), AG VLC, NPS, e-Kalyan (DBT). **No e-Kuber linkage** as of the 2024-25 review. GIA e-sanctions are not generated in IFMS, and UC monitoring is not available. | CAG HP review 2024-25 ; himkosh.nic.in | yes | V |
| 6. Budget classification | Uses **SOE** (standard object of expenditure) as the control level for DDO allocations. The digit structure was not found. | CAG HP review 2024-25 | yes | V / [U] (digits) |
| 7. Languages | Portal is bilingual (Hindi/English); the department name appears in Devanagari. | himkosh.nic.in ; tal.hp.gov.in | yes | V |
| 8. Distinctive features | **Full-fledged treasuries at Pangi and Kaza**, both remote tribal/hill areas (Kaza is in Lahaul-Spiti; Pangi in Chamba), in place of sub-treasuries. Functional treasuries are separate units (Capital, Cyber, GST, SNA). The department also runs lotteries, stamps and opium custody. The CAG found 439 DDOs mapped to more than one treasury and PAN missing for 36,212 employees (Tribune, secondary). | CAG HP review 2024-25 | yes | V / [U] (Tribune items) |

Candidate state-specific requirements
- IFS-HP-001: Treasury-unit types must include **functional treasuries** (Capital, Cyber, GST, SNA) and **full-fledged treasuries in remote/tribal blocks** (Pangi, Kaza), not just district/sub-treasury.
- IFS-HP-002: Budget check at bill time must run at **DDO x SOE allocation** level (TR 2017 Rule 110). Configurable control level per state (SOE vs object head vs detailed head).
- IFS-HP-003: Support **cheque-drawing DDOs** and non-banking sub-treasuries where the treasury pays in cash or by cheque, alongside e-Kuber or agency-bank e-payment. The payment rail must work without e-Kuber.
- IFS-HP-004: Payroll must split pay bills by pension scheme (OPS vs NPS joined on or after 15-05-2003) as a configurable rule.
- IFS-HP-005: DBT bills via an external welfare portal (e-Kalyan, HPTR-5 form) must flow into the treasury bill queue with a form-type code.
- IFS-HP-006: One DDO maps to exactly one treasury; validate and report on multi-mapping.
- IFS-HP-007: Form catalogue must be state-prefixed (HPTR-n; BTC Form-n in Bihar) so form identifiers are data, not code.

---

## Cross-state observations

1. **SNA-SPARSH is now universal, but how states host it varies.** The DoE OM of 10-06-2025 makes all CSS go through SNA-SPARSH from 01-11-2025 (V). States host it differently: a dedicated **SNA treasury** (HP), a **state cyber treasury** (Haryana, Panchkula, from 25-01-2025), an IFMS module (Uttarakhand, Jharkhand portal), or a **bill type** inside CFMS 2.0 (Bihar). Sangam needs SNA-SPARSH as a pluggable treasury-unit type plus a bill type, not a hard-coded flow.
2. **The treasury hierarchy is not uniform.** Bihar has no directorate. UP has division and HQ treasuries. Uttarakhand has upgraded sub-treasuries and PAOs. Haryana and HP have cyber, GST, SNA and capital treasuries. HP has full-fledged treasuries in tribal blocks. Org-unit types and depth must be configuration.
3. **Rulebooks are inherited.** Haryana uses Punjab FR/TR. Uttarakhand inherited UP-era rules (Stamp Rules revised from UP 1942; a Financial Handbook naming pattern). Jharkhand inherited Bihar-style codes (JTC/JFR). A rule library with "adopted-from + local amendments + effective dates" fits better than per-state copies.
4. **The budget control level varies**: Object Head (UP, 15-digit), SOE (HP), sub-plan component tags (Jharkhand TSP/SCSP/OSP). Sangam should let each state choose the control dimension and the classification length.
5. **e-Kuber adoption is uneven**: in use in UP, Punjab (not for pension), Jharkhand (with SBI-CMP), Haryana and Bihar (SNA); **not yet linked in HP** (2024-25). Payment rails must be pluggable, and failure handling must cover both e-Kuber return notes and downstream bank failures (Punjab finding).
6. **Grants-in-aid and UC tracking is a common CAG gap** (Punjab, HP, Bihar). GIA sanction capture and UC linkage should be a first-class Sangam workflow.
7. **PRI/ULB fund flows run through 8448 PD-account sub-heads** (Bihar ULB 8448-00-102-0001/2/3; Haryana PRI 8448-109-98/99). A local-body deposit account template is a reusable cross-state need.
8. **Common audit-control findings**: AC/DC bill pendency (Bihar, Jharkhand), back-end validation bypass and changes made directly in production (Jharkhand), and no BCP/DR (UP, Jharkhand). Sangam should ship with immutable audit trails, no-bypass validation and versioned workflow/config change control as defaults.
9. **Languages**: Hindi/English bilingual in UP, Uttarakhand, Bihar (Hindi-first circulars), Jharkhand and HP; English/Punjabi on Punjab sites. CAG material is English throughout. UI and document templates need at least Hindi + English and Punjabi (Gurmukhi) for Punjab.
10. **Open [U] items to chase in a follow-up** (the search budget was exhausted): the Bihar CFMS module list and go-live date; DDO counts for UP, Bihar, Jharkhand and Punjab; procurement rules for Bihar, Jharkhand, Punjab and Haryana; budget code digit structures for every state except UP; state DBT portal URLs (UP, Bihar, Haryana); the UP FHB Vol I and VI subjects.
