<!-- SPDX-License-Identifier: Apache-2.0 -->
# Capability coverage: every department system and every sector against Sangam's generic capabilities

Status: 2026-11-05. Workstream: CAPABILITY-COVERAGE (research only; no code, nothing committed).
Decision status of everything proposed here: **PROPOSED**. Delivery: **NOT STARTED**.

**Why this exists.** ADR-D-20 (SANGAM.md X.0e) says the generic capabilities must serve every system a central or state department runs, such as IFMS, IHMS, bank management and circuit-house management, and equally serve private clients, with nothing built for one department. ADR-D-22 fixes the boundary. Sangam provides workflow, rules, approvals, generic fund/limit ledgers, master data and documents, and it integrates with the systems of record. This document tests that claim system by system. It answers four questions:

1. What do departments and sectors run? Sections 3 and 4 hold the catalogue.
2. Which existing capability carries each system's workflows? Section 5.
3. Which generic capabilities are missing, and how widely are they needed? Sections 6 and 7.
4. What should be built, and where does each piece sit relative to the D-22 boundary? Sections 8 and 9.

**Legend.**
- **[U]**: unverified. The name, count or scope still needs checking against the official source before it is relied on.
- **[I]**: design inference.
- **●**: core need. The system cannot be run end to end without the capability.
- **○**: secondary need. The capability is used by a sub-flow, or it improves the system without being essential.
- **GC / GS / GX / P***: catalogue row ids. GC = central ministry or department, GS = state department type, GX = cross-cutting system that every department runs, P* = private sector system.
- **C01–C16**: existing or in-progress generic capabilities (section 2).
- **N01–N18**: missing generic capabilities (section 8).

---

## 0. Summary

| Item | Count |
|---|---|
| Central ministry/department rows (ministries merged where their systems are alike) | 52 |
| State department types | 55 |
| Cross-cutting systems run by every department | 24 |
| Private-sector systems | 67 systems in 21 sectors |
| **Catalogue rows mapped** | **198** |
| Existing generic capabilities (built, planned or in progress) | 16 (C01–C16) |
| **Missing generic capabilities** | **18 (N01–N18)** |
| Proposed new platform modules | 8 (plus pack families and two extensions of existing modules) |

**Main finding.** The approval core, DMN, forms, SLA, connectors, documents and the fund/limit ledger already cover the *approval* half of nearly every system. That half is the scrutiny → recommendation → sanction → execution lifecycle (IX.3) together with its exceptions. What is missing is the *operational object* half. The workflows act on things such as a room, a stock item, an asset, an issued licence, a person, a demand, a place or a sealed parcel, and Sangam has no generic model for those things today. The top five gaps are as follows:

- **N01**: booking, allotment and scheduling.
- **N10**: billing, demand and collection.
- **N06**: the registry of issued licences, permits and certificates.
- **N02**: inventory.
- **N13**: GIS.

Each of the five is needed by at least 48 of the 198 rows and by government and private sectors alike. Every one of them can stay inside D-22 if it is built as a generic ledger, registry or calendar that a system of record can feed or replace. None of them is a department module.

---

## 1. Method and sources

**Method.**
1. Departments were enumerated from the official directory structure, using the IGOD categories and the Union "Ministries / Departments" lists, the Allocation of Business Rules, and the NeGP / e-Kranti mission-mode project lists.
2. State department types were normalised across states, because names and groupings differ. For example, "Revenue" sometimes includes Stamps & Registration and Disaster Management. One row is one *type* of department.
3. For each row, the typical systems are the national or state platforms the department is known to run, and the core workflows are its high-volume or high-value processes.
4. Each workflow was mapped onto C01–C16. Any recurring need that no capability carries became a candidate N-capability.
5. A candidate was kept only if it is needed in at least three unrelated rows and can be specified without naming a department (the ADR-D-20 test).
6. Ranking: weighted score = (● rows) + 0.5 × (○ rows), with ties broken by the number of ● rows. Section 7 also shows the counts by catalogue and the number of private sectors.

**Sources.**

| Key | Source | What it gives | Checked this session |
|---|---|---|---|
| S1 | National Portal directory of government websites, `igod.gov.in` | Union and State/UT organisation directory | Category structure fetched 2026-11-05. **Union:** Ministries, Independent Departments, Departments, Attached/Subordinate Offices, Commissions, Statutory/Autonomous Bodies, PSUs. **State:** Departments, Directorates, Boards/Undertakings, Districts, Schemes/Applications. Per-state department counts were **not** shown on the pages fetched [U] |
| S2 | Cabinet Secretariat, Government of India (Allocation of Business) Rules 1961, First Schedule (`cabsec.gov.in`) | Authoritative list of ministries and departments | Not fetched. The total is reported as about 54–58 ministries and about 93 departments, and the sources disagree [U]. The rows in 3.1 merge similar ministries, so the total does not change the analysis |
| S3 | MeitY NeGP / e-Kranti mission-mode projects | The national "systems" list | Counts confirmed from a Lok Sabha answer and other secondary pages. **NeGP 2006:** 31 MMPs (11 central, 13 state, 7 integrated). **e-Kranti (2015):** 44 MMPs, split as 13/17/14 or as 15/17/12 depending on the source [U]. The project-by-project table was not retrievable (MeitY PDF returned 404) [U] |
| S4 | `india.gov.in` services directory | Citizen services by department | Not fetched; general knowledge [U] |
| S5 | The system's own portal (e.g. Vahan/Sarathi on parivahan.gov.in, e-Courts, NREGASoft, HMIS, PFMS, CCTNS, UDISE+, PARIVESH, Passport Seva, e-Prisons, NGDRS, Poshan Tracker) | System names and their main flows | Not fetched this session. System names are well-known national platforms; items whose name or scope is uncertain carry [U] |
| S6 | Sangam's own research: `spike/GOV-PATTERNS.md` (GP-01…55), `spike/results/IFMS-*-REQUIREMENTS.md`, `GP-coverage-validation.md` | Government process patterns and IFMS functions | Read |
| S7 | Sector regulators and Sangam's `spike/results/SECTOR-WORKFLOWS.md`: RBI, SEBI, IRDAI, TRAI/DoT, NABH, CDSCO / Drugs and Cosmetics Rules, RERA | Regulated private workflows | Read (Sangam document); regulator texts not re-fetched |
| S8 | Industry practice: ITIL, APICS/MRP, ISO 55000 asset management, NABH hospital standards, WMS/TMS/FSM/CMMS product categories | Private operational systems | General knowledge [I] |

**Honest limits.**
- Only S1's structure and S3's counts were checked live in this session.
- Every system name in sections 3–4 should be read as "typical, to verify per tenant", because states differ widely. Some states have built their own platforms in place of the national ones, for example their own land-records or treasury systems.
- This does not affect the capability findings. A capability is counted only when the *workflow* needs it, whatever the system is called.

---

## 2. Baseline: the generic capabilities Sangam has or has planned

| Code | Capability | Where | State (2026-11-05) |
|---|---|---|---|
| C01 | Workflow engine (BPMN 2.0, DMN 1.6/FEEL, CMMN 1.1) and the Flowable ports: ad-hoc, dynamic injection, event registry, migration | `sangam-engine`, `sangam-flowable-ports` | Built (code complete, testing last) |
| C02 | Approval core: seats/posts, hierarchy, delegation of powers through DMN, maker-checker/SoD, payload-hash binding, committee, referral, appeal, cancellation, reconciliation | `sangam-security`, `sangam-packs` core (IX.3, IX.5) | Built |
| C03 | Forms: versioned contract with server validation (R47) | `sangam-flowable-ports` forms, `sangam-ui` | Built / UI in progress |
| C04 | Configuration profile hierarchy (jurisdiction → region → organisation → office), effective-dated | `sangam-config` (ADR-D-20) | In progress |
| C05 | Master-data and classification registry: code lists, heads of account, hierarchies, versioned | `sangam-config` | In progress |
| C06 | Limit / fund ledger: budget, allotment, encumbrance, sanction limits | `sangam-ledger` | In progress |
| C07 | Document-type catalogue | `sangam-config` | In progress |
| C08 | Locale: currency, fiscal calendar, number format, time zone, language | `sangam-config` | In progress |
| C09 | Connectors: REST, SOAP, SFTP/file, e-mail, SMS/DLT, eSign, DigiLocker, payment gateway, PFMS/IFMS; simulators | `sangam-integration` | Built |
| C10 | Event registry, inbox/outbox, Kafka/JMS/webhook channels, unique start | `sangam-integration`, `sangam-flowable-ports` | Built |
| C11 | Documents / content (R74): store, versions, generation | core `DocumentStore`, ports `content/*` | Partly built (generation in D) |
| C12 | SLA model and business calendar (R31, R79) | engine, ports | Built (subset) |
| C13 | Audit timeline and audit chain (R42) | core, security | Built |
| C14 | Analytics and process mining (R65, R66) | read models | Planned (D/E) |
| C15 | Notifications (DLT registry, e-mail) | `sangam-integration` | Built |
| C16 | Signing (DSC/eSign bound to the approval hash) | `sangam-integration` | Built |

The **packs** that use these capabilities are listed in `sangam/sangam-packs/README.md` §5:
- the core;
- P1–P4;
- 25 government domain packs;
- UP-01…UP-22;
- 54 `sec.*` sector packs.

There are 105 pack directories in total.

**Assumption used in the matrix.** C01, C02, C03, C09, C12 and C13 are used by every row, so the matrix does not repeat them. Section 5 shows where the others are used.

---

## 3. Catalogue: government

### 3.1 Central ministries and departments

The rows group ministries by the systems they run. Where a ministry has several departments, the main ones are named. The scientific departments (Space, Atomic Energy) run their mission systems outside any workflow platform, and only their administration (GX rows) applies.

