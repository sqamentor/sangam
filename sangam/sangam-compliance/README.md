# sangam-compliance (COMPLIANCE workstream)

A jurisdiction-neutral compliance framework with **jurisdiction packs as data**. Java only knows the neutral
concepts: consent, purposes and lawful bases, data-subject rights, retention, breach and incident reporting,
localisation, impact assessments and evidence. Each law lives in a pack (`compliance/jurisdictions/<CODE>/pack.json`
plus notice templates and an obligation catalogue). The pack supplies legal bases, exemptions, rights and their time
limits, authorities with their deadlines, incident catalogues and report items, notice languages, retention classes,
contact roles, periodic evidence, hosting categories, national-identifier formats, lawful-request bases, a security
checklist and the commencement calendar.

The first pack is **IN (India)**. It covers the DPDP Act 2023 and Rules 2025, the CERT-In Directions 2022, the IT Act and
SPDI Rules 2011, the IT Rules 2021, the CERT-In government guidelines, MeitY cloud terms, the Public Records Act,
Aadhaar, and the state CERT and SDC hooks. Another jurisdiction, such as EU GDPR or US HIPAA, is added as another pack
with no code change.

The research behind the IN pack is in `../../spike/results/COMPLIANCE-REQUIREMENTS.md`, which lists obligations
CMP-001 to CMP-153.

**Status:** the code compiles and installs. Nothing has been tested yet, per the phase rule.

Build with `mvnw.cmd -q -B -DskipTests -f sangam-compliance\pom.xml install`. The parent reactor command
(`-pl sangam-compliance -am`) currently fails before compiling anything, because the parent `pom.xml` lists modules
from other workstreams that do not exist yet: `sangam-control-plane`, `sangam-gateway` and `sangam-rollup`.

## Configuration

Set `sangam.compliance.jurisdiction=IN`. A blank value selects the only installed pack. Defaults resolve in this
order: the property, then the pack's `defaults`, then a neutral fallback.

Legal floors and ceilings come only from the pack's `limits` and `conditionalLimits`. `ComplianceConfig.validate()`
reports any configured value that crosses them, and `requireValid()` refuses to start.

| Key | Meaning |
|---|---|
| `entity-attributes` | For example `public-sector,body-corporate,intermediary`. `public-sector` makes cross-border transfer deny-by-default and enforces home-country hosting. `body-corporate` caps IN grievances at 30 days until 13-05-2027 (SPDI r.5(9)). |
| `applied-exemptions` | Pack exemption codes applied to the whole tenant, for example `S17_4` for the State under IN s.17(4). The retention scheduler and erasure then keep data instead of erasing it. |
| `elevated-tier`, `elevated-tier-ref` | Marks a Significant Data Fiduciary under IN s.10. |
| `languages` | `all` (the default) or a CSV of codes. The IN default enables all 22 Eighth Schedule languages plus English. |
| `default-language` | The default notice language. |
| `allow-language-fallback` | Whether a missing language may fall back to the default. |
| `sla.<right>-days` | Response period per right, capped at the pack maximum. |
| `retention.*` | Retention scheduler settings. |
| `logs.retention-days`, `logs.region` | Log retention and the region logs must stay in. |
| `breach.authority-report-hours` | Deadline for the detailed breach report. |
| `authority.<CODE>.recipients` | Recipients per authority. |
| `incident.additional-recipients` | Extra incident recipients, for example a state CERT. |
| `ntp.enabled`, `ntp.servers`, `ntp.max-offset-ms` | Time-source verification. |
| `hosting.category`, `hosting.accreditation-ref`, `hosting.accreditation-valid-until`, `hosting.required-category` | Hosting declaration, for example `STATE_DATA_CENTRE`. |
| `transfer.negative-list`, `transfer.order-restricted`, `transfer.localised-categories`, `transfer.deny-by-default` | Cross-border transfer controls. |
| `consent-managers.registered` | Registered consent managers. |
| `links.rights-portal`, `links.withdraw-consent`, `links.authority-complaint`, `links.privacy-policy` | Public links shown in notices. |

To add notice translations, put `notice_<code>.properties` (UTF-8) in the pack's `notices/` folder, or load a
directory with `NoticeTemplate.Catalogue.loadDirectory(...)`.

