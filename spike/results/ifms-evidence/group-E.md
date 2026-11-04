# IFMS research — Group E (North-East: AR, MN, ML, MZ, NL, TR)

Research date: 2026-11-04. Status: **V** = verified on an official page (gov.in / nic.in / cag.gov.in incl. its CloudFront CDN redirect / doe.gov.in), read by fetch or by downloading the PDF and extracting its text. **[U]** = unverified: from secondary sources, a search snippet only, or an official site that refused the connection.

Access notes: `megfinance.gov.in` and `nifims.nagaland.gov.in` refused connections (ECONNREFUSED) from this environment, so content on those sites is [U] unless a CAG/NIC document confirms it. CAG PDFs redirect to `d1jmc0u14z2tmp.cloudfront.net`, CAG's own CDN; they were read via pdftotext. No logins and no CAPTCHAs were encountered.

**Key cross-state source (V):** DoE (PFMS Division) OM F.No.1(27)/PFMS/2020 dated 17 Dec 2024 — https://doe.gov.in/files/circulars_document/OM_5.pdf . It notified **Mizoram, Tripura, Manipur, Meghalaya** (plus Sikkim) to onboard SNA-SPARSH "with immediate effect". **Arunachal Pradesh and Nagaland** were told to finish IFMIS–PFMS–RBI testing and onboard **no later than 01.04.2025**.

---

## Arunachal Pradesh (IN-AR)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system / modules | No branded state IFMS portal found. The CAG Annual Review 2022-23 (§3.18) says computerisation is "an essential part of implementation of IFMS". NIC **TreasuryNet** is installed in some treasuries (Tawang, Ziro, Yingkiong), but accounts still go to the PAG **offline**. Jang, Miao, Khonsa and Koloriang sub-treasuries are fully manual. A 2010 press report mentions a web-based system covering bills, challans, PLA and e-clearing. | https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-on-the-working-of-Treasuries-of-Arunahcal-Pradesh-for-the-year-2022-23-066c4439f7e5cf8-48883523.pdf | yes | V (CAG); 2010 web system [U] |
| 2. Treasury structure | **Director of Accounts and Treasuries, Finance Department, Itanagar** has administrative control. Deputy Commissioners (district) and SDOs (Civil) run day-to-day work. **26 Treasuries (incl. Shillong (South) Treasury) + 8 independent Sub-Treasuries as on 31.03.2023**, all banking treasuries. Boleng ST renders accounts through Aalo Treasury. An older CAG web page says 14 T + 8 ST, including the DRC Guwahati and Delhi offices. DDO count not found. | same CAG PDF; https://cag.gov.in/ae/arunachal-pradesh/en/page-ae-arunachal-pradesh-treasuryinspectionfunction | yes | V (count discrepancy noted) |
| 3. Financial rules | CAG cites **Central Treasury Rules Vol-I** (Rules 68, 77, 172, 550) and **GFR 2017** (Rules 52, 57(3), 61, 62(3), 238) as the governing rules. No state Financial or Treasury Rules were found. **AP FRBM Rules 2007** exist (india.gov.in listing). Budget Manual, DFPR and works/procurement rules not found. | CAG PDF above; https://www.india.gov.in/arunachal-pradesh-fiscal-responsibility-and-budget-management-rules-2007 | yes | V (CTR/GFR use); FRBM [U]; others [U] |
| 4. Bill types / e-payment | Bills passed under CTR, with a specimen-signature register for DDOs. No evidence of e-Kuber go-live. | CAG PDF | yes | [U] for e-payment |
| 5. Integrations | **Not onboarded to SNA-SPARSH as of 17 Dec 2024**; deadline 01.04.2025. Current status not confirmed. Agency bank and e-Kuber status not confirmed. CSS funding pattern for NE/Himalayan states (90:10) is a general GoI norm; not verified on a state page. | https://doe.gov.in/files/circulars_document/OM_5.pdf | yes | V (SNA-SPARSH as of Dec-2024); rest [U] |
| 6. Budget classification | Nothing state-specific found beyond LMMH. | — | — | [U] |
| 7. Languages | English is understood to be the official language; all CAG and treasury documents seen are in English. | CAG PDF (English only) | yes | [U] (no official-language source fetched) |
| 8. Distinctive features | Treasuries are in **Shillong (Meghalaya)**, **Guwahati** and **Delhi** (Resident Commissioner offices), i.e. treasuries outside the state's territory. Treasuries run under DC/SDO control. Mixed manual/offline treasuries. Art. 371H (Governor's law-and-order role), PRIs under the AP Panchayat Raj Act, ILP and NESIDS were not verified for finance impact. | CAG PDF | yes | V (extra-territorial treasuries); rest [U] |