| ID | Ministry / department (type) | Typical systems | Core workflows | Sangam packs today | Source |
|---|---|---|---|---|---|
| GC01 | Agriculture & Farmers Welfare (DA&FW; DARE/ICAR) | PM-KISAN; PMFBY crop-insurance portal; e-NAM; Soil Health Card; AgriStack farmer registry; DBT-Agriculture; farm-machinery custom-hiring apps | Farmer registration and e-KYC; benefit eligibility and DBT; crop-insurance claim; soil sample to card; mandi trade settlement; machinery hire booking | p3, dbt-batch, inspection | S1 S2 S3 S5 |
| GC02 | Chemicals & Fertilizers (incl. Pharmaceuticals) | Integrated Fertiliser Management System (fertiliser DBT at PoS); PMBJP Jan Aushadhi supply chain; NPPA Pharma Sahi Daam | Fertiliser movement and retail sale on PoS; subsidy claim; Jan Aushadhi store indent and stock; drug-price complaint | p1, grievance | S1 S2 S5 |
| GC03 | Civil Aviation (DGCA, AAI, BCAS) | eGCA; DigiYatra; airport operations; UDAN | Pilot/AME licences; aircraft registration; airport entry permits; hangar/slot allocation | p4 | S1 S5 |
| GC04 | Coal; Mines (IBM) | Coal/mineral block auction; mining tenement and monthly returns [U] | Allocation/auction; mine plan approval; production returns; inspection | tender, p4, inspection | S1 S2 S5 |
| GC05 | Commerce & Industry (Commerce; DPIIT) | DGFT e-licensing (IEC, authorisations); National Single Window System; Startup India; IP India e-filing; GeM | IEC and licence issue/amend; investor approvals; patent/trademark examination and hearing; recognition | p4, gem-purchase | S1 S2 S5 |
| GC06 | Communications (Telecommunications; Posts) | Saral Sanchar; Tarang Sanchar; Sanchar Saathi/CEIR; India Post CBS/CSI and article tracking; PLI | Telecom licence and fees; lost-device blocking; article booking to delivery; post-office savings servicing | p4, sec.tel.* | S1 S2 S5 |
| GC07 | Consumer Affairs, Food & Public Distribution | IM-PDS / One Nation One Ration Card; Annavitran; FCI Depot Online System; National Consumer Helpline; e-Daakhil; BIS licensing; Legal Metrology | Grain allocation and movement; FPS distribution by ePoS; depot stock; consumer complaint and commission hearing; BIS licence and market surveillance | grievance, p4, stores | S1 S2 S3 S5 |
| GC08 | Cooperation | National Cooperative Database; PACS computerisation; CRCS portal (multi-state societies) | Society registration and amendment; audit; elections; PACS credit and stock | p4, audit-para | S1 S5 |
| GC09 | Corporate Affairs | MCA21 (V3); IEPF | Incorporation; statutory filings; charge register; strike-off; adjudication; IEPF refund claims | p4, rti | S1 S3 S5 |
| GC10 | Culture (ASI, museums, akademis) | Monument e-ticketing; museum collection management [U]; cultural grants | Monument ticket and time slot; artefact accession, loan and conservation; grants | p3 | S1 S5 |
| GC11 | Defence (Defence; Defence Production; Ex-Servicemen Welfare; DRDO; Military Affairs) | SPARSH pension; ECHS; CSD canteens; DGDE defence land records; e-Chhawani (cantonments); defence e-procurement | Pension sanction and disbursement; ECHS referral and bill; canteen supply; defence land records; cantonment civic services | pension, gem-purchase, tender | S1 S2 S5 |
| GC12 | Development of North Eastern Region | Scheme/project monitoring portals [U] | Project sanction; fund release; utilisation certificate; progress | p3, works | S1 S2 |
| GC13 | Earth Sciences (IMD, INCOIS, NCMRWF) | IMD warning dissemination; observation networks; forecast production | Warning issue; observation data pipelines; instrument maintenance | - | S1 S5 |
| GC14 | Education (School Education & Literacy; Higher Education) | UDISE+; PRABANDH (Samagra Shiksha); PM POSHAN; National Scholarship Portal; AISHE; APAAR/ABC; SWAYAM | School data returns; annual plan approval and release; scholarship; accreditation | sec.edu.*, p3 | S1 S2 S3 S5 |
| GC15 | Electronics & Information Technology | DigiLocker; UMANG; e-Office; MeghRaj; API Setu; e-District framework | Issued-document push; service onboarding; cloud empanelment | p4 (DigiLocker push), p2 | S1 S3 S5 |
| GC16 | Environment, Forest & Climate Change (CPCB) | PARIVESH (EC/FC/wildlife/CRZ); CPCB online continuous emission monitoring | Clearance application; expert appraisal committee; compliance monitoring; sensor exceedance alerts | p4, inspection, up21 | S1 S2 S5 |
| GC17 | External Affairs | Passport Seva (PSK appointments); MADAD; e-Migrate | Passport application, appointment, police verification, print and dispatch; consular grievance | p4, grievance | S1 S3 S5 |
| GC18 | Finance: Expenditure, CGA | PFMS; e-Bill; Treasury Single Account; GPF | Sanction; bill; payment; budget control; reconciliation | p1, budget-reappropriation, challan-reconciliation | S1 S5 S6 |
| GC19 | Finance: Revenue (CBDT, CBIC) | Income Tax e-filing and CPC; ICEGATE; CBIC GST back office; faceless assessment | Return processing; assessment; refund; appeal; customs clearance; demand and recovery; seized goods | sec.ecom.gst-tcs, court-case | S1 S3 S5 |
| GC20 | Finance: Economic Affairs, DIPAM, Financial Services | PMJDY/Jan Suraksha; Jan Samarth credit-linked schemes; externally aided projects | Credit-linked subsidy application; project loan disbursement | p3, sec.bank.* | S1 S2 S5 |
| GC21 | Fisheries, Animal Husbandry & Dairying | Bharat Pashudhan (NDLM); PMMSY portal; vaccination programme | Animal identification registry; vaccination campaign; artificial insemination services; subsidy | p3, inspection | S1 S5 |
| GC22 | Food Processing Industries; Heavy Industries; Steel; Textiles | PMFME; PLI and EV-subsidy claim portals; steel import monitoring; handloom/handicraft artisan IDs; cotton MSP procurement | Subsidy and PLI claim appraisal; import registration; artisan registration; MSP procurement | p3, sec.scm.* | S1 S2 S5 |
| GC23 | Health & Family Welfare (incl. NHA) | HMIS; RCH portal; ABDM; U-WIN; Ni-kshay; e-Hospital; eSanjeevani; CGHS; IHIP; drug and vaccine supply-chain systems; e-RaktKosh; PM-JAY | Facility reporting; beneficiary tracking (pregnancy, TB patient); vaccination session; OPD appointment; drug supply chain; CGHS card and referral; claims | sec.health.*, sec.ins.health-claim, up17 | S1 S3 S5 |
| GC24 | Home Affairs (incl. NDMA, CAPFs, NCRB) | CCTNS / ICJS; NCRB; e-Prisons; IVFRT / e-FRRO; FCRA; disaster management systems | FIR to investigation to charge-sheet; visa services; FCRA registration and returns; disaster response | court-case, p4, up10 | S1 S2 S3 S5 |
| GC25 | Housing & Urban Affairs (CPWD, Directorate of Estates) | PMAY-U; e-Sampada (residential allotment, holiday homes, venue booking) [U]; CPWD works; Smart Cities command centres | Quarter allotment by seniority and waitlist; holiday-home and venue booking; works; command-centre alert handling | works, tender, up21 | S1 S2 S5 |
| GC26 | Jal Shakti (Water Resources; Drinking Water & Sanitation) | JJM IMIS; India-WRIS; SBM-G; dam-safety systems [U] | Tap connection tracking; water quality testing; dam inspection; flood forecasting | works, inspection | S1 S2 S5 |
| GC27 | Labour & Employment (EPFO, ESIC) | Shram Suvidha; e-Shram; EPFO unified portal; ESIC portal and hospitals; National Career Service | Registration; returns; claim settlement; randomised inspection; ESIC hospital services | sec.hr.payroll, inspection | S1 S2 S5 |
| GC28 | Law & Justice (Legal Affairs; Legislative; Justice) | e-Courts / CIS; NJDG; LIMBS; Tele-Law; notary portal | Case filing, hearing, order; government litigation tracking; notary licensing | court-case, sec.legal.litigation | S1 S3 S5 |
| GC29 | Micro, Small & Medium Enterprises | Udyam registration; MSME Samadhaan | Registration; delayed-payment reference; facilitation | p4 | S1 S5 |
| GC30 | Minority Affairs | Scholarship schemes on NSP; waqf asset management [U] | Scholarship; waqf property registry | sec.edu.scholarship | S1 S5 |
| GC31 | New & Renewable Energy | PM-KUSUM; PM Surya Ghar rooftop portal | Application, vendor installation, inspection, subsidy release | p3, inspection | S1 S5 |
| GC32 | Panchayati Raj | eGramSwaraj; SVAMITVA; Meri Panchayat; Gram Manchitra | GP development plan; GP accounts; asset geo-tagging; property cards | p3, works | S1 S3 S5 |
| GC33 | Parliamentary Affairs | NeVA; assurance monitoring [U] | Question to reply; assurance tracking; bill/committee papers | p2 | S1 S5 |
| GC34 | Personnel, Public Grievances & Pensions | CPGRAMS; RTI Online; SPARROW (APAR); e-HRMS; Bhavishya; Jeevan Pramaan; iGOT Karmayogi | Grievance; RTI; APAR; pension case; life certificate; training nomination | grievance, rti, pension, sec.hr.appraisal | S1 S2 S5 |
| GC35 | Petroleum & Natural Gas | PMUY / PAHAL (DBTL); LPG distributor systems | Connection issue; subsidy DBT; cylinder delivery | dbt-batch | S1 S5 |
| GC36 | Ports, Shipping & Waterways | Port community system (PCS1x); DG Shipping e-services [U]; seafarer certification | Berth booking; seafarer certificates; port dues | p4 | S1 S5 |
| GC37 | Power (CEA) | RDSS smart metering; national power portal | Scheme and project progress; sanction | works | S1 S5 |
| GC38 | Railways | PRS / UTS ticketing; FOIS freight; IREPS e-procurement; HRMS; Rail Madad; crew management system; rolling-stock maintenance systems [U] | Reservation; freight indent to rake allotment; crew rostering; rolling-stock maintenance; complaints | tender, grievance | S1 S2 S5 |
| GC39 | Road Transport & Highways (NHAI) | Vahan; Sarathi; NETC FASTag; e-Challan; highway project monitoring | Vehicle registration; licence test slot; permits; challan and payment; highway project milestones | p4, works | S1 S3 S5 |
| GC40 | Rural Development (DoRD; Land Resources) | NREGASoft; AwaasSoft (PMAY-G); DAY-NRLM; PMGSY OMMAS; DILRMP; NSAP-PPS | Job card and demand to muster to wage payment; house sanction with geo-tagged stages; rural-road maintenance; land records | dbt-batch, works, p3 | S1 S2 S3 S5 |
| GC41 | Science & Technology (DST, DBT, DSIR) | Grant e-PMS portals | Proposal, peer review committee, sanction, utilisation certificate | p3, gov.core committee | S1 S2 |
| GC42 | Skill Development & Entrepreneurship | Skill India Digital Hub; apprenticeship portal | Training-centre accreditation; assessment; certification | p4 | S1 S5 |
| GC43 | Social Justice & Empowerment (incl. Persons with Disabilities) | UDID; NSP schemes; SMILE | Disability certificate with medical-board slot; scholarships | p4, sec.edu.scholarship | S1 S5 |
| GC44 | Statistics & Programme Implementation | MPLADS e-Sakshi; NSS field data collection | MPLADS work recommendation to sanction; surveys with field enumeration | p3, works | S1 S5 |
| GC45 | Tourism | NIDHI accommodation registry; hotel classification; guide certification | Classification inspection; registration | p4, inspection | S1 S5 |
| GC46 | Tribal Affairs | Forest Rights Act claim systems [U]; scholarships; EMRS | Forest-rights claim with map; scholarship | p3, sec.edu.scholarship | S1 S5 |
| GC47 | Women & Child Development | Poshan Tracker; Mission Shakti (One Stop Centre, 181); CARINGS (adoption) | Anganwadi beneficiary and growth tracking; take-home-ration stock; OSC case; adoption matching and waitlist | grievance, p3 | S1 S3 S5 |
| GC48 | Youth Affairs & Sports (SAI) | MY Bharat; Khelo India; SAI facilities | Athlete registry; facility booking; scholarship | p3 | S1 S5 |
| GC49 | Information & Broadcasting | Broadcast Seva; PRGI press registration; CBFC e-Cinepramaan; CBC advertisement empanelment and billing | Licences and registrations; certification with screening slot; advertisement release and bill | p4, p1 | S1 S5 |
| GC50 | AYUSH | AYUSH Grid; NAMASTE portal | Facility reporting; licences | p4 | S1 S5 |
| GC51 | Cabinet Secretariat, PMO, President's Secretariat, Vice-President's Secretariat | e-Office; review-meeting systems; visitor booking [U] | VIP references; review meetings and action-taken reports; visitor booking | p2 | S1 S2 |
| GC52 | Space; Atomic Energy (scientific departments) | Mission and plant systems stay outside; generic administration only | Administration only (cross-cutting systems GX) | p1, p2, tender | S1 S2 |

### 3.2 State department types

The table lists 55 types, the normal set across Indian states and UTs. Some states split a type into several departments; for example, Medical Education and Health are separate in most large states. Other states merge types, such as Revenue with Disaster Management. Boards, corporations and authorities under a department are counted with it.

| ID | Ministry / department (type) | Typical systems | Core workflows | Sangam packs today | Source |
|---|---|---|---|---|---|
| GS01 | Finance, Treasury & Accounts (incl. Pension, GPF, Lotteries) | State IFMS (budget, bills, treasury); pension directorate; GPF; e-GRAS / receipt portals | Budget; bills; treasury payment; receipts; pension; GPF | p1, budget-reappropriation, challan-reconciliation, pension, salary-monthly | S3 S5 S6 |
| GS02 | Planning, Economics & Statistics | Scheme monitoring; constituency development funds; DES surveys | Scheme targets and monthly progress; MLA-LAD works; surveys | p3, works | S1 S5 |
| GS03 | General Administration, Personnel, Protocol & Estate | HRMS; APAR; online transfer counselling; state guest houses, circuit houses, Bhawans; government quarters; state vehicles and aircraft | Service matters; transfers; guest-house and circuit-house booking with entitlement and tariff; quarter allotment by seniority; vehicle requisition | p2, leave, assets | S1 S5 |
| GS04 | Home / Police | CCTNS; ERSS Dial-112; e-Challan; arms licences; citizen verification services; malkhana (property room) | FIR to charge-sheet; emergency dispatch; duty and bandobast; arms licence; verification; case property custody | court-case, p4 | S1 S3 S5 |
| GS05 | Prisons | e-Prisons (prisoner management, e-Mulakat visitor booking, parole) | Admission; visitor slot booking; parole and remission; prison stores | p4 | S1 S5 |
| GS06 | Fire & Emergency Services | Fire NOC portals; control room | Fire NOC with inspection; call to dispatch; equipment upkeep | p4, inspection | S5 |
| GS07 | Revenue & Land Records | Bhulekh / RoR; mutation; e-District certificates; revenue court case systems; relief | Mutation; certificates; demarcation; revenue court hearing; land acquisition | p4, land-acquisition, court-case | S1 S3 S5 |
| GS08 | Stamps & Registration | NGDRS / state registration systems; e-stamping; guideline values | Deed registration with appointment slot; valuation; duty collection; encumbrance and certified copy | p4 | S1 S5 |
| GS09 | Transport (department and state transport undertaking) | Vahan and Sarathi state instances; e-Challan; permits; STU ticketing, depots, workshops | Registration; licence test slot; permits; challan; bus fleet maintenance; crew rostering | p4 | S1 S3 S5 |
| GS10 | Health & Family Welfare (Directorate and NHM) - IHMS/HMIS | HMIS; hospital management (IHMS / e-Hospital) in district and sub-district hospitals; drug and vaccine store systems; 108/102 ambulances; e-RaktKosh; biomedical equipment maintenance; state health insurance; ASHA incentives | OPD/IPD; drug indent and stock; ambulance dispatch; equipment maintenance; facility reporting; scheme claims; incentives | sec.health.*, sec.ins.health-claim | S1 S3 S5 |
| GS11 | Medical Education | Medical-college hospital systems; counselling (seat allotment); resident duty rosters | Admission counselling; teaching-hospital operations; duty rosters | sec.edu.admission, sec.health.* | S5 |
| GS12 | AYUSH | Dispensaries; AYUSH drug licences; AYUSH hospitals | Dispensary stock; licences; returns | p4 | S5 |
| GS13 | Drug Control & Food Safety (FDA) | Drug licensing systems [U]; FoSCoS food licences; sampling and laboratories | Licence; inspection; sample collection to lab result to prosecution | p4, inspection | S5 |
| GS14 | School Education | UDISE+; transfer counselling; PM POSHAN; RTE admission lottery; free textbook/uniform/bicycle distribution; school boards | School returns; teacher transfers; RTE seat allotment; material distribution; board exams | sec.edu.* | S1 S3 S5 |
| GS15 | Higher & Technical Education | Admission counselling; scholarships; affiliation; university examinations | Seat allotment; affiliation inspection; scholarships; exams | sec.edu.* | S5 |
| GS16 | Women & Child Development | ICDS / Poshan Tracker; take-home-ration supply; child protection (CWC/JJB); One Stop Centres / 181; state schemes | Beneficiary tracking; ration stock; child-protection case; women-helpline case; scheme DBT | grievance, p3, dbt-batch | S1 S5 |
| GS17 | Social Welfare / Social Justice (pensions, disability, hostels) | Social-security pension systems; hostels; UDID camps | Pension sanction and life verification; hostel admission and mess stock; disability camps | pension, p3, dbt-batch | S1 S5 |
| GS18 | SC/ST/OBC/Minority Welfare and Tribal Development | Post-matric scholarships; forest rights; ashram schools and hostels | Scholarship; forest-rights claim; hostel allotment | sec.edu.scholarship, p3 | S1 S5 |
| GS19 | Food, Civil Supplies & Consumer Affairs | PDS (ration cards, FPS ePoS, warehouse supply chain); MSP procurement; Legal Metrology; consumer commissions | Ration card; monthly allocation to FPS; doorstep delivery; procurement at centres; weights-and-measures verification; consumer case | p4, stores, grievance | S1 S3 S5 |
| GS20 | Agriculture (incl. Horticulture, Sericulture) | Farmer registry; input-subsidy DBT; seed/fertiliser/pesticide licences and sampling; soil labs; custom hiring centres; crop-cutting experiments | Subsidy; licence; quality sample; machinery hire booking; crop-cutting with geo-tag | p3, dbt-batch, p4, inspection | S1 S3 S5 |
| GS21 | Animal Husbandry & Veterinary | Veterinary hospitals; vaccination drives; AI services; animal registry | Vaccination campaign; AI and treatment; vet drug stock | inspection | S5 |
| GS22 | Fisheries | Fishing licences; boat registration; tank/pond lease | Licence; registration; lease auction | p4, tender | S5 |
| GS23 | Cooperation and Dairy | Registrar of cooperative societies; cooperative audit; elections; PACS; milk collection | Registration; audit; elections; milk procurement and payment | p4, audit-para | S1 S5 |
| GS24 | Rural Development | MGNREGA; PMAY-G; NRLM / SHG; DRDA | Work demand to wage; house stages with geo-tag; SHG bank linkage | dbt-batch, works, p3 | S1 S3 S5 |
| GS25 | Panchayati Raj | eGramSwaraj; GP property tax; civil registration (birth/death); GP planning | GP plan; GP accounts; property tax; births and deaths | p3, p4 | S1 S3 S5 |
| GS26 | Urban Development / Urban Local Bodies | Property tax; trade licences; online building permission; water and sewer connections; solid-waste vehicle tracking; civil registration; PMAY-U; command centres | Assessment and demand; licence; building permission with inspection; connection; waste collection; births and deaths | p4, inspection, sec.util.* | S1 S3 S5 |
| GS27 | Housing, Development Authorities, Housing Boards | Plot/flat allotment; instalment demand; layout approval | Lottery allotment; demand and instalments; possession; layout approval | p4 | S5 |
| GS28 | Town & Country Planning | Master plans; change of land use; layout approvals | CLU; layout/building approval | p4 | S5 |
| GS29 | PWD / Roads & Buildings (incl. circuit houses, inspection bungalows) | Works (WAMS); e-tender; road asset inventory; inspection bungalows; schedule of rates | Estimate to final bill; road condition survey; bungalow booking; maintenance | works, tender, work-order, contract-management | S1 S5 |
| GS30 | Water Resources / Irrigation | Canal irrigation demand and water tax; dam safety; flood telemetry; works | Water demand and assessment; dam inspection; flood alert; works | works | S5 |
| GS31 | Public Health Engineering / Drinking Water | JJM; water quality laboratories; scheme O&M | Connection; water sample to lab; scheme maintenance | works, inspection | S1 S5 |
| GS32 | Energy department, discoms, transco, genco, electrical inspectorate | New connection; billing; outage management; asset maintenance; electrical safety licences | Connection; meter to cash; outage to crew dispatch; asset maintenance; inspection | sec.util.* | S5 |
| GS33 | Industries, Commerce & Investment Promotion | State single-window system; industrial land allotment; incentive claims; boiler and factory inspection | Investor approvals; plot allotment; incentive disbursement; inspections | p4, p3, inspection | S5 |
| GS34 | Labour & Employment | Shops and establishments; factory licences; randomised inspection; BOCW welfare board; employment exchange; labour courts | Registration; licence; inspection; worker registration and benefits; dispute | p4, inspection, p3, court-case | S3 S5 |
| GS35 | Mines & Geology | Mining leases; e-transit passes; royalty; weighbridges | Lease; transit pass per trip; royalty demand; illegal-mining case | p4 | S5 |
| GS36 | Forest & Environment (incl. State Pollution Control Board) | Forest clearances; transit passes; wildlife permits; eco-tourism and forest rest-house booking; wildlife-damage compensation; plantations; fire alerts; SPCB consents | Clearance; transit pass; rest-house booking; compensation claim; consent to establish/operate | p4, p3, inspection | S5 |
| GS37 | Excise | Licensing; track-and-trace (holograms); duty; enforcement | Licence; permit for movement; stock and sale tracking; duty; seizure case | p4 | S5 |
| GS38 | Commercial Taxes / State GST | State GST back office; legacy VAT; professional tax; assessments; enforcement; refunds | Registration; assessment; audit; demand and recovery; refund; enforcement seizure | p4, court-case | S3 S5 |
| GS39 | Tourism (department and corporation) | Tourism-corporation hotels and packages; guide licences; homestay registration; events | Room and package booking; homestay registration; guide licence | p4 | S5 |
| GS40 | Culture, Archaeology, Archives & Museums | Ticketing; artist pensions; archives | Ticketing; artefact custody; artist pension | p3, records | S5 |
| GS41 | Sports & Youth Welfare | Stadium and facility booking; coaching camps; athlete scholarships | Facility booking; camp enrolment; scholarship | p3 | S5 |
| GS42 | Information & Public Relations | Media accreditation; advertisement release and bills; empanelment | Accreditation; release order to bill; empanelment | p4, p1 | S5 |
| GS43 | Law & Legislative / Prosecution | Government litigation tracking; law officer fees; prosecution | Case tracking; counsel fee bills; prosecution sanction | court-case, p1 | S5 |
| GS44 | Judiciary (High Court and district courts) | e-Courts CIS; e-filing; cause lists; certified copies; process service (NSTEP) | Filing; listing and hearing; order; summons service; certified copy | court-case | S3 S5 |
| GS45 | Legislative Assembly Secretariat | NeVA (questions, bills, committees) | Question to reply; assurances; committee meetings | p2 | S5 |
| GS46 | Election (Chief Electoral Officer) | Electoral roll systems; polling-staff randomisation; EVM warehousing and first-level checking; vehicle requisition | Roll revision; staff deployment; EVM custody and movement; vehicle requisition | - | S5 |
| GS47 | IT / e-Governance (e-District, SWAN, SDC, CSC) | e-District; state portal; CSC network | Certificate services; CSC onboarding | p4 | S3 S5 |
| GS48 | Skill Development, Technical Education & Employment | ITI admissions; apprenticeships; job fairs | Admission; apprenticeship contract; job fair | sec.edu.admission | S5 |
| GS49 | Science & Technology / Remote Sensing Centres | Grants; remote-sensing applications | Grant; spatial data services | p3 | S5 |
| GS50 | Disaster Management / Relief | Early warning; relief camps; relief material; ex-gratia | Alert to response; camp allotment; relief stock; ex-gratia DBT | dbt-batch, up21 | S5 |
| GS51 | Vigilance, Lokayukta, State Information Commission | Complaint and case systems; second appeals | Complaint; investigation; sanction for prosecution; RTI second appeal hearing | rti, court-case | S5 |
| GS52 | Public Service Commission / Staff Selection Board | Recruitment; exam-centre allocation; staff deployment | Recruitment; centre allocation; confidential material custody | recruitment | S5 |
| GS53 | Endowments / Religious & Charitable Institutions | Temple administration: darshan and seva slots; donations; properties; pilgrim accommodation | Slot booking; donation receipt; property lease; accommodation | - | S5 |
| GS54 | Sainik Welfare; Rehabilitation & Resettlement | Ex-servicemen registry; grants; rest houses; R&R entitlements | Registration; grants; rest-house booking; entitlement | p3 | S5 |
| GS55 | Printing & Stationery; State warehousing and civil-supplies corporations | Indents and stock; warehouse receipts and storage charges | Indent to issue; deposit to receipt to storage bill | stores | S5 |

