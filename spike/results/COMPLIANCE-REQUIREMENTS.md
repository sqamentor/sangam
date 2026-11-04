# Sangam: Compliance Requirements Register (India)

Workstream: COMPLIANCE (research half). Prepared 2026-11-04. Input for module `sangam/sangam-compliance` (`org.sangam.compliance`).

## 1. Scope

This register lists the obligations that Indian law and central and state government IT policy place on a **government workflow platform** and on the **government body that deploys it**. Sangam is open-source software (Apache-2.0) on Operaton 2.2.0. It is not itself a Data Fiduciary. The deploying Ministry, Department, State agency or PSU is the Data Fiduciary (DF). Sangam's job is to provide the mechanisms the DF needs.

It covers:

- the DPDP Act 2023 and DPDP Rules 2025;
- CERT-In Directions (2022) and CERT-In guidelines;
- the IT Act 2000, the SPDI Rules 2011 and the Intermediary Rules 2021, where they apply;
- MeitY and central policies, the Aadhaar framework and the Public Records Act;
- state instruments that add a concrete obligation.

It does not cover sector-specific rules (RBI, SEBI, IRDAI, ABDM/health, telecom) or classified MHA/NCIIPC material.

This is not legal advice. The deploying department's legal cell has to confirm how it applies to their case, particularly any exemptions under s.17.

**Status legend:**

- **[V]**: checked against the official text, which I downloaded and read on 2026-11-04 (gazette PDF or issuing-body PDF).
- **[S]**: checked only against a secondary source (law firm, press or a reputable commentary).
- **[U]**: not verified. It comes from background knowledge or search snippets only and must be checked before encoding.

**Bearer legend:**

- **SANGAM**: the software must provide the capability.
- **DF**: an obligation of the deploying government body as Data Fiduciary, or as the CERT-In-covered "Government organisation". It is mostly process work.
- **SDF**: applies only if the deployer is notified as a Significant Data Fiduciary.
- **SHARED**: Sangam provides the mechanism, and the deployer must configure and operate it.

"Date seen" is 2026-11-04 for every row.

## 2. Sources

| # | Title | Issuer | Number / date | Official URL | Seen | Read as |
|---|---|---|---|---|---|---|
| S1 | Digital Personal Data Protection Act, 2023 | Ministry of Law & Justice (Legislative Dept.) | Act No. 22 of 2023, 11-08-2023 | https://www.meity.gov.in/static/uploads/2024/06/2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf | 2026-11-04 | Full gazette text [V] |
| S2 | Digital Personal Data Protection Rules, 2025 | MeitY | G.S.R. 846(E), dated 13-11-2025 (Gazette Extraordinary Part II s.3(i); e-signed 14-11-2025 10:43 IST) | https://www.meity.gov.in/static/uploads/2025/11/53450e6e5dc0bfa85ebd78686cadad39.pdf | 2026-11-04 | Full gazette text [V] |
| S3 | DPDP Act commencement notification | MeitY | G.S.R. 843(E), 13-11-2025 | https://www.meity.gov.in/static/uploads/2025/11/c56ceae6c383460ca69577428d36828b.pdf | 2026-11-04 | [V] |
| S4 | Establishment of Data Protection Board of India (HQ in NCR) / Board of four members | MeitY | G.S.R. 844(E) and G.S.R. 845(E), 13-11-2025 | https://www.meity.gov.in/static/uploads/2025/11/cc217843dc3bcb37b2b05bcc3b4e031f.pdf ; https://www.meity.gov.in/static/uploads/2025/11/f6c0837972422cf79d890bfe84cc04d6.pdf | 2026-11-04 | [V] |
| S5 | DPDP (Removal of Difficulties) Order, 2026 | MeitY | S.O. 5458(E), made 05-10-2026, gazette 06-10-2026 (as reported) | (gazette not fetched) secondary: https://www.legal500.com/intelligence/india/privacy/dpdp-(removal-of-difficulties)-order-2026-what-changes-in-sections-9-and-10-of-the-dpdp-act | 2026-11-04 | [S] |
| S6 | CERT-In Directions under s.70B(6) IT Act | CERT-In / MeitY | No. 20(3)/2022-CERT-In, 28-04-2022 (effective 60 days after issue) | https://www.cert-in.org.in/PDF/CERT-In_Directions_70B_28.04.2022.pdf | 2026-11-04 | [V] |
| S7 | FAQs on Cyber Security Directions of 28.04.2022 | CERT-In | May 2022 | https://www.cert-in.org.in/PDF/FAQs_on_CyberSecurityDirections_May2022.pdf | 2026-11-04 | [V] |
| S8 | Guidelines on Information Security Practices for Government Entities | CERT-In (under s.70B(4)(e)) | June 2023 (date not printed in the PDF; date is [S]) | https://www.cert-in.org.in/PDF/guidelinesgovtentities.pdf | 2026-11-04 | [V] text |
| S9 | Comprehensive Cyber Security Audit Policy Guidelines v1.0 | CERT-In | 25-07-2025 | (primary not fetched) secondary: https://www.azbpartners.com/bank/strengthening-indias-cyber-defence-cert-ins-new-cyber-security-audit-guidelines-decoded/ | 2026-11-04 | [S] |
| S10 | Technical Guidelines on SBOM, QBOM & CBOM, AIBOM and HBOM v2.0 | CERT-In | 09-07-2025 (v1 SBOM Oct 2024) | (primary not fetched) secondary: https://khaitanco.com/thought-leadership/CERT-Ins-Expanded-BOM-Guidelines-Preparing-for-the-Next-Phase-of-Cyber-Regulation | 2026-11-04 | [S] |
| S11 | IT (Reasonable security practices and procedures and sensitive personal data or information) Rules, 2011 (SPDI Rules) | DeitY | G.S.R. 313(E), 11-04-2011 | Gazette copy hosted at https://prsindia.org/files/bills_acts/bills_parliament/2011/IT_Rules_2011.pdf | 2026-11-04 | Gazette text [V] |
| S12 | IT (Intermediary Guidelines and Digital Media Ethics Code) Rules, 2021, as updated to 06-04-2023 | MeitY | G.S.R. 139(E), 25-02-2021, amended by G.S.R. 794(E) and 275(E) | https://www.meity.gov.in/static/uploads/2024/02/Information-Technology-Intermediary-Guidelines-and-Digital-Media-Ethics-Code-Rules-2021-updated-06.04.2023-.pdf | 2026-11-04 | [V] (2023 consolidation; later amendments, e.g. a 2026 MeitY upload, not read) |
| S13 | MeitY OM on Jan Vishwas (Amendment of Provisions) Act 2023 changes to IT Act and Aadhaar Act | MeitY | eF. No. 2(1)/2022-CL, 03-11-2023; S.O. 4745(E)/4746(E) of 31-10-2023, in force 30-11-2023 | https://www.meity.gov.in/static/uploads/2024/03/MeitY-JVA-1.pdf | 2026-11-04 | [V] |
| S14 | Information Technology Act, 2000 (consolidated) | Legislative Dept. / India Code | Act 21 of 2000 | https://www.indiacode.nic.in/handle/123456789/1999 | 2026-11-04 | Download timed out (504). Sections from search snippets and S13 [S] |
| S15 | IT (Information Security Practices and Procedures for Protected System) Rules, 2018 | MeitY | G.S.R. 1159(E)?, 22-05-2018 [U] | not fetched | 2026-11-04 | [U] |
| S16 | Guidelines for Government Departments on Contractual Terms related to Cloud Services; MeitY CSP empanelment (STQC audit) | MeitY | 2017 (exact date [U]) | https://www.meity.gov.in (MeghRaj pages); secondary https://www.mondaq.com/india/contracts-and-commercial-law/624414/guidelines-for-contractual-terms-for-cloud-service-providers | 2026-11-04 | [S] |
| S17 | Guidelines for Indian Government Websites and Apps (GIGW 3.0) | MeitY / NIC | 2023 | https://guidelines.india.gov.in/ | 2026-11-04 | [S] (portal seen only via search) |
| S18 | Aadhaar Act 2016 s.29; Aadhaar (Sharing of Information) Regulations 2016 reg. 6; UIDAI ADV circulars (2017; Circular No. 8 of 2025, 18-07-2025); ADV/HSM FAQs 03-11-2025 | Parliament / UIDAI | as stated | https://uidai.gov.in (all UIDAI PDFs returned HTTP 503 on 2026-11-04); secondary https://www.khaitanco.com/sites/default/files/2025-12/ERGO%20-%20Aadhaar%20Data%20Vault%20-%2010%20December%202025.pdf | 2026-11-04 | [S] |
| S19 | Public Records Act, 1993 and Public Records Rules, 1997 | Ministry of Culture / National Archives of India | Act 69 of 1993; Rules 1997 | https://nationalarchives.nic.in/sites/default/files/2023-06/1997.pdf | 2026-11-04 | [S] (snippets only) |
| S20 | Tamil Nadu Cyber Security Policy 2020 (and CSP 2.0, G.O. it_e_21_2024) | Govt. of Tamil Nadu, IT&DS Dept. | 2020; 2024 | https://tamilnaducareerservices.tn.gov.in/asset/TN_Cyber_Security_policy_2020.pdf ; https://cms.tn.gov.in/cms_migrated/document/GO/it_e_21_2024_Ms.pdf | 2026-11-04 | [S] |
| S21 | Rajasthan Jan-Aadhaar Authority Act, 2020 (Act 3 of 2020) | Govt. of Rajasthan | 2020 | https://www.indiacode.nic.in/bitstream/123456789/18789/3/ERJ2020.pdf | 2026-11-04 | [S] |
| S22 | Telangana Open Data Policy 2016 / TGDeX; Karnataka Open Data Policy 2021 | State govts | 2016 / 2021 / 2025 | secondary https://www.internetfreedom.in/a-comparison-of-state-level-data-policies/ | 2026-11-04 | [S] |

### 2.1 DPDP commencement status (verified against S2 Rule 1 and S3)

All periods run from publication of the gazette. The gazette is **dated 13-11-2025**, but the digital signature on the uploaded copy reads 14-11-2025 10:43 IST. PIB and some commentators therefore use 14-11-2025, 14-11-2026 and 14-05-2027. Most practitioner sources use 13-11-2025, which is the date printed on the notification.

