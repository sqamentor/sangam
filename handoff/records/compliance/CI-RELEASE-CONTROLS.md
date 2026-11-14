# CI and release controls for M-147 and M-148 (lane W3-X4)

Date of every claim: **2026-11-12**. The workflow line numbers were read in the worktree `workspace-scan-resume-06b524` on that date (VERIFIED). They may drift while other lanes edit the files. Nothing here was run.

## Status values

Every control writes exactly one of these five values. No other value exists.

| Value | Meaning |
|---|---|
| **PASS** | The check ran over all of its expected inputs, and it found no violation. |
| **FAIL** | The check ran and found a violation. |
| **SKIPPED** | The check did not run, for any reason: a condition was false, `if:` was false, the job was skipped, or a matrix leg was excluded. |
| **BLOCKED** | The check could not run because an input, tool, network or service was missing. |
| **NOT-RUN** | The check is defined but intentionally not executed in this pipeline (for example, an opt-in licence-acceptance lane that has no operator acceptance). |

---

## CR-01 Results ledger: a skipped, blocked or `|| true` check is never PASS

This rule is mandatory under decision 112 ("never report a skipped check as PASS"). It closes X1 G-4.

**The rule:**
1. Every required check writes one line to `results/<check-id>.json` containing `{check, status, inputs_expected, inputs_seen, tool_version, sha256_of_inputs}`.
2. A check whose expected inputs are missing reports **BLOCKED**. A check that did not execute reports **SKIPPED**. Neither is ever PASS.
3. Any command in a check whose result is suppressed (`|| true`, `continue-on-error: true`, `exit-code: "0"`, `--exit-code 0`, `2>/dev/null` hiding a tool failure) cannot produce PASS. The best such a check can report is a report-only status with an explicit **NOT-RUN (report-only)** label.
4. A final `release-gate` job (`needs:` on every gate job, `if: always()`) reads every ledger line. **The release fails unless every required check is PASS.** On pull requests the same job fails on any required check that is FAIL, SKIPPED or BLOCKED.
5. A lint guard (`.github/scripts/no_silent_pass.py`, run in `governance.yml`) fails a PR that adds `|| true`, `continue-on-error`, `exit-code: "0"` or `if-no-files-found: ignore` to a step listed as required, unless an allowlist entry gives the reason and the ledger status the step will write.

**Required checks** (for release): CR-02 to CR-07, CR-08 to CR-14, CR-16, CR-18, CR-19 and CR-20. CR-15 is required at the threshold set in C-18. CR-17 is required for build-tool forbidden components.

**Pass/fail:** PASS only when every required ledger line is PASS. Anything else is FAIL.

**Where it changes today:**
- New: `.github/workflows/release.yml` gets a final `release-gate` job.
- New: the `release-gate` job is also called from `licence-check.yml` and `ci-build.yml` (`workflow_call`).
- New file: `.github/scripts/no_silent_pass.py`.
- `governance.yml` gets one new step.

---

## CR-02 Forbidden coordinates in the shipped dependency tree

- **Rule:** run `dependency:tree -Dscope=runtime` with no `|| true`.
  - **FAIL** if any `deps.txt` contains a forbidden coordinate.
  - **BLOCKED** (which counts as fail) if the tree command exits non-zero, or if the number of `deps.txt` files is not the number of reactor modules with a runtime scope.
  - **PASS** only otherwise.
- **Changes today:** `licence-check.yml`, step "Forbidden coordinates anywhere in the shipped dependency tree" (lines 40-45). Remove `|| true` at line 42, and add the file-count assertion.

## CR-03 One SBOM per distribution, matching its contents

- **Rule:**
  - One CycloneDX SBOM exists for each release asset: the server, control-plane, gateway and rollup fat jars; the cli jar if released; sdk-java; lib-ext; the UI; each SDK package; each image; the validator image.
  - For each fat jar, the SBOM's set of third-party components equals the jar's `BOOT-INF/lib` list.
  - **FAIL** on a missing SBOM, or on a difference in either direction.
- **Changes today:**
  - `licence-check.yml`, step "CycloneDX aggregate SBOM (shipped scopes)" (lines 35-39).
  - `release.yml`, step "Maven SBOM (CycloneDX 1.6, shipped scopes)" (lines 107-111).
  - Both switch from `makeAggregateBom` to `makeBom` per module, plus a new comparison script, `.github/scripts/sbom_matches_jar.py`.

