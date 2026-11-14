# Sangam 0.1.0 dependency decision register (lane W3-X4)

Owner directive: decision 112 (epic E16), read with decisions 22, 33, 56, 70, 71, 79, 90, 94, 95, 96, 99, 102, 105, 106, 111 and 114 in `sangam-handoff/OWNER-DECISIONS-2026-11-12.md`.
Date of every claim below: **2026-11-12**. Review mode: read-only synthesis of W3-X1, W3-X2 and W3-X3. Nothing in the working tree was changed, nothing was downloaded, built or run, and no check is reported as PASS.

Sources:
- `MAVEN-INVENTORY.csv` (685 rows) and `MAVEN-FINDINGS.md` (W3-X1). "M row N" below means line N of `MAVEN-INVENTORY.csv` (header = line 1).
- `NON-MAVEN-INVENTORY.csv` (487 rows) and `NON-MAVEN-FINDINGS.md` (W3-X2). "N row N" means line N of `NON-MAVEN-INVENTORY.csv`.
- `BOUNDARY-ASSESSMENT.md` (W3-X3).
- `sangam/deploy/licence/licence-policy.json` in the worktree `workspace-scan-resume-06b524` (read 2026-11-12).

Status tags: **VERIFIED** (read from the cited file or inventory row on 2026-11-12), **ASSUMPTION** (not proven offline; stated so it can be challenged), **UNRESOLVED** (unknown; stays unknown until evidence or an owner answer exists).

No entry in this register is a legal opinion, a claim of legal compliance or a claim of government approval. "Licence" columns record what the inventories found and what licence-policy.json says, nothing more.

---

## 0. Summary counts

| Label | Maven (X1) | Non-Maven (X2) | Total | Status |
|---|---|---|---|---|
| ACCEPTED FOR DEFINED USE | 595 | 349 | **944** | VERIFIED (counted from both CSVs, 2026-11-12) |
| ACCEPTED WITH CONDITIONS | 52 | 79 | **131** | VERIFIED |
| REVIEW REQUIRED | 38 | 58 | **96** | VERIFIED |
| REJECTED FOR DEFINED USE | 0 | 1 | **1** | VERIFIED |
| Total rows | 685 | 487 | **1,172** | VERIFIED |

The 944 plain ACCEPTED FOR DEFINED USE rows have no register entry. They are accepted only for the use and scope written in their CSV row, with the generic obligations in that row (licence text kept, NOTICE carried where the jar has one). Of the 595 Maven ones, 253 are shipped (distribution or runtime) per MAVEN-FINDINGS.md section "Summary" (VERIFIED). Their notice obligations are covered by `CONFLICTS-AND-OBLIGATIONS.md`.

The 228 rows that are not plain ACCEPTED are grouped into 56 entries (DR-01 to DR-56). Three more entries (DR-57 to DR-59) cover components that **no inventory has yet**, and they carry no row count.

| Label | Entries | Rows |
|---|---|---|
| REJECTED FOR DEFINED USE | 1 (DR-22) | 1 |
| REVIEW REQUIRED | 27 (inventoried) + 3 (not yet inventoried) | 96 |
| ACCEPTED WITH CONDITIONS | 28 | 131 |

**Owners.** No accountable owner is named in the inputs for most groups. Where a group is covered by an owner decision, the owner column says "product owner (decision N)". W3-X2 names "product owner, with the integrator executing" as the accountable owner for its U-01..U-17 items (NON-MAVEN-FINDINGS.md section 3 heading), and that is used for those rows. Everywhere else the owner column says **owner (to name)**.

**Review dates.** The default is 2027-05-12. REVIEW REQUIRED entries take an earlier date:
- 2026-11-26 if the component is shipped, or if the entry blocks Wave 4 coding;
- 2026-12-12 for build-only or test-only entries.

These dates are proposals by this lane (ASSUMPTION) until the owner confirms them.

**Question columns.** Every entry keeps five question types apart, as decision 112 requires: Licence | Compatibility | Security | Procurement / hosting / data protection | Technical. "n/f" means no finding in that column for this entry. It does not mean "checked and clean".

---

## 1. REJECTED FOR DEFINED USE

### DR-22 Grafana OSS 12.1.1 inside the Sangam air-gap bundle
- **Label:** REJECTED FOR DEFINED USE
- **Defined use and scope:** image, redistributed by Sangam in the air-gap bundle (`observability` group, saved with `--with-observability`).
- **Rows:** N row 436.
- **Evidence:**
  - VERIFIED: `sangam/deploy/airgap/images.txt:27` lists `grafana/grafana-oss:12.1.1`, and line 9 labels it AGPL-3.0.
  - VERIFIED: `licence-policy.json:50-51` lists AGPL-3.0 in `forbidden_licenses`.
  - VERIFIED: no owner decision grants an exception (OWNER-DECISIONS-2026-11-12.md, read in full).
  - UNRESOLVED: the licence is taken from the repository comment and has not been checked against the image itself.
- **Accountable owner:** product owner, with the integrator executing (NON-MAVEN-FINDINGS U-03).
- **Conditions:**
  - Remove the image from the bundle. The customer pulls it.
  - Any exception needs a new owner decision (C-36), plus an AGPL source offer for the exact version.
- **Review date:** 2027-05-12.
- **Questions:** Licence: AGPL-3.0 is forbidden for shipping. | Compatibility: n/f. | Security: tag only, no digest (N row 436). | Procurement: redistribution of a third-party image by Sangam. | Technical: images.txt edit, and `save-images.sh` must not save it.

---

## 2. REVIEW REQUIRED

### DR-01 Jersey 4.0.3 jars that list ten licences with no AND/OR
- **Defined use and scope:** shipped, in the server fat jar (Operaton REST on Jersey).
- **Rows:** M rows 477, 478, 481, 482, 483 and 484 (jersey-container-servlet, jersey-client, jersey-bean-validation, jersey-entity-filtering, jersey-spring6, jersey-hk2).
- **Evidence:**
  - VERIFIED: MAVEN-FINDINGS section 8.1 "REVIEW REQUIRED". The POMs list ten licences, including "jQuery license" and "W3C license".
  - VERIFIED: the gate passes them anyway. `check_licences.py` treats any multi-entry list as OR (MAVEN-FINDINGS V-14 and G-5a).
- **Owner:** owner (to name).
- **Conditions:** Do a per-file review of each jar's `META-INF/NOTICE.md` and licence files, then record the SPDX expression (AND for bundled code, OR for a choice). Until then the entry stays REVIEW REQUIRED.
- **Review date:** 2026-11-26.
- **Questions:** Licence: whether the list means AND or OR is UNRESOLVED; the jQuery/MIT and W3C variants are unproven. | Compatibility: if it is AND, every term is still in `allowed` except the unproven W3C variant (ASSUMPTION). | Security: n/f. | Procurement: n/f. | Technical: Jersey sits beside Spring MVC (MAVEN-FINDINGS V-10). Removing it is an XL Operaton REST change (BOUNDARY 3.1).

