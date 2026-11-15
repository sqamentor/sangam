<!-- SPDX-License-Identifier: Apache-2.0 -->
# CERT-In and DPDP control matrix (DRAFT, NOT LEGAL ADVICE)

Status: draft, 2026-11-15, CERT-In / DPDP e2e lane C1. This is an engineering reading of the CERT-In Directions of
28-04-2022 and the DPDP Act 2023 with the DPDP Rules 2025. It is not a legal conclusion and must be reviewed by
counsel before anyone relies on it. The starting point was the draft matrix of 2026-11-15 (handoff
`2026-11-15/CERTIN-DPDP-UI-MATRIX-draft.md`). Every row below was checked against the code again in this lane.

Sources, with the keys used in the rows:
- [D] CERT-In Directions, 28-04-2022.
- [F] CERT-In FAQs on the Directions, May 2022.
- [C] CERT-In Comprehensive Cyber Security Audit Policy Guidelines v1.0.
- [A] DPDP Act 2023.
- [R] DPDP Rules 2025.

When they take effect: the CERT-In Directions are in force now. Most data-principal duties in the DPDP Rules start about 18 months after publication, around May 2027 (item 2 in the last section is UNVERIFIED). They are all in the 0.1.0 scope.

## How the evidence runs

The Playwright suite in `sangam-ui/e2e` runs in the `ui.yml` e2e job against two containers:
- the nginx UI image `sangam-ui:e2e`, with the real headers and no `bypassCSP` outside `a11y.e2e.ts`;
- the test-mode server, `deploy/docker/server-test-mode.Dockerfile`.

The job sets these on the server:
- the India jurisdiction pack, `SANGAM_COMPLIANCE_JURISDICTIONS=IN`;
- the withdraw, rights and Board-complaint links;
- the compliance seats.

`global-setup` then does two things:
- It creates three identities through the product's own service-principal API (`e2e/support/identities.ts`): a citizen, a user with no role, and a compliance officer. Each one is checked through `GET /me`.
- It publishes the baseline (`e2e/support/compliance.ts`): the DPO contact, one consent purpose, and one published notice.

No production guard, server setting or security check was changed for this.

The browser run happens in CI only. Locally it is NOT_RUN, because no Playwright browsers were downloaded.

## Rows

How to read the table:
- **Status** is the state after this lane:
  - EXISTS: the control is there;
  - PARTIAL: some of it is there;
  - MISSING: it is not there;
  - N/A: it does not apply to self-hosted Sangam.
- **(server)** means the control is server-side, with no UI step to test.
- **Covered by** names the spec that gives evidence for the row, or "none".
- **Note** says what is still open. "Owner list" points to the last section but one.