## CR-04 Checks on every released fat jar

- **Rule:** `check_licences.py --jar` and `build_tools_not_shipped.py --jar` run on every released jar, each named explicitly.
  - **FAIL** on a forbidden nested jar (LIC-1, LIC-2, LIC-3, LIC-4, LIC-5, LIC-7, LIC-10, build tools).
  - **BLOCKED** (fail) if a named jar is absent.
- **Changes today:**
  - `licence-check.yml`, steps "Licence policy" (lines 55-62) and "Build-time tools never shipped" (lines 63-72). The `for j in $(ls ...)` loops at 59-61 and 69-71 add nothing when no jar exists.
  - `release.yml`, steps "Licence gate (strict on release)" (lines 126-131) and "Build-time tools never shipped" (lines 132-136). Today they cover the server jar only, at lines 129 and 134.
  - `ci-build.yml`, step "Stage server jar and MariaDB driver" (lines 121-130). It warns and exits 0 when there is no jar (line 127). The step "Build-time tools never shipped" (lines 131-135) is skipped by `hashFiles` (line 132).
  - `release.yml`, step "Stage artefacts" (lines 92-106). The sdk-java and simulators copies use `|| true` (lines 105-106).

## CR-05 Strict licence gate on pull requests and releases

- **Rule:**
  - An unknown or unmapped licence fails, subject to C-01.
  - A multi-licence entry passes only with a recorded election, or an explicit OR expression.
  - A conditional licence passes only for its listed purl and condition.
  - **FAIL** on any forbidden, unknown or unelected licence in a shipped SBOM.
- **Changes today:**
  - `licence-check.yml`, step "Licence policy" (line 62 call): add `--strict`.
  - `deploy/licence/licence-policy.json`: set `"unknown_license": "fail"`.
  - `deploy/licence/check_licences.py`: `normalise()`, `licence_terms()`, `term_status()` (RM-18).
  - `ui.yml`, the UI licence step (line 75).

## CR-06 Licence gate over image SBOMs, with the OS-package rule

- **Rule:**
  - `check_licences.py --sbom` runs over every image SBOM and the air-gap image list, with the OS-package section C-03 decides.
  - **FAIL** on a forbidden licence (for example AGPL), or on an OS package outside the rule.
  - **BLOCKED** if an expected image SBOM is missing.
- **Changes today:**
  - `release.yml`: step "Image SBOMs (CycloneDX)" (lines 260-268) keeps generating them.
  - Step "Image SBOMs hold no build-time tool" (lines 275-277) is joined by a new step that runs `check_licences.py` over `../image-sbom/*.cdx.json`.
  - A new check over `deploy/airgap/images.txt` in the "Air-gapped bundle" step (line 400).

## CR-07 NOTICE and licence bundle complete for each distribution

- **Rule:**
  - Each distribution carries `THIRD-PARTY-NOTICES.txt` and `licenses/`.
  - Every shipped component in its SBOM has a licence text, a NOTICE text where its jar has one, and its election where one applies.
  - EPL, MPL and CDDL components have a source-location line.
  - **FAIL** on any gap.
- **Changes today:**
  - `release.yml`, "Stage artefacts" (lines 92-106) and the image builds (lines 188-258) gain the bundle.
  - New generator under `sangam/deploy/licence/`.
  - The Dockerfiles in `sangam/deploy/docker/*.Dockerfile` copy it.

## CR-08 Every shipped component has a decision-register entry

- **Rule:** every component in a shipped SBOM maps to a decision-register row whose label is ACCEPTED FOR DEFINED USE or ACCEPTED WITH CONDITIONS, and whose scope covers that distribution.
  - **FAIL** on REVIEW REQUIRED, REJECTED FOR DEFINED USE, a missing row, or a scope mismatch.
- **Changes today:**
  - New committed export of the register: `sangam/deploy/licence/decision-register.json`, generated from the inventory CSVs.
  - New flag in `check_licences.py`: `--register`.
  - Used from the "Licence policy" step of `licence-check.yml` and the "Licence gate" step of `release.yml`.

