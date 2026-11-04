# INTERNATIONAL-PFM: generic public financial management requirements, mapping to Sangam, and country profile sketches

Workstream: INTERNATIONAL-PFM research (Sangam). Date of this pass: **2026-11-05**. Status: research only; no code; nothing committed.

**Purpose.** ADR-D-20…D-23 (SANGAM.md X.0e) require that any country's finance ministry or treasury can adopt Sangam **through configuration only**. ADR-D-23 names "international public financial management (IMF GFS/COFOG, IPSAS, World Bank FMIS patterns)" as one of the first jurisdiction packs. This document:
- turns the international standards into generic requirements PFM-nnn;
- maps each requirement onto Sangam's generic capabilities (config profile hierarchy, classification registry, limit/fund ledger, document-type catalogue, approval chains, packs);
- sketches seven country profiles;
- lists what the generic model still lacks.

It reuses the structure of `IFMS-CENTRAL-REQUIREMENTS.md` (IFC-nnn) and `IFMS-STATES-REQUIREMENTS.md` (IFS-nnn, profile layers L0–L5).

**Pass limits (read this first).** The pass was cut short when the integrator asked every workstream to finish at once (2026-11-05). In addition:
- pefa.org returned **HTTP 403** to automated fetches;
- the agent researching Kenya, Indonesia, the Philippines and Bangladesh could not be started (concurrent-agent limit reached).

As a result, much of the standards detail and all four of those country profiles rest on the author's background knowledge, and are marked **[U]**. Treat every [U] value as a configuration placeholder that must be confirmed from the primary text before it seeds a pack (the same rule as IFC §0).

---

## 0. Method, evidence levels and conventions

- **Evidence levels** (column "Ev."):
  - **P** = primary page or document read in this pass;
  - **S** = official source identified by search (title, date and structure seen in an official snippet), full text not read;
  - **[U]** = unverified: background knowledge, a secondary source, or a blocked page.
- **Seen date:** 2026-11-05 for every source in §1 unless stated otherwise.
- **Quoting:** none beyond names of documents and terms; all content is paraphrased.
- **Map column:** which Sangam generic capability realises the requirement:

  | Code | Capability (ADR-D-20) | Meaning here |
  |---|---|---|
  | **PH** | Config profile hierarchy | Layered, versioned, effective-dated profile (international → country → subnational → entity → office) |
  | **CR** | Classification / master-data registry | Segmented chart of accounts, code lists, crosswalks, versions |
  | **LF** | Limit / fund ledger | Budget authority, release, commitment, payment balances; availability checks |
  | **DT** | Document-type catalogue | Commitment, payment order, invoice, warrant, release and other document types with their fields, checks and life cycle |
  | **AC** | Approval chains | Configurable maker/checker/approver templates over the approval core |
  | **PK** | Packs | BPMN/DMN/form bundles (`intl.pfm.*`, `gov.*`, country `profile.*`) |
  | **LC** | Locale | Currency, fiscal calendar, number format, time zone, language |
  | **CN** | Connector | Integration with an external IFMS, GL, central bank or payment rail (ADR-D-22) |

- **Gap column ("Gap"):** **—** = the generic model as specified covers it; **E** = needs an extension of an existing capability; **N** = needs something new (see §6).

---

## 1. Source register

| Key | Source | Ev. | URL |
|---|---|---|---|
| G1 | IMF, *Government Finance Statistics Manual 2014* (GFSM 2014); appendix on classification codes (revenue 1, expense 2, transactions in assets and liabilities 3; expense also classifiable by COFOG) | S | https://imf.org/external/Pubs/FT/GFS/Manual/pdf/app4.pdf |
| G2 | UNSD classification detail page for COFOG; UNCEISC 2023 slides: GFSM update planned, aligned with SNA update; COFOG among the coordinated topics | S | https://unstats.un.org/unsd/classifications/Family/Detail/1031 ; https://unstats.un.org/unsd/classifications/Meetings/UNCEISC2023/Section_09_GFSM-Update-slide-deck.pdf |
| G3 | UN, *Classification of the Functions of Government* (COFOG 1999), Statistical Papers Series M No. 84: 10 divisions / groups / classes | [U] | (via G2) |
| I1 | IPSASB, *Financial Reporting under the Cash Basis of Accounting* (Cash Basis IPSAS), revised Nov 2017; effective 1 Jan 2019; Part 1 mandatory, Part 2 encouraged; positioned as a step toward accrual | S | https://www.ipsasb.org/news-events/2017-11/ipsasb-issues-revised-cash-basis-ipsas ; https://www.ifac.org/system/files/publications/files/Cash-Basis-IPSAS-2017.pdf |
| I2 | IPSASB *Handbook of International Public Sector Pronouncements* (accrual IPSAS 1–50; incl. IPSAS 4 FX, IPSAS 24 budget information, IPSAS 47 Revenue, IPSAS 48 Transfer Expenses) | [U] | https://www.ipsasb.org |
| P1 | PEFA Secretariat, *PEFA 2016 Framework*: 7 pillars, 31 indicators, 94 dimensions; multi-dimension scoring by weakest-link (M1) or average (M2) | S (PDF 403) | https://www.pefa.org/node/119 ; https://www.pefa.org/sites/pefa/files/resources/downloads/PEFA_2016_Framework_Final_WEB_0.pdf |
| P2 | PEFA supplementary frameworks: gender-responsive PFM (2020) and climate-responsive PFM (2020) | [U] | https://www.pefa.org |
| T1 | IMF WP/10/143, Pattanayak & Fainboim, *Treasury Single Account: Concept, Design and Implementation Issues* (June 2010, 45 pp.) | S | https://www.imf.org/en/Publications/WP/Issues/2016/12/31/Treasury-Single-Account-Concept-Design-and-Implementation-Issues-23927 |
| W1 | World Bank, Dener, Watkins & Dorotinsky, *Financial Management Information Systems: 25 Years of World Bank Experience* (2011) | [U] | https://openknowledge.worldbank.org |
| W2 | World Bank, Hashim, *A Handbook on Financial Management Information Systems for Government* (2014); IMF, Hashim & Piatti, FMIS diagnostic framework (2016) | [U] | — |
| C1 | IMF FAD technical notes on commitment controls (e.g. Tandberg & Pavešić-Škerlep, 2009) and on cash management | [U] | https://www.imf.org |
| US | Fiscal Service CARS, PAM, G-Invoicing, Do Not Pay, USSGL pages; OMB A-11 §120 (Aug 2025); GAO appropriations-law resources; 31 USC 1552 | P (detail in §5.1) | https://fiscal.treasury.gov/cars/ ; https://fiscal.treasury.gov/pam/ ; https://fiscal.treasury.gov/g-invoice/ ; https://fiscal.treasury.gov/dnp/ ; https://fiscal.treasury.gov/ussgl/ ; https://www.whitehouse.gov/wp-content/uploads/2025/08/s120.pdf ; https://www.gao.gov/legal/appropriations-law/resources ; https://www.law.cornell.edu/uscode/text/31/1552 |
| UK | HM Treasury *Supply Estimates: a guidance manual* (2026); OSCAR in-year dataset guidance; Common Chart of Accounts; FReM 2026-27; *Managing Public Money* (Apr 2026); WGA collection | P (detail in §5.2) | https://www.gov.uk/government/publications/supply-estimates-guidance-manual/supply-estimates-a-guidance-manual-2026 ; https://assets.publishing.service.gov.uk/media/68cab75a995dfd01bff0c010/OSCAR_in-year_dataset_guidance.pdf ; https://www.gov.uk/government/publications/common-chart-of-accounts ; https://www.gov.uk/government/collections/government-financial-reporting-manual-frem ; https://www.gov.uk/government/publications/managing-public-money ; https://www.gov.uk/government/collections/whole-of-government-accounts |
| BR | Tesouro Nacional SIAFI page; Lei 4.320/1964 (Câmara consolidated text); Tesouro LRF page | P (detail in §5.3) | https://www.gov.br/tesouronacional/pt-br/siafi ; https://www2.camara.leg.br/legin/fed/lei/1960-1969/lei-4320-17-marco-1964-376590-normaatualizada-pl.html ; https://www.gov.br/tesouronacional/pt-br/execucao-orcamentaria-e-financeira/lei-de-responsabilidade-fiscal |
| KE, ID, PH, BD | Not researched online in this pass | [U] | — |