**Recommended constants:** `DPDP_RULES_PUBLISHED = 2025-11-13`, `+1Y = 2026-11-13`, `+18M = 2027-05-13`. Treat 14-05-2027 as a tolerance.

| Instrument | Provisions | Commencement | Status on 2026-11-04 |
|---|---|---|---|
| DPDP Rules 2025 (G.S.R. 846(E)) r.1(2) | Rules 1, 2, 17, 18, 19, 20, 21 (Board appointment, salaries, meetings, digital office, staff) | On publication: 13-11-2025 | In force |
| r.1(3) | Rule 4 + First Schedule (Consent Manager registration) | One year after publication: 13-11-2026 | Not yet (9 days away) |
| r.1(4) | Rules 3, 5-16, 22, 23 + Second, Third, Fourth, Seventh Schedules (notice, State processing, security, breach, erasure, contact, children, SDF, rights, transfer, research, appeal, calls for information) | Eighteen months after publication: 13-05-2027 | Not yet |
| DPDP Act (G.S.R. 843(E)) para (a) | s.1(2), s.2, ss.18-26 (Board), ss.35, 38-43, s.44(1) (TRAI Act) and **s.44(3) (RTI s.8(1)(j) substituted)** | 13-11-2025 | In force |
| para (b) | s.6(9) (Consent Manager registration), s.27(1)(d) (Board powers over Consent Managers) | 13-11-2026 | Not yet |
| para (c) | ss.3-5, s.6(1)-(8) and (10), ss.7-17, s.27 (except (1)(d)), ss.28-34, 36, 37 and **s.44(2) (omission of IT Act s.43A, amendment of ss.81 and 87)** | 13-05-2027 | Not yet. IT Act s.43A and SPDI Rules remain operative until then |
| Amendments to the timeline | In Jan-Feb 2026 MeitY consulted on compressing to 12 months (SDFs by 13-11-2026; Rules 15 and 23 immediately; Rule 8(3) in 3 months) [S] | No amending notification located as of 2026-11-04 [S/U] | Treat as **not changed**. Re-check eGazette before release |
| DPDP (Removal of Difficulties) Order 2026, S.O. 5458(E) | Textual only: s.9(1) reads "...of a child or of a person with disability..."; s.10(2)(c)(ii) "periodic audit" becomes "periodic data audit". Does not change commencement or the Rules [S] | 06-10-2026 | In force [S] |
| Corrigendum G.S.R. 892(E) of 10-12-2025 to the Rules | Reported by one source. Contents unknown | [U] | Check eGazette |

> **Correction to the brief:** in the notified Rules, **Rule 22 is Appeal to the Appellate Tribunal** and **Rule 23 is Calling for information** (Seventh Schedule). The brief has these the other way round. The Seventh Schedule is also cross-referenced by Rule 8(3), the one-year minimum retention. No SDF had been notified under s.10(1), and no Rule 13(4) specified-data list had been published, as far as I could find [U].

---

## 3. Obligations

### 3.1 DPDP Act 2023 (S1) — ss.4-17, 33, 44. Unless noted, these take effect on 13-05-2027