## Requirements covered, file by file

Paths are under `src/main/java/org/sangam/compliance/`. CMP ids refer to the IN pack.

| File | Covers |
|---|---|
| `jurisdiction/JurisdictionPack`, `JurisdictionRegistry` | The pack model, loader and discovery (`META-INF/sangam/jurisdictions.idx`). Commencement phases with `inForce(citation)`, limits, conditional limits and terminology. |
| `resources/compliance/jurisdictions/IN/pack.json` | Everything India-specific as data: the DPDP s.7 grounds, s.17 exemptions, Fourth Schedule, r.10/r.11 methods, rights with the 90-day cap, the Third Schedule, the DPB and CERT-In (6 hours, 20 Annexure I types, form items), r.7 items and 72 hours, contact roles, evidence kinds, MeitY/NIC/SDC hosting, Aadhaar with Verhoeff, lawful-request bases, a security checklist, and phases P1 13-11-2025, P2 13-11-2026, P3 13-05-2027. |
| `resources/.../IN/notices/notice_en.properties`, `notice_hi.properties` | English and Hindi notice frames (r.3). |
| `resources/.../IN/obligations.tsv` | CMP-001…153, with bearer, evidence area and verification status. |
| `config/ComplianceConfig` | Tenant configuration, legal floors and ceilings from the pack, publication gaps. |
| `ComplianceAudit` | Consent ledger, breach and incident timelines and evidence on the core `AuditChain`, using per-tenant and per-subject chains (CMP-012, CMP-056). |
| `ComplianceSchema` | Reference DDL for 26 tables, in the four dialects through `Dialect` tokens. |
| `consent/NoticeTemplate` | Templates per pack and language, with required keys and a lint for invalid clauses (CMP-004, 006, 047-049). |
| `consent/ConsentNoticeService` | Versioned, hashed notices in every enabled language; links, contact and itemisation enforced; non-consent intimation (CMP-053); privacy policy (CMP-120, GIGW); legacy notices (CMP-003). |
| `consent/ConsentService` | Affirmative, specific consent bound to the exact notice version; partial or full withdrawal; processor cessation; withdrawal listeners (CMP-005, 008-012). |
| `consent/PurposeBindingGuard` | Lawful basis, minimisation, consent or objection, then child checks (CMP-001, 005, 009, 013). |
| `consent/VerifiableParentalConsentService` | Parent and lawful-guardian verification through ports; methods from the pack (CMP-029, 068-070). |
| `consent/ChildDataGuard`, `ChildSafeguards` | Child protections: no tracking, no behavioural monitoring, no targeted ads, nothing detrimental; pack exemptions apply (CMP-030, 031, 071, 072). |
| `consent/ConsentManagerPort`, `ConsentManagerGateway` | Consent-manager hooks, the registered allow-list and status push-back (CMP-010, 011, 050, 051). |
| `lawful/LegitimateUseRegistry`, `PurposeDefinition` | Purpose registry with validation driven by the pack, and objections (CMP-001, 013-019, 043, 052, 081). |
| `lawful/ProcessingRegister` | Record of processing (CMP-020, 021, 035). |
| `lawful/DisclosureRegister` | Sharing log, including withheld s.11(2) disclosures (CMP-035, 036, 124). |
| `lawful/LawfulRequestService` | Agency requests with rank attestation, maker-checker and deadlines (CMP-084, 089, 093, 116, 129). |
| `rights/DataSubjectRightsService`, `RightCategory`, `NominationService` | Access, rectification, erasure, grievance and nomination, with authentication, nominee gating, SLAs within the pack maximum, and the published contact in every response (CMP-035-041, 067, 076-079, 122, 123). |
| `retention/RetentionSchedule`, `RetentionScheduler` | Purpose-based retention with minimum retention, retention classes, record-class retention, the 48-hour notice and approach resets. It respects legal holds through the integration `LegalHoldStore` and erases through `ErasureService` (CMP-025, 026, 058, 064-066, 146). |
| `breach/BreachIncidentService` | Initial notice to the authority, notices to subjects, the detailed report within the deadline, extensions, evidence legal holds, and the linked incident (CMP-024, 061-063). |
| `breach/IncidentReportingService` | Pack catalogue, 6-hour or pack deadline, form items, point-of-contact check, secondary recipients and follow-ups (CMP-087, 088, 090, 108, 148, 149). |
| `logs/LogRetentionPolicy`, `LogArchiveService` | Sealed, hash-linked log segments stored in the home country, purge blocked under hold, and a register of external ICT log sources (CMP-058, 066, 091, 092, 106). |
| `logs/NtpTimeSourceVerifier` | SNTP client in plain JDK code that checks JVM and DB clock offsets (CMP-085, 086). |
| `logs/AuditIntegrityVerifier` | Verifies every chain with the core `AuditChain.verify` (CMP-012, 056, 091). |
| `accountability/ContactDirectory` | Pack contact roles: residency rule for the DPO, Annexure II fields for the CERT-In point of contact (CMP-027, 032, 067, 090, 123, 147). |
| `accountability/AccountabilityService` | Elevated-tier designation, impact assessments every 12 months, periodic audit evidence (CMP-032-034, 073-075, 097, 101, 105, 107, 109, 126, 131). |
| `transfer/DataLocalisationPolicy`, `CrossBorderTransferGuard` | Localisation findings and the transfer rule chain, recorded and audited (CMP-042, 075, 080, 104, 125, 131, 132, 142, 145, 150). |
| `identity/NationalIdGuard` | Aadhaar detection (Verhoeff), masking, redaction, vault tokenising and a variable guard (CMP-140-142). |
| `security/SecurityPracticesChecklist` | The pack checklist evaluated from deployment facts (CMP-054-060, 097-107, 126, 139). |
| `report/ObligationCatalogue`, `ComplianceEvidenceReportGenerator` | Evidence report in JSON and HTML, with an automated status per obligation, hashed and stored in `CMP_REPORT` (CMP-022, 082, 089). |
| `bpmn/ComplianceSteps`, `ComplianceStepDelegate`, `ComplianceRuntime` | Service-task steps for the packs. |
| `resources/packs/compliance/*` | The BPMN pack, discoverable by `PackRegistry` through this module's `packs.idx`: `sangam-dsr-access`, `-rectification`, `-erasure`, `-grievance` (warning at three quarters of the period plus the due date), `-nomination`, `sangam-breach-incident` (parallel authority and subject notices, report warning and overdue timers), `sangam-incident-report` (warning and deadline timers, follow-up loop). Also 15 forms (en/hi labels where relevant) and seed roles. |
| `adapters/IntegrationLegalHoldAdapter`, `IntegrationErasureAdapter` | Reuse of the VI.5 legal holds and erasure from `sangam-integration`, with nothing duplicated. |
| `api/ComplianceApi` | REST port for WS-REST (below). |
| `SangamCompliance` | Wiring facade. A missing legal-hold port fails closed: everything counts as held. |