### DR-02 paranamer 2.8.3
- **Defined use and scope:** shipped, in the server and control-plane fat jars.
- **Rows:** M row 87.
- **Evidence:** VERIFIED. The POM says only "BSD". The URL points at bsd-3-clause, and the jar carries no LICENSE file.
- **Owner:** owner (to name).
- **Conditions:** Prove the BSD variant from the upstream licence file (needs a download or lookup, which is in the test phase). Otherwise remove it, if no module needs it (it comes in via Jackson modules: ASSUMPTION).
- **Review date:** 2026-11-26.
- **Questions:** Licence: the variant is UNRESOLVED. | Compatibility: both BSD variants are in `allowed`. | Security: n/f. | Procurement: n/f. | Technical: n/f.

### DR-03 Oracle JDBC ojdbc11 23.8.0.25.04 in the default test scope
- **Defined use and scope:** test. It is a test dependency of sangam-server in the default build.
- **Rows:** M row 83.
- **Evidence:**
  - VERIFIED: `sangam/sangam-server/pom.xml:271`. MAVEN-FINDINGS V-8.
  - VERIFIED: the POM declares the Oracle Free Use Terms. The policy forbids the component for shipping (`licence-policy.json:63`).
  - VERIFIED: the enforcer allows test scope.
- **Owner:** owner (to name).
- **Conditions:** Move it to a CI-only profile that is never active by default, or record an owner decision for test use (C-08).
- **Review date:** 2026-11-26 (blocks Wave 4 pom work).
- **Questions:** Licence: shipping is forbidden (LIC-2); test use is not covered by the policy. | Compatibility: n/f. | Security: n/f. | Procurement: who accepted the Oracle terms for downloads on developer machines and CI is UNRESOLVED. | Technical: the enforcer comment says "CI-only driver profiles", which does not match the default test scope.

### DR-04 Error Prone and NullAway dataflow artifacts
- **Defined use and scope:** build only (annotation-processor closure).
- **Rows:** M rows 145 and 411 (dataflow-errorprone 3.41.0-eisop1, dataflow-nullaway 4.3.1).
- **Evidence:** VERIFIED. They declare GPL-2.0-only WITH Classpath-exception-2.0 and are not in `build_time_tools` (`licence-policy.json:105-114`).
- **Owner:** owner (to name).
- **Conditions:** Record a build-tool exception or remove the tools (C-12). They must never ship (the enforcer already covers compile and runtime).
- **Review date:** 2026-12-12.
- **Questions:** Licence: outside the owner's list of build-tool exceptions. | Compatibility: n/f (not distributed). | Security: n/f. | Procurement: n/f. | Technical: n/f.

### DR-05 Build artifacts whose BSD variant is ambiguous or whose licence is unmapped
- **Defined use and scope:** build only (plugin closures).
- **Rows:** M rows 2 (antlr 2.7.7), 4 (asm 3.3.1), 229 (jaxen 2.0.6), 249 (antlr4-runtime 4.7.2), 250 (stringtemplate 3.2.1), 497 (jdom2 2.0.6.1) and 686 (sysout-over-slf4j 1.0.2).
- **Evidence:** VERIFIED (MAVEN-FINDINGS section 8.1).
- **Owner:** owner (to name).
- **Conditions:** Prove each licence in the test phase, or cover all of them with a recorded build-tool exception (C-12).
- **Review date:** 2026-12-12.
- **Questions:** Licence: UNRESOLVED variant. | Compatibility: n/f. | Security: old versions (antlr 2.7.7 is from 2006, per the M row 2 release signal). | Procurement: n/f. | Technical: these come mostly from the pitest and maven-site closures (DR-07).

### DR-06 Build artifacts with no declared licence in the cache
- **Defined use and scope:** build only.
- **Rows:** M rows 94 (commons-beanutils 1.7.0), 119 (dom4j 1.1), 424 (plexus-i18n 1.0-beta-10) and 681 (oro 2.0.8).
- **Evidence:** VERIFIED. No licence appears in the POM or in any parent POM in `~/.m2`.
- **Owner:** owner (to name).
- **Conditions:** Same as DR-05.
- **Review date:** 2026-12-12.
- **Questions:** Licence: UNRESOLVED. | Compatibility: n/f. | Security: very old versions (dom4j 1.1, commons-beanutils 1.7.0) in a build tool closure; build hosts only. | Procurement: n/f. | Technical: removable if maven-site-plugin is never invoked (ASSUMPTION; see DR-07).

### DR-07 Plugin closures missing from the offline cache (maven-site-plugin 3.12.1, pitest-maven 1.30.2)
- **Defined use and scope:** build only.
- **Rows (16):** M rows 296 and 298-303 (doxia-module-* 1.11.1), 335 (maven-reporting-exec), 374 (maven-archiver 3.5.2), 378 (maven-core 3.1.0), 413 (plexus-archiver 4.2.7), 435 (plexus-utils 3.4.2) and 457-460 (jetty 9.4.46).
- **Evidence:** VERIFIED. They are not in the cache, and `dependency:resolve-plugins` fails offline (MAVEN-FINDINGS U-1, `w3x1-evidence/plugins-log2.txt`).
- **Owner:** owner (to name).
- **Conditions:**
  - Pin maven-site-plugin, or bind it to no phase. The super-POM default pulls it in even though Sangam does not use it (ASSUMPTION: no workflow calls `site`).
  - Resolve pitest's closure in the test phase under a download approval (C-19), then re-inventory.
- **Review date:** 2026-12-12.
- **Questions:** Licence: UNRESOLVED (no POM). | Compatibility: n/f. | Security: jetty 9.4.46 (2022) runs in the build JVM only. | Procurement: needs a download approval. | Technical: an air-gapped build cannot resolve them.

### DR-08 cyclonedx-maven-plugin 2.9.1
- **Defined use and scope:** build. It generates the Maven SBOM that the release licence gate reads.
- **Rows:** M row 449.
- **Evidence:**
  - VERIFIED: called by coordinates at `licence-check.yml:37` and `release.yml:109`. It is not pinned in any pom and is not cached (MAVEN-FINDINGS U-2).
  - UNRESOLVED: its licence, hash and closure.
- **Owner:** owner (to name).
- **Conditions:** Cache it under a download approval (C-19), pin it in root `pluginManagement`, and record its hash. Until then, a tampered or changed SBOM generator could falsify the release gate.
- **Review date:** 2026-11-26.
- **Questions:** Licence: UNRESOLVED. | Compatibility: how it maps licence names to SPDX ids is UNRESOLVED (MAVEN-FINDINGS V-14). | Security: the release gate trusts the tool's output. | Procurement: download approval. | Technical: an air-gapped SBOM is impossible today.

### DR-24 @fontsource Noto web fonts 5.1.0
- **Defined use and scope:** shipped, in the UI dist (direct production dependencies).
- **Rows:** N rows 98 and 99.
- **Evidence:**
  - VERIFIED: the OFL-1.1 conditional covers only `pkg:generic`/`pkg:github` Noto fonts for PDF embedding (`licence-policy.json:23-26`).
  - VERIFIED: an unlisted purl under a conditional licence is treated as forbidden (`check_licences.py:128-132`, per NON-MAVEN-FINDINGS V-05).
  - UNRESOLVED: the predicted gate failure has not been run.