| ID | Legal source | Link | Seen | Obligation (plain words) | Bearer | Sangam feature | Status |
|---|---|---|---|---|---|---|---|
| CMP-001 | s.4(1)-(2) | S1 | 2026-11-04 | Process personal data only for a lawful purpose, and only on (a) consent or (b) a s.7 legitimate use. Every processing activity needs a recorded lawful ground. | SHARED | `lawful.ProcessingRegister`, `lawful.LegitimateUseRegistry`, `consent.PurposeBindingGuard` | [V] |
| CMP-002 | s.5(1) | S1 | 2026-11-04 | Every consent request is accompanied or preceded by a notice. The notice states the personal data and purpose, how to withdraw consent and exercise s.13 grievance rights, and how to complain to the Board. | SANGAM | `consent.ConsentNoticeService`, `consent.NoticeTemplate` | [V] |
| CMP-003 | s.5(2) | S1 | 2026-11-04 | For consent given before commencement, send a notice with the same contents "as soon as reasonably practicable". Processing may continue until withdrawal. | SHARED | `ConsentNoticeService` bulk legacy-notice job + `ConsentService` legacy-consent flag | [V] |
| CMP-004 | s.5(3) | S1 | 2026-11-04 | Offer the notice in English or any of the 22 Eighth Schedule languages. | SANGAM | `NoticeTemplate` + `config.IndianLanguage` (all 22 + English) | [V] |
| CMP-005 | s.6(1) | S1 | 2026-11-04 | Consent must be free, specific, informed, unconditional and unambiguous, given by a clear affirmative action (no pre-ticked boxes), and limited to data necessary for the specified purpose. | SANGAM | `consent.ConsentService` (affirmative-action capture, per-purpose items), `PurposeBindingGuard` | [V] |
| CMP-006 | s.6(2) | S1 | 2026-11-04 | Any part of a consent that infringes the Act or other law (e.g. waiving the right to complain) is invalid to that extent. Templates must not contain such clauses. | SHARED | `NoticeTemplate` lint/validation rule; deployer legal review | [V] |
| CMP-007 | s.6(3) | S1 | 2026-11-04 | The consent request is in clear, plain language, can be read in English or any Eighth Schedule language, and shows the contact details of the DPO or authorised person. | SANGAM | `ConsentNoticeService` (contact block from `sdf.SdfObligationService` / DF config) | [V] |
| CMP-008 | s.6(4) | S1 | 2026-11-04 | Consent can be withdrawn at any time, as easily as it was given. | SANGAM | `ConsentService.withdraw` (same channel and same number of steps as grant) | [V] |
| CMP-009 | s.6(5)-(6) | S1 | 2026-11-04 | On withdrawal, stop processing within a reasonable time and make Data Processors stop, unless law requires or authorises processing. Earlier processing stays lawful. | SHARED | `ConsentService` withdrawal event to `PurposeBindingGuard` block + processor notification hook; deployer's processor contracts | [V] |
| CMP-010 | s.6(7)-(8) | S1 | 2026-11-04 | The principal may give, manage, review or withdraw consent through a Consent Manager, who is accountable to her. | SANGAM | `consent.ConsentManagerPort` | [V] |
| CMP-011 | s.6(9) (in force 13-11-2026) | S1, S3 | 2026-11-04 | Consent Managers must be registered with the Board. The DF should accept artefacts only from registered CMs. | SHARED | `ConsentManagerPort` (allow-list of Board-registered CMs, configured by deployer) | [V] |
| CMP-012 | s.6(10) | S1 | 2026-11-04 | Where consent is the basis, the DF must prove that notice was given and consent obtained in accordance with law. This needs a tamper-evident record. | SANGAM | Consent ledger in core `AuditChain`; `logs.AuditIntegrityVerifier`; `report.ComplianceEvidenceReportGenerator` | [V] |
| CMP-013 | s.7(a) | S1 | 2026-11-04 | Legitimate use: data voluntarily provided for a specified purpose where the principal has not objected. Processing stops when she says she no longer wants it. | SHARED | `LegitimateUseRegistry` (ground `VOLUNTARY_PROVISION`) + objection capture | [V] |
| CMP-014 | s.7(b) + Rule 5 | S1, S2 | 2026-11-04 | The State or its instrumentalities may process data to provide a subsidy, benefit, service, certificate, licence or permit. This applies where (i) the principal previously consented to any such benefit, or (ii) the data sits in a notified State database or register. Processing follows the Second Schedule standards (see CMP-052, CMP-053). | SHARED | `LegitimateUseRegistry` (ground `STATE_BENEFIT_7B`, with sub-ground i/ii and notified-database reference) | [V] |
| CMP-015 | s.7(c) | S1 | 2026-11-04 | Legitimate use: the State performing a function under law, or for sovereignty and integrity or security of the State. | SHARED | `LegitimateUseRegistry` (`STATE_FUNCTION_7C`, statute reference mandatory) | [V] |
| CMP-016 | s.7(d) | S1 | 2026-11-04 | Legitimate use: fulfilling a legal obligation to disclose information to the State, in line with that law's disclosure provisions. | SHARED | `LegitimateUseRegistry` (`LEGAL_DISCLOSURE_7D`) | [V] |
| CMP-017 | s.7(e) | S1 | 2026-11-04 | Legitimate use: complying with a judgment, decree or order (Indian), or a foreign judgment on contractual/civil claims. | SHARED | `LegitimateUseRegistry` (`COURT_ORDER_7E`, order reference attached) | [V] |
| CMP-018 | s.7(f)-(h) | S1 | 2026-11-04 | Legitimate uses: medical emergency threatening life or health (f); treatment or health services during an epidemic or outbreak (g); safety and assistance during a disaster (DM Act s.2(d)) or breakdown of public order (h). | SHARED | `LegitimateUseRegistry` (`MEDICAL_EMERGENCY_7F`, `EPIDEMIC_7G`, `DISASTER_7H`), time-boxed activation | [V] |
| CMP-019 | s.7(i) | S1 | 2026-11-04 | Legitimate use: employment purposes, safeguarding the employer (espionage, trade secrets, classified information), and services or benefits sought by an employee. This covers government staff workflows (HRMS, leave, APAR). | SHARED | `LegitimateUseRegistry` (`EMPLOYMENT_7I`) | [V] |
| CMP-020 | s.8(1)-(2) | S1 | 2026-11-04 | The DF stays responsible for processing by its Data Processors, and may engage a processor only under a valid contract. This covers the cloud/SDC/NIC host and the system integrator. | DF | Deployer process / not software (processor register field in `ProcessingRegister`) | [V] |
| CMP-021 | s.8(3) | S1 | 2026-11-04 | If data is used for a decision affecting the principal, or disclosed to another DF, ensure it is complete, accurate and consistent. | SHARED | Form validation, master-data checks, `rights` correction flow; `ProcessingRegister` flag "decision-making" | [V] |
| CMP-022 | s.8(4) | S1 | 2026-11-04 | Put in place appropriate technical and organisational measures to make the Act effective. | SHARED | Whole module + `report.ComplianceEvidenceReportGenerator` | [V] |
| CMP-023 | s.8(5) | S1 | 2026-11-04 | Protect personal data, including at processors, with reasonable security safeguards to prevent breach. Penalty up to Rs 250 crore (Schedule item 1). | SHARED | `security.SecurityPracticesChecklist`, `sangam-security` (deny-wins authZ, MFA, field masks), encryption | [V] |
| CMP-024 | s.8(6) | S1 | 2026-11-04 | On a personal data breach, intimate the Board and each affected principal (form per Rule 7). Penalty up to Rs 200 crore (item 2). | SHARED | `breach.BreachIncidentService` + BPMN `dpdp-breach-incident` | [V] |
| CMP-025 | s.8(7)(a)-(b) | S1 | 2026-11-04 | Erase data when consent is withdrawn or when the purpose is no longer served, whichever is earlier, unless law requires retention. Make processors erase too. **Disapplied for the State by s.17(4).** | SHARED | `retention.RetentionScheduler`, `ErasureService` (VI.5 tombstones), `LegalHoldStore` port; State-exemption toggle in `LegitimateUseRegistry` | [V] |
| CMP-026 | s.8(8), (11) | S1 | 2026-11-04 | The purpose is deemed no longer served if the principal does not approach the DF or exercise rights for the prescribed period. "Approach" means she initiated contact in person, electronically or physically. | SANGAM | `RetentionScheduler` (last-contact timestamp per principal per purpose) | [V] |
| CMP-027 | s.8(9) + Rule 9 | S1, S2 | 2026-11-04 | Publish the business contact information of the DPO (if applicable) or a person who can answer processing questions. | SHARED | `SdfObligationService` contact record + `NoticeTemplate`/portal footer | [V] |
| CMP-028 | s.8(10) | S1 | 2026-11-04 | Set up an effective grievance redressal mechanism. | SANGAM | BPMN `dpdp-grievance` | [V] |
| CMP-029 | s.9(1) (wording per S.O. 5458(E)) | S1, S5 | 2026-11-04 | Obtain the verifiable consent of a parent before processing a child's (<18) data. Obtain the lawful guardian's consent before processing data of a person with disability who has a lawful guardian. Penalty for s.9 breach up to Rs 200 crore. | SANGAM | `consent.VerifiableParentalConsentService` | [V] (wording change [S]) |
| CMP-030 | s.9(2) | S1 | 2026-11-04 | Do not process a child's data in a way likely to harm the child's well-being. | SHARED | `consent.ChildDataGuard` (purpose deny-list) + DPIA | [V] |
| CMP-031 | s.9(3) | S1 | 2026-11-04 | No tracking, behavioural monitoring or targeted advertising directed at children (subject to Fourth Schedule exemptions). | SANGAM | `ChildDataGuard` (blocks analytics/tracking/ad hooks when the principal is a child) | [V] |
| CMP-032 | s.10(1)-(2)(a) | S1 | 2026-11-04 | If notified as SDF, appoint a DPO who represents the SDF, is **based in India**, answers to the Board of Directors or equivalent governing body, and is the grievance contact point. | SDF | `SdfObligationService` (DPO record, India-residency attribute) | [V] |
| CMP-033 | s.10(2)(b) | S1 | 2026-11-04 | If SDF, appoint an independent data auditor to evaluate compliance. | SDF | `SdfObligationService` (auditor record + audit evidence) | [V] |
| CMP-034 | s.10(2)(c) (as amended by S.O. 5458(E): "periodic data audit") | S1, S5 | 2026-11-04 | If SDF, carry out a periodic DPIA (rights, purposes, risk assessment and management), a periodic data audit and other prescribed measures. Penalty up to Rs 150 crore. | SDF | `SdfObligationService` (DPIA records, annual cycle) | [V] |
| CMP-035 | s.11(1)(a)-(c) | S1 | 2026-11-04 | On request, give the principal (i) a summary of her data being processed and the processing activities, and (ii) the identities of all DFs and processors it was shared with, plus a description of the data shared. | SANGAM | `rights.DataPrincipalRightsService` + BPMN `dpdp-access-request` (uses `ProcessingRegister` + sharing log) | [V] |
| CMP-036 | s.11(2) | S1 | 2026-11-04 | Sharing with an authorised DF on written request, for offence or cyber-incident prevention, investigation or prosecution, need not be disclosed in the access response. | SANGAM | Sharing log flag `LAW_ENFORCEMENT_S11_2` excluded from access summary | [V] |
| CMP-037 | s.12(1)-(2) | S1 | 2026-11-04 | On request, correct inaccurate or misleading data, complete incomplete data and update data. **For the State, s.12(2) does not apply where processing involves no decision affecting the principal (s.17(4)).** | SANGAM | BPMN `dpdp-correction-request` | [V] |
| CMP-038 | s.12(3) | S1 | 2026-11-04 | On an erasure request, erase unless retention is needed for the specified purpose or by law. **Disapplied for the State by s.17(4).** | SHARED | BPMN `dpdp-erasure-request` + `ErasureService` + `LegalHoldStore` | [V] |
| CMP-039 | s.13(1)-(3) | S1 | 2026-11-04 | Provide readily available grievance redressal, and respond within the prescribed period (Rule 14(3): at most 90 days). The principal must exhaust this before approaching the Board. | SANGAM | BPMN `dpdp-grievance` (90-day timer, then "escalate to Board" link) | [V] |
| CMP-040 | s.14 | S1 | 2026-11-04 | The principal may nominate another individual to exercise her rights on death or incapacity (unsoundness of mind or infirmity of body). | SANGAM | BPMN `dpdp-nomination` + nominee authentication in rights flows | [V] |
| CMP-041 | s.15 | S1 | 2026-11-04 | Duties of principals: no impersonation, no suppression of material information for State-issued IDs, no false or frivolous grievances, only verifiably authentic information for correction/erasure. Penalty up to Rs 10,000. | SHARED | Declaration checkbox in rights/grievance forms; "frivolous" disposition code in `dpdp-grievance` | [V] |
| CMP-042 | s.16(1)-(2) | S1 | 2026-11-04 | Cross-border transfer is allowed except to countries the Centre notifies (negative list). Stricter sectoral laws prevail. | SHARED | `transfer.CrossBorderTransferGuard` (negative-list config) + `transfer.DataLocalisationPolicy` | [V] |
| CMP-043 | s.17(1)-(5) | S1 | 2026-11-04 | Exemptions. (1) Chapter II except ss.8(1) and 8(5), Chapter III and s.16 do not apply to: legal claims; courts and quasi-judicial or regulatory bodies; offence prevention and investigation; foreign principals under contract; schemes of arrangement; and loan-default asset tracing. (2)(a) The whole Act does not apply to State instrumentalities the Centre notifies (sovereignty, security, public order etc.). (2)(b) Research, archiving and statistics meeting the Second Schedule standards and not used for individual decisions. (3) Notified DFs and startups are exempt from s.5, 8(3), 8(7), 10, 11. (4) For the State: no s.8(7), no s.12(3), and no s.12(2) when no decision is made. (5) Time-bound notified exemptions within 5 years of commencement. Security (s.8(5)) still applies to s.17(1) cases. | SHARED | `LegitimateUseRegistry` exemption catalogue (`S17_1_A`...`S17_4`) per processing activity, with notification reference | [V] |
| CMP-044 | s.33 + Schedule | S1 | 2026-11-04 | Penalty caps: s.8(5) security Rs 250 cr; s.8(6) breach notice Rs 200 cr; s.9 children Rs 200 cr; s.10 SDF Rs 150 cr; s.15 principal duties Rs 10,000; voluntary undertaking breach up to the original cap; any other breach Rs 50 cr. These are not software requirements; use them for risk weighting. | DF | `report.ComplianceEvidenceReportGenerator` (risk weight per control) | [V] |
| CMP-045 | s.44(2)(a) (from 13-05-2027) | S1, S3 | 2026-11-04 | IT Act s.43A (body-corporate compensation for negligent SPDI handling) is omitted, along with its rule-making power s.87(2)(ob). The SPDI Rules 2011 lose their parent provision. Until then they apply to body-corporate deployers. | DF | Configuration flag `spdi.applicable=true` until 2027-05-13 | [V] |
| CMP-046 | s.44(3) (in force 13-11-2025) | S1, S3 | 2026-11-04 | RTI Act s.8(1)(j) now exempts "information which relates to personal information" (the public-interest override was removed). Public information officers can rely on this exemption. | SHARED | RTI disclosure workflows: personal-data redaction step (field classification via `sangam-security` masks) | [V] |

### 3.2 DPDP Rules 2025 (S2) — Rules 3, 5-16, 22, 23 from 13-05-2027; Rule 4 from 13-11-2026

