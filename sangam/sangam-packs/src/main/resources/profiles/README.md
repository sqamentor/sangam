<!-- SPDX-License-Identifier: Apache-2.0 -->
# Government finance profiles (data): GOI, its departments and all 36 States / UTs

Domain knowledge as data (ADR-D-20). Read by `sangam-config` (`ProfileLoader.loadIndex(cl, "profiles/profiles.idx")`), resolved per tenant scope by `ProfileResolver`, used by the `gov.ifms.*` packs through the `config.*` and `ledger.*` connectors. Generated from the research documents (`spike/results/IFMS-CENTRAL-REQUIREMENTS.md`, `IFMS-STATES-REQUIREMENTS.md`, `IFMS-RESEARCH-DELTA.md`); after generation these files are the source and are edited as data (maker-checker through `ConfigAdminService`).

## Layout and layers

| File | Layer | Content |
|---|---|---|
| `layers.yaml` | - | the ordered layer model: international, regime, jurisdiction, category, government, department, office |
| `intl/INTL.yaml`, `intl/seeds/cofog-divisions.csv` | international | COFOG divisions (crosswalk target) |
| `regime/REGIME-WESTMINSTER-SUPPLY.yaml` | regime | default ledger stage chain and year-end lapse |
| `in/IN.yaml` | jurisdiction | locale (INR, lakh / crore grouping, April fiscal year, IST, calendars with 15:00 cut-off), the 15-digit head-of-account scheme (major / sub-major / minor locked), derivation (0/1 receipts, 2/3 revenue, 4/5 capital, 6/7 loans, 8000 Contingency Fund, 8001-8999 Public Account), crosswalk to COFOG, SNA-SPARSH constants, CSS sharing, payment-rail catalogue, unit types, public-account types |
| `in/presets/IN-PRESET-*.yaml` | category | GENERAL, NE-HIMALAYAN, UT-LEG, UT-NOLEG, UNION |
| `in/GOI.yaml` | government | Union profile: rulebooks (GFR 2017, DFPR 2024, RPR 2022, CAM 2024 ...), org model and registry (AoBR series 386: 94 business units), budget control (DFPR Rule 10 rule set), appraisal ladder, PAO chain, receipts, grants, establishment, accounts calendar, the RPR bill catalogue (~35 types) and chain templates |
| `in/goi/GOI.<DEPT>.yaml` | department | RAILWAYS, DEFENCE, POSTS, TELECOM, MEA (multi-currency), DAE, SPACE, MHA (CAPF), REVENUE |
| `in/states/<CODE>.yaml` | government | the 28 States and 8 UTs: systems, topology, rulebooks, rail, SNA-SPARSH hosting, per-state head-of-account scheme (`HOA_<CODE>`), statutory languages (scope, script, direction, effective date), procurement family, state forms, specials |
| `in/seeds/*.csv` | - | head-of-account major heads (partial sample), standard minor heads, object heads (pre-2027 list, closes 01-04-2027) |
| `profiles.idx` | - | classpath index of the profile documents |

A department tenant `RJ.PWD` resolves `INTL > REGIME > IN > IN-PRESET-GENERAL > RJ > RJ.PWD` (plus `RJ.PWD/<office>`). `[U]` values are listed under `placeholders` in each file; `ResolvedProfile.isPlaceholder(path)` and the validator report them.

## Coverage summary

Status: **C** = generic capability + data + pack; **D** = configured as data in the profile on a generic capability (no pack step needed, or an existing pack reads it); **P** = partly (placeholder values or part of the behaviour); **G** = gap.

| Set | Ids | C | D | P | G |
|---|---|---|---|---|---|
| IFC (central) | 258 | 160 | 84 | 10 | 4 |
| IFS cross-state | 78 | 46 | 31 | 0 | 1 |
| IFS state-specific | 253 | 89 | 164 | 0 | 0 |
| GA gaps | 30 | 14 | 0 | 15 | 1 |

## IFC-001 ... IFC-258