- **Owner:** product owner, integrator executing.
- **Conditions:** Widen decisions 70/95 to the UI web fonts, or serve the approved TTFs from `deploy/fonts` instead (C-05).
- **Review date:** 2026-11-26.
- **Questions:** Licence: outside the policy scope. | Compatibility: the OFL is compatible with bundling the font unmodified, with its OFL.txt (policy condition text). | Security: n/f. | Procurement: n/f. | Technical: the strict UI gate is predicted to fail.

### DR-25 Sakal Bharati font in the UI
- **Defined use and scope:** shipped (`sangam-ui/public/fonts/gov/central/sakal-bharati/SakalBharati.ttf`).
- **Rows:** N row 449.
- **Evidence:**
  - VERIFIED: SHA-256 F27CF39C…0805.
  - VERIFIED: name ID 13 says OFL-1.1, but name ID 10 says "FOR NON-COMMERCIAL FREE USAGE" (`sangam-ui/FONTS.md` §2.1).
  - VERIFIED: decision 70 says any other OFL font needs a new owner decision.
- **Owner:** product owner, integrator executing.
- **Conditions:** Owner decision C-06. Until then, do not ship it.
- **Review date:** 2026-11-26.
- **Questions:** Licence: conflicting statements, UNRESOLVED. No legal conclusion is drawn here. | Compatibility: if the non-commercial clause applies, it would match the policy pattern `(?i)non-?commercial` (ASSUMPTION about how the gate would read font metadata; today the font is not in any SBOM). | Security: n/f. | Procurement: written confirmation from C-DAC is only possible through the owner (no vendor contact by lanes). | Technical: Noto Sans Devanagari is the replacement already approved for PDF.

### DR-26 IBM install-time telemetry in @carbon/grid, @carbon/layout and @ibm/telemetry-js
- **Defined use and scope:** the packages are shipped (UI dist). The telemetry runs at build time, in `npm ci`.
- **Rows:** N rows 57, 58 and 100.
- **Evidence:**
  - VERIFIED: the postinstall runs `ibmtelemetry --config=telemetry.yml`.
  - VERIFIED: `npm ci` runs without `--ignore-scripts` in `ui.yml:46`, `release.yml:120`, `sdks.yml` and `ui.Dockerfile:28` (NON-MAVEN-FINDINGS V-07). Only `licence-check.yml:53` uses `--ignore-scripts`.
- **Owner:** product owner, integrator executing.
- **Conditions:** Every `npm ci` uses `--ignore-scripts`, followed by an explicit `npm rebuild esbuild`, or sets `IBM_TELEMETRY_DISABLED=true` (C-15).
- **Review date:** 2026-11-26.
- **Questions:** Licence: Apache-2.0, accepted. | Compatibility: n/f. | Security: install scripts execute on the build host. | Procurement / data protection: install-time data leaves the build host; air-gap fit is UNRESOLVED. | Technical: esbuild needs its own install step if scripts are off.

### DR-31 NestJS adapter dependencies without a lockfile
- **Defined use and scope:** SDK package @sangam/nestjs. The peer dependencies are installed by the consumer; the dev dependencies are build/test only.
- **Rows:** N rows 374, 375, 376 and 377.
- **Evidence:** VERIFIED. `sdks/typescript-nestjs` has no lockfile, its peers are `^10 || ^11`, and the adapter has never run against Nest (README.md:64). Decision 96 defers verification to the test phase.
- **Owner:** product owner (decision 96).
- **Conditions:** Generate and commit a lockfile in the test phase (decision 96 forbids npm downloads now). Pin the dev dependencies exactly.
- **Review date:** 2026-12-12.
- **Questions:** Licence: NOASSERTION (expected MIT; unverified). | Compatibility: Nest 10 and 11 are untested. | Security: an open range is not reproducible. | Procurement: n/f. | Technical: there is no CI job.

### DR-32 FastAPI adapter (fastapi >=0.135, starlette >=1.0)
- **Defined use and scope:** SDK package sangam-sdk-fastapi. The consumer installs the dependencies; they are not bundled.
- **Rows:** N rows 382 and 383.
- **Evidence:** VERIFIED. Decision 96 does not mention FastAPI, and no CI job builds or tests the adapter (NON-MAVEN-FINDINGS V-11).
- **Owner:** product owner, integrator executing.
- **Conditions:** Owner scope decision (C-16).
- **Review date:** 2026-11-26.
- **Questions:** Licence: MIT / BSD-3-Clause from local metadata. | Compatibility: open ranges. | Security: n/f. | Procurement: publishing would fall under decision 105 (PyPI) if the adapter is in scope. | Technical: no CI.

### DR-33 Python build backends and pip-licenses
- **Defined use and scope:** build / CI.
- **Rows:** N rows 379 (setuptools >=68), 380 (wheel, unpinned) and 385 (pip-licenses 5.5.5).
- **Evidence:** VERIFIED. The versions are unpinned or carry no hash; the pip-licenses licence comes from a repository comment only.
- **Owner:** owner (to name).
- **Conditions:** Use a hash-locked build environment (a constraints file with `--require-hashes`) in the test phase.
- **Review date:** 2026-12-12.
- **Questions:** Licence: NOASSERTION. | Compatibility: n/f. | Security: unpinned build backend. | Procurement: n/f. | Technical: n/f.

### DR-35 .NET test packages
- **Defined use and scope:** test only (IsPackable=false).
- **Rows:** N rows 387, 388 and 389 (Microsoft.NET.Test.Sdk 17.11.1, xunit 2.9.2, xunit.runner.visualstudio 2.8.2).
- **Evidence:** VERIFIED. The project was never restored and has no lock file (NON-MAVEN-FINDINGS V-12).
- **Owner:** owner (to name).
- **Conditions:** Set `RestorePackagesWithLockFile`, commit the lock in the test phase, and record the licences.
- **Review date:** 2026-12-12.
- **Questions:** Licence: NOASSERTION. | Compatibility: n/f. | Security: no lock. | Procurement: n/f. | Technical: n/f.

### DR-36 Sangam.Sdk.Hosting (Microsoft.AspNetCore.App framework reference)
- **Defined use and scope:** SDK package, if published. The framework is provided by the consumer.
- **Rows:** N row 390.
- **Evidence:** VERIFIED. Decision 105 lists NuGet `Sangam.Sdk` only, and `sdks.yml` packs only Sangam.Sdk.
- **Owner:** product owner (decision 105).
- **Conditions:** C-17.
- **Review date:** 2026-11-26.
- **Questions:** Licence: NOASSERTION (expected MIT). | Compatibility: n/f. | Security: n/f. | Procurement: a publishing scope question. | Technical: n/f.

### DR-37 phpunit ^11.5
- **Defined use and scope:** dev only.
- **Rows:** N row 392.
- **Evidence:** VERIFIED. There is no `composer.lock`, and the ac08 job runs `composer install` without one.
- **Owner:** owner (to name).
- **Conditions:** Commit a CI lock in the test phase.
- **Review date:** 2026-12-12.
- **Questions:** Licence: NOASSERTION (expected BSD-3-Clause). | Compatibility: n/f. | Security: unlocked transitive dev tree. | Procurement: n/f. | Technical: n/f.

