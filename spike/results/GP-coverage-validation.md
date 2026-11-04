# GP catalogue coverage check against official process sources

Input: `spike/GOV-PATTERNS.md` (GP-01 to GP-40, 12 domain models).
Method: I compared each domain's officially described stages with the GP list. Sources come from web search and fetch in this session, gov.in and nic.in first. Some sources are not gov.in, or I could only see their search excerpts and not the full text. Those are marked **[secondary]** or **[excerpt only]**. Wherever I could not find a source, the text says **not found**.

Legend for the mapping column: **C** = covered by existing GP IDs, **P** = partly covered (works with a workaround), **G** = gap (needs a new pattern, GP-41 onwards).

---

## 1. Official stage lists by domain, mapped to GP IDs

### 1.1 IFMS: bills and treasury (state IFMS)

Sources: Odisha Finance circulars ([10196](https://finance.odisha.gov.in/sites/default/files/finance_circulars/10196.pdf), [10494](https://finance.odisha.gov.in/sites/default/files/finance_circulars/10494.pdf), [IFMS notification list](https://finance.odisha.gov.in/en/notification/ifms)) [excerpt only]. Rajasthan: [CAG/SNA-SPARSH presentation](https://saiindia.gov.in/uploads/media/Rajasthan-SNA-SPARSH-CAG-19-09-1-068ceb1aa563593-38246907.pdf), [NIC Informatics IFMS Rajasthan](https://informatics.nic.in/files/websites/july-2025/ifms-rajasthan.php), [IFMS 3.0 TA-bill user manual](https://apnigovt.com/wp-content/uploads/2026/02/User-Manual-for-Travelling-Allowance-Bill-IFMS-3.0.pdf) [secondary host].

| # | Official stage | Source | GP mapping | |
|---|---|---|---|---|
| 1 | The sanction order is generated in IFMS. Sanctions generated outside IFMS are refused from 1 Aug 2018 (Odisha) | Odisha circulars | GP-01, GP-34 | C |
| 2 | The DDO checks the head-of-account allocation before sending the bill | Odisha | GP-11 | C |
| 3 | Bill preparation through maker / checker / approver at the implementing agency | Rajasthan presentation | GP-01 | C |
| 4 | The DDO forwards the bill to the treasury. A token number is generated and used to track status | Odisha; Rajasthan manual | GP-15, GP-32 | C |
| 5 | Treasury objection: the bill reappears in the Online Bill module and is corrected and resubmitted | Odisha | GP-02 (internal return only) → **GP-50** | P |
| 6 | A physical copy is still due in the Book of Drawal. Treasuries process online, but block the DDO's *further* claims if the copies are not sent | Odisha (lockdown circular) | GP-29 gate + **GP-53** | P |
| 7 | Payment | Odisha | GP-12 | C |
| — | Salary bill monthly cycle | Odisha lists 2025–26 salary circulars, but I could not read their content. **Stage detail not found** | GP-30 | — |
| — | Receipts / challan reconciliation stages | **not found** in this session | GP-31 | — |
| — | Budget re-appropriation stages (D-BUDGET-REAPPROPRIATION) | **not found** (GFR full text exists at [doe.gov.in GFR updated to 31.01.2026](https://doe.gov.in/files/circulars_document/GFRupdatedupto31012026.pdf), but I did not extract the relevant rules) | GP-01, GP-11 | — |

### 1.2 Pension (Bhavishya, COMPACT, e-PPO, CPENGRAMS)

Sources: [Bhavishya retiree guide](https://bhavishya.nic.in/NoticeBoard/English/4RETIREE_Bhavishya.pdf), [HOO guide](https://bhavishya.nic.in/NoticeBoard/English/2HOO_Bhavishya.pdf), [PAO guide](https://bhavishya.nic.in/NoticeBoard/English/3PAO_Bhavishya.pdf), [Bhavishya FAQ](https://bhavishya.nic.in/FAQs.aspx?pid=faq), [CPENGRAMS OM 16.10.2024](https://www.bsnlpensioner.in/uploads/admin/cpengrams-om-16102024.pdf) [secondary host], [CPENGRAMS monthly report](https://pensionersportal.gov.in/Document/April2024_CPENGRAMS_BHAVISHYA_MonthlyReport.pdf).

| # | Official stage | Source | GP mapping | |
|---|---|---|---|---|
| 1 | The retiree fills the pension forms online and submits them | Retiree guide | GP-16 | C |
| 2 | HOO processing. Form 7, Form 8 and the calculation sheet are generated automatically | HOO guide | GP-01, GP-27 | C |
| 3 | PAO processing in COMPACT at dealing hand → AAO → PAO | PAO guide | GP-01, GP-32 | C |
| 4 | A case rejected in COMPACT is returned automatically to Bhavishya (it crosses systems) | PAO guide | **GP-50** | G |
| 5 | The e-PPO is generated through PFMS and digitally signed by the PAO. CPAO sends it to the bank | PAO guide / FAQ | GP-34, GP-32 | C |
| 6 | The e-PPO is pushed to DigiLocker, with SMS and e-mail to the retiree | Retiree guide | GP-33, GP-15 | C |
| 7 | PPO number updated about 1 month before the date of retirement (a timer relative to a business date) | Bhavishya manual | GP-06, GP-24 | C |
| 8 | Monthly pension disbursement | not detailed in the sources found | GP-30 | C |
| 9 | CPENGRAMS: redressal within 30 days, an interim reply allowed, appeal within 30 days of closure, appeal disposed within 30 days | CPENGRAMS OM | GP-06, GP-22 (interim reply → **GP-45**) | C |

### 1.3 Works / WAMS (CPWD Works Manual)

Sources: [CPWD Works Manual 2022 (NDMC copy)](https://www.ndmc.gov.in/departments/Departments/Finance/nodal_cell/CPWD%20Works%20Manual%202022.pdf), [SOP to CPWD Works Manual 2019](https://ndmc.gov.in/departments/Departments/Finance/nodal_cell/SOP%20to%20CPWD%20Works%20Manual%202019.pdf), [DoE Manual for Procurement of Works, 2nd ed. 2025](https://doe.gov.in/files/manuals_documents/Works_Manual_SE_2025.pdf), [CAG UP road works — contract variations](https://cag.gov.in/uploads/download_audit_report/2017/Chapter_11_Contract_Variations_of_Report_No.4_of_2017_-_Contract_Management_in_Road_Works_Government_of_Uttar_Pradesh.pdf), [CAG Delhi 2007 on clause 10CC](https://cag.gov.in/uploads/old_reports/state/Delhi/2007/Civil/Civil_Delhi_2007/vol_1chap4.pdf). The stage text for the 2022 manual came from search excerpts only. I did not read the full PDF chapter by chapter.

| # | Official stage | Source | GP mapping | |
|---|---|---|---|---|
| 1 | Preliminary estimate | CPWD manual ch. on works procedure [excerpt only] | GP-01 | C |
| 2 | Administrative approval (AA) and expenditure sanction (ES) | CPWD manual | GP-05 | C |
| 3 | The detailed estimate and technical sanction (TS) come *after* AA/ES | CPWD manual | GP-05 | C |
| 4 | Availability of funds (the fourth precondition) | CPWD manual | GP-11 | C |
| 5 | An excess of up to 10% over the AA amount may be authorised by the TS authority. Anything beyond needs a revised AA/ES | CPWD manual (excerpt); 2019 SOP "excess up to 10% of A/A and E/S" | **GP-42** | G |
| 6 | Tender (see 1.4) | DoE Works manual | D-TENDER | C |
| 7 | Award and agreement, with performance security (now 3%), BG or insurance surety bond | [DoE Performance Security OM](https://doe.gov.in/files/circulars_document/Performance_Security.pdf); [GFR amendment on surety bonds (gconnect)](https://www.gconnect.in/orders-in-brief/conduct-rules/amendment-general-financial-rules-gfr-2017-insurance-surety-bonds.html) [secondary] | **GP-41** | G |
| 8 | Measurement book recording. MB custody and loss of MB are covered by sections of the manual | CPWD manual (TOC excerpt only) | GP-10, **GP-53** | P |
| 9 | Running-account bills | CPWD manual | GP-10 | C |
| 10 | Price escalation under clause 10CC (index formula; usually only above a set contract duration) | CAG Delhi 2007; [CAG Union 2007-08 ch.10](https://saiindia.gov.in/uploads/old_reports/union/union_compliance/2007_2008/Civil/Report_no_2/chap_10.pdf) | GP-10 + DMN (no new engine behaviour) | C |
| 11 | Deviations and extra items, extension of time (hindrance), LD levy or waiver | CAG UP 2017 (442 EoTs, "time extension without levy of penalty"); [MoRTH EoT circular 2021](https://morth.gov.in/sites/default/files/comprehensive_compendium_circular/130.197-09.09.2021%20Consultancy%20services%20for%20Authority%E2%80%99s%20EngineerIndependent%20Engineer%20%E2%80%93%20Regarding%20Extension%20of%20Time%20%28EOT%29._0001%20%281%29.pdf) (title only). CPWD GCC clause numbers for EoT and extra items: **not found** | **GP-43** | G |
| 12 | Final bill and completion | CPWD manual | GP-01, GP-05 | C |
| 13 | Release of the security deposit after the defect liability period | **not found** in this session's sources (an assumption from standard practice; verify in the GCC) | **GP-41** | G |

### 1.4 Tender / e-procurement (GFR, DoE manuals, CPPP/GePNIC)

Sources: [GFR 2017 (doe.gov.in)](https://doe.gov.in/files/inline-documents/GFR2017.pdf), [GFR updated to 31.01.2026](https://doe.gov.in/files/circulars_document/GFRupdatedupto31012026.pdf), [Manual for Procurement of Goods 2nd ed. 2024](https://doe.gov.in/files/manuals_documents/Goods.pdf), [Works manual 2025](https://doe.gov.in/files/manuals_documents/Works_Manual_SE_2025.pdf), [Consultancy manual (2022 update)](https://doe.gov.in/files/manuals_documents/Manual_for_Procurement_of_Consultancy_&_Other_Services_Updated%20June,%202022_1.pdf), [CPPP training page](https://eprocure.gov.in/cppp/trainingdisp/kbadqkdlcswfjdelrquehwuxcfmijmuixngudufgbuubgubfugbububjxcgfvsbdihbgfGhdfgFHytyhRtMzAw) [excerpt only; a direct fetch failed with ECONNRESET], [NCSM two-cover NIT](https://vaccine.ncsm.gov.in/storage/files/tendernotice-1-1.pdf), [MoSPI NIT (financial opening TBA)](https://mospi.gov.in/sites/default/files/tender_notification/cc_tender_26may15.pdf).

| # | Official stage | Source | GP mapping | |
|---|---|---|---|---|
| 1 | NIT published on CPPP / GePNIC | CPPP training | GP-24 | C |
| 2 | Corrigendum, *before* and *after* bid opening (two kinds) | CPPP training | GP-07 | C |
| 3 | Two-cover submission (cover 1 technical with bid security declaration; cover 2 price) | NCSM NIT | GP-15, GP-23 | C |
| 4 | A bid security declaration instead of EMD; MSE / startup exemptions (Rule 170) | [DoE circular / GFR 170 notes (Tripura)](https://finance.tripura.gov.in/sites/default/files/notification%20gfr.pdf) [excerpt] | GP-29 (DMN) | C |
| 5 | Technical bid opening | CPPP training | GP-23, GP-03 | C |
| 6 | Technical evaluation | CPPP training | GP-03 | C |
| 7 | Financial bid opening; the date is often "to be announced" at run time | CPPP; MoSPI NIT | GP-24, GP-07 | C |
| 8 | Comparative chart and financial evaluation → award | CPPP training | GP-03, GP-25 | C |
| 9 | Performance security from the successful bidder (5–10%, reduced to 3%) | DoE Performance Security OM | **GP-41** | G |
| 10 | EMD refund | — | GP-13 | C |

### 1.5 e-Recruitment (SSC / UPSC, DoPT roster)

Sources: DoPT roster: [DoPT OM 01.04.2005 (backlog)](https://documents.doptcirculars.nic.in/D2/D02adm/36012_5_97_01April2005.htm), [NCST copy of DoPT OM 19.11.2008](https://ncst.nic.in/uploads-dev/dopt_circulars/OM19112008-098737654435.pdf), [DoPT reservation-in-promotion notes](https://staffnews.in/wp-content/uploads/2020/08/Brief-Notes-on-Reservation-in-Promotion-by-DoPT.pdf) [secondary]. SSC answer-key process: only **[secondary]** news sources ([careers360 CGL 2025](https://news.careers360.com/ssc-cgl-2025-answer-key-out-tier-1-13-5-lakh-response-sheets-ssc-gov-in-challenge-link-fee-active-question-id-last-date/amp), [bureaucratsindia on fee cut and normalisation](https://bureaucratsindia.in/news/public-affairs/ssc-rolls-out-major-exam-reforms-cuts-challenge-fee-and-adopts-normalization-for-fair-results)). An official ssc.gov.in notice was **not found**. UPSC stage documents were **not found** in this session.

| # | Official stage | Source | GP mapping | |
|---|---|---|---|---|
| 1 | Notification | — | GP-24 | C |
| 2 | Application and fee | — | GP-08, GP-15 | C |
| 3 | Exam and admit cards | — | GP-27 | C |
| 4 | Normalisation of scores across shifts | secondary | GP-09 (bulk external task) | C |
| 5 | Tentative answer key → paid challenge window (a few days) → expert review → final key → result | secondary | **GP-47** | G |
| 6 | Result under the post-based reservation roster (OM 02.07.1997, text not seen; described in OM 01.04.2005) | DoPT OM 2005 | GP-26 | C |
| 7 | Backlog / carry-forward vacancies kept as a separate group across recruitment years; they lapse once representation is met | DoPT OM 2005 | **GP-48** | G |
| 8 | DV with waitlist | — | GP-28 | C |
| 9 | Post-result discrepancy window (e.g. one month, SSC MTS) | [secondary](https://edunovations.com/ssc-mts-final-result-2024-declared/amp/) | GP-22 | C |

### 1.6 e-Office file movement (CSMOP 2022, eFile)

Sources: [CSMOP 2022, 16th ed. (MoEF copy)](https://moef.gov.in/uploads/2017/09/CSMOP-2022-Sixteenth-Edition.pdf), [DIPAM amendment on level of disposal](https://dipam.gov.in/ddaservice/dipam_docs/Circulars/Amendment%20of%20the%2016th%20Edition%20of%20the%20Central%20Secretariat%20Manual%20of%20Officer%20Procedure%2C%202022.pdf), [CAG eOffice manual](https://cag.gov.in/uploads/media/eofficeManual-063919e87cf2040-11483959.pdf), [ICSSR eOffice SOP](https://icssr.org/sites/default/files/2024-05/SOP-v1.pdf), [ICSSR physical file movement](https://icssr.org/sites/default/files/2024-05/eOffice-physical-file-movement.pdf), [WBIIDC circular](https://wbiidc.wb.gov.in/images/pdf/scan0659_New.pdf), [Edudel eOffice FAQ](https://edudel.nic.in/edu/e_offince_dt_11122024/EOfficeFAQ.pdf). I did not extract the CSMOP chapter text for receipt, noting or retention in this session. The rows below come from the eOffice documents.

| # | Official stage / feature | Source | GP mapping | |
|---|---|---|---|---|
| 1 | Receipt diarised and put on a file | CSMOP (text not extracted) | GP-15 | C |
| 2 | Noting and drafting up the channel of submission, within the prescribed level of disposal | CSMOP; DIPAM amendment | GP-01, GP-02 | C |
| 3 | Link / delink other eFiles to the working file | CAG manual / ICSSR | GP-22 (link only) → **GP-54** | P |
| 4 | Park a file with a due date and remarks; parking removes pendency | eOffice manuals | **GP-45** | G |
| 5 | Create volume / create part (for physical files) | eOffice manuals | **GP-54** | G |
| 6 | Physical-file movement tracked electronically | ICSSR | **GP-53** | G |
| 7 | Close the file with remarks (authority: division head and above) | ICSSR SOP | GP-01 | C |
| 8 | Retention period / record review | WBIIDC: "to be specified in due course". CSMOP retention schedule: **not extracted** | **GP-52** | G |
| 9 | DSC / eSign on notings and drafts | eOffice manuals | GP-34 | C |

### 1.7 Grievance (CPGRAMS, DARPG)

Sources: [DARPG comprehensive guidelines (pgportal)](https://pgportal.gov.in/Home/Preview/Q29tcHJlaGVuc2l2ZUd1aWRlbGluZXNGb3JIYW5kbGluZ1RoZVB1YmxpY0dyaWV2YW5jZXMucGRm), [CSIR letter forwarding the DARPG OM 23.08.2024](https://www.csir.res.in/sites/default/files/2024-12/csir_lt_dt_05.12.2024-handling_of_public_grievance_0.pdf), [NPS-CRA circular 24.02.2025](https://www.npscra.proteantech.in/download/Circular%20dated%2024.02.2025%20resolution%20of%20grievances%20on%20CPGRAMS_Eng_Version.pdf).

| # | Official stage | Source | GP mapping | |
|---|---|---|---|---|
| 1 | Lodging (portal, and per the 2025 circular also chatbot, WhatsApp, call centre) | NPS-CRA circular | GP-15 | C |
| 2 | Routed to the GRO / sub-ordinate organisation | DARPG guidelines | GP-21, GP-32 | C |
| 3 | Disposal ordinarily within 21 days; an interim ATR if more time is needed | DARPG OM 23.08.2024 | GP-06; interim extension → **GP-45** | P |
| 4 | Closure and citizen feedback | DARPG | GP-33 | C |
| 5 | One appeal only per grievance | NPS-CRA circular | GP-22 (+ guard) | C |
| 6 | Appeal to the Nodal Appellate Authority (JS level) or Sub-Appellate Authorities; disposal within 30 days | pgportal guidelines; DARPG | GP-06, GP-18, GP-22 | C |

### 1.8 RTI (RTI Act 2005)

Source: the Act text at [cic.gov.in RTI Act PDF](https://cic.gov.in/sites/default/files/RTI-Act_English.pdf). The fetch worked, but the PDF is a scanned image, so I could **not** machine-read the section text. The section time limits below are the well-known statutory figures. Re-check them against the Act before using them in test fixtures. The 48-hour proviso is confirmed by [LiveLaw / CIC reporting](https://www.livelaw.in/amp/rti/all-public-authorities-should-encourage-rti-applications-through-e-mail-in-case-of-life-liberty-matter-cic-155895) [secondary].

| # | Statutory stage | Section | GP mapping | |
|---|---|---|---|---|
| 1 | Application and fee | s6(1) | GP-15 | C |
| 2 | Transfer to another public authority within 5 days | s6(3) | GP-32, GP-06 | C |
| 3 | Decision within 30 days, or 48 hours for life or liberty | s7(1) | GP-35 | C |
| 4 | Further fee intimation; time from that intimation until payment is excluded from the 30 days | s7(3) | **GP-45** | G |
| 5 | Third-party notice and representation; the decision clock is extended | s11 | **GP-46** | G |
| 6 | Failure to decide in time is *deemed refusal* | s7(2) | **GP-44** | G |
| 7 | First appeal within 30 days; disposal in 30 (extendable to 45) days | s19(1), 19(6) | GP-22, GP-35 | C |
| 8 | Second appeal to CIC/SIC within 90 days; hearings | s19(3) | GP-22 + **GP-51** | P |
| 9 | Penalty on the PIO | s20 | GP-21 | C |

### 1.9 HR / leave (CCS (Leave) Rules 1972)

Sources: [DoPT CCS Leave Rules (ISTM copy)](https://www.istm.gov.in/uploads/tenders/17037623701674040098Leave_Rules.pdf), [MCRHRDI leave rules deck](https://mcrhrdi.gov.in/2025/itpepd/week1/ccs%20Leave%20Rules.pdf) [training material]. Recall from leave: **not found**.

| # | Official stage / rule | Source | GP mapping | |
|---|---|---|---|---|
| 1 | Application → sanction by the competent authority | Rule 7 | GP-29, GP-01 | C |
| 2 | Leave cannot be claimed as of right. It may be refused, curtailed or *revoked* in the public interest, including after sanction | Rule 7(1) | GP-14 (applicant-initiated only) + **GP-43** (amendment of a live instance) | P |
| 3 | The kind of leave cannot be altered except at the employee's written request | Rule 7(2) | GP-19 / DMN guard | C |
| 4 | Leave account / balance carried across applications | Rules (leave account) | **GP-48** | G |

### 1.10 Scheme / DBT (DBT Mission SOP, PFMS, NPCI APB)

Sources: [DBT Mission SOP for DBT Payments](https://www.dbtbharat.gov.in/data/documents/SOP%20for%20DBT%20Payments.pdf), [PFMS validation and payment-rejection remedies (CGA)](https://gifmis.cga.gov.in/SitePages/doc/PFMS_Validation_Payment_Rejection_Remedies.pdf), [PFMS DBT FAQ](https://gifmis.cga.gov.in/SitePages/doc/PFMS_DBT_FAQ.pdf), [NPCI APB SOP v3.0.3](https://www.npci.org.in/PDF/nach/notofied-document/Aadhaar-Payments-Bridge-(APB)-System-SOP_V3.0.3.pdf).

| # | Official stage | Source | GP mapping | |
|---|---|---|---|---|
| 1 | Beneficiary identification and enrolment | DBT SOP | GP-08, GP-16 | C |
| 2 | Beneficiary validation / registration on PFMS (Aadhaar mapper check, bank account check) | DBT SOP | GP-15, GP-12 | C |
| 3 | Generation of the payment file | DBT SOP | GP-27, GP-30 | C |
| 4 | Processing of the payment file → payment through NPCI (APBS/NACH) | DBT SOP; PFMS FAQ | GP-12, GP-31 | C |
| 5 | Return reasons (not seeded, de-seeded, UID cancelled …), each with a different corrective actor (beneficiary / bank / department), then a re-run | PFMS remedies. A formal reprocess procedure: **not found** | **GP-49** | G |

### 1.11 Licences / public services (state Right to Public Services Acts)

Sources: [West Bengal RTPS Bill 2013 (PRS)](https://prsindia.org/files/bills_acts/bills_states/west-bengal/2013/Bill11of2013WB.pdf), [Maharashtra RTPS Act 2015 (PRS)](https://prsindia.org/files/bills_acts/acts_states/maharashtra/2015/2015MH31.pdf), [Kerala Bill 2025 (PRS)](https://prsindia.org/files/bills_acts/bills_states/kerala/2025/Bill280of2025KL.pdf), [India Code state RTS act](https://www.indiacode.nic.in/bitstream/123456789/14559/1/2013-17.pdf).

| # | Official stage | Source | GP mapping | |
|---|---|---|---|---|
| 1 | Application with documents | RTPS Acts | GP-15, GP-16 | C |
| 2 | Service by the designated officer within the notified time | Maharashtra s.3 | GP-06 | C |
| 3 | First appeal → second appeal (Maharashtra adds a third, to the Commission, within 60 days); penalty on the officer (WB Rs 250–1000) | WB, MH, Kerala | GP-22, GP-21 | C |
| 4 | Deemed approval on timeout | **not found** in the RTPS sources. (RTI s7(2) deemed *refusal* is the sourced example of the behaviour.) | **GP-44** | G |
| 5 | Licence renewal / periodic recertification | **not found** in this session | **GP-55**-like | G |

---

## 2. Missing patterns (proposed GP-41 to GP-55)

| ID | Pattern | Description | Examples | Sources | Engine features exercised |
|---|---|---|---|---|---|
| **GP-41** | Security-instrument lifecycle | A BG, performance security, EMD or insurance surety bond is attached to a contract. It is verified with the issuing bank, its validity is tracked, extension is requested a set time before expiry, and it is encashed if not extended. It is released at contract close or after the defect liability period. It runs as its own long-lived child of the contract. | Performance security (3%), EMD, mobilisation-advance BG, BG for licences | [DoE Performance Security OM](https://doe.gov.in/files/circulars_document/Performance_Security.pdf); [CVC e-verification of BGs (Business Standard)](https://www.business-standard.com/amp/article/pti-stories/cvc-for-e-verification-of-bank-guarantees-to-check-frauds-116031000882_1.html) [secondary]; [Goa MPT BG acceptance note citing CVC 31/08/10](https://www.mptgoa.gov.in/admin/Public-document/172794006387383411.-Acceptance-of-Bank-Guarantee.pdf); [insurance surety bonds in GFR 170/171](https://www.gconnect.in/orders-in-brief/conduct-rules/amendment-general-financial-rules-gfr-2017-insurance-surety-bonds.html) [secondary]. "3 months before expiry" appears only in an unattributed guideline: **CVC wording not found** | Timer computed from a business date that changes on extension (re-arm a timer); message correlation from the bank's confirmation; a race between "extension received" and "expiry"; child instance tied to the parent's lifecycle |
| **GP-42** | Cumulative-threshold re-sanction | The running total across many child steps (RA bills, variations, excess over AA) is compared against a sanctioned ceiling. Within the tolerance (e.g. 10% over AA) a lower authority can approve. Beyond it, the flow must go back for a *revised* sanction (revised AA/ES/TS) while the work goes on. | Excess over AA/ES, RA billing vs contract value, scheme allocation exhaustion | CPWD Works Manual 2022 / 2019 SOP (10% excess) — links in 1.3 | An aggregate kept across instances or loop iterations; a DMN band evaluated on the cumulative value; a call activity back into GP-05 while the parent keeps running (non-interrupting) |
| **GP-43** | Live contract amendment | After award, a variation order, extra item, extension of time, LD levy or waiver, or leave revocation changes data, deadlines or amounts *of an instance already running*. The amendment has its own approval chain. When it is approved, it changes the parent's variables and timers, and the history keeps the old and new values. | Deviation statements, EoT with or without LD, revocation or curtailment of leave | [CAG UP 2017 contract variations](https://cag.gov.in/uploads/download_audit_report/2017/Chapter_11_Contract_Variations_of_Report_No.4_of_2017_-_Contract_Management_in_Road_Works_Government_of_Uttar_Pradesh.pdf); [MoRTH EoT circular](https://morth.gov.in/sites/default/files/comprehensive_compendium_circular/130.197-09.09.2021%20Consultancy%20services%20for%20Authority%E2%80%99s%20EngineerIndependent%20Engineer%20%E2%80%93%20Regarding%20Extension%20of%20Time%20%28EOT%29._0001%20%281%29.pdf) (title only); CCS Leave Rule 7 (1.9) | A child process signals the parent; variable update plus re-arming the timer on the parent; auditable before and after values in history (GP-36) |
| **GP-44** | Deemed outcome on timeout | When a statutory clock expires, the case moves to a *legal outcome* (deemed refusal or deemed approval). This is more than an escalation. The outcome itself opens the next right (an appeal). | RTI s7(2) deemed refusal; RTPS deemed service (not found in sources) | RTI Act (1.8) | An interrupting boundary timer that completes with a set outcome; later late action by the officer must be blocked or recorded |
| **GP-45** | Pausable statutory clock | The SLA clock *stops* while the matter waits on the citizen or another party, and resumes with the remaining time. This differs from GP-07, which only moves the deadline. | RTI s7(3) fee period excluded; eOffice "park" removes pendency; CPGRAMS interim ATR; hindrance periods in EoT | RTI Act; eOffice manuals (1.6); DARPG OM (1.7) | A timer that can be suspended and resumed with remaining-duration arithmetic, on a business calendar (GP-06); queries must show "net elapsed" |
| **GP-46** | Third-party / objection window | A notice goes to one or more outside parties, each with a fixed window to object. The main decision waits for every response or for the window to close. | RTI s11; land-acquisition objections; licence objections | RTI Act; RFCTLARR (section 3) | Parallel multi-instance of message catch + timer per party; extends the parent clock (links to GP-45) |
| **GP-47** | Provisional → objection → bulk recompute → final | A provisional result is published to everyone, then a time-boxed paid challenge window opens. Objections are reviewed, the correction is applied, and *all* affected results are recomputed and republished as final. | SSC tentative / final answer key; tentative seniority lists; draft beneficiary lists | SSC [secondary] sources in 1.5 | Fee callback (GP-15) for each objection; a timer-gated window; bulk recompute over GP-09 volumes; versioned result sets |
| **GP-48** | Cross-instance ledger / carry-forward | The state that decides a new instance lives *outside* any single instance and carries over from one year or cycle to the next: unfilled reserved vacancies, leave balance, budget remaining. Instances must read and update it under concurrency. | Reservation roster backlog, leave account, allocation balance | [DoPT OM 01.04.2005](https://documents.doptcirculars.nic.in/D2/D02adm/36012_5_97_01April2005.htm); CCS Leave Rules | External-task read and update with idempotency (GP-11 style, but for a non-financial ledger); concurrent instances against one ledger; tests for the end-of-cycle lapse rule |
| **GP-49** | Partial batch failure, reason-coded recovery | One batch (a payment file) goes out. Results come back per item, each with a reason code. Each reason routes the item to a different corrective actor. Only the fixed items are batched again. | DBT/PFMS returns, salary credit failures | [PFMS rejection remedies](https://gifmis.cga.gov.in/SitePages/doc/PFMS_Validation_Payment_Rejection_Remedies.pdf); [DBT SOP](https://www.dbtbharat.gov.in/data/documents/SOP%20for%20DBT%20Payments.pdf) | Many correlations per batch (GP-15 at scale); DMN routing on the reason code; regrouping a subset into a new batch |
| **GP-50** | Return to the originating system, keeping the same identity | An external authority (treasury, PAO/COMPACT) objects. The case goes back to the originating system or seat under the *same* token or case ID, and comes back resubmitted. The objection count and history stay on that one identity. | Treasury bill objection; COMPACT rejection → Bhavishya | Odisha circulars (1.1); [Bhavishya PAO guide](https://bhavishya.nic.in/NoticeBoard/English/3PAO_Bhavishya.pdf) | Message correlation to a waiting instance by business key; a loop across a system boundary; GP-32 in both directions |
| **GP-51** | Hearing series driven by external dates | An outside forum (court, CIC/SIC, appellate authority, land-acquisition collector) sets each next date. Every hearing produces tasks (brief, counter-affidavit, compliance) and either a new date (adjournment) or a final order. | Court cases (LIMBS 7-day hearing dashboard), RTI second appeal, LA s15 hearing | [DoLA LIMBS reply AU2511](https://legalaffairs.gov.in/sites/default/files/AU2511.pdf); [LIMBS live-cases dashboard](https://www.policyedge.in/p/government-launches-real-time-dashboard) [secondary]. Counter-affidavit workflow: **not found** | A loop with a timer date taken from an inbound message; tasks with deadlines relative to the hearing date; long-lived instances (GP-10) |
| **GP-52** | Record lifecycle after closure | A closed file or case enters a retention period, then record review with a choice to retain, transfer or weed. Reopen (GP-22) must still find it. | eOffice file closure → retention; RTI and audit record keeping | WBIIDC eOffice circular (retention "to be specified"); CSMOP 2022 (text not extracted) | Timers after completion; history retention or cleanup settings versus audit (GP-36) |
| **GP-53** | Physical-artefact custody tracking | A paper item (physical file, measurement book, original BG, Book of Drawal) moves alongside the digital workflow. Its custody changes are recorded, and a missing artefact can block later steps. | eOffice "physical" file type; MB custody / loss of MB; Odisha physical bill copies | [ICSSR physical file movement](https://icssr.org/sites/default/files/2024-05/eOffice-physical-file-movement.pdf); CPWD manual TOC; Odisha circular 10494 | User tasks that record custody; a gate condition on an outside fact; a compensating "loss" path |
| **GP-54** | Case split / part / link | A case is split into parts (part file, volume), or linked or attached to another case. Actions on one may need the other's state. | eOffice create part / volume / link; clubbing grievances; tender split into lots | [CAG eOffice manual](https://cag.gov.in/uploads/media/eofficeManual-063919e87cf2040-11483959.pdf); [ICSSR SOP](https://icssr.org/sites/default/files/2024-05/SOP-v1.pdf) | Creating sibling instances with a correlation key; cross-instance queries; merge: **not found** (whether eFile allows merging is unanswered in the FAQ excerpt) |
| **GP-55** | Periodic verification campaign that spawns discrepancy cases | On a fixed cycle, every item in a population (stores, assets, beneficiaries, licences) is verified. Any discrepancy starts its own investigation case, with responsibility fixed for loss or negligence. | GFR Rule 213 physical verification; Rule 217(iv) fixing responsibility; DBT re-verification and licence renewal (not sourced) | GFR 2017 Rules 213 / 217 ([doe.gov.in](https://doe.gov.in/files/inline-documents/GFR2017.pdf); excerpts via [GFR extract](https://sabangcollege.ac.in/GovtOrders/22573GFRExtractofGeneralFinancialRules.pdf)) | Timer start cycle (GP-30) × multi-instance (GP-09) → conditional start of a GP-21 case |

---

## 3. Missing domains for an all-departments product

| Domain | Official stage outline (sourced) | Maps to existing GP | New patterns needed |
|---|---|---|---|
| **Stores / inventory** | Receipt certified and entered in the stock register (Rule 208) → physical verification (Rule 213), discrepancies investigated → declaring items surplus / obsolete / unserviceable, report in Form GFR-10 (Rule 217) → disposal; mode set by the competent authority below Rs 2 lakh residual value (Rule 218) → responsibility fixed for loss through negligence (217(iv)). Sources: [GFR 2017](https://doe.gov.in/files/inline-documents/GFR2017.pdf), [CSIR stores compendium](https://www.csir.res.in/sites/default/files/2023-12/Compendium-Stores-%26-Inventory-DSC.pdf) | GP-01, GP-03 (survey / condemnation board), GP-29, D-TENDER (auction) | GP-55, GP-48 (stock ledger), GP-53 |
| **Fixed asset / vehicle management** | Physical verification of fixed assets (Rule 213); disposal (Rules 217–218). Vehicle-specific procedures (logbook, condemnation by km / age): **not found** | GP-01, GP-30 | GP-55, GP-48 |
| **Court cases / litigation** | Cases are entered and monitored in LIMBS by each ministry; a hearing dashboard covers the next 7 days. Sources: [DoLA AU2511](https://legalaffairs.gov.in/sites/default/files/AU2511.pdf), [UX4G LIMBS case study](https://ux4g.gov.in/case-studies/ux4g-limbs.php). Counter-affidavit, vetting and appeal-decision stages: **not found** | GP-21, GP-32 (Law ministry concurrence), GP-40 (stay), GP-06 | GP-51, GP-43 (compliance with an order changes a live case) |
| **Audit paras** | DAN reply within 4 weeks (Odisha); ATN on C&AG paras within 3 months of the report being laid; ATNs vetted by audit; PAC action-taken replies; tracked in CGA's APMS. Sources: [CGA APMS](https://cga.gov.in/Page/Audit-Para-Monitoring-System-APMS.aspx), [Odisha circular 23930](https://finance.odisha.gov.in/sites/default/files/finance_circulars/23930.pdf), [CAG Union Compliance 2015 ch.18](https://cag.gov.in/uploads/download_audit_report/2015/Union_Compliance_Civil_Report_18_2015_chap_18.pdf). A "four months" Union deadline: **not found** (3 months found) | GP-21, GP-02, GP-06, GP-32 (vetting by audit), GP-36 | GP-50 (returned by audit for re-vetting), GP-54 (one ATN covering several paras) |
| **Land acquisition (RFCTLARR 2013)** | SIA (s4) → expert-group appraisal → preliminary notification (s11) within 12 months, else the SIA lapses (s14) → objections / hearing (s15) → declaration (s19) within 12 months of s11 → award (s23) and R&R award (s31). Sources: [TN copy of the Act](https://cms.tn.gov.in/cms_migrated/document/rules_regulations/RFCTLARR_Act,_2013_090518.pdf), [CAG 2024 ch.3](https://cag.gov.in/uploads/download_audit_report/2024/12.-Chapter-3---Copy-066e27b7be6eaf4.07509376.pdf). The 12-month s19 figure is from CAG and I have not checked it against the statute | GP-05, GP-06, GP-24, GP-09 (many landholders), GP-12 (compensation), GP-40 | GP-44 (lapse on timeout), GP-46 (s15 objections), GP-51 (hearings) |
| **Contract variations / extension of time** | Variation sanction; EoT with or without penalty; hindrance assessment. Source: [CAG UP 2017](https://cag.gov.in/uploads/download_audit_report/2017/Chapter_11_Contract_Variations_of_Report_No.4_of_2017_-_Contract_Management_in_Road_Works_Government_of_Uttar_Pradesh.pdf). CPWD GCC clause procedure: **not found** | GP-01, GP-05 | GP-43, GP-42, GP-45 (hindrance periods) |
| **Price escalation** | Clause 10CC index formula, applied to contracts longer than a set duration. Sources: [CAG Delhi 2007](https://cag.gov.in/uploads/old_reports/state/Delhi/2007/Civil/Civil_Delhi_2007/vol_1chap4.pdf), [CAG Union 2007-08](https://saiindia.gov.in/uploads/old_reports/union/union_compliance/2007_2008/Civil/Report_no_2/chap_10.pdf) | GP-10, GP-19 (index version), DMN | none (computation only) |
| **Liquidated damages** | LD levied for contractor delay; can be waived when EoT is granted. Sources: CAG UP 2017; [TN G.O. 227/2009](https://tn.gov.in/dtp/tamil/gorders/maws_e_227_2009_ms.pdf) [excerpt only]. The LD cap rule in the DoE manuals: **not found** (not extracted) | GP-01, GP-29 | GP-43 |
| **Bank guarantees and their expiry** | Verification with the issuing bank (CVC); extension or encashment before expiry. Sources in GP-41 | GP-15, GP-06 | GP-41 |
| **Consultancy procurement** | QCBS / LCS etc., GFR Rules 192–194. Source: [DoE consultancy manual](https://doe.gov.in/files/manuals_documents/Manual_for_Procurement_of_Consultancy_&_Other_Services_Updated%20June,%202022_1.pdf) (stage text not extracted) | D-TENDER patterns (GP-03, GP-23, GP-25) | GP-41 |
| **Inspections (statutory / field)** | **No official generic inspection SOP found** in this session | GP-17, GP-16, GP-21 | GP-55 (inspection cycles) |
| **Right to Public Services (generalised licences)** | Notified time limit → first and second (and in MH, third) appeal → penalty (links in 1.11) | GP-06, GP-22, GP-21, GP-37 (state variants) | GP-44 |

Other candidates **not researched in this session (no sources looked up)**. Each needs its own validation before it goes into the catalogue: disciplinary proceedings (CCS CCA), APAR, DPC/promotion and MACP, transfers and postings, Parliament questions and VIP references, foreign deputation and visa clearance, vigilance clearance, training nominations.

---

## 4. Coverage verdict

**Basis.** I split the 11 domains already in the catalogue into the 86 stage rows in section 1. Each row comes from a sourced official stage, or is clearly marked as not found. I then marked each row C, P or G against GP-01 to GP-40. This is a count over my own breakdown, not a measured statistic, and the breakdown has known limits: CSMOP, the CPWD 2022 manual and the RTI PDF could not be read in full.

| | Rows | Share |
|---|---|---|
| Covered (C) | 60 | ~70% |
| Partly covered (P) | 7 | ~8% |
| Gap (G) | 19 | ~22% |

Where the gaps fall:
- **Post-award contract administration** in Works and Tender: performance security, cumulative excess, variations and EoT, LD, security-deposit release. This is GP-41, GP-42 and GP-43.
- **Statutory-clock semantics** in RTI, grievance, e-Office and RTPS: deemed outcomes, pausable clocks, third-party windows. This is GP-44, GP-45 and GP-46.
- **Cross-instance state**: roster backlog and leave balance. This is GP-48.
- **Hybrid paper/digital and file structure** in e-Office: GP-52, GP-53 and GP-54.

**Judgement.**
- **Listed domains, application-to-decision part:** the existing catalogue covers most of the *control flow*. Notification, application, scrutiny, approval chains, committees, two-envelope opening, merit lists, payments and appeals are all there.
- **Listed domains, long-lived post-decision part:** coverage is weak. Contract management, instrument expiry, ledgers and record retention are where real departmental workflows spend most of their elapsed time.
- **Breadth for an all-departments product:** the catalogue has 11 domains. Section 3 adds 12 more sourced domains, plus 8 more unresearched candidates. By domain count, the current domain models cover well under half of what an all-departments product would carry.
- **Pattern level:** most of the missing domains reuse existing patterns heavily. Each one maps mainly to GP-01, GP-05, GP-06, GP-21, GP-22 and GP-32. The 15 new patterns are enough to model them.

**Verdict:**
- With GP-41 to GP-55 added, the catalogue is a credible test basis for the engine decision.
- Without them, it under-tests three engine areas: re-arming and pausing timers on live instances, changing parent data from a child, and state that spans instances. Those are the areas where Operaton and Flowable are most likely to differ.

**Open verification items** (sources needed before the patterns become test fixtures):
- the RTI section text (the PDF is scanned);
- CPWD GCC clause numbers for EoT, extra items and LD;
- CVC wording on BG expiry and extension;
- the CSMOP 2022 retention schedule and receipt / noting chapters;
- an official SSC answer-key notice;
- the UPSC stage documents;
- deemed approval in any state RTPS Act.