### 3.3 Cross-cutting systems that every department runs

Every central and state department runs these systems, as do its directorates and district offices. A capability that one of these rows needs is therefore needed by **every** department in 3.1 and 3.2. The owner's "circuit house / guest house" example is GX09.

| ID | Ministry / department (type) | Typical systems | Core workflows | Sangam packs today | Source |
|---|---|---|---|---|---|
| GX01 | e-Office file and dak | e-Office | Note/draft movement; dak receipt and dispatch registers; physical file tracking | p2 | S3 S5 |
| GX02 | Finance (IFMS, budget, bills, receipts, pension) | State IFMS / PFMS | Budget, bills, receipts, pension | p1, budget, challan, pension | S6 |
| GX03 | HRMS and service book | e-HRMS / state HRMS | Service record; transfers; APAR | leave, sec.hr.appraisal | S5 |
| GX04 | Payroll | IFMS / HRMS payroll | Monthly salary | salary-monthly, sec.hr.payroll | S6 |
| GX05 | Procurement (GeM, e-procurement) | GeM; CPPP / state e-procurement | Indent to purchase; tender | gem-purchase, tender, consultancy | S5 |
| GX06 | Works | WAMS / works systems | Estimate to final bill; measurement book | works, work-order, contract-management | S6 |
| GX07 | Stores and inventory | Department stores systems [U] | Indent, receipt, issue, stock verification, write-off | stores (verification only) | S6 |
| GX08 | Assets and vehicle pool | Asset registers; vehicle logs | Asset register to disposal; maintenance; vehicle requisition and log book | assets (lifecycle only) | S6 |
| GX09 | Circuit houses, guest houses, rest houses, inspection bungalows, state Bhawans | Booking registers / department portals [U] | Request with entitlement check; allotment by priority; check-in/out; tariff and bill; VIP override; housekeeping and linen | - | S5 |
| GX10 | Conference halls, auditoriums, training venues | Booking registers | Request; approval; allotment; cancellation | - | S5 |
| GX11 | Public grievance | CPGRAMS / state grievance portals | Grievance to disposal; appeal | grievance | S5 |
| GX12 | RTI | RTI Online / state portals | Request; first appeal | rti | S5 |
| GX13 | Government litigation | LIMBS / state legal case systems | Case tracking; hearing dates; compliance | court-case | S5 |
| GX14 | Audit paras | Audit management [U] | Inspection report; para reply; settlement | audit-para | S6 |
| GX15 | Assembly/Parliament questions, assurances, VIP references | NeVA; e-Office | Question allocation; reply; assurance tracking; VIP-reference tracking | p2 | S5 |
| GX16 | Meetings and committees | e-Office; meeting registers | Agenda; minutes; action-taken report; DPC, purchase and review committees | gov.core committee | S5 |
| GX17 | Scheme MIS and monthly progress reports | Scheme MIS portals; dashboards | Targets; monthly physical/financial progress; roll-up district to state | p3, works | S5 |
| GX18 | Inspections and tours | Tour programmes; inspection notes | Tour approval; inspection with photos and location; compliance | inspection | S6 |
| GX19 | Records and archives | Record rooms | Retention; review; transfer; weeding | records | S6 |
| GX20 | Training and capacity building | iGOT / state ATIs | Nomination; batch scheduling; venue | - | S5 |
| GX21 | Visitor and reception management | Visitor registers | Visitor pass; appointment with officer; public hearing day tokens | - | S5 |
| GX22 | Attendance and leave | AEBAS biometric attendance; leave | Leave; attendance; duty roster in field offices | leave | S5 |
| GX23 | Certificates and licences (e-District, RTPS) | e-District; ServicePlus [U] | Application to certificate; renewal; verification | p4, rtps | S3 S5 |
| GX24 | Control rooms (district disaster/law-and-order/health) | District control rooms | Incident to dispatch to closure | up10, up21 | S5 |

---

## 4. Catalogue: private sectors

The owner's "bank management" example is PB01–PB07, and "hospital management" is PH01–PH12. The data-warehouse example is PW01–PW04. The sectors are listed in the order the owner gave them, followed by the other service and manufacturing sectors.

| ID | Sector | System | Typical products | Core workflows | Sangam packs today | Source |
|---|---|---|---|---|---|---|
| PB01 | Banking | Onboarding / KYC / re-KYC | CBS + onboarding; CKYCR | Account opening; V-CIP; re-KYC | sec.bank.account-opening, rekyc | S7 |
| PB02 | Banking | Lending (LOS/LMS) | LOS; LMS | Origination to disbursal; closure | sec.bank.loan-* | S7 |
| PB03 | Banking | Collections and recovery | Collection systems | Delinquency buckets; field visits; settlement; SARFAESI | sec.bank.collections | S7 |
| PB04 | Banking | Branch operations | Queue systems; locker management; currency chest | Token and counters; locker allotment and waitlist; cash and chest stock; security stationery (cheque books, cards) | - | S7 |
| PB05 | Banking | Premises, ATMs and fixed assets | Facility/ATM monitoring | ATM uptime incidents; preventive maintenance; premises | - | S8 |
| PB06 | Banking | Regulatory returns and reporting | Returns to RBI (CIMS/XBRL) [U] | Periodic returns; data quality sign-off | - | S7 |
| PB07 | Banking | Payments, disputes, AML | Payment hubs; FRM; AML | Dual authorisation; disputes; STR | sec.bank.payment-dual-auth, txn-dispute, aml-str | S7 |
| PN01 | NBFC / MFI | Microfinance field operations | MFI LMS | Centre meetings; field collection with location; group formation | sec.nbfc.digital-lending | S7 |
| PN02 | NBFC / MFI | Gold loans | Gold-loan systems | Appraisal; packet custody in vault; auction | - | S7 |
| PI01 | Insurance | Claims | Claims systems | Survey; assessment; settlement | sec.ins.*-claim | S7 |
| PI02 | Insurance | Distribution and provider network | Agent and hospital network | Agent licensing and renewal; hospital empanelment | sec.ins.intermediary-licensing | S7 |
| PC01 | Capital markets | Broking, depository, RTA operations | Back office | Onboarding; surveillance; corporate actions; end-of-day batches | sec.cap.* | S7 |
| PH01 | Hospitals | OPD appointments and patient queue | HIS front office | Appointment slot; registration; token and calling; consultation | sec.health.admission-discharge | S8 |
| PH02 | Hospitals | IPD bed management (ADT) | HIS ADT | Bed request; allotment by ward/class; transfer; discharge | sec.health.admission-discharge | S8 |
| PH03 | Hospitals | Operation theatre | OT management | OT slot; team roster; checklist | sec.health.ot-scheduling | S7 |
| PH04 | Hospitals | Pharmacy and stores | Pharmacy / inventory | Indent; batch/expiry stock; dispensing; returns | sec.health.pharmacy-dispense | S8 |
| PH05 | Hospitals | Laboratory, radiology, blood centre | LIS / RIS / blood bank | Sample collection, barcode, chain of custody; modality slot; blood unit inventory | sec.health.lab-order, blood-bank | S7 |
| PH06 | Hospitals | Patient billing, packages, TPA | HIS billing | Charge capture; package tariff; deposit; TPA pre-auth and claim | sec.ins.health-claim, up17 | S8 |
| PH07 | Hospitals | Biomedical equipment and facilities | CMMS | Equipment register; preventive maintenance; calibration; breakdown | - | S8 |
| PH08 | Hospitals | Staff duty roster | Rostering | Shift roster; on-call; swaps; leave | - | S8 |
| PH09 | Hospitals | CSSD, linen, diet, housekeeping, biomedical waste | Support services | Instrument-set tracking; linen stock; diet orders; waste bags custody | - | S8 |
| PH10 | Hospitals | Ambulance and patient transport | Dispatch | Call to dispatch to handover | - | S8 |
| PH11 | Hospitals | Patient master and records (EHR boundary) | EHR / EMR (outside) | Master patient index; consent; record release | sec.health.abdm-consent | S7 |
| PH12 | Hospitals | Group/chain analytics and data integration | Data platform | Nightly feeds; KPI packs | up07 | S8 |
| PD01 | Diagnostics chains | Home sample collection | Phlebotomy apps | Slot booking; route; sample custody; report | sec.health.lab-order | S8 |
| PE01 | E-commerce / retail | Order to cash, returns, seller onboarding | OMS | Order; payment; fulfilment; return | sec.ecom.* | S7 |
| PE02 | E-commerce / retail | Catalogue and pricing | PIM; pricing | Listing approval; price change approval | sec.ecom.seller-onboarding | S8 |
| PE03 | E-commerce / retail | Inventory and warehouse | WMS (outside for slotting/picking) | Inbound; putaway; stock; transfers; cycle count | - | S8 |
| PE04 | E-commerce / retail | Last-mile delivery | TMS / delivery apps | Assignment; route; proof of delivery; failed delivery | - | S8 |
| PE05 | E-commerce / retail | Physical store operations | POS; store-ops apps | Store audits; planogram checks; staff rota; stock | - | S8 |
| PE06 | E-commerce / retail | Customer service | Helpdesk | Ticket; case; escalation | sec.fin.complaint, up10 | S8 |
| PW01 | Data warehouse / data platform | Pipeline orchestration | Schedulers (Airflow, Control-M) [U] | DAG run; dependency; backfill; SLA miss | - | S8 |
| PW02 | Data warehouse / data platform | Data quality incidents | DQ tools | DQ check fail; incident; root cause; fix; re-run | up18 | S8 |
| PW03 | Data warehouse / data platform | Access requests and governance | Catalogue; IAM | Access request; recertification; schema change approval | up05, up15, up11 | S8 |
| PW04 | Data warehouse / data platform | Statutory/regulatory data submissions | Reporting | Period close; generate; validate; sign-off; submit | - | S8 |
| PL01 | Logistics | Shipment and trip (TMS) | TMS | Booking; trip; tracking; POD; claims | sec.scm.eway-bill | S8 |
| PL02 | Logistics | Warehouse | WMS | Inbound; stock; outbound | - | S8 |
| PL03 | Logistics | Dock and yard appointments | Yard management | Dock slot booking; gate-in | - | S8 |
| PL04 | Logistics | Fleet maintenance and driver rostering | Fleet systems | PM schedule; breakdown; driver duty | - | S8 |
| PT01 | Telecom | Order and provisioning, MNP, SIM swap | OSS/BSS | Order decomposition; activation | sec.tel.*, up12, up16 | S7 |
| PT02 | Telecom | Trouble tickets and field force | Field service | Fault ticket; technician dispatch; site visit | up10 | S8 |
| PT03 | Telecom | Tower and site assets | NOC; asset systems | Alarm; preventive maintenance; fuel/spares | up21 | S8 |
| PT04 | Telecom | Billing and revenue assurance | Billing (outside for rating) | Bill run; dispute; revenue leakage case | - | S8 |
| PT05 | Telecom | Number and resource inventory | Number management | Number pool; reservation; quarantine | - | S7 |
| PS01 | Education (private) | Admissions, exams, scholarships | SIS | Admission; exam; revaluation | sec.edu.* | S8 |
| PS02 | Education (private) | Timetable, rooms and labs | Timetabling | Room and lab booking; faculty load | - | S8 |
| PS03 | Education (private) | Hostel, fees, transport | SIS / ERP | Hostel allotment; fee demand; bus routes | - | S8 |
| PS04 | Education (private) | Library | Library system | Issue/return; reservations; fines | - | S8 |
| PM01 | Manufacturing | Plant maintenance | CMMS / EAM | Asset hierarchy; PM plans; breakdown; spares | - | S8 |
| PM02 | Manufacturing | Quality, CAPA, batch release | QMS | Inspection; NC; CAPA; batch release | up18, sec.mfg.batch-release | S8 |
| PM03 | Manufacturing | MRP and production orders | ERP (planning solver outside) | MRP run; planned order; production order; material issue | sec.mfg.mrp-planning | S8 |
| PM04 | Manufacturing | Shift and workforce | Time and attendance | Shift roster; overtime approval | - | S8 |
| PM05 | Manufacturing | EHS permit-to-work | EHS | Permit request; isolation; validity; close-out | - | S8 |
| PM06 | Manufacturing | Calibration and traceability | LIMS / MES | Instrument calibration; lot genealogy | - | S8 |
| PV01 | Services (IT services) | ITSM | ITSM | Incident; problem; change; CMDB | up10, up11 | S8 |
| PV02 | Services (IT/professional) | Resource management, timesheets, billing | PSA | Staffing; timesheet approval; invoice; engagement conflict check | - | S8 |
| PV03 | Services (field service) | Field service management | FSM | Work order; scheduling; dispatch; parts; customer sign-off | work-order | S8 |
| PR01 | Real estate | Unit sales and allotment | CRM | Unit hold; booking; allotment; demand schedule; possession | - | S7 |
| PR02 | Real estate | Construction and RERA compliance | Project systems | Milestones; quarterly RERA updates | works | S7 |
| PR03 | Real estate | Facility management | CAFM | Helpdesk; PM; amenity booking | - | S8 |
| PO01 | Hospitality | Reservations and room inventory | PMS (outside for channel management) | Reservation; allocation; check-in/out; folio | - | S8 |
| PO02 | Hospitality | Housekeeping, maintenance, F&B, events | PMS / POS | Room status; staff rota; F&B stock; banquet booking | - | S8 |
| PA01 | Media | Content production and rights | MAM / rights | Commissioning; review; release; rights windows | up19 | S8 |
| PA02 | Media | Ad inventory and scheduling | Ad sales / traffic | Booking of slots; schedule; as-run log; billing | - | S8 |
| PU01 | Utilities (private) | Outage and crew dispatch; network assets | OMS; EAM | Outage; crew dispatch; asset maintenance | sec.util.* | S8 |
| PG01 | Agri / FPO / warehousing | Procurement and warehouse receipts | Agri platforms | Procurement; quality; warehouse receipt | - | S8 |
| PX01 | Automotive dealers and service networks | Service booking and job cards | DMS | Service slot; job card; parts | - | S8 |