| ID | Legal source | Link | Seen | Obligation (plain words) | Bearer | Sangam feature | Status |
|---|---|---|---|---|---|---|---|
| CMP-047 | r.3(a) | S2 | 2026-11-04 | The notice stands alone: it is understandable on its own, not buried in terms and conditions. | SANGAM | `NoticeTemplate` (standalone render) | [V] |
| CMP-048 | r.3(b)(i)-(ii) | S2 | 2026-11-04 | The notice gives, in clear and plain language, (i) an **itemised description** of the personal data and (ii) the specified purpose(s), with a specific description of the goods, services or uses enabled. | SANGAM | `NoticeTemplate` (item list + purpose list, mandatory fields) | [V] |
| CMP-049 | r.3(c)(i)-(iii) | S2 | 2026-11-04 | The notice gives the particular communication link to the DF's website/app and other means to (i) withdraw consent as easily as it was given, (ii) exercise rights, and (iii) complain to the Board. | SANGAM | `NoticeTemplate` (three mandatory links; Board URL configurable) | [V] |
| CMP-050 | r.4(1)-(6) + First Sch. Part A (from 13-11-2026) | S2 | 2026-11-04 | Consent Managers register with the Board. Conditions include: an Indian company, net worth of at least Rs 2 crore, and independent certification of the interoperable platform against the Board's standards. Relevant to Sangam only as an integrating DF. | DF | `ConsentManagerPort` (registration-number check) | [V] |
| CMP-051 | First Sch. Part B items 1-4 | S2 | 2026-11-04 | CM obligations: route consent directly or via another DF; data must not be readable by the CM; keep a record of consents given, denied and withdrawn, of notices, and of sharing; give access and a machine-readable export; **keep records at least 7 years**. The DF side must exchange artefacts and logs in a compatible way. | SHARED | `ConsentManagerPort` (artefact exchange, notice reference, sharing events, ledger reconciliation) | [V] |
| CMP-052 | r.5(1)-(2) + Second Sch. (a)-(f), (h) | S2 | 2026-11-04 | State processing for s.7(b) benefits (under law, policy or public funds) must meet these standards: lawful; limited to s.7(b) uses; data minimisation; reasonable efforts at completeness, accuracy and consistency; retention only while required or by law; reasonable security safeguards; accountability of the person deciding purpose and means. | SHARED | `LegitimateUseRegistry` (`STATE_BENEFIT_7B` requires a minimised field list, retention schedule and owner), `RetentionSchedule`, `SecurityPracticesChecklist` | [V] |
| CMP-053 | Second Sch. (g)(i)-(iii) | S2 | 2026-11-04 | When processing under s.7(b), give the principal an **intimation** containing: business contact of a person who can answer questions; the link to the website/app and other means to exercise rights; and consistency with other applicable Central policy or law. | SANGAM | `ConsentNoticeService` "State intimation" template (Rule 5 variant, no consent capture) | [V] |
| CMP-054 | r.6(1)(a) | S2 | 2026-11-04 | Secure data through encryption, obfuscation, masking or virtual tokens. | SHARED | `sangam-security` field masks + at-rest/in-transit encryption; `SecurityPracticesChecklist` | [V] |
| CMP-055 | r.6(1)(b) | S2 | 2026-11-04 | Control access to the computer resources used by the DF and processor. | SHARED | `sangam-security` (deny-wins authZ, MFA, VI.4 access control) | [V] |
| CMP-056 | r.6(1)(c) | S2 | 2026-11-04 | Provide visibility on access to personal data through logs, monitoring and review, so that unauthorised access can be detected, investigated and remediated. | SANGAM | Core `AuditChain` (read and write access events on personal-data fields), `logs.AuditIntegrityVerifier`, alerting hook | [V] |
| CMP-057 | r.6(1)(d) | S2 | 2026-11-04 | Provide continuity measures, e.g. data backups, for loss of confidentiality, integrity or availability. | SHARED | Backup/restore evidence item in `SecurityPracticesChecklist`; deployer runs backups | [V] |
| CMP-058 | r.6(1)(e) | S2 | 2026-11-04 | **Retain such logs and personal data for one year** for detection, investigation and continuity, unless another law requires otherwise. | SANGAM | `logs.LogRetentionPolicy` (min 365 days for access logs) | [V] |
| CMP-059 | r.6(1)(f) | S2 | 2026-11-04 | DF-processor contracts must contain security safeguard clauses. | DF | Deployer process / not software (contract template annex in docs) | [V] |
| CMP-060 | r.6(1)(g) | S2 | 2026-11-04 | Adopt appropriate technical and organisational measures to make the safeguards effective. | SHARED | `SecurityPracticesChecklist` + evidence report | [V] |
| CMP-061 | r.7(1)(a)-(e) | S2 | 2026-11-04 | On becoming aware of a breach, tell **each affected principal**, without delay and concisely, through her user account or registered communication mode: (a) description (nature, extent, timing); (b) likely consequences for her; (c) mitigation measures taken or in progress; (d) safety measures she can take; (e) business contact of a person who can answer queries. | SANGAM | `BreachIncidentService` principal-notice template (5 mandatory fields) + notification channels | [V] |
| CMP-062 | r.7(2)(a) | S2 | 2026-11-04 | Tell the **Board without delay**: description of the breach, including nature, extent, timing, **location** and likely impact. | SANGAM | BPMN `dpdp-breach-incident` (initial Board intimation task, no fixed hour) | [V] |
| CMP-063 | r.7(2)(b)(i)-(vi) | S2 | 2026-11-04 | **Within 72 hours of becoming aware** (or a longer period the Board allows on written request), give the Board: (i) updated, detailed description; (ii) broad facts on the events, circumstances and reasons; (iii) mitigation measures implemented or proposed; (iv) findings about the person who caused it; (v) remedial measures to prevent recurrence; (vi) a report on the intimations given to principals. | SANGAM | BPMN `dpdp-breach-incident` (72h timer from `awareAt`; extension-granted variable) | [V] |
| CMP-064 | r.8(1) + Third Sch. | S2 | 2026-11-04 | Erase data after the Third Schedule period if the principal has neither approached nor exercised rights. **This applies only to**: e-commerce entities with ≥2 crore registered users in India; online gaming intermediaries with ≥50 lakh; social media intermediaries with ≥2 crore. Period: **3 years** from the last approach or exercise of rights, or from commencement of the Rules, whichever is latest. It does not cover the user-account access or virtual-token access purposes. Government deployers usually fall outside these classes, and the State is exempt from s.8(7) by s.17(4). | SHARED | `RetentionSchedule` (Third Schedule classes as presets, period configurable; default "purpose-based" for government) | [V] |
| CMP-065 | r.8(2) | S2 | 2026-11-04 | **At least 48 hours before** erasure under r.8, tell the principal that data will be erased unless she logs in, contacts the DF or exercises rights. | SANGAM | `RetentionScheduler` (pre-erasure notice at T-48h minimum; cancels erasure on contact) | [V] |
| CMP-066 | r.8(3) + Seventh Sch. | S2 | 2026-11-04 | For **all** processing (by DF or processor), retain the personal data, associated traffic data and processing logs for **at least one year from the date of processing**, for the Seventh Schedule purposes. Then erase, unless other law or a government notification requires longer. The rule's illustration says this holds even if the account is deleted, and processors must also retain. | SANGAM | `LogRetentionPolicy` (floor 1 year from processing date), `RetentionScheduler` (erasure blocked before floor), `ErasureService` | [V] |
| CMP-067 | r.9 | S2 | 2026-11-04 | Prominently publish on the website/app, and **mention in every response to a rights request**, the business contact of the DPO (if applicable) or a person who can answer questions. | SANGAM | `DataPrincipalRightsService` response templates (contact footer mandatory) | [V] |
| CMP-068 | r.10(1)(a) | S2 | 2026-11-04 | Before processing a child's data, obtain the parent's verifiable consent. Do due diligence that the parent is an identifiable **adult (18+)** using **reliable identity and age details already held** by the DF. | SANGAM | `VerifiableParentalConsentService` (mode `EXISTING_RELIABLE_RECORD`) | [V] |
| CMP-069 | r.10(1)(b), (2) | S2 | 2026-11-04 | Or use identity/age details voluntarily provided (i) by the individual, or (ii) through a **virtual token** mapped to those details, issued by an authorised entity (one entrusted by law or government, or its appointee), including details or tokens verified by a **Digital Locker service provider**. | SANGAM | `VerifiableParentalConsentService` (modes `SELF_DECLARED_VERIFIED`, `VIRTUAL_TOKEN`, `DIGILOCKER`) | [V] |
| CMP-070 | r.11 | S2 | 2026-11-04 | For a person with disability, do due diligence that the guardian was appointed by (a) a court, (b) a designated authority under RPwD Act 2016 s.15, or (c) a local level committee under the National Trust Act 1999 s.13, under the applicable guardianship law. | SANGAM | `VerifiableParentalConsentService` guardian path (appointing-body type + order reference + document upload + verifier sign-off) | [V] |
| CMP-071 | r.12 + Fourth Sch. Part A | S2 | 2026-11-04 | s.9(1) and (3) do not apply to: clinical/mental health establishments and healthcare professionals (health services); allied healthcare professionals (treatment plan); **educational institutions** (tracking/monitoring for educational activities or child safety); crèches/day care (safety); child transport providers (location during travel). Each is limited to what is necessary. | SANGAM | `ChildDataGuard` exemption catalogue `FOURTH_SCHEDULE_A_1..5` | [V] |
| CMP-072 | r.12 + Fourth Sch. Part B | S2 | 2026-11-04 | Purpose exemptions from s.9(1) and (3): (1) exercising a power or function in the child's interest under law; (2) **s.7(b) subsidy/benefit/certificate/licence for a child**; (3) email-only account creation; (4) real-time location for the child's safety; (5) blocking harmful content or ads; (6) confirming the user is not a child / Rule 10 due diligence. | SANGAM | `ChildDataGuard` exemption catalogue `FOURTH_SCHEDULE_B_1..6` | [V] |
| CMP-073 | r.13(1)-(2) | S2 | 2026-11-04 | If SDF: **once every 12 months** from notification, carry out a DPIA and an audit. The assessor must give the Board a report of significant observations. | SDF | `SdfObligationService` (12-month cycle timer, Board report artefact) | [V] |
| CMP-074 | r.13(3) | S2 | 2026-11-04 | If SDF: due diligence that technical measures, **including algorithmic software**, used to host, display, upload, modify, publish, transmit, store, update or share personal data pose no likely risk to principals' rights. This covers Sangam's DMN rules and any AI/ML scoring. | SDF | `SdfObligationService` algorithmic due-diligence record (DMN/decision models inventory) | [V] |
| CMP-075 | r.13(4)-(5) | S2 | 2026-11-04 | If SDF: personal data specified by the Centre (on a committee's recommendation), and the traffic data of its flow, must **not be transferred outside India**. | SDF | `DataLocalisationPolicy` (specified-data category hard block) | [V] |
| CMP-076 | r.14(1), (5) | S2 | 2026-11-04 | Prominently publish (a) the means to make a rights request and (b) the identifier needed: username, application reference number, enrolment ID, email, mobile, licence number etc. | SHARED | `DataPrincipalRightsService` public "how to exercise rights" page; identifier types configured by deployer | [V] |
| CMP-077 | r.14(2) | S2 | 2026-11-04 | The principal requests through the published means, giving the required particulars. The system must authenticate the requester against the identifier. | SANGAM | Rights BPMN packs (identity-verification step) | [V] |
| CMP-078 | r.14(3) | S2 | 2026-11-04 | Publish the grievance response period, **a reasonable period not exceeding 90 days**, and implement technical and organisational measures to meet it. | SANGAM | BPMN `dpdp-grievance` (SLA configurable ≤ 90 days, hard cap 90; escalation and breach alerts) | [V] |
| CMP-079 | r.14(4) | S2 | 2026-11-04 | The principal may nominate one or more individuals, using the DF's means and particulars, under its terms and applicable law. | SANGAM | BPMN `dpdp-nomination` (multiple nominees) | [V] |
| CMP-080 | r.15 | S2 | 2026-11-04 | Transfer abroad is subject to any Central general or special order on making data available to a foreign State or entities it controls. | SHARED | `CrossBorderTransferGuard` (order registry, deny by default in government profile) | [V] |
| CMP-081 | r.16 + Second Sch. | S2 | 2026-11-04 | The Act does not apply to research, archiving or statistical processing done under the Second Schedule standards (lawful, minimised, accurate, retention-limited, secure, accountable). | SHARED | `LegitimateUseRegistry` (`S17_2_B_RESEARCH`, requires the Second Schedule checklist) | [V] |
| CMP-082 | r.19(9) (in force) | S2 | 2026-11-04 | The Board completes inquiries within 6 months of the intimation or complaint (extendable by 3 months at a time). The DF must be able to produce evidence quickly. | DF | `ComplianceEvidenceReportGenerator` (case export) | [V] |
| CMP-083 | r.20, r.22(1)-(3) | S2 | 2026-11-04 | The Board and Appellate Tribunal (TDSAT) work as digital offices. Appeals are filed digitally with the TRAI-Act fee, payable by UPI. This is a deployer process. | DF | Deployer process / not software | [V] |
| CMP-084 | r.23(1)-(2) + Seventh Sch. | S2 | 2026-11-04 | The Centre, through authorised persons, may require a DF or intermediary to furnish information. Purposes: State use for sovereignty and security; State functions or legal disclosures; SDF assessment. It may forbid telling the principal where security is involved. | SHARED | Information-request workflow with "non-disclosure to principal" flag that suppresses the access-summary entry; `AuditChain` record | [V] |

### 3.3 CERT-In (S6, S7, S8, S9, S10)

| ID | Legal source | Link | Seen | Obligation (plain words) | Bearer | Sangam feature | Status |
|---|---|---|---|---|---|---|---|
| CMP-085 | Directions para (i) | S6 | 2026-11-04 | All service providers, intermediaries, data centres, body corporates **and Government organisations** synchronise all ICT system clocks to the NTP servers of **NIC or NPL**, or servers traceable to them. Multi-geography entities may use another accurate source that does not deviate from NPL/NIC. | SHARED | `logs.NtpTimeSourceVerifier` (configured sources; drift check) | [V] |
| CMP-086 | FAQ Q40-Q43 | S7 | 2026-11-04 | IST is not mandatory; UTC is fine, but **record the time zone with the timestamp**. NIC servers: `samay1.nic.in`, `samay2.nic.in`; NPL: `time.nplindia.org`. Cloud-native time is allowed if it does not deviate. | SANGAM | `NtpTimeSourceVerifier` defaults; `AuditChain` timestamps as ISO-8601 with offset | [V] |
| CMP-087 | Directions para (ii) + Annexure I | S6 | 2026-11-04 | Report the Annexure I incident types (20 types; list in §5) to CERT-In **within 6 hours of noticing**, or of being told about, the incident. Channels: email incident@cert-in.org.in, phone 1800-11-4949, fax 1800-11-6969, using the format on the CERT-In website. | SHARED | `breach.CertInIncidentReporter`, `breach.CertInIncidentType`, BPMN `certin-incident-report` (6h timer from `noticedAt`) | [V] |
| CMP-088 | FAQ Q30 | S7 | 2026-11-04 | If all the details in the CERT-In incident reporting form are not available within 6 hours, report what is known and add the rest later within reasonable time. | SANGAM | `certin-incident-report` (initial plus follow-up report tasks) | [V] |
| CMP-089 | Directions para (iii) | S6 | 2026-11-04 | Act on CERT-In orders and directions and give information or assistance in the format (up to near real-time) and timeframe specified. Non-compliance is treated as a breach. | SHARED | Log/evidence export endpoint; `ComplianceEvidenceReportGenerator` | [V] |
| CMP-090 | Directions para (iii) + Annexure II | S6 | 2026-11-04 | Designate a **Point of Contact** to interface with CERT-In. Send name, designation, organisation, office address, email, mobile, office phone and fax to info@cert-in.org.in, and keep it updated. | SHARED | `CertInIncidentReporter` PoC configuration record (Annexure II fields) | [V] |
| CMP-091 | Directions para (iv) | S6 | 2026-11-04 | Enable logs of **all ICT systems** and keep them securely for a **rolling 180 days within Indian jurisdiction**. Provide them with any incident report or on CERT-In's direction. | SANGAM | `logs.LogRetentionPolicy` (≥180 days; India-only store), log export | [V] |
| CMP-092 | FAQ Q35-Q37 | S7 | 2026-11-04 | The FAQ (Q35) says logs may also be stored outside India if they can be produced in reasonable time. Q36 says entities serving Indian users must maintain logs in Indian jurisdiction. For government, keep them in India (see CMP-104, CMP-131). Log types include firewall, IPS, SIEM, web, DB, mail, proxy, application, SSH and VPN. **Record both successful and unsuccessful events.** | SANGAM | `LogRetentionPolicy` (India-only default) + audit of failed authN/authZ events | [V] |
| CMP-093 | FAQ Q38 | S7 | 2026-11-04 | Log requests come from a CERT-In officer not below Deputy Secretary rank. Verify the requester before exporting. | SHARED | Log-export workflow with approver step + `AuditChain` | [V] |
| CMP-094 | Directions para (v) | S6 | 2026-11-04 | Data centres, VPS, cloud and VPN providers keep subscriber KYC records (validated names, hire period, IPs, registration email/IP/timestamp, purpose, validated address/phone, ownership pattern) for **5 years** after cancellation. **This does not apply to Sangam as software.** It applies to the deployer's hosting provider (MeitY CSP/SDC/NIC). Enterprise VPNs are excluded (FAQ Q34). | DF | Deployer process / not software | [V] |
| CMP-095 | Directions para (vi) | S6 | 2026-11-04 | Virtual-asset service providers, exchanges and custodian wallets keep KYC and transaction records for 5 years. **Not applicable** to a government workflow platform. | DF | Not applicable | [V] |
| CMP-096 | Directions closing para; IT Act s.70B(7) as amended | S6, S13 | 2026-11-04 | Failing to furnish information or comply may draw s.70B(7) action. The fine was raised from Rs 1 lakh to Rs 1 crore by the Jan Vishwas Act 2023 (in force 30-11-2023). | DF | Risk weighting in evidence report | [V] |
| CMP-097 | Gov. Entities Guidelines s.5 (IAM) | S8 | 2026-11-04 | Unique user IDs; identity provisioning through a workflow with checks; **review at least every six months** with a report to the head of department; immediate deactivation on exit; RBAC from an access matrix; periodic privilege review. | SHARED | `sangam-security` + an access-review BPMN (6-monthly timer) | [V] |
| CMP-098 | Gov. Entities Guidelines s.5.3-5.4, Annexure s.5.22-5.23 | S8 | 2026-11-04 | MFA as far as possible. Government users log in through a national SSO (e-Pramaan, **Parichay/Jan Parichay (NIC)** or DigiLocker). Public users get MFA/2FA. | SANGAM | `sangam-security` (MFA; OIDC/SAML adapters for Parichay, e-Pramaan, DigiLocker) | [V] |
| CMP-099 | Gov. Entities Guidelines s.5.6 | S8 | 2026-11-04 | **Terminate sessions after 15 minutes of inactivity**, with re-authentication. Encrypt passwords in transit. Minimum 8 characters, complex. Change at least every 120 days (where passwords are used). | SANGAM | `sangam-security` session policy (idle timeout default 15 min; password policy) | [V] |
| CMP-100 | Gov. Entities Guidelines s.5.9 | S8 | 2026-11-04 | Segregation of duties; separate admin accounts per role with least privilege. | SANGAM | `sangam-security` role model (maker-checker, admin role separation) | [V] |
| CMP-101 | Gov. Entities Guidelines s.6.1 | S8 | 2026-11-04 | Secure SDLC; privacy of citizen data at each stage; server-side validation; HTTPS with a valid certificate; safe error handling; **VAPT at least yearly or on change, including APIs**; continuous log monitoring with alerts; binary integrity checks. | SHARED | Project SDLC (SAST/DAST, SBOM), API security tests; deployer commissions VAPT | [V] |
| CMP-102 | Gov. Entities Guidelines s.7 | S8 | 2026-11-04 | Classify personal data and encrypt it in transit and at rest; DLP; harden DB defaults; breach detection and alerting; documented backup policy (data, DB, keys, configs); fix IDOR, SQL injection and insecure APIs; micro-segmentation for DB access. | SHARED | `SecurityPracticesChecklist` (data classification on fields, encryption, backup evidence) | [V] |
| CMP-103 | Gov. Entities Guidelines s.9 | S8 | 2026-11-04 | Cloud: shared-responsibility review; no public storage exposure; least privilege; cloud-native logging; MFA on accounts. | DF | Deployer process (deployment guide checklist) | [V] |
| CMP-104 | Gov. Entities Guidelines Annexure s.5.1 | S8 | 2026-11-04 | Host applications **only in government data centres or MeitY-empanelled CSPs**. Never inside a Ministry/Department LAN segment. | SHARED | `transfer.DataLocalisationPolicy` (hosting attestation: NIC / SDC / MeitY CSP) | [V] |
| CMP-105 | Gov. Entities Guidelines Annexure s.5.2, 5.19, 5.20 | S8 | 2026-11-04 | Audit applications through a **CERT-In empanelled auditor before hosting**, at least annually and after major changes. Host only after security-audit clearance. Share reports with the CISO. | DF | `SecurityPracticesChecklist` evidence item (audit certificate, date, auditor); deployer commissions the audit | [V] |
| CMP-106 | Gov. Entities Guidelines Annexure s.5.8-5.11, 6.1-6.2 | S8 | 2026-11-04 | Use NIC DNS (1.10.10.10 / 2409::1) and NIC NTP; review server logs daily; keep logs on a **separate server**; logging on all ICT systems including applications and DBs; **retain logs at least 180 days** unless the CISO says otherwise; onboard to NIC **PRATIMAAN** (SIEM) / IPAM, with State departments via state NIC centres. | SHARED | `LogRetentionPolicy` (external log sink: syslog/SIEM forwarder), `NtpTimeSourceVerifier` | [V] |
| CMP-107 | Gov. Entities Guidelines s.15.3 | S8 | 2026-11-04 | Internal security audit **at least every 6 months**; third-party audit **at least yearly**; audit before and after major implementation; follow-up audits. | DF | `SecurityPracticesChecklist` audit calendar | [V] |
| CMP-108 | Gov. Entities Guidelines s.14.3-14.8 | S8 | 2026-11-04 | Documented incident handling procedures; incident analysis reports; up-to-date point-of-contact list; active log monitoring by a security team. | SHARED | `certin-incident-report` + `dpdp-breach-incident` BPMN (post-incident report task) | [V] |
| CMP-109 | Comprehensive Cyber Security Audit Policy Guidelines v1.0 | S9 | 2026-11-04 | Comprehensive audit **at least annually**, scoped to an up-to-date asset inventory, risk-based and evidence-based, using CERT-In templates. Applications handling sensitive personal data use the CERT-In audit checklist (reported as 282 control points). Binding on empanelled auditors and auditees. | DF | `SecurityPracticesChecklist` asset inventory + evidence export | [S] |
| CMP-110 | CERT-In BOM Guidelines v2.0 | S10 | 2026-11-04 | SBOM (21 minimum fields reported), CBOM, AIBOM etc. Applicable to public sector and government entities and their software suppliers, especially in procurement. The guidelines are described as voluntary but expected in procurement. | SANGAM | Build: CycloneDX SBOM per release (+ CBOM for crypto libraries) published with artefacts | [S] |

### 3.4 IT Act 2000 and SPDI Rules 2011 (S11, S13, S14, S15)

| ID | Legal source | Link | Seen | Obligation (plain words) | Bearer | Sangam feature | Status |
|---|---|---|---|---|---|---|---|
| CMP-111 | IT Act s.43A (until omitted 13-05-2027) | S1, S3, S14 | 2026-11-04 | A **body corporate** that is negligent with SPDI and causes wrongful loss or gain must pay compensation. A government department is not a "body corporate", but PSUs, corporations and system integrators may be. Ends when DPDP s.44(2) commences. | DF | `SecurityPracticesChecklist` (SPDI profile) | [S] |
| CMP-112 | IT Act s.72A (as amended by Jan Vishwas Act) | S13 | 2026-11-04 | Disclosing personal information obtained under a lawful contract, without consent or in breach of contract, with intent to cause wrongful loss or gain, carries a **penalty up to Rs 25 lakh** (no longer imprisonment, from 30-11-2023). Relevant to Sangam vendors and integrators. | DF | Access logging (`AuditChain`) as evidence; processor contracts | [V] |
| CMP-113 | IT Act s.72 (as amended) | S13 | 2026-11-04 | A person given powers under the IT Act who discloses records without consent faces a penalty up to Rs 5 lakh. | DF | Deployer process | [V] |
| CMP-114 | IT Act s.66, s.66E | S14 | 2026-11-04 | Criminal offences for computer-related acts under s.43 (hacking, damage, data theft), and for capturing or publishing images of a private area without consent. Sangam must not facilitate these; access controls and logs support investigation. | SHARED | `sangam-security` + `AuditChain` | [U] |
| CMP-115 | IT Act s.67C (as amended) | S13, S14 | 2026-11-04 | **Intermediaries** must preserve and retain information as the Centre specifies. Non-compliance draws a penalty up to Rs 25 lakh (amended). Applies to the deployer only if it acts as an intermediary. | DF | `LogRetentionPolicy` configurable retention profiles | [V] (penalty) / [S] (duty) |
| CMP-116 | IT Act s.69, s.69A, s.69B (+ 2009 Rules) | S13, S14 | 2026-11-04 | Lawful interception, monitoring and decryption orders (s.69); blocking orders (s.69A); traffic-data monitoring for cyber security (s.69B; penalty amended to up to 1 year or Rs 1 crore fine). The deployer must be able to comply with lawful orders, keeping orders confidential. | SHARED | Lawful-request workflow (restricted visibility, `AuditChain`) | [S] |
| CMP-117 | IT Act s.70 + Protected System Rules 2018 | S14, S15 | 2026-11-04 | If a Sangam deployment is declared a **protected system** (CII), the organisation must have a CISO, an ISMS, regular audits, and report incidents to NCIIPC/CERT-In. Access is limited to authorised persons. | DF | `SecurityPracticesChecklist` "protected system" profile | [U] |
| CMP-118 | IT Act s.70B(6)-(7) | S6, S13 | 2026-11-04 | CERT-In may call for information and give directions. Failing to comply carries up to 1 year imprisonment or a fine up to Rs 1 crore (amended). | DF | See CMP-085 to CMP-096 | [V] (fine) |
| CMP-119 | SPDI r.3 | S11 | 2026-11-04 | SPDI means: password; financial info (bank, card, payment instrument); physical, physiological and mental health; sexual orientation; medical records; biometrics. Public-domain and RTI-furnished data is excluded. Sangam field classification should tag these. | SANGAM | `sangam-security` field classification `SPDI_*` tags | [V] |
| CMP-120 | SPDI r.4 | S11 | 2026-11-04 | Publish a privacy policy on the website covering: practices; types of SPDI collected; purpose; disclosure; security practices. | SHARED | `ConsentNoticeService` privacy-policy page generator from `ProcessingRegister` | [V] |
| CMP-121 | SPDI r.5(1)-(5), (7) | S11 | 2026-11-04 | Obtain written (letter, fax, email) consent for the purpose before collecting SPDI. Collect only for a lawful, necessary purpose. Tell the person the fact of collection, purpose, recipients, and the collecting and retaining agency. Retain no longer than needed. Use only for the stated purpose. Offer the option not to provide, and to withdraw in writing. | SHARED | `ConsentService` (electronic written consent artefact), `PurposeBindingGuard`, `RetentionSchedule` | [V] |
| CMP-122 | SPDI r.5(6) | S11 | 2026-11-04 | Let providers review their information and correct inaccurate or deficient data. | SANGAM | `dpdp-access-request`, `dpdp-correction-request` | [V] |
| CMP-123 | SPDI r.5(9) | S11 | 2026-11-04 | Designate a **Grievance Officer**, publish name and contact on the website, and **redress grievances within one month** of receipt. Stricter than DPDP's 90 days for SPDI-covered body corporates until 13-05-2027. | SHARED | `dpdp-grievance` SLA profile `SPDI_ONE_MONTH` (30 days) | [V] |
| CMP-124 | SPDI r.6 | S11 | 2026-11-04 | Disclosing SPDI to third parties needs prior permission, unless contracted or legally required. Government agencies mandated by law get it on a written request stating the purpose. Never publish SPDI, and recipients must not disclose further. | SHARED | Sharing workflow with approval and purpose capture; sharing log | [V] |
| CMP-125 | SPDI r.7 | S11 | 2026-11-04 | Transfer SPDI (in India or abroad) only to an entity giving the same level of protection, and only if necessary for the contract or with consent. | SHARED | `CrossBorderTransferGuard` + recipient register | [V] |
| CMP-126 | SPDI r.8(1)-(4) | S11 | 2026-11-04 | Reasonable security practices: a documented information security programme and policies (managerial, technical, operational and physical controls), demonstrable after a breach. **IS/ISO/IEC 27001** is deemed compliant if certified or audited by an independent government-approved auditor **at least once a year** or on significant upgrade. | SHARED | `SecurityPracticesChecklist` (ISO 27001 Annex A mapping + audit evidence) | [V] |

### 3.5 Intermediary Guidelines and Digital Media Ethics Code Rules 2021 (S12)

> **Applicability:** a government department running Sangam to deliver its own services is ordinarily a Data Fiduciary, not an "intermediary" (one that receives, stores or transmits records *on behalf of another person*). These rules matter only where the deployer runs a portal that hosts third-party user content, or a shared-services platform for other agencies (e.g. a state-wide e-office SaaS run by a state IT agency). Treat CMP-127 to CMP-130 as **conditional**.

| ID | Legal source | Link | Seen | Obligation (plain words) | Bearer | Sangam feature | Status |
|---|---|---|---|---|---|---|---|
| CMP-127 | r.3(1)(g)-(h) | S12 | 2026-11-04 | (g) Preserve removed or disabled content and associated records for **180 days** (or longer if a court or agency asks). (h) Keep a user's registration information for **180 days after cancellation or withdrawal** of registration. | SHARED | `RetentionSchedule` profile `INTERMEDIARY_180D` (post-deregistration hold, overrides erasure) | [V] |
| CMP-128 | r.3(1)(i) | S12 | 2026-11-04 | Secure the computer resource following the SPDI Rules 2011 reasonable security practices. | SHARED | `SecurityPracticesChecklist` | [V] |
| CMP-129 | r.3(1)(j) | S12 | 2026-11-04 | Provide information or assistance to lawfully authorised agencies **no later than 72 hours** after receiving an order (identity verification, offences, cyber security incidents). | SHARED | Lawful-request workflow (72h timer) | [V] |
| CMP-130 | r.3(2)(a)-(b) | S12 | 2026-11-04 | Publish the Grievance Officer's name and contact and the complaint mechanism. **Acknowledge within 24 hours** and **resolve within 15 days**. Removal requests under r.3(1)(b) are resolved within 72 hours. Private-area/impersonation content is removed within 24 hours. Appeal to the Grievance Appellate Committee within 30 days. | SHARED | `dpdp-grievance` SLA profile `INTERMEDIARY_24H_15D` | [V] |

### 3.6 Central government IT policies (S8, S16, S17 and others)

| ID | Legal source | Link | Seen | Obligation (plain words) | Bearer | Sangam feature | Status |
|---|---|---|---|---|---|---|---|
| CMP-131 | MeitY Cloud contractual-terms guidelines (2017); CSP empanelment | S16 | 2026-11-04 | Government data on cloud must **reside in India**, with a localisation clause in contracts. Use only MeitY-empanelled CSPs that hold a valid STQC audit (annual surveillance audit). | SHARED | `DataLocalisationPolicy` (India-only regions; empanelment ID + STQC certificate expiry tracked) | [S] |
| CMP-132 | MeghRaj / GI Cloud policy; National Data Centre Policy (draft 2020) | S16 | 2026-11-04 | Use GI Cloud and empanelled data centres for government workloads; prefer government community cloud. Policy direction rather than a statute. | DF | `DataLocalisationPolicy` hosting-type enum (`NIC`, `SDC`, `MEITY_CSP_GCC`, `MEITY_CSP_PUBLIC`) | [U] |
| CMP-133 | GIGW 3.0 (2023): accessibility | S17 | 2026-11-04 | Government websites and apps conform to **WCAG 2.1 Level AA**, based on RPwD Act 2016, IT Act and ISO 23026. Applies to citizen-facing Sangam forms, tasklists and notices. | SANGAM | `sangam-ui` (not this module); notices rendered by `NoticeTemplate` must be accessible HTML | [S] |
| CMP-134 | GIGW 3.0: language | S17 | 2026-11-04 | Government sites are expected to be bilingual (English and Hindi) and to offer regional languages. The exact clause wording was not checked. | SANGAM | `sangam-ui` i18n; `config.IndianLanguage` | [U] |
| CMP-135 | GIGW 3.0: website policies and security | S17 | 2026-11-04 | Publish website policies (privacy, copyright, hyperlinking, terms) and follow the CERT-In-drafted security chapter (HTTPS, security audit before hosting). | SHARED | Privacy-policy page generator (CMP-120); security items per CMP-101, CMP-105 | [S] |
| CMP-136 | RPwD Act 2016 s.40, s.46 and RPwD Rules 2017 r.15 | — | 2026-11-04 | Service providers and government establishments must make ICT accessible to the prescribed standard (GIGW). | SANGAM | `sangam-ui` | [U] |
| CMP-137 | Email Policy of GoI (2014) / Policy on Use of IT Resources (2014) | — | 2026-11-04 | Official communication uses the government email service (gov.in / nic.in). Official data is not routed through private email services. Sangam's notification sender must support the NIC SMTP relay and gov.in sender domains. | SHARED | Notification connector config (NIC mail relay, DKIM/SPF); no third-party email SaaS in government profile | [U] |
| CMP-138 | NISPG (MHA, National Information Security Policy & Guidelines) | — | 2026-11-04 | Classify government information (Top Secret, Secret, Confidential, Restricted) and apply handling controls. The document is not public, so obligations cannot be verified. | SHARED | `sangam-security` classification label on process and document data | [U] |
| CMP-139 | IT Act s.43A / SPDI r.8 and S8 Annexure 5.21 | S8, S11 | 2026-11-04 | All endpoints HTTPS with a valid TLS certificate (no plain HTTP in production). | SANGAM | Spring Boot TLS config; HSTS by default | [V] |

### 3.7 Aadhaar (S18)

| ID | Legal source | Link | Seen | Obligation (plain words) | Bearer | Sangam feature | Status |
|---|---|---|---|---|---|---|---|
| CMP-140 | Aadhaar Act s.29(1)-(4) | S18 | 2026-11-04 | Core biometric information must never be shared or stored beyond authentication. Identity information is used only for the purpose submitted. The Aadhaar number must not be published, displayed or posted publicly except as regulations allow. | SHARED | `sangam-security` field masks (Aadhaar shown as `XXXX-XXXX-1234`), biometric fields prohibited in form schema | [S] |
| CMP-141 | Aadhaar (Sharing of Information) Regs 2016 reg. 6 | S18 | 2026-11-04 | Do not publicly display the Aadhaar number. Do not make public any database or record containing Aadhaar numbers unless they are redacted or masked. Mask by default in all displays (UIDAI guidance). | SANGAM | Field mask `AADHAAR` default-on in UI, exports, PDFs, logs and audit payloads | [S] |
| CMP-142 | UIDAI ADV circular 25-07-2017 → Circular No. 8 of 2025 (18-07-2025) + ADV/HSM FAQs (03-11-2025) | S18 | 2026-11-04 | **Requesting Entities (AUA/KUA/Sub-AUA)** store Aadhaar numbers, UID tokens, e-KYC XML/PDF and Aadhaar-linked demographic data **only in an Aadhaar Data Vault**. The ADV is encrypted with AES-256 or above, keys are kept in an HSM, and it is hosted on RE premises, a MeitY-empanelled Government Community Cloud, or ADV-as-a-service. Use **reference keys** in all business systems; strict access control and logging. ADV duties do **not** apply to non-RE entities (e.g. offline verification or employee records). Data in the ADV must come post-authentication from UIDAI. | SHARED | Port `AadhaarVaultPort` (reference-key only in Sangam DB; vault external); field mask; recommend new port in `sangam-integration` | [S] |
| CMP-143 | UIDAI Circular No. 14 of 2025 | S18 | 2026-11-04 | Reported to supersede the 2017 circular and Circular 8 of 2025. Contents not read; re-check before encoding. | SHARED | — | [U] |
| CMP-144 | Aadhaar Act s.41 (as amended by Jan Vishwas Act) | S13 | 2026-11-04 | Contravention by a requesting entity or enrolling agency carries a penalty up to Rs 1 lakh (individual) or Rs 10 lakh (company), from 30-11-2023. | DF | Risk weighting | [V] |

### 3.8 Public Records Act 1993 and Rules 1997 (S19)

| ID | Legal source | Link | Seen | Obligation (plain words) | Bearer | Sangam feature | Status |
|---|---|---|---|---|---|---|---|
| CMP-145 | PRA s.4 | S19 | 2026-11-04 | Public records must not be taken or transmitted **out of India** without prior Central approval (except for official purposes). This supports India-only hosting of workflow records. | SHARED | `DataLocalisationPolicy` (records class "public record" hard-pinned to India) | [U] |
| CMP-146 | PRA s.8 + Rules (retention schedules, appraisal, review, destruction register) | S19 | 2026-11-04 | Public records may be destroyed **only under a retention schedule**, drawn up with the National Archives for substantive-function records, after review and recording. Destruction must be recorded. Contravening s.4 or s.8 is punishable with up to 5 years and/or a fine up to Rs 10,000. DPDP erasure must give way to records retention (an "any law" exception under s.8(7) and s.12(3)). | SHARED | `RetentionSchedule` (record-class retention from departmental schedule), `LegalHoldStore`, destruction register via `ErasureService` tombstones | [S] |
| CMP-147 | PRA s.6-7 (records officer) | S19 | 2026-11-04 | Each records creating agency designates a Records Officer responsible for record management and safe custody. | DF | Role `RECORDS_OFFICER` required approver on bulk erasure / destruction | [U] |

### 3.9 State instruments (S20, S21, S22)

Most states have policies, not statutes, and these rarely add obligations a platform must encode. Below are the ones with a concrete hook.

| ID | Legal source | Link | Seen | Obligation (plain words) | Bearer | Sangam feature | Status |
|---|---|---|---|---|---|---|---|
| CMP-148 | Tamil Nadu Cyber Security Policy 2020 / CSP 2.0 (G.O. 2024) | S20 | 2026-11-04 | All TN State Government IT assets fall under **CERT-TN**, and departments report to TN-CERT (CERT-In takes the lead in crises). Incidents must therefore go to CERT-TN **in addition to** CERT-In. | SHARED | `CertInIncidentReporter` secondary "state CERT" recipient (configurable: CERT-TN, CERT-K, CERT-MH etc.) | [S] |
| CMP-149 | State CERTs generally (e.g. Kerala, Maharashtra, Karnataka, Odisha) | — | 2026-11-04 | Several states run state CERTs or SOCs and expect incidents to be shared with them. Specific mandates not verified. | SHARED | Same configurable state-CERT recipient | [U] |
| CMP-150 | State Data Centre (SDC) hosting under NeGP/state IT policies | — | 2026-11-04 | Many states require state applications to be hosted in the State Data Centre or state cloud. Mandates differ by state and were not verified individually. | SHARED | `DataLocalisationPolicy` hosting-type `SDC` + state code | [U] |
| CMP-151 | Rajasthan Jan-Aadhaar Authority Act 2020 ss.10-12, 33 | S21 | 2026-11-04 | The authority and agencies keep the Jan-Aadhaar repository data secure and confidential. Identity information, photograph and Jan-Aadhaar ID are not shared or publicly displayed except as the Act or rules allow. Authentication logs are shared with the holder on request. Breach carries a fine up to Rs 10,000. This applies when a Sangam deployment in Rajasthan consumes Jan-Aadhaar data. | SHARED | Field mask `JAN_AADHAAR` (state-specific identifier masks), authentication-log export to holder | [S] |
| CMP-152 | Telangana Open Data Policy 2016 / TGDeX; Karnataka Open Data Policy 2021 (data exchange framework under revision) | S22 | 2026-11-04 | Open-data policies call for shareable, non-personal datasets to be published. Karnataka is revising its policy to align with DPDP. Datasets must be **de-identified before publication**. | SHARED | Export pipeline with de-identification (masks + aggregation) | [S] |
| CMP-153 | State official-language acts (e.g. state Official Language Acts) | — | 2026-11-04 | Notices to citizens in the state's official language are often required alongside English or Hindi. DPDP s.5(3) already requires an Eighth Schedule language option. | SANGAM | `NoticeTemplate` per-state default language (`config.IndianLanguage`) | [U] |

---

## 4. Interpretation notes

1. **State exemptions.**
   - s.17(2)(a) takes notified State instrumentalities entirely outside the Act, but only by notification, and only for sovereignty, security, foreign relations, public order or incitement purposes.
   - s.17(4) gives every State processor three relaxations without any notification: no s.8(7) erasure on purpose/withdrawal, no s.12(3) erasure right, and no s.12(2) correction right where no decision is made about the person.
   - Even so, the deployer, not Sangam, decides whether it is exempt. Sangam therefore implements every obligation and lets the deployer switch an obligation off per processing activity, recording the exemption ground and notification reference in `LegitimateUseRegistry`. The switch itself is audited.
2. **s.7(b) is the main lawful ground for welfare and licence workflows.** It needs either prior consent for *any* State benefit or a *notified* State database (s.7(b)(ii)). For it to apply, the Second Schedule standards (minimisation, accuracy, retention, security, intimation with contact and rights link) must be met. So "no consent needed" still means **an intimation is needed** (CMP-053).
3. **Retention conflicts, resolved by precedence:** legal hold > statutory retention (Public Records schedules, sectoral law) > DPDP Rule 8(3) one-year minimum > CERT-In 180-day log floor > purpose-based erasure.
   - The one-year floor in Rule 6(1)(e) and Rule 8(3) is stricter than CERT-In's 180 days. Encode `minLogRetention = max(365 days, 180 days) = 365 days`.
   - The Rule 8(3) year runs from the date of each processing operation, not account closure.
4. **The Third Schedule does not apply to most government deployers.** Its three classes are large e-commerce, online gaming and social media. For a State deployer s.8(7) is also disapplied by s.17(4). Ship the Third Schedule periods as presets, not defaults.
5. **CERT-In 6h vs DPDP 72h.** These are separate clocks and separate recipients. The CERT-In clock runs from *noticing*; the DPDP clocks run from *becoming aware*. A single personal-data breach normally triggers both BPMNs. The DPDP Board intimation has two stages: "without delay", then 72 hours. The principal intimation is "without delay" with no hour figure.
6. **SPDI/s.43A sunset.** Until 13-05-2027, body-corporate deployers (PSUs, corporations, SIs) are also under the SPDI Rules, including the one-month grievance period. Government departments proper are not "body corporates".
7. **Children.** "Child" means under 18 (DPDP s.2(f), not re-fetched here). Most government child workflows (scholarships, school admission, mid-day meal, birth certificates) fall under Fourth Schedule Part B items 1-2. So s.9(1) consent and s.9(3) are disapplied, but s.9(2) (no detrimental processing) still applies. `ChildDataGuard` must keep s.9(2) on even when an exemption is active.
8. **Logs in India.** The CERT-In Direction says "within the Indian jurisdiction". FAQ Q35 relaxes this to producibility. The CERT-In government guidelines (Annexure 5.1) and MeitY cloud terms require Indian hosting anyway. The government profile therefore pins logs to India.
9. **Consent Manager integration** becomes relevant from 13-11-2026, when Board registration opens. No CM had been registered as of 2026-11-04 [U].

## 5. Constants for the implementer (all [V] unless marked)

- **DPDP Rules:** G.S.R. 846(E) dated 13-11-2025.
  - Immediate: Rules 1, 2, 17-21.
  - 13-11-2026: Rule 4.
  - 13-05-2027: Rules 3, 5-16, 22, 23.
  - Act ss.3-17 and s.44(2) from 13-05-2027 (G.S.R. 843(E)); s.6(9) from 13-11-2026; s.44(3) RTI change already in force.
- **Rule 7 (breach):**
  - Principal: "without delay". Contents: description (nature, extent, timing); consequences; mitigation; safety steps for her; contact.
  - Board: "without delay" (description incl. nature, extent, timing, location, likely impact), then **within 72 hours** of becoming aware, or a longer period the Board allows on written request. The 72-hour report covers: updated details; facts and reasons; mitigation; findings about the person responsible; remedial measures; report on principal intimations.
- **Rule 6(1)(e):** logs and personal data retained **1 year** for security purposes.
- **Rule 8:**
  - Third Schedule period **3 years** from last approach or exercise of rights, or from the Rules' commencement, whichever is latest. Only for e-commerce ≥ 2 crore users, online gaming ≥ 50 lakh users, social media ≥ 2 crore users.
  - Pre-erasure notice **≥ 48 hours**.
  - Minimum retention of personal data, traffic data and processing logs **1 year from the date of processing** (r.8(3)).
- **First Schedule:** Consent Manager records kept **≥ 7 years**; CM net worth ≥ Rs 2 crore.
- **Rule 13:** DPIA and audit **every 12 months** from SDF notification.
- **Rule 14(3):** grievance response period **≤ 90 days**, published.
- **Rule 10 verification modes:** (a) reliable identity and age details already held by the DF; (b)(i) details voluntarily provided by the individual; (b)(ii) virtual token issued by an authorised entity, including tokens or details made available and verified through a DigiLocker service provider. Adult means 18 or older.
- **Rule 11:** guardian appointed by a court, a designated authority (RPwD Act 2016 s.15) or a local level committee (National Trust Act 1999 s.13).
- **Rule 19(9):** Board inquiry within 6 months, extendable by 3 months at a time.
- **CERT-In:**
  - Report within **6 hours**; logs **180 days** rolling, in India.
  - NTP: `samay1.nic.in`, `samay2.nic.in`, `time.nplindia.org`.
  - VPN/VPS/cloud/DC KYC: **5 years**; virtual-asset KYC and transactions: **5 years**.
  - Directions effective 60 days after 28-04-2022.
  - **CERT-In Annexure I** (exact numbering):
    - i. Targeted scanning/probing of critical networks/systems
    - ii. Compromise of critical systems/information
    - iii. Unauthorised access of IT systems/data
    - iv. Defacement of website or intrusion into a website and unauthorised changes such as inserting malicious code, links to external websites etc.
    - v. Malicious code attacks such as spreading of virus/worm/Trojan/Bots/Spyware/Ransomware/Cryptominers
    - vi. Attack on servers such as Database, Mail and DNS and network devices such as Routers
    - vii. Identity Theft, spoofing and phishing attacks
    - viii. Denial of Service (DoS) and Distributed Denial of Service (DDoS) attacks
    - ix. Attacks on Critical infrastructure, SCADA and operational technology systems and Wireless networks
    - x. Attacks on Application such as E-Governance, E-Commerce etc.
    - xi. Data Breach
    - xii. Data Leak
    - xiii. Attacks on Internet of Things (IoT) devices and associated systems, networks, software, servers
    - xiv. Attacks or incident affecting Digital Payment systems
    - xv. Attacks through Malicious mobile Apps
    - xvi. Fake mobile Apps
    - xvii. Unauthorised access to social media accounts
    - xviii. Attacks or malicious/suspicious activities affecting Cloud computing systems/servers/software/applications
    - xix. Attacks or malicious/suspicious activities affecting systems/servers/networks/software/applications related to Big Data, Block chain, virtual assets, virtual asset exchanges, custodian wallets, Robotics, 3D and 4D Printing, additive manufacturing, Drones
    - xx. Attacks or malicious/suspicious activities affecting systems/servers/software/applications related to Artificial Intelligence and Machine Learning
- **CERT-In government guidelines:** session idle timeout 15 min; password change at least every 120 days, minimum 8 characters; access review every 6 months; internal audit every 6 months; third-party audit yearly; VAPT yearly or on change; logs at least 180 days.
- **SPDI r.5(9):** grievance within **one month**.
- **Intermediary Rules:** 180-day preservation / post-deregistration retention; 72-hour information to agencies; grievance acknowledged in 24 hours and resolved in 15 days.

## 6. Summary

### 6.1 Counts by bearer

| Bearer | Count |
|---|---|
| SANGAM | 52 |
| SHARED | 72 |
| DF | 23 |
| SDF | 6 |
| **Total** | 153 |

### 6.2 Counts by status

| Status | Count |
|---|---|
| [V] verified against official text | 126 |
| [S] secondary source | 14 |
| [U] unverified | 13 |

(CMP-029 and CMP-115 are counted as [V] by their primary marker.)

### 6.3 Not verified / to re-check before release

- The eGazette for any 2026 amendment to DPDP Rules commencement. None was found; the January 2026 MeitY 12-month proposal was not located as notified. Also the G.S.R. 892(E) corrigendum of 10-12-2025.
- S.O. 5458(E) (Removal of Difficulties Order, 06-10-2026). The gazette text was not read.
- UIDAI primary texts: Aadhaar Act s.29, Sharing Regulations reg. 6, ADV Circular 8 of 2025 and Circular 14 of 2025. uidai.gov.in returned HTTP 503 throughout.
- The consolidated IT Act text (India Code timed out): ss.43A, 66, 66E, 67C duty wording, 69/69A/69B; the Protected System Rules 2018.
- Primary PDFs of: the CERT-In Comprehensive Audit Policy Guidelines (25-07-2025); the CERT-In BOM Guidelines v2.0 (09-07-2025); MeitY cloud contractual-terms guidelines; GIGW 3.0 language clause; Email Policy 2014; NISPG; National Data Centre Policy.
- Public Records Act ss.4, 6-8 exact text.
- State mandates: SDC hosting, state CERT reporting other than Tamil Nadu, state official-language notice requirements.
- Whether any SDF or Rule 13(4) specified-data list has been notified, and whether any Consent Manager has been registered.

## Implementation name map (added 2026-11-05 after the generic-framework refactor)

The integrator directed that `sangam-compliance` be jurisdiction-neutral, with India as the first data pack (`IN`). The "Sangam feature" column above uses the names planned before the refactor. They map as follows:

| Name in this register | Implemented as |
|---|---|
| `rights.DataPrincipalRightsService` | `rights.DataSubjectRightsService` (rights come from the IN pack) |
| BPMN `dpdp-access-request`, `dpdp-correction-request`, `dpdp-erasure-request`, `dpdp-grievance`, `dpdp-nomination` | `sangam-dsr-access`, `sangam-dsr-rectification`, `sangam-dsr-erasure`, `sangam-dsr-grievance`, `sangam-dsr-nomination` |
| BPMN `dpdp-breach-incident`, `certin-incident-report` | `sangam-breach-incident`, `sangam-incident-report` |
| `breach.CertInIncidentReporter`, `breach.CertInIncidentType` | `breach.IncidentReportingService`; the Annexure I catalogue is in `IN/pack.json` (`authorities[CERT-IN].catalogue`) |
| `sdf.SdfObligationService` | `accountability.AccountabilityService` (elevated tier = Significant Data Fiduciary) |
| `sdf.ContactDirectory` | `accountability.ContactDirectory` (roles from the pack) |
| `config.IndianLanguage` | `JurisdictionPack.Language` (23 languages in `IN/pack.json`) |
| `lawful` grounds `STATE_BENEFIT_7B`, …; exemptions `S17_*` | pack codes `S7B_STATE_BENEFIT` …, `S17_1_A` … `S17_5` |
| `ChildDataGuard` exemptions `FOURTH_SCHEDULE_A_1..B_6` | pack codes `FOURTH_SCH_A1` … `FOURTH_SCH_B6` |
| `AadhaarVaultPort` | `CompliancePorts.NationalIdVault` + `identity.NationalIdGuard` (pack `nationalIdentifiers`) |

The obligation register is shipped as data in `sangam/sangam-compliance/src/main/resources/compliance/jurisdictions/IN/obligations.tsv`.