## CR-09 New or changed transitive components

- **Rule:** on each PR, compare each per-distribution SBOM against the committed baseline.
  - **FAIL** if a new group:artifact:version (or npm name@version) has no register row (CR-08). The PR must add the row with evidence.
- **Changes today:** `licence-check.yml`, a new step after the SBOM steps. New baseline file in `sangam/deploy/licence/`.

## CR-10 Unpinned actions and images

- **Rule:**
  - **Actions:** every `uses:` is a 40-hex SHA. The one allowlisted exception is `slsa-framework/slsa-github-generator`, which is referenced by tag by design.
  - **Images:** every image on a release path (Dockerfile `ARG *_IMAGE` defaults used by `release.yml`; `images.txt`; Helm values with `digest`) carries `@sha256:`. `latest`, `nonroot` and floating-minor tags fail.
  - **FAIL** on any violation.
- **Changes today:**
  - New script `.github/scripts/pins_check.py`, run in `governance.yml`.
  - All 123 `uses:` lines across `ci-build.yml`, `db-matrix.yml`, `governance.yml`, `licence-check.yml`, `quality.yml`, `release.yml`, `sdks.yml`, `security.yml` and `ui.yml`.
  - `.github/dependabot.yml`: the github-actions `open-pull-requests-limit` goes above 0 (C-29), and a `docker` ecosystem entry is added.

## CR-11 Verified tool downloads

- **Rule:**
  - No `curl ... | sh`.
  - Each binary tool is downloaded as a fixed release asset, and its SHA-256 is checked against a value committed in the repository.
  - Where the vendor signs releases, the signature is verified as well.
  - The cosign version is pinned.
  - **FAIL** on a hash or signature mismatch, or on a missing pin.
- **Changes today:**
  - `release.yml`, step "Image SBOMs (CycloneDX)": syft at line 263.
  - `release.yml`: `sigstore/cosign-installer@v3` at line 360 (add `cosign-release:`).
  - `ci-build.yml`: the grype step at line 195.
  - The kubeconform, gitleaks and kubescape install steps (in `ci-build.yml` and `security.yml`).

## CR-12 Pinned SBOM generators

- **Rule:**
  - cyclonedx-maven-plugin is pinned in the root pom `pluginManagement` and resolved from the cache, with a recorded hash.
  - cyclonedx-npm is installed by `npm ci` from a committed tools lockfile.
  - **FAIL** on a call by bare coordinates or through `npx --yes`.
- **Changes today:**
  - `licence-check.yml` lines 37 and 54.
  - `release.yml` lines 109 and 123.
  - `ui.yml` line 71.
  - Root `sangam/pom.xml` `pluginManagement`.

## CR-13 Maven repository and integrity

- **Rule:**
  - Every non-Central repository has a group filter file (`.mvn/rrf/groupId-<id>.txt`) and `checksumPolicy=fail`.
  - Strict checksums are on.
  - `maven-wrapper.properties` has `distributionSha256Sum` and `wrapperSha256Sum`.
  - **FAIL** on any gap. Enforced by `RepositoryScopeGuardTest`, which runs in every build.
- **Changes today:**
  - `sangam/pom.xml:413-422`.
  - New `sangam/.mvn/maven.config` and `sangam/.mvn/rrf/`.
  - `sangam/.mvn/wrapper/maven-wrapper.properties`.
  - New guard test in `sangam-core/src/test/java/org/sangam/core/arch/`.

## CR-14 npm install scripts off

- **Rule:** every `npm ci` and `npm install` in workflows and Dockerfiles uses `--ignore-scripts`. Packages that need a native binary are rebuilt explicitly by name. **FAIL** if a grep finds an exception.
- **Changes today:**
  - `ui.yml:46`.
  - `release.yml:120` (step "UI build + SBOM").
  - The `npm ci` steps in `sdks.yml`.
  - `sangam/deploy/docker/ui.Dockerfile:28`.
  - `licence-check.yml:53` already complies.

## CR-15 Security findings gate at release

- **Rule:**
  - **Release mode:** **FAIL** on findings at or above the threshold set in C-18. The recommended threshold is: a critical vulnerability that has a fix, in a shipped image or jar; any verified secret; Semgrep errors in the authorisation, money or audit paths.
  - **PR mode:** may be report-only, but it writes **NOT-RUN (report-only)**, never PASS.