## Integration notes

1. **Tables for sangam-persistence.** Add these to Liquibase, using the DDL in `ComplianceSchema`:
   - consent and notices: `CMP_NOTICE`, `CMP_NOTICE_DELIVERY`, `CMP_CONSENT`, `CMP_CONSENT_PURPOSE`, `CMP_GUARDIAN_VERIFICATION`
   - purposes and processing: `CMP_PURPOSE`, `CMP_OBJECTION`, `CMP_PROCESSING_ACTIVITY`, `CMP_DISCLOSURE`, `CMP_LAWFUL_REQUEST`
   - contacts and accountability: `CMP_CONTACT`, `CMP_TIER_STATUS`, `CMP_IMPACT_ASSESSMENT`, `CMP_AUDIT_EVIDENCE`
   - retention and rights: `CMP_RETENTION_ITEM`, `CMP_RIGHTS_REQUEST`, `CMP_NOMINATION`
   - breach and incidents: `CMP_INCIDENT`, `CMP_BREACH`, `CMP_BREACH_NOTICE`, `CMP_BREACH_ROOT`
   - logs and time: `CMP_LOG_SEGMENT`, `CMP_LOG_SOURCE`, `CMP_NTP_CHECK`
   - transfer and reports: `CMP_TRANSFER_DECISION`, `CMP_REPORT`

   Add the 16 indexes listed there as well. The module also uses `LEGAL_HOLD` (integration) and the core
   `AUDIT_EVENT`, `AUDIT_PAYLOAD` and `ROOT_SEQ`.