### DR-40 Build-stage images
- **Defined use and scope:** build. Nothing but `/out` is copied to runtime, except that the script-worker `test` target copies Maven and the JDK.
- **Rows:** N rows 408 and 409 (maven 3.9.11 on temurin 25 and 21) and 415 (node 22.18.0-alpine3.22).
- **Evidence:** VERIFIED. The images are tag-only and not pulled (NON-MAVEN-FINDINGS V-16).
- **Owner:** owner (to name).
- **Conditions:** Pin by digest at the test-phase pull. The node stage must run `npm ci --ignore-scripts` (DR-26).
- **Review date:** 2026-12-12.
- **Questions:** Licence: NOASSERTION (not pulled). | Compatibility: n/f. | Security: mutable tags. | Procurement: image pulls wait for the test phase (decision 56 excludes them). | Technical: n/f.

### DR-41 Runtime base images of shipped Sangam images
- **Defined use and scope:** image (shipped base layers).
- **Rows:** N rows 410 (eclipse-temurin 25.0.1_8-jre-noble), 411 (temurin 21.0.8_9-jre-noble), 412 (distroless java25-debian13:nonroot), 414 (distroless base-debian12:nonroot) and 416 (nginx-unprivileged 1.28.0-alpine3.21).
- **Evidence:**
  - VERIFIED: tag-only, not pulled.
  - VERIFIED: the image SBOMs are checked only by `build_tools_not_shipped.py` (`release.yml:275-277`), not by `check_licences.py`.
  - VERIFIED: the policy has no rule for OS packages (NON-MAVEN-FINDINGS V-26).
- **Owner:** product owner, integrator executing (U-04).
- **Conditions:** An owner rule for OS packages (C-03), a licence gate over the image SBOMs, digest pins, and the copyright files kept.
- **Review date:** 2026-11-26.
- **Questions:** Licence: GPL/LGPL OS packages (busybox, glibc, bash) inside shipped layers. The policy is silent on them (UNRESOLVED). | Compatibility: n/f. | Security: the `nonroot` tag is mutable. | Procurement: n/f. | Technical: no gate covers them.

### DR-42 GraalVM native-image-community 25.0.1
- **Defined use and scope:** build stage. Its SubstrateVM runtime is compiled into the shipped sdk-worker binary.
- **Rows:** N row 413.
- **Evidence:** VERIFIED that it is tag-only and not pulled. UNRESOLVED whether it is the Community edition with no GFTC part (U-15).
- **Owner:** product owner, integrator executing.
- **Conditions:** Verify at the pull. Record the edition and its licence in the sdk-worker image SBOM. Any GFTC component is forbidden (`licence-policy.json:53,57`).
- **Review date:** 2026-11-26.
- **Questions:** Licence: expected GPL-2.0 with the Classpath Exception; unverified. | Compatibility: n/f. | Security: n/f. | Procurement: image pull. | Technical: n/f.

### DR-43 Script-worker image and libnl-route-3-200
- **Defined use and scope:** image (sangam-script-worker). Distribution is not approved; decision 79 covers the test phase only.
- **Rows:** N rows 423 (libnl-route-3-200) and 445 (sangam-script-worker).
- **Evidence:**
  - VERIFIED: the image is not built by `release.yml` and is not in `images.txt`, yet Helm references `sqamentor/sangam-script-worker`.
  - VERIFIED: the LGPL-2.1 conditional in the policy covers MariaDB only (`licence-policy.json:13-16`).
  - VERIFIED: copying the bare node binary drops Node's LICENSE (NON-MAVEN-FINDINGS V-20).
- **Owner:** product owner (decisions 77-79).
- **Conditions:** C-13. If approved: release build, image SBOM, Node LICENSE copied, nsjail/kafel commits recorded, a libnl source offer, and pinned apt versions.
- **Review date:** 2026-11-26.
- **Questions:** Licence: LGPL-2.1 in a shipped image is outside the policy. | Compatibility: dynamic linking keeps libnl replaceable (ASSUMPTION). | Security: the sandbox tool's commit is not recorded (U-06). | Procurement: n/f. | Technical: there is no release path.

### DR-45 Database images under proprietary terms (test and dev)
- **Defined use and scope:** test and dev compose; air-gap `devdb` group with a customer-only flag.
- **Rows:** N rows 425 (gvenzl/oracle-free 23-slim-faststart), 426 (mssql/server 2022-latest), 427 (mssql/server 2025-CU10) and 430 (db2_community 12.1.2.0).
- **Evidence:**
  - VERIFIED: `AcceptanceDatabase.java:163` calls `.acceptLicense()`.
  - VERIFIED: compose sets `ACCEPT_EULA: "Y"` (`docker-compose.yml:177`) (NON-MAVEN-FINDINGS V-21).
  - VERIFIED: there is no owner record of accepting these terms.
- **Owner:** product owner (decision 112: no contract acceptance without authorisation).
- **Conditions:** C-07. Acceptance happens at runtime by the operator (an explicit environment flag), never in code. Never distribute these images (LIC-2, LIC-3).
- **Review date:** 2026-11-26.
- **Questions:** Licence: proprietary terms, shipping forbidden. | Compatibility: n/f. | Security: the `2022-latest` tag is mutable. | Procurement: who accepts the EULA is UNRESOLVED. | Technical: test lanes must stay opt-in.

### DR-46 Open-source database images (Sangam redistributes or defaults to them)
- **Defined use and scope:**
  - postgres:17: dev, test, the Helm backup CronJob, and the air-gap `runtime` group (redistributed).
  - mysql:8.4: dev, test, and the Helm backup default (customer cluster).
  - mariadb:11.4: test.
- **Rows:** N rows 424, 428 and 429.
- **Evidence:** VERIFIED. The tags float, and no image has been pulled.
- **Owner:** owner (to name). For the Helm defaults: product owner (C-14).
- **Conditions:** Digest pins. postgres in the bundle follows the OS-package rule (C-03). Helm backup defaults per C-14.
- **Review date:** 2026-11-26.
- **Questions:** Licence: PostgreSQL licence plus Debian OS packages; MySQL GPL-2.0 per comment; unverified. | Compatibility: n/f. | Security: floating minor tags. | Procurement: the default config makes the customer cluster pull these images. | Technical: n/f.

### DR-47 Sidecar and helper images
- **Defined use and scope:**
  - keycloak 26.3.3 and otel-collector-contrib 0.131.1: the air-gap `runtime` group (redistributed).
  - jaeger 2.9.0 and prometheus v3.5.0: the `observability` group (redistributed when opted in).
  - curlimages/curl 8.15.0: dev/test compose.
- **Rows:** N rows 432, 433, 434, 435 and 438.
- **Evidence:** VERIFIED from `images.txt:22-26`. The licences come from repository comments only.
- **Owner:** product owner, integrator executing (U-14).
- **Conditions:** Pin by digest. Verify the licences at the pull. Keycloak's UBI redistribution terms (U-14). The prometheus busybox falls under the OS-package rule.
- **Review date:** 2026-11-26.
- **Questions:** Licence: NOASSERTION. Keycloak's UBI EULA layer is UNRESOLVED. | Compatibility: n/f. | Security: tag-only. | Procurement: redistribution. | Technical: n/f.