---

## 5. How the existing capabilities already carry these systems

| Existing capability | What it already carries across the catalogue | Rows that rely on it most |
|---|---|---|
| C02 approval core + P1–P4 | Every application → scrutiny → recommendation → sanction → execution flow: licence/permit applications, scheme sanctions, bills, file movement, procurement, recruitment | All rows (it is the reason the approval half is covered) |
| C06 limit/fund ledger | Budget control, encumbrance, sanction limits, scheme fund release, DLG caps | GC18, GS01, GX02, GC14, GC40, PB07 |
| C05 master data | Heads of account, code lists, schedules of rates, item and service catalogues (as code lists only) | GS29, GX05–GX07, PE02 |
| C10 events + UP-03/UP-21 | Callbacks, payment acknowledgements and alarms *as messages* | GC13, GC16, GS30, GS32, PT03 |
| C11 documents + C16 signing | Certificates, orders and sanction letters with eSign and DigiLocker push | GX23, GS07, GC15 |
| CMMN (C01) + court-case, grievance, audit-para packs | Case-shaped work *as a process*, with no shared case-file model | GS04, GS44, GX11, GX13 |
| `gov.stores`, `gov.assets`, `gov.inspection`, `sec.util.meter-to-cash` | Prove the *workflows* around stock verification, asset condemnation, inspections and billing cycles. There is still no stock ledger, asset register, offline field capture or demand ledger underneath them | GX07, GX08, GX18, GS32 |

**Conclusion of section 5.** No row is fully blocked on the approval side. Every row that has a ● in section 6 is blocked on the operational object that the approval acts on.

---

## 6. Coverage matrix (system × missing capability)

Columns N01–N18 are named in section 8. Empty means the row does not need that capability; the existing capabilities suffice. Row names are shortened, and section 3 or 4 gives the full name.

**N-codes:**
- N01 booking/allotment;
- N02 inventory;
- N03 asset and maintenance;
- N04 case file;
- N05 field capture;
- N06 entitlement registry;
- N07 queue/token;
- N08 roster;
- N09 registers/numbering;
- N10 billing/demand/collection;
- N11 data pipelines;
- N12 IoT triggers;
- N13 GIS;
- N14 party registry;
- N15 targets/returns;
- N16 meetings/questions/references;
- N17 dispatch;
- N18 custody tracking.

### 6.1 Central

| ID | System | N01 | N02 | N03 | N04 | N05 | N06 | N07 | N08 | N09 | N10 | N11 | N12 | N13 | N14 | N15 | N16 | N17 | N18 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| GC01 | Agriculture & Farmers Welfare | ○ |  |  |  | ○ |  |  |  |  | ○ |  |  | ● | ● |  |  |  | ○ |
| GC02 | Chemicals & Fertilizers |  | ● |  | ○ |  | ○ |  |  |  |  |  |  |  | ○ |  |  |  |  |
| GC03 | Civil Aviation | ○ |  | ○ |  |  | ● |  | ○ | ● |  |  |  |  |  |  |  |  |  |
| GC04 | Coal; Mines |  |  |  |  | ○ | ● |  |  |  |  |  |  | ● |  | ● |  |  |  |
| GC05 | Commerce & Industry | ○ |  |  | ○ |  | ● |  |  | ● | ○ |  |  |  |  |  |  |  |  |
| GC06 | Communications |  | ○ |  |  |  | ● | ○ |  |  | ● |  |  |  |  |  |  | ○ | ● |
| GC07 | Consumer Affairs, Food & Public Distribution |  | ● |  | ○ | ○ | ● |  |  |  |  |  |  | ○ | ● |  |  |  | ○ |
| GC08 | Cooperation |  | ○ |  |  |  | ● |  |  | ● | ○ |  |  |  |  |  | ○ |  |  |
| GC09 | Corporate Affairs |  |  |  | ○ |  | ● |  |  | ● | ○ |  |  |  |  |  |  |  |  |
| GC10 | Culture | ● |  | ● |  |  |  | ○ |  |  | ○ |  |  |  |  |  |  |  | ● |
| GC11 | Defence |  | ● | ● |  |  |  |  | ○ | ○ | ● |  |  | ○ | ○ |  |  |  |  |
| GC12 | Development of North Eastern Region |  |  |  |  |  |  |  |  |  |  |  |  | ○ |  | ● |  |  |  |
| GC13 | Earth Sciences |  |  | ○ |  |  |  |  |  |  |  | ● | ● | ○ |  |  |  |  |  |
| GC14 | Education |  | ○ |  |  |  | ○ |  |  |  |  |  |  |  | ● | ● |  |  |  |
| GC15 | Electronics & Information Technology |  |  |  |  |  | ● |  |  |  |  | ○ |  |  |  |  |  |  |  |
| GC16 | Environment, Forest & Climate Change |  |  |  |  | ○ | ● |  |  |  |  |  | ● | ● |  |  | ● |  |  |
| GC17 | External Affairs | ● |  |  |  | ○ | ● | ● |  |  |  |  |  |  |  |  |  | ○ | ○ |
| GC18 | Finance: Expenditure, CGA |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ○ |  |  |  |
| GC19 | Finance: Revenue |  |  |  | ● |  |  |  |  |  | ● | ○ |  |  | ○ |  |  |  | ○ |
| GC20 | Finance: Economic Affairs, DIPAM, Financial Se... |  |  |  |  |  |  |  |  |  |  |  |  |  | ● | ○ |  |  |  |
| GC21 | Fisheries, Animal Husbandry & Dairying |  | ○ |  |  | ● |  |  |  | ○ |  |  |  |  | ● |  |  |  |  |
| GC22 | Food Processing Industries; Heavy Industries; ... |  | ○ |  |  |  |  |  |  |  | ○ |  |  |  | ○ | ○ |  |  |  |
| GC23 | Health & Family Welfare | ● | ● |  |  |  |  | ○ |  |  | ○ |  | ○ |  | ● | ● |  |  | ○ |
| GC24 | Home Affairs |  |  |  | ● |  | ○ |  | ○ | ● |  |  |  | ○ |  |  |  | ● | ● |
| GC25 | Housing & Urban Affairs | ● |  | ● |  |  |  |  |  |  | ○ |  | ○ | ○ |  |  |  |  |  |
| GC26 | Jal Shakti |  |  | ○ |  | ● |  |  |  |  |  |  | ● | ● |  | ○ |  |  |  |
| GC27 | Labour & Employment | ○ |  |  |  | ○ |  |  |  |  | ● |  |  |  | ● | ○ |  |  |  |
| GC28 | Law & Justice | ● |  |  | ● |  | ○ |  |  | ○ |  |  |  |  |  |  |  |  |  |
| GC29 | Micro, Small & Medium Enterprises |  |  |  | ○ |  |  |  |  | ● |  |  |  |  |  |  |  |  |  |
| GC30 | Minority Affairs |  |  |  |  |  |  |  |  | ● |  |  |  | ○ | ○ |  |  |  |  |
| GC31 | New & Renewable Energy |  |  | ○ |  | ● |  |  |  |  |  |  |  |  | ○ |  |  |  |  |
| GC32 | Panchayati Raj |  |  | ● |  | ○ |  |  |  | ○ |  |  |  | ● |  | ● |  |  |  |
| GC33 | Parliamentary Affairs |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  | ● |  |  |
| GC34 | Personnel, Public Grievances & Pensions | ○ |  |  |  |  |  |  |  |  |  |  |  |  | ○ | ○ |  |  |  |
| GC35 | Petroleum & Natural Gas |  | ● |  |  |  |  |  |  |  | ○ |  |  |  | ● |  |  | ○ |  |
| GC36 | Ports, Shipping & Waterways | ● |  | ○ |  |  | ● |  | ○ |  | ● |  |  |  |  |  |  |  |  |
| GC37 | Power |  |  | ○ |  |  |  |  |  |  |  |  | ○ |  |  | ● |  |  |  |
| GC38 | Railways | ● | ● | ● |  |  |  | ○ | ● |  | ● |  | ○ |  |  |  |  | ○ |  |
| GC39 | Road Transport & Highways | ● |  |  |  |  | ● |  |  |  | ● |  | ○ | ○ |  | ○ |  |  |  |
| GC40 | Rural Development |  |  |  |  | ● |  |  | ○ | ○ |  |  |  | ● | ● | ● |  |  |  |
| GC41 | Science & Technology |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ○ | ● |  |  |
| GC42 | Skill Development & Entrepreneurship | ○ |  |  |  |  | ● |  |  |  |  |  |  |  | ○ |  |  |  |  |
| GC43 | Social Justice & Empowerment | ● |  |  |  |  | ● |  |  |  |  |  |  |  | ○ |  |  |  |  |
| GC44 | Statistics & Programme Implementation |  |  |  |  | ● |  |  |  |  |  |  |  | ○ |  | ● |  |  |  |
| GC45 | Tourism |  |  |  |  | ● | ● |  |  | ○ |  |  |  |  |  |  |  |  |  |
| GC46 | Tribal Affairs |  |  |  | ○ |  |  |  |  |  |  |  |  | ● | ○ |  |  |  |  |
| GC47 | Women & Child Development | ○ | ● |  | ● | ○ |  |  |  |  |  |  |  |  | ● | ○ |  |  |  |
| GC48 | Youth Affairs & Sports | ● |  |  |  |  |  |  | ○ |  |  |  |  |  | ○ |  |  |  |  |
| GC49 | Information & Broadcasting | ○ |  |  |  |  | ● |  |  |  | ● |  |  |  |  |  |  |  |  |
| GC50 | AYUSH |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  | ○ |  |  |  |
| GC51 | Cabinet Secretariat, PMO, President's Secretar... | ● |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ● |  |  |
| GC52 | Space; Atomic Energy |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |

### 6.2 State