| Id | Generic capability | Data | Packs | St | Note |
|---|---|---|---|---|---|
| IFC-001 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | G | registry seed of the 94 business units not loaded as rows (counts and events in profile) |
| IFC-002 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-003 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-004 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-005 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-006 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-007 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | `gov.ifms.reappropriation` | C |  |
| IFC-008 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-009 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-010 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-011 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-012 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | `gov.ifms.gia-uc` | C |  |
| IFC-013 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-014 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-015 | config: profile `orgModel` (seat types, office tiers, registry events) + sangam-security seats / postings / delegation | `in/GOI.yaml#orgModel` | - | D |  |
| IFC-016 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D |  |
| IFC-017 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D |  |
| IFC-018 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D |  |
| IFC-019 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D |  |
| IFC-020 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D |  |
| IFC-021 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D |  |
| IFC-022 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D |  |
| IFC-023 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D |  |
| IFC-024 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D |  |
| IFC-025 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | `gov.ifms.loans-advances` | C |  |
| IFC-026 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | `gov.ifms.gpf` | C |  |
| IFC-027 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | D | [U] values are placeholders |
| IFC-028 | config: profile `rulebooks` with versions and citations; DMN rows carry ruleRef (GP-19 binding) | `in/GOI.yaml#rulebooks` | - | C |  |
| IFC-029 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-030 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-031 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-032 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-033 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-034 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-035 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | `gov.ifms.reappropriation` | C |  |
| IFC-036 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | `gov.ifms.reappropriation` | C |  |
| IFC-037 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-038 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-039 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-040 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-041 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | - | D |  |
| IFC-042 | config: classification registry (segmented scheme, master codes, derivation, combinations, correction slips, crosswalks) | `in/IN.yaml#classification; in/seeds/*.csv` | `gov.ifms.bill` | C |  |
| IFC-043 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.budget-formulation` | P | [U] values are placeholders |
| IFC-044 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.budget-formulation` | P | [U] values are placeholders |
| IFC-045 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.budget-formulation` | C |  |
| IFC-046 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.budget-allocation`, `gov.ifms.budget-formulation` | C |  |
| IFC-047 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.budget-allocation` | C |  |
| IFC-048 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.supplementary` | C |  |
| IFC-049 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.supplementary` | C |  |
| IFC-050 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.bill`, `gov.ifms.supplementary` | C |  |
| IFC-051 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.supplementary`, `gov.ifms.year-end-closing` | C |  |
| IFC-052 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.budget-formulation` | P | [U] values are placeholders |
| IFC-053 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.budget-formulation`, `gov.ifms.scheme-appraisal` | C |  |
| IFC-054 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.budget-formulation`, `gov.ifms.scheme-appraisal` | C |  |
| IFC-055 | ledger (APPROPRIATION stage) + budget-formulation / supplementary packs | `in/GOI.yaml#budgetControl` | `gov.ifms.budget-formulation` | P | [U] values are placeholders |
| IFC-056 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.budget-allocation` | C |  |
| IFC-057 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.bill`, `gov.ifms.budget-allocation` | C |  |
| IFC-058 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.bill` | C |  |
| IFC-059 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | - | D |  |
| IFC-060 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | - | D |  |
| IFC-061 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.reappropriation` | C |  |
| IFC-062 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.reappropriation` | C |  |
| IFC-063 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.reappropriation` | C |  |
| IFC-064 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.reappropriation` | C |  |
| IFC-065 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.reappropriation` | C |  |
| IFC-066 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.reappropriation` | C |  |
| IFC-067 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.reappropriation` | C |  |
| IFC-068 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.reappropriation` | C |  |
| IFC-069 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.surrender-lapse` | C |  |
| IFC-070 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.surrender-lapse` | C |  |
| IFC-071 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.budget-allocation` | C |  |
| IFC-072 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.budget-allocation` | C |  |
| IFC-073 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.supplementary` | C |  |
| IFC-074 | ledger: N-stage chain, commitment / encumbrance, hold queue, transfer rules, freeze, period close | `in/GOI.yaml#budgetControl; regime/REGIME-WESTMINSTER-SUPPLY.yaml#ledgers` | `gov.ifms.surrender-lapse` | C |  |
| IFC-075 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.bill` | C |  |
| IFC-076 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.scheme-appraisal` | C |  |
| IFC-077 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.bill` | C |  |
| IFC-078 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | - | D |  |
| IFC-079 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.bill` | C |  |
| IFC-080 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.scheme-appraisal` | C |  |
| IFC-081 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.scheme-appraisal` | C |  |
| IFC-082 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.scheme-appraisal` | C |  |
| IFC-083 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.scheme-appraisal` | C |  |
| IFC-084 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.scheme-appraisal` | C |  |
| IFC-085 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | `gov.ifms.bill` | C |  |
| IFC-086 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | - | D |  |
| IFC-087 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | - | D |  |
| IFC-088 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | - | D |  |
| IFC-089 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | - | D |  |
| IFC-090 | DMN delegation tables + core sanction / power guard | `in/GOI.yaml#appraisal, budgetControl` | - | D |  |
| IFC-091 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-092 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-093 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFC-094 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFC-095 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFC-096 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFC-097 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFC-098 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFC-099 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-100 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-101 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-102 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-103 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-104 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-105 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-106 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-107 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill`, `gov.ifms.loans-advances` | C |  |
| IFC-108 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-109 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.audit-para-settlement`, `gov.ifms.bill` | C |  |
| IFC-110 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-111 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-112 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFC-113 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.audit-para-settlement` | C |  |
| IFC-114 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-115 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-116 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-117 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-118 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-119 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-120 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-121 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-122 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-123 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | P | [U] values are placeholders |
| IFC-124 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-125 | config: document-type catalogue + approval-chain templates + number series; ledger encumber / consume | `in/GOI.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFC-126 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-127 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-128 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-129 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-130 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-131 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-132 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-133 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-134 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-135 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-136 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts`, `universal.billing-cycle` | C |  |
| IFC-137 | config: receipt rules in profile; receipts pack; connectors receipt.portal / treasury.scroll | `in/GOI.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFC-138 | config: payment-rail catalogue + rail DMN; core execution / reconciliation | `in/IN.yaml#paymentRails` | - | D |  |
| IFC-139 | config: payment-rail catalogue + rail DMN; core execution / reconciliation | `in/IN.yaml#paymentRails` | `gov.ifms.receipts` | C |  |
| IFC-140 | config: payment-rail catalogue + rail DMN; core execution / reconciliation | `in/IN.yaml#paymentRails` | `gov.ifms.monthly-accounts` | C |  |
| IFC-141 | config: payment-rail catalogue + rail DMN; core execution / reconciliation | `in/IN.yaml#paymentRails` | `gov.ifms.receipts` | C |  |
| IFC-142 | config: payment-rail catalogue + rail DMN; core execution / reconciliation | `in/IN.yaml#paymentRails` | - | D |  |
| IFC-143 | config: payment-rail catalogue + rail DMN; core execution / reconciliation | `in/IN.yaml#paymentRails` | - | D |  |
| IFC-144 | config: payment-rail catalogue + rail DMN; core execution / reconciliation | `in/IN.yaml#paymentRails` | `gov.ifms.sna-sparsh` | C |  |
| IFC-145 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-146 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-147 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-148 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-149 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-150 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-151 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-152 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-153 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-154 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-155 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-156 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-157 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.sna-sparsh` | C |  |
| IFC-158 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.sna-sparsh` | C |  |
| IFC-159 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.sna-sparsh` | C |  |
| IFC-160 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.sna-sparsh` | C |  |
| IFC-161 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.sna-sparsh` | C |  |
| IFC-162 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | - | C |  |
| IFC-163 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-164 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-165 | ledger (grant / SNA limits) + GIA-UC and SNA-SPARSH packs | `in/GOI.yaml#grants; in/IN.yaml#snaSparsh` | `gov.ifms.gia-uc` | C |  |
| IFC-166 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill` | C |  |
| IFC-167 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill` | C |  |
| IFC-168 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill` | C |  |
| IFC-169 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill` | C |  |
| IFC-170 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D |  |
| IFC-171 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill`, `gov.ifms.gpf` | C |  |
| IFC-172 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill` | C |  |
| IFC-173 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D | [U] values are placeholders |
| IFC-174 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D |  |
| IFC-175 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D |  |
| IFC-176 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D |  |
| IFC-177 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill` | C |  |
| IFC-178 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D |  |
| IFC-179 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D | [U] values are placeholders |
| IFC-180 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill`, `gov.ifms.loans-advances` | C |  |
| IFC-181 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D |  |
| IFC-182 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill` | C |  |
| IFC-183 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | `gov.ifms.bill` | C |  |
| IFC-184 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D |  |
| IFC-185 | catalogue (pay, advance, GPF, retirement bill types) + pension DMN (CCS 2021) + GPF / advances packs | `in/GOI.yaml#establishment` | - | D |  |
| IFC-186 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | D |  |
| IFC-187 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | D |  |
| IFC-188 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | D |  |
| IFC-189 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | G | scientific-department list in profile; procurement packs not yet reading it |
| IFC-190 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | D |  |
| IFC-191 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | C |  |
| IFC-192 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | D |  |
| IFC-193 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | D |  |
| IFC-194 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | D |  |
| IFC-195 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | D |  |
| IFC-196 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | C |  |
| IFC-197 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | C |  |
| IFC-198 | existing procurement / works packs; thresholds as profile data | `in/GOI.yaml#procurement` | - | D |  |
| IFC-199 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFC-200 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFC-201 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFC-202 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.year-end-closing` | P | [U] values are placeholders |
| IFC-203 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.year-end-closing` | C |  |
| IFC-204 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.year-end-closing` | C |  |
| IFC-205 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.year-end-closing` | C |  |
| IFC-206 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFC-207 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFC-208 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFC-209 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFC-210 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.loans-advances` | C |  |
| IFC-211 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFC-212 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFC-213 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.surrender-lapse`, `gov.ifms.year-end-closing` | C |  |
| IFC-214 | monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping | `in/GOI.yaml#accounts` | `gov.ifms.monthly-accounts` | P | [U] values are placeholders |
| IFC-215 | audit-para settlement pack; core audit trail | `in/GOI.yaml#audit` | `gov.ifms.audit-para-settlement` | C |  |
| IFC-216 | audit-para settlement pack; core audit trail | `in/GOI.yaml#audit` | `gov.ifms.audit-para-settlement` | C |  |
| IFC-217 | audit-para settlement pack; core audit trail | `in/GOI.yaml#audit` | `gov.ifms.audit-para-settlement` | P | [U] values are placeholders |
| IFC-218 | audit-para settlement pack; core audit trail | `in/GOI.yaml#audit` | `gov.ifms.audit-para-settlement` | C |  |
| IFC-219 | audit-para settlement pack; core audit trail | `in/GOI.yaml#audit` | `gov.ifms.audit-para-settlement` | P | [U] values are placeholders |
| IFC-220 | audit-para settlement pack; core audit trail | `in/GOI.yaml#audit` | `gov.ifms.audit-para-settlement` | C |  |
| IFC-221 | audit-para settlement pack; core audit trail | `in/GOI.yaml#audit` | `gov.ifms.audit-para-settlement` | C |  |
| IFC-222 | audit-para settlement pack; core audit trail | `in/GOI.yaml#audit` | `gov.ifms.reappropriation` | C |  |
| IFC-223 | audit-para settlement pack; core audit trail | `in/GOI.yaml#audit` | - | C |  |
| IFC-224 | department profiles (department layer) over GOI | `in/goi/GOI.RAILWAYS.yaml` | - | D |  |
| IFC-225 | department profiles (department layer) over GOI | `in/goi/GOI.DEFENCE.yaml` | - | D |  |
| IFC-226 | department profiles (department layer) over GOI | `in/goi/GOI.DEFENCE.yaml` | - | D |  |
| IFC-227 | department profiles (department layer) over GOI | `in/goi/GOI.POSTS.yaml` | - | D |  |
| IFC-228 | department profiles (department layer) over GOI | `in/goi/GOI.TELECOM.yaml` | - | D |  |
| IFC-229 | department profiles (department layer) over GOI | `in/goi/GOI.MEA.yaml` | - | D |  |
| IFC-230 | department profiles (department layer) over GOI | `in/goi/GOI.MEA.yaml` | - | D |  |
| IFC-231 | department profiles (department layer) over GOI | `in/goi/GOI.MHA.yaml` | - | D |  |
| IFC-232 | department profiles (department layer) over GOI | `in/goi/GOI.DAE / GOI.SPACE.yaml` | - | D |  |
| IFC-233 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-234 | department profiles (department layer) over GOI | `in/goi/GOI.REVENUE.yaml` | - | D |  |
| IFC-235 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-236 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-237 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-238 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | G | CPWD account-code departments: no specific profile |
| IFC-239 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | `gov.ifms.reappropriation` | C |  |
| IFC-240 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | `gov.ifms.reappropriation` | C |  |
| IFC-241 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-242 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | `gov.ifms.sna-sparsh` | C |  |
| IFC-243 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-244 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | G | field-heavy CDDO patterns: data only |
| IFC-245 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-246 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | `gov.ifms.bill` | C |  |
| IFC-247 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-248 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-249 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | - | D |  |
| IFC-250 | department profiles (department layer) over GOI | `in/goi/GOI.*.yaml` | `gov.ifms.year-end-closing` | C |  |
| IFC-251 | platform core (forms, eSign, idempotency, SoD, tenancy, adapters) | `-` | - | C |  |
| IFC-252 | platform core (forms, eSign, idempotency, SoD, tenancy, adapters) | `-` | `gov.ifms.bill` | C |  |
| IFC-253 | platform core (forms, eSign, idempotency, SoD, tenancy, adapters) | `-` | `gov.ifms.bill` | C |  |
| IFC-254 | platform core (forms, eSign, idempotency, SoD, tenancy, adapters) | `-` | `gov.ifms.gpf` | P | [U] values are placeholders |
| IFC-255 | platform core (forms, eSign, idempotency, SoD, tenancy, adapters) | `-` | - | D |  |
| IFC-256 | platform core (forms, eSign, idempotency, SoD, tenancy, adapters) | `-` | `gov.ifms.pao-chain` | C |  |
| IFC-257 | platform core (forms, eSign, idempotency, SoD, tenancy, adapters) | `-` | - | C |  |
| IFC-258 | platform core (forms, eSign, idempotency, SoD, tenancy, adapters) | `-` | - | C |  |