### DR-52 GitHub Actions on jobs that hold write tokens or credentials
- **Defined use and scope:** CI and release.
- **Rows:** N rows 459, 464, 465, 466, 470, 473, 475 and 476 (attest-build-provenance@v2, setup-qemu@v3, login@v3, build-push@v6, setup-graalvm@v1, configure-aws-credentials@v4, cosign-installer@v3, action-gh-release@v2).
- **Evidence:** VERIFIED. 0 of 123 `uses:` references are SHA-pinned (NON-MAVEN-FINDINGS V-23).
- **Owner:** owner (to name).
- **Conditions:** Pin to the full 40-hex commit with a version comment before any release (C-19 for the online lookup).
- **Review date:** 2026-11-26.
- **Questions:** Licence: not inspected (A-04). | Compatibility: n/f. | Security: a mutable tag can be moved by its owner. | Procurement: n/f. | Technical: Dependabot will not raise update PRs (limit 0).

### DR-54 CI tools downloaded without verification, and floating CI runtimes
- **Defined use and scope:** CI and release.
- **Rows:** N rows 478 (trivy), 479 (grype), 480 (syft), 481 (kubeconform), 485 (cosign, unpinned) and 486 (Node "22").
- **Evidence:**
  - VERIFIED: grype is installed with `curl | sh` at `ci-build.yml:195`.
  - VERIFIED: syft is installed with `curl | sh` at `release.yml:263`.
  - VERIFIED: cosign comes from `release.yml:360` with no version.
- **Owner:** owner (to name).
- **Conditions:** Verify each download by checksum and signature, and pin versions (CR-11).
- **Review date:** 2026-11-26.
- **Questions:** Licence: not inspected. | Compatibility: n/f. | Security: the release job installs unverified tools that produce attested SBOMs. | Procurement: n/f. | Technical: n/f.

### DR-56 @cyclonedx/cyclonedx-npm 2.1.0
- **Defined use and scope:** CI and release. It generates the UI SBOM that the release gate reads.
- **Rows:** N row 378.
- **Evidence:** VERIFIED. It runs through `npx --yes` with no integrity check (`licence-check.yml:54`, `release.yml:123`, `ui.yml:71`).
- **Owner:** owner (to name).
- **Conditions:** Install it from a committed lockfile (a tools package) with integrity, never with `npx --yes`.
- **Review date:** 2026-11-26.
- **Questions:** Licence: NOASSERTION. | Compatibility: n/f. | Security: the gate trusts its output. | Procurement: n/f. | Technical: n/f.

### DR-57 veraPDF validator container (decision 114). Not yet inventoried.
- **Defined use and scope:** a new image (the PDF validator service), separate from the server. The server will send it PDF bytes.
- **Rows:** none (not in either CSV).
- **Evidence:**
  - VERIFIED: decision 114 (OWNER-DECISIONS line 123-125).
  - VERIFIED: `sangam/sangam-server/pom.xml` declares no veraPDF (grep on 2026-11-12).
  - VERIFIED: `sangam/sangam-integration/pom.xml:122-128` now declares `org.verapdf:validation-model-jakarta` as `<optional>true</optional>`. This post-dates W3-X1 V-7 ("no reactor module declares it").
  - VERIFIED: `licence-policy.json:72` bans `pkg:maven/org.mozilla/rhino` by purl prefix, with no scope.
  - UNRESOLVED: whether the root enforcer `bannedDependencies` (SL-106) fails sangam-integration now that Rhino is in its optional compile closure. Nothing was built.
  - UNRESOLVED: the closure and licences of veraPDF 1.30.4 (Rhino 1.7.15.1, Saxon-HE 12.8, jai-imageio, stax-utils). They are not in the inventory.
- **Owner:** product owner (decisions 94, 102, 114).
- **Conditions:** C-04 (policy scope for Rhino in the validator image). Its own SBOM and notices. Rhino never in the server fat jar (LIC-10 unchanged). Re-run the X1 inventory over the validator module.
- **Review date:** 2026-11-26.
- **Questions:** Licence: the elections for veraPDF (MPL-2.0, decision 94) and decision 102 are recorded; Rhino and Saxon-HE are expected MPL-2.0 (ASSUMPTION, unverified). | Compatibility: the purl ban on Rhino would fail any SBOM gate that reads the validator image (UNRESOLVED). | Security: Rhino evaluates profile rules inside the validator container only. | Procurement: n/f. | Technical: where the module lives (sangam-integration or a new module) is open.

### DR-58 Modules outside the reactor. Not yet inventoried.
- **Defined use and scope:**
  - `sdks/kotlin`: an SDK, via kotlin-maven-plugin 2.0.21.
  - `deploy/native/sdk-worker`: native-maven-plugin 1.1.18, maven-assembly-plugin 3.7.1, Error Prone and GraalVM build tools. The sdk-worker image ships.
- **Evidence:** VERIFIED (MAVEN-FINDINGS U-6).
- **Owner:** owner (to name).
- **Conditions:** Inventory them in the test phase (online resolution) and add them to the per-distribution SBOMs.
- **Review date:** 2026-12-12.
- **Questions:** Licence: UNRESOLVED. | Compatibility: n/f. | Security: n/f. | Procurement: downloads. | Technical: outside every gate today.

### DR-59 Quarkus 3.40.1.x POM-only entries (decision 56 batch). Not yet inventoried.
- **Defined use and scope:** none in 0.1.0. Per decisions 54R and 80, Quarkus is not a core dependency.
- **Evidence:** VERIFIED (MAVEN-FINDINGS U-7).
- **Owner:** product owner (decisions 54R, 80).
- **Conditions:** Inventory them only if M-143 enters 0.1.0. They must never reach a shipped scope (R-11 pom-level ban).
- **Review date:** 2026-12-12.
- **Questions:** Licence: UNRESOLVED. | Compatibility: n/f. | Security: n/f. | Procurement: n/f. | Technical: n/f.

---

## 3. ACCEPTED WITH CONDITIONS

### DR-09 Jakarta, HK2, Jersey-core, Expressly and Angus dual or multi-licence jars (election needed)
- **Defined use and scope:** shipped (fat jars), except angus-mail 2.0.4, which is runtime-only.
- **Rows (21):**
  - M rows 215, 216, 218, 219 and 223 (the jakarta APIs);
  - 466 (expressly);
  - 467-476 (hk2 family);
  - 479, 480 and 485 (jersey-common, jersey-server, jersey-media-json-jackson);
  - 455 and 456 (angus-mail 2.0.4 and 2.0.5).
- **Evidence:** VERIFIED. The POMs list EPL-2.0 / GPL-2.0+CPE, with EDL or Apache on some. The OR reading is an ASSUMPTION (MAVEN-FINDINGS A-1).
- **Owner:** owner (to name).
- **Conditions:**
  - Elect EPL-2.0, or BSD-3-Clause (EDL) for mail and angus, or Apache-2.0 for jersey-media-json-jackson.
  - Record each election as an SPDX expression in the SBOM and in NOTICE (C-02).
  - Carry the full NOTICE.md texts.
  - State where the EPL source is available.