| ID | System | N01 | N02 | N03 | N04 | N05 | N06 | N07 | N08 | N09 | N10 | N11 | N12 | N13 | N14 | N15 | N16 | N17 | N18 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| GS01 | Finance, Treasury & Accounts |  |  |  |  |  |  |  |  |  | ○ | ○ |  |  |  | ○ |  |  |  |
| GS02 | Planning, Economics & Statistics |  |  |  |  | ○ |  |  |  |  |  |  |  | ○ |  | ● |  |  |  |
| GS03 | General Administration, Personnel, Protocol & ... | ● |  | ○ |  |  |  |  | ○ |  | ○ |  |  |  | ○ |  |  |  |  |
| GS04 | Home / Police |  |  |  | ● |  | ○ |  | ● | ● |  |  | ○ | ○ |  |  |  | ● | ● |
| GS05 | Prisons | ● | ○ |  |  |  |  |  | ○ | ● |  |  |  |  | ● |  |  |  |  |
| GS06 | Fire & Emergency Services |  |  | ○ |  | ● | ● |  |  |  |  |  |  |  |  |  |  | ● |  |
| GS07 | Revenue & Land Records | ○ |  |  | ● | ○ | ● |  |  | ● |  |  |  | ● |  |  |  |  |  |
| GS08 | Stamps & Registration | ● |  |  |  |  |  | ○ |  | ● | ● |  |  | ○ |  |  |  |  |  |
| GS09 | Transport | ● |  | ● |  |  | ● | ○ | ● |  | ● |  | ○ |  |  |  |  | ○ |  |
| GS10 | Health & Family Welfare - IHMS/HMIS | ● | ● | ● |  |  |  | ○ | ○ |  | ○ |  |  |  | ● | ● |  | ● | ○ |
| GS11 | Medical Education | ● | ○ | ○ |  |  |  |  | ● |  |  |  |  |  |  |  |  |  |  |
| GS12 | AYUSH |  | ○ |  |  |  | ○ |  |  |  |  |  |  |  |  | ○ |  |  |  |
| GS13 | Drug Control & Food Safety |  |  |  |  | ● | ● |  |  |  |  |  |  | ○ |  |  |  |  | ● |
| GS14 | School Education | ● | ● |  |  | ○ |  |  |  |  |  |  |  |  | ○ | ● |  | ○ |  |
| GS15 | Higher & Technical Education | ● |  |  |  | ● | ○ |  |  |  |  |  |  |  | ○ |  |  |  |  |
| GS16 | Women & Child Development |  | ● |  | ● | ○ |  |  |  |  |  |  |  |  | ● | ○ |  |  |  |
| GS17 | Social Welfare / Social Justice | ○ | ○ |  |  | ○ |  |  |  |  |  |  |  |  | ● |  |  |  |  |
| GS18 | SC/ST/OBC/Minority Welfare and Tribal Developm... | ○ | ○ |  |  |  |  |  |  |  |  |  |  | ● | ● |  |  |  |  |
| GS19 | Food, Civil Supplies & Consumer Affairs |  | ● |  | ○ | ○ | ● |  |  |  |  |  |  | ○ | ● |  |  | ● | ○ |
| GS20 | Agriculture | ○ | ○ |  |  | ● | ● |  |  |  |  |  |  | ○ | ● |  |  |  | ○ |
| GS21 | Animal Husbandry & Veterinary | ○ | ● |  |  | ● |  |  |  |  |  |  |  |  | ● |  |  |  |  |
| GS22 | Fisheries |  |  |  |  |  | ● |  |  | ● |  |  |  | ○ |  |  |  |  |  |
| GS23 | Cooperation and Dairy |  | ○ |  |  |  |  |  |  | ● | ● |  | ○ |  |  |  | ○ |  |  |
| GS24 | Rural Development |  |  |  |  | ● |  |  | ○ |  |  |  |  | ● | ● | ○ |  |  |  |
| GS25 | Panchayati Raj |  |  | ○ |  |  |  |  |  | ● | ● |  |  | ● |  | ○ |  |  |  |
| GS26 | Urban Development / Urban Local Bodies | ○ |  | ● |  | ○ | ● |  |  | ● | ● |  | ○ | ● |  |  |  | ○ |  |
| GS27 | Housing, Development Authorities, Housing Boards | ● |  |  |  |  |  |  |  | ○ | ● |  |  | ○ |  |  |  |  |  |
| GS28 | Town & Country Planning |  |  |  |  |  | ● |  |  |  |  |  |  | ● |  |  |  |  |  |
| GS29 | PWD / Roads & Buildings | ● |  | ● |  | ○ |  |  |  |  | ○ |  |  | ● |  | ○ |  |  |  |
| GS30 | Water Resources / Irrigation |  |  | ○ |  | ○ |  |  |  |  | ● |  | ● | ● |  |  |  |  |  |
| GS31 | Public Health Engineering / Drinking Water |  |  | ● |  | ● |  |  |  |  | ○ |  | ○ | ○ |  |  |  |  | ● |
| GS32 | Energy department, discoms, transco, genco, el... |  | ○ | ● |  |  | ○ |  | ○ |  | ● |  | ● | ○ |  |  |  | ● |  |
| GS33 | Industries, Commerce & Investment Promotion | ● |  |  |  | ○ | ● |  |  |  |  |  |  | ○ |  |  |  |  |  |
| GS34 | Labour & Employment |  |  |  | ○ | ● | ● |  |  | ○ |  |  |  |  | ● |  |  |  |  |
| GS35 | Mines & Geology |  |  |  |  | ○ | ● |  |  |  | ● |  | ○ | ● |  |  |  |  |  |
| GS36 | Forest & Environment | ● | ○ |  |  | ● | ● |  |  |  |  |  | ○ | ● | ○ |  |  |  |  |
| GS37 | Excise |  | ● |  | ○ |  | ● |  |  |  | ● |  |  |  |  |  |  |  | ● |
| GS38 | Commercial Taxes / State GST |  |  |  | ● | ○ |  |  |  |  | ● | ○ |  |  |  |  |  |  | ○ |
| GS39 | Tourism | ● | ○ |  |  |  | ● |  |  |  | ● |  |  |  |  |  |  |  |  |
| GS40 | Culture, Archaeology, Archives & Museums | ● |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  | ● |
| GS41 | Sports & Youth Welfare | ● |  |  |  |  |  |  | ○ |  |  |  |  |  | ○ |  |  |  |  |
| GS42 | Information & Public Relations | ○ |  |  |  |  | ● |  |  |  | ● |  |  |  |  |  |  |  |  |
| GS43 | Law & Legislative / Prosecution | ○ |  |  | ● |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |
| GS44 | Judiciary | ● |  |  | ● |  |  | ○ |  | ● |  |  |  |  |  |  |  | ○ | ○ |
| GS45 | Legislative Assembly Secretariat | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ● |  |  |
| GS46 | Election | ○ | ● |  |  |  |  |  | ● |  |  |  |  | ● |  |  |  | ○ | ● |
| GS47 | IT / e-Governance |  |  |  |  |  | ○ |  |  |  |  | ○ |  |  |  |  |  |  |  |
| GS48 | Skill Development, Technical Education & Emplo... | ○ |  |  |  |  | ○ |  |  |  |  |  |  |  | ○ |  |  |  |  |
| GS49 | Science & Technology / Remote Sensing Centres |  |  |  |  |  |  |  |  |  |  |  |  | ○ |  |  | ○ |  |  |
| GS50 | Disaster Management / Relief | ○ | ● |  |  |  |  |  |  |  |  |  | ● | ● | ○ |  |  | ● |  |
| GS51 | Vigilance, Lokayukta, State Information Commis... | ○ |  |  | ● |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| GS52 | Public Service Commission / Staff Selection Bo... | ○ |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  | ○ |
| GS53 | Endowments / Religious & Charitable Institutions | ● | ○ | ● |  |  |  | ○ |  |  | ● |  |  |  |  |  |  |  |  |
| GS54 | Sainik Welfare; Rehabilitation & Resettlement | ○ |  |  |  |  |  |  |  |  |  |  |  | ○ | ● |  |  |  |  |
| GS55 | Printing & Stationery; State warehousing and c... |  | ● |  |  |  |  |  |  | ● | ● |  |  |  |  |  |  |  |  |

### 6.3 Cross-cutting (applies to every department)

| ID | System | N01 | N02 | N03 | N04 | N05 | N06 | N07 | N08 | N09 | N10 | N11 | N12 | N13 | N14 | N15 | N16 | N17 | N18 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| GX01 | e-Office file and dak |  |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  | ○ |
| GX02 | Finance |  |  |  |  |  |  |  |  |  | ○ |  |  |  |  | ○ |  |  |  |
| GX03 | HRMS and service book |  |  |  |  |  |  |  |  | ○ |  |  |  |  | ○ |  |  |  |  |
| GX04 | Payroll |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| GX05 | Procurement |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| GX06 | Works |  |  |  |  | ○ |  |  |  |  |  |  |  | ○ |  | ○ |  |  |  |
| GX07 | Stores and inventory |  | ● |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ○ |
| GX08 | Assets and vehicle pool | ○ |  | ● |  |  |  |  |  |  |  |  |  |  |  |  |  | ○ |  |
| GX09 | Circuit houses, guest houses, rest houses, ins... | ● | ○ |  |  |  |  |  | ○ |  | ● |  |  |  |  |  |  |  |  |
| GX10 | Conference halls, auditoriums, training venues | ● |  |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |
| GX11 | Public grievance |  |  |  | ○ |  |  |  |  |  |  |  |  | ○ |  |  |  |  |  |
| GX12 | RTI |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| GX13 | Government litigation | ○ |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| GX14 | Audit paras |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| GX15 | Assembly/Parliament questions, assurances, VIP... |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  | ● |  |  |
| GX16 | Meetings and committees | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ● |  |  |
| GX17 | Scheme MIS and monthly progress reports |  |  |  |  |  |  |  |  |  |  |  |  | ○ |  | ● |  |  |  |
| GX18 | Inspections and tours |  |  |  |  | ● |  |  |  |  |  |  |  | ○ |  |  |  |  |  |
| GX19 | Records and archives |  |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  | ○ |
| GX20 | Training and capacity building | ○ |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |
| GX21 | Visitor and reception management | ○ |  |  |  |  |  | ● |  |  |  |  |  |  |  |  |  |  |  |
| GX22 | Attendance and leave |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |
| GX23 | Certificates and licences |  |  |  |  |  | ● |  |  | ○ |  |  |  |  |  |  |  |  |  |
| GX24 | Control rooms |  |  |  |  |  |  |  |  |  |  |  | ○ | ○ |  |  |  | ● |  |

### 6.4 Private

| ID | System | N01 | N02 | N03 | N04 | N05 | N06 | N07 | N08 | N09 | N10 | N11 | N12 | N13 | N14 | N15 | N16 | N17 | N18 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| PB01 | Banking: Onboarding / KYC / re-KYC |  |  |  |  |  |  |  |  |  |  |  |  |  | ○ |  |  |  |  |
| PB02 | Banking: Lending |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PB03 | Banking: Collections and recovery |  |  |  |  | ● |  |  |  |  |  |  |  | ○ |  |  |  | ○ |  |
| PB04 | Banking: Branch operations | ● | ● |  |  |  |  | ● |  |  | ○ |  |  |  |  |  |  |  | ● |
| PB05 | Banking: Premises, ATMs and fixed assets |  |  | ● |  | ○ |  |  |  |  |  |  | ○ |  |  |  |  |  |  |
| PB06 | Banking: Regulatory returns and reporting |  |  |  |  |  |  |  |  |  |  | ● |  |  |  | ● |  |  |  |
| PB07 | Banking: Payments, disputes, AML |  |  |  |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |
| PN01 | NBFC / MFI: Microfinance field operations |  |  |  |  | ● |  |  | ○ |  |  |  |  | ● | ○ |  |  |  |  |
| PN02 | NBFC / MFI: Gold loans |  | ○ |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  | ● |
| PI01 | Insurance: Claims |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PI02 | Insurance: Distribution and provider network |  |  |  |  | ○ | ● |  |  |  |  |  |  |  |  |  |  |  |  |
| PC01 | Capital markets: Broking, depository, RTA oper... |  |  |  |  |  |  |  |  |  |  | ○ |  |  |  | ○ |  |  |  |
| PH01 | Hospitals: OPD appointments and patient queue | ● |  |  |  |  |  | ● |  |  |  |  |  |  |  |  |  |  |  |
| PH02 | Hospitals: IPD bed management | ● |  |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |
| PH03 | Hospitals: Operation theatre | ● | ○ |  |  |  |  |  | ● |  |  |  |  |  |  |  |  |  |  |
| PH04 | Hospitals: Pharmacy and stores |  | ● |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ○ |
| PH05 | Hospitals: Laboratory, radiology, blood centre | ○ | ● |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ● |
| PH06 | Hospitals: Patient billing, packages, TPA |  |  |  |  |  |  |  |  |  | ● |  |  |  |  |  |  |  |  |
| PH07 | Hospitals: Biomedical equipment and facilities |  |  | ● |  |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |
| PH08 | Hospitals: Staff duty roster |  |  |  |  |  |  |  | ● |  |  |  |  |  |  |  |  |  |  |
| PH09 | Hospitals: CSSD, linen, diet, housekeeping, bi... |  | ● |  |  |  | ○ |  | ○ |  |  |  |  |  |  |  |  |  | ● |
| PH10 | Hospitals: Ambulance and patient transport |  |  |  |  |  |  |  |  |  |  |  |  | ○ |  |  |  | ● |  |
| PH11 | Hospitals: Patient master and records |  |  |  |  |  |  |  |  |  |  |  |  |  | ● |  |  |  |  |
| PH12 | Hospitals: Group/chain analytics and data inte... |  |  |  |  |  |  |  |  |  |  | ● |  |  |  | ● |  |  |  |
| PD01 | Diagnostics chains: Home sample collection | ● |  |  |  |  |  |  |  |  |  |  |  | ○ |  |  |  | ● | ● |
| PE01 | E-commerce / retail: Order to cash, returns, s... |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PE02 | E-commerce / retail: Catalogue and pricing |  |  |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |
| PE03 | E-commerce / retail: Inventory and warehouse |  | ● |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ○ |
| PE04 | E-commerce / retail: Last-mile delivery |  |  |  |  | ○ |  |  |  |  |  |  |  | ○ |  |  |  | ● |  |
| PE05 | E-commerce / retail: Physical store operations |  | ○ |  |  | ● |  |  | ○ |  |  |  |  |  |  |  |  |  |  |
| PE06 | E-commerce / retail: Customer service |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PW01 | Data warehouse / data platform: Pipeline orche... |  |  |  |  |  |  |  |  |  |  | ● | ○ |  |  |  |  |  |  |
| PW02 | Data warehouse / data platform: Data quality i... |  |  |  | ○ |  |  |  |  |  |  | ● |  |  |  |  |  |  |  |
| PW03 | Data warehouse / data platform: Access request... |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PW04 | Data warehouse / data platform: Statutory/regu... |  |  |  |  |  |  |  |  |  |  | ● |  |  |  | ● |  |  |  |
| PL01 | Logistics: Shipment and trip |  |  |  |  |  |  |  |  |  |  |  | ○ | ● |  |  |  | ● | ○ |
| PL02 | Logistics: Warehouse |  | ● |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ○ |
| PL03 | Logistics: Dock and yard appointments | ● |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  |
| PL04 | Logistics: Fleet maintenance and driver roster... |  |  | ● |  |  |  |  | ● |  |  |  | ○ |  |  |  |  |  |  |
| PT01 | Telecom: Order and provisioning, MNP, SIM swap |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PT02 | Telecom: Trouble tickets and field force |  |  |  |  | ● |  |  |  |  |  |  |  | ○ |  |  |  | ● |  |
| PT03 | Telecom: Tower and site assets |  | ○ | ● |  |  |  |  |  |  |  |  | ● | ○ |  |  |  |  |  |
| PT04 | Telecom: Billing and revenue assurance |  |  |  |  |  |  |  |  |  | ● | ○ |  |  |  |  |  |  |  |
| PT05 | Telecom: Number and resource inventory |  | ○ |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  |
| PS01 | Education: Admissions, exams, scholarships |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PS02 | Education: Timetable, rooms and labs | ● |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |
| PS03 | Education: Hostel, fees, transport | ● |  |  |  |  |  |  |  |  | ● |  |  |  |  |  |  | ○ |  |
| PS04 | Education: Library |  | ○ |  |  |  |  |  |  |  | ○ |  |  |  |  |  |  |  | ● |
| PM01 | Manufacturing: Plant maintenance |  | ○ | ● |  |  |  |  |  |  |  |  | ● |  |  |  |  |  |  |
| PM02 | Manufacturing: Quality, CAPA, batch release |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PM03 | Manufacturing: MRP and production orders |  | ● |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PM04 | Manufacturing: Shift and workforce |  |  |  |  |  |  |  | ● |  |  |  |  |  |  |  |  |  |  |
| PM05 | Manufacturing: EHS permit-to-work |  |  |  |  | ○ | ● |  |  |  |  |  |  |  |  |  |  |  |  |
| PM06 | Manufacturing: Calibration and traceability |  |  | ● |  |  |  |  |  |  |  |  |  |  |  |  |  |  | ● |
| PV01 | Services: ITSM |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PV02 | Services: Resource management, timesheets, bil... | ○ |  |  |  |  |  |  | ○ |  | ● |  |  |  | ○ |  |  |  |  |
| PV03 | Services: Field service management | ● | ○ | ○ |  | ● |  |  |  |  |  |  |  |  |  |  |  | ● |  |
| PR01 | Real estate: Unit sales and allotment | ● |  |  |  |  |  |  |  | ○ | ● |  |  |  |  |  |  |  |  |
| PR02 | Real estate: Construction and RERA compliance |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  | ● |  |  |  |
| PR03 | Real estate: Facility management | ● |  | ● | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| PO01 | Hospitality: Reservations and room inventory | ● |  |  |  |  |  |  |  |  | ● |  |  |  |  |  |  |  |  |
| PO02 | Hospitality: Housekeeping, maintenance, F&B, e... | ● | ● | ○ |  |  |  |  | ● |  |  |  |  |  |  |  |  |  |  |
| PA01 | Media: Content production and rights |  |  |  |  |  | ○ |  |  |  |  |  |  |  |  |  |  |  |  |
| PA02 | Media: Ad inventory and scheduling | ● |  |  |  |  |  |  |  | ○ | ● |  |  |  |  |  |  |  |  |
| PU01 | Utilities: Outage and crew dispatch; network a... |  |  | ● |  |  |  |  |  |  |  |  | ● | ○ |  |  |  | ● |  |
| PG01 | Agri / FPO / warehousing: Procurement and ware... |  | ● |  |  |  |  |  |  | ● | ○ |  |  |  |  |  |  |  |  |
| PX01 | Automotive dealers and service networks: Servi... | ● | ○ | ○ |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |

---

## 7. Missing generic capabilities, ranked

Ranking is by weighted score. Weighted score = ● + 0.5 × ○, summed over all 198 rows. "Private sectors" counts the distinct sectors among the 21 in section 4.

| Rank | Capability | Central ● / ○ | State ● / ○ | Cross-cutting ● / ○ | Private ● / ○ | Private sectors (of 21) | Rows needing it | Weighted score |
|---|---|---|---|---|---|---|---|---|
| 1 | **N01** Resource booking, allotment and scheduling | 11 / 8 | 17 / 15 | 2 / 5 | 15 / 2 | 11 | 75 | 60.0 |
| 2 | **N10** Billing, demand, tariff and collection | 8 / 9 | 15 / 6 | 1 / 2 | 7 / 6 | 11 | 54 | 42.5 |
| 3 | **N06** Issued-entitlement registry (licences, permits, certificates, registrations) | 16 / 5 | 16 / 6 | 1 / 0 | 2 / 2 | 4 | 48 | 41.5 |
| 4 | **N02** Inventory, stock and warehouse (quantity ledger) | 7 / 5 | 9 / 11 | 1 / 1 | 9 / 9 | 12 | 52 | 39.0 |
| 5 | **N13** GIS and spatial data | 7 / 9 | 12 / 13 | 0 / 5 | 2 / 7 | 8 | 55 | 38.0 |
| 6 | **N05** Field inspection, mobile/offline capture, geo-tagged evidence | 6 / 8 | 9 / 12 | 1 / 1 | 5 / 8 | 8 | 50 | 35.5 |
| 7 | **N14** Party / beneficiary registry with de-duplication | 10 / 11 | 11 / 7 | 0 / 1 | 1 / 3 | 4 | 44 | 33.0 |
| 8 | **N03** Asset registry and maintenance | 5 / 6 | 7 / 5 | 1 / 0 | 8 / 4 | 11 | 36 | 28.5 |
| 9 | **N09** Statutory registers and numbering | 7 / 6 | 10 / 3 | 0 / 4 | 1 / 3 | 4 | 34 | 26.0 |
| 10 | **N15** Targets, indicators, milestones and periodic returns | 8 / 10 | 3 / 6 | 1 / 2 | 4 / 1 | 5 | 35 | 25.5 |
| 11 | **N18** Physical item custody and tracking (chain of custody) | 3 / 5 | 6 / 6 | 0 / 3 | 7 / 4 | 8 | 34 | 25.0 |
| 12 | **N17** Dispatch, trips and field-unit tracking | 1 / 4 | 6 / 5 | 1 / 1 | 7 / 2 | 9 | 27 | 21.0 |
| 13 | **N08** Rostering, shifts and duty deployment | 1 / 6 | 4 / 7 | 0 / 3 | 5 / 5 | 8 | 31 | 20.5 |
| 14 | **N04** Case file and party model (generic case management) | 4 / 7 | 7 / 3 | 0 / 3 | 0 / 3 | 3 | 27 | 19.0 |
| 15 | **N12** IoT / telemetry-triggered processes (stream ingestion) | 3 / 5 | 3 / 7 | 0 / 1 | 3 / 5 | 7 | 27 | 18.0 |
| 16 | **N11** Data-pipeline and batch-job orchestration | 1 / 2 | 0 / 3 | 0 / 0 | 5 / 3 | 5 | 14 | 10.0 |
| 17 | **N07** Queue and token management | 1 / 4 | 0 / 5 | 1 / 0 | 2 / 1 | 3 | 14 | 9.0 |
| 18 | **N16** Meetings, committees, questions and references | 4 / 1 | 1 / 2 | 2 / 0 | 0 / 0 | 0 | 10 | 8.5 |

**How to read the ranking.**
- **Booking and allotment (N01) is first by a wide margin.** It is the owner's circuit-house example, but the same capability also serves:
  - OT and OPD slots;
  - licence-test slots;
  - quarter and plot allotment;
  - hall and vehicle booking;
  - court cause lists;
  - prison visits;
  - darshan slots;
  - hostel and bed allotment;
  - hotel rooms;
  - dock slots;
  - ad slots.

  It is one capability with two modes: short time-slot booking, and long-term allotment by priority or waitlist.
- **N10, N06, N02 and N14 are the "public finance and service delivery" set.** They come next because the revenue-earning and benefit-giving departments all need them: ULBs, transport, registration, excise, mining, PDS and welfare. A cross-cutting row also needs each of them (GX02, GX07, GX09 and GX23), so they reach every department.
- **N13 GIS and N05 field capture rank high in government, lower in private.** Land, rural, forest, water and works systems are spatial and field-verified by nature. In private, they matter for collections, field service, logistics and telecom.
- **N11 (data pipelines) and N12 (IoT) rank low by row count.** They are still strategic for the owner's data-warehouse and manufacturing/utilities examples. N12 is already planned as R83 / UP-21 (Stage E); this ranking argues for moving a subset forward to D.
- **N16 (meetings/questions/references) has the fewest rows.** Two of those rows are cross-cutting (GX15, GX16), so every department needs it. It is cheap to build on N04 and N01.

---

## 8. Design sketches for the missing capabilities

**Common rules for all of N01–N18.**
- Every entity is tenant-scoped and effective-dated, and is read only through the R19 `AuthorizedQueryBuilder`.
- Every external effect uses R36 operationId.
- Configuration is data: C04 profiles, C05 code lists, DMN and packs (ADR-D-20).
- No department or country is named in code.
- Each capability exposes REST under `/api/v1/<area>`, worker topics and BPMN/DMN building blocks so that packs can compose it.
- Each capability can run **standalone** for a small office, or as a **façade over an external system of record** through a connector port. That second mode is what keeps it inside D-22.

### N01 Resource booking, allotment and scheduling

- **Covers:**
  - circuit houses, guest houses, rest houses, inspection bungalows and state Bhawans;
  - halls, vehicles and equipment;
  - OPD/OT/modality slots and beds;
  - test slots (driving licence, PSK, registration appointment);
  - court cause lists and hearing dates;
  - prison and officer visits;
  - darshan;
  - long-term allotment (quarters, plots, flats, hostel seats, counselling seats);
  - hotel rooms, dock slots, ad slots.
- **Entities:**
  - `Resource`: type, unit, capacity, attributes, owning org unit, and an optional parent (a building with rooms in it).
  - `ResourceCalendar`: opening hours, blackout dates and the business calendar from C12.
  - `SlotTemplate` and `Slot`.
  - `Booking`: requester, beneficiary/party (N14), purpose, entitlement class, period, state HELD → CONFIRMED → CHECKED_IN → CHECKED_OUT / CANCELLED / NO_SHOW / BUMPED.
  - `AllotmentRequest` and `Waitlist`: priority key, seniority date, category.
  - `Allotment`: long-term occupancy with a vacate date.
  - `EntitlementRule`: a DMN table giving who may book what class, for how long, and at what tariff.
- **Services:**
  - availability search;
  - hold with expiry, then confirm;
  - overbooking guard;
  - priority bump with notice (for example, a VIP override in a circuit house), always audited with reason and authority;
  - waitlist promotion (GP-28);
  - lottery or draw with a published seed and an audited result;
  - recurring bookings;
  - check-in/out;
  - charge hand-off to N10.
- **Concurrency:** double booking is impossible by construction, on all four Tier-1 databases.
  - Every booking claims slot-granular rows with a unique key (resource, slot).
  - PostgreSQL exclusion constraints are not used, because Oracle, SQL Server and MySQL lack them.
  - Long allotments claim one row per resource with `valid_from`/`valid_to`, checked under the R35 `resource:` exclusive lease.
- **Configuration:**
  - resource types and attributes as R78 data objects;
  - entitlement, priority, tariff and cancellation-refund rules as DMN;
  - booking windows and notice periods in C04 profiles.
- **Packs enabled:**
  - `gov.circuit-house`, `gov.hall-booking`, `gov.vehicle-pool`, `gov.quarters-allotment`, `gov.plot-allotment-lottery`, `gov.counselling-seat-allotment`, `gov.visit-booking`, `gov.facility-booking`;
  - `sec.health.opd-appointment`, `sec.health.bed-management`;
  - `sec.hosp.reservation`, `sec.edu.timetable-rooms`, `sec.log.dock-slot`.
  - The existing `sec.health.ot-scheduling` would be refactored onto N01.
- **Boundary:** **IN, generic.** It is a calendar/ledger of capacity, which is master data plus a ledger in D-22 terms. Out of scope:
  - channel management for hotels (an OTA connector);
  - optimisation solvers for timetables. These are an optional solver worker; Timefold Solver (Apache 2.0) is a candidate [U].

### N02 Inventory, stock and warehouse (quantity ledger)

- **Covers:**
  - government stores (GFR indent → issue, stock verification);
  - drug and vaccine stores, Jan Aushadhi, PDS warehouses and FPS stock;
  - take-home ration, textbooks and uniforms;
  - EVM warehouses, excise stock, relief material, printing and stationery;
  - hospital pharmacy, CSSD and linen;
  - e-commerce and logistics warehouses;
  - manufacturing material issue;
  - telecom spares, currency chests, gold packets (quantity side).
- **Entities:**
  - `Item`: in the C05 registry, with UoM, conversion, batch/serial/expiry flags and hazard class.
  - `Store`: an org unit or location, with an optional hierarchy of bins.
  - `StockLedgerEntry`: append-only movements (RECEIPT, ISSUE, TRANSFER_OUT/IN, ADJUSTMENT, WRITE_OFF, RETURN, CONSUMPTION) by (item, store, lot, expiry, serial).
  - `StockBalance`: a projection of the ledger.
  - `Reservation`: the quantity analogue of encumbrance.
  - `Indent`, `IssueVoucher`, `GoodsReceipt`.
  - `ReorderRule`: DMN.
- **Services:**
  - post a movement idempotently by operationId;
  - reserve and release;
  - FEFO/FIFO pick suggestion;
  - balance as of a date;
  - in-transit quantities;
  - expiry alerts (timer start);
  - valuation (weighted average or FIFO, configurable) for reports only;
  - physical-verification snapshots feeding the existing `gov.stores` discrepancy flow.
- **Design choice:** build it as a **second account kind in `sangam-ledger`**, a quantity account next to the fund/limit account. Then posting, reservation (encumbrance), period close, audit and reconciliation are written once, and a single sanction can encumber both money and stock.
- **Configuration:** item classes, movement types and approval thresholds by value (C02 powers), all in C05 and DMN.
- **Packs enabled:**
  - `gov.stores-indent-issue`, `gov.pds-allocation`, `gov.drug-store`, `gov.relief-material`, `gov.evm-warehouse`, `gov.excise-stock`;
  - `sec.health.pharmacy-stock`, `sec.retail.inventory`, `sec.log.warehouse`, `sec.mfg.material-issue`.
- **Boundary:** **IN, as a generic quantity ledger.** Out: WMS optimisation (slotting, wave picking, labour) and MRP solvers, which stay in ERP/WMS and are reached by connectors. When an ERP is the system of record, N02 runs in **mirror mode**: it holds reservations only and posts movements to the ERP.

### N03 Asset registry and maintenance

- **Covers:**
  - government asset registers (GFR) and vehicles;
  - PWD road and building inventories;
  - dam, canal and water-scheme assets;
  - discom networks;
  - hospital biomedical equipment;
  - ATMs and premises;
  - telecom towers;
  - plant maintenance and calibration;
  - fleet and facility management.
- **Entities:**
  - `Asset`: a registry type in N-registry (see module M4), with class, tag, location (N13 geometry optional), parent/child hierarchy, custodian seat, acquisition value, warranty/AMC contract link and status.
  - `Meter`: odometer, run-hours, cycles.
  - `MaintenancePlan`: time-based, meter-based or condition-based (N12).
  - `MaintenanceWorkOrder`: kept separate from the existing contract `gov.work-order`.
  - `Failure` and `Downtime`.
  - `CalibrationRecord`.
  - `SparePartUse`: posts to N02.