**Candidate state-specific requirements**
- IFS-AR-001: Treasury master must allow a treasury located outside the state (Shillong South, DRC Guwahati, DRC Delhi), with its own bank and accounting jurisdiction.
- IFS-AR-002: Treasury hierarchy needs "parent treasury for account rendition" (e.g. Boleng ST → Aalo T) separate from administrative control.
- IFS-AR-003: Support offline/low-connectivity treasuries: batch export of monthly accounts to the AG and a store-and-forward sync.
- IFS-AR-004: Rule pack set to CTR Vol-I + GFR 2017 (central rules applied to the state), not a state Treasury Code.
- IFS-AR-005: District Collector / SDO(Civil) as the treasury-supervising authority in the approval chain.
- IFS-AR-006: SNA-SPARSH onboarding switch, tracked per state with a cut-over date (state was a late adopter).

---

## Manipur (IN-MN)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system / modules | NIC-built suite: **TreasuryNet Manipur** (payments and receipts); **BEAMS** (budget allocation, since 1 Apr 2014, integrated with CMIS and TreasuryNET); **eGRAS** (receipts, since 27 Mar 2021); **e-Budget Manipur** (21 Jul 2022); **Online Pension Payment System** with six-monthly photo life proof; and a **Cyber Treasury** (Accounts Rendering Unit for e-receipts). | https://informatics.nic.in/files/flipbooks/april23/files/basic-html/page6.html | yes | V |
| 2. Treasury structure | **Directorate of Treasuries and Accounts, Government of Manipur**. CAG inspection page: **12 Treasuries (incl. DRC Manipur at Guwahati) + 7 Sub-Treasuries = 19**, plus the **Cyber Treasury Office** (inspected since 2019-20). Elsewhere CAG compiles accounts from "11 Treasuries and 5 Sub-treasuries". DDO count not found. | https://cag.gov.in/ae/manipur/en/page-ae-manipur-treasury-inspection | yes | V |
| 3. Financial rules | **Delegation of Financial Powers Rules, 1995** (made under Art.166(3); amended 2012 and 29-Oct-2024), as incorporated in the **Manual of Financial Procedure, 1999**. Finance officers must check compliance with **GFR, Central Treasury Rules and the Manipur DFPR** (2012 gazette, FD(TRY) order). No separate Manipur Treasury Rules or Budget Manual found. | https://manipurgovtpress.nic.in/media/gazzete/EOG_244_01-10-2012.pdf ; https://www.manipurgovtpress.nic.in/media/gazzete/EOG_156_25-07-2012.pdf | yes | V |
| 4. Bill types / e-payment | **100% e-payment through RBI e-Kuber** (NIC Informatics). BEAMS issues "authorization slips" before bills are passed. | informatics page above | yes | V |
| 5. Integrations | **TreasuryNet–e-Kuber integration launched 11 Feb 2020**, after a pilot at Imphal East Treasury from 6 Jan 2020. **RBI is the sole agency bank**, with no intermediary bank for government payments. Interface built by NIC Manipur. PFMS implemented, with an SPMU at the DTA (2018 press report). **SNA-SPARSH notified 17 Dec 2024**. | https://manipur.nic.in/?p=4940 ; https://doe.gov.in/files/circulars_document/OM_5.pdf | yes | V (PFMS SPMU [U]) |
| 6. Budget classification | BEAMS authorization-slip layer, i.e. a budget release/LOC object between allocation and bill. No codes beyond LMMH found. | informatics page | yes | V (BEAMS); codes [U] |
| 7. Languages | Manipuri (Meitei Mayek / Bengali script) and English are understood; the finance documents seen were in English. | — | — | [U] |
| 8. Distinctive features | **Autonomous District Councils** in the hill areas (Manipur's are under a state Act, not the Sixth Schedule). Each has a **Manipur Finance Service officer** who must vet every ADC/DRDA scheme, purchase and contract, and certify proposals to administrative departments and the Finance Department ("vetted by Finance Officer vide U.O. No…"). Art. 371C Hill Areas Committee not verified on a finance page. | https://www.manipurgovtpress.nic.in/media/gazzete/EOG_156_25-07-2012.pdf | yes | V (ADC finance-officer vetting); 371C [U] |

**Candidate state-specific requirements**
- IFS-MN-001: Mandatory "Finance Officer vetting" step, with U.O. number and date captured, for every ADC/DRDA proposal before release of sanction or LOC.
- IFS-MN-002: Budget authorization-slip (BEAMS-style) object: release → authorization slip → bill, checked against the slip balance.
- IFS-MN-003: Payment rail: RBI e-Kuber as sole agency bank (no commercial agency-bank leg); NEFT/RTGS via e-advice.
- IFS-MN-004: Cyber Treasury / Accounts Rendering Unit as a virtual treasury for e-receipts (GRAS), reporting separately to the AG.
- IFS-MN-005: Rules pack GFR + CTR + Manipur DFPR 1995 (Manual of Financial Procedure 1999), with per-department Schedule-III overrides (e.g. the High Court, 2024).
- IFS-MN-006: Pension life-certificate workflow with a periodic photo (six-monthly) as well as the annual DLC.
- IFS-MN-007: Hill-area flag on DDO/office (ADC jurisdiction) for reporting and approval routing.

---

## Meghalaya (IN-ML)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system / modules | **MeghIFMS**. CAG lists its modules: Integrated Online Budget System (IOBS), IFMS Portal, **BEAMS**, **TreasuryNET**, **Meghalaya e-Billing**, **MeghEIS** (employee information), **CPPS** (centralised pension), fingerprint life-certificate, **MeghNPS**, **GRAS**, Meghalaya GST-AR, DLFA automation, **SNA-SPARSH**, **eProposal**. e-Service Book is under development. TreasuryNET went live at Mawkyrwat in Sep 2009 (NIC). | https://cag.gov.in/uploads/ae_state_accounts/Annual-Review-on-working-of-Treasuries-2024-25-pdf-06a1fbbe24b9673-57242665.pdf ; https://southwestkhasihills.gov.in/?p=24002 ; https://megfinance.gov.in/ifms.html | yes | V (CAG + district site); megfinance blocked |
| 2. Treasury structure | **Directorate of Accounts and Treasuries** (est. 1972, under the Finance (Establishment) Department). **13 District Treasuries + 3 Sub-Treasuries + 1 Cyber Treasury** (CAG 2024-25). Banking by **SBI** for most treasuries and **PNB** for Jowai, Khliehriat and Amlarem. **Shillong (South) Treasury also works for the Governments of Assam, Mizoram and Arunachal Pradesh**. The Cyber Treasury is under the Director, who renders its accounts directly to the PAG. Staff: 374 in position against 455 sanctioned (31.03.2025). DDO count not found. | CAG 2024-25 PDF | yes | V |
| 3. Financial rules | **Meghalaya Treasury Rules, 1985** (cited by CAG, Rules 382 and 403); **GFR 2017** (Rule 62) also cited. **Meghalaya Financial Rules, 1981** (replaced the Assam rules); **Meghalaya Delegation of Financial Powers Rules 1981** and **DFP Rules 2006**. Budget Manual: the Assam Budget Manual reportedly still applies (2006 gazette). | CAG 2024-25 PDF; https://megfinance.gov.in/files/rules/Meghalaya-Financial-Rules-First-Edition-1981.pdf ; https://www.megfinance.gov.in/files/rules/The_Meghalaya_Delegation_of_Financial_Power_Rules_2006.pdf | yes | V (Treasury Rules 1985, GFR); MFR/DFPR [U] (official URLs, site blocked) |
| 4. Bill types / e-payment | The **DDO prepares an eBill on Meghalaya e-Billing**, uploads invoices and sanctions, and pushes it to TreasuryNET. **No edits (esp. payee) are allowed at the treasury; only approve or object.** The TO signs vouchers with a DSC. The e-Kuber payment file is authenticated by **OTP over the SANDES app and email**. | CAG 2024-25 PDF | yes | V |
| 5. Integrations | API: **eProposal** (Planning Department sanctions / AA payment advice), **PFMS** (SNA-SPARSH), **NPCI ABPS**, GSTN (CPIN/CIN), agency banks with GRAS, department portals with GRAS (e-district, excise, investmeghalaya…), and ministry MIS portals for SPARSH (NREGA, PMGSY, PMAY-G, GramNidhi, PMMVY), plus a state **NHM portal**. SFTP: **RBI e-Kuber** (payments and receipts). SNA-SPARSH notified 17 Dec 2024. Monthly accounts due by the 5th of the following month. | CAG 2024-25 PDF; DoE OM | yes | V |
| 6. Budget classification | IOBS/BEAMS layers. No extra code segments found. | CAG PDF | yes | [U] for codes |
| 7. Languages | English is the official language. Khasi and Garo were associate languages under the 2005 Act; a 2026 ordinance reportedly gives them full official status. | https://www.drishtiias.com/state-pcs-current-affairs/meghalaya-recognises-khasi-and-garo-as-official-languages/print_manually (secondary) | yes | [U] |
| 8. Distinctive features | Sixth Schedule **KHADC/JHADC/GHADC**. CAG ADC audits cite District Fund rules (e.g. the 1952 Fund Rules of the erstwhile United Khasi-Jaintia Hills DC) and non-release by the state of the council's share of forest revenue and profession tax. One treasury serves four governments (Shillong South). Cyber Treasury for receipts. | CAG ADC report listings (calm.cag.gov.in), not opened | — | [U] (ADC detail); Shillong South V |

**Candidate state-specific requirements**
- IFS-ML-001: Multi-government treasury: one treasury (Shillong South) transacting for several state governments, with separate ledgers, AG reporting and banks per government.
- IFS-ML-002: Agency-bank mapping per treasury (SBI vs PNB), configurable for each treasury.
- IFS-ML-003: Treasury-side bill immutability: the treasury can only approve or object, never edit DDO bill data (payee), enforced by policy.
- IFS-ML-004: DSC signing by the TO, plus OTP via a configurable channel (SANDES / email) before releasing the e-Kuber file.
- IFS-ML-005: eProposal → sanction / administrative-approval → payment-advice linkage, with sanction reference required on the bill.
- IFS-ML-006: Rules pack: Meghalaya Treasury Rules 1985 + Meghalaya Financial Rules 1981 + DFPR 2006 + GFR 2017 fallback; Assam Budget Manual inheritance.
- IFS-ML-007: ADC share-of-revenue transfers (forest, profession tax) as a tracked statutory transfer with due-vs-released reporting.
- IFS-ML-008: Monthly-account rendition SLA (5th of the following month) with an escalation workflow.

---

## Mizoram (IN-MZ)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system / modules | **COMPACT** (NIC treasury software, customised): pilot at Aizawl South in 2012, all **10 treasuries by 2016**. **eBillDDO** (DDO bill file → COMPACT). **Mizo e-Lekha** (accounting, since Jun 2017, daily uploads). **eGPF v6** (Apr 2023). "IFMIS" is now the umbrella name, with new modules: **MIWAS** (works accounts for PWD, PHE, Power, I&WR, Forest, LRSWC), **e-Budget**, **e-Sanction**, and **100% DBT** for state schemes (Thlai Thar Leina, Bana Kaih, Power Subsidy), trackable in IFMIS. | https://informatics.nic.in/uploads/pdfs/b0dbf947_mizoram.pdf ; https://newsonair.gov.in/mizoram-cm-lalduhoma-launches-4-digital-financial-management-initiatives-to-enhance-transparency-accountability/ | yes | V |
| 2. Treasury structure | **Accounts & Treasuries** department headed by the **Chief Controller of Accounts (A&T)**, with Directorates (e.g. Local Fund Audit & Pension). The Directorate of Accounts and Treasuries was established May 1975. **10 treasuries**, all banking, rendering directly to the AG. Sub-treasury list linked but not counted. DDO count not found. | https://cag.gov.in/ae/mizoram/en/page-ae-mizoram-account-vlc ; https://cag.gov.in/uploads/media/mizoram-06a19707adef680-92391773.pdf | yes | V |
| 3. Financial rules | Gazette notices use the **central Delegation of Financial Powers Rules, 1978**, applied via the State of Mizoram Adaptation of Laws Order (No.2) 1978 (Head-of-Office designations under Rule 14). By inference GFR/CTR apply; this is not confirmed. No state Treasury Rules or Budget Manual found. | https://mizoramassembly.in/storage/Assembly/Gazette/EO/GZEO32_ISS165.pdf (official Assembly gazette mirror, non-gov.in) | — | [U] |
| 4. Bill types / e-payment | e-Payment from Oct 2015 at Aizawl South and North: salary and personal-claim bills, later contingent bills. Other treasuries used cheques printed from COMPACT. **e-Sanction** routes sanctions DDO → HoD → Administrative Department → Finance, with a budget check. | NIC Informatics PDF; newsonair | yes | V |
| 5. Integrations | Banking by **SBI** (all treasuries). **SNA-SPARSH notified 17 Dec 2024**. The CM links SNA-SPARSH adoption to SASCI incentives (press). State DBT is paid through the treasury after the bill is passed. e-Kuber status not confirmed. | CAG VLC page; DoE OM; newsonair | yes | V (SBI, SNA-SPARSH, DBT); e-Kuber [U] |
| 6. Budget classification | MIWAS reconciles works expenditure with the AG. No extra code segments found. | newsonair | yes | [U] |
| 7. Languages | Mizo and English. | secondary only | — | [U] |
| 8. Distinctive features | Sixth Schedule **Lai, Mara and Chakma ADCs**. **Mizoram Autonomous District Council Fund Rules, 1996** govern the District Fund (per CAG ADC audit summaries). CAG audits the ADCs separately (e.g. Lai ADC 2016-17, Mara ADC 2019-20). Village Councils instead of PRIs, Art. 371G and ILP were not verified for finance impact. | https://cag.gov.in/ag/aizawl/en/audit-report/details/123887 (listing) | — | [U] |

**Candidate state-specific requirements**
- IFS-MZ-001: Works-accounts module (MIWAS-style) for multiple engineering/forest departments, with division-level accounts rendered to the AG and voucher submission through the treasury.
- IFS-MZ-002: e-Sanction workflow DDO → HoD → Administrative Department → Finance, with a budget-availability hard stop.
- IFS-MZ-003: State-scheme DBT: beneficiary payment after treasury pass, with a status-tracking API.
- IFS-MZ-004: Rules pack based on the central DFPR 1978 (or a successor), applied by an adaptation order. Head-of-Office designation is a gazette-notified, configurable role.
- IFS-MZ-005: ADC grant and District Fund flows (Lai/Mara/Chakma) under the MADC Fund Rules 1996 as separate accounting entities.
- IFS-MZ-006: Department naming: "Accounts & Treasuries" under a Chief Controller of Accounts, with Pension and Local Fund Audit as sub-directorates. The org model must not assume a "DTA" label.

---

## Nagaland (IN-NL)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system / modules | **NIFiMS** (Nagaland Integrated Financial Management System; portal nifims.nagaland.gov.in refused connection). Applications on the DTA site: a **new in-house e-Pay Bill system** (launched 2 Dec 2025 per DTA news; press says 28 Nov 2025, hosted at the State Data Centre), **eGPF Grade-IV**, **ePaySlip** (2026), **PIMS v2** (pension, effective 16 Oct 2026), Jeevan Pramaan DLC checker. The CAG page says IFMS is the name of treasury computerisation, with NIC Nagaland assisting. | https://treasuries.nagaland.gov.in/ ; https://treasuries.nagaland.gov.in/news-events?id=21 ; https://cag.gov.in/ae/nagaland/en/page-ae-nagaland-treasury-inspection | yes | V (DTA, CAG); NIFiMS modules [U] |
| 2. Treasury structure | **(Principal) Directorate of Treasuries & Accounts, Nagaland, Kohima**, under the Finance Department. RTI manual: started 1980 with 8 DT + 2 ST, grew to 11 + 12, then +3 DT and +10 ST, i.e. about **14 DT + 22 ST**. The RTI list tabulates 12 treasuries + 12 STs. The CAG page says established 1963. **960 DDOs**. | https://treasuries.nagaland.gov.in/media/download/tmprv8q7j5tDTA%20RTI%20Manual,2024-2025.pdf | yes | V (counts inconsistent across sources) |
| 3. Financial rules | RTI manual lists **Central Treasury Rules, General Financial Rules, Nagaland Treasuries, Accounts & Audit Service Rules 2002, CPWD Account Code**. TOs pass bills "within the provision of CTR". No state DFPR found (only department OMs). | RTI manual | yes | V |
| 4. Bill types / e-payment | e-Pay bill system (2025). The TO monitors DDO-wise sub-allotments, and the treasury maintains the budget record so a DDO cannot exceed allocation. Treasuries act as DDO for pensioners and for Grade-IV GPF. | RTI manual; DTA news | yes | V |
| 5. Integrations | Training on **eKuber implementation** (Apr/May 2025) and **SNA-SPARSH** (May and Sep 2025, PFMS team). Per DoE, **not onboarded to SNA-SPARSH as of 17 Dec 2024**; deadline 01.04.2025. A press report says payments ran through the **SBI CMP** application before e-Kuber. | DTA news page; DoE OM | yes | V (training, DoE); SBI CMP [U] |
| 6. Budget classification | DDO-wise sub-allotment of budget circulated to treasuries, i.e. treasury-level allotment control. No extra code segments found. | RTI manual | yes | V (process); codes [U] |
| 7. Languages | English is the official language. | secondary | — | [U] |
| 8. Distinctive features | **Art. 371A** (Naga customary law, land and resources) and **Village Councils** (Nagaland Village Councils Act) not verified on a finance page. A treasury-run **Training Institute** (Feb 2005). Life-verification drives at village level (e.g. Chunlikha). Treasury computerisation was funded under the NeGP MMP. | RTI manual; DTA news | yes | partly V; 371A/VC [U] |

**Candidate state-specific requirements**
- IFS-NL-001: Treasury-held DDO sub-allotment ledger: the treasury blocks a bill that exceeds the DDO's sub-allotment (treasury as budget gatekeeper).
- IFS-NL-002: Treasury acting as DDO for pensioners and Grade-IV GPF subscribers (role overlay on the treasury office).
- IFS-NL-003: Rules pack: CTR + GFR + CPWD Account Code for works; state service rules for the cadre.
- IFS-NL-004: Payment-rail migration support: commercial bank (CMP) → RBI e-Kuber cut-over, run in parallel and reconciled.
- IFS-NL-005: SNA-SPARSH onboarding switch, tracked per state with a cut-over date (late adopter).
- IFS-NL-006: Village Council as a payee or implementing entity (in place of PRIs) for grants. Customary-land cases may need non-standard land/compensation document types [U].

---

## Tripura (IN-TR)

| Field | Finding | Source URL | Accessed 2026-11-04 | Status |
|---|---|---|---|---|
| 1. IFMS system / modules | CAG Audit Report 2023-24, Ch.VI: **IFMS was not implemented as an integrated solution**. It runs standalone **CTOS** (Computerised/Centralised Treasury Operation System, Dec 2010) for budgeting and payments, plus **HRMS** (payroll, e-service book), and they are not integrated. Payroll is entered manually in CTOS as e-vouchers. CAG found about ₹3.97 cr of excess or fraudulent salary payments over six years. The state plans **IFMS 2.0** (SRS consultants engaged) and a next-gen HRMS (NIC). The Finance Department site also lists SNA SPARSH, e-GRAS, GST AIR, NPS and e-Tender. | https://d1jmc0u14z2tmp.cloudfront.net/uploads/download_audit_report/2025/8--Chapter-6-069ccc56ee40cd2.11725036.pdf (via cag.gov.in) ; https://finance.tripura.gov.in/about-us | yes | V |
| 2. Treasury structure | **Directorate of Treasuries** under the Finance Department (created 2014). **9 Treasuries + 13 Sub-Treasuries**. 634 DDOs were flagged in the CAG sample; the total DDO count was not found. | https://finance.tripura.gov.in/about-us | yes | V |
| 3. Financial rules | **Delegation of Financial Power Rules, Tripura, 2019** (2nd edition; 7th amendment 25.01.2021; notification 03.09.2022). **GFR 2017 "as adopted by the State of Tripura"** (modification 16 Mar 2024). **CTR as adopted in the State** (per DFPR). Budget Manual 1998 (per CAG). GeM forward auction adopted (23 Mar 2026). NIT/RFP guidelines (Mar 2025). | https://finance.tripura.gov.in/about-us ; https://finance.tripura.gov.in/budget?page=3 ; https://www.thc.nic.in/Tripura%20State%20Lagislation%20Rules/ | yes | V (DFPR 2019, GeM, NIT); GFR-adoption title/CTR/Budget Manual [U] (search snippet) |
| 4. Bill types / e-payment | Online salary bill module in CTOS. CTOS checks budget availability, head of account, and the **RBI e-Kuber payment-mandate file format** (Govt reply, Jul 2024). | CAG Ch.VI | yes | V |
| 5. Integrations | **RBI e-Kuber** payments (CAG). **SNA-SPARSH notified 17 Dec 2024**. HRMS–CTOS integration missing. IDEA analytics software procured for anomaly detection. | CAG Ch.VI; DoE OM | yes | V |
| 6. Budget classification | Nothing beyond LMMH found. The TTAADC has its own budget under separate rules. | — | — | [U] |
| 7. Languages | Bengali and Kokborok (Tripura Official Languages Act 1964) plus English. A Kokborok official-language cell exists (kokborokoml.tripura.gov.in). | https://kokborokoml.tripura.gov.in/ (listing only) | — | [U] |
| 8. Distinctive features | Sixth Schedule **TTAADC** with its own **TTAADC (Budget & Account) Rules, 1982**. A CAG audit found the council had no treasury pass book since 1994 and unreconciled PLA balances. The Audit Directorate (2002) has a Local Fund Audit wing for GPs and ULBs. | https://www.thc.nic.in/Tripura%20State%20Lagislation%20Rules/The%20Tripura%20Tribal%20Areas%20Autonomous%20District%20Council%20(Budget%20&%20Account)%20Rules,%201982..pdf (title only) | — | [U] (TTAADC rules title seen in search, not opened) |

**Candidate state-specific requirements**
- IFS-TR-001: Hard integration of HRMS (service status, pay entitlement) with salary bills. No free-text net-pay entry; flag allowances not in the employee master. (Directly addresses a CAG fraud finding.)
- IFS-TR-002: Beneficiary master controls: one beneficiary ID per bank account, maker-checker on creating a beneficiary, and duplicate-account detection.
- IFS-TR-003: Rules pack: GFR 2017 as adopted with state modifications (versioned amendments), CTR as adopted, DFPR Tripura 2019 with amendment history.
- IFS-TR-004: TTAADC as a separate accounting entity with its own Budget & Account Rules, a PLA at the treasury, and periodic pass-book/PLA reconciliation.
- IFS-TR-005: Analytics hook for anomaly detection on payroll and payments (state procured IDEA).
- IFS-TR-006: Trilingual UI/print templates: Bengali, Kokborok (Roman/Bengali script) and English [U].
- IFS-TR-007: Migration path from legacy CTOS to IFMS 2.0, with parallel run and data mapping.

---

## Cross-state observations

1. **SNA-SPARSH timing differs (V):** DoE OM of 17-Dec-2024 onboarded MZ, TR, MN and ML at once. AR and NL were laggards with a 01-04-2025 deadline. Nagaland DTA ran e-Kuber/SNA-SPARSH training through 2025. Sangam needs a per-state "fund-flow mode" switch (legacy SNA bank account vs SNA-SPARSH JIT via PFMS + e-Kuber) with an effective date.
2. **Central rules carry much of the load:** AR (CAG cites CTR Vol-I + GFR 2017), NL (CTR, GFR, CPWD Account Code), MN (GFR + CTR + own DFPR 1995) and TR (GFR 2017 and CTR "as adopted", own DFPR 2019) apply central rules with state overlays. MZ uses the central DFPR 1978 via an adaptation order [U]. **Only Meghalaya has its own full set**: Treasury Rules 1985, Financial Rules 1981, DFPR 2006 (the Budget Manual is inherited from Assam). The rules engine should be layered: central base pack → "as adopted by State X" diffs → state DFPR schedules.
3. **NIC-built, modular stacks dominate (TreasuryNet, BEAMS, GRAS, COMPACT/e-Lekha, CTOS).** Meghalaya's MeghIFMS is the most complete, with documented API/SFTP integrations. Tripura is explicitly not integrated (CAG). Arunachal is partly manual and offline. Sangam must support **coexistence and incremental replacement** of modules, not a big-bang replacement.
4. **Extra-territorial and multi-government treasuries:** Shillong (South) Treasury serves ML, Assam, MZ and AR. AR and MN have treasuries at their Resident Commissioner offices in Guwahati and Delhi. The treasury entity needs `owning_government`, `served_governments[]`, `physical_location_state` and a per-government bank and AG mapping.
5. **Payment rails vary:** RBI e-Kuber as sole agency bank (MN, since Feb 2020); e-Kuber via SFTP alongside SBI and PNB agency banks for GRAS (ML); SBI (MZ banking treasuries); SBI CMP → e-Kuber migration (NL, [U]). Payment-file authentication also varies (DSC + SANDES/email OTP in ML).
6. **Autonomous councils are first-class accounting entities:** TTAADC (Budget & Account Rules 1982), Mizoram's Lai/Mara/Chakma ADCs (Fund Rules 1996), Meghalaya's KHADC/JHADC/GHADC (District Fund rules, state share of forest and profession tax), and Manipur's hill ADCs under a state Act with mandatory Finance-Officer vetting. CAG audits ADCs separately. Sangam needs an "ADC / District Fund" entity type with its own budget, PLA at the treasury, statutory revenue-share transfers and reconciliation.
7. **Treasury as budget gatekeeper:** NL treasuries hold DDO sub-allotments, MN uses BEAMS authorization slips, and MZ has e-Sanction with budget checks. Budget-control checkpoints must be configurable at sanction, allotment and treasury pass.
8. **Data gaps:** DDO totals (only NL: 960), 90:10 CSS sharing on state pages, budget code extensions beyond LMMH, and official-language rules are mostly [U]. The next pass should target state Finance Accounts / Accounts at a Glance (cag.gov.in) and state gazettes. megfinance.gov.in and nifims.nagaland.gov.in should be retried from an Indian network.