---

## 2. The international reference model (finding)

### 2.1 GFSM 2014 and COFOG (classification)

- **Top level** (G1, S): 1 Revenue, 2 Expense, 3 Transactions in assets and liabilities. Positions and other economic flows (holding gains, volume changes) are separate flow types.
- **Revenue** [U]: 11 Taxes, 12 Social contributions, 13 Grants, 14 Other revenue.
- **Economic classification of expense** (21 is S, the rest [U]): 21 Compensation of employees, 22 Use of goods and services, 23 Consumption of fixed capital, 24 Interest, 25 Subsidies, 26 Grants, 27 Social benefits, 28 Other expense.
- **Assets and liabilities** [U]: 31 Nonfinancial assets (fixed assets, inventories, valuables, nonproduced assets), 32 Financial assets, 33 Liabilities.
- **Financial instruments** [U], used for 32 and 33 at the 4th digit:
  - 3x01 Monetary gold and SDRs;
  - 3x02 Currency and deposits;
  - 3x03 Debt securities;
  - 3x04 Loans;
  - 3x05 Equity and investment-fund shares;
  - 3x06 Insurance, pension and standardised-guarantee schemes;
  - 3x07 Financial derivatives and employee stock options;
  - 3x08 Other accounts receivable/payable.
- **Counterparty sector** for financial assets and liabilities (G1, S): domestic/external, and then by sector.
- **Institutional coverage** [U]: general government (budgetary central government, extrabudgetary units, social-security funds), state and local government, and public corporations for the wider public sector.
- **COFOG** (G3 [U]): three levels (division 2 digits, group 3, class 4), 10 divisions:
  - 01 General public services
  - 02 Defence
  - 03 Public order and safety
  - 04 Economic affairs
  - 05 Environmental protection
  - 06 Housing and community amenities
  - 07 Health
  - 08 Recreation, culture and religion
  - 09 Education
  - 10 Social protection
- **Versioning signal** (G2, S): the IMF plans a GFSM update aligned with the SNA update, with COFOG among the coordinated topics. **Consequence:** GFS and COFOG must be loaded as **versioned reference code lists** (`GFSM2014`, a successor, `COFOG1999`, a successor) with crosswalks between versions, never as constants.
- **Basis:** GFS is accrual-based, and countries usually derive GFS by **mapping (bridging)** their national chart of accounts, not by coding transactions directly in GFS [U].

### 2.2 IPSAS

- **Cash Basis IPSAS** (I1, S): revised 2017, effective 2019.
  - Part 1 (mandatory): a statement of cash receipts and payments, and the entity's accounting policies.
  - Part 2 (encouraged): additional disclosures. The 2015–17 review covered consolidation, external assistance and third-party payments. Where the external-assistance and third-party-payment disclosures now sit (Part 1 or Part 2) is **[U]**; check the text.
  - The IPSASB sees cash basis as a **step toward accrual**.
- **Accrual IPSAS** (I2 [U]), the items that drive platform features:
  - **IPSAS 24**, presentation of budget information: budget versus actual on the **budget's own basis**, with reconciliation to the financial statements.
  - **IPSAS 4**, foreign exchange: functional currency, transaction-date rate, period-end translation of monetary items, recognition of exchange differences.
  - **IPSAS 47 Revenue / IPSAS 48 Transfer expenses** (effective 2026 [U]): binding-arrangement analysis of grants and transfers.
  - **IPSAS 45**, property, plant and equipment, and **IPSAS 43**, leases: asset register and lease liabilities.
  - **IPSAS 41**, financial instruments.
  - **IPSAS 19**, provisions and contingencies.
- **Transition path** [U]: most developing countries go cash, then modified cash, then modified accrual, then accrual over 5–10+ years. Budget execution normally stays **cash or commitment based** even after the financial statements move to accrual. The platform therefore needs **two bases at once** for one transaction stream.

### 2.3 PEFA 2016: the requirement checklist for a PFM system

PEFA (P1) has **7 pillars, 31 indicators (PI) and 94 dimensions**. That structure is S; the indicator and dimension titles below are [U] because the PDF was blocked. An FMIS cannot score PEFA by itself, but many dimensions can be **evidenced only if** the system records the data. The "Platform evidence" column says what Sangam must capture.

| Pillar | PI | Title [U] | Platform evidence needed (→ PFM-nnn) |
|---|---|---|---|
| I Budget reliability | 1 / 2 / 3 | Aggregate expenditure outturn; expenditure composition outturn (by function, economic type, contingency); revenue outturn | Original budget vs outturn by every classification segment (PFM-040, 052) |
| II Transparency | 4 | Budget classification (GFS/COFOG consistency) | Crosswalk of the national CoA to GFS/COFOG (PFM-001–008) |
| | 5 | Budget documentation | Budget-document bundle (PFM-022) |
| | 6 | Operations outside financial reports | Extrabudgetary-unit registry (PFM-011) |
| | 7 | Transfers to subnational governments | Rule-based transfer allocation and timely notice (PFM-063) |
| | 8 | Performance information for service delivery | Programme/output/KPI attributes (PFM-009) |
| | 9 | Public access to fiscal information | Publication outputs (PFM-056) |
| III Assets and liabilities | 10 | Fiscal risk reporting | Contingent-liability / guarantee register (PFM-068) |
| | 11 | Public investment management (11.1 economic analysis, 11.2 selection, 11.3 costing, 11.4 monitoring) | Project appraisal and multi-year project costing (PFM-024) |
| | 12 | Public asset management (financial, nonfinancial, disposal) | Asset register links (PFM-067) |
| | 13 | Debt management (recording, approval, strategy) | Debt / guarantee approval chain; connector to the debt system (PFM-069) |
| IV Fiscal strategy and budgeting | 14 / 15 / 16 | Macro-fiscal forecasting; fiscal strategy; medium-term expenditure perspective (ceilings, alignment) | Multi-year ceilings (MTEF) in the ledger (PFM-021) |
| | 17 | Budget preparation (calendar, guidance/circular, submission) | Budget-calendar process (PFM-020) |
| | 18 | Legislative scrutiny (scope, procedures, timing, **rules for in-year adjustment by the executive**) | Virement / reallocation rules (PFM-027) |
| V Predictability and control in execution | 19 / 20 | Revenue administration; accounting for revenue (collections info, transfer to treasury, reconciliation) | Receipt pipeline into the TSA (PFM-045–047) |
| | **21** | Predictability of in-year allocation: 21.1 cash-balance consolidation; 21.2 cash forecasting; 21.3 information on commitment ceilings; 21.4 in-year adjustments | TSA view, cash plan, released ceilings with notice dates (PFM-030–033, 042) |
| | **22** | Expenditure arrears (stock, monitoring) | Payables ageing; due-date tracking (PFM-038) |
| | **23** | Payroll controls (personnel–payroll integration, changes, internal control, audit) | Payroll connector + change workflow (PFM-064) |
| | **24** | Procurement (monitoring, methods, public access, complaints) | Procurement packs (existing) + complaint workflow (PFM-065) |
| | **25** | Internal controls on non-salary spending: 25.1 segregation of duties; 25.2 **commitment controls**; 25.3 compliance with payment rules | SoD, commitment control, exception logging (PFM-034–037, 041) |
| | 26 | Internal audit | Audit-plan / finding workflow (existing AUDIT pack) |
| VI Accounting and reporting | **27** | Financial data integrity: 27.1 bank reconciliation; 27.2 suspense accounts; 27.3 advance accounts; 27.4 integrity processes (access, audit trail) | Reconciliation, suspense and advance ledgers; audit trail (PFM-048–051) |
| | **28** | In-year budget reports (coverage, timing, accuracy, including commitments) | Ledger reporting by stage, period close (PFM-052) |
| | **29** | Annual financial reports (completeness, submission, standards) | Year-end close and statement export (PFM-053–055) |
| VII External scrutiny | 30 / 31 | External audit; legislative scrutiny of audit reports | SAI access, audit-para and ATN workflow (existing AUDIT pack) (PFM-057) |