- **Services:**
  - plan → due work-order generation (timer start, GP-30 style);
  - breakdown intake from a person or an alarm (N12);
  - assignment by skill (R82);
  - completion with evidence (N05);
  - downtime and MTBF figures to C14;
  - warranty/AMC claim routing to the vendor (UP-22);
  - hand-off to condemnation and disposal (existing `gov.assets`).
- **Configuration:** asset classes, attributes and plans as data; maintenance policies as DMN.
- **Packs enabled:**
  - `maint.preventive`, `maint.breakdown`, `maint.calibration`, `maint.amc-claim`;
  - `gov.road-asset-inspection`, `gov.biomedical-equipment`, `gov.dam-safety-inspection`;
  - `sec.telecom.site-maintenance`, `sec.mfg.plant-maintenance`.
- **Boundary:** **IN for the register and the maintenance workflow.** Out: full EAM functions such as reliability engineering and spare-part optimisation, and SCADA. The register can be a mirror of an ERP/EAM through a connector.

### N04 Case file and party model (generic case management)

- **Covers:**
  - FIR and investigation;
  - court, revenue-court and consumer-commission cases;
  - child-protection, OSC and vigilance cases;
  - tax assessment and enforcement;
  - complaints;
  - insurance investigations;
  - DQ incidents.

  CMMN already gives the *behaviour*. What is missing is a shared **case file**.
- **Entities:**
  - `Case`: type, number from N09, status, jurisdiction.
  - `CaseParty`: role, plus a link to a party in N14.
  - `CaseLink`: a parent, linked, merged or split case (GP-54).
  - `Hearing` or `Event`: date, forum, outcome. Dates are set by an outside forum (GP-51).
  - `Exhibit`: links to N18 and C11.
  - `Note`, `Deadline` (C12) and `Order`.
- **Services:**
  - case-type configuration (fields, roles, stages, milestones);
  - conflict and duplicate check across parties;
  - a timeline that merges process history with case events;
  - cause-list feed to N01;
  - cross-case search under R19 with party masks.
- **Packs enabled:** refactors `gov.court-case`, `gov.grievance`, `gov.audit-para` and `sec.legal.litigation` onto a common file. New packs:
  - `gov.police-case` (as a façade over CCTNS);
  - `gov.child-protection-case`;
  - `gov.consumer-case`;
  - `gov.vigilance-case`;
  - `gov.revenue-court`.
- **Boundary:** **IN.** It is generic and sits on CMMN. The court's or police's own CIS/CCTNS stays the system of record where one exists, and Sangam integrates with it.

### N05 Field inspection, mobile/offline capture, geo-tagged evidence

- **Covers:**
  - inspections (factory, food, fire, labour, licence);
  - field verification (DBT, scholarships, insurance surveys);
  - geo-tagged stage photos (PMAY-G, MGNREGA, works);
  - crop-cutting;
  - census-style surveys;
  - bank collections;
  - MFI centre meetings;
  - field service;
  - store audits.
- **Entities:**
  - `Assignment`: inspector seat, target, due date, randomisation seed.
  - `Checklist` / `FormVersion`: C03 forms flagged offline-capable.
  - `CapturePacket`: answers, photos and media references, GPS fix with accuracy, device clock, server-receipt clock, device id, app version, a SHA-256 hash of the packet, and an optional signature.
  - `GeoFence`: from N13.
  - `Finding` / `NonCompliance`: feeds penalty DMN or CAPA (UP-18).
- **Services:**
  - an offline-first PWA/mobile client that syncs through an idempotent upload (R41, keyed by packet id) into the inbox (R38);
  - anti-spoofing checks: GPS against the geofence, photo time against the visit window, EXIF consistency, duplicate-photo hash, mock-location flag;
  - random selection of units to inspect (risk-based DMN with an audited seed — central and state inspection schemes ask for randomised, risk-based selection [U]);
  - route lists;
  - inspector rotation, so that the same inspector does not keep the same unit (DMN).
- **Packs enabled:**
  - the generic `field.inspection`, `field.verification` and `field.geo-stage-evidence`;
  - refactor of `gov.inspection`;
  - `gov.crop-cutting`, `gov.random-inspection`;
  - `sec.bank.field-collection`, `sec.fsm.site-visit`.
- **Boundary:** **IN.** It is generic capture, not a domain. The mobile client is a Sangam product component.

### N06 Issued-entitlement registry (licences, permits, certificates, registrations)

- **Gap:** P4 handles the *application*. Nothing models the **issued thing** over its life. That life covers validity, renewal, amendment, suspension, cancellation, surrender, transfer, duplicate issue and public verification.
- **Covers:**
  - trade, drug, food, excise, mining and factory licences;
  - transit passes and vehicle permits;
  - arms licences;
  - fire NOCs;
  - environmental consents;
  - caste, income and domicile certificates;
  - registrations (societies, companies, shops, homestays, MSMEs);
  - agent licences;
  - industrial permit-to-work.
- **Entities:**
  - `EntitlementType`: schema, validity rule, renewal window, transferability, fee rule (N10), inspection rule (N05), output template (C11).
  - `Entitlement`: number from N09, holder party (N14), premises/asset (N03/N13), conditions, valid_from/valid_to.
  - State machine: ISSUED → ACTIVE → RENEWAL_DUE → (RENEWED | EXPIRED), with side exits SUSPENDED, CANCELLED, SURRENDERED and TRANSFERRED.
  - `Endorsement` (amendment) and `ComplianceObligation` (periodic returns from N15, conditions to report).
- **Services:**
  - issue from any pack (the P4 closure step calls `entitlement.issue`);
  - renewal campaign (UP-15) with deemed-renewal rules where the law allows them (DMN);
  - public verification by number or QR, using a signed, minimal payload with DPDP masking;
  - DigiLocker push (existing connector);
  - bulk expiry jobs;
  - holder change through a transfer workflow.
- **Packs enabled:** the generic `entitlement.renewal`, `entitlement.suspension-cancellation`, `entitlement.transfer` and `entitlement.duplicate`, which every licence pack reuses. Also `sec.mfg.permit-to-work` and `sec.ins.agent-renewal`.
- **Boundary:** **IN.** It is a generic registry, which D-22 calls master data, and it is the core of every "time-bound service".

### N07 Queue and token management

- **Covers:** OPD, Passport Seva and RTO counters, registration offices, bank branches, ration offices, public hearing days and temple queues.
- **Entities:**
  - `ServicePoint` / `Counter`;
  - `Token`: walk-in, or appointment check-in from N01; priority class;
  - `QueueRule`: DMN for priority (senior citizens, persons with disabilities, pregnant women), routing to a counter skill and transfer;
  - `CallEvent`.
- **Services:**
  - issue a token (kiosk, SMS, QR);
  - call next;
  - recall, skip, transfer;
  - display and SMS feed;
  - wait-time statistics to C14.
- **Packs enabled:** `queue.walk-in-service`, `queue.opd`, `queue.branch`.
- **Boundary:** **IN, small.** Display hardware and the kiosk UI are outside and reached by a webhook or websocket feed.

### N08 Rostering, shifts and duty deployment

- **Covers:**
  - police bandobast and duty;
  - hospital nurse and resident rosters;
  - railway crew;
  - bus crew;
  - prison guards;
  - election polling-staff randomisation and deployment;
  - discom field crews;
  - plant shifts;
  - hotel housekeeping;
  - circuit-house staff.
- **Entities:**
  - `ShiftTemplate`, `RotationPattern`;
  - `CoverageDemand`: per unit, skill and time band;
  - `RosterPeriod`, `RosterAssignment`: person ↔ seat ↔ shift;
  - `SwapRequest`, `OnCall`;
  - `Constraint`: rest hours, maximum hours and weekly off, held as DMN and profile data (labour codes, service rules).
- **Services:**
  - generate a draft roster (rule-based by default; optional solver worker);
  - publish with approval (C02);
  - swap and cover approvals;
  - leave integration (`gov.leave`);
  - attendance reconciliation (AEBAS / time-and-attendance connector);
  - **duty ⇒ seat link**: for the duty window, the person on duty acts in the duty seat, reusing the IX.5 "additional charge" machinery, so tasks route to whoever is on shift;
  - audited randomisation for election and exam duty.
- **Packs enabled:** `roster.publish`, `roster.swap`, `gov.election-staff-randomisation`, `gov.police-bandobast`, `sec.health.duty-roster`, `sec.mfg.shift`.
- **Boundary:** **IN** for rosters, approvals and the seat link. **OUT** for payroll computation and advanced optimisation, which can be an optional worker.

### N09 Statutory registers and numbering

- **Covers:**
  - registers that law or manuals require, numbered without gaps per office and year: dak inward/outward, FIR, cash book, stock register, register of births and deaths, deed registration numbers, licence numbers, case numbers, cheque/receipt books;
  - registration offices (cooperatives, companies, MSMEs);
  - private numbering: invoice numbers under GST, which must be unique and sequential per financial year [U], and RERA registers.
- **Entities:**
  - `RegisterDefinition`: number format pattern (prefix, office code, fiscal year from C08, serial width), gap policy (gap-free vs unique-only), reset period and the columns, as an R78 data object;
  - `RegisterEntry`: append-only, with corrections made by countersigned "correction entries", never by update;
  - `NumberAllocation`;
  - `CertifiedCopy`: a signed extract.
- **Services:**
  - `allocate(registerKey)` inside the business transaction;
  - extract and certified copy with eSign (C16);
  - period close and freeze;
  - export to PDF/A for records (R61).
- **Concurrency:** gap-free numbering needs one counter row per (register, office, period).
  - The counter row is locked in a short transaction and allocated at commit.
  - The lock is ordered after L2 per VI.1.
  - Its lock-wait metric is published.
  - "unique-only" registers use a sequence and need no lock.
- **Packs enabled:** every pack that issues a number (N06, N04, N10 invoices). Also `gov.dak-register`, `gov.birth-death-registration` (as a façade where CRS is the system of record) and `gov.society-registration`.
- **Boundary:** **IN.** It is generic and small, but essential for legal validity.

### N10 Billing, demand, tariff and collection

- **Covers:**
  - property, water and professional tax;
  - trade-licence fees;
  - stamp duty;
  - royalty;
  - excise duty;
  - irrigation water rates;
  - circuit-house room rent;
  - hospital bills, packages and TPA;
  - utility and telecom bills (rating stays outside);
  - plot instalments;
  - fees for schools and hostels;
  - consultancy and time-and-material billing;
  - ad billing;
  - storage charges.
- **Entities:**
  - `Demand` or `Charge`: party (N14), object (property, entitlement, asset, booking), period, head of account (C05), amount, due date;
  - `Tariff` / `RateCard`: DMN, effective-dated per jurisdiction, so fee notifications change mid-flight the GP-19 way;
  - `Invoice` / `Bill`: number from N09; GST e-invoice through an IRP connector;
  - `Receipt` / `Collection`: channel (cash counter, gateway, challan, bank);
  - `Adjustment`: rebate, waiver, penalty or interest (DMN);
  - `Refund`;
  - `ArrearsBucket`;
  - `WriteOff`, which needs C02 powers.
- **Design choice:** a **receivable/demand account kind in `sangam-ledger`**, alongside fund/limit and quantity. The ledger engine then supplies:
  - posting;
  - ageing;
  - reconciliation, reusing `gov.challan-reconciliation`;
  - period close;
  - audit.

  Summarised journals go to the external GL (IFMS, ERP) through a connector. **The full GL stays outside, per D-22.**
- **Services:**
  - demand generation (bulk, one instance per item, GP-09);
  - bill generation (C11 templates);
  - collection posting from gateway, challan or bank feeds through the inbox (R38) with operationId;
  - part-payment allocation rules;
  - dunning and notices (UP-15-style campaigns);
  - the `sec.util.disconnection`-style enforcement hand-off;
  - refund through the approval core.
- **Packs enabled:**
  - `gov.property-tax`, `gov.water-charges`, `gov.trade-licence-fee`, `gov.royalty`, `gov.guest-house-billing`, `gov.instalment-demand`;
  - `sec.health.patient-billing`, `sec.edu.fees`, `sec.services.time-billing`, `sec.media.ad-billing`.
  - `sec.util.meter-to-cash` would be refactored onto N10.
- **Boundary:** **IN, as a demand/collection ledger plus tariff DMN.** OUT: telecom/utility *rating engines* at CDR scale, and the full GL / accounts receivable of an ERP.

### N11 Data-pipeline and batch-job orchestration

- **Covers:**
  - data-warehouse loads;
  - nightly hospital-chain feeds;
  - regulatory return generation;
  - treasury end-of-day bank scrolls;
  - IMD and observation pipelines;
  - MIS roll-ups;
  - DBT file generation.
- **Entities:**
  - `Pipeline`: a BPMN process with typed `data.*` tasks;
  - `Dataset` / `DatasetVersion`: availability events;
  - `Run`: parameters and backfill window;
  - `QualityGate`: DMN over metrics;
  - `Lineage` reference.
- **Services:**
  - typed connectors and workers for Airflow, Spark, dbt, JDBC SQL, SFTP/file, S3 and HTTP;
  - start on schedule or on dataset events (C10);
  - dependency waits;
  - DQ gates opening incidents (`up18`/`up10`);
  - backfill as an R64 bulk batch;
  - SLA (R79);
  - approvals for production releases (UP-11).
- **Packs enabled:** `data.pipeline-run`, `data.dq-incident`, `data.backfill`, `data.regulatory-return`, `data.access-request` (exists as UP-05/UP-15).
- **Boundary:** **THIN IN.** Sangam orchestrates, approves and audits. Data engines, storage and transformation code stay outside, which D-22 requires. **No new module:** connectors go in `sangam-integration` and the pack family in `sangam-packs`.

### N12 IoT / telemetry-triggered processes

- **Covers:**
  - flood and dam telemetry;
  - CPCB/SPCB emission sensors;
  - smart-city command centres;
  - discom SCADA and outage alarms;
  - telecom NOC alarms;
  - plant condition monitoring;
  - cold-chain temperature;
  - ambulance AVL.
- **Planned already:** R83 and UP-21, both at Stage E.
- **Additions:**
  - `Device` / `Sensor` as a registry type linked to N03 assets and N13 locations;
  - `Threshold` / `Rule` as DMN;
  - `AlarmEpisode` with de-duplication, debounce, auto-close on clear, and an operator-acknowledgement SLA.
- **Recommendation:** move a **bounded subset** to Stage D:
  - MQTT and HTTP ingestion through the inbox;
  - per-device debounce;
  - episode → process start or message;
  - back-pressure per R40.

  Stream analytics stays out.
- **Boundary:** **IN (thin).** SCADA control loops and historians stay outside.

### N13 GIS and spatial data

- **Covers:**
  - land parcels and mutation;
  - forest compartments and FRA claims;
  - works and roads;
  - canals;
  - PMAY/MGNREGA geo-tagging;
  - SVAMITVA;
  - building permission;
  - mining leases;
  - routing a complaint to the ward or police station whose polygon contains it;
  - nearest-unit dispatch;
  - collections territories;
  - delivery zones.