2. **Wiring.**
   - Build `new SangamCompliance(dialect, Transactions.of(engineAdapter), configProvider, ports)`, then call
     `ComplianceRuntime.install(...)`.
   - `ports.legalHolds = new IntegrationLegalHoldAdapter(legalHoldStore)`.
   - `ports.erasure = new IntegrationErasureAdapter(erasureService)`.
   - Grant the principal `system:retention` the `ERASE_DATA` permission in the integration `LifecyclePorts.Authorizer`.
   - Bind `PrincipalNotifier` to the outbox with the DLT SMS and e-mail connectors.
   - `SupervisoryAuthorityGateway` and `IncidentAuthorityGateway` go through the e-mail/HTTP connectors.
   - `WorkflowStarter` goes through `EngineAdapter`.
   - The DigiLocker connector serves `AdultIdentityVerifier`.
   - `LogArchiveStore` is an object store in the home country.
3. **Schedulers** (leader-elected, as in `IntegrationMaintenance`):
   - `retention.runCycle(tenant, n)` hourly;
   - `rights.escalateOverdue`, `breaches.overdue` and `incidents.overdue` every 5 minutes;
   - `logs.seal` daily, followed by `purgeExpired`;
   - `ntp.check` hourly;
   - `integrity.verifyTenant` daily.
4. **Packs.** This module ships its own `META-INF/sangam/packs.idx` (pack `sangam.compliance`) and
   `META-INF/sangam/jurisdictions.idx`. The BPMN files have no DI layout yet.
5. **REST (WS-REST).** Implement `api/ComplianceApi` under `/api/v1/compliance`. The endpoints are listed on its
   methods: notices, privacy-policy, purposes, consents (+withdraw), consent-manager artefacts, rights-requests,
   breaches, incidents (+submit), lawful-requests, contacts, evidence, transfers:evaluate, logs/verify, reports and
   jurisdiction.
   - Map `ComplianceException.httpStatus()/code()` to problem+json.
   - Authorise on resource type `COMPLIANCE`. WS-SECURITY needs to add it to `ResourceRegistry`.
6. **Engine and UI.**
   - Packs and the REST layer should call `purposeBinding.require(...)` before processing personal data, and
     `nationalIds.requireNoRawIdentifiers(...)` on variables.
   - `retention.track(...)` should run at instance start and `onPurposeCompleted(...)` at instance end.
   - These calls are a natural fit for a `sangam-engine` parse or execution listener, owned by WS-ENGINE.

## Dependencies

Nothing new. The module uses sangam-core, sangam-integration (optional), operaton-engine (optional, provided),
jackson-databind and slf4j, all Apache-2.0.

## Known gaps

- **No tests yet**, per the phase rule.
- **Missing notice translations.** Only English and Hindi templates ship. The other 21 Eighth Schedule languages are
  enabled by default, so they appear as report gaps until translations are added. The Hindi text needs review by the
  Rajbhasha section.
- **Denial audits roll back.** Denial audit events (purpose binding, child block) are written in the caller's
  transaction, so they are lost when the caller rolls back. A separate denial transaction, as in sangam-security,
  is not done here because of the ROOT_SEQ deadlock risk.
- **Partial pack data for some sources.** The pack data for the UIDAI circulars, the CERT-In audit and BOM guidelines,
  GIGW language clauses, NISPG and state mandates is marked [U]/[S] in the requirements document. State-specific
  obligations are configuration hooks only: `incident.additional-recipients`, `hosting.required-category` and
  `entity-attributes`.
- **Incomplete REST, UI and processing hooks.** `ComplianceApi` has no implementation here (WS-REST owns it). GIGW 3.0
  accessibility belongs to sangam-ui. Purpose-binding and retention hooks into engine execution are not wired yet.
- **BPMN diagrams.** There is no diagram (DI) layout, and the user tasks use candidate groups, not seat routing.
- **Consent managers.** No integration with an actual registered Consent Manager exists. None is registered with the
  Board yet.
- **Not in Sangam's scope.** Cloud/VPN KYC record keeping (CERT-In para (v)/(vi)) and other deployer-only obligations
  (bearer DF) are listed in the report as DEPLOYER.