## IFS-001 ... IFS-078 (cross-state)

| Id | Generic capability | Data | Packs | St | Note |
|---|---|---|---|---|---|
| IFS-001 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | - | D |  |
| IFS-002 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | - | D |  |
| IFS-003 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | - | D |  |
| IFS-004 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | - | D |  |
| IFS-005 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | `gov.ifms.budget-formulation` | C |  |
| IFS-006 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | - | D |  |
| IFS-007 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | - | D |  |
| IFS-008 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | - | D |  |
| IFS-009 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | `gov.ifms.budget-formulation` | C |  |
| IFS-010 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | - | D |  |
| IFS-011 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-012 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-013 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-014 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-015 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-016 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-017 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-018 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-019 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-020 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-021 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-022 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-023 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-024 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-025 | config: rail catalogue + rail DMN (tenant overrides); core execution | `in/IN.yaml#paymentRails; in/states/*.yaml#paymentRails` | - | D |  |
| IFS-026 | config: rail catalogue + rail DMN (tenant overrides); core execution | `in/IN.yaml#paymentRails; in/states/*.yaml#paymentRails` | - | D |  |
| IFS-027 | receipts pack; profile receipts rules | `in/states/*.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFS-028 | receipts pack; profile receipts rules | `in/states/*.yaml#receipts` | `gov.ifms.receipts` | C |  |
| IFS-029 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-030 | DMN delegation (tenant overrides) + profile rulebooks / procurement family | `in/states/*.yaml#rulebooks, procurement` | `gov.ifms.reappropriation` | C |  |
| IFS-031 | DMN delegation (tenant overrides) + profile rulebooks / procurement family | `in/states/*.yaml#rulebooks, procurement` | - | D |  |
| IFS-032 | ledger N-stage chain, hold queue, ceilings, period lock | `in/states/*.yaml#budgetControl` | `gov.ifms.budget-allocation` | C |  |
| IFS-033 | profile orgTopology / subStateEntities / regime; n-level scopes | `in/states/*.yaml#orgTopology, subStateEntities` | - | D |  |
| IFS-034 | classification tags (TSP) / grants to ULBs | `in/states/*.yaml#tags` | - | D |  |
| IFS-035 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-036 | profile orgTopology / subStateEntities / regime; n-level scopes | `in/states/*.yaml#orgTopology, subStateEntities` | `gov.ifms.monthly-accounts` | C |  |
| IFS-037 | classification tags (TSP) / grants to ULBs | `in/states/*.yaml#tags` | - | D |  |
| IFS-038 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | - | D |  |
| IFS-039 | config: classification registry, per-state scheme, tags, crosswalks | `in/IN.yaml; in/states/*.yaml#classification` | `gov.ifms.bill` | C |  |
| IFS-040 | ledger N-stage chain, hold queue, ceilings, period lock | `in/states/*.yaml#budgetControl` | `gov.ifms.budget-allocation` | C |  |
| IFS-041 | ledger N-stage chain, hold queue, ceilings, period lock | `in/states/*.yaml#budgetControl` | `gov.ifms.bill`, `gov.ifms.budget-allocation` | C |  |
| IFS-042 | ledger N-stage chain, hold queue, ceilings, period lock | `in/states/*.yaml#budgetControl` | `gov.ifms.budget-allocation` | C |  |
| IFS-043 | ledger N-stage chain, hold queue, ceilings, period lock | `in/states/*.yaml#budgetControl` | `gov.ifms.bill`, `gov.ifms.year-end-closing` | C |  |
| IFS-044 | catalogue + chains + series; bill / PAO-chain / GIA packs | `in/states/*.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFS-045 | catalogue + chains + series; bill / PAO-chain / GIA packs | `in/states/*.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFS-046 | catalogue + chains + series; bill / PAO-chain / GIA packs | `in/states/*.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFS-047 | catalogue + chains + series; bill / PAO-chain / GIA packs | `in/states/*.yaml#documentTypes, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFS-048 | catalogue + chains + series; bill / PAO-chain / GIA packs | `in/states/*.yaml#documentTypes, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFS-049 | catalogue + chains + series; bill / PAO-chain / GIA packs | `in/states/*.yaml#documentTypes, approvalChains` | `gov.ifms.bill` | C |  |
| IFS-050 | catalogue + chains + series; bill / PAO-chain / GIA packs | `in/states/*.yaml#documentTypes, approvalChains` | `gov.ifms.bill`, `gov.ifms.gia-uc` | C |  |
| IFS-051 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-052 | ledger (SNA limits) + SNA-SPARSH pack; profile snaSparsh | `in/IN.yaml#snaSparsh; in/states/*.yaml#integrations` | `gov.ifms.sna-sparsh` | C |  |
| IFS-053 | config: rail catalogue + rail DMN (tenant overrides); core execution | `in/IN.yaml#paymentRails; in/states/*.yaml#paymentRails` | `gov.ifms.bill` | C |  |
| IFS-054 | config: rail catalogue + rail DMN (tenant overrides); core execution | `in/IN.yaml#paymentRails; in/states/*.yaml#paymentRails` | `gov.ifms.bill` | C |  |
| IFS-055 | receipts pack; profile receipts rules | `in/states/*.yaml#receipts` | `gov.ifms.receipts`, `universal.billing-cycle` | C |  |
| IFS-056 | payee golden record via PayeeDirectory port (sangam-registry party registry) + registry.party bill step | `in/states/*.yaml#payee` | `gov.ifms.bill` | C |  |
| IFS-057 | ledger public-account sub-ledger types (profile publicAccounts) | `in/IN.yaml#publicAccounts; in/states/*.yaml` | - | D |  |
| IFS-058 | ledger public-account sub-ledger types (profile publicAccounts) | `in/IN.yaml#publicAccounts; in/states/*.yaml` | - | D |  |
| IFS-059 | ledger public-account sub-ledger types (profile publicAccounts) | `in/IN.yaml#publicAccounts; in/states/*.yaml` | - | D |  |
| IFS-060 | profile orgTopology / subStateEntities / regime; n-level scopes | `in/states/*.yaml#orgTopology, subStateEntities` | - | D |  |
| IFS-061 | profile orgTopology / subStateEntities / regime; n-level scopes | `in/states/*.yaml#orgTopology, subStateEntities` | - | D |  |
| IFS-062 | profile orgTopology / subStateEntities / regime; n-level scopes | `in/states/*.yaml#orgTopology, subStateEntities` | - | D |  |
| IFS-063 | profile orgTopology / subStateEntities / regime; n-level scopes | `in/states/*.yaml#orgTopology, subStateEntities` | - | D |  |
| IFS-064 | profile orgTopology / subStateEntities / regime; n-level scopes | `in/states/*.yaml#orgTopology, subStateEntities` | `gov.ifms.bill` | C |  |
| IFS-065 | profile orgTopology / subStateEntities / regime; n-level scopes | `in/states/*.yaml#orgTopology, subStateEntities` | - | D |  |
| IFS-066 | profile orgTopology / subStateEntities / regime; n-level scopes | `in/states/*.yaml#orgTopology, subStateEntities` | - | D |  |
| IFS-067 | DMN delegation (tenant overrides) + profile rulebooks / procurement family | `in/states/*.yaml#rulebooks, procurement` | - | D |  |
| IFS-068 | DMN delegation (tenant overrides) + profile rulebooks / procurement family | `in/states/*.yaml#rulebooks, procurement` | `gov.ifms.bill` | C |  |
| IFS-069 | existing works packs + profile works mode | `in/states/*.yaml#works` | - | D |  |
| IFS-070 | pension DMN + salary pack + profile pension / payroll | `in/states/*.yaml#pension, payroll` | - | D |  |
| IFS-071 | pension DMN + salary pack + profile pension / payroll | `in/states/*.yaml#pension, payroll` | `gov.ifms.bill` | C |  |
| IFS-072 | config locale languages (scope, script, direction, effectiveFrom) | `in/states/*.yaml#locale` | - | D |  |
| IFS-073 | chain templates (signatures, sequence) | `in/states/*.yaml#signatures, approvalChains` | `gov.ifms.pao-chain` | C |  |
| IFS-074 | monthly-accounts pack; profile accounts | `in/states/*.yaml#accounts` | `gov.ifms.monthly-accounts` | C |  |
| IFS-075 | config admin service (maker-checker, change register); ledger audit link | `-` | - | D |  |
| IFS-076 | connector bindings / ledger SHADOW mode | `in/states/*.yaml#integrations` | - | D |  |
| IFS-077 | profile offlineUnits flags only | `in/states/*.yaml#operations` | - | G | store-and-forward: field workstream (sangam-field) |
| IFS-078 | existing DBT pack; ledger striping | `-` | - | D |  |

## State- and UT-specific items (IFS-<CODE>-nnn, incl. the pass-2 delta ids)

| Id | Data | Packs | St |
|---|---|---|---|
| IFS-AN-001 | `in/states/AN.yaml` | - | D |
| IFS-AN-002 | `in/states/AN.yaml` | - | D |
| IFS-AN-003 | `in/states/AN.yaml` | - | D |
| IFS-AN-004 | `in/states/AN.yaml` | `gov.ifms.gia-uc` | C |
| IFS-AN-005 | `in/states/AN.yaml` | - | D |
| IFS-AP-001 | `in/states/AP.yaml` | - | D |
| IFS-AP-002 | `in/states/AP.yaml` | - | D |
| IFS-AP-003 | `in/states/AP.yaml` | `gov.ifms.bill` | C |
| IFS-AP-004 | `in/states/AP.yaml` | `gov.ifms.pao-chain` | C |
| IFS-AP-005 | `in/states/AP.yaml` | - | D |
| IFS-AP-006 | `in/states/AP.yaml` | `gov.ifms.receipts` | C |
| IFS-AP-007 | `in/states/AP.yaml` | - | D |
| IFS-AR-001 | `in/states/AR.yaml` | - | D |
| IFS-AR-002 | `in/states/AR.yaml` | - | D |
| IFS-AR-003 | `in/states/AR.yaml` | - | D |
| IFS-AR-004 | `in/states/AR.yaml` | - | D |
| IFS-AR-005 | `in/states/AR.yaml` | - | D |
| IFS-AR-006 | `in/states/AR.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-AS-001 | `in/states/AS.yaml` | - | D |
| IFS-AS-002 | `in/states/AS.yaml` | - | D |
| IFS-AS-003 | `in/states/AS.yaml` | - | D |
| IFS-AS-004 | `in/states/AS.yaml` | - | D |
| IFS-AS-005 | `in/states/AS.yaml` | `gov.ifms.receipts` | C |
| IFS-AS-006 | `in/states/AS.yaml` | - | D |
| IFS-AS-007 | `in/states/AS.yaml` | - | D |
| IFS-AS-008 | `in/states/AS.yaml` | - | D |
| IFS-AS-009 | `in/states/AS.yaml` | - | D |
| IFS-AS-010 | `in/states/AS.yaml` | - | D |
| IFS-BR-001 | `in/states/BR.yaml` | - | D |
| IFS-BR-002 | `in/states/BR.yaml` | `gov.ifms.bill` | C |
| IFS-BR-003 | `in/states/BR.yaml` | - | D |
| IFS-BR-004 | `in/states/BR.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-BR-005 | `in/states/BR.yaml` | - | D |
| IFS-BR-006 | `in/states/BR.yaml` | - | D |
| IFS-BR-007 | `in/states/BR.yaml` | - | D |
| IFS-CH-001 | `in/states/CH.yaml` | - | D |
| IFS-CH-002 | `in/states/CH.yaml` | - | D |
| IFS-CH-003 | `in/states/CH.yaml` | `gov.ifms.bill` | C |
| IFS-CH-004 | `in/states/CH.yaml` | `gov.ifms.gia-uc` | C |
| IFS-CT-001 | `in/states/CT.yaml` | `gov.ifms.budget-allocation` | C |
| IFS-CT-002 | `in/states/CT.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-CT-003 | `in/states/CT.yaml` | - | D |
| IFS-CT-004 | `in/states/CT.yaml` | `gov.ifms.monthly-accounts` | C |
| IFS-CT-005 | `in/states/CT.yaml` | `gov.ifms.budget-formulation` | C |
| IFS-CT-006 | `in/states/CT.yaml` | - | D |
| IFS-DH-001 | `in/states/DH.yaml` | - | D |
| IFS-DH-002 | `in/states/DH.yaml` | - | D |
| IFS-DH-003 | `in/states/DH.yaml` | `gov.ifms.gia-uc` | C |
| IFS-DH-004 | `in/states/DH.yaml` | - | D |
| IFS-DH-005 | `in/states/DH.yaml` | - | D |
| IFS-DH-006 | `in/states/DH.yaml` | - | D |
| IFS-DL-001 | `in/states/DL.yaml` | - | D |
| IFS-DL-002 | `in/states/DL.yaml` | `gov.ifms.monthly-accounts` | C |
| IFS-DL-003 | `in/states/DL.yaml` | `gov.ifms.receipts` | C |
| IFS-DL-004 | `in/states/DL.yaml` | - | D |
| IFS-DL-005 | `in/states/DL.yaml` | `gov.ifms.budget-formulation` | C |
| IFS-DL-006 | `in/states/DL.yaml` | - | D |
| IFS-GA-001 | `in/states/GA.yaml` | - | D |
| IFS-GA-002 | `in/states/GA.yaml` | - | D |
| IFS-GA-003 | `in/states/GA.yaml` | - | D |
| IFS-GA-004 | `in/states/GA.yaml` | - | D |
| IFS-GA-005 | `in/states/GA.yaml` | - | D |
| IFS-GA-006 | `in/states/GA.yaml` | - | D |
| IFS-GA-007 | `in/states/GA.yaml` | - | D |
| IFS-GA-008 | `in/states/GA.yaml` | - | D |
| IFS-GJ-001 | `in/states/GJ.yaml` | `gov.ifms.bill` | C |
| IFS-GJ-002 | `in/states/GJ.yaml` | - | D |
| IFS-GJ-003 | `in/states/GJ.yaml` | - | D |
| IFS-GJ-004 | `in/states/GJ.yaml` | - | D |
| IFS-GJ-005 | `in/states/GJ.yaml` | - | D |
| IFS-GJ-006 | `in/states/GJ.yaml` | - | D |
| IFS-GJ-007 | `in/states/GJ.yaml` | - | D |
| IFS-GJ-008 | `in/states/GJ.yaml` | - | D |
| IFS-HP-001 | `in/states/HP.yaml` | - | D |
| IFS-HP-002 | `in/states/HP.yaml` | `gov.ifms.bill` | C |
| IFS-HP-003 | `in/states/HP.yaml` | `gov.ifms.bill` | C |
| IFS-HP-004 | `in/states/HP.yaml` | `gov.ifms.bill` | C |
| IFS-HP-005 | `in/states/HP.yaml` | `gov.ifms.bill` | C |
| IFS-HP-006 | `in/states/HP.yaml` | - | D |
| IFS-HP-007 | `in/states/HP.yaml` | - | D |
| IFS-HR-001 | `in/states/HR.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-HR-002 | `in/states/HR.yaml` | `gov.ifms.pao-chain` | C |
| IFS-HR-003 | `in/states/HR.yaml` | `gov.ifms.bill` | C |
| IFS-HR-004 | `in/states/HR.yaml` | - | D |
| IFS-HR-005 | `in/states/HR.yaml` | - | D |
| IFS-JH-001 | `in/states/JH.yaml` | - | D |
| IFS-JH-002 | `in/states/JH.yaml` | - | D |
| IFS-JH-003 | `in/states/JH.yaml` | `gov.ifms.receipts` | C |
| IFS-JH-004 | `in/states/JH.yaml` | - | D |
| IFS-JH-005 | `in/states/JH.yaml` | - | D |
| IFS-JH-006 | `in/states/JH.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-JH-007 | `in/states/JH.yaml` | - | D |
| IFS-JK-001 | `in/states/JK.yaml` | `gov.ifms.budget-allocation` | C |
| IFS-JK-002 | `in/states/JK.yaml` | `gov.ifms.bill` | C |
| IFS-JK-003 | `in/states/JK.yaml` | - | D |
| IFS-JK-004 | `in/states/JK.yaml` | - | D |
| IFS-JK-005 | `in/states/JK.yaml` | - | D |
| IFS-JK-006 | `in/states/JK.yaml` | - | D |
| IFS-JK-007 | `in/states/JK.yaml` | - | D |
| IFS-KA-001 | `in/states/KA.yaml` | `gov.ifms.bill` | C |
| IFS-KA-002 | `in/states/KA.yaml` | `gov.ifms.budget-allocation` | C |
| IFS-KA-003 | `in/states/KA.yaml` | - | D |
| IFS-KA-004 | `in/states/KA.yaml` | `gov.ifms.bill` | C |
| IFS-KA-005 | `in/states/KA.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-KA-006 | `in/states/KA.yaml` | - | D |
| IFS-KA-007 | `in/states/KA.yaml` | `gov.ifms.bill` | C |
| IFS-KA-008 | `in/states/KA.yaml` | `gov.ifms.bill` | C |
| IFS-KL-001 | `in/states/KL.yaml` | - | D |
| IFS-KL-002 | `in/states/KL.yaml` | - | D |
| IFS-KL-003 | `in/states/KL.yaml` | - | D |
| IFS-KL-004 | `in/states/KL.yaml` | - | D |
| IFS-KL-005 | `in/states/KL.yaml` | `gov.ifms.bill` | C |
| IFS-KL-006 | `in/states/KL.yaml` | `gov.ifms.bill` | C |
| IFS-KL-007 | `in/states/KL.yaml` | `gov.ifms.pao-chain` | C |
| IFS-KL-008 | `in/states/KL.yaml` | `gov.ifms.receipts` | C |
| IFS-LA-001 | `in/states/LA.yaml` | - | D |
| IFS-LA-002 | `in/states/LA.yaml` | - | D |
| IFS-LA-003 | `in/states/LA.yaml` | - | D |
| IFS-LA-004 | `in/states/LA.yaml` | - | D |
| IFS-LA-005 | `in/states/LA.yaml` | - | D |
| IFS-LD-001 | `in/states/LD.yaml` | - | D |
| IFS-LD-002 | `in/states/LD.yaml` | - | D |
| IFS-LD-003 | `in/states/LD.yaml` | - | D |
| IFS-LD-004 | `in/states/LD.yaml` | - | D |
| IFS-LD-005 | `in/states/LD.yaml` | - | D |
| IFS-MH-001 | `in/states/MH.yaml` | `gov.ifms.bill` | C |
| IFS-MH-002 | `in/states/MH.yaml` | `gov.ifms.bill` | C |
| IFS-MH-003 | `in/states/MH.yaml` | `gov.ifms.bill` | C |
| IFS-MH-004 | `in/states/MH.yaml` | - | D |
| IFS-MH-005 | `in/states/MH.yaml` | - | D |
| IFS-MH-006 | `in/states/MH.yaml` | - | D |
| IFS-MH-007 | `in/states/MH.yaml` | - | D |
| IFS-MH-008 | `in/states/MH.yaml` | - | D |
| IFS-ML-001 | `in/states/ML.yaml` | - | D |
| IFS-ML-002 | `in/states/ML.yaml` | - | D |
| IFS-ML-003 | `in/states/ML.yaml` | `gov.ifms.pao-chain` | C |
| IFS-ML-004 | `in/states/ML.yaml` | `gov.ifms.pao-chain` | C |
| IFS-ML-005 | `in/states/ML.yaml` | - | D |
| IFS-ML-006 | `in/states/ML.yaml` | - | D |
| IFS-ML-007 | `in/states/ML.yaml` | - | D |
| IFS-ML-008 | `in/states/ML.yaml` | `gov.ifms.monthly-accounts` | C |
| IFS-MN-001 | `in/states/MN.yaml` | - | D |
| IFS-MN-002 | `in/states/MN.yaml` | `gov.ifms.budget-allocation` | C |
| IFS-MN-003 | `in/states/MN.yaml` | - | D |
| IFS-MN-004 | `in/states/MN.yaml` | `gov.ifms.receipts` | C |
| IFS-MN-005 | `in/states/MN.yaml` | - | D |
| IFS-MN-006 | `in/states/MN.yaml` | - | D |
| IFS-MN-007 | `in/states/MN.yaml` | - | D |
| IFS-MP-001 | `in/states/MP.yaml` | `gov.ifms.bill` | C |
| IFS-MP-002 | `in/states/MP.yaml` | - | D |
| IFS-MP-003 | `in/states/MP.yaml` | `gov.ifms.bill` | C |
| IFS-MP-004 | `in/states/MP.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-MP-005 | `in/states/MP.yaml` | `gov.ifms.receipts` | C |
| IFS-MP-006 | `in/states/MP.yaml` | - | D |
| IFS-MP-007 | `in/states/MP.yaml` | - | D |
| IFS-MZ-001 | `in/states/MZ.yaml` | - | D |
| IFS-MZ-002 | `in/states/MZ.yaml` | - | D |
| IFS-MZ-003 | `in/states/MZ.yaml` | - | D |
| IFS-MZ-004 | `in/states/MZ.yaml` | - | D |
| IFS-MZ-005 | `in/states/MZ.yaml` | - | D |
| IFS-MZ-006 | `in/states/MZ.yaml` | - | D |
| IFS-NL-001 | `in/states/NL.yaml` | `gov.ifms.budget-allocation` | C |
| IFS-NL-002 | `in/states/NL.yaml` | `gov.ifms.gpf` | C |
| IFS-NL-003 | `in/states/NL.yaml` | - | D |
| IFS-NL-004 | `in/states/NL.yaml` | - | D |
| IFS-NL-005 | `in/states/NL.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-NL-006 | `in/states/NL.yaml` | - | D |
| IFS-OD-001 | `in/states/OD.yaml` | - | D |
| IFS-OD-002 | `in/states/OD.yaml` | - | D |
| IFS-OD-003 | `in/states/OD.yaml` | `gov.ifms.pao-chain` | C |
| IFS-OD-004 | `in/states/OD.yaml` | - | D |
| IFS-OD-005 | `in/states/OD.yaml` | `gov.ifms.bill` | C |
| IFS-OD-006 | `in/states/OD.yaml` | - | D |
| IFS-OD-007 | `in/states/OD.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-OD-008 | `in/states/OD.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-OD-009 | `in/states/OD.yaml` | `gov.ifms.bill` | C |
| IFS-OD-010 | `in/states/OD.yaml` | `gov.ifms.bill` | C |
| IFS-OD-011 | `in/states/OD.yaml` | - | D |
| IFS-OD-012 | `in/states/OD.yaml` | - | D |
| IFS-PB-001 | `in/states/PB.yaml` | `gov.ifms.receipts` | C |
| IFS-PB-002 | `in/states/PB.yaml` | `gov.ifms.gia-uc` | C |
| IFS-PB-003 | `in/states/PB.yaml` | `gov.ifms.bill` | C |
| IFS-PB-004 | `in/states/PB.yaml` | `gov.ifms.bill` | C |
| IFS-PB-005 | `in/states/PB.yaml` | `gov.ifms.bill` | C |
| IFS-PB-006 | `in/states/PB.yaml` | - | D |
| IFS-PY-001 | `in/states/PY.yaml` | - | D |
| IFS-PY-002 | `in/states/PY.yaml` | - | D |
| IFS-PY-003 | `in/states/PY.yaml` | - | D |
| IFS-PY-004 | `in/states/PY.yaml` | - | D |
| IFS-PY-005 | `in/states/PY.yaml` | - | D |
| IFS-RJ-001 | `in/states/RJ.yaml` | - | D |
| IFS-RJ-002 | `in/states/RJ.yaml` | `gov.ifms.bill` | C |
| IFS-RJ-003 | `in/states/RJ.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-RJ-004 | `in/states/RJ.yaml` | `gov.ifms.bill` | C |
| IFS-RJ-005 | `in/states/RJ.yaml` | - | D |
| IFS-RJ-006 | `in/states/RJ.yaml` | `gov.ifms.surrender-lapse` | C |
| IFS-RJ-007 | `in/states/RJ.yaml` | - | D |
| IFS-RJ-008 | `in/states/RJ.yaml` | `gov.ifms.receipts` | C |
| IFS-RJ-009 | `in/states/RJ.yaml` | - | D |
| IFS-SK-001 | `in/states/SK.yaml` | `gov.ifms.bill` | C |
| IFS-SK-002 | `in/states/SK.yaml` | - | D |
| IFS-SK-003 | `in/states/SK.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-SK-004 | `in/states/SK.yaml` | `gov.ifms.bill`, `gov.ifms.year-end-closing` | C |
| IFS-SK-005 | `in/states/SK.yaml` | - | D |
| IFS-SK-006 | `in/states/SK.yaml` | - | D |
| IFS-SK-007 | `in/states/SK.yaml` | - | D |
| IFS-SK-008 | `in/states/SK.yaml` | - | D |
| IFS-TG-001 | `in/states/TG.yaml` | - | D |
| IFS-TG-002 | `in/states/TG.yaml` | - | D |
| IFS-TG-003 | `in/states/TG.yaml` | - | D |
| IFS-TG-004 | `in/states/TG.yaml` | - | D |
| IFS-TG-005 | `in/states/TG.yaml` | `gov.ifms.pao-chain` | C |
| IFS-TG-006 | `in/states/TG.yaml` | `gov.ifms.budget-allocation` | C |
| IFS-TG-007 | `in/states/TG.yaml` | - | D |
| IFS-TN-001 | `in/states/TN.yaml` | `gov.ifms.bill` | C |
| IFS-TN-002 | `in/states/TN.yaml` | - | D |
| IFS-TN-003 | `in/states/TN.yaml` | - | D |
| IFS-TN-004 | `in/states/TN.yaml` | `gov.ifms.receipts` | C |
| IFS-TN-005 | `in/states/TN.yaml` | `gov.ifms.budget-allocation` | C |
| IFS-TN-006 | `in/states/TN.yaml` | - | D |
| IFS-TN-007 | `in/states/TN.yaml` | - | D |
| IFS-TN-008 | `in/states/TN.yaml` | - | D |
| IFS-TN-009 | `in/states/TN.yaml` | - | D |
| IFS-TR-001 | `in/states/TR.yaml` | `gov.ifms.bill` | C |
| IFS-TR-002 | `in/states/TR.yaml` | `gov.ifms.bill` | C |
| IFS-TR-003 | `in/states/TR.yaml` | - | D |
| IFS-TR-004 | `in/states/TR.yaml` | - | D |
| IFS-TR-005 | `in/states/TR.yaml` | - | D |
| IFS-TR-006 | `in/states/TR.yaml` | - | D |
| IFS-TR-007 | `in/states/TR.yaml` | - | D |
| IFS-UK-001 | `in/states/UK.yaml` | - | D |
| IFS-UK-002 | `in/states/UK.yaml` | - | D |
| IFS-UK-003 | `in/states/UK.yaml` | - | D |
| IFS-UK-004 | `in/states/UK.yaml` | - | D |
| IFS-UK-005 | `in/states/UK.yaml` | - | D |
| IFS-UK-006 | `in/states/UK.yaml` | - | D |
| IFS-UP-001 | `in/states/UP.yaml` | - | D |
| IFS-UP-002 | `in/states/UP.yaml` | `gov.ifms.budget-formulation` | C |
| IFS-UP-003 | `in/states/UP.yaml` | `gov.ifms.pao-chain` | C |
| IFS-UP-004 | `in/states/UP.yaml` | `gov.ifms.receipts` | C |
| IFS-UP-005 | `in/states/UP.yaml` | - | D |
| IFS-UP-006 | `in/states/UP.yaml` | - | D |
| IFS-UP-007 | `in/states/UP.yaml` | `gov.ifms.bill` | C |
| IFS-WB-001 | `in/states/WB.yaml` | - | D |
| IFS-WB-002 | `in/states/WB.yaml` | - | D |
| IFS-WB-003 | `in/states/WB.yaml` | `gov.ifms.receipts` | C |
| IFS-WB-004 | `in/states/WB.yaml` | `gov.ifms.sna-sparsh` | C |
| IFS-WB-005 | `in/states/WB.yaml` | `gov.ifms.monthly-accounts` | C |
| IFS-WB-006 | `in/states/WB.yaml` | - | D |
| IFS-WB-007 | `in/states/WB.yaml` | - | D |
| IFS-WB-008 | `in/states/WB.yaml` | - | D |
| IFS-WB-009 | `in/states/WB.yaml` | `gov.ifms.monthly-accounts` | C |
| IFS-WB-010 | `in/states/WB.yaml` | - | D |

## GA-01 ... GA-30

| Id | Capability | Packs | St | Note |
|---|---|---|---|---|
| GA-01 | profile hierarchy (n-level scopes, layers, inheritance, locks, effective dating, admin workflow) | - | C |  |
| GA-02 | classification registry + per-state schemes + seeds | - | P | LMMH seed partial; per-state master codes below minor to load |
| GA-03 | ledger N-stage chain + allocation / surrender packs | `gov.ifms.budget-allocation`, `gov.ifms.supplementary`, `gov.ifms.surrender-lapse` | C |  |
| GA-04 | bill pack: sanction reference checked by the catalogue | `gov.ifms.bill` | P | sanction as linked case: via sanction chain template, no Work ID entity |
| GA-05 | document-type catalogue | `gov.ifms.bill` | C | state form lists partly [U] (OD 20 types, JK 37) |
| GA-06 | bill intake from external sources (integrations.sourceSystem) | `gov.ifms.bill` | P | inbound start contracts per source are WS-INTEGRATION |
| GA-07 | PAO / treasury chain pack, token series, no-edit policy | `gov.ifms.pao-chain` | C |  |
| GA-08 | GIA-UC pack; AC/DC flags in profile | `gov.ifms.gia-uc` | P | AC/DC pairing pack not separate (contingent family + profile acDc) |
| GA-09 | SNA-SPARSH pack + ledger | `gov.ifms.sna-sparsh` | C |  |
| GA-10 | rail catalogue + rail DMN + overrides; two-stage failure / reissue | `gov.ifms.bill` | C |  |
| GA-11 | receipts pack + profile receipt rules | `gov.ifms.receipts` | C |  |
| GA-12 | payee golden record: PayeeDirectory port over the sangam-registry party registry; bill step registry.party | `gov.ifms.bill` | C |  |
| GA-13 | ledger sub-ledger types (profile publicAccounts) | - | P | PD/PLA policies as data; no dedicated PD pack |
| GA-14 | profile orgTopology (typed units, servedGovernments, rendition parent) | - | P | unit registers per state to load as seeds |
| GA-15 | profile subStateEntities + n-level scopes | - | P | share-formula DMNs per entity to write |
| GA-16 | DMN delegation tables (reapp, appraisal, GIA, rail) + per-government financial-powers table skeleton | `gov.ifms.reappropriation` | P | State values are [U] skeletons (in/dmn/<GOV>/) |
| GA-17 | profile adopt (donor sections) + citations | `gov.ifms.reappropriation` | C |  |
| GA-18 | existing works packs + profile works mode | - | P | CCL/DCL / LoC as rail; e-MB attachments data only |
| GA-19 | profile procurement family + per-government procurement-mode DMN skeleton | - | P | State thresholds are [U] skeletons |
| GA-20 | pension DMN CCS 2021 + profile payer classes | - | P | state pension rules not loaded |
| GA-21 | salary pack + profile payroll binding | - | P |  |
| GA-22 | locale languages per profile | - | P | forms still en / hi; language packs for forms not generated |
| GA-23 | chain template signatures / sequence | `gov.ifms.pao-chain` | C |  |
| GA-24 | monthly-accounts pack | `gov.ifms.monthly-accounts` | C |  |
| GA-25 | config admin change register; ledger period lock | `gov.ifms.year-end-closing` | C |  |
| GA-26 | ledger SHADOW mode + connector bindings | - | P | SAP / NIC adapters are WS-INTEGRATION |
| GA-27 | offline units (profile flags); store-and-forward belongs to the field workstream | - | G | out of this scope |
| GA-28 | DBT pack + ledger striping | - | P | throughput not measured (testing phase) |
| GA-29 | presets UT-LEG / UT-NOLEG; union demand; regime history | `gov.ifms.budget-formulation` | C |  |
| GA-30 | two-level (n-level) tenant scopes | - | C |  |

## Still placeholders (IFMS-RESEARCH-DELTA section C)

Code structures of GJ, WB, TG, OD, ML, JK, LA (Union layout used); budget-document languages (27 entities); TG procurement rules; works manuals (GJ, HP, CT, ML, MN, MZ, TR, AR, LD); SNA-SPARSH dates (KL, JK); Chandigarh treasury software; rush-of-expenditure percentages (unset); March Prelim and Sy-II dates; e-bill post-check sampling; ATN months; GPF days; commutation rule number and full commutation table; CPWD manual after 2022; the 61-head object list from FY 2027-28 and its old -> new map; the full LMMH list (the seed is a sample).