- **Entities:**
  - `Geometry` as a typed attribute of any registry object, stored as GeoJSON plus a bounding box and a geohash for portability;
  - `AdminBoundary` / `JurisdictionLayer`: wards, villages, police stations, beats, circles, versioned in C05 and linked to org units so that "office by location" works;
  - `Layer` reference to an external GIS (WMS/WFS).
- **Services:**
  - point-in-polygon jurisdiction resolution for routing (a FEEL function `jurisdictionAt(lat, lon, layer)`);
  - distance and nearest-N;
  - geofence check for N05;
  - an overlap check (for example, a new lease against protected forest), done by a GIS worker for heavy geometry.
- **Database neutrality:** spatial types differ across PostgreSQL/PostGIS, Oracle, SQL Server and MySQL. The default therefore stores portable GeoJSON with bounding-box indexes and evaluates in Java with a JTS-class library; licence to verify, since JTS is EPL-2.0/EDL-1.0 [U]. Native spatial indexes are an optional per-dialect optimisation (A-15 style).
- **Boundary:** **THIN IN** for geometry as master data, jurisdiction resolution and geofencing. **OUT** for map rendering, cadastral editing and imagery: GeoServer, Bhuvan, state GIS and NIC GIS services are reached through connectors.

### N14 Party / beneficiary registry with de-duplication

- **Covers:**
  - beneficiaries (DBT, pensions, ration cards, scholarships, MGNREGA job cards, ICDS);
  - farmers and animals (NDLM);
  - patients (master patient index);
  - applicants, licensees, taxpayers, contractors and vendors;
  - bank customers and gold-loan customers;
  - prisoners;
  - ex-servicemen.
- **Entities:**
  - `Party`: person or organisation;
  - `Identifier`: Aadhaar held only as a vault reference token (R50), plus PAN, ABHA, ration-card number, employee id and others;
  - `Relationship`: household, family, guardian, nominee;
  - `Address` with an N13 geometry;
  - `ConsentRecord` / `PurposeTag` (R53);
  - `MatchCandidate` and `MergeEvent`, with unmerge.
- **Services:**
  - deterministic plus configurable fuzzy matching. The default rules match on name, date of birth and an identifier; a probabilistic matcher can be plugged in as a worker;
  - golden record with field-level survivorship rules;
  - household roll-up for eligibility, so that DMN can count members;
  - eligibility snapshot attached to a sanction, so that a later change does not alter an approved decision (VI.4 rule 5);
  - de-duplication across schemes (GP-16 scrutiny);
  - DPDP masking by purpose.
- **Boundary:** **IN, as master data.** D-22: clinical records stay in the EHR. The registry holds identity, demographics and links only, and it can mirror an external registry (state family database, AgriStack, ABHA) through a connector.

### N15 Targets, indicators, milestones and periodic returns

- **Covers:**
  - monthly progress reports (physical and financial);
  - scheme MIS (HMIS, UDISE+, PRABANDH-style plan versus achievement);
  - project milestones (works, highways, MPLADS);
  - statutory periodic returns (mining, labour, RERA quarterly, regulatory returns);
  - KPI targets for analytics.
- **Entities:**
  - `Indicator`: formula, unit and roll-up rule, in C05;
  - `Target`: by period and org unit, allocated down the hierarchy with an approval;
  - `ReportingPeriod`;
  - `ReturnDefinition`: a C03 form version plus validation DMN;
  - `ReturnSubmission`: per office per period, state DUE → SUBMITTED → VALIDATED → ACCEPTED/RETURNED → FROZEN;
  - `Milestone`: planned and actual dates, evidence from N05.
- **Services:**
  - campaign start per period (GP-30) as one instance per office (GP-09);
  - defaulter lists and reminders (C15);
  - consolidation up the hierarchy;
  - variance alerts;
  - freeze and re-open with reason;
  - feeding C14 dashboards from the read model.
- **Packs enabled:** `gov.monthly-progress-report`, `gov.scheme-target-allocation`, `gov.project-milestones`, `gov.statutory-return`, `sec.realestate.rera-quarterly`, `sec.bank.regulatory-return`.
- **Boundary:** **IN.** It is a generic data-collection and approval capability. Dashboards stay C14.

### N16 Meetings, committees, legislative questions and references

- **Covers:**
  - review meetings with action-taken reports;
  - DPC, purchase, appraisal and expert committees (PARIVESH EAC);
  - board meetings;
  - Parliament/Assembly questions and assurances;
  - VIP and MP/MLA references.
- **Entities:**
  - `Meeting`: committee, venue booking via N01, quorum through the existing `sangam-core-committee`;
  - `AgendaItem`: linked to files, cases and applications;
  - `Minutes`: draft → approved → circulated, with eSign optional;
  - `Decision`;
  - `ActionItem`: owner seat, due date and an SLA;
  - `Question`: a case type on N04 with a statutory clock;
  - `Assurance`;
  - `Reference`: source dignitary, subject and reply SLA.
- **Packs enabled:** `gov.review-meeting`, `gov.committee-meeting`, `gov.legislative-question`, `gov.assurance`, `gov.vip-reference`, `sec.corp.board-meeting`.
- **Boundary:** **IN**, built mostly as packs on N04, N01 and C02, with only the small entity set above.

### N17 Dispatch, trips and field-unit tracking

- **Covers:**
  - police PCR and Dial-112 response;
  - fire;
  - 108 ambulances;
  - disaster response;
  - PDS doorstep delivery;
  - process-serving (summons);
  - discom and telecom crews;
  - last-mile delivery;
  - phlebotomy;
  - school transport;
  - government vehicle trips and logbooks.
- **Entities:**
  - `Unit`: a vehicle, team or person, linked to N03 and N08;
  - `UnitStatus`: AVAILABLE, ASSIGNED, EN_ROUTE, ON_SCENE, RETURNING, OFF_DUTY;
  - `Job` / `Incident`;
  - `Trip` / `Leg`: start and end, odometer, purpose;
  - `PositionFix`: from N12 AVL feeds;
  - `ProofOfService`: from N05.
- **Services:**
  - candidate units by distance and skill (N13, R82);
  - offer, accept, decline and timeout;
  - re-dispatch;
  - status SLA (response time);
  - vehicle logbook and trip approval (GX08);
  - fuel claims to N10/C06.
- **Boundary:** **IN** for job and unit state, assignment and evidence. **OUT** for computer-aided dispatch telephony, live maps and route optimisation: ERSS, TMS and fleet telematics are reached through connectors.

### N18 Physical item custody and tracking (chain of custody)

- **Covers:**
  - police malkhana case property;
  - court exhibits;
  - food, drug and water samples;
  - laboratory specimens and blood units;
  - CSSD instrument sets;
  - EVMs/VVPATs;
  - exam papers;
  - seized excise and GST goods;
  - physical files and measurement books (GP-53);
  - original bank guarantees;
  - museum artefacts;
  - gold-loan packets;
  - library books;
  - lot genealogy in manufacturing;
  - parcels.
- **Entities:**
  - `TrackedItem`: serialised; label (barcode, QR or RFID); seal number; description; link to its case, sample or order;
  - `CustodyEvent`: from and to custodian (seat or person), location, time, condition, seal intact yes/no, and the signatures of both parties;
  - `Container`: for nesting (a bag in a box in a room);
  - `DisposalOrder`: destruction, return or auction, with authority from C02.
- **Services:**
  - scan-to-handover in the N05 app, which requires both parties;
  - a custody gap check (alert when an item has no custodian);
  - a periodic verification campaign (GP-55);
  - a chain-of-custody report, signed with C16 and fit for court;
  - genealogy queries (which lots made which batch).
- **Boundary:** **IN.** It is a generic registry plus events, and is small.

---

## 9. Proposed platform modules

| Module | Capabilities | Type | Builds on | D-22 verdict |
|---|---|---|---|---|
| **M1 `sangam-resources`** | N01 booking and allotment, N07 queue/token, N08 rostering | New module | C04, C05, C12, R35 resource lease, IX.5 seats (duty → seat) | IN (capacity calendar and ledger) |
| **M2 `sangam-ledger` (extend)** | N02 quantity ledger, N10 demand/receivable ledger | Extension of the in-progress module: two account kinds beside fund/limit | C06 posting, encumbrance and period close | IN (generic ledgers; full GL stays outside) |
| **M3 `sangam-billing`** | N10 tariff/rating via DMN, bill and invoice generation, collection posting, dunning, e-invoice connector | New thin module over M2 | M2, N09, C11, C09 | IN (thin) |
| **M4 `sangam-registry`** | N06 entitlements, N09 registers and numbering, N14 party registry, N03 asset register, N18 custody | New module: one "registered object" kernel (typed schema per R78, lifecycle state machine, numbering, validity, verification, merge, custody events) with five registry kinds | C05, R78 data objects, C11, C16, R19 masks | IN (master data and registries) |
| **M5 `sangam-case`** | N04 case file, N16 meetings/questions/references | New module over CMMN | C01 CMMN, ports `links/*`, M1 calendars, M4 parties | IN |
| **M6 `sangam-field`** | N05 offline field capture (mobile/PWA client + sync API), N17 dispatch and trips | New module + client app | C03 forms, R38 inbox, R41 idempotency, M7, R82 | IN (dispatch telephony and maps stay outside) |
| **M7 `sangam-geo`** | N13 geometry type, jurisdiction layers, point-in-polygon routing, geofences, GIS connectors | New thin module | C05, C04 (layer ↔ org unit), C09 | THIN IN (rendering and editing stay outside) |
| **M8 `sangam-programme`** | N15 indicators, targets, returns, milestones | New module | C03, C05, C14 read model, GP-30 campaigns | IN |
| (no module) `sangam-flowable-ports` events | N12: bring a bounded R83 subset (MQTT/HTTP ingestion, debounce, alarm episodes) from Stage E to D | Extension | C10, R40 | THIN IN |
| (no module) `sangam-integration` `data.*` connectors + `data.*` packs | N11 pipeline orchestration | Extension | C09, R64, R79 | THIN IN (engines stay outside) |
| (packs only) `maint.*`, `entitlement.*`, `field.*`, `queue.*`, `roster.*`, `data.*` and the new `gov.*`/`sec.*` packs listed in section 8 | Domain behaviour as configuration | Packs | M1–M8 | Packs (ADR-D-20) |

**Order of work (proposal).** The order follows the ranking and the rule that government comes first.

- **Wave 1:** M1 (N01), M2 + M3 (N02, N10) and M4's entitlement and party kinds (N06, N14). This is the top five excluding GIS. Together these unlock:
  - circuit and guest houses, quarters, halls and vehicles;
  - stores, drugs and PDS;
  - ULB, transport, registration, excise and mining revenue;
  - licence renewal;
  - beneficiary schemes;
  - IHMS front office, pharmacy and billing;
  - bank branches.
- **Wave 2:** M7 (N13), M6 field capture (N05), M4 assets, registers and custody (N03, N09, N18), M8 (N15).
- **Wave 3:** M6 dispatch (N17), M1 rostering (N08), M5 (N04, N16), N12 subset, N11 connectors, M1 queue (N07).

**What stays outside (integrate, do not build)**, per D-22:
- clinical EHR/EMR and PACS imaging;
- core banking ledgers and card/payment switches;
- the full general ledger and ERP accounts receivable;
- WMS slotting and picking optimisation;
- MRP and scheduling solvers (optional solver worker only);
- telecom/utility rating at CDR or interval scale;
- SCADA control and historians;
- CAD/telephony for emergency dispatch;
- GIS rendering, editing and imagery;
- data-processing engines and storage (Spark, dbt, warehouses);
- examination question banks and evaluation engines;
- learning management;
- hotel channel managers;
- video surveillance and analytics.

Each of these appears in the catalogue only as a connector target.

**The owner's named examples, after Waves 1–2:**

| Example | Approval half (today) | Operational half (after M1–M8) | Outside |
|---|---|---|---|
| IFMS (GS01, GC18, GX02) | P1, budget re-appropriation, challan reconciliation, pension, salary, C06 ledger | N10 receipts/demands, N15 returns, N11 treasury end-of-day files | Full GL/treasury of record (connector) |
| IHMS / hospital (GS10, PH01–PH12) | `sec.health.*`, prior authorisation, ABDM consent | N01 OPD/OT/bed, N07 queue, N02 pharmacy/stores, N10 billing, N03 biomedical equipment, N08 duty roster, N18 samples/CSSD, N17 ambulances, N14 master patient index | Clinical EHR, LIS/RIS/PACS devices |
| Bank management (PB01–PB07) | `sec.bank.*` (KYC, loans, collections, payments, AML, disputes) | N07 branch tokens, N01 lockers, N02/N18 currency chest and security stationery, N05 field collection, N03 ATM/premises, N15/N11 regulatory returns | Core banking ledger, payment switches |
| Circuit house / guest house (GX09, GS03, GS29) | Approval of requests and VIP override through C02 | N01 rooms by entitlement class, waitlist and bump; N10 tariff and bill; N02 linen and consumables; N08 staff duty | None |

---

## 10. Items to verify before relying on them

| # | Item | Why it matters | Where |
|---|---|---|---|
| V1 | Exact number of Union ministries and departments (First Schedule, AoB Rules, current amendment) [U] | Catalogue completeness claim | S2 |
| V2 | e-Kranti 44-MMP table by name and the 13/17/14 vs 15/17/12 split [U] | System list for 3.1/3.2 | S3 |
| V3 | Per-state department lists (IGOD state pages) for 3–5 sample states [U] | Normalised state types in 3.2 | S1 |
| V4 | Names and scope of systems marked [U] in sections 3–4 (e-Sampada scope, dam-safety system, drug-licensing systems, assurance monitoring, mining returns) | Row accuracy, not capability findings | S5 |
| V5 | GST rule on invoice serial numbering per financial year [U] | N09 gap policy default | CGST Rules |
| V6 | Licence of the geometry library (JTS EPL-2.0/EDL-1.0) and of the solver candidate (Timefold, Apache 2.0) [U] | No non-OSI dependency rule | Library sites |
| V7 | Whether randomised, risk-based inspection selection is required under the relevant central and state schemes [U] | N05 random-selection feature | Labour/industry department notices |

---

## 11. Counts

- **Catalogue:** 198 rows in total:
  - 52 central;
  - 55 state department types;
  - 24 cross-cutting;
  - 67 private systems in 21 sectors.
- **Existing capabilities:** 16 (C01–C16).
- **Missing capabilities:** 18 (N01–N18), each with a design sketch in section 8.
- **Proposed new modules:** 8 (M1–M8), plus extensions of `sangam-ledger` (M2), `sangam-flowable-ports` (N12) and `sangam-integration` (N11).
- **New pack names proposed in section 8:** about 60. They are names only; nothing is generated.
- **Candidate requirement ids:** if adopted, N01–N18 would become requirements R85–R102 in SANGAM.md X.1. None of these ids exists in the documents yet.