- **Review date:** 2027-05-12.
- **Questions:** Licence: elections not recorded. | Compatibility: EPL-2.0 is weak copyleft and is allowed. | Security: n/f. | Procurement: n/f. | Technical: angus-mail runs at 2.0.4 and 2.0.5 in different modules (MAVEN-FINDINGS V-12).

### DR-10 jakarta.servlet-api 6.0.0 and 6.1.0 (provided)
- **Defined use and scope:** build (provided).
- **Rows:** M rows 220 and 221.
- **Evidence:** VERIFIED.
- **Owner:** owner (to name).
- **Conditions:** Elect EPL-2.0. Align on 6.1.0 (MAVEN-FINDINGS V-11).
- **Review date:** 2027-05-12.
- **Questions:** Licence: election. | Compatibility: n/f. | Security: n/f. | Procurement: n/f. | Technical: 6.0.0 in sangam-integration against 6.1.0 elsewhere.

### DR-11 logback-classic and logback-core 1.5.38
- **Defined use and scope:** shipped (all four fat jars).
- **Rows:** M rows 6 and 7.
- **Evidence:** VERIFIED. The POM lists EPL-2.0 and LGPL-2.1-only, and the jar carries no LICENSE.
- **Owner:** owner (to name).
- **Conditions:** Elect EPL-2.0 (C-02). Supply the licence text. State the source location.
- **Review date:** 2027-05-12.
- **Questions:** Licence: no election recorded anywhere. | Compatibility: LGPL is not elected. | Security: n/f. | Procurement: n/f. | Technical: n/f.

### DR-12 H2 2.3.232
- **Defined use and scope:** shipped, at runtime scope, in the server, control-plane and rollup fat jars.
- **Rows:** M row 73.
- **Evidence:** VERIFIED (MPL-2.0 OR EPL-1.0).
- **Owner:** owner (to name).
- **Conditions:** Elect MPL-2.0 or EPL-1.0 (C-02). W3-X1 proposed EPL-1.0 in the CSV and "either" in section 4.
- **Review date:** 2027-05-12.
- **Questions:** Licence: election. | Compatibility: both are weak copyleft and both are allowed. | Security: an embedded database inside production jars (ASSUMPTION: dev and H2 profiles only). | Procurement: n/f. | Technical: pinned at 2.3.232, while Boot manages 2.4.240.

### DR-13 javax.xml.bind:jaxb-api 2.3.1
- **Defined use and scope:** shipped (server, control-plane and rollup fat jars), via Liquibase 4.33.0.
- **Rows:** M row 228.
- **Evidence:** VERIFIED.
- **Owner:** owner (to name).
- **Conditions:** Elect CDDL-1.1 (C-02). State the source location.
- **Review date:** 2027-05-12.
- **Questions:** Licence: election. | Compatibility: CDDL is weak copyleft and allowed. | Security: a 2018 artifact. | Procurement: n/f. | Technical: a duplicate XML binding (MAVEN-FINDINGS V-10).

### DR-14 Other shipped dual licences: javassist 3.33.0-GA, amqp-client 5.37.1, HdrHistogram 2.2.2
- **Defined use and scope:** shipped, except amqp-client, which is runtime-only and optional.
- **Rows:** M rows 495, 84 and 488.
- **Evidence:** VERIFIED.
- **Owner:** owner (to name).
- **Conditions:** Elect Apache-2.0 for javassist and amqp-client, and BSD-2-Clause for HdrHistogram (C-02).
- **Review date:** 2027-05-12.
- **Questions:** Licence: election. | Compatibility: n/f. | Security: n/f. | Procurement: n/f. | Technical: n/f.

### DR-15 Eclipse Paho mqttv3 1.2.5
- **Defined use and scope:** shipped (server fat jar; optional broker adapter).
- **Rows:** M row 461.
- **Evidence:** VERIFIED. EPL-2.0. The last upstream release signal is 2020-07-14 (MAVEN-FINDINGS section 4).
- **Owner:** owner (to name).
- **Conditions:** Ship it unmodified, keep the licence text, and state the source location.
- **Review date:** 2027-05-12.
- **Questions:** Licence: EPL-2.0. | Compatibility: n/f. | Security: maintenance risk (no release since 2020). | Procurement: n/f. | Technical: optional per broker (BOUNDARY 3.1).

### DR-16 SG-27 FEEL exception set
- **Defined use and scope:** shipped (server and control-plane fat jars).
- **Rows (6):** M rows 407, 576, 76, 77, 78 and 26.
- **Evidence:** VERIFIED. `licence-policy.json:96-103` holds exact pins with watch=true; the evidence file is `deploy/licence/SG-27-SD-301-exception.md`.
- **Owner:** product owner (SG-27 exception).
- **Conditions:** Exact versions only; keep the exception record in the SBOM (`--annotate`).
- **Review date:** 2027-05-12.
- **Questions:** Licence: Apache-2.0 / MIT. | Compatibility: the consumer's `org.camunda*` ban is excepted for feel-engine only. | Security: n/f. | Procurement: consumer policy. | Technical: Jackson is pinned by this closure (BOUNDARY 3.1).

### DR-17 cryptacular 1.3.0
- **Defined use and scope:** shipped (server fat jar, via the OpenSAML closure).
- **Rows:** M row 448.
- **Evidence:**
  - VERIFIED: decision 90 elects Apache-2.0.
  - VERIFIED: decision 99 keeps server SAML off in 0.1.0.
  - VERIFIED: the policy text "reached only through the optional M-076 SAML relying party" (`licence-policy.json:30`) does not match the jar contents (MAVEN-FINDINGS V-6).
- **Owner:** product owner (decision 90).
- **Conditions:** Correct the wording to "feature-optional, present in the default server jar" (C-20). Alternatively, remove the SAML SP closure from the 0.1.0 server, since decision 99 leaves it unused (technical option, C-20).
- **Review date:** 2027-05-12.
- **Questions:** Licence: the election is recorded. | Compatibility: n/f. | Security: an unused SAML closure (OpenSAML, xmlsec, velocity, Bouncy Castle 1.84) ships in the default jar. | Procurement: n/f. | Technical: two Bouncy Castle providers (MAVEN-FINDINGS V-9).

### DR-18 MariaDB Connector/J 3.5.11
- **Defined use and scope:** shipped as a separate lib-ext jar. It is excluded from the fat jars by configuration.
- **Rows:** M row 515.
- **Evidence:**
  - VERIFIED: LIC-5 (`licence-policy.json:13-16`).
  - VERIFIED: the spring-boot-maven-plugin excludes (MAVEN-FINDINGS V-5).
  - VERIFIED: `SOURCE-OFFER.txt` exists.
  - VERIFIED: the published sangam-server POM still carries the driver at runtime scope.
  - UNRESOLVED: whether the real fat jars contain it (U-4; no jar was built).
- **Owner:** owner (to name).
- **Conditions:** Never inside a fat jar (`--jar` on every jar). Keep the source offer. Keep the driver off the published POM's runtime closure (RM-38).
- **Review date:** 2027-05-12.
- **Questions:** Licence: LGPL-2.1-or-later. | Compatibility: a replaceable separate jar. | Security: n/f. | Procurement: n/f. | Technical: consumers of the plain jar inherit it.