- **Changes today:**
  - `ci-build.yml` lines 178-203 (Trivy `exit-code: "0"` at 189; `continue-on-error` at 181, 192 and 203).
  - `security.yml` (the comment at line 174 says every job is continue-on-error; semgrep `|| true` at line 248; gitleaks `--exit-code 0` at line 286; `continue-on-error` at 185, 221, 267, 299 and 329).

## CR-16 Build-time tools never shipped, with no silent skip

- **Rule:** the existing `build_tools_not_shipped.py` runs over every shipped SBOM, every fat jar and every image SBOM. **BLOCKED** (fail) when any expected input is missing.
- **Changes today:**
  - `licence-check.yml` lines 63-72.
  - `release.yml` lines 132-136 and 275-277.
  - `ci-build.yml` lines 131-135 (remove the `hashFiles` skip at line 132; staging must produce the jar or fail).

## CR-17 Review gate for build and test components

- **Rule:**
  - A build/test SBOM (test scope plus the plugin closures) is generated and checked against the build-tool section of the policy (C-12).
  - **FAIL** on a forbidden component outside its allowed scope. Examples: ojdbc11 outside its CI-only profile (C-08), or an LGPL tool not on the list.
  - Unknown licences in build scope are reported, and fail only if C-12 says so.
- **Changes today:** `licence-check.yml` gets a new step. `licence-policy.json` gets a new build-tool section.

## CR-18 Lockfile present for every package manager in CI

- **Rule:**
  - npm uses `npm ci` with a committed lockfile (this includes `sdks/typescript-nestjs`).
  - Composer uses `composer install` with a committed `composer.lock`.
  - .NET uses `RestorePackagesWithLockFile` with `--locked-mode`.
  - Cargo uses `--locked`.
  - pip uses `--require-hashes`.
  - The pyyaml pin is the same everywhere.
  - Node, Temurin and Python patch versions are pinned on CI.
  - **FAIL** on a missing lockfile or a floating version.
- **Changes today:** `sdks.yml` (the npm, composer, dotnet, cargo and pip steps); `ci-build.yml` (pyyaml 6.0.3); `sdks.yml` (pyyaml 6.0.2); the `setup-node` `node-version: "22"` lines (for example `licence-check.yml:48`).

## CR-19 bpmn.io watermark visible

- **Rule:**
  - A rendered test asserts that the bpmn.io watermark element exists in the modeller and viewer screens, has a non-zero size, is inside the viewport, and that no element is stacked above it.
  - The gate maps the 11 bpmn.io purls to `LicenseRef-bpmn.io`.
  - **FAIL** otherwise.
  - The current grep of `dist/` for the string `bpmn.io` is kept only as a smoke check, and on its own it never produces PASS.
- **Changes today:** `ui.yml`, step "bpmn.io watermark kept (LIC-8)"; `sangam-ui/src/components/BpmnDiagram.watermark.test.tsx`.

## CR-20 Source offer present where a licence requires one

- **Rule:**
  - Every shipped component under LGPL or GPL, or under an OS-package rule that requires a source offer (C-03), has its offer file in the distribution.
  - For example: `SOURCE-OFFER.txt` for MariaDB (present today); libnl, if the script-worker ships (C-13); OS packages per C-03.
  - **FAIL** if an offer file is missing.
- **Changes today:** `release.yml`, steps "Stage artefacts" and the image builds; `sangam/deploy/docker/server/SOURCE-OFFER.txt` stays.

## CR-21 Architecture guards in the reactor

- **Rule:** these guards run in every build. **FAIL** on any new offender.
  - `PermissiveDefaultGuardTest` (RM-27).
  - The host-thinness rule and the pom-level framework ban (RM-29).
  - `RepositoryScopeGuardTest` (CR-13).
  - `dependencyConvergence` in report-only mode (RM-42), which writes NOT-RUN (report-only) until C-22 is answered.
- **Changes today:** `sangam-core/src/test/java/org/sangam/core/arch/*`; the root pom enforcer. The CI step that runs them is the existing build step in `ci-build.yml`.

