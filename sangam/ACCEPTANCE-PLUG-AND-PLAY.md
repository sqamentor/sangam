<!-- SPDX-License-Identifier: Apache-2.0 -->
# Plug-and-play acceptance plan

Goal: any country, government, department or private sector adopts Sangam by configuration alone (SANGAM.md X.0e, ADR-D-20 to 24). A scenario fails if it needs a Java change. Its data may change.

**Status: written, not yet run.** The scenario data has not been executed, so expect to correct data values on the first run. That is allowed. A needed product code change is a finding against conformance.

Code: `sangam-onboarding/src/test/java/org/sangam/onboarding/acceptance/PlugAndPlayAcceptanceTest.java`. Data: `sangam-onboarding/src/test/resources/acceptance/AT-n.json`.

## Environment

1. Build `sangam/`. For AT-6, build and sign `examples/plugin-connector-example` (see its README) and place the jar, `.sig` and `trusted-keys/*.pub` in the server's `lib-ext/plugins`.
2. Start `sangam-server` with:
   - `sangam.security.kek.keys.<id>` and `current-id` (a test key);
   - `sangam.onboarding.bundle.key-id`, `.private-key` and `.trusted-keys.<id>`;
   - `sangam.packs.simulator-fallback=true`, which is refused on a production profile. Without it, every pack connector needs a binding.
3. Set the environment and run:
   - `SANGAM_AT_URL`;
   - `SANGAM_AT_MAKER_TOKEN` and `SANGAM_AT_CHECKER_TOKEN`: two different people, both in `sangam-admin` (the static dev token is only one person, so use OIDC);
   - `SANGAM_AT_TENANT_TOKEN_<tenant>` for the business runs (dots become `_`); without it that part is skipped and reported as aborted, not passed;
   - `mvnw.cmd -B -pl sangam-onboarding test -Dtest=PlugAndPlayAcceptanceTest`.

Without the variables the class is skipped. Take the shared heavy lock (owner `customwf`) around the run.

## What every scenario proves

Dry run reports no error and leaves no run behind; the run reaches `AWAITING_APPROVAL`; the maker's own approval gets 422; a stale hash gets 409; the checker's approval brings the run to `LIVE` with the tenant `ACTIVE`.

## Scenarios

| Id | Scenario | Data | Pass criteria beyond the common ones |
|---|---|---|---|
| AT-1 | New state from zero, IFMS packs, bill end to end | Jurisdiction + category preset; sections adopted from a donor; `gov.ifms.bill` | Dependencies added automatically; `sangam-ifms-bill` starts as the tenant and reaches a wait state or ends |
| AT-2 | Central department with a delegation-rule exception | Scope under the parent government; override in the profile | The resolved profile carries the override; parent untouched |
| AT-3 | Private hospital | Sector packs and connectors | Admission process runs for the tenant |
| AT-4 | Private bank | Sector pack | Account-opening process runs for the tenant |
| AT-5 | Foreign treasury | New jurisdiction document on the regime preset, inline, no shipped country data | Chain resolves through regime preset to the new jurisdiction |
| AT-6 | Signed third-party connector plugin | Example plugin | Plugin `LOADED` with `signedBy`; test call succeeds; its pack calls it |
| AT-7 | Export, import, diff | Bundle of AT-1 into a second tenant | Bundle signed; dry-run clean; tampered bundle gets 422; diff shows only the allowed paths. Run AT-1 first |

## Manual checks

- **No jurisdiction:** with `SANGAM_COMPLIANCE_JURISDICTIONS` empty, a request without `compliance.jurisdictions` fails the dry run with `JURISDICTION_REQUIRED`; `/api/v1/compliance/**` answers 409 `JURISDICTION_NOT_SET`.
- **UI assets:** without a token, an enabled plugin's asset is served; a disabled or refused plugin's asset gets 404; `/plugins/ui` still needs a token.
- **Plugin refusal:** on a non-dev profile an unsigned jar is `REFUSED`; a jar changed after signing is `REFUSED` on every profile; an unsatisfied `requires` is `REFUSED`; the server starts in each case.
- **Plugin admin:** disable, restart, plugin `DISABLED` and its content absent; enable, restart; `/admin/plugins/audit` shows each step.
- **UI plugin:** `GET /plugins/ui` lists the example; its `index.js` is served with `nosniff`; `.html` in a bundle or a missing entry refuses or fails the bundle; disable removes it after restart.
- **Resume:** stop the control plane during PLACEMENT; the run is `FAILED`; restart it, `POST /runs/{id}/resume`; the run continues without repeating finished steps.
- **Fix loop:** remove a required key; the run is `WAITING_FOR_FIX` with `PROFILE_KEY_MISSING`; amend; it reaches approval.
- **Restart:** restart a live tenant's server; its connector routes, pack selection and compliance jurisdiction are restored.