| ID | Control (source) | Status | Covered by | Note |
|---|---|---|---|---|
| C-01 | Clocks synchronised to NIC/NPL NTP ([D] dir.(i)) | EXISTS (server) | none | `NtpTimeSourceVerifier`; server test. |
| C-02 | Annexure I incidents reported to CERT-In within 6 h ([D] dir.(ii)) | EXISTS (round 4 D1: the type is a pick-list of the pack catalogue; the server refuses a free-text type, `INCIDENT_TYPE_NOT_IN_CATALOGUE`, decision 344) | `breach-and-incident` (written, NOT_RUN locally) | The 6-hour report itself is sent by the operator; the spec checks registration, the catalogue and the deadline. |
| C-03 | Point of contact in the Annexure II format ([D] dir.(iii)) | EXISTS (round 4 D1: the contact form shows the role's `requiredFields`, decision 345) | `rights-requests` (the role select offers `CERT_IN_POC` and every India pack role) | |
| C-04 | Logs kept 180 days in India and provided on order ([D] dir.(iv)) | EXISTS (server) | `audit-evidence` (India pack, floors; report hash = SHA-256 of the report served) | Owner list 7: the UI cannot generate the report. |
| C-05 | All ICT systems, including the nginx proxy ([F] Q37) | PARTIAL | none | nginx has no retention or forwarding of its own; ops. |
| C-06 | Logs and information on order ([D] dir.(iii)-(iv)) | EXISTS | none | `/audit/exports`, lawful requests. |
| C-07 | KYC records of hosting providers ([D] dir.(v)) | N/A | none | Only if Sangam is offered as a hosted service. |
| C-08 | Annual audit, OWASP WSTG, SBOM ([C] §6, §8) | PARTIAL | the W-rows below are the UI-side WSTG evidence | The audit report is a record; [S] is UNVERIFIED. |
| D-01 | Notice: itemised data, purposes, links to withdraw, rights and the Board ([A] s.5(1); [R] r.3) | EXISTS (portal form fixed by owner decision 339) | `consent-notice` (the public policy signed out and the published notice: links, data items, purpose, DPO, version, hash; citizen path: the portal form shows the service's published notice, `GET /portal/services/{key}/notice`) | Owner list 4, done. A service without a `noticeKey` in its registry entry takes no consent and shows no notice. |
| D-02 | Notice in English or an Eighth Schedule language ([A] s.5(3)) | PARTIAL (owner decision Q-06: English-only UI) | none | The server templates exist for more languages; the UI chrome is English. Accepted gap. |
| D-03 | Free, specific, unbundled consent ([A] s.6(1)) | PARTIAL (unbundled: EXISTS, owner decision 341; clear affirmative action an explicit, required answer: EXISTS, owner decision 369 (h); draft before consent: OPEN for counsel) | `consent-notice` (citizen path: one box per purpose, none pre-ticked, consent only for the ticked purposes) | Owner list 6: one checkbox per purpose, done. Decision 369 (h): `GiveConsentRequest.affirmativeAction` is boxed and required by the contract; an omitted one is 400 `VALIDATION_FAILED` naming the field, never read as false or true, and the officer form asks it as an explicit Yes / No (`GiveConsentAffirmativeActionRequestTest`, `complianceAttestations.test.ts`). "Save draft" still stores the form before any consent and sends no consent; whether that is a legitimate use under s.7(a) is OPEN for counsel, and the draft behaviour is unchanged. |
| D-04 | Proof of notice and consent ([A] s.6(10), wording UNVERIFIED) | EXISTS (operator path fixed earlier; portal path by owner decision 339) | `consent-withdrawal` (operator: records on the published notice; the register row and the API show the key, version, purposes and `givenAt`); `consent-notice` (citizen: the portal submission records the consent on the notice key and version, channel `portal:<service>`, in the transaction that starts the application; a stale or other notice is refused, 409 `NOTICE_NOT_CURRENT`) | Owner list 4, done. Gap: a child's consent row carries no guardian verification id in the standalone server (no principal directory); the id is kept on the application. |
| D-05 | Withdrawal as easy as giving ([A] s.6(4); [R] r.3(c)(i)) | EXISTS (self-service by owner decision 340) | `consent-withdrawal` (operator: withdraw → `WITHDRAWN`, `withdrawnAt`; citizen: "My consents", one click per purpose or for the whole consent, no reason) | Owner list 5, done. |
| D-06 | Processing stops after withdrawal ([A] s.6(5)-(6)) | PARTIAL | none | `PurposeBindingGuard`; the end-to-end stop is not shown. |
| D-07 | Consent managers ([A] s.6(7)-(9); [R] r.4) | EXISTS (server) | none | Server test. |
| D-08 | Legitimate uses ([A] s.7) | EXISTS (server) | none | Server test. |
| D-09 | Safeguards: encryption, masking, tokens ([A] s.8(5); [R] r.6(1)(a)) | PARTIAL | `client-storage-and-urls`, `xss-rendering` (UI side only) | |
| D-10 | Access control ([R] r.6(1)(b)) | EXISTS | `access-control` (user with no role and citizen refused in the UI and with 403; the officer passes by permission) | |
| D-11 | Visibility of access to personal data ([R] r.6(1)(c)) | EXISTS (server; round 4 D1: an officer's read of another principal's consents or rights requests writes `PERSONAL_DATA_READ` on the principal's chain, decision 348) | none (not visible through the API; `CompliancePrincipalReadsH2Test`) | |
| D-12 | Logs and data kept for at least one year ([R] r.6(1)(e), r.8(3)) | EXISTS | `audit-evidence` (pack floors 365 days) | |
| D-13 | Continuity, backups ([R] r.6(1)(d)) | PARTIAL (not verified) | none | Ops drill record. |
| D-14 | Breach: intimation, then a report to the Board within 72 h ([A] s.8(6); [R] r.7(2)) | EXISTS (server; the processes come from the installed compliance pack, else `COMPLIANCE_PACK_NOT_INSTALLED`, decision 336) | `breach-and-incident` (written, NOT_RUN locally: registration, the 72 h deadline and the linked CERT-In incident) | |
| D-15 | Breach notice to each affected data principal ([R] r.7(1)) | EXISTS (server; portal notification by owner decision 347) | `portal-breach-notice` (a fresh citizen has none; the affected citizen gets one notice with the five r.7(1) items under the pack's labels and citations, another citizen none; the full flow is fixme until the compliance processes are deployed, owner list 2 / decision 336) | Owner list 12, done: the notice is also a portal notification, written in the delivery transaction. Server: `BreachSubjectNoticeListenerH2Test`, `PortalComplianceAdapterH2Test`. |
| D-16 | Erasure ([A] s.8(7), s.12(3)) | EXISTS (round 4 D1: intake by the signed-in data principal, decision 335; round 4 D6: the server wires `CompliancePersonalData` over the process-instance store and the retention register, decision 361; execution shown end to end on a dedicated test citizen, decision 369 (g)) | `erasure-legal-hold` (written, NOT_RUN locally: intake, refusals, the officer's search; a dedicated citizen's erasure through the records officer's approval task: the root without a hold loses its variables, the request closes `PARTIALLY_RESOLVED`) | Also asserted by `LegalHoldErasureH2Test` and `CompliancePersonalDataTest`. Correction of process data is refused by name (decision 369 (b)); erasure stays partial (decision 369 (a)). The e2e spec has not run yet (CI). |
| D-17 | Legal hold overrides erasure ([A] s.8(7)) | EXISTS (round 4 D6: legal-hold API on a process root, place / list / release by a different person, audited LEGAL_HOLD_SET / LEGAL_HOLD_RELEASED; the erasure refusal is audited on the held root's chain and the request names LEGAL_HOLD, decision 361) | `erasure-legal-hold` (written, NOT_RUN locally: place, list, refusals, four-eyes release; decision 369 (g): during a real erasure the held root keeps the citizen's data and the outcome names `LEGAL_HOLD` with the hold id) | The `CMP_RETENTION_HELD` audit on the held root's chain is asserted by `LegalHoldErasureH2Test` (not visible through the API). |
| D-18 | 48-hour pre-erasure intimation ([R] r.8(2)) | EXISTS (server) | none | Time-based; server test. |
| D-19 | Third Schedule 3-year inactivity erasure ([R] r.8(1)) | EXISTS | none | Counsel: does it apply to deployers? |
| D-20 | Purpose deemed no longer served ([A] s.8(8)) | EXISTS | none | `/admin/retention`. |
| D-21 | DPO contact published, and given in rights responses ([A] s.8(9); [R] r.9) | EXISTS for rights requests (round 4 D1: every rights-request read carries `contact`, decision 346); the portal grievance is lane D2's | `consent-notice` (the DPO on the public policy and the notice); `erasure-legal-hold` (the contact on the acknowledgement) | |
| D-22 | Grievance redressal within the published period, at most 90 days ([A] s.8(10), s.13; [R] r.14(3)) | EXISTS | `rights-requests` (reference shown, the published period shown, due date ≤ the pack maximum) | Owner list 15, done (owner decision 350): the portal grievance due date is the pack's GRIEVANCE period (`defaultDays`, the tenant's `sla.grievance-days` within `maxDays`); with no jurisdiction set it has no due date. Server: `PortalGrievanceDueH2Test`. |
| D-23 | Rights: access, correction, completion, updating, erasure, nomination ([A] s.11, s.12, s.14; [R] r.14) | PARTIAL (round 4 D1: submission by the signed-in data principal works, decision 335; round 4 D6: OTP intake by a compliance-owned one-time code, decision 360; issue limits per identifier and per client address, keyed identifier hashes, decision 369 (d), (e)) | `rights-requests` (the select offers exactly the India pack rights; OTP refusals by name; one code over the pack's per-identifier limit is 429 `OTP_RATE_LIMITED`); `erasure-legal-hold` (an ERASURE submission and its execution) | The portal offers only a generic data-rights kind; the OTP requester is the identifier itself (decision 369 (c)). The per-address limit counts the resolved client address, so `server.trustedProxies` must name the UI and ingress, or every citizen behind them shares one address (see the decision 369 section). |
| D-24 | Children: guardian consent, no tracking or targeted advertising ([A] s.9; [R] r.10, r.12) | PARTIAL (round 4 D6: the consent row of a declared child carries the guardian verification id with no principal directory, decision 365) | `no-tracking` ((a): no request, beacon, WebSocket, subresource or third-party cookie off the origin); `portal-children` ((b): the portal asks the age when the pack has a child-age limit; a declared child without a valid parent verification is refused, 403 `GUARDIAN_CONSENT_REQUIRED`, a missing age 422 `AGE_DECLARATION_REQUIRED`) | Owner list 8, done for the portal (owner decision 343, fail closed through the existing guardian verifier). The accepted child path needs a parent verification, which only an integrator-bound `AdultIdentityVerifier` creates (decision 68); it is proven by `PortalComplianceAdapterH2Test`, not e2e. D-25 is unchanged. |
| D-25 | Guardian of a person with a disability ([R] r.11) | PARTIAL | none | Owner list 8. |
| D-26 | Significant Data Fiduciary duties ([A] s.10; [R] r.13) | PARTIAL | none | Applies only if a deployer is notified as an SDF. |
| D-27 | Transfer outside India ([A] s.16; [R] r.15) | EXISTS (server) | none | Server test. |
| D-28 | No term that waives rights ([A] s.6(2)) | EXISTS | `consent-notice` (a purpose saying "irrevocable" is refused with 422 `NOTICE_INVALID`, and no notice is published) | |
| W-01 | Strict CSP | PARTIAL | `security-headers` (fixed directives on every route and asset; no violation across the app) | `style-src 'unsafe-inline'` for bpmn-js; Trusted Types, see W-02. |
| W-02 | Trusted Types | PARTIAL (report-only, decision 349, lane D3) | `security-headers` (`Content-Security-Policy-Report-Only: require-trusted-types-for 'script'` on every route, never in the enforced policy; the sinks the app reports are attached to the run as `trusted-types-reports.json`, nothing is blocked) | Enforcement is the next step, after the reported sinks (bpmn-js, dmn-js) are handled. No CSP report endpoint exists, so reports stay in the browser (console, `securitypolicyviolation`); an endpoint is a server contract change. |
| W-03 | No unsafe DOM sinks | EXISTS | `xss-rendering` (markup in a grievance subject and a purpose name shown as text) | |
| W-04 | No third-party subresources | EXISTS | `no-tracking`, `shell.e2e.ts` | |
| W-05 | Clickjacking | EXISTS | `security-headers` (`X-Frame-Options DENY` and `frame-ancestors 'none'`, `/teams.html` too while Teams is off) | |
| W-06 | Other headers | PARTIAL | `security-headers` (nosniff, Referrer-Policy, Permissions-Policy, COOP, no server version) | HSTS belongs to the TLS ingress, not this image. |
| W-07 | No tokens or PII in persistent storage; clean-up at sign-out | EXISTS (decision 337, lane D3) | `client-storage-and-urls` (token in sessionStorage only; none in any web storage after sign-out; a saved inbox filter keeps no search text and its key is a hash, never the user id); `savedFilters.test.ts`, `ui-parity-2.test.tsx` (P35, decision 337) | Saved inbox filters stay in localStorage by design (P35), without the free text and under SHA-256 of tenant and user. Old entries lose their free text on load; another user's old key keeps its name until that user opens the inbox. |
| W-08 | No PII in URLs | EXISTS (round 4 D1: principal look-ups are `POST .../consents/search` and `.../rights-requests/search` with the reference in the body; the `subject` query is refused, `SUBJECT_QUERY_WITHDRAWN`, decision 338) | `client-storage-and-urls`; `access-control`; `consent-withdrawal`; `erasure-legal-hold` | |
| W-09 | Session lifetime and idle timeout | EXISTS (expiry; idle by decision 349, lane D3) | `session-expiry` (an expired token is refused with 401, and the UI returns to sign-in; the image serves `session.idleMinutes` 15 by default; after the idle period a warning, then the session ends and the user must sign in again; "Stay signed in" keeps it); `src/auth/idle.test.tsx`; Teams tab (decision 359, lane D5): `src/teams/idle.test.tsx` | Idle period: `SANGAM_UI_IDLE_MINUTES` of the UI image (1-480, default 15, checked by `18-sangam-session.envsh`); Helm `ui.idleMinutes` (schema 1-480), compose `SANGAM_UI_IDLE_MINUTES` (`.env`), `deploy/README.md` (lane D5). The default follows NIST SP 800-63B (2017) 4.3.3 AAL3 and OWASP ASVS 4.0.3 V3.3.2 level 3, technical references only: the legal source of a specific period is UNVERIFIED. Decision 359: exempt only on the hall display route (`/display/queue/`, `App.tsx`, nobody gives it input); the optional Teams tab has it when enabled, and there the session ends by forgetting the tab's tokens (`AuthProvider providerSignOut={false}`: the provider's sign-out page cannot run in a Teams frame). |
| W-10 | No stack traces or internals in errors | EXISTS | `access-control` (compliance, portal and notice ids that do not exist) | |
| W-11 | No public production source maps | EXISTS (fixed in this lane: `build.sourcemap: false` in both Vite builds) | `security-headers` (no `sourceMappingURL`; every `.map` gives 404); `scripts/build-sourcemaps.test.mjs` | |
| W-12 | postMessage origin validation | EXISTS (N/A today) | none | No message listener in the UI. |

## Product fixes made in this lane

- **Rights and contact-role codes come from the jurisdiction pack.** The UI reads them from `GET /compliance/jurisdiction` and keeps no list of its own. The old UI list offered `UPDATE`, but the India pack defines `UPDATING`, so every "updating" request was refused. The contact-role list also lacked three of the pack's roles.
  - Files: `compliance.tsx`, `compliancePackCodes.test.ts`.
- **The consent register and the rights list show the contract fields.** They now show `consentId`, `activePurposes`, `requestId` and `receivedAt`. The old columns named fields the server does not return, so those columns were always empty.
- **No source maps in the UI build or the embed build.** This fixes W-11.
- **Missing English labels added:** `fld.language`, `opt.UPDATING`, `opt.CONTACT_PERSON`, `opt.RECORDS_OFFICER`, `opt.STATE_CERT_NODAL`.
- **e2e fixture fix:** `base.request.newContext` is undefined at run time. The fixture now uses the module's `request`.

## Open points for the owner (gap, options, recommendation)

1. **Rights intake is refused in the standalone server.** `ComplianceWiring` passes no principal authenticator, so `DataSubjectRightsService.submit` always fails with `PRINCIPAL_NOT_AUTHENTICATED`. This blocks D-16, D-17 and the D-23 submission.
   - **Decided and done (decision 335, round 4 D1).** The pack names the methods (`verification.principal`: IN `SIGNED_IN`, `OTP`, r.14(1)). `SIGNED_IN` uses the identity the server authenticated, never the body; another person's identifier is refused. OTP stays refused until an authenticator is wired (round 4 D1 open point A).
   - Options:
     - (a) an authenticator over the signed-in Sangam identity, for the data principal's own request;
     - (b) OTP to the published identifier;
     - (c) both, chosen by the pack.
   - Recommendation: (c), with (a) first.
2. **Breach and incident registration in a standalone server.** The registration starts `sangam-breach-incident` and `sangam-incident-report`. The server has `auto-deployment-enabled: false`, and nothing deploys `packs/compliance/*.bpmn`, so the e2e spec was not written. Not confirmed by running.
   - **Decided and done (decision 336, round 4 D1).** The processes come only from the installed pack `sangam.compliance`; without it the start is refused with `COMPLIANCE_PACK_NOT_INSTALLED` (409). The test-mode server deploys the packs at start-up (h2 profile), so `breach-and-incident.e2e.ts` was written.
   - Options:
     - (a) deploy the compliance processes at start-up when a jurisdiction is set;
     - (b) install a compliance pack through the pack lifecycle API;
     - (c) refuse with a clear problem until the pack is installed.
   - Recommendation: (b) for production, plus (c) as the fail-closed refusal. The e2e setup would install the pack.
3. **Saved inbox filters and data-principal references in URLs.**
   - **W-08 decided and done (decision 338, round 4 D1):** option (a), `POST .../search` with `ComplianceSubjectSearch`; the `subject` query is a 400 `SUBJECT_QUERY_WITHDRAWN`, recorded as a 0.1.0 correction. The inbox filters (decision 337) are lane D3's.
   - Inbox filters (W-07): they stay in localStorage after sign-out. This is intended (P35, the "Saved filters are kept in this browser" copy). Options:
     - (a) clear them at sign-out, or keep them in sessionStorage;
     - (b) keep them, but never store the free-text query, and key them by a hash of the user;
     - (c) a server-side preference API.
     - Recommendation: (b) now, (c) later. DONE for (b) (decision 337, lane D3): `src/areas/tasklist/savedFilters.ts`.
   - References in the query string (W-08): `listConsents` and the rights list take only a `subject` query parameter. Options:
     - (a) `POST …/search` with the reference in the body;
     - (b) an opaque principal id;
     - (c) accept it, with access-log masking.
     - Recommendation: (a). It is a contract change through `sangam-rest/api/*.py`.
4. **The portal application records no consent and shows a fixed notice string** (D-01, D-04).
   - Options:
     - (a) show the published notice on the form and send `noticeKey`, `noticeVersion` and the purposes with the submission, written in the same transaction;
     - (b) a separate consent step before the form.
   - Recommendation: (a).
   - **Done (owner decision 339): option (a).** The service's registry entry names its notice (`noticeKey`); the form shows it (`GET /portal/services/{key}/notice`) and the final submission sends `consent` (key, version, language, purposes, age). The server records it through `ConsentService.give` in the transaction that starts the application and refuses, by name, a notice that is not the current published one (409 `NOTICE_NOT_CURRENT`) and a missing consent (403 `CONSENT_REQUIRED`). Without the compliance module a consent is refused with 501.
5. **Self-service withdrawal** (D-05).
   - Options:
     - (a) a "My consents" portal page using the existing withdraw endpoint, which already lets the owner withdraw their own consent;
     - (b) a link from the notice only.
   - Recommendation: (a).
   - **Done (owner decision 340): option (a)**, `/portal/consents`. Purpose names come from the current notices of the caller's portal services; any other purpose is shown by its code.
6. **Bundled consent, and a draft saved before consent** (D-03).
   - Options:
     - (a) one checkbox per purpose;
     - (b) keep one checkbox when the notice has one purpose.
   - For the draft: counsel decides whether it is a legitimate use under s.7(a).
   - Recommendation: (a), plus counsel on the draft.
   - **Done (owner decision 341): option (a).** **OPEN for counsel:** whether "Save draft" may store personal data before consent. The draft behaviour is unchanged; a draft never carries a consent (400 if one is sent).
7. **The evidence report cannot be generated from the UI.** `ComplianceController.report` requires `from` and `to` as instants. The UI sends optional date-only values, so the server refuses the request (found in this lane, not run).
   - **Decided and done (decision 342, round 4 D1):** option (a). The UI sends the start of the first day and of the day after the last as instants in the tenant zone (`/me` `locale.zone`, `compliancePeriod.ts`); with no zone it sends nothing.
   - Options:
     - (a) the UI requires both dates and sends the start of `from` and the end of `to` in the tenant's time zone;
     - (b) the server defaults the period.
   - Recommendation: (a). The time zone needs a decision first, so it was not fixed here.
8. **Children and guardians in the portal** (D-24 (b), D-25).
   - Options:
     - (a) an age declaration with a guardian-consent step through the existing verifier, which fails closed (decision 68);
     - (b) operator-assisted only.
   - Recommendation: (a).
   - **Done for D-24 (b) (owner decision 343): option (a).** The form asks the age when the pack has `limits.childAgeYears`; a declared child needs a parent verification that `VerifiableParentalConsentService.requireValid` accepts for that child, unless every purpose is exempt by the pack. Open: no REST endpoint creates a parent verification and no `AdultIdentityVerifier` is bound by default (decision 68), so a child cannot apply in the standalone server; and the consent row carries no guardian verification id there, because `ConsentService` takes it only when a principal directory says CHILD (none in the server). Options: (a) `GiveConsent` carries the declared category; (b) a principal directory fed by the declarations. Recommendation: (a), owned by the compliance lane.
9. **CERT-In incident type is free text** (C-02).
   - **Decided and done (decision 344, round 4 D1):** option (a), and the server refuses a type outside the catalogue (`INCIDENT_TYPE_NOT_IN_CATALOGUE`, 422), for an incident and for a breach, before anything is written.
   - Options:
     - (a) a pick-list from the pack's `authorities[].catalogue`, through the same pack-code field as the rights;
     - (b) keep the free text, which the server already checks.
   - Recommendation: (a).
10. **Point-of-contact Annexure II fields** (C-03). The server requires designation, mobile and address for `CERT_IN_POC` (`contactRoles[].requiredFields`). The form exposes them only through the JSON "more fields".
    - **Decided and done (decision 345, round 4 D1):** option (a). The form shows the chosen role's `requiredFields`, and the residency attestation for a `mustBeResident` role.
    - Options:
      - (a) render the `requiredFields` of the chosen role;
      - (b) fixed extra fields.
    - Recommendation: (a), which is pack-driven.
11. **DPO contact in rights and grievance responses** (D-21).
    - **Decided and done for rights requests (decision 346, round 4 D1):** option (a), `contact` on every rights-request read. The portal grievance (`OperatonPortalAdapter`) is lane D2's.
    - Options:
      - (a) the acknowledgement and the response template carry the published contact;
      - (b) a link to the privacy page.
    - Recommendation: (a).
12. **Breach notice in the data principal's account** (D-15).
    - Options:
      - (a) a portal notification with the five r.7(1) items;
      - (b) e-mail or SMS only, if counsel agrees.
    - Recommendation: (a).
    - **Done (owner decision 347): option (a).** `BreachIncidentService.notifySubjects` tells its subject-notice listeners after each delivery; the REST layer writes the notice to the principal's portal account (`GET /portal/notifications`, page "My notices") in the same transaction, so a failed write rolls the delivery back.
13. **Audit of reads of personal data** (D-11).
    - **Decided and done (decision 348, round 4 D1):** option (a), `PERSONAL_DATA_READ` (SANGAM.md X.4.0.3).
    - Options:
      - (a) an audit event when an officer reads a principal's consents or rights;
      - (b) access logs only.
    - Recommendation: (a).
14. **Idle timeout (W-09) and Trusted Types (W-02).**
    - Idle timeout options: (a) a configurable idle period with a warning, then sign-out; (b) leave it to the IdP session. The legal source of a period is UNVERIFIED.
    - Trusted Types options: (a) report-only first, then enforce; (b) not yet, because bpmn-js writes innerHTML.
    - Recommendation: (a) for both, the period set by policy. DONE (decision 349, lane D3): idle (a) with a warning, the period from `SANGAM_UI_IDLE_MINUTES` (default 15); Trusted Types (a), report-only step.
15. **Grievance due date fixed in code.** `OperatonPortalAdapter.fileGrievance` sets `dueAt` to now + 30 days. The pack has GRIEVANCE `defaultDays 30` and `maxDays 90`.
    - Options:
      - (a) read `defaultDays` from the pack;
      - (b) keep it.
    - Recommendation: (a).
    - **Done (owner decision 350): option (a).** The due date is the pack's GRIEVANCE period through `ComplianceConfig.rightSlaDays` (default `defaultDays`, the tenant's `sla.grievance-days`, capped at `maxDays`). Open: with no jurisdiction set (or without the compliance module) the grievance has no due date. Options: (a) keep no due date; (b) refuse the grievance until a jurisdiction is set. Recommendation: (a), a grievance is never lost for a missing setting.
16. **The Javadoc of `ApiTokenService.issue` says the scopes are a subset of the owner's roles, but the code does not check it.**
    - Options:
      - (a) enforce the subset, fail closed;
      - (b) correct the Javadoc.
    - Recommendation: (a), owned by the security lane. The e2e identities do not rely on either behaviour.
    - Decision 351 chose (a). NOT YET IMPLEMENTED (lane D3, 2026-11-15): three callers of `issue` give scopes the owner does not hold, by design. (1) `BreakGlassService.approve` issues the class roles (for example `tenant-admin`) to a holder who lacks them, after dual approval. (2) `SecurityIdentityAdminBridge.createServicePrincipal` creates a new `svc-<name>` person with no role, then issues the requested scopes. (3) Every e2e identity is such a service principal (`e2e/support/identities.ts`), so the sentence above does not hold. Enforcing the subset inside `issue` alone would break all three. The options are with the owner.
    - **Done (decisions 358 and 368, lane D5, 2026-11-15).** `ApiTokenService.issue` refuses every scope that is not a role the owner holds now (role bindings of the person, its groups and seats, mirrored IdP roles, `PrincipalResolver.heldRoles`; for one's own token also the session's roles) with `409 TOKEN_SCOPE_NOT_HELD`, naming the scopes; nothing is stored. (1) Break-glass issues through its own path, `ApiTokenService.breakGlassIssuer()`, which only `BreakGlassService` holds (taken once at construction); the roles come from the token class after dual approval, and every use still raises the alarm. (2) The platform groups the server grants permissions to (`Permissions.DEFAULT_GROUP_PERMISSIONS`, for example `sangam-worker`, `sangam-citizen`, `sangam-dpo`; not `sangam-admin`) are role rows (SEC_ROLE, no rules of their own) of each tenant, created when the tenant is set up (`TenantAdminService.createTenant` → `RoleAdminService.ensurePlatformRoles`; the development bootstrap adds missing rows to an existing tenant, never overwriting a defined role). `createServicePrincipal` binds each requested scope to the new principal through `RoleAdminService.bind` before it issues: `409 UNKNOWN_ROLE` for a scope that is no defined role, `403 ACTION_NOT_PERMITTED` naming the scope when the caller may not grant it; either rolls the creation back. (3) The e2e set-up defines its role-less scope through the new `PUT /api/v1/admin/roles/{roleId}` (policy.admin; CHANGE_GRANT on ROLE) and then creates its identities as before. Tests: `ServicePrincipalScopeRolesH2Test` (7) and `BreakGlassServiceH2Test` (2 new), each shown failing with its part of the fix removed. Decision 370 (1), done: at every server start the missing platform role rows of every existing tenant are filled in (idempotent, never overwriting a defined role, audited per tenant; refused when no list is bound), `PlatformRoleRowsAtStartH2Test`. Decision 370 (2): an IdP group name alone is never a held role.
17. **Type-checking the e2e suite needs `@types/node`.** DONE (decision 352, lane D3): `@types/node` 22.20.5 (the CI Node line, ui.yml `node-version` 22.18.0) is an exact-pinned dev dependency, recorded in `docs/records/DOWNLOADS-2026-11-15.md`. `npm run typecheck:e2e` (`tsc -p e2e/tsconfig.json`) reports 0 errors. A CI step since lane D5 (2026-11-15): ui.yml "Type-check the end-to-end specs", ledger check `ui-typecheck-e2e` in the `pr-ui` gate (`release-checks.json`).
    - Options were:
      - (a) add `@types/node` as a pinned devDependency (needs approval);
      - (b) keep the filtered check.
    - Chosen: (a).

## Round 4, lane D1 (2026-11-15)

- Owner decisions 335, 336, 338, 342, 344, 345, 346 and 348: see the rows and the owner list above.
- **Integrator finding, CI ui e2e run 43717963671 (main 209d2765):** `PUT /compliance/purposes/{code}` bound its body to `PurposeDefinition`, whose `standardsAttested`, `version`, `active` and `DataItem.sensitive` are primitives; the JSON binding refuses a missing primitive, so the e2e seed failed with 400. The body is now `ComplianceDefinePurposeRequest`: `standardsAttested` and `active` required, every data item's `sensitive` required, no `version` (the server assigns it), each an explicit Yes / No in the form, never defaulted. The contact body's `resident` is boxed: omitted is not attested. Recorded as a 0.1.0 correction.
- Open point A, OTP intake. Options: (a) a compliance-owned OTP challenge sent through `PrincipalNotifier` [recommended]; (b) the Aadhaar OTP connector; (c) the IdP.
- Open point B, erasure execution: the standalone server wires no `PersonalDataPort`, and there is no legal-hold API on a process root, so D-16 execution and D-17 are not shown end to end. Options: (a) a port over the process-instance and document stores [recommended]; (b) operator-only erasure.
- Open point C, other compliance request bodies with primitives the contract does not require: `VerifyLawfulRequest.rankAdequate`, `AuditEvidenceRequest.independent`, `ImpactAssessmentRequest.independent`, `TransferRequest.recipientSameProtectionAttested` and `recipientConditionMet`, `ConsentArtefact.noticeVersion`. Options: (a) box and require them, as was done for purposes [recommended]; (b) leave them.

## Round 4, lane D6 (2026-11-15)

- **Open point C decided and done (decision 362):** option (a). `VerifyLawfulRequest.rankAdequate`, `AuditEvidenceRequest.independent`, `ImpactAssessmentRequest.independent`, the transfer's `recipientSameProtectionAttested` and `recipientConditionMet` (REST body `EvaluateTransferRequest`) and the artefact's `noticeVersion` (REST body `ConsentArtefactRequest`) are boxed, required by the contract, and an omitted one is 400 `VALIDATION_FAILED` naming the field, never defaulted; the officer forms ask each as an explicit Yes / No. Recorded as 0.1.0 corrections. Tests: `ComplianceAttestationRequestsTest`, `complianceAttestations.test.ts`.
- **Open point A decided and done (decision 360):** option (a). `PrincipalOtpChallenges` is the authenticator when none is bound: `POST /compliance/rights-requests/otp-challenges` (public, like the submission) sends a code through `PrincipalNotifier` to an identifier type the tenant accepts (`rights.otp.identifier-types`, IN pack defaults MOBILE and EMAIL; an SMS needs the tenant's `rights.otp.sms-template-id`); `.../{challengeId}/verify` checks it in constant time against a salted hash; the submission names the verified challenge in `proof.challengeId` and consumes it once. Lifetime, attempts, lock-out and length come from configuration over the IN pack defaults (10 minutes, 5 wrong codes per identifier within 30 minutes, 30 minutes lock-out, 6 digits), none defaulted in code (`OTP_NOT_CONFIGURED`); a new code supersedes the open one; tenant-scoped; `RIGHTS_OTP_ISSUED`, `RIGHTS_OTP_VERIFIED`, `RIGHTS_OTP_FAILED` (SANGAM.md X.4.0.3 Security) carry no code and no identifier. Table `CMP_OTP_CHALLENGE` (changeset 0045-21). Test: `PrincipalOtpChallengesH2Test`.
- **Open point B decided and done (decision 361):** option (a). `CompliancePersonalData` (sangam-server) implements `PersonalDataPort`: roots = the tenant's process roots whose `sangamApplicantId` or `principalRef` is the principal, plus the retention register's roots; correlation keys = their business keys; summary = process, start, end and variable names (no values). `LegalHoldService` places a hold (kind RTI, AUDIT, LITIGATION, VIGILANCE, OTHER; SANGAM.md X.2.3.5 #5) on a process root of the tenant through `POST /compliance/process-roots/{rootId}/legal-holds` (compliance.modify), lists it, and releases it only by a different person (`FOUR_EYES_REQUIRED`); both audited on the root's chain. A held root is not handed to the erasure port; the refusal writes `CMP_RETENTION_HELD` on its chain with the reason, and the request closes naming `LEGAL_HOLD [ids]`. Tests: `LegalHoldErasureH2Test`, `CompliancePersonalDataTest`.
- **D-24 (decision 365):** `GiveConsent` carries the declared category (`ADULT` or `CHILD`, `declaredCategory`, required on `POST /compliance/consents`); a declared child needs the verifiable consent of the parent and the consent row carries the guardian verification id with no principal directory; the more protective of the declared and the directory category applies; a channel that declares nothing (a consent manager's artefact) leaves the directory to decide. Tests: `DeclaredChildConsentH2Test`, `GiveConsentCategoryRequestTest`. The portal submit passes the declared category at integration (see the lane report).

## Round 4, lane D6, decision 369 (2026-11-15)

- **(d) OTP issue limits:** `PrincipalOtpChallenges.issue` counts the codes issued within `rights.otp.issue-window-minutes` for the identifier (`issue-max-per-identifier`) and for the client address (`issue-max-per-address`); one more is 429 `OTP_RATE_LIMITED` naming the limit. The limits come from configuration over the IN pack defaults (60 minutes, 5 per identifier, 20 per address; owner to confirm with the (i) values), none defaulted in code (`OTP_NOT_CONFIGURED`). The client address is the server's resolved address (forwarding headers only from `server.trustedProxies`), stored as a keyed hash (`CMP_OTP_CHALLENGE.client_hash`, changeset 0045-22). A refused issue (limit or lock-out) rolls the caller back, so `RIGHTS_OTP_FAILED` is a denial that commits on its own (M-009) on the denial chain of the identifier's token; before this, the lock-out refusal of an issue was audited in the rolled-back transaction and lost. Test: `PrincipalOtpChallengesH2Test` (limits, window, configuration, the audit surviving the rollback).
- **(e) Keyed references:** `PrincipalKey` (HMAC-SHA256, at least 32 bytes) keys the principal token of every compliance audit payload (consent proof, withdrawal, guardian verification, nomination, personal-data read, OTP) and the stored identifier and client-address hashes; the earlier plain SHA-256 is gone. sangam-server reads it from `sangam.compliance.principal-key-ref`, a `secret://` reference resolved through the `SecretResolver`; a literal key, an unknown secret or a short key stop the start. Without the key every such operation is 409 `PRINCIPAL_KEY_NOT_CONFIGURED` and nothing is written (fail closed). Tests: `PrincipalKeyTest`, `CompliancePrincipalKeyWiringTest`, `PrincipalOtpChallengesH2Test`, `DeclaredChildConsentH2Test`, `CompliancePrincipalReadsH2Test`. The e2e workflow, compose (`.env.example`) and deploy/README.md section 1 carry the key.
- **(g) Erasure end to end:** `erasure-legal-hold.e2e.ts` creates a citizen of its own, starts two process roots with that citizen's data, holds one, files an erasure, completes the records officer's `u_approve` task with `APPROVE`, and asserts the free root's variables are gone, the held root keeps them, and the request closes `PARTIALLY_RESOLVED` naming `LEGAL_HOLD` and the hold id. Written, NOT_RUN locally (Playwright runs in CI).
- **(h) Affirmative action:** see D-03.
- **Decision 371:** (5) in production the server refuses to start without `sangam.compliance.principal-key-ref` (`CompliancePrincipalKeyWiringTest.unsetInProductionTheStartIsRefusedByName`); outside production the by-name refusal stays. (1) `server.trustedProxies` must list the UI and the ingress (deploy/README.md section 1, Helm values); refusing to start in production while it is empty is a follow-up before release (gap register DEC-371-1). (2) the bounded overshoot of the count-then-insert check is accepted for 0.1.0.
- **(a), (b), (c), (i):** recorded as decided (partial erasure; correction of process data refused with `INVALID_STATE`; the OTP principal reference is the identifier; the IN OTP values stay, owner to confirm before release).

## Carried over as UNVERIFIED

1. G.S.R. 843(E): which DPDP Act sections commence when.
2. The exact calendar dates the DPDP Rules phases commence: about 13/14 Nov 2026 for rule 4, and about 13/14 May 2027 for rules 3, 5–16, 22 and 23.
3. Corrigendum G.S.R. 892(E) of 10 Dec 2025 and what it changes.
4. The CERT-In SBOM / QBOM / CBOM / AIBOM / HBOM Guidelines v2.0: date and contents.
5. The exact wording of DPDP Act s.6(10), as used in D-04.
6. A legal source for a specific session idle timeout (W-09). Still UNVERIFIED (decision 349); the default of 15 minutes rests on technical references only (NIST SP 800-63B, OWASP ASVS).

Code facts from the draft, as they stand now:
- **The UPDATE / UPDATING refusal:** fixed.
- **Test mode loads the India pack:** now required by global-setup.
- **Whether reads of personal data are audited:** open, owner list 13.
- **HSTS at the ingress:** open; ingress profile.
- **Whether `sangam-backup` covers r.6(1)(d):** open; ops.
