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
| C-02 | Annexure I incidents reported to CERT-In within 6 h ([D] dir.(ii)) | PARTIAL | none | Owner list 2 (no spec) and 9 (`type` is free text). |
| C-03 | Point of contact in the Annexure II format ([D] dir.(iii)) | PARTIAL | `rights-requests` (the role select offers `CERT_IN_POC` and every India pack role) | Owner list 10: designation, mobile and address only through "more fields". |
| C-04 | Logs kept 180 days in India and provided on order ([D] dir.(iv)) | EXISTS (server) | `audit-evidence` (India pack, floors; report hash = SHA-256 of the report served) | Owner list 7: the UI cannot generate the report. |
| C-05 | All ICT systems, including the nginx proxy ([F] Q37) | PARTIAL | none | nginx has no retention or forwarding of its own; ops. |
| C-06 | Logs and information on order ([D] dir.(iii)-(iv)) | EXISTS | none | `/audit/exports`, lawful requests. |
| C-07 | KYC records of hosting providers ([D] dir.(v)) | N/A | none | Only if Sangam is offered as a hosted service. |
| C-08 | Annual audit, OWASP WSTG, SBOM ([C] §6, §8) | PARTIAL | the W-rows below are the UI-side WSTG evidence | The audit report is a record; [S] is UNVERIFIED. |
| D-01 | Notice: itemised data, purposes, links to withdraw, rights and the Board ([A] s.5(1); [R] r.3) | PARTIAL | `consent-notice` (the public policy signed out and the published notice: links, data items, purpose, DPO, version, hash) | Owner list 4: the portal form shows a fixed string, not the published notice. |
| D-02 | Notice in English or an Eighth Schedule language ([A] s.5(3)) | PARTIAL (owner decision Q-06: English-only UI) | none | The server templates exist for more languages; the UI chrome is English. Accepted gap. |
| D-03 | Free, specific, unbundled consent ([A] s.6(1)) | PARTIAL | none | Owner list 6: one checkbox for all purposes; a draft is saved before consent. |
| D-04 | Proof of notice and consent ([A] s.6(10), wording UNVERIFIED) | PARTIAL (was MISSING: the operator path now shows the consent id and purposes; fixed in this lane) | `consent-withdrawal` (records on the published notice; the register row and the API show the key, version, purposes and `givenAt`) | Owner list 4: the portal application records no consent. |
| D-05 | Withdrawal as easy as giving ([A] s.6(4); [R] r.3(c)(i)) | PARTIAL (operator only) | `consent-withdrawal` (withdraw → `WITHDRAWN`, `withdrawnAt`) | Owner list 5: no self-service withdrawal for the data principal. |
| D-06 | Processing stops after withdrawal ([A] s.6(5)-(6)) | PARTIAL | none | `PurposeBindingGuard`; the end-to-end stop is not shown. |
| D-07 | Consent managers ([A] s.6(7)-(9); [R] r.4) | EXISTS (server) | none | Server test. |
| D-08 | Legitimate uses ([A] s.7) | EXISTS (server) | none | Server test. |
| D-09 | Safeguards: encryption, masking, tokens ([A] s.8(5); [R] r.6(1)(a)) | PARTIAL | `client-storage-and-urls`, `xss-rendering` (UI side only) | |
| D-10 | Access control ([R] r.6(1)(b)) | EXISTS | `access-control` (user with no role and citizen refused in the UI and with 403; the officer passes by permission) | |
| D-11 | Visibility of access to personal data ([R] r.6(1)(c)) | PARTIAL | none | Owner list 13: reads are not shown to be audited. |
| D-12 | Logs and data kept for at least one year ([R] r.6(1)(e), r.8(3)) | EXISTS | `audit-evidence` (pack floors 365 days) | |
| D-13 | Continuity, backups ([R] r.6(1)(d)) | PARTIAL (not verified) | none | Ops drill record. |
| D-14 | Breach: intimation, then a report to the Board within 72 h ([A] s.8(6); [R] r.7(2)) | EXISTS (server) | none | Owner list 2: registration needs the compliance processes, which nothing deploys. |
| D-15 | Breach notice to each affected data principal ([R] r.7(1)) | PARTIAL | none | Owner list 12. |
| D-16 | Erasure ([A] s.8(7), s.12(3)) | EXISTS (server) | none | Owner list 1: rights requests are refused in the standalone server. |
| D-17 | Legal hold overrides erasure ([A] s.8(7)) | EXISTS | none | Owner list 1 (same block as D-16). |
| D-18 | 48-hour pre-erasure intimation ([R] r.8(2)) | EXISTS (server) | none | Time-based; server test. |
| D-19 | Third Schedule 3-year inactivity erasure ([R] r.8(1)) | EXISTS | none | Counsel: does it apply to deployers? |
| D-20 | Purpose deemed no longer served ([A] s.8(8)) | EXISTS | none | `/admin/retention`. |
| D-21 | DPO contact published, and given in rights responses ([A] s.8(9); [R] r.9) | PARTIAL | `consent-notice` (the DPO name and e-mail on the public policy, signed out, and in the notice) | Owner list 11: rights and grievance responses do not carry the contact. |
| D-22 | Grievance redressal within the published period, at most 90 days ([A] s.8(10), s.13; [R] r.14(3)) | EXISTS | `rights-requests` (reference shown, the published period shown, due date ≤ the pack maximum) | Owner list 15: due date fixed in code. |
| D-23 | Rights: access, correction, completion, updating, erasure, nomination ([A] s.11, s.12, s.14; [R] r.14) | PARTIAL (the UPDATE / UPDATING mismatch fixed in this lane) | `rights-requests` (the select offers exactly the India pack rights, `UPDATING` included) | Owner list 1: submission is refused in the standalone server; the portal offers only a generic data-rights kind. |
| D-24 | Children: guardian consent, no tracking or targeted advertising ([A] s.9; [R] r.10, r.12) | PARTIAL | `no-tracking` ((a): no request, beacon, WebSocket, subresource or third-party cookie off the origin) | Owner list 8: no age or guardian flow in the portal. |
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
| W-08 | No PII in URLs | PARTIAL | `client-storage-and-urls` (no token or typed text in any URL of the citizen's grievance flow) | Owner list 3: the register filters put the principal reference in the query string. |
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
   - Options:
     - (a) an authenticator over the signed-in Sangam identity, for the data principal's own request;
     - (b) OTP to the published identifier;
     - (c) both, chosen by the pack.
   - Recommendation: (c), with (a) first.
2. **Breach and incident registration in a standalone server.** The registration starts `sangam-breach-incident` and `sangam-incident-report`. The server has `auto-deployment-enabled: false`, and nothing deploys `packs/compliance/*.bpmn`, so the e2e spec was not written. Not confirmed by running.
   - Options:
     - (a) deploy the compliance processes at start-up when a jurisdiction is set;
     - (b) install a compliance pack through the pack lifecycle API;
     - (c) refuse with a clear problem until the pack is installed.
   - Recommendation: (b) for production, plus (c) as the fail-closed refusal. The e2e setup would install the pack.
3. **Saved inbox filters and data-principal references in URLs.**
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
5. **Self-service withdrawal** (D-05).
   - Options:
     - (a) a "My consents" portal page using the existing withdraw endpoint, which already lets the owner withdraw their own consent;
     - (b) a link from the notice only.
   - Recommendation: (a).
6. **Bundled consent, and a draft saved before consent** (D-03).
   - Options:
     - (a) one checkbox per purpose;
     - (b) keep one checkbox when the notice has one purpose.
   - For the draft: counsel decides whether it is a legitimate use under s.7(a).
   - Recommendation: (a), plus counsel on the draft.
7. **The evidence report cannot be generated from the UI.** `ComplianceController.report` requires `from` and `to` as instants. The UI sends optional date-only values, so the server refuses the request (found in this lane, not run).
   - Options:
     - (a) the UI requires both dates and sends the start of `from` and the end of `to` in the tenant's time zone;
     - (b) the server defaults the period.
   - Recommendation: (a). The time zone needs a decision first, so it was not fixed here.
8. **Children and guardians in the portal** (D-24 (b), D-25).
   - Options:
     - (a) an age declaration with a guardian-consent step through the existing verifier, which fails closed (decision 68);
     - (b) operator-assisted only.
   - Recommendation: (a).
9. **CERT-In incident type is free text** (C-02).
   - Options:
     - (a) a pick-list from the pack's `authorities[].catalogue`, through the same pack-code field as the rights;
     - (b) keep the free text, which the server already checks.
   - Recommendation: (a).
10. **Point-of-contact Annexure II fields** (C-03). The server requires designation, mobile and address for `CERT_IN_POC` (`contactRoles[].requiredFields`). The form exposes them only through the JSON "more fields".
    - Options:
      - (a) render the `requiredFields` of the chosen role;
      - (b) fixed extra fields.
    - Recommendation: (a), which is pack-driven.
11. **DPO contact in rights and grievance responses** (D-21).
    - Options:
      - (a) the acknowledgement and the response template carry the published contact;
      - (b) a link to the privacy page.
    - Recommendation: (a).
12. **Breach notice in the data principal's account** (D-15).
    - Options:
      - (a) a portal notification with the five r.7(1) items;
      - (b) e-mail or SMS only, if counsel agrees.
    - Recommendation: (a).
13. **Audit of reads of personal data** (D-11).
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
16. **The Javadoc of `ApiTokenService.issue` says the scopes are a subset of the owner's roles, but the code does not check it.**
    - Options:
      - (a) enforce the subset, fail closed;
      - (b) correct the Javadoc.
    - Recommendation: (a), owned by the security lane. The e2e identities do not rely on either behaviour.
    - Decision 351 chose (a). NOT YET IMPLEMENTED (lane D3, 2026-11-15): three callers of `issue` give scopes the owner does not hold, by design. (1) `BreakGlassService.approve` issues the class roles (for example `tenant-admin`) to a holder who lacks them, after dual approval. (2) `SecurityIdentityAdminBridge.createServicePrincipal` creates a new `svc-<name>` person with no role, then issues the requested scopes. (3) Every e2e identity is such a service principal (`e2e/support/identities.ts`), so the sentence above does not hold. Enforcing the subset inside `issue` alone would break all three. The options are with the owner.
17. **Type-checking the e2e suite needs `@types/node`.** DONE (decision 352, lane D3): `@types/node` 22.20.5 (the CI Node line, ui.yml `node-version` 22.18.0) is an exact-pinned dev dependency, recorded in `docs/records/DOWNLOADS-2026-11-15.md`. `npm run typecheck:e2e` (`tsc -p e2e/tsconfig.json`) reports 0 errors. A CI step since lane D5 (2026-11-15): ui.yml "Type-check the end-to-end specs", ledger check `ui-typecheck-e2e` in the `pr-ui` gate (`release-checks.json`).
    - Options were:
      - (a) add `@types/node` as a pinned devDependency (needs approval);
      - (b) keep the filtered check.
    - Chosen: (a).

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
