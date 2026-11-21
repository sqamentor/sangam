# IFMS-CENTRAL: requirements catalogue for Union Government finance workflows

Workstream: IFMS-CENTRAL research (Sangam). Date of this pass: **2026-11-04**. Status: research only; no code; nothing committed.

**Purpose.** This is an evidence-based list of what Sangam must do so that the finance workflow of **every Union ministry and department** can be configured on it. Each requirement has an ID (IFC-nnn), its source, and its coverage by the packs that exist today (`sangam/sangam-packs/README.md`). The document closes with a gap analysis and a configuration model for the implementation workstream.

**Inputs read first:**
- `SANGAM.md` IX.3–IX.7e: approval core, packs, seats, GOV-1…12;
- `spike/GOV-PATTERNS.md`: GP-01…GP-55;
- `sangam/sangam-packs/README.md`: 51 packs.

The pack DMN tables were also inspected (P1, budget re-appropriation, pension, works, core powers).

## 0. Method, evidence levels and conventions

- **Sources:** official sources only (Government of India domains), or an official document hosted on another government domain. Where only a secondary report was found, the item is marked **[U]**.
- **Evidence levels** (column "Ev."):
  - **P** = primary text read in this pass. PDFs were downloaded and text-extracted; the scanned R&P Rules 2022 were OCR'd with Tesseract.
  - **S** = an official document identified by search, with its title, number and date seen but the full text not read.
  - **X** = secondary source only.
- **[U]** = unverified: either the figure or claim is not confirmed from primary text, or the official sources conflict. The implementation workstream must treat **[U]** values as configuration placeholders, never as hard-coded rules.
- **Seen date:** every pass-1 source in the register (§1) was seen on **2026-11-04**. Pass-2 updates (marked "pass 2") were seen on **2026-11-05**.
- **Quoting:** quotes are kept to a minimum. Rules are paraphrased with their rule or para numbers.
- **Coverage column** (against the existing packs):
  - **C** = covered;
  - **P** = partly covered: the pattern exists, but the government-specific rule, data or step is missing or only illustrative;
  - **M** = missing.
- **Pack short names:**

  | Short name | Pack |
  |---|---|
  | core | `sangam.core` |
  | P1 | `gov.p1.bill-payment` |
  | P2 | `gov.p2.file-movement` |
  | P3 | `gov.p3.grant-sanction` |
  | DBT | `gov.dbt.batch` |
  | REAPP | `gov.ifms.budget-reappropriation` |
  | CHALLAN | `gov.ifms.challan-reconciliation` |
  | SALARY | `gov.ifms.salary-monthly` |
  | PENSION | `gov.pension` |
  | SECINST | `gov.security-instrument` |
  | CONTRACT | `gov.contract-management` |
  | TENDER | `gov.tender` |
  | CONSULT | `gov.consultancy` |
  | WO | `gov.work-order` |
  | WORKS | `gov.works` |
  | GEM | `gov.gem-purchase` |
  | STORES | `gov.stores` |
  | ASSETS | `gov.assets` |
  | AUDIT | `gov.audit-para` |
  | RECORDS | `gov.records` |
  | COURT | `gov.court-case` |
  | LEAVE | `gov.leave` |
  | UP14 | `universal.up14-three-way-match` |

## 1. Source register (S01–S44 seen 2026-11-04; S45–S58 and the pass-2 updates seen 2026-11-05)