### 2.4 FMIS and Treasury Single Account patterns

- **TSA** (T1, S): consolidate government cash in a **unified account structure**, usually at the central bank, so that no spending unit holds idle balances outside treasury control. The paper describes alternative models for different country contexts. The models below are [U]:
  - **centralised:** a single account, with payments executed by the treasury;
  - **distributed:** spending units transact through zero-balance sub-accounts or commercial-bank accounts that sweep daily to the TSA.
  - The paper also covers sequencing and preconditions.
- **FMIS** (W1/W2 [U]):
  - **core modules:** budget execution, commitment management, accounts payable/receivable, cash management, GL and reporting, under a single chart of accounts;
  - **non-core:** budget preparation, payroll/HRMIS, procurement, debt, assets, revenue;
  - **success factors:** a unified CoA, a TSA, phased roll-out, and **centralised processing with a web front end**.
- **Implication for Sangam (ADR-D-22).** Sangam does not replace the FMIS GL. It runs the **control workflows**: release, commitment, verification, payment authorisation, reconciliation exceptions, virement. It keeps the **limit/fund ledger** as the authoritative availability check, or as a shadow of the FMIS when the FMIS is the authority. Both modes must be configurable (PFM-044).

### 2.5 The budget cycle and the expenditure chain (generic)

```
Budget cycle:  strategy / ceilings (MTEF) → circular → submissions → negotiation → executive proposal
               → legislative appropriation → execution (release, commitment, payment) → in-year reports / adjustments
               → year-end close → annual accounts → external audit → legislative audit scrutiny
Expenditure chain (local names vary; stages are configurable, some are optional):
  Appropriation ─► Apportionment / warrant / allocation ─► Release / allotment (with cash ceiling)
  ─► [Reservation / pre-commitment] ─► Commitment / obligation / encumbrance (PO, contract)
  ─► Verification / receipt / liquidation (goods/services received, invoice matched)
  ─► Payment order / voucher (certified) ─► Payment (TSA debit, rail) ─► Reconciliation (bank / central bank)
```

| Generic stage | US | UK | Brazil | Kenya [U] | Indonesia [U] | Philippines [U] | Bangladesh [U] | India (IFC/IFS) |
|---|---|---|---|---|---|---|---|---|
| Appropriation | Act of Congress (TAS/TAFS) | Supply Estimate + Supply & Appropriation Act | LOA + créditos adicionais | Appropriation Act (national), county Appropriation Acts | APBN law → DIPA | GAA | Appropriation Act | Appropriation Act / grant |
| Apportionment / allocation | OMB apportionment SF 132 (categories A/B/AB/C, footnotes) | HMT budget control totals (DEL/AME) | Decree of financial programming (cotas) | Exchequer requisition approved by the Controller of Budget | DIPA approval (DJPA/DJPb) | Allotment: GAA as allotment order (GAAAO) / SARO | Budget release / warrant | Allocation / release |
| Release / allotment | Agency allotment / suballotment (A-11 §150) | Departmental delegations | Credit and cash decentralisation | Exchequer issue to the spending account | Cash plan / ceiling | NCA (cash) | Release to the DDO | DDO allotment |
| Commitment | Obligation (budgetary USSGL 48xx/49xx) | Departmental PO (no statutory stage) | **Empenho** (nota de empenho; ordinary / estimative / global) | LPO / LSO in IFMIS | Komitmen / contract registration | ORS (Obligation Request and Status) | Commitment (iBAS++) | Sanction / liability register |
| Verification | Receipt and acceptance; certifying officer (31 USC 3325) | Goods received in the departmental ERP | **Liquidação** | Invoice and GRN matching | SPP (payment request) | Disbursement voucher | Bill verification | Bill pre-check |
| Payment order | Certified voucher → Fiscal Service | Department pays via Government Banking | **Ordem Bancária** | IFMIS payment voucher → TSA / IFMIS–CBK | **SPM** (payment order, spending unit) → **SP2D** (KPPN) | LDDAP-ADA via MDS bank | EFT advice | e-payment advice |
| Payment | PAM (ACH, check, wire); TGA at the Fed | Bacs / FPS / CHAPS from GBS accounts | SPB from the Conta Única | CBK / commercial bank | Bank Indonesia RKUN / partner banks | MDS government servicing banks | Bangladesh Bank BACH / BEFTN | e-Kuber / agency bank |

---

## 3. Generic requirements catalogue (PFM-001 …)

### A. Classification and chart of accounts

| ID | Requirement | Source | Ev. | Map | Gap |
|---|---|---|---|---|---|
| PFM-001 | Support a **segmented chart of accounts** with N independent segments, defined by the country: administrative/organisation, economic (object), functional, programme/activity, fund/source, geographic (location), project, and others. Each segment has its own width, hierarchy depth, code list and validity dates; no segment is hard-coded | W1/W2, P1 PI-4 | [U] | CR, PH | E (IFS GA-02 is single-hierarchy) |
| PFM-002 | Load **GFSM 2014** economic codes (revenue 1x, expense 2x, assets/liabilities 3x, financial instruments 3x0n) as a versioned **reference code list** (L0) | G1 | S/[U] | CR | — |
| PFM-003 | Load **COFOG** (10 divisions / groups / classes) as a versioned reference list, ready for the post-SNA-update revision | G2, G3 | S/[U] | CR | — |
| PFM-004 | **Crosswalks** (many-to-one, with effective dates) from the national economic and functional segments to GFS and COFOG, plus crosswalks between versions (old → new codes). Used for PEFA PI-4, GFS reporting and COFOG reporting | G1, P1 | [U] | CR | N (no crosswalk object) |
| PFM-005 | Validate combinations of segments (allowed cross-segment rules, e.g. this programme only with these economic codes; this fund only with these organisations) at document entry | W2 | [U] | CR + DMN | E |
| PFM-006 | Counterparty-sector attribute on financial-asset and liability transactions (domestic/external; sector) for GFS | G1 | S | CR | E |
| PFM-007 | Institutional coverage typing of entities: budgetary central government, extrabudgetary unit, social-security fund, state/regional, local, public corporation | G1, P1 PI-6 | [U] | PH, CR | E |
| PFM-008 | Classification masters are owned by named authorities (e.g. treasury owns the economic segment, budget office owns the programme segment). Changes go through an approval chain with a legal citation and effective date (as IFC-016/032) | IFC-032, IFS §6 | P (India) | CR, AC | E |
| PFM-009 | Programme-based budgeting attributes: programme → sub-programme → output, with KPIs and targets attached to the programme segment (PEFA PI-8, PI-16) | P1 | [U] | CR | E |
| PFM-010 | Tags across segments: gender, climate, SDG, poverty, disaster-risk, plus country tags (India TSP/SCSP equivalents). Multi-valued, with %-split support (e.g. 40 % climate-relevant) | P2 | [U] | CR | N (%-split tags) |
| PFM-011 | Registry of **extrabudgetary units and funds** with their reporting obligations | P1 PI-6 | [U] | PH | E |

### B. Budget formulation and authorisation