### DR-19 LGPL build-time tools listed by the owner
- **Defined use and scope:** build only.
- **Rows (7):** M rows 43, 41, 74, 589, 586, 587 and 588.
- **Evidence:** VERIFIED (`licence-policy.json:105-114`; decisions 22 and 33).
- **Owner:** product owner (2026-11-12 build_time_tools decision).
- **Conditions:** Exact versions. Never in a shipped scope, fat jar or image (checked by `build_tools_not_shipped.py`; note CR-01 and CR-04: that check is skipped today when no jar exists).
- **Review date:** 2027-05-12.
- **Questions:** Licence: LGPL, build only. | Compatibility: n/f. | Security: n/f. | Procurement: n/f. | Technical: n/f.

### DR-20 Build and test jars offering LGPL OR Apache-2.0
- **Defined use and scope:** build and test.
- **Rows:** M rows 36 (javaparser-core 3.28.2), 234 (jna-platform 5.17.0), 236 (jna 5.17.0) and 235 (jna 5.13.0, test).
- **Evidence:** VERIFIED.
- **Owner:** owner (to name).
- **Conditions:** Elect Apache-2.0 (C-02).
- **Review date:** 2027-05-12.
- **Questions:** Licence: election. | Compatibility: n/f. | Security: n/f. | Procurement: n/f. | Technical: n/f.

### DR-21 Maven wrapper 3.1.1 and Apache Maven 3.9.9
- **Defined use and scope:** build.
- **Rows:** M rows 372 and 373.
- **Evidence:** VERIFIED. `sangam/.mvn/wrapper/maven-wrapper.properties` has no `distributionSha256Sum` or `wrapperSha256Sum`, and a binary `maven-wrapper.jar` is committed (MAVEN-FINDINGS U-5).
- **Owner:** owner (to name).
- **Conditions:** Add both checksums from the official published values (C-19 for the lookup). Consider the wrapper's script-only mode so that no binary jar sits in git (technical choice).
- **Review date:** 2027-05-12.
- **Questions:** Licence: Apache-2.0. | Compatibility: n/f. | Security: the download is not verified. | Procurement: n/f. | Technical: n/f.

### DR-23 Grafana OSS 12.1.1 in dev compose only
- **Defined use and scope:** dev and local only; not distributed.
- **Rows:** N row 437.
- **Evidence:** VERIFIED.
- **Owner:** owner (to name).
- **Conditions:** Unmodified and never saved into the bundle (DR-22). Pin by digest.
- **Review date:** 2027-05-12.
- **Questions:** Licence: AGPL-3.0 (not distributed). | Compatibility: n/f. | Security: tag-only. | Procurement: n/f. | Technical: n/f.

### DR-27 bpmn.io packages (LIC-8)
- **Defined use and scope:** shipped (UI dist).
- **Rows (11):** N rows 44, 45, 51, 174, 202 and 208-213.
- **Evidence:**
  - VERIFIED: `licence-policy.json:17-21`.
  - VERIFIED: watermark clause at `node_modules/bpmn-js/LICENSE` lines 13-16.
  - VERIFIED: the CI control only greps `dist/` (NON-MAVEN-FINDINGS V-04).
  - VERIFIED: seven packages declare "SEE LICENSE IN LICENSE".
  - UNRESOLVED: how the strict gate reads them (U-11).
- **Owner:** product owner (LIC-8).
- **Conditions:**
  - Unmodified, with the watermark visible and not overlapped.
  - Map the bpmn.io purls explicitly to `LicenseRef-bpmn.io` (C-24).
  - Prove the watermark with a rendered-DOM visibility test, not a string grep.
- **Review date:** 2027-05-12.
- **Questions:** Licence: bpmn.io licence (© Camunda Services GmbH). | Compatibility: the consumer's `org.camunda*` ban is scoped to the Maven namespace; whether it reaches these npm packages is UNRESOLVED (C-25). | Security: n/f. | Procurement: consumer policy. | Technical: no other modeller round-trips the `operaton:` extensions (BOUNDARY 3.1).

### DR-28 Shipped MIT npm packages with no LICENSE file
- **Defined use and scope:** shipped (UI dist).
- **Rows:** N rows 55, 187, 188, 224 and 225.
- **Evidence:** VERIFIED.
- **Owner:** owner (to name).
- **Conditions:** The notices file reproduces the MIT text with each package's copyright line.
- **Review date:** 2027-05-12.
- **Questions:** Licence: MIT. | Compatibility: n/f. | Security: n/f. | Procurement: n/f. | Technical: a generated notices file is needed (RM-16).

### DR-29 dompurify 3.4.17
- **Defined use and scope:** shipped (UI dist).
- **Rows:** N row 226.
- **Evidence:** VERIFIED: `(MPL-2.0 OR Apache-2.0)`.
- **Owner:** owner (to name).
- **Conditions:** Record the Apache-2.0 election (C-02).
- **Review date:** 2027-05-12.
- **Questions:** Licence: election. | Compatibility: n/f. | Security: a sanitiser on the security path (keep it current). | Procurement: n/f. | Technical: n/f.

### DR-30 Dev-only npm packages with copyleft or attribution licences
- **Defined use and scope:** build and test only.
- **Rows:** N rows 171 (axe-core 4.14.0, MPL-2.0) and 178 (caniuse-lite, CC-BY-4.0).
- **Evidence:** VERIFIED dev-only. It is an ASSUMPTION that they are absent from `dist/` (NON-MAVEN-FINDINGS A-01).
- **Owner:** owner (to name).
- **Conditions:** Confirm in the test phase, by inspecting `dist/`, that neither reaches it.
- **Review date:** 2027-05-12.
- **Questions:** Licence: MPL-2.0 and CC-BY-4.0. | Compatibility: n/f. | Security: n/f. | Procurement: n/f. | Technical: n/f.

### DR-34 Python CI and test tools
- **Defined use and scope:** CI and test.
- **Rows:** N rows 381 (pytest >=8), 384 (semgrep 1.185.0, owner build-tool exception) and 386 (pyyaml 6.0.3 / 6.0.2).
- **Evidence:** VERIFIED.
- **Owner:** owner (to name). For semgrep: product owner (build_time_tools).
- **Conditions:** Pin exact versions with hashes. Use one pyyaml version.
- **Review date:** 2027-05-12.
- **Questions:** Licence: MIT / LGPL-2.1 (build only). | Compatibility: n/f. | Security: no hashes. | Procurement: n/f. | Technical: n/f.

### DR-38 Toolchains approved by decision 106
- **Defined use and scope:** build and test (CI).
- **Rows (6):** N rows 391 (.NET SDK 8.0.404), 393 (php and ext-*, provided by the consumer), 394 (PHP 8.3 / Composer 2.8.3), 396 (Go 1.22.10), 406 (Gradle 8.10.2) and 407 (openapi-generator-cli v7.10.0).
- **Evidence:** VERIFIED (decision 106).
- **Owner:** product owner (decision 106).
- **Conditions:** Log each version in the downloads register, and pin images by digest.
- **Review date:** 2027-05-12.
- **Questions:** Licence: NOASSERTION (expected permissive). | Compatibility: n/f. | Security: digest pins. | Procurement: n/f. | Technical: n/f.

