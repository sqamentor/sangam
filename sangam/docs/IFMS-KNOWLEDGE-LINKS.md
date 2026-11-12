# IFMS knowledge base: folder links for research and verification

**Why this file exists.** The owner asked that the IFMS knowledge base be linked folder by folder, so that Sangam work
can be researched and checked against it.

**Boundary (read first).** IFMS is a separate consumer project, not part of Sangam. Its material is **reference only**:
nothing from it is copied into the product.
- Sangam stays generic (ADR-D-20, SANGAM.md X.0e): government-finance knowledge reaches the product only as data
  (profiles, master data, DMN, `gov.*` packs) written through the generators, never as consumer text or ids.
- A consumer programme's own identifiers stay out of product files (SG-37, guarded by
  `sangam-core/src/test/java/org/sangam/core/licence/ConsumerIdsOutOfProductFilesTest.java`). Consumer conformance tests
  and fixtures live under `sangam-server/src/test/.../acceptance/consumer/`.
- The IFMS repository has no public remote and stays local only (owner decision recorded as SD-448 in JOINT-LEDGER §4).
  The links below are therefore descriptions relative to the IFMS repository root, not hyperlinks.

**How to use it.** Cite an IFMS document by its folder and file name when a Sangam decision rests on it, and quote the
owner's decisions from JOINT-LEDGER §4 rather than from these folders. This list was built from folder and file names
only (2026-11-12); the contents were not read for it.

## Workflow programme documents (`DOCS\`)

| Folder (relative to the IFMS root) | What it holds (by file name) | Use it for |
|---|---|---|
| `DOCS\1_RESEARCH` (R-76 … R-100) | Research notes `R-<n>_*.md`. In this range: the state IFMS workflow study (R-76), workflow standards and open-source engines (R-77), the worldwide IFMS workflow architecture study (R-78), the engine eligibility, policy-compliance and trial notes on Flowable and Operaton (R-80, R-83, R-89 … R-94), the master investigation phases (R-85, R-86 folders) and its independent review (R-87), the assumptions register (R-88), plan reconciliation and completeness checks (R-95, R-96), the hosting handover (R-97), the time-zone facts for SL-53 (R-98), the budget-distribution seat model (R-99) and the joint-plan review (R-100) | Background and the evidence behind joint decisions, for example R-98 for ADR-D-27 and R-99 for SL-57 |
| `DOCS\4_ENGINEERING` (E-45 … E-59) | Engineering documents `E-<n>_*.md` (several versions each). In this range: the workflow design and engine trial plan (E-45), selection gates (E-46), platform design (E-47), the variation catalogue (E-48), the configuration and architecture specification with its schema and validator (E-49), the extension and engine decision (E-50), the acceptance and lifecycle assurance library (E-51), the Operaton vs Flowable trial comparison (E-52), the Sangam trial test plan and results (E-53, E-54), the push-approval ledger (E-55), the code-complete work breakdown (E-56), the cross-team coordination protocol (E-57), the system landscape map (E-58) and the P-463 time-rule build plan (E-59) | The consumer's test plan and findings (E-53/E-54 are the source of the SG rows), coordination rules (E-57, mirrored by `sangam/COORDINATION.md`) |
| `DOCS\3_FINAL\F-07_IFMS_Workflow_Engine_Decision_v1.0.md` | The consumer's final workflow-engine decision. F-07 is a single file in `DOCS\3_FINAL`, not a folder | The consumer-side record of the engine choice (compare ADR-D-1, SANGAM.md X.0d) |
| `DOCS\0_RECORDS` | Records: `REC-01` sponsor-instruction log and id map, `REC-02` event log, `REC-03` standing architecture and development rule, `REC-04` incident and lessons register, `REC-05` session handover and resume, `REC-06` session hierarchy and lineage, a memory snapshot | Tracing when and in which session the consumer recorded an instruction |

## IFMS domain study

| Folder (relative to the IFMS root) | What it holds (by file name) | Use it for |
|---|---|---|
| `10_COMMON_IFMS_BLUEPRINT` | Capability, entity, interface and process catalogues, a capability commonality matrix, security controls and a state configuration pack (CSV), with their build scripts and a README | Checking that a generic capability or a profile field covers what states have in common |
| `09_IFMS_COMPREHENSIVE_SYSTEM_STUDY` | The comprehensive system study, a document inventory, classification and evidence index, a boundary list, second-pass terms, the Rajasthan deep-harvest addendum, and a summary | Locating the source document behind a domain fact |
| `06_STANDARDS_INTEROP\OMG_BPMN` | The BPMN 2.0.2 formal specification (PDF) and its XSDs (`BPMN20.xsd`, `Semantic.xsd`) | Checking BPMN conformance questions against the specification |
| `00_MASTER_INDEX` | The coverage tracker, crawl and harvest scripts, and per-source component lists (central, CGA, PFMS, state, e-governance) | Finding where a source was harvested and whether it is covered |

## Government finance sources

| Folder (relative to the IFMS root) | What it holds (by file name) | Use it for |
|---|---|---|
| `01_CENTRAL_PFMS` | Central PFMS material: official portal, system overview, user manuals, circulars and OMs, SNA/SPARSH, integration specifications, reports and studies, budget documents | Central-government rules and interfaces behind the `gov.*` packs and profiles |
| `02_STATES` | One folder per state (29) | State-specific variation, delivered in Sangam as profiles and pack data only |
| `04_AUDIT_CAG` | CAG and state audit reports on IFMS and treasury systems (PDF) | Control and audit expectations (audit chain, SoD, system-functionality findings) |
| `05_POLICY_RESEARCH` | International FMIS and security research, sponsor-supplied material, Indian PFM reform and e-governance compendia | Policy context for the international PFM and security packs |
| `PFMS_IFMS_COMPLIANCE` | A compliance library in 25 numbered areas, from the master index, central and state government, PFMS and IFMS through financial compliance, cybersecurity, privacy, web, Android and iOS, API integration, database, cloud, SDLC/DevSecOps, testing, accessibility, audit and governance, business continuity, third parties and legal, to the compliance matrix, gap analysis, evidence and open questions | Checking a compliance requirement (R50–R62, the IN compliance pack) against its source |