| ID | Requirement | Source | Ev. | Map | Gap |
|---|---|---|---|---|---|
| PFM-020 | Budget-calendar process: circular → ceilings → submissions → reviews → executive approval → legislature, with configurable dates and SLA timers per country | P1 PI-17 | [U] | PK, LC | E (new pack `intl.pfm.budget-preparation`) |
| PFM-021 | Multi-year ceilings (MTEF: budget year + 2 or more outer years) per organisation and programme, held in the ledger as a non-binding "ceiling" stage | P1 PI-16 | [U] | LF | N (ledger has no outer-year dimension) |
| PFM-022 | Budget-document bundle (proposal, annexes, fiscal-risk statement, citizens' budget) produced from the data | P1 PI-5, PI-9 | [U] | DT | E |
| PFM-023 | Appropriation import: the enacted appropriation becomes the top ledger stage with a **legal reference** (act, schedule, line). The level of legal control (vote, programme, economic line) is a profile setting | US, UK, BR | P | LF, PH | E |
| PFM-024 | Public-investment project lifecycle: appraisal, selection, multi-year costing, monitoring | P1 PI-11 | [U] | PK | E (IFC CH-5 analogue, generic) |
| PFM-025 | **Supplementary / additional appropriations** of several kinds, with their own approval routes (e.g. Brazil créditos suplementares / especiais / extraordinários; UK Supplementary Estimates; US supplementals) | BR art 40–41, UK | P | AC, LF | E |
| PFM-026 | **Contingency / emergency** instruments: Contingencies Fund advance (UK), extraordinary credit (BR), Contingency Fund (India), with repayment or regularisation tracking | UK, BR | P | LF, PK | E |
| PFM-027 | **Virement / reallocation rules** as DMN: allowed within a limit, banned across limits or across resource/capital, thresholds needing treasury or legislative approval, reporting to the legislature | UK manual, P1 PI-18.4, IFC-061 | P | DMN, AC | E (generalise REAPP) |
| PFM-028 | **Continuing resolution / vote on account / interim budget:** a formula-based temporary authority (e.g. a % of the prior year per month) that is replaced when the full appropriation passes | UK (Vote on Account), US | P/[U] | LF | N (formula-derived limits) |
| PFM-029 | **Several voted limits per appropriation** (UK: resource DEL, capital DEL, resource AME, capital AME, non-budget, Net Cash Requirement), including **net limits and negative limits** (income-offset) and **token** amounts | UK | P | LF | N (net/negative limits) |

### C. Budget execution and commitment control

| ID | Requirement | Source | Ev. | Map | Gap |
|---|---|---|---|---|---|
| PFM-030 | A **configurable chain of control stages** (2 to N) per country: appropriation, apportionment, allotment, sub-allotment, release, reservation, commitment, verification, payment order, payment. Each stage consumes the stage above, with a hard or soft check | §2.5, US A-11 §120/150, BR arts 58–65 | P | LF, PH | **N (core gap)** |
| PFM-031 | **Time-phased releases:** quarterly (US Category A, BR cotas) or monthly cash ceilings. A release above the plan needs an approval chain | US A-11 §120.13, BR art 47–50 | P | LF | E |
| PFM-032 | **Purpose-phased apportionment** (US Category B by programme/project/activity) and future-year categories (US Category C) | US | P | LF | E |
| PFM-033 | **Executable footnotes / conditions on authority:** e.g. "amount available only after event X" or "not before report Y is submitted". Expressed as DMN/FEEL conditions attached to a limit; breach equals a limit breach | US A-11 §120 | P | LF + DMN | N |
| PFM-034 | **Commitment control:** no commitment without available released authority. The check is at commitment, not only at payment. Commitments are recorded as encumbrances that reduce availability | C1, P1 PI-25.2, BR art 59–60 | P/[U] | LF | — (encumbrance exists) / E (stage-aware) |
| PFM-035 | Commitment **types**: ordinary (fixed amount), **estimative** (amount not known, adjusted later), **global** (multi-instalment contract with several verifications). Over- and under-run adjustments are audited | BR art 60 | P | LF, DT | E |
| PFM-036 | **Multi-year commitments** (contracts spanning fiscal years): recording future-year obligations against outer-year ceilings, with legal limits on them | C1, P1 PI-16 | [U] | LF | N (needs PFM-021) |
| PFM-037 | **Revenue-shortfall throttling:** a global or targeted block on new commitments (Brazil *limitação de empenho*) applied by an order to many limits at once, with effective dating and later release | BR LRF | P | LF, AC | N (bulk limit freeze order) |
| PFM-038 | **Arrears tracking:** payables ageing (due date vs payment date), stock and flow of arrears, with configurable arrear definition (e.g. > 30/60/90 days) | P1 PI-22 | [U] | LF, DT | E |
| PFM-039 | **Breach handling:** a limit breach is a legal event in some countries (US Antideficiency Act report to the President and Congress; UK Excess Vote). The platform must open a case of a configured type with the notified parties | US GAO, UK | P | PK, AC | E |
| PFM-040 | Ledger reporting by every segment and stage: original, revised, released, committed, verified, paid, available (PEFA PI-1/2/28) | P1 | [U] | LF | E |
| PFM-041 | **Segregation of duties** across stages: the person or seat who commits may not verify; the verifier may not authorise payment; configurable by country | P1 PI-25.1 | [U] | AC | — (SoD exists) |
| PFM-042 | **Commitment-ceiling notice:** spending units get their ceilings a configured number of days in advance; the notice date is recorded (PEFA PI-21.3) | P1 | [U] | LF | E |
| PFM-043 | **Year-end treatment per stage, configurable:** (a) lapse (UK annuality); (b) carry-forward of unspent authority by rule; (c) carry-over of commitments as payables of the next year (**Brazil restos a pagar**, *processados* and *não processados*, with expiry dates); (d) **multi-year / no-year availability** with expired → cancelled phases (US: fixed accounts close 5 years after expiry, 31 USC 1552) | UK, BR art 36, US | P | LF, PH | **N (core gap)** |
| PFM-044 | **Authority mode** per tenant: Sangam ledger is authoritative (standalone), or a **shadow** of the external FMIS that is the authority (check via connector, reconcile daily) | ADR-D-22, W1 | P (ADR) | LF, CN | E |

### D. Treasury, cash and payments

| ID | Requirement | Source | Ev. | Map | Gap |
|---|---|---|---|---|---|
| PFM-045 | **TSA topology** as data: main account at the central bank, sub-accounts (ledger-only or real), zero-balance accounts at commercial banks, sweep schedules, exceptions (donor accounts, revenue collection accounts) | T1 | S | PH, LF | N (account-topology model) |
| PFM-046 | Revenue collection into the TSA: collection channels, transfer deadlines (e.g. T+1), reconciliation of collections against bank credits (PEFA PI-20) | P1 | [U] | PK, CN | E (generalise CHALLAN) |
| PFM-047 | Cash forecasting and cash plan: monthly/weekly forecasts from releases and commitments, updated in-year (PI-21.2) | P1, C1 | [U] | LF, PK | E |
| PFM-048 | Payment-order document with certification by an authorised officer (US certifying officer personally liable; BR ordenador de despesa). The certifier's **delegation and personal liability** are recorded | US 31 USC 3325, BR art 64 | P/[U] | DT, AC | E |
| PFM-049 | **Payee integrity checks before payment:** payee master validation, debarment/exclusion and deceased lists (US Do Not Pay), duplicate payments, bank-account validation | US DNP | P | DMN, CN | E |
| PFM-050 | **Payment rails as connector bindings:** central-bank RTGS, ACH/EFT, cheque, card, mobile money, foreign-currency wire. The payment state machine includes rejection, return, re-issue and UNKNOWN_OUTCOME (existing P1 semantics) | All profiles | P | CN | — |
| PFM-051 | **Reconciliation and integrity** (PEFA PI-27): daily bank/TSA reconciliation; **suspense** accounts with ageing and clearance workflow; **advance** (imprest, travel, cash advance; BR *suprimento de fundos*) accounts with retirement deadlines | P1, BR art 65 | [U]/P | LF, PK | E |
| PFM-052 | **Period close:** monthly close with lock, in-year reports within a configured number of weeks of period end (PI-28.2) | P1 | [U] | PK, LF | E |
| PFM-053 | **Intragovernmental transactions:** buyer/seller agreements and orders between entities, settled without external cash (US G-Invoicing → IPAC; BR intra-SIAFI) | US, BR | P/[U] | DT, PK | N (two-sided document) |

### E. Accounting basis and reporting

| ID | Requirement | Source | Ev. | Map | Gap |
|---|---|---|---|---|---|
| PFM-054 | **Accounting basis** is a profile setting per entity and per period: cash, modified cash, modified accrual, accrual (IPSAS, national GAAP based on IPSAS, FASAB, FReM/IFRS). Transition dates are effective-dated | I1, I2, US, UK, BR | S/P | PH | E |
| PFM-055 | **Dual tracks:** the same event updates the budgetary (control) ledger and produces accounting entries on the financial-reporting basis; a **budget-to-accounting reconciliation** is produced (IPSAS 24; US SFFAS 53 [U]) | I2, US | [U] | LF, CN | N (Sangam is not the GL; needs event mapping) |
| PFM-056 | Fiscal transparency outputs: in-year reports, annual statements, citizens' budget, open data in machine-readable form | P1 PI-9 | [U] | PK | E |
| PFM-057 | External audit access: read-only SAI role across all tenants of the jurisdiction, with audit-trail export (PEFA PI-30) | P1 | [U] | AC, PH | — (AUDIT pack, read roles) |
| PFM-058 | **Consolidation inputs:** each entity submits returns to a central consolidation (UK OSCAR monthly; US CARS/GTAS [U]; WGA). Sangam produces the submission and tracks its approval | UK | P | PK, CN | E |

### F. Donor-funded projects and external assistance

| ID | Requirement | Source | Ev. | Map | Gap |
|---|---|---|---|---|---|
| PFM-059 | **Fund/source segment** distinguishes domestic revenue, loans and grants by donor and financing agreement, with on-budget / off-budget and on-treasury / off-treasury flags | I1 (external assistance), W1 | [U] | CR | E |
| PFM-060 | **Financing agreement** master: donor, instrument (loan/grant), currency, amount, eligible categories and expenditure %, co-financing ratio, closing date, disbursement methods (advance/designated account, direct payment, reimbursement, special commitment) | W1 [U] | [U] | CR, LF | N (agreement-level limits in a foreign currency) |
| PFM-061 | **Donor-eligibility checks** at commitment and payment: eligible category, % financed, retroactive financing window, procurement method required by the donor | [U] | [U] | DMN | E |
| PFM-062 | **Designated / project accounts** (often in foreign currency at the central bank or a commercial bank) with replenishment workflows (withdrawal applications, statements of expenditure), and donor reporting (interim financial reports) | [U] | [U] | LF, PK, CN | N |

### G. Subnational, payroll, procurement, assets, debt

| ID | Requirement | Source | Ev. | Map | Gap |
|---|---|---|---|---|---|
| PFM-063 | **Intergovernmental transfers:** formula-based allocation, release schedule, conditions and reporting; recipients are separate tenants (Kenya counties, Indonesia regions, India states) | P1 PI-7 | [U] | PH, PK | E (tenant referral, IFS §6.4) |
| PFM-064 | **Payroll controls:** personnel-to-payroll link, change workflow, monthly payroll as one commitment and one payment batch, payroll audit (PI-23) | P1 | [U] | PK, CN | E (generalise SALARY) |
| PFM-065 | **Procurement complaints** and public procurement data (PI-24.3/24.4) | P1 | [U] | PK | E |
| PFM-066 | Procurement thresholds and methods per country as DMN (generalise IFC D3) | P1 PI-24 | [U] | DMN | — |
| PFM-067 | Asset register link: capital commitments create asset records; disposal approval chain (PI-12) | P1, I2 | [U] | PK, CN | E (ASSETS pack) |
| PFM-068 | **Guarantee / contingent-liability register** and fiscal-risk reporting (PI-10) | P1 | [U] | LF, PK | N |
| PFM-069 | **Debt operations** approval chain (borrowing authority, limits, guarantees); debt recording stays in the debt system (CS-DRMS/DMFAS [U]) via a connector | P1 PI-13 | [U] | AC, CN | E |

### H. Multi-currency and locale

| ID | Requirement | Source | Ev. | Map | Gap |
|---|---|---|---|---|---|
| PFM-070 | **Functional currency per tenant**; documents in a transaction currency; ledger stores **both** amounts and the rate used | I2 IPSAS 4 | [U] | LF, LC | N (ledger is single-currency) |
| PFM-071 | **Exchange-rate source** as a connector (central-bank rates), rate type (spot, budget rate, month-average, donor rate), and the effective date rule (transaction, commitment or payment date) | I2 | [U] | CN, CR | N |
| PFM-072 | **FX re-measurement of commitments:** an open foreign-currency commitment is re-measured at a configured rate; the difference adjusts availability (and may breach a limit) | [U] | [U] | LF | N |
| PFM-073 | **Limits in a currency other than the functional one** (donor agreements, missions abroad, IFC-229) with dual-currency availability | IFC-229 | P (India) | LF | N |
| PFM-074 | **Fiscal calendar** per tenant: start month (Jan, Apr, Jul, Oct), period count (12 + adjustment periods 13/14), mid-year transitions; legislative calendar dates | US, UK, BR, KE [U] | P/[U] | LC | E |
| PFM-075 | Multi-language legal text and documents (UK en, BR pt, ID id, PH en/fil, BD bn/en, KE en/sw) [U for language policies] | — | [U] | LC | — (BCP-47 extension, IFS §6.3) |

### I. Governance, accountability and law

| ID | Requirement | Source | Ev. | Map | Gap |
|---|---|---|---|---|---|
| PFM-076 | **Independent approval authorities outside the executive:** e.g. Kenya's Controller of Budget approves withdrawals from public funds [U]; the authority is a separate tenant or seat with its own SLA, and a refusal blocks release | KE Constitution Art 228 [U] | [U] | AC, PH | E (cross-tenant approval) |
| PFM-077 | **Accounting Officer direction** (UK): an officer may request a written ministerial direction, which is recorded, copied to the SAI and the PAC, and normally published | UK MPM | P/[U] | PK, AC | E |
| PFM-078 | **Fiscal-rule checks:** debt and personnel-cost limits, end-of-mandate restrictions (Brazil LRF art 42 [U]) as DMN over aggregated ledger data, with alerts and blocks | BR LRF | P/[U] | DMN, LF | N (aggregate-based rules) |
| PFM-079 | **Legal citation and versioning** of every rule and limit (as IFC-016), including **country-specific legal phases** (e.g. appropriation expiry) | IFC-016 | P | PH | — |
| PFM-080 | **PEFA evidence pack:** a report set that produces the indicator evidence listed in §2.3 directly from Sangam data | P1 | S | PK | E (new pack `intl.pfm.pefa-evidence`) |

---

## 4. Mapping onto Sangam's generic capabilities

### 4.1 Profile layers (extends IFS §6.1 to international scope)

```
L0  international reference   GFSM 2014 codes, COFOG 1999, IPSAS basis catalogue, PEFA 2016 indicator list,
                              ISO 4217 currencies, ISO 3166 countries                         (read-only, shipped)
L1  regime preset             e.g. PRESIDENTIAL_APPORTIONMENT (US-like) | WESTMINSTER_SUPPLY (UK/KE/BD/IN-like)
                              | CIVIL_LAW_EMPENHO (BR/LatAm-like) | DIPA_TREASURY_OFFICE (ID-like)
                              | ALLOTMENT_NCA (PH-like); fixes the default stage chain and year-end policy
L2  country profile           national CoA segments + crosswalks to L0, fiscal calendar, currency, laws,
                              stage chain overrides, TSA topology, rails, institutions (SAI, budget office, CoB)
L3  subnational / entity      state, county, region, municipality, extrabudgetary unit (inherits L2)
L4  department / ministry     delegations, seats, local overlays (today's GP-37)
L5  office / spending unit    office rows (e.g. KPPN, PAO, DDO, satker)
Resolution: most specific wins; L0 is not overridable; L1 sets defaults only.
```

India becomes `L2 = IN` with L3 = states (as in IFS), so the India packs fit this model without change.

### 4.2 Capability mapping summary

| Capability | What PFM needs from it | Requirements |
|---|---|---|
| **Config profile hierarchy (PH)** | L0–L5 above; regime presets; accounting-basis and authority-mode switches; institutions as tenants or seats | 007, 011, 023, 043, 044, 054, 063, 076 |
| **Classification registry (CR)** | N-segment CoA with per-segment owners and versions; **crosswalk objects**; combination rules; %-split tags; donor agreements as master data | 001–011, 059, 060, 071 |
| **Limit / fund ledger (LF)** | **Stage chain of configurable depth**; time and purpose phasing; executable conditions; multi-year and outer-year dimensions; year-end policies; net/negative limits; **dual currency**; bulk freeze orders; authoritative or shadow mode | 021, 023, 028–040, 042–045, 047, 051, 060, 062, 068, 070–073 |
| **Document-type catalogue (DT)** | Generic document types with a stage binding: release order, warrant, commitment (3 sub-types), receipt/verification, invoice, payment order, payment advice, withdrawal application, intragovernmental order | 022, 035, 038, 048, 053 |
| **Approval chains (AC)** | Templates per stage; SoD across stages; cross-tenant approvals (Controller of Budget); certifying-officer liability | 008, 025, 027, 039, 041, 048, 076, 077 |
| **Packs (PK)** | `intl.pfm.*` packs (below) plus country `profile.*` packs | 020, 024, 026, 046, 047, 052, 056, 058, 062–065, 080 |
| **Locale (LC)** | Fiscal calendar with adjustment periods, currency, language | 070, 074, 075 |
| **Connector (CN)** | FMIS GL, central bank (TSA statements, FX rates), payment rails, payroll, debt, consolidation systems | 044, 046, 049, 050, 055, 058, 069, 071 |

### 4.3 Generic document-type catalogue (seed; fields are illustrative, a data contract, not code)

| docType | Stage bound | Key fields | Typical local names |
|---|---|---|---|
| `APPROPRIATION_LINE` | appropriation | legal ref, limit type, period of availability, net/gross | TAS line; Estimate limit; LOA line; DIPA line [U] |
| `APPORTIONMENT` / `WARRANT` | apportionment | category (time/purpose/future), footnote conditions | SF 132; GAAAO [U]; warrant |
| `RELEASE_ORDER` | release / allotment | period, cash vs authority, notice date | allotment; SARO / NCA [U]; cota; exchequer issue [U] |
| `COMMITMENT` | commitment | subtype ORDINARY / ESTIMATIVE / GLOBAL, currency, multi-year schedule, supplier | obligation; empenho (NE); ORS; LPO [U] |
| `VERIFICATION` | verification | receipt evidence, invoice match, instalment no. | receiving report; liquidação; SPP [U] |
| `PAYMENT_ORDER` | payment order | certifier, payee checks, rail, value date | certified voucher; OB; SPM→SP2D; LDDAP-ADA [U] |
| `PAYMENT_ADVICE` / `RETURN` | payment | rail reference, status, UNKNOWN_OUTCOME | PAM schedule; bank file |
| `ADVANCE` / `RETIREMENT` | advance ledger | purpose, deadline, retirement | imprest; suprimento de fundos |
| `IG_AGREEMENT` / `IG_ORDER` | intragovernmental | two parties, settlement | G-Invoicing GT&C/order; intra-SIAFI |
| `WITHDRAWAL_APPLICATION` | donor account | financing agreement, category, SOE | donor WA [U] |
| `LIMIT_FREEZE_ORDER` | any | scope (limits matched by query), % or amount, effective dates | limitação de empenho |
| `VIREMENT` | appropriation / allotment | from/to, rule outcome, approvals | virement; re-appropriation |

### 4.4 Generic approval-chain templates

| Chain | Steps (seat types; configurable) |
|---|---|
| PFM-CH-1 Release | Budget office proposal → treasury cash check → (independent authority, e.g. CoB [U]) → release order |
| PFM-CH-2 Commitment | Requester → budget holder (availability check) → procurement (if needed) → commitment approver (delegation DMN) |
| PFM-CH-3 Verification & payment | Receiver → verifier (≠ committer) → certifying officer (liability recorded) → treasury/disbursing officer → rail → reconciliation |
| PFM-CH-4 Virement | Proposer → finance unit → treasury / budget office (by DMN) → legislature report list |
| PFM-CH-5 Year-end | Close commitments → carry-over decisions per policy → lapse → open next year |
| PFM-CH-6 Donor replenishment | Project unit → treasury → (donor no-objection, external) → withdrawal application → receipt in designated account |
| PFM-CH-7 Breach | Breach detected → investigation → report to configured authorities (e.g. President/Congress/GAO; PAC) |

### 4.5 Packs suggested (names indicative)

| Pack | Covers |
|---|---|
| `intl.pfm.ref` (L0 data only) | GFSM 2014, COFOG, IPSAS basis list, PEFA indicator list, ISO currency |
| `intl.pfm.budget-preparation` | PFM-020–024 |
| `intl.pfm.budget-execution` | Stage chain, releases, commitments, virement, freeze orders, breach (PFM-025–044) |
| `intl.pfm.treasury` | TSA, cash plan, payment order, payee checks, reconciliation, suspense, advances (PFM-045–053) |
| `intl.pfm.year-end` | Year-end policies, close, statements export (PFM-043, 052–055) |
| `intl.pfm.donor-projects` | PFM-059–062 |
| `intl.pfm.pefa-evidence` | PFM-080 |
| Country profiles | `profile.us-federal`, `profile.uk-central`, `profile.br-federal`, `profile.ke-national` (+ county preset), `profile.id-central`, `profile.ph-national`, `profile.bd-central` |

---

## 5. Country profile sketches

Each sketch lists only the switches over its L1 preset. Facts for US, UK and BR come from the research pass (P where pages were read, [U] where not). KE, ID, PH and BD are **entirely [U]** in this pass.

### 5.1 USA federal (`profile.us-federal`, preset PRESIDENTIAL_APPORTIONMENT)

- **Systems:**
  - Fiscal Service **CARS**, the system of record for agency accounting reporting; every transaction carries a **TAS-BETC** classification (P).
  - **PAM** pays most federal payments, except foreign-currency ones (P).
  - **G-Invoicing** handles intragovernmental orders, mandatory for new orders from FY2023 (P).
  - **Do Not Pay** screens payees (P).
  - Agencies run their own core financial systems [U].
- **CoA:** USSGL, with budgetary and proprietary accounts (4000 = budgetary, 5000 revenue, 6000 expense) and attributes (P). TAS segments (agency, availability period, availability type X = no-year, main account, sub-account) [U].
- **Stages:** appropriation → OMB **apportionment** (SF 132; categories A by quarter, B by programme/project/activity, AB, C future years; footnotes binding) → **allotment / sub-allotment** under agency funds-control rules (A-11 §150) → obligation → certified voucher → disbursement (P for A-11 §120 and §150).
- **Breach:** a breach at any level is an Antideficiency Act violation (31 USC 1341, 1517) and must be reported (P).
- **Certification:** the certifying officer is personally liable (31 USC 3325, 3528) [U].
- **Cash:** the Treasury General Account is held at the Federal Reserve [U]. Rails are ACH, check and wire [U].
- **Basis:** budgetary accounting plus accrual under FASAB [U].
- **Fiscal year:** 1 Oct–30 Sep; currency USD.
- **Distinctive switches:**
  - period of availability (one-year, multi-year, no-year);
  - expired → cancelled after 5 years (31 USC 1552, P);
  - executable apportionment footnotes;
  - automatic apportionment and adjustment authority;
  - continuing-resolution formulas.

### 5.2 UK central government (`profile.uk-central`, preset WESTMINSTER_SUPPLY)

- **Systems:**
  - HM Treasury **OSCAR (II)** for consolidation and monthly outturn (P);
  - departmental ERPs [U];
  - **Government Banking** (in HMRC) [U].
- **CoA:** the **Common Chart of Accounts** (2013, updated 2018) (P). OSCAR dimensions: organisation, control budget (DEL/AME/non-budget), programme vs administration, segment, sub-segment, economic category, resource/capital, PESA economic group aligned to COFOG/ESA 2010 (P).
- **Authorisation:** **Supply Estimates** voted by the Commons (P), with:
  - limits for resource and capital DEL, resource and capital AME, non-budget, and the **Net Cash Requirement**;
  - ambits;
  - a Main Estimates Act and an Anticipation and Adjustments Act (Supplementaries, Excesses, Vote on Account);
  - virement within limits only;
  - a token £1,000 to keep a limit alive;
  - an **Excess Vote** for any breach.
- **Accountability:** the **Accounting Officer** is personally answerable to Parliament; the ministerial direction process applies (P/[U]).
- **Cash:** the Consolidated Fund at the Bank of England; supply drawn down to Government Banking accounts; unused cash surrendered (P).
- **Basis:** accrual under the **FReM** (IFRS adapted), 2026-27 edition (P). Budgets are also on a resource (accrual) basis (P). Whole of Government Accounts (P).
- **Fiscal year:** 1 Apr–31 Mar; currency GBP.
- **Distinctive switches:** strict annuality, several limits per Estimate, net and negative limits, retained income vs CFER payover, no statutory commitment stage (an optional stage in the chain).

### 5.3 Brazil federal (`profile.br-federal`, preset CIVIL_LAW_EMPENHO)

- **Systems:** **SIAFI** (STN) for budget, financial, asset and accounting execution of the federal administration (P); SIOP for the budget [U].
- **Classification** [U]:
  - esfera;
  - institutional (órgão / UO);
  - functional (função / subfunção);
  - programmatic (programa / ação);
  - **natureza da despesa**, 6 digits (category, GND, modalidade, elemento);
  - **fonte de recursos**.
- **Chart of accounts:** PCASP in 8 classes (patrimonial 1–4, budgetary 5–6, control 7–8) [U].
- **Stages** (Lei 4.320/1964, P):
  - LOA plus créditos adicionais (suplementares, especiais, extraordinários);
  - quarterly **cotas**;
  - **empenho** (ordinary, estimativa or global; no expense without prior empenho; nota de empenho);
  - **liquidação** (verification against the contract and proof of delivery);
  - **ordem de pagamento** / **Ordem Bancária**, paid from the **Conta Única**.
- **Unity of treasury:** Lei 4.320 art 56 (P). The Conta Única is held at the Banco Central [U].
- **Fiscal rules:** LRF (LC 101/2000) limits on personnel and debt, **limitação de empenho**, RGF reports (P); the end-of-mandate restriction in art 42 [U].
- **Basis:** for the budget, revenue on a cash basis and expense on commitment (art 35, P). Patrimonial accounting is accrual under MCASP/NBC TSP, converging on IPSAS [U].
- **Fiscal year:** calendar year; currency BRL.
- **Distinctive switches:**
  - **restos a pagar** (processados / não processados, with expiry);
  - three commitment types;
  - quotas plus bulk freeze;
  - fonte on every transaction;
  - intra-SIAFI transfers;
  - suprimento de fundos advances.

### 5.4 Kenya (`profile.ke-national`, preset WESTMINSTER_SUPPLY + CoB) [U throughout]

- **Law and systems:**
  - PFM Act 2012 under the 2010 Constitution;
  - National Treasury runs an **Oracle-based IFMIS**, which includes e-procurement;
  - 47 county governments, each with its own budget and appropriation, on the same IFMIS.
- **Controller of Budget:** an independent office that **approves withdrawals** from the Consolidated Fund and the county revenue funds (Constitution Art 228) and reports on budget implementation. This is PFM-076.
- **Classification:** a Standard Chart of Accounts with administrative, economic (GFS-based), programme, funding source, location and project segments.
- **Basis:** the Public Sector Accounting Standards Board (PSASB) issues IPSAS-based standards; national government has been on cash-basis IPSAS and is moving to accrual.
- **TSA:** at the Central Bank of Kenya.
- **Fiscal year:** 1 Jul–30 Jun; currency KES.
- **Distinctive switches:** two government levels as tenants with an equitable-share transfer formula (PFM-063); exchequer requisition → CoB approval → exchequer release chain.

### 5.5 Indonesia (`profile.id-central`, preset DIPA_TREASURY_OFFICE) [U throughout]

- **Law and institutions:** Law 17/2003 on State Finance and Law 1/2004 on State Treasury. The Ministry of Finance's DG Treasury (**DJPb**) runs about 170+ regional treasury offices (**KPPN**).
- **Systems:** **SPAN**, the central treasury and budget system (since c. 2015), and **SAKTI**, the application used by spending units (*satker*).
- **Execution document:** the **DIPA** authorises each satker.
- **Payment chain:** SPP (payment request) → **SPM** (payment order, issued by the satker) → **SP2D** (disbursement order, issued by the KPPN) → payment from the State General Cash Account (**RKUN**) at Bank Indonesia through partner banks. This is a TSA.
- **Classification:** the Standard Chart of Accounts (*Bagan Akun Standar*, BAS).
- **Basis:** accrual government accounting standards (SAP, PP 71/2010).
- **Fiscal year:** calendar year; currency IDR.
- **Subnational:** regions have their own budgets (APBD) and systems (e.g. SIPD).
- **Distinctive switches:** centralised payment at the treasury office (a **two-organisation** payment chain: the satker orders and the KPPN disburses); one DIPA per satker as the top execution limit; revisions of DIPA as a document type.

### 5.6 Philippines (`profile.ph-national`, preset ALLOTMENT_NCA) [U throughout]

- **Institutions:** the Department of Budget and Management (**DBM**) releases allotments under the **GAA**, enacted by Congress. Since about 2018 the GAA itself serves as the allotment order for most items, and a **SARO** (Special Allotment Release Order) covers the rest. DBM also issues **NCAs** (Notices of Cash Allocation) that set cash ceilings.
- **Payment chain:** agencies obligate through the **ORS** (Obligation Request and Status), pay on a disbursement voucher, and issue the **LDDAP-ADA** (list of due and demandable accounts payable, advice to debit account) to an **MDS** government servicing bank. The Bureau of the Treasury (**BTr**) manages the TSA and cash, and is modernising through the **BTMS** (Budget and Treasury Management System).
- **Classification:** **UACS** (Unified Accounts Code Structure), with funding source, organisation, location, programme (PAP) and object code segments.
- **Basis:** accrual under **PPSAS** (IPSAS-based). The Commission on Audit (**COA**) is both the SAI and the issuer of the government accounting manual.
- **Fiscal year:** calendar year; currency PHP.
- **Distinctive switches:** **separate authority and cash ceilings** (allotment vs NCA) as two ledger stages; validity of appropriations (one-year or two-year, depending on the GAA) as a period-of-availability setting.

### 5.7 Bangladesh (`profile.bd-central`, preset WESTMINSTER_SUPPLY) [U throughout]

- **Systems:** the Finance Division runs **iBAS++** (integrated Budget and Accounting System), covering budget preparation, execution, payroll and accounting, with the Controller General of Accounts (**CGA**) for accounts.
- **Classification:** the **Budget and Accounts Classification System (BACS)**, introduced in the late 2010s with a GFS-aligned economic segment and institutional, functional, geographic, fund and project segments.
- **TSA and payments:** a TSA at Bangladesh Bank; payments go directly to payees by **EFT** (BEFTN) from iBAS++, including G2P social-protection payments, reformed under the **SPFMS** programme.
- **Audit:** by the Comptroller and Auditor General of Bangladesh.
- **Fiscal year:** 1 Jul–30 Jun; currency BDT.
- **Distinctive switches:** centralised EFT from the treasury to the beneficiary's bank or mobile account; a large development budget (ADP) with donor-funded projects (PFM-059–062).

### 5.8 Comparison of profile switches

| Switch | US | UK | BR | KE [U] | ID [U] | PH [U] | BD [U] |
|---|---|---|---|---|---|---|---|
| FY start | Oct | Apr | Jan | Jul | Jan | Jan | Jul |
| Stage chain depth (appr → payment) | 6–7 | 3–4 (no statutory commitment) | 6 | 5–6 | 5 | 6 (allotment + NCA) | 5 |
| Separate cash ceiling stage | Cat. A quarterly | NCR (net) | cotas | exchequer release | cash plan | **NCA** | release |
| Period of availability | one/multi/no-year | annual | annual + RAP | annual | annual | 1–2 years | annual |
| Year-end commitments | carried within availability | lapse | **restos a pagar** | lapse/payables | lapse/payables | per GAA validity | lapse |
| Independent pre-approval of withdrawals | — | — | — | **Controller of Budget** | — | — | — |
| Central disbursing | PAM | none (departments) | Conta Única OB | TSA/IFMIS | KPPN SP2D | MDS banks | iBAS++ EFT |
| Accounting basis (reports) | FASAB accrual + budgetary | FReM accrual | MCASP accrual + budget cash/commitment | IPSAS cash → accrual | SAP accrual | PPSAS accrual | cash (modified) |
| Breach = statutory report | ADA | Excess Vote | LRF sanctions | [U] | [U] | [U] | [U] |

---

## 6. What is still missing from the generic model (gap list)

These are ranked by how many countries each blocks. All of them are **data/config capabilities** (ADR-D-20), not country code.

1. **N-stage limit/fund ledger (PFM-030).** Today's encumbrance/release pattern, plus the India `budgetControl.chain`, assumes a short, fixed chain.
   - **Needed:** a declarative stage graph per profile (appropriation → apportionment → allotment → sub-allotment → release → commitment → verification → payment order → payment). For each stage: consumption rules, hard/soft checks, phasing (time and purpose), and which document type moves it.
   - **Blocks:** all 7 countries profiled and India.
2. **Period-of-availability and year-end policies (PFM-043).**
   - **Needed:** a per-limit availability window (one-year, multi-year, no-year), an expired → cancelled lifecycle, and configurable carry-over (lapse, carry-forward, restos a pagar, payables roll-over) with expiry timers.
   - **Blocks:** US, BR, PH, and India-style lapse.
3. **Multi-currency ledger (PFM-070–073).** The ledger is single-currency.
   - **Needed:** a transaction/functional amount pair, an FX-rate connector and rate types, re-measurement of open commitments, and limits denominated in a foreign currency.
   - **Blocks:** donor projects everywhere, missions abroad (IFC-229) and FX payments.
4. **Classification crosswalks and N-segment CoA (PFM-001, 004, 005).**
   - **Needed:** crosswalk objects (national → GFS/COFOG, version → version), cross-segment combination rules, and percentage-split tags (PFM-010).
   - **Blocks:** GFS/COFOG reporting, PEFA PI-4, and classification reforms (e.g. India's FY 2027-28 object-head change, IFC-034).
5. **Executable conditions on limits (PFM-033) and bulk freeze orders (PFM-037).**
   - **Needed:** limits that carry DMN/FEEL availability conditions, and one order that freezes or releases many limits matched by a query.
   - **Blocks:** US footnotes, BR limitação de empenho, and in-year cuts in any country.
6. **Net, negative, formula-derived and outer-year limits (PFM-021, 028, 029, 036).**
   - **Needed:** limits that can be net of income or negative, limits computed from a formula (continuing resolution, vote on account), and outer-year (MTEF) dimensions for multi-year commitments.
   - **Blocks:** UK, US, and any country with an MTEF.
7. **Donor financing-agreement model (PFM-060–062).**
   - **Needed:** agreement-level limits (by category, % financed, currency, closing date), designated accounts, withdrawal applications and donor reports.
   - **Blocks:** most low- and middle-income adopters (KE, BD, PH, ID [U]).
8. **TSA account topology (PFM-045).**
   - **Needed:** a data model for the main account, sub-accounts, zero-balance accounts, sweeps and exceptions, used by reconciliation and the cash plan.
9. **Dual-basis event mapping (PFM-055).**
   - **Needed:** a mapping from each ledger event to accounting entries on the configured basis (cash / accrual), exported to the GL connector, plus a budget-to-accounting reconciliation report. Sangam stays outside the GL (ADR-D-22).
10. **Cross-tenant and external approvals (PFM-076, 077, CH-6).**
    - **Needed:** an approval step owned by another tenant or an external body (Controller of Budget, donor no-objection, ministerial direction), with SLA and refusal semantics.
    - This builds on the tenant referral in IFS §6.4.
11. **Two-sided intragovernmental documents (PFM-053).**
    - **Needed:** one document with a buyer and a seller tenant, both of which must approve, and settlement without external cash.
12. **Aggregate fiscal-rule checks (PFM-078) and contingent-liability register (PFM-068).**
    - **Needed:** DMN evaluated over aggregated ledger data (debt ratio, personnel ratio, end-of-mandate rules), not only over a single document.
13. **Fiscal calendar extensions (PFM-074).** Adjustment periods 13/14, mid-year FY changes, and the legislative calendar as data.
14. **Regime presets (L1, §4.1).** A new profile layer kind, shipping the default stage chain, year-end policy and chain templates per budget tradition, so that a new country starts from the nearest preset.

**Owner decisions surfaced:**
- **(a)** Is the limit/fund ledger **authoritative** or a **shadow** of the adopter's FMIS by default (PFM-044)?
- **(b)** Should multi-currency be in the first ledger release, given that donor projects and missions need it?
- **(c)** Confirm the L1 "regime preset" layer as a new profile layer.

---

## 7. Coverage counts

| Area | IDs | Count |
|---|---|---|
| A Classification | PFM-001–011 | 11 |
| B Budget formulation and authorisation | PFM-020–029 | 10 |
| C Execution and commitment control | PFM-030–044 | 15 |
| D Treasury, cash and payments | PFM-045–053 | 9 |
| E Accounting and reporting | PFM-054–058 | 5 |
| F Donor-funded projects | PFM-059–062 | 4 |
| G Subnational, payroll, procurement, assets, debt | PFM-063–069 | 7 |
| H Multi-currency and locale | PFM-070–075 | 6 |
| I Governance and law | PFM-076–080 | 5 |
| **Total** | | **72** |

| Gap marker | Count |
|---|---|
| — covered by the specified generic model | 8 (PFM-002, 003, 041, 050, 057, 066, 075, 079) |
| E extension of an existing capability | 43 |
| N new capability | 21 |

(PFM-034 is counted under E; it is marked "— / E" because encumbrance exists but is not stage-aware.)

| Evidence | Count of requirements whose source is wholly or partly P/S | Wholly [U] |
|---|---|---|
| | 34 | 38 |

---

## 8. Open points and [U] items for the next pass

1. **PEFA 2016 Framework PDF** (403 to automated fetches). Confirm the indicator and dimension titles in §2.3, the scoring methods, and the 2020 gender and climate supplements. Retry from a browser or the PEFA Secretariat.
2. **GFSM 2014 full text:** chapter numbering, financial-instrument codes and sector codes. Check the status of the GFSM and COFOG revisions after the SNA update (G2).
3. **IPSAS:** the current handbook number range and the effective dates of IPSAS 46–48. Also, where the external-assistance and third-party-payment disclosures sit in the 2017 Cash Basis IPSAS (Part 1 or Part 2).
4. **World Bank / IMF FMIS and TSA guidance:** read W1, W2 and T1 in full for the TSA model taxonomy and the FMIS module list. Read the IMF technical notes on commitment controls and cash management (C1).
5. **USA:** TFM TAS chapter and FAST Book (TAS segment detail); A-11 §150 text; GTAS; PAM file formats.
6. **UK:** the Managing Public Money chapters on Accounting Officers and Treasury consent; Government Banking documentation; the CCoA code list.
7. **Brazil:** the MCASP/PCASP PDF (class mapping), Decreto 93.872/1986 art 68 (expiry of restos a pagar), IN STN 4/2004 (Ordem Bancária types), and the MTO 2027 natureza-da-despesa structure.
8. **Kenya, Indonesia, Philippines, Bangladesh: everything.** The next pass should read:
   - **Kenya:** the PFM Act 2012, Controller of Budget reports and the National Treasury SCOA;
   - **Indonesia:** DJPb pages (SPAN/SAKTI, DIPA, SPM/SP2D), PMK on BAS and PP 71/2010;
   - **Philippines:** DBM circulars on the GAA-as-allotment-order, SARO and NCA, BTr TSA/BTMS, the UACS manual, and the COA GAM/PPSAS;
   - **Bangladesh:** Finance Division iBAS++ and BACS manuals, and SPFMS programme documents.
   Also obtain the latest PEFA report for each country from pefa.org, to use as the evidence base.
9. **Donor procedures:** World Bank disbursement and financial-information guidelines (designated accounts, withdrawal applications, IFRs) as the reference for PFM-060–062.

*End of document. Nothing in this document has been committed.*