### DR-39 Rust crates of sangam-worker
- **Defined use and scope:** build of the crate. Consumers resolve their own versions; nothing is vendored.
- **Rows (9):** N rows 397-405.
- **Evidence:** VERIFIED. `Cargo.lock` is gitignored (`.gitignore:2`). ryu is `Apache-2.0 OR BSL-1.0`, and unicode-ident carries `AND Unicode-3.0`.
- **Owner:** owner (to name).
- **Conditions:** Elect MIT or Apache-2.0, and Apache-2.0 for ryu (C-02). Commit `Cargo.lock` or check with `--locked` in CI.
- **Review date:** 2027-05-12.
- **Questions:** Licence: elections. | Compatibility: Unicode-3.0 is allowed. | Security: not reproducible. | Procurement: n/f. | Technical: n/f.

### DR-44 Script-worker build inputs (test phase only)
- **Defined use and scope:** build inputs. The node binary, nsjail, python3 and libprotobuf end up in the runtime image.
- **Rows (6):** N rows 417 (node 22.18.0-bookworm-slim), 418 (nsjail 3.4), 419 (kafel), 420 (apt build packages), 421 (python3) and 422 (libprotobuf32t64).
- **Evidence:** VERIFIED (decisions 77, 79). No approved nsjail commit is recorded (U-06).
- **Owner:** product owner (decisions 77, 79).
- **Conditions:** Test phase only. Record the nsjail and kafel commits (C-26). Copy Node's LICENSE. Pin apt versions. Distribution is governed by DR-43.
- **Review date:** 2027-05-12.
- **Questions:** Licence: NOASSERTION (expected Apache-2.0 / MIT / PSF). | Compatibility: n/f. | Security: the sandbox commit is unrecorded. | Procurement: n/f. | Technical: the build is not reproducible (unpinned apt).

### DR-48 ClamAV 1.4.3 sidecar
- **Defined use and scope:** an optional separate service; never linked; pulled in the test phase only.
- **Rows:** N row 431.
- **Evidence:** VERIFIED (decision 71). The Helm `digest: ""`.
- **Owner:** product owner (decision 71).
- **Conditions:** Pin by digest. Sangam does not redistribute it. The signature database terms are UNRESOLVED (C-27).
- **Review date:** 2027-05-12.
- **Questions:** Licence: GPL-2.0 for the engine. | Compatibility: a separate program. | Security: n/f. | Procurement: the database terms. | Technical: n/f.

### DR-49 Customer-supplied Oracle client and RMAN image
- **Defined use and scope:** customer-supplied, never shipped.
- **Rows:** N row 439.
- **Evidence:** VERIFIED (a Helm `required` guard).
- **Owner:** product owner (LIC-2).
- **Conditions:** Keep the `required` guard.
- **Review date:** 2027-05-12.
- **Questions:** Licence: Oracle (customer). | Compatibility: n/f. | Security: n/f. | Procurement: customer. | Technical: n/f.

### DR-50 Sangam product images
- **Defined use and scope:** image (distribution).
- **Rows (6):** N rows 440, 441, 442, 443, 444 and 446 (server, ui, control-plane/gateway/rollup, sdk-worker, simulators, server-test-mode).
- **Evidence:** VERIFIED. The Helm digests are empty, and the image SBOMs are not licence-gated.
- **Owner:** owner (to name).
- **Conditions:** Per-image SBOM, licence gate and notices (RM-14, RM-19, RM-16). Pin by digest for production and air-gap installs.
- **Review date:** 2027-05-12.
- **Questions:** Licence: own code is Apache-2.0, plus the bundled third-party code. | Compatibility: n/f. | Security: digest pins. | Procurement: n/f. | Technical: n/f.

### DR-51 Noto fonts for PDF embedding
- **Defined use and scope:** shipped (embedded in generated PDF/A).
- **Rows:** N rows 447 and 448.
- **Evidence:** VERIFIED (decisions 70, 95; the hashes match `deploy/fonts/README.md`).
- **Owner:** product owner (decisions 70, 95).
- **Conditions:** Unmodified, with OFL.txt, never sold alone.
- **Review date:** 2027-05-12.
- **Questions:** Licence: OFL-1.1 (conditional). | Compatibility: n/f. | Security: n/f. | Procurement: n/f. | Technical: n/f.

### DR-53 GitHub Actions without write credentials
- **Defined use and scope:** CI.
- **Rows (20):** N rows 450-458, 460-463, 467-469, 471, 472, 474 and 477 (slsa-github-generator v2.1.0, which must be referenced by tag).
- **Evidence:** VERIFIED (NON-MAVEN-FINDINGS V-23).
- **Owner:** owner (to name).
- **Conditions:** Pin to SHA (all except the slsa generator). Raise the Dependabot github-actions limit above 0 (C-29).
- **Review date:** 2027-05-12.
- **Questions:** Licence: not inspected. | Compatibility: n/f. | Security: mutable tags. | Procurement: n/f. | Technical: n/f.

### DR-55 CI tools with weaker but present controls
- **Defined use and scope:** CI.
- **Rows (5):** N rows 482 (kubescape), 483 (gitleaks), 484 (helm), 487 (Temurin JDK on CI) and 488 (Python on CI).
- **Evidence:** VERIFIED.
- **Owner:** owner (to name).
- **Conditions:**
  - Signature verification where the vendor signs releases.
  - Pin the patch level of Temurin and Python on CI.
  - gitleaks gating per C-18.
- **Review date:** 2027-05-12.
- **Questions:** Licence: not inspected. | Compatibility: n/f. | Security: report-only scanners. | Procurement: n/f. | Technical: n/f.

---

## 4. Cross-cutting items that are not tied to one component

These come from W3-X3. They are recorded here because decision 112 asks for every finding to have a status, an owner and a review date. They are not licence decisions. The details are in `REMEDIATION-PLAN.md`.

| Id | Finding | Status | Owner | Review date |
|---|---|---|---|---|
| X-01 | Payment confirmation silently weakened (SW-1, SW-2, SW-3) | VERIFIED (`BillingPaymentPort.java:39-67`, `BillingService.java:116`) | owner (to name) | 2026-11-26 |
| X-02 | Ledger posting with no audit and no GL entry after a retry (SW-6) | VERIFIED code path; the caller's transaction context is UNRESOLVED (X3 U-2) | owner (to name) | 2026-11-26 |
| X-03 | Consent PERMIT_ALL default (SW-4), eSign unverified (SW-5), OIDC iss/aud (SW-12), replay deny-wins (SW-13) | VERIFIED; whether SW-13 is intended is UNRESOLVED (C-09) | W3-D for SW-13 (X3 U-1); otherwise owner (to name) | 2026-11-26 |
| X-04 | No host conformance kit exists (BOUNDARY section 4) | VERIFIED | owner (to name) | 2026-12-12 |

---
Generated by lane W3-X4 on 2026-11-12 (read-only synthesis; no tree edits, no downloads, no builds, no tests).