## CR-22 Release aggregation and evidence

- **Rule:**
  - The evidence pack (`release.yml`, step "Assemble pack", line 348) includes the results ledger from CR-01, the per-distribution SBOMs, notices and licence reports.
  - `SHA256SUMS` covers them, and cosign signs it (line 367).
  - **FAIL** if any required ledger line is missing from the pack.
- **Changes today:** `release.yml`, steps "Assemble pack", "Sign checksums (keyless)" and "Checksums" (line 409).

---

## Workflow files and steps that change today (summary)

| File | Steps (VERIFIED line refs, 2026-11-12) | Controls |
|---|---|---|
| `.github/workflows/licence-check.yml` | "CycloneDX aggregate SBOM" 35-39; "Forbidden coordinates..." 40-45 (`|| true` at 42); "UI SBOM" 49-54 (`npx --yes` at 54); "Licence policy" 55-62; "Build-time tools never shipped" 63-72; upload with `if-no-files-found: ignore` at 80; `setup-node` "22" at 48 | CR-01, 02, 03, 04, 05, 08, 09, 12, 16, 17, 18 |
| `.github/workflows/release.yml` | "Stage artefacts" 92-106 (`|| true` at 105-106); "Maven SBOM" 107-111; "UI build + SBOM" 116-125 (`npm ci` without `--ignore-scripts` at 120, `npx --yes` at 123); "Licence gate (strict on release)" 126-131 (server jar only); "Build-time tools never shipped" 132-136; image builds 188-258; "Image SBOMs (CycloneDX)" 260-268 (syft `curl | sh` at 263); "Image SBOMs hold no build-time tool" 275-277; `cosign-installer@v3` 360; "Assemble pack" 348; "Air-gapped bundle" 400; "Checksums" 409; every `uses:` (SHA pins) | CR-01, 03, 04, 06, 07, 10, 11, 12, 14, 16, 20, 22 |
| `.github/workflows/ci-build.yml` | "Stage server jar and MariaDB driver" 121-130 (exit 0 on no jar at 127); "Build-time tools never shipped" 131-135 (`hashFiles` skip at 132); image scans 178-203 (report-only; grype `curl | sh` at 195) | CR-01, 04, 11, 15, 16 |
| `.github/workflows/ui.yml` | `npm ci` (line 46 per NON-MAVEN-FINDINGS V-07); UI SBOM `npx` 71; licence check 75; "bpmn.io watermark kept (LIC-8)" | CR-05, 12, 14, 19 |
| `.github/workflows/sdks.yml` | `npm ci`, `composer install` (ac08 job without a lock), `dotnet restore`, pyyaml 6.0.2 | CR-14, 18 |
| `.github/workflows/security.yml` | Every job continue-on-error (comment at 174); semgrep `|| true` 248; gitleaks `--exit-code 0` 286; `aws-actions/configure-aws-credentials@v4` | CR-10, 15 |
| `.github/workflows/governance.yml` | New steps: `no_silent_pass.py`, `pins_check.py` (the file was not read by this lane) | CR-01, 10 |
| `.github/workflows/quality.yml` | `continue-on-error` at 37 and 63. The purpose of those steps was not read (UNRESOLVED); they must be classified under CR-01 | CR-01 |
| `.github/workflows/db-matrix.yml` | `continue-on-error` for unsupported or candidate tiers at 150 (allowed only if it writes NOT-RUN or FAIL, never PASS); the Oracle lane activates the RM-23 profile; image digest pins | CR-01, 10 |
| `.github/dependabot.yml` | `open-pull-requests-limit: 0`; no docker entry | CR-10 |
| `sangam/pom.xml`, `sangam/.mvn/*` | `<repositories>` 413-422; `pluginManagement` (cyclonedx, maven-site); the enforcer | CR-12, 13, 21 |
| `sangam/deploy/licence/licence-policy.json`, `check_licences.py` | `unknown_license`, elections, OS packages, build tools, Rhino scope, SPDX ids | CR-05, 06, 08, 17 |
| `sangam/deploy/docker/ui.Dockerfile:28` | `npm ci` | CR-14 |

---
Generated by lane W3-X4 on 2026-11-12 (read-only; nothing was run, so no control is reported as PASS).