| Key | Source (official unless marked) | Ev. | URL |
|---|---|---|---|
| S01 | IGOD Union Government directory: categories Ministries (53), Independent Departments (2), Departments (50) | P | https://igod.gov.in/ug/categories |
| S02 | Cabinet Secretariat, Allocation of Business Rules 1961, **consolidated "as amended upto Amendment Series no. 386 dated 22nd July, 2026"** (complete rules and First Schedule uploaded 27-07-2026; the amendment list has 386 entries). *Pass 2, seen 2026-11-05.* The older text to Series 351 (06-08-2019) is superseded | P | https://cabsec.gov.in/writereaddata/allocationbusinessrule/completeaobrules/english/1_Upload_4265.pdf ; https://cabsec.gov.in/writereaddata/allocationbusinessrule/firstschedule/english/1_Upload_4266.pdf ; amendment list: https://cabsec.gov.in/ajax_page_data.php?page_id=205 |
| S03 | Delegation of Financial Powers Rules 2024 (DoE book): S.O. 1543(E), 22-03-2024, in force 01-04-2024; full text incl. appendices. Hosted on a government institute site | P | https://neigrihms.gov.in/Institute,%20Circular,%20Order,%20Format/2025/Delegation_of_Financial_Powers_Rules_2024_Booklet.pdf |
| S04 | DoE OM No. 1(14)/2016-E.II(A)(Vol.III) of 09-06-2026, "Amendment in Delegation of Financial Power Rules, 2024". It replaces Annexure-I under Rule 8 (Standard Object Heads) with a harmonised Union/State list of **61 object heads in 8 object classes, effective FY 2027-28**. *Pass 2, read 2026-11-05* | P | https://doe.gov.in/files/circulars_document/Rule8Orderdated090626.pdf |
| S05 | General Financial Rules 2017, consolidated up to 31-01-2026 (full text read) | P | https://www.iitbbs.ac.in/wp-content/uploads/2026/06/GFR-updated-up-to-31.01.2026.pdf |
| S06 | DoE GFR amendments page; OMs 05-05-2026 (Rule 310, transfer of land) and 08-05-2026 (Rule 151, debarment) | S | https://doe.gov.in/hi/circulars/amendments-general-financial-rules-gfr-2017 |
| S07 | Central Government Account (Receipts and Payments) Rules 2022, G.S.R. 644(E), 20-08-2022 (scanned; OCR'd) | P | https://cga.gov.in/DownloadPDF.aspx?filenameid=1806 |
| S08 | Subsidiary Instructions to RPR 2022 (forms RPR-1…RPR-100, concordance with GAR forms). Correction slips 02-01-2024 and 15-01-2025 | P | https://cga.gov.in/DownloadPDF.aspx?filenameid=1807 |
| S09 | Civil Accounts Manual, Revised Fourth Edition 2024 (CS-1 26-09-2024, CS-2 25-03-2025) | P | https://cga.gov.in/DownloadPDF.aspx?filenameid=1853 |
| S10 | List of Major and Minor Heads of Account (LMMH), "correction slips incorporated up to 1127 dated 09-10-2026" | P | https://cga.gov.in/DownloadPDF.aspx?filenameid=1979 |
| S11 | CGA books list: GAR, DDO Manual, Government Bank Manual, Standardised Audit Checklists, Compendium of Accounting Procedures | P | https://cga.gov.in/Books/Print/list.aspx |
| S12 | e-Bill: CGA OM 28-05-2024 (prerequisites, DSC); OM 31-07-2024 (rollout to the PAOs of the civil ministries in its Annexure A **from 05-08-2024**; verified in pass 2, 2026-11-05); OM 18-12-2025 (standard claim-cum-bill forms, rollout Jan–Apr 2026) | S | https://cga.nic.in//writereaddata/file/OMNo156Dated28052024.pdf ; https://cga.nic.in//writereaddata/file/OMNo206Dt31072024.pdf ; https://cga.nic.in//writereaddata/file/TA459dt18122025.pdf |
| S13 | CGA OMs 12-06-2026 and 11-08-2026: internal-audit users in PFMS for **post-check of e-bills** | S | https://cga.gov.in/Circular/Published/9441.aspx |
| S14 | SNA-SPARSH: DoE OM 13-07-2023; CGA OM I-126/3/2022-ITD-CGA/95 dated 11-08-2025 (SOP, revised 3-role flow) | P (SOP) / S (OM) | https://cga.nic.in//writereaddata/file/SOPrevisedworkflowSNASPARSH95dt14082025.pdf ; https://doe.gov.in/files/public-finance-state-cna-sna-document/Letter_Dated_13_07_202_To_ALL_Chief_Secretaries_Principal_Secretaries.pdf |
| S15 | CNA model for Central Sector Schemes: DoE OM F.No.1(18)/PFMS/FCD/2021 dated 09-03-2022; corrigendum 04-05-2022; OM 05-02-2024 (schemes of ≥ ₹100 crore to Model 1); master circular 21-05-2024 | S | https://gifmis.cga.gov.in/SitePages/doc/FAQsOnCNAwithletter.pdf ; https://doe.gov.in/files/public-finance-state-cna-sna-document/04_05_2022.pdf |
| S16 | TSA for autonomous bodies: CGA TSA guidelines; universal implementation from 01-04-2023 (OM 28-03-2023) | S | https://cga.gov.in/Page/TSA-Guidelines.aspx ; https://cga.nic.in/writereaddata/file/TSAsystem1003202210032022.pdf |
| S17 | Bharatkosh (NTRP): CGA page; SOP for unaccounted NEFT/RTGS receipts OM 342 dated 31-12-2024; e-Kuber 2.0 migration 22-02-2025 (CPIN, auto-UTR); card-receipt settlement T+3 (OM 15-06-2026) | S | https://cga.gov.in/Page/Bharatkosh.aspx ; https://cga.nic.in//writereaddata/file/OMNo342dt31122024.pdf |
| S18 | Monthly accounts to CGA by the 8th (DAMA OM 44 of 12-06-2025); March Prelim / Sy-I / Sy-II dates for 2025-26 (OM of 03-03-2026; text garbled) | S | https://cga.nic.in//writereaddata/file/DAMAOMNo44dt12062025.pdf ; https://cga.nic.in//writereaddata/file/DAMA468dt03032026.pdf |
| S19 | Year-end cut-offs for 31-03-2026 (assignment advice valid to 24:00 hrs 31-03-2026), CGA OM 25-03-2026 | S | https://cga.nic.in//writereaddata/file/GBA1468-1688dt25032026.pdf |
| S20 | Budget circular for RE 2026-27 / BE 2027-28: issued 27-08-2026, estimates through UBIS, actuals to 30-09-2026 (press reports); MHA internal circular 14-08-2026 | X / S | https://www.mha.gov.in/sites/default/files/FormulationREBE202728_14082026.pdf |
| S21 | DEA Budget Division: call for proposals for the 1st batch of Supplementary Demands 2025-26 | S | https://dea.gov.in/files/budget_division_documents/Calling%20of%20proposals%20for%20First%20batch%20of%20Supplementary%20Demands%20for%20Grants%202025-26.pdf |
| S22 | DoE OM 24(35)/PF-II/2012 dated 05-08-2016: appraisal and approval of schemes (reproduced as DFPR 2024 Appendix III) | P | via S03 |
| S23 | DoE OM 23(3)/E.Coord/2018 dated 13-06-2023: revised Charter for Financial Advisers (cited in CAM 2024 ch. 16, 18) | S | https://www.gconnect.in/news/charter-financial-advisers-doe.html (X pointer). **[U] after pass 2:** not found on doe.gov.in (the orders list loads by JavaScript; a title filter returned nothing) |
| S24 | Pension: CCS (Pension) Rules 2021; Bhavishya mandatory and Form 6-A online; CGA/CPAO OM of 05-12-2024 | S | https://bhavishya.nic.in/FAQs.aspx?pid=faq ; https://cga.nic.in//writereaddata/file/OMNo431-475dt05122024.pdf |
| S25 | UPS: DFS notification FX-1/3/2024-PR dated 24-01-2025; PFRDA (Operationalisation of UPS under NPS) Regulations 2025 dated 19-03-2025; CCS (Implementation of UPS under NPS) Rules 2025 (around 09-2025) | S | https://cag.gov.in/uploads/ae_circulars_office_orders/CircularsOfficeOrders-06808e0b74054b3-33120723.pdf |
| S26 | GPF (CS) Rules 1960: ₹5 lakh annual subscription ceiling, G.S.R. 96 of 15-06-2022 | S | https://dahd.gov.in:80/sites/default/files/2023-08/CeilinginGPFSubscription.pdf |
| S27 | CGA Audit Para Monitoring System (APMS): ATNs on C&AG paras, ATRs on PAC reports, explanatory notes on excess/savings | S | https://cga.gov.in/Page/Audit-Para-Monitoring-System-APMS.aspx |
| S28 | CGA Internal Audit page; annual risk-based internal audit plan 2026-27 (circular 9057, 05-02-2026) | S | https://cga.gov.in/Page/Internal-Audit.aspx ; https://cga.gov.in/Circular/Published/9057.aspx |
| S29 | Defence: CGDA site; Defence Account Code; DFPDS 2021 (Raksha Mantri release of 07-09-2021); SPARSH (defence pensions) | S / X | https://www.cgda.nic.in/index.php ; https://cgda.nic.in/audit/Defence%20Account%20Code.pdf ; https://www.rajnathsingh.in/press-release/raksha-mantri-shri-rajnath-singh-approves-delegation-of-financial-powers-to-armed-forces-for-revenue-procurement/ |
| S30 | Railways: CAG Report 13 of 2021 (IPAS payroll/accounting); merger of the Rail Budget into the Union Budget from 2017-18 (PRS, X) | S / X | https://cag.gov.in/uploads/download_audit_report/2021/Railway%20Finance%20Report-13-2021-061c1c356591929.85046974.pdf |
| S31 | Department of Posts: Postal Accounts Manual Vol. I | S | https://Indiapost.gov.in/VAS/DOP_RTI/PAM_Vol_I_5th_2022.pdf |
| S32 | DoT: CGCA / Pr.CCA functions (licence fee, spectrum charges, SAMPANN pensions); DoT OM 2026 (PMRTS fees via SARAS from 01-07-2026, receipt head 1275-00-103-02-02-00) | S | https://cgca.gov.in/function ; https://www.dot.gov.in/static/uploads/2026/06/3aef99e2bde4b3bbdccebb986e6f44e3.pdf |
| S33 | MEA: accounts page (161 missions/posts, 30 RPOs); I-MAS (NIC); RPR 2022 Rules 13(7)–(8) and 17(4) on foreign currency (P via S07) | S / P | https://www.mea.gov.in/mea-accounts ; https://informatics.nic.in/uploads/pdfs/cc8ad679_oct2011%2015-16.pdf |
| S34 | MHA Pr.CCA organisation (48 PAOs; CAPF regional PAOs); MHA finance directory 10-04-2026 | S | https://www.mha.gov.in/en/divisionofmha/finance-division/principal-chief-controller-of-accounts-org |
| S35 | Department of Space Book of Financial Powers; ISRO "powers and duties" page | S | https://nesac.gov.in/assets/resources/2021/03/dos_book_of_financial_powers_2016.pdf ; https://www.isro.gov.in/Powers_and_duties.html |
| S36 | CPWD Works Manual 2022 (latest CPWD edition found); DoE Manual for Procurement of Works, 2nd Ed. 2025 | S | https://www.ndmc.gov.in/departments/Departments/Finance/nodal_cell/CPWD%20Works%20Manual%202022.pdf |
| S37 | Working Group of Finance Secretaries on rationalising object heads (Union 61 heads; proposed common structure, full roll-out from FY 2027-28) | S | https://saiindia.gov.in/uploads/media/Update-on-Action-Point-of-First-Finance-Secretaries-19-09-2025-068ceaffc4cafb0-11251343.pdf |
| S38 | EAT module mandatory for central sector grantees (MoCulture notice 19-08-2019 citing DoE) | S | https://www.indiaculture.gov.in/sites/default/files/Schemes/EAT_Module_19_08_2019.pdf |
| S39 | e-UC module for CSS UCs in Form GFR 12-C (UT Puducherry DAT notice, 12-03-2025) | S | https://dat.py.gov.in/roll-out-e-uc-module-submission-uc-css-form-12-c-gfrs-2017-reg |
| S40 | PFMS DBT FAQ (SSID, four integration models), already in SANGAM IX.7d | S | https://gifmis.cga.gov.in/SitePages/doc/PFMS_DBT_FAQ.pdf |
| S41 | DoPPW OM 09-03-2021: pension-case timelines (HoO→PAO, PAO→CPAO) | S (X host) | https://staffnews.in/wp-content/uploads/2021/03/DoPPW-OM-Dtd-9th-March-2021.pdf . *Pass 2:* no official host found. **Superseded as a source by S45**, which states the same timelines |
| S42 | Cash Management System (MEP/QEP), DEA OM as circulated by DoT | S | https://dot.gov.in/sites/default/files/Cash%20Management%20System-Q2%20of%202021-22_0.pdf |
| S43 | Rush of expenditure ceilings quoted from MoF (33 % last quarter / 15 % March), ICAR circular | S | https://icar.org.in/sites/default/files/Circulars/Rush-of-Expenditure-1.pdf |
| S45 | CCS (Pension) Rules 2021, G.S.R. 868(E), 20-12-2021 (pass 2, read 2026-11-05) | P | https://pensionersportal.gov.in/Document/CCS-Pension-Rules%202021-English.pdf |
| S46 | CCS (Implementation of UPS under NPS) Rules 2025, G.S.R. 599(E), 02-09-2025 (pass 2, read) | P | https://pensionersportal.gov.in/Document/UPS_Notification_599_02092025eiawE.pdf |
| S47 | DEA Budget Circular 2026-27, F.No. 2(16)-B(D)/2025 dated 28-08-2025 (pass 2, read) | P | https://dea.gov.in/files/circular_document/Budget_Circular202627.pdf |
| S48 | GFR 2017 updated up to 31-01-2026, official DoE copy of S05 (identified, not downloaded) | S | https://doe.gov.in/files/circulars_document/GFRupdatedupto31012026.pdf |
| S49 | DoE CNA master circular for Central Sector Schemes, F.No. 3/(06)/PFMS/2023 dated 21-05-2024 (pass 2, read) | P | https://doe.gov.in/files/circulars_document/OM_1.pdf |
| S50 | DoE Manual for Procurement of Works, 2nd Edition 2025 (listed 05-01-2026); amendment OM of 18-06-2026 (scanned, not read) | S | https://doe.gov.in/files/circulars_document/Works_Manual_SE_2025.pdf ; https://doe.gov.in/files/circulars_document/OM392026_18062026.pdf |
| S51 | 8th Central Pay Commission: Resolution F.No.01-01/2025-E.III(A) of 03-11-2025 (Gazette) | P | https://doe.gov.in/files/cenetral-pay_document/8thcpcnotification.pdf |
| S52 | 16th Finance Commission, Explanatory Memorandum (01-02-2026): award period 2026-27 to 2030-31 | P | https://dea.gov.in/files/circular_document/Explanatory%20Memorandum%2016th%20Finance%20commisssion%20-%20English%20Final.pdf |
| S53 | CGA DAMA OM 482 of 31-03-2026 (March Sy-I last date) and DAMA OM 468 of 03-03-2026 (checkpoints; distribution list of accounting units) | P | https://cga.nic.in//writereaddata/file/DAMA482dt31032026.pdf ; https://cga.nic.in//writereaddata/file/DAMA468dt03032026.pdf |
| S54 | CGA IAD OM 300-01 of 11-08-2026 (PFMS internal-audit users) | P | https://cga.nic.in//writereaddata/file/IAD300-01dt11082026.pdf |
| S55 | CPAO Scheme booklet, 6th edition 2026 (Jeevan Pramaan accepted) | P | https://cpao.nic.in/pdf/scheme_bookle_new_feb.pdf |
| S56 | Constitution of India as on 01-05-2024 (Legislative Department), Art. 112(3) | P | https://cdnbbsr.s3waas.gov.in/s380537a945c7aaa788ccfcdf1b99b5d8f/uploads/2024/07/20240716890312078.pdf |
| S57 | RBI FAQ "RBI as Banker to Government" (14-04-2020): s.21A agreements with all State Governments except Sikkim | P | https://www.rbi.org.in/Scripts/FAQView.aspx?Id=61 |
| S58 | RBI presentation to the Conference of State Finance Secretaries, 19-09-2025: all States integrated with RBI for e-payments except Sikkim | P | https://cag.gov.in/uploads/media/RBI-PPT-on-State-Finance-Secretaries-Conference-19-9-25-068ceb1d5e44388-11348057.pdf |
| S44 | MHA notice (Grant No. 51, Police) of 05-02-2026: surrender / additional-funds requests by 10-02-2026 | S | https://xn--i1b5bzbybhfo5c8b4bxh.xn--11b7cb3a6a.xn--h2brj9c/sites/default/files/Buget_05022026.pdf |

---

## 2. Structure of the Union Government (finding)

### 2.1 Counts and lists

**IGOD (S01, read 2026-11-04):**
- **53 ministries**, **2 independent departments** (Department of Atomic Energy, Department of Space) and **50 departments**.
- Secondary sources quote "54 ministries / 93 departments". *Pass 2:* no official source uses it; only Wikipedia-derived pages repeat it. It is probably the Second Schedule unit count before Amendment 386 (94 − 1). **Do not use it.**

**Allocation of Business Rules (S02):** the First Schedule is the legal list of "Ministries, Departments, Secretariats and Offices".
- DFPR 2024 Rule 3(1)(e) defines "Department of the Government of India" by reference to this First Schedule, plus the Vice-President's Secretariat (S03).
- **Verified (pass 2, S02, seen 2026-11-05).** cabsec.gov.in now hosts the AoBR consolidated to **Amendment Series 386 of 22-07-2026**. Its own "Numeric details of Ministries and Departments" gives:
  - **53 Ministries**: 34 without departments and 19 with departments;
  - **53 Departments** under those 19 ministries;
  - **7 standalone units**: the Departments of Atomic Energy and Space, plus 5 secretariats/offices (Cabinet Secretariat, President's Secretariat, PMO, NITI Aayog, NSCS);
  - **94 business units** in the Second Schedule index (a ministry with no departments counts as one unit).
- **Reconciliation with IGOD.** The 53 ministries match. IGOD's 50 departments + 2 independent departments equal the AoBR's 53 + 2, less three MHA departments IGOD does not list (Internal Security; States; Jammu, Kashmir and Ladakh Affairs). IGOD omits the 5 secretariats/offices. The Vice-President's Secretariat is not in the First Schedule; DFPR Rule 3(1)(e) adds it separately.
- **Main amendments since 2019** (from the footnotes):
  - Department of Military Affairs: Series 353, 30-12-2019;
  - Ministry of Education rename: 356, 14-08-2020;
  - Ports, Shipping and Waterways: 358, 09-11-2020;
  - Ayush: 360, 13-04-2021;
  - Heavy Industries' departments omitted, DPE moved to Finance: 361, 06-07-2021;
  - Ministry of Cooperation: 362, 06-07-2021;
  - Departments of Capital Development and Urban Development under MoHUA: 386, 22-07-2026 (S.O. 4040(E) of 23-07-2026).

**The 53 ministries (IGOD):**
Agriculture and Farmers Welfare; AYUSH; Chemicals and Fertilizers; Civil Aviation; Coal; Commerce and Industry; Communications; Consumer Affairs, Food and Public Distribution; Cooperation; Corporate Affairs; Culture; Defence; Development of North Eastern Region; Earth Sciences; Education; Electronics and IT; Environment, Forest and Climate Change; External Affairs; Finance; Fisheries, Animal Husbandry and Dairying; Food Processing Industries; Health and Family Welfare; Heavy Industries; Home Affairs; Housing and Urban Affairs; Information and Broadcasting; Jal Shakti; Labour and Employment; Law and Justice; MSME; Mines; Minority Affairs; New and Renewable Energy; Panchayati Raj; Parliamentary Affairs; Personnel, Public Grievances and Pensions; Petroleum and Natural Gas; Planning; Ports, Shipping and Waterways; Power; Railways; Road Transport and Highways; Rural Development; Science and Technology; Skill Development and Entrepreneurship; Social Justice and Empowerment; Statistics and Programme Implementation; Steel; Textiles; Tourism; Tribal Affairs; Women and Child Development; Youth Affairs and Sports.

**Departments listed by IGOD (50 + 2):**
DPIIT, DARPG, DARE, Agriculture and FW, Animal Husbandry and Dairying, Biotechnology, Border Management, Capital Development, Chemicals and Petrochemicals, Commerce, Consumer Affairs, Defence, Defence Production, Defence R&D, Drinking Water and Sanitation, Economic Affairs, Empowerment of PwD, Ex-Servicemen Welfare, Expenditure, Fertilizers, Financial Services, Fisheries, Food and PD, Health and FW, Health Research, Higher Education, Home, DIPAM, Justice, Land Resources, Legal Affairs, Military Affairs, Official Language, Pension and PW, DoPT, Pharmaceuticals, Posts, Public Enterprises, Revenue, Rural Development, School Education and Literacy, Science and Technology, DSIR, Social Justice and Empowerment, Sports, Telecommunications, Urban Development, Water Resources RD&GR, Youth Affairs, Legislative Department; plus Atomic Energy and Space.

The IGOD entries "Department of Capital Development" and "Department of Urban Development" (both MoHUA, Series 386 of 22-07-2026) and "Department of Border Management" (MHA, item (vi)) **all exist in the current AoBR** (verified pass 2, S02). Capital Development's business includes Union properties, civil works, CPWD and DDA, so works and estate profiles must bind to that department, not to MoHUA as a whole.

**Design consequence:** Sangam must not hard-code the ministry/department list.
- Seed the registry from IGOD/AoBR.
- Version it with effective dates, because AoBR amendments create, merge and rename departments.
- Allow attached and subordinate offices and autonomous bodies to hang under any department.

### 2.2 Finance roles (S03, S07, S09)

| Role | Legal basis | What it does in the workflow |
|---|---|---|
| Secretary = **Chief Accounting Authority (CAA)** | CAM 2024 ch. 1 (ICAS officers report to the CAA through the FA); DFPR Rule 10(9) (CAA re-appropriation powers) | Owns the grant, signs the Appropriation Accounts, holds re-appropriation and many sanction powers |
| **Financial Adviser (FA) / Integrated Finance Division (IFD)** | DFPR Rule 3(1)(f) proviso (IFA exercises Finance Ministry powers, under supervision); Charter for FAs 13-06-2023 (S23) | Concurrence on expenditure, re-appropriation advice (DFPR 10(9)(vii)), budget, MEP/QEP adherence (GFR 62(3)–(4)), write-off reports (DFPR 13) |
| **Pr.CCA / CCA / CA (i/c)** = Head of Accounting Organisation | RPR 2(v); CAM 1.2.3 | On behalf of the CAA: arranges payments through PAOs / CDDOs (new CDDOs need CGA approval), compiles accounts, prepares Appropriation Accounts, runs internal audit |
| **Principal Accounts Office (Pr.AO)** | RPR 2(z); CAM 2.2.2, 5.10 | Nodal office: PFMS user approvals, budget entry and head mapping, monthly account consolidation, SCT; e-Monthly DO by the 12th |
| **Pay and Accounts Officer (PAO)** | RPR 2(w), 13(1) | The withdrawal authority. Pre-checks every bill, signs payment files (DSC), accounts at object-head level, handles TSA and DBT |
| **Cheque Drawing and Disbursing Officer (CDDO)** | RPR 2(k), 13(2)–(4); CAM ch. 3 | DDO with an assignment account, for outstations or special cases; designated by the CGA (civil) or by the department (non-civil) |
| **Drawing and Disbursing Officer (DDO)** | RPR 2(s) | Head of Office or designated gazetted officer. Draws bills (e-bill with DSC) |
| **Head of Department (HoD)** | DFPR 3(1)(g) (not below Deputy Secretary, declared by the department) | Holds re-delegated powers (DFPR 12(2)); may authorise a gazetted officer (12(3)) |
| **Head of Office (HoO)** | DFPR 3(1)(h), 12(4) (one HoO per distinct office) | Establishment sanctions, DDO by default, pension papers (Form 6-A flow) |
| **Programme Division (PD) maker / checker; Sanction Maker / Checker** | CAM 2.3.3; SNA-SPARSH SOP | Create and approve e-sanctions in PFMS |
| **Controlling Officer / Accounts Officer** | GFR 62 (liability statement GFR 3-A monthly from October; liability register GFR 3) | Watches savings and excesses every month |
| **CGA** | RPR 2(q) | Accounting rules, consolidation, PFMS, exchequer control, internal audit standards |
| **C&AG** | Art. 148–151; GFR 238 (audit of grantees) | External audit; Appropriation and Finance Accounts are certified and laid in Parliament |

### 2.3 Attached and subordinate offices, autonomous bodies

- **Attached / subordinate offices:**
  - they are offices of the department, with HoDs and HoOs (DFPR 12);
  - they draw through the department's PAOs, or through their own CDDOs.
- **Autonomous bodies (ABs):**
  - they are grantees: GIA under GFR ch. 9, UCs in Form GFR 12-A, accounts audited by the C&AG or by CAs (GFR 237–238);
  - they are covered by the **TSA** since 01-04-2023 (S16);
  - creating a new AB is appraised by a Committee on Establishment Expenditure (CEE) and approved by the Cabinet (DFPR App.);
  - for an AB, the chief-executive post at any pay level needs the Cabinet (DFPR App., creation of posts).

---

## 3. Requirements catalogue (IFC-001 …)

### A. Organisation, seats and department registry

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-001 | Hold a versioned registry of ministries and departments (53 ministries, 2 independent departments, 50 departments per IGOD) with effective-dated create / merge / rename events from AoBR amendments | S01, S02 | P/S | M |
| IFC-002 | Model the hierarchy: ministry → department → attached / subordinate office → HoD establishment → HoO → DDO; ABs linked as grantees, not as offices | S03 (3(1)(g),(h)), S09 | P | P (core seats: generic office/seat tree, no finance-office typing) |
| IFC-003 | Model the accounting organisation separately from the administrative one: Pr.CCA/CCA/CA → Pr.AO → PAO → CDDO → DDO, and the mapping of each DDO to exactly one PAO (and of a CDDO to an assignment account) | S07 (2(k),(s),(w),(z)), S09 ch. 1–3 | P | M |
| IFC-004 | Seat types for the finance roles in §2.2 (CAA, FA/IFD, HoAO, Pr.AO, PAO, AAO, DH, Bill Distributor, DDO, CDDO, HoD, HoO, PD maker / checker, sanction maker / checker, Controlling Officer). Seat ≠ person; acting-for and additional charge as in IX.5 | S09 (2.2.2, 2.3.3), S14 | P | P (core seats and acting-for exist; finance seat catalogue missing) |
| IFC-005 | Declaring an HoD needs a department order and a rank check (not below Deputy Secretary or equivalent); declaring an HoO needs a gazetted officer, with only one HoO per distinct office | S03 Rule 3(1)(g),(h), 12(4) | P | M |
| IFC-006 | Re-delegation chain: department → HoD/Administrator → authorised gazetted officer, each by written order, versioned, with the original authority still accountable (12(3)) | S03 Rule 12(2)–(3) | P | P (powers DMN versioned; no re-delegation order object) |
| IFC-007 | Non-delegable powers must be blocked from re-delegation: re-appropriation (Rule 10), waiver of recovery of overpayment (Rule 15), scheme appraisal and approval (Rule 16) | S03 Rule 12(2) proviso | P | M |
| IFC-008 | On relieving an officer, deactivate the PFMS-equivalent user ID and DSC mapping. This is a condition for the NOC / Last Pay Certificate; issue new credentials to the incumbent; review active users periodically (report MST-01 analogue) | S09 2.2.3 | P | P (seat holding effective-dating exists; DSC deactivation link missing) |
| IFC-009 | User and role approval: the Pr.AO approves PAO, AAO and DH users; DDO and PD users are approved by the next two levels of the hierarchy | S09 2.2.2 | P | M |
| IFC-010 | Department profile flag: **DFPR 2024 does not apply** to Railways, Defence (DSE), DAE, DoS, DoT and representatives abroad. These must load their own delegation books (§O) | S03 Rule 21(2) | P | M |
| IFC-011 | Department profile flag "non-civil" (Railways, Defence, Posts, Telecom), which changes who designates CDDOs, who opens bank accounts and who approves Personal Deposit Accounts | S07 Rule 13(4),(6)(b), 2(ab), Explanation | P | M |
| IFC-012 | Autonomous-body profile: grantee type (AB / NGO / State / CPSE / CNA), TSA participation, auditor (C&AG sole auditor or CA), annual-accounts calendar | S05 GFR 237–238; S16 | P/S | M |
| IFC-013 | Posts: creating posts at SAG level and above (Pay Level 14+) and AB chief-executive posts needs the Cabinet; below SAG, the DoE. A post vacant for more than 5 years stands abolished, subject to the revival rules | S03 Appendix (creation of posts 3.6, 5.1) | P | M |
| IFC-014 | Residuary powers (incl. creation and abolition of posts) vest in the Finance Ministry. Any power not found in a delegation table must route to the FA → DoE path, not fail open | S03 Rule 6 | P | P (core powers DMN default; no FA→DoE referral rule) |
| IFC-015 | Union Territories without legislature: Administrator as a delegate (DFPR 3(1)(a)); the central treasury exists only at Chandigarh (RPR 2(i) note) | S03, S07 | P | M |

### B. Rule base, versioning and legal citation

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-016 | Every finance decision table (powers, thresholds, checklists) carries: legal source (rule / OM number and date), effective-from, effective-to and the superseded version. Running cases keep the version in force at their start unless a rule says otherwise (GP-19) | S03 Rule 21; S05 footnotes; GP-19 | P | P (GP-19 version binding exists; no legal-citation metadata) |
| IFC-017 | Load **GFR 2017** as amended, with the amendment OM per threshold, e.g. 10-07-2024 (Rules 133, 154, 155, 218), 05-05-2026 (Rule 310), 08-05-2026 (Rule 151) | S05, S06 | P/S | M |
| IFC-018 | Load **DFPR 2024** (S.O. 1543(E), 22-03-2024, from 01-04-2024, replacing DFPR 1978). Earlier sanctions remain valid unless revoked (Rule 21(1)). **Amendment of 09-06-2026 (verified pass 2, S04):** it replaces Annexure-I (Standard Object Heads) under Rule 8 with 61 heads in 8 classes, effective FY 2027-28 | S03, S04 | P | M |
| IFC-019 | Load **RPR 2022** + Subsidiary Instructions (which replaced RPR 1983 **and the Treasury Rules of the Central Government**). Keep correction slips (02-01-2024; 15-01-2025: charges of two or more major heads must not be in one bill) | S07, S08 | P | M |
| IFC-020 | Load **CAM 2024** (Revised 4th Ed.) with correction slips 1 (26-09-2024) and 2 (25-03-2025) as procedure references for the pre-check, compilation, IA and closing workflows | S09 | P/S | M |
| IFC-021 | Treasury Rules: there are no separate Central Treasury Rules after RPR 2022. The only central treasury is at Chandigarh. The platform's treasury connector applies to State IFMS (P1) and to Chandigarh, not to central PAOs | S07 (2(i) note), S08 | P | P (P1 treasury connector generic) |
| IFC-022 | Power to relax: the President may relax, add, reduce or withdraw powers for any authority (DFPR Rule 2), and the Government may relax RPR (Rule 45). Relaxation orders must be first-class, versioned overrides with a scope (authority, period) | S03 Rule 2; S07 Rule 45 | P | P (tenant DMN override GP-37; no scoped relaxation order) |
| IFC-023 | Validation sanction: delegated powers may validate an action already taken, even if the validating authority lacked competence at the time (DFPR 12(6)). This requires an "ex-post validation" case type | S03 Rule 12(6) | P | M |
| IFC-024 | Authorised departmental regulations (made with DoE consultation) may override RPR procedures for a department; the profile must point to them | S07 Rule 2(d), 44 | P | M |
| IFC-025 | Compendium of Rules on Advances (date-of-payment rule 14 cited by RPR 17(7)) and FR/SR as rule sources for pay, TA and advances **[U: FR/SR and Compendium not read in this pass]** | S07 Rule 17(7) | P (pointer) | M |
| IFC-026 | CCS (Pension) Rules 2021, GPF (CS) Rules 1960, NPS / UPS rules as rule sources (see §K) | S24–S26 | S | P |
| IFC-027 | CPWD Works Manual 2022 and the DoE Manual for Procurement of Works (2nd Ed. 2025) as rule sources for works. The DoE Works Manual 2nd Ed. 2025 is verified as listed on doe.gov.in (05-01-2026) and was amended on 18-06-2026 (S50). The CPWD edition after 2022 is still **[U]**: pass 2 found only the 2012, 2014 and 2007 manuals linked on https://cpwd.gov.in/Documents/cpwd_publication.aspx | S36; S50 | S | P (WORKS) |
| IFC-028 | Hindi + English rule text and forms (RPR forms exist in Hindi; CAM 2024 Hindi; Subsidiary Instructions Hindi) | S11 | P | C (R54/GP-38, forms bilingual) |

### C. Budget and accounting classification

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-029 | Head of account = **6 tiers / 15 digits**: Major (4, function) – Sub-major (2, sub-function) – Minor (3, programme) – Sub-head (2, scheme) – Detailed head (2, sub-scheme) – Object head (2, primary unit of appropriation) | S03 Rule 8(5)(i) | P | M |
| IFC-030 | Major-head first digit encodes the section: 0/1 revenue receipt, 2/3 revenue expenditure, 4/5 capital (4000 = capital receipts), 6/7 loans, 8 Contingency Fund and Public Account. The +2 correspondence (0401 → 2401 → 4401 → 6401) is used for validation | S10 (Coding Pattern) | P | M |
| IFC-031 | Sub-major codes: 00 when none, 80 = "General". Minor codes: 001–100 and some 750–900 are standard (001 Direction and Administration, 800 Other Expenditure, 600 where 800 is also used, 9xx = deduct receipts/recoveries; 191–198 assistance to local bodies; 791 loss/gain by exchange) | S10 | P | M |
| IFC-032 | The **LMMH master** must be loadable and updatable from CGA correction slips (current: up to slip 1127 of 09-10-2026), with history; new codes only via CGA | S10 | P | M |
| IFC-033 | **Object heads** per DFPR Annexure I (61 heads in 8 object classes: 1 Compensation to employees … 8 Accounting adjustments), with codes (01 Salaries, 02 Wages, 04 Pensionary charges, 05 Rewards, 06 Medical, 07 Allowances, 08 LTC, 09 Training, 11–29, 31–37 aid, 41 Secret service, 44 Loss in exchange, 45 Interest, 49 Other, 51–80 capital assets, 54–60 financial assets, 43 and 61–70 adjustments) | S03 Annexure I | P | M |
| IFC-034 | The object-head set can change. **Verified (pass 2):** the DoE OM of 09-06-2026 (S04) replaces DFPR Annexure-I with the harmonised Union/State list (61 heads, 8 classes) **from FY 2027-28**. New codes include 19 Digital Equipment, 23 Cost of Ration, 40 Awards and Prizes, 61 Depreciation, 63 Inter Account Transfers, 64 Write Off of Losses, and asset heads 71–80 (78 Land, 80 Intangible Assets). Object heads must be data, with old → new mapping tables effective 01-04-2027. Before seeding, check whether the IFC-033 list (taken from the S03 booklet) is the pre-2027 or the post-2027 list | S37; S03 Rule 8(4); S04 | P | M |
| IFC-035 | **Voted vs charged** must be carried on every head line (a primary unit can hold both, shown separately). Charged ↔ voted transfers are forbidden | S03 Rule 8(2), 10(4) | P | M |
| IFC-036 | **Revenue vs Capital section** of a grant; no re-appropriation across them; capital outlay ↔ loans needs DEA approval | S03 Rule 10(6), 10(10)(ii) | P | M |
| IFC-037 | **Plan / non-Plan** no longer exists: merged by the DoE OM of 05-08-2016. Keep the attribute only for history and legacy data | S22 (title and content) | P | M |
| IFC-038 | Scheme typing on the head: Central Sector (CS) vs Centrally Sponsored (CSS), Establishment vs Non-establishment, EAP flag, NER flag (lump-sum heads 2552 / 4552 / 6552), Secret Service flag | S03 Rule 10(10)(iv),(v), GoI decision (2) | P | M |
| IFC-039 | Grant / Appropriation number (Demand No.) and the department that controls it; one grant can be shared by several departments' PAOs | S09 ch. 11 | P | M |
| IFC-040 | Receipt heads, including Public Account heads (8xxx) and the TSA head 8454 with minor heads mapped to programme minor heads | S09 2.2.5 | P | M |
| IFC-041 | PAOs complete the 15-digit functional-head mapping of their DDOs' budgets; the Pr.AO completes the account-head mapping of schemes | S09 2.2.4 | P | M |
| IFC-042 | Classification check at the payment point: a bill must not mix two or more major heads (Subsidiary Instructions 2.2(5)(g), CS-2 of 15-01-2025); the object head must follow DoE communication of 01-01-2014 | S08; S09 2.1.1 | S/P | M |

### D. Budget formulation and Parliamentary authorisation

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-043 | Annual budget cycle: the DEA Budget Circular (late August) → RE (current year) + BE (next year) prepared on UBIS from actuals up to 30 September → pre-budget / RE meetings chaired by Secretary (Expenditure) (Oct–Nov) → ceilings → Demands for Grants → Union Budget (1 February) | S20 | X/S [U dates] | M |
| IFC-044 | Department-internal budget workflow: HoD / programme divisions → budget section → FA (IFD) → Secretary approval → submission. The FA completes the circular appendices by the circular date. **Verified (pass 2, S47):** Budget Circular 2026-27 of 28-08-2025 requires FAs to enter Appendices I–VII in UBIS by 03-10-2025, with pre-budget meetings from 09-10-2025. For RE 2026-27 / BE 2027-28, the circular link on dea.gov.in returned 404; the pre-budget schedule of 23-09-2026 shows meetings from 12-10-2026. That circular's date and appendix deadline stay **[U]** | S20; S23; S47 | P | M |
| IFC-045 | Receipt budget and non-tax revenue estimates, prepared with the Head of Accounting Organisation (HoAO assists the FA on NTR estimation and user-charge review) | S09 ch. 16.1.1 | P | M |
| IFC-046 | Detailed Demands for Grants (DDG) as approved must be uploaded to the financial system at the start of the year; the Pr.AO DH enters head-wise budget, the Pr.AO approves and tallies it with the DDG | S05 (PFMS rule); S09 2.2.4 | P | M |
| IFC-047 | Appropriation Act → funds available to departments (DFPR Rule 4); a sanction is operative only with funds (Rule 7) | S03 Rules 4, 7 | P | P (P1 budget check exists) |
| IFC-048 | **Supplementary Demands for Grants**: batch-wise proposals at DEA calls (categories incl. Contingency Fund advances to be recouped, court decrees that cannot be postponed); technical vs cash supplementaries [U detail] | S21 | S | M |
| IFC-049 | **New Service / New Instrument of Service**: decide by the DEA limits (OM F.1(22)-B(AC)/2022 of 23-02-2024); either prior Parliament approval or "report with next batch"; no expenditure on NS/NIS without a supplementary grant or a Contingency Fund advance (GFR "Expenditure on New Service") | S03 Rule 10(1); S05; S09 ch. 11 note 2 | P | M |
| IFC-050 | **Contingency Fund of India** advance (Art. 267(1)) for urgent unforeseen expenditure, with a withdrawal bill (RPR form "Bill for withdrawal from Contingency Fund of India") and later recoupment through a supplementary demand | S05 (GFR "advance from the Contingency Fund"); S08 form list | P | M |
| IFC-051 | **Excess grants** (Art. 115): excess over a grant is regularised only after the Appropriation Accounts, with explanatory notes through APMS | S09 ch. 11; S27 | P/S | M |
| IFC-052 | Vote on account / interim budget periods (election years): sanctions are limited to the vote-on-account amount. **Verified (pass 2):** GFR Rule 55 (Vote on Account under Art. 116; it cannot fund a New Service) and Rule 67(2) (spending beyond the provision needs a Contingency Fund advance). DEA instructions on sanctions under a vote on account stay **[U]** (DEA orders pages 1–3 checked) | S48 | P | M |
| IFC-053 | Scheme outlays tied to the Finance Commission cycle: schemes have sunset dates coterminous with FC cycles, and continuation needs re-appraisal (current cycle: **16th FC, 2026-27 to 2030-31**, verified pass 2, S52. Budget Circular 2026-27 para 2(v): no liabilities carry over from the 15th FC cycle unless specifically permitted, S47. DoE OM of 31-10-2025 on appraisal of schemes ending 31-03-2026 identified; scanned, not read) | S22 para 11; S47; S52 | P | M |
| IFC-054 | Outcome framework per CS/CSS scheme (output–outcome framework with NITI Aayog) linked to the budget lines | S22 para 12 | P | M |
| IFC-055 | Budget data exchange with UBIS / PFMS as configurable adapters (file or API), never a hard dependency (ADR-D-13) | S20; SANGAM X.0b | X | P (adapter pattern exists) |

### E. Budget execution and expenditure control

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-056 | **Allotment / distribution** of the grant to controlling and disbursing officers (DFPR Rule 9); DDO-wise allocation entered by the PAO DH and approved by the PAO | S03 Rule 9; S09 2.2.4 | P | M |
| IFC-057 | Budget availability check at sanction **and** at bill pre-check; no payment above the allotment unless specifically approved by the CAA (GFR "Excess Expenditure" (1)) | S05; S09 2.1.1 | P | C (P1 budget check + J-P1-E11) / P (CAA override missing) |
| IFC-058 | Commitment control: encumber at sanction, release on cancel or lapse | GP-11 | — | C (P1, WORKS, GEM) |
| IFC-059 | **Liability statements** in Form GFR 3-A monthly from October and a liability register in Form GFR 3 per Controlling Officer | S05 (GFR 62 area) | P | M |
| IFC-060 | Monthly savings / excess review by the HoD / Controlling Officer; the Accounts Officer reports disproportionate expenditure immediately | S05 | P | M |
| IFC-061 | **Re-appropriation** only within the same grant and section; the general restrictions of DFPR 10(1)–(8) must be enforced as hard validation rules: NS/NIS; unsanctioned expenditure; works without AA and TS; charged ↔ voted; between grants; capital ↔ revenue; from a head augmented by a supplementary; from savings in an activity with a CF advance | S03 Rule 10(1)–(8) | P | P (REAPP: amount-only DMN; none of these rules) |
| IFC-062 | CAA re-appropriation powers (10(9)): augment Salaries, Allowances, Wages, Pensionary charges, Medical, Rent-rates-taxes; Salaries → Salaries across schemes; augment heads already raised by a supplementary; NER lump sum → NER schemes; works excess up to 20 % with competent approval; within MoF-set limits; always with FA consultation | S03 Rule 10(9) | P | P |
| IFC-063 | Re-appropriations needing **Budget Division (DEA) + Secretary (Expenditure)**: from GIA-to-States savings to the revenue section; capital outlay ↔ loans; from Salaries/Allowances; EAP → non-EAP; Secret Service (plus C&AG if ≥ 25 %); from Buildings/Infrastructure/Other fixed assets; works excess > 20 %; beyond MoF limits; back to a head previously re-appropriated from | S03 Rule 10(10) | P | M |
| IFC-064 | DoE OM of 01-04-2024: **no re-appropriation in Q1** without MoF; none from CS/CSS savings to establishment without MoF; NER 10 % savings normally not available; establishment proposals go to Pers. Division DoE, others to DEA Budget Division | S03 GoI decision (1) | P | M |
| IFC-065 | Monetary limits of departmental re-appropriation by object-head group: **₹2 crore** (establishment: Office Expenses, Other Revenue Expenditure, Domestic / Foreign Travel); **₹5 crore** (Minor Works, Professional Services, Rewards, LTC, Training, Materials, Ration, Fuel, Minor civil/electrical works, Repair and Maintenance, Bank charges, Loss in exchange); **₹15 crore** (all other) | S03 GoI decision (1) para 3(v) | P | M |
| IFC-066 | **Reporting to Parliament**: any re-appropriation raising a line item (ending at an object head) by more than 20 % of BE or ₹100 crore (whichever is more) is reported with the last batch of supplementaries; after the last batch, prior DoE approval | S03 GoI decision (1) para 4 | P | M |
| IFC-067 | NER technical re-appropriation from 2552 / 4552 / 6552 to functional heads: delegated to the Secretary (also in Q1), re-delegable to JS; not for MDoNER | S03 GoI decision (2) (OM 03-06-2024) | P | M |
| IFC-068 | All re-appropriation and surrender orders are generated in the financial system and pushed to the PAO / Pr.AO for entry | S05 (PFMS rule (5)); S09 2.2.4 | P | P (REAPP output exists; no accounting push) |
| IFC-069 | **Surrender of savings** to the Finance Ministry by the dates the Ministry prescribes; surrender as soon as savings are foreseen; no reserve for future excesses; MoF communicates accepted surrenders to the Accounts Officer before year-end | S05 GFR 62(1)–(2); S44 (example cut-off 10-02-2026) | P | M |
| IFC-070 | **Lapse**: unutilised funds lapse at the close of the financial year; no carry-forward unless a rule provides one | S05 GFR 62(1) | P | M |
| IFC-071 | **Rush of expenditure** control: monitor against the Monthly Expenditure Plan / Quarterly Expenditure Plan; the FA ensures adherence. **Verified (pass 2):** GFR 62(3) (rush in the closing months is a breach of financial propriety; the FA enforces the MEP) and 62(4) (QEP). Budget Circular 2026-27 para 16 makes DEA OM 12(13)-B(W&M)/2020 of 25-05-2022 (Modified Exchequer Control Based Expenditure Management, its Annexure R) the governing instrument: MEP/QEP are filed in UBIS, and deviations need Secretary (Expenditure) approval. The percentage ceilings (33 % / 15 %, or 25 % / 10 %) stay **[U for the current year]**: Annexure R is scanned and was not OCR'd. Ceilings must be configurable | S05 GFR 62(3)–(4); S42; S43; S47 | P (rule) / S (ceilings) | M |
| IFC-072 | Cash-management rules: release beyond the QEP needs prior Budget Division consent; quarterly savings do not carry forward automatically | S42 | S | M |
| IFC-073 | Excess over a head: the FA and CAA must ensure funds through re-appropriation or supplementary before concurring (GFR "Excess Expenditure" (2), Appendix 10) | S05 | P | M |
| IFC-074 | Year-end payment cut-offs set by CGA circular (e.g. assignment advice valid until 24:00 on 31-03-2026) are configurable calendar events that close the bill and payment workflows | S19 | S | M |

### F. Sanctions, scheme appraisal and delegation of financial powers

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-075 | Every expenditure needs **sanction + appropriation**. A recurring sanction becomes operative with first-year funds and continues subject to yearly appropriation | S03 Rule 7 | P | P |
| IFC-076 | **New principle or practice** increasing future expenditure → prior Finance Ministry consent (routing rule) | S03 Rule 5(1) | P | M |
| IFC-077 | **Uniform sanction format** (RPR-100), with head of account, amount, payee and the IFD dy./U.O. number | S08 RPR-100; S09 2.1.2 | P | M |
| IFC-078 | Sanction communication clause: "issues with the concurrence of the Ministry of Finance …" (DoE consent) or "issues with the approval of competent authority; advice of IF conveyed vide Dy./U.O. No. … dated …" (FA advice). The sanction is endorsed to the PAO / audit | S03 Rule 20; S09 2.1.2 | P | M |
| IFC-079 | e-Sanction in the system by Sanction Maker → Sanction Checker (DSC), from the HoO (establishment) or the head of division (scheme) | S09 2.3.3; S08 2.2(1)(c) | P | P (core sanction + signing; no maker-checker sanction object) |
| IFC-080 | **Scheme / project appraisal by outlay**: up to ₹100 cr appraised by the FA; > ₹100–500 cr by SFC/DIB chaired by the department Secretary; > ₹500 cr by EFC/PIB chaired by Secretary (Expenditure), except notified dispensations | S22 (DFPR App. III table) | P | M |
| IFC-081 | **Scheme / project approval by outlay**: up to ₹100 cr by the Secretary; > 100–500 cr by the Minister-in-charge; > 500–1,000 cr by the Minister + Finance Minister; > ₹1,000 cr by the Cabinet / Cabinet Committee. Limits apply to the total size incl. EBR, external aid and state share | S22 | P | M |
| IFC-082 | Appraisal timeline template: DPR / EFC memo → comments (DoE, NITI Aayog, ministries) in 4 weeks → final memo 2 weeks → meeting → minutes → on-file approvals → Cabinet note (> ₹1,000 cr); "trigger mechanism" escalation when any stage overruns | S22 (time-frame table) | P | P (SLA escalation GP-06 exists; template missing) |
| IFC-083 | Pre-investment activities up to ₹100 cr can be approved by the Secretary with FA concurrence; above that, the full appraisal | S22 | P | M |
| IFC-084 | Procurement and contract sanction (DFPR 11): the Secretary up to **₹100 cr** (open / limited tender) and **₹25 cr** (negotiated / single / proprietary); above that, the Minister; re-delegation to HoDs with FA consultation; PIB/EFC/Cabinet-approved scheme limits override | S03 Rule 11 | P | P (TENDER/GEM authority DMN illustrative) |
| IFC-085 | Grants-in-aid, scholarships and loans: full powers to departments, provided the scheme rules were framed with Finance Ministry consent and the sanction carries a certificate to that effect | S03 Rule 17 | P | P (P3) |
| IFC-086 | Trading operations (purchase for sale, price fixation) need Finance Ministry concurrence unless below ₹25 cr (clauses (a), (b)) | S03 Rule 18 | P | M |
| IFC-087 | Insurance of Government property is prohibited without Finance Ministry consent | S03 Rule 14 | P | M |
| IFC-088 | Dismantling public buildings: full power with FA concurrence, subject to conditions (no other department needs it; certified unsafe or uneconomic; auction through CPWD). Purely temporary structures (life ≤ 2 years): full power | S03 Rule 19 | P | P (ASSETS condemnation; no buildings rule) |
| IFC-089 | Delegation DMN inputs must include: power type, authority seat, object-head group, procurement mode, amount (per case / per occasion / cumulative), scheme approval link and FA-consultation flag. Amount-only tables (the current packs) are insufficient | S03 Rules 10–19 | P | P |
| IFC-090 | Anti-splitting: sanctions or orders must not be split to bring them within a lower authority's power (losses from one cause written off at one time; DFPDS rule) | S03 Rule 13 note 8; S29 | P/X | M |

### G. Claims, bills and payments

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-091 | **e-Claim** by the claimant (employee or supplier) with login, key invoice fields (invoice no./date, PO no.; ticket / PNR for personal claims), scanned supporting documents with serial number, date and signature; if the claimant is unregistered, the Sanction Maker prepares the e-claim and signs it | S09 2.3 (procedure for e-claims) | P | M |
| IFC-092 | **e-Bill flow**: claimant → Sanction Maker → Sanction Checker (DSC) → DDO e-bill (DSC) → PAO pre-check and pass (DSC) → payment authorisation (DSC) → bank → e-scroll; SMS to the beneficiary; documents in an integrated DMS | S09 2.3.3; S12 | P/S | P (P1 maker → checker → approver; roles and DMS missing) |
| IFC-093 | **PAO internal 3-level pre-check**: Dealing Hand → AAO → PAO. Only the PAO can finally return a bill, with observations; the DDO resubmits after addressing them | S09 2.2.8 | P | P (core recommendation levels; PAO-only return rule missing) |
| IFC-094 | **Bill Distributor** role with assignment / reassignment and pull-back to enforce **FIFO** processing in the PAO | S09 2.3.3 (iv)(a) | P | M |
| IFC-095 | **Token number** generated when a bill is received (auto-generated on DDO signature for e-bills); token issue register; same-day receipt; AAO verification on the 20th of each month | S09 2.2.6–2.2.7 | P | M |
| IFC-096 | **Pre-check content**: provision of funds, conformity with the Act and rules, a valid sanction (special or general), correct classification, arithmetic and entitlement checks; "independent of the proposal of the sanctioning authority" | S09 2.1.3–2.1.5 | P | P (checker scrutiny generic) |
| IFC-097 | **Standard checklists per bill type** (CGA Standardised Audit Formats: checklists for PAOs with/without COMPACT and for DDOs) loaded as data | S11 | S | M |
| IFC-098 | **Two signatures** (two DSCs) on any cheque or e-payment of **₹10 lakh and above**; the second signatory is nominated by the HoAO | S09 2.2.9 note | P | P (joint approval above threshold J-P1-E09; signatory nomination missing) |
| IFC-099 | Batch payment file signed by the PAO (DSC) → bank; success / failure / failure-after-success reporting via e-scroll | S09 2.2.9, 2.3.3 | P | C (core execution + reconciliation, UNKNOWN_OUTCOME) |
| IFC-100 | Payment modes: payment advice (e-payment), cheque, DD, postal or money order, cash. Payments above the prescribed limits only by payment advice; salary to bank or post-office accounts, with cash only under HoD + HoAO relaxation or classified operations | S07 Rule 2(c), 14(4)–(5) | P | P |
| IFC-101 | **Date of payment** rules per mode (upload to the bank gateway or the due date, whichever is later; RBI scroll date; foreign remittance date; MO receipt date; cheque rules) | S07 Rule 17 | P | M |
| IFC-102 | **Cheque validity 3 months**; a time-barred cheque is returned and re-issued after verification; no alterations; lost-cheque indemnity (RPR-12) | S07 Rule 16; S08 (time-barred cheque) | P | M |
| IFC-103 | **Time-barred claims**: arrear claims preferred within 2 years of falling due are settled by the DDO / AO after the usual checks; older claims need the GFR 295–297 procedure before pre-check | S05 GFR 295–297; S08 | P | M |
| IFC-104 | **Bill-type catalogue** (RPR 2022 forms, replacing the GAR forms; see §5.3): pay bill (RPR-13/13A), NPS nil bill (RPR-13B), TA tour / transfer / LTC claim-cum-bill (RPR-14A/B/C), consolidated TA (RPR-14), medical CGHS / CS(MA), contingent advance / adjustment / fully-vouched (RPR-29), imprest recoupment (RPR-29A), refund of revenue (RPR-33), GIA / subsidy / contribution / investment / loan bill (RPR-34), HBA / computer advance, short-term advances, GPF / CPF withdrawal and final payment, refund of deposit, CGEGIS, GeM e-bill, retirement benefits, CFI withdrawal, GST refund e-bill | S08 forms index | P | P (P1 single generic bill; SALARY pay bill) |
| IFC-105 | **Last Pay Certificate** (RPR-2): first pay claim after transfer needs the LPC of the previous office; arrears are drawn before the LPC issues | S08 2.1(6); S09 19.1.1 | P | M |
| IFC-106 | **Nil bills** (net payable zero): adjustment of advance, NPS, one debit with one or more credit heads; multi-head, multi-grant allowed; voucher number on passing | S09 2.2.11 | P | M |
| IFC-107 | Advances and adjustment: contingent advance, imprest (GFR 322) and adjustment bills with RPR-28 statements; limits fixed by the HoD with IFD | S08 (RPR-28, contingent limits) | P | M |
| IFC-108 | **CDDO payments** from an assignment account against assignment by the PAO; the CDDO renders accounts and records to the PAO; recovery on PAO intimation (RPR 19) | S07 Rule 13(2)–(3), 19; S09 ch. 3 | P | M |
| IFC-109 | **Recoveries ordered by the PAO** in post-check (or missed in pre-check) are made in the next bill of the same type | S08 (recoveries ordered by PAO) | P | M |
| IFC-110 | Duplicate-bill and similar-claim detection (invoice number, payee, amount, period) | GP / J-P1-E03 | — | C (P1) |
| IFC-111 | Approval bound to the payload hash: change after approval invalidates (AC-02) | VI.4 | — | C (P1, core) |
| IFC-112 | **DSC** for claimant, sanction maker / checker, DDO, PAO and the second signatory; DSC repository; Pr.AO approves PAO DSC enrolment | S12; S09 2.2.4, 2.3.2 | P/S | P (core signing via eSign / DSC agent R57; DSC enrolment approval missing) |
| IFC-113 | **e-Bill post-check** by internal-audit users of the ministry (separate PFMS module since 2026); a sampling and objection workflow back to the DDO / PAO | S13 | S | M |
| IFC-114 | Documents retained digitally (e-bill, invoice, sanction, sub-vouchers) for post-check and audit; vouchers and sub-vouchers defined (RPR 14(2)–(3)) | S07 Rule 14; S09 2.3.2 | P | P (RECORDS retention; voucher model missing) |
| IFC-115 | GeM purchase e-bill (dedicated RPR form) with a GeM order / CRAC reference, three-way match of order, receipt and invoice | S08 form list; UP-14 | P | P (GEM + UP14) |
| IFC-116 | Refund of revenue: sanction regulated by the rules; bill RPR-33 with the original challan or an attested statement; passport-fee refunds by the RPO | S07 Rule 22; S08 | P | M |
| IFC-117 | Death of an employee: pay and allowances for the day of death; arrears to heirs with an indemnity bond (RPR-26) | S07 Rule 14(7); S08 | P | M |
| IFC-118 | Payment through RBI for transfers to States (loans / grants through RBI by the Pr.AO) | S09 1.2 (Pr.AO functions) | P | M |
| IFC-119 | **Classified / secret-service payments**: payee names not disclosed; certificate by the disbursing officer in lieu of receipt; secret-service head rules | S08 2.16(6); S03 Rule 10(10)(v) | P | M |
| IFC-120 | Entertainment and other certificate-backed claims: the system enforces mandatory certificates per claim type | S08 2.16 | P | M |
| IFC-121 | Overpayment waiver workflow: a recovery order within 1 month of detection; waiver up to ₹2 lakh per individual by the department with FA (recommended by the FA, approved by the Secretary); above ₹2 lakh to the DoE with a 13-point checklist and the disciplinary-authority report | S03 Rule 15 + GoI decision | P | M |
| IFC-122 | Payments to foreign payees and missions in foreign currency through the accredited bank advice; the bank claims the INR equivalent via the payment scroll (see §O MEA) | S07 Rule 13(8) | P | M |
| IFC-123 | Interest on delayed payments and DBT / vendor statutory deductions (TDS, GST-TDS) on bills. *Pass 2:* the booking head for GST-TDS is verified as **8658-00-139** (LMMH CS 932 of 04-06-2019; DAMA OM 468). Deduction rates (CGST s.51; Income-tax s.194C/194J) and MSMED Act interest stay **[U]** (search budget ran out before cbic-gst.gov.in and incometaxindia.gov.in) | S10; S53 | P (head) / [U] | M |
| IFC-124 | Integration: payment, sanction and status exchange with PFMS (SFTP + API, SSID; models MIS-only / MIS+payment / UI / State IFMIS) | S40; SANGAM IX.7d | S | P (R58 connector design; simulator) |
| IFC-125 | Round-off rule: fractions of a rupee rounded per RPR 41 | S07 Rule 41 | P | M |

### H. Receipts and non-tax revenue

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-126 | Authorised receipt modes: e-modes (net banking, cards, RTGS / NEFT), cheque, DD, postal order, MO, cash | S07 Rule 2(b) | P | M |
| IFC-127 | Challan / e-challan (RPR-7 / 7E), pay-in-slip (RPR-1), receipt / e-receipt (RPR-6 / 6E); guarantee-fee challan (RPR-7A) | S07 Rule 8; S08 forms | P | P (CHALLAN expects challans; forms missing) |
| IFC-128 | Date of receipt per mode (RPR 10) and acknowledgement (RPR 9) | S07 Rules 9–10 | P | M |
| IFC-129 | **Bharatkosh**: purpose / functional head mapping per department, CPIN generation, NEFT / RTGS with auto-UTR via e-Kuber 2.0 (from 22-02-2025); RBI credit notification → auto-reconciliation; BK-09 reconciliation and BK-10 scroll report equivalents | S17 | S | P (CHALLAN generic scroll match) |
| IFC-130 | Unaccounted-receipt SOP: NEFT / RTGS credits without a matching UTR or credit notification are traced, booked or refunded; target the gap between the RBI CAS Nagpur balance and MH 8675 | S17 (OM 342, 31-12-2024) | S | P |
| IFC-131 | Card-collection settlement by the accredited bank within **T+3 working days**, excluding the convenience fee; settlement-lag monitoring | S17 (OM 15-06-2026) | S | M |
| IFC-132 | Purpose rationalisation in Bharatkosh (redundant purposes merged, CGA order 567 of 19-12-2025) means purpose masters are versioned | S10 news list (Order 567) | S | M |
| IFC-133 | NTR responsibilities: the HoAO assists the FA in estimation, collection, reconciliation and periodic review of user charges, licence fees, royalties and dividends | S09 16.1.1 | P | M |
| IFC-134 | Tax-receipt accounting for CBDT and CBIC (separate chapters: banking, reconciliation, refunds) is a department profile with its own receipt flows | S09 ch. 14–15 | P | M |
| IFC-135 | Department-specific receipt portals feeding the government account (e.g. DoT SARAS for licence and spectrum fees) as adapters | S32 | S | M |
| IFC-136 | Receipt and expenditure deduct heads (9xx) and "Receipts awaiting transfer" (500) / "Expenditure awaiting transfer" (502) are used in clearing workflows | S10 | P | M |
| IFC-137 | Guarantee fees and government guarantees register (RPR 26) | S07 Rule 26 | P | M |

### I. Banking and reconciliation

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-138 | **Accredited bank** per ministry (nominated by RBI on CGA advice); PAO and CDDO accounts at identified branches; a branch change needs RBI / CGA steps; focal-point branch | S05 (GFR banking rule); S07 2(a), 13(6); S09 ch. 4 | P | M |
| IFC-139 | Payment and receipt scrolls (e-scrolls) from the bank / RBI, matched to vouchers and challans daily; failed and returned credits re-issued under a new operation | S07 2(y),(ad); S09 ch. 13 | P | P (CHALLAN, core reconciliation) |
| IFC-140 | Monthly **DDO-level reconciliation** of expenditure with the PAO books. **Verified (pass 2):** CAM 2024 para 2.19.12. The DDO sends the PAO a monthly reconciliation certificate in **CAM-90**, checked against PFMS reports PAO-01 and CMP-03, by the last day of the following month (GFR 57(5)) | S09 | P | M |
| IFC-141 | Reconciliation of the RBI cash balance (CAS Nagpur) with the accounts (MH 8675) | S17 | S | M |
| IFC-142 | Personal Deposit Accounts: opened only with CGA (civil) or competent (non-civil) approval, for the listed purposes; list maintained; reconciled | S07 Rule 13(9)–(11) | P | M |
| IFC-143 | Departmental treasure chests (military) and cash book rules (RPR 20, 39) | S07 Rules 20, 39 | P | M |
| IFC-144 | e-Kuber (RBI) as the settlement rail for CSS releases, TSA and Bharatkosh, as configurable adapters | S14, S17 | P/S | P (adapter pattern) |

### J. Grants-in-aid, fund flow, CNA / SNA / TSA, utilisation and assets

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-145 | Register all implementing agencies down to the last level in the financial system, to track fund flow and unspent balances | S05 (PFMS rule (2)) | P | M |
| IFC-146 | Release "just-in-time" wherever possible | S05 (PFMS rule (3)) | P | M |
| IFC-147 | **Unspent balance** of a previous grant considered before each new release; check the recipient's bank balance before release | S05 GFR 230(7) | P | M |
| IFC-148 | **Recurring grants**: release for the next year only after a provisional UC; release above **75 %** of the next year's sanction only after the UC and audited statement of the previous year | S05 GFR 238(2) | P | M |
| IFC-149 | **UC Form GFR 12-A** (institutions / ABs / NGOs) within **12 months** of the close of the financial year, output-based, targets reached or reasons; non-submission allows blacklisting | S05 GFR 238(1) | P | M |
| IFC-150 | **UC Form GFR 12-C** for central grants to State Governments for a scheme, countersigned by the Administrative Secretary / Finance Secretary; e-UC module for CSS | S05 (State UC rule); S39 | P/S | M |
| IFC-151 | Form GFR 12-B certificate for loans (conditions fulfilled) at agreed intervals | S05 (loans rule) | P | M |
| IFC-152 | All grantee UCs submitted in the financial system | S05 (PFMS rule (6)) | P | M |
| IFC-153 | **Register of Grants** (Form GFR-21 info used for subsequent grants) | S05 GFR 234–235 | P | M |
| IFC-154 | Grantee accounts: subsidiary accounts and audited statements; C&AG access under s.14/15 DPC Act; CA audit otherwise; audit fees | S05 GFR 236–238 | P | M |
| IFC-155 | **AB annual-accounts calendar**: accounts to audit by 30 June; SAR by 31 October; annual report and accounts to the nodal ministry for Parliament by 31 December; laying rules (incl. societies with one-time grants ≥ ₹5 crore) | S05 GFR 237 | P | M |
| IFC-156 | **EAT module**: expenditure, advance and transfer by central-sector grantees recorded in the financial system; no further release without active EAT use | S38; S09 1.2 (EAT monitoring) | S/P | M |
| IFC-157 | **CNA model** (Central Sector Schemes): one CNA per scheme; CNA can be an AB / CPSE / state agency, not a bank; Model 1 (TSA) for schemes ≥ ₹100 cr, Model 2 (CNA account in a scheduled commercial bank); interest earned deposited into the CFI | S15 | S | M |
| IFC-158 | **TSA for ABs**: assignment-based drawing limits; accounting through MH 8454; universal since 01-04-2023; hybrid model SOP (2025) | S16; S09 2.2.5 | S/P | P (WORKS / DBT mention TSA only as text) |
| IFC-159 | **SNA-SPARSH (CSS JIT)**: mother sanction per CSS/State/SLS with head allocation → state IFMS pushes claims → PD Checker creates the **daily sanction** (DSC) → Pr.AO payment authorisation (DSC) → PAO pre-check and pass (DSC) → batch to RBI debiting the ministry's RBI account and crediting the State's; return of payment files by the PD | S14 (SOP 11-08-2025) | P | M |
| IFC-160 | The previous 7-role flow (PD maker, PD checker, Pr.AO, Bill Distributor, PAO DH, PAO AAO, PAO) must remain configurable as an alternative chain; the revised 3-role flow is the default | S14 | P | M |
| IFC-161 | SNA (single nodal account) per CSS per State; return of unspent SNA and treasury balances; state share matching. *Pass 2:* there is no January 2024 OM in the DoE SNA/CNA list (60 entries, https://doe.gov.in/public-finance-state-pfms-cna-sna). The relevant OMs are 20-05-2022 (transfer to the CFI of funds not moved to the SNA), 07-07-2022 and 14-09-2022 (return of central share not released by the treasury) and 30-11-2022 (relaxation for sub-agencies). For central sector schemes, the CNA master circular of 21-05-2024 (S49) paras 52–53 require return of unspent amounts, and para 47 credits interest to the CFI via Bharatkosh | S14; S49 | S/P | M |
| IFC-162 | DBT: beneficiary registry, Aadhaar-seeded payments, partial-failure handling, resend only failures | GFR (DBT rule); GP-49 | P | C (DBT, P3) |
| IFC-163 | Assets created from grants: a register of assets acquired wholly or substantially from grants, with restrictions on their disposal. **Verified (pass 2):** GFR Rule 230(9) bars disposal without the sanctioning authority's approval. GFR 2017 prescribes **no form** for the grantee's asset register. Form GFR 21 (Rule 234) is the register of grants; Form GFR 22 is the government's own fixed-asset register. Sangam must define the grantee register itself | S05 | P | M |
| IFC-164 | Grants to voluntary organisations / NGOs: eligibility (registration, NGO-Darpan), inspection, release in instalments. *Pass 2:* GFR 230–231 do not mention NGO-Darpan (V). The Darpan Unique ID requirement comes from NITI Aayog: notification of 05-05-2022 identified but not read (HTTP 403): https://www.niti.gov.in/sites/default/files/2023-08/NotificationNIT-05052022.pdf | S05 GFR 231 | P / S | P (P3) |
| IFC-165 | Interest earned on grants and CNA balances returned to the CFI per OM; refunds of unspent grant through receipt challan | S15; S05 | S | M |

### K. Establishment finance: salary, pension, GPF, NPS / UPS, advances

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-166 | Monthly pay bill (RPR-13) from the HRMS / EIS with pay-bill register, abstract, absentee statement, increment certificate, court attachments (RPR-17…23B) | S08 forms | P | P (SALARY) |
| IFC-167 | Salary paid by payment advice to bank / POSB accounts; EIS e-bill (Phase II from 12-2024) | S07 Rule 14(5); S12 | P/S | P |
| IFC-168 | Pay of the President, VP and Ministers on separate forms (RPR-15/16) | S08 | P | M |
| IFC-169 | Work-charged establishment wages on the pay-bill form or a departmental form | S08 2.30 | P | M |
| IFC-170 | Foreign service / deputation: recovery of leave-salary and pension contributions, payments and accounting | S09 ch. 9 | P | M |
| IFC-171 | **GPF** (old-pension employees): accounts kept by the accounting organisation; annual subscription ceiling **₹5 lakh** (G.S.R. 96, 15-06-2022); minimum 6 %; withdrawals sanctioned and paid within **15 days** [U: 2017 OM]; final payment; annual statements | S09 ch. 6; S26 | P/S | M |
| IFC-172 | **NPS**: government contribution via a nil bill (RPR-13B), PRAN on the LPC, credit timelines and compensation for delayed credit (UPS rules) | S08; S25 | P/S | M |
| IFC-173 | **UPS** option (from 01-04-2025): option exercise, switch UPS → NPS (time-bound before retirement), assured payout eligibility, employer contribution. **Verified (pass 2, S46):** Rule 11: a one-time switch back to NPS, at the latest 12 months before superannuation (or 3 months before deemed VR, or at resignation / FR 56(j)); not allowed while proceedings are pending. Rule 13(6)–(7): VR before 25 years gives a pro-rata payout; from 25 years, the full payout. Rule 16: absorption after 10 years or more counts as superannuation. Payout figures (DFS FAQs with S25, S): full payout = 50 % of the 12-month average basic pay after 25 years; pro-rata from 10 to 25 years; minimum ₹10,000 a month after 10 years. PFRDA UPS Regulations 2025 not read [U] | S25; S46 | P | M |
| IFC-174 | **Pension case** (verified pass 2 from S45, CCS (Pension) Rules 2021). HoO starts 1 year before retirement (r.56); service verification by 8 months before (r.57(1)(a)(vii)); the employee submits Forms 4 and 6 / 6-A online **no later than 6 months before** (r.57(2)(a)); HoO completes Form 7 Part I by 4 months before (r.59); the case reaches the Accounts Officer by 4 months before (r.60(4)); PPO issued 2 months before retirement (r.63(a); 45 days in other cases); Accounts Officer → CPAO within 2 months of receipt (r.63(4)(a)); **CPAO → bank Special Seal Authority within 21 days** (r.63(4)(b)) | S24; S45 | P | P (PENSION: sanction + monthly; no Form 6-A / CPAO stages) |
| IFC-175 | **CPAO** scope: civil pensions except Railways, Posts, Defence; also NCT Delhi, UTs, former MPs, judges, AIS, freedom fighters, NPS family pensions | S07 Rule 2(h) | P | M |
| IFC-176 | e-PPO (PPO booklet form CAM-52 revised 28-11-2024) pushed to DigiLocker | S09 (CAM-52 slip); S24 | S | P (P4 DigiLocker adapter exists) |
| IFC-177 | Retirement benefits bill (commutation, gratuity, leave encashment) on the dedicated RPR form; gratuity payment rules (RPR 32) | S08; S07 Rule 32 | P | P |
| IFC-178 | Pension disbursement by authorised banks; life certificate (Jeevan Pramaan, including face authentication, accepted per the CPAO Scheme booklet 2026, S55; verified pass 2); revision; family pension; undrawn arrears; death of the pensioner (RPR 27–37) | S07 Rules 27–37 | P | P (PENSION monthly, life certificate form) |
| IFC-179 | Pension computation per CCS (Pension) Rules 2021 (qualifying service, emoluments, minimum / maximum, commutation table). **Verified (pass 2, S45):** r.44(1): eligible after 10 years' qualifying service; pension = 50 % of emoluments or of average emoluments, whichever is more beneficial; minimum ₹9,000 and maximum ₹1,25,000 a month. r.44(2): service gratuity below 10 years. r.31: emoluments. r.32: average of the last 10 months. r.11–29: qualifying service. Commutation (40 % limit and table) is in the CCS (Commutation of Pension) Rules 1981 (S; the rule number for 40 % was not read [U]) | S24; S45 | P | P |
| IFC-180 | Long-term advances (HBA, computer) and short-term advances (TA, LTC, medical); recovery schedules (RPR-36) | S08 | P | M |
| IFC-181 | CGEGIS / GIS accumulation claims (RPR forms) | S08 | P | M |
| IFC-182 | Medical claims CGHS / CS(MA) bills | S08 | P | M |
| IFC-183 | Children Education Allowance, OTA and other personal claims (RPR-48 group) | S08 | P | M |
| IFC-184 | Pay Commission / DA revisions as rule-version events with bulk arrear computation and drawal (GP-47 style bulk recompute). **Verified (pass 2, S51):** the 8th CPC was constituted by Resolution of 03-11-2025 (Chair: Justice Ranjana Prakash Desai), with its report due within 18 months (about May 2027). Plan a pay-revision rule-version event for FY 2027-28 or later | S51 | P | P (GP-47 pattern exists in recruitment) |
| IFC-185 | Retirement-related no-dues: deactivation of DSC / user ID, LPC and recoveries (IFC-008) | S09 2.2.3 | P | M |

### L. Procurement, works, stores and assets

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-186 | **GeM mandatory** for goods and services available on GeM; direct purchase up to **₹50,000**; above ₹50,000 to ₹10 lakh from the L1 GeM seller among at least 3 [U: "at least 3" not re-read]; above that via bid / reverse auction; automobiles without ceiling | S05 GFR 149 | P | P (GEM mode DMN illustrative) |
| IFC-187 | Outside GeM: purchase without quotation up to **₹50,000** with a certificate (₹2 lakh for scientific departments); Local Purchase Committee above ₹50,000 to ₹5 lakh (scientific: higher limits) | S05 GFR 154–155 | P | P |
| IFC-188 | Limited / advertised tender thresholds. **Verified and corrected (pass 2):** GFR Rule 162(i): LTE **up to ₹50 lakh** (DoE OM F.1/3/2024-PPD, 10-07-2024). The **₹1 crore-and-above** LTE limit applies **only to scientific ministries/departments** (DST, DBT, DSIR, DAE, Space, MoES, DRDO, DHR/ICMR) and research institutions, for scientific equipment (DoE OM F.20/42/2021-PPD, 05-06-2025). The threshold is keyed on department class | S05; S48 | P | P (TENDER) |
| IFC-189 | Scientific ministries / departments list for relaxed procurement limits: DST, DBT, DSIR, DAE, DoS, MoES, DRDO, ICAR (+ institutions and universities), DHR / ICMR, … | S05 footnotes | P | M |
| IFC-190 | Works: a ministry may execute repair works up to **₹60 lakh** directly; above that, and original works of any value, through a Public Works Organisation (CPWD, State PWD, MES, BRO, etc.); scientific departments may assign repairs up to ₹5 cr on nomination (special provision to 31-03-2025, then review [U current]) | S05 GFR 133 | P | P (WORKS) |
| IFC-191 | Works sanction sequence: administrative approval and expenditure sanction → technical sanction → funds; no work begins without AA/ES (and no re-appropriation to works without AA and TS, DFPR 10(3)) | S36; S03 10(3) | S/P | C (WORKS chained sanctions GP-05) |
| IFC-192 | Works excess over the sanctioned cost: up to 20 % by the CAA with competent approval; above 20 % needs Budget Division (DEA) approval | S03 10(9)(v), 10(10)(vii) | P | P (GP-42 cumulative threshold) |
| IFC-193 | **Write-off of losses** per DFPR 13 table: department ₹5 lakh (stores: theft / fraud / negligence) / ₹50 lakh (other); Administrators ₹2 lakh / ₹5 lakh; revenue (DoR full; others ₹5 lakh); HoD re-delegation up to ₹5,000 (revenue) or 10 % of department power (stores); one write-off per cause; quarterly statement to the IFD | S03 Rule 13 + GoI decision | P | P (STORES write-off DMN illustrative) |
| IFC-194 | Vehicle condemnation by life norms (HCV 4 lakh km / 10 yrs; LCV 1.5 lakh km / 6.5 yrs; motorcycles 1.2 lakh km / 7 yrs, whichever is later) with a workshop certificate; scrapping at 15 years via an RVSF; disposal within 3 months of the replacement order | S03 Rule 13 table | P | P (ASSETS) |
| IFC-195 | Disposal of goods (GFR 217–218; ₹4 lakh limit in Rule 218 after 10-07-2024) and sale accounts | S05 | P | P (ASSETS) |
| IFC-196 | Stock verification and discrepancies (GFR ch. 7) | S05; GP-55 | P | C (STORES) |
| IFC-197 | Contracts executed by an authorised officer in the President's name (GFR 224–225); BG / EMD / performance security lifecycle | S05 | P | C (SECINST, CONTRACT) |
| IFC-198 | Debarment of bidders per amended Rule 151 (convictions under the BNS etc., OM 08-05-2026) | S06 | S | P (TENDER: debarment check not modelled) |

### M. Accounts compilation, closing, Appropriation and Finance Accounts

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-199 | The PAO is the basic accounting unit and compiles at object-head level; monthly account PAO → Pr.AO → CGA | S09 5.1.1 | P | M |
| IFC-200 | **Monthly account to the CGA by the 8th** of the following month; pre-submission checks for recurring errors (e.g. expenditure without budget provision) | S18 | S | M |
| IFC-201 | e-Monthly DO by the Pr.AO by the **12th** | S09 5.9.6 | P | M |
| IFC-202 | **March accounts in three cuts**: March (Preliminary), March (Supplementary-I), March (Supplementary-II), each with a closing date and a Pr.AO submission date set yearly by the CGA (2025-26: **Sy-I last date 30-04-2026**, late adjustments by 06-05-2026; verified pass 2 from DAMA OM 482 of 31-03-2026 (S53), which cites CGA Order 469 of 03-03-2026. The DAMA 468 OM lists checkpoints, not dates. The March Preliminary date and the Sy-II dates (brought forward by Order 16 of 04-05-2026, title only) stay **[U]**) | S18; S53 | P / [U] | M |
| IFC-203 | **Statement of Central Transactions** (SCT) after March Sy-I: in thousands, to minor-head level, charged / voted, receipts and public account; closed months cannot be altered, so corrections go by journal entries | S09 5.10 | P | M |
| IFC-204 | **Appropriation Accounts (Civil)** per grant: original + supplementary, re-appropriations, actuals, savings / excess; signed by the CAA; audited; to the CGA; explanatory notes for large savings / excess and for NS/NIS regularisation | S09 ch. 11; S03 | P | M |
| IFC-205 | **Finance Accounts** of the Union (civil + Defence + Railways + Posts + Telecom): revenue and capital, public debt, liabilities and assets statements | S09 ch. 12 | P | M |
| IFC-206 | Appropriation audit registers kept by the Pr.AO | S09 1.2 | P | M |
| IFC-207 | Inter-departmental and inter-governmental adjustments (by cheque / transfer entry / through RBI), with closure dates for adjustments | S09 ch. 8; S05 GFR 117–123 | P | M |
| IFC-208 | Suspense heads and their clearance (CGA Suspense Accounts Manual) | S11 | S | M |
| IFC-209 | Loss / gain by exchange (minor head 791 / object head 44) for foreign-currency transactions | S10; S03 Annex I | P | M |
| IFC-210 | Accounts of loans, advances, guarantees and investments; loan ledgers, interest rates notified (RPR 23–24) | S07; S09 ch. 10 | P | M |
| IFC-211 | Ledger and summary of balances; journal entries for misclassification corrections | S09 ch. 5 | P | M |
| IFC-212 | Accrual-ready data: the "new chart of accounts likely to be implemented" (CGA) means the model must not assume cash-only accounting | S11 (item 7) | S | M |
| IFC-213 | Financial-year boundaries and the year-end freeze per CGA calendar; post-closure adjustments only through prescribed supplementary cuts | S18, S19 | S | M |
| IFC-214 | Financial reporting: expenditure and MIS by head, scheme, agency, state; FRBM statements support [U] | S09 1.2 | P | P (GP-36 MIS over history) |

### N. Audit and accountability

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-215 | **Internal audit wing** in every ministry; departmental IA manual vetted by the CGA IA division; risk-based annual IA plan (2026-27 plan circular) | S09 ch. 17; S28 | P/S | M |
| IFC-216 | IA scope covers DDOs, PAOs, grantees and the checks deferred from pre-check (e.g. stores accounts) | S09 2.16.19 notes | P | M |
| IFC-217 | e-Bill post-check by IA users (IFC-113). *Pass 2:* CGA OM TA-2-01002/1/2026/209 of 12-06-2026 (seen only via a staffnews copy, so S): PAOs pass bills within **3 working days**, and the Pr.CCA/CCA/CA office does a **concurrent post-check** in the IAOS module. IAD OM 300-01 of 11-08-2026 (S54, V) creates PFMS internal-audit user IDs. **Neither gives a sampling percentage**, so sampling stays **[U]** (the user manual went out by e-mail only) and must be configurable | S13; S54 | P / S | M |
| IFC-218 | **C&AG audit**: inspection reports → paras → replies → settlement | GP-21 | — | C (AUDIT) |
| IFC-219 | **ATN on C&AG report paras** via APMS, with a statutory follow-up deadline (4 vs 6 months in sources: **[U]**); ATRs on PAC recommendations; explanatory notes on excess / savings | S27 | S [U] | P (AUDIT: replies; APMS / ATN missing) |
| IFC-220 | Audit objections and recoveries: prompt attention by every officer (RPR 43); overcharges (RPR 42) | S07 Rules 42–43 | P | P |
| IFC-221 | Loss reporting at two stages (initial and final) to the FA / CAA / CGA for serious irregularities; petty losses ≤ ₹10,000 | S05 GFR 33 | P | M |
| IFC-222 | Secret Service augmentation ≥ 25 % needs C&AG approval as well | S03 10(10)(v) | P | M |
| IFC-223 | Complete audit trail of seat, person, rule version and payload hash for every finance step (R42) | SANGAM IX.3 | — | C (core) |

### O. Department-specific profiles

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-224 | **Railways** profile: DFPR does not apply; the Indian Railway Financial Code / Code for the Accounts Department and Railway Board schedules of powers [U: not read]; associate-finance concurrence; one demand for Railways in the Union Budget since 2017-18 [X]; Railway pensions outside CPAO; IPAS payroll / accounting, bill passing linked to agreements, integration with IREPS / GeM [S/X] | S03 21(2)(a); S07 2(h); S30 | P/S/X | M |
| IFC-225 | **Defence** profile: DFPR not applicable to DSE expenditure; the CGDA / Defence Accounts Department does audit, payment and accounting (audit decentralised in CDA/PCDA offices; payment centralised) [S]; DFPDS 2021 schedules per Service (ASP, NSP, AFSP, ISP) with IFA advice that the CFA may overrule in writing, and emergency powers below Command [X]; Defence pensions via SPARSH / PCDA(P), outside CPAO and the authorised-bank scheme; Defence Account Code 2022 | S03 21(2)(b); S07 2(h), 29, 38; S29 | P/S/X | M |
| IFC-226 | Defence: Military Treasure Chests and officers commanding units' public / regimental funds (PD accounts; regimental funds may sit outside the government account) | S07 Rules 13(9)(b)(v), 39 | P | M |
| IFC-227 | **Posts** profile: non-civil; Circle Postal Accounts Offices under a Director of Postal Accounts; Postal Accounts Manual; post-office transactions (savings bank; PLI is MH 8014 Postal Life Insurance with SMH 01 PLI / 02 RPLI and minor heads 101–108, investments under MH 8015, and only DoP may book under 8014/8015/8016: verified pass 2 from the LMMH and DAMA OM 468) and Postal pensions outside CPAO; PO savings accounts as a salary-payment destination | S07 2(h), 13 Expl., 14(5); S31 | P/S | M |
| IFC-228 | **Telecom (DoT)** profile: DFPR not applicable; Pr.CCA / CCA offices under the CGCA; licence-fee assessment from audited accounts, spectrum charges, bank guarantees of licensees; SARAS receipts (e.g. PMRTS fees from 01-07-2026); SAMPANN pension disbursement to DoT / BSNL retirees | S03 21(2)(d); S32 | P/S | M |
| IFC-229 | **MEA and missions abroad**: DFPR does not apply to representatives abroad (separate rules with the Finance Ministry); INR accounts in India + foreign-currency accounts abroad opened with CGA consultation; remittances by bank advice; monthly RBI foreign-currency releases to missions; mission accounts in foreign currency converted at the official rate; exchange gain / loss accounting; I-MAS mission accounting; ~161 missions / posts and 30 RPOs | S03 21(2)(e); S07 13(7)–(8), 17(4); S33 | P/S | M |
| IFC-230 | Missions: multi-currency budget allotment and expenditure with official-rate conversion, revaluation and loss-by-exchange heads; local-currency vendors; foreign cheque validity per local law | S07 16(3); S33 | P/S | M |
| IFC-231 | **MHA / CAPF**: Pr.CCA (MHA) with ~48 PAOs incl. force-wise regional PAOs (e.g. BSF, CRPF RPAOs); very large DDO networks; CAPF-specific allowances (risk, ration, uniform); secret-service heads (IB) | S34; S03 Annex I (ration, risk allowance) | S | M |
| IFC-232 | **DAE and DoS** profile: DFPR not applicable; own books of financial powers approved through the Atomic Energy Commission / Space Commission with a Member (Finance); sanctions with Member (Finance) concurrence; delegation down to centres / units | S03 21(2)(c); S35 | P/S | M |
| IFC-233 | **Other scientific departments** (DST, DBT, DSIR, MoES, DRDO, ICAR, DHR / ICMR, …): relaxed GFR procurement limits and works nomination; grant-heavy profiles (ABs, UCs, TSA) | S05 footnotes | P | M |
| IFC-234 | **CBDT / CBIC (Department of Revenue)**: tax-receipt accounting and reconciliation (separate CAM chapters), refunds incl. GST refunds (e-bill RPR form), write-off of revenue with full power for DoR and re-delegation by its own instructions | S09 ch. 14–15; S08; S03 Rule 13 GoI 2 | P | M |
| IFC-235 | **DoE / CGA itself and CPAO**: pension authorisation and disbursement accounting as a distinct profile | S07 2(h) | P | M |
| IFC-236 | **Charged-expenditure bodies** (President's Secretariat, Supreme Court, UPSC, C&AG, Parliament Secretariats [U list per Art. 112(3)]) need charged-only or mixed grants and must never mix charged and voted | S03 10(4) | P [U list] | M |
| IFC-237 | **UTs without legislature**: Administrator powers (DFPR tables give separate Administrator limits); a Chandigarh central treasury. **Corrected (pass 2, S53):** UT accounts do **not** go through the MHA PAO network. Each UT sends monthly accounts to the CGA through its own unit: Director of Accounts DNH&DD; CPAO Port Blair; AG (A&E) Chandigarh; Ladakh Finance Department and Treasuries; Lakshadweep Secretary (Pay & Accounts). This is inferred from the DAMA 468 addressee list | S03 (write-off table); S07 2(i); S53 | P | M |
| IFC-238 | **Departments with authorised CPWD Account Code** (CPWD, Forest, others): divisional accounts and works-cash procedure; CDDO status | S07 2(k) | P | M |
| IFC-239 | Ministries handling **external aid** (DEA, implementing ministries): EAP heads with restrictions (no EAP → non-EAP re-appropriation without DEA); loss / gain by exchange | S03 10(10)(iv) | P | M |
| IFC-240 | **MDoNER / NER**: 10 % gross budgetary support earmark for NER in many ministries (lump-sum heads 2552 / 4552 / 6552, technical re-appropriation); MDoNER excluded from the relaxation | S03 GoI decision (2) | P | M |
| IFC-241 | Ministries with **DBT-heavy schemes** (Rural Development, Agriculture, WCD, Social Justice, …): DBT, SNA / CNA, PFMS integration model per scheme (SSID) | S40, S14, S15 | S/P | P (DBT, P3) |
| IFC-242 | Ministries releasing **grants to States** (Finance Commission grants, CSS): 12-C UCs, SNA-SPARSH, payment through RBI | S05; S14 | P | M |
| IFC-243 | Department-specific **pension-sanction authorities** and accounting (Railways, Posts, Defence, DoT, civil / CPAO) selectable by profile | S07 2(h), 29 | P | P (PENSION single authority) |
| IFC-244 | Department-specific **cash / CDDO patterns** (field-heavy departments: Forests, CPWD, Customs, CAPF) | S07 2(k) | P | M |
| IFC-245 | Profile switch for **who designates CDDOs** (CGA for civil, the department for non-civil) and who opens bank accounts | S07 13(2),(4),(6) | P | M |
| IFC-246 | Profiles must allow **department-specific bill forms and checklists** under "authorised departmental regulations" | S07 2(d), 44 | P | M |
| IFC-247 | Railways / Defence / DAE / DoS / DoT still align their primary units of appropriation with Rule 8, so the classification model is shared even when the delegation books differ | S03 Rule 21 Note 1 | P | M |
| IFC-248 | Profiles carry their **own pay / pension / IT systems** as adapters (IPAS, SPARSH, SAMPANN, I-MAS, PFMS, e-HRMS), never hard-coded | S29–S33 | S | P (adapter pattern) |
| IFC-249 | Department-specific **IFD structure** (AS&FA, JS&FA, Member Finance, PFA for Railways); concurrence routing by profile | S23; S35; S30 | S/X | M |
| IFC-250 | Department-specific **year-end and reporting calendars** (non-civil accounts feed the Finance Accounts). *Pass 2:* DAMA 468 is addressed to DoP, DoT, the Railway Board and CGDA, so the same March Prelim / Sy-I calendar applies to them; no separate department calendars were found (S). Keep calendars as per-department data | S09 12.1.1; S53 | S | M |

### P. Cross-cutting

| ID | Requirement | Source | Ev. | Cov. |
|---|---|---|---|---|
| IFC-251 | All finance forms bilingual (Hindi / English; Hindi RPR / CAM versions exist) | S11; R54 | P | C |
| IFC-252 | DSC / eSign on sanction, bill, payment file and UC; the signature bound to the approved payload | S12; R57 | S | C (core) |
| IFC-253 | Idempotent external payment operations with reconciliation; never a duplicate payment | VI.3 | — | C (core, P1) |
| IFC-254 | SLA clocks for finance steps (e.g. GPF withdrawal 15 days [U: not researched in pass 2]; pension stages, now verified in IFC-174 from S45; ATN deadlines [U]) as configurable timers with escalation | S26; S45; S27 | S / P | C (pattern GP-06) / M (data) |
| IFC-255 | Record retention for vouchers, sanctions and bills per departmental record-retention schedules. **Verified (pass 2):** GFR Rule 320 and Appendix 9, e.g. sanctions 2 years or 1 year after audit; DDO cash books 10 years; contingent expenditure 3 years or 1 year after audit. Records with an outstanding audit objection, or orders of a permanent character, are never destroyed. CAM 2024 para 19.16 holds the PAO retention table (CGA order 12-07-2021; periods counted from the close of the financial year) (S: columns garbled in extraction) | R61; S05; S09 | P | P (RECORDS) |
| IFC-256 | Segregation of duties: maker ≠ checker ≠ approver ≠ PAO signatory, by person and by seat | IX.5; S09 2.2.8 | P | C (core) |
| IFC-257 | Department-wise tenant isolation, with consolidated views for the Pr.AO, CGA and FA | AC-09 | — | C (core) / P (consolidation views) |
| IFC-258 | Air-gapped and state-DC deployability; the PFMS / RBI / Bharatkosh / UBIS integrations are adapters with simulators (ADR-D-13) | SANGAM X.0b | — | C (pattern) |

**Totals:** 258 requirements (IFC-001…IFC-258). After pass 1, **35 carried [U] or rested on secondary (X) evidence only**. Pass 2 (2026-11-05) closed or corrected 20 of them; 15 keep a [U] part (listed in §6). See §7 for the changelog.

---

## 4. Gap analysis against the existing packs

### 4.1 Coverage counts

The counts were computed from the Cov. column of §3. A cell with mixed marks (e.g. "C … / P …") is counted at its **lower** level, so an item counts as covered only if nothing in it is partial or missing. Three cells are mixed: IFC-057 (C/P), IFC-254 (C/M) and IFC-257 (C/P).

| Coverage | Count | Share |
|---|---|---|
| **C** covered | 16 | 6 % |
| **P** partly covered | 65 | 25 % |
| **M** missing | 177 | 69 % |

| Area | Items | C | P | M |
|---|---|---|---|---|
| A Organisation | 15 | 0 | 5 | 10 |
| B Rule base | 13 | 1 | 5 | 7 |
| C Classification | 14 | 0 | 0 | 14 |
| D Budget formulation | 13 | 0 | 2 | 11 |
| E Budget execution | 19 | 1 | 4 | 14 |
| F Sanctions and powers | 16 | 0 | 7 | 9 |
| G Bills and payments | 35 | 3 | 10 | 22 |
| H Receipts | 12 | 0 | 3 | 9 |
| I Banking / reconciliation | 7 | 0 | 2 | 5 |
| J GIA / fund flow | 21 | 1 | 2 | 18 |
| K Establishment | 20 | 0 | 8 | 12 |
| L Procurement / works / stores | 13 | 3 | 9 | 1 |
| M Accounts / closing | 16 | 0 | 1 | 15 |
| N Audit | 9 | 2 | 2 | 5 |
| O Department profiles | 27 | 0 | 3 | 24 |
| P Cross-cutting | 8 | 5 | 2 | 1 |
| **Total** | **258** | **16** | **65** | **177** |

The 16 covered items are IFC-028, 058, 099, 110, 111, 162, 191, 196, 197, 218, 223, 251, 252, 253, 256 and 258.

### 4.2 What is already strong (reuse as-is)

- **Payment safety:** UNKNOWN_OUTCOME and reconciliation, idempotent operations, duplicate-bill check, payload-hash approval binding, maker-checker and SoD, DSC binding, encumbrance and release, DBT partial failure.
- **Procurement and works patterns:** tender, consultancy, GeM, security instruments, contract amendments, chained AA/TS sanctions, cumulative-excess re-sanction, stores verification.
- **Platform:** tenant DMN overrides, rule-version binding, SLA and escalation, bilingual forms, records retention.

### 4.3 Top gaps (ranked by how many ministries each blocks)

1. **Budget classification master is absent (area C, 14 items).** There is no 15-digit head model, LMMH loader, object-head master, voted / charged or revenue / capital typing, or scheme flags (CS/CSS, NER, EAP, secret service). Every finance workflow depends on this, so it blocks **all** ministries.
2. **Accounting organisation and the PAO pre-check chain (IFC-003, 093–098).** The packs have a generic checker / approver. Real central payments need:
   - the Pr.AO / PAO / AAO / DH / Bill Distributor seats;
   - token and FIFO handling;
   - "only the PAO returns a bill";
   - two DSCs at ₹10 lakh and above;
   - CDDO assignment accounts.
3. **Bill-type catalogue (IFC-104 to 109, 116–121, 166–183).** There is one generic P1 bill. About 35 RPR bill forms each need a sanction source, a checklist, accounting rules and a payment mode.
4. **Re-appropriation rule set (IFC-061 to 067).** REAPP routes by amount only. DFPR 10 restrictions, the ₹2 / 5 / 15 crore object-group limits, the Q1 bar, the DEA-approval list, Parliament reporting (> 20 % or ₹100 cr) and the NER technical route are all missing.
5. **Budget formulation, supplementaries, surrender, lapse, MEP / QEP (IFC-043 to 053, 069 to 074).** No pack exists for BE/RE preparation, supplementary-demand batches, NS/NIS determination, Contingency Fund advances, surrender by MoF dates, the year-end lapse or rush-of-expenditure monitoring.
6. **Grant lifecycle and fund-flow regimes (IFC-145 to 161).** P3 / DBT cover citizen grants. Institutional GIA needs:
   - unspent-balance checks;
   - the 75 % rule;
   - UCs in 12-A / 12-C with 12-month deadlines and blacklisting;
   - the EAT, CNA (Model 1 / 2), TSA and SNA-SPARSH (mother sanction → daily sanction → Pr.AO → PAO → RBI) flows;
   - the AB annual-accounts calendar.
7. **Scheme appraisal and approval ladder (IFC-080 to 083).** The SFC / EFC / PIB and Secretary / Minister / FM / Cabinet thresholds and the appraisal timeline are not modelled.
8. **Accounts compilation and closing (area M, 15 of 16 missing).** Missing: monthly accounts by the 8th, e-DO by the 12th, March Prelim / Sy-I / Sy-II, SCT, Appropriation and Finance Accounts, explanatory notes, inter-departmental adjustments, suspense.
9. **Receipts (Bharatkosh / NTR / tax) (area H).** CHALLAN is a generic scroll match. The CPIN / UTR / e-Kuber credit-notification reconciliation, the unaccounted-receipt SOP, T+3 card settlement and CBDT / CBIC accounting are missing.
10. **Department profiles (area O, 24 of 27 missing).** The delegation books of Railways, Defence (DFPDS), DAE / DoS, DoT and missions abroad differ by law (DFPR Rule 21(2)). Missions need multi-currency. CPAO / SPARSH / SAMPANN / IPAS are pension and pay adapters.
11. **Establishment finance (area K).** Missing: GPF ledger and ceilings, NPS nil bill, the UPS option and switch, Form 6-A → e-PPO → CPAO stages, and advances. The pension DMN is a placeholder.
12. **Internal audit and e-bill post-check, ATN / APMS (IFC-113, 215–219).**

### 4.4 Pack-by-pack verdict

| Pack | Verdict for central-government use | Main changes needed (for the implementation workstream) |
|---|---|---|
| P1 bill/payment | **Partly usable**: the engine pattern is right; the government detail is generic and its competent-authority DMN uses works-style EE / SE / CE bands | Add bill types, the PAO 3-level chain, token / FIFO, two DSCs at ≥ ₹10 lakh, CDDO, nil bill, LPC, time-barred claims, cheque validity |
| REAPP | **Partly usable** | Replace the amount-only DMN with the DFPR 10 rule set, object-group limits, the Q1 bar, Parliament reporting and the NER route |
| CHALLAN | **Partly usable** | Add the Bharatkosh model (CPIN, UTR, credit notification, BK-09/10), the unaccounted-receipt SOP and card T+3 |
| SALARY | **Partly usable** | Add the RPR-13 family, the NPS nil bill, GPF deductions, court attachments, the LPC and EIS e-bill |
| PENSION | **Partly usable** | Add Form 6-A, the HoO → PAO → CPAO stages, e-PPO and DigiLocker, CCS (Pension) Rules 2021 computation and the UPS option |
| P3 / DBT | Usable for DBT | Add the institutional GIA lifecycle, UCs and the CNA / SNA / TSA regimes as a new pack |
| AUDIT | Usable for paras | Add ATN / APMS deadlines, the internal-audit plan and e-bill post-check |
| WORKS / WO / TENDER / GEM / SECINST / CONTRACT / STORES / ASSETS | Largely usable | Load real GFR / DFPR thresholds (IFC-186 to 195); add the Rule 151 debarment check |

### 4.5 New packs suggested (names indicative)

| Pack | What it covers |
|---|---|
| `gov.ifms.budget-formulation` | BE/RE, DDG upload, supplementary batches, NS/NIS, Contingency Fund |
| `gov.ifms.budget-control` | Allotment, liability register, MEP / QEP, surrender, lapse, year-end cut-offs |
| `gov.ifms.sanction` | Uniform sanction, e-claim / e-sanction, FA concurrence, Rule 20 clauses |
| `gov.ifms.pao-precheck` | PAO DH / AAO / PAO chain, token / FIFO, bill-type checklists, CDDO, cheque / e-payment, two signatories |
| `gov.ifms.gia-lifecycle` | GIA, UCs 12-A / 12-C / 12-B, register of grants, AB accounts calendar, EAT |
| `gov.ifms.fund-flow` | CNA Model 1 / 2, TSA, SNA-SPARSH mother / daily sanction |
| `gov.ifms.receipts-ntr` | Bharatkosh / NTR and refunds |
| `gov.ifms.accounts-closing` | Monthly accounts, March cuts, SCT, Appropriation / Finance Accounts, explanatory notes |
| `gov.ifms.gpf-nps-ups` | GPF, NPS, UPS |
| `gov.ifms.scheme-appraisal` | SFC / EFC / PIB ladder |
| `gov.ifms.internal-audit` | IA plan, e-bill post-check, ATN |
| Department profile packs | `profile.railways`, `profile.defence`, `profile.posts`, `profile.dot`, `profile.mea-missions`, `profile.dae-dos`, `profile.cbdt-cbic`, `profile.ut-administration` |

---

## 5. Proposed configuration model (for the implementation workstream; no code)

The principle: **one engine and one set of packs; each ministry or department differs only by configuration data.** All configuration is versioned and effective-dated, and carries a legal citation (IFC-016).

### 5.1 Department profile

| Field | Type / values | Example | Drives |
|---|---|---|---|
| deptCode, name (en / hi), ministryCode | registry key (IGOD / AoBR) | `MHA-HOME` | IFC-001 |
| aobrRef, effectiveFrom / To | AoBR entry + amendment no. | "AoBR First Schedule, as amended …" | IFC-001 |
| accountingRegime | `CIVIL_DEPARTMENTALISED` / `RAILWAYS` / `DEFENCE` / `POSTS` / `TELECOM` / `ATOMIC_SPACE` / `MISSIONS` / `UT_ADMIN` | `CIVIL_DEPARTMENTALISED` | Which rule books and adapters load |
| nonCivil | bool (RPR 13 Explanation) | false | CDDO designation, bank-account approval, PD accounts |
| delegationBook | `DFPR_2024` / `IR_SOP` / `DFPDS_2021` / `DOS_BOOK` / `DAE_BOOK` / `DOT_BOOK` / `MISSIONS_RULES` + version | `DFPR_2024@2026-06-09` | IFC-010 |
| scientificDept | bool (GFR footnote list) | false | GFR 154 / 155 / 133 relaxed limits |
| caaSeat, faSeat (IFD type: AS&FA / JS&FA / Member Finance / PFA) | seat refs | `SECY-HOME`, `ASFA-MHA` | Concurrence routing |
| hoAO (Pr.CCA / CCA / CA), prAO, PAOs[], CDDOs[], DDOs[] | office tree with PAO ↔ DDO map | 48 PAOs (MHA) | IFC-003 |
| accreditedBank, branches, focal-point branch | bank refs | — | IFC-138 |
| grants[] | Demand nos. controlled, with the PAOs sharing them | Demand No. 51 | IFC-039 |
| currencies[] | INR + mission currencies | INR (MEA: INR + n) | IFC-229 |
| integrations | PFMS model 1–4 + SSID; Bharatkosh purposes; SARAS / IPAS / SPARSH / SAMPANN / I-MAS adapters | PFMS model 2 | IFC-124 |
| calendars | working days; CGA closing calendar; MoF surrender dates | — | IFC-069, 202 |
| departmentalRegulations[] | authorised regulations overriding RPR forms / checklists | — | IFC-024, 246 |
| languages | hi, en (+ state language for UTs) | hi, en | R54 |

### 5.2 Budget-head master

**Head key (15 digits):** `MMMM-SS-NNN-HH-DD-OO`, plus the grant number and the section.

| Attribute | Values | Rule enforced |
|---|---|---|
| majorHead / subMajor / minor / subHead / detailed / objectHead | codes from LMMH + DDG; object head from DFPR Annex I | IFC-029–033 |
| section | REVENUE / CAPITAL (+ LOANS) | No revenue ↔ capital re-appropriation |
| chargedVoted | CHARGED / VOTED (both allowed on one unit, held separately) | No charged ↔ voted |
| objectClass, reappGroup | class I–VIII; group `EST_2CR` / `NONEST_5CR` / `OTHER_15CR` | ₹2 / 5 / 15 crore limits |
| schemeType | CS / CSS / ESTABLISHMENT / OTHER; fundFlowModel: TSA / CNA-M1 / CNA-M2 / SNA / SNA-SPARSH / DIRECT | Release flow selection |
| flags | NER-lumpsum, NER-earmark, EAP, SecretService, GIAtoStates, Salaries / Allowances, Buildings / Infrastructure / FixedAssets, NS/NIS-pending, CF-advance-taken, augmentedBySupplementary | DFPR 10(7), 10(8), 10(10) restrictions |
| provisions | BE, supplementary 1..n, re-appropriations ±, surrenders, RE, final grant | Appropriation Accounts |
| allotments[] | per Controlling Officer / DDO with PAO approval | IFC-056 |
| version / validity | LMMH slip no., DDG year | IFC-032 |

### 5.3 Delegation tables (DMN families, versioned)

Each row of every table carries: `authorityLevel` (seat type), `deptProfile` / `delegationBook`, the conditions, the limit (per case / per occasion / cumulative / per year), `faConsultation` (none / advice / concurrence), `escalateTo`, `legalRef` and `effectiveFrom` / `To`.

| Family | Key inputs | Seeded from |
|---|---|---|
| D1 Sanction of expenditure (general) | powerType, objectHead group, amount, newPrincipleFlag | DFPR 5, 7, 12 + department re-delegation orders |
| D2 Re-appropriation | from / to heads (with flags), amount, quarter, after-last-supplementary flag, % of BE | DFPR 10 + OM 01-04-2024 + OM 03-06-2024 |
| D3 Procurement and contracts | mode (GeM direct / GeM L1 / bid / RA / LTE / open / single / proprietary / nomination), amount, scientificDept | DFPR 11; GFR 149, 154, 155, 133 |
| D4 Scheme / project appraisal and approval | total outlay (incl. EBR, state share), department special dispensation | OM 05-08-2016 |
| D5 Write-off / condemnation / disposal | nature of loss, cause, amount, vehicle type and usage | DFPR 13, GFR 218 |
| D6 Waiver of overpayment | amount per individual | DFPR 15 |
| D7 Grants-in-aid release | grantee type, recurring flag, UC status, %-of-sanction, unspent balance | GFR 230, 238 |
| D8 Creation of posts | pay level, AB chief-executive flag | DFPR App. (posts) |
| D9 Payment controls | amount ≥ ₹10 lakh → second signatory; cash-payment relaxation | CAM 2.2.9; RPR 14 |
| D10 Department books | Railways, DFPDS 2021 schedules, DoS / DAE books, DoT, missions | Profile-specific; loaded as tenant overrides (GP-37) |

### 5.4 Bill-type catalogue

| Field | Meaning |
|---|---|
| billTypeCode, rprForm, garFormLegacy | e.g. `TA_TOUR` = RPR-14A (was GAR-14) |
| claimant | employee / supplier / grantee / state / internal |
| sanctionRequired | none (entitlement) / e-sanction (RPR-100) / existing sanction ref / FA concurrence ref |
| precheckChecklist | list ID from the CGA Standardised Audit Formats + departmental additions |
| mandatoryCertificates | e.g. entertainment, LPC-backed, absentee, increment |
| accountingRule | allowed object heads; single major head per bill; nil-bill allowed |
| paymentModes | payment advice / cheque / RBI advice / foreign-currency advice; cash only with relaxation |
| postCheckSampling | rate, risk factors (IA module) [U values] |
| timeBar | 2-year arrear rule → GFR 295–297 route |
| retention | record class (R61) |
| integrations | PFMS bill type, EIS / HRMS, GeM, Bharatkosh refund |

**Seed list:** the RPR-2022 forms index (S08), i.e.
- **personal claims:** pay (RPR-13, 13A, 13B), TA (RPR-14, 14A/B/C), President / VP / Ministers (RPR-15/16), medical (CGHS, CS(MA));
- **contingent and imprest:** RPR-29, RPR-29A;
- **refunds:** refund of revenue (RPR-33), GST refund e-bill, refund of deposit, lapsed deposit;
- **grants and advances:** GIA / subsidy / loan (RPR-34), HBA / computer, short-term advances;
- **funds and insurance:** GPF / CPF / DLIS, CGEGIS, GIS-AIS;
- **other:** other personal claims, GeM e-bill, retirement benefits, Contingency Fund withdrawal.

### 5.5 Approval chains (configurable templates over the core)

| Chain | Steps (seat types) | Notes |
|---|---|---|
| CH-1 Establishment claim | Claimant e-claim → Sanction Maker (HoO) → Sanction Checker (DSC) → DDO e-bill (DSC) → Bill Distributor → DH → AAO → PAO (DSC) → [2nd signatory if ≥ ₹10 lakh] → bank → e-scroll → reconcile | IFC-091–099 |
| CH-2 Scheme / vendor payment | PD Maker → PD Checker (DSC) [→ FA concurrence if required] → DDO → PAO chain as CH-1 | IFC-079, 092 |
| CH-3 SNA-SPARSH daily release | State IFMS claim → PD Checker daily sanction (DSC) → Pr.AO payment authorisation (DSC) → PAO pre-check / pass (DSC) → RBI batch | Alternative 7-role chain retained (IFC-160) |
| CH-4 Re-appropriation | Budget section proposal → FA (consultation, mandatory) → CAA (Secretary) → [DEA Budget Division / DoE Pers. + Secretary (Expenditure) if D2 says so] → order → Pr.AO budget entry → [Parliament report list] | IFC-061–068 |
| CH-5 Scheme appraisal | Proposal → FA appraisal (≤ ₹100 cr) / SFC (₹100–500 cr) / EFC-PIB (> ₹500 cr) → approval Secretary / Minister / Minister + FM / Cabinet | IFC-080–082 |
| CH-6 GIA release | Programme division → unspent-balance and UC checks (D7) → FA → sanction → PAO; UC tracking timer of 12 months → blacklisting option | IFC-147–152 |
| CH-7 Write-off / waiver | Investigation → HoD / department per D5 / D6 → FA → (DoE above limits) → quarterly IFD statement | IFC-121, 193 |
| CH-8 Surrender / supplementary | Controlling Officer savings → FA → CAA → MoF by the prescribed date; supplementary proposals → FA → DEA batch | IFC-048, 069 |
| CH-9 Pension | Employee Form 6-A → HoO → PAO (e-PPO) → CPAO (SSA) → bank; department variants for Railways / Posts / Defence / DoT | IFC-174–178 |
| CH-10 Month / year close | PAO compile → Pr.AO consolidate (by the 8th) → CGA; March Prelim / Sy-I / Sy-II; SCT; Appropriation Accounts → CAA signature → audit | IFC-199–206 |

---

## 6. Open points and [U] items for the next pass

Status after pass 2 (2026-11-05). "Closed" means verified or corrected on an official source, recorded in the row itself.

1. **AoBR First Schedule:** closed (§2.1; S02 to Series 386 of 22-07-2026).
2. **DFPR amendment OM of 09-06-2026:** closed (S04; object heads from FY 2027-28).
3. **Rush-of-expenditure ceilings for 2026-27:** still [U]. The rule and the governing DEA OM of 25-05-2022 are verified (IFC-071); the percentages sit in a scanned Annexure R of Budget Circular 2026-27. Next: OCR pp. 225–227 of S47.
4. **Timelines:**
   - pension stages: closed (IFC-174, S45);
   - UPS thresholds: closed (IFC-173, S46); PFRDA UPS Regulations 2025 not read;
   - ATN on C&AG paras (4 vs 6 months): still [U]; not searched in pass 2 (search budget ran out);
   - GPF withdrawal 15 days: still [U]; not searched.
5. **Not read:**
   - closed: NGO-Darpan (IFC-164: GFR is silent; the NITI notification is identified), the GFR asset-register form (IFC-163: none exists), vote on account (IFC-052: GFR 55, 67(2));
   - still [U]: FR/SR, the Compendium of Rules on Advances, TDS / GST-TDS rates (only the GST-TDS head 8658-00-139 is verified), DEA instructions on sanctions under a vote on account.
6. **Department books:** still [U]: Railways Financial Code and schedules of powers, DFPDS 2021 official text, DAE book, DoT book, missions rules. The CPWD Works Manual after 2022 is also still [U]: cpwd.gov.in links only the 2007, 2012 and 2014 manuals. The DoE Works Manual 2nd Ed. 2025 is verified (S50).
7. **Accounts calendar 2025-26:** the Sy-I date is closed (30-04-2026, S53). The March Preliminary and Sy-II dates are still [U]: CGA Order 469 of 03-03-2026 and Order 16 of 04-05-2026 have no PDF link.
8. **e-Bill post-check sampling norms:** still [U]. They are in neither CGA OM; the user manual was e-mailed only (IFC-217).
9. **Charged-expenditure bodies:** closed (S56, Art. 112(3)). The list:
   - (a) the President's emoluments and office;
   - (b) the presiding officers of both Houses;
   - (c) debt charges;
   - (d) the pay and pensions of Supreme Court judges, and the pensions of Federal Court and High Court judges;
   - (e) the C&AG;
   - (f) court decrees and awards;
   - (g) anything the Constitution or a law declares charged.
10. **Also still [U]:**
    - the RE 2026-27 / BE 2027-28 circular date and FA appendix deadline (IFC-044; the DEA link returns 404);
    - FRBM statements (IFC-214; not searched);
    - S23, the Charter for FAs: no doe.gov.in copy found;
    - the official copy of CGA OM 209 of 12-06-2026;
    - the commutation-rule number for the 40 % limit (IFC-179);
    - the CAM 19.16 retention table: periods not cleanly extracted.

---

## 7. Changelog

| Date | Pass | Change |
|---|---|---|
| 2026-11-04 | 1 | First version: 258 requirements; 35 carry [U] or rest on X evidence only. |
| 2026-11-05 | 2 (official-source follow-up) | **Org registry:** S02 updated to the AoBR consolidated to Series 386 (22-07-2026). §2.1 now gives the official counts (53 ministries, 53 departments, 7 standalone units, 94 business units) and the IGOD reconciliation. Capital Development, Urban Development and Border Management are confirmed. The "54 / 93" figure is rejected. |
| 2026-11-05 | 2 | **Rules and finance:** S04 is now the official DoE OM. IFC-018 and IFC-034: object heads change from FY 2027-28 (61 heads, 8 classes). IFC-027: Works Manual 2nd Ed. 2025. IFC-044: Budget Circular 2026-27. IFC-052: GFR 55 and 67(2). IFC-053: 16th FC period. IFC-071: governing OM. IFC-123: GST-TDS head. IFC-140: CAM-90. IFC-161: no January 2024 OM; the 2022 OMs and the 2024 CNA master circular apply. IFC-163: no GFR form. IFC-164: NITI source. |
| 2026-11-05 | 2 | **Establishment:** IFC-173 (UPS rules 11, 13, 16), IFC-174 (pension timelines, r.56–63), IFC-178 (Jeevan Pramaan), IFC-179 (r.44, 31, 32) and IFC-184 (8th CPC, 03-11-2025) verified. |
| 2026-11-05 | 2 | **Corrections:** IFC-188: LTE is ₹50 lakh, and ₹1 crore applies to scientific departments only. IFC-237: each UT sends accounts to the CGA through its own unit, not through MHA Pr.CCA. IFC-202: the dates are in Order 469, not in DAMA 468. |
| 2026-11-05 | 2 | **Accounts and audit:** IFC-202 Sy-I date; IFC-217 3-working-day pass and concurrent post-check (still no sampling norm); IFC-227 PLI heads; IFC-250 common calendar; IFC-255 GFR 320 / App. 9; Art. 112(3) list. S12 e-Bill date verified. |
| 2026-11-05 | 2 | **New sources** S45–S58 added to §1. §6 rewritten with the status of every open point. Values that changed are listed for the coding workstream in `spike/results/IFMS-RESEARCH-DELTA.md`. |

*End of document. Nothing in this document has been committed.*
